# Progress — Tier 1 loaded/CPU-bound tail test

## Session 2026-07-15 — environment stood up, knee-probing

**User decisions:** direction = commit Tier 0 fixes → Tier 1 tail (chosen 2026-07-15). I/O-knee removal method
= `durable_writes=false` first, escalate to tmpfs if still I/O/GC-capped.

**Loadgen box (fresh — old one deleted):** provisioned `tpc-loadgen` ccx43 (16 vCPU EPYC, 61 GB, hel1) at
**62.238.35.142** (Hetzner reassigned the SAME IP as the deleted box → existing scripts/memory still valid).
cloud-init installed jdk17+sysstat+htop+rsync. easy-cass-stress seeded rig→loadgen to
`/root/repos/cassandra-easy-stress/` + wrapper `/usr/local/bin/cassandra-easy-stress`. Rig pubkey authorized
on loadgen; loadgen reaches rig:9042. **BILLS HOURLY — delete when done.**

**Rig config for CPU-bound regime:**
- `ALTER KEYSPACE cassandra_easy_stress WITH durable_writes = false` (drops commitlog).
- irqbalance stopped + disabled. NIC = `eno1`, single IRQ 133. HT siblings: physical core N = logical {N, N+6}.
- Cassandra currently unpinned (affinity 0-11); launched via `prep_flip.sh` → `setsid bin/cassandra -R` (NOT
  systemd) — so IRQ core-reservation needs `taskset` baked into that launch (TODO, before HDR runs).
- Live jar = alloc-gap (5348018527).

**THE stress-tool gotcha that cost ~30 min (re-confirmed [[feedback_easy_cass_stress_scripted_run_gotchas]] /
STRESS-RUNBOOK):** `--rate` DEFAULTS TO 5000. Running with NO `--rate` self-throttled to ~5k/s (rig looked
idle). Correct method: `--rate <above-knee> --concurrency 3000 --threads 32 --queue 2000000`, read cass CPU as
the saturation signal. With that, off-box drove **~200–240k writes/s, 0 errors** (durable_writes=false) —
already above the old ~140k co-located I/O knee.

**Measurement gotcha:** snapshot mpstat/tablestats from a separate SSH call kept mis-timing the load window
(showed idle+0 while the run clearly delivered +28M to the table counter). Switched to a rig-side TIME-SERIES
(count-delta + mpstat every 10s) to read the actual CPU-during-load — running now. Also resolving a second
`CassandraDaemon`-matching pid (676699; only 673551 listens on 9042).

**Next:** read the time-series → if cass ~≥90% busy at ~200k+ → CPU-bound, proceed to IRQ core-reservation +
interleaved HDR A/B (trunk vs alloc-gap) at a sub-knee rate (script drafted: scratchpad `tail_ab.sh`). If
cass still ~45% → escalate to tmpfs commitlog+data (fresh keyspace).

## CRITICAL FINDING (2026-07-15) — a write-only CPU-bound regime is UNREACHABLE on this box

Decoupled continuous `mpstat 2` time-series (`rig:/root/mp.log`, 101 samples over a full loadgen run) while
the server write counter climbed **+14.4M / 100s = ~144k writes/s**: **max busy = 10.0%, nearly all samples
<2%.** The box runs at **~5–10% CPU at 144k writes/s** with durable_writes=false. Cross-checked: loadgen drove
up to **275k req/s** off-box (43M writes/run, 0 errors); server counter climbs monotonically (124M→139M in the
window) — so writes ARE landing, the box just barely uses CPU.

**Interpretation:** durable_writes=false makes writes memtable-only (a concurrent-map put) — CPU-trivial. The
old "45% CPU I/O knee" (roi-path.md) was the commitlog + flush + GC; removing the I/O removed the CPU work
too. So **writes fundamentally cannot saturate CPU on this box** — this confirms roi-path.md's scaling-ladder
abandonment from the other direction. (Earlier "idle+0" snapshots weren't measurement bugs — the box really is
~idle; only the nodetool counter snapshots were flaky. mpstat was right.)

**Consequence for Tier 1:** the paper's "TPC wins under CPU-bound load" regime can't be built with WRITES here.
Making cores CPU-bound needs READ load (deserialize/merge/decompress), but the fork routes writes only (reads
fall to the shared NTR pool) → a read-bound test wouldn't exercise the mechanism. So this box + write-only can
give a clean OFF-BOX tail comparison (running now, `tail_ab2.sh` @100k) but NOT a CPU-saturated one. At ~5%
CPU, GC is minimal, so the async-machinery alloc delta likely won't surface as tail — expect the Tier-0
co-located 50–100× p99 hint to prove a co-location/profiling artifact (both arms fast off-box). The A/B result
will confirm or refute. Strategic upshot: the real ROI test needs read-path sharding (Tier 2) to enable a
CPU-bound test that exercises the mechanism, OR the big-box cross-NUMA run — not more write-only tuning here.
