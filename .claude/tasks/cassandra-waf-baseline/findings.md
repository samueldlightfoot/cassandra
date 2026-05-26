# Cassandra WAF baseline — findings

Pre-bench: this document captures the measurement framework, methodology rationale, paper-comparability points, and decision tree. Post-bench: results land in §10+.

## 1. Goal + scope

**Goal**: Produce a defensible, reproducible measurement of Apache Cassandra's end-to-end write amplification on Samsung PM9A3 datacenter NVMe across a matrix of workloads and fill ratios, and publish it to the Apache Cassandra Jira as a community baseline.

**In scope**:
- DB WAF, SSD WAF, Total WAF for Cassandra UCS T4 baseline (no mechanism modifications)
- Workloads: YCSB-A zipf 0.8 (paper-comparable) + at least one TWCS time-series workload (Cassandra-realistic)
- Fill ratios: 30%, 60%, 80%, 90%
- Drive: **whichever Hetzner-shipped DC NVMe drive arrives** — model is unknown until provisioning. Strongly likely (~90%+) to be Samsung PM9A3 based on Hetzner's historical inventory; other DC drives (Micron 7450 PRO, Solidigm D7-P5520, Kioxia) are theoretically possible but not seen in agent-research evidence. Drive identity is captured at Phase 1 and pinned to the report. Cross-paper anchoring only fires cleanly if PM9A3 is what arrives.
- 3 replicates per (workload, fill_ratio) cell, 95% CI on the result

**Out of scope (deliberate, to keep the scope honest)**:
- Other drive models (single-drive limitation explicitly stated)
- FDP placement hints (drive doesn't support FDP)
- NoWA / GDT / any mechanism modifications (this is baseline only)
- Cross-version Cassandra comparison (one fork-version measured)
- Replica streaming / repair / bootstrap WAF profile (different question)

## 2. Measurement primitive

The OCP Datacenter NVMe SSD Specification log page 0xC0 exposes **Physical Media Units Written (PMUW)** — bytes actually written to NAND, including SSD-internal GC and wear-leveling relocations. Standard NVMe SMART exposes **data_units_written** — bytes the host sent to the device.

Formulas:
```
SSD WAF   = ΔPMUW / Δdata_units_written          (the SSD's internal amplification)
DB WAF    = Δdata_units_written / client_bytes   (the DBMS-layer amplification)
Total WAF = ΔPMUW / client_bytes                  (= DB WAF × SSD WAF)
```

These are the exact formulas Lee/Ziegler/Leis use in PVLDB 2026 Figure 1 / Table 1 / Figure 14, so our numbers are directly comparable methodology-wise.

Tooling:
- `sudo nvme ocp smart-add-log <device> -o json` — returns PMUW + extended OCP fields
- `sudo nvme smart-log <device> -o json` — returns standard SMART including `data_units_written`
- Both work on PM9A3 per Hetzner-listings agent research; verify on actual hardware in Phase 1

PMUW resolution: vendor-specific but in the same general range as `data_units_written` (~512 KB units on Samsung). Adequate for runs > ~10 GB of writes (~12 minutes at 14 MB/s sustained, much less at higher rates).

Counters are not user-resettable on PM9A3 (typical for OCP 1.0 drives). We take **deltas across the measurement window**, not absolute values.

## 3. Methodology framework

Three procedural pillars, all of which can be wrong if not handled carefully (see `../nowa-feasibility/findings.md` §10 for the gotcha catalogue that informed this design):

### 3.1 Fill ratio

SSD WAF rises non-linearly with fill ratio because the SSD's free-block pool shrinks. The paper's Figure 15 shows in-place LeanStore at 2.33 (160 GB / 18% fill) climbing to 4.72 (800 GB / 90% fill) on the same PM9A3. **A WAF number without its fill ratio is meaningless.**

We measure at 30 / 60 / 80 / 90 % to characterize the curve, not a single point. 80% is the production-typical anchor; 90% is the paper-comparable stress condition.

Pre-fill procedure: bulk-load via easy-cass-stress with no-deletes and verified `nodetool tablestats` until the target fill is reached, before any measurement begins.

### 3.2 Steady state

A freshly-filled SSD has a generous internal free-block pool even at high logical fill, and SSD WAF reads near 1.0 even under bad write patterns. WAF rises as sustained writes deplete the free-block pool to its steady-state level (the SSD's internal GC trigger threshold).

The paper waits ~3 drive-writes of throughput before measurement. For PM9A3 1.92 TB at ~500 MB/s sustained write, that's ~64 minutes per drive-write → ~3.2 hours of pre-measurement warmup.

We use an adaptive procedure: sample SSD WAF every 5 minutes during warmup; declare steady-state reached when 4 consecutive samples agree within 2%. Cap at 4 hours.

### 3.3 Measurement window

30 minutes of sustained workload at steady state, with 60s OCP sampling intervals. Long enough to:
- Generate ≥ 100 GB of host writes → counter resolution is not a limit
- Average over a meaningful number of SSD GC firings
- Detect transient effects via the time series

Plus snapshot reads at T0 (window start) and T1 (window end). Window delta = the primary measurement; time-series is for diagnostics and verification of stationarity.

## 4. Workload selection

### W1 — YCSB-A zipf 0.8 (paper-comparable, mandatory)

- 50/50 read/update split
- Zipfian skew (paper used 0.8)
- 1 KB or 2 KB rows (paper used 1 KB; calibrate so the dataset fits target fill ratio cleanly)
- Single table, single keyspace, no TTL
- Reason: direct cross-reference to paper Table 1. Strongest credibility anchor.

### W2 — Time-series write-heavy (TWCS-realistic)

- 95/5 write/read split
- Monotonic timestamp keys + simulated buckets
- Schema with `WITH compaction = {'class': 'TimeWindowCompactionStrategy'}`
- Reason: this is what Cassandra is actually good at, and what users frequently deploy. Reporting "Cassandra WAF" without including a TWCS-shaped workload would over-narrow the result to OLTP.

### W3 — Read-heavy mixed (YCSB-B equivalent, optional)

- 95/5 read/update
- Reason: bracket the read/write mix. May reveal that SSD WAF profile is dominated by writes (likely) and read-mix doesn't matter much (testable hypothesis).

Whether W3 makes the cut depends on Phase 4 pilot timing; if matrix execution stays reasonable, include it.

## 5. Fill ratio matrix execution

Per (workload, fill_ratio) cell:
1. **Reset**: drop keyspace, force-drop SSTables, `nodetool truncate`, wait for compaction queue to drain, verify `du -sh /var/lib/cassandra/data` returns to baseline
2. **Pre-fill** to target % via bulk-load workload
3. **Verify fill** via `nodetool tablestats` + `df`
4. **Steady-state warmup** with the target workload (adaptive, up to 4h)
5. **Measurement window**: 30 min, 60s OCP sampling
6. **Capture** all JSONL + Cassandra metrics + system stats to per-run dir

Cell time: ~3 hours wall (1h pre-fill at 80% target + 1.5h steady-state warmup + 0.5h measurement). Higher fill ratios take longer to pre-fill but warmup may be quicker.

## 6. Statistical confidence

For each cell, report:
- Mean of 3 replicates ± 95% CI (using t-distribution since n=3)
- Inter-replicate coefficient of variation as a methodology-health metric (target ≤ 5%)

Rejection criteria for individual runs:
- Inter-sample variance during the measurement window > 10% (steady state not actually reached)
- Counter movement < expected by > 50% (something wasn't writing what we thought it was)
- Cassandra metrics report write-stall events during the window (load wasn't sustained)

Flagged runs are replicated, not silently dropped. Document all rejected runs in the published data.

## 7. Caveats — explicit, prominent in the writeup

These will be called out clearly in the Jira post. Hiding caveats is what makes "perf measurement" papers unreliable; surfacing them is what makes them useful.

1. **PM9A3-specific**: results don't generalize to other drives. State this prominently.
2. **Fill-ratio-dependent**: the *shape* of the curve generalizes; specific numbers at one fill point don't. Always quote the fill ratio with the number.
3. **Workload-dependent**: YCSB and TWCS are bracketing examples, not exhaustive. State which workloads were tested.
4. **Single fork-version Cassandra**: pin the commit SHA + UCS config.
5. **Single FS config**: ext4 with documented mount options. Other FSes may behave differently.
6. **Compression on**: DB WAF includes compression-as-reduction effect. Report both compressed and uncompressed-input variants if time allows.
7. **RAID-1 broken for bench**: results are single-drive. Mirror-on writes a different question.
8. **Commitlog isolation**: commitlog moved to separate device (or kept and quantified). State the choice.
9. **No mechanism comparison**: this is baseline only. We don't claim what alternative strategies would deliver.

## 8. Paper-comparability anchors

Cross-references depend on which drive arrives. Three cases:

### Case A — Hetzner shipped PM9A3 (most likely, ~90%+)

| paper claim (PVLDB 2026 Table 1, PM9A3, YCSB-A zipf 0.8, 800GB) | our equivalent (TBD) |
|---|---|
| In-place LeanStore: SSD WAF 2.36, DB WAF 2.00, OPS 229K | Cassandra UCS T4: SSD WAF ?, DB WAF ?, OPS ? |
| Out-of-place + comp + GDT + NoWA: SSD WAF 1.07, OPS 510K | (out of scope for baseline, but contextually relevant) |

Strongest cross-reference. Our Cassandra-on-PM9A3 number directly answers: "is Cassandra's LSM more or less SSD-friendly than LeanStore's in-place B-tree on the exact same drive?" Nobody currently has this data. **High novelty value.**

### Case B — Hetzner shipped another paper-tested drive (Micron 7450 PRO, Solidigm D7-P5520, Kioxia CM7R)

Indirect comparison: paper Figure 14 reports DB / SSD / Total WAF for the same workload across these drives. We can still cross-reference but with weaker direct attribution. Anchor would be: "Cassandra on [drive X] shows SSD WAF Y; paper's in-place LeanStore on [same drive X] shows SSD WAF Z." Still publishable, slightly weaker hook.

### Case C — Hetzner shipped a DC NVMe not in the paper

Paper-comparability collapses to genus-level only ("Cassandra on a modern enterprise NVMe SSD shows..."). Still useful for the Cassandra community but loses the direct cross-system anchor that makes this measurement novel. In this case, document the drive specs (P/E cycles rated, OP space %, NAND type if known) in detail to allow future readers to triangulate.

Case A is the strongest motivation. Case B is still good. Case C is acceptable. None of the three should block publication — the *Cassandra* angle is the primary contribution regardless of paper comparability.

## 9. Decision tree based on measured WAF

Pre-committing to interpretation prevents motivated reasoning later. Roughly:

- **SSD WAF ≤ 1.1 across the matrix**:
  - Headline: "Cassandra's LSM write pattern is already SSD-friendly on PM9A3 across realistic fill ratios."
  - Implication for SSD-aware mechanism work (NoWA, FDP): minimal headroom, probably not worth pursuing.
  - Still publishable — closes a real open question.

- **SSD WAF 1.1–1.4 at 80%+ fill**:
  - Headline: "Cassandra exhibits modest SSD-side amplification under typical fill conditions; potential headroom for SSD-aware optimization."
  - Implication: weak motivation for mechanism work, ~5% gain ceiling estimate.

- **SSD WAF ≥ 1.4 at 80%+ fill**:
  - Headline: "Cassandra exhibits substantial SSD-side amplification; meaningful headroom for SSD-aware optimization."
  - Implication: strong motivation for follow-up NoWA/FDP work.

- **SSD WAF varies wildly with workload**:
  - Interesting in itself. Headline: "Cassandra SSD-side amplification is workload-dependent; characterize before optimizing."

- **DB WAF ≫ paper's LeanStore numbers**:
  - Headline: "Cassandra's LSM-layer amplification on writes is high relative to optimized B-tree systems on the same drive."
  - Implication for mechanism work: targets are different (compression / page-pack territory, not SSD-side).

The Jira post discussion section will pick the corresponding framing.

## 10. Related context

- `../nowa-feasibility/findings.md` — gotcha catalogue (§10) informs Phase 1 ops choices (RAID, commitlog isolation, FS journal). Mechanism work parked behind this measurement.
- `../gdt-first-principles/recommendation.md` — explains why we're not pursuing a mechanism deliverable directly.
- `../gdt-ucs/operational-lessons.md` — bench-noise observations (3-15% on this rig class) informing replicate count and CI design.
- Paper: Lee, Ziegler, Leis. "How to Write to SSDs." PVLDB Vol. 19 No. 7, 2026.

## 11. Results

(Filled as Phase 5 runs complete. Structure planned:

- §11.1 Pilot run (Phase 4) — pilot data
- §11.2 W1 × fill ratio matrix
- §11.3 W2 × fill ratio matrix
- §11.4 (W3 if executed)
- §11.5 Cross-workload analysis
- §11.6 Paper comparison
- §11.7 Final summary numbers for the Jira post

End of pre-bench section.)
