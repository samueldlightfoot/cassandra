# Explorer report — SEDA stage & executor inventory (2026-07-05)

All paths under `src/java`. Evidence = file:line.

## 1. Stage enum inventory (`concurrent/Stage.java:44-62`, factories `:199-243`)

| Stage | Decl | Impl method | Threads | Backing | Queue |
|---|---|---|---|---|---|
| READ | :46 | multiThreadedLowSignalStage (232) | getConcurrentReaders (`concurrent_reads`=32, Config.java:251) | SEP (SharedExecutorPool.SHARED) | per-executor ConcurrentLinkedQueue, unbounded (SEPExecutor.java:70,107) |
| MUTATION | :47 | same | getConcurrentWriters (=32, :252) | SEP | unbounded |
| COUNTER_MUTATION | :48 | same | getConcurrentCounterWriters (=32, :253) | SEP | unbounded |
| VIEW_MUTATION | :49 | same | getConcurrentViewWriters (=32, :254) | SEP | unbounded |
| ACCORD_MIGRATION | :50 | same | getAccordConcurrentOps | SEP | unbounded |
| GOSSIP | :51 | singleThreadedStage (216) | 1 | LocalAwareSingleThreadExecutorPlus | unbounded (ThreadPoolExecutorBuilder.java:160-168) |
| REQUEST_RESPONSE | :52 | multiThreadedLowSignalStage | availableProcessors | SEP | unbounded |
| ANTI_ENTROPY | :53 | singleThreadedStage | 1 | dedicated single | unbounded |
| MIGRATION | :54 | migrationStage (208) | 1 | LocalAwareSingleThread (sequential) | unbounded |
| MISC | :55 | singleThreadedStage | 1 | dedicated single | unbounded |
| TRACING | :56 | tracingStage (199) | 1 | SingleThreadExecutorPlus (NOT localAware) | **bounded 1000**; rejection → recordSelfDroppedMessage(_TRACE) (:204-205) |
| INTERNAL_RESPONSE | :57 | multiThreadedStage (224) | availableProcessors | LocalAwareThreadPoolExecutorPlus (dedicated) | unbounded |
| IMMEDIATE | :58 | immediateExecutor (240) | 0 | ImmediateExecutor.INSTANCE — caller thread | none |
| PAXOS_REPAIR | :59 | multiThreadedStage | P | dedicated pool | unbounded |
| INTERNAL_METADATA | :60 | multiThreadedStage | P | dedicated pool | unbounded |
| FETCH_METADATA | :61 | singleThreadedStage | 1 | dedicated single | unbounded |

`shutdownBeforeCommitlog=true` for MUTATION/COUNTER/VIEW/GOSSIP (Stage.java:47-51; used by
mutatingExecutors/shutdownAndAwaitMutatingExecutors :163-184). SEP stages report
getMaxTasksQueued()==MAX_VALUE (SEPExecutor.java:104-108) — back-pressure lives elsewhere.

## 2. Executor infrastructure

Interfaces: ExecutorPlus (ExecutorPlus.java:42; maybeExecuteImmediately default :55-58),
SequentialExecutorPlus, LocalAwareExecutorPlus (propagates ExecutorLocals),
ScheduledExecutorPlus. Concretes: ThreadPoolExecutorBase → ThreadPoolExecutorPlus /
SingleThreadExecutorPlus (+LocalAware variants), ScheduledThreadPoolExecutorPlus (**always 1
core thread**, :79), SEPExecutor, ImmediateExecutor (all sizes 0, :148-154), WrappedExecutorPlus.

ExecutorFactory (ExecutorFactory.java): Global.executorFactory() (:197,200-203). Builder:
`.localAware().withJmx(type).shared(name, threads, onSetMaxSize)` →
SharedExecutorPool.SHARED.newExecutor (:256-259) — how SEP stages are made. `.pooled/.sequential`
→ dedicated builders (:232-294); `scheduled(...)` (:296-303); raw threads `startThread`
(:305-314); InfiniteLoopExecutor `infiniteLoop` (:316-320).

### SEP scheduling model (SharedExecutorPool.java:40-64 doc; one global SHARED :65-67)
Per-executor task queues + one shared self-scheduling worker pool that hops between executors
(workers acquire whole-executor "work permits", not per-task stealing):
- Each SEPExecutor: ConcurrentLinkedQueue tasks (:70) + packed AtomicLong permits — low 32 bits
  queued task permits, high 32 bits available work permits (init = maximumPoolSize) (:59-79).
- addTask (:121-138): enqueue, ++taskPermits; only if 0 prior → pool.maybeStartSpinningWorker()
  (starts one only if spinningCount==0, SharedExecutorPool.java:129-136).
- SEPWorker.run (SEPWorker.java:90-218): SPINNING → selfAssign scans executors, takeWorkPermit(true)
  (:256-277); before each task, assigned.maybeSchedule() spawns partner if backlog (:151;
  SEPExecutor:112-119); drains via takeTaskPermit(true) (:146-166); returns permit (:172),
  reassigns or startSpinning (:184-185); randomized sleep-spin sized by spinningCount (:300-330);
  maybeStop deschedules surplus (:338-367).
- **maybeExecuteImmediately (SEPExecutor.java:204-227): takeWorkPermit(false) → run INLINE on
  calling thread, return permit, maybeSchedule; else enqueue.** The inline-execution hook.

SEP users: READ, MUTATION, COUNTER_MUTATION, VIEW_MUTATION, ACCORD_MIGRATION, REQUEST_RESPONSE
+ Native-Transport-Requests + Native-Transport-Auth-Requests. Dedicated: GOSSIP, ANTI_ENTROPY,
MIGRATION, MISC, TRACING, FETCH_METADATA, INTERNAL_RESPONSE, PAXOS_REPAIR, INTERNAL_METADATA.

## 3. Stage usage census

Dominant dispatch = messaging: every Verb carries a Stage (Verb.java:430) and inbound dispatch is
`header.verb.stage.execute(ExecutorLocals.create(state), task)` (InboundMessageHandler.java:420-430).

Verb→Stage (Verb.java:201-408): MUTATION ← MUTATION_REQ:202, VIRTUAL_MUTATION_REQ:204,
HINT_REQ:206, READ_REPAIR_REQ:208, BATCH_STORE/REMOVE:210/212, PAXOS_PREPARE/PROPOSE/COMMIT:215-219,
TRUNCATE:222, PAXOS2_*:278-287. READ ← READ_REQ:228, RANGE_REQ:230.
COUNTER_MUTATION ← :225. REQUEST_RESPONSE ← all *_RSP (MUTATION_RSP:201, READ_RSP:227,
RANGE_RSP:229, REPAIR_RSP:256, FAILURE_RSP:389, REQUEST_RSP:399).
GOSSIP ← :232-240. MIGRATION ← SCHEMA_*:244-252. ANTI_ENTROPY ← repair verbs :257-271.
MISC ← :273-276, 320-323, Accord misc :363-386. PAXOS_REPAIR ← :288-300.
IMMEDIATE ← PAXOS2_CLEANUP_FINISH_PREPARE_REQ:296, nearly all ACCORD_*:326-383, _TEST:394-395.
INTERNAL_RESPONSE ← _SAMPLE:393, INTERNAL_RSP:402, UNUSED_CUSTOM_VERB:408.
INTERNAL_METADATA ← TCM_*:303-316. FETCH_METADATA ← TCM_FETCH_*:306-318, ACCORD_FETCH:377-381.
TRACING ← _TRACE:392.

Direct call sites: READ — AbstractReadExecutor.makeRequests:168 (**maybeExecuteImmediately**,
LocalReadRunnable), RangeCommandIterator:223, AbstractReadRepair:102 (mEI),
ReplicaFilteringProtection:175 (mEI), ShortReadPartitionsProtection:191 (mEI),
CacheService:383,412. MUTATION — Keyspace.applyInternal:509 (deferred view re-enqueue),
HintsDispatcher:284 (mEI), CommitLogReplayer:330, StorageProxy:3392,
ConsensusKeyMigrationState:408. GOSSIP — Gossiper:536. INTERNAL_RESPONSE — RepairSession:512.
ANTI_ENTROPY — Validator:239,298. MISC — AccordFastPathCoordinator:215, PaxosRepairState:135.
ACCORD_MIGRATION — AccordTopologyService:162, BlockingReadRepair:251. TRACING — TracingImpl:110.

## 4. Pools OUTSIDE Stage

| Subsystem | Pool | Creation | Threads |
|---|---|---|---|
| Native transport requests (SEP) | "Native-Transport-Requests" | Dispatcher.java:62-65 | native_transport_max_threads=128 (Config.java:352) |
| Native transport auth (SEP) | "Native-Transport-Auth-Requests" | Dispatcher.java:81-84 | max(1, auth threads) |
| Native transport netty | workerGroup Epoll/Nio | NativeTransportService.java:67-76; Server.java:108-111 | netty default 2×P |
| Internode netty | acceptGroup 1; "Messaging-EventLoop" defaultGroup; "Streaming-EventLoop" | SocketFactory.java:181-197 | EVENT_THREADS = P (:90) |
| Messaging sync work | synchronousWorkExecutor | SocketFactory.java:185 | unbounded |
| Compaction | CompactionExecutor | CompactionManager.java:172; 2218-2239 | concurrent_compactors |
| | ValidationExecutor :173;2295-2320 | concurrent_validations | |
| | CacheCleanupExecutor :174;2331-2337 (1) · ViewBuildExecutor :175;2323-2329 · SecondaryIndexExecutor :180;2354-2359 (2) | | |
| Flush | "MemtableFlushWriter" ColumnFamilyStore.java:199-201 (getFlushWriters); "MemtablePostFlush" :204-206 (1, sequential); "MemtableReclaimMemory" :208-210 (1); "PerDiskMemtableFlushWriter_N" :212-216,3399-3418 (per dir) | | |
| Commitlog | "COMMIT-LOG-ALLOCATOR" InfiniteLoop AbstractCommitLogSegmentManager.java:160; sync thread AbstractCommitLogService.java:155 | 1+1 NON_DAEMON | |
| Streaming | StreamReceiveTask.java:48 (unbounded); StreamTransferTask.java:54 (timeouts); StreamingMultiplexedChannel.java:134 (fileTransfer), :173 ("Stream-Deserializer-*" 1/connection) | | |
| Repair | RepairSession.java:202 ("RepairJobTask" unbounded); RepairCoordinator.java:527-530 (jobThreads), :554 (driver); LocalSessions.java:891; AutoRepair.java:129-130 | | |
| Scheduled | scheduledFastTasks/scheduledTasks/nonPeriodicTasks/optionalTasks ScheduledExecutors.java:37-52 | 1 each | |

## 5. Inline-execution paths

maybeExecuteImmediately runs inline if the TARGET executor has a free work permit. Read path:
AbstractReadExecutor.makeRequests:164-169 (local read deferred to last, then mEI); also
read-repair/RFP/SRP sites above. Mutation: HintsDispatcher:284. Normal coordinator write uses
queued execute / performLocally.

Native transport: netty loop does NOT execute queries. Dispatcher.dispatch →
requestExecutor.submit(RequestProcessor) (Dispatcher.java:107-132); RequestProcessor.run
(:313-318) on an NTR SEP worker parses/executes CQL. Local reads may then run INLINE ON THE NTR
THREAD via Stage.READ.maybeExecuteImmediately (READ permit free) or enqueue to READ. Netty
threads never run stage work.

## 6. Thread census (P = availableProcessors)

- SEP shared pool: floats 0 → Σ work permits ≈ 32×4 + accordOps + P + 128 + auth ≈ **~290+P**
  saturated; far fewer steady.
- Dedicated stages: 6 singles + 3P (INTERNAL_RESPONSE, PAXOS_REPAIR, INTERNAL_METADATA).
- Netty: ~4P+1 (+sync work).
- Compaction group: compactors + validations + 1 + 1 + 2.
- Flush: flushWriters × (1 + #dataDirs) + 2.
- Commitlog 2; Scheduled 4; streaming/repair on demand.
- Order of magnitude P=8 at rest: **~50–80 threads**; SEP can add ~290 under load.

Caveats: dedicated pools allow core-thread timeout (may be 0 live idle); SEP work permits are
the real concurrency knobs; the stage boundary is permeable (inline execution).
