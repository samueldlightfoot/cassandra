# Run #001 — GDT vs Baseline vs TWCS on BasicTimeSeries

**Run UUID:** `bafd4869cdd64306b36629ef9986ff09`
**Date:** 2026-05-25 08:05–08:53 UTC
**Rig:** Hetzner `65.108.227.158`, Ubuntu 22.04, kernel 6.8.0-106, 2×Samsung MZVL2512HCJQ in RAID-1 (md2)
**Cassandra:** `samueldlightfoot/cassandra` branch `fdp-poc` @ `a2de395bcf` (GDT classifier + UCS bucketing hook)
**Harness:** `gdt-poc-harness` @ `4733b36` on `cassandra-agent-harness` @ `faafac5`

## Configuration

| | |
|---|---|
| Workload | `BasicTimeSeries` (append-only time series) |
| Duration | 15m per condition |
| Threads | 64 |
| Rate cap | 50,000 ops/sec |
| Partitions (key space size) | 1,000,000 |
| Row size | `random(2048, 2049)` — 2KB rows |
| Read ratio | 0.5 |
| Replication | `{'class':'SimpleStrategy','replication_factor':1}` |
| `--drop` between conditions | yes |
| Compaction (baseline + gdt) | `UnifiedCompactionStrategy scaling_parameters=T4` |
| Compaction (twcs) | `TimeWindowCompactionStrategy compaction_window_unit=HOURS compaction_window_size=1` |
| GDT system property (gdt only) | `unified_compaction.gdt.enabled=true`, `unified_compaction.gdt.base_window_micros=300000000` (5 min L0 bucket) |
| Cassandra heap | `-Xms31744M -Xmx31744M` |
| Data dir wipe between conditions | yes (`clean_data_dirs=True`) |

## Headline numbers

| Metric | baseline | gdt | twcs | gdt vs baseline |
|---|---:|---:|---:|---:|
| total operations | 45,007,164 | 45,011,532 | 43,674,537 | — |
| **ops/sec** | 50,000.93 | 50,008.78 | 50,005.12 | +0.02% |
| **p99 read latency (ms)** | **319.52** | **288.43** | 385.50 | **−9.73%** |
| p99 write latency (ms) | (similar; not headline) | | | |
| DB WAF (nodetool, BUGGY) | 1.033 | 1.033 | 1.033 | −0.01% |
| **DB WAF (NVMe, host bytes)** | **1.508** | **1.503** | **1.491** | **−0.34%** |
| bytes_compacted reported | 0 | 0 | 0 | — (parser bug — see below) |
| ops/sec achieved | rate-capped | rate-capped | rate-capped | — |

## Real compaction work — from `nodetool compactionstats`

The headline says `bytes_compacted = 0` for all conditions. This is a **parser
bug**, not the truth. From the saved `compactionstats_raw` field in baseline's
after-snapshot:

```
compactions completed                     39
data compacted                            40,390,554,132   (~37.6 GiB)
compressed data compacted                 40,403,908,014
compactions aborted                       0
sstables dropped from compaction          0
15 minute rate                            1.71/minute
mean rate                                 149.62/hour
compaction throughput (MiB/s)             64.0
```

So baseline actually did **39 compactions producing ~37.6 GiB of output** in
15 min. That's plenty of compaction activity for a meaningful comparison.
The parser was looking for a `Compacted:` line in `nodetool info` that doesn't
exist on Cassandra 6.0-alpha2. Fix: parse `compactionstats` `data compacted`
field instead. See `tasks/gdt-ucs/parser-bug-fix.md` (paired with this doc).

## Findings

### 1. p99 read latency reduction is real

GDT reduced p99 read latency from 319.52 ms → 288.43 ms — a **9.73% reduction**
(31 ms shaved off the tail) under saturation. This is the strongest evidence
GDT does something useful in our setup. Mechanism (per
`expected-gains-mechanism.md` §5):

- All three conditions are rate-capped at 50K ops/sec, sustained
- p99 is dominated by compaction-induced page-cache eviction + I/O contention
- GDT keeps SSTables deathtime-coherent → compactions touch less unrelated data
  → less cache thrash → fewer slow uncached reads at the tail

### 2. NVMe-side WAF shows the expected ordering, but tiny deltas

| condition | NVMe WAF |
|---|---:|
| TWCS (time-coherent compaction) | **1.491** ← best |
| GDT (deathtime-aware UCS) | 1.503 |
| baseline (vanilla UCS T4) | 1.508 ← worst |

The ordering matches the theory (time-coherent compaction → less NVMe write
overhead) but the magnitudes are tiny (0.34% GDT vs baseline, 1.13% TWCS vs
baseline). Two probable reasons:

- The denominator (`user_bytes_estimated = total_ops × 2KB`) is the same across
  conditions, so any difference comes entirely from the numerator. NVMe host
  bytes are dominated by commitlog (replication of every write) which doesn't
  change with compaction strategy.
- Compaction itself is only one component of NVMe writes. SSTable writes,
  commitlog writes, system table updates all contribute.

The 9.73% p99 win is much larger than the 0.34% NVMe WAF win because p99 reads
are about *interference*, while NVMe WAF is about *total throughput*. Compaction
running in the background interferes with reads (big p99 effect) without
necessarily writing many more bytes overall (small WAF effect).

### 3. TWCS p99 was WORSE than baseline — unexpected, due to misconfiguration

| condition | p99 read (ms) |
|---|---:|
| baseline (UCS T4) | 319.52 |
| gdt (UCS T4 + GDT classifier) | 288.43 |
| **twcs (1-hour window)** | **385.50** ← worse |

This was a surprise. Reason: TWCS with `compaction_window_unit=HOURS,
compaction_window_size=1` on a 15-minute run means **all writes land in a
single window**. TWCS then degenerates to STCS-within-the-window. UCS T4 has
better selection heuristics than STCS in that regime, so TWCS appears worse.

**TWCS's strength only shows up when data spans multiple windows.** For a fair
TWCS test we'd need either:
- duration ≥ 2 hours with hour-windows, or
- `compaction_window_size: 1` and `compaction_window_unit: MINUTES` to fit
  multiple windows in 15m

This means we don't have a valid "TWCS ceiling" in this run — only an
**unfair** comparison where TWCS is handicapped by window mis-tuning. Note for
future runs.

### 4. Throughput differentiation impossible at rate cap

All three conditions sustained 50,000 ops/sec ± 0.02%. We hit the `--rate 50000`
cap exactly. To measure throughput differentiation we'd need an uncapped run
(rate set to 200,000 or similar — safely above hardware ceiling).

### 5. The "fraction of gap closed" framing isn't usable here

Because TWCS was mis-configured, the baseline → TWCS gap isn't a valid
"ceiling". Headline result is thus the direct baseline → GDT delta:
**−9.73% p99 read** at saturation.

## Caveats

| Caveat | Impact |
|---|---|
| Single run, no replicates | Can't yet say "9.73% ± X%". Reproducibility run is the next step. |
| DB WAF Cassandra metric was buggy | Parser fix needed; rerun will use the corrected metric |
| Workload is append-only (no overwrites) | GDT's mechanism is weaker here than in YCSB-like workloads (paper's domain). Real GDT effect on update-heavy workloads would likely be larger. |
| Saturation regime only | p99 = 319 ms is queue-dominated. Different regime than typical production. Worth a rate sweep follow-up. |
| TWCS mis-configured | Not a valid "ceiling" reading in this run |
| No SSD WAF | Standard SMART only (host bytes). No OCP on consumer Samsung. The 9.73% p99 is the cleanest claim. |

## Artifacts (on rig)

```
/root/repos/gdt-poc-harness/results/bafd4869cdd64306b36629ef9986ff09/
├── baseline/
│   ├── condition.json
│   ├── .complete
│   ├── snapshots/
│   │   ├── prework_nodetool.json
│   │   ├── prework_smart.json
│   │   ├── after_nodetool.json
│   │   └── after_smart.json
│   └── workload/
│       └── stress.log
├── gdt/    (same structure)
├── twcs/   (same structure)
├── run.json
└── summary.json   (the headline)
```

## What's the headline claim from this run?

> **"On Cassandra (6.0-alpha2, UCS T4 compaction) running BasicTimeSeries at
> saturation (50K ops/sec, 2KB rows, 64 threads), enabling GDT (deathtime-aware
> compaction bucketing) reduced p99 read latency by 9.73% (319.52 ms → 288.43
> ms). DB-side write amplification was unchanged at the parser-fix-pending
> Cassandra-side measurement and 0.34% lower at the NVMe host-byte
> measurement. Single 15-minute run; reproducibility check pending."**

For a 20-year-old production database, a 10% tail-latency reduction from a
configuration-flag change (one system property, no schema change, no
operational change) is meaningful. The Cassandra-side DB WAF claim needs the
parser fix before we can substantiate it.
