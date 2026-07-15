# Result — Tier 0 alloc-gap fixes: correct, safe, −1.3% total alloc (gap narrowed, not closed)

Three Fable-validated async fast paths landed (Site A Dispatcher inline, map-path MapListener fusion, Site B
RetryingMutationDispatch lazy promise). Gate GREEN. Measured same-session, matched-throughput, 0 write errors.

## The decisive number — same-session isolation (routing-alloc 81e13444 vs alloc-gap 5348018527)
Both arms one session (`/root/alloc_ab_iso.sh` → `results_allociso/`), co-located @90k, asprof `-e alloc -o
collapsed -d 40`, throughput matched to 0.005% (5,425,582 vs 5,425,836 writes), 0 write errors both arms.

| type (leaf `_[i]` samples) | routing-alloc | alloc-gap | Δ |
|---|---|---|---|
| **TOTAL_alloc** | 43,543 | **42,983** | **−560 (−1.3%)** |
| AsyncPromise | 635 | 337 | −298 |
| ImmediateFuture | 150 | 499 | **+349** |
| AsyncFuture | 437 | 522 | +85 |
| RunnableWithExecutor (map node) | 486 | 0 | −486 |
| GenericFutureListenerList | 161 | 0 | −161 |
| AbstractFuture$$Lambda (map lambda) | 482 | 0 | −482 |
| Dispatcher$$Lambda (callback) | ~486→ | 213 | ~−273 |
| RetryingMutationDispatch$$Lambda | 248 | 0 | −248 |
| MapListener (new fused node) | 0 | 0 | 0 |

Trunk baseline (stable across sessions: 39,577 / 39,429): the gap vs trunk went **+10.4% → +9.0%**, i.e. the
three fixes closed **~14% of the +10% gap** (−560 of ~4,070).

## Why only −1.3% — the AsyncPromise fix is a WASH; the win is what it ENABLES
- **AsyncPromise → ImmediateFuture is net-neutral on object count.** Both are ~24 B young-gen objects.
  Returning `ImmediateFuture.success(...)` on the inline path replaces the promise 1:1 (AsyncPromise −298,
  ImmediateFuture +349). The promise sites themselves save ≈ nothing.
- **The real reduction is the killed nodes + lambdas the inline paths ENABLE:** because Site B now returns an
  already-done `ImmediateFuture`, the downstream `ModificationStatement.map` source is done → it takes the map
  FAST path, so the map node (RunnableWithExecutor 486→0) and its lambda (482→0) vanish entirely — MapListener
  is never even allocated. Site B's direct-call also removes the retry-dispatch `addListener` node
  (GenericFutureListenerList 161→0) + its lambda (248→0). Site A removes the Dispatcher callback lambda.
- These true savings (~1,650) are partially offset by the +349/+85 future-object churn and run-to-run jitter
  in the other ~40k of allocation, netting −560.

## The reframe this forces on Tier 0
The Tier 0 premise — "+10% alloc gap is async-future machinery, close it by fixing the AsyncPromise sites" —
is **only ~⅓ true**. In alloc-gap, the entire future family (AsyncPromise 337 + ImmediateFuture 499 +
AsyncFuture 522 = 1,358) is **~38% of the +3,533 gap vs trunk**; the other **~62% (~2,175) is non-future
fork-added allocation** — routing/coordination lambdas (ReplicaPlans, ConsensusMigrationMutationHelper,
ShardExecutors) and write-path data structures — that these async fixes structurally cannot touch. Even
driving the future family to its floor (one future per async op is unavoidable) closes at most ~38% of the gap.

**So a −1.3% total-alloc reduction will NOT materially change GC pressure, and will NOT by itself unmask the
tail.** The "alloc gap masks the tail" lever is smaller and more diffuse than Tier 0 assumed.

## Intriguing (but UNCLEAN) co-located p99 hint — do not over-read
In the co-located loadgen ramp, alloc-gap p99 ran ~0.7–2 ms where trunk ran ~30–140 ms at matched throughput
(both during asprof). This is a 50–100× gap far too large to be the 1.3% alloc delta — likely the async fast
paths shaving completing-thread work + listener hops. BUT co-located tail is contaminated (methodology), the
off-box loadgen is deleted, and tail is explicitly Tier 1's call. Flag it; do not claim it.

## Verdict
The three changes are **correct, safe (Fable-validated, re-entrancy/threading/RF-3 argued closed), gate-green
(AsyncPromiseTest 11/11, ImmediateFutureTest 3/3), and a real −1.3% alloc + hair of CPU** with 0 write errors.
They are worth keeping. But they do **not** achieve Tier 0's goal of closing the gap enough to unmask the tail
— because the gap is mostly not the promise machinery. Decision needed (see progress.md): commit + re-scope
(the remaining gap is routing lambdas, a different/larger effort), or take these fixes into a Tier-1 loaded
tail test directly (the co-located p99 hint says measure the tail, not chase the last alloc %).
