# Findings — I2 shard-routed local reads + I3 non-blocking coordinator (agent report, 2026-07-07)

Verbatim exploration report; inventories distilled into `expected-changes.md`. Paths
under `src/java/org/apache/cassandra/` unless noted; line numbers verified on
tpc-migration 2026-07-07.

## I2 §1 — Local read call graph and THE chunk-read seam

### 1.1 Submission
- `service/reads/AbstractReadExecutor.makeRequests` (AbstractReadExecutor.java:138-170):
  local replica detected at :149 (`replica.isSelf() && coordinator.localReadSupported()`),
  deferred to last, then **`Stage.READ.maybeExecuteImmediately(new
  LocalReadRunnable(readCommand, handler, requestTime))` at AbstractReadExecutor.java:168**
  (verified). Digest requests reuse the same path via `makeDigestRequests` →
  `makeRequests(command.copyAsDigestQuery(...))` (:131-136).
- `StorageProxy.LocalReadRunnable` — StorageProxy.java:2721. Body (:2740-2801):
  `command.executionController(trackRepairedStatus)` + `command.executeLocally(controller)`
  (:2752-2756), `RejectException` → empty response + warnings, success →
  `handler.response(response)` (:2773-2775), failure → `handler.onFailure(self,
  TIMEOUT|UNKNOWN)` (:2781).
- `Stage.READ` = SEP `multiThreadedLowSignalStage` (Stage.java:46, :232-237);
  `maybeExecuteImmediately` → `SEPExecutor.maybeExecuteImmediately`
  (SEPExecutor.java:205-227): permit free → runs **inline on the calling (NTR) thread**.

### 1.2 executeLocally → sstable read
- `ReadCommand.executeLocally` (ReadCommand.java:500-506; `queryStorage` :534) →
  `SinglePartitionReadCommand.queryStorage` (:556, row-cache branch :562) →
  `queryMemtableAndDisk` (:727; `cfs.select(View.select(...))` :733 — OpOrder-guarded, no
  per-sstable refcount) → `queryMemtableAndDiskInternal` (:748+): memtable lookup
  `memtable.rowIterator(partitionKey(), ...)` (:789), then sstable iterators →
  `RandomAccessReader` → `Rebufferer`.

### 1.3 THE seam (cache miss → pread)
- `ChunkCache` (cache/ChunkCache.java): enabled iff `file_cache` enabled and sized
  (:49-53); **`instance` is null when disabled**. Caffeine built with
  **`.executor(ImmediateExecutor.INSTANCE)` (:152, directly verified)** → miss-loads run
  **synchronously on the calling thread**. Loader `load(Key)` (:160-174): BufferPool
  buffer, then **`key.file.readChunk(key.position, buffer)` (:166)**.
- ChunkReader impls that execute the disk read:
  - `SimpleChunkReader.readChunk` (io/util/SimpleChunkReader.java:38-43):
    `channel.read(buffer, position)` at **:41** (uncompressed tables).
  - `CompressedChunkReader.Standard.readChunk` (io/util/CompressedChunkReader.java:399-451):
    compressed path `readFrom.read(chunk, shouldCheckCrc)` :413 (internally
    `channel.read(compressed, chunk.offset)` :187), pass-through path
    `channel.read(uncompressed, chunk.offset)` :429.
- **`ChannelProxy.read(ByteBuffer, long)` (io/util/ChannelProxy.java:169-180) —
  `channel.read(buffer, position)` at :174 = synchronous positional pread. The single
  choke point all three non-mmap readers funnel through.**
- **fd is reachable at the seam**: `ChannelProxy.getFileDescriptor()` (:218-221) →
  `NativeLibrary.getfd(FileChannel)` (NativeLibrary.java:379-402, −1 on failure).

**Seam statement:** the io_uring `readSync` replacement point is
`ChannelProxy.read(ByteBuffer, long)` (ChannelProxy.java:174), with fd via
`getFileDescriptor()`; equivalently a decorator at `ChunkReader.readChunk`. ChannelProxy
is the smaller, complete intercept (also covers non-cached `BufferManagingRebufferer`
reads).

### 1.4 disk_access_mode — the PoC-shaping fact (directly verified)
`Config.disk_access_mode = DiskAccessMode.mmap_index_only` default (Config.java:130);
enum `{auto, mmap, mmap_index_only, standard, legacy, direct}` (Config.java:1355-1367).
Resolution in `DatabaseDescriptor` (DatabaseDescriptor.java:675-693, read directly):
- `auto`/`mmap_index_only` → **`conf.disk_access_mode = standard; indexAccessMode = mmap`**
  (:675-679).
- `legacy` → mmap if 64-bit else standard for both (:680-684).
- **`direct` → `throw new ConfigurationException("DiskAccessMode 'direct' is not
  supported")` (:685-688).** (`CompressedChunkReader.Direct` is reachable only via
  `compaction_read_disk_access_mode` — :695-697 — not the query path.)
- explicit `mmap`/`standard` → same for index (:689-692).

Dispatch in `FileHandle.Builder.complete` (FileHandle.java:445-536): mmap →
`CompressedChunkReader.Mmap` (:496) or `MmapRebufferer` (:502) — **no explicit read
syscall; page faults on mapped regions**; non-mmap → `CompressedChunkReader.Direct`
(:512) / `.Standard` (:516) / `SimpleChunkReader` (:523), wrapped by `maybeCached` →
`chunkCache.wrap` iff cache enabled (:538-543). ChunkCache is **not used in mmap mode**.

| effective mode (data files) | read mechanism | ring-replaceable? |
|---|---|---|
| default `mmap_index_only` → data=standard, index=mmap | ChunkCache miss → ChannelProxy.read pread (data); page fault (index) | yes, data files already |
| `standard` | pread for data + index | yes, fully |
| `mmap` | page faults only | **no — nothing to replace** |
| `direct` | rejected at startup | n/a |

**PoC must pin `disk_access_mode: standard`** (covers index components too). Compression
is irrelevant to the seam — both readers hit ChannelProxy.read. O_DIRECT reads are
unavailable on the query path without a config change — out of I2 scope; the ring runs
against buffered preads.

**Fallback requirement:** ChannelProxy.read is also called by compaction, streaming,
validation, internal queries — threads with no ring. The intercept must be "ring if
`UringRings.threadLocal()` registered for this (shard) thread, else `channel.read`".

## I2 §2 — Range reads and other local read shapes

- **Range reads:** `PartitionRangeReadCommand.queryStorage`
  (PartitionRangeReadCommand.java:401-435) iterates all memtables/sstables in the key
  range — crosses every shard. Local submission:
  **`Stage.READ.execute(new StorageProxy.LocalReadRunnable(...))` at
  RangeCommandIterator.java:223** (note: `execute`, not mEI). **I2 leaves range reads on
  Stage.READ** (routing a cross-shard scan to one shard thread serializes it); the flag
  branch keys on command type at the submission site.
- **Digest reads:** same LocalReadRunnable path (`isDigestQuery=true`,
  ReadCommand.java:164); shard-routable identically.
- **Read-repair reads:** `AbstractReadRepair.sendReadCommand`
  (service/reads/repair/AbstractReadRepair.java:96-124): local branch at :100 →
  **`Stage.READ.maybeExecuteImmediately(new StorageProxy.LocalReadRunnable(...))` at
  :102**. Single-partition — routable.
- **Short-read / replica-filtering protection:** `ShortReadPartitionsProtection.java:191`
  and `ReplicaFilteringProtection.java:175` both submit `LocalReadRunnable` via mEI
  mid-resolution. Same-partition follow-ups — routable, same decision.

## I2 §3 — Everything else on Stage.READ (must NOT be routed)

1. AbstractReadExecutor.java:168 — coordinator local point/digest reads (**route**).
2. RangeCommandIterator.java:223 — local range reads (**leave**).
3. ShortReadPartitionsProtection.java:191, 4. ReplicaFilteringProtection.java:175,
5. AbstractReadRepair.java:102 — protection/repair reads (recommend route).
6. `CacheService.CounterCacheSerializer` (service/CacheService.java:383) and
7. `RowCacheSerializer` (:412) — cache-warming `Stage.READ.submit` tasks (**leave**).
8. `AccordInteropRead` (service/accord/interop/AccordInteropRead.java:289,304) — Accord
   chains on `Stage.READ.executor()` (**leave**).
9. Verb-mapped replica reads: `READ_REQ`/`RANGE_REQ` → Stage.READ (Verb.java:228-229) →
   `ReadCommandVerbHandler.doVerb` — **out of I2 scope (I5's inbound dispatch)** — but on
   a 1-node rig, coordinator==replica reads all arrive via path 1, so I2 still covers the
   benchmark's hot path.
10. Internal queries (`QueryProcessor.executeInternal`) do **not** use Stage.READ — they
    call `executeLocally` inline on the calling thread. They hit the ChannelProxy seam
    from non-shard threads → covered by the ring fallback.

Because the flag branches at explicit submission sites, sites 6-10 are naturally excluded.

**Shard keying:** `command.partitionKey()` (SinglePartitionReadCommand.java:104) →
`ShardBoundaries.getShardForKey` (db/memtable/ShardBoundaries.java:80-88) with boundaries
from `cfs.localRangeSplits(shardCount)` (ColumnFamilyStore.java:1608-1631, epoch-cached).
`AbstractReadExecutor` already holds `protected final ColumnFamilyStore cfs` (:77) —
everything needed is in scope at line 168.

## I3 §4 — Complete blocking-await inventory (coordinator request path)

Signaller thread key: all `*_RSP` verbs run on `Stage.REQUEST_RESPONSE`
(Verb.java:201-408) via `ResponseVerbHandler.doVerb` (ResponseVerbHandler.java:62-87:
`callbacks.remove` :64, `cb.onFailure` :80, `cb.onResponse` :85 — inline, no further
hop). Expiry-driven signals run on `Stage.INTERNAL_RESPONSE` (§6).

### Reads
| # | Site | Parks on | Signalled by | Continuation shape |
|---|---|---|---|---|
| R1 | `ReadCallback.awaitResults` → `awaitUntil` (ReadCallback.java:134-136 → :122-132, `condition.awaitUntil(deadline)` **:126**; condition **:71**) | one-time Condition, deadline from `requestTime.computeDeadline` | `onResponse` → `condition.signalAll()` **:242**; `onFailure` → **:281** (REQUEST_RESPONSE); reaper timeout via `onFailure` (INTERNAL_RESPONSE) | AsyncPromise; result-check body of awaitResults (:134-214, failure snapshot `ImmutableMap.copyOf` :185) becomes the completion function |
| R2 | Speculation window: `SpeculatingReadExecutor.maybeTryAdditionalReplicas` (doc :172-174 "May block") — short await on the same ReadCallback condition (`awaitUntil` doc :109-121) | same condition, sample-latency timeout | same | timer-based speculation on a scheduled executor |
| R3 | `StorageProxy.fetchRows` orchestration (StorageProxy.java:2649-2718): `executeAsync` :2676 → `maybeTryAdditionalReplicas` :2683 → **`awaitResponses` :2691** → `maybeSendAdditionalDataRequests` :2698 → **`awaitReadRepair` :2704**; plus `concatAndBlockOnRepair` → **`repairs.forEach(ReadRepair::awaitWrites)` :2620** | R1's condition; then read-repair latches (R4) | as above | per-command future chain; digest-mismatch retry (awaitResponses body, AbstractReadExecutor.java:424-460 — `handler.awaitResults` :428, mismatch → `readRepair.startRepair` :451) becomes a `thenCompose` |
| R4 | `BlockingReadRepair.awaitWrites` (service/reads/repair/BlockingReadRepair.java:165-197, `repair.awaitRepairsUntil(deadline)` :174) and `BlockingPartitionRepair.awaitRepairs` (:96-112, Future.get :102); `awaitReads` via awaitReadRepair (:462-478) | countdown latch/future per repair set | MUTATION_RSP callbacks (REQUEST_RESPONSE) | repair futures composed into the read future; **must be converted or I3's read path still parks** |

### Writes
| # | Site | Parks on | Signalled by | Continuation |
|---|---|---|---|---|
| W1 | `AbstractWriteResponseHandler.get` (AbstractWriteResponseHandler.java:130-194, `condition.await(timeoutNanos)` **:137**, condition **:83**) called per-mutation from `StorageProxy.mutate` loop (**:1004**) | one-time Condition, `currentTimeoutNanos()` | `signal()` → `condition.signalAll()` :359 (REQUEST_RESPONSE); reaper via `onFailure` | handler exposes Future; mutate becomes `allOf(handlers)` |
| W2 | Write speculation: `maybeTryAdditionalReplicas` (:430-464, `condition.await(timeout, MICROSECONDS)` **:449**) | same condition | same | timer-scheduled extra write |
| W3 | Batch: `mutateAtomically` (:1433-1599) → `syncWriteToBatchlog` **`handler.get()` :1663** → `syncWriteBatchedMutations` **`wrapper.handler.get()` :1712** loop → cleanup async; Accord branch `accordResult.awaitAndGet()` :1564 | two+ sequential WRH conditions | REQUEST_RESPONSE | three-stage future chain |
| W4 | Counter: `mutateCounter` (:2082-2117) — leader==self → `Stage.COUNTER_MUTATION` (`counterWriteOnCoordinatorPerformer` :303-309, `applyCounterMutation` :2148); leader remote → forward then NTR parks in W1. On the leader, `CounterMutation.grabCounterLocks` (db/CounterMutation.java:199-218): **`lock.tryLock(timeout, NANOSECONDS)` :209** on Striped locks (:73) + local read inside the lock → a counter write can park twice | Striped lock + WRH condition | lock holder; REQUEST_RESPONSE | PoC: leave counters blocking (flag-excluded) |
| W5 | MV: `mutateMV` (:1110-1221) — sends async (`asyncWriteBatchedMutations` :1214, does not park NTR); but local apply can hit `Keyspace.applyInternal` view-lock branch (Keyspace.java:461-538): lock null → WTE (:483-501); deferrable → re-enqueue (:502-512); **else `Thread.sleep(10)` spin :523** (verified). `viewLockAcquireTime` :546 | ViewManager tryLock (view/ViewManager.java:229-237) | lock release | Not on NTR path; **I1/I2 hazard: shard threads must never enter the :523 branch** |
| W6 | Hints on CL.ANY timeout: `mutate` catch (:1006) → `hintMutations` (:1010, :1066-1087); `submitHint` async (`Stage.MUTATION.submit`, :3387-3392); `LocalMutationRunnable` deadline→hint :3186-3204 — MUTATION stage, not NTR | n/a (async) | — | unchanged |

### SERIAL (Paxos) — all verified
| # | Site | Parks on |
|---|---|---|
| P1 | v1 `AbstractPaxosCallback.await` (service/paxos/v1/AbstractPaxosCallback.java:53-67, `latch.await` :60, latch :44); callers `StorageProxy.preparePaxos` **:826**, `proposePaxos` **:862** | CountDownLatch; REQUEST_RESPONSE signals |
| P2 | v1 commit: `commitPaxos` → `responseHandler.get()` **:923** (also `expired()` :913 for dead replicas) | WRH condition |
| P3 | v2 `Paxos.cas`: propose `.awaitUntil(proposeDeadline)` **:811**, commit `.awaitUntil(commitDeadline)` **:859**, repropose :934/:1092; `Paxos.read`: prepare `.awaitUntil(deadline)` **:1056**. `PaxosPrepare.awaitUntil` = `Object.wait` on monitor (PaxosPrepare.java:433-451); `PaxosCommit.awaitUntil` = ConditionAsConsumer (PaxosCommit.java:141-154) | monitor/Condition; PAXOS2_*_RSP on REQUEST_RESPONSE |
| P4 | v2 contention backoff: `waitForContention` **:849, :962, :1115, :1164** → `ContentionStrategy.doWaitForContention` → sleeping thread (Clock.Global.waitUntil, Clock.java:204) | async needs a delayed-retry scheduler |
| P5 | `PaxosState.lock` (service/paxos/PaxosState.java:410-437, `lock.lock(deadline)` :428) — local lock on coordinator-as-replica | custom deadline lock |

### Misc request-path parks
| # | Site | Notes |
|---|---|---|
| M1 | Truncate: `TruncateResponseHandler.get` (:63-92, await :69), from `StorageProxy.truncateBlocking` **:3084** | rare; leave blocking |
| M2 | `describeSchemaVersions` `latch.await` :2878 | ops path only |
| M3 | TCM fetch on the **REQUEST_RESPONSE** thread: `ResponseVerbHandler.maybeFetchLogs` (:89-133) → `fetchLogFromPeerOrCMS` :115/:129 — blocking, with a comment acknowledging it (:126). Precedent that REQUEST_RESPONSE already sometimes blocks (a hazard, not a license) | coordinator-behind cases |
| M4 | Auth: `Dispatcher.authExecutor` (Dispatcher.java:81-84, `native_transport_max_auth_threads` Config.java:356 default 4; <1 → shared requestExecutor :125-129). `PasswordAuthenticator.authenticate` (:175-212): SELECT → `StorageProxy.read` → parks (R1); BCrypt :152-160 (CPU); `AuthCache.get` (:233-236) sync Caffeine load on caller | PoC: exclude auth from I3 |
| M5 | Accord: `AccordService.coordinate` (service/accord/AccordService.java:1135-1138) = `coordinateAsync(...).awaitAndGet()` — blocks NTR, but the underlying `node.coordinate(...).begin(...)` (:1153-1164) is already async. Interaction-only for I3 | |
| M6 | ClusterMetadata reads on request path = immutable snapshots — non-blocking | resolved non-issue |

## I3 §5 — Dispatcher/NTR structure (verified directly)

- **Today's chain:** netty loop: `CQLMessageHandler.processRequest` checks backpressure
  then `dispatcher.dispatch` → `Dispatcher.dispatch` (Dispatcher.java:108-133): shutdown
  fast-path responds inline (:110-122), auth-vs-request executor select (:125-129),
  **`executor.submit(new RequestProcessor(...))` :131**. `requestExecutor =
  SHARED.newExecutor(getNativeTransportMaxThreads(), ...)` :62 —
  `native_transport_max_threads = 128` (Config.java:352). `RequestProcessor.run` →
  `processRequest(...)` :317 → instance `processRequest` :480-486:
  **`response = processRequest(...)` :482 (fully synchronous), `toFlushItem` :483,
  `flush(toFlush)` :485.**
- **`Message.Request.execute` returns `Response`, not a future** (Message.java:250-252) —
  no async path exists in message execution.
- **ClientWarn/tracing bracketing** in static `processRequest` (:365-475):
  `ClientWarn.instance.captureWarnings()` :381, warnings attached
  `response.setWarnings(...)` **:438**, error path `ErrorMessage.fromException` +
  `error.setWarnings(...)` :447-466, `finally ClientWarn.instance.resetWarnings()`
  **:473**. Async completion must relocate :438/:466/:473 to the continuation.
- **Response flush IS thread-safe from arbitrary threads**: `Dispatcher.flush`
  (:488-496) looks up/creates a per-event-loop `Flusher` (:490-494); `Flusher.enqueue`
  adds to a **ConcurrentLinkedQueue** (Flusher.java:118, :137-140) and `start()`
  CAS-schedules the flusher onto the channel's event loop (:124-129). Channel writes
  happen on the event loop. Remaining risk is what `toFlushItem` touches (payload/frame
  release accounting — see open questions).
- **NTR-pool consequence:** with awaits converted, the NTR thread's job shrinks to
  parse/bind/dispatch (~µs); the 128-thread pool loses its raison d'être — only parking
  justifies 128 threads on ≤16 cores. SEP work permits remain the only queue brake.
- **Backpressure today** (CQLMessageHandler.java:195-235+): per-frame on the event loop —
  bytes-in-flight `acquireCapacity` vs `native_transport_max_request_data_in_flight[_per_ip]`
  (Config.java:365-367; `ClientResourceLimits` :43, :184-186), optional rate limiter
  (Config.java:368), and **`dispatcher.hasQueueCapacity()`** =
  `requestExecutor.oldestTaskQueueTime() < native_transport_timeout × threshold`
  (Dispatcher.java:356-359). Non-throw mode pauses frame delivery via WaitQueue/autoread
  (AbstractMessageHandler.java:127-129, :154-162, :404).
- **Where backpressure moves:** under I3 the NTR queue never builds, so
  `Overload.QUEUE_TIME` goes dead; bytes-in-flight bounds decoded-but-unreleased request
  bytes only while the payload is retained (release at flush time via FlushItem). Nothing
  counts **outstanding coordinator operations**; I3 needs an explicit in-flight-ops
  gauge/limit at dispatch (natural home: `Dispatcher.dispatch`/`ClientResourceLimits`).
  This is I3's micro counter (NTR pool utilization + outstanding ops).

## I3 §6 — Timeout/hint machinery (mostly already async-capable)

- Dual timeout today: (a) the parked thread's own `condition.await(deadline)`; (b) **the
  Callback-Map-Reaper**: `RequestCallbacks` owns
  `executorFactory().scheduled("Callback-Map-Reaper")` (RequestCallbacks.java:64) with
  `scheduleWithFixedDelay(this::expire, ...)` (:70-72); expiry → `onExpired` (:149-158):
  metrics, then **`if (info.invokeOnFailure()) INTERNAL_RESPONSE.submit(() ->
  info.callback.onFailure(peer, RequestFailure.TIMEOUT))`** (:156-157).
- **`ReadCallback.invokeOnFailure()` and `AbstractWriteResponseHandler.invokeOnFailure()`
  both return true** (ReadCallback.java:285-287, AWRH.java:403-406; interface default
  false, RequestCallback.java:55-58). ⇒ **read/write timeouts are ALREADY delivered
  asynchronously**; the parked thread is redundant as a timer for the two hot paths.
  I3's continuation timeout = reaper-driven `onFailure(TIMEOUT)` completing the future
  exceptionally (plus a local deadline check for local-replica-only requests, which never
  register callbacks).
- Caveat: expiry-driven `onFailure` runs on **INTERNAL_RESPONSE**, not REQUEST_RESPONSE —
  continuations must be safe from both stages. Paxos v1 latches and other
  `invokeOnFailure()==false` callbacks get **no** async timeout — their conversion needs
  the reaper opt-in or a timer.
- **Hints:** timeout-hinting is narrow: CL.ANY `hintMutations` on WTE catch
  (StorageProxy.java:1006-1010, :1066-1087); dead-replica hints at send time (:1901-1913,
  `responseHandler.expired()` :1901); Paxos commit :913/:917; local-runnable
  deadline→hint :3186-3204. `submitHint` is `Stage.MUTATION.submit` (:3387) — async;
  **no hint logic depends on the parked coordinator thread**.
- **idealCLWriteLatency:** already response/expiry-driven (AWRH:238-239, :250-266,
  :273-286, :290-293, :413-425 `writeFailedIdealCL` at zero). Safe under async. What DOES
  assume the blocking span: client-side latency — `QueryMessage` `queryStartTime` :114 +
  `QueryEvents.notifyQuerySuccess` :119, ClientRequestMetrics recorded at the end of the
  synchronous StorageProxy call — these move to the continuation (RequestTime is
  immutable and carries `enqueuedAtNanos/startedAtNanos`, Dispatcher.java:135-160, so
  end-to-end latency stays computable).

## I3 §7 — Adversarial findings

1. **ExecutorLocals don't follow responses.** ClientWarn/Tracing live in `ExecutorLocals`
   (ExecutorLocals.java:95-102, :113-117; ClientWarn.java:37, :77-83). SEP submission
   propagates them, but `ResponseVerbHandler` runs with the **replica message's** locals
   (InboundMessageHandler.java:429) — the originating request's ClientWarn state is NOT
   present on REQUEST_RESPONSE. Continuations must capture the request's ExecutorLocals
   at conversion point and install them around completion + response building (warnings
   attach at Dispatcher.java:438/:466; reset :473). `ClientWarn.State` is a
   thread-shareable CopyOnWriteArrayList (ClientWarn.java:94-95). `MessageParams` is a
   separate FastThreadLocal (db/MessageParams.java:32, :88) — same treatment.
2. **REQUEST_RESPONSE starvation/deadlock.** Pool = availableProcessors threads
   (Stage.java:52). If a continuation running there blocks (digest-mismatch full-data
   await, CAS contention sleep, a sync chunk read), it consumes the very workers needed
   to deliver the responses that would unblock it. TCM fetch (M3) is a bug-shaped
   precedent, not a license. **Rule for I3: continuations on REQUEST_RESPONSE must be
   non-blocking all the way down — every nested await in the flagged chain converted
   (R4 included) or the path flag-excluded.**
3. **Digest-mismatch retry currently runs on the woken NTR thread** (awaitResponses body,
   AbstractReadExecutor.java:424-460: mismatch → `readRepair.startRepair` :451; then
   awaitReadRepair :462-478). Under I3 it becomes a nested continuation — two-stage
   compose, not a single await.
4. **Counter writes** park a COUNTER_MUTATION thread on Striped locks including a read
   under the lock (CounterMutation.java:73, :199-218) — cannot be made non-blocking by
   future composition alone; exclude from I3's flag.
5. **MV local apply spin:** `Keyspace.applyInternal` non-deferrable branch sleeps 10 ms in
   a loop (Keyspace.java:514-529). Not on the coordinator await path, but lethal if
   I1/I2 shard threads ever execute a view-updating apply.
6. **Batchlog** = 2 sequential parks (W3) — convert as a chain or exclude batch statements
   from the I3 flag initially.
7. **Failure aggregation is safe:** `failureReasonByEndpoint` is concurrent (put in
   onFailure :274-282), exception built from an `ImmutableMap.copyOf` snapshot (:185).
   Exceptions must complete the request future and convert via the existing
   `ErrorMessage.fromException` path (Dispatcher.java:447-475) on the completing thread.
8. **Monitoring/slow-query is migration-safe:** MonitorableImpl uses `approxTime` state,
   no `Thread.currentThread()` capture (db/monitoring/MonitorableImpl.java:43-50,
   :124-136); MonitoringTask queues async.
9. **Tracing:** `traceState` captured once and passed (AbstractReadExecutor.java:94) —
   safe; trace writes from continuations must come from the captured locals.
10. **Accord:** coordinator-side blocks NTR today via `awaitAndGet`
    (AccordService.java:1135-1138) over an async core (:1153-1164). I3 need not touch
    Accord internals; a later step could route `IAccordResult` into the same completion
    plumbing.
11. **Audit/FQL timing:** `QueryEvents.notifyQuerySuccess` fires on the NTR thread at
    :119 after synchronous execute — under I3 it must move to the continuation or it
    reports dispatch-only latency.
12. **Per-connection concurrency already exists** (each message independently submitted
    at Dispatcher.java:131), so async completion does not newly introduce concurrent
    responses per connection; ordering is by streamId; the Flusher queue is
    order-agnostic. ResultSet building has no shared builders; building on a non-NTR
    thread is safe provided locals are installed.

## RESOLVED

1. io_uring seam = `ChannelProxy.read` (ChannelProxy.java:174); callers
   SimpleChunkReader:41, CompressedChunkReader:413/:429; fd reachable (:218-221).
2. Default `mmap_index_only` → data=standard/index=mmap (DatabaseDescriptor.java:675-679)
   — data-file misses already pread; `direct` rejected (:685-688). PoC pins `standard`;
   `mmap` gives the ring nothing.
3. Cache-miss read runs on the calling thread (Caffeine ImmediateExecutor,
   ChunkCache.java:152) — under I2 that's the shard thread; thread-local ring works.
4. Read/write timeouts already async-capable (reaper + invokeOnFailure=true); Paxos v1
   latches not covered.
5. Response flushing thread-safe from arbitrary threads (Flusher CLQ + event-loop drain).
6. Awaits signalled inline on REQUEST_RESPONSE (ResponseVerbHandler:62-87); expiry on
   INTERNAL_RESPONSE.
7. No hint logic depends on the parked coordinator thread.
8. idealCL accounting survives async.
9. Ten Stage.READ submission sites enumerated; flag branches at submission sites.
10. NTR pool = 128 threads to absorb parking; queue-age backpressure signal dies under I3.
11. Accord coordinator await is independent of I3's flag.

## OPEN QUESTIONS (for user/spec)

1. I2 routing scope beyond :168 — also read-repair (:102), short-read (:191),
   replica-filtering (:175)? (Recommend all four — same key, same shard.)
2. Seam choice — ChannelProxy.read branch (one method, complete coverage, per-read
   thread-local lookup process-wide) vs UringChunkReader decorator in FileHandle.Builder
   (read-path-only, one new class, misses BufferManagingRebufferer-without-cache).
   Recommend ChannelProxy.
3. I2 benchmark config — pin `disk_access_mode: standard`; decide chunk-cache size and
   compressed vs uncompressed table for the A/B.
4. I3 PoC flag scope — proposed: single-partition reads + plain mutations only; Paxos,
   counters, batch/batchlog, truncate, auth, schema ops stay blocking.
5. Continuation executor — REQUEST_RESPONSE (requires converting every nested await and
   accepting INTERNAL_RESPONSE-delivered timeouts) vs a dedicated completion executor
   tolerating residual blocking. Which risk profile for the PoC?
6. Backpressure replacement — outstanding-ops limit value and location; shrink
   `native_transport_max_threads` in the flag-on A/B (recommend yes — it IS the
   measurable claim).
7. **FlushItem/payload release audit** — `toFlushItem` and request-frame release
   accounting when the completing thread isn't the NTR thread (Dispatcher.java:483,
   Flusher FlushItem.release): buffer refcount paths need a dedicated verification pass
   before coding (only piece of §5 not fully pinned).
8. I1 interaction — I2 must use the SAME boundaries source as I1
   (`cfs.localRangeSplits(n)`) or shard-locality claims break.
9. MV/counter exclusion enforcement — flag-exclude those tables from shard routing vs
   assert-and-fallback.
