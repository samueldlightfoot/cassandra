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

### B2 — flag-ON behavioral proof + Fable review — NOT STARTED (crucial: flag-on is compiled, not verified)
- [ ] **Flag-on in-JVM dtest** (3-node RF=3, `memtable='trie'`, flag set before `Cluster.start()`): prove the
      router actually fires — routed-through-router count advances, `misroutedPuts`==0, owner-inline bypass
      hits (no double-enqueue), tracing/ClientWarn propagate, data reads back at ALL.
- [ ] **Guard dtests:** epoch-ahead diverts to Stage (topology-change window); FORWARD_TO diverts (multi-DC).
- [ ] **Micro-instrumentation:** router fallback count + routed count; inbox depth; messaging `internalLatency`.
- [ ] **Fable review:** the epoch/FORWARD_TO Stage fallbacks, the owner-inline bypass, the locals overload.

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
