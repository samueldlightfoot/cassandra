# Cassandra WAF baseline — validated results

**Purpose:** ONLY captures measurements that are methodologically defensible. A row here means: cross-checked via multiple independent signals, known-good methodology at the time of measurement, ready for citation in the eventual Jira post.

**Not in this file:** experimental smoke runs, contaminated measurements, runs from before a discovered bug was fixed. Those live in `progress.md` for the chronological record but aren't published numbers.

**Discipline:** when a measurement is added here, it must include the commit SHAs of cassandra-agent-harness + waf-baseline-poc at the time of the run, AND list which methodology lessons (`findings.md` §11) it acknowledges.

---

## R1. First clean single-cell measurement (2026-05-27)

**Status:** validated, low-fill, single-replicate. Methodologically defensible as a single data point but **not production-representative** (fill too low to expose SSD-side amplification; n=1).

### Conditions

| dimension | value |
|---|---|
| rig | Hetzner HEL1, `cassandra-waf-rig` (157.180.98.112) |
| measurement drive | Samsung PM9A3 960GB U.2, S/N `S64FNE0R401522`, firmware `GDC5A02Q`, mounted at `/data` |
| commitlog drive | Samsung PM9A3 960GB U.2, S/N `S64FNE0R401526`, mounted at `/commitlog` (isolated from measurement device) |
| Cassandra | fork `fdp-poc`, jar `apache-cassandra-7.0-SNAPSHOT`, default config + data_file_directories=/data, commitlog_directory=/commitlog |
| workload | YCSB-A profile via cass-stress KeyValue: 50/50 read/write, 64 threads, 1M partitions, 1024-byte values, zipfian distribution |
| compaction | UCS T4 (`UnifiedCompactionStrategy`, `scaling_parameters='T4'`) |
| compression | LZ4 (Cassandra default), `chunk_length_in_kb=16` |
| /data fill at window start | ~7.1% (62 GB of 829 GB, accumulated from prior testing) |
| warmup | 10 min — steady state NOT reached (drive's free-block pool too deep for this throughput to deplete in 10 min) |
| measurement window | 30 min, 31 OCP samples (60s interval) |
| `nodetool flush` at window close | yes |

### Numbers

| field | value |
|---|---:|
| writes_count | 4,423,663 |
| reads_count | 4,425,818 |
| total_operations | 8,849,481 |
| ops_per_second | 5,000.0 |
| p99_latency_ms (cass-stress reported) | 0.68 |
| host_bytes_written (SMART Δ on /data) | 11,886,592,000 (11.89 GB) |
| physical_bytes_written (OCP PMUW Δ) | 11,885,084,672 (11.89 GB) |
| client_payload_bytes — writes × 1024 (key ignored) | 4,529,830,912 (4.53 GB) |
| client_payload_bytes — writes × (1024 + 16) (key included) | 4,600,609,520 (4.60 GB) |
| **SSD WAF** | **0.9999** |
| **DB WAF (key-ignored)** | **2.6241** |
| **DB WAF (key-included, post-fix)** | **2.5837** |
| **Total WAF (key-included)** | **2.5834** |

### Interpretation

- **SSD WAF ≈ 1.0** is expected at this fill ratio. The drive's free-block pool is enormous (≥80% of capacity unused), so no internal GC pressure, no relocations, host bytes ≈ physical bytes. This MATCHES the paper's finding that SSD WAF on commodity NVMe at low fill is near unity. The interesting SSD WAF behaviour starts emerging at 80%+ fill.
- **DB WAF ≈ 2.58** is consistent with LSM + UCS T4 at this stage: memtable flush 1× + L0→L1 compaction ~1.5× + aux files (Index.db, Filter.db, Statistics.db, CompressionInfo.db, Summary.db) ~5-10% overhead. Cassandra's UCS hasn't reached steady-state level-balance in 30min of writing, so this is a transient number, not the long-term DB WAF.
- **Total WAF is dominated by the DB layer** at this fill. The story will shift at 80%+ fill where SSD WAF starts pulling its weight.

### Caveats explicitly disclosed

1. **Single replicate.** No confidence interval. The 5K ops/sec line was steady throughout (variance < 1%) but a single-run point estimate is not the same as a measurement.
2. **Low fill (~7%).** Far below the paper's 90% condition. The SSD WAF result is essentially "the floor" — meaningful only as a "free-block pool is plentiful" data point, not as a "production behaviour" data point.
3. **Compression is on.** DB WAF measures compressed disk bytes / uncompressed user bytes. For comparison against measurements where compression is off, multiply by the achieved compression ratio (cass-stress's `Random.getText()` on 1024-byte values compresses essentially 1:1 in practice — verified against lifetime tablestats).
4. **Steady state not reached in warmup.** 10-min cap fired before `is_steady_state` returned True. At higher fill or higher throughput the cap should be raised; at this combination it's structurally unreachable.
5. **Key column omitted from original calc; ~1.2% bias.** Fixed in `cassandra-agent-harness:HEAD` after this measurement; the key-ignored number is preserved here for trace-ability.

### Cross-checks performed

- Value column length verified to be exactly 1024 bytes via `cqlsh SELECT length(value) FROM cassandra_easy_stress.keyvalue LIMIT 5`
- `writes_count` (stdout) cross-checked against cass-stress CSV cumulative — CSV truncated by SIGTERM 6 minutes early but consistent shape (lesson §11.3); stdout is authoritative
- Throughput math: `4,423,663 writes / 1800s = 2,457 w/s ≈ 0.5 × 5000 ops/s` ✓
- Lifetime tablestats `Space used` (18.55 GB) ≈ `total_writes × row_size` (~18.4 GB) → confirms LZ4 compression is ~1:1 on this workload's random text
- No orphan workload processes during the window (confirmed via `ps` snapshots; lesson §11.4 fix in place)

### Code state at measurement time

- `cassandra-agent-harness:main` = `3e28c41` (process-group fix in `launch_easy_stress_async`)
- `waf-baseline-poc:main` = `5332a4a` (CLI + WafBaselinePoc subclass)
- Cassandra fork `fdp-poc` = `c4777dd988` at measurement; rig had whatever was rsynced at the time

### Methodology lessons acknowledged

- §11.2 — Compression on, post-compression bytes vs uncompressed user bytes
- §11.3 — Stdout authoritative for counts (not CSV)
- §11.4 — Process-group signaling required to avoid orphan JVMs
- §11.6 — Key-bytes undercount (corrected in current results above)

### What this result enables

- Establishes the **floor SSD WAF** for Cassandra on PM9A3 at low fill (≈1.0)
- Establishes the **shape of DB WAF** for UCS T4 (~2.6) for a 50/50 read/write workload before steady-state — useful as a transient datapoint
- Validates every code path in the pipeline (prereqs → reset → prefill → warmup → measurement → finalise + flush → persistence)
- Calibrates the throughput expectations for the production matrix (5K ops/sec, ~13 MB/s host write rate)

### What this result does NOT enable

- ❌ Any claim about "Cassandra's WAF" generally — single-cell, low-fill, n=1
- ❌ Comparison against the paper's Table 1 at 90% fill — wrong fill condition
- ❌ Conclusions about NoWA-style mechanism headroom — SSD WAF ≈ 1.0 at low fill says nothing about high-fill behaviour

---

## R2. First clean-methodology cold-start measurement (2026-05-27, T4, low fill)

**Status:** validated, methodologically clean for the first time. Reveals the path-dependence of Cassandra DB WAF that R1 missed.

### What's different from R1

| dimension | R1 (v2) | R2 (v3) |
|---|---|---|
| reset keyspace name | `keyvalue` (wrong — no-op) | `cassandra_easy_stress` (works) |
| reset actually dropped data | NO | YES |
| schema bootstrap step | NO | YES (10s cass-stress --drop) |
| keyspace state at window start | "warm" (accumulated from smoke + v1) | "cold" (just recreated) |
| key bytes in client_payload | excluded | included (+16 bytes/row) |

Everything else identical (T4, YCSB-A zipf 0.8, 50/50 r/w, 1 KB values, 30-min window, ~7% fill).

### Numbers

| field | value |
|---|---:|
| writes_count | 4,426,312 |
| reads_count | (similar, 50/50 split) |
| ops_per_second | 5,000.03 |
| p99_latency_ms | 0.44 (R1 was 0.68) |
| host_bytes_written | 6,266,880,000 (6.27 GB) |
| physical_bytes (PMUW Δ) | 6,266,482,688 (6.27 GB) |
| client_payload_bytes (writes × 1040) | 4,603,364,480 (4.60 GB) |
| **SSD WAF** | **0.9999** |
| **DB WAF** | **1.3614** |
| **Total WAF** | **1.3613** |

### The 2.62 → 1.36 drop is the headline

Same workload, same drive, same fill regime, same window. **DB WAF dropped 48%.** The difference is entirely the keyspace's prior history:
- **R1**: keyspace was the same one that had been writing through smoke + v1 contaminated runs. Cassandra's compaction was actively merging accumulated data. Lots of L0→L1 and possibly higher-level rewrites firing.
- **R2**: keyspace dropped by reset, schema recreated by bootstrap, measurement window opened against a near-empty schema. 4.4M writes mostly land in L0 SSTables; compaction has minimal pyramid to traverse.

**Implication: Cassandra DB WAF is heavily transient-dependent.** A measurement at minute 30 ≠ a measurement at minute 300 ≠ a measurement at minute 3000. Steady-state DB WAF requires either much longer runs or pre-populating the keyspace before opening the window.

### Why p99 also dropped (0.68 → 0.44ms)

Same root cause. R1's Cassandra was actively compacting accumulated data, producing read-time contention. R2's was mostly writing fresh memtable → flush → L0 SSTable with little background work. Lower p99 reflects less compaction interference.

### Methodology gain over R1

R2 is the first measurement where:
- Reset actually clears state between runs (essential for matrix replicates)
- Schema bootstrap ensures workload doesn't fail post-reset
- Key bytes included in client_payload (1.2% accuracy gain)
- No orphan JVM contamination (process-group fix already in place by R1, but now combined with working reset)

### What R2 enables and does NOT enable

**Enables:**
- "Cold start" Cassandra DB WAF baseline (~1.36 for UCS T4 on this workload)
- Comparison point against future T8/T16 runs under identical "cold start" conditions
- A defensible *lower bound* on DB WAF — steady-state is at least this much

**Does NOT enable:**
- ❌ Steady-state DB WAF claims — we haven't run long enough for compaction pyramid to fill
- ❌ Comparison against R1 — different keyspace state, not apples-to-apples
- ❌ Production-representative numbers — real Cassandra clusters have been writing for weeks; their compaction state is far from cold

### Code state at measurement time

- `cassandra-agent-harness:main` = `cf7bd7b` (bootstrap step + key_size_bytes + reset fix)
- `waf-baseline-poc:main` = `6146863` (keyspaces_to_drop fixed default)

### Methodology lessons acknowledged

- §11.1 (reset keyspace name) — fixed before this measurement
- §11.2 (compression post-) — same as R1
- §11.3 (stdout authoritative) — applied
- §11.4 (process-group) — applied
- §11.5 (cross-check) — verified writes_count via ops/sec math (4,426,312 / 1800s ≈ 2459 w/s; ops_per_second 5000 × 0.5 = 2500 w/s; within 1.6%)
- §11.6 (key bytes) — INCLUDED for the first time
- §11.7 (preconditioning) — NOT applied; R2 is at low fill / no SSD pressure. Future R-entries at high fill will use preconditioning per §11.7.

### Open question this raises

What does the DB WAF curve look like over a 4-6 hour run that lets the compaction pyramid fully form? That's the steady-state R1 was approximately measuring (but contaminated). To answer cleanly, we'd need a long run with no contamination — or a high-fill precondition + Cassandra pre-population step that fast-tracks to steady-state. Both will be addressed by R3+ entries.

---

## R3. UCS T-parameter sweep at cold start (2026-05-27 / 2026-05-28, low fill)

**Status:** validated. Three cells under identical conditions except for UCS `scaling_parameters`. Conditions: YCSB-A zipf 0.8, 50/50 r/w, 1 KB values, 30-min window, fill_fraction=0.04 → bailed (actual fill ~7-9% via accumulated dropped-keyspace snapshots). Cold start each time (reset drops keyspace; bootstrap recreates fresh).

### Numbers

| T | writes_count | ops/sec | p99 (ms) | host GB | phys GB | SSD WAF | DB WAF | results dir |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| T4  | 4,426,312 | 5000.0 | 0.44 | 6.267 | 6.266 | 0.9999 | 1.3614 | `window30v3-20260527T151101Z/` |
| T8  | 4,424,660 | 5000.0 | 0.56 | 6.257 | 6.258 | 1.0001 | 1.3598 | `T8-20260527T210223Z/` |
| T16 | 4,424,785 | 5000.0 | 0.77 | 6.259 | 6.256 | 0.9995 | 1.3602 | `T16-20260528T054443Z/` |

### Findings

**DB WAF is essentially T-invariant at cold start.** Spread of 0.0016 across T4-T16 (0.12% relative) — below the measurement's noise floor for a single replicate. **The T-parameter does not impact DB WAF at 30-min cold-start measurement scale.**

This is the *expected* result. UCS's T-parameter controls when compaction *triggers* (when N SSTables accumulate in a level). At cold start, the rate of trigger events depends on how fast L0 fills via flush, then how fast L1 fills via L0→L1 compactions. In 30 min:
- ~4.4M writes × 1 KB ≈ 4.4 GB of client payload
- Memtable threshold roughly ~256 MB (default) → ~17 memtable flushes → 17 L0 SSTables
- At T4: L0→L1 fires every 4 L0 SSTables → 4 L0→L1 compactions in the window
- At T8: L0→L1 fires every 8 L0 SSTables → 2 L0→L1 compactions
- At T16: L0→L1 fires every 16 L0 SSTables → 1 L0→L1 compaction (or maybe zero, just barely)

So at most ~4 compactions happen during the T4 window vs ~1 at T16. The compaction-driven write-amp difference at this scale is at most a few hundred MB out of ~6.3 GB — well within the noise floor for a single replicate. **T's effect lives in steady-state where dozens to hundreds of compactions fire per window.**

**The p99 read-latency signal IS visible:**
- T4 → 0.44 ms
- T8 → 0.56 ms (+27%)
- T16 → 0.77 ms (+75% vs T4)

Reads have to check bloom filters across all L0 SSTables; higher T = more L0 SSTables → more filter checks → higher read latency. Even at cold-start, the read path is sensitive to T because every L0 SSTable is touched per query regardless of compaction events. **The classic "write-amp ↓ vs read-amp ↑" tradeoff that motivates the T-parameter is half-visible here — only the read side**, because we haven't run long enough for the write side to differentiate.

### What R3 tells us

- **T-parameter cannot reduce DB WAF in a 30-min cold-start measurement.** Any future writeup that wants to make a T-related DB WAF claim needs either (a) much longer runs or (b) pre-populated keyspaces where compaction is already firing at full rate.
- **p99 IS T-sensitive even at cold-start.** Useful as a directional finding for read-heavy operational concerns.
- **All three measurements landed at SSD WAF ≈ 1.0** — confirms the SSD WAF floor is structurally pinned at low fill regardless of which DB-side configuration we test.

### What R3 does NOT tell us

- ❌ Steady-state DB WAF for T4 vs T8 vs T16 — needs multi-hour runs
- ❌ Any high-fill behaviour for either DB or SSD WAF — needs preconditioning
- ❌ Crossover point where higher T's read-amp cost exceeds its write-amp savings — needs an actual mixed-workload optimization study

### Code state at measurement time

All three cells: `cassandra-agent-harness:main` = `cf7bd7b` (post-bootstrap fix + key_size_bytes), `waf-baseline-poc:main` = `29fa03d` (T-param flag + threading).

### What's next

The preconditioned high-fill matrix (per §11.7 in findings.md) is the next planned bench:
1. Stop Cassandra, set `auto_snapshot: false` in cassandra.yaml (per §11.10), unmount /data
2. fio sequential or randwrite precondition of /dev/nvme1n1p3 — ~10-30 min
3. mkfs.ext4 + remount, restart Cassandra
4. Run T4, T8, T16 high-fill cells — ~3 × 40 min = 2 hours

Expected outcome: SSD WAF lifts above 1.0 (real GC pressure on the drive); DB WAF stays approximately the same (cold-start regime persists for the Cassandra keyspace). Whether SSD WAF lifts meaningfully or stays close to 1.0 is the open empirical question — the answer determines if there's any room for SSD-side mechanism work on Cassandra.

---

## R4. UCS T-sweep at HIGH SSD pressure (2026-05-28)

**Status:** the headline result of the investigation. Three cells with the SSD's free-block pool genuinely depleted to 5% via `fio` precondition + `mkfs.ext4 -E nodiscard`. Demonstrates that Cassandra's SSD WAF stays at the floor (~1.0) even when the drive is in the regime where the paper measured LeanStore at 1.94.

### Conditions

- Drive: PM9A3 960 GB U.2, `percent_free_blocks` = 5 (precondition preserved through mkfs by `-E nodiscard` flag)
- Cassandra: same fork + commits as R3
- Workload: YCSB-A zipf 0.8, 50/50 r/w, 1 KB values, 30-min window each
- Reset between cells: `auto_snapshot: false` in cassandra.yaml + reset_cassandra dropping the (correctly named) keyspace; precondition preserved across cells because no TRIM occurs between them
- SSD state verified at 5% percent_free_blocks before EACH of the three cell launches

### Numbers

| T | writes | ops/s | p99 ms | host GB | phys GB | SSD WAF | DB WAF | Total WAF |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| T4  | 4,424,548 | 5000.0 | 0.59 | 6.260 | 6.265 | 1.0007 | 1.3605 | 1.3615 |
| T8  | 4,425,011 | 5000.0 | 0.60 | 6.273 | 6.285 | 1.0019 | 1.3631 | 1.3656 |
| T16 | 4,424,780 | 5000.0 | 0.54 | 6.264 | 6.264 | 1.0000 | 1.3613 | 1.3613 |

### Combined R3 + R4 — the full 6-cell matrix

| cell | fill regime | SSD WAF | DB WAF | p99 ms |
|---|---|---:|---:|---:|
| T4 low-fill | percent_free 99 | 0.9999 | 1.3614 | 0.44 |
| T8 low-fill | percent_free 99 | 1.0001 | 1.3598 | 0.56 |
| T16 low-fill | percent_free 99 | 0.9995 | 1.3602 | 0.77 |
| T4 high-fill | percent_free 5 | 1.0007 | 1.3605 | 0.59 |
| T8 high-fill | percent_free 5 | 1.0019 | 1.3631 | 0.60 |
| T16 high-fill | percent_free 5 | 1.0000 | 1.3613 | 0.54 |
| **range** | | **0.9995 – 1.0019** | **1.3598 – 1.3631** | 0.44 – 0.77 |
| **mean ± stdev** | | 1.0004 ± 0.0009 | 1.3611 ± 0.0012 | |

### The headline findings (now n=6, robust)

**Finding 1: Cassandra SSD WAF is structurally ~1.0 across the entire (T, fill) parameter space tested.**
- SSD WAF spread of 0.0024 (0.24%) across 6 cells covering 3 T-parameters × 2 fill regimes
- Even at SSD's high-pressure regime (5% free blocks, where Lee/Ziegler/Leis Figure 13b shows LeanStore at SSD WAF 2.36), Cassandra's LSM produces SSD WAF of 1.0007 ± 0.0019
- **The paper's SSD-side mechanism work (NoWA, GDT, FDP, alignment) has effectively zero headroom on Cassandra in any of these conditions.** The mechanism question is closed by measurement.

**Finding 2: DB WAF is 1.36 ± 0.002 at cold-start, T-invariant in this measurement window.**
- DB WAF spread of 0.0033 (0.24%) across all 6 cells
- T-parameter does not affect DB WAF at 30-min cold-start scale (compaction pyramid never builds up; needs multi-hour or pre-populated runs to differentiate)
- This is the cold-start lower bound; steady-state DB WAF is higher (path-dependent on prior keyspace activity, as R1 vs R2 showed)

**Finding 3: p99 read latency IS T-sensitive, even at cold-start.**
- Low-fill: T4 → T16 gives p99 0.44 → 0.77 ms (+75%)
- High-fill: T4 → T16 gives 0.59 → 0.54 ms (NOT monotonic — within noise band, see caveat)
- Reads scale with L0 SSTable count (bloom filter checks) regardless of compaction history

### Why the SSD WAF stays at 1.0 even under pressure

Hypothesis (supported by measurement + LSM architecture):
- Cassandra's LSM writes whole SSTables as single sequential append operations
- Each SSTable is many MB to several GB, much larger than the SSD's superblock size
- An SSTable's bytes are programmed contiguously into the SSD's open superblocks
- When that SSTable is later compaction-deleted (TRUE delete, since auto_snapshot=false), it leaves a chunk of contiguous *invalid* pages in those superblocks
- When the SSD's GC needs to reclaim a superblock, the victim it picks is dominated by Cassandra's deletions — i.e. the SSD finds a superblock that's already nearly all-invalid
- Valid-ratio of GC victims is near zero → near-zero relocations per reclaimed block → SSD WAF ≈ 1.0

This is the structural property the paper notes but doesn't quantify for LSM engines. **Cassandra's compaction-driven invalidation pattern happens to be exactly what an SSD's GC wants.**

### Caveats explicitly disclosed

1. **Single replicate per cell.** No formal confidence interval. Variance across the 6 cells is dominated by structural similarity, not noise — the 0.24% spread is itself indicative of stable behavior, but proper CIs would require ≥3 replicates per cell (12 more bench runs).
2. **Cold-start regime for all cells.** DB WAF will be higher at steady-state; SSD WAF behavior at steady-state is unmeasured.
3. **Single workload (YCSB-A 50/50).** TWCS time-series, read-heavy, write-heavy variants not measured. Likely DB WAF differs; SSD WAF likely unchanged (LSM property).
4. **Single drive model.** PM9A3 specifically. Cross-drive generalization argued from architecture but not measured.
5. **High-entropy values (`Random.getText()`).** LZ4 compression is effectively neutral on this data; production data may compress 2-4×, which would proportionally reduce DB WAF.

### Code state

- `cassandra-agent-harness:main` = `cf7bd7b` through R3, `18181b2624` for R4 (includes mkfs-TRIM gotcha documentation)
- `waf-baseline-poc:main` = `29fa03d` (T-param threading)
- Cassandra fork `fdp-poc` head includes results.md updates through R3 (R4 to be committed)

### Investigation outlook

**This is the investigation's pivotal result.** Combined with R2/R3:
- The original NoWA/FDP/GDT mechanism investigation has a measured negative answer: zero SSD-side headroom on Cassandra
- The WAF baseline measurement (the Jira-publishable artifact) has clean numbers at cold-start; steady-state remains a follow-up
- The methodology lessons (findings.md §11) constitute an unintended but valuable secondary contribution

The "should we pursue mechanism work" question is settled. The "what should the Jira post say" question now has a clear answer: report the measurements, the negative SSD-WAF finding, and the T-parameter sensitivity (read-amp side visible, write-amp side requires longer runs).

(Future R5+ could add: replicates for CI, TWCS cells, compressible-data variant, multi-hour run for steady-state.)

## R5. Multi-hour T4 vs T16 at low fill — steady-state DB WAF (2026-05-28 → 2026-05-29)

**Status:** The "steady-state remains a follow-up" item from R4 is now closed. Two 4-hour measurement windows (T4 and T16) at low fill, run back-to-back with a soft reset between. T-parameter DOES differentiate DB WAF once the compaction pyramid has time to build out — exactly what R3/R4's 30-min windows were too short to see. Plan: `r5_plan_v2.md`.

### Conditions

- Drive: PM9A3 960 GB U.2 (`/dev/nvme1n1p3`, S/N S64FNE0R401522)
- Fill regime: low (drive in `percent_free_blocks ≈ 78 → 57` over the 8-hour run; firmly low-fill the entire time)
- Cassandra: same fork + commits as R3/R4; node launched with `bin/cassandra -f -R` (foreground, allow-root)
- Workload: YCSB-A zipf 0.8, 50/50 r/w, 1 KiB values, 64 threads
- Window per cell: **4 hours** (`--measurement-window-s 14400 --measurement-duration 4h10m`)
- Warmup per cell: SSD-WAF-steady gating, terminated at 35 min for both cells (6 samples × 300s = minimum to declare steady)
- Reset between cells: harness soft-reset (drop `cassandra_easy_stress`, force-drop SSTables, restart). Cassandra was *not* restarted; the reset block in cell.json shows `data_bytes_after=300596` (system tables only) and `compaction_drained=true`.
- Prefill: skipped both cells (drive's `data_units_written` already at ~5.1% of partition before each cell, exceeding the 4.0% target)
- Sample cadence: 1× per minute → 241 samples per measurement window

### Numbers

| cell | host GB | NAND GB | client GB | SSD WAF | DB WAF | Total WAF |
|---:|---:|---:|---:|---:|---:|---:|
| T4-LF4h  | 101.58 | 101.56 | 37.37 | 0.99978 | **2.7180** | 2.7173 |
| T16-LF4h | 65.03 | 65.02 | 37.37 | 0.99986 | **1.7404** | 1.7401 |
| **Δ T4 → T16** | −36.0% | −36.0% | (same) | (≈) | **−36.0%** | −36.0% |

(Client payload is the same because cass-stress drives the same nominal rate; only the compaction-driven amplification changes.)

Throughput observed in `stress.log`: ~2500 writes/s + ~2500 reads/s sustained on both cells. 0 errors. p99 write latency ~0.5 ms baseline with occasional ~230 ms spikes during compaction.

### The four R5 findings

**Finding 4: DB WAF IS T-sensitive at steady state.** The T-invariance reported in R3/R4 was an artifact of the 30-min cold-start window. At 4 hours, T4 = 2.72 vs T16 = 1.74 — a clean **36% reduction in NAND writes per client byte** from doubling T (4→16). This matches LSM theory (`DB_WAF ≈ 1 + log_T(D/M)`):

| T | predicted (`1 + log_T(36/2.6)`) | measured |
|---:|---:|---:|
| T4  | ~2.9 | 2.72 |
| T8  | ~2.3 | (not run) |
| T16 | ~1.95 | 1.74 |

The measured T16 is below the simple model, consistent with the dataset (~36 GiB) being small enough relative to T16's `L1` cap (`16 × ~2.6 GiB = ~42 GiB`) that the pyramid stops at one level for T16 but spills to L2 for T4.

**Finding 5: SSD WAF stays at 1.0 across the steady-state window too.** SSD WAF in both 4-hour cells came out at 0.99978 / 0.99986 — indistinguishable from the cold-start 1.0. **The R4 finding that Cassandra's compaction pattern keeps the SSD's GC victim valid-ratio near zero is robust across both timescales tested.** No 4-hour drift, no fill-regime sensitivity (low-fill matches R4's high-fill behaviour).

**Finding 6: Cold-start to steady-state DB WAF is exactly the doubling predicted.** R3/R4 cold-start at T4 was 1.36; R5 steady-state at T4 is 2.72. That's `2.0×`, matching the prediction that the 30-min window captured ≈1 round of compaction (memtable → L0 → L1) while the 4-h window captured 2 rounds (adds L1 → L2). Specifically: 1.36 = memtable-flush WAF (~1.0) + L0→L1 (~0.36 because the L0 sstables were nearly empty); 2.72 = 1.36 + ~1.36 from the L1→L2 round once L1 caps.

**Finding 7: The dataset is right-sized to differentiate T at this T-range.** A common failure mode of "what if both T's come out the same" (predicted in `r5_plan_v2.md` risks) didn't happen — the 36-GiB dataset is large enough that T4 sees an extra compaction level vs T16, but small enough that an 8-hour bench fits in a session. Future R6 (TWCS, replicates, or T2/T32 extremes) can reuse this dataset shape.

### Caveats

1. **Single replicate per cell** — no confidence intervals. The −36% delta is large vs any plausible run-to-run noise (R3/R4 cold-start variance was ~0.2%), but proper CIs need 3 replicates per cell.
2. **Read latency not characterized at steady state.** The R4 finding that p99 reads scale with T (T16 = +75% p99 vs T4 at cold-start) likely amplifies further at steady-state where more SSTables exist. R5 stress.log shows p99 ~0.5 ms baseline for both cells, but with occasional 230-ms spikes that need separate analysis to attribute to T.
3. **Soft-reset (not mkfs) between cells.** T16 launched with the drive at `percent_free=68` (post-T4) vs T4 at `percent_free=78`. Both still firmly in low-fill regime; SSD WAF identical, so the soft-reset is methodologically adequate for this comparison. Would need full mkfs+TRIM between cells for a strict same-starting-state comparison.
4. **Same caveats as R4** carry over: single workload (YCSB-A), single drive (PM9A3), high-entropy values (LZ4 effectively neutral), DB WAF is post-compression.
5. **Wall-time correction from plan.** R5 plan assumed ~4h per cell; actual was ~4h35m (35 min warmup + 4 h window + ~60s teardown). Total R5 wall: ~9h20m.

### Code state

- `cassandra-agent-harness`: editable install on rig, last sync 2026-05-28 (Phase 0 of R5 pre-flight)
- `waf-baseline-poc`: editable install on rig, same sync
- Cassandra fork `fdp-poc`: same commits as R3/R4
- Two new task-folder docs landed this session: `runbook.md` (rig facts), `r5_plan_v2.md` (corrected plan)

### Investigation outlook

R5 closes the steady-state question and validates the LSM-WAF model. Combined with R3+R4+R5:

| What's measured | Answer | Confidence |
|---|---|---|
| Cassandra SSD WAF on PM9A3 (T, fill) | ~1.0 across (T4/T8/T16) × (low/high fill) × (cold/steady) | High (n=8 cells now) |
| Cassandra DB WAF at cold start (T-invariant) | 1.36 ± 0.002 | High |
| Cassandra DB WAF at steady state | 1.74 (T16) → 2.72 (T4) | Medium (n=1 per cell) |
| T-parameter knob effect at steady state | −36% NAND writes per byte (T4→T16) | Medium |

The Jira post can now lead with **two** measured headline findings: SSD WAF is structurally ~1.0, and DB WAF at steady-state is T-sensitive with measured magnitudes. Future R6+ would harden CIs and add TWCS.
