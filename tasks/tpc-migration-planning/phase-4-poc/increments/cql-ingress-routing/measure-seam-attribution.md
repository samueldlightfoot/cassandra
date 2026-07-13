# Runbook — pre-build seam attribution (measure-first, before any code)

**Goal (user gate, 2026-07-13):** decompose the flip+step1 baseline's cs/op ~2.1 **by seam** (waker→wakee
thread transition) to confirm the **loop→NTR hop** is a material fraction — i.e. that deleting it (the
whole point of CQL ingress routing) has a real ceiling — BEFORE writing any code. Fable's top sequencing
note; every later measurement needs this decomposition anyway.

## Why this is new work

Prior shard-dispatch measurement gave cs/op ~2.1 as an **aggregate** (async-profiler CPU fold + vmstat
cs/s + `%sys`); `rig_capture.sh` never attributes context switches to a specific thread-pair. This runbook
adds a `perf sched` waker→wakee pass. Only `perf` is on the rig (no bpftrace/BCC) — perf is sufficient.

## Rig state (verified 2026-07-13, no cost)

- **Cassandra node `157.180.98.112`:** UP, idle (load 0.08, 7d uptime), routing ON
  (`Dcassandra.mutation.shard_routing=true`), build `cassandra-tpc-i1`
  (`build/apache-cassandra-7.0-SNAPSHOT.jar`), conf `/data/tpc-poc/conf`.
- **Thread populations for attribution (from `/proc/$PID/task/*/comm`, 15-char truncated):**
  - `epollEventLoopG` ×24 — netty **native-transport client loops** (internode is `Messaging-Event` ×4, distinct).
  - `SharedPool-Work` ×129 — the SEP pool; **coordinate/NTR (`requestExecutor`) runs here** (`Dispatcher.java:68` `SHARED.newExecutor`).
  - `Shard-N` ×12 (Shard-0..11) — the shard executors (apply).
- **`perf`** present; `sched:sched_wakeup` + `sched:sched_switch` tracepoints available.
- **Helper scripts** `/root/{prep_flip,rig_capture,measure,orchestrator_tight}.sh`. `prep_flip.sh` = fresh
  routing-ON restart + truncate + disable autocompaction. Workload = easy-cass-stress write-only
  (`--readrate 0`), off-box, ~180k delivered rung.
- **Off-box loadgen must be re-provisioned** (old `62.238.35.142` torn down). Routine, fully documented —
  NOT a blocker. **Provisioning = `~/repos/agent-common/rig/cloud.md`:** token at
  `~/repos/agent-common/.secrets/hcloud.token` → `export HCLOUD_TOKEN=$(cat …)`; `hcloud` reads the env var
  (no persisted CLI context — an empty `context list` is expected, not "unauthed" — this is the trap I hit);
  ssh-key `mac-ed25519` already uploaded; `hcloud server create --name tpc-loadgen --type ccx43 --image
  ubuntu-24.04 --location hel1 --ssh-key mac-ed25519`. Rig binds `0.0.0.0:9042` broadcast `157.180.98.112`
  → reachable off-box directly, no firewall/vSwitch step. **Bills hourly — `hcloud server delete
  tpc-loadgen` the moment the run ends.**
- **Loadgen software:** easy-cass-stress. Local clone `~/repos/cassandra-easy-stress` (apache upstream,
  `main`); seam attribution only drives load (read server-side + perf-sched), so `main` + `shadowJar`
  suffices — no `feature/csv-latency`. Bootstrap: openjdk-17-jdk + git, clone, `./gradlew shadowJar`, jar =
  `build/libs/*-all.jar`. Canonical run cmd: `phase-4-poc/STRESS-RUNBOOK.md` (`--rate 200000 --concurrency
  3000 --threads 32 --readrate 0.0 --host 157.180.98.112 --prometheusport 0`).

## The seam→thread-pair map (the attribution key)

Current 3-hop path (flip + routing ON, coordinate on SharedPool, apply on Shard, flush on loop):

| Seam | Waker → wakee (comm) | Meaning |
|---|---|---|
| **hop A** (the one CQL routing deletes) | `epollEventLoopG` → `SharedPool-Work` | loop dispatches request to NTR/coordinate |
| **hop B** (collapses to inline once bypass built) | `SharedPool-Work` → `Shard-N` | coordinate dispatches apply to owner shard |
| **hop C** (structural, kept) | `Shard-N` → `epollEventLoopG` | flip callback on shard schedules flush onto loop |
| timer (noise, in the table for honesty) | `Shard-N`/`SharedPool` → `ScheduledTasks`/scheduler | per-write deadline arm (`AbstractWriteResponseHandler.java:178`) |

## Run sequence

1. **Provision loadgen** (paid): hcloud ccx43 hel1; install easy-cass-stress fork; note IP.
2. **Prep node:** `/root/prep_flip.sh` (fresh routing-ON JVM, truncate, autocompaction off; verify
   `flag=true pools=ON` in `prep_state.txt`). — `rsync_before_rig_launch`/`monitor_silence` hygiene.
3. **Drive workload** from loadgen at the ~180k rung (match the shard-dispatch baseline: offered ~200k →
   ~180k delivered clean; write-only, CC≈3000, TH≈32, 40s warmup + ≥60s window). Off-box, CPU-fenced.
4. **During the steady window**, on the node, run in parallel:
   - existing `rig_capture.sh <tag>` (async-profiler cpu+wall, vmstat cs/s — the aggregate cross-check), AND
   - **NEW perf-sched pass** (10–15s inside the window, after warmup):
     ```
     perf record -e sched:sched_wakeup -a -o /tmp/sw_${TAG}.data -- sleep 12
     perf script -i /tmp/sw_${TAG}.data | \
       sed -E 's/^ *([^ ]+).* sched_wakeup: comm=([^ ]+).*/\1 -> \2/' | \
       sed -E 's/-[0-9]+ / /g' | sort | uniq -c | sort -rn > /tmp/wakematrix_${TAG}.txt
     ```
     Watch `perf`'s "lost events" line — if high, shorten to 8s or pin `-C 0-11`.
5. **Reconcile + attribute:**
   - Total wakeups/s from the matrix ≈ vmstat cs/s (~375k) — sanity check the capture is complete.
   - ops/s in the window (from `thru_${TAG}.txt`); per-seam cs/op = (seam wakeups / window secs) / ops-per-sec.
   - hop A fraction = `epoll→SharedPool` / total. **This is the answer.**
6. **Teardown:** delete the loadgen box. Record numbers here + in `findings.md`.

## Interpretation (pass/fail for the build)

- **hop A is material** (say ≳ 0.4–0.5 cs/op, a meaningful slice of ~2.1): deleting it has a real ceiling →
  **green-light Phase 2 build.**
- **hop A is small** (coalesced, or SharedPool wakes are dominated by something else): the server-side
  build's ceiling is low → reconsider scope before coding (maybe the flush hop C dominates → only the
  shard-aware/shard-as-EventLoop path helps, which is CEP-era).
- Either way the decomposition calibrates the expected win and becomes the baseline every Phase-3
  measurement compares against.

## Hazards (pinned memories)

`cpu_fence_colocated_loadgen` (loadgen OFF-box, prove headroom w/ mpstat), `monitor_silence_is_not_success`
(grep startup failures, not just terminal cells), `rsync_before_rig_launch` (verify behaviour on rig
first), `easy_cass_stress_scripted_run_gotchas` (never `--rate` WITH `--maxrlat/--maxwlat`; one process),
`gc_dominates_cassandra_tail` (cs/s scales with ops/s; measure at matched throughput), `bench_rig_topology`
(E-2276G 6C/12HT single L3; off-box loadgen for near-knee).

---

## RESULTS (2026-07-13) — GREEN-LIGHT

**Regime:** off-box ccx43 loadgen (hel1), write-only, `--rate 200000 --concurrency 3000 --threads 32`.
Delivered **~214k writes/s**, Cassandra **74.2%** busy (12 HT), p99 216µs, 0 errors. Baseline regime
(67.4%@180k) — slightly higher throughput/CPU, same shape. Loadgen deleted after run.

**Aggregate:** `sched:sched_switch` capture (5s, -m 128M, ~0 lost): **361,754 switches/s** at 214k
delivered ⇒ **cs/op ≈ 1.69** (vs the prior aggregate ~2.1 @180k — cs/op falls with load via batching,
exactly Fable's Q1 caution; the ratio below is the robust signal).

**Seam decomposition (the answer):**

| metric | value | meaning |
|---|---|---|
| **switches touching SharedPool** | **786,727 = 43.5% of all = 0.735/op** | the NTR/coordinate pool CQL routing **bypasses** |
| switches INTO SharedPool | 424,318 = 0.396/op | coordinate gets scheduled |
| switches INTO Shard-* | 409,550 = 0.383/op | apply gets scheduled (unchanged by routing) |
| switches INTO epoll loop | 336,573 = 0.314/op | RX + flush |

Per-core reality (top pairs): `swapper→SharedPool` 253k, `epoll→swapper`/`swapper→epoll` ~200k each,
`SharedPool→swapper` 209k (park), `SharedPool→SharedPool` 62k, `SharedPool→epoll` 46k, `epoll→SharedPool`
37k (hop A direct), `Shard-N→swapper` ~16.6k×12. SharedPool wakes cross cores (→ shard shows as
`swapper→Shard-N`), so the coordinate pool's cost shows as **its own core scheduling** = the 43.5%.

**Verdict — MATERIAL, build justified.** The coordinate/NTR pool (`SharedPool`) is involved in **43.5%
of all context switches (0.735/op)**. Routed writes bypass it entirely — coordinate folds into the
shard's *already-scheduled* apply run (switches-into-Shard stay ~0.383/op). At a pure-write workload
nearly all SharedPool activity IS the write coordinate, so routing removes a large fraction of 0.735
cs/op. Conservative net (half nets out, half reappears as denser shard scheduling): **~0.35–0.7 cs/op
of ~1.69 → ~20–40%** — far above the ≳0.4–0.5 green-light bar. **The one bounded risk (Fable Q2,
Phase-3 gate): folding coordinate onto shards raises shard CPU (shards were part of the 74.2%); the
realized saving is capped by shard-core headroom.** Raw matrices: `results_seam/{switchmatrix4,
wakematrix_seam2}.txt` (rig) + scratchpad copies.
