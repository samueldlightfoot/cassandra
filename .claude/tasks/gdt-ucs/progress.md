# GDT-aware UCS — progress

## Session log

### 2026-05-24 — Pivot and plan

- FDP hardware blocker confirmed during rig survey (consumer Samsung SSDs in
  RAID-1, no spare device for `blkdiscard`).
- Read Lee et al. 2026 paper end-to-end; identified GDT as the salvageable
  insight that doesn't need FDP or NoWA.
- Drafted this task plan with an explicit time-to-first-number focus.
- No code yet.

## Phase status

- **Phase 1 (DeathtimeClassifier): DONE.** 9/9 unit tests passing; full
  ControllerTest suite green. Files: `DeathtimeClassifier.java` (new),
  `DeathtimeClassifierTest.java` (new), `CassandraRelevantProperties.java`
  (+2 lines: `UCS_GDT_ENABLED`, `UCS_GDT_BASE_WINDOW_MICROS`),
  `Controller.java` (+30 lines: static reads, instance field, accessor),
  `ControllerTest.java` (+18 lines: null-when-disabled smoke test).
- **Phase 2 (UCS bucketing hook): DONE.** GDT-on path partitions
  `Level.sstables` by classifier output before forming overlap sets;
  GDT-off path unchanged. Tests: 3 new partition tests pass; existing
  `testGetBucketsSameWUniqueArena` / `DifferentWs` / `BucketSelectionSimple`
  all still pass (regression check). Files: `UnifiedCompactionStrategy.java`
  (+~60 lines: `overlapsByDeathtime`, `partitionByDeathtime`,
  `partitionSizes`, plus the dispatch in `Level.getBuckets`),
  `UnifiedCompactionStrategyTest.java` (+~55 lines of partition tests).
- **Phase 3 (rig driver): SUPERSEDED by `gdt-poc-harness` repo.**
  Initial bash version exists at `.claude/tasks/gdt-ucs/scripts/` for
  reference, but the canonical driver is now `gdt-poc run` from
  https://github.com/samueldlightfoot/gdt-poc-harness, built on
  `cassandra-agent-harness`. User directive: "ensure we use the agent
  harness when it comes to it" — bash version retained as fallback only.
- Phase 4 (baseline run): blocked on rig deploy (sync fork to rig, build)
- Phase 5 (GDT-on run): blocked on rig deploy
- Phase 6 (TWCS ceiling run): blocked on rig deploy
- Phase 7 (compare + decide): blocked on Phases 4–6

## Library additions to support the harness pivot

Cassandra-agent-harness gained two new pieces to support GdtInvestigation:

- `capture/nodetool.py` — `nodetool_snapshot()` returning a Snapshot with
  parsed `bytes_compacted` + `bytes_disk_used` plus raw text. 5 new unit
  tests; 78/78 library tests passing.
- `Investigation.pre_workload_capture(condition)` hook — fires after the
  node is live, before workload starts. Required for "zero work done"
  baseline. Default empty impl, so existing `FdpInvestigation` was not
  affected (21/21 fdp-poc-harness tests still pass).

## Lessons surfaced

- **Cassandra ships JUnit 4.12.** `Assert.assertThrows` (4.13+) isn't
  available — use the classic try/fail/catch pattern in any new tests.
  Promoted to `.claude/tasks/lessons.md`.
- **Window-alignment in deathtime tests.** Picking arbitrary base timestamps
  for "should be in same bucket" assertions risks straddling a window
  boundary. Always pre-align the base to the largest window being tested.
  Caught one false-failing test in this slice.
