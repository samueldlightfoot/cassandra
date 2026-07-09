# Task: land the easy-cass-stress value-generator fix + re-baseline

**Status:** root cause found + fix validated (14×, see findings.md). Fix is applied but
UNCOMMITTED on the rig (`feature/csv-latency`). This plan is the clean-context follow-up.

## Why this matters
The TPC PoC baseline (`tasks/tpc-migration-planning/phase-4-poc/poc-criteria.md` §8,
`baseline_v1`) was captured with the BROKEN tool → every throughput number is
load-generator-limited (Cassandra was idle), so it's **invalid for any throughput claim**.
Latency-at-fixed-rate cells may survive but must be re-checked. The fix unblocks measuring
Cassandra's real throughput.

## Phase 1 — land the fix properly
- [ ] Apply the fix (the-fix.diff) to the user's fork `~/repos/fork/cassandra-easy-stress`.
      NOTE branch skew: rig runs `feature/csv-latency`, local is `feature/mixed-ttl-workload`.
      Put it on the branch(es) that matter; commit with a clear message.
- [ ] **Thread-safety check:** confirm one shared `RandomStringGenerator` is safe across the 32
      worker threads (commons-text default uses ThreadLocalRandom → safe; verify no shared
      mutable state; a quick concurrent-generate test or doc cite suffices).
- [ ] **Audit sibling generators** for the same per-call construction anti-pattern:
      `generators/functions/{Book,FirstName,LastName,USCities,Gaussian}.kt` — do any rebuild
      heavy objects in getText()/getInt()? Fix uniformly.
- [ ] Consider upstreaming to easy-cass-stress (user said "we can contribute if broken").

## Phase 2 — re-establish the load-gen ceiling (options 1 then 2, per user)
- [ ] With the fixed tool, find how far load scales and whether Cassandra SATURATES
      (MutationStage Active→ concurrent_writes=32 with Pending building, and/or Cassandra fence
      cores ~100%). Sweep: 1 → N processes on client cores 8–11; watch client vs Cassandra CPU.
- [ ] If client cores (8–11) cap before Cassandra saturates: **option 2 — more client cores.**
      Re-pin Cassandra smaller (e.g. 0–5) and client wider (6–11), measure. Trade-off recorded
      in parent hurdles.md A16: fewer Cassandra cores = less TPC-representative; decide with data.
- [ ] Only if neither saturates Cassandra on the 12-core box → revisit an off-box load gen.
      (Expectation from findings: one fixed process already gets Cassandra to 61%, so ~2 procs
      or a modest core shift should saturate it — no new box likely needed.)

## Phase 3 — RE-RUN the TPC baseline with the fixed tool
- [ ] Re-capture `baseline_v1` throughput curves (parent `baseline_driver.sh`, rate-ladder) with
      the fixed tool at a load level where Cassandra is actually the bottleneck. Update
      poc-criteria.md §8; supersede the load-gen-limited numbers.
- [ ] Re-confirm the read/write delta and the write-path characterization now that writes can be
      driven hard (the earlier "write path serialization" was retracted as load-gen-limited —
      re-examine whether a real server-side write ceiling exists once the client can push).
- [ ] This closes Phase 4.1 for real → proceed to I0/I1.

## Pointers
- Full investigation + measurements: `findings.md` (this dir). The fix: `the-fix.diff`.
- Parent hurdle log (A1–A16, incl. the A16 corrections): `../tpc-migration-planning/phase-4-poc/hurdles.md`.
- Rig facts + stress-tool gotchas: memory `feedback_easy_cass_stress_scripted_run_gotchas`,
  `feedback_easy_cass_stress_hdr_semantics`, and `../tpc-migration-planning/runbook.md`.
- Load-test probe on rig: `/data/tpc-poc/lt.sh`.
