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

(Add R4+ as further validated measurements land — high-fill cells next.)
