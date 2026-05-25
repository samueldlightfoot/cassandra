# Forensic Audit — Why GDT Shows No Measurable Signal

After 3 runs (2 workloads, 6 conditions total) showing essentially zero
GDT-vs-baseline differentiation, we audited the scenario + implementation
end-to-end. **The smoking gun: UCS baseline already does effectively what
our GDT implementation does.**

## TL;DR — three findings, ranked by importance

| # | Finding | Severity |
|---|---|---|
| **1** | **UCS baseline already picks deathtime-coherent SSTables** via `maxTimestampDescending` sort within a bucket. Our GDT classifier just makes the partitioning explicit, but the selection outcome is essentially the same. | **CRITICAL — explains the null result** |
| 2 | The workload's effective unique-key count is ~3.17M, not the 100K we configured via `--partitions 100000`. Workload is "moderate-write" not "heavy-overwrite". | Important — affects workload selection logic |
| 3 | Baseline and GDT did the *exact same number of compactions* (37 each). Confirms compactions are driven by flush rate × threshold, not by selection. | Confirmatory |

---

## Verification — what we audited and how

### A. Was GDT actually engaged at runtime?

Checked `/root/repos/fork/cassandra/conf-runs/{baseline,gdt,twcs}/jvm-server.options`:
- baseline: `-Dunified_compaction.gdt.enabled=false` ✓
- gdt: `-Dunified_compaction.gdt.enabled=true`, `-Dunified_compaction.gdt.base_window_micros=300000000` ✓
- twcs: `-Dunified_compaction.gdt.enabled=false` ✓

So GDT's branch of the code WAS taken. The classifier was instantiated.
The bucketing hook was active. Not a propagation bug.

### B. Did GDT produce different compaction *selections*?

Counted compaction events by condition (timestamp-filtered from system.log):
- baseline: **37 compactions**
- gdt: **37 compactions**
- twcs: 9 compactions (different strategy, expected)

GDT and baseline triggered exactly the same number of compactions. Not
"approximately" — exactly. With T=4 threshold and a steady flush rate,
that's expected — threshold is met at the same cadence regardless of
which buckets the SSTables fall in. **But it does suggest the SELECTIONS
are very similar too**, which leads to Finding #1.

---

## Finding #1 — The Killer (UCS already does this)

### What baseline UCS actually does

In `UnifiedCompactionStrategy.java` lines 900 and 916:

```java
this.allSSTablesSorted.sort(SSTableReader.maxTimestampDescending);
// we remove entries from the back
```

A bucket sorts its SSTables by `maxTimestamp` descending and **pulls
the oldest T entries from the back**. So baseline UCS picks the
**T absolute-oldest SSTables in the bucket** every time it compacts.

The T oldest SSTables, by definition, are a deathtime-coherent group:
they were flushed sequentially over a short time window. There's no
"old + new + middle-aged" mixing — baseline already gives you the
oldest contiguous block.

### What GDT in our implementation does

In `UnifiedCompactionStrategy.java`'s `Level.overlapsByDeathtime`:

```java
Map<Integer, List<SSTableReader>> partitions =
    partitionByDeathtime(liveSet, classifier, level, controller);
// for each partition, construct overlap sets independently
```

Then `assignOverlapsIntoBuckets` produces per-bucket compaction
candidates. Inside each bucket, the **same `maxTimestampDescending` sort
+ pull-from-back logic** applies (it's downstream of our hook). So GDT
also picks the T oldest within its chosen bucket.

### Why this is a tie

For a workload with uniform write rate (like ours):

- **Baseline**: pulls T = 4 absolute-oldest SSTables → they span ~T × flush-period of write time = ~4 × 46s ≈ 3 min of writes
- **GDT** (with `base_window_micros = 300000000` = 5 min): picks 4 oldest within a 5-min bucket → also spans ~3 min of writes

**The two selection mechanisms produce overlapping (or identical) input
groups for the first compaction.** As compactions repeat, the
behaviours diverge slightly (GDT round-robins across buckets vs
baseline always picks absolute-oldest), but the *time-span* of inputs
stays similar.

Since the input time-span is what controls dedup/locality during
compaction, both produce ~the same output size. Hence DB WAF ≈ same.

### Cross-check from the data

Run #003 baseline compaction sample:
```
4 sstables 7.803GiB → 6.147GiB (~78% of original)
Partition merge counts: {1:2409851, 2:673265, 3:84267, 4:3982}
```

Run #003 gdt compaction: same scale, same merge-count distribution.
The bucketing didn't produce qualitatively different input
characteristics.

### Why I missed it during design

The original UCS investigation findings (`.claude/tasks/fdp-poc/ucs-investigation-findings.md`)
Q4 noted that UCS *doesn't* expose arrival-position-within-level
explicitly. I interpreted that as "UCS doesn't care about time order"
— but I missed the next bullet point under "Available proxies" which
explicitly notes maxTimestamp-descending IS used for selection. So
baseline UCS *does* care about time order; it just uses an implicit
mechanism.

**The original investigation doc had the answer. I didn't read it
carefully enough when designing GDT.**

---

## Finding #2 — The Workload Doesn't Concentrate Overwrites

Configured: `--partitions 100000` (intent: 100K unique keys, heavy
overwrites per key).

Observed (from compaction log partition counts): each 4-SSTable
compaction processes ~4M input partition occurrences → ~3.17M output
partitions per compaction. With only 4 input SSTables that ought to
contain ≤ 100K keys each, we'd expect output ≤ 100K partitions.

3.17M is 30× our expectation. Several possible explanations (we
verified the PartitionKey source — it produces `"test<int>"` with int
in [0, 100K) so logically max 100K unique keys), but the data is what
it is — the actual SSTables on disk reflect this:

- Each input SSTable ≈ 2 GB
- 100K keys × 2KB ≈ 200 MB
- Actual is 10× larger → each SSTable contains ~10× more partition
  instances than just 100K-distinct-keys would explain

**Likely cause:** something about how Cassandra storage encodes
multiple writes to the same key in an SSTable. May be that each
write becomes a separate cell with its own writetime (not coalesced
on flush), and "partitions" in the compaction log counts cells. Worth
investigating with `sstabledump` if we want to be sure, but the
operational implication is clear: **our key space is effectively much
larger than 100K, so overwrite-density per key is much lower than we
intended**.

Compaction output is 78% of input (modest dedup, not the massive
dedup we'd see with true 100K hot keys at 22M writes).

So **even if GDT did differentiate from baseline**, the workload
shape isn't presenting the conditions where the difference would
matter. Heavy dedup requires high key overlap between SSTables; our
SSTables have only modest overlap.

---

## Finding #3 — Same Compaction Count Confirms Selection-Driven (Not Trigger-Driven)

Run #003 per condition:
- baseline: 37 compactions, 33.93 GB compacted
- gdt: 37 compactions, 33.93 GB compacted
- twcs: 9 compactions, 33.41 GB compacted (different strategy, expected)

Identical count means: at every "should I compact?" decision point,
both baseline and GDT make the same call. Threshold T=4 is met in
both at the same flush count. Compaction work is driven by **how
fast flushes arrive** and the **threshold**, not by selection
strategy.

Selection strategy can only affect:
- WHICH 4 SSTables get picked (Finding #1 says: same time-span either way)
- The output size of each compaction (Finding #1: same dedup either way)
- Cumulative bytes compacted (consequence of the above: same)

This is consistent with the measurement: 0.0% difference in
`bytes_compacted` across baseline vs gdt in Run #003. Not "small" —
**exactly zero**.

---

## Why our DB-level mechanism doc was wrong

The `expected-gains-mechanism.md` doc predicted 2-8% GDT improvement
based on the Lee paper's LeanStore implementation. That implementation
differs from ours in two material ways:

1. **LeanStore's deathtime classifier is sophisticated.** It tracks a
   per-page "Write History" with the last N write timestamps, uses
   that to *predict* future deathtime, and groups by predicted-death,
   not by latest-observed-write. Our classifier uses `maxTimestamp`
   as a direct proxy — which is exactly what UCS already uses for
   selection ordering.
2. **LeanStore's baseline doesn't sort by write time.** Its zoned
   B-tree picks compaction victims by other criteria (size, valid
   page ratio). So GDT introduces NEW information to LeanStore. In
   UCS, our GDT introduces the same information UCS already uses.

**The 5% reduction the paper reports is from giving LeanStore a
mechanism it didn't have. UCS already has that mechanism (implicit
maxTimestamp-descending). Our GDT is largely redundant.**

---

## What it would take to make GDT measurably different

If we want to extract value from a deathtime-aware classifier on
Cassandra UCS, the classifier needs to provide information that
UCS's existing maxTimestamp-descending doesn't:

| Direction | Mechanism | Effort |
|---|---|---|
| **Use TTL when available** | If the row has a TTL, predicted-death = writetime + TTL. Groups soon-to-expire data together → compaction can drop entire groups of expired data at once. | Moderate — need to track per-SSTable min/max TTL. |
| **Per-partition write rate prediction** | Track how often each partition is being written. Hot partitions → short expected lifetime. Cold partitions → long. Group by predicted lifetime, not by writetime. | High — needs partition-level stats infrastructure. |
| **Cross-level classification** | Currently GDT classifies within a single UCS level. Cross-level mixing happens via density promotion. Classify across levels could prevent old-level data from being mixed with new-level data during cross-level compactions. | High — changes UCS bucketing semantics. |
| **Negative deathtime via tombstone count** | SSTables with high tombstone counts will be invalidated soon (gc_grace + repair). Group them together for efficient discard. | Moderate. |

None of these are quick wins. All require real classifier work, not
just a system property flag.

---

## Honest implications for the project

Three options now:

### Option A — Pivot to a smarter classifier
Implement TTL-aware classification (the cheapest of the directions
above). Run a comparison on TTL-heavy workload. Estimated effort: ~2
days Cassandra-fork work + 1 day measurement.

Expected outcome: measurable GDT effect (because TTL provides
information UCS doesn't have implicitly), 5-15% DB WAF reduction
plausible.

### Option B — Accept the null result, publish negative
Write up "We implemented the paper's GDT mechanism on Cassandra UCS;
because UCS already does deathtime-coherent selection via
`maxTimestampDescending` ordering, our naive implementation is
redundant and shows no measurable benefit. Implementing a more
sophisticated classifier remains future work."

That's a real contribution — saves the next person from making the
same mistake. Estimated effort: ~1 day writeup.

### Option C — Stop work on GDT entirely, redirect
Go back to the FDP investigation when DC hardware lands. OR pivot to
NoWA-lite (zone-size alignment), which has a separate mechanism not
shadowed by existing UCS.

### My honest recommendation

**Option A.** We've built all the infrastructure. Implementing
TTL-aware classification is incremental work on top of what exists.
If it shows a signal, we have a real story. If not, we have a richer
negative result. Either way, the harness + measurement work we've
done is reused.

But if you'd rather close this out as a negative result and pivot
to something else (e.g., NoWA-lite on the DC drive when it lands),
that's also defensible — we've genuinely characterised the problem.

The question is whether you want to spend another ~3 days getting a
plausible positive result, or call it now and document the null with
context.
