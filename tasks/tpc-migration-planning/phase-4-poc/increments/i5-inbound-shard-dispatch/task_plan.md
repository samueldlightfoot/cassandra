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
- [ ] **`net/ShardInboundRouter.java`** — allowlist **{MUTATION_REQ} ONLY** (READ_REQ deferred to
      I2a — it imports the read-miss-blocks-shard hazard); inbox-full → `stage.execute` fallback,
      keeping existing capacity accounting as the back-pressure authority.
- [ ] **`InboundMessageHandler.java:429` branch** — reuse the `ProcessSmallMessage` task verbatim;
      route only when flag on, verb allowlisted, small message (key knowable), and neither guard
      trips. Capacity ACQUIRE/deserialize/arrival-expiry stay on the loop.
- [ ] **Guard 1 — epoch-ahead → Stage fallback:** on the loop, `message.epoch().isAfter(current)`
      → `stage.execute` (TCM catch-up at `AbstractMutationVerbHandler:93/:139/:189` blocks; must
      never run on a shard thread — no-blocking-on-shard invariant).
- [ ] **Guard 2 — FORWARD_TO → Stage fallback:** messages bearing `FORWARD_TO` take the Stage
      path (forwarding to other replicas must not queue behind a hot shard).
- [ ] **Owner-inline bypass** in `MutationVerbHandler.applyMutation`: `if
      (ShardExecutors.currentShardId() == shardId) apply.run()` — under I5 `doVerb` already runs
      on the owning shard thread; avoid re-enqueue to the same shard.
- [ ] **`ShardExecutors.execute(ExecutorLocals, int, Runnable)` overload** — carry the
      `ExecutorLocals`/`TraceState` the loop builds (executors are `localAware()`); else flag-on
      silently drops tracing/ClientWarn for routed verbs.
- [ ] **In-JVM dtest seam** — `Instance.receiveMessageRunnable` (or the inboundSink hook) calls
      the SAME router; without it, in-JVM flag-on dtests bypass the router and prove nothing.
- [ ] Flag-off = `header.verb.stage.execute` verbatim; micro: inbox depth, fallback count,
      `internalLatency`.

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
