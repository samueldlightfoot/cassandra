# Clean CPU/p99/throughput A/B — methodology (routing OFF vs ON)

Phase-3 proved the *mechanism* (hop deleted: SharedPool 43.5%→0%, Routed 100%). This measures the
*performance* cleanly, because the first pass used hand-aligned unequal windows + an over-driven regime
(console p99 191/234 were CO artifacts, not service time). Same jar both arms (`bf5e4356`); the only
difference is `cassandra.tpc.cql_ingress_routing` (read once at startup → arm switch needs a restart).

## Baselines to compare against
- CPU (matched ~180k): **flip+step1 ≈ 67.4%**, routing-off (no shard pools) **58.5%**, cs/op ~2.1.
  Flag-OFF on the current jar IS flip+step1 → should reproduce ~67.4%; flag-ON is the test.
- Peak write-only knee (old loadbox): ~253k @ ~97% CPU.
- PoC criterion: **p99 ≤ trunk at throughput ≥ trunk**.

## Part A — matched sub-knee (fixed load → compare CPU & tail)
1. **Calibrate** one clean rate: delivered ≈ offered, ~0 CO errors, both arms. Start `--rate 180000`;
   step down if CO drops appear.
2. Per arm (OFF, then ON): `prep_flip` fresh JVM + truncate + autocompaction off. Then **3 independent
   loadgen runs** (variance on every metric, incl. p99): `--rate <clean> --concurrency 3000 --threads 32
   --readrate 0.0 --hdr rep<N> --duration ~110s`, off-box, CPU-fenced.
3. Each run → rig `abwin.sh` captures a **60s steady window** (after 40s warmup): tablestats delta
   (throughput), mpstat mean %busy over the same 60s (CPU headline), `sched:sched_switch` matrix
   (cs/s, SharedPool%, cs/op), asprof cpu (frame-level fold), tpstats (Shard Blocked/Pending), Routed
   delta. Client `--hdr` p99 pulled post-run (full-res, CO-corrected — the tail *authority*, not console).
4. Report **mean ± range** over the 3 runs per arm. Compare OFF vs ON at matched delivered.

## Part B — saturation sweep (peak deliverable → the "throughput ≥ trunk" half)
Per arm: over-drive `--rate 350000 --concurrency 4000` ~90s; read **server-side** delivered (tablestats
delta) + mpstat CPU at the knee (ignore client offered/CO). Peak deliverable at ~full CPU = the arm's
ceiling. Optionally a 2–3 rung ladder (250k/300k/350k) for the curve.

## Hygiene (pinned memories)
Off-box ccx43 hel1 loadgen, CPU-fenced, prove headroom w/ mpstat (`cpu_fence_colocated_loadgen`).
Never `--rate` with `--maxwlat` (`easy_cass_stress_scripted_run_gotchas`). `--prometheusport 0`.
Grep loadgen for startup failures (`monitor_silence_is_not_success`). Measure at matched ops/s so GC
tail folds out (`gc_dominates_cassandra_tail`). Jar already deployed+verified. Delete loadgen when done.

## Pass read
At matched ~180k: does routing-ON CPU move below flag-OFF (~67.4%) toward the 58.5% floor, with p99
≤ off? At saturation: is ON's peak deliverable ≥ OFF's? Report all with variance; no single-point claims.

---

## RESULTS (2026-07-13) — clean A/B, a REGIME CROSSOVER

Same jar (`bf5e4356`); only `cassandra.tpc.cql_ingress_routing` differs. Both arms: fresh `prep_flip`
(clean JVM + truncate + autocompaction off) → 3× 60s sub-knee windows → 1 saturation window on the
now-loaded table. Off-box ccx43 loadgen (deleted after). **Two harness bugs fixed first:** (1) running
`asprof`+`perf sched_switch` *inside* the CPU window inflated busy% by ~14pp — the clean window uses only
mpstat/vmstat/tablestats (cs/op from vmstat, the SharedPool attribution is already proven in Phase 3);
(2) the async flip does not record `ClientRequest.Write.Latency` (proxyhistograms + JMX both read 0) — a
real observability gap, present in *both* arms so unbiased, but it removes the server-side p99 source.

### Part A — matched sub-knee ~182k (3 windows/arm, fresh JVM)
| metric | flag-OFF (flip+step1) | flag-ON (routed) | Δ |
|---|---|---|---|
| delivered/s | 181.0 / 182.2 / 181.7k | 181.3 / 185.2 / 182.1k | matched |
| **CPU busy** | 64.9 / 65.6 / 65.9 → **65.5%** | 59.6 / 59.6 / 61.0 → **60.1%** | **−5.4pp** |
| **cs/op** | 2.12 / 2.10 / 2.10 → **2.10** | 1.56 / 1.51 / 1.56 → **1.54** | **−27%** |
| %usr / %sys | 48.0 / 12.8 | 45.2 / 10.0 | −2.8 / −2.8pp |
| Routed/window | 0 | ~14.2M (≈100% of writes) | — |
| shard Blocked | 0 | 0 | — |

Flag-OFF reproduces the documented flip+step1 baseline (67.4%@180k / cs/op~2.1; measured 65.5% / 2.10),
validating the harness. **Routing cuts CPU ~5.4pp at matched load** — matching the context-switch
arithmetic (deleting ~100k switches/s ≈ 2–6% of the box). Both %usr and %sys drop.

### Part B — saturation, matched (both on a loaded table, over-driven --rate 350k --concurrency 4000)
| metric | flag-OFF | flag-ON | Δ |
|---|---|---|---|
| **peak deliverable/s** | **310k** | **298k** | **−4% (routing lower)** |
| CPU busy | 98.3% | 98.0% | ~equal |
| cs/op | 0.446 | **0.716** | +60% (routing thrashes more) |
| shard Blocked | 0 | 0 | — |

Table-state matters at saturation (fresh-table flag-off hit 325k; loaded-table 310k) — matched-table is
the fair read. **At peak, routing delivers ~4% LESS** and spends ~60% more context switches per op: the
per-request loop↔shard handoff + coordinate serialized on 12 shard threads can't batch like the wide NTR
pool does, so the 12-thread path saturates a little lower. This is the design's bounded risk (Fable Q2,
shard serialization under load) — real but modest, and shards never block.

### Part C — tail
Client CO-corrected steady p99 ~**193ms (off) vs ~194ms (on) — neutral**. Large + equal across arms =
GC/flush-dominated, not service time (`gc_dominates_cassandra_tail`); routing does not move it. Server-side
p99 unavailable (flip metric gap).

### Verdict
**Regime crossover.** CQL ingress routing is a **moderate-load CPU win** (−5.4pp at 182k, cs/op −27%,
tail-neutral) and a **small saturation-throughput cost** (−4% peak, 12-shard-thread serialization). Note
the A/B is vs **flip+step1**, not trunk — it isolates the routing increment; a vs-trunk PoC-criterion read
(p99 ≤ trunk at throughput ≥ trunk) needs a separate trunk build. Numbers are 3-window means (sub-knee) /
single window (saturation); raw in `/root/results_ab/` on the rig.
