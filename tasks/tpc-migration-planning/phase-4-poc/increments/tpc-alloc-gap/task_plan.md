# Task plan — Tier 0: close the +10% async-machinery allocation gap

**Precondition for the loaded-tail ROI (Tier 1).** Routing is at CPU + p50/p90 parity with trunk with the
coordination mechanism firing (memtable contention 72/1k→0, c2c HITM −27%), but allocates +10.1% (43,591 vs
trunk 39,577 asprof samples). That young-gen churn feeds GC, and GC dominates the tail on this 6-core box, so
routing's tail reads equal-or-worse and the contention win can't surface. Goal: routing alloc materially down
(stretch ≤ trunk), no test regressions. Grounding + exact sites in `findings.md`.

## Phases

### Phase 0 — Fable design consult (DONE / in progress)
- [x] Consult Fable on the AsyncPromise inline-complete design for both sites BEFORE cutting code
      (hot path, hard to reverse). Capture verdict + concrete design + sequence in `findings.md`/`progress.md`.

### Phase 1 — Site A: Dispatcher.processRequestAsync inline fast path (LOW risk, mirror of outcome())
- [x] `if (exec.isDone() [&& exec.notifyExecutor()==null])` → compute `toSend` inline (same try/catch as the
      callback body) → `return ImmediateFuture.success(toSend)`; skip `finalized` promise + callback lambda.
- [x] Guard per Fable's ExecutorLocals / notifyExecutor verdict.
- Kills ~344 (promise) + 265 (lambda) on the inline fraction.

### Phase 2 — map-path node fusion (LOW/MED risk, additive)
- [x] Replace `AbstractFuture.map`'s `addListener(() -> {...}, executor)` (lambda + RunnableWithExecutor)
      with a single dedicated `MapListener` `ListenerList` subclass holding (result, mapper, executor),
      mirroring `CallbackBiConsumerListener`. Fast path (`AsyncFuture.map`) unchanged.
- [x] Consider the same fusion for `flatMap`/`andThenAsync` only if cheap and clearly safe.
- Saves ~533 (map lambda) on the async fraction; keeps one fused node.

### Phase 3 — Site B: RetryingMutationDispatch lazy promise (HARD / architectural — gated on Fable verdict)
- [x] Lazy-allocate `result`; capture a fully-inline settle into a field and return `ImmediateFuture`.
- [x] `attempt()`: when `normalFuture.isDone() && notifyExecutor()==null` and no Accord arm, call
      `onNormalComplete(...)` directly (skip addListener node+lambda); else existing slow path.
- [x] Null-safe rewrites of the `result.isDone()` guards (attempt top, armAccordDeadline); `ensureResult()`
      whenever an Accord arm appears or the normal arm is async.
- Kills ~329 (promise) + 193 (GFLL) + 222 (lambda) on the inline fraction — IF Fable rules it safe;
      otherwise defer and ship Phases 1–2, measure, revisit.

### Phase 4 — Build + regression gate
- [x] `ant build`; `AsyncPromiseTest` 9/9 (incl. re-entrant `[0,2,1]`), `ImmediateFutureTest` 3/3.
- [x] `ant jar`; `javap -cp <jar>` verify changed classes are in the jar.

### Phase 5 — Rig measurement (co-located alloc A/B, same-session)
- [x] rsync src → rig, `ant jar` on rig, javap-verify, stage `.jar.<suffix>`.
- [x] Arm trunk vs new build; asprof `-e alloc -o collapsed` ~90k co-located; compare TOTAL + per-type.
- [x] Confirm AsyncPromise + map-node counts down and 0 write errors under load.
- [x] Record result; do NOT judge tail (that's Tier 1).

## Review section
- Elegance check: does each fast path reduce to an existing proven pattern (outcome(), CallbackBiConsumerListener)?
- Staff-engineer bar: RF=3 slow path untouched-in-behavior; re-entrancy safe; no new ingress throw.
- Verify at source, not from memory: rebuild jar + javap before trusting any A/B number.
