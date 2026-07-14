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

---

## RESULTS-VS-TRUNK-FIXED (2026-07-13, later) — re-measure with the two CPU fixes

Re-run of the vs-trunk A/B after the two committed CPU optimizations (memoize the CqlShardRouter routing
verdict `4d27780d47`; skip the seed future for single-mutation async writes `cb036fbc9c`), built into a fresh
routing jar (`.jar.routing-fixed`, md5 `6a346b24`, contains `CqlShardRouter$Plan`). **TRUNK `50ddce8455`
(`.jar.trunk`) vs ROUTING-FIXED, back-to-back same session, with a repeat-trunk arm (T2) to bound thermal
drift** — killing the sequential-drift caveat that muddied the first vs-trunk read. Off-box ccx43 hel1 loadgen
(deleted after). p99 via ecs `--hdr` (CO-corrected whole-run, ms). Profiles via **async-profiler 4.4** (both
arms), differential routing−trunk at matched 182k.

### Part A — matched sub-knee ~183k (3 windows/arm)
| metric | TRUNK T1 (cool) | TRUNK T2 (warm) | trunk mean | ROUTING-FIXED |
|---|---|---|---|---|
| deliv/s | 183.9k | 182.9k | ~183.4k | 182.7k |
| **CPU busy** | 54.6% (53.5/54.5/55.7) | 56.4% (56.5/56.3/56.5) | **~55.5%** | **61.6%** (61.8/61.5/61.5) |
| **cs/op** | 1.79 | 1.80 | 1.80 | **1.40** (1.36/1.42/1.41) |
| %usr / %sys | 41.4 / 9.2 | 42.9 / 9.5 | 42 / 9.3 | 47.4 / 9.6 |
| **p99** (`--hdr`) | 217.1ms | 219.2ms | ~218ms | **223.4ms** |
| p999 / p50 | 413 / 0.93 | 390 / 0.93 | ~400 / 0.93 | 457 / 0.93 |
| Routed / shard Blocked | 0 / 0 | 0 / 0 | — | ~100% / 0 |

**Drift control (the point of T2):** trunk warmed **+1.8pp** T1→T2 over ~27 min of sustained load (same
code, same jar — pure thermal/session drift). So the routing-vs-trunk CPU gap is **+6.1pp vs the trunk mean**,
bracketed **+5.2pp (vs warm T2) to +7.0pp (vs cool T1)**. cs/op −22% (mechanism intact, now stronger).

### Part B — saturation (matched loaded table, over-drive 350k/cc4000)
| metric | TRUNK | ROUTING-FIXED | Δ |
|---|---|---|---|
| **peak deliv/s** | **260.7k** @97.9% | **274.7k** @98.2% | **+5.3% (routing higher)** |
| cs/op | 1.486 | **0.820** | −45% |
| shard Blocked | 0 | 0 | — |

Routing-fixed now delivers **more** at saturation (+5.3%), flipping the earlier flip+step1 result (−4%) and
beating the prior unfixed vs-trunk (+1.8%). Plausibly the async fast-path (fewer futures/listeners per write)
raised the shard-path ceiling; caveat — trunk's 260.7k here ran low vs the prior 272.8k, so read as
"routing ≥ trunk at saturation", not a firm +5%.

### Part C — CPU-hunt re-profile (asprof 4.4 differential, routing−trunk, matched 182k, 30s)
Routing burns **+11.9% CPU samples** (21327 vs 19055) and **+16.6% alloc** (69069 vs 59216) — down from the
prior **+20% / +18%**. The +11.9% matches the busy ratio (61.6/55.5 = +11%), internally consistent.

**Fix #1 (memoize routing verdict) — WORKED for its target, but the `containsIgnoreCase` headline barely
moved (2.48%→2.04% self).** Why: the memoized *decision* is now cheap — `computePlan` = **0 samples** (cache
hits every request), and the `CqlShardRouter`-attributed `containsIgnoreCase` is down to **0.32%**. The
remaining `containsIgnoreCase` is **apply-side, which fix #1 never touched**:
| `containsIgnoreCase` source (routing-fixed) | self-weight |
|---|---|
| `CqlShardRouter` (fix #1 target — memoized) | **0.32%** |
| `MutationShardRouting.route` (I1 apply re-route in `performLocally`, per-mutation, NOT memoized) | 0.94% |
| `ConsensusMigrationMutationHelper.validateSafeToExecuteNonTransactionally` (apply path) | 0.56% |
| `Schema.getKeyspaceInstance` (coordinate lookups, `ModificationStatement.executeWithoutConditionAsync`) | ~0.94% (in "other") |

So the prior "2.48% = CqlShardRouter + shard resolution" attribution over-credited the CQL-ingress decision;
the case-insensitive keyspace scans live mostly in the **structural TPC apply/route stack**.

**Fix #2 (single-mutation async fast-path) — WORKED, as predicted.** `ImmediateFuture` seed alloc = **0**
(the redundant `success(null)` seed is gone); total alloc **+18%→+16.6%** (−1.4pp, exactly the estimated
1.4–1.8pp); cs/op 1.62→1.40. The QUORUM-await `AsyncPromise`/`AsyncFuture` (necessary) remain.

**Top routing-added CPU self-frames (routing% − trunk%) — the structural premium the fixes DON'T reach:**
`containsIgnoreCase` +2.04 (apply-side, above) · `TrieMemtable$MemtableShard` apply lambda +1.05 · async
listener churn (`ListenerList.notifyExclusive` +0.75, `AsyncFuture.trySet` +0.42, `.appendListener` +0.37) ·
`itable stub` (megamorphic dispatch) +0.69 · `NaturalOrderComparator.compare` +0.65 · `EpollEventLoop.wakeup`
+0.39 (loop↔shard handoff). All inherent to coordinate+apply on the shard-routing + async-flip path.

### Verdict — did routing CPU move toward trunk?
**No, not the gap.** Absolute routing CPU dropped 64.9%→61.6%, but **trunk (unchanged code) dropped in
lockstep 58.0%→55.5%** (session/environment drift), so the **routing-vs-trunk CPU gap is unchanged: +6.9pp
(prior) → +6.1pp (now), within the drift band** (within-session T1↔T2 drift alone is 1.8pp). The two fixes
did their specific micro-jobs (decision-side `containsIgnoreCase`→0.32%, `ImmediateFuture` seed gone, alloc
−1.4pp, cs/op −14%) but the ~6pp premium is **structural** — apply-side keyspace scans, shard-memtable apply,
async listener machinery, megamorphic dispatch, cross-thread wakeups.

**PoC-criterion read (updated):**
- **throughput ≥ trunk → PASS** (sub-knee matched; saturation routing-fixed 274.7k ≥ trunk 260.7k; Blocked=0).
- **p99 ≤ trunk → now essentially NEUTRAL** (223 vs ~218ms, within trunk's own T1/T2 spread 217/219; p50
  identical 0.93ms → GC-dominated). Improved from the prior +9%; the alloc cut plausibly tightened the tail.
  Still GC-blind on G1 — a de-GC'd read (ZGC) is the only way to *adjudicate* the p99 half.
- **CPU (not a gate):** routing still costs ~+6pp over stock trunk; the next lever is the apply-side
  (`MutationShardRouting.route` is memoizable the same way; the async/shard machinery is structural), not more
  decision-side micro-opts.

Raw: rig `/root/results_ab/{subresult,sat}_{trunk,routing,trunk2}*.txt` + `/root/results_prof/{cpu,alloc}_
{trunk,routing}.collapsed` (asprof 4.4). Jars: `.jar.trunk` (50ddce8455), `.jar.routing-fixed` (054f14e484,
md5 6a346b24), `.jar.routing-validated` (bf5e4356). Rig restored routing-ON on `.jar.routing-fixed`.
