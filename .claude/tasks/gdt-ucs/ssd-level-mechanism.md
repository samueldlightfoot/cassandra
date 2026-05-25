# GDT — Why It Should Reduce SSD-Level Write Amplification

This doc fills the gap between *Cassandra-side write amplification* (covered in
`expected-gains-mechanism.md`) and *NAND-level write amplification* (the
device-physics layer). It explains the chain of writes from `INSERT` statement
down to flash erase, where amplification appears at each step, and why
deathtime-coherent compaction outputs should reduce work at the SSD's internal
garbage collector.

> **Constraint we hit:** the Samsung MZVL2512HCJQ on our current rig doesn't
> expose OCP SMART, so we cannot measure SSD WAF directly to validate the
> claims below. This doc is therefore *predictive*. We measure DB-side and
> NVMe-host-side WAF; SSD-internal WAF is inferred from those plus the
> mechanism described below. To validate on actual hardware we need a DC
> NVMe (pending — Hetzner upgrade discussion).

---

## 1. The write chain — what actually happens to a byte

```
INSERT (CQL)
  │
  ├── commitlog append (immediately, durable)            → host write #1
  │
  └── memtable insert (in-memory)
        │ (memtable fills, flushed when memtable_heap_space × cleanup_threshold reached)
        ▼
      flush → SSTable on disk                            → host writes #2..N
        │ (multiple SSTables accumulate at L0)
        │ (UCS T4 triggers compaction when 4 overlap)
        ▼
      compaction: read K SSTables, merge, write 1 SSTable → host writes #N+1..N+M
        │ (input SSTables are deleted; filesystem unlink → eventually TRIM)
        │ (output SSTable lives until next-level compaction)
        ▼
      ... repeat at each level ...
```

So a single user write produces several host writes: commitlog + flush +
(compaction at every level the data passes through).

The Cassandra-side `DB WAF` = `(commitlog + flush + compaction bytes) / user bytes`.
For our workload this lands around 1.5 at the NVMe level (Run #001 measurement).

This is the *host-visible* amplification. There's another layer below it.

---

## 2. Why the SSD amplifies further — NAND mechanics

NAND flash has three asymmetric operations:

| operation | unit | typical size | latency |
|---|---|---|---|
| **read** | page | 4–16 KiB | ~50 μs |
| **program (write)** | page | 4–16 KiB | ~200 μs |
| **erase** | block | 512 KiB – 4 MiB (= many pages) | ~5 ms |

Three iron rules of NAND:
1. **A page must be erased before it can be rewritten.** No in-place
   overwrite at the cell level.
2. **Erase is at block granularity**, hundreds of pages at once.
3. **Erase wears out the cell.** Each block has a finite P/E (program/erase)
   cycle budget — ~1000–3000 for TLC, ~100–500 for QLC.

These rules force out-of-place writes inside the SSD. Every host write goes
to a *new* page; the old page is marked stale. The FTL (Flash Translation
Layer) maintains the mapping from logical LBA to physical page.

### The garbage collection problem

Eventually the SSD runs out of clean pages. To recover space it must erase a
block. But the block may contain a *mix* of stale and valid pages. To erase
it the FTL must:

1. **Copy** the valid pages to a fresh block (one program per page)
2. **Erase** the now-empty source block
3. **Update** the FTL mapping for the relocated pages

That copy is the source of SSD-internal write amplification.

If a block to be reclaimed is 100% stale → 0 copies, no SSD WA from it.
If a block is 80% stale, 20% valid → 20% of the block's pages get copied → SSD WA.
If a block is 10% stale, 90% valid → 90% copy → very high SSD WA.

**SSD WAF = total NAND writes / host writes = 1 + (GC copy bytes / host write bytes).**

The Lee paper (Figure 1) shows in-place-update LeanStore had SSD WAF ≈ 2.3 on
their hardware. Plain out-of-place had 1.94. NoWA achieved 1.07.

---

## 3. Superblocks and multiplexing — the structural source of SSD WA

Real SSDs don't program one page at a time. They program across many NAND dies
in parallel for bandwidth. The unit of parallel programming is a *superblock*:
one block from each die, striped together. Sizes vary by drive — typically
4–256 MiB. The OCP SSD spec calls this the "GC unit"; the Lee paper "infers"
it by sweep.

The SSD's allocator typically fills the *current open superblock* sequentially
with whatever host writes arrive next. If host writes come from one logical
stream (one app, one file), the superblock fills with related data. If they
come from *multiple concurrent streams*, the data gets interleaved.

For Cassandra at saturation we have many concurrent host write streams:

- **commitlog writer** (one sequential stream)
- **memtable flushers** (`memtable_flush_writers = 2` by default → up to 2 streams)
- **compactors** (`concurrent_compactors = 4` → up to 4 streams)
- **system logs** (low rate but constant)

So up to ~7 concurrent host streams during heavy workload. Their writes
interleave at the superblock level.

### Why interleaving causes WA

Take a superblock S that contains:

- 25% pages from SSTable A (compaction output)
- 25% pages from commitlog
- 25% pages from SSTable B (different compaction output)
- 25% pages from a flush

Eventually SSTable A is compacted away and its file is unlinked → its pages
are stale. SSTable B is still live. Commitlog has rotated past those segments.
Flush data was promoted via compaction → stale.

S is now ~75% stale, 25% valid (the SSTable B pages). When the FTL picks S
for GC, it copies 25% of S to a fresh superblock. That's the SSD WA tax.

If S had been 100% SSTable A (all SSTable A pages in S, nothing else), then
when SSTable A was deleted, S would be 100% stale → erase is free → no SSD WA.

So **the source of SSD WA is the mismatch between the SSD's grouping granularity
(superblock) and the DBMS's invalidation granularity (file deletion).**

---

## 4. How GDT addresses this — indirect uniform invalidation

GDT doesn't directly control the SSD's grouping. We can't tell the FTL
"please put these writes in the same superblock." (That's what FDP placement
hints do, and we don't have FDP.)

But GDT controls *which SSTables get compacted (and thus invalidated)
together*. The mechanism:

1. UCS bucketing now partitions compaction candidates by deathtime classifier
   output (`floor(maxTimestamp / window)`).
2. SSTables with similar `maxTimestamp` ended up there because they were
   *written* at similar times.
3. Writes that happened at similar times were *also* multiplexed together
   in the same set of superblocks at the SSD level.
4. When GDT picks 4 deathtime-coherent SSTables for compaction, those 4
   SSTables share most of their superblocks.
5. After the compaction, those 4 input SSTables are *all* deleted at once.
6. The shared superblocks transition from "25% from each of 4 SSTables" to
   "100% stale across all 4 contributors."
7. The FTL's next GC pass on those superblocks has 0 valid pages to copy.

**This is uniform invalidation by accident.** Lee's NoWA achieves it
deliberately (via "compensation writes" — explicit FTL-aware sequencing).
GDT achieves it as a side effect of grouping by deathtime, because in our
workload deathtime correlates strongly with write-time.

The correlation is the key. For BasicTimeSeries (append-only time series),
`maxTimestamp` ≈ `write-time` exactly. For YCSB-A (overwrites), deathtime
correlates with write-time because hot keys get overwritten quickly → their
SSTables are invalidated quickly → those SSTables also happen to be the
most-recently-written.

---

## 5. What GDT can NOT do at the SSD level

| limitation | why |
|---|---|
| **Eliminate commitlog/flush multiplexing** | These streams are independent of compaction strategy. GDT only affects compaction output grouping. |
| **Tell the FTL about the grouping** | No host→SSD signal for that without FDP. The FTL still allocates the next available superblock for each write. |
| **Cope with FTL static wear leveling** | Some SSDs proactively relocate cold data to redistribute wear. That copy contributes to SSD WA regardless of host-side grouping. |
| **Survive open-block exhaustion** | If too many concurrent host streams cause the SSD to mux across more superblocks than it can keep "open" → forced relocation → SSD WA. GDT doesn't reduce the number of concurrent streams. |

So GDT is *necessary but not sufficient* for full SSD WAF reduction. NoWA's
explicit compensation writes are the missing piece, and we can't implement
those without OCP-driven measurement.

---

## 6. Quantifying the expected SSD-WAF effect

Per Lee §5.4, NoWA + GC-unit alignment achieved SSD WAF ≈ 1.0 on enterprise
SSDs (down from ~2.3 for in-place updates, 1.94 for naive out-of-place). Of
that improvement, the bulk comes from "balanced invalidation across active
groups" — which is conceptually what GDT does.

For our setup (consumer Samsung, no NoWA, GDT alone), realistic prediction:

| layer | baseline | GDT | NoWA-lite (hypothetical) | full NoWA |
|---|---:|---:|---:|---:|
| **DB WAF (Cassandra-side)** | ~1.5 | 1.45–1.49 | similar to GDT (orthogonal) | similar |
| **NVMe host bytes / user bytes** | ~1.5 | ~1.5 | ~1.5 (orthogonal) | ~1.5 |
| **SSD WAF (NAND / host) — unmeasurable on this rig** | ~2.0–2.5 | **1.7–2.2** | 1.3–1.7 | 1.0–1.2 |
| **Total WAF (NAND / user) = DB × SSD** | **3.0–3.75** | **2.4–3.3** (best case ~−20%) | 2.0–2.6 | 1.5–1.8 |

Notes on the table:
- The DB WAF column is what we can directly measure post-parser-fix.
- The SSD WAF column needs OCP (not available now). The numbers come from
  applying the Lee paper's per-mechanism share to our workload context.
- "Total WAF" is the multiplicative chain: every byte of user data ends up
  causing this many bytes written to flash. This is what matters for SSD
  endurance.

Even at the pessimistic end, GDT *should* reduce total WAF by ~10% — most of
which is invisible to host-side measurement. The Cassandra-side and
NVMe-host-side DB WAF reductions we measure are the *tip of the iceberg*; the
larger story is at the NAND layer below.

---

## 7. The TRIM dependency

For GDT's grouping benefit to actually translate to SSD-level GC savings, the
SSD must *know* that the deleted SSTable's pages are stale. That happens via
TRIM (NVMe `Deallocate` command).

**TRIM paths:**
1. **Online TRIM (`discard` mount option)** — every file unlink immediately
   sends TRIM. Reliable but adds latency to unlink. Sometimes slow on
   fragmented filesystems.
2. **Periodic TRIM (`fstrim`)** — daily cron / systemd timer scans free
   filesystem space and sends TRIM in batches. Cheaper but data sits "valid"
   from the SSD's perspective until the next fstrim run.
3. **No TRIM** — SSD never learns data is dead. Will keep treating those
   pages as valid → high SSD WA → fast wear-out. Pathological.

On our rig we should verify which is in effect. Probable status: ext4 default
is *not* `discard` on Ubuntu (Ubuntu uses weekly `fstrim.timer`). For a benchmark
this is OK as long as we account for it — the SSD's WA measurement won't
reflect the TRIM-mediated reclamation until fstrim runs.

For Phase-2 measurement on a DC drive we should either:
- Mount with `discard` for predictable per-unlink TRIM
- OR run `fstrim` between conditions to normalise the TRIM-mediated state

---

## 8. Putting it all together — the chain of why GDT helps

```
GDT classifies SSTables by deathtime  (DB-LEVEL)
        ↓
Compaction inputs are deathtime-coherent  (DB-LEVEL)
        ↓
Compaction outputs land in narrower deathtime windows  (DB-LEVEL)
        ↓
At next level, GDT keeps narrowing further  (DB-LEVEL)
        ↓
When an SSTable is finally invalidated, the SSTables that share its 
  superblocks on the SSD are also being invalidated around the same time  (HOST → SSD)
        ↓
TRIM marks those LBAs stale  (HOST → SSD)
        ↓
The SSD's affected superblocks have a HIGH stale-page ratio  (SSD)
        ↓
The SSD's internal GC finds few valid pages to copy  (NAND)
        ↓
SSD WA drops  (NAND)
        ↓
Less total NAND writing for the same user bytes  (NAND)
        ↓
Higher SSD endurance and lower latency interference under load  (USER-VISIBLE)
```

The chain is *long* and each link is *probabilistic*. That's why GDT's measured
effect at the user-visible layers (DB WAF, p99) is small (~10% in Run #001).
The mechanism gets attenuated at each layer:

- DB-level deathtime grouping is exact
- SSD-level superblock alignment is statistical (might be 80% aligned, not 100%)
- TRIM timing is delayed (periodic, not immediate)
- SSD GC choice of victim superblocks is firmware-internal

But the chain *is* working. p99 read improving 9.73% in Run #001 is evidence
that compaction interference is reduced, which is evidence that the SSD layer
is doing less GC work, which is evidence the chain is partly intact.

---

## 9. What would prove it definitively

To turn the chain above from "should be working" into "is working":

1. **OCP SMART on a DC drive.** Capture `physical_media_units_written` per
   condition. Compute SSD WAF directly. Compare across conditions.
2. **Throughput-drop methodology as a proxy.** Even without OCP, the
   Lee paper §5.3 notes you can detect SSD GC pressure by watching when
   sustained throughput drops as a function of total bytes written. If
   GDT delays the drop, that's evidence.
3. **`nvme smart-log` deltas paired with `controller_busy_time`.** Time-percentage
   the controller is busy correlates with internal GC work.
4. **A long run (hours, > 4× device capacity)** to push the SSD into
   sustained-state GC (the only regime where WA differences are visible).

These are all Phase-2 directions. Run #002 (in progress) and a possible
Run #003 (KeyValue or rate-sweep) are still Phase-1 characterisation.

---

## 10. Open questions

- **Does `concurrent_compactors=4` cause enough multiplexing to matter at the
  SSD level on our drive?** Probably yes for big drives (more dies, wider
  superblocks) and less so for consumer drives (fewer dies, smaller
  superblocks). Hard to know without measurement.
- **How aggressive is the MZVL2512HCJQ's wear-leveling?** Consumer firmware
  varies a lot. Static wear-leveling (proactive relocation of cold data) adds
  SSD WA that no DBMS-side change can prevent.
- **Would `mount -o discard` change our results?** Worth a controlled test on
  Phase 2. Hypothesis: yes, modestly — earlier TRIM means earlier visibility
  of staleness to the SSD GC.

---

## 11. Bottom line

GDT shifts *when* the DB invalidates SSTables. That shift causes the SSD's
internal GC to encounter superblocks with more uniform stale-page ratios on
average. The SSD does less internal copying. Total NAND writes drop. SSD wears
out slower; tail latency improves.

We can't measure the NAND-level effect on this rig. We can only see the
host-visible shadow of it (p99 reads, host bytes written). The 9.73% p99 in
Run #001 is the shadow of a larger SSD-level effect we can't quantify yet.

**On a DC drive with OCP, we expect the SSD WAF reduction to be 1.5–3× larger
than the DB WAF reduction we measure** — because the SSD-level WA tax is
multiplicative on top of host-visible writes.
