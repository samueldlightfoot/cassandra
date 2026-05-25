# GDT — Expected Gains, Mechanism, and What We'll See

Companion to `ucs-compaction-estimation.md`. That doc estimated *how much*
compaction work the run produces. This doc explains *why GDT reduces it*,
*what gains transfer to other metrics* (throughput, p99 read), and
*what we cannot expect to see*.

Grounded in the GDT code at `src/java/.../db/compaction/UnifiedCompactionStrategy.java`
and `unified/DeathtimeClassifier.java` (fdp-poc branch, HEAD a2de395bcf).

---

## 1. What GDT does at the code level

The change is a single hook inside `Level.getBuckets()`:

```java
DeathtimeClassifier classifier = context.controller.getDeathtimeClassifier();
List<Set<SSTableReader>> overlaps = (classifier != null)
    ? overlapsByDeathtime(liveSet, classifier, index, context.controller)
    : Overlaps.constructOverlapSets(liveSet, ...);  // baseline path unchanged
```

When the classifier is non-null:

1. Partition the level's SSTables by classifier output (currently
   `floor(maxTimestamp / window)`, where `window = baseWindow × fanout^level`).
2. For each partition, run `Overlaps.constructOverlapSets()` independently.
3. Concatenate the per-partition overlap sets and pass them to
   `Overlaps.assignOverlapsIntoBuckets(threshold, ...)`.

The trigger rule is unchanged: a bucket is eligible iff `maxOverlap ≥ threshold`
(T=4 for UCS T4). What changes is **which SSTables are eligible to bucket
together**: only SSTables with the same deathtime classifier output.

## 2. What this changes about compaction patterns

### Baseline (no GDT)

UCS at L0 sees `N` SSTables that all overlap (because every flush spans the full
token range for our random-partition-key workload). Once `N ≥ 4`, it compacts
the 4 with highest priority (currently picked by maxTimestamp-descending order
within the bucket).

The 4 chosen SSTables can have **arbitrarily different write times**:
- SSTable A: maxTimestamp = `t=0…+30s`
- SSTable B: maxTimestamp = `+45s…+75s`
- SSTable C: maxTimestamp = `+1m30s…+2m`
- SSTable D: maxTimestamp = `+3m20s…+3m50s`

Compaction output: a single L1 SSTable spanning `t=0…+3m50s`. Its `maxTimestamp`
is `+3m50s`. **The output has a wide deathtime range.** When this L1 SSTable
later participates in an L1→L2 compaction, it's mixed with other wide-range
L1 SSTables → L2 outputs have even wider ranges.

### GDT-enabled

Same scenario but with `baseWindow = 300s` (our chosen value):
- L0 bucket width = 300s → SSTables grouped into 1-minute slices of write time
- 4 SSTables in same L0 bucket → maxTimestamps all within a 5-minute window
- Compaction output: L1 SSTable spanning ~5 minutes
- L1 bucket width = 300s × 4 = 1200s (20 min) → L1 SSTables grouped by 20-min slices
- L1→L2 compaction inputs all within 20 minutes of each other

**Compaction outputs are deathtime-coherent at every level.** The hierarchy
maintains time locality from L0 down through L2/L3.

## 3. Direct gain — reduced bytes_compacted

This is the **Layer 1** metric, the strongest proof we can make.

### The mechanism

In our append-only workload, no row is ever invalidated mid-life. So every byte
written to disk lives until its SSTable is compacted into the next level. The
quantity `bytes_compacted` = sum over all compaction events of the output bytes
written.

For append-only data, every L0→L1 compaction's output ≈ sum of inputs (no
dedup). So the *number of times each byte gets re-written through the
hierarchy* drives `bytes_compacted`.

If we write `U` GB of user data and it takes `K` promotions to reach the
deepest level, `bytes_compacted ≈ U × K`.

### Where GDT reduces it

Two effects, both modest for append-only workloads:

**(a) Better compaction selection within a level.** When UCS at a level has
many SSTables to choose from, the *least efficient* choice is to compact 4
SSTables whose outputs land at the next level via density math but whose
combined density is suboptimal (e.g., 1 large + 3 small → output near boundary →
gets reclassified up unnecessarily). GDT's deathtime partitioning indirectly
helps because SSTables of similar age tend to have similar sizes (same flush
trigger), so the output density is more predictable. Effect size: small,
maybe 1-3% reduction in unnecessary level promotions.

**(b) Bucket-internal density coherence.** With GDT, each bucket holds
SSTables flushed in the same 5-min window. They have similar density. The
"average density" the compactor uses to bucket them is more representative.
This reduces the chance of edge-case compactions where 4 SSTables with mixed
densities produce an output that straddles two level boundaries. Effect size:
1-2% reduction.

**Total expected `bytes_compacted` reduction: 2-8%** for our append-only
BasicTimeSeries workload.

### Where GDT *doesn't* help append-only

Lee paper's larger reductions (≥5% on YCSB-A, larger on TPC-C) come from a
mechanism that append-only data doesn't have: **dedup at compaction**. When
the workload overwrites a key, compaction discards older versions. If GDT
groups overwrites together, compaction can drop more pages → output much
smaller than inputs. Append-only never invalidates a row → compaction output
size ≈ input size always → GDT can't shrink outputs this way.

## 4. Indirect gain — throughput under saturation

The mechanism:

Cassandra reserves CPU + I/O for background compaction. If compaction work
drops, those resources free up for foreground request handling.

`concurrent_compactors = 4` (our config) means up to 4 compaction threads.
Each running compaction:
- Reads input SSTables (page cache → memory)
- Decompresses, merges, recompresses
- Writes output SSTable (sequential disk write)
- Updates index + commit log of completion

Foreground writes share the same I/O queue. At 44K ops/sec, the system is
I/O-bound on the writes path. Every byte less of compaction I/O frees ~30 μs of
write capacity.

If GDT reduces `bytes_compacted` by 5%, the compaction I/O drops by 5%, and
foreground throughput should grow by approximately the same fraction —
assuming compaction was the bottleneck (which it is at our saturation point).

**Expected throughput gain: ~0-10%, central estimate ~5%.**

We won't see this unless we exceed the `--rate` cap (we're at 50K cap,
sustaining ~44K — so any gain shows up as ops/sec rising toward 50K).

## 5. p99 read latency — yes, expected, with caveats

This is what the user specifically asked about.

### Why p99 read should improve

Three mechanisms, all real, magnitudes vary:

#### (a) Reduced compaction interference (biggest effect)

Compactions evict page cache (they read old SSTables, pulling them into
cache, displacing hot data). When a foreground read needs a page that just
got evicted, it pays a disk read cost (~5-10 ms instead of <1 ms).

p99 read latency is dominated by these uncached-page events. The fewer
compactions run, the fewer cache-eviction events, the fewer slow reads.

Our 2m smoke showed p99 = 100 ms at 44K ops/sec. That's massively above
the per-op floor (~0.3 ms in Run #1 when system was rate-capped at 5K).
The 100 ms is overwhelmingly back-pressure + cache pressure from
compaction-induced contention.

**Expected p99 improvement from this mechanism: scales with compaction
reduction, but non-linearly. A 5% compaction reduction could yield 5-20%
p99 improvement** — partly because removing a few worst-case compaction
spikes disproportionately affects the tail.

#### (b) Bloom filter check count (small effect)

A read on `sensor_id=X` must check the bloom filter of every SSTable that
could contain that key. With our random partition keys, every L0/L1/L2
SSTable could contain any sensor_id (each SSTable spans the full token
range).

Number of bloom checks per read = total SSTable count. With 30 SSTables,
that's 30 × ~10μs = 300μs per read. Negligible at the median, but at p99
the variance matters.

If GDT keeps the SSTable count similar to baseline (we calibrated bucket
width to ensure this), bloom check count is unchanged → no gain from this
mechanism.

If GDT under-fires compactions (which we explicitly fixed), SSTable count
goes UP → bloom checks SLOWER → p99 WORSE. (This is why bucket sizing
matters.)

#### (c) Deathtime-coherent SSTable page caching (small effect)

With GDT, "older" SSTables (older deathtime classifier output) are less
frequently touched by compactions (they only mix with other older
SSTables). Their pages are less likely to be evicted from page cache.
Reads that target those (rare) regions benefit.

For our workload (random partition keys, no read skew toward old data),
this effect is small.

### Expected p99 read gain on our run

| GDT improvement in compaction work | Expected p99 reduction |
|---|---:|
| 2-3% (modest) | 5-10% |
| 5-8% (mid) | 10-20% |
| 10%+ (strong) | 15-30% |
| ~0% (no effect) | ~0% |

The mapping is non-linear because p99 is tail-sensitive — removing one or
two slow compaction events can move the 99th percentile meaningfully even
if total bytes_compacted only drops a few percent.

## 6. Throughput vs p99 — which moves first?

Throughput tracks *mean* compaction overhead. p99 read latency tracks
*worst-case* compaction overhead.

If GDT eliminates one large compaction → throughput barely moves (it was
1 of N), but p99 read may improve a lot (that one event was a p99 spike).

If GDT reduces all compactions proportionally → throughput moves more
than p99.

So:
- **Throughput improvement ≈ mean compaction-bytes reduction**
- **p99 reduction ≈ worst-case compaction-bytes reduction** (could be more
  or less than mean)

This is also why we shouldn't be surprised if throughput and p99 move in
*different* magnitudes. The relationship between them is workload- and
compaction-pattern-dependent.

## 7. What we cannot expect to see on this rig

- **Direct SSD WAF reduction.** Standard SMART only gives host bytes
  written, not physical writes. Even if GDT reduces device-internal WA,
  we can't measure it. See `ucs-compaction-estimation.md` for the
  Layer-1 / Layer-2 / Layer-3 discussion.
- **Catastrophic baseline-vs-GDT differentiation.** Lee paper saw 5% on
  YCSB-A (B-tree). Append-only time-series with random keys is a weaker
  case for GDT — single-digit % is realistic.
- **Multi-level GDT effects.** Our 15-min run only triggers L0→L1 and
  L1→L2 compactions. GDT's effect at deeper levels (L3+) needs hour-scale
  runs.

## 8. What we *should* see if GDT is working correctly

Sanity checks for "is this real or a measurement bug":

| Signal | Healthy GDT | Pathological GDT (under-firing) |
|---|---|---|
| `bytes_compacted_delta` | ~same as baseline, slightly less | Much less than baseline (looks like a win but isn't) |
| SSTable count at end | Similar to baseline | Much higher than baseline (compaction deferred) |
| `bytes_disk_used_delta` | Similar to baseline | Similar to baseline (data still on disk) |
| Throughput | Slightly higher (less compaction overhead) | Higher (because compaction isn't running) |
| p99 read | Lower (less interference) | Higher (more SSTables to bloom-check) |
| Number of GDT log "partitioned N sstables into M buckets" lines (DEBUG) | Many, with M < N | Many, with M ≈ 0 because nothing meets threshold |

**The pathological case is what we explicitly designed bucket width to
avoid.** If we see the pathological pattern after the run, the bucket
width is still too narrow and we re-run with a larger window.

## 9. The TWCS-as-ceiling reading

TWCS does explicit time-window compaction. For BasicTimeSeries (every write
has a fresh timeuuid), TWCS produces near-perfect deathtime grouping. So
TWCS is what GDT *should* approximate.

Three possible outcomes from the three-way comparison:

| baseline DB WAF | gdt DB WAF | twcs DB WAF | Interpretation |
|---|---|---|---|
| 3.5 | 3.4 | 1.5 | GDT mechanism barely engages — closes <10% of available gap |
| 3.5 | 3.0 | 1.5 | GDT closes ~25% of the gap automatically — modest but real |
| 3.5 | 2.0 | 1.5 | GDT closes ~75% of the gap — strong result |
| 3.5 | 1.6 | 1.5 | GDT matches TWCS automatically — banner result |

The "fraction of gap closed" framing is the better metric than absolute
percentage, because it tells the operational story: **how much manual
TWCS-tuning effort does GDT save you?**

## 10. Honest expectations summary

For our specific run (BasicTimeSeries, 15m, 50K rate, 2KB rows, 300s bucket):

| Metric | Predicted GDT effect | Confidence |
|---|---:|---|
| `bytes_compacted_delta` | -2 to -8% vs baseline | medium |
| DB WAF Cassandra | -2 to -8% vs baseline | medium |
| DB WAF NVMe | -2 to -7% vs baseline | medium |
| Throughput | +0 to +10% vs baseline | medium-low |
| p99 read latency | **-5 to -20% vs baseline** | medium |
| SSTable count at end | within 10% of baseline | high |

If we want stronger gains: switch to KeyValue or KeyValue-with-TTL workload
(see `ucs-compaction-estimation.md` §6). Append-only time-series is the
weakest GDT case.
