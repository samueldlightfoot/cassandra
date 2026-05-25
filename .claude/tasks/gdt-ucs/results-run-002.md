# Run #002 — Reproducibility Check (BasicTimeSeries, same config as Run #001)

**Run UUID:** `fe20164a2ec04ebdb8a0a91c593db755`
**Date:** 2026-05-25 09:33–10:21 UTC
**Purpose:** Verify Run #001's 9.73% p99 reduction is reproducible. Confirm DB
WAF numbers with the fixed `bytes_compacted` parser (Cassandra 6.0-alpha2
exposes the field in `nodetool compactionstats`, not `nodetool info`).

## TL;DR — Run #001's p99 finding was within run-to-run noise

| metric | Run #001 | Run #002 | reproducible? |
|---|---:|---:|---|
| **gdt vs baseline p99** | **−9.73%** | **−2.43%** | **NO** |
| gdt vs baseline bytes_compacted | (parser bug — read 0) | −0.04% | now real, ≈ zero |
| gdt vs baseline DB WAF (Cassandra) | 0.03% | 0.03% | reproducibly zero |
| gdt vs baseline DB WAF (NVMe) | −0.34% | +0.04% | within noise |
| baseline p99 | 319.52 ms | 330.01 ms | ~3% drift — normal variance |

**The reproducibility check did exactly what it should:** caught a false
positive before we shipped it. The 9.73% p99 in Run #001 was almost certainly
random variation, not a real GDT effect.

## Full headline table (Run #002 — corrected parser)

| condition | ops/sec | p99 read ms | DB WAF Cass | DB WAF NVMe | bytes_compacted |
|---|---:|---:|---:|---:|---:|
| baseline | 50,000.05 | 330.01 | 1.582 | 1.499 | 40.39 GB |
| gdt | 50,000.72 | 321.99 | 1.582 | 1.500 | 40.37 GB |
| twcs | 49,999.75 | 404.91 | **1.418** | **1.481** | **34.47 GB** |

| comparison | gdt vs baseline | twcs vs baseline |
|---|---:|---:|
| ops/sec | +0.00% | −0.00% |
| p99 read | **−2.43%** | +22.7% (worse) |
| bytes_compacted | **−0.04%** | **−14.7%** |
| DB WAF Cassandra | +0.03% | −10.4% |
| DB WAF NVMe | +0.04% | −1.23% |

## Interpretation

### 1. GDT does essentially nothing on BasicTimeSeries

`bytes_compacted` delta of 0.04% is indistinguishable from zero. DB WAF
Cassandra delta of 0.03% is indistinguishable from zero. p99 delta of 2.43%
is within typical inter-run variance (baseline alone drifted ~3% between
Run #001 and Run #002 with no config change).

This **confirms** the mechanism doc's prediction: append-only time-series
is the weakest GDT case because there's no overwrite churn for the
deathtime-grouping mechanism to exploit. Every row has the same expected
lifetime; GDT bucketing doesn't differentiate anything useful.

### 2. The 9.73% p99 in Run #001 was noise

p99 latency at saturation is highly sensitive to scheduling and JVM GC
timing. A single 15-min run can land anywhere in a ±15% band around the
"true" mean. Without replicates we mistook variance for signal.

This is why the reproducibility check matters and why we now have a
**rule** (see `operational-lessons.md` §10): no shippable finding from
a single run.

### 3. TWCS DOES reduce compaction work — but it's not GDT

| condition | bytes_compacted | DB WAF |
|---|---:|---:|
| baseline | 40.39 GB | 1.582 |
| **twcs** | **34.47 GB (−14.7%)** | **1.418 (−10.4%)** |

So time-coherent compaction *does* help on this workload — TWCS achieves
a real ~15% compaction reduction. But:
- TWCS still had the WORST p99 (404.91 ms) due to the 1-hour window mis-config
- This is a finding about TWCS, not GDT
- It validates that time-coherent compaction can save work; just GDT's
  implementation in our config doesn't capture it

### 4. The TWCS p99 paradox

TWCS compacted 14.7% less than baseline but had 22.7% WORSE p99. Why?

Likely cause: with 1-hour window on 15-min data, TWCS falls back to
STCS-within-window. STCS picks bigger compactions less often. So:
- Fewer-but-bigger compactions = bigger pause per compaction event = bigger p99 spikes
- vs UCS T4: smaller-more-frequent compactions = smoother latency profile

This is interesting but a separate finding. For our purposes: **TWCS as
configured in this run is not a valid "ceiling" for the GDT comparison.**

### 5. DB WAF formula sanity check

Run #002 baseline:
- bytes_compacted_delta = 40.39 GB
- user_bytes_estimated = 45M ops × 2KB = 92.1 GB
- DB WAF Cassandra = (40.39 + disk_used_delta) / 92.1 = 1.582
- → disk_used_delta = 105.34 GB

That's 13 GB MORE than user bytes. Probable explanation: system + system_schema
tables, commitlog index updates, hints, plus snapshot artefacts grew during
the run. Or the formula is double-counting (compaction outputs appear in both
`bytes_compacted` and `disk_used_delta`). Worth investigating for v3 of the
summary computation — see open question below.

## Open questions

| question | impact | when to investigate |
|---|---|---|
| Does our DB WAF formula double-count compaction output? | If yes, real WAF is lower; conclusions still hold (GDT delta still ≈ 0) | Before Run #004 |
| Why is TWCS p99 worse despite less compaction work? | Affects how we frame TWCS in any write-up | After we have a working GDT signal |
| Run-to-run variance band — is ±15% typical? | Sets threshold for what counts as "real signal" in future runs | Inherent — accept as noise floor for single 15m runs |
| TWCS with right window (5-min on 15m run = 3 windows) — what's its p99 then? | Fair "ceiling" reading | Phase 2 if we want a clean ceiling |

## Decision and next step

**Pivot to KeyValue workload for Run #003.** GDT's mechanism requires
overwrite-driven deathtime variation. BasicTimeSeries doesn't have that.
KeyValue (INSERT-as-UPSERT on a small key space) does — every hot key gets
many overwrites, creating real deathtime differentiation that GDT can act on.

Run #003 config:
- `--workload KeyValue`
- `--partitions 100000` (concentrate updates on a smaller key space)
- everything else identical: 15m × 3, 50K rate, 2KB rows, 300s GDT bucket

If Run #003 shows GDT differentiation → real shippable finding.
If Run #003 also shows ~zero GDT effect → real finding ("GDT mechanism
doesn't measurably help Cassandra UCS on either workload"). Still
publishable as a negative result.

## Artifacts (on rig)

```
/root/repos/gdt-poc-harness/results/fe20164a2ec04ebdb8a0a91c593db755/
├── baseline/{condition.json,.complete,snapshots/,workload/}
├── gdt/
├── twcs/
├── run.json
└── summary.json
```

## Updates to operational-lessons.md from this run

- §10 (Reproducibility) — *vindicated*. The 9.73% p99 we thought was a real
  GDT effect turned out to be noise. Without the re-run, we would have
  shipped a false claim.
- §11 (Cleanup) — followed verbatim, worked correctly.
- §12 (Pre-launch checklist) — the parser-version check (#2 in the
  checklist) is what caught the `nodetool info` issue in Run #001's
  post-mortem. Add to checklist permanently.
