# Phase 5 Spec — Upstream Path & Decision (post-PoC)

**Status:** Ready to execute once Phase 4's PoC exit gate passes.
**Renumbered from Phase 4 on 2026-07-07** (PoC-first reframe): the CEP now FOLLOWS a
working PoC. The evidence pack leads with Cassandra-level PoC numbers (Phase 4
poc-verdict.md), not raw-file benchmarks, and the hurdle log becomes the CEP's honest
risks section.
**Objective:** convert Phases 0–4 outputs into (a) an upstream-ready proposal and (b) a
recommendation memo on how to land it. The final call is the user's; this phase builds
the decision-grade case, both directions.

---

## 1. Verified upstream landscape (agent report 2026-07-05, all primary-sourced)

- **CASSANDRA-10989** ("Move away from SEDA to TPC", Aleksey Yeschenko 2016, TPC proposal
  originated by Benedict Elliott Smith): Open, unassigned, **zero comments, dormant since
  ≤2019**, 16 votes / 96 watchers. Sub-structure: 10994 (stage 1, Open) → 10993
  (non-blocking read/write paths, Open); 8520 (TPC prototype) closed Won't Fix.
  **Consequence: this is a revival-as-new-proposal, not a reopen.** The old ticket is the
  historical anchor to link, and its AIO-rejection line is the narrative hook ("the 2016
  blocker no longer exists").
- **Comment threads read 2026-07-07** (digest: findings.md §7): 10994 has 2 comments —
  Weisberg's load-skew/temporal-skew warning and non-uniform-cores advocacy; Yeschenko
  keeping maintenance off the loops. 10993 has 39 (2016–2017) — the FSM-vs-RxJava style
  war, Aleksey's "one does not outsource project's core competency to an external
  library", and the Hobbs POC numbers: **+15% throughput but ~2× worse p99/p99.9 vs
  trunk, never root-caused**. 10993's fixVersion was re-groomed 5.x → 6.x by Michael
  Semb Wever on **2026-03-21** — the ticket is still tracked, not tombstoned.
- **CEP process** (cwiki): CEP "highly recommended for … changes that cut across multiple
  subsystems" — a scheduler/execution-model change unambiguously qualifies. Required
  sections: Scope · Goals & non-goals · Approach · Operational implications (migration,
  config, tooling, metrics) · Test plan (performance, correctness, failure, boundary) ·
  Timeline · Mailing list/Slack · Related JIRAs. Flow: wiki page (next free CEP number) →
  `[DISCUSS] CEP-N` on dev@ → `[VOTE]` (72 h, 3 binding +1, no binding veto). Large CEPs
  historically spend months in DISCUSS.
- **No competing/adjacent CEP exists** for TPC, io_uring, async I/O, or scheduler rework
  (full CEP index checked). Corrections recorded: CEP-27 = data-collections API;
  CASSANDRA-17020 = cqlshlib — both unrelated (earlier guesses, now dead).
- **Only two io_uring JIRAs in the whole project**: CASSANDRA-19887 "Support Netty
  io_uring Transport" (reporter: **Sam Lightfoot**, Triage Needed) and CASSANDRA-21175
  "Upgrade to Netty 4.2" (Dmitry Konstantinov, 2026-02, cites io_uring access).
- **The DIO umbrella is the precedent** for landing I/O work incrementally WITHOUT a CEP:
  CASSANDRA-14466 umbrella; shipped 18464 (commitlog, 5.0), 19987 (compaction reads, 6.0),
  21147, 21134 (background writes, resolved 2026-06-24); open 19988/19707/21382/20087.
- **JDK trajectory**: trunk supports 11/17/21; CASSANDRA-21171 (JDK 25) is Patch
  Available; Jon Haddad's May-2025 dev thread proposes "7.0: 21+24 (2026)" and explicitly
  motivates FFM/arenas. No decision recorded to require 22+. **Consequence: JNA binding
  now is correct (Phase 0 D1); design the native layer behind an interface so an FFM
  implementation can slot in when the floor rises.**
- **dev@ temperature**: zero io_uring or thread-per-core mentions 2024–2026. SEDA's
  weaknesses acknowledged in passing (Jeff Jirsa, Jan 2024, rate-limiter thread: "The
  SEDA model is bad at back pressure and deferred cost makes it non-obvious which
  resource to slow"). Performance-architecture energy currently: direct I/O, JDK
  modernization, Accord (CEP-15), mutation tracking (CEP-45). **Greenfield, not
  contested — but also zero existing momentum to ride.**

## 2. Pinned strategy decisions

### D5. Vehicle: one CEP for the shard-routing program; increments as its phases
The TPC program (Phase 3 increments I1–I5) changes the execution model — CEP territory by
the wiki's own definition. Do NOT try to slip I1/I2 through as plain JIRAs first: a
scheduler change discovered mid-review to be "TPC by stealth" burns trust that a
years-long program needs. The io_uring binding ships INSIDE the CEP as supporting
infrastructure for I2 — not as a standalone orphan JIRA (upstream has no consumer for a
bare binding).
**Exception (the hedge):** if the recommendation lands on (b) targeted-increments-only,
I3 (non-blocking coordinator) alone is arguably NOT a cross-subsystem change and could go
as a JIRA under a 14466-style umbrella — decide in 5.4 with the PoC results in hand.

### D6. Framing: "finish what the data structures started", not "adopt ScyllaDB's model"
The CEP's technical narrative is Phase 3 §1: memtables are already core-count
token-sharded, metrics thread-local, TCM snapshots immutable — the scheduler is the last
non-sharded layer. The 2016 objection (AIO/XFS) is documented obsolete (Phase 0/1 kernel
facts). This framing survives review because every claim carries a file:line from the
current tree, not from Seastar envy.
Two ready-made quotes from the tickets themselves strengthen it: 10994's stage-1 text
concedes its one forced compromise — an extra thread pool for cache-miss reads that
"cannot be avoided, as we have to support filesystems that aren't xfs" — which is
precisely what io_uring deletes; and Ellis's 10993 comment that the real win is "not
needing to use threadsafe memtables" is 2016 agreeing with this CEP's core claim.
Aleksey's no-external-library rule is the ready answer for why the io_uring binding is
greenfield in-tree (D1), not a dependency.

### D7. Relationship to existing tickets
Link, don't absorb: 10989/10994/10993 (historical anchor; propose closing 10989 as
superseded when the CEP is adopted), 19887 + 21175 (network-side io_uring explicitly
OUT of scope — storage-path only; avoids coupling to the Netty 4.2 upgrade), 14466
(pattern precedent), CEP-15/45 (state interaction constraints from Phase 3 risk
register — Accord verbs run IMMEDIATE on messaging threads and must coexist).

## 3. Sub-phases

### 5.1 Evidence pack → `evidence.md`
Collate into one reviewable document, **headlined by the PoC results** (Phase 4
poc-verdict.md: full-system A/B vs trunk + per-increment attribution + tail analysis,
with hurdles.md as the honest-risks appendix), then: Phase 2 verdict (G1–G4 numbers +
graphs), the single-thread QD proof (Phase 1.4), the hostile census table (Phase 3
§2.2), effort bands (Phase 3.5), kernel-facts summary with the 5.19/6.1 feature floor,
and the JDK/FFM trajectory note. Every number links to raw data in the results dirs.
Acceptance: a reviewer can verify any headline claim in ≤2 clicks from this doc.

### 5.2 CEP draft → `cep-draft.md`
Map to the required sections verbatim (§1 list). Section-by-section sources:
Scope/Goals ← Phase 3 design-target.md (incl. explicit non-goals: network transport,
full Seastar-style takeover, mixed-version wire changes); Approach ← increments I1–I5
with flags and rollback, now carrying their PoC measurements + the D10 production
freight (mixed-version, JMX compat, upgrade paths) as the CEP-scope work items;
Operational implications ← per-increment config/metrics changes (new shard metrics,
SEP pool metrics semantics) + kernel floor (graceful fallback below 5.19 — binding
availability probe); Test plan ← per-increment measurable effects (3.4 harness + PoC
results), in-JVM dtest strategy, failure/boundary (EINTR, EBADR, ring teardown,
kernel-disabled sysctl); Timeline ← effort bands calibrated by actual PoC build times;
Related JIRAs ← D7 list.
Acceptance: complete draft, no TBD sections, reviewed by an adversarial agent pass
("which section would a committer -1 and why").

### 5.3 Socialization plan → `socialization.md`
- Pre-DISCUSS: share evidence pack informally in #cassandra-dev Slack; individually flag
  to the natural reviewers — Benedict Elliott Smith + Aleksey Yeschenko (original TPC
  authors), Ariel Weisberg (10994's substantive commenter; his skew concern gets a named
  answer in 3.1's design), Jeff Jirsa (on-record SEDA critique), Jon Haddad (JDK/perf
  modernization), Dmitry Konstantinov (21175, will care about scope boundary), Alan Wang
  (19887 commenter), Michael Semb Wever (groomed 10993 to 6.x in 2026-03 — still watching).
- DISCUSS thread: lead with the D6 framing + the PoC headline number; expect months;
  pre-write responses to the four predictable objections: "DSE tried this" (answer:
  incremental + hedge set, not big-bang), "JNA/Unsafe in 2026?" (answer: JDK-floor
  reality + FFM-ready interface), "who maintains it" (answer: increment ownership +
  the I1/I3 standalone value), "the 2016 POC made tail latency WORSE — 2× p99" (answer:
  that prototype had no ownership partitioning and no back-pressure and the regression
  was never root-caused; this plan sequences ownership first and every increment carries
  an explicit p99 gate with rollback).
- VOTE only after DISCUSS converges; 72 h / 3 binding +1.
Acceptance: named-person plan + drafted objection responses.

### 5.4 Recommendation memo → `recommendation.md` ★ final deliverable of the whole task
Present exactly three options with the decision criteria filled from real outputs:
- **(a) Full program CEP** — iff the PoC met its 4.1 criteria ∧ effort likely-band
  (calibrated by PoC build times) ≤ what user can sustain ∧ at least one credible
  co-sponsor emerged in 5.3 pre-flight.
- **(b) Targeted increments only** — the hedge set (I1 + I3, per Phase 3.4's
  "worthwhile without full TPC" analysis) via 14466-style JIRAs, each carrying its PoC
  numbers; the rest parked in the fork. Default if the PoC shows partial wins or
  sponsorship is thin.
- **(c) Park with artifacts** — iff the PoC missed its criteria and hurdles.md says why.
  Publish findings as a 10989 comment anyway (ends the ticket's 7-year silence with
  data either way).
Each option: 6-month cost, what's irreversible, what's learned if it fails.
Acceptance: memo ends with ONE recommended option and the two-sided case for it;
user makes the call.

## 4. Exit gate (task complete)

evidence.md + cep-draft.md + socialization.md + recommendation.md exist ·
recommendation delivered to user · task_plan.md/progress.md closed out ·
memory updated (project status memory: where the program stands, what gate is next).

## 5. Risks

- **Reviewer bandwidth**: Accord/CEP-45 consume the same senior reviewers the CEP needs;
  socialization timing should avoid landing mid-Accord-crunch (check dev@ state at
  execution time).
- **The fork-vs-upstream fork-in-the-road**: if (b), the binding lives only in the fork —
  budget for trunk-rebase maintenance or accept drift (the NoWA scoping memory is the
  precedent for consciously deferring such ports).
- **10989 nostalgia**: original authors may prefer their 2016 shape (fully non-blocking
  everything, 10993). The increments narrative must position I3 as delivering exactly
  10993's goal — allies, not competitors. Name the sequencing inversion openly: stage 1
  went non-blocking-first with ownership deferred ("every worker thread will be able to
  serve requests for any token") and its POC lost the tail; this plan goes
  ownership-first (I1 before I3), and 10993's own benchmark is the supporting evidence.
