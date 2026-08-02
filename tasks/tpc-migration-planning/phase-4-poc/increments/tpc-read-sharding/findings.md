# Findings — Read-path sharding design (Phase 0)

> **OUTCOME (2026-07-16, see `result.md` + `progress.md`):** Variant A was built, proven correct + firing, and
> A/B'd across the full load range. It is a **net regression** on the 6-core shared-L3 rig — p99 1–2 orders of
> magnitude worse, ~57% more CPU, lower sustainable throughput — because 12 single-threaded floating shard
> executors head-of-line-block while the shared `Stage.READ` pool load-balances + runs inline. Locality has no
> payoff on a shared L3. Two open threads: (1) implementation perf bugs inflating the cost (`perf-bug-hunt-plan.md`);
> (2) the mechanism's fair test is the deferred big-box (multi-L3/NUMA). Design rationale below is preserved as-is.

## The two routing points (the design fork the start prompt collapsed into one)

A single-partition read's CPU-heavy work (deserialize / merge / decompress / row iteration) lives in the
**local read** — `command.executeLocally(controller)`, run today by `LocalReadRunnable` submitted to
`Stage.READ` at `AbstractReadExecutor.makeRequests:168`. The *coordinate* (parse, replica plan, dispatch,
await, digest-compare, read-repair) is cheap by comparison. There are therefore two distinct places to route:

### Variant A — route the local read only (swap `Stage.READ` → owning shard executor)
At `makeRequests:168`, when the read is single-partition and its owning shard resolves, submit the
`LocalReadRunnable` to `ShardExecutors.instance().execute(shardId, ...)` instead of `Stage.READ`. Coordinate
and the blocking `handler.await()` stay on the NTR/coordinate thread; only the local read's CPU+disk moves to
the core that owns that partition's shard.

- **Correct at every CL and RF, no gate.** The `makeRequests` loop still `sendReadCommand`s to remote
  replicas; the `ReadCallback` still aggregates, digest-compares, and triggers read-repair. We change only
  *which thread* runs the local portion — not whether replicas are contacted. RF=3 correctness is untouched
  because the coordinator path is untouched. This dissolves the start prompt's headline correctness worry.
- **No ingress-throw exposure.** `makeRequests` runs on the NTR/coordinate thread, downstream of
  `Dispatcher.dispatch` — NOT the netty loop. A throw here is caught by the ordinary request error handler
  (returns an error message), it does not close the connection. The `[[feedback_ingress_throw_kills_connection]]`
  constraint is Variant B's problem, not A's.
- **Shard thread blocks only for bounded work.** The local read is CPU + (on cache-miss) a disk read — no
  remote-replica RTT. The shard thread is never parked awaiting another node.
- **Reuses `LocalReadRunnable` unchanged.** It already builds its own `ReadExecutionController` internally
  (`runMayThrow:3161`) and completes the `ReadCallback`. And it is already thread-agnostic today: under load
  `maybeExecuteImmediately` runs it inline on the coordinate thread or on a read-stage worker interchangeably
  — there is no read-stage thread affinity to break. So probably **no new `ShardReadRunnable` class**.
- **Works for QUERY and EXECUTE** (prepared or not) — it keys off the `SinglePartitionReadCommand`, not the
  wire message, so unlike the write path it is not `ExecuteMessage`-only.

### Variant B — route the whole coordinate at ingress (the write-path analog: Dispatcher + CqlShardRouter)
Route the SELECT the way writes route: `Dispatcher.dispatch` sends the coordinate to the shard executor,
which runs parse→plan→dispatch→**await**. Because the read path is **synchronous** (`StorageProxy.read`
returns a `PartitionIterator`, it does not return a future), the shard thread then **blocks on the entire
coordinated read** — including remote-replica RTT at CL>ONE.

- This is what makes the **CL gate a hard liveness requirement**: blocking a single-writer shard thread on a
  remote read RTT stalls every write to that shard. Only safe when the local read alone satisfies CL — i.e.
  `CL∈{ONE,LOCAL_ONE}` or `RF==1`, self is the owner, no remote await. SERIAL never routes.
- Adds the loop→NTR handoff deletion (the write-path win). For reads that saving is small next to the local
  read cost.
- Carries the ingress-throw hazard (runs on the netty loop) and needs the pk-from-bound-values extraction
  (`SelectStatement.getPartitionKeyBindVariableIndexes:245` — the same machinery the write path uses).

## Decision: Variant A now; Variant B deferred and coupled to an async read path

**Variant B is architecturally premature while the read path is synchronous.** It trades a block on a
fungible NTR thread for a block on the *scarce single-writer shard thread*, and that block spans the remote
RTT — the opposite of what thread-per-core wants. Variant B only becomes correct-and-worthwhile once reads
are async (a `readAsync` analog to `mutateAsync`, so the shard thread fires the local read and returns
without awaiting) — a materially bigger project, out of scope here (pairs naturally with io_uring, the next
increment). Until then the CL gate is real but it is guarding a design we shouldn't build yet.

**Variant A captures the paper's read-tail mechanism directly** — the local read (the CPU-bound half) runs on
the core that owns the shard's data, so that shard's sstable pages / row-cache entries / bloom filters /
partition index stay hot in one core's cache instead of bouncing across read-stage workers. It is a ~10-line
change at one call site plus a shard-resolution helper, correct at all CL/RF, with the coordinator path and
the netty loop both untouched. It is the elegant core of read-sharding; B is an additive layer for later.

This also matches the start prompt's own observation ("the read path is synchronous — no `readAsync` analog")
— that synchronicity is *precisely why* A is right and B is premature, not merely an implementation detail.

## Variant A — concrete shape (gates hardened after Fable review, all verified at source)
At `makeRequests:168`, route the `LocalReadRunnable` to the owning shard executor **only when every gate
below passes**; otherwise `Stage.READ.maybeExecuteImmediately(...)` exactly as today.

1. Routing enabled: `CqlShardRouter.ENABLED` and `ShardExecutors.instance() != null`.
2. **Caller is NOT already a shard thread** — `ShardExecutors.currentShardId() == UNSET`. *Mandatory: this is
   the revert-class fix.* The write increment routes the whole `RequestProcessor` (incl. `checkAccess`) onto
   a shard thread (`Dispatcher.java:154`). An expired auth-cache entry makes `checkAccess` do a *synchronous
   distributed auth read* on that shard thread; if its local portion is submitted back to the *same* shard
   executor while that thread blocks in `awaitResults`, the local read sits behind the blocked task forever →
   the shard (and every write queued to it) freezes until the 5s read timeout, then the read is **dropped with
   no handler callback** (`DroppableRunnable`, `StorageProxy.java:3541+`), recurring on every auth-cache
   expiry. Invisible to the PoC bench and the RF=3 dtest (both use `AllowAllAuthenticator`). Reads issued from
   NTR threads are cycle-free (NTR waits on shard; shard waits only on disk), so this gate loses nothing real.
3. **Not a local-system keyspace** — mirror the write path's `keyspace.isLocalSystemKeyspace()` exclusion
   (`MutationShardRouting.java:101`). Local-system tables use `ShardBoundaries.NONE` → `shardForKey` returns
   **0 for every key**, so without this every driver control-plane read of `system.local`/`system.peers`
   would serialize behind shard-0's user writes.
4. **No secondary-index searcher** — exclude `command.indexQueryPlan() != null` (`ReadCommand.java:341`);
   else arbitrary index-searcher code runs on the shard thread. Mirrors the write path's non-SAI exclusion.
5. Owning shard resolves: `MutationShardRouting.shardForKey(command.metadata(), command.partitionKey())`
   present (empty on a non-sharded memtable → fall back).

Then submit the existing `LocalReadRunnable` via plain `ShardExecutors.execute(shardId, runnable)` — **no new
class**. Locals (trace/ClientWarn) propagate automatically: shard executors are `localAware()`
(`ShardExecutors.java:79`), so the `execute(locals,...)` overload is unnecessary. **Wrap the submit in
try/catch → `Stage.READ` fallback** for `RejectedExecutionException` during the `drainAndAwait` shutdown
window (shard executors terminate while `Stage.READ` stays up). Add a read-routed/fallback counter sibling to
`CqlIngressRouted/Fallbacks`.

## Hazards / assumptions carried into Phase 1
1. **Reads serialize against writes on the shard's single-writer executor.** Intended for the TPC model, fine
   for a read-heavy CPU-bound test; but a cache-miss disk read on the shard thread stalls that shard's writes.
   At **RF=1** a read that expires in a write-backed-up shard queue is dropped with no callback → a guaranteed
   client-side read timeout (not just a delay); at RF>1 speculation masks it (watch `speculativeRetries`).
   This is the io_uring motivation; acceptable for the PoC, must be stated. **Pre-agreed fallback if the A/B
   tail is dominated by read/write mutual stalls:** a *separate* per-core sharded read pool (same hash, not the
   write executors) — isolates the mechanism without read-blocks-write, at the cost of stepping partly back
   from strict one-thread-owns-shard.
2. **No self-deadlock (verified):** the `ReadExecutionController`/OpOrder read group closes (try-with-resources
   `StorageProxy.java:3161-3165`) before `handler.response()`; reads take no memtable write lock; read/write
   OpOrders are separate and group-start is non-blocking. The only nested-read hazard was the auth cycle in
   gate 2 above, which gate 2 removes.
3. **Metrics:** routed local reads leave `ReadStage` tpstats and the `concurrent_reads` permit bound (shard
   queues are unbounded, mixed with writes). PoC-acceptable; state it. `shardTagged` wraps in a plain lambda,
   so the runnable loses `RunnableDebuggableTask` introspection — parity with the write path.
4. **Oversubscription:** 12 shard executors on 6C/12T + read_stage still present. mpstat-prove genuine
   saturation, not thrash ([[feedback_cpu_fence_colocated_loadgen]]).

## Measurement-plan fixes (Phase 3 — from Fable, both validity-critical)
- **Neutralize the dynamic-snitch feedback loop.** `LocalReadRunnable` feeds local-read latency —
  *including shard-queue wait behind writes* — to the snitch (`latencySubscribers.add`,
  `StorageProxy.java:3195`). Under mixed load the snitch de-selects self as the data replica → local reads
  silently stop → **the mechanism under test turns itself off mid-A/B.** Disable the dynamic snitch for the
  A/B, or monitor the local-read fraction and discard runs where it drops.
- **Add a memtable-resident hot-read arm alongside the sstable-miss arm.** On this single-L3 box the strongest
  same-core mechanism is reusing hot *memtable trie* nodes written by the same thread (the proven c2c/HITM
  win). A sstable-only dataset (reads miss the memtable) designs that mechanism OUT — it shares only
  bloom-filter/partition-index/chunk fragments per shard, far exceeding the 256KB L2. Without the
  memtable-resident arm, a null tail result can't distinguish "no win exists" from "the dataset designed the
  win out / the floating-thread scheduler flushed L1/L2." State this confound *before* the run. (Note:
  `ShardExecutors` are plain sequential executors with **no core pinning** — "owning core" is really a
  floating thread; residency is not guaranteed, which further caps the locality win on this box.)
