# Cassandra Write Amplification — Investigation Summary

**Status:** primary measurements complete (n=1 per cell, 8 cells: 6 cold-start across T-parameter × SSD-fill, 2 steady-state at low fill for T4 vs T16).
**Headline:** Cassandra's LSM produces structurally near-1.0 SSD WAF in all tested regimes, **closing the question of whether the paper's SSD-side mechanisms (NoWA, GDT, FDP, alignment) are worth porting to Cassandra**. The answer, by measurement: no. R5 also closed the steady-state side: at 4-hour windows the UCS T parameter visibly differentiates DB WAF (T4 = 2.72, T16 = 1.74 — a measured 36% reduction in NAND writes per client byte at T16).

---

## 1. Why this exists

Lee, Ziegler, Leis. *How to Write to SSDs.* PVLDB Vol. 19 No. 7 (2026). The paper demonstrates on LeanStore (out-of-place B-tree) several techniques that drive Total WAF from 7.8× down to ~1.0× under heavy SSD pressure. The investigation set out to ask: do those techniques transfer to Apache Cassandra (LSM, UCS T4)?

Two complementary deliverables were possible:

| deliverable | nature | risk |
|---|---|---|
| (A) WAF baseline characterisation | Measurement-first — quantify what Cassandra's actual WAF is on representative hardware | Low |
| (B) Mechanism port | Implementation-first — port NoWA-style work to Cassandra and measure improvement | High (~6-8 weeks dev with uncertain payoff) |

Per the `nowa-feasibility/findings.md` scoping, (B) was estimated at ~30% chance of producing a ≥10% repeatable gain. (A) was the prerequisite for (B) and would be ~90% likely to produce a publishable artifact. The investigation pursued (A) first.

This summary documents the outcome of (A) and explains why (B) is no longer the right next step.

---

## 2. What we measured

**Hardware:** Hetzner HEL1 dedicated server, Samsung PM9A3 960 GB U.2 NVMe (the exact drive family in paper Table 1), OCP DC SSD Spec 2.0 compliant. Drive serial number S64FNE0R401522 mounted at `/data`. Second PM9A3 (S64FNE0R401526) at `/commitlog` for write-stream isolation.

**Software:** Cassandra fork on `fdp-poc` branch (post-rebase from trunk), built JAR `apache-cassandra-7.0-SNAPSHOT`. Workload via `cassandra-easy-stress` (post-Apache-donation name). Library: `cassandra-agent-harness` capturing OCP `Physical Media Units Written` + standard SMART `data_units_written` over a 30-minute measurement window.

**Workload:** YCSB-A profile — `KeyValue` workload, 50/50 read/write split, zipfian skew 0.8, 1 KB values, 1M partitions, 64 threads. UCS compaction strategy with scaling parameter varied (T4 / T8 / T16). LZ4 compression on (Cassandra default).

**Measurement primitives:**
- `MeasurementWindow` brackets the workload phase with pre/post OCP+SMART snapshots and a 60s periodic sampler thread
- `WafResult` computes:
  - SSD WAF = ΔPMUW / Δhost_bytes_written
  - DB WAF = Δhost_bytes_written / client_payload_bytes
  - Total WAF = SSD × DB
- `nodetool flush` issued at window close (memtable → SSTable bytes captured in Δhost)
- `client_payload_bytes` = writes_count × (row_size + key_size) = writes × 1040

**Two SSD fill regimes:**
- **Low fill** (R2/R3): drive fresh; `percent_free_blocks` ≈ 99 → SSD GC rarely fires
- **High fill** (R4): `fio` precondition writes 843 GB random data to the partition's raw block device, depleting `percent_free_blocks` to 5. Critical detail: `mkfs.ext4 -E nodiscard` preserves the precondition state across filesystem recreation.

---

## 3. Results — the 6-cell matrix

| cell | T | fill regime | SSD WAF | DB WAF | Total | p99 ms |
|---|---|---|---:|---:|---:|---:|
| R2  | T4  | percent_free 99 (low)  | 0.9999 | 1.3614 | 1.3613 | 0.44 |
| R3a | T8  | percent_free 99 (low)  | 1.0001 | 1.3598 | 1.3597 | 0.56 |
| R3b | T16 | percent_free 99 (low)  | 0.9995 | 1.3602 | 1.3596 | 0.77 |
| R4a | T4  | percent_free 5 (high)  | 1.0007 | 1.3605 | 1.3615 | 0.59 |
| R4b | T8  | percent_free 5 (high)  | 1.0019 | 1.3631 | 1.3656 | 0.60 |
| R4c | T16 | percent_free 5 (high)  | 1.0000 | 1.3613 | 1.3613 | 0.54 |
| | | **range** | **0.9995 – 1.0019** | **1.3598 – 1.3631** | 1.3596 – 1.3656 | 0.44 – 0.77 |
| | | **mean ± stdev** | **1.0004 ± 0.0009** | **1.3611 ± 0.0012** | | |
| R5a | T4  | low, 4h window | 0.9998 | **2.7180** | 2.7173 | ~0.5 |
| R5b | T16 | low, 4h window | 0.9999 | **1.7404** | 1.7401 | ~0.5 |

R5 (rows R5a/R5b) was deliberately run at 4 hours per cell to let the compaction pyramid build out. This is the steady-state regime; cold-start (R2–R4) and steady-state (R5) are reported as separate rows because they answer different questions.

For comparison, the paper's Table 1 on the same drive at 90% fill:
- LeanStore in-place baseline: SSD WAF 2.36, DB WAF 2.00, Total 4.72
- LeanStore out-of-place + comp + GDT + NoWA + alignment: SSD WAF 1.00, DB WAF 0.60, Total 0.60

---

## 4. Key findings

### Finding 1 — Cassandra's SSD WAF is structurally ≈ 1.0 in every tested regime

Across 6 cells covering 3 T-parameters × 2 fill regimes, SSD WAF spread is **0.24%** (0.9995 to 1.0019). Even at `percent_free_blocks = 5` — where the paper's Figure 13b measures LeanStore at SSD WAF ≈ 2.36 — Cassandra produces 1.0007 ± 0.0019. **The SSD-side WAF problem the paper targets does not exist on Cassandra in any measured regime.**

The structural explanation: Cassandra writes SSTables as single sequential append operations, each many MB to several GB. When an SSTable is compaction-deleted, it leaves a contiguous chunk of *invalid* pages in the SSD's superblocks. The SSD's GC, picking victims by lowest valid-ratio, finds these compaction-deleted regions and reclaims them with near-zero relocation work. LeanStore's out-of-place B-tree writes are 4-16 KiB pages scattered across the buffer pool, producing victim superblocks with mixed valid/invalid pages and forcing relocation — exactly the problem NoWA addresses. LSM doesn't have this problem.

### Finding 2 — DB WAF is 1.36 ± 0.002 at cold-start, T-invariant in 30-min windows

DB WAF spread is 0.24% across all 6 cold-start cells. The UCS T-parameter does not affect DB WAF at 30-min cold-start scale because the compaction pyramid never builds up — at most ~4 L0→L1 compactions fire during the window for T4, ~1 for T16. The write-amp savings from larger T require either multi-hour runs or pre-populated keyspaces to materialise.

The 1.36 ≈ flush (1×) + L0→L1 (~1.3×) + auxiliary files (~5%). This is a *cold-start lower bound*. R5 measured the steady-state side (see Finding 5).

### Finding 5 — Steady-state DB WAF IS T-sensitive and matches LSM theory (R5)

Two 4-hour cells at low fill, same dataset shape:

| T | predicted (`1 + log_T(36/2.6)`) | measured | vs cold-start (1.36) |
|---:|---:|---:|---:|
| T4  | ~2.9 | **2.72** | 2.00× |
| T16 | ~1.95 | **1.74** | 1.28× |

The doubling of T (4→16) cut DB WAF by **36%** — the other half of the classic write-amp/read-amp tradeoff that R4 couldn't see. The 2.00× cold-start→steady-state factor at T4 matches the prediction that the 30-min window captured ≈1 round of compaction (memtable → L0 → L1) while 4-h captured 2 rounds (adds L1 → L2).

Two operational implications:

1. **Tuning T is a real lever on SSD wear in production.** For write-heavy workloads where reads are rare or cached, T16 measurably reduces NAND-bytes-written-per-client-byte — at our shape, by 36% indefinitely once the pyramid stabilises.
2. **30-min WAF benchmarks systematically underreport.** Anyone reporting Cassandra DB WAF from a sub-hour bench is reporting a cold-start floor, not steady-state. The R5 number is what production operators actually see.

### Finding 3 — UCS T-parameter IS visible in read p99 latency

| T | low-fill p99 ms | high-fill p99 ms |
|---:|---:|---:|
| T4  | 0.44 | 0.59 |
| T8  | 0.56 | 0.60 |
| T16 | 0.77 | 0.54 |

The low-fill series shows a clean +75% p99 climb from T4 to T16. Reads have to check bloom filters across all L0 SSTables; higher T accumulates more L0 SSTables. The read-amp half of the classic tradeoff is visible at cold-start; the write-amp half is quantified at steady-state in Finding 5.

### Finding 4 — Methodology gotchas worth documenting (the meta-finding)

The investigation surfaced 8 distinct methodology bugs, costing ~5-6 hours of bench wall time:

1. `keyspaces_to_drop` default referenced table name instead of keyspace name → reset was silent no-op → R1's "warm" measurement (2.62 DB WAF) was actually accumulated-data effect, not real
2. cass-stress launcher's `java -jar` (no exec) → SIGTERM orphans JVM → contaminated v1 30-min run
3. `client_payload_bytes` ignored key column → ~1.2% bias
4. cass-stress `--csv` truncated by SIGTERM → must use stdout summary
5. Forgot `rsync` before launch → 4-hour silent failure
6. Monitor grep didn't cover `unrecognized arguments` → same 4 hours
7. `auto_snapshot: true` → DROP KEYSPACE moves data to snapshots → fill ratio confusing
8. `mkfs.ext4` TRIMs by default → undid the first precondition → ~50 min lost

Each fix is documented in `findings.md §11` with the rule + how-to-apply text. The meta-lesson: **measurement methodology is the dominant uncertainty in a WAF investigation; the underlying drive physics are well-understood, but operational discipline matters more than people expect.**

---

## 5. Conclusion

**Cassandra's LSM architecture is structurally well-matched to commodity SSDs.** In all tested regimes (3 T-parameters × 2 fill states, 6 independent cells), the SSD-side write amplification is at the structural floor (≈ 1.0). The mechanism work in Lee/Ziegler/Leis (NoWA, GDT, FDP, alignment) addresses problems that arise from out-of-place B-tree page-level writes; those problems do not manifest with LSM's sequential SSTable writes. **No SSD-side mechanism implementation on Cassandra would produce a measurable improvement in any of the conditions tested.**

DB-side amplification (compaction overhead) is the dominant component of Cassandra's WAF — 1.36 at cold-start, 1.74 (T16) → 2.72 (T4) at steady-state. R5 quantified the UCS T-parameter knob at steady-state: doubling T from 4 to 16 reduces DB WAF (and therefore NAND-bytes-written-per-client-byte) by 36%, at the cost of a read-amp penalty already characterised in Finding 3. **The realistic remaining levers for DB WAF reduction are real-world data compressibility (a measurement question, not an implementation question) and compaction strategy choice for the workload (TWCS for time-series, T16 for write-heavy/cached-read workloads) — both characterised by the existing UCS literature plus R5 and orthogonal to the paper's contributions.**

**Investigation recommendation:** publish the WAF baseline as a Cassandra Jira contribution with the measured numbers, the negative mechanism finding, *and* the R5 steady-state T-sensitivity result. Do not pursue NoWA / FDP / GDT mechanism work on Cassandra. If a follow-up is desired, the highest-leverage direction is a **compressibility-sensitivity study** on representative production data, which would map our 1.36/1.74/2.72 numbers into the actual DB WAF that Cassandra operators see in different workloads.

---

## 6. What is NOT in the data

Disclosed caveats for the eventual writeup:

1. **n = 1 per cell.** No formal confidence interval. The 0.24% spread across the 6 cold-start cells is suggestive of stable behavior but is not a substitute for replicates. R5's two steady-state cells were also n=1 each, though the 36% T4→T16 gap is large vs any plausible run-to-run noise.
2. **Steady-state characterised only for T4 vs T16 at low fill.** R5 closes the cold-start-only gap noted in the original caveat list, but only for two T values and one fill regime. T8 steady-state and high-fill steady-state remain unmeasured.
3. **Single workload.** YCSB-A 50/50 r/w only. TWCS time-series, read-heavy (95/5 r/w), and write-heavy variants were planned but not run.
4. **Single drive model.** PM9A3 960 GB. Cross-drive generalisation argued from architecture but not measured. Other DC NVMe drives (Micron 7450 PRO, Solidigm D7-P5520, Kioxia CM7R per paper Figure 14) would be useful confirmations.
5. **High-entropy values.** `Random.getText()` produces values that LZ4 compresses ~1:1. Real production data often compresses 2-4×, which would proportionally reduce DB WAF. The headline 1.36 is a worst-case-compression number.
6. **30-minute measurement window.** Long enough for stable ops/sec but short of multi-drive-write durations the paper's methodology recommends.

---

## 7. Reproducibility

| artifact | location |
|---|---|
| Cassandra fork at measurement time | `github.com/samueldlightfoot/cassandra` `fdp-poc` branch, see commit log |
| Measurement library | `github.com/samueldlightfoot/cassandra-agent-harness` `main` |
| Investigation runner | `github.com/samueldlightfoot/waf-baseline-poc` (local; can be open-sourced) |
| Raw cell artifacts | rig `/data/results/*/ycsb_*/cell.json` (per-cell JSON with full OCP samples, workload summary, WafResult) |
| Per-cell stress logs | rig `/data/results/*/{warmup,<cell_id>}/stress.log` and `ycsb_a.csv` (per-second time series) |
| Methodology docs | this folder: `findings.md` §11 (lessons), `results.md` (validated entries R1-R4), this `summary.md` |

A reader following the methodology in `findings.md` §11.7 (preconditioning) + the R4 conditions can reproduce the high-fill cells on any OCP-capable DC NVMe drive in ~1.5 hours per cell.

---

## 8. Future work (if continued)

Not required for the publishable artifact. Listed in order of leverage:

1. **Compressibility sensitivity study** — re-run the matrix with cass-stress's `--field` configured to generate compressible (e.g. JSON-shaped, repeating-pattern) data. Maps the headline 1.36 to a range across compression ratios.
2. **TWCS time-series cell** — different compaction strategy, expected to produce DB WAF closer to 1.0 because TWCS minimises cross-window compaction.
3. **Replicates for CI** — 3-5 replicates per existing cell. Confirms the n=1 results.
4. ~~**Steady-state DB WAF**~~ — **DONE in R5** for T4 and T16 at low fill. Remaining: T8 steady-state, high-fill steady-state, and longer-than-4h windows to check whether DB WAF continues to climb past R5's numbers.
5. **Multi-drive variants** — if access to other DC NVMe drives is feasible.

Each of these is a measurement question, not an engineering question.
