# Progress — I1 single-node off-box A/B

## 2026-07-12 — PAUSED mid-run (user powering off local machine)

**Everything is provisioned and the off-arm is running detached on the rig.** Nothing here depends
on the local Mac — the benchmark is a `setsid` process on the rig and continues without it.

### Live infrastructure (STILL RUNNING — costs money)
- **Loadgen:** Hetzner Cloud `ccx43` `tpc-loadgen` = `62.238.35.142` (hel1, 16 vCPU). **Bills hourly.**
  Left running deliberately so the I1-arm can run without re-provisioning. Stress jar at
  `/opt/ces/cassandra-easy-stress-10-all.jar`; driver scripts in `/opt/ces/` (`sp-hdr.sh`, `populate.sh`).
  **To delete when done:** `HCLOUD_TOKEN=$(cat ~/repos/agent-common/.secrets/hcloud.token) hcloud server delete tpc-loadgen`
- **Cassandra:** rig `157.180.98.112`, build `/root/repos/fork/cassandra-tpc-i1` (I1 classes verified in
  jar), conf `/data/tpc-poc/conf`, data `/data/tpc-poc/data`. Node UP, **flag OFF** (off arm). JMX 7199.
- Dataset: fresh **2M partitions / 429 MB** (`cassandra_easy_stress.keyvalue`), compactions settled.

### Done
- Token stored (gitignored `agent-common/.secrets/hcloud.token`) + `agent-common/rig/cloud.md` runbook.
- Loadgen up, I1 build deployed, stale node swapped, dataset reset+populated, end-to-end smoke OK.
- **Calibration curve (off arm):** offered→achieved / srv-p99 / rigCPU:
  200k→178k / 0.92ms / 63% · 400k→**240k / 3.3ms / 98% (knee)** · 700k→68k(collapse) · 1100k→28k(collapse).
  Box is CPU-bound at the knee (~240k @ ~98%); overload cliff past offered ~400k.
- Operating points locked (offered): **loaded 320000, mid 200000, low 120000**, 3 iters each.

### In flight (rig-side, detached — will finish ~26 min after 11:2x UTC launch)
- **OFF-arm block** `orchestrator.sh off 3 loaded:320000 mid:200000 low:120000` (pid was 450120).
  Writes `/root/results/summary_off.tsv` (+ raw `ph_/sp_/hdr_/mpstat_` per cell) and logs
  `/root/results/orch_off.log` (marker `DONE arm=off` when complete).

### RESUME STEPS (next context)
1. Collect off-arm: `ssh root@157.180.98.112 'column -t /root/results/summary_off.tsv'` (wait for
   `DONE arm=off` in orch_off.log).
2. **Flip to I1 arm:** add `-Dcassandra.mutation.shard_routing=true` to
   `/data/tpc-poc/conf/jvm-server.options`; restart node (drain + kill pid + `CASSANDRA_CONF=/data/tpc-poc/conf
   /root/repos/fork/cassandra-tpc-i1/bin/cassandra -R`); **verify** `nodetool tpstats | grep -E 'Shard-[0-9]'`
   shows the 12 `Shard-N` pools (flag ON). Confirm `inbound_shard_dispatch` still absent.
3. Run I1 block: `setsid /root/orchestrator.sh i1 3 loaded:320000 mid:200000 low:120000 ...` → `summary_i1.tsv`.
4. **Gate:** compare srv_wp99us (primary, from proxyhistograms) off vs i1 at MATCHED achieved/s, per point,
   within 3-iter band. Low-load is characterization only (I1 expected to regress). p99-neutral-or-better = pass.
5. **DELETE loadgen** (command above). Write findings.

## 2026-07-12 (RESULT) — valid A/B done; i1 +28.5pp CPU root-caused to coordinator↔shard rendezvous

**See `findings.md` for the full writeup.** Valid A/B (v2 harness: truncate-per-cell +
autocompaction-off, tripwire green): i1 = **+28.5pp CPU / p50 14→149µs at matched 178.8k**.
async-profiler (both arms) root-caused it: shard routing makes the coordinator **park waiting
for the local apply**, the shard thread **signals/unparks it** — a futex rendezvous per write
(620k cs/s). OFF applies inline (no handoff). ShardExecutors subtree 0.07%→22.9%;
WaitQueue/Condition.signal 1.5%→16.5% (usr); futex/unpark ~5%→~19% (sys). Not a bug — an RF=1
artifact; RF≥3 should hide it behind the remote-replica wait (HYPOTHESIS → multi-node gate).
asprof A/B method documented in agent-common `tools/async-profiler.md`.

**Infra state:** orchestrator STOPPED; node UP (off arm, autocompaction disabled — needs
`enableautocompaction` + restart before any reuse); loadgen 62.238.35.142 UP (billing ~6h).
Full 12-cell matrix aborted after root-cause (remaining cells would only reconfirm +28pp).

## 2026-07-12 (latest) — INVALID run discarded; tightened harness re-running

**The off/i1 block run below is INVALID** — `sp-hdr.sh` (measurement client) omitted
`--partitions`, so the dataset grew 429 MB → ~7 GB *during* the matrix. Compaction tax
aliased onto arm order: "i1" mid showed 91% CPU / 2759µs p99 vs off 69% / 1331µs at the
same 178k ops/s — pure confound, zero mechanism. Diagnosis confirmed with numbers.

**Methodology documented** so it can't recur (agent-common, durable):
`benchmarking/methodology.md` §2 (pin write key space), §6 (single-node gate on server
p50/p95, not GC/CO tail), §8 (interleave arms + per-cell invariant tripwire);
`cassandra/stress-tooling.md` §3 (pin `--partitions` on run phase, not just populate);
`gotchas.md` Benchmarking-Validity row. (Uncommitted — awaiting user.)

**v1 tightened harness ALSO INVALID** — the `--partitions 2000000` pin does NOT bound the
`run`-phase key space in easy-cass-stress (KeyValue `run` writes ~1 new partition/op; killed
run hit **50.9M partitions / 14 GB**). Confirmed via tablestats. Even v1 cell-1 grew
439 MB → 5 GB *within its own window*, so no cell was clean. `--partitions` only affects populate.

**v2 fix (write-only insight):** workload is `--readrate 0`, so existing data is irrelevant
to the measured path. Per cell: **TRUNCATE to empty** (13 GB → 84 K in 1.4s, verified) →
**`disableautocompaction`** during the window → measure. Every cell starts identical (~0 MB,
tripwire = equal start), grows equally (same write volume), and the µs write-apply signal
isn't buried under compaction's ~30pp-CPU noise. Summary logs mb0 (start) + mb_end per cell.
Relaunched 17:08 UTC. **VALIDATION GATE: cells 1 (off_mid) + 2 (i1_mid) must both start ~0 MB
with comparable p50** — else stop again.

**v1 tightened harness (SUPERSEDED — see v2 above):**
- Client pins `--partitions 2000000` (fixed 439 MB baseline; live MB logged per cell as tripwire).
- Node restarted every cell → fresh JVM + empty proxyhistograms (no metric bleed) + arm flip.
- Compaction drained to pending=0 before each measurement window.
- Arms interleaved per cell, point order balanced across 3 rounds (12 cells: off/i1 × mid/loaded × 3).
- Gate: server p50/p95 + rig CPU @ matched throughput. p99/Max reported, NOT gated (GC/CO; multi-node's job).
- Cell c01_off_mid clean: p50 14µs / p95 372µs / ach 178.9k / cpu 60.7% / **live_MB 439 (stable)**.
- Monitor: `grep "DONE tightened" /root/results/orch_tight.log`; then GATE + write findings + DELETE loadgen.

## 2026-07-12 (later) — PAUSED again; OFF-arm DONE, I1-arm now running [SUPERSEDED — INVALID, see above]

**OFF-arm results (SEDA baseline), 3 iters each — loadgen ≤53% CPU (server-bound, valid):**
| point | offered | achieved/s | srv_wp99µs | rig_cpu% |
|---|---|---|---|---|
| loaded | 320k | ~232k | 3311 | 97% |
| mid    | 200k | ~178k | 1331–1916 | 69% |
| low    | 120k | ~108k | 535–642 | 40% |
(client stdout p99: loaded ~11.6ms [CO-inflated near saturation], mid ~0.43ms, low ~0.13ms)

**I1-arm: flag flipped + VERIFIED.** `-Dcassandra.mutation.shard_routing=true` at
`jvm-server.options:217`; node restarted; `nodetool tpstats` shows **12 `Shard-N` pools** (flag ON);
`inbound_shard_dispatch` absent in cmdline (I5 off). I1 block launched:
`orchestrator.sh i1 3 loaded:320000 mid:200000 low:120000` (pid was 454636) → `summary_i1.tsv`,
marker `DONE arm=i1` in `orch_i1.log`. ~26 min.

**RESUME (next context):**
1. `ssh root@157.180.98.112 'column -t /root/results/summary_i1.tsv'` (wait for `DONE arm=i1`).
2. GATE: compare `srv_wp99us` off vs i1 at MATCHED achieved/s per point, within the off-arm noise band.
   loaded+mid gate; low is characterization (I1 expected to regress). p99 ≤ off = pass.
3. **DELETE loadgen:** `HCLOUD_TOKEN=$(cat ~/repos/agent-common/.secrets/hcloud.token) hcloud server delete tpc-loadgen`
4. Write findings.md; update task_plan status.

### Known issues / notes
- Client `--hdr` mutations files came up **empty** in calibration (0 lines) — investigate on resume
  (naming/flush); server proxyhistograms (the PRIMARY gate metric) capture fine, so not blocking.
- Orchestrator parse bugs fixed after calibration (ANSI-strip for achieved/cli-p99; CPU busy=100-%idle;
  swapped absent p99.9 row for Max). summary_off.tsv uses the fixed version.
- `refs`/build: I1 build is origin/tpc-migration `c5c29943b9` (has I1 code; missing only the I5-metrics
  commit e710b56deb, inert single-node).
- Loadgen scripts: `sp-hdr.sh <rate> <cc> <threads> <dur> <tag>` (adds `--hdr`); never `--rate`+`--maxwlat`.
