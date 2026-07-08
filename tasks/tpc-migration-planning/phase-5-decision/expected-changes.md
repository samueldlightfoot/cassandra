# Phase 5 — Expected Changes

Companion to `spec.md`. Phase 5 produces documents only (evidence pack, CEP draft,
socialization plan, recommendation memo) — **no code changes**. This file exists for
symmetry and to register what the 2026-07-07/08 code sweeps add to phase 5's inputs:

1. **CEP "Approach" section gains hard file:line inventories** — the increment change
   lists in `../phase-4-poc/expected-changes.md` are already CEP-shaped (flag, scope,
   files touched, rollback = flag off). The D6 framing ("finish what the data structures
   started") now carries stronger evidence: the shard lock is provably the ONLY
   writer-exclusion in the memtable (`InMemoryTrie.java:39-44` single-mutator contract),
   and the commitlog's segment-id allocator is already global-static — per-shard logs
   replay unchanged.
2. **A fifth predictable objection for 5.3:** "how does this coexist with Accord?" — now
   pre-answerable with the three-option analysis
   (`../phase-3-execution-model/findings-accord-extension-points.md`), including the
   point that inbox-routing REMOVES two existing Accord blocking hazards, and that
   Accord already runs a token-sharded THREAD_PER_SHARD-capable executor model
   (alignment option (b)). Accord's authors review this CEP; this section is now the
   strongest card, not the weakest.
3. **Evidence-pack additions:** the misroutedPuts health metric (boundary-agreement
   proof), the tpstats-freeze caveat (so reviewers don't misread flag-on JMX), and the
   single-node caveat on I5's macro numbers.
4. **D5 hedge check unchanged:** I3 remains the standalone-value increment; its blast
   radius (widest of the five — see phase-3 §3.5 sizing signals) is the honest cost to
   state if option (b) targeted-increments is recommended.
5. **External evidence for the CEP (Enberg ANCS'19 — `../findings-tpc-paper.md`):**
   peer-reviewed result that TPC + data partitioning cuts KV tail latency up to 71% on
   commodity Linux — motivation ammunition next to the 10993 history. More valuable:
   it gives the 2016 "2× p99, never root-caused" objection two nameable candidate
   mechanisms — uncontrolled IRQ/steering environment (the paper's dominant variable)
   and wake-up steering cost at under-distributed load (their measured low-concurrency
   weakness) — both of which the PoC controls for by construction (per-cell environment
   capture; tail gates at target throughput with characterized low-load cells). Also
   pre-answers "TPC hurts at low load" (yes, by design, bounded) and "hot partitions
   starve a core" (known shared-nothing ceiling; shared-something scope + skew stance
   + backlog/misrouted instruments).
