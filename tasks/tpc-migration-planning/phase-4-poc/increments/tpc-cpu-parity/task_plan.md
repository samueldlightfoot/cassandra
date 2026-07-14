# Task plan — close the TPC routing CPU gap (Scylla-guided), and fix the ruler first

Goal: take the CQL-ingress-routing build from **+6pp CPU vs trunk** toward parity by fixing the
implementation-immaturity hotspots identified in `findings.md`, each against a ScyllaDB reference. Success is
measured in **instructions-per-op** (drift-proof), not wall-%busy. Rationale + source cites: `findings.md`.

Ordering principle: **fix the measurement ruler before cutting** — most single fixes are ~1pp, below the
current ~3pp drift floor. Then do the unambiguous "ours" fixes, then the greyer ones, then prove where TPC
actually wins.

---

## Phase 0 — fix the ruler (PREREQUISITE, blocks measuring everything else)
Without this, a 1pp fix is invisible under thermal/session drift.
- [ ] **instructions-per-op harness.** `perf stat -e instructions,cycles,cache-misses,context-switches,
  branch-misses -p <cass_pid> -- sleep 30`, divide by the tablestats write delta over the same 30s. Wrap as
  `/root/ipo.sh <tag>` alongside `abwin.sh`. This is the new primary metric (thermally + load invariant).
- [ ] **Pin CPU frequency** to kill the T1→T2 thermal drift: `cpupower frequency-set -g performance` and/or
  fix the max freq (disable turbo). Re-check T1↔T2 drift shrinks to <0.5pp.
- [ ] **Interleaved A/B/A/B** (short windows alternating jars) OR **two live nodes** (trunk + routing driven
  simultaneously) to cancel drift entirely. The cloud rig can run 2× ccx nodes.
- [ ] Acceptance: repeat-trunk instructions/op stable to <1% across the session; then re-baseline trunk vs
  routing-fixed in instructions/op (this replaces the noisy %busy headline).
- Tools: `perf stat`, `cpupower`, `mpstat` (cross-check), the existing `abwin.sh`/`prep_flip.sh`.

## Phase 1 — the two unambiguous "ours" fixes (biggest clean wins)
### 1a. Apply-side schema handle (kills `containsIgnoreCase` ~1.2pp) — findings §3.2
- [x] **route-side DONE** (`33c64e9167`): cached `boolean localSystem` on `Keyspace` (computed once at
  construction), read via `Keyspace.isLocalSystemKeyspace()` in `MutationShardRouting.route` instead of a
  per-mutation `SchemaConstants.isLocalSystemKeyspace` name scan. Chose `Keyspace` over `TableMetadata`: route
  already holds the resolved `Keyspace`, and system-ness is a function of the immutable name.
- [ ] **`ConsensusMigrationMutationHelper.validateSafeToExecuteNonTransactionally:318` — HELD.** It's on the
  general apply path (trunk runs it too), so it is likely **gap-neutral**, not routing-added. Classify it with
  the rig profile diff (routing−trunk frame) before touching; if gap-neutral it belongs with the Phase-2
  both-arms upstreamable bucket, not the routing-gap A/B.
- [ ] Scylla ref: `schema_ptr` carried + UUID-hash table lookup (`replica/database.cc:1991`).
- [x] Verified: `MutationShardRoutingTest` 9/9, `ShardRoutedReplicaApplyTest` (in-JVM) 1/1, `ant build` green.
- [ ] Measure (rig): asprof cpu differential (`isLocalSystemKeyspace` route frame → ~0) + instructions/op.
- Risk: low (optimization-only; `performLocally` still re-decides the apply shard authoritatively).

### 1b. Async future: three-tier "attach-on-done" (~0.5–0.9pp + alloc) — findings §3.1
> CORRECTED 2026-07-14 (Fable + concurrency map). The original "single-listener bare field" is DEAD: the
> `listeners` field is `ListenerList`-typed and its updater `valueCheck`s writes → a bare listener throws
> `ClassCastException` (not "already permitted"); and the busiest attaches use `addCallback`/`map` which
> allocate a fused node regardless. Do NOT resurrect it. This ranks BELOW 1a (1a is ~1.2pp at trivial risk).
- [x] **Tier 1 DONE** (`a37e9e8f6d`): in `AbstractWriteResponseHandler.outcome()` (:174), if
  `writeResult.isDone()` compute the verdict inline and return `ImmediateFuture` — skips the `AsyncPromise` +
  `scheduler.schedule` timer + `timer.cancel` + listener + drain. `computeVerdict()` is pure (:224-227); a
  not-done result falls through to today's path (zero concurrency risk). Bonus: removes a per-write
  cross-thread timer schedule+cancel (a `EpollEventLoop.wakeup` feeder). Verified: `WriteResponseHandlerTest`
  9/9 + `ShardRoutedReplicaApplyTest` (in-JVM) 1/1, `ant build` green.
- [~] **Tier 2 — ATTEMPTED & REVERTED.** The naive "fire inline, never touch the field" form double-fires
  re-entrant listeners (`AsyncPromiseTest` → `order.size()`=count+2): the `NOTIFYING` sentinel must be claimed
  so a listener that adds a listener *while firing* defers to the single drainer. Safe form = `CAS(null →
  NOTIFYING)` + inline notify + `notify()`'s re-drain tail; saves only the `push` + a 1-elem reverse. Gate on
  the rig showing `notifyExclusive` is still a gap after Tier 1 before spending more risk on the core primitive.
- [ ] **Tier 3 (optional):** done-checks in `addCallback`/`map` (`AbstractFuture.java:273-355`) before node
  construction, scoped to `notifyExecutor()==null && executor==null`; `map` returns `ImmediateFuture`.
- [ ] Measure: asprof **alloc** differential (async-future alloc → near-0), GC-log pause analysis (fewer/
  shorter G1 pauses → also helps the p99 tail), instructions/op.
- Risk: Tier 1 low; Tier 2/3 low-moderate — listener ordering + concurrency semantics must be preserved
  (`AsyncFuture` guarantees ordering). Re-run the future/promise unit tests + the write-path dtests.
- RF=3 caveat: attach-on-done is correct under RF=3 (fast-path branch; remote-quorum writes take the slow
  path) but its pp-win is weighted to the RF=1 inline-apply rig — don't over-claim on that number.

## Phase 2 — metrics `findIndex` O(1) (NOT a gap-closer; ~4% absolute on BOTH arms, upstreamable) — findings §3.3
> REFRAMED 2026-07-14: pursue as an independent *upstream Cassandra* win on its own timeline, not as part of
> TPC parity (the gap is closed). Still the biggest *absolute* item; unrelated to the routing delta.
- [ ] Replace `DecayingEstimatedHistogramReservoir.findIndex` binary-search bucket lookup with O(1) bit-math
  (count-leading-zeros) bucket index; Scylla ref `utils/estimated_histogram.hh:158-168`.
- [ ] Audit which per-op metric points on the shard path are still **shared/decaying** (striped/atomic) vs
  thread-local; make any shared ones per-shard, merge lazily at scrape (findings §3.3b).
- [ ] Measure: JMH microbench old-vs-new `findIndex`; then whole-system instructions/op drop on both arms.
- Note: this is arguably the highest *absolute* value item and lands in stock Cassandra too, but keep it
  labelled gap-neutral so nobody credits it to routing.

## Phase 3 — memtable single-writer CAS removal (~1.4pp) — findings §3.4
> DROPPED 2026-07-14: the per-op gap is already ~closed (routing +1.4% NS vs trunk after 1a+Tier1), so this
> HIGH-risk core-primitive change is no longer justified. Revive only if a near-knee/RF=3 re-measure reopens it.
- [ ] Confirm the shard apply path (`TrieMemtable$MemtableShard` / `AtomicBTreePartition`) still executes a
  CAS/`AtomicReference`/volatile update despite single-writer-per-shard ownership.
- [ ] **Prove the single-writer invariant** (no concurrent flush thread or other writer touches the partition
  during apply) — this is the correctness gate.
- [ ] If proven, add a single-writer fast path that skips the CAS. Scylla ref: `replica/memtable.cc` (zero
  atomics on insert), `logalloc.cc:1727-1751` (bump alloc).
- [ ] Measure: JMH `memtable.put` (CAS vs plain single-writer) + asprof on the CAS-retry frames.
- Risk: HIGH (correctness — memtable is the durability-adjacent hot path). Do not skip the invariant proof.

## Phase 4 — megamorphic dispatch (~0.6pp, grey/JVM) — findings §2
- [ ] Trace `itable stub`/`vtable stub` callers in the flamegraph to the polymorphic call sites the routing
  layer introduced. Tools: `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining`, JITWatch, `perf annotate`.
- [ ] Fix = monomorphize the hot shard path (dedicated type / avoid interface dispatch), or accept as a
  JVM↔C++ gap (Scylla templates are monomorphic). Lower priority; may not be worth the complexity.

## Phase 5 — IRQ affinity (the paper's biggest lever; config-only; affects tail + both arms) — findings §1
- [ ] `cat /proc/interrupts` → NIC-queue→core map; `ethtool -l/-x` RX queues/steering.
- [ ] Pin NIC IRQs off the shard cores (`/proc/irq/N/smp_affinity`), disable `irqbalance`; tune RSS/RPS/XPS.
- [ ] Measure: client `--hdr` p99 (tail) + instructions/op on both arms. This is Enberg's headline result and
  we've never tuned it; may directly move the p99 gate.

## Phase 6 — prove where TPC actually wins (strategic; informs whether CPU-parity-on-6-cores is the right gate)
- [x] **`perf c2c`** trunk vs routing — DONE: routing −31% cross-core HITM (8,596→5,901) at matched write load.
  The mechanism made visible; GC-independent. (JIT-unresolved symbols → count/rate, not per-line named.)
- [x] **Rate ladder** (fixed `--concurrency 3000`) with `--hdr` per rung — DONE: NO tail blowup divergence.
  Delivered identical (IO-bound knee); p50 parity; routing's p90/1–20ms-band marginally WORSE (its higher
  alloc → young-GC). Answer to the gate question: 6-core single-L3 CANNOT show tail-at-scale (GC/RTT swamp the
  tens-of-ns contention delta) — CPU-parity + mechanism is what this rig proves; the tail claim needs a big box.
- [ ] **Optional: Scylla-on-rig control** — same `cassandra-easy-stress` KeyValue workload against real
  ScyllaDB. If mature TPC is at/below trunk CPU with better tail, it proves the model wins and our residual is
  implementation. Read the *shape* (tail-vs-concurrency, c2c, cross-shard ops), not the absolute (C++/no-GC
  confound).

---

## Expected outcome
Phases 1+3 (schema handle + async + memtable CAS) should claw the +6pp down to ~2–3pp. Phase 2 + Phase 5 are
absolute-perf/tail wins that don't close the gap but help every node. Phase 6 tells us whether the residual is
a real TPC floor or still more of our own overhead, and whether the PoC gate should be CPU-parity at all
(the paper says the win is tail-at-scale, not single-node CPU).

## Review section (fill as phases land)
- Phase 0: instructions/op ruler (perf stat -p ÷ server-side write delta) — co-location & frequency invariant.
- Phase 1a/1b: landed. Capstone: routing-newfixes +1.4% NS vs trunk (was +6.1%); fix_delta 4,758 ins/op, 76%.
  Attribution (asprof, 2026-07-14): the win is 1b Tier-1 (outcome() fast path) — CPU outcome() 1061→90, alloc
  5-obj chain→1 ImmediateFuture, ScheduledFutureTask 474→6. 1a route fix landed but tiny (24 samples). The §2
  containsIgnoreCase "~1.2pp routing tax" was mis-attributed (fork check is cheaper than trunk). See progress.md.
- Phase 2: deferred (upstream findIndex; gap-neutral).
- Phase 3: DROPPED (memtable CAS — not worth HIGH risk for a closed gap).
- Phase 6: DONE (this rig). perf c2c: routing −31% cross-core HITM at matched write load (8,596→5,901) = the
  mechanism, GC-independent — WINS. Rate ladder + HDR bands: NO net latency win (delivered identical/IO-bound;
  p50 parity; p90 + 1–20ms band marginally WORSE on routing, tracking its 15%-higher alloc → young-GC). asprof
  cache-misses: GC dominates & is equal across arms (~19%), so the tens-of-ns HITM saving is swamped by RTT+GC
  on 6 cores. Conclusion: rig proves MECHANISM + no-regression, NOT tail-at-scale. Next = core-count scaling
  slope (scaling_ladder.sh) on ≥32-core/multi-NUMA box + close routing's residual alloc. §0 baseline fair;
  RF=1-conditional. Full: progress.md §PHASE 6 RESULT + phase6-methodology-critique.md.
