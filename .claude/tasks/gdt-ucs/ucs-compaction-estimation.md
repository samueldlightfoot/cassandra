# UCS Compaction Triggering — Theory and Estimate for Run #2

Source-grounded breakdown of *when* and *how often* UCS compactions fire under
a given workload, with a concrete pre-flight estimate for Run #2. After the
run completes we compare the estimate to actuals — gaps tell us where the
model is wrong.

All formulas referenced below are at specific lines in
`src/java/org/apache/cassandra/db/compaction/`, commit `3fd4d22bcb` (the GDT
branch HEAD).

---

## 1. Scaling parameter, fanout, threshold

`UnifiedCompactionStrategy.java:112–120`:

```java
public static int fanoutFromScalingParameter(int w)   { return w < 0 ? 2 - w : 2 + w; }
public static int thresholdFromScalingParameter(int w) { return w <= 0 ? 2 : 2 + w; }
```

| spec | W | fanout F | threshold T |
|---|---:|---:|---:|
| `T4` (tiered, 4) | +2 | **4** | **4** |
| `L4` (leveled, 4) | −2 | 4 | 2 |
| `N`  (balanced)  | 0 | 2 | 2 |
| `T8` | +6 | 8 | 8 |

For our run we use `T4`: **a level triggers compaction when it accumulates 4 SSTables**, and the next level holds 4× the density.

## 2. Level boundaries (density-based binning)

`Controller.java:713–729` defines:

```java
getBaseSstableSize(F) = max(1 MB, flush_size_bytes) * (1 - 0.9/F)
getMaxLevelDensity(index, minSize) = floor(minSize * F * survival_factor)
```

For T4 (F=4, default survival_factor=1):
- `baseSize ≈ flush_size × 0.775`
- `L0.max = baseSize × 4`
- `L1.max = L0.max × 4`
- `L2.max = L1.max × 4` … each level is **4× the previous**.

UCS does not bin by SSTable *size* directly — it bins by *density* (`onDiskLength / tokenSpaceCoverage`). On a single-node cluster covering the full token range, density ≈ size, so we treat them as equivalent for this estimate.

## 3. The triggering rule

`UnifiedCompactionStrategy.Level.getBuckets()` calls `Overlaps.constructOverlapSets`, then `assignOverlapsIntoBuckets(threshold, ...)`. A bucket is eligible for compaction iff its `maxOverlap ≥ threshold`. For UCS's default `SINGLE` inclusion method, each overlap set is its own bucket.

So: when the count of SSTables that overlap at the same key reaches T, compaction fires on those T SSTables.

For a write-heavy workload like BasicTimeSeries where every flush spans the entire token range (because partition keys are randomly distributed), **every flushed SSTable overlaps every other one at L0**. Practically: the count of L0 SSTables is the count of overlapping SSTables. The trigger fires when **L0 has 4 SSTables**.

## 4. Flush size on this rig

Cassandra flushes a memtable when its memtable_heap_space × memtable_cleanup_threshold is hit (or on `nodetool flush`). Defaults relevant on the rig:

- `memtable_heap_space_in_mb` ≈ 1/4 of heap. With our jvm-server.options of `-Xmx8G`, that's **2 GB**.
- `memtable_cleanup_threshold` default = `1 / (1 + memtable_flush_writers)` = **0.333** (since `memtable_flush_writers=2` is the default).
- Cleanup target = 2 GB × 0.333 ≈ **666 MB in-heap**.

After serialisation + compression to disk, the flushed SSTable is typically smaller. For random 2 KB rows (compression ratio ~0.85), each flush ≈ **500–600 MB on disk**.

**Using flush_size = 550 MB for the estimate.**

## 5. Level boundaries with flush_size = 550 MB

- `baseSize` = 550 MB × 0.775 = **426 MB**
- `L0.max` = 426 MB × 4 = **1.70 GB**  → an SSTable goes to L0 if density < 1.70 GB
- `L1.max` = 1.70 GB × 4 = **6.83 GB**
- `L2.max` = 6.83 GB × 4 = **27.3 GB**
- `L3.max` = 27.3 GB × 4 = **109 GB**
- `L4.max` = 109 GB × 4 = **437 GB**

A flush at 550 MB → fits in L0 (< 1.70 GB). After 4 L0 SSTables compact, output ≈ 4 × 550 MB = 2.2 GB → goes to L1 (since 1.70 < 2.2 < 6.83). Pattern repeats per level.

## 6. Per-condition estimate (Run #2)

### Estimating user bytes per condition

Run #2 config:
- duration = 30 min
- rate = unlimited (10M cap)
- row size = 2 KB

Throughput ceiling is hardware-bound. Three reference points:
- **Run #1** (capped at 5 K ops/sec): hit 5 K/s exactly → cap was the bottleneck.
- **1 m smoke at start**: ~1120 ops/sec with 64 threads + 100 B rows. Cold-start, JVM warming, no compaction pressure.
- **Expected unlimited steady-state** with 2 KB rows on this hardware: **15 K – 25 K ops/sec** is a reasonable central range. Lower than 1 m smoke would suggest, because (a) larger rows = more bytes per op = more flush/compaction work = back-pressure, (b) reads at 50/50 hit progressively more SSTables as the dataset grows.

**Central estimate: 18 K ops/sec.**

User bytes per condition:
- 30 min × 60 s × 18 K ops/sec × 2 KB/op = **~65 GB**

### Expected compaction counts (T4, flush_size=550 MB, 65 GB user bytes)

| step | input × count → output | density of output | lands at |
|---|---|---|---|
| flushes | 65 GB / 550 MB ≈ **118 flushes** at L0 | 550 MB each | L0 |
| L0 → L1 | 4 × 550 MB = 2.2 GB output × ⌊118/4⌋ = **29 compactions** | 2.2 GB each | L1 |
| L1 → L2 | 4 × 2.2 GB = 8.8 GB output × ⌊29/4⌋ = **7 compactions** | 8.8 GB each | L2 |
| L2 → L3 | 4 × 8.8 GB = 35 GB output × ⌊7/4⌋ = **1 compaction** | 35 GB | **L3** (35 > 27.3 L2.max) |
| L3 → L4 | needs 4 L3 SSTables — we only get 1 — **0 compactions** | — | — |

Leftover at end of run (workload-trigger, no compactions force-fired):
- L0: 118 − 4×29 = **2 SSTables**
- L1: 29 − 4×7 = **1 SSTable**
- L2: 7 − 4×1 = **3 SSTables**
- L3: **1 SSTable**

(`flush_and_drain` at the end of the workload writes any remaining memtable but does not trigger compaction; the leftover SSTables stay.)

### Expected `bytes_compacted` (the Layer-1 metric)

`CompactionMetrics.bytesCompacted` increments by *output bytes* per compaction.

- L0→L1: 29 × 2.2 GB = **63.8 GB**
- L1→L2: 7 × 8.8 GB = **61.6 GB**
- L2→L3: 1 × 35 GB = **35 GB**
- **Total bytes_compacted ≈ 160 GB**

### Expected DB WAF

```
total bytes written = flushed + compacted = 65 GB + 160 GB = 225 GB
DB_WAF = 225 / 65 = 3.46
```

This is consistent with the Lee paper's reported in-place LeanStore baseline of ~2.0 (theirs is lower because of out-of-place efficiency). For Cassandra LSM with no GDT, **DB WAF ≈ 3.5 is the prediction**.

### Expected NVMe-side host bytes

NVMe host bytes ≈ disk writes + a small commitlog/system overhead. Commitlog re-writes every mutation (≈ 65 GB ÷ commitlog_compression_ratio, ~0.7 → ~95 GB). So:

```
NVMe host bytes delta ≈ 225 GB (SSTables) + 95 GB (commitlog) ≈ 320 GB
NVMe-side WAF = 320 / 65 ≈ 4.9
```

Cassandra-side WAF (3.46) and NVMe-side WAF (4.9) shouldn't agree — the gap is commitlog + system_log + filesystem metadata. We should see roughly a **constant factor difference** across conditions.

### GDT expected effect

Per the Lee paper §4 / Table 1 (LeanStore on YCSB-A): GDT alone reduced DB WAF by ~5%. For Cassandra with deeper compaction hierarchy (L2→L3 in our case), the room is potentially larger because deathtime-mixing happens at every promotion. Range:

- **Optimistic** (paper-like): 5–15% reduction → `gdt_db_waf ≈ 2.95 – 3.30` vs baseline 3.46
- **Pessimistic** (UCS's existing density-binning already captures most of the win): 0–5%
- **TWCS ceiling**: time-series workload — TWCS doesn't compact across windows, so its WAF for a 30 min single-window run should be close to 1.0 (just flushes, no cross-window compaction). Expected `twcs_db_waf ≈ 1.1 – 1.3`.

| condition | predicted DB WAF | predicted bytes_compacted |
|---|---:|---:|
| baseline | **3.46** | **160 GB** |
| gdt | 2.95 – 3.30 (−5% to −15%) | 136 – 152 GB |
| twcs | 1.1 – 1.3 (window-effect) | < 20 GB |

## 7. Sensitivity / failure modes

Things that could break the estimate:

| Assumption | Risk | Effect on prediction |
|---|---|---|
| 18 K ops/sec sustained | could be 10 K–30 K | scales everything linearly |
| 550 MB flush size | depends on actual heap + cleanup threshold | shifts level boundaries |
| Full-token-range overlap | true for random partition gen (our config) | if false, fewer compactions |
| 2 KB rows survive compression | actually probably less | flush count higher |
| `nodetool info` `Compacted` field reports output bytes | confirmed by source, but version-dependent | could be 0 if field absent |

## 8. How to validate after Run #2

When the run completes, compare against the table in §6:

```bash
# For each condition:
cat results/<uuid>/<cond>/snapshots/{prework,after}_nodetool.json | jq '.data.bytes_compacted'
# delta = after - before  ≟  predicted bytes_compacted
```

If actuals differ from predictions by >2×, the dominant error is probably one of:
- **Throughput off** (compare `total_operations` to expected 18K × 1800s = 32.4M ops)
- **Flush size off** (compare on-disk size of an L0 SSTable to predicted 550 MB)
- **Compaction triggered fewer times than threshold math predicts** (UCS might pick differently when multiple buckets are eligible at the same maxOverlap)

The biggest expected source of error is throughput — saturation rate varies a lot with row size, cache state, and disk speed.
