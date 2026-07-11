# I0 + I1 — shard-routed mutation apply — implementation plan

**Goal:** the smallest real TPC increment — route the local mutation *apply* to a per-shard
single-writer executor so the per-shard memtable lock becomes redundant, flag-gated, with a
byte-identical flag-off path. Foundation (I0) + routing (I1) ship in one patch (increments.md §3).

**Gate (increments.md §0/I1):** flag-on write-heavy p99 ≤ flag-off at the loaded-tail + mid-load
points (gate-reconciliation.md), measured both server-side and client-CO (AND-gate). Micro claim:
`contendedPuts ≈ 0`, `contentionTime ≈ 0` flag-on; `misroutedPuts ≈ 0` on healthy boundaries.
**Honest cost:** trunk can apply INLINE on the NTR thread (zero hops) when a MUTATION permit is
free; routing always costs one hop → low-load may regress (non-gating crossover cell).

**Inventory (authoritative, verified on-branch 2026-07-10):** `../../findings-i1-mutation-apply.md`. All seams confirmed present at the cited lines.

---

## Pinned decisions (from design docs / open-question resolutions)
- **Oversubscription accepted** (poc-criteria §5): N = `availableProcessors` shard threads ADDED
  atop the existing 32-permit MUTATION SEP. Not a program verdict — a config consequence.
- **Plain executors, no CPU pinning** for I1 (findings Q2; pinning is I3/CEP scope).
- **Executor = `executorFactory().localAware().withJmx("request").sequential(name)`** per shard
  (findings §3): unbounded blocking queue, ExecutorLocals propagation, free ThreadPoolMetrics JMX.
  Hand-rolled MPSC is a later optimization, NOT this patch.
- **Node-global N executors; per-(table,key) shard id** = `memtable.boundaries.getShardForKey(key)`
  mapped to `shardId % N` (findings §2 consequence).
- **Step-2 = owner-check-with-lock-fallback** (findings §5), NOT unconditional skip. Always correct;
  unrouted/racing writers simply take the lock (raising `misroutedPuts`/`contendedPuts`).
- **Routing predicate excludes** (design-target §5/§9 normative list — the predicate's source of truth):
  counters, views (`updatesAffectView`), **CDC tables (`cdc=true`)**, **legacy-2i-bearing tables**,
  local-system keyspaces, non-`AbstractShardedMemtable` tables, multi-table boundary disagreement.
  CDC excluded: `CommitLogSegmentManagerCDC.throwIfForbidden` can block/throw on space exhaustion.
  Legacy-2i excluded: index puts are keyed by the indexed VALUE (≠ base token) → systematically
  off-owner + full write-path blocking budget. SAI stays routable (in-memory, no memtable write).
  `commitlog_sync: batch|group` enforced as a **startup guard** (refuse-to-route — immutable at
  runtime, so fail-fast, NOT per-op; increments.md §2 "periodic startup check"). Blocking
  `Keyspace.apply` callers (paxos/counters) stay on their threads via arrival path (finding §7.5,
  CASSANDRA-12689 class).
- **Flags** (findings §4): `MUTATION_SHARD_ROUTING("cassandra.mutation.shard_routing","false")` +
  `MUTATION_SHARD_SKIP_LOCK("cassandra.mutation.shard_routing.skip_lock","false")`, read-once into
  `static final boolean` (no hot-swap → avoids mixed-writer windows).
- **New counter:** `misroutedPuts` (owner-check failed → lock fallback) — the boundary-health metric.

## Open items to confirm before/while coding (minor, non-blocking)
1. **Expiry re-check on shard dequeue** (finding §7.3): the extra hop adds a second queue; replica
   expiry is checked once at stage-dequeue. **Recommend:** add a cheap dequeue-time re-check in the
   shard runnable (mirrors `MutationVerbHandler:54`) — cleaner than accepting later drops. Confirm.
2. **RESOLVED → pinned:** legacy-2i is now a pinned predicate exclusion (above). Residual (finding
   §7.8): custom (non-SAI, non-legacy) `Index` impls run arbitrary user code inline on the shard
   thread — for the PoC conservatively skip routing for ANY table bearing a custom index (moot for
   KeyValue: no indexes). SAI-only tables stay routable per design-target §5 but are outside the gate.
3. Workload pinned to **non-Accord, trie-memtable, periodic-commitlog, RF=1** tables (findings §7.1/§7.10)
   — already the rig config. No code, just the A/B cell definition.

---

## Phase 0 — I0 foundation (inert; no flag; gate = tests green)
- [ ] `concurrent/ShardExecutors.java`: N `sequential()` LocalAware executors (N=availableProcessors);
      drain BEFORE commitlog stops by hooking the **EXISTING** ordering site (`Stage.java:163-184`:
      `shutdownBeforeCommitlog` flag → `mutatingExecutors()` → `shutdownAndAwaitMutatingExecutors()`) —
      ShardExecutors isn't a `Stage`, so drain it at that same caller (or extend the filter), NOT via
      new ordering code; `currentShardId()` via a thread-local set on each shard thread;
      `currentThreadIsOwnerOf(int shardIndex)`; `executorFor(int shardId)`.
- [ ] `db/MutationShardRouting.java`: `route(Mutation)` → `OptionalInt shardId` implementing the
      design-target §5/§9 predicate (all exclusions incl. CDC + legacy-2i); pure function, no side
      effects; per-table boundary agreement check.
- [ ] `AbstractShardedMemtable`: add public `ShardBoundaries getShardBoundaries()` (field is
      `protected`, no getter today — finding §2).
- [ ] Micro: export enqueue→dequeue hand-off latency + unparks/sec from day one (paper's steering cost).
- [ ] Unit tests: routing predicate truth table (each exclusion), `currentShardId` correctness,
      shutdown-ordering test. **Nothing routes here yet → dead code, zero behavior change.**

## Phase 1 — I1 routing (flag `shard_routing`; flag-off byte-identical)
- [ ] `StorageProxy.java:1918` (coordinator self-write, `sendToHintedReplicas` local branch): if flag
      on AND `MutationShardRouting.route(mutation)` present → submit the existing `LocalMutationRunnable`
      to `ShardExecutors.executorFor(shardId)` instead of `performLocally(stage,…)`. Else unchanged
      (`performLocally` → `maybeExecuteImmediately` at `:2025`). Reuse the SAME runnable (deadline→hint
      logic rides free, finding §1a.7).
- [ ] `MutationVerbHandler.applyMutation:83` (replica write): if flag on AND routable → run
      `applyFuture()` on the shard executor; ack callback stays thread-safe (finding §7.2). Optional
      dequeue expiry re-check (open item 1).
- [ ] Verify flag-off diff is **byte-identical path** (rollback = `maybeExecuteImmediately`); add a
      test asserting the unrouted path is taken when flag off.
- [ ] In-JVM dtest: RF=1 trie-memtable table, flag-on writes land + read back; `misroutedPuts==0` on
      steady boundaries; a forced epoch/boundary race raises `misroutedPuts` then heals.

## Phase 2 — step-2 owner-check lock skip (flag `skip_lock`) + counter
> **SUPERSEDED 2026-07-11 (see `progress.md` PIVOT + `../i5-inbound-shard-dispatch/`).** skip_lock
> is RF=1-only (a skipping owner races a lock-taking unrouted writer at RF≥3). Dropped. The lock
> stays; `misroutedPuts` is kept but decoupled from the skip. Below retained for provenance only.
- [ ] `TrieMemtable.MemtableShard`: add shard-index arg — ctor def at `TrieMemtable.java:541`
      (`(TableMetadataRef, MemtableAllocator, TrieMemtableMetricsView)` today) AND the instantiation
      call at `:140`; store as a `final int shardIndex` field for the owner assert.
- [ ] `MemtableShard.put` (`:550`): `if (skipLock && ShardExecutors.currentThreadIsOwnerOf(thisIndex))
      { apply WITHOUT writeLock; assert owner-thread field } else { existing tryLock path }`.
- [ ] Add `misroutedPuts` counter to `TrieMemtableMetricsView` (finding §6) — incremented when
      `skipLock` on but owner-check fails (fell back to lock).
- [ ] Test: single-writer-per-shard holds under routed load (`contendedPuts→0`); unrouted writer
      (simulated hint/read-repair) correctly takes the lock and bumps `misroutedPuts`.

## Phase 3 — build + verify (macOS local, then rig)
- [ ] `ant jar` (NOT just `build`) — verify JAR timestamp + classes present (JAR-rebuild gotcha).
- [ ] Run I0/I1 unit + in-JVM dtests locally (distributed.Cluster dtests run on macOS).
- [ ] Flag-off regression: full existing memtable/write dtests green (byte-identical path).
- [ ] JMX smoke: shard executors registered, `contended/uncontended/misroutedPuts` readable.

## Review / done criteria
- [ ] Flag-off = trunk behavior (proven, not asserted).
- [ ] Flag-on: `contendedPuts≈0`, `contentionTime≈0`, `misroutedPuts≈0` on a healthy KeyValue A/B.
- [ ] No deadlock on paxos/counter/hint paths (they stay unrouted, lock-fallback).
- [ ] Ready for the rig A/B (re-provision load box → 3-iter noise bands trunk + flag-on at the
      gate points). ⟵ this is where the box is needed again.
