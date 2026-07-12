# I1 single-node off-box A/B — off vs shard_routing (write-heavy)

**Goal:** measure whether I1 (shard-routed mutation apply, lock kept) is p99-neutral-or-better at
matched write throughput, single-node, off-box loadgen. This is the cheap signal before committing
to the RF=3 multi-node cloud gate. I5 (`inbound_shard_dispatch`) stays OFF — inert single-node
(coordinator==replica → no inbound MUTATION_REQ).

**Arms (same build, flag only differs):**
- **off** = `-Dcassandra.mutation.shard_routing` absent → trunk/SEDA apply.
- **I1**  = `-Dcassandra.mutation.shard_routing=true` → routed apply, lock kept.
Read-once at class init → **full node restart per arm.**

## Topology (as provisioned 2026-07-12)
- **Cassandra:** bare-metal rig `157.180.98.112` (Helsinki, Xeon E-2276G, 6c/12t, 16 G heap).
  Build `/root/repos/fork/cassandra-tpc-i1` (fresh clone origin/tpc-migration `c5c29943b9`,
  `ant jar` OK, I1 classes verified in jar). conf `/data/tpc-poc/conf`, data `/data/tpc-poc/data`,
  commitlog `/commitlog/tpc-poc`. Native `0.0.0.0:9042`, no firewall.
- **Loadgen:** Hetzner Cloud `ccx43` (16 vCPU) `62.238.35.142`, hel1, same city → sub-ms RTT.
  easy-cass-stress `-all` jar copied from rig. `taskset -c 0-15`. **DELETE when done (hourly).**

## Dataset — RESET to 2M (baseline-comparable)
Existing keyspace held ~32.5M partitions / 13 G (an earlier run). Reset to the baseline's **2M
partitions** (`--partitions 2000000`, `--populate 62500` PER-THREAD × 32) for low compaction noise
and comparability to poc-criteria §8. Table = `cassandra_easy_stress.keyvalue`. Populate ONCE
(persists across per-arm restarts); `auto_snapshot:false` already set.

## Operating points (write-only, from gate-reconciliation.md; re-tune to matched achieved)
| Role | Gates? | Target achieved | ~CPU | `--rate` start |
|---|---|---|---|---|
| Primary — loaded tail | **YES** | ~240k | ~88% | 240000 |
| Secondary — mid-load | **YES** | ~180k | ~57% | 180000 |
| Characterization — low load | no (I1 crossover, expected regress) | ~120k | ~37% | 120000 |

Command (on loadgen), per point/arm/iter:
```
taskset -c 0-15 java -jar /opt/ces/cassandra-easy-stress-10-all.jar run KeyValue \
  --host 157.180.98.112 --no-schema --prometheusport 0 --readrate 0.0 \
  --rate <R> --concurrency 3000 --threads 32 --queue 2000000 --duration 160s \
  --hdr /tmp/hdr_<arm>_<pt>_i<N>
```
Gate on **matched achieved throughput** — re-tune `--rate`/`--concurrency` so both arms land at the
same delivered ops/s, THEN compare p99. Never `--rate` WITH `--maxwlat`.

## Run structure
3 iters × 2 arms × 3 points, interleave **off, I1, I1, off** (restart per arm; data persists).
Discard ~30–40 s warmup per cell; measure a 60–100 s steady window. ~1.5–2 h wall-clock.
Between arms: stop node → edit jvm-server.options flag → start → **verify flag** → re-warm.

## Verification (per arm, before trusting numbers)
- **Flag took effect:** `nodetool tpstats | grep Shard` → I1 arm shows `Shard-0..11` pools
  (CompletedTasks climbing under load); off arm shows **none** (ShardExecutors.instance null).
- **I5 stays off:** `ps -ef | grep -o inbound_shard_dispatch=[a-z]*` empty; grep conf → absent.
- **Mechanism corroboration:** `TrieMemtable` MBean `keyspace=cassandra_easy_stress,scope=keyvalue`:
  off arm `contendedPuts>0` under load; I1 arm `contendedPuts≈0`, `misroutedPuts≈0`.
- **Work actually happened:** `nodetool tablestats` Local write-count delta ≈ delivered ops;
  driver stdout errors ≈ 0 (CSV is SIGTERM-truncated — read stdout/--hdr).

## Metrics per cell
- Client p99 (ms): `--hdr .../<tag>-mutations.txt`.
- Server p99 (µs, PRIMARY gate, CO-immune): `nodetool proxyhistograms`.
- `nodetool tpstats` (MutationStage + Shard-*), gc.log overlay, `mpstat -P ALL` on rig (~88% target)
  AND on loadgen (must have headroom, else client-bound → invalid).

## Gate
At each **gating** point, matched throughput: **server p99 ≤ off-median AND client p99 ≤ off-median**,
within the 3-iter noise band (a delta inside band = tie = pass). p99.9 ≤ too. Low-load is
characterization only — I1 expected to regress there (routing hop when a permit is free); does NOT gate.

## Status
- [x] Loadgen provisioned (ccx43 hel1, ready), I1 build on rig (jar verified), token+runbook in agent-common.
- [ ] Stress jar → loadgen; stop stale node; start I1 build flag-off; reset dataset to 2M; smoke test.
- [ ] Off-arm cells (3 pts × 3 iters) + verification.
- [ ] I1-arm cells (flip flag, restart, verify Shard pools) + verification.
- [ ] Analyze off vs I1 at matched throughput; write findings; **delete loadgen**.
