# Phase 3 Spec — Execution-Model Rewrite Scoping (SEDA → TPC)

**Status:** Ready to execute once Phase 2 completes. Its gates classify the PoC's I/O
shape (phase-2 spec §3) — no Phase 2 outcome blocks this phase.
**Nature of this phase:** unlike Phases 1–2, the deliverables are DESIGN DOCUMENTS and an
EFFORT MODEL, not code. The exit artifacts are the build plan for Phase 4's PoC; the CEP
(Phase 5) reuses them later with PoC evidence attached. Audience = the implementing
agent first, CEP reviewers second: every design ends in a buildable decision, not a
survey. Sub-phases below are scoped so each produces a reviewable document with a
decision.
**Evidence base:** three code sweeps (2026-07-05), condensed in §2 with file:line — an
executing agent extends these, it does not re-survey from scratch.

---

## 1. The reframe the evidence forces

The 2016-era assumption was "everything is global; TPC means rewriting the world." The
sweeps say otherwise. Cassandra's **data structures** have quietly become TPC-shaped:

- `TrieMemtable` is **already token-sharded, default shard count = CPU cores**, boundaries
  computed by splitting locally-owned ranges, cached per TCM epoch
  (`TrieMemtable.java:114,191`, `AbstractShardedMemtable.java:46,61-62`,
  `ColumnFamilyStore.java:1607-1631`).
- Metrics are **thread-local by default** (`CassandraMetricsRegistry.java:304-315`,
  `ThreadLocalCounter.java:47-57`).
- BufferPool has a **thread-local front layer** (`BufferPool.java:172-183`).
- Ring/schema reads are **immutable TCM snapshots** (`ClusterMetadata.java:1048-1090`).
- SSTable set reads are **copy-on-write, lock-free** (`Tracker.java:101,622-625`); point
  reads don't even per-sstable refcount (guarded by the table's read OpOrder —
  `SinglePartitionReadCommand.java:733`, `ColumnFamilyStore.java:2064-2069`).
- Replica mutation apply is **already continuation-style**
  (`MutationVerbHandler.java:83` — applyFuture().addCallback, no blocking).
- Inline-execution seams exist (`SEPExecutor.maybeExecuteImmediately`,
  `AbstractReadExecutor.java:168`; `Stage.IMMEDIATE` for all Accord verbs).

What is NOT TPC-shaped is the **scheduler and a shortlist of write-path singletons**:
any SEP worker executes any token's work, so the sharded memtable still needs its
per-shard lock (`TrieMemtable.java:553`), and every write crosses one global OpOrder
cacheline and one commitlog atomic. **TPC for Cassandra is therefore not "rewrite the
database"; it is "make the scheduler agree with the data structures, then remove the
five hostiles."** That is a fundamentally smaller project than 10989 assumed — and it
can land as flag-gated increments, not a big bang.

## 2. Verified current state (condensed; full reports in section refs)

### 2.1 Execution model
- 16 stages; hot ones (READ, MUTATION, COUNTER/VIEW_MUTATION, REQUEST_RESPONSE,
  ACCORD_MIGRATION) + Native-Transport-Requests/Auth all run on ONE
  `SharedExecutorPool.SHARED` (per-executor queues + floating workers + work permits;
  `Stage.java:44-62,232-238`, `SEPExecutor.java:59-138`, `SEPWorker.java:90-218`).
  Sum of work permits ≈ 290+P at defaults; queues unbounded.
- Dedicated pools: compaction/flush/commitlog/streaming/repair/scheduled (census table in
  the Phase-3 explorer report §4; thread formula §6: ~50–80 threads idle at P=8).
- CQL: netty event loop (2P threads) → decode on loop → `Dispatcher.dispatch` →
  NTR SEP pool (`Dispatcher.java:131`); coordinator work runs ON the NTR thread
  (`StorageProxy.read`, `StorageProxy.java:2173`).
- Internode: netty loop (P threads) deserializes small messages on-loop, then
  `header.verb.stage.execute` (`InboundMessageHandler.java:420-430`); Verb→Stage table
  `Verb.java:201-408`.
- **Coordinator threads BLOCK** on one-time Conditions for the replica round trip
  (`ReadCallback.java:71,126,242`; `AbstractWriteResponseHandler.java:83,137,359`).
- 8 handoff points per QUORUM read enumerated in the topology report §3; response
  delivery is callback-based (`ResponseVerbHandler.java:62-86`, `RequestCallbacks`).
- Token knowable: CQL only after parse+bind (`SelectStatement.java:821,839,847`);
  internode only after payload deserialization (header carries verb, not token;
  `ReadCommandVerbHandler.java:186`).
- No affinity/NUMA prior art in src (OpenHFT affinity jar present but transitive-only);
  event-loop assignment is round-robin; jctools MPSC queues already used
  (`SocketFactory.java:111-140`).

### 2.2 Shared-state classification (write+read hot paths)
| State | Classification | Evidence |
|---|---|---|
| `Keyspace.writeOrder` global OpOrder | **HOSTILE #1** global-but-atomic, every write, all cores | `Keyspace.java:100-102`, `OpOrder.java:206-216` |
| CommitLog segment `allocatePosition` CAS + `synchronized` rollover + 1 sync thread | **HOSTILE #2** | `CommitLogSegment.java:103,242-257`, `AbstractCommitLogSegmentManager.java:298-323` |
| SEP scheduler ↔ shard mismatch (per-shard memtable lock exists only because any thread writes any shard) | **HOSTILE #3 (structural)** | `Stage.java:47,232-238`, `TrieMemtable.java:513,553-596` |
| Global memtable `MEMORY_POOL` + one allocator shared across a memtable's shards + 1 cleaner | **HOSTILE #4** | `AbstractAllocatorMemtable.java:61,120`, `MemtablePool.java:113-114`, `NativeAllocator.java:223-258` |
| ChunkCache/BufferPool/CacheService global backing | **HOSTILE #5 (least bad — Caffeine + thread-local fronts)** | `ChunkCache.java:48-53`, `BufferPool.java:163,386-399` |
| MV/counter Striped locks | shardable (token-keyed), niche tables only | `ViewManager.java:69`, `CounterMutation.java:73` |
| Tracker view updates | per-table lock, rare (flush/compaction) | `Tracker.java:100,171-189` |
| Schema/TCM/metrics/tracing/monitoring | global-but-atomic or rare; not blockers | census report §3 |

### 2.3 Shard-routing seeds
`ShardBoundaries` + `getShardForKey` (`ShardBoundaries.java:67-113`), epoch-cached
`localRangeSplits` (`ColumnFamilyStore.java:1607-1631`), `DiskBoundaries` same-splitter
disk routing (`DiskBoundaries.java:108-160`). A shard router reuses these, it does not
invent new machinery.

---

## 3. Sub-phases

### 3.1 Target-architecture design note → `design-target.md`
Define, with code-level precision, the end-state:
1. **Shard model:** N shard threads (N = cores or cores−reserved), each owning a
   token-range slice via the SAME `ShardBoundaries` the memtable uses; per-shard MPSC
   inbox (jctools, already in-tree); shard thread loop = drain inbox + drive its Phase-1
   io_uring ring + run continuations.
2. **What stays off-shard:** compaction, flush, streaming, repair, gossip, TCM, scheduled
   tasks (they keep pools; they interact with shards via messages/snapshots). This matches
   the 10994 consensus (Weisberg: big immutable-data tasks lose nothing on plain threads;
   Yeschenko: don't move maintenance until numbers justify it) — cite both in the doc.
   Netty event loops STAY as the network layer (Seastar-style full takeover is explicitly
   rejected — record why: Netty 4.1 works, the win is in storage-path affinity, not socket
   polling; 10993's POC also showed netty-queue overhead ~1% at saturation).
3. **Routing points:** CQL — after bind in statement execution (earliest token point,
   `SelectStatement.java:839`); internode — after payload deserialize on the netty loop
   for small messages (token already knowable there, `InboundMessageHandler.java:161-216`).
4. **Cross-shard ops:** range reads, batches (incl. batchlog), MV updates, counter RMW,
   and **Paxos/SERIAL reads and writes** — enumerate each with its dispatch pattern
   (scatter/gather vs owner-forwarding). Paxos is not optional detail: the prepare/propose/
   commit rounds each carry their own replica round trip and local state
   (PaxosState), and the 2016 POC needed a dedicated `PaxosWriteTask` distinct from the
   plain write task — treat it as a first-class path, plus hint delivery as the failure-mode
   sibling.
5. **Load-skew stance** (Weisberg's on-record 10994 concern: "load skew especially
   temporal skew" — with static ownership one slowed shard eventually holds all
   outstanding requests): choose strict ownership vs bounded work-stealing for read-only
   work, name the per-shard backlog metric that detects skew, and state the observation
   that would trigger revisiting. Note the mitigations already implicit in this design vs
   2016's fear: maintenance stays off-shard (no compaction stealing a shard's core) and
   shard threads outnumber-able vs cores if needed.
6. **Accord coexistence (required section, not a risk line):** Accord verbs run
   `Stage.IMMEDIATE` on the messaging threads (`Stage.java:58`; all Accord verbs per §2.1)
   and CEP-15 is live on trunk and growing. The design must state: do Accord verbs stay
   IMMEDIATE (shard threads and Accord threads then share memtable/commitlog access —
   which reintroduces multi-writer on anything I1/I4 made single-writer) or route through
   shard inboxes (latency + ordering implications for the Accord protocol)? Whichever is
   chosen, enumerate the shared state Accord touches on the write path and its fate —
   this is likely the program's largest external unknown and the CEP will be reviewed by
   Accord's authors.
Acceptance: every §2.2 row has a stated fate in the design (owned-by-shard /
message-passed / stays-global-atomic); every §2.1 handoff point is mapped to
removed/kept/replaced; the skew stance is chosen with its trigger metric; the Accord
coexistence choice is stated with its shared-state enumeration. Reviewed against the
"no open questions" bar. State explicitly the design's ceiling: routing happens
coordinator-side after bind — a shard-aware client protocol (Scylla-style per-shard
ports) is OUT of scope, so every request retains one inbox hop; reviewers should learn
this from the doc, not discover it.

### 3.2 Non-blocking coordinator design → `design-async-coordinator.md`
The blocking Condition awaits (`ReadCallback.java:126`,
`AbstractWriteResponseHandler.java:137`) are separable from shard-routing and valuable
alone (frees NTR threads ≈ removes the 128-thread pool's raison d'être). Design the
continuation form: `RequestCallback` already delivers responses async; the design turns
`awaitResults` call sites into future compositions completing on the owning shard's inbox
(or, pre-TPC, on REQUEST_RESPONSE). Must cover: timeout/hint paths
(`LocalMutationRunnable` deadline→hint logic, `StorageProxy.java:3180-3214`), speculative
retry, CASContention, and back-pressure once NTR stops being the throttle (queues are
unbounded today — SEP permits are the only brake, `SEPExecutor.java:104-108`).
Must also inventory the blocking EXTENSION POINTS flagged in 10993's description —
custom IAuthenticator/IAuthorizer, triggers, 2i hooks, UDF/UDA — which cannot be forced
non-blocking; each needs a stated contract (non-blocking built-ins) plus an off-shard
escape pool for arbitrary user code.
Acceptance: call-site inventory of every blocking await on the request path with its
continuation replacement sketched; back-pressure story stated; extension-point
escape-pool story stated.

### 3.3 Hostile remediation notes → `design-hostiles.md`
One section per HOSTILE 1/2/4 (3 is solved by 3.1 itself; 5 is explicitly deferred with
rationale):
- **writeOrder:** per-shard OpOrder; the global MV-ordering requirement
  (`Keyspace.java:100-101`) handled by routing view-generating mutations through the
  owning shard (ordering becomes trivial within an owner). Barrier/flush interactions
  (`OpOrder.Barrier` users) enumerated.
- **CommitLog:** compare (a) per-shard segments (N allocators, N files, reconciled
  replay order) vs (b) dedicated log-writer core fed by shard inboxes. Decide ONE,
  with replay, archiving, CDC, and `direct`/`mmap` mode implications
  (`commitlog_disk_access_mode`) addressed. Note existing group-commit machinery.
- **Memtable memory:** per-shard allocator (one `NativeAllocator` per shard removes
  sibling-shard region CAS, `TrieMemtable.java:129,140`); global cap kept as atomic with
  per-shard slack; cleaner stays global (rare).
Acceptance: each note ends with a chosen design (not options), failure/upgrade behavior,
and a test strategy sketch.

### 3.4 Incremental landing plan → `increments.md` ★ the phase's key output
Flag-gated, independently shippable, measurable, revertible. This is now the **PoC build
order** (Phase 4 executes it in the fork): flags stay because they give clean A/B
measurement and mergeable shape, but production freight — mixed-version care, JMX
compat, polished rollback — is deferred to CEP-era per Phase 4 D10; each increment entry
marks which of its requirements are PoC-blocking vs CEP-era. **Measurement harness,
pinned:** the Phase 2 harness is deliberately raw-file (no Cassandra process) and CANNOT
measure increments. Increments are measured macro-level: cassandra-easy-stress against
the rig per the established methodology (runbook.md; bench-playbook memory — CPU fencing,
kernel/governor recording, no-SIGTERM), flag-on vs flag-off on the same build, plus each
increment's own micro counter (I1: `contendedPuts`/`uncontendedPuts`/`contentionTime`;
I3: NTR pool size/utilization; I4: commitlog `allocatePosition` contention — name one per
increment). Both levels gate on tail latency per the rule below. Pinned candidate
sequence (the sub-phase validates/amends, with evidence):
- **I1 — Shard-routed mutation apply:** route LOCAL mutation apply to a per-shard
  single-thread executor keyed by `ShardBoundaries` (replaces
  `Stage.MUTATION.maybeExecuteImmediately` for local applies, `StorageProxy.java:1995`).
  Memtable per-shard lock contention → ~0 (observable via existing
  contended/uncontended counters, `TrieMemtable.java:553-563`). Smallest real TPC step;
  measurable; touches no wire protocol.
- **I2 — Shard-routed local reads:** same for `LocalReadRunnable`
  (`AbstractReadExecutor.java:168`) + per-shard io_uring ring for cache-miss chunk reads
  (Phase 1 API; `ChunkReader` seam from Phase 0 §2.3).
- **I3 — Non-blocking coordinator** (3.2 design).
- **I4 — Per-shard commitlog** (3.3 decision) + per-shard writeOrder.
- **I5 — Inbound shard dispatch:** internode small-message routing straight from netty
  loop to shard inbox, bypassing Stage for READ/MUTATION verbs
  (`InboundMessageHandler.java:429` branch on verb).
Acceptance per increment entry: scope (files/classes), flag name, expected measurable
effect + how measured — the measurable effect MUST include a tail-latency (p99) gate,
not throughput alone: the 2016 10993 POC posted +15% throughput with ~2× worse p99/p99.9
and would have passed a throughput-only gate — rollback story, dependency edges. Plus:
which increments are worthwhile EVEN IF the full TPC end-state is never reached (I1/I3
candidates — this is the hedge that de-risks the whole program).

### 3.5 Effort bands + risk register → `effort.md`
- Per increment: S/M/L/XL (S≤2wk, M≤6wk, L≤3mo, XL>3mo single-engineer-equivalent),
  justified against the file inventories above; sum → program bands (optimistic/likely).
- Risks: mixed-version clusters (all increments are node-local — verify none leak into
  wire formats), JMX/metrics compat (stage pool metrics change meaning), Accord
  interaction (now a REQUIRED design section — 3.1 item 6; the register tracks residual
  risk after that design choice, e.g. CEP-15 evolving under the program), test debt (in-JVM dtests run on macOS — memory:
  injvm-dtest-runs-on-macos; CCM multi-node needs Linux).
- Explicit comparison row: this plan vs "adopt DSE-6-style full TPC" vs "do nothing" —
  one paragraph each, for Phase 5's recommendation.
Acceptance: every increment has a band + justification; risk register has an owner
action per risk (mitigate/accept/investigate).

---

## 4. Execution guidance

Use sub-agents per design note (3.1–3.3 can draft in parallel AFTER 3.1's shard model
section is fixed, since 3.2/3.3 depend on it); adversarially review each design note with
a fresh agent prompted to find the state/path the design forgot (the §2.2 table is the
checklist). The increments plan (3.4) is written LAST, by the main agent, from the
accepted designs.

## 5. Exit gate (Phase 3 → Phase 4)

All five documents exist · every §2.2 hostile has a chosen remediation or an explicit
defer-with-rationale · increments each have flag/measure/rollback/band · the "worthwhile
without full TPC" hedge set is identified.
