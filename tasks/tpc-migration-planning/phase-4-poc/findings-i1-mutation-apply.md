# Findings — I1 shard-routed mutation apply + shard runtime (agent report, 2026-07-07)

Verbatim exploration report; class-level inventory distilled into `expected-changes.md`.
All paths `src/java/org/apache/cassandra/` abbreviated `o.a.c/`; line numbers verified on
tpc-migration 2026-07-07.

## 1. Full local-apply call graph

### 1a. Coordinator self-write

1. `ModificationStatement.java:693` / `BatchStatement.java:559` →
   `StorageProxy.mutateWithTriggers(...)` (`o.a.c/service/StorageProxy.java:1224`) — runs
   on a **Native-Transport-Requests SEP worker** (`o.a.c/transport/Dispatcher.java:62`
   pool creation, `:131` submit).
2. `StorageProxy.mutate(List<IMutation>, CL, RequestTime)` — `StorageProxy.java:975`;
   counters branch to `mutateCounter` at `:990`, plain writes to
   `performWrite(mutation, cl, localDC, standardWritePerformer, null, plainWriteType,
   requestTime)` at `:992`.
3. `performWrite` — `StorageProxy.java:1729-1754`: builds `ReplicaPlan.ForWrite`,
   `AbstractWriteResponseHandler`, calls `performer.apply(...)` at `:1753`.
4. `standardWritePerformer` — `StorageProxy.java:284-287`: →
   `sendToHintedReplicas(mutation, targets, handler, localDC, Stage.MUTATION, requestTime)`.
5. `sendToHintedReplicas` — `StorageProxy.java:1824-1932`: `destination.isSelf()` →
   `insertLocal` (`:1858-1861`); local apply at `:1918`:
   `performLocally(stage, localReplica, mutation::apply, responseHandler, mutation,
   requestTime)`. Remote replicas via `MessagingService.sendWriteWithCallback` (`:1924`).
6. `performLocally(Stage, Replica, Runnable, RequestCallback, Object, RequestTime)` —
   `StorageProxy.java:2023-2066`: **`stage.maybeExecuteImmediately(new
   LocalMutationRunnable(...))` at `:2025`** (spec cited `:1995` — that is the *other*
   overload, `StorageProxy.java:1993-2021`, used by batchlog store/remove). The runnable
   calls `runnable.run()` (= `mutation.apply()`) then `handler.onResponse(null)`
   (`:2036-2038`), failure → `handler.onFailure` (`:2044`).
   - **Thread transition:** `Stage.MUTATION.maybeExecuteImmediately`
     (`o.a.c/concurrent/Stage.java:130`) → `SEPExecutor.maybeExecuteImmediately`
     (`o.a.c/concurrent/SEPExecutor.java:205-227`): if a work permit is free, **runs
     inline on the calling NTR thread**; else enqueues to the MUTATION SEP queue.
7. `LocalMutationRunnable.run()` — `StorageProxy.java:3169-3233`: deadline check at
   `:3184-3204` — if past deadline, records self-dropped message and **converts the apply
   into a hint** (`submitHint`, `:3195-3202`, non-transient replicas only). This logic
   lives *inside* `run()`, so any executor running the same object reproduces it free.
8. → `Mutation.apply()` — `o.a.c/db/Mutation.java:314-318` →
   `Keyspace.apply(mutation, durableWrites, true, true)` → §1b step 5.

### 1b. Replica write

1. `InboundMessageHandler.java:429`: `header.verb.stage.execute(ExecutorLocals.create(state),
   task)` — netty messaging loop → MUTATION SEP queue (`MUTATION_REQ` → MUTATION,
   `o.a.c/net/Verb.java:202`).
2. `MutationVerbHandler.doVerb(Message<Mutation>)` — `o.a.c/db/MutationVerbHandler.java:52-78`:
   expiry check at `:54-59` (dropped-message metric); `MessageParams.reset()` `:61`; size
   validation `:62-63`; forwarding `:65-67`; then `processMessage` (inherited,
   `o.a.c/db/AbstractMutationVerbHandler.java:57-76`) — TCM token-ownership +
   schema-epoch checks `:59-64` (**can block** fetching TCM log, `:93`), then
   `applyMutation`.
3. `MutationVerbHandler.applyMutation` — `MutationVerbHandler.java:80-84`:
   `Map<ParamType,Object> params = MessageParams.capture()` (**captured on the stage
   thread, BEFORE apply** — `:82`) then **`message.payload.applyFuture().addCallback(o ->
   respond(...), wto -> failed())`** (`:83`). The callback runs on whichever thread
   completes the future = the applying thread.
4. `Mutation.applyFuture()` — `Mutation.java:289-293` →
   `Keyspace.applyFuture(this, durableWrites, true)` →
   `applyInternal(mutation, makeDurable, true, true, /*isDeferrable*/true,
   new AsyncPromise<>())` (`o.a.c/db/Keyspace.java:396-399`).
5. `Keyspace.applyInternal` — `Keyspace.java:447-599`, in order:
   - **View-lock branch** (`:459-548`): `requiresViewUpdate = updateIndexes &&
     viewManager.updatesAffectView(mutation, false)` (`:459`); per-`(key,tableId)`
     `ViewManager.acquireLockFor` (Striped locks, `o.a.c/db/view/ViewManager.java:69`)
     with tryLock; on failure either WTE (`:496-500`), **re-enqueue self onto
     `Stage.MUTATION.execute` if deferrable** (`:509-511`), or sleep-retry (`:523`).
   - **`getWriteHandler().beginWrite(mutation, makeDurable)`** (`:549`) =
     `CassandraKeyspaceWriteHandler.beginWrite`
     (`o.a.c/db/CassandraKeyspaceWriteHandler.java:42-65`): **`Keyspace.writeOrder.start()`**
     first (`:47`; global static OpOrder, `Keyspace.java:100-102`), **then**
     `CommitLog.instance.add(mutation)` (`:53`,`:99`). CommitLog.add
     (`o.a.c/db/commitlog/CommitLog.java:301-344`): serialize to thread-local scratch
     (`:307`), `segmentManager.allocate` (CAS loop; `:312`), write+checksum,
     `executor.finishWriteFor(alloc)` (`:337` — **blocks for fsync in `batch` commitlog
     mode**; periodic returns immediately). Ordering: **OpOrder group opens before CL
     add; memtable apply after CL add; group closes after memtable apply**
     (try-with-resources `:549-589`).
   - Per-`PartitionUpdate` loop (`:552-583`): view replica updates pushed synchronously
     if required (`:568` — `pushViewReplicaUpdates` → `TableViews.java:200` →
     `StorageProxy.mutateMV`, which for a self-paired endpoint **applies the view-table
     mutation synchronously on the same thread**, `StorageProxy.java:1175-1176`); then
     `cfs.getWriteHandler().write(upd, ctx, updateIndexes)` (`:579`) =
     `CassandraTableWriteHandler.write` → `cfs.apply`.
   - `future.trySuccess(null)` (`:586`) — **completes the AsyncPromise on the applying
     thread → the ack send in MutationVerbHandler runs here.**
6. `ColumnFamilyStore.apply` — `o.a.c/db/ColumnFamilyStore.java:1515-1553`:
   `data.getMemtableFor(opGroup, commitLogPosition)` (`:1523`;
   `o.a.c/db/lifecycle/Tracker.java:390-406` — lock-free scan of `view.liveMemtables`,
   memtable chosen by OpOrder group + CL position, **not** by "current");
   `newUpdateTransaction` (2i indexer, `:1524`,`:1555-1560`); **`mt.put(update, indexer,
   opGroup)`** (`:1525`); row-cache invalidation (`:1527`); table metrics (`:1528-1542`).
7. `TrieMemtable.put` — `o.a.c/db/memtable/TrieMemtable.java:186-208`:
   **`MemtableShard shard = shards[boundaries.getShardForKey(key)]`** (`:191`) →
   `shard.put(...)` (`:192`); flush trigger on trie size (`:194-198`).
8. `TrieMemtable.MemtableShard.put` — `TrieMemtable.java:550-596`: **`ReentrantLock
   writeLock` (`:513`)**; `tryLock` at `:553`; **`metrics.uncontendedPuts.inc()` (`:556`)
   / `metrics.contendedPuts.inc()` (`:560`) / blocking `lock()` +
   `metrics.contentionTime.addNano(...)` (`:562-563`)**. Under the lock:
   `data.putSingleton(...)` into the `InMemoryTrie` (`:572-575`), allocator adjust
   (`:576-577`), then non-atomic RMW of volatile
   `minTimestamp/minLocalDeletionTime/liveDataSize/currentOperations/partitionKeysSize`
   (`:581-585`), `columnsCollector/statsCollector.update` (`:587-588`).

**Shared state touched per write:** global `Keyspace.writeOrder` CAS (`Keyspace.java:102`);
commitlog `allocatePosition` CAS + possible rollover/sync; Striped view locks (MV only);
Tracker view volatile read; per-shard `writeLock`; shared-per-memtable
`MemtableAllocator`; row cache invalidation; thread-local-backed metrics.

## 2. ShardBoundaries lifecycle

- `ShardBoundaries` — `o.a.c/db/memtable/ShardBoundaries.java`: `Token[] boundaries` +
  `Epoch` (`:49-50`); `getShardForToken` linear scan `:67-75`; `getShardForKey` `:80-88`
  (returns 0 when boundaries empty); `shardCount()` `:110-113`; `NONE` singleton `:47`.
- **Computation & caching, per table:** `ColumnFamilyStore.localRangeSplits(int shardCount)`
  — `ColumnFamilyStore.java:1608-1631`. Cache field `cachedShardBoundaries` (`:337`,
  volatile, **one per CFS**). Recomputed when: cache empty, `shardCount` differs, or
  **cached epoch ≠ `ClusterMetadata.current().epoch`** (`:1618-1620`). Returns
  `ShardBoundaries.NONE` when `shardCount==1`, partitioner has no splitter, or TCM not
  initialized (`:1610-1616`). Source ranges: `localRangesWeighted()` (`:1573-1605`) —
  **replication-strategy-dependent, so keyspace-dependent**; local-system keyspaces and
  foreign partitioners get the **full token range** with `RING_VERSION_IRRELEVANT` epoch
  (`:263`, `:1596-1603`). Split by
  `partitioner.splitter().splitOwnedRanges(shardCount, weightedRanges, false)` (`:1624`)
  — deterministic.
- **Pinning per memtable:** `AbstractShardedMemtable` ctor —
  `o.a.c/db/memtable/AbstractShardedMemtable.java:55-63`:
  `boundaries = owner.localRangeSplits(shardCount)` captured **once at memtable
  construction and immutable for the memtable's lifetime**; comment at `:48-53` states
  this is deliberate. Shard count: per-table `shards` memtable option
  (`TrieMemtable.java:758`) else `defaultShardCount` =
  `MEMTABLE_SHARD_COUNT.getInt(availableProcessors)` (`:46`) — **mutable at runtime via
  JMX MBean** `org.apache.cassandra.db:type=ShardedMemtableConfig` (`:39-94`, affects
  only future memtables).
- **When boundaries change:** on flush/truncate/schema-change the `Flush` ctor builds a
  **new memtable** (`ColumnFamilyStore.java:1258`) which re-calls `localRangeSplits` →
  picks up any epoch bump. In-flight writes are directed to old vs new memtable by the
  `Keyspace.writeOrder` barrier + commitLogUpperBound (`ColumnFamilyStore.java:1222-1274`;
  `Tracker.getMemtableFor` `Tracker.java:390-406`). **During a switch, two live memtables
  with *different* boundaries can both accept writes.** No shard-count change happens
  *within* one memtable, ever.
- **Per-table vs global:** boundaries are **per-table** objects but a pure function of
  (shardCount, partitioner, keyspace's owned ranges, epoch). Same keyspace + same shard
  count + same epoch → **equal** boundaries (distinct objects); different keyspaces
  (RF/strategy) or different `shards` options → **different token ranges for the same
  shard index**.
- **Consequence for I1:** a node-global executor pool of N threads works, but the routing
  function must be **per-(table, key)**: `shardId =
  thatTable'sCurrentMemtableBoundaries.getShardForKey(key)`, mapped to executor
  `shardId % N` (or require `shardCount == N`). Router-vs-memtable boundary mismatch is
  unavoidable in corner cases (epoch bump mid-flight, memtable switch race, per-table
  `shards` overrides, multi-table mutations) → forces owner-check-with-lock-fallback
  (§5). Note `AbstractShardedMemtable.boundaries` is `protected` with **no public
  getter** (`:53`) — I1 needs an accessor.

## 3. Per-shard runtime: what exists, what's new

- **No shard executor exists.** `Stage.MUTATION` = `multiThreadedLowSignalStage` →
  `executorFactory().localAware().withJmx("request").shared(jmxName, numThreads,
  listener)` = SEP (`o.a.c/concurrent/Stage.java:47`, `:232-238`), `concurrent_writes`=32
  permits, **unbounded queue** (`SEPExecutor.java:104-108` returns Integer.MAX_VALUE).
- **The Stage seam:** `Stage` holds a lazy `executorSupplier` (`Stage.java:64`, `:73-78`),
  materialized in `executor()` (`:135-148`); `@VisibleForTesting unsafeSetExecutor`
  (`:150-154`). **A flag could swap Stage.MUTATION's executor — evaluated and REJECTED as
  the primary mechanism:** an `ExecutorPlus` receives opaque `Runnable`s and cannot
  extract a token; and MUTATION stage carries non-routable traffic (HINT_REQ
  `Verb.java:206`, READ_REPAIR_REQ `:208`, BATCH_STORE/REMOVE `:210-212`, PAXOS commit
  verbs `:219`,`:287`, batchlog store `StorageProxy.java:1659`, deferred-view re-enqueue
  `Keyspace.java:509`, commitlog replay `CommitLogReplayer.java:330`, hint submission
  `StorageProxy.java:3392`). Routing must happen at call sites where the `Mutation` is in
  hand.
- **Queues:** jctools is a direct dependency (`lib/jctools-core-3.1.0.jar`;
  `MpscUnboundedArrayQueue` used at `o.a.c/journal/Journal.java:133`). Cassandra's own
  non-blocking MPSC **`o.a.c/concurrent/ManyToOneConcurrentLinkedQueue`** was deliberately
  chosen over jctools for netty event loops (`o.a.c/net/SocketFactory.java:92-141`). For
  the PoC, the simplest correct option:
  `executorFactory().localAware().withJmx("request").sequential(name)` →
  `LocalAwareSingleThreadExecutorPlus` (unbounded `newBlockingQueue`,
  `o.a.c/concurrent/ThreadPoolExecutorBuilder.java:160-168`) — gives ExecutorLocals
  propagation (tracing/ClientWarn), thread naming, and **free `ThreadPoolMetrics` JMX
  registration**. A hand-rolled MPSC + `startThread` loop (`ExecutorFactory.java:142`,
  `:305-314`) is the later optimization.
- **ExecutorFactory API** (`o.a.c/concurrent/ExecutorFactory.java`):
  `Global.executorFactory()` (`:197-203`); `.localAware()` `:95`; `.withJmx(type)` `:72`;
  `.sequential(name)` / `.pooled(name, n)` / `.shared(...)` (`:67`, `:232-294`); raw
  `startThread` `:142-152`; `infiniteLoop` `:170-184`. Default
  `ExecutorPlus.maybeExecuteImmediately` = plain `execute`
  (`o.a.c/concurrent/ExecutorPlus.java:55-58`) — only SEP overrides it to run inline.

## 4. Flag + config idiom

- **Precedent:** `CassandraRelevantProperties` enum entries, read once into a static —
  exactly how the sharding knob works: `MEMTABLE_SHARD_COUNT("cassandra.memtable.shard.count")`
  (`o.a.c/config/CassandraRelevantProperties.java:407`) consumed once at
  `AbstractShardedMemtable.java:46`. Boolean-flag precedent:
  `NATIVE_EPOLL_ENABLED("cassandra.native.epoll.enabled", "true")` (`:421`). Getters:
  `getBoolean()` `:839`, `getInt(int)` `:969`.
- **Recommendation:** two entries — `MUTATION_SHARD_ROUTING("cassandra.mutation.shard_routing",
  "false")` and `MUTATION_SHARD_SKIP_LOCK("cassandra.mutation.shard_routing.skip_lock",
  "false")` — each read once into `static final boolean` (read-once, not hot-swappable —
  hot-swap of routing would create mixed-writer windows). No `Config.java`/yaml change
  needed for a PoC.

## 5. Deleting the shard lock (step 2)

What `MemtableShard.writeLock` actually protects (`TrieMemtable.java:550-596`):
1. **`InMemoryTrie` single-mutator contract** — class doc
   `o.a.c/db/tries/InMemoryTrie.java:39-44`: "reads executing concurrently with writes
   from a **single mutator thread**". Readers (point reads `TrieMemtable.java:320-321`,
   range/flush iteration, `getFlushSet` `:360-493`) never take the lock — reader safety
   is by trie design + OpOrder, **not** the lock. The lock exists solely because any SEP
   worker can write any shard.
2. Non-atomic RMW of volatile shard stats (`:581-585`) — single-writer-safe without it.
3. `columnsCollector.update` — thread-safe anyway (`AbstractMemtable.java:185-227`);
   `statsCollector.update` — CAS loop (`:246-260`). Allocator adjustments are atomics
   shared across shards regardless.

So **single-writer-per-shard makes the lock strictly redundant** — flush/snapshot
readers, `switchMemtable`, and OpOrder barriers are unaffected (they never acquire it;
the barrier at `ColumnFamilyStore.java:1285-1286` waits on write *groups*, which routing
doesn't change; `OpOrder.start()` has no thread affinity, `OpOrder.java:105-113`).

**But single-writer must actually hold**, and today these paths write plain-user-table
memtables from non-routed threads:
- Hints: `HintVerbHandler` → `Hint.apply` → `mutation.applyFuture()`
  (`o.a.c/hints/Hint.java:100-113`) on MUTATION stage.
- Read repair: `ReadRepairVerbHandler.java:30` `mutation.apply()` on MUTATION stage.
- Paxos commit: `PaxosState.applyCommit` → `Keyspace.open(...).apply(mutation, true)`
  (`o.a.c/service/paxos/PaxosState.java:722`) on MUTATION stage; plus coordinator-local
  legacy commit `StorageProxy.java:933`.
- **Accord: `TxnWrite.Update.write` → `executor.chain(() -> mutation.apply(false, false))`
  (`o.a.c/service/accord/txn/TxnWrite.java:150`) on AccordExecutor threads** — user
  tables, entirely outside Stage.MUTATION; Accord also writes its own system tables via
  direct `memtable.put` (`o.a.c/service/accord/AccordKeyspace.java:363`).
- Commitlog replay (startup, multi-threaded on MUTATION stage,
  `CommitLogReplayer.java:325-330`), batchlog-replay remotes, MV local pairing
  (`StorageProxy.java:1175` — view tables), counter result apply (leader:
  `applyCounterMutation` + local `performLocally(..., Stage.COUNTER_MUTATION, ...)`,
  `StorageProxy.java:2148-2150` — counter tables only).

**Therefore the safe step-2 guard is a per-put owner check with lock fallback, not
unconditional skip:** in `MemtableShard.put`, `if (skipLockFlag &&
ShardExecutors.currentThreadIsOwnerOf(thisShardIndex)) { apply without lock; assert via
owner-thread field } else { existing tryLock path }`. Owner check requires (a)
`MemtableShard` knowing its own index (pass into ctor at `TrieMemtable.java:133-143`),
and (b) the routed thread's shard id having been computed **from this memtable's own
pinned boundaries** — on mismatch the thread simply isn't owner and takes the lock.
Self-healing for every unrouted path and every boundary race, at the cost of
contendedPuts>0 under paxos/hint/read-repair/Accord traffic (fine — the PoC gate
measures a plain-write workload).

**Routing predicate (step 1) — route only when ALL of:** call site is
`sendToHintedReplicas` local branch with `stage == Stage.MUTATION`
(`StorageProxy.java:1918` — automatically excludes counters at `:2150`, view-batch at
`:1214`, batchlog store/remove at `:1659/:1675`) or `MutationVerbHandler.applyMutation`
(`:83` — automatically excludes counter/hint/read-repair/paxos/batch verbs); AND
`!viewManager.updatesAffectView(mutation)` (avoid nested synchronous view apply +
Striped-lock sleep-retry on a shard thread); AND keyspace is not local-system
(`SchemaConstants.isLocalSystemKeyspace`); AND every updated table's current memtable is
an `AbstractShardedMemtable` whose boundaries agree on one shard id for the key
(multi-table same-key mutations: same keyspace ⇒ same ranges, but per-table `shards`
option can differ ⇒ compute per table, fall back on disagreement). Non-routable →
existing `maybeExecuteImmediately` path unchanged.

## 6. Counters + measurement

- **Micro counters** (`o.a.c/metrics/TrieMemtableMetricsView.java`): per-table, shared
  across that table's successive memtables (`:29-30`). JMX (factory `:86-95`):
  `org.apache.cassandra.metrics:type=TrieMemtable,keyspace=<ks>,scope=<table>,name=<metric>`
  with names **`Uncontended memtable puts`**, **`Contended memtable puts`** (`:35-36`),
  contention latency as **`Contention timeLatency`** / **`Contention timeTotalLatency`**
  (LatencyMetrics suffixing, `o.a.c/metrics/LatencyMetrics.java:94-95`), plus
  `Shard sizes during last flush` min/max/avg.
- **Macro/A-B extras:** MutationStage pool metrics
  `org.apache.cassandra.metrics:type=ThreadPools,path=request,scope=MutationStage,...`
  (`o.a.c/metrics/ThreadPoolMetrics.java:36-44,105-112`); new shard executors get the
  same family free via `withJmx`; table `writeLatency`/`viewLockAcquireTime`
  (`ColumnFamilyStore.java:1532`, `Keyspace.java:546`); dropped-mutation counters
  (`MutationVerbHandler.java:57`, `StorageProxy.java:3189`). Recommend one **new**
  counter: `misroutedPuts` (owner-check failed → lock fallback) — the direct health
  metric for the boundary-agreement assumption.

## 7. Adversarial pass — new behavior-determining findings

1. **Skiplist default trap:** trunk's default memtable is `SkipListMemtable`
   (`conf/cassandra.yaml:820-825` `default: inherits: skiplist`;
   `o.a.c/schema/MemtableParams.java:99`). Unsharded memtables have no `getShardForKey`,
   no lock, no counters. **I1 must gate per-table on the memtable being sharded, and the
   benchmark must configure `memtable: trie`.**
2. **Ack from shard thread:** safe. `MessageParams.capture()` happens on the stage thread
   *before* apply (`MutationVerbHandler.java:82`); `MessagingService.send` and
   `AbstractWriteResponseHandler.onResponse` are thread-safe. No ordering contract
   between acks of different mutations.
3. **Expiry re-check after the extra hop:** replica-side expiry is checked once at
   stage-dequeue (`MutationVerbHandler.java:54`); a routed task adds a second queue. Add
   a dequeue-time re-check or accept slightly-later drops. Behavior decision for spec.
4. **Backpressure parity:** MUTATION's SEP queue is already unbounded; SEP permits bound
   concurrency (32) not depth; the true write throttle is upstream (NTR pool +
   `Dispatcher` queue-time backpressure, `Dispatcher.java:358`). An unbounded per-shard
   inbox is parity, not a regression. No bound needed for PoC; note for CEP.
5. **Blocking `Keyspace.apply` callers must NOT be routed-and-awaited** (CASSANDRA-12689
   deadlock class, comment `Keyspace.java:418-421`): route only the
   `applyFuture`/`performLocally` (async) entry points; blocking callers (paxos commit,
   counters) stay on their threads and rely on the lock fallback.
6. **Mutation = one partition key** (`Mutation.java:78`), so one token — but possibly
   **multiple tables** (one keyspace); unlogged batches fan out as separate per-partition
   Mutations (`StorageProxy.java:982-993`); logged batches via
   `syncWriteBatchedMutations(..., Stage.MUTATION, ...)` (`:1524`) routed per-mutation.
7. **Commitlog `batch` mode:** routed `applyInternal` performs `CommitLog.add` on the
   shard thread, and batch mode blocks in `finishWriteFor` until fsync
   (`CommitLog.java:337`) — one slow fsync stalls a whole shard. PoC: periodic only
   (default) or exclude when `commitlog_sync: batch`.
8. **2i on the shard thread:** index updates run inline inside `Memtable.put` via
   `UpdateTransaction` callbacks — they move to the shard thread with the put. SAI
   structures are per-memtable; no SEP-thread assumption found, but **custom Index
   implementations can run arbitrary user code on the shard thread** — flag for PoC
   verification.
9. **TCM epoch change mid-flight / memtable switch mid-apply:** handled structurally
   (memtable pins boundaries; owner-check falls back on disagreement; OpOrder groups are
   thread-agnostic). Residual effect = transient lock-taking, visible in `misroutedPuts`.
10. **Accord coexistence is already live on this branch:** `TxnWrite.java:150` writes
    user tables from Accord executors *today*. Owner-check handles correctness, but any
    A/B on an Accord-enabled table will not reach contendedPuts→0 — pin the PoC workload
    to non-Accord tables.
11. **NTR inline-apply loss:** today a local apply can run inline on the NTR thread with
    zero hops when a MUTATION permit is free (`SEPExecutor.maybeExecuteImmediately:205-227`).
    Routing always costs one hop. The I1 p99 gate must beat that baseline — the honest
    cost being measured; state it in the spec.

## RESOLVED (question → answer)

1. Spec line `StorageProxy.java:1995` drifted in meaning: `:1995` is the batchlog
   overload; the mutation local-apply is `performLocally` at `:2023` / mEI at `:2025`,
   called from `:1918`.
2. Config-only Stage executor swap: NO (opaque Runnables; mixed stage traffic).
3. Executor: node-global N threads, per-(table,key) shard-id computation.
4. Two tables' shard N same range? Same keyspace+count+epoch yes; else no.
5. In-flight work on boundary change: memtable pins boundaries; owner-check absorbs
   mismatch windows.
6. Lock protects writer-writer exclusion only; skip safe ONLY under per-put owner check.
7. Ack thread / deadline / MessageParams: all safe or reproduced free.
8. Routing predicate exclusions enumerated (§5).
9. MPSC availability: jctools 3.1.0 + in-house queue; `sequential()` executor is the
   right PoC starting point.
10. Micro counters exist and are JMX-readable (names in §6).

## OPEN QUESTIONS (for user/spec)

1. **N and CPU budget:** N = cores shard threads added on top of the existing SEP pool.
   Accept oversubscription for the PoC, or reduce `concurrent_writes` when flag on?
2. **Shard threads: plain executors or pinned?** I1 needs no CPU pinning; confirm out of
   scope for I1.
3. **Expiry re-check on shard dequeue:** include or defer?
4. **Commitlog `batch`/`group` sync mode:** exclude from routing, or accept shard-thread
   blocking (default `periodic` unaffected)?
5. **Step-2 flag semantics:** owner-check skip (always-correct) vs hard no-lock variant
   (needs workload preconditions as policy, not code guarantee). Which does Phase 4 want?
6. **Views excluded for the whole PoC?** (routed mutations never hit the deferred-view
   re-enqueue since views are excluded — confirm exclusion stands program-wide.)
