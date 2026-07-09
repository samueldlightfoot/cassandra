# Phase 3.5 — Effort Bands + Risk Register (effort.md)

**Status:** decided 2026-07-09, written by the main agent from the accepted designs
and the verified file inventories (phase-4 expected-changes §§1-6 as amended;
design-hostiles' reworked I4 scope; design-async-coordinator §9's step list).
Bands: S ≤ 2wk, M ≤ 6wk, L ≤ 3mo, XL > 3mo, single-engineer-equivalent. Bands price
PoC scope (flag-gated, A/B-ready, D10 production freight excluded); CEP-era freight
is listed separately where it will matter to Phase 5's totals.

## 1. Per-increment bands

| Increment | Band | Justification (against the verified inventories) |
|---|---|---|
| I0 + I1 | **S–M** (2–3 wk) | 2 new classes + 6 modified files (phase-4 §§1-2), all seams verified with line numbers; the work is tests (owner-check races, memtable-switch boundary races) more than code. The smallest real increment, as designed. |
| I2a | **S** (1–2 wk) | 4 flag branches on verified seams + boundaries reuse from I1. Test surface small (routing equivalence, hot-read cells). |
| I2b | **M** (4–6 wk) | The custom shard loop (idle strategy + observables) is new engineering; ring seam is one method (`ChannelProxy.read`) but the miss-reschedule exception path (rebufferer abort → pool resubmit → fresh controller) cuts across the read stack; the I/O pool itself is a plain executor. Phase 1 binding arrives as-built. |
| I3 (steps 0–3) | **L** (8–13 wk) | The widest blast radius, now precisely scoped: step 0 scaffolding (helper, guard at ~15 park sites, limiter, deadline tasks), promise plumbing in ReadCallback/AWRH, executeAsync + predicate on 3 message types, read-chain composition incl. R4 (mostly free — BlockingPartitionRepair already extends AsyncFuture) and digest-mismatch compose, write-chain allOf + metrics relocation. The hidden costs the reviews surfaced are IN this band: exactly-once slot handles, leak paths, RR-health instrumentation, no-inline-local-work sweep. |
| I4 (b→a→c) | **L** (8–13 wk) | Grew from M-ish to solid L when the review killed the single bound pair: banded ids + bound vectors + per-manager getCurrentPosition + truncation-record vectors touch ~12 commitlog/memtable files, and the new test matrix (loss-sequence, replay-union, banded-id, truncation-under-N, per-manager discard, shared-cap, CDC-exhaustion, N∈{1,4} parameterization of the existing suite) is half the band. ShardedOpOrder + 5 composite sites + Group owner field is the smaller half (M alone); allocators (c) ride the same barrier tests. |
| I5 | **S** (1–2 wk) | Router + one dispatch branch + the dtest seam; task object reused verbatim. Measurement caveats, not code, are I5's cost. |

**Program bands** (sequential single-engineer-equivalent, excluding Phase-4 bench
execution time and hurdle remediation):
- **Optimistic:** ~24 wk (≈ 6 months) — every increment at band floor, no hurdles.
- **Likely:** ~36–40 wk (≈ 9 months) — I3/I4 at band ceiling, one round of
  hurdle-driven rework each, I2b's miss-storm cell forcing one pool-design
  iteration.
- Parallelism note: the dependency graph (increments.md §1) allows I3 to proceed in
  parallel with I2/I4 by a second engineer-equivalent; wall-clock ≈ 5–6 months at
  two lanes, at the cost of merge coordination on StorageProxy.

## 2. The hand-JNI cost line (Phase 2 G2 consequence — priced, not scheduled)

| Item | Band | Content |
|---|---|---|
| Replace JNA trampoline with hand-written JNI for the Phase-1 binding | **M** (3–5 wk) | Profiled 25–35% of per-op CPU on the G1 shape is JNA trampoline + syscall stub. Scope: one `.c` file (ring setup/enter/register wrappers over raw syscalls), JNI bindings for the ~10 native entry points, build integration (ant + platform gating), byte-for-byte test parity with the JNA path (the 28-test suite runs against both), CI story for the native artifact. Risk: build/packaging friction in upstream review, not the C itself. |

Pull triggers (Phase 4/5 decide): a G1-shaped B/A ratio materially gating an
increment's tail number, or the CEP needing the binding's headroom story
(1.77–1.84× vs fio was measured WITH the JNA tax — hand-JNI only widens it).
Until a trigger fires this line stays priced-not-built; it does not block any
increment (QD1 parity through JNA is already free — B/A 1.00).

## 3. Risk register (owner action per risk)

| # | Risk | Exposure | Action (owner) |
|---|---|---|---|
| 1 | Mixed-version clusters | All increments are node-local; no wire or persistent format changes (banded ids process-local; IntervalSet shape unchanged; no new error codes) | **Mitigate:** per-increment spec includes a "wire/format delta: none" verification line (increments.md §10); re-verify at each increment's spec time. (Phase-4 implementer) |
| 2 | JMX/metrics compatibility | tpstats freeze (I1/I2 partial, I5 full); ClientRequestMetrics relocation (I3); commitlog gauges aggregate over N (I4) | **Mitigate:** each increment's entry documents its metric map; CEP-era carries the operator-facing compat plan (D10). (Phase-4 implementer → CEP) |
| 3 | Accord residual after D7(a) | Thread-budget re-derivation (~2P Accord + N shard on P cores); CEP-15 evolving under the program; migration-mode two-writer windows | **Accept for PoC** (non-Accord tables, (c) de facto); **investigate at CEP time** with Accord authors — D7's §7 table is the opening evidence. Watch trunk. (Program) |
| 4 | Two-WALs-one-device + W-SYNC | AccordJournal fsync cadence inherits `commitlog_sync_period`; one sync thread × N managers multiplies pass duration — node-wide shard park if lag threshold crossed | **Instrument:** W-SYNC is a named watch item with a cell-failing threshold (design-hostiles §2.2-ii); remedies pre-identified (per-manager lastSyncedAt, N sync threads). (I4 A/B) |
| 5 | CEP-45 mutation tracking not in tree | Unknown interaction with per-shard commitlog/writeOrder | **Watch trunk;** re-check at I4 spec time and at CEP writing. (Program) |
| 6 | Test debt | New burn/loss-sequence/composite-barrier suites; in-JVM dtests run on macOS (memory: injvm-dtest-runs-on-macos) but CCM multi-node needs Linux; Linux-gated uring tests skip locally | **Mitigate:** test strategy sketches exist per hostile (design-hostiles); rig/CI runs are the source of truth (memory: linux-gated-tests-skip-locally). (Phase-4 implementer) |
| 7 | RR starvation bet (I3 §1 executor decision) | Write completions on the P-thread RR pool could starve response delivery at saturation | **Falsifiable by construction:** RR PendingTasks + oldest-task-age first-class in every I3 cell; the §1 decision reverses to requestExecutor-offload-for-writes if the gauge moves. (I3 A/B) |
| 8 | I/O-pool sizing is rig-class-relative | Default 8 assumed the 12-core G1 rig; wrong on bigger boxes | **Accept with note:** re-derive cores-relative at CEP; the miss-storm cell is the check. (I2b A/B) |
| 9 | Oversubscription honesty (USER item 5) | N=cores atop existing pools can mute every tail number | **User pins at 4.1** with the criteria, before I1 code (increments.md §0). (USER) |
| 10 | Arm A/B adjudication drift | The two-arm A/B (phase-4 §8 item 8) could get deferred under schedule pressure, silently defaulting the I/O model | **Gate:** pool×A and pool×B are primary adjudication cells in I2b's plan (increments.md §5); poc-criteria.md restates the two-arm requirement (already amended). (Program) |

## 4. The comparison row (for Phase 5's recommendation)

**This plan (incremental, shared-something, PoC-first).** ~6–9 engineer-months to a
measured PoC; every step flag-gated, tail-gated, and revertible; the riskiest
engineering (commitlog coverage protocol, async coordinator) is now designed to
code-level with its failure modes named by adversarial review. Carries a real hedge:
I3 and I4c stand alone if the program stops. Its end-game is Scylla-referenced per
decision (design-target §10): aligned where their model is proven (per-shard
commitlog, strict ownership), transitional-with-destination where we migrate
(I/O pool → continuations, global → per-shard budgets), divergent-with-measurement
where Cassandra's facts differ (Netty, maintenance pools). The cost is discipline —
five increments, each with an A/B, is slower than a branch-and-rewrite.

**DSE-6-style full TPC (adopt/port the 2016-era architecture).** Rewrites the
scheduler, I/O, and every hot path in one arc; the 2016 POC's own numbers (+15%
throughput, ~2× worse p99, never root-caused) are the cautionary tale — a big-bang
port reproduces exactly the unmeasurable-intermediate-state problem this plan's
increments exist to avoid, against a codebase that has since grown TCM, Accord, and
SAI (none of which existed when DSE 6 forked). No hedge: value realizes only at the
end. Estimated multi-engineer-year scale; rejected as the program shape, though its
artifacts (PaxosWriteTask precedent, NotInCacheException shape) inform this plan.

**Do nothing.** Zero cost, and the data structures keep drifting TPC-ward on their
own (TrieMemtable sharding, thread-local metrics, TCM snapshots) — but the
scheduler/data-structure mismatch this program exists to close stays: the per-shard
lock, the global writeOrder/commitlog cachelines, and the 128-parked-thread
coordinator remain the tax on every write at every core count, growing with core
counts. The Phase 2 measurements (one core drives 62–67% of the box's IOPS; the
binding beats fio by 1.7–1.8×) expire as evidence if unused.

## 5. Acceptance closure

Every increment has band + justification (§1); hand-JNI is a distinct banded row
with pull triggers (§2); every risk has an owner action (§3); the three-way
comparison paragraphs exist for Phase 5 (§4). Exit-gate cross-check against spec §5:
all five documents now exist; every §2.2 hostile has a chosen remediation
(design-hostiles H1/H2/H4, design-target D1 for H3) or an explicit defer with
trigger (H5, readOrdering); increments each have flag/measure/rollback (increments.md)
+ band (here); the hedge set is identified (increments.md §9).
