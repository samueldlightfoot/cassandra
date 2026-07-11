# Progress — I1-close + I5 inbound shard dispatch

## 2026-07-11 — Pivot decided, plan produced (no code yet)
Continuation of `../i1-shard-routed-apply/` (I0+I1 built + committed: `a770ed590a`,
`b102227cec`). Two user directives redirected the increment:
1. "RF=3 is the assumption even for single-node tests; an RF=1-only mechanism means little."
2. "Move the dispatch point upstream" (toward the Scylla model: route all access to the
   owning shard at ingress).

**Correctness finding that forced it:** `skip_lock` (owner skips the memtable writeLock) is
RF=1-only — at RF≥3 a skipping owner races a lock-taking unrouted writer (hint/read-repair/LWT)
on the `InMemoryTrie`. Step-1 routing (lock KEPT, uncontended) is the RF≥3-safe, production-
analogous mechanism. Full reasoning + hop accounting + seams: `findings.md`.

**Two subagent reviews (both read the authoritative docs + verified seams on-branch):**
- Explore (seam-verify): `InboundMessageHandler.java:429` dispatch not drifted; small msgs
  deserialized pre-dispatch (key knowable); in-JVM delivery bypasses InboundMessageHandler via
  `inboundSink` (needs a separate router hook); single-node writes never hit inbound messaging
  (inbound dispatch inert single-node); enumerated pre-apply Stage-thread work incl. the
  *blocking* TCM catch-up.
- Fable (design pressure-test): **PROCEED-WITH-CHANGES.** Confirmed I1-alone ADDS a hop at RF≥3
  (I5 is its completion, not an optional last increment). Corrections adopted into the plan:
  keep `misroutedPuts` (decouple from the dropped skip_lock — it's D6's discriminator);
  MUTATION_REQ-only allowlist (READ_REQ→I2a); three guards (owner-inline bypass, epoch-ahead
  fallback, FORWARD_TO fallback) + ExecutorLocals overload + dtest seam; multi-node is an ADDED
  gate track, not a replacement; gate I5 on mechanism evidence + tail-neutrality, not p99.
  Top risk: multi-node re-baseline churn (noisiest instrument, smallest-amplitude claim) —
  mitigated by keeping the single-node track and time-boxing the 3-node stand-up.

**Files updated this session:** `task_plan.md`, `findings.md` (this folder);
`../i1-shard-routed-apply/{progress,task_plan}.md` (Phase-2/skip_lock superseded);
`../../../phase-3-execution-model/increments.md` §1 (re-sequencing amendment).

**Multi-node resolved (user, 2026-07-11):** no standing rig. Build RF=3-correct code now,
benchmark I1 single-node, prove I5 correct via multi-node in-JVM dtests, and DEFER the multi-node
*perf* run to on-demand Hetzner Cloud instances (per-hour, disposable — cheap) once the code is
ready. Dissolves Fable's top risk (multi-node re-baseline churn): the cloud run is on-demand, not
a permanent cost, and never blocks the single-node work. poc-criteria §9 updated accordingly.

## Next (plan settled + committed `7838b3e534` — START HERE in a fresh context)
Plan is finalized; no more sign-off needed. First buildable step is **Phase A** (`task_plan.md`),
single-node/in-JVM, NO rig:
1. Delete `MUTATION_SHARD_SKIP_LOCK` (property + enum, unconsumed) and any references.
2. Build `misroutedPuts` on `TrieMemtableMetricsView` (metrics pkg, pattern at `:41/:44/:47`);
   increment in `MemtableShard.put` (`TrieMemtable.java:550`) when the caller is on a shard thread
   AND the owner-check fails while still taking the `tryLock`. Decoupled from any lock-skip.
3. Multi-node flag-on in-JVM dtest via the inboundSink seam (NOT `CQLTester.execute` — it bypasses
   StorageProxy): `shard_routing` on, writes land + read back, `misroutedPuts` observable.
4. `ant jar` (not just build) + class-in-jar check; flag-off byte-identical (`SimpleReadWriteTest`).
Then **Phase B** (I5, `task_plan.md`) — MUTATION_REQ-only + the three guards. **Phase C** (Hetzner
multi-node perf) is later, when the code is ready.

Model: Opus builds; Fable reviews the `misroutedPuts` owner-check site (Phase A) and the I5 seam
guards — epoch-ahead / FORWARD_TO stage fallbacks + owner-inline bypass are the subtle bits (Phase B).
As-built I0/I1 interfaces to reuse verbatim: see `../i1-shard-routed-apply/progress.md` HANDOFF.

## 2026-07-11 — Phase A BUILT + verified (all four items green)
Seams re-verified on-branch before coding (no drift). One inconsistency surfaced + resolved: the plan
said "no shard-index field" AND "owner-check inside `MemtableShard.put`", but the shard can't see its
own index. Resolved by threading `shardIndex` as a **method parameter** from `TrieMemtable.put:191`
(field-free). Guard is two-part: `currentShardId() >= 0 && !currentThreadIsOwnerOf(shardIndex)` — the
`>= 0` excludes off-thread writers (hints/RR/LWT) that would otherwise all read as misrouted.

Changes (4 files): deleted `MUTATION_SHARD_SKIP_LOCK` (unconsumed); `misroutedPuts` counter on
`TrieMemtableMetricsView`; `shardIndex` param + guard in `TrieMemtable`; new dtest
`ShardRoutedReplicaApplyTest`.

Verification:
- `ShardRoutedReplicaApplyTest` GREEN (3-node RF=3, flag on): replica routing proven (routed-apply
  count ≥200 on nodes 1/2/3), `misroutedPuts`==0 all nodes, read-back at ALL. Non-NETWORK sink→doVerb
  path (macOS-runnable). This is the replica path single-node can't reach.
- `ant jar` + `javap` on the jar-extracted class confirms the new bytecode is packaged.
- Flag-off byte-identical: `SimpleReadWriteTest` 40/40 GREEN.
- `ShardExecutorsTest` 5/5, `MutationShardRoutingTest` 6/6, `ShardRoutedMutationApplyTest` 1/1 GREEN.
- `TrieMemtableMetricsTest` errors (tests=0) = PRE-EXISTING driver ABI skew (`ProtocolVersion.
  supportedVersions()` returns `ImmutableList`, datastax driver expects `List`) in `transport/` — not
  my diff. Same issue the I1 handoff logged; unrelated to routing.

NOT committed yet (user says when). Next: **Phase B** (I5 inbound dispatch — `ShardInboundRouter`,
the three guards, `ShardExecutors.execute(ExecutorLocals,int,Runnable)` overload, inboundSink dtest
seam). Fable should review the epoch-ahead / FORWARD_TO fallbacks + owner-inline bypass.
