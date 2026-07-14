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
- [ ] Thread the already-resolved `TableMetadata`/`Keyspace` handle through `MutationShardRouting.route` and
  the `ConsensusMigrationMutationHelper.validateSafeToExecuteNonTransactionally` apply check, instead of
  re-deriving keyspace identity per mutation.
- [ ] If a system-keyspace distinction is genuinely needed on the path, cache a **boolean flag on
  `TableMetadata`** (computed once), not a per-op `containsIgnoreCase` scan.
- [ ] Scylla ref: `schema_ptr` carried + UUID-hash table lookup (`replica/database.cc:1991`).
- [ ] Measure: asprof cpu differential (`containsIgnoreCase` frame → ~0) + instructions/op.
- Risk: low (optimization-only; `performLocally` still re-decides the apply shard authoritatively).

### 1b. Async future: single-listener bare field + ready-future fast path (~1.2pp + alloc) — findings §3.1
- [ ] In `AbstractFuture`, store the lone listener as a bare field; promote to a `ListenerList` node only on
  the 2nd add (mirror Netty `DefaultPromise`; the field type at `AbstractFuture.java:103` already permits it).
  Reconcile the `ListenerList.notify/notifyExclusive` path to branch on bare-listener vs list.
- [ ] Add a ready-future inline path (already-complete → invoke continuation synchronously, skip node+notify;
  mirror Seastar `future.hh` L1680) and a `make_ready_future`-style singleton for already-available returns.
- [ ] Measure: asprof **alloc** differential (async-future alloc → near-0), GC-log pause analysis (fewer/
  shorter G1 pauses → also helps the p99 tail), instructions/op.
- Risk: medium — listener ordering + concurrency semantics must be preserved (`AsyncFuture` guarantees
  ordering). Re-run the future/promise unit tests + the write-path dtests.

## Phase 2 — metrics `findIndex` O(1) (NOT a gap-closer; ~4% absolute on BOTH arms, upstreamable) — findings §3.3
- [ ] Replace `DecayingEstimatedHistogramReservoir.findIndex` binary-search bucket lookup with O(1) bit-math
  (count-leading-zeros) bucket index; Scylla ref `utils/estimated_histogram.hh:158-168`.
- [ ] Audit which per-op metric points on the shard path are still **shared/decaying** (striped/atomic) vs
  thread-local; make any shared ones per-shard, merge lazily at scrape (findings §3.3b).
- [ ] Measure: JMH microbench old-vs-new `findIndex`; then whole-system instructions/op drop on both arms.
- Note: this is arguably the highest *absolute* value item and lands in stock Cassandra too, but keep it
  labelled gap-neutral so nobody credits it to routing.

## Phase 3 — memtable single-writer CAS removal (~1.4pp) — findings §3.4
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
- [ ] **`perf c2c`** on trunk vs routing to visualize cross-core cache-line bouncing — if trunk contends on
  its shared NTR pool / shared memtable and routing doesn't, that is the TPC win made visible.
- [ ] **Concurrency sweep** with client `--hdr` per rung — look for the rung where trunk's tail blows up
  (contention onset) but routing's stays flat.
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
- Phase 0: …
- Phase 1a/1b: …
- Phase 2: …
- Phase 3: …
