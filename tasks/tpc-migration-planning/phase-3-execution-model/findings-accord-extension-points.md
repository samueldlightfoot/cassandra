# Findings — Accord/TPC coexistence + blocking extension points (agent report, 2026-07-07)

Verbatim exploration report for phase-3 §3.1 item 6 and §3.2. Accord submodule IS
initialized (`modules/accord/accord-core` present); facts span main tree + accord-core.
All line numbers verified on tpc-migration 2026-07-07.

## 1. Accord execution topology today

**Verbs and stage**
- All Accord protocol verbs run `Stage.IMMEDIATE`: `net/Verb.java:326-362` (PRE_ACCEPT,
  ACCEPT, COMMIT, APPLY, READ, RECOVER, AWAIT, CHECK_STATUS, FETCH_DATA, GET_*_DEPS, etc.)
  and interop verbs `Verb.java:371-383`.
- Exceptions — NOT IMMEDIATE: `ACCORD_SET_SHARD_DURABLE_REQ`/`ACCORD_SET_GLOBALLY_DURABLE_REQ`/
  `ACCORD_SYNC_NOTIFY_*`/`CONSENSUS_KEY_MIGRATION` run on `Stage.MISC` (`Verb.java:363-370`).
- What IMMEDIATE actually executes on the messaging thread: `AccordVerbHandler.doVerb`
  (`service/accord/AccordVerbHandler.java:56-92`) does endpoint mapping + epoch check,
  then `request.process(node, fromNodeId, header)`. That resolves via
  `MapReduceConsumeCommandStores` → `CommandStores.mapReduceConsume`
  (`modules/accord/accord-core/src/main/java/accord/local/CommandStores.java:1091-1142`),
  which selects intersecting command stores and submits async work to each store's
  executor. **The messaging thread does routing only; command execution happens on
  AccordExecutor threads.**

**Accord's own executor structure — it ALREADY has a shard concept**
- `AccordCommandStores` holds `AccordExecutor[] executors`; command stores map to
  executors by `id % executors.length` (`service/accord/AccordCommandStores.java:72,84`).
- Executor-shard models (`config/AccordConfig.QueueShardModel`): `THREAD_PER_SHARD`,
  `THREAD_PER_SHARD_SYNC_QUEUE`, `THREAD_POOL_PER_SHARD`. **Default =
  `THREAD_POOL_PER_SHARD`** (`AccordConfig.java:158`), submission model default `SYNC`
  (`AccordConfig.java:159`).
- Sizing at defaults: executor shards = `queue_shard_count or P/8, min 1`
  (`AccordCommandStores.java:340-341`); threads per executor =
  `getAccordConcurrentOps()/shards` (`AccordCommandStores.java:139-141`) where
  `getAccordConcurrentOps()` defaults to **2×P** (`config/DatabaseDescriptor.java:2951-2954`).
  So default ≈ P/8 executors × 16 threads = **~2P Accord threads**. In `THREAD_PER_SHARD`
  mode: shards = P, 1 thread each (`AccordCommandStores.java:337-339,135-137`).
- `AccordExecutor` doc contract: "NO BLOCKING TASKS are submitted to this executor AND
  WAITED ON by another task executing on this executor"
  (`service/accord/AccordExecutor.java:119-122`); modes `RUN_WITH_LOCK`/`RUN_WITHOUT_LOCK`
  (`AccordExecutor.java:138`); each executor owns a slice of the AccordCache
  (`AccordCommandStores.refreshCapacities`, `AccordCommandStores.java:212-227`).
- **Command stores are token-range partitioned**: `command_store_shard_count` defaults to
  **available processors** (`AccordConfig.java:205,499`); ranges split per keyspace by
  `new KeyspaceSplitter(new EvenSplit<>(commandStoreShardCount, partitioner.accordSplitter()))`
  (`service/accord/AccordService.java:477`). So Accord already runs a
  "shard-per-core-count, token-range-owned, single-logical-writer-per-store" model —
  structurally parallel to the TPC plan, but with its OWN splitter and its OWN threads.

## 2. Accord's write path into storage

- Txn apply: `TxnWrite.applyDirect` grabs `((AccordCommandStore) commandStore).executor()`
  and applies each update via `executor.chain(() -> mutation.apply(false, false))`
  (`service/accord/txn/TxnWrite.java:499-508,144-150`). **The final memtable apply runs
  on an AccordExecutor thread.**
- `mutation.apply(false, false)` = `apply(durableWrites=false, isDroppable=false)` →
  `keyspace.apply(mutation, writeCommitLog=false, updateIndexes=true, isDroppable=false)`
  (`db/Mutation.java:295-307`).
- `Keyspace.applyInternal` → `getWriteHandler().beginWrite(mutation, makeDurable=false)`
  (`db/Keyspace.java:447,549`): `Keyspace.writeOrder.start()` ALWAYS runs
  (`db/CassandraKeyspaceWriteHandler.java:47`), `CommitLog.instance.add` is SKIPPED
  because makeDurable=false (`CassandraKeyspaceWriteHandler.java:51-54,99`). Then the
  same cfs.apply → memtable put + index hooks as plain writes.
- **AccordJournal is the WAL**: `org.apache.cassandra.journal.*` is a generic journal;
  `AccordJournal` wraps it with segments in a dedicated directory (default
  `<storagedir>/accord_journal`, `DatabaseDescriptor.java:801-804,3316-3319`) PLUS a
  backing `ColumnFamilyStore` (`AccordKeyspace.JOURNAL`) into which segment compaction
  writes SSTables **directly via `SSTableTxnWriter`, bypassing memtables**
  (`service/accord/journal/AccordJournal.java:103-121`;
  `service/accord/journal/AbstractSegmentCompactor.java:45`).
- Journal write threading: appends happen on the **caller** (AccordExecutor) thread via
  `segments.asyncWrite(key, change)` (`AccordJournal.java:162-190`); durability callbacks
  via `segments.onDurable(pointer, onFlush)`. The journal runs its own threads:
  `<name>-allocator` infinite loop + `<name>-closer` + `<name>-releaser` sequential
  executors (`journal/Journal.java:109-110,235-237`), plus `Flusher` with a
  `-disk-flusher-<mode>` thread and a separate fsync executor
  (`journal/Flusher.java:68-69,103-105`).
- Journal flush defaults: `FlushMode.PERIODIC`, `flushPeriod` **inherits
  `commitlog_sync_period`** (`AccordConfig.JournalConfig`, `config/AccordConfig.java:400-401`;
  `DatabaseDescriptor.java:664-670`).
- Replay: on startup, journal replay re-executes commands per command store
  (`service/accord/journal/Replay.java:57-181`) — accord-managed memtable data is
  recovered by re-driving applies from the journal, not from the commitlog. Memtable
  flush ↔ journal truncation coordinated by flush listeners marking durability
  (`service/accord/AccordDurableOnFlush.java:38,105-159`).
- **Reads**: `TxnNamedRead.performLocalKeyRead/performLocalRangeRead` execute
  `SinglePartitionReadCommand`/`PartitionRangeReadCommand` with a `ReadExecutionController`
  ON the AccordExecutor (`service/accord/txn/TxnNamedRead.java:291-322,366-402,405-407`) —
  sstable I/O, ChunkCache, BufferPool all touched from Accord threads. Interop reads
  instead run on **`Stage.READ`** via `ReadCommandVerbHandler.instance.doRead`
  (`service/accord/interop/AccordInteropRead.java:289,304`), and interop coordination uses
  **`Stage.ACCORD_MIGRATION`** (`interop/AccordInteropExecution.java:279,315,420`).

## 3. Accord vs the five TPC hostiles

| Hostile | Does Accord touch it? | Evidence |
|---|---|---|
| #1 writeOrder | **YES — every Accord apply starts a global writeOrder group**, from AccordExecutor threads | `CassandraKeyspaceWriteHandler.java:47` reached via `Mutation.apply(false,false)` (`TxnWrite.java:150`) |
| #2 CommitLog | **NO for txn data** (makeDurable=false skips `CommitLog.add`; AccordJournal is the WAL — no double-logging) | `CassandraKeyspaceWriteHandler.java:51-54`; `Mutation.java:295-307` |
| #3 memtable shard lock | **YES** — Accord applies go through the same `cfs.apply → memtable.put`, contending the TrieMemtable per-shard lock from ~2P Accord threads | same applyInternal path, `Keyspace.java:549-620` |
| #4 MEMORY_POOL | **YES, and it can BLOCK an AccordExecutor thread**: allocation waits on `hasRoom` WaitQueue when the pool is full | `utils/memory/MemtableAllocator.java:170-198`; `MemtablePool.java:52,55` |
| #5 ChunkCache/BufferPool | **YES on reads** — normal read machinery executed on Accord threads | `TxnNamedRead.java:291-322` |
| (bonus) MV Striped locks | Accord applies with `updateIndexes=true`, so an MV-bearing table would run the MV lock loop — including the `Thread.sleep(10)` retry — on an AccordExecutor thread | `Keyspace.java:459-538`, esp. 516-529 |

## 4. Blocking extension points census (spec §3.2)

1. **Auth (IAuthenticator/IAuthorizer/IRoleManager)** — runs on NTR threads BEFORE apply:
   `ClientState.java:682-684` (`user.getPermissions`), `ClientState.java:502-530`
   (`ensurePermissionOnResourceChain`), `ModificationStatement.java:381-403`. Escape hatch
   exists: `AuthCache` (Caffeine) with a dedicated refresh executor
   `executorFactory().sequential(name + "Refresh")` (`auth/AuthCache.java:197,359-362`);
   **cache misses still load on the caller thread**. Custom impls can do arbitrary
   network I/O. Off the apply thread already (coordinator-side).
2. **Triggers** — coordinator-side, BEFORE apply, on the calling (NTR) thread:
   `StorageProxy.java:1250` (`TriggerExecutor.instance.execute(mutations)` in
   mutateWithTriggers), `StorageProxy.java:481` (CAS/Paxos path), execution internals
   `triggers/TriggerExecutor.java:102-114,236-287`. Arbitrary user code, no escape pool
   today. NOT inside applyInternal.
3. **2i/SAI write hooks — the one that runs INSIDE apply, on the applying thread**:
   `ColumnFamilyStore.java:1524-1525` (`UpdateTransaction indexer = newUpdateTransaction(...);
   mt.put(update, indexer, opGroup)`); indexer construction
   `SecondaryIndexManager.java:1504-1526`; per-row callbacks
   `SecondaryIndexManager.java:1586-1654`. SAI memtable writes are in-memory (no I/O);
   legacy `CassandraIndex` writes the index table's memtable inline (same thread, nested
   applyInternal); **custom `Index.Indexer` implementations may block — contract does not
   forbid it**. Index BUILDS are off-path on `"SecondaryIndexManagement"` sequential
   executor (`SecondaryIndexManager.java:216-221`).
4. **UDF/UDA** — read/result path only, never on apply: `UDFunction.java:386-387` picks
   `executeAsync` when `DatabaseDescriptor.enableUserDefinedFunctionsThreads()`; async
   submission `UDFunction.java:476-498` to per-impl `executor()` (`UDFunction.java:571`,
   JavaBasedUDFunction:292). Config `user_defined_functions_threads_enabled` **defaults
   true** (`Config.java:738-739`) — a dedicated pool already exists.
5. **Guardrails/constraints** — pure synchronous checks, coordinator-side pre-apply
   (`ModificationStatement.java:405-472`); no blocking, safe inline.
6. **CDC — a genuine write-path blocker**: `CommitLog.instance.add`
   (`CassandraKeyspaceWriteHandler.java:99`) → `CommitLogSegmentManagerCDC.allocate`
   (`db/commitlog/CommitLogSegmentManagerCDC.java:172-192`) can throw/block on CDC space
   (`throwIfForbidden`, lines 215-231; `cdc_block_writes`). Under I4 this lands on shard
   threads.

**Must-not-run-inline on a shard apply thread**: custom `Index.Indexer` (only pluggable
code truly inside applyInternal), CDC space exhaustion stall, memtable-pool allocation
stall (hostile #4, affects Accord too). Triggers/auth/UDF are coordinator-side; they
matter for I3 (NTR absorption), not I1. Existing pools: `"{Cache}Refresh"`,
`"SecondaryIndexManagement"`, `"CDCSizeCalculationExecutor"`, UDF per-impl executors.
**No general "user-code escape pool" exists; one new pool (or reuse of a MISC-like stage)
is needed for custom-indexer/trigger contracts.**

## 5. Adversarial pass — new questions

1. **Two shard maps for the same node.** Accord partitions by
   `EvenSplit(command_store_shard_count=P)` per keyspace over the accordSplitter
   (`AccordService.java:477`; `AccordConfig.java:499`); memtables partition by
   `ShardBoundaries` size-weighted local-range splits. The token→owner functions are
   DIFFERENT, so mapping "command store → TPC shard" is not the identity; an Accord apply
   for command store k can hit multiple memtable shards, and one memtable shard receives
   applies from multiple command stores. Aligning the two splitters (or routing
   per-partition-update) is a design decision nobody has stated.
2. **Thread budget collision.** Defaults: ~2P Accord executor threads + P TPC shard
   threads + journal threads (allocator/closer/releaser/flusher/fsync) +
   Stage.ACCORD_MIGRATION SEP threads. If shard threads pin P cores, Accord's sizing
   assumptions (2P concurrent ops) are consumed capacity — nobody has re-derived Accord's
   defaults under TPC.
3. **Two WALs on one device.** AccordJournal fsyncs on its own cadence (PERIODIC,
   inheriting `commitlog_sync_period`) in `<storagedir>/accord_journal` while I4 creates
   N per-shard commitlog writers. Device-level interference between journal fsync, N
   commitlog fsyncs, and journal→SSTable segment compaction is unmodeled.
4. **Migration/interop = same table, two writer populations.** During CEP-15 migration,
   `splitMutationsIntoAccordAndNormal` (`StorageProxy.java:1273,1494`;
   `ConsensusMigrationMutationHelper.java:191`) sends some mutations via Accord
   (AccordExecutor threads, no commitlog) and some via the normal path. The guard is
   range-based and apply-time-checked (`validateSafeToExecuteNonTransactionally`,
   `ConsensusMigrationMutationHelper.java:311+`, called at `Keyspace.java:551`, with
   `PotentialTxnConflicts.ALLOW` exempting Accord's own mutations, `TxnWrite.java:149`) —
   it prevents same-KEY double-writing but NOT same-memtable-shard multi-writer. I1's
   "single writer per memtable shard" is violated for any migrating table unless Accord
   applies are also routed through shard inboxes.
5. **Crash-recovery ordering across two WALs.** Commitlog replay and Accord journal
   replay independently reconstruct the same table's memtables after a crash
   mid-migration. Replay-order reconciliation under a per-shard commitlog design must
   include journal replay as a third source.
6. **writeOrder barriers vs Accord.** Flush barriers must wait on writeOrder groups
   started by AccordExecutor threads; a per-shard writeOrder (I4) only works if EVERY
   writer population — including Accord — is enumerable per shard. Also
   `AccordDurableOnFlush` couples memtable flush to journal truncation
   (`AccordDurableOnFlush.java:105-159`) — per-shard flush changes touch this contract.
7. **AccordExecutor blocking hazards.** `mutation.apply` on an AccordExecutor thread can
   block on memtable pool exhaustion (`MemtableAllocator.java:196`) and on the MV lock
   `Thread.sleep(10)` loop (`Keyspace.java:516-529`) — violating AccordExecutor's own
   no-blocking contract (`AccordExecutor.java:119-122`). Routing Accord applies through
   shard inboxes would REMOVE these hazards from Accord — an argument FOR the inbox
   option that hasn't been made.
8. **CEP-45 mutation tracking: NOT in this tree.** No `MutationJournal`/mutation-tracking
   replication journal exists in src (grep negative). One less writer today; flag as
   "watch trunk".
9. **Accord cache memory vs TPC.** AccordCache defaults to 10% heap + 5% working set
   (`DatabaseDescriptor.java:1040-1062`), partitioned per executor — if executor count
   changes to match TPC shards, per-executor cache slices shrink/grow implicitly.

## RESOLVED

- All Accord protocol verbs IMMEDIATE (housekeeping verbs MISC); IMMEDIATE = routing
  only, execution on AccordExecutor.
- Final memtable apply thread = AccordExecutor thread (`TxnWrite.java:499,150`).
- No double-logging: makeDurable=false; journal is sole WAL; replay re-drives applies.
- Accord starts writeOrder groups on every apply.
- Accord has its own shard model: P command stores, token-range EvenSplit per keyspace,
  executor-sharded; a THREAD_PER_SHARD mode exists (not default).
- Accord reads on its own threads (txn); interop reads on Stage.READ.
- CEP-45 mutation tracking not present in this tree.
- Extension points inside apply = index hooks only; triggers/auth/UDF coordinator-side;
  CDC blocks inside CommitLog.add.

## OPEN QUESTIONS (feed phase-3 design docs)

1. **The forced choice, sharpened:** (a) route Accord's `mutation.apply`/local reads
   through TPC shard inboxes (extra hop per apply; removes Accord's blocking hazards;
   preserves I1/I4 single-writer), (b) ALIGN the two shardings — make command_store
   splits use `ShardBoundaries` and pin each AccordExecutor (THREAD_PER_SHARD mode) 1:1
   onto the TPC shard thread so the shard thread IS the command-store thread, or (c) keep
   Accord as-is and exempt accord-managed tables from I1/I4 single-writer claims. Option
   (b) is newly visible and must be explicitly evaluated in design-target.md.
2. Migration-window story for I1: single-writer simply NOT claimed for tables with
   migration in progress, or interop applies also inbox-routed?
3. I4 replay design must state how Accord journal replay ordering composes with N-shard
   commitlog replay for mixed tables.
4. Accord's re-derived defaults (`queue_shard_count`, `queue_thread_count`, cache
   slicing) when P cores are owned by shard threads.
5. Extension-point contract: one new off-shard escape pool for custom `Index.Indexer` +
   triggers, or per-feature pools? Does CDC's block-on-space move off the shard thread?
6. Does per-shard writeOrder enumerate AccordExecutor threads as group-starters, or does
   inbox-routing make that moot? (Falls out of Q1.)
