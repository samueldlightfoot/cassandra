# NoWA feasibility on Cassandra — findings

Written 2026-05-25 after re-reading Lee/Ziegler/Leis, *How to Write to SSDs* (PVLDB Vol. 19 No. 7, 2026), specifically §5.3 + §5.4 + §5.5, and after the user noted that **Cassandra already has DIO compaction reads/writes**. This is a corrected scope assessment — my earlier framing in conversation ("compensation writes alone, ~5% gain") was wrong on both counts.

## 1. What NoWA actually is (correcting my earlier framing)

NoWA is not "compensation writes". That's the smallest of its three components. NoWA is a write-pattern discipline enforced on top of an out-of-place DB engine that manages its own zones, GC, and physical placement on a raw block device. The three components, in order of contribution:

1. **Zone-size alignment to SSD GC unit (§5.3).** DB zone size = N × SSD Reclaim Unit (RU). Discoverable on FDP-enabled drives directly; on commodity drives by sweeping zone size and watching when SSD WAF drops to 1 (paper measured ~4–8 GB on commodity enterprise drives; ~16 GB on FDP drives).
2. **Open-zone discipline (NoWA rule 1, §5.4).** Defer opening a new zone until all currently open zones are completely written. Enforces `max_open_zones × zone_size = N × SSD_GC_unit`. Eliminates write-stream multiplexing across superblocks.
3. **Compensation writes (NoWA rule 2, §5.4).** When DB GC victim selection would create invalidation-frequency imbalance across an active group, issue an extra write to re-pack the under-represented zone. Slightly increases DB WAF in exchange for a much larger SSD WAF reduction.

## 2. The actual headline numbers (Table 1, paper)

YCSB-A zipf 0.8, 800 GB dataset, Samsung PM9A3, on top of out-of-place LeanStore + compression + page-pack + GDT:

| step | DB WAF | SSD WAF | OPS (K) |
|---|---:|---:|---:|
| oop + comp + GDT (baseline for NoWA's contribution) | 0.59 | 1.96 | 458 |
| **+ NoWA** | 0.60 | **1.07** | **510** |
| + GC-unit alignment | 0.60 | 1.00 | 535 |

**NoWA alone: SSD WAF 1.96 → 1.07 (−45%), throughput +11%.** Plus the alignment step: SSD WAF → 1.00, another +5% throughput. Combined: ~+17% throughput and SSD WAF halved.

This is the corrected number for what NoWA can deliver. The ~5% figure I had cited earlier was GDT's headline, not NoWA's.

## 3. What Cassandra already has that NoWA needs

The original "not portable" objection collapses substantially once you include DIO:

- **Out-of-place writes** — LSM. SSTables are immutable. Compaction is the DBMS-managed GC equivalent. ✓
- **Direct I/O for compaction reads + writes** — removes page-cache reordering. ✓ (This is the new information that changes the assessment.)
- **Deathtime estimation** — `DeathtimeClassifier` (MaxTimestamp + MinLocalDeletionTime). This is the input signal NoWA's compensation-write decision needs. ✓
- **Build-rev tripwire + jar-freshness checks** — operational substrate for any further fork-vs-baseline work, regardless of whether the work is NoWA. ✓

About 50% of the architectural substrate the paper assumes is already in the fork.

## 4. What's missing — implementation delta

In order of complexity:

### 4.1 RU-size discovery (~1 day, OCP-gated)
Paper §5.3's zone-size sweep. Vary "DB zone size" (in Cassandra: SSTable target size) and watch SSD WAF drop to 1. Requires OCP `Physical Media Units Written` counter to read SSD-side writes. Without OCP, only the throughput-drop proxy is available, which the paper itself calls indirect.

### 4.2 RU-aligned SSTable sizes + fallocate (~1–2 weeks)
Two pieces:
- Modify UCS to target the discovered RU size (or a multiple) as the SSTable output size — currently UCS has upper bounds + bucket-based sizing, no lower-bound discipline.
- Preallocate the SSTable data file with `fallocate(FALLOC_FL_KEEP_SIZE)` at target size before writing. Without this, even DIO writes can hit non-contiguous LBAs because the FS allocates extents on demand. Verify with `filefrag -v` per output.

The tricky sub-problem: flushes. Memtable flushes produce small SSTables that don't hit RU size. Options:
- (a) Coalesce flushes into one RU-sized output — introduces flush latency, write-stall risk.
- (b) Accept that L0 lives outside the NoWA regime until first compaction promotes it.
- The paper sidesteps this — LeanStore writes large batches on buffer-pool eviction, not small irregular flushes. Cassandra doesn't have an analogue, so this is new design space.

### 4.3 Concurrent-writer cap + deferred-open scheduler (~1 week)
NoWA rule 1. Cassandra currently opens an SSTable writer per compaction task + per flush, ungoverned. Required:
- Global semaphore across `concurrent_compactors` + `memtable_flush_writers` capping total in-flight SSTable writers.
- Scheduler that holds back new writer creation until current writers finish (or hit RU-multiple boundaries).

Most of the *behavioral* risk lives here. If you cap at e.g. 2 concurrent writers and a flush arrives mid-compaction, the flush either waits (write stall, propagates back to write path) or breaks the discipline. Needs careful back-pressure design.

### 4.4 Compensation-write decision in UCS victim selection (~1–2 weeks)
NoWA rule 2. Builds on the existing `DeathtimeClassifier`. UCS picks compaction victims today by bucket + age + size. NoWA adds: *does this set produce balanced invalidation across the active group?*

Two implementations:
- **Cheap version**: when UCS would pick an imbalanced set, pick a different (slightly less optimal) set that's more balanced. Zero extra writes, just a scoring tweak. Try first.
- **Paper-faithful version**: when no balanced alternative exists, issue a compensation compaction (extra read+write that re-packs an under-represented SSTable to a fresh location). Only needed if cheap version under-delivers.

The cheap version is plausibly enough on a workload where UCS already has alternative victim sets to choose from. Worth measuring before building the full mechanism.

### 4.5 SSD-iq equivalent for timing (~1 week, OCP-gated)
The compensation writes only pay off when issued *before* SSD GC fires. Paper uses their prior `SSD-iq` work [35] which leans on OCP physical-write counters to model SSD free-block pool. Without OCP, the only fallback is *always compensate*, which the paper itself warns degrades the win.

### 4.6 Operational changes on the rig (~1 hour)
- Break RAID-1. NoWA assumes you know which physical drive your write hit; RAID is a black box that doubles writes possibly differently per drive.
- Move commitlog to a separate device (or accept it as measurement noise).

### 4.7 Workload boundary → `nodetool flush` (optional)
Builds on the existing `MixedTTLKeyValue` workload. Forces class-pure flushes, which lets you separately measure Benefit A (wholesale drops) from NoWA proper. Not required for NoWA, but useful for clean attribution.

## 5. Effort summary (revised)

| component | effort | OCP-gated for validation? |
|---|---|---|
| RU-size discovery | 1 day | Yes |
| RU-aligned SSTable + fallocate | 1–2 wk | Yes (for SSD WAF readout) |
| Concurrent-writer cap | 1 wk | Yes |
| Compensation-write decision | 1–2 wk | No for impl |
| SSD-iq timing model | 1 wk | **Yes — gates the mechanism itself** |
| Bench + tune | 1–2 wk | Yes |
| **Total** | **6–8 weeks** | OCP required throughout |

Down from my earlier "2–3 months" because DIO is a much bigger gimme than I had credited.

Of the 6–8 weeks, ~4 weeks is buildable today on the current rig as "blind preparation". But none of it is validatable without OCP. So if you start now, you'd be writing code on faith for ~4 weeks.

## 6. Hetzner DC NVMe drive availability (2026-05-25 research)

Background research result. Hetzner doesn't publish drive models on product pages — they advertise generically ("X GB NVMe SSD Datacenter Edition"). From corroborating auction listings, smartctl reports, and the OCP product registry:

| Drive (as Hetzner ships it) | OCP DC SSD (PMUW counter) | FDP (TP4146) | Hetzner availability |
|---|---|---|---|
| **Samsung PM9A3** (U.2, MZQL2-series) | **Yes — OCP 1.0**, `nvme ocp smart-add-log` returns Physical Media Units Written | No | Standard DC Edition add-on across AX/PX; common in auction |
| Samsung PM9A1 / PM981a (consumer M.2) | No | No | Standard in cheap auction tiers (AX41-NVMe, EX, current rig) |
| **Samsung PM9D3 / PM9D3a** (Gen5, FDP-capable) | Yes | **Yes** | **Not in Hetzner catalog as of 2026-05** |
| Micron 7450 PRO / Kioxia CD8/CM7 / Solidigm D7-P5520 | Yes (vendor-dependent) | No | Not in Hetzner catalog |

**Pricing**:
- Consumer 1 TB / 2 TB NVMe: €9 / €15
- DC 960 GB / 1.92 TB / 3.84 TB / 7.68 TB / 15.36 TB: €28 / €40 / €49 / €93 / €154
- A 2×1.92 TB PM9A3 swap is roughly **+€60/mo** vs the consumer 2 TB pair we're on now.

**Two decision-relevant outcomes**:

1. **OCP measurement is available on Hetzner via PM9A3** — and the PM9A3 is literally the drive the paper uses in its primary Table 1 + Figure 13 results. This means our SSD WAF numbers would be **directly comparable to the paper's** rather than approximate. That's an unusually strong methodological position for a perf write-up.
2. **FDP is unavailable on Hetzner** — period. PM9A3 firmware does not expose Reclaim Unit Handles; the FDP-capable PM9D3a isn't in their lineup. The "FDP placement hints" alternative path (§5.5, ~2 weeks of work, matches NoWA SSD WAF=1) would require switching providers entirely (Equinix Metal, Latitude.sh, or self-supplied colo).

Sources from the research: [Hetzner addon prices](https://docs.hetzner.com/robot/dedicated-server/dedicated-server-hardware/price-server-addons/), [AX server line](https://docs.hetzner.com/robot/dedicated-server/server-lines/ax-server/), [PM9A3 on OCP registry](https://www.opencompute.org/products/262/samsung-pm9a3-nvme-pcie-ssd), [Samsung FDP whitepaper (PM9D3-only)](https://download.semiconductor.samsung.com/resources/white-paper/FDP_Whitepaper_102423_Final.pdf).

## 7. Recommendation hierarchy (updated with §6 results)

The earlier branching tree collapses to one viable path on Hetzner:

**Recommended path: NoWA on PM9A3 at Hetzner**
- Swap to a 2×1.92 TB PM9A3 box (+~€60/mo)
- Direct paper-comparability is a bonus — paper's Table 1 numbers were measured on the same drive family
- Gate on Phase A (§5.3 alignment alone) before committing to full NoWA — if alignment alone closes most of the SSD WAF gap on Cassandra, stop there

**Alternative (only if FDP is non-negotiable)**: switch providers to one that ships PM9D3a / Kioxia XD8 / DapuStor H5. Trades the €60/mo Hetzner delta for ~$150-300/mo at Equinix Metal or similar. Faster implementation path (~2 weeks vs 6-8) but loses the Hetzner cost/operational profile and loses direct paper-drive comparability.

**Don't pursue**: NoWA without OCP measurement (the mechanism's value cannot be validated; compensation-write timing in Phase D is unbuildable without `Physical Media Units Written` over time).

## 8. Honest framing change vs my prior message

| earlier framing | corrected framing |
|---|---|
| NoWA is "compensation writes" | NoWA is alignment + open-zone discipline + compensation writes, with alignment doing most of the work |
| ~5% gain | ~45% SSD WAF reduction + ~17% throughput |
| 2–3 month port | 6–8 weeks given DIO already exists |
| "rebuild the storage engine" | "extend UCS + add a writer scheduler + extend victim selection" |
| FDP hints clearly preferable | FDP preferable *if* FDP-capable drives are available; NoWA realistic otherwise |

## 9. Related context

- `../gdt-first-principles/recommendation.md` — strategic context this branches off from. GDT investigation recommended stop after Run #004.
- `../gdt-first-principles/assessment.md` — first-principles analysis of why GDT-on-UCS didn't pan out. The DeathtimeClassifier substrate built for GDT is reusable for NoWA Phase C.
- `../gdt-ucs/results-run-004.md` — the one valid GDT-vs-baseline run.
- `../gdt-ucs/stale-jar-postmortem.md` — operational lesson that produced the build-rev tripwire and jar-freshness controls. These persist independent of GDT/NoWA decisions.

## 10. Implementation gotchas and unknown unknowns

Written after a user request to "find the unknown unknowns" with the explicit goal of repeatable 10%+ gains. This is the risk register for the plan in `task_plan.md`.

### 10.1 Gain ceiling — honest re-estimate

The paper's headline +11% NoWA-throughput contribution is layered on top of `oop + comp + page-pack + GDT`. Cassandra's pre-NoWA state mixes those:

| paper feature | Cassandra status | implication |
|---|---|---|
| out-of-place | ✓ already (LSM) | no gain available from this step |
| compression | ✓ but per-chunk, no page-pack equivalent | 5–15% available, orthogonal to NoWA |
| GDT (deathtime bucketing) | demonstrated doesn't transfer (Run #004) | ~0% — UCS already does it via maxTimestampDescending |
| **NoWA** | this work | 5–15% if all phases land |
| GC-unit alignment (§5.3) | Phase A | 0–5% on its own |

**Realistic gain estimates on Cassandra + PM9A3**:
- Pessimistic: 0–5% throughput. SSD WAF 1.4 → 1.2. Within noise band.
- Base: 5–10%. SSD WAF 1.5 → 1.1. Detectable with replicated runs.
- Optimistic: 10–15%. Requires Phases A+B+C to each contribute cleanly.

**Repeatable 10%+ probably requires the full Phase A+B+C stack.** Phase A alone won't reach it. This raises the commitment bar from "let's see if alignment moves the needle" to "let's commit to the full implementation".

### 10.2 Phase A gotchas (RU-aligned sizes + fallocate)

- **G1 — Cassandra writes 5–8 files per SSTable, not one.** Data.db plus Index.db, Filter.db, CompressionInfo.db, Statistics.db, Summary.db, etc. NoWA assumes one zone = one contiguous LBA range. If auxiliary files land in different superblocks, there's inadvertent per-SSTable multiplexing. Unknown: how much do non-Data files contribute (probably <1% on big SSTables, more on small ones), and does the FS reliably colocate them?
- **G2 — fallocate doesn't guarantee a single extent on a fragmented FS.** Single extent on fresh `mkfs`; degrades to 2–10 extents per file with fragmentation. Each extent boundary is a potential superblock boundary on the SSD. Mitigation: bench from fresh mkfs. Unknown: how fast does the FS fragment during a high-write bench?
- **G3 — UCS doesn't know exact output size before writing.** Estimates from live-ratios but actual depends on tombstone purging, compression drift. fallocate'd 4 GiB output landing as 3.2 GiB wastes LBA reservation; landing as 4.5 GiB triggers extent-extension breaking contiguity. Mitigation: split outputs at zone boundaries (UCS supports splits) rather than oversizing.
- **G4 — L0 flushes are too small for RU alignment.** Flushes are 100–500 MB; RU is 4–8 GB. Two paths, neither clearly right:
  - Coalesce flushes into RU-sized super-flushes — holds memtables longer, risks write stalls, may force grown memtable headroom
  - Pass L0 through unmanaged — L0 writes multiplex into superblocks alongside L1+ writes, breaks NoWA for L1+ too

  **This is the biggest open design problem. The paper's LeanStore doesn't have this two-tier write source. Could eat the gain if mishandled.**
- **G5 — Commitlog interference.** 30–50% of total write volume on write-heavy workloads. On the same drive, the SSD sees unmanaged interleaving with NoWA-managed writes. Frequency imbalance is *guaranteed*. Mitigation: separate device (break RAID, use one drive for data and one for commitlog), or accept the noise.
- **G6 — FS journal writes.** ext4 journal is small but live. Each fallocate, file open/close, metadata update writes to the journal region — small scattered writes through the SSD's active superblocks. Mitigation: `data=writeback` mode or journal on a separate device. Unknown: 1% or 10% pollution.

### 10.3 Phase B gotchas (writer cap + deferred-open scheduler)

- **G7 — Many SSTable writer entry points.** Memtable flush, compaction, stream receive, snapshot restore, secondary index build, system table autocompaction. Each needs to flow through the governor. Missing one means unmanaged writer slips through silently. Mitigation: single `SSTableWriterBuilder.build()` chokepoint, throw if not called through the governor. Audit existing direct constructions.
- **G8 — Back-pressure cascade.** Cap concurrent writers → flushes wait → memtable fills → write path stalls → client timeouts. Existing memtable back-pressure tuned assuming bounded flush time; NoWA cap can extend that arbitrarily. Unknown: how much memtable headroom do we need to add (and what heap cost)?
- **G9 — Throughput cap implied by RU × max_open.** If RU = 8 GiB and max_open = 2, writing 16 GiB at a time. At 500 MB/s per writer, 16s per flight. If compaction queue produces > 1 GB/s aggregate inputs, queue buildup. Unknown: PM9A3 sustained throughput under our DIO pattern (datasheet ~3.3 GB/s but real numbers will be lower).
- **G10 — Repair / streaming bypass.** Destination node receives arbitrary-sized SSTables sized by source's state, not RU-aligned. Mitigation: don't bench during repair, document the production limitation.

### 10.4 Phase C gotchas (compensation writes in UCS)

- **G11 — "Active group membership" doesn't exist in Cassandra.** Need persistent metadata tracking writer-cohort + per-cohort invalidation rates. New SSTable metadata column (probably in Statistics.db). 1–2 weeks of bookkeeping work alone.
- **G12 — DeathtimeClassifier observes, doesn't predict.** Reads current SSTable state; to decide whether victim set X+Y will produce balanced *future* invalidation, you need a prediction model. UCS's existing `maxTimestampDescending` is a coarse predictor — Phase C might essentially be a tweak of it, not a new mechanism. Unknown: is residual signal above maxTimestampDescending big enough to matter? If not, **Phase C contributes ~0%**.
- **G13 — Rewrite compactions cost real I/O.** Paper-faithful version reads RU bytes + writes RU bytes per compensation. Wrong trigger threshold → fire constantly → *increase* total write volume. Unknown: real firing frequency on Cassandra.

### 10.5 Phase D gotchas (SSD-iq timing model)

- **G14 — OCP polling is sampled, GC firing isn't.** Poll once/sec; SSD GC fires on its own clock. Between samples, free-block count drops. PM9A3 free-block depletion is probably minutes-scale vs polling at seconds, so likely fine, but "probably" is doing work.
- **G15 — OP space is not directly observable.** Vendor datasheet says "X% OP" but actual free-block pool depends on background GC, wear-leveling, internal fragmentation. The paper's SSD-iq work is its own characterization study. Replicating for PM9A3 specifically is probably 2–4 weeks, not 1.
- **G16 — Without solid SSD-iq, fall back to "always compensate".** Paper warns this degrades the gain. Could be the difference between +10% (gated SSD-iq works) and -5% (always compensate makes things worse).

### 10.6 Cross-cutting unknowns

- **G17 — Read amplification shift.** Bigger SSTables → bigger Index scans, bigger compaction read passes. Run #004 showed +35% p99 read (within noise). NoWA's larger compactions could push further.
- **G18 — Repeatability bar.** Single-run noise is 3–15% on this rig. To claim 10% repeatably need 3+ replicates with tighter noise — probably 30-min runs for steady-state. 4 conditions × 3 replicates × 45 min = 9 hours of bench wall time minimum (~36 hours with cleanup). Harness needs extension for replicate management + CI computation.
- **G19 — Inadvertent baseline shifts.** Each phase changes the baseline for the next. End-to-end measurement (throughput, p50, p99, p999, SSD WAF, DB WAF) on every condition so nothing gets hidden.
- **G20 — RAID-1 measurement asymmetry.** Per-drive OCP counters should be symmetric; meaningful divergence = NoWA interacting with RAID timing in an unexpected way. Mitigation: bench on a single drive, or measure both drives' OCP counters in parallel.

### 10.7 De-risking sequence (revised, optimizing for information per effort)

1. **Order PM9A3 rig.** Gate to all measurement.
2. **Gate 1 measurement** (~3 days). Baseline SSD WAF on stock Cassandra. ≤ 1.2 → stop, no headroom. ≥ 1.5 → proceed.
3. **§5.3 zone-sweep** (~2 days). Discover RU size. Result outside paper's 4–8 GB profile → regroup.
4. **Phase A skeleton + run** (~2 weeks). Address G1, G2, G4 (L0 flush) as they arise.
5. **Address G5 (commitlog) and G6 (FS journal) before measuring**. If skipped, Phase A's numbers are noisy.
6. **Decision gate after Phase A**: 5%+ → proceed to B+C. 0–2% → stop and write up.
7. **Phases B + C in parallel** (3 weeks). Cheap-C can be built independently of B.
8. **Phase D only if borderline**. A+B+C delivers 10%+ → skip D. A+B+C delivers 5–8% → D is the only path to 10%.
9. **Replicated final bench** (~36 hours wall time) for confidence intervals.

### 10.8 Top risks ordered by impact

1. **G4 (L0 flush handling)** — genuinely new design space. The paper doesn't help. Could eat the entire gain.
2. **G12 (DeathtimeClassifier predicts vs observes)** — if UCS's existing ordering already captures the signal, Phase C contributes near-zero and you're betting on A+B alone reaching 10%.
3. **G18 (repeatability bar)** — even if mechanism works, claiming "repeatable 10%+" with 5% noise needs tight bench discipline.
4. **G16 (always-compensate degrades the gain)** — if SSD-iq is too hard, Phase D actively hurts.

### 10.9 Honest probability assessment

If commitment is made to the full plan and a PM9A3 rig:
- **30% chance**: hit 10%+ repeatably.
- **50% chance**: 3–8%. Publishable but modest.
- **20% chance**: cannot distinguish from noise.

For a personal-curiosity perf investigation, those odds are reasonable. For a "this must succeed" plan, they're not.
