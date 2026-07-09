# Phase 3.1 — Target Architecture (design-target.md)

**Status:** decided 2026-07-09. Inputs: spec.md §1–§3.1; expected-changes.md §3.1/§3.6
(pinned decisions restated, not reopened); Phase 2 verdict.md (G1/G3 consequences);
../findings.md §5.1 (two-arm I/O directive, user 2026-07-09); the four findings-* sweeps
(2026-07-05/07); ../findings-tpc-paper.md (Enberg ANCS'19). All load-bearing file:line
refs re-verified against the working tree 2026-07-09 (one drift:
`ColumnFamilyStore.localRangeSplits` is now `:1608`, was `:1607`). Amended same day per
adversarial review (review-design-target.md, PASS-WITH-FIXES): CDC timing corrected to
I1, hints reclassified as fallback-covered, fates added for readOrdering / legacy-2i /
commitlog batch-group sync, skew remedy lever corrected, misroutedPuts defined precisely. Amended again same day
per design-hostiles §6's normative notes (post its own review): writeOrder carrier is
the `OpOrder.Group` owner field (not `CassandraWriteContext`), attribution gains a
threadId%N third tier, and the commitlog replay clause now carries the banded-id +
bound-vector coverage protocol.

Audience: the implementing agent (Phase 4 builds `phase-4-poc/expected-changes.md` §1's
I0 exactly as specified here), then CEP reviewers. Every section ends in a DECISION.

---

## 0. The model, named, and its ceiling

**In Enberg et al.'s taxonomy (ANCS'19) this design is *shared-something*:** per-shard
memtable shard / commitlog manager / OpOrder / allocator (shared-nothing slices) atop
shared SSTables, a shared chunk cache, and shared maintenance pools. The one-line
pre-answer to "why not full shared-nothing like Scylla": the shared-nothing skew ceiling
(one core serves a hot partition — their §III.A) plus Cassandra's page-cache reliance —
**with the explicit caveat that the page-cache half of that answer is PROVISIONAL**: per
../findings.md §5.1 the user's prior at Phase 2 close is a full-Scylla end-state (arm B,
§2 below), and performance is the sovereign criterion. The skew-ceiling half stands
either way, so the model stays shared-something under both arms — arm B narrows what is
shared (page cache out, expanded ChunkCache in), it does not change the shard model.

**The routing ceiling (state it so reviewers learn it here, not discover it):** all
shard routing in this design is **coordinator-side, after CQL parse+bind** (the earliest
token-known point, §4). A shard-aware client protocol — Scylla-style per-shard ports
with the driver computing token→shard and holding one connection per shard per node
(shipped today in Scylla's forked drivers: `SCYLLA_NR_SHARDS` handshake + deterministic
`source port % nr_shards` assignment; ../findings-tpc-paper.md §3 Phase-4 prior-art
note) — is **OUT of scope**. Consequence: **every request retains exactly one in-node
inbox hop** from its arrival thread to its owning shard. That hop is the same hand-off
Scylla pays for non-shard-aware clients; the protocol extension is the known destination
(roadmap conscious-defer item 13), a sequencing choice, not an unsolved problem.

---

## 1. Shard model — DECISION D1

**N node-global shard threads, N = cores** (matching
`AbstractShardedMemtable.getDefaultShardCount()` — `AbstractShardedMemtable.java:46`).
Oversubscription (shard threads atop existing pools) is accepted for I1 and re-derived
per increment where the pool IS the claim — this adopts phase-4 §8 item 5's
recommendation, which is USER-flagged and must still be ratified with the 4.1 criteria
before increment code; cores−reserved is CEP-era tuning. Unpinned in the PoC (JVM-level taskset per the rig runbook records the
envelope; per-thread pinning is CEP-era).

**Shard identity is per-(table, key), never global.** `ShardBoundaries` are a per-table
function of shardCount × partitioner × locally-owned ranges × TCM epoch
(`ColumnFamilyStore.localRangeSplits`, `ColumnFamilyStore.java:1608-1627`; boundaries
object `ShardBoundaries.java:80,110`), and **memtables pin their boundaries for life** —
during a memtable switch two live memtables with different boundaries accept writes;
local-system keyspaces get full-range boundaries (findings-i1 §2, restated from
expected-changes §3.1 item 1). Therefore shard-id computation is
`memtable.getShardBoundaries().getShardForKey(key)` from **each table's current
memtable**, mapped `% N` — there is no global boundary object and can't be.

**Consequence, elevated to a design principle: owner-check-with-lock-fallback.**
Routing can never be assumed perfect (epoch bumps, memtable switches, and the standing
non-routed writer populations: hints, read-repair, paxos commit, replay, counters,
views, Accord — phase-4 §2 PINNED list). Every structure that becomes single-writer
keeps its guard as a fallback: `TrieMemtable.MemtableShard.put` keeps its
`tryLock` path (`TrieMemtable.java:513,553`) and takes the lock-free path only when
`ShardExecutors.isOwner(shardIndex)` computed from *this memtable's own boundaries*.
**`misroutedPuts` is defined precisely** (it is D6's discriminator, so its noise floor
matters): it increments ONLY when the owner check fails **on a shard thread**
(`currentShardId() >= 0`); puts arriving from off-shard threads (hints delivery,
replay, repair, excluded-table traffic — all of Stage.MUTATION's residual users) take
the tryLock fallback without touching the counter. Correctness never depends on
routing; routing only buys contention-freedom. This is what lets everything below ship
flag-gated and incrementally.

**Runtime primitive (restating the pin, expected-changes §3.1 item 2):** for the PoC,
per-shard executors are
`executorFactory().localAware().withJmx("request").sequential("Shard-"+i)` (name pin
amended from item 2's `"MutationShard-"+i` to phase-4 §1's `"Shard-"+i` — the executors
serve reads too) —
ExecutorLocals propagation and ThreadPoolMetrics JMX for free; unbounded inboxes are
backpressure *parity* with today (SEP queues are unbounded, `SEPExecutor.java:104-108`).
The "swap Stage.MUTATION's executor" shortcut stays REJECTED (opaque Runnables; the
stage carries hints/paxos/batchlog/read-repair/replay traffic — findings-i1 §3). At
**I2b** the `sequential()` executor is replaced by a custom loop:
drain-MPSC-inbox + drive-the-shard's-io_uring-ring + run-continuations
(`ManyToOneConcurrentLinkedQueue` precedent from the netty loops,
`SocketFactory.java:115,140`; jctools in-tree). Two paper-sourced notes bind the custom
loop (expected-changes §3.6):
- **The idle strategy is the hotspot** — the wake-up is the unit of steering cost
  (wake ≈ µs vs enqueue ≈ tens of ns; Enberg's #1 held-back-by finding).
  Park-after-every-drain pays a futex wake per message at low load; pure spin burns the
  core (their Shenango critique). DECIDED: adaptive spin→yield→park (netty/Aeron
  precedent), with park/unpark rate exported as a first-class observable from day one.
- **Single MPSC inbox accepted for the PoC** with tail-CAS producer contention from ~P
  producers recorded as the known next bottleneck (Sphinx used per-producer-pair SPSC
  queues); revisit only if inbox enqueue shows in profiles.

**Shutdown:** shard executors drain BEFORE commitlog stops, wired like Stage's
`shutdownBeforeCommitlog` (`Stage.java:163-184`; phase-4 §1).

---

## 2. The two I/O arms — DECISION D2 (what is invariant, what differs)

User directive (2026-07-09, ../findings.md §5.1): the small I/O pool must be tested,
but the stated prior is the full-Scylla end-state. Both arms are first-class in this
design; the Phase 4 A/B adjudicates on the PoC criteria (p99 ≤ trunk at throughput ≥
trunk).

- **Arm A — page-cache buffered foreground reads:** sstable data reads are buffered
  ring reads (I2b); the page cache remains the primary data cache; ChunkCache stays at
  today's size as a front.
- **Arm B — full-Scylla:** foreground reads are O_DIRECT ring reads; **ChunkCache
  expanded to be the primary cache** (userspace caching tier — an expansion of an
  existing seam, `ChunkCache.java:48-53`, not greenfield); page cache irrelevant for
  data files.

**Invariant across both arms (the shard model itself):**
- Shard threads, boundaries, routing points (§4), inboxes, owner-check-with-lock-
  fallback (§1) — identical bytes.
- The **entire write path**: background writers (commitlog, compaction, flush,
  streaming, hints) target DIO+ring in BOTH arms (Phase 2 G3: DIO+ring never punts to
  iou-wrk on any fs; buffered async writes punt on both fs on kernel 6.8 with awful
  completion tails). I4's commitlog/writeOrder/allocator design is arm-independent.
- Async coordinator (I3), inbound dispatch (I5), cross-shard patterns (§5), skew stance
  (§6), Accord choice (§7), the off-shard census (§3) including the small I/O pool's
  *existence and dispatch seam* in the PoC.

**What differs:**
- **I/O mode of foreground miss reads**: buffered ring (arm A; may punt to iou-wrk on
  ext4 — G3) vs DIO ring (arm B; never punts; the binding does 288k DIO w/s and
  1.7–1.8× fio on hot batched reads — verdict G2/G3).
- **Hit cost structure** (priced by Phase 2, ../findings.md §5.1): a ChunkCache hit is
  a memory read, zero kernel entry, in both arms; a page-cache hit through the ring
  (arm A only) still costs a syscall ≈ 1.4 µs/op. Arm B converts all hits to the zero-
  syscall class — that asymmetry is the mechanism behind the user's prior.
- **Memory layout**: arm B moves most of the file-cache budget into (off-heap)
  ChunkCache + eviction/sizing work; index access must also come through the cache
  (mmap index page faults are blocking hazards on shard threads — paper rule C5), so
  arm B retires `mmap_index_only` for shard-routed reads. Arm A keeps today's split.
- **HOSTILE #5's trajectory**: stays global-atomic in both arms for the PoC (§8), but
  arm B adds a CEP-era option — per-shard/NUMA sharding of the expanded ChunkCache
  (Scylla's per-shard cache analogue) — that arm A never needs.

**Phase 4 cell matrix this implies (two independent axes, don't conflate "the A/B"):**
{miss-dispatch-to-pool (default), pure-per-shard-ring (comparator)} × {arm A, arm B} —
pool×A and pool×B are the primary adjudication cells; the ring comparator runs at
minimum on whichever arm wins.

---

## 3. What stays off-shard — DECISION D3

Shard threads own the foreground read/write hot path and nothing else. Off-shard, with
pools kept, interacting with shards via messages/snapshots:

1. **Compaction, flush, streaming, repair, gossip, TCM, scheduled tasks** — unchanged
   pools (census in findings-seda-stages §4). This matches the CASSANDRA-10994
   consensus: Weisberg — big immutable-data tasks lose nothing on plain threads;
   Yeschenko — don't move maintenance until numbers justify it. Structural bonus vs
   2016's fear: maintenance never steals a shard's core (see §6).
2. **Netty stays as the network layer** (native transport 2P loop threads + internode P
   loop threads). Seastar-style full network takeover is explicitly REJECTED: Netty
   4.1 works; the measured win is in storage-path affinity, not socket polling —
   CASSANDRA-10993's POC put netty-queue overhead at ~1% at saturation. Loop→shard
   hand-off is the one inbox hop (§0 ceiling).
3. **The extension-point escape pool (NEW, one pool):** custom `Index.Indexer` is the
   only pluggable code that runs *inside* apply on the applying thread
   (`ColumnFamilyStore.java:1524-1525`; contract does not forbid blocking —
   findings-accord §4); triggers run coordinator-side pre-apply
   (`StorageProxy.java:1250`) and are arbitrary user code. Both get one new off-shard
   sequential/pooled executor ("UserCodeEscape"). Built-in indexer certification,
   qualified per review: **SAI is certified non-blocking inline** — it writes parallel
   in-memory per-memtable index structures, not a memtable
   (`StorageAttachedIndex.java:1026-1028`; those structures remain shared-multi-writer
   concurrent under TPC — accepted, they are lock-free in-memory, a one-line
   stays-shared rationale for CEP reviewers). **Legacy `CassandraIndex` is NOT
   non-blocking**: it applies a mutation to the index CFS inline through the full
   write path (`CassandraIndex.java:536-547` → `indexCfs.getWriteHandler().write`) and
   so carries the standard write-path blocking budget (MEMORY_POOL park, commitlog
   allocation) — handled by predicate exclusion, §5's legacy-2i row. Auth and UDF
   already have pools (`Dispatcher.java:81-84`; `Config.java:738-739` UDF threads
   default on) and keep them.
4. **Write-path blocking classes: CDC block-on-space + batch/group commitlog sync
   (decision notes, required before I1 — not I4):** `CommitLog.instance.add` runs
   INLINE in `beginWrite` on the applying thread
   (`CassandraKeyspaceWriteHandler.java:99`), so the moment **I1** routes an apply to
   a shard thread the whole commitlog add runs there — including
   `CommitLogSegmentManagerCDC.throwIfForbidden` (`:178/:186` → `:215-231`), which can
   block/throw on CDC space exhaustion. DECIDED: **tables with `cdc=true` are excluded
   from shard routing by the routing predicate** (same mechanism as MV exclusion, §5)
   — the exclusion ships with I0's predicate, and this doc AMENDS phase-4 §1's
   `MutationShardRouting` spec accordingly (§9); async CDC allocation is CEP-era work.
   Same inline-add fact governs **`commitlog_sync: batch/group`**: those modes wait
   for the fsync inside add (`CommitLog.java:337` `finishWriteFor`), which would park
   the shard thread per write. DECIDED: shard routing in the PoC **requires `periodic`
   sync** (startup check when the routing flag is on; the bench pins it anyway,
   phase-4 §0); batch/group remain supported-but-antithetical — flag-on with batch
   sync disables routing rather than silently cliffing — and the CEP-era resolution is
   async group-commit at I4 (the sync waiter becomes a continuation), decided in
   design-hostiles.
5. **The small I/O pool for cache-miss reads (NEW — Phase 2 G1 consequence).** This is
   10994's stage-1 compromise, now chosen by measurement, and it must be placed
   precisely:
   - **Why it exists:** G1 showed one pinned core drives ~260k IOPS = 62–67% of the
     12-core psync-50 baseline (417k), CPU-bound at 100% core — and proportionally
     less when the core also does query work. A synchronous `readSync` on a shard
     thread blocks the whole shard for full device latency (~80–100 µs); 32 READ
     workers absorb that today, N = cores shard threads (12 on the G1 rig) don't
     (paper rule C5).
   - **Dispatch seam:** the shard-side read runs under a no-device-blocking policy;
     at the pinned I2 seam (`ChannelProxy.read`, phase-4 §3) a ChunkCache miss on a
     shard thread aborts the shard-side attempt (reschedule exception at the
     rebufferer, DSE's NotInCacheException shape) and **resubmits the whole read task
     to the pool** — reads are idempotent, duplicated work is bounded by one
     memtable+cache probe. **Read-group lifecycle across the reschedule (stated, per
     review):** OpOrder groups never cross threads — the aborted shard-side attempt
     closes its own `ReadExecutionController` (and its `readOrdering` group) via the
     existing try-with-resources on the shard thread; the pool re-executes the task
     from the top and opens a fresh controller/group on the pool thread
     (`ReadExecutionController.forCommand`, `ReadExecutionController.java:129-146`).
     Safe because a read group only guards resources referenced by its own attempt
     (memtable/sstable snapshot validity for that attempt), and the retry re-snapshots
     — the same reasoning that makes reads idempotent. Arm-A refinement: try the
     buffered ring read inline with `RWF_NOWAIT` first — page-cache hit completes in
     ~1.4 µs without blocking; EAGAIN → dispatch. DECIDED: **skip for the PoC build**;
     build it only if I2's pool-dispatch profile shows a material share of dispatched
     reads completing in µs on the pool (= page-cache-resident chunks paying a wasted
     hop). The pure per-shard-ring shape (shard thread does readSync itself) is the
     A/B comparator, not the default (spec §3.4 I2; cell matrix in §2).
   - **Sizing basis (Phase 2 numbers):** PoC pool threads run blocking whole-read
     tasks ⇒ each contributes ~QD1 ≈ 10–12k IOPS at NVMe latency; the device wants
     ~QD50 for its 417–500k ceiling. The pool is sized for the *expected miss
     fraction* of a cache-dominated workload, not for miss storms — default 8, tunable
     (sizing assumed the 12-core G1 rig class; re-derive per hardware, cores-relative
     at CEP); the phase-4 §7.5 cold-cache miss-storm cell adjudicates (backlog
     explosion = hurdle-log entry). Evolution: batched ring submission from pool
     threads (2 × 260k ≥ device ~500k, G1's own numbers) once chunk-level
     continuations exist.
   - **How it shrinks/disappears under arm B:** the expanded ChunkCache absorbs a
     larger share of reads in userspace (zero-syscall hits), so the pool's traffic is
     the true device-miss residue, now DIO (never punts, no iou-wrk noise). The pool's
     terminal size is **zero in either arm** once read continuations let each shard's
     own ring hold multiple in-flight reads (out of PoC scope — say so; phase-4 §7.5
     I2 note). Arm B reaches that end-state naturally (Scylla's model has no separate
     I/O pool); arm A can also retire it via continuations but keeps paying the
     syscall per page-cache hit.

Everything off-shard writes through the same seams as today and is covered by the
owner-check fallback when it touches shard-owned structures (§1).

---

## 4. Routing points — DECISION D4 (verified line numbers)

| Path | Point | Evidence (verified 2026-07-09) |
|---|---|---|
| CQL, token first knowable | after parse+bind, in statement execution | `restrictions.getPartitionKeys` `SelectStatement.java:821` → `partitioner.decorateKey` `:839` (single-key), `:847` (multi). The loop→NTR hop (`Dispatcher.java:131`) precedes token knowledge — hence the §0 ceiling. |
| Coordinator local write | `sendToHintedReplicas` local branch → `performLocally` | **`StorageProxy.java:1918` → the `:2023-2066` overload, mEI at `:2025`** — NOT `:1995` (that overload serves batchlog store/remove, call sites `:1659/:1675`; spec's `:1995` citation corrected per expected-changes §3.1 item 5). `LocalMutationRunnable` reused unchanged (deadline→hint logic inside `run()`). |
| Coordinator local read | `Stage.READ.maybeExecuteImmediately(LocalReadRunnable)` | `AbstractReadExecutor.java:168`; `cfs` in scope at `:77` — everything routing needs is present. Read-repair/SRP/RFP siblings route with it (`AbstractReadRepair.java:102`, `ShortReadPartitionsProtection.java:191`, `ReplicaFilteringProtection.java:175` — same key ⇒ same shard). |
| Replica write | `MutationVerbHandler.applyMutation` | `MutationVerbHandler.java:80-83` — already continuation-style (`applyFuture().addCallback`); body submits to shard inbox. |
| Inbound internode | after payload deserialize on the netty loop, small messages only | dispatch seam `InboundMessageHandler.java:429` (`header.verb.stage.execute`). Header carries verb, not token; small messages deserialize on-loop (`:161-216`) so token IS knowable there. Allowlist {MUTATION_REQ (`Verb.java:202`); READ_REQ iff `instanceof SinglePartitionReadCommand` — READ_REQ shares its serializer with RANGE_REQ (`Verb.java:228-230`)}. Large messages stay on Stage (token unknowable pre-deserialize; off the hot path). Inbox-full → fall back to `stage.execute` (existing capacity accounting stays the back-pressure authority). |

DECISION: these five are the only routing points. Everything else reaches shard-owned
state through them or through the owner-check fallback.

---

## 5. Cross-shard operations — DECISION D5 (dispatch pattern per op)

| Op | PoC state | End-state dispatch pattern |
|---|---|---|
| **Range reads** | NOT shard-routed — stay on Stage.READ (`RangeCommandIterator.java:223`); a cross-shard scan on one shard thread would serialize behind point reads (phase-4 §3 pin). | **Scatter/gather**: per-shard sub-scans over the boundary-split ranges, gathered by the coordinator continuation. CEP-era. |
| **Batches + batchlog** | Batchlog store/remove stay on Stage.MUTATION (`StorageProxy.java:1659/:1675` — the `:1995` overload's actual users); each batched mutation is a single-partition apply and routes per-key. | **Fan-out to owners**: batchlog write off-shard (multi-token system write), constituent mutations owner-routed; batchlog replay applies route per-key like any apply. |
| **MV updates** | MV-bearing tables EXCLUDED from routing by predicate (`updatesAffectView`; the Striped-lock sleep-retry loop `Keyspace.java:~516-529` must never run on a shard thread — phase-4 §2 pin). | **Owner-forwarding**: base mutation applies on the base key's owner shard; generated view mutations are message-passed (existing view-mutation machinery) to *their* keys' owners. Within one owner, base/view ordering is trivial — this is what dissolves writeOrder's global-for-MV rationale (`Keyspace.java:100-101`) and eventually the Striped lock (`ViewManager.java:69`). |
| **Counter RMW** | Stays on COUNTER_MUTATION stage with its Striped lock (`CounterMutation.java:73`) — the lock-then-read RMW is not routable without owning the whole sequence. | **Owner-forwarding**: the owner shard serializes read-modify-write for its keys; the Striped lock becomes redundant on the routed path (kept as the fallback guard). CEP-era. |
| **Paxos v1+v2 (first-class, not detail)** | Entirely excluded from shard routing AND from I3's async conversion (v1 latches lack the async-timeout reaper coverage; contention sleeps `StorageProxy.java:643/:710/:749`). PoC measures non-SERIAL workloads. | **Owner-forwarding per round**: `PaxosState` is (key,table)-keyed (`PaxosState.java:94` ACTIVE map, `Key` at `:154`) — prepare/propose/commit for a key each land on its owner shard, making the PaxosState lock owner-serialized; the commit's mutation apply routes like a plain write (today `PAXOS_COMMIT_REQ.stage.maybeExecuteImmediately` at `StorageProxy.java:933`). Each round keeps its own replica RTT — the pattern is per-round owner-forwarding, not scatter/gather. Precedent: the 2016 POC needed a dedicated `PaxosWriteTask` distinct from the plain write task; expect the same distinct task type. CEP-era. |
| **Hints (failure-mode sibling)** | **Fallback-covered, NOT routed** (reclassified per review — D4's five points stand): hint *storage* stays off-shard (HintsStore is per-host, multi-token, own file machinery); hint *delivery* applies arrive replica-side via HINT_REQ → Stage.MUTATION (`Verb.java:206`; `hint.applyFuture()` `HintVerbHandler.java:110`) plus the dispatching node's local/rehint corner (`HintsDispatcher.java:284`) — neither is a D4 routing point, so delivery applies hit the owner-check tryLock fallback. Deliberate: delivery is bursty background recovery traffic off the latency path, and off-shard puts don't touch `misroutedPuts` (§1's definition), so the bursts don't pollute D6's discriminator. Local deadline→hint conversion preserved verbatim inside `LocalMutationRunnable`. | Same classification; promote HINT_REQ to the I5 allowlist only if delivery-burst lock contention ever shows in profiles. |
| **Legacy 2i index maintenance (added per review)** | **Legacy-2i-bearing tables EXCLUDED from routing by predicate**: `CassandraIndex.insert` applies a mutation to the index CFS inline on the applying thread, keyed by the indexed VALUE (`CassandraIndex.java:536-547`) — a different token than the base key, so on a shard thread every index-memtable put would be systematically off-owner, and `indexCfs.apply` carries the full write-path blocking budget (§3 item 3). | **Permanently excluded** — value-token ≠ base-token means owner-forwarding is not natural, and SAI (routable: in-memory parallel structures, no memtable write) is the indexing path the routed world targets; message-passing legacy index updates to the value-token owner is possible but not worth building for a deprecated-in-practice index. |

DECISION: two dispatch patterns only — scatter/gather for multi-shard reads,
owner-forwarding for everything keyed — and the PoC exclusion list above is the routing
predicate's definition, **normative for phase-4 §1's `MutationShardRouting`** (§9):
views, counters, CDC tables, legacy-2i-bearing tables, paxos, batchlog, range reads,
local-system keyspaces, non-sharded memtables; hints excluded by arrival path (no
routing point), commitlog batch/group sync disables routing globally (§3 item 4).

---

## 6. Load-skew stance — DECISION D6

Weisberg's on-record 10994 concern: "load skew especially temporal skew" — with static
ownership one slowed shard eventually holds all outstanding requests. The paper gives
the mechanism a citation instead of a re-derivation: shared-nothing's throughput
ceiling is the hot core (Enberg §III.A; consequence C4).

**DECISION: strict ownership.** No work-stealing in the PoC or the initial CEP scope,
for read-only work included. Rationale:
1. The two 2016 fear-sources are already mitigated structurally: maintenance stays
   off-shard (no compaction stealing a shard's core — §3), and the small I/O pool
   removes the worst per-request blocking source (device-latency misses — §3 item 5)
   from shard threads entirely.
2. Correctness never depends on ownership (owner-check fallback, §1), so a future
   stealing scheme is an additive optimization, not a redesign. The hot-shard
   splitting lever (corrected per review — raising N is arithmetically inert once
   N ≥ a table's shardCount, since `getShardForKey % N` becomes the identity): the
   per-table **`shards` memtable option** raised above the default splits the hot
   table's token range across more shard threads, taking effect at the next memtable
   switch when boundaries re-pin — a per-table config action, no restart.
3. Stealing read-only work is the only safe class (writes would reintroduce
   multi-writer), and reads' worst blocking already left the shard.

**Detection metric (named):** each shard executor's **ThreadPoolMetrics PendingTasks
gauge** (free via `withJmx`, expected-changes §3.1 item 4) is the leading indicator —
under skew the hot shard's gauge diverges from the median; **`misroutedPuts`** (new
TrieMemtable counter beside contended/uncontended, `TrieMemtable.java:553-563` metrics
site) is the boundary-health check that distinguishes skew from stale routing. The
discriminator is valid because of §1's definition: only on-shard-thread owner-check
failures count — off-shard writers (hint bursts, replay, repair) and excluded-table
traffic can never inflate it, so a flat line genuinely means boundaries are healthy.

**Revisit trigger (stated observation):** any accepted A/B cell failing its p99 gate
where the failure is shard-attributable — hottest shard's PendingTasks sustained
(> 1 s) at ≥ 10× the median shard while other shards have idle capacity and
`misroutedPuts` stays flat. That observation reopens this section with **bounded
stealing for read-only work** as the pre-identified remedy (writes stay strictly
owned regardless).

---

## 7. Accord coexistence — DECISION D7 (the CEP end-state choice)

Context (facts from findings-accord-extension-points.md, carried per expected-changes
§3.1 item 3): all Accord protocol verbs run `Stage.IMMEDIATE` on messaging threads
(`Stage.java:58`; `Verb.java:326-383`) but IMMEDIATE does routing only — execution
happens on AccordExecutor threads. Accord already runs token-range-partitioned command
stores (`command_store_shard_count` default = P, `AccordService.java:477`;
`AccordConfig.java:499`) with a non-default `THREAD_PER_SHARD` mode
(`AccordConfig.java:158` default = `THREAD_POOL_PER_SHARD`). Its final memtable apply
runs on AccordExecutor threads through the SAME `Keyspace.applyInternal`
(`TxnWrite.java:150` → `mutation.apply(false,false)`); no double-logging
(makeDurable=false — AccordJournal is the WAL); it starts global writeOrder groups
(`CassandraKeyspaceWriteHandler.java:47`, and from its own system-table writes,
`AccordKeyspace.java:361`); the two shard maps use DIFFERENT splitters today
(EvenSplit-per-keyspace vs size-weighted local-range splits); migration-mode tables
have two writer populations by design (`splitMutationsIntoAccordAndNormal`).

The three-way menu:
- **(a) inbox-route** Accord's local applies/key-reads through TPC shard inboxes;
- **(b) align the shardings** — command-store splits use `ShardBoundaries`, pin
  executor:store 1:1 onto shard threads (the shard thread IS the command-store thread);
- **(c) exempt** accord-managed tables from single-writer claims (status quo; the
  owner-check fallback absorbs Accord writers).

**PoC pragmatics (pinned, restated):** the Phase 4 workload runs on non-Accord tables;
**(c) is the PoC's de facto state.** No Accord code changes in Phase 4.

**DECISION — recommended CEP end-state: (a) inbox-route, with (b) recorded as a
possible later optimization.** Justification:
1. **(c) is not an end-state.** CEP-15 adoption is growing; exemption means the tables
   the project's future runs on keep multi-writer memtable shards and global-writeOrder
   participation — TPC's wins evaporate exactly where they will matter most, and
   migration-mode tables (two writer populations) stay permanently multi-writer.
2. **(a) fixes two live Accord bugs-in-waiting, an argument in Accord's own favor:**
   `mutation.apply` on an AccordExecutor thread can block on memtable-pool exhaustion
   (`MemtableAllocator.java:170-198` — `hasRoom` WaitQueue park) and on the MV-lock
   `Thread.sleep(10)` retry loop (`Keyspace.java:~516-529`) — both violate
   AccordExecutor's own documented no-blocking contract (`AccordExecutor.java:119-122`,
   verified in tree). Inbox-routing moves those stalls off Accord's threads. This is
   the argument to lead with when Accord's authors review the CEP.
3. **(a) is minimally invasive to Accord:** the protocol engine, journal, cache, and
   executor structure are untouched; only the terminal storage touches change —
   `TxnWrite.applyDirect`'s `executor.chain(() -> mutation.apply(false,false))`
   (`TxnWrite.java:150`) submits to the owner shard's inbox instead (it is already an
   async chain — the extra hop composes; one wake per apply is the priced cost, §1
   idle-strategy note), and `TxnNamedRead` local *key* reads route to the owner
   (range reads stay on Accord threads — reads don't violate single-writer and the
   shared read structures are lock-free). Accord applies use the SAME routing
   predicate as plain writes; non-routable cases (views, CDC, boundary races) fall
   back to today's behavior under the owner-check guard — including during CEP-15
   migration windows, where inbox-routing both populations restores single-writer for
   migrating tables (closes findings-accord adversarial Q4).
4. **(b) is rejected as the committed end-state, kept as an optimization:** it couples
   the two programs hard — it requires Accord to adopt `ShardBoundaries`' splitter
   (per-table, epoch-cached, memtable-pinned lifecycle) in place of its per-keyspace
   EvenSplit, re-derives Accord's thread/cache budgets (~2P Accord threads today;
   AccordCache sliced per executor), and lands squarely in CEP-15's evolving core
   while CEP-15 is still moving (risk register: watch trunk). The 1:1 thread identity
   also can't be the clean identity it appears: boundaries differ per table and per
   epoch, so "the shard thread is the command-store thread" still needs per-key
   re-mapping at apply time — i.e., most of (a)'s machinery anyway. If, post-(a),
   profiles show the apply hop dominating Accord latency AND CEP-15's sharding has
   stabilized, (b) becomes an incremental follow-on (the inbox submit becomes a no-op
   when producer == owner), not a fork in the road.

**Shared state Accord touches on the write path, and its fate under (a):**

| State Accord touches | Today | Fate under (a) |
|---|---|---|
| `Keyspace.writeOrder` group start (`CassandraKeyspaceWriteHandler.java:47` via `TxnWrite.java:150`; also `AccordKeyspace.java:361` for its system tables) | Started from AccordExecutor threads — a foreign group-starter population for per-shard writeOrder | User-table applies start groups ON the owner shard → shard attribution is native. Accord system-table writes (non-routed) use the standing non-shard-writer rule: `currentShardId()` else token-hash%N else threadId%N for token-less empty contexts (`createEmptyContext`/`createContextForRead` — design-hostiles §0 rule 3; `createContextForRead` is per-read on 2i-indexed reads, not rare), carried by the `OpOrder.Group` owner field (I4 pin; design-hostiles §1.2) |
| TrieMemtable per-shard lock (HOSTILE #3) | Contended from ~2P Accord threads via the same `cfs.apply` | Applies arrive on the owner → lock-free fast path; fallback self-heals residual cases |
| Memtable `MEMORY_POOL` allocation (HOSTILE #4) | Can PARK an AccordExecutor thread (`MemtableAllocator.java:170-198`) — contract violation | Stall moves to shard threads — where it is enumerated and handled once for ALL writers by the hostiles design (C5 budget), and off Accord's threads |
| CommitLog (HOSTILE #2) | NOT touched (makeDurable=false; AccordJournal is the WAL, own fsync cadence inheriting `commitlog_sync_period`) | Unchanged. AccordJournal stays off-shard with its own threads; two-WALs-one-device interference and journal-replay-vs-N-shard-commitlog-replay ordering go to the risk register / I4 replay design, not this choice |
| MV Striped lock sleep loop | Runs on AccordExecutor threads for MV-bearing tables | MV tables are non-routable by predicate → falls back to today's path off-shard; end-state owner-forwarding (§5) retires it for all writers at once |
| ChunkCache/BufferPool on txn reads (`TxnNamedRead`) | Touched from Accord threads | Key reads route to owner shard (same seams as I2); range reads stay on Accord threads against the lock-free read structures — no single-writer implication |

Residual risk after (a) (tracked in effort.md's register): Accord thread-budget
re-derivation under TPC (2P + N on P cores), CEP-15 code drift under the program,
CEP-45 mutation tracking not yet in tree.

---

## 8. Fates and hand-offs (acceptance closure)

### 8.1 Shared-state fates — every spec §2.2 row

| §2.2 row | Fate | Where decided |
|---|---|---|
| `Keyspace.writeOrder` global OpOrder (HOSTILE #1, `Keyspace.java:102`) | **Owned-by-shard**: N per-shard orders behind a `ShardedOpOrder`; barriers become composite over N; shard identity is a final owner back-reference on `OpOrder.Group`, set at both construction sites (`OpOrder.java:97,399`) — additive package-internal change; the public `Memtable.accepts` signature (`Memtable.java:375`) and `CassandraWriteContext` unchanged (design-hostiles §1.2); MV's global-order rationale dissolved by owner-routing (§5). Non-shard writers: currentShardId else token-hash%N else threadId%N (token-less contexts) | design-hostiles (3.3), shape fixed here |
| CommitLog `allocatePosition` CAS + synchronized rollover + 1 sync thread (HOSTILE #2, `CommitLogSegment.java:103,242-257`) | **Owned-by-shard**: option (a) N per-shard segment managers — replay FILE machinery unchanged, but the coverage protocol requires manager-banded segment ids (per-manager counters in disjoint id bands) + per-manager memtable bound vectors + N per-manager intervals in the existing IntervalSet sstable metadata; discard becomes a per-manager fan-out keeping today's `contains` terminator (design-hostiles §2.2-i — supersedes findings-i4-i5 RESOLVED-1's "replay works unchanged iff the global static allocator is shared" as incomplete: file-sort survives, coverage does not); size cap stays a **shared global atomic**; ONE sync service iterating N managers; failure policy stays global. Sub-decision for design-hostiles (per review): N managers ⇒ N per-manager `SimpleCachedBufferPool`s — segment-buffer memory multiplies by N, budget it | design-hostiles decides formally; evidence (incl. the paper's cross-core-hop cost against option (b)) already forces (a) |
| SEP scheduler ↔ shard mismatch (HOSTILE #3, `Stage.java:47,232-238` vs `TrieMemtable.java:513,553`) | **Removed by this design** — routing to owner + owner-check fast path; lock retained as fallback (§1) | this doc, D1 |
| Global `MEMORY_POOL` + shared per-memtable allocator + 1 cleaner (HOSTILE #4) | **Split**: allocator **owned-by-shard** (one per MemtableShard — removes sibling `Region.nextFreeOffset` CAS); pool accounting **stays-global-atomic** (`MemtablePool.java:155` SubPool CAS; shard-local slack batching only if measurement demands — step 2 conditional); cleaner **stays global** (rare) | design-hostiles (3.3) |
| ChunkCache/BufferPool/CacheService (HOSTILE #5) | **Stays-global-atomic, deferred-with-rationale**: least-bad (Caffeine striped + FastThreadLocal fronts, `BufferPool.java:172-183`); row-cache-invalidation-on-write decoupling deferred. Arm B addendum: per-shard/NUMA sharding of the expanded ChunkCache is a CEP-era option (§2) | this doc, D2/D3 |
| MV/counter Striped locks (`ViewManager.java:69`, `CounterMutation.java:73`) | **Message-passed (end-state)** via owner-forwarding, locks retained as fallback guards; PoC: excluded from routing, unchanged | this doc, D5 |
| Tracker view updates (`Tracker.java:100,171-189`) | **Stays-global-atomic (per-table, rare)** — flush/compaction only, both off-shard | this doc, D3 |
| Schema/TCM/metrics/tracing/monitoring | **Stays-global-atomic**: immutable TCM snapshots, thread-local metrics — already TPC-friendly; not blockers | this doc |
| Per-table `readOrdering` OpOrder (added per review — the raw sweep row spec §2.2 dropped; read-path twin of HOSTILE #1: `ColumnFamilyStore.java:299`, started for EVERY read in `ReadExecutionController.forCommand` `:137,:145-146` — the 2i case starts base+index groups; barrier users `CFS.java:1442,:3366`) | **Stays-global-atomic (per-table) for the PoC, deferred-with-rationale**: contention is diluted per table (unlike writeOrder's single node-global instance), and reads take one group per query, not per chunk. CEP-era: rides the SAME ShardedOpOrder + composite-barrier machinery I4 builds for writeOrder if read-path profiles show the CAS. I2 reschedule lifecycle stated in §3 item 5: groups never cross threads — aborted attempt closes its controller on the shard thread, pool attempt opens a fresh one | this doc, D3; machinery in design-hostiles |

### 8.2 Hand-off points — every §2.1 hop mapped

| # | Hand-off (findings-arrival-topology summary) | Fate |
|---|---|---|
| 1 | native loop → NTR (`Dispatcher.java:131`) | **KEPT** — the routing ceiling (§0); token unknowable pre-bind. NTR shrinks under I3 (its raison d'être — parked RTT threads — goes away); removal requires the out-of-scope shard-aware protocol |
| 2 | NTR → outbound messaging loop (`OutboundConnection.java:336`) | **KEPT** — netty stays (D3); enqueue is any-thread-safe |
| 3 | NTR → READ/MUTATION local (`AbstractReadExecutor.java:168`, `StorageProxy.java:1918→2025`) | **REPLACED for routable traffic** by shard-inbox submit (I1/I2, D4); the §5 exclusion list keeps flowing through `maybeExecuteImmediately` at the same sites |
| 4 | coordinator BLOCKS on Condition (`ReadCallback.java:71,126`; `AbstractWriteResponseHandler.java:83,137`) | **REMOVED** — I3 continuations (design-async-coordinator); the parked thread is already redundant as a timer on the two hot paths |
| 5 | replica loop → READ/MUTATION stage (`InboundMessageHandler.java:429`) | **REPLACED** for the I5 allowlist (small MUTATION_REQ / single-partition READ_REQ) by loop→shard-inbox; **KEPT** for everything else (large messages, range, paxos, counters, batch, Accord, responses) |
| 6 | replica stage → outbound loop (`MessagingService.send`) | **KEPT** — send is thread-safe from shard threads (verified for I1's ack path, phase-4 §2) |
| 7 | coordinator loop → REQUEST_RESPONSE → callback (`ResponseVerbHandler.java:62-86`) | **KEPT** — the continuation-completion site, decided in design-async-coordinator §1 as a SPLIT terminal: write completions inline on the acking thread (RR for remote acks — the cache-hot argument banked), read terminal materialization dispatched to the park-licensed requestExecutor (SRP/RFP/close-time-repair parks are unconvertible in PoC scope) — one RR→requestExecutor hand-off this table's original sketch didn't show; under full TPC the same promises complete into shard inboxes (executor swap, not redesign) |
| 8 | NTR → native loop response flush (`Dispatcher.java:490`) | **KEPT** — Flusher is thread-safe from arbitrary threads (CLQ + CAS-scheduled drain); under I3 the *producer* changes (completion thread, not NTR); FlushItem refcount audit is I3 step 0 |

Net: of 8 hops, 2 removed/replaced on the hot path per request (3, 4), 1 partially
replaced (5), 5 kept by decision with reasons — plus the one new inbox hop per request
that the §0 ceiling makes explicit and permanent within this design's scope.

---

## 9. Buildability statement

This document's decisions instantiate phase-4 expected-changes §1's I0 —
`ShardExecutors` (node-global N `sequential()` executors, owner registry,
shutdown-before-commitlog) + `MutationShardRouting` (per-(table,key) shard-id from the
current memtable's pinned boundaries, −1 for the §5 exclusion list) — **with one
amendment this doc makes normatively (per review E1): phase-4 §1's predicate spec must
add the `cdc=true` and legacy-2i exclusions (§3 item 4, §5), plus the
`commitlog_sync=periodic` startup requirement; §5's exclusion list is the predicate's
source of truth, to be folded into phase-4 §1 at I0 spec time.** Nothing here
requires machinery the PoC inventories don't already specify; the two additions this
doc places (small I/O pool with its reschedule seam; UserCodeEscape pool) are both
plain executors behind existing seams (`ChannelProxy.read`;
`SecondaryIndexManager`/`TriggerExecutor` call sites). Arm A/arm B divergence is
confined to the I/O mode at the I2 seam and ChunkCache sizing — the shard model ships
identical bytes for both (§2), which is what makes the Phase 4 A/B a fair adjudicator.
