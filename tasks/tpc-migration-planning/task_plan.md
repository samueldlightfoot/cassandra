# Task Plan — Planning the Migration to TPC (CASSANDRA-10989 revival)

**Status:** Spec production COMPLETE (2026-07-05); **expected-changes layer COMPLETE
(2026-07-08)** — every phase now has an `expected-changes.md` with class-level change
inventories verified against the tree (6 code sweeps, findings files indexed in
findings.md), pinned implementation decisions, and a consolidated user-decision list
(`phase-4-poc/expected-changes.md` §8). All five phases decomposed into
sub-phase specs, each verified against code/kernel/upstream primary sources.
**Owner:** samlightfoot
**Related:** `tasks/flush-write-pacing/` (DIO pacing; shares NativeLibrary idiom).
**Out of scope by user direction:** the `iouring-poc` branch (see phase-0 spec note).

## Goal

**Reframed 2026-07-07 — PoC-first.** Build a working TPC PoC on this fork and measure it
against trunk; the phases exist to surface hurdles, not to justify a go/no-go (user: "I
see no reasons why it won't work"). The CEP comes AFTER the PoC, carrying its numbers.
io_uring removed the 2016 I/O blocker. Each spec leaves no behavior-determining
questions open for an implementing agent. (Original decision-first framing: the old
phase 4 became phase 5; new phase 4 = the PoC build.)

**Big picture:** `roadmap.md` — the steps from here to a working TPC architecture and
its upstream landing.

## The specs (execution order; each has sub-phases, acceptance criteria, exit gates)

| Phase | Spec | Delivers | Gate to next |
|---|---|---|---|
| 0 | `phase-0-baseline/spec.md` | Decisions D1–D4 (JNA greenfield binding; Unsafe ordering; arch assert; TPC-shaped bench question) + rig runbook + kernel ≥5.19 + /bench-xfs & /bench-ext4 (same NVMe) | runbook complete, partitions live ⚠ 0.3 USER GATE (destroys /commitlog) |
| 1 | `phase-1-uring-binding/spec.md` | `org.apache.cassandra.io.uring` binding: sync facade → lifecycle → batched QD64 → O_DIRECT/fixed buffers → fsync → tests + bench skeleton. Kernel constants + behavioral contracts pinned from primary sources | all tests green on rig; single-thread QD proof recorded |
| 2 | `phase-2-benchmark/spec.md` | fio ground truth vs JMH-via-binding (delta = JVM tax); 4 gates: G1 TPC viability (1 thread ≥70% of 50-thread pread), G2 tax ≥0.8, G3 XFS fast-path, G4 sanity | verdict.md; gates classify the PoC's I/O shape (no outcome kills the program) |
| 3 | `phase-3-execution-model/spec.md` | Design docs (build plan for the PoC): target architecture incl. skew/Accord/Paxos, async coordinator, hostile remediations (writeOrder/commitlog/mempool), increments I1–I5 (flag-gated, tail-gated, revertible) + effort bands | all designs chosen (not optioned); hedge set identified |
| 4 | `phase-4-poc/spec.md` | **Working TPC PoC in the fork**: baseline + success criteria pinned BEFORE code (D9: p99 ≤ trunk at throughput ≥ trunk), I1–I5 built flag-gated in order, macro+micro measured, hurdles.md (first-class deliverable) | poc-verdict.md adjudicated vs pre-stated criteria |
| 5 | `phase-5-decision/spec.md` | Evidence pack (PoC-headlined), CEP draft (D5: one CEP, not stealth JIRAs), socialization plan, recommendation memo (a/full CEP, b/targeted increments, c/park-with-data) | recommendation delivered; user decides |

## Key findings that shaped the plan (details in findings.md + specs)

- Panama FFM blocked (trunk = JDK 11/17/21) → JNA raw syscalls, FFM-ready interface.
- The rig is all-ext4 with UNRECORDED kernel — Phase 0 verifies; fs A/B must be
  same-device (fixed in 0.3).
- EINTR returned-not-restarted + short-reads-are-real → hard requirements in Phase 1.
- Data structures are already TPC-shaped (TrieMemtable core-sharded, thread-local
  metrics, TCM snapshots); the scheduler + 5 hostiles are the actual gap → increments,
  not big-bang.
- Upstream: 10989 dormant 7 yrs, no competing CEP, dev@ greenfield on the topic; DIO
  umbrella (14466) is the incremental-landing precedent; user already owns 19887.
- 10994/10993 comment threads (read 2026-07-07, findings.md §7): Weisberg's load-skew
  warning → 3.1 skew decision; blocking extension points → 3.2 inventory; the 2016 POC's
  +15% throughput / ~2× worse p99 → tail gates on every increment; Aleksey's
  no-external-library rule supports the greenfield binding (D1). No decision flipped.

## Review section

- Phase 0 outcome: COMPLETE 2026-07-05 — kernel 6.8.0-124 (HWE), /bench-xfs + /bench-ext4 live on nvme0n1, fstab reboot-proven, D1-D4 unchanged. See runbook.md + progress.md.
- Phase 1 outcome: COMPLETE 2026-07-08 — exit gate PASSED. Binding live in
  `org.apache.cassandra.io.uring` (5 classes + 5 test suites + bench skeleton; only other
  src change = URING_ENABLED property). 28 tests green on rig, SKIP-not-FAIL on macOS.
  **Single-thread QD proof: 27.41×** (sync QD1 8,234 IOPS → batched QD64 225,694 IOPS,
  cold 4 KiB reads, 8 GiB file). features=0x3fff, tier=DEFER_TASKRUN. See progress.md
  session 6 + runbook.md Phase 1 facts.
- Phase 2 outcome: (pending)
- Phase 3 outcome: (pending)
- Phase 4 (PoC) outcome: (pending — poc-verdict.md)
- Final recommendation: (pending — phase-5 recommendation.md)
