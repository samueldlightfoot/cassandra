# Phase 2 Verdict — Gates G1–G4 (2026-07-09)

Executed 2026-07-08/09 on the rig (157.180.98.112, kernel 6.8.0-124, PM9A3 nvme0n1,
governor performance + irqbalance stopped for every cell, restored after; per-cell IRQ/
governor snapshots in each cell dir). Level A = fio 3.28 (32 pinned cells + 4-cell annex,
x3 iterations, spreads <=5%); Level B = JMH through the Phase 1 binding (30 cells).
Raw data: `results/uring_fio_v1/`, `results/uring_jmh_v1/` (local) and
`/data/results/uring_{fio,jmh}_v1` (rig). Parser: `jobs/parse-results.py`.
Every headline number cross-checked against a second signal (fio/JMH json vs iostat
device counters; syscall counts vs scores) — cross-check table at bottom.

## G1 — TPC viability (1 thread QD>=32 >= 70% of psync-50): **FAIL (narrow, diagnosed)**

| arm | ext4 | xfs |
|---|---|---|
| uring qd32 / qd64, 1 thread pinned | 258,099 / 260,103 | 260,944 / 260,749 |
| + fixedbufs + registerfiles (annex, qd64) | 277,774 | 280,025 |
| psync 50 threads, 12 cores | 417,566 | 417,840 |
| **ratio (plain / fixed)** | **61.8–62.3% / 66.5%** | **62.4–62.5% / 67.0%** |

- Mechanism, not noise: the pinned core saturates (usr+sys = 100%, 82% kernel) at
  ~3.8 µs CPU/op; qd32==qd64 flat = CPU-bound, not device-bound. The psync-50 arm rides
  the device's ~QD50 curve on 12 cores. Context switches: ring ~1,700 total vs psync-50
  50,070,387 (exactly 1/op) over 120 s.
- ~1/3 of the shortfall is recoverable per-op overhead (annex +7%); profile shows ~17%
  of kernel CPU is Intel IOMMU per-op DMA mapping (`intel_iommu=pt` = future env lever).
- fio 3.28 cannot set SINGLE_ISSUER|DEFER_TASKRUN (binding's tier); Level B evidence
  (binding beats fio 1.7–1.8x on hot reads / DIO writes) says the true kernel ceiling is
  somewhat higher than fio shows — not enough to claim 70% without measuring it.
- **Pre-committed consequence:** hurdle recorded — one shard thread on this hardware
  class drives ~2/3 of whole-box depth-50 throughput. The PoC keeps a small I/O pool for
  cache-miss reads (10994's stage-1 compromise, now chosen by measurement); the shard
  model proceeds unchanged. Aggregate submission is over-provisioned regardless
  (12 x 260k >> device ~500k); the gate exists for the hot-shard scenario.

## G2 — JVM/JNA tax (B/A >= 0.8 on the G1 shape): **FAIL, attributed**

- B/A on batched qd32/64 direct cold: **0.66–0.70** (JMH 181k vs fio 260k = 5.5 vs 3.8 µs/op).
- Attribution (async-profiler collapsed stacks, `results/uring_jmh_v1/
  profile-batched-qd64-collapsed-cpu.csv`): ~25–35% of CPU in the userspace JNA
  trampoline + libc syscall stub; ~65% shared kernel path; GC ~0; bench-loop collections
  <1%. Arithmetic closes: 1.7 µs/op delta ≈ 31% ≈ the JNA share.
- Tax is shape-specific: ALL qd1 cells B/A = 0.96–1.10 (invisible at device latency);
  hot batched reads 1.77–1.84x IN FAVOR of the binding (1.24M/1.37M cached reads/s/core);
  DIO batched writes 1.68–1.72x in favor (288k w/s, iostat-verified) — the binding's
  flag tier (unavailable to fio 3.28) has real measured value.
- **Pre-committed consequence:** G1's verdict rests on Level A; hand-JNI (per-syscall
  ~100–200 ns + trampoline elimination) goes into the Phase 3 effort estimate as a cost
  line. It attacks the exact 25–35% identified here.

## G3 — buffered async writes (XFS ~0 punt AND >=1.5x psync at QD32): **FAIL, and the premise inverted**

- iou-wrk workers during buffered 256k seqwrite: **XFS up to 32** (one per queued op),
  **ext4 <= 2**. The "XFS >=5.19 stays inline / ext4 punts" story is the wrong way round
  on kernel 6.8 under sustained dirty-throttled writes.
- Throughput: engines identical (0.99x) on both fs (~1.3–1.5 GB/s, writeback-bound; QD
  buys nothing). Ring completion tails awful (p99 14–20 ms vs psync 123 µs on ext4).
- **Consequence under the §5.1 target state:** moot for background writers — they target
  O_DIRECT + ring, which never punts on any fs (A5: 168–172k w/s one thread = 2.3x one
  psync thread; binding does 288k). The punt result matters only if a buffered write
  path remains; record, don't re-architect around it.

## G4 — sanity (hot QD1 ring read SLOWER than pread): **PASS, both levels**

- fio: ring qd1 hot = 84.4/84.8% of psync (702k/751k vs 832k/886k IOPS).
- JMH: ring sync = 75.3–76.5% of FileChannel pread (684k/733k vs 894k/973k ops/s).
- Bigger gap than the spec's ~2–10% guess, same direction; harness honest.

## Syscalls-per-op KPI (binding KPI from phase-1 expected-changes §6)

Whole-run strace (`results/uring_jmh_v1/strace2/`), ops = score x 33 s:
pread 1.00 pread64/op · ring sync 1.00 enter/op · **batched qd64 0.25 enter/op**
(≪1 = batching proven; not 1/64 because the bench loop calls submit() every pass —
bench shape, improvable, not binding cost).

## Execution findings beyond the gates

1. **Binding bug found & fixed (would have been a flaky PoC read failure):** syncOp
   treated io_uring_enter's signal-after-submit short-SUCCESS return (man page
   documented) as corruption. Now wait-loops like awaitCompletions. 28/28 Uring tests
   green on rig post-fix (2026-07-09). Found because the strace attach window perturbed
   timing — the measured sweeps alone would never have caught it.
2. A2 "cold buffered" time_based cells are cache-fill profiles (file cached in ~20 s);
   valid within-shape, absolute numbers are mixed miss/hit.
3. A4 B/A is approximate by design (JMH fsyncs DATASYNC/64 MiB via the ring; fio uses
   end_fsync per 32 GiB pass) — punt detection was the point, not the ratio.
4. b5 caveat: JMH 5 s bursts vs fio continuous 16 GiB passes — device state differs;
   but fio's iostat max (175k) never approached the binding's sustained 276–288k.

## Cross-checks (>=2 independent signals per headline)

| headline | signal 1 | signal 2 |
|---|---|---|
| G1 uring qd64 260,103 | fio json | iostat 257,726 r/s mean (-1%, ramp) |
| G1 psync-50 417,566 | fio json | iostat 412,983 r/s mean (-1%) |
| b5 binding 288k w/s | JMH score | iostat 276,540 w/s mean, 288,131 max |
| ctx switches 1/op vs ~0 | fio `ctx` field | pidstat -w logs (per-cell .d dirs) |
| syscalls/op | strace2 counts | JMH scores x wall time (match to 0.3%) |

## Exit-gate status (spec §3)

- verdict.md (this file) — all four gates adjudicated ✅
- results archived on rig AND locally under `results/` ✅
- `../findings.md` §8 (Level A) + §9 (Level B) written ✅
- runbook updated (fio version, stance, pull-rsync, mounts, SMART; IOMMU note pending) ✅
- **Phase 2 exit gate: PASSED.** Gate outcomes classify the PoC's I/O shape per the
  pre-committed consequences; nothing blocks Phase 3.
