# Phase 4 — Expected Code Changes (I1–I5 + shard runtime)

Companion to `spec.md`. Class-level change inventories verified against tpc-migration
@ 50ddce8455 (sweeps 2026-07-07; full evidence in `findings-i1-mutation-apply.md`,
`findings-i2-i3-reads-coordinator.md`, `findings-i4-i5-commitlog-dispatch.md`, and
`../phase-3-execution-model/findings-accord-extension-points.md`). Each increment's 4.2
implementation spec starts from its section here; decisions marked PINNED need no
further discussion, those marked DESIGN inherit from the phase-3 docs, those marked
USER are in the consolidated list (§8).

Paths under `src/java/org/apache/cassandra/` unless noted.

## 0. Program-wide pins

- **Flags** (all `CassandraRelevantProperties` enum entries — checkstyle forces this;
  read once into `static final`, NOT hot-swappable: hot-swap creates mixed-writer
  windows): `cassandra.mutation.shard_routing` (I1),
  `cassandra.mutation.shard_routing.skip_lock` (I1 step 2), `cassandra.tpc.shard_reads`
  + `cassandra.tpc.uring_reads` (I2 — two flags so scheduler effect and I/O effect
  A/B separately), `cassandra.tpc.async_coordinator` (I3),
  `cassandra.tpc.sharded_commitlog` + `cassandra.tpc.sharded_write_order` (I4),
  `cassandra.tpc.inbound_shard_dispatch` (I5).
- **PoC bench-config pins** (violating any invalidates an A/B): `memtable: trie`
  (trunk default is SKIPLIST — unsharded, no shard routing possible;
  `conf/cassandra.yaml:820-825`, `MemtableParams.java:99`); `commitlog_sync: periodic`
  (batch mode blocks the applying thread on fsync — `CommitLog.java:337`);
  `disk_access_mode: standard` (default `mmap_index_only` leaves index reads as page
  faults; `mmap` gives the ring nothing; `direct` rejected at startup —
  `DatabaseDescriptor.java:675-693`); workload tables: non-Accord, no MVs, no 2i, no
  counters.
- **Correctness bar per D10:** unit tests + in-JVM dtests flag-on. Note the I5 dtest
  seam requirement (§6) — without it "dtests pass flag-on" is vacuous for I5.

## 1. I0 — shard runtime foundation (new; shared by I1/I2/I5)

Ships inside I1's patch but is its own gated step (spec.md 4.2): no flag, no A/B —
inert until routed to; gate = tests green + shutdown-before-commitlog ordering.

| File (CREATE) | Responsibility |
|---|---|
| `concurrent/MutationShardExecutors.java` (working name; serves reads too — consider `concurrent/ShardExecutors.java`) | Node-global singleton. N = `AbstractShardedMemtable.getDefaultShardCount()` (= cores) single-thread executors via `executorFactory().localAware().withJmx("request").sequential("Shard-"+i)` — buys ExecutorLocals propagation, thread naming, ThreadPoolMetrics JMX free. Owner registry (`Thread[] owners` set by thread factory). API: `static boolean enabled()`, `static void execute(int shardId, Runnable)`, `static boolean isOwner(int shardId)`, `static int currentShardId()` (−1 off-shard). Shutdown drains BEFORE commitlog stops (wire like `Stage` shutdownBeforeCommitlog, `Stage.java:163-184`). |
| `db/MutationShardRouting.java` | Routing predicate + shard computation: `static int shardIdFor(Mutation)` → −1 when not routable. Checks: every updated table's current memtable is `AbstractShardedMemtable`; boundary agreement across tables (per-table `shards` option can differ); `!viewManager.updatesAffectView(mutation, false)`; not local-system keyspace; **not `cdc=true`** (CommitLog.add runs inline in beginWrite — `throwIfForbidden` can block/throw on CDC space, design-target §3 item 4); **no legacy-2i (`CassandraIndex`) on any updated table** (index puts are value-token = systematically off-owner + full write-path blocking budget, design-target §5). Plus a **startup check: routing flag-on requires `commitlog_sync: periodic`** (batch/group park the shard thread inside add — flag-on+batch disables routing with a log line, design-target §3 item 4). Per-(table,key): `shardId = memtable.getShardBoundaries().getShardForKey(key)` mapped `% N`. design-target §5's exclusion list is this predicate's normative source (folded 2026-07-09 per design-target §9). |

PINNED: node-global N threads (boundaries are per-table — a global boundary object is
impossible; findings-i1 §2); `sequential()` executors for the PoC, custom
MPSC+ring-driving loop arrives with I2 (the loop must also drive the io_uring ring);
unbounded inboxes are backpressure PARITY with today (MUTATION SEP queue is unbounded;
permits bound concurrency, not depth). Evolution note: when I2 lands, the shard loop
becomes drain-inbox + drive-ring + run-continuations; `sequential()` is replaced then,
not before.

## 2. I1 — shard-routed mutation apply

**Modify**

| File | Change |
|---|---|
| `config/CassandraRelevantProperties.java` | 2 enum entries (near `:407`, `MEMTABLE_SHARD_COUNT` precedent). |
| `service/StorageProxy.java` | At `sendToHintedReplicas` local branch `:1918` (NOT the `:1995` overload — that's batchlog; spec citation corrected): flag-on && `stage == Stage.MUTATION` && `shardIdFor(mutation) >= 0` → `ShardExecutors.execute(shardId, localMutationRunnable)`; else existing `performLocally`→`maybeExecuteImmediately` (`:2023-2066`, mEI at `:2025`). `LocalMutationRunnable` reused UNCHANGED — its deadline→hint logic lives inside `run()` (`:3184-3204`), reproduced for free. Batchlog `:1659/:1675`, paxos `:933` untouched. |
| `db/MutationVerbHandler.java` | `applyMutation` (`:80-84`): flag-on && routable → submit existing body to shard executor. `MessageParams.capture()` stays pre-submit (`:82` — already captured before apply today). Ack via `applyFuture().addCallback` completes on the applying (= shard) thread — verified safe (MessagingService.send + handler.onResponse thread-safe; no cross-mutation ack ordering contract). PINNED: add a dequeue-time expiry re-check mirroring `:54-59` (cheap; the routed task adds a second queue). |
| `db/memtable/AbstractShardedMemtable.java` | Add `public ShardBoundaries getShardBoundaries()` — field is `protected`, no getter today (`:53`). |
| `db/memtable/TrieMemtable.java` | Step 2 (skip_lock flag): pass shard index into `MemtableShard` ctor (`:133-143`, `:541-548`); in `MemtableShard.put` (`:550-596`): `if (SKIP_LOCK && ShardExecutors.isOwner(shardIndexComputedFromTHISmemtable'sBoundaries))` → lock-free path (owner-thread assert) else existing tryLock path (`:553-563`). Increment `misroutedPuts` on fallback-when-flag-on. |
| `metrics/TrieMemtableMetricsView.java` | Add `misroutedPuts` counter alongside contended/uncontended (`:41-59`). |

PINNED decisions: **owner-check-with-lock-fallback, not unconditional lock deletion** —
single-writer is violated today by hints, read-repair, paxos commit, commitlog replay,
counter/view applies, and Accord (`TxnWrite.java:150` writes user tables from
AccordExecutor threads on this branch NOW); the fallback is self-healing for all of
them and for every boundary race (epoch bump, memtable switch — two live memtables with
different boundaries can accept writes simultaneously). The lock is provably the ONLY
thing writer-exclusion protects (InMemoryTrie is single-mutator-multi-reader by design,
`InMemoryTrie.java:39-44`; readers/flush/OpOrder never take it). Routing predicate
excludes views (nested synchronous view apply + Striped-lock sleep-retry
`Keyspace.java:459-548` must never run on a shard thread), local-system keyspaces,
non-sharded memtables; counters/batchlog/hints/paxos are excluded structurally by call
site. Blocking `Keyspace.apply` callers are never routed-and-awaited (CASSANDRA-12689
deadlock class).

Micro counters (JMX): `org.apache.cassandra.metrics:type=TrieMemtable,keyspace=<ks>,
scope=<table>,name={Uncontended memtable puts, Contended memtable puts, Contention
timeLatency}` + new misroutedPuts. Honest-baseline note for the increment spec: today a
local apply can run INLINE on the NTR thread (zero hops) when a permit is free; routing
always costs one hop — that's what the p99 gate measures.

USER: N oversubscription (shard threads on top of SEP pool — reduce `concurrent_writes`
flag-on?); step-2 semantics if a hard "no lock at all" measurement variant is wanted
(needs workload preconditions as policy).

## 3. I2 — shard-routed local reads + per-shard ring

**Create:** `io/uring/*` arrives from Phase 1 unchanged. Shard loop upgrade (see §1
evolution note).

**Modify**

| File | Change |
|---|---|
| `service/reads/AbstractReadExecutor.java` | `:168` (`makeRequests`): flag branch — `command instanceof SinglePartitionReadCommand` → shard executor via `cfs.localRangeSplits(...)` boundaries (SAME source as I1 — dependency edge); else existing `Stage.READ.maybeExecuteImmediately`. `cfs` in scope at `:77`. |
| `service/reads/repair/AbstractReadRepair.java` `:102`, `service/reads/ShortReadPartitionsProtection.java` `:191`, `service/reads/ReplicaFilteringProtection.java` `:175` | Same branch (PINNED: route all four — same partition key ⇒ same shard; keeps every local read for a key on its owner). |
| `io/util/ChannelProxy.java` | `read(ByteBuffer, long)` `:169-180`: `UringRing r = UringRings.threadLocal(); if (r != null) return r.readSync(fd, position, buffer); else channel.read(...)`. Cache the fd at proxy construction (one reflective `getfd` per channel, not per read). Fallback covers compaction/streaming/validation/internal-query callers — threads with no ring return null. |

PINNED: **seam = ChannelProxy.read** (`:174` is the single choke point all three
non-mmap readers funnel through — SimpleChunkReader `:41`, CompressedChunkReader
`:413/:429`; also covers the no-cache BufferManagingRebufferer path). The decorator
alternative (UringChunkReader in `FileHandle.Builder.complete`) is recorded as rejected:
misses the no-cache path, one more class, same effect. Cache-miss loads already run on
the calling thread (Caffeine `ImmediateExecutor.INSTANCE`, `ChunkCache.java:152`) — the
thread-local ring works with zero ChunkCache changes. Range reads
(`RangeCommandIterator.java:223`) stay on Stage.READ — a cross-shard scan on one shard
thread would serialize behind point reads. Explicitly untouched: cache-warming
(`CacheService.java:383/:412`), Accord interop reads (`AccordInteropRead.java:289,304`),
replica-side verb reads (I5's job), internal queries (inline, never Stage.READ — hit
the seam via fallback).

USER (bench-shaping): chunk-cache size and compressed-vs-uncompressed table for the I2
A/B (both change miss cost); single-node rig note — coordinator==replica means I2 covers
the benchmark hot path even before I5.

## 4. I3 — non-blocking coordinator

**Create**

| File | Responsibility |
|---|---|
| `service/AsyncRequestCompletion.java` (name TBD) | Owns: captured request `ExecutorLocals` + `RequestTime` + channel/FlushItemConverter. `complete(Response)`/`completeExceptionally(Throwable)`: install locals, attach warnings (relocates `Dispatcher.java:438/:466/:473`), build FlushItem, `Dispatcher.flush` (thread-safe from any thread — Flusher CLQ + event-loop drain, `Flusher.java:118-140`), record ClientRequestMetrics + QueryEvents (moves off NTR or it reports dispatch-only latency). |
| Outstanding-ops limiter | Counter + limit at `Dispatcher.dispatch`/`ClientResourceLimits` — replaces the dead `Overload.QUEUE_TIME` signal (NTR queue never builds under I3). This is I3's micro counter (with NTR utilization). |

**Modify**

| File | Change |
|---|---|
| `service/reads/ReadCallback.java` | Add future alongside/replacing `condition` (`:71`); complete where `signalAll()` fires (`:242`, `:281`); `awaitResults` body (`:134-214`) becomes `verifyAndThrow()` callable from the continuation. Blocking path retained flag-off. Timeout: the Callback-Map-Reaper ALREADY delivers async `onFailure(TIMEOUT)` (`invokeOnFailure()` → true, `:285-287`; `RequestCallbacks.java:149-158`) — the future completes exceptionally from it; local-replica-only requests never register callbacks → continuation carries its own deadline check. |
| `service/AbstractWriteResponseHandler.java` | Same at `signal()` `:359` / condition `:83`; `get()` `:130-194` body → completion function; write speculation `:430-464` → scheduled timer. `invokeOnFailure()` already true (`:403-406`). idealCL accounting already response/expiry-driven — untouched. |
| `service/StorageProxy.java` | `fetchRows` `:2649-2718` → per-command future chain (executeAsync → speculation timer → responses → digest-mismatch/repair compose `:451/:2704/:2620` → resolve); `mutate` loop (`get()` at `:1004`) → allOf. Flag-excluded paths keep blocking `get()` verbatim: Paxos (`:811/:826/:849/:859/:862/:923/:1056` + contention sleeps `:849/:962/:1115/:1164`), batch (`:1663/:1712`), counters (`:2082-2148` — Striped lock + read under lock, not convertible by composition), truncate (`:3084`). |
| `service/reads/AbstractReadExecutor.java` | `awaitResponses` `:424-460` / `awaitReadRepair` `:462-478` → future variants; speculation → timer. |
| `service/reads/repair/BlockingReadRepair.java` (+ BlockingPartitionRepair) | `awaitRepairsUntil` `:174` / `awaitRepairs` `:96-112` → composable future. REQUIRED, not optional: an unconverted repair await parks a REQUEST_RESPONSE thread — the starvation rule (only P threads; they deliver the very responses that unblock things). |
| `transport/Dispatcher.java` | `RequestProcessor`/`processRequest` `:300-320/:365-486` allow async completion; ClientWarn bracketing relocated; `hasQueueCapacity` `:356-359` → outstanding-ops; `native_transport_max_threads` becomes tunable-down in the A/B. |
| `transport/Message.java` | `Request.execute` (`:250-252`) gains async-capable variant; sync default for non-flagged message types. |

PINNED: read/write timeout machinery needs NO new timer (reaper discovery above).
ExecutorLocals capture is mandatory — REQUEST_RESPONSE runs with the replica message's
locals, not the request's (`InboundMessageHandler.java:429`); `MessageParams`
(FastThreadLocal) gets the same treatment. Failure aggregation is safe
(concurrent map + ImmutableMap snapshot `:185`). Monitoring/slow-query is
thread-migration-safe (approxTime, no thread capture).

DESIGN (from phase-3 3.2): continuation executor (strict-REQUEST_RESPONSE vs dedicated
completion pool); cut-line ratification. USER/pre-code task: **FlushItem/payload
release-refcount audit** when the completing thread isn't NTR (`Dispatcher.java:483`,
FlushItem.release) — the one unpinned piece; schedule as I3-spec step 0.

## 5. I4 — per-shard commitlog + writeOrder (+ allocator)

**DECIDED (design-hostiles §2, 2026-07-09): option (a) N per-shard managers.** The
inventory below is amended by design-hostiles' review-forced coverage protocol —
"unchanged replay for free" was INCOMPLETE (file-sort survives, coverage does not):
(a) requires **manager-banded segment ids + per-manager memtable bound vectors + N
per-manager intervals in the existing IntervalSet sstable metadata**; see
design-hostiles §2.2-i for the full design (single conservative CL-bound pair and
id-terminated discard both RETRACTED as silent-data-loss). Option (b) inventory kept
for the record only.

**Option (a) inventory**

| File | Change |
|---|---|
| `db/commitlog/CommitLog.java` | `AbstractCommitLogSegmentManager[] shardManagers` (flag-gated); `add()` picks manager by §0 attribution (design-hostiles §0: currentShardId → token-hash%N → threadId%N; first `add` is `persistLocalMetadata`, `CassandraDaemon.java:325`, before TCM — selection must not depend on ShardBoundaries); `discardCompletedSegments` fans out per-manager keeping TODAY'S `contains` terminator (design-hostiles §2.2-i — the id-terminated variant is retracted); no-arg `getCurrentPosition()` REMOVED → per-manager `getCurrentPosition(int)` with the caller disposition table (design-hostiles §2.2-i); per-band `replayLimitId` (`CommitLog.java:82`); `sync()`/`forceRecycleAll()` iterate N; `metrics.attach` aggregation (`:135`); `recoverSegmentsOnDisk` UNCHANGED (id-sorted union works — band-major order). |
| `db/commitlog/AbstractCommitLogSegmentManager.java` | Instantiable ×N; **shared size accounting** for the cap (`:105, :447-453` — otherwise N managers each assume the full budget = N× overshoot); `awaitNewBarrier` `:371` → composite barrier; one AllocatorRunnable per manager (N threads, acceptable for PoC). |
| `db/commitlog/CommitLogSegment.java` | Manager-banded id allocation replaces the shared static counter: `id = (managerIndex << 56) \| counter`, per-band seeding generalizing the `:83-92` scan (band 0 continues the legacy sequence — no migration); per-band `shouldReplay` filter (`:227-230`). Per-segment `allocatePosition` CAS + `appendOrder` KEPT (near-single-writer under routing; fallback guard). (Amended per design-hostiles §2.2-i — was "NO change".) |
| `db/commitlog/CommitLogSegmentManagerCDC.java` | CDCSizeTracker shared across instances. |
| `db/commitlog/AbstractCommitLogService.java` (+3 mode subclasses) | ONE sync service for all managers — sync thread iterates every manager's segments (same fsync count as today; N sync threads is a later, phase-2-data-informed option); waiter path unchanged (per-segment syncComplete already). |
| `db/commitlog/CommitLogArchiver.java`, `CommitLogReplayer/Reader`, tools | NO change. |
| `metrics/CommitLogMetrics.java` | attach-N variant, aggregating gauges. |
| `db/memtable/AbstractMemtableWithCommitlog.java` | **Per-manager bound VECTORS** (`commitLogUpperBound`/`lowerBound`/`approximate…` become N-slot arrays; `accepts`/`mayContainDataBefore` select the slot by the position's band — public signatures unchanged; flush seal loops slots per band). The previously-pinned single conservative pair is RETRACTED as silent-data-loss — full loss chain and design in design-hostiles §2.2-i; sstable flush records N intervals via the existing `IntervalSet.Builder` loop (no format change). |
| Failure policy | Stays GLOBAL (`handleCommitError`, `CommitLog.java:577-580`) — per-shard isolation would change semantics. |

**Option (b) inventory (for the record):** new `CommitLogWriterCore` (MPSC inbox,
allocate+memcpy, position via callback) + `CommitLog.addAsync` + async
`KeyspaceWriteHandler.beginWrite` contract + `Keyspace.applyInternal` restructure +
`CassandraWriteContext` carries future. Replay/CDC/archiver/cap/metrics untouched.
Costs: per-mutation cross-core hop on the latency path + write-path interface change I1
otherwise avoids.

**Per-shard writeOrder (both options)**

| File | Change |
|---|---|
| `db/Keyspace.java:100-102` | `writeOrder` → `ShardedOpOrder` wrapper (N orders): `start(shard)`, `newCompositeBarrier()`, `awaitNewBarrier()` = all-N. Flag-gated. |
| `utils/concurrent/OpOrder.java` | Composite-barrier support + **`Group` gains a final owner back-reference set at both construction sites (`:97,:399`)** — the earlier context-carrier pin is OVERTURNED (design-hostiles §1.2: `Barrier.isAfter` is per-instance group-id arithmetic, the group must self-identify; threading the context would break the public `Memtable.accepts` signature). `CassandraWriteContext` unchanged. |
| `db/CassandraKeyspaceWriteHandler.java` `:47,:107` | start on current shard's order; record shard in context. |
| `db/memtable/AbstractMemtableWithCommitlog.java` `:40,:55-62,:71-109` | writeBarrier → composite; `accepts` pairs opGroup with its own shard's barrier (`isAfter` is intra-order only). |
| Barrier sites | `ColumnFamilyStore.java:1247/:1260/:1271/:1285-86/:3365`, `AbstractCommitLogSegmentManager.java:371`, `index/internal/CassandraIndex.java:681`, `tcm/.../DistributedSchema.java:365` → composite. |
| `service/accord/AccordKeyspace.java:361` | Shard attribution for Accord-thread writes — **blocked on phase-3 3.1 item 6**. |

Non-shard-writer attribution rule (DECIDED, design-hostiles §0): `currentShardId()`
else token-hash%N else threadId%N for token-less empty contexts (covers startup,
replay Stage.MUTATION threads, 2i builds, `createContextForRead` — per-read on
2i-indexed reads, and Accord's CFK loader). Computed ONCE in `beginWrite`, shared by
the writeOrder start and the commitlog manager selection.

**Allocator step (HOSTILE #4, ships with I4 or as I4.5)**

| File | Change |
|---|---|
| `db/memtable/TrieMemtable.java` `:129,:140,:535` | One `MemtableAllocator` per MemtableShard. |
| `db/memtable/AbstractAllocatorMemtable.java` `:120,:167,:173,:190-191` | allocator → allocators[]; lifecycle/usage aggregation over N. |
| `utils/memory/MemtablePool.java` `:155` | UNCHANGED step 1 (global SubPool CAS remains — per-shard allocators only remove the sibling `Region.nextFreeOffset` CAS); step 2 slack-batching ONLY if measurement says the SubPool CAS matters. |

## 6. I5 — inbound shard dispatch

**Create:** `net/ShardInboundRouter.java` — allowlist {MUTATION_REQ;
READ_REQ iff `instanceof SinglePartitionReadCommand` (READ_REQ shares its serializer
with RANGE_REQ — `Verb.java:226-230`)}; token→shard; non-blocking offer; **on inbox
full → fall back to `stage.execute`** (existing capacity accounting stays the sole
back-pressure authority). Excluded with reasons: all `_RSP` (RequestCallbacks wakes
coordinator, no token), RANGE_REQ (multi-shard), PAXOS v1/v2 (synchronous handlers +
PaxosState locks; first-class separate path per 3.1 item 4), COUNTER_MUTATION_REQ
(synchronous lock + read on handler thread), HINT_REQ (multi-token payload, already
async), BATCH_*/TRUNCATE/VIRTUAL_MUTATION (multi-partition/rare), Accord verbs
(IMMEDIATE — 3.1 item 6's question).

**Modify**

| File | Change |
|---|---|
| `net/InboundMessageHandler.java` `:420-430` | In `dispatch`: flag-on && task is `ProcessSmallMessage` && router says route → `shardExecutor.execute(locals, task)`; else `stage.execute` `:429`. **Task object reused verbatim** — preserves expiry recheck (`:451-454`), InboundSink catch + failure response (`InboundSink.java:120-150, :108-118`), capacity release in finally (`:465-475`), callbacks. Large messages stay on Stage (token unknowable pre-deserialize; >64 KiB is off the hot path — threshold `OutboundConnections.java:72`). |
| `test/distributed/.../impl/Instance.java` `:582-595` | Call the SAME router when picking the executor. REQUIRED: in-JVM dtest delivery bypasses `InboundMessageHandler` entirely (verified — `receiveMessageRunnable` submits straight to `verb.stage.executor()`); without this seam, dtests pass flag-on without exercising I5. |
| Metrics | New per-shard inbox depth/dequeue-latency; doc note: MutationStage/ReadStage tpstats FREEZE flag-on — A/B uses messaging `internalLatency` (`MessagingMetrics.java:115-128`, still recorded) + inbox metrics. |

## 7. Measurement plan pins (feeds 4.1/4.2)

Per increment micro counter: I1 contended/uncontended/misroutedPuts (JMX
`type=TrieMemtable`); I2 ring ops/s + ChunkCache miss latency + shard backlog
(ThreadPoolMetrics PendingTasks per shard executor); I3 outstanding-ops gauge + NTR pool
utilization at reduced `native_transport_max_threads`; I4 commitlog
`waitingOnSegmentAllocation` + allocatePosition contention proxy (segment rollover
stalls) per manager; I5 inbox depth/fallback count + `internalLatency`. Macro:
cassandra-easy-stress per runbook methodology, flag-on/off same build, p99-gated.
Config pins in §0. Single-node caveat: coordinator==replica — I2 covers the hot read
path pre-I5; I5's effect only fully shows multi-node (rig is single-node: note in
poc-criteria.md, don't oversell I5's macro number).

## 7.5 TPC hotspots per increment (Enberg ANCS'19 — reasoning in `../findings-tpc-paper.md`)

Where the implementer must pay extra attention to actually collect TPC's benefit,
sourced from the closest published analogue (Sphinx KV store A/B on commodity Linux):

- **Cross-increment:** (i) *environment before architecture* — the paper's biggest
  tail lever was IRQ affinity + irqbalance, not the app design; every A/B records
  irqbalance state, NIC/NVMe IRQ affinity, taskset mask, or it's invalid. (ii) *the
  wake-up is the steering cost* (µs to unpark vs ns to enqueue) — measure
  enqueue→dequeue latency and unparks/sec from I0 day one. (iii) *never block a
  shard thread* — 8 shard threads have 4× less blocking absorption than 32 SEP
  workers; every increment answers "what can block here and for how long".
- **I0:** hand-off latency is the product; make it a first-class micro metric.
  Pinning: the paper's threads are pinned and *dedicated* — §8 item 5
  (oversubscription) should note that unpinned/oversubscribed cells put a floor on
  the demonstrable tail win, so a muted I1 number is a config consequence, not a
  program verdict.
- **I1:** expect the paper's low-concurrency crossover — the routed hop costs µs
  where uncontended `tryLock` costs ns; median may regress slightly and low-load
  tail almost certainly will. The claim is the *loaded* tail (no lock convoys, no
  SEP queue-time variance) — matches the existing p99-at-throughput gate. Add one
  low-load cell to characterize the crossover; don't let it gate.
- **I2a/I2b — the sharpest warning in the plan:** `readSync` on a shard thread
  blocks the whole shard for full device latency (~80–100 µs); a cache-miss storm
  serializes every queued op on that shard. The A/B needs a **cold-chunk-cache
  miss-storm cell** watching per-shard PendingTasks — backlog explosion is a
  hurdle-log entry, not a silent p99 number. Sync-per-thread also caps the ring at
  QD1 per shard: the ring's full value needs multiple in-flight reads per shard
  (continuations through the read path — out of PoC scope; say so in the I2b spec so
  nobody oversells it). Cheap intermediate if profiles justify: batch-submit
  adjacent read tasks from the inbox in one `enter`, reap together.
- **I3:** the outstanding-ops limiter bounds the tail under overload (their fig 3:
  tail grows monotonically with concurrent connections) — size it from the measured
  concurrency-vs-tail curve, not a constant. Continuation executor: cache-hot
  completion on REQUEST_RESPONSE vs one extra wake for a pool — phase-3 3.6.
- **I4:** I4c is Sphinx's per-thread log-structured allocator one-for-one (slab
  segments, whole-segment reclaim) — cite as prior art in the CEP. The residual
  global `SubPool.allocated` CAS is a shared-everything leak the paper predicts
  limits scalability eventually — step-2's measure-first condition is right; watch
  CAS retries/cache-line traffic on the allocation path.
- **I5:** message path today: NIC RX core → softirq core → netty loop → shard
  thread = up to 4 cores per message (paper complains at 3). I5 removes the Stage
  hop but *adds* the netty→shard wake — net win depends on wake cost and IRQ/loop
  affinity, which is why environment capture is load-bearing for I5's A/B
  specifically. Large-message Stage fallback aligns with their payload-copy finding
  — keep it. Shard-affine NIC steering is their "programmable NIC offload" future
  work = our deferred shard-aware client protocol (phase-5 defers).

## 8. Consolidated open questions (status updated 2026-07-09 — Phase 3 designs closed most)

1. ~~**Accord end-state**~~ **DECIDED** (design-target D7): CEP end-state = (a)
   inbox-route, (b) recorded as later optimization; PoC = (c) de facto, no Accord code
   changes in Phase 4.
2. ~~**Commitlog architecture**~~ **DECIDED** (design-hostiles §2): (a) N per-shard
   managers, with the banded-id + bound-vector coverage protocol.
3. ~~**I3 continuation executor**~~ **DECIDED** (design-async-coordinator §1): no new
   pool — split terminal (writes inline on the acking thread; read materialization +
   audit/FQL completions on requestExecutor), park guard as enforcement.
4. ~~**I3 cut-line**~~ **RATIFIED** (design-async-coordinator §2) with clarifications:
   IN/paging groups convert, unlogged plain batches convert (BatchMessage override),
   predicate is CL-aware (SERIAL/LOCAL_SERIAL reads behind the line).
5. **Shard-thread CPU budget** (STILL USER): N=cores on top of existing pools — accept
   oversubscription for the PoC, or shrink `concurrent_writes`/NTR threads flag-on?
   (Affects every A/B's honesty; recommend: accept for I1, shrink NTR in I3's A/B where
   it IS the claim.) **Gates 4.1, not 4.2** — must be pinned with the criteria before
   any increment code.
6. **I1 step-2 measurement variant** (STILL USER): owner-check skip only (recommended),
   or also a hard no-lock build with workload-precondition policy?
7. ~~**FlushItem/payload release audit**~~ **DONE** (design-async-coordinator §8):
   safe as-is; converts into three I3 step-0 build requirements (exactly-once promise
   completion, ops-release in cleanup consumers, idempotent slot handle +
   catch-around-encode).
8. **Foreground-read caching model** (STILL OPEN — adjudicated by Phase 4 A/B, both
   arms first-class per design-target D2; user prior 2026-07-09 = full-Scylla): buffered
   ring reads keeping the page cache (I2b default) vs DIO reads + expanded ChunkCache
   (Scylla model). PROVISIONAL — performance is the sovereign criterion; user: "if we
   nerf performance by still including buffered io then it isn't an option." Adjudicated
   by an I2b A/B variant cell on the PoC criteria; any DIO-compaction cell must measure
   read p99 ACROSS a compaction boundary (outputs go cache-cold at switchover). Raw
   asymmetry already priced by Phase 2: page-cache hit via ring ≈ 1.4 µs/op (syscall);
   userspace-cache hit = memory read, no kernel entry. Background writers (commitlog,
   compaction, flush, streaming, hints) target DIO in EITHER outcome — DIO+ring never
   punts to iou-wrk on any fs, so G3's ext4 punt concern applies only to still-buffered
   paths.
