# UCS density grouping — worked example

A concrete walkthrough showing how SSTables get classified into levels by density, and why sharding does not change the classification.

## Configuration

| Parameter | Value |
|---|---|
| Scaling parameter `W` | `0` for all levels |
| Fanout `F` | `2 + W = 2` |
| Threshold `T` | `2 + max(W, 0) = 2` |
| Survival factor | `1.0` (no GC) |
| Flush size on disk | `100 MB` |
| Local space coverage | `1.0` (single node, owns full ring) |
| Shard set coverage | `1.0` |

Code references:
- `fanoutFromScalingParameter` — `UnifiedCompactionStrategy.java:110`
- `thresholdFromScalingParameter` — `UnifiedCompactionStrategy.java:115`
- `getBaseSstableSize` — `Controller.java:684`
- `getMaxLevelDensity` — `Controller.java:697`
- `formLevels` — `UnifiedCompactionStrategy.java:578`

## Step 1 — compute the density ladder

```
baseSstableSize = max(1 MB, 100 MB) × (1 − 0.9 / F)
                = 100 MB × (1 − 0.45)
                = 55 MB
```

Each next level's max density = previous max × `F × survivalFactor` = previous max × 2.

| Level | min density | max density |
|---|---|---|
| L0 | 0      | 110 MB |
| L1 | 110 MB | 220 MB |
| L2 | 220 MB | 440 MB |
| L3 | 440 MB | 880 MB |
| L4 | 880 MB | 1.76 GB |
| L5 | 1.76 GB | 3.52 GB |

Density is `onDiskLength / rangeSpanned` (`ShardManager.java:139`). With one shard covering the whole ring, `rangeSpanned = 1.0`, so **density == size** in this example.

## Step 2 — flush, then watch promotions

### After flush #1
```
L0: [ s1: 100 MB, span 1.0, density 100 MB ]    maxOverlap = 1
```
1 < T → no compaction.

### After flush #2
```
L0: [ s1: 100 MB, s2: 100 MB ]                   maxOverlap = 2
```
Both cover the full ring, so they overlap on every key → `maxOverlap = 2 ≥ T`. Triggers a compaction at L0.

### After L0 compaction
Compacting s1+s2 → ~200 MB output covering the full ring.

```
Density = 200 MB / 1.0 = 200 MB
```

200 MB falls in `[110, 220)` → **lands in L1**.

```
L0: empty
L1: [ s12: 200 MB, density 200 MB ]              maxOverlap = 1
```

### After flushes #3, #4 + another L0 compaction
```
L0: empty
L1: [ s12: 200 MB, s34: 200 MB ]                 maxOverlap = 2
```
L1 trips its threshold. Compacting both produces a ~400 MB sstable, density 400 MB → falls in `[220, 440)` → **lands in L2**.

### Steady-state cascade

The cascade is geometric: every doubling of flushes promotes one more level.

| Flushes ingested | L0 | L1 | L2 | L3 | L4 |
|---|---|---|---|---|---|
| 1   | 1 | 0 | 0 | 0 | 0 |
| 2   | 0 | 1 | 0 | 0 | 0 |
| 4   | 0 | 0 | 1 | 0 | 0 |
| 8   | 0 | 0 | 0 | 1 | 0 |
| 16  | 0 | 0 | 0 | 0 | 1 |
| 17  | 1 | 0 | 0 | 0 | 1 |
| 18  | 0 | 1 | 0 | 0 | 1 |
| 20  | 0 | 0 | 1 | 0 | 1 |
| 24  | 0 | 0 | 0 | 1 | 1 |
| 32  | 0 | 0 | 0 | 0 | 1 (then merges to L5 as L4 fills) |

Read amplification stays at ~1 sstable per level (because `maxOverlap < T` everywhere except at the moment of compaction). Write amplification is ~`log₂(N)` per byte.

## Step 3 — why sharding doesn't change the picture

Now repeat with `baseShardCount = 4`. A flush produces 4 sstables, one per shard, each ~25 MB covering 1/4 of the ring.

```
Density of each shard sstable = 25 MB / 0.25 = 100 MB
```

**Same density as the unsharded case.** Each of the 4 sstables still falls in L0.

After flush #2:

```
L0 shard A: [ s1A: 25 MB span 0.25, s2A: 25 MB span 0.25 ]   maxOverlap = 2
L0 shard B: [ s1B: 25 MB span 0.25, s2B: 25 MB span 0.25 ]   maxOverlap = 2
L0 shard C: [ s1C, s2C ]                                      maxOverlap = 2
L0 shard D: [ s1D, s2D ]                                      maxOverlap = 2
```

Each shard's overlap set trips T=2 independently, producing 4 parallel compactions. Each output is ~50 MB on span 0.25:

```
Density = 50 MB / 0.25 = 200 MB → L1
```

Same level as the unsharded case, just split into 4 pieces. **Size halved twice (full ring → quarter ring), span halved twice (1.0 → 0.25) — density unchanged at every step.** The compaction work, the level placement, and the write amplification are all identical to the unsharded version. Sharding only changes parallelism and per-output-file size.

## What changes if you tune the knobs

| Change | Effect |
|---|---|
| `W = +8` (F=10, T=10) | STCS-leaning: 10 sstables must accumulate per level before merging. Bigger compactions, lower write amp, higher read amp (maxOverlap up to 10 per level). |
| `W = −8` (F=10, T=2) | LCS-leaning: only 2 sstables accumulate before triggering, but the output is split into ~10 shards so density jumps a full F=10 step per compaction. Many tiny outputs, low read amp, higher write amp. |
| Larger flush size | Whole ladder shifts up (base × F per level), fewer levels needed for the same dataset. |
| `survivalFactor < 1` | Levels' max densities shrink — accounts for the fact that compactions delete data, so the post-compaction density grows less than F×. |

## The takeaway

The level a freshly-written sstable lands in is determined entirely by **bytes-per-token-share** at the moment it's classified. Flushes land at the bottom; each compaction's output lands one rung higher because compaction multiplies density by ~F. Promotion is not bookkeeping — it's a re-classification of the output sstable against the same density ladder every other sstable is measured against.
