# Agent Investigation: Cassandra UCS Internals for FDP Placement Hints RFC

**Context.** We are designing a PoC that propagates SSTable deathtime information from `UnifiedCompactionStrategy` (`org.apache.cassandra.db.compaction.UnifiedCompactionStrategy`) to the NVMe device via `RWF_WRITE_LIFE_*` placement hints. The RFC needs precise answers about UCS's internal model before the deathtime-classifier design can be finalised.

The questions below are scoped to be answerable by reading the Cassandra trunk source code. Each question lists the likely starting points and the form the answer should take. **Do not speculate where the code is ambiguous — say so and quote the relevant snippet.**

---

## Target repository

Apache Cassandra trunk, ideally a recent 5.x or 6.x branch. If working from a fork, note the commit SHA in the report.

Primary class: `org.apache.cassandra.db.compaction.UnifiedCompactionStrategy`
Supporting package: `org.apache.cassandra.db.compaction.unified.*`
Related: `ShardManager`, `Controller`, `Arena`, `BucketingTier`, level/shard bookkeeping classes.

---

## Q1: Level assignment at write time

**Question.** For an SSTable that is about to be written, when and where is its target level determined, and is that level information available at the point where the `SSTableWriter` (or its underlying file) is opened?

**Why it matters.** The placement hint must be set on the file descriptor at open time, before any bytes are written. If UCS only determines the level after the SSTable is complete (e.g., based on its final size), we cannot use level as the hint source — we'd need a different signal.

**Where to look.**
- `UnifiedCompactionStrategy.createSSTableMultiWriter(...)` or equivalent factory method
- `CompactionTask` / `AbstractCompactionTask` subclasses used by UCS
- Memtable flush path: `ColumnFamilyStore.flushMemtable(...)` and how it interacts with the strategy
- `SSTableWriter` constructor — what context is passed in
- `Descriptor` and `SSTable.Components` — whether level is part of the on-disk descriptor

**Expected answer format.**
- Yes/no on whether level is known at writer-open time.
- If yes: the exact call path from compaction/flush trigger → level determination → writer open. Cite line numbers.
- If no: at what point in the SSTable lifecycle does the level become known, and is there an alternative signal available at open time (e.g., the source SSTables' levels, the compaction task's target shard)?

---

## Q2: Scaling parameter access per level

**Question.** Given an SSTable's target level N, how does code at the storage-engine layer query whether level N is configured as Tiered (T), Leveled (L), or Balanced (N) for the parent table?

**Why it matters.** The deathtime classifier needs to know the level's behaviour to derive the hint. Leveled levels have clean deathtime monotonicity (level number alone suffices); tiered levels need a secondary signal (arrival order, see Q4).

**Where to look.**
- `Controller` class in `unified` package — likely owns the scaling parameter parsing
- The `scaling_parameters` config string parser (handles e.g. `"T8, T4, N, L4"`)
- Any method along the lines of `getScalingParameter(int level)` or `getStrategy(int level)`

**Expected answer format.**
- The method signature and class for querying per-level scaling parameter.
- The return type and how to interpret it (enum? int with sign convention?).
- Whether this is thread-safe to call from the SSTableWriter open path.

---

## Q3: Memtable flush target level

**Question.** When a memtable is flushed under UCS, what level does the resulting SSTable land at, and is that determined by configuration or always L0?

**Why it matters.** In LCS, flush always produces L0. UCS may behave differently — particularly with sharding, a flush may produce multiple SSTables at varying initial levels depending on density. The hint for flush-produced SSTables depends on this.

**Where to look.**
- The flush path: `Memtable.FlushCollection` → `SSTableMultiWriter` construction
- UCS's `getInitialLevel(...)` or similar; possibly handled in `ShardManager`
- The interaction between sharding and flush output

**Expected answer format.**
- Definitive statement: flush always produces level X, OR flush may produce levels in range [X, Y] depending on factors A, B, C.
- If the latter, the rule for determining the level at flush time.

---

## Q4: Arrival-order-within-level signal availability

**Question.** For tiered levels (T configuration), can the strategy report, at the point an SSTable is being written, an estimate of where it falls in the level's compaction queue — i.e., is it the first arrival at this level since the last compaction, the second, the N-th out of the threshold T?

**Why it matters.** This is the secondary deathtime signal for tiered levels. Without it, all SSTables at a tiered level get the same hint, and we lose the deathtime granularity that tiered levels could in principle provide.

**Where to look.**
- `Arena` / `BucketingTier` classes in `unified` package — these likely track level occupancy
- `getCompactionCandidates(...)` and how it selects SSTables — there's likely an ordering that implies arrival position
- SSTable metadata: is there a generation number, write timestamp, or sequence number on each SSTable that we can use as a proxy for arrival order?

**Expected answer format.**
- Either: a method or data structure that gives arrival position within level, and how to query it.
- Or: an alternative signal (generation, timestamp, sequence number) that can stand in for arrival position, and where it lives.
- Or: "no such signal is available at write time; would require new instrumentation in class X".

---

## Q5: Shard influence on deathtime

**Question.** Does shard membership affect SSTable lifetime? Specifically: if shard S receives 10× the write traffic of shard S', does an SSTable at level N in shard S have a shorter expected lifetime than one at level N in shard S'?

**Why it matters.** If shard write rate matters, the hint derivation should incorporate it. If not, we can ignore shards for hint purposes and key only on (level, scaling parameter, arrival position).

**Where to look.**
- `ShardManager` — how shards interact with level compaction triggers
- Whether each shard maintains its own per-level threshold, or whether thresholds are global
- Any documentation in the class headers or CEP-26 about shard independence

**Expected answer format.**
- Yes or no on whether shard write rate affects per-SSTable lifetime.
- If yes: the mechanism, and whether shard write rate is queryable from the strategy.
- If no: the reasoning (e.g., "thresholds are per-shard so each shard's level N triggers at the same fill regardless of arrival rate").

---

## Q6: SSTableWriter open path

**Question.** Walk the exact code path from `UnifiedCompactionStrategy` deciding to write an SSTable (either for compaction output or via the flush path) down to the system call that opens the data file's file descriptor. List every class and method involved.

**Why it matters.** This is the integration surface. The JNI shim that calls `fcntl(fd, F_SET_RW_HINT, ...)` has to be invoked between the file open and the first write. Knowing the exact path lets us pick the least invasive integration point.

**Where to look.**
- `SSTableWriter` → `BigTableWriter` (or `BtiTableWriter` for the newer format) → `SequentialWriter` → `FileChannel.open(...)`
- The role of `org.apache.cassandra.io.util.File` and `FileChannel` wrappers
- Whether direct IO paths (CASSANDRA-19987) introduce alternative file open mechanisms

**Expected answer format.**
- Full call chain with class names and method signatures.
- The point at which the underlying `int fd` becomes available (we need the raw integer fd for `fcntl`).
- Whether there's already a JNI/JNR-FFI hook point we can extend, or whether we need to add one.
- Note: Cassandra uses JNR-FFI for native calls, not raw JNI. The shim should follow that pattern.

---

## Q7: Direct IO interaction

**Question.** Does the Direct IO compaction path (CASSANDRA-19987) use a different file open mechanism than buffered IO, and if so, does that path also support `fcntl(F_SET_RW_HINT)`?

**Why it matters.** Direct IO bypasses the page cache, which may make placement hints work more reliably (no kernel write-back reordering). If the existing Direct IO path is compatible with hints, that's a strong combination. If it's incompatible (e.g., uses io_uring without hint support), that's a constraint.

**Where to look.**
- `DirectIOSequentialWriter` (if that's the class name introduced by 19987 — verify)
- The native call sites added by 19987 — look for `O_DIRECT` usage
- Whether the file open path can be parametrised to set both `O_DIRECT` and a write-life hint

**Expected answer format.**
- Yes/no on whether Direct IO writes are compatible with `F_SET_RW_HINT`.
- The class/method that performs the Direct IO file open.
- Any constraints that would prevent combining the two.

---

## Q8: Existing per-SSTable metadata fields available at write time

**Question.** Enumerate the SSTable metadata fields available at the point an SSTable is being written. We need to know what we have to work with for hint derivation beyond the explicit level/scaling-parameter signals.

**Why it matters.** There may be useful side-channels (estimated row count, expected compression ratio, source SSTable count for a compaction output) that improve hint accuracy without new instrumentation.

**Where to look.**
- `StatsMetadata`, `CompactionMetadata` in `org.apache.cassandra.io.sstable.metadata`
- `SSTableWriter` constructor parameters
- The compaction task: what does it know about its output before writing?

**Expected answer format.**
- A list of fields with their types and the class that exposes them.
- Brief note on which are deterministic at writer-open time vs only known at writer-close time.

---

## Q9: Test infrastructure for compaction strategies

**Question.** What test fixtures and harnesses does the project provide for exercising compaction strategies under controlled write workloads?

**Why it matters.** The PoC needs a reliable way to drive UCS through realistic compaction cycles in tests, ideally without standing up a full cluster. If there's an existing harness for this, we use it. If not, we need to build one.

**Where to look.**
- `test/unit/org/apache/cassandra/db/compaction/` — UCS tests
- Any `CQLTester`-based fixtures for compaction
- `test/long/` for longer-running tests

**Expected answer format.**
- List of existing test classes that exercise UCS end-to-end.
- Whether they support injecting custom write workloads.
- Gap analysis: what's missing for our PoC?

---

## Q10: Logging and observability hooks

**Question.** Where in the UCS code path can we add structured logging to record, for each SSTable write, the (level, scaling parameter, hint value, fd) tuple? We need this for the measurement phase.

**Why it matters.** Without per-SSTable observability, we can't validate that hints are actually being set correctly. Adding this is straightforward but we need to know the natural insertion point.

**Where to look.**
- Existing logging in `UnifiedCompactionStrategy` — what level (DEBUG/TRACE) and what fields
- Whether there's a `Tracing` integration for compaction events
- Metrics: `CompactionMetrics` and any per-strategy metric registration

**Expected answer format.**
- The natural insertion points for new log lines.
- Whether to use logger, tracing, or metrics for this signal.
- Sample log line format.

---

## Deliverables

A single markdown document with:

1. One section per question, with the answer in the format specified.
2. A summary at the top noting which questions had clean answers, which had ambiguous answers, and which require new instrumentation.
3. The Cassandra trunk commit SHA the investigation was done against.
4. Any cross-cutting observations the investigation surfaced that affect the RFC design but weren't asked about directly.

## Out of scope for this investigation

- Performance measurements (that's Phase 0/1 of the actual PoC)
- Anything requiring running Cassandra (this is a code-reading exercise)
- Anything requiring NVMe hardware
- Design choices about the RFC itself — just gather facts about what UCS does today

If a question requires running code to answer definitively, note that and provide the best static-analysis answer available.
