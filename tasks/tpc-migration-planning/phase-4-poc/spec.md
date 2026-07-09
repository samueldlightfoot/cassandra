# Phase 4 Spec — The TPC PoC (fork)

**Status:** Ready to execute once Phase 3's designs are chosen.
**Created 2026-07-07** when the program was reframed PoC-first (progress.md session 3):
the goal is a WORKING TPC Cassandra on this fork with measured performance; the CEP
(Phase 5) follows with those numbers in hand. Gates and benchmarks classify hurdles —
they do not kill the build.

---

## 1. Pinned decisions

### D8. PoC before CEP — but mergeable-shaped
No CEP draft, no dev@ socialization, before the PoC verdict exists. Fork-only work, BUT
each increment stays mergeable-shaped (flag-gated, self-contained, buildable in
isolation) so PoC patches convert to CEP patches without a rewrite. The 2016 lesson
(10528/10993 built PoCs that never landed) is survived by shape, not hope.

### D9. Success criteria are stated BEFORE the first increment lands
Pinned in 4.1, not adjudicated after the fact. Headline form: at throughput ≥ trunk,
**p99 ≤ trunk** on the rig's standard workloads (exact workloads and margins pinned in
4.1). The 10993 POC's failure mode — +15% throughput, 2× worse p99, discovered late —
is precisely what this decision exists to prevent.

### D10. Production freight is CEP-era, not PoC-era
Mixed-version clusters, JMX/metrics semantics compat, upgrade paths, polished operator
rollback: deferred to Phase 5 scope. PoC flags are cheap system properties. The PoC
correctness bar: existing unit tests + in-JVM dtests pass flag-on (in-JVM dtests run on
macOS; CCM multi-node needs the rig/Linux).

## 2. Sub-phases

### 4.1 Baseline + success criteria → `poc-criteria.md`
Capture the trunk baseline on the rig: easy-cass-stress standard workloads per the
runbook methodology (CPU fencing, governor recorded, no SIGTERM) — throughput,
p50/p99/p99.9, GC profile. Pin the workload set, the success margins (D9), and the
rebase cadence for the fork. Also pinned HERE, not in an increment spec:
expected-changes §8 item 5 (shard-thread CPU budget — accept oversubscription or
shrink pools flag-on), because it shapes the honesty of every subsequent A/B.
Also restate ../findings.md §5.1 in poc-criteria.md: the foreground-read caching model
(page-cache buffered ring vs DIO + expanded ChunkCache) is PROVISIONAL and performance
is the sovereign criterion — the criteria doc must name the A/B that settles it
(expected-changes §8 item 8) so no increment silently hard-wires the buffered assumption.
Acceptance: baseline numbers + criteria committed before any increment code exists.

### 4.2 Increment builds — I1 → I5, in order
Per increment: (a) implementation spec derived from the Phase 3 design, same
no-open-questions bar as Phases 0–2; (b) build behind its flag; (c) tests green flag-on;
(d) macro A/B + micro counter per the 3.4 harness, tail-gated; (e) findings + hurdle log
updated. An increment may be descoped with a hurdle-log rationale — never silently
skipped. I1's spec is written first and sets the template.

**Template requirement:** every increment spec contains an ordered step list with a
per-step done-criterion — the increments are not uniform units (expected-changes has
the evidence). Pinned decomposition; deviating needs a hurdle-log rationale:

- **I0 — shard runtime foundation** (expected-changes §1): shared by I1/I2/I5, ships
  inside I1's patch but gated separately. No flag, no A/B (inert until routed to);
  gate = tests green + shutdown-before-commitlog ordering verified.
- **I1**: already two-step via its flags (shard_routing, then skip_lock). No change.
- **I2a**: shard-routed reads on the `sequential()` executors (`shard_reads` flag).
  **I2b**: shard-loop replacement (drain-inbox + drive-ring) + ring at the
  ChannelProxy seam (`uring_reads` flag). I2b is the piece blocked on Phase 1/2
  outputs; the split isolates that dependency. I2b's default is buffered ring reads
  (page cache retained) per findings §5.1 — provisional: its A/B set must include a
  DIO-read variant cell (O_DIRECT + ChunkCache sized up) so the caching-model decision
  (§8 item 8) is made by measurement, not inherited.
- **I3**: step 0 FlushItem/payload release audit (pre-code); step 1 future plumbing
  (ReadCallback / AbstractWriteResponseHandler / BlockingReadRepair — inert flag-off,
  unit-testable alone); step 2 coordinator composition (fetchRows / mutate); step 3
  Dispatcher/Message async completion + outstanding-ops limiter. Only step 3 delivers
  the macro A/B; each prior step is buildable and revertible on its own.
- **I4a**: per-shard commitlog (post-3.3 choice, `sharded_commitlog`); **I4b**:
  per-shard writeOrder (`sharded_write_order`); **I4c**: per-shard memtable allocator.
  Separate flags/micro counters give per-step A/Bs.
- **I5**: single-step. No change.

### 4.3 Hurdle log → `hurdles.md` (living; updated every increment)
Every obstacle: what, where found, severity (blocks-PoC / costs-perf / CEP-era-only),
remediation or defer. This is the program's second deliverable — the stated purpose of
the whole exercise is finding these.

### 4.4 PoC verdict → `poc-verdict.md`
Full-system A/B (all flags on vs trunk) against the 4.1 criteria; per-increment
attribution table (which step bought what); honest tail analysis. This document becomes
the headline of Phase 5's evidence pack.

## 3. Exit gate (Phase 4 → Phase 5)

poc-criteria.md + hurdles.md + poc-verdict.md exist · every increment built or
descoped-with-rationale · verdict adjudicated against the pre-stated criteria · rig
results archived on rig AND locally (rsync-before-launch and verify-on-rig lessons apply).

## 4. Risks

- **Fork drift vs trunk** during a months-long build — rebase cadence decided in 4.1;
  Accord/CEP-45 churn on trunk is the likely friction point (Phase 3 §3.1 item 6).
- **One hardware class, single node** — the verdict generalizes with the same caveat
  Phase 2 records; note for Phase 5, don't oversell.
- **The 2016 failure mode**: PoC works, energy dissipates before upstreaming. Mitigated
  by D8's mergeable shape and by Phase 5's already-written socialization plan.
