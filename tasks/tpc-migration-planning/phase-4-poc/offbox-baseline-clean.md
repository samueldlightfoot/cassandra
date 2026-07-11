# Phase 4.1 — CLEAN trunk baseline (single-process method) — AUTHORITATIVE

**Date:** 2026-07-10. Supersedes the multi-process first pass in `offbox-baseline-results.md`.
**Method:** ONE `cassandra-easy-stress` process, `--concurrency 3000 --threads 32`, `--rate`
swept per rung, off-box from the CCX43 (16 vCPU EPYC) → Cassandra `157.180.98.112` (all 6
physical cores), fresh 2M/459 MB dataset. Each rung: 30 s idle-settle → 100 s run → server-side
measured over a 25 s steady window. Throughput = `nodetool` count delta; latency = `nodetool
proxyhistograms` (µs, coordinator service time). Raw: `results-offbox/clean_{write,mixed,read}.txt`.

**Latency authority = server-side proxyhistograms.** The client-side p99 column (`cliP99`) is
CO-corrected + startup-polluted → unreliable for absolute latency; ignore it. proxyhistogram
p99 is bucketed (…535/770/924/1109/1331/1597/1916/2299/2759/3311/3973/4768… µs) — read as a band.

## Write-only (`readrate 0.0`)
| offered | delivered | ratio | Cassandra CPU | srv write p50/95/**p99** (µs) | errors |
|--:|--:|--:|--:|--|--:|
| 120k | 119k | 0.99 | 37% | 14 / 179 / **535** | 0 |
| 180k | 181k | 1.01 | 57% | 14 / 372 / **924** | 0 |
| 240k | **246k** | 1.02 | **88%** | 24 / 770 / **1916** | 0 |
| 300k | 241k | — | 98% | 310 / 924 / 2759 | 1.27M (over-driven) |
→ **clean knee ≈ 246k @ 88%, write p99 ~1.9 ms, 0 err.** Past it (rate 300k) delivery drops + CO storm.

## Balanced (`readrate 0.5`)
| offered | delivered (w+r) | Cassandra CPU | srv **write** p99 | srv **read** p99 | errors |
|--:|--:|--:|--:|--:|--:|
| 80k | 80k | 44% | (2759, boundary-polluted) | 124 µs | 0 |
| 130k | 133k | 69% | **1916 µs** | 215 µs | 0 |
| 180k | 180k | 96% | **8239 µs** | 1916 µs | 0 |
| 230k | 209k | 100% | 11864 | 2299 | 926k (over-driven) |
| 280k | 209k | 100% | 14237 | 2759 | 1.67M |
→ **clean ≈ 133k @ 69% (wr 1.9 ms / rd 0.2 ms); knee ≈ 180k @ 96%** (tails blow up); saturation ~209k.

## Read-heavy (`readrate 0.9`)
| offered | delivered (r) | Cassandra CPU | srv **read** p50/95/**p99** (µs) | errors |
|--:|--:|--:|--|--:|
| 50k | 46k | 37% | 50 / 103 / (1597, boundary-polluted) | 0 |
| 90k | 84k | 59% | 50 / 86 / **1109** | 0 |
| 120k | 106k | 80% | 50 / 1331 / **3311** | 0 |
| 150k | 122k | 100% | 642 / 1916 / 5722 | 688k (over-driven) |
→ **clean ≈ 90k @ 59%, read p99 ~1.1 ms; knee ≈ 120k @ 80%** (rd p99 3.3 ms); saturation ~136k @ 100%.
Reads are bimodal: p95 stays ~90 µs while p99 jumps to ms — occasional GC/compaction/cache blips.

## Saturation summary (Cassandra, 6 physical cores)
| mix | clean operating point | knee | saturation |
|---|---|---|---|
| write-only | 119k @ 37% (0.5 ms) · 181k @ 57% (0.9 ms) | **246k @ 88%** (1.9 ms) | ~250k @ 98% |
| balanced 0.5 | 133k @ 69% (wr 1.9 / rd 0.2 ms) | **180k @ 96%** | ~209k @ 100% |
| read-heavy 0.9 | 90k @ 59% (1.1 ms) | **120k @ 80%** (3.3 ms) | ~136k @ 100% |

Reads ~2× CPU-heavier than writes. Delivered ≈ offered (ratio ~1.0) with 0 errors below each
knee → clean closed-loop regime, single process suffices.

## Validity / caveats
- **Good enough to anchor the trunk side of the gate** for the sub-knee + knee points: clean
  server-side p99, delivered≈offered, 0 errors, monotonic curves.
- **Boundary pollution:** the FIRST rung of the mixed and read ladders carries a decayed tail
  from the previous ladder's saturation (30 s idle < enough after 100% CPU). Affected cells are
  marked "boundary-polluted" — discard those specific p99s (the within-ladder rungs are clean).
- **Not yet done:** (1) 3-iter noise bands at the chosen gate operating points; (2) gate-point
  definition reconcile (`poc-criteria §5` sub-knee vs `increments.md` loaded-tail); (3) the
  increment build + identical curve for the A/B. For final gate numbers, rerun the 2–3 chosen
  operating points per mix in isolation (≥60 s settle) ×3 for a noise band.
- **Small-box (6 physical core) conservative baseline** — see `LOADGEN-DECISION-ANALYSIS.md`.
