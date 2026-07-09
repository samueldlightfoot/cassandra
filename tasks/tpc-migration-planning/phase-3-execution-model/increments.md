# Phase 3.4 — Incremental Landing Plan (increments.md) ★ the PoC build order

**Status:** decided 2026-07-09, written by the main agent from the three ACCEPTED
designs (design-target.md, design-async-coordinator.md, design-hostiles.md — each
adversarially reviewed and amended). Class-level file inventories live in
`../phase-4-poc/expected-changes.md` (amended 2026-07-09 to match the designs);
this document is the ORDER, the GATES, and the CLAIMS. Phase 4 executes this list
in the fork; flags exist for clean A/B and mergeable shape, production freight
(mixed-version care, JMX compat polish, rollback tooling) is CEP-era per Phase 4
D10 — each entry marks PoC-blocking vs CEP-era requirements.

---

## 0. Measurement harness (pinned — applies to every increment)

- **Macro:** cassandra-easy-stress against the rig per the established methodology
  (runbook.md + bench-playbook memory): CPU-fenced load generators, kernel/governor
  /IRQ environment recorded per cell (irqbalance state, NVMe IRQ affinity, taskset
  mask — the Enberg paper's dominant tail variable; a cell without the capture is
  invalid), no-SIGTERM, stdout counts. **Flag-on vs flag-off on the SAME build.**
- **Config pins (violating any invalidates the A/B):** `memtable: trie` (trunk
  default is SKIPLIST — unsharded), `commitlog_sync: periodic`,
  `disk_access_mode: standard`, non-Accord tables, no MVs / 2i / counters on bench
  tables (phase-4 §0).
- **The tail gate (every increment, non-negotiable):** acceptance = **p99 ≤ flag-off
  at throughput ≥ flag-off** on the increment's macro cells. The 2016 10993 POC
  posted +15% throughput with ~2× worse p99/p99.9 and would have passed a
  throughput-only gate — that is the failure mode this rule exists to catch. A
  failed tail gate files a hurdle-log entry (phase-4 4.3) with the shard-attribution
  evidence (per-shard PendingTasks, misroutedPuts) before any remedy is attempted.
- **Micro counter per increment** (the mechanism evidence, named per entry below) —
  macro says whether it's faster, micro says whether it's faster *for the claimed
  reason*.
- **tpstats freeze:** MutationStage/ReadStage SEP gauges stop meaning anything once
  traffic routes around them (partially at I1/I2, fully at I5) — A/Bs use messaging
  `internalLatency` (still recorded) + shard-executor ThreadPoolMetrics + the
  per-increment counters, never Stage gauges.
- **Oversubscription is a 4.1 gate, not an increment decision** (USER item 5,
  phase-4 §8): N=cores shard threads atop existing pools shapes every A/B's honesty
  — pinned with the criteria before I1 code. Recommendation on record: accept for
  I1; shrink NTR flag-on only in the I3 cell where the shrink IS the claim.
- **Single-node rig caveat:** coordinator == replica, so I2 covers the benchmark's
  hot read path before I5 exists, and I5's effect only fully shows multi-node —
  recorded in poc-criteria.md, I5's macro number is not oversold.

## 1. Build order and dependency graph

```
I0 (shard runtime, inside I1's patch)
 └─ I1 (routed mutation apply)
     ├─ I2a (routed reads, sequential())          [needs I0 + same boundaries source]
     │    └─ I2b (custom shard loop + ring + I/O pool)   [needs Phase 1 binding]
     ├─ I4b (ShardedOpOrder) ─ I4a (N commitlog managers) ─ I4c (allocators)
     │    [I4b's composite barriers are I4c's discard-safety prerequisite —
     │     design-hostiles §3.3; I4a consumes I4b's site-3 composite]
     └─ I5 (inbound dispatch)                     [independent of I2/I3/I4]
I3 (async coordinator) — INDEPENDENT of routing; steps 0–3 can start any time
     after I0; sequenced after I2 in the pinned order to keep one increment
     in flight at a time, but it is the hedge (§8) precisely because it
     doesn't need the others.
```

Validated amendments to the spec's pinned I1→I5 sequence (with evidence):
- **I0 named and gated separately** (session-4 decomposition, confirmed): no flag,
  no A/B — inert until routed to; gate = tests green + shutdown-before-commitlog
  ordering (`Stage.java:163-184` wiring).
- **I2 split I2a/I2b** (confirmed): isolates the Phase 1/2 dependency in I2b; the
  two flags (`shard_reads` / `uring_reads`) A/B scheduler effect and I/O effect
  separately.
- **I3 steps 0–3** (confirmed, now concrete from design-async-coordinator §9):
  step 0 is flag-independent scaffolding testable alone.
- **I4 order fixed b→a→c** (new, from design-hostiles): composite barriers (b)
  before per-shard managers (a) because site 3 (`ACLSM:371`) needs the composite;
  allocators (c) last because discard safety consumes (b)'s await-all. One flag
  pair as pinned (`sharded_commitlog`, `sharded_write_order`); c rides
  `sharded_commitlog` (ships with I4, phase-4 §5).
- **I5 stays last and single** (confirmed): smallest patch, biggest measurement
  caveat (single-node).

---

## 2. I0 — shard runtime foundation

| | |
|---|---|
| Scope | CREATE `concurrent/ShardExecutors.java`, `db/MutationShardRouting.java` (inventory: phase-4 §1, predicate per design-target §5 normative list incl. cdc/legacy-2i exclusions + periodic startup check) |
| Flag | none (inert until I1 routes to it) |
| Claim / gate | No measurable claim. Gate: unit tests green; shutdown drains shards BEFORE commitlog stops; `currentShardId()` correct from owner registry. |
| Micro | enqueue→dequeue hand-off latency + unparks/sec exported from day one (paper rule: the wake-up is the steering cost — this is the number every later increment interprets). |
| Rollback | Dead code (nothing routes to it). |
| PoC-blocking | All of it. CEP-era: none. |

## 3. I1 — shard-routed mutation apply (+ I0 in the same patch)

| | |
|---|---|
| Scope | `StorageProxy.java:1918` local branch, `MutationVerbHandler.java:80-84`, `AbstractShardedMemtable` getter, `TrieMemtable` step-2 owner-check path + `misroutedPuts` (phase-4 §2) |
| Flags | `cassandra.mutation.shard_routing`; step 2 `…shard_routing.skip_lock` |
| Claim | Memtable per-shard lock contention → ~0 flag-on (contended puts ≈ 0, contention time ≈ 0) at equal-or-better loaded p99. **Honest baseline stated in the cell:** today a local apply can run INLINE on NTR with zero hops when a MUTATION permit is free — routing always costs one hop, so expect the paper's low-concurrency crossover: median and low-load tail may regress. One low-load cell characterizes the crossover; it does NOT gate. The gate is the loaded tail. |
| Measured | Macro write-heavy cells flag-on/off + tail gate (§0). Micro: JMX `type=TrieMemtable` contended/uncontended/contentionTime + new `misroutedPuts` (must stay ~0 on healthy boundaries — it is D6's discriminator). |
| Rollback | Flag off = byte-identical trunk path (`maybeExecuteImmediately` at `:2025`). |
| Depends | I0. |
| PoC-blocking | Both flags + counters + the owner-check fallback. CEP-era: none (this is the smallest real TPC step). |

## 4. I2a — shard-routed local reads (sequential())

| | |
|---|---|
| Scope | `AbstractReadExecutor.java:168` + the three sibling seams (`AbstractReadRepair.java:102`, `ShortReadPartitionsProtection.java:191`, `ReplicaFilteringProtection.java:175` — same key ⇒ same shard), boundaries from `cfs.localRangeSplits(n)` — **the SAME source as I1** (dependency edge, phase-3 expected-changes §3.4) |
| Flag | `cassandra.tpc.shard_reads` |
| Claim | Read scheduling coherence (key-affine execution) at equal p99; **the known hazard is stated up front:** a synchronous cache-miss on a shard thread blocks the whole shard (readSync-blocks-shard, Phase 2 syscall KPI: sync facade = 1 enter/op) — I2a with `standard` disk access keeps misses on the shard thread, so I2a's gate cells are cache-hot; the miss story is I2b's I/O pool. |
| Measured | Macro read cells (hot) + tail gate. Micro: per-shard PendingTasks; ChunkCache hit/miss rates per cell. |
| Rollback | Flag off = `Stage.READ.maybeExecuteImmediately` verbatim. |
| Depends | I0/I1 (boundaries source + executors). |
| PoC-blocking | The four routed seams. CEP-era: none. |

## 5. I2b — custom shard loop + ring + the small I/O pool

| | |
|---|---|
| Scope | Replace `sequential()` with the custom loop (drain MPSC inbox + drive the shard's io_uring ring + run continuations; adaptive spin→yield→park idle strategy with park/unpark rate observable — design-target §1); `ChannelProxy.read:169-180` ring seam; the **small I/O pool** (default 8, rig-class-relative) with the miss-reschedule seam (NotInCacheException shape at the rebufferer; read-group lifecycle per design-target §3 item 5 — groups never cross threads); RWF_NOWAIT refinement SKIPPED for PoC (named profile trigger recorded) |
| Flag | `cassandra.tpc.uring_reads` (composes with `shard_reads`) |
| Claim | Cache-miss reads stop blocking shards (dispatch to pool); ring QD1 parity is free (Phase 2: B/A = 1.00). **The A/B axes must not be conflated** (design-target §2): {pool-dispatch (default), pure-per-shard-ring (comparator)} × {arm A buffered, arm B DIO} — pool×A and pool×B are the primary adjudication cells for phase-4 §8 item 8 (user prior: full-Scylla arm B). |
| Measured | Macro read cells hot AND the **cold-cache miss-storm cell** (phase-4 §7.5: per-shard PendingTasks watched; backlog explosion = hurdle-log entry, not a silent p99). Micro: ring ops/s, pool queue depth, dispatched-read latency split (pool wait vs device), park/unpark rate. Tail gate on all accepted cells. |
| Rollback | Flag off = FileChannel.read path; `shard_reads` alone falls back to I2a. |
| Depends | I2a + Phase 1 binding (as-built API per phase-1 findings-execution §3 with hardened syncOp). |
| PoC-blocking | Loop, seam, pool, miss-storm cell. CEP-era: chunk-level read continuations (the pool's terminal-size-zero end-state — out of PoC scope, stated so nobody oversells I2b), RWF_NOWAIT, per-thread pinning. |

## 6. I3 — non-blocking coordinator (steps 0–3)

Build order inside the increment = design-async-coordinator §9; the cut-line,
executor decision, and all machinery are DECIDED there — this entry is the gate
structure.

| | |
|---|---|
| Scope | Step 0: completion helper + exactly-once promise guard + idempotent ops-slot handle + park guard (full §3-inventory insertion list) + ops limiter (event-loop tryAcquire, release-signalled WaitQueue) + deadline-task machinery — **flag-independent, inert, testable alone**. Step 1: ReadCallback/AWRH promises at the existing signal sites. Step 2: `executeAsync` + statement-and-CL predicate (QueryMessage/ExecuteMessage/BatchMessage) + ContinuationContext capture. Step 3: read chain (digest compose, R4 AsyncFuture, materialization → requestExecutor) + write chain (allOf, CL.ANY exceptional branch, speculation timers, metrics relocation). |
| Flag | `cassandra.tpc.async_coordinator` (steps 1–3; step 0 has no flag) |
| Claim | Parked-RTT NTR threads become redundant: outstanding-ops replaces thread-count as the concurrency bound, at equal-or-better tail. The measurable claim is the **shrink cell**: `native_transport_max_threads` reduced flag-on (default UNCHANGED otherwise — design-async-coordinator §2). |
| Measured | Macro: standard A/B + the shrink cell, tail-gated. Micro: outstanding-ops gauge + rejection meter (THE I3 counter), NTR utilization, **RR-pool health (PendingTasks + oldest-task-age) first-class in every cell** — RR also delivers the responses that unblock everything behind the line, so this gauge is what falsifies or confirms the §1 executor bet; park-guard violation counter (expected: zero); FlushItems-per-request assert (test mode). |
| Rollback | Flag off = synchronous `execute` byte-for-byte; step-0 scaffolding is inert. |
| Depends | Nothing TPC-side (I0 only for eventual shard-inbox completion — CEP-era executor swap). Sequenced after I2 in the pinned order; independently startable. |
| PoC-blocking | Steps 0–3, the three §8 build requirements, the guard. CEP-era: Paxos/counter/batch conversion (per-round owner-forwarding, D5), async QueryHandler interface, wheel timer (only if cancellation churn shows). |

## 7. I4 — per-shard writeOrder (b) → commitlog (a) → allocators (c)

| | |
|---|---|
| Scope | **I4b:** `ShardedOpOrder(N)` replacing `Keyspace.writeOrder` (N = flag: 1 off / cores on — one code path); `OpOrder.Group` owner field; composite barriers at the 5-site census; §0 attribution computed once in `beginWrite` (design-hostiles §1). **I4a:** N per-shard segment managers with the coverage protocol — manager-banded ids + per-manager bound vectors + N IntervalSet intervals (no format change); shared cap atomic; ONE sync service iterating N (watch item W-SYNC); shared CDC tracker; global failure policy; per-manager `getCurrentPosition(int)` with the caller disposition (design-hostiles §2). **I4c:** one `MemtableAllocator` per MemtableShard; pool/cap/cleaner global; step-2 slack batching NOT built unless the named signal fires (design-hostiles §3). |
| Flags | `cassandra.tpc.sharded_write_order` (I4b), `cassandra.tpc.sharded_commitlog` (I4a; I4c rides it) |
| Claim | The two global write-path cachelines stop being global: writeOrder `register()` CAS and commitlog `allocatePosition` CAS become per-shard at equal-or-better tail and **zero replay regressions** (the loss-sequence test is the headline regression gate — design-hostiles §2's TEST list). |
| Measured | Macro write-heavy cells per flag (I4b alone, I4b+I4a, +I4c), tail-gated. Micro: commitlog `waitingOnSegmentAllocation` + `waitingOnCommit` (W-SYNC threshold: any shard-thread occurrence, or sync-pass p99 > period/2, fails the cell into the hurdle log); allocatePosition contention proxy per manager; **I4c's step-2 gate signal** = `MemtablePool.SubPool` addAndGet as top-10 profiler frame or dominant perf-c2c HITM line at the I4c A/B — below that bar step 2 is not built. |
| Rollback | Flags off: `ShardedOpOrder(1)` = trunk semantics on the same code path; 1 manager = band-0 ids = byte-identical including the id sequence. Restart with flags flipped either direction replays cleanly (design-hostiles §2 FAILURE/UPGRADE); drain-before-flip recommended, not required. |
| Depends | I1 (attribution rule-1 needs shard threads; managers are near-single-writer only under routing). Internal order b→a→c (§1 graph). |
| PoC-blocking | I4b composite machinery + I4a coverage protocol + the new test suite (loss-sequence, replay-union, banded-id, truncation-under-N, per-manager discard, shared-cap, CDC-exhaustion) + I4c step 1. CEP-era: async group-commit (batch/group under routing — decided shape in design-hostiles §2.3, sequenced after I3), step-2 slack batching (measurement-gated), per-band globalPosition prefilter, snapshot-restore at N>1, `commitlog_segment_size` re-tuning. |

## 8. I5 — inbound shard dispatch

| | |
|---|---|
| Scope | `net/ShardInboundRouter.java` (allowlist {MUTATION_REQ; READ_REQ iff SinglePartitionReadCommand}; inbox-full → `stage.execute` fallback keeping existing capacity accounting as the back-pressure authority); `InboundMessageHandler.java:429` branch reusing the ProcessSmallMessage task verbatim; **the dtest seam** — `Instance.receiveMessageRunnable` calls the SAME router (in-JVM delivery bypasses InboundMessageHandler entirely; without the seam, dtests-pass-flag-on is vacuous — phase-4 §6) |
| Flag | `cassandra.tpc.inbound_shard_dispatch` |
| Claim | Replica-side verb execution lands on the owner shard without the Stage hop; net win = Stage-hop removal minus the netty→shard wake (paper: wake ≈ µs) — **environment capture is load-bearing for this A/B specifically** (IRQ/loop affinity decides the sign). |
| Measured | Macro multi-node when available; on the single-node rig the honest cells are replica-path microbenches + messaging `internalLatency` (tpstats FROZEN flag-on — §0). Micro: inbox depth, fallback count, `internalLatency`. Tail gate on whatever cells are accepted. |
| Rollback | Flag off = `header.verb.stage.execute` verbatim. |
| Depends | I0/I1 (executors + routing predicate); independent of I2/I3/I4. |
| PoC-blocking | Router + branch + dtest seam. CEP-era: HINT_REQ allowlist promotion (only on profile evidence — design-target §5), large-message handling, Accord verbs (D7 route-through-inboxes end-state). |

---

## 9. The hedge set (worthwhile even if full TPC is never reached)

Ranked; this is the program's de-risk (spec §3.4 requirement):

1. **I3 — the strong hedge.** Zero dependency on shard routing; frees the
   128-thread NTR pool's raison d'être; replaces a dead back-pressure signal with a
   real concurrency bound; fixes the silent local-leg drop hang class (deadline
   authority) and makes the FQL/audit blocking visible. Mergeable on its own as
   "async coordinator" with no TPC framing at all.
2. **I4c step 1 (allocator-per-MemtableShard) — the quiet hedge.** TrieMemtable
   already HAS N shards multi-written by SEP workers today; one allocator per shard
   removes the sibling `Region.nextFreeOffset` CAS with no dependency on shard
   THREADS (only the discard-safety barrier work ties it to I4b in this plan — as a
   standalone it would ride today's single barrier unchanged). Small, measurable,
   upstream-friendly.
3. **I1 — the conditional hedge.** Contention-to-zero on the memtable lock is real,
   but standalone value depends on the loaded-tail win beating the added hop —
   exactly what its A/B measures. If the gate passes convincingly, it stands alone;
   if it passes only narrowly, its value is as the foundation for I2/I4/I5.
4. **Not hedges:** I2a/I2b (scheduler+ring value realizes through shard ownership),
   I5 (pure TPC plumbing), I4a/I4b (the coverage-protocol complexity is only worth
   carrying to enable single-writer commitlog/writeOrder — as standalone
   engineering it's cost without a claim).

## 10. Cross-cutting invariants (every increment inherits)

- **Owner-check-with-lock-fallback everywhere** (design-target §1): correctness
  never depends on routing; every single-writer claim keeps its guard.
- **No blocking on shard threads** — each increment's entry above names its
  blocking budget; the shared write-path stalls (MEMORY_POOL park,
  `awaitAvailableSegment`, W-SYNC) are enumerated once in design-hostiles and
  watched, not assumed away.
- **No wire-format or persistent-format changes** in any increment (banded segment
  ids are process-local longs; IntervalSet metadata shape unchanged; no new error
  codes — I3 reuses `OverloadedException`). Verify per increment at spec time;
  this is what keeps mixed-version risk at "node-local" (effort.md register).
- **Flags are startup-pinned** (`CassandraRelevantProperties`, read-once static
  final — hot-swap creates mixed-writer windows; phase-4 §0).
- **Every CEP-era item above has a Scylla reference** (user directive 2026-07-09:
  influence, not gospel) — design-target §10 is the per-decision map; when a
  CEP-era item is picked up, consult its row first (e.g. read continuations, not
  pool growth; per-shard admission; scheduling groups as the maintenance
  direction).
