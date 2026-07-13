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

---

## RESULTS-VS-TRUNK (2026-07-13) — the PoC-criterion read

The A/B above is vs flip+step1. This one answers `poc-criteria.md §1` directly: **routing-ON vs stock
trunk**. Baseline = the exact pre-TPC fork point `50ddce8455` (= local `trunk` tip; the whole TPC stack —
I1 shard executors, I5 inbound dispatch, step1, the flip — forks from it; the handoff's "parent-of-flip
`55a1a71f0a`" was wrong, it still carries I1+I5+step1). Trunk jar built on the rig with the same JDK/ant as
the routing jar (no toolchain confound); verified no TPC/io-uring classes. Both arms measured **same
session, back-to-back** (fresh `prep_flip` each), off-box ccx43 loadgen (busy ~38% = headroom). p99 via ecs
`--hdr` (CO-corrected whole-run, ms).

| metric | TRUNK `50ddce8455` | ROUTING `bf5e4356` | Δ |
|---|---|---|---|
| sub-knee deliv/s (3-win mean) | ~184.2k | ~181.9k | matched |
| **sub-knee CPU** | **58.0%** (57.6/58.8/57.6) | **64.9%** (65.6/64.8/64.3) | **+6.9pp**¹ |
| cs/op | 1.94 | 1.62 | −16% (mechanism: fewer hops) |
| **p99** (`--hdr` mutations) | **200.3ms** | **218.1ms** | **+9%**² |
| p999 | 325ms | 419ms | +29%² |
| mean service time | 11.49ms | 11.75ms | ~equal |
| **saturation peak/s** | **272.8k** @97.7% | **277.7k** @98.0% | **+1.8%** |

¹ **Drift caveat:** arms run **sequentially, not interleaved** — trunk (cool, first) vs routing (~1h into
sustained load). So +6.9pp is partly a thermal/ordering confound; the earlier *interleaved* session had
routing at 60.1% (≈+2pp over a trunk-equivalent). True delta is between. An interleaved A/B/B/A would settle it.
² **GC-dominated → gate BLIND.** Mean service time is identical (11.5≈11.75ms); the whole p99/p999 gap sits
in the G1-pause region (p99 ≈ 18× mean under `MaxGCPauseMillis=300`, 16G heap). Per `poc-criteria §1` a tail
delta that lives only in GC-adjacent windows is not a verdict.

### PoC-criterion verdict
- **throughput ≥ trunk → PASS** (sub-knee matched; sat peak 277.7k ≥ 272.8k, shard Blocked=0 both).
- **p99 ≤ trunk → UNANSWERABLE on G1**, and on raw numbers routing is +9% (worse). The tail is GC-noise;
  to adjudicate the p99 half honestly the tail must be de-GC'd (ZGC — generational needs JDK 21+; rig is 17).
- **CPU (not a gate):** routing costs vs stock trunk — the TPC stack (async flip + shard dispatch + route/
  lookup) adds per-op compute even though it cuts context switches. Attributed below.

### CPU-hunt (async-profiler 3.0 differential, routing−trunk, matched 182k)
Routing burns **+20% CPU samples** and **+18% allocation** at matched throughput. Top routing-added
self-time frames (trunk 0% → routing X%) = enhancement targets:
- **`SchemaConstants.containsIgnoreCase` 2.48%** (trunk 0). Per-request case-insensitive keyspace scans from
  TWO routing-only callers: `CqlShardRouter:143` `isLocalSystemKeyspace` hazard check **and** extra
  `Schema.getKeyspaceInstance` calls in shard resolution. Both **invariant per `TableMetadata`** → memoize
  the routability/shard decision once per prepared statement. Highest-value, low-risk (~2.5pp).
- **Async-future allocation ~7pp** — the flip's per-write `AsyncPromise`/`AsyncFuture`/`ListenerList`
  callback churn (top alloc frames) drives the +18% alloc → extra G1 work (`G1ParScanThreadState` +0.68pp).
  Also boxes `OptionalInt` per `routeShard`/`shardForKey` return (0.68pp alloc) — return an int sentinel.
- **`DecayingEstimatedHistogramReservoir.findIndex` 1.82%** — metrics histogram on the hot path; investigate
  which histogram (shard-executor pool metrics?) and whether it can be cheaper/off.
- Minor: `itable stub` (megamorphic dispatch), thread-local churn, `ShardBoundaries.getShardForToken`.

Raw: rig `/root/results_ab/` (A/B) + `/root/results_prof/{cpu,alloc}_{trunk,routing}.collapsed`. Jars
preserved: `…jar.trunk` (50ddce8455), `…jar.routing-validated` (bf5e4356).
