# Phase 2 — Expected Code Changes

Companion to `spec.md`. Class/script-level inventory, verified against the tree
2026-07-07 (JMH harness mechanics, exemplar benches, O_DIRECT idioms). Goal: an
implementing agent starts with zero harness discovery to do.

## 1. Java changes (all in `test/microbench/org/apache/cassandra/test/microbench/uring/`)

### 1.1 `UringRawReadBench.java` (extends the Phase 1.7 skeleton)
- Template: `DirectorySizerBench.java` shape (pure JMH, `@State(Scope.Benchmark)`,
  `@Setup(Level.Trial)`/`@TearDown`, **no** `DatabaseDescriptor`/daemon init — do NOT
  copy `CacheLoaderBench`'s server bootstrap), `VIntCodingBench` for `@Param` style,
  `CacheLoaderBench.java:62-68` as the in-tree `Mode.SampleTime` precedent.
- Class annotations: `@BenchmarkMode(Mode.SampleTime)`, `@OutputTimeUnit(MICROSECONDS)`,
  `@Fork(1)`, `@Threads(1)`, `@Warmup(iterations=5)`, `@Measurement(iterations=5)` —
  overridable per cell via `-Djmh.args` (no ant-side hardcoded iteration args exist;
  verified `build-bench.xml:110-117`).
- Params (all `@Param` with defaults, overridden per cell with `-p name=value`):
  `file` (default `/bench-ext4/uring-bench.dat`), `qd ∈ {1,32,64}`,
  `mode ∈ {sync,batched}`, `direct ∈ {true,false}`, `cold ∈ {true,false}`.
  `-p` values land in the result JSON's `params` block — cells self-label.
- `@Setup(Level.Trial)`: open `FileChannel` (`ExtendedOpenOption.DIRECT` when
  `direct`, gated on `FileUtils.isDirectIOSupported` — `FileUtils.java:745-773`);
  fd via `NativeLibrary.getfd(channel)` (`NativeLibrary.java:379`, idiom
  `ChannelProxy.java:218-221`); aligned buffers via
  `BufferUtil.allocateDirectAligned` + `FileUtils.getBlockSize` (idiom
  `DirectThreadLocalByteBufferHolder.java:66`); create `UringRing` when batched.
  Hot arms: one explicit sequential full-file read here (priming = deterministic,
  not left to warmup — see Q2 below).
- `@Setup(Level.Iteration)` when `cold`: exec `/bin/sync` then write `3` to
  `/proc/sys/vm/drop_caches` (rig runs as root — the forked JMH JVM can do this
  directly; keeps warmup iterations cold too, matching spec §1.1 "cold = drop
  immediately before each iteration").
- Benchmark methods return the bytes-read `int` (sufficient DCE protection for real
  I/O): `sync` = `readSync`/`FileChannel.read(dst, pos)` (positional read = pread
  comparison arm), `batched` = prepare×qd → submit → awaitCompletions.
- Naming constraint: class must match `.*Bench$` (`check-test-names`,
  `build.xml:1311`) — both names comply.

### 1.2 `UringRawWriteBench.java` — NEW scope (Phase 1.7 delivers only the read skeleton)
Owner: sub-phase 2.2. Mirrors A4 (256k seqwrite buffered) and A5 (4k randwrite
O_DIRECT, preallocated file) shapes; same param/setup structure; adds
writeback-settle handling between iterations (sync + drop_caches + 10 s, per spec §4)
and fsync arms via the binding's fsync API.

### 1.3 No build changes needed — verified
- `ant microbench -Dbenchmark.name=X` compiles src → jar → test automatically
  (`microbench` → `maybe-build-test` → `build-test` → `_main-jar`,
  `build.xml:1227-1231`); `-Dno-build-test=true` is the per-cell rebuild skip.
- Fork classpath includes the main jar (so the `io.uring` binding) + `lib/jna-5.13.0.jar`
  automatically (`cassandra.classpath.test`, `build.xml:474-483`).
- Named benches bypass `microbench.exclude.pattern` entirely (`build-bench.xml:124-127`,
  `if:blank`) — do not touch the blacklist either way.
- Extra JMH args: `-Djmh.args="..."` (`build.xml:78`, spliced at `build-bench.xml:119`).
  Constraint: `<arg line>` splits on whitespace — **no values with spaces** (paths!).
- Results: always `build/test/output/jmh-result.json`, overwritten per invocation —
  driver copies per cell. JMH 1.37 (`parent-maven-pom.xml:667-675`); under `-bm sample`
  the JSON has `primaryMetric.scorePercentiles` with key `"99.9"` = p999. Parse script
  asserts the key exists on first real output (fixture-vs-real lesson).
- JMH fork inherits `-Xmx1G` (`build-bench.xml:101-108`) — ample for QD64 buffers; if
  ever insufficient the escape hatch is `@Fork(jvmArgsAppend=...)`. No `-ea` in the
  bench JVM chain — binding asserts are free but silent in Phase 2 runs.
- `microbench-with-profiler` self-installs async-profiler 2.9 into
  `build/async-profiler/` (`build-bench.xml:66-86,147-153`) — needs outbound internet
  on the rig once.

## 2. Scripts (all in `phase-2-benchmark/jobs/`, rsync'd per runbook; results pulled back to `phase-2-benchmark/results/`)

| Script | Role |
|---|---|
| `gen-fio-jobs.sh` | Emit the 32 §1.1 Level-A jobfiles + manifest (json output, runtime/time_based per spec §1.2) |
| `make-bench-file.sh` | 32 GiB pseudo-random file per fs via fio itself (`size=32g, rw=write, bs=1M, refill_buffers=1`) — **same file serves Level A and B** so cold reads hit identical layouts |
| `run-fio-cells.sh` | Per cell: eBPF pkill sweep, governor set+record, readahead record, drop_caches (cold), nohup fio, samplers, JSON → `/data/results/uring_fio_v1/<cell>-iter<n>.json`; log grep covers `error|invalid|usage|Permission` |
| `run-jmh-cells.sh` | Per cell: `taskset -c <cpus> ant microbench -Dbenchmark.name=... -Dno-build-test=true -Djmh.args="-bm sample -f 1 -wi 5 -i 5 -p file=... -p qd=... ..."`; fork PID via `pgrep -f jmh.runner.ForkedMain` for pidstat; copy jmh-result.json per cell. One `ant jar` + class-in-jar check before the sweep |
| `samplers.sh` | `iostat -xz 1`, `pidstat -w -p <pid> 1`, `ps -eLo comm \| grep -c iou-wrk` @1Hz, `/proc/meminfo` Dirty/Writeback @1Hz |
| `collect-results.sh` | fio+JMH JSONs → one CSV, B/A ratios, the five headline comparisons; asserts `scorePercentiles["99.9"]` present |
| `strace-window.sh` | Separate non-measured window; attach to the JMH fork PID (or run with `-f 0` so the bench runs in the runner — syscall counting only) |

Pinning facts: taskset affinity inherits ant → runner → JMH fork (incl. GC threads);
single-socket 12-core box, no numactl. iou-wrk kernel workers are NOT bound by
taskset — that is part of what's measured. `ulimit -l` (8 GiB, runbook) inherits into
the fork — registered-buffer arms fine.

## 3. Questions closed by this research

1. Extra-args mechanism exists (`-Djmh.args`); no conflict with ant defaults.
2. File path parameterization = `@Param` + `-p` (no in-tree System.getProperty bench
   precedent; `-p` self-labels the JSON).
3. p999 is extractable from `jmh-result.json` under `-bm sample`.
4. Binding + JNA reach the fork classpath with zero build changes.
5. O_DIRECT open/fd/alignment idioms pinned (FileUtils/ChannelProxy/BufferUtil).
6. Cold-drop CAN be done in-bench (root) — resolved to in-bench `@Setup(Level.Iteration)`.
7. 32 GiB file generation: fio `create_only`-style job, one tool for both levels.
8. strace target = the JMH fork, not ant's JVM.

## 4. Open questions (need decision before/at execution)

1. **Hot-arm priming** — pinned here as explicit sequential full-file read in
   `@Setup(Level.Trial)` (deterministic; 4k-random warmups would never touch all
   32 GiB). Confirm in spec §1.1.
2. **Reverse results transfer** — runbook has push-rsync only; add the pull command
   (rig `/data/results/uring_*` → `phase-2-benchmark/results/`) on first use.
3. **Live mount options unrecorded** — capture `mount | grep bench` into runbook.md
   before the sweep (relevant to O_DIRECT and the A4 buffered-write probe; only mkfs
   flags are recorded today).
4. **CPU split for pinning** — which cores get the JMH fork vs the samplers (12-core
   box; CPU-fence lesson). Pin in `run-jmh-cells.sh` at execution.

## 5. TPC hotspot notes (Enberg ANCS'19 — full mapping in `../findings-tpc-paper.md` §3)

- **Keep the QD1 cells.** The paper shows TPC-style designs *lose* when work isn't
  distributed/deep; sync-QD1 ≈ pread minus syscall cost. A matrix without low-QD
  cells overstates the ring — QD1 numbers are the honest floor, not noise.
- **Record IRQ config per cell** (paper's dominant tail variable — results flipped
  between IRQ regimes): snapshot `grep nvme /proc/interrupts` + irqbalance state into
  the per-cell results dir; add an irqbalance-state assert to the `run-*-cells.sh`
  preflight. NVMe completion softirqs landing on the pinned bench core vs elsewhere
  changes tail.
- **iou-wrk placement, not just count**: unpinned io-wq kernel workers are the storage
  analogue of the paper's packet-bounce-across-cores complaint. `samplers.sh` should
  record which CPUs they run on (`ps -eLo psr,comm | grep iou`), feeding I2b's
  shard-affinity expectations.
