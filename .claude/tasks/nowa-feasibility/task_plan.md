# NoWA feasibility on Cassandra — task plan

Companion to `findings.md` (technical delta) and `../gdt-first-principles/recommendation.md` (the broader strategic context this branches off from).

## Decision

Whether to pursue "NoWA-style write-pattern optimization" in Cassandra after the GDT investigation closed (Run #004, deferral-not-savings). The hook is: paper §5.4's headline gain is **SSD WAF 1.96 → 1.07 (−45%) and +11% throughput on top of an already-optimized engine** — much larger than GDT's ~5% — but only achievable on out-of-place engines with direct device control.

Re-evaluation prompted by the observation that **Cassandra already has DIO compaction reads/writes**, which removes the biggest architectural blocker I originally cited (page-cache reordering). With that piece in place, the delta to a NoWA-capable Cassandra fork shrinks from "rewrite the storage engine" to "extend UCS + add a writer scheduler + add compensation-write logic to victim selection".

## Gates before any implementation work starts

Each gate is cheap (≤ 3 days). Don't proceed past a failed gate.

- [ ] **Gate 0 — drive availability**. Confirm a Hetzner SKU exists that exposes the OCP physical-write counter. Without it, the whole investigation is unmeasurable. See `findings.md` §6 for the agent-research result on which Hetzner DC NVMe drives qualify. If no SKU qualifies → stop, document, archive.
- [ ] **Gate 1 — current SSD WAF**. On the upgraded rig, measure stock-Cassandra SSD WAF (OCP `Physical Media Units Written` / `data_units_written`) under a representative bench. If ≤ 1.2 → LSM is already SSD-friendly, no headroom, stop. If ≥ 1.5 → headroom exists, proceed. The 1.2–1.5 band is "marginal — decide based on Gate 2".
- [ ] **Gate 2 — §5.3 zone-size sweep on the target drive**. Vary SSTable size from 256 MB → 32 GB (via UCS config), measure SSD WAF at each. The zone size at which SSD WAF first reaches 1 is the inferred RU size. If the result is in the 4–8 GB range (paper's commodity result) → reasonable. If "WAF never reaches 1" → drive's internal placement is anti-aligned with our writes, NoWA-style work won't help much.

## Phases (only execute after all gates pass)

### Phase A — §5.3 alignment alone (~2 weeks)
The cheapest portion of NoWA, isolatable, possibly recovers half the gain. Builds nothing on top of the GDT branch.

- [ ] Modify UCS to target the discovered RU size as the lower-bound SSTable output size (currently UCS has upper bounds + bucket-based sizing, no lower-bound discipline).
- [ ] Preallocate SSTable data files with `fallocate(FALLOC_FL_KEEP_SIZE)` at target size before write, so the FS commits to a single contiguous extent. (Verify with `filefrag -v`.)
- [ ] Handle small-flush case: either coalesce flushes into one RU-sized output (introduces flush latency) or accept that L0 lives outside the NoWA regime until first compaction promotes it.
- [ ] Bench with OCP measurement. Compare to Gate-1 baseline.
- [ ] Decision point: if Phase A captures most of the available SSD-WAF headroom (e.g. drops from 1.7 → 1.2), Phases B–C are not worth it.

### Phase B — concurrent-writer cap + open-zone discipline (~1 week)
NoWA's first rule. Constrains `concurrent_compactors + memtable_flush_writers` to a global cap and defers opening new writers until current ones finish.

- [ ] Global semaphore across flush + compaction SSTable writers, sized to `max_open × target_size = N × RU_size`.
- [ ] Scheduler change: new compaction/flush starts wait until in-flight SSTable writers finish (or hit RU-multiple boundaries). Carefully — this can cause write stalls. Likely needs a back-pressure path back to memtable allocation.
- [ ] Bench. Compare to Phase A end-state.

### Phase C — compensation writes in UCS victim selection (~1–2 weeks)
The novel mechanism. Builds on the existing `DeathtimeClassifier`.

- [ ] Cheap version first: extend UCS victim selection scoring to prefer compaction inputs that produce balanced invalidation across the current active group. Picks a slightly less optimal set rather than the optimal-but-imbalanced one. Zero extra writes.
- [ ] Paper-faithful version (only if cheap version is insufficient): when no balanced set exists, issue a compensation compaction that re-packs an underrepresented SSTable to a fresh location before continuing.
- [ ] Bench. Compare to Phase B end-state.

### Phase D — SSD-iq for timing (~1 week, OCP-gated)
The compensation writes only pay off if they fire before SSD GC. Needs a model that watches OCP counters over time and predicts when SSD free-block pool will hit the GC threshold.

- [ ] Sample OCP `Physical Media Units Written` periodically, build a free-block-pool estimator using assumed OP space.
- [ ] Wire the estimator into Phase C's compensation-write trigger.

### Phase E — bench + write-up (~1–2 weeks)
- [ ] Replicated runs with proper noise band (≥ 3 replicates per condition, per `gdt-ucs/operational-lessons.md`).
- [ ] Single coherent write-up: paper claim, our applicability analysis, our results, honest comparison.

## Effort summary

| phase | dev effort | OCP required to validate? |
|---|---|---|
| Gates 0–2 | ~5 days | Yes (Gate 1, Gate 2) |
| Phase A — alignment | 2 wk | Yes |
| Phase B — writer cap | 1 wk | Yes |
| Phase C — compensation writes | 1–2 wk | No for impl; yes for validation |
| Phase D — SSD-iq | 1 wk | Yes |
| Phase E — bench + write-up | 1–2 wk | Yes |
| **total to faithful NoWA** | **6–8 weeks** + rig upgrade lead time | OCP required throughout |
| total if Phase A is sufficient | 3 weeks + rig | OCP required |

Down from my earlier "2–3 months" estimate because DIO is already in place.

## Operational changes required on the rig

- [ ] Break RAID-1 on the bench drive — NoWA assumes you know which physical drive a write hit; RAID is a black box that may double writes asymmetrically.
- [ ] Move commitlog to a separate device (or accept it as measurement noise).
- [ ] Verify ext4/xfs preserves the fallocated extents end-to-end via `filefrag`.

## Explicit non-goals

- No FDP placement-hint implementation in this branch — that's a separate (smaller) path if FDP-capable drives are available. See `findings.md` §7.
- No port to LeanStore — the paper already did that; the point is to test whether the mechanism transfers to LSM.
- No attempt at SSD-side firmware work.

## Review section

To be filled after Phase E or earlier-phase decision-to-stop.
