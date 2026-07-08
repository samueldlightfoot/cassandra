# Phase 2 Spec — Benchmark: the TPC-Shaped I/O Question, Quantified

**Status:** Ready to execute once Phase 1 exit gate passes.
**Question under test (Phase 0 D4):** can **1 thread + io_uring ring at QD 8–64** match or
beat **N threads + pread64** on this rig's NVMe — buffered and O_DIRECT, cache-cold and
cache-hot, ext4 and XFS? Secondary: quantify the JVM/JNA tax and the io-wq punt behavior
per filesystem.
**Why this matrix:** under TPC one shard thread owns its core's I/O and serialises to QD1
with pread64; today's architecture gets QD≈50 free from thread count. If a single ring
thread can pull most of the device's throughput, the I/O layer stops being a TPC blocker.
Expectations to confirm (not assume): single-op cache-hot ring I/O LOSES to pread64;
batched cold O_DIRECT WINS; XFS ≥5.19 buffered writes stay inline while ext4 punts.

Everything runs on the rig (157.180.98.112) with Cassandra STOPPED — `nvme0n1` hosts both
bench partitions and must be quiet. Benchmarks are raw-file (fio + JMH); no Cassandra
process is involved anywhere in Phase 2.

---

## 1. Design: two levels, one delta

| Level | Tool | What it isolates |
|---|---|---|
| A | **fio** (native, has `io_uring` and `psync` engines) | Hardware/kernel ceiling per shape — no JVM anywhere |
| B | **JMH** via Phase 1 `UringRawReadBench`/`UringRawWriteBench` | Same shapes through the JNA binding |

**Delta A→B = the JVM/JNA tax.** Without Level A you cannot tell "io_uring doesn't help"
from "the binding is slow" — this separation is the load-bearing design decision.

Fixed context for every run: CPU governor `performance` (set + record; restore after);
no stale eBPF monitors (`pkill` sweep first — 32% regression lesson); readahead recorded;
kernel version + `UringAvailability` features word + flag tier logged per run; runs end
by duration/completion, never SIGTERM (CSV-truncation lesson).

### 1.1 The matrix (pinned — 32 fio cells + 24 JMH cells, not a free-for-all)

Common axes: fs ∈ {`/bench-ext4`, `/bench-xfs`} (same NVMe, Phase 0.3); file = 32 GiB
pseudo-random (RAM is 64 GB — cold arms also drop caches; cold = `echo 3 >
/proc/sys/vm/drop_caches` immediately before each iteration, hot = one full priming pass).

**Level A (fio), per fs — 16 cells each:**
| # | Shape | engine=io_uring | engine=psync |
|---|---|---|---|
| A1 | 4k randread, direct=1, cold | iodepth ∈ {1,8,32,64}, numjobs=1 | numjobs ∈ {1,50}, iodepth=1 |
| A2 | 4k randread, direct=0, cold | iodepth ∈ {32}, numjobs=1 | numjobs ∈ {50} |
| A3 | 4k randread, direct=0, hot | iodepth ∈ {1,32}, numjobs=1 | numjobs ∈ {1} |
| A4 | 256k seqwrite, direct=0 (buffered write — the 5.19 XFS fast-path probe) | iodepth ∈ {32}, numjobs=1 | numjobs ∈ {1} |
| A5 | 4k randwrite, direct=1, preallocated file | iodepth ∈ {32}, numjobs=1 | numjobs ∈ {50} |

**Level B (JMH), per fs — 12 cells each,** mirroring A1/A3/A4/A5 shapes through the
binding: mode ∈ {sync (readSync loop), batched (prepare×QD → submit → await)},
qd ∈ {1, 32, 64}, direct ∈ {true, false} — pinned subset matching the fio cells so every
JMH number has a native twin.

Headline comparisons the analysis MUST produce:
1. `io_uring 1-thread QD64` vs `psync 50-thread` — A1, both fs (the TPC question).
2. Same pair at Level B (JMH batched QD64 vs... note: B has no 50-thread pread arm; use
   fio psync-50 as the reference bar — recorded rationale: Phase 1's binding is
   single-owner-thread; a 50-thread JMH pread bench adds nothing fio doesn't show).
3. B/A ratio per shape (JVM tax), flagged if < 0.8.
4. A4 io_uring: iou-wrk thread count ext4 vs XFS (punt vs inline buffered writes).
5. A3/B hot QD1: confirm the expected pread64 win (harness sanity check).

### 1.2 Metrics per cell
- fio: `--output-format=json` — IOPS, BW, clat p50/p99/p999, cpu usr/sys. One
  `--runtime=120 --time_based` (reads) or size-bound (writes) run per cell, 3 iterations,
  report median (record all three; >10% spread = investigate before trusting).
- JMH: throughput + sample-time percentiles (`-bm sample`), JSON via the ant harness
  (`jmh-result.json`), `-f 1 -wi 5 -i 5` minimum.
- System-side samplers per cell (always-on, cheap — no eBPF): `iostat -xz 1` (device
  truth), `pidstat -w -p <pid> 1` (context switches), `ps -eLo comm | grep -c iou-wrk`
  sampled 1/s (punt detection), `/proc/meminfo` Dirty/Writeback 1/s (buffered write arms).
- One separate short `strace -c -f` window per JMH mode (NOT during measured runs) to
  count syscalls/op — proves batching (QD64 batched ⇒ enter calls ≈ ops/64).

---

## 2. Sub-phases

### 2.1 Rig prep + Level A (fio ground truth)
Deliverable: `/data/results/uring_fio_v1/` + fio table in `../findings.md`.
1. `apt install fio` (need ≥3.13 for stable io_uring engine; record `fio --version` in
   runbook.md). Verify engine: `fio --enghelp | grep io_uring`.
2. Confirm Phase 0 partitions mounted; Cassandra stopped; governor performance;
   `ulimit -l` recorded (registered-buffer arms in B need MEMLOCK headroom).
3. Generate fio jobfiles for §1.1 Level A programmatically (one script, checked into
   `tasks/tpc-migration-planning/phase-2-benchmark/jobs/` locally, rsync'd — the rsync
   command from runbook.md; verify files on rig BEFORE launching — rsync lesson).
4. Run under `nohup`, monitor via log greps that match failure text too
   (`error|invalid|usage|Permission` — monitor-silence lesson).
5. Parse JSONs → one CSV + the five §1.1 headline comparisons (fio-only preliminary).
Acceptance: all 32 cells have 3 iterations with <10% median spread (or documented
explanation); headline table drafted.

### 2.2 Level B (JMH through the binding)
Deliverable: `/data/results/uring_jmh_v1/` + B/A ratio table in `../findings.md`.
1. Extend Phase 1.7 skeleton: `UringRawReadBench` (+`UringRawWriteBench`) params exactly
   as §1.1-B; file path param so the same class runs against both mounts.
2. Fresh `ant jar` on rig clone; verify Uring classes in jar (JAR lesson); run each cell
   via `ant microbench -Dbenchmark.name=UringRaw...` with `-Djmh.args` for params; JSON
   per cell copied into the results dir (the harness overwrites `jmh-result.json`).
3. Samplers as §1.2; strace window runs separately at the end.
Acceptance: every B cell has a paired A cell and a computed B/A ratio; syscall-count
proof of batching recorded.

### 2.3 Punt & tax analysis
Deliverable: analysis section in `../findings.md`.
- iou-wrk counts: ext4 vs XFS buffered writes (A4/B write cells) — table + verdict on
  the 5.19 fast-path claim as observed on THIS rig.
- Context-switch and cpu/op comparison io_uring-batched vs psync-50 (the mechanism check:
  if io_uring wins, the win should show up as fewer switches/syscalls, not magic).
- JVM tax: B/A per shape; if any < 0.8, one profiling pass (async-profiler via
  `ant microbench-with-profiler`) to attribute (JNA boundary? GC? ring logic?) before
  concluding.

### 2.4 Verdict against gates
Deliverable: `verdict.md` in this directory + `../findings.md` "Phase 2 outcomes"; gates:
- **G1 (TPC viability):** 1-thread io_uring QD≥32 O_DIRECT 4k cold ≥ **70%** of
  psync-50-thread IOPS, on both filesystems. Rationale: a shard owns ~1/nproc of CPU; if
  one thread can drive ≥70% of what 50 threads drive, per-shard I/O is not the TPC
  bottleneck (and TPC removes cross-thread costs this bench can't credit).
- **G2 (JVM tax):** B/A ≥ 0.8 on the G1 shape. Fail → hand-JNI cost goes into Phase 3
  effort estimate; G1 verdict then rests on Level A with B as supporting evidence.
- **G3 (buffered async):** XFS buffered-write io_uring shows ~zero iou-wrk activity and
  ≥1.5× psync-1-thread throughput at QD32; ext4 behavior recorded either way.
- **G4 (sanity):** hot-cache QD1 sync ring read is SLOWER than pread64 (expected ~2-10%).
  If it isn't, suspect the harness before celebrating.
Each gate gets PASS/FAIL/PARTIAL + the number + pointer to raw data. Cross-check every
headline number against ≥2 independent signals (fio json vs iostat; JMH vs pidstat —
cross-check lesson) before writing the verdict.

---

## 3. Exit gate (Phase 2 → Phase 3)

verdict.md exists with all four gates adjudicated · results dirs archived on rig AND
copied locally under this directory (`results/` — raw JSONs + CSVs) · findings.md updated
· runbook.md updated with fio version, governor, ulimit, any surprises.

**Gate consequence (pre-committed — reframed 2026-07-07: gates classify hurdles for the
PoC, they do not kill it):** G1 PASS → the PoC's shard threads own their I/O via the
ring, as designed. G1 FAIL on Level A (fio itself can't do it) → hurdle recorded: this
hardware class can't feed a core from one thread; the PoC keeps a small I/O pool for
cache-miss reads (10994's stage-1 compromise, now chosen by measurement instead of
imposed by AIO's limits) and the shard model proceeds unchanged. G1 PASS but G2 FAIL →
hurdle recorded: JNA tax; the PoC proceeds with the binding as-is and hand-JNI is noted
as a CEP-era optimization.

## 4. Risks
- Hetzner NVMe (consumer-class Samsung) may saturate at low QD, compressing the
  io_uring-vs-psync gap — if the device tops out at QD8, record that; the TPC argument
  weakens on THIS hardware but the methodology transfers to bigger rigs (note for
  Phase 4 writeup; NoWA rig-upgrade thread is related context).
- Buffered-write cells dirty large amounts of page cache — respect writeback settling
  time between iterations (sync + drop_caches + 10 s settle, scripted).
- Thermal/SMART throttling across long sweeps — record `smartctl -a` before/after; keep
  total sweep < 3 h; interleave fs arms to spread wear.
