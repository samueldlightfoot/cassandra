# I1-close + I5 inbound shard dispatch — implementation plan

**Pivot (2026-07-11):** the production-analogous mutation-apply mechanism is *route to the
owning shard, keep the (now-uncontended) lock*, exercised **multi-node at RF=3** — not
*route late + skip the lock* (RF=1-only). This plan closes I1 on that footing and pulls the
upstream dispatch (formerly I5, sequenced last) forward as I1's completion. Rationale +
verified seams: `findings.md`. Governing docs amended in step: `increments.md` §1,
`poc-criteria.md` (multi-node track — awaiting user sign-off).

**Gate stance:** I5's win vs trunk is µs-scale (queue discipline + affinity), sign decided by
IRQ/loop placement. Gate it on **mechanism evidence (messaging `internalLatency` delta, inbox
depth, wake/fallback counts, hop accounting) + tail-neutrality at matched throughput**, never a
headline p99. The load-bearing A/B is 3-arm: **off / I1 / I1+I5** — measuring only off vs I1+I5
would hide whether I5 recovers I1's own RF≥3 hop regression or adds net value.

---

## Phase A — I1-close (RF=3-safe headline; single-node + in-JVM) — DONE 2026-07-11
- [x] **Drop `skip_lock`.** Deleted the `MUTATION_SHARD_SKIP_LOCK` property (confirmed unconsumed:
      single declaration, zero readers). No `MemtableShard.put` lock change; writeLock stays.
- [x] **Build `misroutedPuts` decoupled from skip_lock.** New counter on `TrieMemtableMetricsView`
      (name + field + ctor init beside contended/uncontended). Incremented in `MemtableShard.put`
      via the two-part guard `currentShardId() >= 0 && !currentThreadIsOwnerOf(shardIndex)` — the
      `>= 0` half is load-bearing: it excludes off-thread writers (hints/RR/LWT), which would
      otherwise all count as misrouted (`-1 != floorMod`).
      **Resolution of the "no shard-index field" tension:** `MemtableShard` can't see its own index,
      so the outer `TrieMemtable.put:191` (which computes it) threads it as a **method parameter** to
      `MemtableShard.put(shardIndex, …)`. Field-free, honors the plan; counter sits with its siblings.
- [x] **Multi-node flag-on in-JVM dtest** — `ShardRoutedReplicaApplyTest` (test/distributed): 3 nodes
      RF=3, flag on, memtable='trie'; 200 coordinator writes forward MUTATION_REQ to remote replicas.
      GREEN (1/1, 8.5s): routing enabled on all nodes, routed-apply count advanced ≥200 on nodes
      1/2/3 (replica path proven), `misroutedPuts`==0 everywhere, all rows read back at ALL. Uses the
      default (non-NETWORK) in-process sink → `doVerb` — macOS-runnable; NETWORK would bind 127.0.0.2/3.
- [x] `ant jar` + class-in-jar check (verified by `javap` on the class **extracted from the jar**:
      `currentShardId`/`currentThreadIsOwnerOf`/`getfield misroutedPuts` present — zip-date column is
      Ant-normalized, not trustworthy). Flag-off byte-identical: `SimpleReadWriteTest` 40/40 GREEN.

  Side-check: `TrieMemtableMetricsTest` errors (tests=0) on a PRE-EXISTING driver/server ABI skew —
  `transport.ProtocolVersion.supportedVersions()` returns `ImmutableList` but the bundled datastax
  driver expects `java.util.List` → `NoSuchMethodError` in `Cluster.connect()` (setup:90). Entirely in
  `transport/`; my diff (config/db.memtable/metrics) can't touch it. Same issue the I1 handoff logged.

## Phase B — I5 inbound shard dispatch (flag `cassandra.tpc.inbound_shard_dispatch`)

### B1 — skeleton wired at both call sites — DONE 2026-07-11 (compiles, flag-off regression-clean)
- [x] **`net/ShardInboundRouter.java`** — `tryRoute(Message, ExecutorLocals, Runnable) → boolean`,
      the single decision point for both call sites (so netty + in-JVM cannot drift). Allowlist
      **{MUTATION_REQ} ONLY** (READ_REQ deferred to I2a — read-miss-blocks-shard hazard). `ENABLED` =
      `INBOUND_SHARD_DISPATCH.getBoolean() && MutationShardRouting.ROUTING_ENABLED` (read once).
      **Deferred, not built:** the inbox-full → Stage fallback. The shard executors are unbounded
      `sequential()` queues (no depth signal yet); `InboundMessageHandler` capacity ACQUIRE/RELEASE
      remains the sole back-pressure authority for now. Needs a per-shard depth metric first (ties to D6).
- [x] **`InboundMessageHandler.dispatch()` branch** — routes only `task instanceof ProcessSmallMessage`
      (large messages deserialize on-stage → key unknowable → Stage path). Extracts
      `((ProcessSmallMessage) task).message` and calls `tryRoute`; on false, `stage.execute(locals, task)`
      verbatim. Capacity ACQUIRE/deserialize/arrival-expiry still on the loop (dispatch runs post-ACQUIRE).
- [x] **Guard 1 — epoch-ahead → Stage fallback:** `message.epoch().isAfter(ClusterMetadata.current().epoch)`.
      Conservative **superset** of the true blocking set (fires even for the `containsSelf` async-catchup
      case at `AbstractMutationVerbHandler:99`) — safe: never risks a shard-thread block, only over-diverts a
      rare topology-change window to the Stage.
- [x] **Guard 2 — FORWARD_TO → Stage fallback:** `message.forwardTo() != null`.
- [x] **Owner-inline bypass** in `MutationVerbHandler.applyMutation`: **`currentThreadIsOwnerOf(shardId)`**
      (NOT the plan's original `currentShardId() == shardId` — that skips the `floorMod` and breaks when
      #memtable-shards > cores). Inline `apply.run()` when already on the owning shard thread, else
      `shards.execute(...)` as before. Flag-off: `currentShardId()==-1` → always the else-branch (I1 behavior).
- [x] **`ShardExecutors.execute(ExecutorLocals, int, Runnable)` overload** — delegates to the executor's
      `execute(WithResources, Runnable)` (`ExecutorLocals implements WithResources`; the shard executors
      are `localAware()`), sharing a new private `shardTagged(shard, task)` helper with the int-only method
      so the `CURRENT_SHARD` set/restore is defined once. Confirms + fixes a latent I1 gap: the int-only
      `execute` passes no locals, so I1 routed applies drop tracing/ClientWarn today.
- [x] **In-JVM dtest seam wired** — `Instance.receiveMessageRunnable` async branch calls the SAME
      `ShardInboundRouter.tryRoute` before `executor.execute(locals, deliver)`. (This is the hook; the
      flag-on dtest that *exercises* it is B2, below — not yet written.)
- [x] Flag-off byte-identical verified: `SimpleReadWriteTest` 40/40 GREEN; Phase A `ShardRoutedReplicaApplyTest`
      1/1, `ShardExecutorsTest` 5/5, `MutationShardRoutingTest` 6/6 GREEN; `ant jar` packages both new classes.

### B2 — flag-ON behavioral proof — DONE 2026-07-11 (router + bypass proven live at RF=3)
- [x] **Router counters** — `ShardInboundRouter.routedCount()` / `stageFallbackCount()` (AtomicLong;
      `fallback()` helper increments the diverted count). Observable per-instance via `callOnInstance`.
- [x] **Flag-on in-JVM dtest** — `ShardInboundDispatchTest` (3-node RF=3, `memtable='trie'`, BOTH flags set
      before `Cluster.start()`). GREEN (1/1, 11.5s). Non-vacuous proof via a 1x-vs-2x discriminator:
      - Remote replicas (2,3): `routedCount` delta **≥200** (router fires on the I5 inbound path).
      - Owner-inline bypass: replica `submittedTaskCount` delta ≤ `routedCount` delta + 5 (**~1x**, not 2x —
        a broken bypass would re-enqueue and roughly double it).
      - Node 1 (coordinator+replica): `routedCount` delta **< rows** — its own writes apply via
        `performLocally`, confirming I5 is the *inbound-replica* mechanism (distinct from I1 coordinator-local).
      - `stageFallback` delta **0** on 2,3 (no guard tripped in steady state); `misroutedPuts` **0** all nodes;
        all rows read back at ALL.
- [x] Flag-off re-verified after the counter change: `SimpleReadWriteTest` 40/40 GREEN; `ant jar` packages
      the counter-bearing router (javap-confirmed).

### B3 — Fable review + fixes + guard tests — DONE 2026-07-12 (one BLOCKER found + fixed)
- [x] **Fable adversarial review** — verdict **PROCEED-WITH-CHANGES**. Verified sound (with reasoning):
      epoch guard (double-protected via epoch monotonicity + the writePlacements NPE path), owner-inline
      bypass (ack/respond already ran on shard threads under I1; floorMod predicate correct), locals
      hygiene (localAware TaskFactory sets+restores per task; CURRENT_SHARD nested inside, finally-restored),
      capacity accounting (RELEASE already ran off-loop pre-I5), allowlist (MUTATION_REQ excludes all other
      MUTATION-stage verbs; large MUTATION_REQ → Stage where I1 still routes).
- [x] **BLOCKER B1 FIXED** — `route()` called `Keyspace.open()` which asserts/NPEs on an unknown keyspace;
      under I5 that runs on the **netty event loop**, outside `processSmallMessage`'s try/catch, so a
      keyspace-drop-in-flight race → AssertionError → `fatalExceptionCaught` → `channel.close()` (whole
      connection) + permanent inbound-capacity leak (ACQUIRE done, RELEASE never runs). Root cause: `route()`
      now uses `Schema.instance.getKeyspaceInstance` (null → empty). Defense: `tryRoute` body wrapped in
      `try/catch(Throwable) → fallback()` + NoSpamLogger — the loop must never die from a routing decision
      (aligns with "routing is only ever an optimization"). Unit test `skipsUnknownKeyspaceWithoutThrowing`.
- [x] **FORWARD_TO guard dtest** — `ShardInboundDispatchTest.forwardToMessagesDivertToStage`: 2-DC
      (dc0:1 / dc1:3, NTS), CL.ALL writes; each forwards one FORWARD_TO message into DC1 → asserts DC1
      `stageFallbackCount` delta ≥ rows (guard 2 diverts). GREEN.
- [x] **S1 fixed** — dropped the flaky `fallbacks==0` assertion (`routedDelta>=rows` already proves no user
      write was diverted). NITs applied (doc on stageFallbacks/instance-null, epoch-monotonicity comment,
      in-JVM over-route comment).

### B4 — perf-run observability — DONE 2026-07-12 (most already existed)
- [x] **Micro-instrumentation.** Investigation showed two of the three signals already exist:
      - `internalLatency` = the existing per-verb `MUTATION_REQ-WaitLatency` timer, recorded in
        `onExecuting` inside `ProcessMessage.run()` (InboundMessageHandlers.java:269) — which runs on the
        shard thread under routing, so it transparently measures shard-queue wait. No new code.
      - **Per-shard inbox depth** already JMX-exposed: the shard executors are built `.withJmx("request")`,
        so each `Shard-N` publishes `PendingTasks`/`ActiveTasks` (read via nodetool/JMX/Prometheus exporter).
      - GAP FIXED: the router's routed/fallback counters were plain AtomicLongs (dtest-only). Now registered
        Counters under the existing `Messaging` group — `ShardRoutedMessages`, `ShardRoutingStageFallbacks`
        — so they're scrapeable on a real node. (A novel metric group needs the registry allowlist + its
        virtual table updated, so an existing group was reused.) Also dropped stale codenames from comments.

### B5 — remaining (optional / blocked)
- [ ] **Epoch-ahead guard dtest** — DEFERRED: in-JVM filters can't rewrite a message's epoch (they only
      drop), so there's no clean way to force `message.epoch().isAfter(current)` end-to-end. Fable proved the
      guard sound analytically (monotonicity + double-protection); a direct `tryRoute` unit test with a
      synthesized epoch-ahead MUTATION_REQ is the fallback if coverage is demanded.
- [ ] **Deferred:** inbox-full → Stage fallback (shard queues unbounded; needs a per-shard depth
      signal, ties to D6). Today `InboundMessageHandler` capacity is the sole back-pressure.

## Phase C — multi-node RF=3 perf (DEFERRED to on-demand Hetzner Cloud, poc-criteria §9)
No standing rig. Correctness is proven earlier (Phase A/B in-JVM multi-node dtests); this phase is
the *measured* multi-node number, run per-hour on disposable Hetzner Cloud instances once I5 is built.
- [ ] Spin up 3 Hetzner Cloud instances (real inter-node network), RF=3 CL=QUORUM; capture a
      `baseline_v1_multinode` (own rate ladder + operating points + 3-iter band; the RF=1 baseline
      does not transfer). Record per-instance RTT + CPU steal (cloud-VM caveat).
- [ ] The **3-arm gate: off / I1 / I1+I5** on write-heavy cells; tail gate = p99 ≤ off at
      throughput ≥ off, judged with the mechanism-evidence framing above (µs-scale delta — not a p99 headline).
- [ ] **Hint-flood correctness cell (non-gate):** pause a node, resume, hint delivery concurrent
      with routed writes → `misroutedPuts` flat, no corruption, data readable.
- [ ] Keep the single-node track alive for I2/I3/I4 (I5 inert there — no confound).

## Downstream (unchanged sequence, noted so nothing is dropped)
- READ_REQ promotion into the I5 allowlist → **I2a** (cache-hot cells; miss story is I2b).
- HINT_REQ allowlist promotion, large-message handling, Accord verbs → **CEP-era** (design-target §5/D7).

## Review / done
- [ ] skip_lock gone; `misroutedPuts` lives and is ~0 on healthy boundaries.
- [ ] I5 flag-off byte-identical; flag-on routes MUTATION_REQ to the owner, both guards + inline
      bypass exercised by dtests through the router seam.
- [ ] No blocking on shard threads (epoch guard proven); no cross-node head-of-line (FORWARD_TO guard).
- [ ] 3-arm multi-node gate run; I5 judged on mechanism evidence + tail-neutrality.
