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

## 11. Methodology lessons from the first rig runs (2026-05-27)

Surfaced during smoke + 30-min runs on `cassandra-waf-rig`. Each lesson cost real bench-time to find; documenting so we don't pay for them again on the matrix or any follow-on investigation.

### 11.1 cass-stress's KeyValue workload uses keyspace `cassandra_easy_stress`, not `keyvalue`

**Symptom:** the harness's `reset_cassandra()` was running `DROP KEYSPACE IF EXISTS keyvalue` (the table name) between runs. Reset was a silent no-op for the entire investigation — `cqlsh "DESCRIBE KEYSPACES"` revealed the actual keyspace is `cassandra_easy_stress` (matching the binary's post-Apache-donation name). Data accumulated across all runs without the operator noticing.

**Implication for production matrix:** every cell's `data_bytes_before` reflects whatever the previous cell left behind. Pre-fill is then approximating "add to existing" instead of "start clean and fill to target".

**Rule:** when configuring `keyspaces_to_drop` or any keyspace-targeted CQL against a cass-stress workload, verify the actual keyspace name via `DESCRIBE KEYSPACES` or read the cass-stress source. **Don't assume the keyspace name matches the table or workload name.**

**Default `keyspaces_to_drop` in waf-baseline-poc should be `["cassandra_easy_stress"]` (NOT `["keyvalue", "sensor_data"]`).** TWCS workload uses the same keyspace with a different table (`sensor_data`), so dropping the keyspace covers both.

### 11.2 DB WAF measured here is *post-compression* on disk vs uncompressed client bytes

**What we measure:**
```
DB WAF = host_bytes_written / client_payload_bytes
       = (compressed SSTable bytes hitting /data, from SMART) / (writes_count × row_size_bytes)
```

LZ4 compression is enabled by default on Cassandra tables (`chunk_length_in_kb=16, class=LZ4Compressor` for cass-stress's KeyValue). The host-side bytes counter measures the COMPRESSED writes that leave the OS for the NVMe device. The client-side payload calculation uses the UNCOMPRESSED row size.

**This is the paper's convention.** Lee/Ziegler/Leis Table 1 shows compression dropping DB WAF from 4.06 → 0.62 — exactly this effect. For paper-comparability, our number is right.

**But it must be disclosed in any writeup.** A reader assuming "DB WAF = bytes the database physically wrote per user byte without compression" would interpret our 2.62 as much lower than it really is when measured against uncompressed SSTable size.

For our `random(1024, 1025)` value pattern: cass-stress's `Random.getText()` produces high-entropy text → LZ4 compression is ~1:1 in practice (verified by comparing lifetime tablestats Space-used vs `total_writes × row_size`). So our numbers happen to also approximate "uncompressed DB WAF". This won't hold for real-world data; document the workload's compressibility separately.

### 11.3 cass-stress `--csv` output is truncated by SIGTERM; stdout is authoritative for counts

**Observation:** the 30-min v2 run's CSV showed `Mutations Count = 3,441,363` in its last fully-written row at elapsed=1404s. The window ran for 1800s and stdout's tabular summary reported `Mutations Count = 4,423,663`. Discrepancy: ~22%.

**Root cause:** cass-stress flushes the CSV every few seconds; when SIGTERM fires, the in-flight CSV record is left incomplete (the last line in our CSV is literally truncated mid-timestamp: `2026-05-27T1`). The stdout summary captures the moment-of-stop counts more accurately.

**Rule:** for `writes_count` totals, use the cass-stress stdout parser (`parse_easy_stress_summary`). Use the CSV ONLY for per-second time-series visualisation (latency CDFs, ops/sec curves over time). **Never sum CSV rows to get totals.**

### 11.4 cass-stress's launcher orphans the JVM on SIGTERM (FIXED in library `3e28c41`)

The launcher script ends with `java -jar` (no `exec`). SIGTERM to the bash leader leaves the JVM running orphaned. Discovered when the warmup-phase JVM was still running on port 19500 throughout 22 of the 30-min measurement window — Δhost on /data was DOUBLED, DB WAF was inflated from ~2.6 to ~3.8.

**Fix lives in `cassandra-agent-harness:capture/easy_stress.py`** — `launch_easy_stress_async` now uses `start_new_session=True` and `WorkloadHandle.stop` uses `os.killpg`. Permanent fix; future runs benefit automatically.

**Why this still matters as a lesson:** any future investigation that wraps a Java tool needs to assume the launcher doesn't `exec`. Default to process-group signaling rather than `subprocess.terminate()` alone.

### 11.5 Methodology principle: validate workload counts via multiple independent signals

The 30-min validation cross-checked `writes_count` against:
1. **stdout summary parser** (authoritative)
2. **per-second CSV** (truncated, but consistent shape)
3. **Throughput math**: `writes_count / window_seconds` matched stdout's reported `ops_per_second × 0.5` (50/50 r/w) within 1%
4. **`SELECT length(value)`** to confirm row_size matches the workload's `--field` override
5. **`nodetool tablestats` Local write count** (lifetime, not delta — useful as upper bound)

Without (4) we wouldn't have noticed the key-bytes undercount or known to verify compression. Without (1)+(2)+(3) consistency we wouldn't have caught the orphan JVM. **Cross-checking is the methodology, not just the measurement.**

### 11.6 Minor: client_payload_bytes ignores the key column (~1.2% bias)

Our calculation is `writes_count × 1024`. The actual write includes a `key` column too — observed keys like `001.17.425960` are ~13-14 bytes. Total client bytes per row is ~1037, not 1024. Net effect: our DB WAF is overestimated by ~1.2% (true ~2.59 vs computed ~2.62).

Two options to address:
- Estimate avg key bytes via `SELECT length(key) FROM ...` once and add to row_size_bytes
- Switch to a workload schema where the key is a fixed-size synthetic — gives exact reproducibility

For the Jira-post-grade methodology either is fine. Disclose either way.

### 11.7 SSD preconditioning: skipping the fill-to-80% bench time

**The problem.** SSD WAF is structurally pinned at 1.0 until the drive's free-block pool has been depleted. At low logical fill (≤30%), the SSD has so much OP space + uncommitted LBA range that GC almost never has to relocate valid data. So measuring "Cassandra under high SSD pressure" naively requires filling /data to 80%+, which at our observed throughput (~13 MB/s on /data) takes ~14 hours of pure prefill — for *every* cell that needs that fill state.

**The shortcut.** The SSD doesn't know about partitions, filesystems, or files — it only sees LBAs. We can fill the *raw device* with random data in ~10-45 minutes via `dd` or `fio`, which makes the SSD's FTL mark every LBA as valid. Then we run Cassandra in a fresh ext4 partition on top, and Cassandra's writes face an SSD whose free-block pool is already at the OP-only minimum. SSD WAF > 1.0 immediately, no 14-hour wait. Standard methodology in the SSD-benchmark community (called "preconditioning" or "drive saturation").

**Two preconditioning modes:**

| pattern | command (≈) | time on PM9A3 | accuracy |
|---|---|---|---|
| Sequential dd-random | `fio --rw=write --bs=1M --size=800G --direct=1 --buffer_compress_percentage=0 --refill_buffers` | ~10 min @ 1.5 GB/s | **lower bound** — ballast superblocks are all-valid; GC initially targets Cassandra's region (easier victims); SSD WAF starts low and climbs over time |
| Random precondition | `fio --rw=randwrite --bs=4k --size=1200G --io_size=1200G --direct=1 --iodepth=32 --buffer_compress_percentage=0 --refill_buffers` | ~30-45 min | **realistic steady state** — writing 1.5× capacity ensures every LBA touched + re-touched; FTL fully scrambled |

The 1.5× capacity overwrite in mode 2 is intentional. Writing capacity-once leaves the FTL in a "first-pass" state where valid pages are clustered by write order. Writing 1.5× ensures the SSD has done its first round of GC, blocks have mixed valid/invalid pages, and the next host write hits realistic GC behaviour.

**Procedure on our rig:**

```bash
# 1. Stop Cassandra
nodetool drain && kill -TERM $(cat /data/cassandra.pid)
umount /data

# 2. Precondition (Path B — realistic):
fio --name=precond --filename=/dev/nvme1n1 \
    --rw=randwrite --bs=4k --size=1200G \
    --io_size=1200G --direct=1 --ioengine=libaio --iodepth=32 \
    --buffer_compress_percentage=0 --refill_buffers

# 3. Recreate FS + remount (precondition nuked the partition table + ext4 super)
parted /dev/nvme1n1 mklabel gpt mkpart primary ext4 1MiB 100%
mkfs.ext4 /dev/nvme1n1p1
mount /dev/nvme1n1p1 /data
mkdir -p /data/hints /data/saved_caches /data/logs

# 4. Restart Cassandra; recreate system tables
# 5. Run waf-baseline pilot — SSD WAF now reflects high-fill behaviour
```

**What this enables.** Each cell that would have needed 14h of prefill now needs:
- 30-45 min precondition (one-time before the high-fill matrix)
- 0 min prefill (FS-level fill is whatever the precondition + minimal Cassandra state is; the SSD-level fill is ~100% from the precondition)
- 30 min measurement window as usual

A 3-replicate × 2-workload × 3-T-parameter (T4/T8/T16) high-fill matrix becomes ~5h of bench wall instead of ~30h.

**What this changes about interpretation.** Honest disclosure for the writeup:
- Δhost during the measurement window still reflects Cassandra's writes only (precondition is one-time pre-event; doesn't contribute to Δ)
- SSD WAF measured is "Cassandra under post-fresh drive conditions" — equivalent to a drive that's been in production for several weeks of normal write traffic
- *Not* equivalent to "Cassandra at 90% Cassandra-allocated logical fill after natural buildup" — the SSD's heatmap of "which LBAs are hot" reflects the precondition pattern, not Cassandra's natural access pattern over time
- In practice these should converge after enough Cassandra work, but for a 30-min window the precondition signature might be visible

**When this approach is wrong.** If we wanted to claim "Cassandra fills naturally to 90% over 6 months of production load and then we measured SSD WAF", preconditioning wouldn't reproduce that — natural fill produces an FTL state where Cassandra's *own write pattern* shaped the SSD's view of hot/cold LBAs. For the Jira post we should claim only "Cassandra under steady-state post-fresh-pool SSD conditions" — accurate and honestly disclosed.

**Caveat: TRIM/discard.** Modern ext4 with the `discard` mount option or periodic `fstrim` will send TRIM commands telling the SSD that freed LBAs are no longer needed → SSD removes them from its valid set → fresh-pool grows back. This UNDOES preconditioning. For the bench window, the mount must NOT have `discard` enabled, and `fstrim` should not be run mid-bench. Our current `/data` is mounted `defaults,noatime` (no `discard`) — good.

**Why this approach didn't surface earlier.** The investigation framed prefill as "make Cassandra's data directory full." A subtle reframing — "make the SSD's free-block pool empty" — is the operationally useful invariant. Preconditioning at the block-device level is the right tool for it.

### 11.8 Deploy-before-launch discipline — the T8 rsync-forgot incident

**Cost: ~4 hours of wall time lost on 2026-05-27.**

**Symptom.** T8 cell launched at 15:51 UTC with `--ucs-scaling-parameters T8`. Local library + app tests passed. The CLI parser on the dev box accepted the flag. But the **rig's installed waf-baseline didn't have that flag** — I'd added it locally + committed but never `rsync`'d the source to the rig before launching. The rig's argparse rejected the flag immediately:

```
waf-baseline: error: unrecognized arguments: --ucs-scaling-parameters T8
```

The process exited within milliseconds. No `cells_succeeded`, no `cells_failed`, no `Traceback` — just an argparse error printed to stderr and a clean exit. **The monitor I'd armed didn't include `unrecognized arguments` in its grep pattern.** Zero events emitted. I went idle waiting for a notification that would never come.

**Detection: the next "check" the user asked for, ~4 hours later.**

**Root causes:**

1. **The rig's waf-baseline-poc was a separate editable install** at `/root/waf-baseline-poc/`. It tracks the local files on the rig, NOT my dev machine. Adding a CLI flag locally requires an explicit `rsync` to propagate. No automation does this; it's a manual mental gate.
2. **The monitor's grep pattern only matched the happy path + known explicit failures.** Argparse-time errors weren't covered. (See §11.9.)

**Rules — enforce before any further multi-hour bench:**

- **A "deploy" step MUST precede every rig launch.** Either:
  - Add a `bin/deploy-and-run` helper script in waf-baseline-poc that does `rsync local→rig` then `ssh ... waf-baseline run ...` in one atomic operation. Use this for every launch.
  - Or: extend the harness with a `--rig` mode that detects local vs rig and refuses to launch from local without first rsync'ing.
- **Verify the deploy worked** by calling `waf-baseline --help | grep <new-flag>` on the rig before the actual run. Three-second check.
- **Add a startup smoke test**: 5-second invocation with the same args but with `--measurement-window-s 5 --warmup-max-s 5 --measurement-duration 1s` to verify the args parse and the bench at least starts. If THAT works, then launch the real run.

The 4-hour loss came from a 30-second rsync that wasn't done. Process discipline matters more than code quality at this stage.

### 11.9 Monitor grep coverage — silence is not success

**Cost: contributed to the 4-hour loss in §11.8.**

The Monitor I armed for the failed T8 launch used:

```
grep -E --line-buffered "cells succeeded|cells failed|cell failed|Traceback"
```

T8 actually failed at startup with `unrecognized arguments` — which matches NONE of those patterns. The monitor emitted zero events. I interpreted "no events" as "still running." For 4 hours.

This is the **exact failure mode** the Monitor tool's own docs warn about:

> *"Coverage — silence is not success. When watching a job or process for an outcome, your filter must match every terminal state, not just the happy path. ... ask: if this process crashed right now, would my filter emit anything? If not, widen it."*

I did exactly the wrong thing despite the explicit warning. The lesson is in the tool docs already; I just need to apply it.

**Template for any bench-launch monitor:**

```
grep -E --line-buffered "PASS|FAIL|cells succeeded|cells failed|cell failed|Traceback|Error |error:|unrecognized|usage:|exit|killed|aborted|warmup timed out|bootstrapping"
```

The alternation is intentionally broad. Some events are progress markers (`bootstrapping`, `warmup timed out`) — those confirm the run is alive. Others are failure markers (`error:`, `unrecognized`, `Traceback`). Either way, the monitor is NEVER silent for >5 min if the bench is actually progressing.

**Rule:** if a monitor goes silent for >5 min when something interesting should be happening, ASSUME silent failure. Don't wait — verify directly via `ssh ... pgrep` + log tail. Five seconds beats four hours.

### 11.10 DROP KEYSPACE doesn't free disk space (snapshot accumulation)

**Symptom.** After v3's reset which actually dropped `cassandra_easy_stress`, /data still showed ~70 GB used per statvfs (~8% fill) instead of dropping to ~3 GB. The bootstrap workload's 10s of writes can't account for >60 GB.

**Root cause.** Cassandra's DROP KEYSPACE doesn't `rm -rf` the SSTable files. It moves them to `<keyspace>/<table>/snapshots/dropped-<ts>-<keyspace>/` subdirectories. These persist until either:
- `nodetool clearsnapshot` is run
- `auto_snapshot: false` is set in cassandra.yaml **before** the DROP
- Manual `rm -rf` of the snapshot directories

Cassandra's design treats DROP as recoverable — the snapshots exist so you can `nodetool refresh` to restore. For our bench it's the opposite — we want DROP to actually free disk.

**Rules for the upcoming high-fill matrix:**

| approach | when to use |
|---|---|
| `auto_snapshot: false` in cassandra.yaml | Recommended for the entire bench. We never want bench-time snapshots. Add this before the precondition step. |
| `nodetool clearsnapshot` after each reset | Belt-and-braces if `auto_snapshot` isn't disabled. Should be added to `reset_cassandra()` in the library. |
| `rm -rf /data/.../snapshots/` after reset | Brute-force, only if nodetool's mechanism breaks. |

**Why this matters for SSD WAF interpretation.** The SSD tracks "valid LBAs" — its view of `which physical pages contain data the host might want again`. Even after DROP, the LBAs holding snapshot data are still marked valid in the FTL. So the SSD's fresh-block pool never recovers from a heavy write run unless we follow up with TRIM (which would happen automatically only with `discard` mount option, which we deliberately disabled).

This actually compounds with §11.7 (preconditioning) in a useful way: once the SSD is in "high-pressure" mode from preconditioning + accumulated writes, it stays there regardless of Cassandra-side DROPs. **Inter-cell reset on the host side doesn't reset the SSD-side measurement conditions.** Good for measuring sustained high-fill behaviour across cells.

## 12. Results

(Filled as Phase 5 runs complete. Structure planned:

- §12.1 Pilot run (Phase 4) — pilot data
- §12.2 W1 × fill ratio matrix
- §12.3 W2 × fill ratio matrix
- §12.4 (W3 if executed)
- §12.5 Cross-workload analysis
- §12.6 Paper comparison
- §12.7 Final summary numbers for the Jira post

### 12.0 First clean 30-min single-cell result (2026-05-27, low-fill validation)

| field | value |
|---|---|
| cell | YCSB-A zipf 0.8, 50/50 r/w, 1KB rows, ~7% fill |
| window | 30 min, 31 OCP samples (60s interval) |
| writes_count | 4,423,663 |
| ops_per_second | 5,000.0 |
| p99_latency_ms | 0.68 |
| host_bytes_written (SMART Δ) | 11.89 GB |
| physical_bytes (PMUW Δ) | 11.89 GB |
| client_payload_bytes (writes_count × 1024) | 4.53 GB |
| **SSD WAF** | **0.9999** |
| **DB WAF** | **2.62** (post-compression bytes / uncompressed user bytes) |
| **Total WAF** | **2.62** |

Caveats: single replicate; low fill (drive's free-block pool deep, no SSD GC pressure); steady state never reached in 10-min warmup; key-bytes undercount of ~1.2%. Numbers are CORRECT under the stated definition, but this is NOT a production-representative measurement (fill is too low and replicate count is one). **It is the first methodologically-defensible single data point on the rig.**)
