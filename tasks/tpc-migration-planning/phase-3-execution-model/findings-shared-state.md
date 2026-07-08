# Explorer report — Shared mutable state census, hot paths (2026-07-05)

Legend: already-sharded · shardable · global-lock · global-but-atomic · global-but-rare.

## 1. Local write path (Keyspace.apply → CFS.apply → Memtable.put → CommitLog.add)

- **Keyspace.writeOrder** — single global static OpOrder (Keyspace.java:102; comment :100-101:
  global "since we need to order writes across Keyspaces in the case of Views (batchlog of view
  mutations)"). Started per mutation: CassandraKeyspaceWriteHandler.beginWrite →
  writeOrder.start() (:47). start() spins on current.register() (OpOrder.java:105-113) = CAS
  loop on ONE shared int `running` (OpOrder.java:206-216, updater :170). **global-but-atomic;
  every write, every core, all tables.**
- **MV lock** — only when updatesAffectView (Keyspace.java:459-548); key
  hash(partitionKey, tableId) (:473) → ViewManager.acquireLockFor (:229-233), backed by
  `Striped.lazyWeakLock(getConcurrentViewWriters()*1024)` (ViewManager.java:69). **shardable**
  (token-keyed); MV tables only.
- **Counter lock** — CounterMutation.LOCKS Striped.lazyWeakLock(...*1024) (CounterMutation.java:73),
  bulk-acquired by (cfId, key) hash (:199-225). **shardable**; counter tables only.
- **CFS.apply: no CFS-wide lock** (ColumnFamilyStore.java:1515-1553): memtable via
  data.getMemtableFor (lock-free volatile View read, Tracker.java:390-406) → mt.put →
  invalidateCachedPartition (row cache) → thread-local metrics. **shardable / per-table.**
- **Memtable structure**: SkipListMemtable (unsharded), ShardedSkipListMemtable, TrieMemtable.
  **TrieMemtable is token-sharded** (MemtableShard[] shards, TrieMemtable.java:114); shard
  chosen per key `shards[boundaries.getShardForKey(key)]` (:191). Default shard count =
  MEMTABLE_SHARD_COUNT.getInt(availableProcessors) — **CPU cores**
  (AbstractShardedMemtable.java:46, applied :61-62 via owner.localRangeSplits(shardCount)).
  Boundaries scan: ShardBoundaries.java:67-88, shardCount :110-113. Each shard: ReentrantLock
  writeLock (:513); put() tryLock + contended/uncontended metrics (:550-596); content =
  single-producer InMemoryTrie (:528). **already-sharded by token — the primary seed.**
- **Memtable memory**: global static MEMORY_POOL (AbstractAllocatorMemtable.java:61, built :80-114);
  per-memtable allocator (MEMORY_POOL.newAllocator(), :120) **shared across ALL of that
  memtable's shards** (single allocator to every MemtableShard, TrieMemtable.java:129,140).
  Pool accounting global atomics (SubPool allocated/reclaiming, MemtablePool.java:113-114,
  155-220, 264-266); one MemtableCleanerThread + WaitQueue hasRoom per pool (:46,55).
  NativeAllocator: currentRegion AtomicReference + trySwapRegion CAS (NativeAllocator.java:65,
  212-258); Region.nextFreeOffset AtomicInteger (:324ff). flushLargestMemtable scans all
  (AbstractAllocatorMemtable.java:257-326). **pool accounting global-but-atomic; shared
  allocator across sibling shards = contention across would-be cores; cleaner global-but-rare.**
- **CommitLog.add** (CommitLog.java:301-344): serialize to thread-local scratch (:307) →
  segmentManager.allocate (:312). **Within-segment allocation = ONE AtomicInteger
  allocatePosition CAS loop + parkNanos backoff for ALL cores** (CommitLogSegment.java:103,
  242-257). Per-segment appendOrder OpOrder (:205,267,294-298); dirty tracking :216.
  **Rollover = synchronized(this) on the manager** (advanceAllocatingFrom,
  AbstractCommitLogSegmentManager.java:298-323; standard manager loop
  CommitLogSegmentManagerStandard.java:47-56; volatile fields prepared by ONE background
  manager thread :83,95). **Sync = one background thread** (CommitLog.java:88,120-130).
  Segment buffers from per-manager SimpleCachedBufferPool (:115), allocated ahead.
  **Classification: allocatePosition global-but-atomic (hot); rollover global-lock-but-rare
  (~1/32MiB); sync global-but-rare.**
- **2i hooks**: newUpdateTransaction per-table (ColumnFamilyStore.java:1555-1560). **shardable.**

## 2. Local read path

- **Per-TABLE OpOrder readOrdering** (ColumnFamilyStore.java:299); started in
  ReadExecutionController.forCommand (ReadExecutionController.java:129-153), held for whole
  query. **global-but-atomic per table.**
- **Tracker/View**: volatile copy-on-write (Tracker.java:101); reads lock-free (getView :622-625;
  CFS.select → data.getView, ColumnFamilyStore.java:2064-2069). Writes replace immutable View
  under fair ReentrantLock viewUpdateLock (:100,171-189; comment :99 "less CPU/garbage than
  CAS") — flush/compaction only. **reads lock-free; write side per-table rare.**
- **SSTable Ref counting**: Ref.GlobalState.counts AtomicInteger (Ref.java:357; CAS :377-380;
  release :389). **Point reads do NOT per-sstable ref**: queryMemtableAndDisk uses
  cfs.select(View.select(LIVE, key)) (SinglePartitionReadCommand.java:733) → ViewFragment
  WITHOUT Refs.tryRef (ColumnFamilyStore.java:2064-2069); safety = readOrdering group.
  selectAndReference (:2038-2062) is for compaction/streaming. **off the point-read hot path.**
- **ChunkCache**: single global (ChunkCache.java:53), **Caffeine** LoadingCache (:26-28,150-156),
  maximumWeight = fileCacheSize−32MiB (:48-49), eviction on ImmediateExecutor (:152). Per-buffer
  refcount AtomicInteger (:102,111-123,141); rebuffer = cache.get(key).reference() (:232-243).
  **global-but-atomic (Caffeine striped internally).**
- **BufferPool(s)**: CHUNK_CACHE_POOL + NETWORKING_POOL (BufferPools.java:39,45,59-62).
  **FastThreadLocal LocalPool front layer** (BufferPool.java:172-183); GlobalPool
  ConcurrentLinkedQueues (:386-399) + AtomicLong memoryAllocated cap (:163); per-thread counters
  (:153,158). **already-sharded front + global-but-atomic backing.**
- **Key/Row/CounterCache**: global via CacheService.instance (CacheService.java:101-105),
  AutoSavingCache over CaffeineCache (:128,161,177). RowCache consulted at
  SinglePartitionReadCommand.getThroughCache (:575-612), **invalidated on every write**
  (CFS.apply → invalidateCachedPartition, ColumnFamilyStore.java:1527). **global-but-atomic.**
- **Metrics**: readLatency/writeLatency LatencyMetrics (TableMetrics.java:124-128) =
  ThreadLocalTimer + counters (LatencyMetrics.java:37-39,150-154,169). Counter factory returns
  **ThreadLocalCounter** by default (CassandraMetricsRegistry.java:304-315;
  ThreadLocalCounter.java:47-57; atomicLongCounter() variant :317-328). Histograms =
  DecayingEstimatedHistogramReservoir (striped). **already-sharded — unusually TPC-friendly.**

## 3. Cross-cutting singletons

- **Schema/TableMetadataRef**: Schema.instance (Schema.java:74); distributed via TCM snapshot
  ClusterMetadata.current().schema (:149); local finals (:92,168-170); CFS via ConcurrentHashMap
  (Keyspace.java:105,209-215); KeyspaceMetadataRef.getWithCaching by schema version
  (Keyspace.java:756-777). **global-but-atomic.**
- **Ring/TokenMetadata → TCM**: ClusterMetadata.current() immutable volatile snapshot
  (ClusterMetadata.java:1048-1050,1076-1090); consumed for shard boundaries
  (CFS.localRangeSplits → currentNullable, ColumnFamilyStore.java:1614) and disk boundaries.
  **global-but-atomic (replaced mutable TokenMetadata/Gossip ring reads).**
- **Tracing**: Tracing.instance (:110), ConcurrentHashMap sessions (:108), ExecutorLocals
  carriage. **global-but-rare.**
- **ClientRequestMetrics**: global handles, ThreadLocal backing. **already-sharded backing.**
- **Gossiper**: not on request path (TCM serves reads). **global-but-rare.**
- **MonitoringTask**: global instance + BlockingQueue (MonitoringTask.java:27,81,111); per-request
  state on ReadCommand; only slow/aborted ops enqueue. **global-but-rare.**

## 4. Existing partition-by-token machinery (seeds)

- **TrieMemtable + AbstractShardedMemtable + ShardBoundaries** — shard =
  boundaries.getShardForKey(key) (TrieMemtable.java:191); default count = cores; getFlushSet
  already splits flush along shard boundaries (:360-493). **Strongest seed.**
- **Boundary computation** — CFS.localRangeSplits(shardCount) (ColumnFamilyStore.java:1607-1631):
  partitioner.splitter().splitOwnedRanges(shardCount, weightedRanges, false) over LOCALLY-OWNED
  ranges; cached + versioned by TCM epoch (cachedShardBoundaries; invalidated on ring change).
  Local ranges via DiskBoundaryManager.getVersionedLocalRanges (:1573-1605).
- **DiskBoundaries/DiskBoundaryManager** — token→data-dir routing: getCorrectDiskForKey/
  getDiskIndex binary search (DiskBoundaries.java:108-160); positions via
  splitter.splitOwnedRanges(#dirs) (DiskBoundaryManager.java:166-186).
- **PartitionDenylist** — token-keyed immutable Caffeine-cached (PartitionDenylist.java:93,99-107).
- **ANTI-SEED: MUTATION stage has NO core/token affinity** — multiThreadedLowSignalStage →
  .shared(...) (Stage.java:47,232-238); any worker dequeues any mutation → the per-shard
  ReentrantLock in TrieMemtable.MemtableShard.put must exist (contended/uncontended counters,
  TrieMemtable.java:553-563). **Token machinery exists in the DATA STRUCTURE, not the SCHEDULER.**

## 5. Five worst TPC hostiles (ranked, with fix shapes)

1. **Keyspace.writeOrder global OpOrder** (Keyspace.java:102; CAS OpOrder.java:206-216; started
   CassandraKeyspaceWriteHandler.java:47). Every core, every write, one cacheline. Fix: per-shard
   OpOrder; the global MV-ordering reason handled by message-passing view mutations to owner.
2. **CommitLog: allocatePosition CAS + synchronized rollover + single sync thread**
   (CommitLogSegment.java:242-257; AbstractCommitLogSegmentManager.java:298-323;
   CommitLog.java:88,120-130). Fix: per-core segments/allocators, or dedicated log-writer core
   fed by shard inboxes; global durable-position reconciliation stays atomic.
3. **Shared stages with no token affinity (structural)** (Stage.java:47,232-238;
   TrieMemtable.java:553). Fix: route by token to core-pinned single-threaded executors keyed on
   the SAME ShardBoundaries; per-shard lock becomes uncontended then removable.
4. **Global MEMORY_POOL accounting + one allocator across a memtable's shards + one cleaner**
   (AbstractAllocatorMemtable.java:61; MemtablePool.java:46,55,113-114,264-266;
   TrieMemtable.java:129,140; NativeAllocator.java:223-258,324). Fix: per-shard allocator/region;
   per-core sub-pool reconciled to a global atomic cap; cleaner stays global.
5. **Global ChunkCache + BufferPool backing + CacheService caches** (ChunkCache.java:48-53,
   150-156; BufferPool.java:163,386-399; CacheService.java:101-105; row-cache invalidation on
   write ColumnFamilyStore.java:1527). Least bad (Caffeine + thread-local fronts). Fix: leave
   global-atomic, optionally NUMA-shard later; actionable = decouple row-cache invalidation
   from write hot path.

Honorable mentions: MV/counter Striped arrays (ViewManager.java:69, CounterMutation.java:73) —
shardable, niche tables; Tracker.viewUpdateLock (Tracker.java:100) — per-table, rare.
