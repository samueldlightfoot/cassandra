# GDT-aware UCS — pivot from FDP placement hints

## Goal

Validate that grouping compaction outputs by **deathtime** (GDT, per Lee et al.
2026 §4) reduces DB-side write amplification and improves throughput / p99 on
Cassandra UCS, **without** requiring FDP hardware. Ship the smallest viable
implementation that produces credible numbers. Decide go/no-go based on
first-measurement results before any production-hardening work.

## Why this, and why now

- **FDP hardware blocker.** The rig (`65.108.227.158`) has two consumer
  Samsung MZVL2512HCJQ in RAID-1 — no FDP-capable device, no spare to wipe.
  See `.claude/tasks/fdp-poc/` for the FDP plan that's now blocked.
- **GDT is the salvageable insight.** Lee et al. report GDT alone (no
  NoWA, no FDP) gives DB WAF `0.62 → 0.59` and OPS `380K → 458K` on YCSB-A
  in their LeanStore B-tree implementation.
- **UCS does this only partially today.** Level structure approximates
  deathtime; within a level, bucketing is by token-range overlap only.
  See `.claude/tasks/fdp-poc/ucs-investigation-findings.md` Q4.
- **No persistence-layer rewrite required.** This is a UCS-internal change.
- **Reuses the deathtime classifier we already designed for FDP** — the
  consumer changes (UCS bucketing instead of `fcntl(F_SET_RW_HINT)`), the
  classifier doesn't.
- **10% on a 20-year-old production storage engine is shippable.** Even if
  the win is modest on YCSB-A, time-series workloads should show larger
  deltas — that's the headline workload.

## Expected results (predictive read, low confidence)

| Workload | DB WAF Δ | OPS Δ | p99 read Δ |
|---|---|---|---|
| YCSB-A (KV, 50/50, zipf 0.8) | 5–15% reduction | 5–20% | 10–30% |
| Time-series (TTL-heavy) | 20–50% reduction | minor | 20–50% |
| Insert-only | <5% | negligible | negligible |
| Tombstone-heavy | 10–25% | 5–15% | 15–35% |

**Headline workload: time-series.** Biggest expected win, cleanest signal,
TWCS gives a natural performance ceiling to compare against.

## Optimisations to find time-to-first-number

All of these are speed optimisations only — production hardening lives in a
later slice.

- **Hardcoded simple classifier.** Bucket SSTables by
  `floor(maxTimestamp / window_size)`. Skip the per-shard tracking and the
  proper-arrival-position work flagged in UCS findings Q4. We can revisit
  if the simple version's signal is unclear.
- **System property `cassandra.gdt.enabled` to toggle.** One build, two run
  modes. No separate `gdt-on` Cassandra binary.
- **Skip metrics MBeans for the first round.** Use logger DEBUG lines plus
  `nodetool tablestats` deltas to compute DB WAF post-run.
- **Skip the fdp-poc-harness Investigation rewrite for now.** Drive
  `easy-cass-stress` directly with shell scripts; let the harness work wait
  until we know GDT is worth productising.
- **No Jolokia.** Sample `CompactionMetrics.bytesCompacted` via `nodetool
  info -- compaction-stats-history` or whichever subcommand exists.

## Phases (sequential — earliest phases unblock later ones)

### Phase 1 — Minimal `DeathtimeClassifier` (~2–3 days)
- [ ] New class `org.apache.cassandra.db.compaction.unified.DeathtimeClassifier`
- [ ] Method: `int classify(SSTableReader rdr, int level, Controller c)`
      returning a deathtime bucket id (simple formula:
      `floor(rdr.maxTimestamp() / window_size)` with `window_size` derived
      from level + scaling parameter)
- [ ] System property `cassandra.gdt.enabled` (default false), read once
      in the strategy constructor
- [ ] Unit test against synthetic SSTableReader stubs

### Phase 2 — UCS bucketing hook (~2 days)
- [ ] Identify the insertion point in
      `UnifiedCompactionStrategy.formLevels` / `chooseCompactionPick` —
      already mapped in UCS findings doc
- [ ] When GDT enabled: partition the candidate set by
      `DeathtimeClassifier.classify` *before* `Overlaps.constructOverlapSets`
- [ ] Result: each compaction bucket contains SSTables of similar deathtime
- [ ] Logger DEBUG: per-bucket classifier histogram per compaction pick
- [ ] Existing UCS tests still pass with GDT off (zero behaviour change)

### Phase 3 — Build + rig prep (~1 day)
- [ ] Build the GDT-enabled Cassandra fork (`fdp-poc` branch, GDT
      changes committed as new commits on top)
- [ ] `scp` the build to the rig
- [ ] Install Python 3.11 (deadsnakes) or relax `cassandra-agent-harness`
      to 3.10 — but harness is optional for this slice, decide cheaply
- [ ] Confirm `easy-cass-stress` (at `~/repos/easy-cass-stress`) runs the
      `BasicTimeSeries` workload against a freshly-started Cassandra
- [ ] Document the exact build SHA in the run notes

### Phase 4 — Baseline run: UCS T4, GDT off (~0.5–1 day)
- [ ] Workload: BasicTimeSeries, 1 hour steady state, fixed
      `partitions`, `threads`, `rate`
- [ ] Pre: `nodetool tablestats` snapshot, `bytesCompacted` snapshot
- [ ] Post: same snapshots
- [ ] Capture: ops, p99 read, p99 write, latency CDF (from parquet)
- [ ] Save artifacts under `~/runs/gdt-ucs/<run-uuid>/baseline/`

### Phase 5 — Treatment run: UCS T4, GDT on (~0.5–1 day)
- [ ] Same workload, same duration, `-Dcassandra.gdt.enabled=true`
- [ ] Same captures
- [ ] Save under `~/runs/gdt-ucs/<run-uuid>/gdt/`

### Phase 6 — Ceiling run: TWCS (~0.5–1 day)
- [ ] Same workload, change compaction strategy to
      `TimeWindowCompactionStrategy` with sensible window
- [ ] Same captures
- [ ] Save under `~/runs/gdt-ucs/<run-uuid>/twcs/`

### Phase 7 — Compare + decide (~1 day)
- [ ] Compute DB WAF for each: `(compacted_bytes + flushed_bytes) / user_bytes`
- [ ] Tabulate: OPS Δ, p99 Δ, DB WAF Δ vs baseline; gap to TWCS ceiling
- [ ] Decision tree:
  - GDT-on within 10% of TWCS on the time-series workload → **commit**, run
    YCSB-A + tombstone-heavy workloads next
  - GDT-on shows real but modest improvement → spec a v2 classifier with
    TTL-awareness, re-test
  - GDT-on shows no improvement or regression → abort, write up what we
    learned, return to FDP-when-hardware-available plan

## Phases NOT in this slice (deferred until Phase 7 says go)

- TTL-aware classifier (use TTL when present, fall back to maxTimestamp)
- Per-shard arrival tracking (UCS findings Q4)
- Per-life-class metrics in `CompactionMetrics`
- Configuration parsing beyond the simple system property
- YCSB-A + tombstone-heavy validation runs
- Pivoting `fdp-poc-harness` repo to a `gdt-poc-harness` Investigation
- Blog post draft
- JIRA / CEP filing for upstream

## Critical path / fastest schedule

Assuming uninterrupted: Phases 1+2 are blocking (≈5 days). Phase 3 unblocks
in parallel with Phase 1+2 from day 2. Phases 4+5+6 are ≈3 days serial on
the rig (the runs themselves are long; can pipeline phases 4 and 5 if we
have a second baseline rig snapshot to restore).

**Target: first comparison numbers in 8–10 working days.**

## Open decisions

- **Window size formula for the simple classifier.** Default:
  `window_size = base_window × (fan_factor ^ level)` where `base_window` is
  ~1 hour expressed in microseconds. Reconsider after first run if buckets
  are too coarse / too fine.
- **TWCS window for the ceiling run.** 1 hour is conservative; 10 minutes
  may be more comparable to the GDT window. Decide at Phase 6 start.
- **DB-WAF measurement granularity.** `bytesCompacted` is a counter; we
  read it at run start/end. Risk: counter resets if Cassandra restarts
  mid-run. Mitigation: explicit restart logging, drop runs with restarts.
- **Should the harness/Investigation pivot happen as part of this slice
  or after?** Plan says after. If we end up running 3+ comparison passes
  it'll be obvious we need it; defer until then.

## Review

(Filled in after Phase 7.)
