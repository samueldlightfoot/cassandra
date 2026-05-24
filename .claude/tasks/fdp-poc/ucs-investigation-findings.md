# UCS Internals Investigation — Findings for FDP Placement Hints RFC

**Repository:** Apache Cassandra (fork at `/Users/samlightfoot/repos/fork/cassandra`)
**Branch:** `fdp-poc`
**Commit SHA:** `206485e8205624536680c9f27e273b222582ffa9` (`Merge branch 'cassandra-6.0' into trunk`)
**Method:** Static analysis only. Where source is ambiguous, the report quotes the relevant snippet and marks `REQUIRES RUNTIME VERIFICATION` rather than speculating.

---

## Summary

| # | Question | Status |
|---|---|---|
| Q1 | Level at writer-open time | **Clean answer — but answer reframes the question.** The `sstableLevel` *field* is known at writer open, but for UCS it is always `0` for both flush and compaction (UCS does not override `CompactionTask.getLevel()`). The UCS *operating* level is not persisted on the SSTable. |
| Q2 | Per-level scaling parameter access | **Clean.** `Controller.getScalingParameter(int index)`; returns signed `int W`; immutable after Controller construction so thread-safe to read. |
| Q3 | Memtable flush target level | **Clean.** Always L0. Flush hardcodes `sstableLevel = 0` in `ColumnFamilyStore.flushInternal`. |
| Q4 | Arrival-order within a level | **Requires new instrumentation.** UCS does not track arrival position; levels are recomputed from density each scheduling pass. Proxies (`maxTimestamp`, `Descriptor.generation`) exist but do not encode position within a level's bucket. |
| Q5 | Shard influence on deathtime | **Clean.** Shard membership does not affect lifetime; thresholds are per-level-index (effectively global), shards only partition output ranges. |
| Q6 | SSTableWriter open path | **Clean.** Full chain identified down to `FileChannel.open()` in `SequentialWriter.openChannel`. JNR-FFI `fcntl` wrapper already exists (`NativeLibrary.tryFcntl`); we only need to add `F_SET_RW_HINT` constants. |
| Q7 | Direct IO interaction | **Out of scope today.** Direct IO for SSTable writes (CASSANDRA-21134) is **not merged on trunk**. F_SET_RW_HINT is orthogonal to `O_DIRECT` semantically (both operate on the same `int fd`), so the two are compatible in principle, but this `REQUIRES RUNTIME VERIFICATION` once the write path lands. |
| Q8 | Open-time metadata fields | **Mostly clean.** Open-time signals are `repairedAt`, `pendingRepair`, `isTransient`, `keyCount` (estimated), and source-SSTable metadata via the lifecycle transaction. Almost everything statistically interesting (size, row count, timestamps, compression ratio, density) is close-time only. |
| Q9 | Test infrastructure | **Clean, with a gap.** Strong base classes (`AbstractCompactionStrategyTest`, `CQLTester`, in-JVM `distributed` cluster, `test/long`). No observer hook that captures `(level, scaling, hint, fd)` per output SSTable — that fixture must be built. |
| Q10 | Logging hooks | **Clean.** UCS already uses SLF4J `logger` with conditional `isDebugEnabled()` guards; no tracing integration in the compaction package. Best insertion points are `ShardedCompactionWriter.sstableWriter()` and `CompactionAwareWriter.finish()`. |

**Cross-cutting observations affecting the RFC are in the [Cross-Cutting Notes](#cross-cutting-notes) section at the bottom — read these before locking the design.**

---

## Q1: Level assignment at write time

**Question.** When/where is the target level determined for an SSTable about to be written; is it available at writer-open time?

### Finding

The `sstableLevel` parameter is plumbed into the writer **before** `FileChannel.open()` is called, so a level *integer* is available at writer-open time. **However**, for UCS that integer is always `0` — UCS does not currently encode its operating level in the `sstableLevel` metadata field.

#### Call paths

**Flush path** (`src/java/org/apache/cassandra/db/ColumnFamilyStore.java`):
- `flushInternal()` calls `createSSTableMultiWriter(..., sstableLevel=0, ...)` at line ~2459.
- Default overloads also pin `0` (lines ~687, ~692): `return createSSTableMultiWriter(..., null, 0, header, txn)`.

**Compaction path:**
- `CompactionTask.getCompactionAwareWriter()` calls `new DefaultCompactionWriter(cfs, dirs, txn, nonExpired, keepOriginals, getLevel())` (`src/java/org/apache/cassandra/db/compaction/CompactionTask.java:~392`).
- `CompactionTask.getLevel()` returns `0` by default (`CompactionTask.java:544-547`).
- `UnifiedCompactionTask` (`src/java/org/apache/cassandra/db/compaction/unified/UnifiedCompactionTask.java`) **does not override `getLevel()`** — so UCS compaction outputs also receive `sstableLevel = 0`.

**Plumbing into the writer** (`src/java/org/apache/cassandra/db/compaction/writers/CompactionAwareWriter.java:~240-246`):
```java
MetadataCollector collector = new MetadataCollector(...).sstableLevel(sstableLevel());
SSTableWriter writer = SSTableWriter.create(... collector ...);
```

So at the moment `SequentialWriter.openChannel(file)` is invoked, an integer "level" is known — it is just always zero for UCS.

#### What UCS actually uses for "level"

UCS recomputes a logical level for each SSTable on every scheduling pass by **density-sorting all suitable SSTables**:

`src/java/org/apache/cassandra/db/compaction/UnifiedCompactionStrategy.java:~578-624` (`formLevels`):
```java
suitable.sort(shardManager::compareByDensity);
// binned into Level objects purely by density (onDiskLength / tokenSpaceCoverage)
```

`ShardManager.java:139-142` defines density purely as a per-SSTable function (`onDiskLength / rangeSpanned`). Density is finalized only **after** the SSTable is fully written, so the *true* UCS level of a freshly-written SSTable is not knowable at writer-open time.

What **is** knowable at writer-open time:
1. The hardcoded `sstableLevel=0` placeholder (useless as a hint).
2. For flushes: trivially "this is a flush product" (the constant 0 is meaningful — it is *always* an L0 arrival).
3. For compactions: the source SSTables (`txn.originals()` / `nonExpiredSSTables`), the `UnifiedCompactionTask`'s `density` and `numShards` fields (`UnifiedCompactionTask.java:88-89`), and via these we can compute an **expected** target level using the same density math UCS will later use to classify the finished SSTable.

### Verdict

A `sstableLevel` integer is plumbed to the writer before file open, but for UCS it is always `0` and does not reflect the operating level. To derive a meaningful "level" hint at writer-open time the RFC must compute it from compaction-task context (source SSTable densities + `ShardManager`), not read it from a built-in field.

---

## Q2: Scaling parameter access per level

**Question.** Given target level N, how does code query the scaling parameter (T, L, N) for the parent table?

### Finding

**API:** `Controller.getScalingParameter(int index)` — `src/java/org/apache/cassandra/db/compaction/unified/Controller.java:234-240`.

```java
public int getScalingParameter(int index) {
    if (index < 0) throw new IllegalArgumentException(...);
    return index < scalingParameters.length
        ? scalingParameters[index]
        : scalingParameters[scalingParameters.length - 1];
}
```

**Encoding (signed int `W`):**
- `W < 0` → Leveled (`L`), fan factor = `2 - W` (e.g. `W=-2` → `L4`).
- `W = 0` → Balanced (`N`).
- `W > 0` → Tiered (`T`), fan factor = `2 + W` (e.g. `W=2` → `T4`).

Parsed by `UnifiedCompactionStrategy.parseScalingParameter(String)` (`UnifiedCompactionStrategy.java:120-134`) against the pattern at line 77:
```java
private static final Pattern SCALING_PARAMETER_PATTERN = Pattern.compile("(N)|L(\\d+)|T(\\d+)|([+-]?\\d+)");
```

The reverse — pretty-printing `W` back to `"T4"` / `"L4"` / `"N"` — is `UnifiedCompactionStrategy.printScalingParameter(int)`.

**Thread safety:** The `int[] scalingParameters` array is `final` and populated once at Controller construction (`Controller.java:164`). Reads from the writer-open path are safe data-race-free reads; no lock needed.

**How to get a `Controller` from the writer path:** the Controller is held by the `UnifiedCompactionStrategy` instance attached to the `ColumnFamilyStore`. The cleanest accessor from compaction code is to thread it through `UnifiedCompactionTask` (which already holds a reference to the strategy). From the *flush* path the strategy is reachable via `cfs.getCompactionStrategyManager()` but doing so inside `SequentialWriter` would be a layering inversion — see Cross-Cutting Note #3 below.

### Verdict

Lookup is a single static-array read; safe to call from any thread; encoded as a signed `int` whose sign discriminates T/L/N. The handle to the `Controller` must be plumbed down — `SequentialWriter` does not have it today.

---

## Q3: Memtable flush target level

**Question.** What level does a memtable flush land at under UCS?

### Finding

**Always L0**, unconditionally, for UCS:

1. `ColumnFamilyStore.flushInternal()` passes `sstableLevel = 0` to `createSSTableMultiWriter()` (`ColumnFamilyStore.java:2454-2464`); default overloads at lines ~687 and ~692 also hardcode 0.
2. `UnifiedCompactionStrategy.createSSTableMultiWriter()` (`UnifiedCompactionStrategy.java:304`) receives `sstableLevel` but does **not** mutate it for level assignment; it only uses `sstableLevel > 0` as a switch for shard-splitting policy (line ~311: `boolean supportsSharding = sstableLevel > 0 || ...`).
3. `ShardedMultiWriter` (`src/java/org/apache/cassandra/db/compaction/unified/ShardedMultiWriter.java:72-147`) splits flush output across shards but never alters the level.

Sharding may cause a flush to produce **multiple SSTables** (one per shard range) but they are all level 0.

### Verdict

Flush always produces L0. The placement-hint rule for flush-product SSTables is therefore a constant: "all flush output is the lowest-lifetime (most ephemeral) class." Any sharding-aware refinement (e.g., "L0 in a hot shard is even shorter-lived") would have to come from per-shard write-rate data, which UCS does not currently track (see Q5).

---

## Q4: Arrival-order-within-level signal

**Question.** For tiered levels, can the strategy report at write time which arrival-position the new SSTable occupies?

### Finding

**No such signal exists. New instrumentation is required.**

UCS does not maintain a per-level arrival counter or queue. On each call to `getNextBackgroundTasks()`:

1. `getSuitableSSTables()` (`UnifiedCompactionStrategy.java:628`) returns a flat list of *all* suitable SSTables across all shards.
2. `formLevels(suitable)` (`UnifiedCompactionStrategy.java:578`) **density-sorts** and bins them into freshly-allocated `Level` objects — no state carried across invocations.
3. Within a level, `Overlaps.constructOverlapSets()` (`UnifiedCompactionStrategy.java:782`) groups by key-range overlap, not by arrival.
4. Within a bucket, SSTables are sorted by `maxTimestamp` descending (`UnifiedCompactionStrategy.java:840`) to preserve newest-first selection for compaction — this is **content time order, not arrival-into-level order**.

The `Level` class (`UnifiedCompactionStrategy.java:664-687`) holds an `ArrayList<SSTableReader>` and a `threshold` field; there is no `arrivedCount` / `arrivalIndex` / queue position.

**Available proxies — and why each is weak:**

| Signal | Source | Why imperfect |
|---|---|---|
| `Descriptor.generation` | filename / `Descriptor` | Monotonic per table, but global across all levels — gives chronological ordering, not within-level position. |
| `SSTableReader.maxTimestamp` | `StatsMetadata` (close-time) | Content timestamp, may be reorderable across SSTables (back-dated writes). Not available until close. |
| `SSTableReader.onDiskLength()` | post-close | Used by UCS via density; not arrival-order. |

The closest static proxy is `Descriptor.generation` taken modulo a per-level counter — but maintaining that counter is exactly the new instrumentation needed.

### Verdict

**REQUIRES NEW INSTRUMENTATION.** The natural place to add it is the `UnifiedCompactionStrategy.Level` class (`UnifiedCompactionStrategy.java:664-687`) plus a per-(table, level) `AtomicInteger` held on the Controller or strategy, incremented every time a new SSTable enters the level (i.e., on `Tracker` add events). For the PoC this could be replaced with a coarser proxy: use `Descriptor.generation % thresholdT` as a synthetic arrival index, accepting that it does not survive compaction cycles cleanly.

---

## Q5: Shard influence on deathtime

**Question.** Does a level-N SSTable in a heavily-written shard have a shorter expected lifetime than one in a quiet shard?

### Finding

**No.** Shard membership is orthogonal to per-SSTable lifetime under UCS.

1. **Thresholds are per-level-index, not per-shard:** `Level.threshold = controller.getThreshold(index)` (`UnifiedCompactionStrategy.java:683`); `Controller.getThreshold(int index)` (`Controller.java:256-259`) is shard-agnostic.
2. **Levels are formed globally across all shards:** `formLevels(suitable)` (`UnifiedCompactionStrategy.java:578`) is called with one flat list (`UnifiedCompactionStrategy.java:466`); the suitable set is not partitioned by shard before binning.
3. **Compaction selection picks the highest-overlap level across all shards:** `chooseCompactionPick()` (`UnifiedCompactionStrategy.java:460-481`) does not iterate per shard.
4. **`ShardManager` partitions output ranges, not compaction urgency:** `splitSSTablesInShards()` (`ShardManager.java:176-209`) and friends are invoked *after* level identification, to split output by shard range. No per-shard threshold or write-rate is tracked. `ShardManagerNoDisks` (`src/java/org/apache/cassandra/db/compaction/unified/ShardManagerNoDisks.java:31-214`) and `ShardManagerDiskAware` (`src/java/org/apache/cassandra/db/compaction/unified/ShardManagerDiskAware.java:32-237`) hold only shard boundaries.
5. **Density is per-SSTable, not per-shard:** `ShardManager.density()` (`ShardManager.java:139-142`) divides this SSTable's bytes by its range span; not influenced by sibling traffic.

A heavily-written shard fills faster in wall-clock time, but each constituent SSTable's compaction trigger fires at the same global density threshold as in a quiet shard.

### Verdict

Shard write-rate **does not** belong in the hint derivation. Key the hint only on `(level, scaling parameter, arrival-position-proxy)` and ignore shard ID. (Sharding may still be relevant for *device-level* placement choices — e.g. round-robin streams across hardware queues — but not for *write-lifetime* classification.)

---

## Q6: SSTableWriter open path

**Question.** Walk the exact path from UCS deciding to write an SSTable down to the `fd` open. Identify the integration surface for `fcntl(fd, F_SET_RW_HINT, hint)`.

### Finding — full call chain

Compaction or flush trigger
↓ `CompactionTask.getCompactionAwareWriter()` (compaction) or `ColumnFamilyStore.createSSTableMultiWriter()` (flush)
↓ `BigTableWriter` constructor — `src/java/org/apache/cassandra/io/sstable/format/big/BigTableWriter.java:77-86`
↓ `SortedTableWriter` constructor — `src/java/org/apache/cassandra/io/sstable/format/SortedTableWriter.java:97-126` — line 108: `dataWriter = builder.openDataWriter();`
↓ `BigTableWriter.Builder.openDataWriter()` — `BigTableWriter.java:399-412`
↓ `DataComponent.buildWriter()` — `src/java/org/apache/cassandra/io/sstable/format/DataComponent.java:37-64` — branches on `metadata.params.compression.isEnabled()`:
   - compressed path → `new CompressedSequentialWriter(...)`
   - uncompressed path → `new ChecksummedSequentialWriter(...)` (`src/java/org/apache/cassandra/io/util/ChecksummedSequentialWriter.java:33-39`)
↓ `SequentialWriter(File, ByteBuffer, SequentialWriterOption, boolean)` — `src/java/org/apache/cassandra/io/util/SequentialWriter.java:157-183`:
```java
protected SequentialWriter(File file, ByteBuffer buffer, SequentialWriterOption option, boolean strictFlushing) {
    super(openChannel(file), buffer);          // ← line 175: the OPEN happens here
    this.strictFlushing = strictFlushing;
    this.fchannel = (FileChannel) channel;     // ← line 177: fd is reachable from here
    ...
}
```
↓ `SequentialWriter.openChannel(File)` — `SequentialWriter.java:112-139` — the system call:
```java
private static FileChannel openChannel(File file) {
    if (file.exists())
        return FileChannel.open(file.toPath(), READ, WRITE);
    else {
        FileChannel channel = FileChannel.open(file.toPath(), READ, WRITE, CREATE_NEW);
        SyncUtil.trySyncDir(file.parent());
        return channel;
    }
}
```

The BTI format goes through the same `DataComponent.buildWriter()` and therefore the same `SequentialWriter` constructor.

### Raw `int fd` extraction

Already implemented in `src/java/org/apache/cassandra/utils/NativeLibrary.java:379-403`:
```java
public static int getfd(FileChannel channel) {
    try { return getfd((FileDescriptor) FILE_CHANNEL_FD_FIELD.get(channel)); }
    catch (IllegalArgumentException | IllegalAccessException e) { ... }
    return -1;
}
```
`FILE_CHANNEL_FD_FIELD` is a `Field` reflected onto `sun.nio.ch.FileChannelImpl.fd` at static init (`NativeLibrary.java:79-92`).

### Existing JNR-FFI `fcntl` wrapper

We do **not** need a new JNR binding — the wrapper exists:

- `NativeLibrary.tryFcntl(int fd, int command, int flags)` — `NativeLibrary.java:276-299`.
- Backend on Linux: `NativeLibraryLinux.callFcntl(int fd, int command, long flags)` — `src/java/org/apache/cassandra/utils/NativeLibraryLinux.java:93-96`.
- JNR `@LastError` native declaration: `NativeLibraryLinux.java:75` — `private static native int fcntl(int fd, int command, long flags) throws LastErrorException;`.

To add `F_SET_RW_HINT` support we need to:
1. Add a constant for `F_SET_RW_HINT` (Linux value `1030`).
2. Add a `tryFcntlSetRwHint(int fd, long hint)` helper that wraps `tryFcntl` and passes a pointer (RW hints are passed as a `uint64_t *` via fcntl, not as the inline `arg`; this means we likely need a JNR `Pointer` form of the call — verify by reading the kernel `fcntl(2)` man page and adding a `callFcntlPtr` variant in `NativeLibraryLinux`).

### Where to insert the call

The natural insertion point is **between lines 177 and the rest of the constructor body in `SequentialWriter.java`**, i.e. immediately after `this.fchannel = (FileChannel) channel;`. At that point the channel is open, no writes have occurred, and the fd is extractable via `NativeLibrary.getfd(this.fchannel)`.

The challenge is that `SequentialWriter` is generic infrastructure used far beyond SSTable writes (commit log, hints, etc.). The hint must be **opt-in via the `SequentialWriterOption`** so non-SSTable callers don't accidentally apply a write-life class. Suggested shape:
- Add `writeLifeHint(long)` to `SequentialWriterOption.Builder`.
- Default = `RWH_WRITE_LIFE_NOT_SET` (kernel default).
- `UnifiedCompactionStrategy` / `UnifiedCompactionTask` constructs the option with the derived hint and threads it through `DataComponent.buildWriter()` (which already accepts a `SequentialWriterOption`).

### Verdict

Path is unambiguous. The JNR-FFI plumbing is 90% in place (`tryFcntl` exists). Integration requires (a) a `F_SET_RW_HINT` variant of the wrapper that takes a `Pointer<Long>`, (b) a `writeLifeHint` field on `SequentialWriterOption`, and (c) a hint-derivation call site in UCS that fills it in before `DataComponent.buildWriter()` is invoked. No invasive surgery to the writer hierarchy.

---

## Q7: Direct IO interaction

**Question.** Is the Direct IO compaction write path (CASSANDRA-19987 / 21134) on trunk, and if so does it support `F_SET_RW_HINT`?

### Finding

**CASSANDRA-21134 ("Direct I/O for background SSTable writes") is not merged on trunk at `206485e8`.** The agent verified that the relevant feature commits are not ancestors of HEAD. Trunk has Direct IO only for the commit log (`src/java/org/apache/cassandra/db/commitlog/DirectIOSegment.java`) and as a *read-side* capability check (`FileHandle.supportsDirectIO()` in `src/java/org/apache/cassandra/io/util/FileHandle.java`). There is no `DirectCompressedSequentialWriter` or equivalent on the write path.

### Compatibility analysis (forward-looking)

`F_SET_RW_HINT` and `O_DIRECT` operate at independent layers of the I/O stack:
- `O_DIRECT` is an `open(2)` flag that bypasses the page cache.
- `F_SET_RW_HINT` is an `fcntl(2)` command that attaches a write-life class to the *file*, used by the block allocator / hardware (in our case, NVMe FDP).

They are not mutually exclusive — combining them is a documented pattern. The fd returned by `FileChannel.open()` with `O_DIRECT` is still a normal Linux fd that accepts `fcntl`. The only constraint that affects integration is unrelated: Direct IO writes must be aligned to filesystem block boundaries (typically 4 KiB), which is a property of the write path, not of the hint.

If the Direct IO write path lands via a separate `DirectIOSequentialWriter` (rather than a flag on `SequentialWriter`), the hint-insertion point would have to be duplicated in that class.

### Verdict

Not applicable on current trunk. **REQUIRES RUNTIME VERIFICATION** once CASSANDRA-21134 lands. Static analysis suggests no incompatibility, but the integration surface (which class opens the fd) will determine whether the hint plumbing in Q6 covers it or needs a second insertion point.

---

## Q8: Per-SSTable metadata available at write time

### Finding

#### Available at writer OPEN time

From `SSTableWriter` constructor parameters (`src/java/org/apache/cassandra/io/sstable/format/SSTableWriter.java:97-110`):

| Field | Type | Source |
|---|---|---|
| `descriptor` | `Descriptor` | from builder — includes `generation` (monotonic per CF), `directory`, `format`, `version` |
| `repairedAt` | `long` | `builder.getRepairedAt()` |
| `pendingRepair` | `TimeUUID` | `builder.getPendingRepair()` |
| `isTransient` | `boolean` | `builder.isTransientSSTable()` |
| `keyCount` | `long` | `builder.getKeyCount()` — **estimated**, not exact |
| `header` | `SerializationHeader` | column/type info for this SSTable |
| `metadataRef` | `TableMetadataRef` | full schema |
| `metadataCollector` | `MetadataCollector` | initialized empty, will accumulate during write |

From `ValidationMetadata` (`src/java/org/apache/cassandra/io/sstable/metadata/ValidationMetadata.java:37-38`) — known at open via schema:
- `partitioner` (String)
- `bloomFilterFPChance` (double)

From compaction-task context (compaction path only):
- `txn.originals()` — the *source* SSTables, including all of their close-time metadata (size, level, density, timestamps, etc.)
- `UnifiedCompactionTask.density`, `UnifiedCompactionTask.numShards` (`UnifiedCompactionTask.java:88-89`)
- `ShardTracker` for the current output shard — boundaries and shard index

#### Available only at writer CLOSE time

From `StatsMetadata` (`src/java/org/apache/cassandra/io/sstable/metadata/StatsMetadata.java:61-83`):
- `estimatedPartitionSize`, `estimatedCellPerPartitionCount` (`EstimatedHistogram`)
- `minTimestamp`, `maxTimestamp`, `minLocalDeletionTime`, `maxLocalDeletionTime` (`long`)
- `minTTL`, `maxTTL` (`int`)
- `compressionRatio` (`double`)
- `estimatedTombstoneDropTime` (`TombstoneHistogram`)
- `sstableLevel` (`int`) — see Q1: always set from the input parameter, always 0 for UCS
- `totalColumnsSet`, `totalRows` (`long`)
- `coveredClustering` (`Slice`)
- `tokenSpaceCoverage` (`double`)
- `firstKey`, `lastKey` (`ByteBuffer`)

From `CompactionMetadata` (`src/java/org/apache/cassandra/io/sstable/metadata/CompactionMetadata.java:41`):
- `cardinalityEstimator` (`ICardinality` / HyperLogLog)

### Verdict

For hint derivation at writer-open time the practical inputs are:
- **Compaction outputs:** source SSTables' close-time metadata (size, density, level-as-density-bin, timestamps) via `txn.originals()`, plus `UnifiedCompactionTask.density` and shard index.
- **Flush outputs:** essentially nothing data-shaped — just `repairedAt`, `isTransient`, estimated `keyCount`. Hint must be the constant "L0/flush" class.

Anything that depends on the *output's* actual size/row count must be deferred or estimated from inputs.

---

## Q9: Test infrastructure for compaction strategies

### Finding

**Unit tests** in `test/unit/org/apache/cassandra/db/compaction/`:

| Class | Approach |
|---|---|
| `UnifiedCompactionStrategyTest` | Mocked. `Tracker.newDummyTracker()`, mocked `ColumnFamilyStore` and `CompactionStrategyManager`. Simulates SSTable creation via mocks; no real flush/compaction. |
| `AbstractCompactionStrategyTest` | Uses `SchemaLoader.prepareServer()`, real keyspace/table. `insertKeyAndFlush()` (~line 120) inserts and flushes real data. Tests query `strategy.getNextBackgroundTasks()` and verify transaction behavior. |
| `LeveledCompactionStrategyTest`, `SizeTieredCompactionStrategyTest`, `TimeWindowCompactionStrategyTest` | Pattern as above with strategy-specific assertions. |
| `ShardManagerTest` (small) | Tests shard boundary calculation only. |
| `ControllerTest`, `ArenaTest` | Unit-level coverage of UCS sub-components. |

**CQL-level harness:** `test/unit/org/apache/cassandra/cql3/CQLTester` — full in-process Cassandra, CQL execution, programmatic `flush()` and `compact()` hooks. Subclass and drive arbitrary INSERT workloads.

**In-JVM distributed tests:** `test/distributed/org/apache/cassandra/distributed/Cluster` and `AbstractClusterTest` — multi-node in-process clusters with isolated config, suitable for cross-node behavior under realistic flush/compaction cycles.

**Long-running tests:** `test/long/org/apache/cassandra/db/compaction/` — `LongLeveledCompactionStrategyTest`, `LongCompactionsTest`. Sustained workloads.

**Gap analysis for the PoC:**

What is missing for the FDP placement-hints PoC:
1. **No observer hook** that captures `(level, scaling_parameter, derived_hint, fd, output_descriptor)` per SSTable as it is opened.
2. **No test that asserts hint values** under a controlled workload (e.g., "after N writes triggering K compactions, sstables at the highest-density level were opened with hint `RWH_WRITE_LIFE_LONG`").
3. **No fixture for stubbing `NativeLibrary.tryFcntl`** to assert the fcntl call was made with expected arguments without requiring an FDP-capable kernel.

Recommended PoC fixture: a subclass of `AbstractCompactionStrategyTest` that registers a `Tracker` listener (SSTable add/remove notifications already exist) plus a test-only seam in `SequentialWriter` (or in the proposed hint-derivation helper) that records the (file, hint) tuples in a list inspectable from the test.

### Verdict

Strong existing base. The PoC needs one new fixture: an observer that captures the `(level, scaling, hint, fd)` tuple per SSTable open and a stub for `tryFcntl` to allow CI runs without privileged hardware.

---

## Q10: Logging and observability hooks

### Finding

**Existing UCS logging** (`UnifiedCompactionStrategy.java`):
- Logger created at line 72: `private static final Logger logger = LoggerFactory.getLogger(UnifiedCompactionStrategy.class);`
- Conditional debug guards at lines 242, 450, 476, 706, 723, 748, 753, 780 (`if (logger.isDebugEnabled())`).
- Example (lines ~476-477):
  ```java
  if (logger.isDebugEnabled())
      logger.debug("Selected compaction on level {} overlap {} sstables {}",
                   selectedLevel, overlapCount, sstableList);
  ```

UCS already logs level/overlap at compaction selection; it does **not** log per-output-SSTable placement decisions.

**Tracing:** A search of `src/java/org/apache/cassandra/db/compaction/` for `Tracing` references returned **zero hits**. Compaction is not integrated with `org.apache.cassandra.tracing.Tracing` today. Wiring it up is non-trivial (tracing sessions are tied to client requests, not background tasks).

**Metrics** (`src/java/org/apache/cassandra/metrics/CompactionMetrics.java:41-71`):
- Aggregate: `pendingTasks`, `pendingTasksByTableName`, `completedTasks`, `totalCompactionsCompleted`, `bytesCompacted`, `compressedBytesCompacted`, `bytesCompactedThroughput`.
- **No per-SSTable, per-level, or per-placement-class metric.**

**Recommended insertion points for placement-hint observability:**

1. **`ShardedCompactionWriter.sstableWriter(directory, nextKey)`** — `src/java/org/apache/cassandra/db/compaction/unified/ShardedCompactionWriter.java:86-91`. Called before each new output writer is opened. Shard index and start key are known here.
2. **`CompactionAwareWriter.finish()`** — `src/java/org/apache/cassandra/db/compaction/writers/CompactionAwareWriter.java:128-132`. Called after all partitions are written; finalized metadata is available.
3. **`UnifiedCompactionTask.getCompactionAwareWriter()`** — `src/java/org/apache/cassandra/db/compaction/unified/UnifiedCompactionTask.java:83-98`. One-shot at task start; useful for high-level compaction intent logging.
4. **The hint-derivation helper itself** (new) — log inline with the derivation so the hint value, the inputs that produced it, and the target fd appear in a single line.

**Sample log line** matching the existing style:
```java
logger.debug("FDP hint: sstable={} shard={} sourceLevel={} targetLevel={} scaling={} hint={} fd={}",
             descriptor.filenameBase(),
             shardIndex,
             sourceLevel,
             targetLevel,
             UnifiedCompactionStrategy.printScalingParameter(scalingParam),
             hintName,
             fd);
```

**Recommendation:** Logger (DEBUG) for per-SSTable lines, plus one new aggregate `Meter` per write-life class in `CompactionMetrics` for production observability (e.g. `bytesWrittenByLifeClass.NOT_SET`, `.SHORT`, `.MEDIUM`, `.LONG`, `.EXTREME`). Skip tracing — the cost-to-benefit is poor for background tasks.

### Verdict

Logger insertion is straightforward at two natural choke points. Add a per-life-class `Meter` to `CompactionMetrics` for aggregate visibility. Tracing is unnecessary and would require new plumbing.

---

## Cross-Cutting Notes

These observations cut across questions and should inform the RFC before details are locked.

### 1. The persisted `sstableLevel` is not UCS's operating level

The single most consequential finding: **for UCS, the `sstableLevel` field on every SSTable is `0`**. UCS recomputes the operating level on the fly from density (Q4). The RFC cannot lean on "read the level from the SSTable" the way an LCS-targeted design would. Two consequences:

- The deathtime classifier must compute its own *expected* level for compaction outputs from `UnifiedCompactionTask.density`, `ShardManager`, and source-SSTable metadata.
- For flush outputs, the level is trivially L0 (Q3). The hint is essentially fixed by "this is a flush" — the only refinement available is via `repairedAt` / `isTransient` (e.g. transient SSTables on a transient-replica node may have very different lifetimes).

### 2. Arrival-position is genuinely missing, not just hidden

Q4 is the only "needs new instrumentation" answer. The tracker subscription model is the cheapest hook: register a `INotificationConsumer` that increments per-(level, shard) counters when SSTables are added. For PoC, `Descriptor.generation % T` is a serviceable hack; for production it should be tracked properly on the Controller (or a sidecar object) so it survives restart by re-deriving from current SSTable set on strategy attach.

### 3. Hint-derivation should live in UCS, not in `SequentialWriter`

`SequentialWriter` is shared infrastructure used by the commit log, hints, secondary indexes, etc. The cleanest separation is:
- `SequentialWriterOption` gains an optional `writeLifeHint` field (a `long`, defaulting to `RWH_WRITE_LIFE_NOT_SET`).
- `SequentialWriter` constructor, post-open, conditionally calls `NativeLibrary.tryFcntlSetRwHint(fd, hint)` if a hint was supplied.
- UCS (in a new `DeathtimeClassifier` collaborator) computes the hint and stuffs it into the option before `DataComponent.buildWriter()` is invoked.

This keeps shared infrastructure unaware of UCS-specific policy and avoids needing a Controller handle in `SequentialWriter`.

### 4. The flush path needs a parallel plumbing route

Compaction outputs reach the writer via `CompactionAwareWriter` → `DataComponent.buildWriter()`. Flush outputs reach it via `ColumnFamilyStore.createSSTableMultiWriter()` → `SSTableMultiWriter` → format-specific writer. Both ultimately bottom out at `SequentialWriter`, but the upstream argument-threading is separate. The RFC needs two hint-injection sites: one in the compaction task path, one in the flush path.

### 5. Direct IO will arrive as a parallel writer class

When CASSANDRA-21134 lands it is likely to introduce a `Direct*SequentialWriter` (mirroring the existing `DirectIOSegment` pattern for commit log) rather than flagging `SequentialWriter` itself. Plan to apply the hint at the same constructor-post-open seam in whichever class performs the open. The `SequentialWriterOption.writeLifeHint` plumbing should cover both if the Direct IO writer respects the same option type.

### 6. JNR-FFI groundwork is sufficient; only constants and a `Pointer` form are missing

`NativeLibrary.tryFcntl` is the template. The only additions are:
- A `tryFcntlSetRwHint(int fd, long hint)` that handles the `uint64_t *` argument shape (RW hints are pointer-passed, not inline-passed).
- A `callFcntlPtr` variant in `NativeLibraryLinux` declaring `fcntl(int fd, int command, Pointer arg)`.
- `F_SET_RW_HINT = 1030` constant.
- `RWH_WRITE_LIFE_*` constants (`NOT_SET=0`, `NONE=1`, `SHORT=2`, `MEDIUM=3`, `LONG=4`, `EXTREME=5`).

### 7. Test gap is small but real

The existing test base is strong (Q9); the missing piece is a test seam to inspect derived hints without privileged hardware. Provide a `NativeLibrary` stub-or-spy in tests (a small refactor to allow injection of the fcntl call) and an observer that records per-output-SSTable `(file, level, scaling, hint)` tuples for assertions.

---

*End of report.*
