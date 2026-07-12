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

## 2026-07-12 (later) — PAUSED again; OFF-arm DONE, I1-arm now running

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
