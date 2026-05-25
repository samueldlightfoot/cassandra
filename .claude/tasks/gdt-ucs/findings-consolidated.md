# GDT on Cassandra UCS — Consolidated Findings

**Status:** Phase 1 (naive GDT classifier) complete. Phase 2 (TTL-aware
classifier + mixed-TTL workload) in progress.

This is the entry-point document. Detailed evidence and reasoning live in the
other files in this folder; this one tells the story.

---

## TL;DR

We ported the GDT mechanism from Lee, Ziegler, Leis 2026 ("How to Write to
SSDs", PVLDB Vol. 19 No. 7) to Apache Cassandra's Unified Compaction
Strategy (UCS T4). The naive classifier (`floor(maxTimestamp / window)`)
showed **no measurable benefit** across three runs on two workloads. The
audit revealed why: **UCS T4 already does effectively the same thing
implicitly**, via its `maxTimestampDescending` sort + "pull T from the back"
selection rule. The information our classifier provides is already
exploited by baseline UCS.

This is a useful negative result. Phase 2 implements a TTL-aware classifier
(`MinLocalDeletionTime`) which provides information UCS doesn't have
implicitly — the row-level expiration time. A mixed-TTL benchmark is
expected to surface a real ~15-30% DB-WAF reduction.

---

## What we built (Phase 1)

1. **Cassandra fork (`fdp-poc` branch)** with two changes:
   - New class `DeathtimeClassifier` (interface + `MaxTimestamp` impl)
   - `Level.getBuckets()` hook: when classifier is non-null, partition
     SSTables by classifier output, run `Overlaps.constructOverlapSets`
     per partition, concatenate, then `assignOverlapsIntoBuckets` as
     normal.
   - Three system properties: `unified_compaction.gdt.enabled`,
     `unified_compaction.gdt.base_window_micros`, plus the new
     `unified_compaction.gdt.classifier` (Phase 2).

2. **`cassandra-agent-harness`** (https://github.com/samueldlightfoot/cassandra-agent-harness):
   Generic Python harness for driving Cassandra perf investigations under
   agent automation. Lifecycle, capture, workload, orchestrator, results,
   prereqs, agent modules. 94 unit tests. Used by:

3. **`gdt-poc-harness`** (https://github.com/samueldlightfoot/gdt-poc-harness):
   GDT-specific investigation built on the harness. Implements
   `Investigation` with conditions {baseline, gdt, twcs}, captures
   SMART + nodetool snapshots, summarises results.

---

## What we ran

| # | Workload | Duration | Rate | Result |
|---|---|---|---|---|
| 001 | BasicTimeSeries | 15m × 3 | 50K cap | "9.73% p99 reduction" — looked positive, turned out to be noise. Parser bug hid Cassandra-side DB-WAF metric. |
| 002 | BasicTimeSeries (reproducibility) | 15m × 3 | 50K cap | 2.43% p99 reduction → within run-to-run variance. Confirmed Run #001 was noise. With parser fix, DB-WAF Cassandra delta = +0.03% (zero). |
| 003 | KeyValue | 15m × 3 | 50K cap | 1.57% p99 reduction (noise). DB-WAF delta exactly 0.0%. Compaction count identical (37/37). |

Detailed per-run analysis: `results-run-001.md`, `results-run-002.md`.
Pre-flight estimation: `ucs-compaction-estimation.md`.

---

## The audit finding

After three runs of zero signal, we audited the implementation forensically.
The smoking gun is in `audit-why-no-signal.md` §1, summarised here:

In `UnifiedCompactionStrategy.java`:

```java
// Bucket selection — line 900 and 916
this.allSSTablesSorted.sort(SSTableReader.maxTimestampDescending);
// we remove entries from the back  ← THIS is the killer
```

Baseline UCS sorts the bucket by `maxTimestamp` descending, then pulls T (=4
for T4) SSTables from the back — meaning the T **absolute-oldest** SSTables
in the bucket. The T oldest by writetime are, by construction, a
deathtime-coherent group.

Our GDT partitions SSTables by `floor(maxTimestamp / 5min)`. Within each
partition, the same `maxTimestampDescending` + pull-from-back logic applies
(it runs downstream of our hook). So GDT's selection differs from baseline
only in *which bucket it picks from* — not in the time-locality of the
inputs.

For a uniform-write workload, "T absolute-oldest across the level" and "T
oldest within a 5-min bucket" produce overlapping selections with similar
time-spans. Output sizes are identical. `bytes_compacted` is identical.

**Our naive GDT is functionally redundant with UCS baseline. Hence zero
measurable benefit.**

---

## Comparison: STCS / UCS / TWCS deathtime handling

Out-of-box behaviour (Phase 1 finding spurred this characterisation):

| Strategy | Bucketing | Within-bucket pick | Deathtime locality |
|---|---|---|---|
| **STCS** | by size tier | sort+pick involves age weighting but mixes sizes-equal across all ages | **near zero** — mixes recent flush with old compacted SSTable of similar size |
| **UCS T4** | by density (level) | `maxTimestamp` desc, pull oldest T | **surprisingly strong** — effectively time-coherent for write-time-correlated workloads |
| **TWCS** | hard partition by time window | STCS within window, no cross-window | **strongest but rigid** — explicit time windows, can't promote across them |

**Ranking on out-of-box time-coherence: TWCS > UCS T4 >> STCS.**

For our uniform-write benchmark workloads, UCS T4 effectively does what
TWCS does. That's why Run #003's TWCS condition didn't dramatically beat
baseline. The win TWCS brings on time-series — explicit time grouping — is
already provided by UCS's implicit ordering.

This is an interesting standalone finding: **STCS users should consider
migrating to UCS T4 for time-correlated workloads — UCS T4 has TWCS-like
time-coherence without the rigidity.**

---

## Where GDT can actually help (Phase 2)

A classifier that provides information UCS doesn't already use. From the
audit doc §"What it would take":

| Direction | Classifier basis | Expected gain | Implementation effort |
|---|---|---|---|
| **TTL-aware** | `minLocalDeletionTime` per SSTable | **15-30%** on mixed-TTL workloads | **moderate — Phase 2** |
| Hot/cold skew | per-partition write rate | 5-15% on zipf workloads | high |
| Repair churn | partition-level writetime (vs SSTable maxTimestamp) | niche, hard to benchmark | high |

Phase 2 implements TTL-aware. The mechanism:

- Cassandra's `StatsMetadata` already records `minLocalDeletionTime` per
  SSTable. For rows written with TTL N seconds, the expiration is
  `writetime + N` (in seconds-since-epoch). For non-TTL rows, the value
  is the sentinel `Cell.NO_DELETION_TIME` (i.e., never expires from
  TTL).
- `MinLocalDeletionTime` classifier buckets by `floor(minLocalDeletionTime / window)`.
- For SSTables with no TTL (sentinel), fall back to `MaxTimestamp`
  classifier behaviour. Preserves existing semantics for non-TTL data.

This provides information UCS does NOT use implicitly — UCS sorts by
`maxTimestamp` (write-time), not by `localDeletionTime` (expire-time).
For workloads where the two diverge — i.e., mixed TTLs — GDT can group
SSTables by predicted expiration, leading to:

- Compaction picks SSTables about to mostly-expire → dedup is huge → small
  output → less compaction work
- Compaction avoids picking long-lived SSTables that won't yield much →
  delays unnecessary work

Expected DB-WAF reduction: 15-30% on a 50/50 mixed-TTL workload (rows with
TTL=300s vs TTL=3600s). Source: extrapolation from Lee paper's GDT
mechanism applied to a case where UCS's existing maxTimestamp ordering
provides no useful signal.

---

## Operational lessons captured

The other docs in this folder record what would have saved us time:

- `operational-lessons.md` — 13 specific rules + pre-launch checklist. The
  most important one: **calibrate GDT bucket width against actual flush
  rate before committing to a long run**. Took us 4 attempts to get the
  current bucket sizing right.

- `expected-gains-mechanism.md` — the Cassandra-side WA mechanism doc. The
  audit revealed this doc was overconfident: it predicted 2-8% gain
  assuming the Lee paper's mechanism transferred cleanly. It doesn't,
  because UCS already does the time-coherent selection.

- `ssd-level-mechanism.md` — the SSD-physics layer mechanism. Why GDT
  *should* reduce NAND-level WA via uniform-invalidation of multiplexed
  superblocks. Currently unmeasurable on our consumer-NVMe rig (no OCP
  log support).

---

## What's worth shipping right now (Phase 1 writeup)

Two real findings, both publishable:

1. **UCS T4's implicit `maxTimestamp`-descending compaction selection is
   functionally equivalent to deathtime-aware bucketing for write-time-
   correlated workloads.** This is news to anyone who'd consider naively
   porting Lee 2026's GDT to Cassandra. It also implies UCS T4 is
   underappreciated as a time-series compaction strategy — it gets
   TWCS-like time-coherence for free, without the rigidity.

2. **A naive GDT port to UCS shows no measurable benefit on
   write-time-correlated workloads (BasicTimeSeries, KeyValue with
   modest skew). The mechanism reduces to what UCS already does.** This
   saves the next implementer three weeks of work.

These should be a blog post. Material:
- `findings-consolidated.md` (this doc) as the entry point
- `audit-why-no-signal.md` for the technical depth
- The strategy comparison table above
- Run results from `results-run-002.md` (the parser-fixed one)
- Methodology lessons from `operational-lessons.md`

---

## What Phase 2 produces if it works

Phase 2 will tell us whether GDT *can* help Cassandra UCS at all (with
the right classifier), or whether the mechanism is fundamentally
shadowed even with explicit deathtime information. Two outcomes:

**Outcome A: TTL-aware GDT shows >5% on mixed-TTL workload.**
- "GDT with `MinLocalDeletionTime` classifier reduces DB WAF by N%
  on workloads with heterogeneous TTLs. The naive `maxTimestamp`
  classifier (and by extension the Lee 2026 paper's direct port)
  does not."
- Real finding. Worth a CEP. Worth the blog.

**Outcome B: Even TTL-aware GDT shows ~zero effect.**
- "GDT mechanism in Cassandra UCS produces no measurable benefit
  even with explicit deathtime information, on a mixed-TTL workload
  where the information *should* matter. Possible explanations:
  UCS T4's existing selection captures the relevant dynamics
  regardless of which classifier provides them; or the effect
  exists at the SSD layer but is invisible to host-side measurement
  on consumer hardware."
- Still publishable, with stronger negative force.

---

## Open questions and tradeoffs

- **Workload design for Phase 2.** easy-cass-stress's `--ttl` is uniform.
  We need *mixed* TTLs to engage the TTL classifier. Options: write a
  custom workload (Python or Kotlin), modify easy-cass-stress, or use a
  custom CQL preamble via `--cql`. The simplest is probably forking
  easy-cass-stress to add a `MixedTTLKeyValue` workload. ~1 day of work.
- **Run cadence.** With the noise floor at ~3-5% on single 15-min runs,
  we need replicates (3-5 runs per condition) to claim a 15% effect. So
  Phase 2 measurement is ~6 runs × 50 min ≈ 5 hours rig time.
- **DC drive timing.** If we get the OCP-supporting DC NVMe (the €56/mo
  Hetzner upgrade discussed earlier), Phase 2 results would gain a real
  SSD-WAF measurement on top of the Cassandra-side DB-WAF claim. That's
  a much stronger story. Worth waiting for if the DC machine is being
  provisioned anyway.

---

## File map

| Doc | Purpose |
|---|---|
| `findings-consolidated.md` (this) | Entry point + headline story |
| `task_plan.md` | Original plan (Phase 1 complete) |
| `findings.md` | Notes accumulated during Phase 1 |
| `progress.md` | Session log |
| `ucs-compaction-estimation.md` | Pre-flight UCS theory + estimate for Run #2 |
| `expected-gains-mechanism.md` | Cassandra-side mechanism (Phase 1 prediction — partly wrong) |
| `ssd-level-mechanism.md` | SSD-physics mechanism (predictive, unmeasured) |
| `operational-lessons.md` | 13 rules + pre-launch checklist |
| `results-run-001.md` | First run, parser bug, false-positive p99 |
| `results-run-002.md` | Reproducibility check, real numbers, null result |
| `audit-why-no-signal.md` | Forensic audit + STCS/UCS/TWCS comparison |
| `scripts/` | Superseded bash drivers (kept for reference) |
| `conf/` | Cassandra config templates |
