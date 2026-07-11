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

Committed: `c565067b4a` (code+dtest), `67cd4b5c9c` (these notes).

---

## HANDOFF → Phase B (I5 inbound dispatch) — from actual diff, 2026-07-11

**(1) Deviations from plan**
- `shardIndex` is a **method param** on `MemtableShard.put(int shardIndex, DecoratedKey, …)`, not a
  field — plan said "no shard-index field" but the owner-check needs the index. Threaded from
  `TrieMemtable.put` (computes it once for both the `shards[]` lookup and the call).
- misrouted guard is **two-part**: `ShardExecutors.currentShardId() >= 0 && !currentThreadIsOwnerOf(shardIndex)`.
  `>= 0` IS the "on a shard thread" test (UNSET=-1). Do not collapse to owner-check alone — off-thread
  writers would all read as misrouted (`-1 != floorMod`).
- Phase A dtest is a plain `Cluster` (non-NETWORK), not an inboundSink hook: the I1 replica apply runs
  inside `doVerb`, already reached by the in-process sink. **Phase B's router at `InboundMessageHandler:429`
  is NOT reached by in-JVM delivery** (goes sink→doVerb, bypassing InboundMessageHandler) — Phase B still
  needs a separate hook at `Instance.receiveMessage`/inboundSink or the flag-on dtest is vacuous (findings §seams).

**(2) As-built interfaces Phase B builds against (verbatim)**
- Owner-inline bypass site = `MutationVerbHandler.applyMutation(Message<Mutation>, InetAddressAndPort)` :82.
  Current routing block (:91-101):
  ```
  ShardExecutors shards = MutationShardRouting.ROUTING_ENABLED ? ShardExecutors.instance() : null;
  if (shards != null) {
      OptionalInt shardId = MutationShardRouting.route(mutation);
      if (shardId.isPresent()) { shards.execute(shardId.getAsInt(), apply); return; }
  }
  apply.run();
  ```
  Bypass goes at the `shards.execute(...)` line; correct predicate is **`ShardExecutors.currentThreadIsOwnerOf(shardId.getAsInt())`** (NOT the plan's loose `currentShardId()==shardId`) → `apply.run()` else `shards.execute(...)`.
- `ShardExecutors.execute(int memtableShardId, Runnable task)` (:106): `floorMod(id,SHARD_COUNT)`,
  `submitted.incrementAndGet()`, then `executors[shard].execute(wrapper)` where wrapper does
  `CURRENT_SHARD.set(shard); try{task.run()} finally{CURRENT_SHARD.set(UNSET)}`. The new
  `execute(ExecutorLocals, int, Runnable)` overload MUST replicate this CURRENT_SHARD wrapper.
  **Open (verify before coding):** how the `localAware()` `SequentialExecutorPlus` accepts locals — check
  `SequentialExecutorPlus`/`LocalAwareExecutorPlus` for an `execute(ExecutorLocals, Runnable)`; the current
  `execute` does NOT pass locals, so routed verbs currently drop tracing/ClientWarn (the bug the overload fixes).
- Statics: `currentShardId()` :91 (-1=UNSET off-thread), `currentThreadIsOwnerOf(int)` :99
  (`CURRENT_SHARD==floorMod(id,SHARD_COUNT)`), `instance()` (null when off), `shardCount()`,
  `submittedTaskCount()` (instance). `SHARD_COUNT` is **private** (`=FBUtilities.getAvailableProcessors()`) — use `shardCount()`.
- Dispatch seam **UNCHANGED by Phase A**: `InboundMessageHandler.java:429`
  `header.verb.stage.execute(ExecutorLocals.create(state), task);` — `ProcessSmallMessage` inner class at :484;
  small msgs fully deserialized before :429 so key is knowable.
- `MutationShardRouting.route(Mutation) → OptionalInt`; `MutationShardRouting.ROUTING_ENABLED` (static final)
  = master flag AND periodic commitlog.
- Metric read cross-instance: registry key = `MetricRegistry.name(GROUP_NAME,"TrieMemtable","Misrouted memtable puts",scope)`;
  `CassandraMetricsRegistry.Metrics.getCounters((n,m)->n.contains("Misrouted memtable puts"))` (Metrics extends codahale MetricRegistry).

**(3) Tested / deferred / broken**
- GREEN: ShardRoutedReplicaApplyTest (3-node RF=3 flag-on), SimpleReadWriteTest 40/40 (flag-off byte-identical),
  ShardExecutorsTest 5/5, MutationShardRoutingTest 6/6, ShardRoutedMutationApplyTest 1/1.
- BROKEN, pre-existing (NOT this diff): `TrieMemtableMetricsTest` + any native-driver test
  (`Cluster.connect()`/`executeNet`) → `NoSuchMethodError ProtocolVersion.supportedVersions()`
  (server returns `ImmutableList`, bundled datastax driver expects `List`), all in `transport/`.
- Phase B: no code yet.

**(4) Decisions + rationale**
- Bypass predicate `currentThreadIsOwnerOf(shardId)` not `currentShardId()==shardId`: the former already
  does the `floorMod(id, SHARD_COUNT)` mapping (memtable-shard-id → executor-id); a raw `==` breaks when
  #memtable-shards > cores.
- dtest at Cluster level, non-NETWORK: NETWORK binds 127.0.0.2/3 (not routable on macOS); the sink→doVerb
  path exercises the same replica apply and runs locally.

**(5) Gotchas**
- Flag read-once at class-init **per instance classloader**; set `MUTATION_SHARD_ROUTING` before `Cluster.start()`.
  System properties are process-global across in-JVM instances (all nodes see it).
- `callOnInstance` lambda must read statics fresh inside the instance — a captured singleton → NotSerializableException.
- in-JVM default `commitlog_sync=periodic` (InstanceConfig:116) ⟹ ROUTING_ENABLED true. No override needed.
- Routing only engages on **sharded** memtables — table needs `WITH memtable='trie'`; SkipList is excluded.
  dtest config: `withConfig(c->c.set("memtable", Map.of("configurations", Map.of("trie", Map.of("class_name","TrieMemtable")))))`.
- `ant jar` zip-entry dates are Ant-normalized (showed 05-31) — verify packaged bytecode with `javap`, never the date column.

**(6) Assumptions to treat as given**
- Owner-check is a hint: wrong/absent shard id → falls back to taking the lock, still correct.
- `misroutedPuts`==0 on healthy boundaries because `route()` and `TrieMemtable.put` read the same
  boundaries+key → same shard; a nonzero value means skew/staleness (D6 discriminator).
- I5 is single-node-inert (coordinator==replica → `performLocally`, never inbound) — Phase B gates are multi-node only.

Fable should review the epoch-ahead / FORWARD_TO Stage fallbacks + the owner-inline bypass + the localAware overload.

---

## 2026-07-11 — Phase B1 BUILT + flag-off verified (skeleton wired at both call sites; NO flag-on proof yet)
Seams re-verified on-branch before coding (no drift from the handoff). Resolved the one OPEN item:
the locals overload delegates to the executor's `execute(WithResources, Runnable)` — `ExecutorLocals
implements WithResources` and the shard executors are `localAware()` (`Stage.execute(ExecutorLocals,
Runnable)` at `Stage.java:129` does exactly this). Also confirms a latent I1 gap: the int-only
`ShardExecutors.execute` passes no locals, so I1 routed applies drop tracing/ClientWarn today.

**Corrected two plan/findings errors against the code:**
- Owner-inline bypass uses **`currentThreadIsOwnerOf(shardId)`**, not the plan's `currentShardId() ==
  shardId` — the latter skips `floorMod(id, SHARD_COUNT)` and breaks when #memtable-shards > cores.
- Epoch guard `message.epoch().isAfter(ClusterMetadata.current().epoch)` is a conservative **superset**
  of the true blocking condition (it also diverts the `containsSelf` async-catchup case) — safe, slightly
  over-diverts. Kept: correctness (never block a shard thread) over precision.

**Changes (6 files):** new flag `INBOUND_SHARD_DISPATCH` (`cassandra.tpc.inbound_shard_dispatch`, default
false, alphabetical slot); new `net/ShardInboundRouter.tryRoute(Message, ExecutorLocals, Runnable)→boolean`
(single decision point for both paths); `ShardExecutors` locals overload + shared `shardTagged` helper;
`InboundMessageHandler.dispatch()` routes `ProcessSmallMessage` only; `Instance.receiveMessageRunnable`
async branch calls the same router; `MutationVerbHandler.applyMutation` owner-inline bypass.

**Verification (all GREEN, actually re-ran — see gotcha):** `SimpleReadWriteTest` 40/40 (flag-off
byte-identical), `ShardRoutedReplicaApplyTest` 1/1 (Phase A), `ShardExecutorsTest` 5/5,
`MutationShardRoutingTest` 6/6. `ant jar` packages `ShardInboundRouter.class` + `ShardExecutors.class`
(fresh timestamps). Not committed yet.

**GOTCHA (cost ~10 min):** `ant test -Dtest.name=<FQN> -q` is a **no-op** — returns BUILD SUCCESSFUL in
~10s without running anything (stale XML untouched). Use the **simple class name** (`-Dtest.name=ShardExecutorsTest`)
and confirm via the `Tests run: N` line + fresh XML mtime, never BUILD SUCCESSFUL alone.

### HANDOFF → Phase B2 (flag-ON behavioral proof)
Flag-on is **compiled but behaviorally unverified.** B2 is the load-bearing proof:
- Flag-on 3-node RF=3 dtest (`memtable='trie'`, set `cassandra.tpc.inbound_shard_dispatch` before
  `Cluster.start()` — system props are process-global across in-JVM instances). Assert: router fires
  (add a routed/fallback counter to `ShardInboundRouter` to observe), owner-inline bypass hits (no
  double-enqueue), `misroutedPuts`==0, tracing propagates, reads back at ALL. The in-JVM hook is at
  `Instance.receiveMessageRunnable` async branch — delivery is `inboundSink→doVerb`, so routing the
  whole `() -> inboundSink.accept(messageIn)` task runs doVerb+apply on the shard thread; the bypass
  then prevents the self-re-enqueue in `applyMutation`.
- Guard dtests (epoch-ahead diverts; FORWARD_TO diverts) + micro-metrics (router routed/fallback count,
  inbox depth, `internalLatency`).
- **Deferred from B1 (don't forget):** inbox-full → Stage fallback. Shard queues are unbounded; needs a
  per-shard depth signal (D6) before the fallback can trigger. Today `InboundMessageHandler` capacity is
  the only back-pressure. Router currently always routes when allowlisted + guards pass.
- Then Fable review (epoch/FORWARD_TO fallbacks, owner-inline bypass, locals overload).

## 2026-07-11 — Phase B2 BUILT + flag-ON proven (router + owner-inline bypass live at RF=3)
Committed B1 first (`a33130ff39` code, `446391909b` notes). Then B2:

**Prereq verified:** in-JVM `receiveMessage` defaults to `RECEIVE_MESSAGES_ASYNC=false` →
`sync(runnable).accept(false)` → `runOnCaller=false` → the **else branch** (`Instance.java:580-594`) where
the hook lives. So default in-JVM clusters DO exercise the router (not vacuous). `sync(Consumer)` submits
the body to the isolated executor and waits (`IsolatedExecutor.java:158`).

**Added:** `ShardInboundRouter.routedCount()`/`stageFallbackCount()` (AtomicLong + `fallback()` helper);
new dtest `ShardInboundDispatchTest`.

**Proof (`ShardInboundDispatchTest` GREEN 1/1) — a 1x-vs-2x discriminator, non-vacuous:**
- Remote replicas 2,3: `routedCount` delta ≥200 → the I5 inbound router fires on the replica path.
- Owner-inline bypass: replica `submittedTaskCount` delta ≤ routedDelta+5 (~1x). Broken bypass → ~2x
  (router submits + applyMutation re-enqueues). This is the precise bypass proof.
- Node 1 `routedCount` delta < rows: its own coordinator writes apply via `performLocally`, never inbound —
  confirms I5 targets the inbound-replica path (I1 is coordinator-local).
- `stageFallback` delta 0 on 2,3 (no guard tripped steady-state); `misroutedPuts` 0 all nodes; reads at ALL.

Flag-off re-verified after the counter change (`SimpleReadWriteTest` 40/40); `ant jar` packages the
counter-bearing router (javap). Commit pending.

### HANDOFF → Phase B3 (remaining before Phase C perf)
- **Guard dtests not yet written.** epoch-ahead and FORWARD_TO guards are wired + counted but not
  independently exercised. Need: a topology-change window to force epoch-ahead → assert `stageFallback`
  advances + routing still correct; a multi-DC `FORWARD_TO` write → assert divert. Both are steady-state 0
  in `ShardInboundDispatchTest`, so their divert paths are UNPROVEN.
- **Inbox-full fallback still deferred** (B1 note): shard queues unbounded, no depth signal yet (D6).
- **1x-vs-2x slack (+5)** is a guess against background traffic; Fable should sanity-check it isn't masking a
  partial bypass failure. In this run the replicas had no other shard traffic, so delta should be ~exact.
- Then Fable review, then Phase C (Hetzner multi-node perf, 3-arm off/I1/I1+I5).
