# Findings — Tier 0 allocation gap (async-machinery churn), authoritative decomposition

Inherits `../tpc-cpu-parity/findings.md §3.1` (Seastar inline-value idiom) and `sidebyside-result.md`
(+10.1% alloc: routing 43,591 vs trunk 39,577 asprof `-e alloc` samples). This file pins the EXACT
routing-added allocation sites from the side-by-side collapsed profile
(`rig:/root/results_sxs/alloc_{routing-alloc,trunk}.collapsed`).

## The gap in one number
routing 43,591 − trunk 39,577 = **+4,014 samples (+10.1%)**. Trunk allocates ZERO on the async-dispatch
paths below — the whole `executeAsync`/`mutateAsync`/`processRequestAsync` future machinery is fork-added
(trunk uses the synchronous, thread-blocking write path). So every future/lambda/node we remove is pure
gap closure, not a wash against a trunk equivalent.

## Authoritative routing-added allocation leaves (asprof `-e alloc` leaf `_[i]` samples)
| leaf type | samples | site | fix |
|---|---|---|---|
| `AsyncPromise` (Dispatcher `finalized`) | ~344 | `Dispatcher.java:592` processRequestAsync | inline: `if exec.isDone()` → compute toSend → `ImmediateFuture` |
| `Dispatcher$$Lambda` (addCallback body) | 265 | `Dispatcher.java:593` | killed by same fix (inline body, no lambda) |
| `AsyncPromise` (`RetryingMutationDispatch.result`) | ~329 | `StorageProxy.java:1510` | architectural: lazy-allocate, capture inline settle |
| `GenericFutureListenerList` | 193 | `StorageProxy.java:1569` `normalFuture.addListener` | killed if inline path calls onNormalComplete directly |
| `RetryingMutationDispatch$$Lambda` (onNormalComplete) | 222 | `StorageProxy.java:1569` | killed by same |
| `RunnableWithExecutor` (map slow path node) | 540 | `AbstractFuture.java:433` via `map` | fuse lambda+node into one `MapListener` |
| `AbstractFuture$$Lambda` (`lambda$map$0`) | 533 | `AbstractFuture.java:342` | fused away (save ~533, keep one node) |

- AsyncPromise total 673 splits ~344 (Dispatcher `finalized`) / ~329 (`RetryingMutationDispatch.result`),
  measured by grepping the two leaf stacks (`processRequestAsync`-only vs also-`RetryingMutationDispatch`).
- **Already dead** (prior session, don't rechase): `CallbackBiConsumerListener` 654 → 0 (addCallback fast
  path); the map fast path already skips node alloc when `isDone() && executor==null && notifyExecutor()==null`.

## Where the map-path residual actually comes from (verified in the collapsed stacks)
The `map` slow path fires only on the ASYNC fraction — when the source future is NOT done at map-attach
(local apply still in flight on the shard thread) — from two chained maps per write:
`ModificationStatement.executeWithoutConditionAsync` (`.map` at :746) and `QueryProcessor.processStatementAsync`.
When the source IS done, `AsyncFuture.map`'s fast path already fires (no node). So the 540 RunnableWithExecutor
+ 533 lambda are the async-branch cost. It is NOT reducible to zero (a not-yet-done future MUST heap a
continuation), but the TWO allocations (lambda + node) can be FUSED into ONE dedicated node — the same shape
as `CallbackBiConsumerListener`. Net save ~533 (the lambda); keep ~540 (one fused node).

## Hot write path (RF=1 single-partition, the inline case) — allocation trace
1. `ModificationStatement.executeAsync` → `executeWithoutConditionAsync`
   → `StorageProxy.mutateWithTriggersAsync(...).map(ignored -> metrics)`  ← map return AsyncFuture (unavoidable)
2. `mutateWithTriggersAsync` → (non-atomic, non-ANY) → `dispatchMutationsWithRetryOnDifferentSystemAsync`
   → **`new RetryingMutationDispatch` allocates `result` AsyncPromise (:1510)** ← Site B target
3. `attempt()` → `mutateAsync(...)` → `responseHandlers[0].outcome()`; when local apply done inline,
   returns `ImmediateFuture` (outcome()'s fast path — already merged, no promise). Then
   **`normalFuture.addListener(f -> onNormalComplete)` allocates GenericFutureListenerList + lambda (:1569)**
   ← also Site B (killable on the inline branch)
4. onNormalComplete → finish(null) → `result.trySuccess(null)`. result done before dispatch returns.
5. `processRequestAsync`: **`new AsyncPromise finalized` (:592)** + `exec.addCallback(lambda)` (:593)
   ← Site A target. exec is done inline → callback fires inline → finalized.trySuccess(toSend).

## Facts confirmed at source (relied on by the design)
- `mutateWithTriggersAsync` returns `dispatchMutationsWithRetryOnDifferentSystemAsync(...)` UNWRAPPED (:1387);
  the map at ModificationStatement:746 attaches directly to `dispatch.result`.
- `AbstractFuture.notifyExecutor()` returns null by default (:140); `ImmediateFuture extends AsyncFuture`
  → notifyExecutor()==null → `addListener` on a done ImmediateFuture fires the listener INLINE on the
  calling thread. So replacing `addListener` with a direct call is threading-identical when
  `isDone() && notifyExecutor()==null`.
- `Future.map(mapper)` → `map(mapper, null)` (Future.java:164), so single-arg map has executor==null.

## Design constraints (violating any has already cost a revert)
- Netty-aligned futures only ([[feedback_async_medium_netty_futures]]).
- Any inline fast path must reduce to `notify()`'s `null→NOTIFYING` claim + `ListenerList.drainAfterInline`
  re-drain (the reverted naive Tier-2 double-fired re-entrant listeners; test `AsyncPromiseTest [0,2,1]`).
  NB: Site A and Site B act as LISTENERS on FOREIGN futures (exec / normalFuture), not managers of their own
  listener list, so there is no NOTIFYING claim to make there — the claim protocol only applies to fast paths
  living INSIDE AsyncFuture (addCallback/map). The map-fusion node change is inside the listener machinery.
- Ingress (netty inbound loop) must not throw ([[feedback_ingress_throw_kills_connection]]); processRequestAsync
  runs off the event loop, and the inline path adds no new throw beyond the existing try/catch + a non-throwing
  ImmediateFuture return.
- RF=3 keeps the async slow path: under RF=3 the coordinator awaits remote acks, the write does NOT complete
  inline, so the promise/listener slow path stays as-is. Don't optimize it away.

## Measurement plan
Co-located alloc A/B, asprof 4.4 `-e alloc -o collapsed`, ~90k co-located, SAME session (cross-run alloc
drifts). Adapt `rig:/root/alloc_ab.sh` to arm **trunk vs new build** (or reuse sidebyside alloc leg). Target:
new-build TOTAL_alloc materially below routing-alloc's 43,591, with AsyncPromise + map-node counts down;
stretch = ≤ trunk 39,577. Regression gate: `AsyncPromiseTest` 9/9 (incl. `[0,2,1]`), `ImmediateFutureTest`
3/3, `ant build`. Do NOT judge tail/GC payoff here — that is Tier 1.
