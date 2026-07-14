# Tier 0 start prompt — close the +10% allocation gap (fresh-context handoff, 2026-07-14)

**Read this, then `roi-path.md` (Tier 0), `findings.md §3.1`, `sidebyside-result.md`, and the top HANDOFF of
`progress.md`. Then design (Fable) before you cut code.**

## Why this is Tier 0 (the precondition, not a nice-to-have)
The current routing build (`routing-alloc`, md5 81e13444 = two async fast paths landed) is at CPU + p50/p90
**parity** with trunk and already fires the coordination mechanism (memtable contention 72/1k → 0, c2c HITM
−27%). Its ONE residual cost is **+10.1% allocation vs trunk** (asprof `-e alloc` samples: routing 43,591 vs
trunk 39,577). That extra young-gen churn feeds GC, and **GC dominates the tail on this box**, so routing's
tail reads equal-or-worse and the contention win can't surface. **Every downstream ROI tier (loaded tail,
scaling) is masked until routing allocates ≤ trunk.** Closing it is the unlock. Success metric: routing
TOTAL_alloc ≤ trunk (or as close as is provably safe), no test regressions.

## Exactly what's left to kill (measured residual, routing-added types)
From the side-by-side alloc profile (`results_sxs/alloc_routing-alloc.collapsed`), the routing-added types are:
- `AsyncPromise` **~673** — the per-handler promise objects. **The hard, architectural one.** Concrete sites:
  - `Dispatcher.java:592` `AsyncPromise<Message.Response> finalized = new AsyncPromise<>();` (per CQL request)
  - `StorageProxy.java:1510` `private final AsyncPromise<Void> result = new AsyncPromise<>();` (per write)
- `ListenerList$RunnableWithExecutor` **~540** (`ListenerList.java:385`) — a listener node the `map` path still
  allocates on its slow/executor branch. The `map` fast path (AsyncFuture.java:205) already skips it when the
  future is already-done AND `executor==null`; the residual is the paths that miss that guard.
- `ListenerList$GenericFutureListenerList` ~193 + `ListenerList$$Lambda` ~166 — smaller listener nodes/lambdas.

Already dead (don't rechase): `CallbackBiConsumerListener` 654 → **0** (the `addCallback(BiConsumer)` fast path).

## The design insight (why Fable, and what to point it at)
Seastar allocates **~zero** on a local write: `future`/`promise` are inline value objects, the result stored in
the future's own frame; only a genuine suspension (commitlog fsync) heap-allocates one continuation (findings
§3.1). Our `AsyncPromise` is **always heap-allocated**, even when the operation completes **inline** — which is
exactly the RF=1 local-write case (`writeResult.isDone()` at attach time, ~4–5× per write). The architectural
question for Fable: **can we avoid allocating the `AsyncPromise` on the inline-complete path** — reuse it,
store the value inline, or return an already-completed `ImmediateFuture` before the promise is ever built
(the way `AbstractWriteResponseHandler.outcome()` already does for its terminal path)? This is a hot-path,
hard-to-reverse concurrency-primitive design → **the right place to spend Fable** (memory
[[feedback_fable_expensive_reserve_high_leverage]]): give it the two AsyncPromise sites + the Seastar
inline-value reference + the re-entrant-listener safety constraint, and have it design the fix (or rule it
architectural) BEFORE implementation. The `RunnableWithExecutor` map-path node is the easier, lower-risk win —
extend the existing map fast-path guard to cover more call shapes.

## Hard constraints (violating any of these has already cost a revert)
- **Netty-aligned medium only** — in-package `AsyncPromise`/`AsyncFuture` (Promise extends io.netty Promise).
  No RxJava / Reactive Streams / JDK CompletableFuture ([[feedback_async_medium_netty_futures]]).
- **Re-entrant listener safety** — any inline fast path MUST reduce to `notify()`'s `null→NOTIFYING` claim +
  `ListenerList.drainAfterInline` drain. A listener may add a listener while firing; firing inline without the
  claim double-fires re-entrant adds (this killed the reverted Tier-2). Test: `AsyncPromiseTest` order `[0,2,1]`.
- **Ingress path must not throw** — work pulled onto the netty inbound loop (Dispatcher) must use
  null-returning lookups + try/catch→fallback; a throw closes the connection + leaks capacity
  ([[feedback_ingress_throw_kills_connection]]).
- **Parity is RF=1-conditional** — the inline fast path fires only when `writeResult.isDone()`; under RF=3 the
  coordinator awaits remote acks → slow path. Keep the RF=3 path correct; don't optimize it away.

## Measurement (co-located only — the loadgen box is DELETED)
`tpc-scale-lg` (62.238.35.142) was deleted after Tier 1's off-box work; **alloc/op is co-location-invariant**,
so Tier 0 needs no off-box box. Use the co-located alloc A/B:
- Script: adapt `/root/alloc_ab.sh` (currently arms routing-newfixes vs routing-alloc) to arms **trunk vs your
  new build**, or reuse `sidebyside_colocated.sh`'s alloc leg. asprof 4.4 `-e alloc -o collapsed`, ~90k
  co-located, same-session (cross-run alloc drifts → compare arms in ONE run).
- Target: your build's TOTAL_alloc ≤ trunk's 39,577, with `AsyncPromise` + `RunnableWithExecutor` counts down.
- Regression gate: `AsyncPromiseTest` 9/9 (incl. re-entrant `[0,2,1]`), `ImmediateFutureTest` 3/3, `ant build`.
  Do NOT trust the tail/GC payoff on this box — that's validated in Tier 1 (a separate, later step).

## Rig / build state (as of 2026-07-14 eve)
- Rig `157.180.98.112` (E-2276G, 6C/12T, single L3/NUMA), live conf `/data/tpc-poc/conf` (TrieMemtable), data
  `/data/tpc-poc/data`, JMX local 7199. Currently live on **routing-alloc** (81e13444).
- Staged jars in `…/cassandra-tpc-i1/build/`: `.jar.trunk` (745ce392, TPC-free), `.jar.routing-alloc`
  (81e13444 = the current baseline you build ON). Scripts: `swap.sh` `prep_flip.sh` `ipo.sh` `capstone.sh`
  `alloc_ab.sh` `sidebyside_colocated.sh` `MemtableContention.java`.
- Build: edit in the main mac tree → `rsync -az --delete src/ mac→rig` → `ant jar` (NOT `ant build`) →
  `javap -cp <jar> <FQCN>` to verify the class is in the jar → `swap.sh <suffix>` → `prep_flip.sh`. `ant jar`
  overwrites the live classpath jar, so never measure the live node without restarting.
- Code committed on `tpc-migration` (NOT pushed): the two fast paths + this session's docs (HEAD 6a0126f6fa).

## Source files
`AsyncFuture.java` (map fast path :205, addCallback fast path, appendListener :122), `AsyncPromise.java` (the
allocation being chased), `ListenerList.java` (:385 RunnableWithExecutor, :133 drainAfterInline),
`AbstractFuture.java` (map/addCallback/flatMap slow paths), `Dispatcher.java:592`, `StorageProxy.java:1510`,
`AbstractWriteResponseHandler.java` (the outcome() terminal fast path = the pattern to mirror).

## Suggested folder
Spin `tasks/tpc-migration-planning/phase-4-poc/increments/tpc-alloc-gap/{task_plan,findings,progress}.md`
inheriting `tpc-cpu-parity/findings.md §3.1`, or continue in `tpc-cpu-parity/`. Keep progress.md from growing
unbounded → a new folder is cleaner.

---

### Paste-able start prompt
> Start TPC Tier 0 — close routing's +10% allocation gap (the precondition that unblocks the loaded-tail ROI).
> Read `tasks/tpc-migration-planning/phase-4-poc/increments/tpc-cpu-parity/tier0-alloc-gap-start.md` in full,
> then `roi-path.md` (Tier 0), `findings.md §3.1`, `sidebyside-result.md`. The current build (routing-alloc,
> 81e13444) is at CPU/p50-p90 parity with trunk with the mechanism firing (memtable contention 72/1k→0, c2c
> −27%) but allocates +10.1% (43,591 vs trunk 39,577), and GC masks the tail until that closes. Targets:
> `AsyncPromise` ~673 (Dispatcher.java:592 + StorageProxy.java:1510 — the architectural one) and map-path
> `RunnableWithExecutor` ~540 (ListenerList.java:385). FIRST consult Fable on the AsyncPromise inline-complete
> design (avoid allocating the promise when writeResult.isDone(), Seastar inline-value idiom) BEFORE cutting
> code — hot path, hard to reverse. Constraints: Netty-aligned futures only; any inline fast path must reduce
> to notify()'s null→NOTIFYING claim + drainAfterInline (re-entrant `[0,2,1]` test); ingress must not throw;
> keep the RF=3 slow path correct. Measure co-located alloc A/B (trunk vs new build, asprof -e alloc,
> same-session) — the loadgen box is DELETED and alloc/op is co-location-invariant. Gate: AsyncPromiseTest 9/9,
> ImmediateFutureTest 3/3, ant build. Rig `157.180.98.112` live on routing-alloc; build via rsync→ant jar→
> javap-verify→swap→prep_flip. Don't judge the tail payoff here — that's Tier 1.
