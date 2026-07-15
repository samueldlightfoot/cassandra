# Progress — Tier 0 allocation gap

## Session 2026-07-14 (eve) — grounding + Fable design consult launched

**State inherited:** routing-alloc (81e13444) live on rig 157.180.98.112, at CPU/p50-p90 parity with trunk,
mechanism firing, residual +10.1% alloc. Loadgen box DELETED (alloc/op is co-location-invariant). Baseline
side-by-side in `../tpc-cpu-parity/sidebyside-result.md`.

**Done this session:**
- Pulled the authoritative alloc decomposition from `rig:/root/results_sxs/alloc_*.collapsed`. Split the 673
  AsyncPromise samples: ~344 Dispatcher `finalized` (:592) + ~329 `RetryingMutationDispatch.result` (:1510).
  Map slow path = 540 RunnableWithExecutor + 533 `lambda$map$0` (fusable). Full table + traced hot path in
  `findings.md`. Trunk allocates ZERO on these paths (async machinery is fork-added) → direct gap closure.
- Confirmed at source: `mutateWithTriggersAsync` returns dispatch.result unwrapped (:1387); `notifyExecutor()`
  null by default → `addListener` on a done ImmediateFuture fires inline on the calling thread; single-arg
  `map` has executor==null.
- Set up this task folder (task_plan/findings/progress). Design north star = `AbstractWriteResponseHandler.outcome()`
  (already-merged inline `isDone()` → `ImmediateFuture`, no promise/timer/listener).
- **Launched Fable (background) on the AsyncPromise inline-complete design** for both sites: verdict +
  concrete design + correctness (re-entrancy, notifyExecutor threading, ExecutorLocals, RF=3) + implementation
  sequence. Awaiting return before cutting code (per directive: Fable first on the hot, hard-to-reverse path).

**Fable verdict (returned, all claims source-verified):**
- **Site A (Dispatcher):** SHIP + add `exec.notifyExecutor() == null` to the guard → makes the inline path a
  strict behavioral subset. Read-only pattern (like outcome()): no NOTIFYING claim (we never fire exec's
  listeners). Off the netty loop; no new throw; a throw now propagates to processRequest's catch (better than
  today's swallow-and-hang). RF=3: exec not done → guard fails → byte-identical slow path.
- **Map fix (mine):** fuse lambda+node into one `MapListener`, BUT preserve BOTH `result.tryFailure(t)` AND the
  re-throw (Site A depends on exec ALWAYS completing; dropping either hangs the request + leaks
  inFlightAsyncRequests). MapListener.result must stay a plain AsyncFuture (notifyExecutor null) so Site A's
  guard keeps matching. Add async-path map test: order [0,2,1] + mapper-throws (result failed AND handler ran).
- **Site B (StorageProxy):** SHIP with 3 tightenings: (i) `ensureResult()` UNCONDITIONAL when `accordResult != null`
  (not gated on `!accordDeadlineArmed`); (ii) `ensureResult()` BEFORE the addListener fallback unconditionally;
  (iii) pass the promise into `armAccordDeadline(result)` as a param. Load-bearing invariant: `result` is
  non-null before ANY async registration → off-thread code only READS result, never creates it; `settledInline`
  touched only on the dispatch thread. Direct-call onNormalComplete is identical (we register nothing on
  normalFuture → no list to protect). Do it LAST so a regression is attributable.

**Sequence (Fable, risk-adjusted): Site A → Map fix → Site B.** Implementing now.

## Session 2026-07-15 — all 3 fixes implemented, built, gate-green; measurement in progress

**Implemented (committed to working tree, jar md5 316dfd70 local / 5348018527 rig):**
- Site A: Dispatcher.processRequestAsync inline fast path (`exec.isDone() && exec.notifyExecutor()==null` →
  finalize inline → ImmediateFuture). Site B: RetryingMutationDispatch lazy `result` + `settledInline` +
  `ensureResult()`/`isTerminal()` + direct-call onNormalComplete + armAccordDeadline(param). Map fix:
  ListenerList.MapListener fused node replacing AbstractFuture.map's lambda+RunnableWithExecutor.
- Gate GREEN: `ant build` + `ant jar`; AsyncPromiseTest **11/11** (added map async-path success + mapper-throws);
  ImmediateFutureTest 3/3. javap-verified all classes in the rig jar.

**A/B #1 (trunk vs alloc-gap, same session) — SETUP FLAW for isolation.** trunk TOTAL 39,429, alloc-gap 43,568
(+10.5%), throughput MATCHED (both ~5.43M ops at the I/O knee, identical req/s ladder), 0 write errors. But
trunk has ZERO async machinery, so this TOTAL conflates my 3 changes with the ENTIRE fork async stack — it
can't isolate my delta. It only confirms the absolute gap is still ~+10% (expected — my changes touch only
part of it).

**What the per-type breakdown shows (grep -F, reliable; lambda exact-names are NOT comparable across JVM runs):**
- AsyncPromise 673 → **331** — the INLINE fraction's promises are gone (Dispatcher fully, StorageProxy inline);
  331 residual = the genuinely-async fraction (normalFuture/exec NOT done at attach → promise still needed).
- ImmediateFuture 158 → **487** (+329) — the inline paths now return ImmediateFuture. **KEY: AsyncPromise→
  ImmediateFuture is a WASH on object count** (both ~24B). The promise swap alone saves ~nothing.
- RunnableWithExecutor 540 → **0**, GenericFutureListenerList 193 → **0**, MapListener 0 → **0** — the map
  slow path is no longer hit (sources now done inline → map FAST path) and the direct-call skips the GFLL.
  These node kills + the removed lambdas (Dispatcher callback, onNormalComplete, map) are the REAL reduction.
- Future family (AsyncPromise+ImmediateFuture+AsyncFuture+nodes): routing 2044 → alloc-gap 1301 = **−743**,
  BUT cross-session (routing was prior session) → needs same-session confirmation.

**A/B #2 (routing-alloc vs alloc-gap, SAME session) — DONE, decisive.** Full table + accounting in `result.md`.
- **TOTAL_alloc: routing-alloc 43,543 → alloc-gap 42,983 = −560 (−1.3%).** Throughput matched to 0.005%
  (5.4256M writes both), 0 write errors both arms. Gap vs trunk (~39,450): **+10.4% → +9.0%** (~14% of gap closed).
- Mechanism: map node RunnableWithExecutor 486→0 + map lambda 482→0 (Site B's ImmediateFuture makes the
  downstream map hit the FAST path) + GFLL 161→0 + onNormalComplete lambda 248→0 + Dispatcher callback lambda;
  offset by ImmediateFuture +349 (AsyncPromise→ImmediateFuture is a WASH). AsyncPromise 635→337 (inline
  fraction gone; 337 residual = genuinely-async fraction).

**KEY FINDING / reframe:** the Tier-0 premise is only ~⅓ true. Future family = ~38% of the +gap; ~62% is
non-future routing allocation (ReplicaPlans/ConsensusMigration/ShardExecutors lambdas + write-path data
structures) these async fixes cannot touch. A −1.3% alloc cut will NOT materially move GC / unmask the tail.
So the "alloc gap masks the tail" lever is smaller + more diffuse than assumed.

**Co-located p99 hint (UNCLEAN, do not claim):** alloc-gap ran ~0.7–2 ms p99 vs trunk ~30–140 ms at matched
throughput during asprof — 50–100×, far bigger than 1.3% alloc → suggests the async fast paths cut
completing-thread work/listener hops. But co-located tail is contaminated + off-box loadgen deleted → this is
Tier 1's measurement, not a claim here.

**DECISION POINT (user's call):** the 3 fixes are correct/safe/gate-green/−1.3% and worth keeping, but do NOT
meet Tier 0's goal. Options: (a) commit + RE-SCOPE Tier 0 (remaining gap = routing lambdas, a different/larger
effort), or (b) commit and go straight to Tier 1 loaded/CPU-bound/IRQ tail test with these in (the p99 hint
says measure the tail directly, not chase the last alloc %). NOT yet committed (working tree, jar 316dfd70).
Rig left live on alloc-gap (5348018527).
