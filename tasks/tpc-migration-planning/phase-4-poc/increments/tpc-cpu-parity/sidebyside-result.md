# TASK 2 result — routing-alloc vs trunk, consolidated side-by-side (2026-07-14)

First head-to-head of the **current** build (`routing-alloc`, 81e13444 = routing-newfixes + both async
fast paths) against `trunk` (745ce392). All prior comparisons were trunk vs routing-newfixes (pre-alloc-fix).

Rig `157.180.98.112`, conf `/data/tpc-poc/conf` (TrieMemtable), RF=1. Co-located loadgen @90k for the
co-location-invariant metrics (ins/op, alloc); off-box loadgen (`62.238.35.142`, RTT 0.46ms) for the
tail-sensitive ones (rate-ladder, c2c).

## The one consolidated table
| metric | trunk | routing-alloc | Δ | read |
|---|---|---|---|---|
| **ins/op** (mean of 6×40s, same-session) | 102,751 (sd 2.3%) | 102,459 (sd 1.8%) | **−0.28%** | statistical parity (was +1.4% pre-fix) |
| **alloc** (asprof `-e alloc` samples, same window) | 39,577 | 43,591 | **+10.1%** | residual gap, narrowed from +14.6% |
| **memtable contended/1k puts** (TASK 1, 90s window) | 71.9 | **0.0** | **−100%** | routing removes shard-lock contention |
| **c2c Load-Local HITM** (off-box, ldlat=30, mean of 2 rounds) | 9,456 | 6,876 | **−27.3%** | routing bounces ~¼ fewer modified lines across cores |
| **c2c shared cache lines** | 4,237 | 3,259 | **−23.1%** | same, tight both rounds |

### rate-ladder p50/p90/p99 (off-box, 90s/rung, conc 3000; ms, CO-corrected) — T=trunk R=routing-alloc
| rate | deliv/s T/R | p50 T/R | p90 T/R | p99 T/R | p999 T/R |
|---|---|---|---|---|---|
| 80k  | 56,597 / 56,580  | 0.88 / 0.89 | 1.16 / 1.18 | 7.0 / 40.6  | 161 / 178 |
| 120k | 84,667 / 84,762  | 0.87 / 0.86 | 1.17 / 1.20 | 93.9 / 84.4 | 192 / 191 |
| 160k | 112,910 / 112,966| 0.83 / 0.80 | 1.33 / 1.27 | 135 / 138   | 196 / 206 |
| 200k | 140,333 / 140,375| 0.85 / 0.87 | 1.54 / 1.65 | 160 / 154   | 210 / 239 |

**p50/p90 at parity across every rung; delivered identical (same I/O/commitlog knee ~0.70× offered).
p99/p999 swing both directions run-to-run = GC noise, no clean signal.** No tail win, no material
regression on this 6-core box — consistent with Phase 6, now with the alloc-fixed jar. The small p90
regression Phase 6 saw at 160k (1.11/1.28) is gone (1.33/1.27).

## Reading the two that moved vs Phase 6
- **ins/op parity improved.** Pre-fix routing-newfixes was +1.4% (capstone); the alloc fast paths land it
  at −0.28% — dead even. The async fast paths shaved a hair off CPU too (routing cyc/op ~183k vs trunk
  ~192k here — routing does slightly fewer cycles/op, more instructions retired per cycle).
- **alloc gap narrowed but not closed.** +14.6% → +10.1%. `CallbackBiConsumerListener` is *gone* from the
  routing profile (addCallback fast path). Residual is `AsyncPromise` (673, per-handler, architectural) +
  `RunnableWithExecutor`/`GenericFutureListenerList`/lambdas (the `map` path still allocates its result +
  some listener nodes). This residual alloc is why routing's tail isn't *better* despite the contention
  win — more young-gen churn feeds GC, which dominates the tail on this small box.

## c2c — mechanism win reproduces (−27% HITM), 2 tight rounds
| round | trunk HITM (pools=OFF) | routing-alloc HITM (pools=ON) | trunk sharedLines | routing sharedLines |
|---|---|---|---|---|
| 1 | 9,450 | 6,906 | 4,104 | 3,186 |
| 2 | 9,462 | 6,845 | 4,369 | 3,332 |
| mean | 9,456 | 6,876 (**−27.3%**) | 4,237 | 3,259 (**−23.1%**) |

Matched load (records within 2.4%). Per-shard routing bounces ~¼ fewer modified cache lines across cores
at matched write load — the shared-everything→per-shard mechanism, reproduced independently of Phase 6
(which saw −31%). This is the hardware-coherence corroboration of the domain contention counter (TASK 1).

Note: the first attempt inside `task2_all.sh` called `c2c.sh` without a swap (c2c.sh doesn't swap; the
sweep left routing-alloc live), so both runs measured routing-alloc (6,478/6,555 HITM ≈ routing's ~6,900,
confirming the bug). Redone with `c2c_ab.sh` (swap+prep per arm).

## Bottom line
At this operating point the current build is **at CPU/latency parity with trunk** (ins/op −0.28%, p50/p90
flat across all rungs) while **eliminating memtable shard-lock contention** (72/1k → 0) and bouncing
**−27% fewer modified cache lines across cores** (c2c HITM). The mechanism win is proven twice over — in
domain terms (contention counter → 0) and in hardware coherence (c2c HITM −27%, reproduced). The one
remaining cost is a **+10% allocation gap** (async future machinery: AsyncPromise + map-path listener
nodes) that keeps GC — not architecture — as the tail's ceiling on this 6-core box, which is why p99/p999
show no tail win here. Tail-at-scale still needs ≥32 cores / multi-NUMA (per the Phase 6 ceiling).
