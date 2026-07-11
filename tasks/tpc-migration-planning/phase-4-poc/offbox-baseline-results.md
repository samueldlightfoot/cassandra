# Phase 4.1 — off-box baseline results (trunk-config, first pass)

**Date:** 2026-07-10. **Rig:** Cassandra on `157.180.98.112` (Xeon E-2276G, **6 physical
cores 0-11**, Trie memtable, RF=1, periodic commitlog, 16 G G1), fresh 2M-partition / 459 MB
dataset. Load generated **off-box** from the CCX43 (`62.238.35.142`, 16 vCPU EPYC-Milan / 8
physical cores) over Hetzner's internal net, N parallel `cassandra-easy-stress` processes
(`--threads 16` each, `@100k` offered/proc). Measured **server-side** (`nodetool tablestats`
count deltas + `proxyhistograms` + `mpstat` on both fences). Raw: `results/offbox/`.

> **⚠️ METHOD UPDATE (2026-07-10, later):** the N-process ladders below were a workaround for a
> non-existent limit. The single-process ceiling was the tool's **`--concurrency` default (100)**,
> not tool serialization. **One process with `--concurrency 3000` delivers 253k @ Cassandra 97%
> (load box 41%)** — cleaner and simpler than multi-process. Re-run the curve single-process
> (`--rate <pt> --concurrency 3000 --threads 32`, `--rate` ≤ knee for 0-drop points). The CPU-vs-
> throughput saturation shape below still holds; treat the throughput knees as ~correct (single-
> proc write knee ~253k @ 97%). See `STRESS-RUNBOOK.md`.

## Headline: the off-box setup does what co-location could not
At Cassandra's saturation knee the **load box still has CPU headroom** (≤68%), so the knee is
unambiguously Cassandra's, not the generator's. This was impossible co-located (there, client
and Cassandra saturated together). Decision (off-box) validated empirically.

## Saturation curve per mix (throughput + CPU are exact per-window; latency = see below)

**Write-only (`readrate 0.0`)** — N procs @100k offered each:
| N | delivered w/s | Cassandra CPU | load-box CPU | errors |
|--:|-----:|---:|---:|--:|
| 1 |  70.8k | 26% | 28% | 0 |
| 2 | 139.1k | 45% | 38% | 0 |
| 3 | 197.2k | 74% | 50% | 0 |
| 4 | 220.8k | **92%** | 64% | 13 |
| 5 | 229.0k (drops) | 96% | 79% | 40 |
→ **clean write knee ≈ 197k @ 74%** (0 err); **saturation ≈ 221k @ 92%** (errors begin);
overload past N=4 (delivered plateaus/drops, timeouts climb).

**Read-heavy (`readrate 0.9`)**:
| N | tot ops/s | Cassandra CPU | errors |
|--:|-----:|---:|--:|
| 1 |  69.4k | 57% | 0 |
| 2 | 115.2k | **92%** | 8 |
| 3 | 123.2k | 100% | 29 |
| 4 | 122.1k | 100% | 67 |
→ **clean read point ≈ 69k @ 57%** (0 err); **read saturation ≈ 115k @ 92%**. Reads are
~2× CPU-heavier per op than writes (path is CPU-bound on merge/deserialize; 459 MB dataset
fits in page cache so this is not disk).

**Balanced (`readrate 0.5`)**:
| N | tot ops/s | Cassandra CPU | errors |
|--:|-----:|---:|--:|
| 1 | (transient control-conn timeout — Cassandra still recovering from prior read overload; rerun) |
| 2 | 143.8k | 76% | 0 |
| 3 | 179.5k | **98%** | 17 |
→ **clean ≈ 144k @ 76%**; **saturation ≈ 180k @ 98%**.

**Saturation ranking (as expected):** read-heavy 115k < balanced 180k < write-only 221k.

## Latencies — clean pass
First-pass proxyhistogram latencies from the ladder were **decay-polluted** (Cassandra's
latency metric is a decaying reservoir; back-to-back rungs bleed into each other). A clean
pass (40 s idle-settle + 110 s steady, read at steady state) is in
`results/offbox/clean_latency.txt`:

| point | throughput | Cassandra CPU | srv **write** p50/95/99 (µs) | srv **read** p50/95/99 (µs) |
|---|---:|---:|---|---|
| write (2 proc) | 201k w | 74% | 17 / 770 / **3973** | — |
| write knee (3 proc) | 225k w | 99% | 310 / 1109 / **3311** | — |
| read-heavy (1 proc) | 100k (90k r) | 72% | 310 / 924 / 3311 | 60 / 642 / **1597** |
| balanced (2 proc) | 202k (100k r) | 100% | 20 / 3311 / **11864** | 60 / 1331 / **3311** |

Reads: clean p99 **~1.6 ms** at 100k/72% (vs decay-polluted 3.9 ms — idle-settle worked).
Writes: p99 **~3–4 ms** near knee. Balanced at saturation (100%): write tail blows to **~12 ms**.

**Measurement notes (important):**
1. **proxyhistogram p99 is bucketed** (EstimatedHistogram: …1109/1331/1597/1916/2299/2759/3311/3973/4768… µs) — read p99 as a band, not a point.
2. **Throughput is method-dependent, CPU% is not.** Idle-settled clat shows 2 procs deliver ~200k clean; the back-to-back ladder showed ~139k at the *same 74% CPU* (early-window + inter-rung contention under-counted). **Trust Cassandra CPU% as the saturation signal**: ~74% ≈ 200k, ~92–99% ≈ 220–225k = knee.
3. **Gap:** since 2 procs already deliver ~200k, the clean-latency points landed *near* the knee; low sub-knee clean points (~100k write) still need capturing (1 proc at reduced `--rate`).

## Caveats / next
- **Mixed N=1 + all overload rungs**: transient control-conn timeouts appear when a rung
  starts while Cassandra is still at ~100% from the previous overload rung. Inter-rung gap
  (6 s) is too short after overload — for clean reruns use ≥30 s recovery, and avoid the
  overload rungs entirely for gate points.
- **Cassandra uses the WHOLE box** — pinned `0-11` = all 12 threads = all **6 physical cores**
  (the E-2276G's total; nothing held back). "6 cores" is the machine's full size, not a subset.
  These are therefore conservative (small-box) numbers: TPC benefit is load-gated and present
  here, but magnitude understates many-core. A bigger *Cassandra* box (more real cores / NUMA)
  is the "stronger, not required" upgrade — see LOADGEN-DECISION-ANALYSIS.md §4.
- **Gate operating points** still to be reconciled (poc-criteria §5 sub-knee vs increments.md
  loaded-tail). The curve above supports either choice.
- **COST:** the CCX43 bills hourly — destroy it once trunk+increment baselines are captured.
