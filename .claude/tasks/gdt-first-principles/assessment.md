# First-principles assessment of the GDT investigation

Written 2026-05-25 after Phase 2 attempt 2 produced an apparent 33% compaction reduction that, on inspection, was just deferred work (12 extra SSTables left on disk vs baseline).

## 1. What the paper actually claims

Lee, Ziegler, Leis, *How to Write to SSDs*, PVLDB Vol. 19 No. 7 (2026), §4 ("GDT — Group Deathtime"):

- **Mechanism**: predict an invalidation/deathtime for each *page* (LeanStore-native unit) at write time. Group pages with similar predicted deathtimes together when writing to the SSD.
- **Why it works**: when a group of co-located pages all expires around the same time, the storage layer can reclaim the entire region without rewriting "survivor" pages from the middle of it. Reduces compaction read+write amplification.
- **Headline number**: **~5% DB-side WAF reduction** on LeanStore running YCSB-A (a 50/50 update-heavy workload). Not massive — the SSD-level NoWA mechanism in the same paper produces much bigger wins (~40%).
- **Baseline being compared against**: LeanStore's normal page-replacement, which has **no deathtime awareness at all** (picks victims by buffer-pool clock + size). So GDT's 5% is the gap between *no* deathtime info and *learned* deathtime info.

## 2. The two distinct benefits GDT could theoretically provide

| | mechanism | how it reduces work |
|---|---|---|
| **Benefit A — wholesale drop** | Class-pure SSTables of short-TTL data become entirely-expired at the same moment. | Cassandra can drop the whole SSTable at the next expired-SSTable check, with **zero compaction cost**. |
| **Benefit B — avoid cross-class rewrite** | Long-TTL data sits in its own bucket and doesn't get co-compacted with hot/short-TTL data that's being churned. | Cold data isn't repeatedly rewritten during hot data's churn cycle → fewer reads + writes per cold byte. |

These are independent. A workload could trigger one, both, or neither.

## 3. Does Cassandra UCS have these mechanisms at all?

### Benefit A — wholesale drop

**Yes, in principle.** Cassandra's `UnifiedCompactionStrategy` (like the legacy strategies) has an "expired SSTable check" that periodically scans for SSTables whose `maxLocalDeletionTime` is in the past, and drops them without compaction.

**Requirements:**
- Every row in the SSTable must be TTL-expired (`maxLocalDeletionTime < now`).
- `gc_grace_seconds` must have elapsed since expiry (we set it to 0).
- The expired-SSTable-check must actually run before the run ends.
- The check defaults to **`expired_sstable_check_frequency_seconds = 600` (10 min)**. On a 15-min run, this fires at most once. **If it never fires, Benefit A is silently impossible** — and we have ZERO "fully expired SSTable dropped" log lines in our Phase 2 run, which is consistent with the check never firing OR with no SSTable qualifying.

### Benefit B — avoid cross-class rewrite

**Probably already present in baseline UCS.** This was the conclusion of `.claude/tasks/gdt-ucs/audit-why-no-signal.md`: UCS sorts each bucket by `maxTimestampDescending` and picks the T oldest SSTables. So it tends to compact **time-coherent** input groups, not "old + new + middle-aged" mixtures. Adding TTL-aware classification on top doesn't give UCS new information for this case — it gives a different *partitioning* of the same time-ordered SSTables.

**Caveat from Phase 2 attempt 2**: gdt did fire 25% fewer compactions and produced slightly smaller per-compaction outputs. This is consistent with TTL-aware bucketing genuinely splitting SSTables across more buckets — but it's also consistent with simply deferring work that would otherwise have happened. The 12-SSTable-higher steady-state proves it was deferral, at least within our 15-min window.

## 4. Have we exercised these mechanisms in our test?

| requirement | status | notes |
|---|---|---|
| Class-pure SSTables on flush | **Partially.** With phaseSeconds=300 and ~46s flushes, ~85% of flushes are class-pure; ~15% are boundary-mixed. | Sufficient in principle. |
| Short-TTL chosen so expiry happens during run | **Yes.** TTL=300s on class 0; phase-0 writes expire by t=600s (well within the 900s run). | Correct. |
| gc_grace_seconds = 0 | **Yes.** Set in the workload's CREATE TABLE. | Correct. |
| Expired-SSTable check actually runs | **UNKNOWN — never verified.** Default is 10-min interval; we never overrode it, never confirmed it fired, never grepped for the check firing in the log. | **THIS IS THE GAP.** |
| Run long enough to reach a steady-state cycle (so deferred work shows up) | **NO.** 15-min run captures one compaction generation, not enough for the queue to drain or for cycles to even out. | Critical methodology gap. |
| Measure work-to-steady-state, not work-in-window | **NO.** Our headline `bytes_compacted_delta` is in-window. Deferred compactions don't appear in it. | Critical metric gap. |

## 5. Have we measured the right thing?

We measured `bytes_compacted_delta` over the 15-min workload window. **This is the wrong metric** because:

- It counts work done *during* the window only.
- Work that's been deferred (still pending after `flush_and_drain`) isn't counted.
- A strategy that defers 30% of compactions appears 30% "better" until the queue drains.
- We learned this by observing gdt has 12 more SSTables on disk despite "compacting less".

**What we should measure instead** (one or more):

1. **Total bytes written for the same final database state** — run until both conditions reach an SSTable count threshold (e.g. "compacted to ≤ N L0 SSTables"), measure cumulative bytes_compacted.
2. **Bytes-dropped via expiry** — the direct measurement of Benefit A. Specifically: count "fully expired SSTable dropped" events × their pre-drop size, sum to a single number.
3. **Steady-state WAF** — run for ≥ 2× a "compaction generation period", measure WAF over the back half once the system has stabilized.
4. **Disk-size delta normalised to "useful bytes"** — if both conditions end with 45 GB on disk but baseline had to write 40 GB to compactions to get there and gdt only 25 GB, gdt is genuinely doing less work *only if the on-disk shape is comparable*. If gdt has 12 extra small SSTables that need future compacting, the work is the same — just queued differently.

## 6. The Phase 2 attempt 2 result, honestly read

| signal | value | what it actually means |
|---|---:|---|
| bytes_compacted gdt vs baseline | −32.8% | **Deferred 12 GB**, did not save it. Headline is misleading. |
| DB WAF Cassandra | −9.4% | Same — looks better in-window because queue is fuller. |
| Final SSTable count | gdt 61 vs baseline 49 | gdt did NOT reach the same steady state — 12 SSTables of deferred work outstanding. |
| Live disk usage | gdt 45.14 vs baseline 45.02 GB | Essentially identical → zero data was wholesale-dropped via expiry. **Benefit A did not trigger.** |
| Compaction count | gdt 24 vs baseline 32 | gdt fired 8 fewer compactions. Same direction as deferral; could also be class-pure-bucketing splitting work into too-small buckets. |
| "Fully expired SSTable" log events | 0 | Confirms Benefit A did not trigger. Either expiry check didn't fire (10-min default interval) or no SSTable qualified. |
| p99 read | gdt 341 vs baseline 252 ms | +35%. Could be noise (baseline p99 has run-to-run drift of ~3-30% in our runs). Or could be reflecting a fuller pending-compaction queue. Cannot tell from one run. |

**Net**: Phase 2 attempt 2 produced no measurable evidence of either Benefit A or Benefit B. The apparent in-window savings is the deferral artifact. We cannot conclude that GDT-on-UCS provides any benefit from this run.

## 7. What we should do

Order matters here — each step blocks the next.

### Step 1 — Verify the expired-SSTable check can fire (~30 min)

The biggest unknown is whether UCS's expired-check mechanism even ran. Three concrete things to check:
1. Read UCS source for the expired-SSTable check — confirm it exists and what controls its frequency.
2. Set `expired_sstable_check_frequency_seconds = 60` (or similar low value) in the test's cassandra.yaml override.
3. Add an `INFO`-level log to wherever the check fires (or at minimum, look for an existing DEBUG line that we can elevate). Without observability we can't tell deferral from drop.

### Step 2 — Pick a metric that actually measures total work (~30 min)

Replace the in-window `bytes_compacted_delta` headline with one of the options in §5. The "drain to ≤ N SSTables" approach is the most operationally clear: run the workload, stop writes, force compactions to finish, THEN measure cumulative bytes_compacted. Compare conditions on the same end-state.

### Step 3 — Construct a workload that should genuinely trigger Benefit A (~30 min)

For wholesale drops to actually happen, we need class-pure SSTables AND those SSTables' content to all expire. Right now:
- ~15% of SSTables are boundary-mixed and won't expire (they contain long-TTL rows)
- The 85% class-pure short-TTL SSTables SHOULD expire — but only the check firing on a fully-expired SSTable can drop them.

Mitigations to consider:
- Pre-flush at phase boundary (`nodetool flush` via the workload) → 100% class-pure flushes.
- Workload alternates write phases with NO-WRITE silent phases so memtables can flush cleanly.
- Reduce numClasses to 2 (already doing this) and increase phaseSeconds further so phase-spillover is a smaller fraction.

### Step 4 — Re-bench properly and re-evaluate (~90 min wall: a longer run)

With (1) + (2) + (3), run a longer experiment (e.g., 30-min workload + 10-min drain) and measure properly.

### Step 5 — If Step 4 still shows no benefit, the conclusion writes itself

The honest negative result is: *on UCS with realistic workloads, the GDT classifier mechanism either degenerates into baseline (because UCS is already deathtime-aware) or defers rather than reduces compaction work. The wholesale-drop pathway requires conditions that are difficult to construct organically.*

That's a publishable finding. But we owe it the proper experimental setup first.

## 8. What about previous Run #001 / #002 / #003 documents

**All of them are invalid as GDT-vs-baseline measurements** because the JAR was stale and both conditions ran base UCS. The observations about run-to-run variance, the operational lessons about cleanup, and the workload calibration are all still valid — those are properties of the harness and rig, not the GDT mechanism. The conclusions specifically about "GDT showing no signal" are noise: those runs literally had no GDT to show signal from.

The `audit-why-no-signal.md` conclusion that "UCS already does deathtime-coherent selection via maxTimestampDescending" is still true (it's from reading the source, not from the broken runs). But its claim that this makes our GDT classifier redundant is unproven — we haven't run a real comparison yet.
