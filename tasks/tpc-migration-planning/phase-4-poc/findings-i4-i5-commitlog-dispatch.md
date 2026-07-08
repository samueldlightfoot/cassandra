# Findings — I4 per-shard commitlog/writeOrder + I5 inbound dispatch (agent report, 2026-07-07)

Verbatim exploration report; inventories distilled into `expected-changes.md`. `C/` =
`src/java/org/apache/cassandra/`. Line numbers verified on tpc-migration 2026-07-07;
load-bearing ones re-verified by direct read.

## §1 — CommitLog write path today (I4)

**The chain:**
1. `CommitLog.add` — `C/db/commitlog/CommitLog.java:301-344`: thread-local scratch
   `DataOutputBuffer` :307, serialize :309, `segmentManager.allocate(mutation, totalSize)`
   :312, CRC32s into the allocated slice :314-327, `alloc.markWritten()` :334 (closes the
   segment's appendOrder group), `executor.finishWriteFor(alloc)` :337 (sync-mode wait),
   returns `alloc.getCommitLogPosition()` :338.
2. `CommitLogSegmentManagerStandard.allocate` — :47-60: reads `allocatingFrom()`, loops
   `segment.allocate(...)`; on null (segment full) calls `advanceAllocatingFrom(segment)`
   and retries.
3. `CommitLogSegment.allocate(Mutation,int)` — `C/db/commitlog/CommitLogSegment.java:203-225`:
   `appendOrder.start()` :205 (per-segment OpOrder, field :101); private `allocate(size)`
   :242-257 = CAS loop on `AtomicInteger allocatePosition` (field :103) with
   `LockSupport.parkNanos(1)` backoff :255 — verified verbatim. Marks tables dirty
   :215-216 (`tableDirty` NonBlockingHashMap :126). Returns `Allocation` (inner class
   :708-755) carrying opGroup + position + buffer slice.
4. **Rollover** — `C/db/commitlog/AbstractCommitLogSegmentManager.java`:
   `advanceAllocatingFrom` :297-337, `synchronized(this)` loop :300-316 installing
   `availableSegment` (volatile :83) as `allocatingFrom` (volatile :95) and adding to
   `activeSegments` (ConcurrentLinkedQueue :88); if none, `awaitAvailableSegment`
   :339-350 parks on `segmentPrepared` WaitQueue :85 under
   `metrics.waitingOnSegmentAllocation` :343. One background `AllocatorRunnable` per
   manager :165-241 creates segments ahead (:191, `segmentPrepared.signalAll()` :193),
   parks on `managerThreadWaitQueue` :111. Segment buffers from per-manager
   `SimpleCachedBufferPool` (:115; impl `C/io/util/SimpleCachedBufferPool.java:36-98`,
   bounded MpmcArrayQueue :63).
5. **Sync machinery** — `C/db/commitlog/AbstractCommitLogService.java`: one sync thread
   (`SyncRunnable` :158-225) — fields `lastSyncedAt` :62, `pending` AtomicLong :66,
   `syncComplete` WaitQueue :69, `haveWork` Semaphore :70. `finishWriteFor` :281-285 →
   abstract `maybeWaitForSync` :287. Modes (chosen `CommitLog.java:117-130`):
   - `PeriodicCommitLogService.java:36-45` — waiter only blocks if `lastSyncedAt` lags
     `blockWhenSyncLagsNanos` (**default mode does not wait for fsync per write**).
   - `BatchCommitLogService.java:36-43` — `requestExtraSync()` then `awaitDiskSync`.
   - `GroupCommitLogService.java:34-41` — `awaitDiskSync` without extra signal.
   - Waiter mechanics: `Allocation.awaitDiskSync` `CommitLogSegment.java:740-746` →
     `waitForSync(position)` :497-507 registers on **per-segment** `syncComplete`
     WaitQueue :123 until `lastSyncedOffset >= position`; sync thread's
     `CommitLogSegment.sync()` :305-374 (synchronized) waits in-flight appends via
     `waitForModifications` :294-298 (= `appendOrder.awaitNewBarrier()`), writes
     marker+flushes, sets `lastSyncedOffset` :367, `syncComplete.signalAll()` :372.
     `CommitLog.sync` :282-284 fans into `segmentManager.sync(flush)`.
6. **Disk access modes** — resolved in `C/config/DatabaseDescriptor.java:1842-1887`
   (`auto` → direct if SSD+supported, mmap otherwise; compressed/encrypted forces
   standard), validated :1889-1912. Builder selection
   `AbstractCommitLogSegmentManager.createSegmentBuilder` :123-145: `EncryptedSegment` /
   `CompressedSegment` (both extend `FileDirectSegment.java:34-84`) / `DirectIOSegment`
   (`C/db/commitlog/DirectIOSegment.java:46-147` — fsBlockSize/mask :48-49, aligned flush
   :111-134, CASSANDRA-21134) / `MemoryMappedSegment` (:41-115, msync :94).

**Every shared mutable structure:** per-manager: `activeSegments` :88, `allocatingFrom`
:95, `availableSegment` :83, `segmentPrepared` :85, `managerThreadWaitQueue` :111, `size`
AtomicLong :105 (`addSize` :436, cap check `unusedCapacity()` :447-453,
`maybeFlushToReclaim` :248-266), `SimpleCachedBufferPool` :115. Per-segment:
`allocatePosition` :103, `appendOrder` :101, `lastSyncedOffset` :109 /
`lastMarkerOffset` :115, `syncComplete` :123, `tableDirty` :126 / `tableClean` :129.
Per-service: `pending` :66, `lastSyncedAt` :62, `haveWork` :70. **Static/global:**
segment-id generator `idBase`/`replayLimitId`/`nextId` `CommitLogSegment.java:70-92`
(`getNextId()` :145 = `idBase + nextId.getAndIncrement()`, assigned :155);
`CommitLog.instance` static (:80); `CommitLogMetrics` (`C/metrics/CommitLogMetrics.java:38-50`;
`attach(executor, segmentManager)` :60-82 wires exactly one of each, `CommitLog.java:135`).

**Position → memtable coupling:** `CassandraKeyspaceWriteHandler.beginWrite`
(`C/db/CassandraKeyspaceWriteHandler.java:42-65`) does `writeOrder.start()` :47 **then**
`addToCommitLog(mutation)` :53 (→ `CommitLog.instance.add` :99) and returns
`CassandraWriteContext(group, position)` (`C/db/CassandraWriteContext.java:26-59`).
`Keyspace.applyInternal` (:549-579) then does the memtable put on the same thread;
`AbstractMemtableWithCommitlog.accepts(opGroup, commitLogPosition)`
(`C/db/memtable/AbstractMemtableWithCommitlog.java:71-109`) needs the position
(:101-108) to route old-vs-new memtable. **The CommitLogPosition is required before the
memtable put** — the fact that dominates the (a)/(b) choice.

## §2 — What N per-shard commitlogs must reproduce (option a)

**CommitLogPosition / segment ids:** `CommitLogPosition.java` — `segmentId` long +
`position` int :45-47, compareTo (segmentId, then position) :49-58, `NONE=(-1,0)` :43.
Id generation is **already a single global static allocator** (`CommitLogSegment.java:70-92`):
`idBase = max(currentTimeMillis, maxOnDiskId+1)`, ids = `idBase + nextId.getAndIncrement()`.
⇒ N managers sharing this static get globally-unique, roughly-interleaved ids **for
free** (one getAndIncrement per 32 MiB segment per shard — negligible). Filenames
`CommitLog-<version>-<id>.log` (`CommitLogDescriptor.java:238`) never collide even in one
shared directory.

**Replay:** `CommitLog.recoverSegmentsOnDisk` (`CommitLog.java:183-219`) lists the
directory, sorts by id (:206; comparator `CommitLogSegment.java:664-671`) — with unique
ids across shards this works **unchanged** on the union of all shards' files.
`CommitLogReplayer.construct` (:115-186): per-table `IntervalSet<CommitLogPosition>` from
truncation records (:124), sstable `commitLogIntervals` (:153), archiver snapshot (:147);
`globalPosition = firstNotCovered(...)` :375-381. **Replay apply is already parallel**:
`MutationInitiator` :281-332 submits each mutation to `Stage.MUTATION` (:330).
Correctness under N logs by position alone: yes — a partition lives in exactly one
shard's log; that shard's segment ids ascend; within-segment order by position;
cross-shard interleaving is irrelevant (mutations timestamp-resolved; per-partition order
is what the single log guarantees today). What DOES assume one sequence:
`discardCompletedSegments`'s early-break `if (segment.contains(upperBound)) break`
(`CommitLog.java:382`) — the upperBound lives in ONE shard's sequence, so per-manager
iteration needs its own terminator; and `replayLimitId` :82 (static — fine, shared).

**Flush lower-bound tracking:** `AbstractMemtableWithCommitlog.java` —
`approximateCommitLogLowerBound = CommitLog.instance.getCurrentPosition()` at
construction (:36-50), `commitLogLowerBound` ctor-injected AtomicReference,
`commitLogUpperBound` set at `switchOut` :55-62 and CAS-raced by in-flight writes in
`accepts` :92-108. Flush (`C/db/ColumnFamilyStore.java:1244-1290`, verified): one shared
`commitLogUpperBound` AtomicReference across table+2i memtables :1252-1262,
`setCommitLogUpperBound` :1266, `writeBarrier.issue()` :1271; PostFlush →
`CommitLog.discardCompletedSegments(id, lower, upper)` :1199 →
`segment.markClean(tableId, interval)` (`CommitLogSegment.java:557-622`), segment freed
when `tableDirty.isEmpty()`. Under N logs with the **shared id namespace**, a single
`(min lower, max upper)` pair per memtable stays *safe* (the barrier guarantees every
pre-barrier write, in any shard log, is in the flushed set; markClean over-coverage per
table is harmless) but makes discard conservative; the invasive alternative is per-shard
bound pairs in the memtable.

**CDC:** `CommitLogSegmentManagerCDC.java` — global `CDCSizeTracker` per manager :54-59
with `AtomicLong sizeInProgress` :295 + single-thread recalc executor :292;
`createSegment` hard-links into cdc_raw :240-251; `CDCState` :73-78; `throwIfForbidden`
throws `CDCWriteException` :215-230; index file written on sync
(`CommitLogSegment.java:365-395`). N CDC managers must **share one size tracker** (or
split `cdc_total_space` N ways); index/hard-link naming is id-based ⇒ fine.

**Archiving/PITR:** `CommitLogArchiver.java` — `maybeArchive` :196-211 (async, single
executor :90-94, `archivePending` keyed by segment name) invoked from
`AbstractCommitLogSegmentManager.java:329`; restore :271-334; PITR truncation-record
interplay `CommitLogReplayer.java:131-143`. One archiver instance already shared via
`commitLog.archiver` — N managers keep sharing it unchanged.

**Tools/tests:** external/CDC consumer contract is `CommitLogReader` (`minPosition`
filter :170-172, `CommitLogReadHandler` :71) — unaffected given naming+id-sort
preserved. Test surface: `test/unit/org/apache/cassandra/db/commitlog/` — CommitLogTest,
CommitLogReaderTest, CommitLogArchiverTest, CommitLogSegmentManagerCDCTest,
CDCTestReplayer, SegmentReaderTest, CommitLogDescriptorTest, CommitLogFailurePolicyTest.
They assume filename pattern + id ordering, both preserved.

**Option (b) — what it sidesteps and what it costs:** (b) keeps ONE log ⇒
replay/CDC/archiver/tools/metrics/size-cap all untouched. Cost: `CommitLog.add` is
synchronous inside `beginWrite` and its returned position is needed **before** the
memtable put. So (b) = per-mutation round trip shard→writer-core→shard (position
allocation + buffer copy on the writer core), i.e. either a blocking wait on the shard
thread (forbidden) or restructuring `Keyspace.applyInternal`/`CassandraWriteHandler` into
a position-continuation — a bigger blast radius in the write path than (a)'s
commitlog-internal changes. Group-commit interaction: in **periodic** (default) mode
today's add() doesn't wait for fsync, so (b)'s hop is pure allocation latency; in
batch/group modes writers already block on per-segment `syncComplete` until the (already
single-threaded) sync thread fsyncs — (b) turns that block into a callback but does
**not** add fsync serialization; the real (b) ceiling is one core doing memcpy of every
mutation plus the per-mutation cross-core round trip (~1-2 µs each way) on the latency
path.

## §3 — writeOrder census (I4)

**Declaration + verbatim comment** (`C/db/Keyspace.java:100-102`, verified):
```
//OpOrder is defined globally since we need to order writes across
//Keyspaces in the case of Views (batchlog of view mutations)
public static final OpOrder writeOrder = new OpOrder();
```

**Complete use census:**
- `start()`: `CassandraKeyspaceWriteHandler.beginWrite` :47; `createEmptyContext` :107
  (2i/read-repair contexts); `C/service/accord/AccordKeyspace.java:361` (**Accord threads
  write system tables under writeOrder** — direct `memtable.put` under a group).
- group close: `CassandraWriteContext.close` :55-58.
- Barrier lifecycle at flush (`ColumnFamilyStore.java`, verified): `newBarrier()` :1247 →
  per-CFS `oldMemtable.switchOut(writeBarrier, commitLogUpperBound)` :1260 → `issue()`
  :1271 → in flush run: `markBlocking(); await()` :1285-1286.
- `awaitNewBarrier()` (issue+await shorthand): `ColumnFamilyStore.java:3365` (CFS
  invalidate/shutdown), **`AbstractCommitLogSegmentManager.java:371`** (segment
  force-recycle — the commitlog itself depends on writeOrder),
  `C/index/internal/CassandraIndex.java:681` (index invalidation),
  `tcm/compatibility/.../DistributedSchema.java:365` (keyspace unload).
- Read side for contrast: `readOrdering` is already per-table (`ColumnFamilyStore.java:299`).

**OpOrder mechanics** (`C/utils/concurrent/OpOrder.java`): `current` volatile Group :97;
`start()` spin :105-113; `Group.register()` CAS on `running` :206-216 (**the one shared
cacheline every write on every core hits — HOSTILE #1**); `close()` :222-244; `newBarrier`
:121-124; `Barrier.issue()` :389-402 (synchronized on the OpOrder, swaps current group);
`isAfter` :375-383 (**group-id comparison — only meaningful within one OpOrder
instance**); `await` :420-426/:256-270; `markBlocking` :333-339/:407-415. **`Group` is
`public static final class` (:143) with NO back-reference to its owning OpOrder** — a
per-shard design must carry shard identity alongside the group (e.g. in
`CassandraWriteContext`) or add an owner field to Group.

**MV assessment:** view generation runs inside `applyInternal` under the SAME group
(`Keyspace.java:549-577`, `pushViewReplicaUpdates` :568; `C/db/view/TableViews.java:173-201`
reads base :191-192 and calls `StorageProxy.mutateMV` :200; local view applies go async
on `Stage.VIEW_MUTATION`, `StorageProxy.java:1214` — there is **no VIEW_MUTATION_REQ
verb**; remote view mutations travel as MUTATION_REQ). Under shard routing, base-read +
view generation happen on the owning shard under that shard's group. The global order's
only remaining job — "flush doesn't miss any in-flight write in any keyspace" — is
preserved if every flush/awaitNewBarrier site awaits **all N** shard orders. Conclusion:
global order is NOT semantically required, provided (i) every `start()` is attributable
to a shard and (ii) all five barrier sites become composite. Residual risk = non-shard
writers (Accord :361, replay threads, startup writes, 2i builds) — each needs a
shard-attribution rule.

## §4 — Memtable memory, HOSTILE #4 (adjacent to I4)

**Chain:** global static `MEMORY_POOL` (`C/db/memtable/AbstractAllocatorMemtable.java:61`,
type by `memtable_allocation_type` :82-113 → HeapPool/SlabPool/NativePool); **one**
`MemtableAllocator` per memtable :120, handed to ALL shards
(`C/db/memtable/TrieMemtable.java:129,140`, field :535); per-put use :552
(`allocator.cloner(opGroup)`), :576-577 (`onHeap()/offHeap().adjust`).

**Where sibling-shard contention actually is (per-allocation):**
1. `MemtablePool.SubPool.allocatedUpdater.addAndGet` — `C/utils/memory/MemtablePool.java:155`
   (updater :265, fields :113-114) via `MemtableAllocator.SubAllocator.allocate →
   parent.tryAllocate` (`C/utils/memory/MemtableAllocator.java:176`). **Global across ALL
   memtables** — hit on every allocation.
2. `Region.nextFreeOffset.getAndAdd` — `C/utils/memory/NativeAllocator.java:345` (field
   :324) / `SlabAllocator.java:199` (field :179). **Shared across sibling shards**
   because of the single allocator.
3. `owns` is a LongAdder (`MemtableAllocator.java:115`, :208,:225) — already striped.
Per-region (cheap, ~1 MiB granularity): `currentRegion` CAS `NativeAllocator.java:254` /
`SlabAllocator.java:111`.

**What a per-shard allocator changes:** one `MemtableAllocator` per `MemtableShard`
removes #2 entirely. It does **NOT** remove #1 — `tryAllocate` still CASes the global
SubPool per allocation; removing that requires a slack-batching layer (shard-local budget
acquired from SubPool in region-sized chunks). **Stays global:** pool `limit` :107,
`cleanThreshold` :110, `needsCleaning`/`nextClean` :127-145, cleaner thread + hasRoom
(MemtablePool :46,55), `flushLargestMemtable` (`AbstractAllocatorMemtable.java:257-326`).
Lifecycle to N-ify: `setDiscarding`/`setDiscarded` (:167,:173 →
`MemtableAllocator.java:84-98,130-156`), `ownershipRatio` :190-191, blocking-when-full.

## §5 — Inbound path today + I5 bypass design

**Small vs large:** threshold `LARGE_MESSAGE_THRESHOLD` = `OTCP_LARGE_MESSAGE_THRESHOLD`
default **64 KiB** minus frame overhead (`C/net/OutboundConnections.java:72`, prop
`C/config/CassandraRelevantProperties.java:438`), fed to inbound via
`InboundMessageHandlers.java:147`. Small: `InboundMessageHandler.processSmallMessage`
:161-216 — expiry pre-check :135-142 (`callbacks.onArrivedExpired`), **deserialize ON the
netty loop** :171, deserialize-failure handling :183-202. `ProcessSmallMessage` :484-509
(message pre-built); `ProcessLargeMessage` :511-540 — `provideMessage()` :530-532
deserializes **on the stage thread** (`LargeMessage.deserialize` :379-414).

**Dispatch (verified):** `InboundMessageHandler.java:429` —
`header.verb.stage.execute(ExecutorLocals.create(state), task)`; TraceState from
`Tracing.instance.initializeFromMessage(header)` :424-426; `callbacks.onDispatched` :428.
Expiry re-checked at run time: `ProcessMessage.run` :451-454 → `callbacks.onExpired`
(drop metrics `C/metrics/MessagingMetrics.java:140-171`).

**Post-deserialize token:** `Mutation.key()` — plain field getter (`C/db/Mutation.java:180-183`);
`SinglePartitionReadCommand.partitionKey()` :497 — field getter. Both cheap. **But
READ_REQ and RANGE_REQ share `ReadCommand.serializer`** (`C/net/Verb.java:226-230`,
verified) — routing must `instanceof SinglePartitionReadCommand`.

**The minimal-bypass shape the facts force:** swap the *executor*, keep the *task*.
Everything correctness-critical lives inside `ProcessMessage.run()` /
`InboundSink.accept()` — expiry recheck :451-454, handler invocation :460, Throwable
catch + failure response (`C/net/InboundSink.java:120-150`, `fail()` :108-118 sends
`Message.failureResponse` :113-116), capacity release in finally :465-475,
`callbacks.onProcessed/onExecuted` :462,:474. If I5 replaces only
`stage.execute(locals, task)` with `shardExecutor(token).execute(locals, task)`, all of
it (including dtest inbound filters, which decorate `inboundSink`,
`Instance.java:391-406` / `InboundSink.add` :152-155) carries over verbatim.

**Safe allowlist:**

| Verb | Stage today | I5 | Why |
|---|---|---|---|
| MUTATION_REQ :202 | MUTATION | **route** | token = `mutation.key()`; handler already continuation-style (`MutationVerbHandler.java:83`) |
| READ_REQ :228 | READ | **route iff instanceof SinglePartitionReadCommand** | token = `partitionKey()`; shared serializer with range |
| all _RSP | REQUEST_RESPONSE | exclude | `ResponseVerbHandler` → `RequestCallbacks` wakes blocked coordinator threads; no token |
| RANGE_REQ :230 | READ | exclude | multi-shard scan |
| PAXOS v1 :214-219, PAXOS2 :281-296 | MUTATION (mostly) | exclude | synchronous handlers + PaxosState locks; Paxos is a first-class separate path |
| COUNTER_MUTATION_REQ :225 | COUNTER_MUTATION | exclude | synchronous lock + read-before-write on handler thread (`StorageProxy.java:2148-2149`, `CounterMutation.java:158-163`) |
| HINT_REQ :206 | MUTATION | exclude | payload = mutations for arbitrary tokens; apply already async (`HintVerbHandler.java:110-118`) |
| BATCH_STORE/REMOVE_REQ :210-212, TRUNCATE_REQ :222, VIRTUAL_MUTATION_REQ :204 | MUTATION | exclude | multi-partition / rare / non-token |
| Accord verbs :326-383 | IMMEDIATE | exclude | already inline on messaging threads — 3.1-item-6's question, not I5's |

## §6 — Large messages, fallback, back-pressure (I5)

- **Large messages:** token unknowable until deserialized, and deserialization happens on
  the target executor (:530-532) — chicken-and-egg. PoC: large messages keep the Stage
  path (mutations >64 KiB are off the hot path). No code change beyond allowlist checking
  `ProcessSmallMessage` only.
- **Flag-off/mixed:** routing is a per-message executor choice at :429; flag-off is
  byte-identical to today; no wire-format impact (node-local).
- **Back-pressure:** capacity acquired on the loop before processing
  (`C/net/AbstractMessageHandler.java:399-462` — `queueSizeUpdater`, endpoint/global
  `ResourceLimits`, WaitQueue tickets :404-406,630, FrameDecoder reactivation :314) and
  released on the executing thread in `ProcessMessage.run` finally (:465-475 →
  `releaseProcessedCapacity` :486-488 → `releaseCapacity` :464-477). Keeping the task
  object intact preserves this exactly; the only new failure mode is the shard inbox
  itself — offer must be non-blocking; **on full, fall back to `stage.execute` (today's
  path)** rather than dropping, so existing capacity accounting stays the sole
  back-pressure mechanism.

## §7 — Adversarial findings (both increments)

1. **Startup ordering (I4):** `CommitLog.instance` static (:80); `start()` at
   `CassandraDaemon.java:273`; **first write = `SystemKeyspace.persistLocalMetadata()`
   :325**; replay :361 — and replay applies with `writeCommitLog=false`
   (`CommitLogReplayer.java:325`), so replay never re-logs. ⇒ per-shard managers must be
   constructible+started at :273, before TCM/ring is usable; shard-log selection must not
   depend on `ShardBoundaries`/TCM (fallback: current-thread-shard, else raw token
   hash % N).
2. **Failure policy:** `handleCommitError` is global (`CommitLog.java:577-580`; callers:
   sync service, manager, replayer :538, CDC index write `CommitLogSegment.java:392`).
   Keep global under N managers — one failed shard log stops/kills the node, same
   semantics as today (per-shard isolation would be a semantic change; don't).
3. **Size cap:** each manager's `unusedCapacity()` reads the FULL configured total
   (:447-453) against its own `size` :105 → N managers would each assume the whole
   budget (N× overshoot). Fix: share one size atomic (recommended) or divide cap by N.
4. **Buffers:** `SimpleCachedBufferPool` per-manager (bounded); N managers = N pools + N
   preallocated 32 MiB segments — footprint multiplies with shard count.
5. **Metrics:** `CommitLogMetrics.attach` binds one service+manager (:60-82) — needs
   aggregating gauges over N managers.
6. **Replay parallelism:** already parallel (`Stage.MUTATION.submit`,
   `CommitLogReplayer.java:330`); per-shard logs neutral (after I1, replay applies route
   through shard executors anyway).
7. **I5 exception handling:** nothing to reproduce if the task is reused — catch lives in
   `InboundSink.accept` :126-149 with failure response :108-118.
8. **In-JVM dtest gap (verified by direct read):** dtest delivery does **NOT** go through
   `InboundMessageHandler` — `Instance.receiveMessageRunnable`
   (`test/distributed/org/apache/cassandra/distributed/impl/Instance.java:548-596`)
   deserializes itself and calls `header.verb.stage.executor().execute(locals, () ->
   inboundSink.accept(messageIn))` :582-595. ⇒ an I5 change confined to
   `InboundMessageHandler.dispatch` is INVISIBLE to in-JVM dtests (they'd pass flag-on
   without exercising routing). Filters still work either way (they decorate
   `inboundSink`). Fix: extract executor-selection into a helper called from BOTH sites.
9. **I1 micro-counter safety:** I1's A/B counters are memtable-level
   (`TrieMemtableMetricsView.java:41,44`, incremented `TrieMemtable.java:556,560`) —
   unaffected by Stage bypass. What DOES change: MutationStage tpstats (gauges wired at
   `SEPExecutor.java:80`, `ThreadPoolMetrics.java:48-54`) freeze at pre-flag values →
   the I5 measurement plan must use messaging `internalLatency`
   (`MessagingMetrics.java:115-128`, still recorded) plus new per-shard inbox metrics,
   not tpstats.

## RESOLVED (question → answer)

1. N per-shard log streams replay correctly by CommitLogPosition alone **iff the existing
   global static segment-id allocator is shared** — the only single-sequence assumption
   needing code change is `discardCompletedSegments`'s break (`CommitLog.java:382`).
2. Option (b) does NOT serialize ack-after-fsync (fsync already one thread; waiters on
   per-segment syncComplete). (b)'s real costs: per-mutation cross-core hop for position
   allocation + single-core memcpy of all mutations.
3. MV global-order not semantically required under shard routing — base+view share one
   group on the owning shard; flush must await all N shard barriers; residual = shard
   attribution for non-shard writers.
4. Sibling-shard memtable CAS contention = global `SubPool.allocated` addAndGet
   (`MemtablePool.java:155`) + shared `Region.nextFreeOffset` getAndAdd
   (`NativeAllocator.java:345`/`SlabAllocator.java:199`). Per-shard allocators remove
   only the second; the first needs slack batching (optional step 2).
5. Token cheaply available post-deserialize (Mutation.key / partitionKey); READ_REQ needs
   instanceof guard (shared serializer with RANGE_REQ).
6. Stage-bypass exception/capacity handling: reuse the task ⇒ nothing extra
   (InboundSink.accept catch; ProcessMessage.run finally).
7. dtest filters see I5 changes; dtest DELIVERY bypasses InboundMessageHandler — shared
   helper required for coverage.
8. I5 does not break I1's micro counter; MutationStage tpstats freeze flag-on.
9. Replay already parallel.
10. First CommitLog.add is persistLocalMetadata at CassandraDaemon.java:325, after
    start() :273, before replay :361 (replay doesn't re-log).

## OPEN QUESTIONS (for user/spec)

1. **§3.3 must choose (a) vs (b)** — (a) ~10 commitlog-internal files + bookkeeping
   reconciliation vs (b) ~5 files but an async restructure of the beginWrite→memtable-put
   contract plus a mandatory per-mutation cross-core hop. Data implies (a).
2. **Option (a) memtable CL bounds:** single (min,max) interval (safe, conservative
   reclamation — segments pinned until the max-upper flush) vs per-shard bound pairs
   (correct reclamation, touches memtable/CFS plumbing).
3. **Sync-thread topology under (a):** one sync thread iterating N managers (same fsync
   count as today) vs N sync threads (N× fsyncs — device-dependent; phase-2 data should
   inform).
4. **Commitlog cap sharing:** shared size atomic (recommended) vs cap/N —
   operator-visible semantics.
5. **Shard attribution rule for non-shard writers** (startup/main, replay threads, 2i
   builds, Accord threads at `AccordKeyspace.java:361`): current-thread-shard else
   token-hash%N proposed; the Accord case is gated on §3.1 item 6.
6. **I5 dtest seam:** require the shared executor-selection helper in
   `Instance.receiveMessageRunnable` (recommended — otherwise "dtests pass flag-on" is
   vacuous), or accept the coverage gap explicitly.
7. **OpOrder shard identity:** owner/shard field on `OpOrder.Group` vs carrying shard id
   in `CassandraWriteContext`.
8. **I5 inbox-full policy:** fallback-to-Stage (proposed) vs shed — defines behavior
   under overload, which is exactly what the p99 gate measures.
