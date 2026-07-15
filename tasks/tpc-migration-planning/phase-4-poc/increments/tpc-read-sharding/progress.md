# Progress — Read-path sharding

## 2026-07-15 — Phase-start: inherited-interface restatement (no code yet)

Fresh increment. Read `read-sharding-start.md`, `task_plan.md`, `roi-path.md`, and both prior results
(`tpc-alloc-gap/result.md`, `tpc-tier1-loaded-tail/result.md`). **Re-verified every code anchor with grep** —
all the Haiku-Explore anchors held up (±a few lines). Live rig jar = alloc-gap (`5348018527`); loadgen deleted.

### As-built interfaces confirmed
- `CqlShardRouter.routeShard` L112 gates reads OUT at `instanceof ModificationStatement` L131; `computePlan`
  L187; `fallback()` L213. Routes `ExecuteMessage` (prepared) only.
- `Dispatcher.dispatch` L122, route branch L148-159, NTR fallback L161.
- `ShardExecutors.execute(int,Runnable)` L117 / `execute(locals,int,Runnable)` L127; `currentThreadIsOwnerOf`
  L110; `SHARD_COUNT = getAvailableProcessors` L50 (12 shard executors on 6C/12T).
- `MutationShardRouting.shardForKey(TableMetadata, DecoratedKey)` L144 — reusable for reads; returns empty
  unless the current memtable is an `AbstractShardedMemtable` (Trie on the rig).
- Local read today: `AbstractReadExecutor.makeRequests` L138 → `Stage.READ.maybeExecuteImmediately(new
  LocalReadRunnable(...))` **L168** (the swap point).
- `StorageProxy.LocalReadRunnable` L3130 — builds its own `ReadExecutionController` INSIDE `runMayThrow`
  L3161, runs `executeLocally`, completes the `ReadCallback` handler L3184. (So a bespoke `ShardReadRunnable`
  may be unnecessary — reuse `LocalReadRunnable`, just change the submit target.)
- `StorageProxy.read` L2582 returns a `PartitionIterator` **synchronously**; `readWithConsensus` = the SERIAL
  branch (Paxos/Accord) that must never route. `SinglePartitionReadCommand.partitionKey()` L497.
- `SelectStatement.getPartitionKeyBindVariableIndexes()` L245 — the SAME method the write path uses; only
  needed for the ingress-routing variant (B), not the local-read variant (A).

### The one refinement vs the start prompt (design decision recorded in findings.md)
The start prompt frames the **CL gate** (route only `CL∈{ONE,LOCAL_ONE}` or `RF==1`) as universal to read
routing. Verifying the code shows the gate binds only to the **ingress-coordinate-routing** variant (which
blocks a single-writer shard thread on the whole synchronous read, including remote-replica RTT). The
**local-read-only** variant (swap `Stage.READ`→shard executor at `makeRequests:168`) is correct at every
CL/RF with no gate — the coordinator still contacts all replicas and does digest/read-repair; only the local
read's CPU+disk moves to the owning shard. Decision: **Variant A first** (see findings.md §Decision).

### Fable review (DONE) — one revert-class flaw + gate omissions, all verified at source
Fable confirmed Variant-A-now is the right call (routing point, CL/RF argument, B deferral all check out) but
**not as drafted**. Findings folded into findings.md §Variant A concrete shape + §Measurement-plan fixes:
- **Revert-class (mandatory gate):** route a local read ONLY when `currentShardId()==UNSET`. A routed write
  coordinate can trigger a synchronous auth read on the shard thread; submitting its local portion back to the
  same shard executor self-stalls that shard for 5s → dropped with no callback. Invisible to the bench + RF=3
  dtest (AllowAllAuthenticator). Guard verified: `ShardExecutors.currentShardId()`/`UNSET`.
- **Gate omissions:** exclude local-system keyspaces (`ShardBoundaries.NONE`→shard 0 for all keys, verified
  `MutationShardRouting.java:100-101`) and `indexQueryPlan()!=null` (verified `ReadCommand.java:341`); wrap
  submit in try/catch → `Stage.READ` for `RejectedExecutionException` during drain.
- **Correctness confirmed:** thread-swap is correct at all CL/RF (coordinator path untouched); `LocalReadRunnable`
  is thread-self-contained (`MessageParams`/monitoring/`ReadCallback` all thread-agnostic; locals propagate via
  `localAware()`); no self-deadlock (read OpOrder group closes before `response()`).
- **Measurement landmines:** (1) dynamic snitch eats local-read latency incl. shard-queue wait
  (`StorageProxy.java:3195`) → de-selects self → mechanism turns off mid-A/B; disable snitch or monitor
  local-read fraction. (2) sstable-only dataset designs OUT the one proven single-L3 mechanism (memtable trie
  node reuse) — add a memtable-resident read arm; `ShardExecutors` have no core pinning, capping the win.
- **Pre-agreed fallback:** if the A/B tail is read/write mutual-stall dominated, pivot to a separate per-core
  sharded read pool (same hash, not the write executors).

## 2026-07-15 — Phase 1 (implement) + Phase 2 (correctness) DONE, both green

### Implemented (4 files; compiles clean, `ant build`)
- `CassandraRelevantProperties`: new `CQL_READ_ROUTING` (`cassandra.tpc.cql_read_routing`, default false).
- `ShardExecutors.isShardThread()` — additive guard (`CURRENT_SHARD != UNSET`).
- `service/reads/ShardReads.java` (new) — the read analog of `CqlShardRouter`: `ENABLED` =
  `CQL_READ_ROUTING && MutationShardRouting.ROUTING_ENABLED`; `submitLocalRead(ReadCommand, Runnable)` applies
  the 5 gates (enabled · not-a-shard-thread · not-local-system · `indexQueryPlan()==null` · `shardForKey`
  present), submits the existing `LocalReadRunnable` to the owning shard executor, catches
  `RejectedExecutionException`→fallback; `ShardLocalReadRouted/Fallbacks` counters.
- `AbstractReadExecutor.makeRequests`: `if (!(ShardReads.ENABLED && ShardReads.submitLocalRead(command,
  localRead))) Stage.READ.maybeExecuteImmediately(localRead);` — flag-off is byte-identical to trunk.

### Scoping decision surfaced during Phase 2 (recorded, not a gap for the PoC)
`makeRequests` is COORDINATOR-side: my change routes only the read the coordinator runs *as a replica*.
Remote replica reads arrive via the internode read verb handler (untouched, stays on `Stage.READ`) — the read
analog of the write path's `MutationVerbHandler` replica routing, deliberately out of scope. The single-node
PoC bench and the big-box test are one Cassandra process (coordinator == replica for every key), so
coordinator-local routing covers 100% of the measurement scenarios. Multi-node replica-read routing is a
clean follow-up, not needed here. Correct at RF=3 regardless (coordinator still contacts all replicas).

### Phase 2 gate — GREEN
- `ShardRoutedLocalReadTest` (new, 3-node in-JVM, TrieMemtable): **PASS** (1 test, 0 failures). Correct results
  at **RF=1 and RF=3**, **CL ONE / QUORUM / ALL**; asserts routed counter grew ≥200 (path exercised, not
  silently falling back). Digest resolution runs on the coordinator over 200 QUORUM rows.
- `ShardRoutedReplicaApplyTest` (write path): **PASS** — unregressed by the read changes.
- `ShardExecutorsTest`: **5/5 PASS**.
- Teardown "Invariant failed" lines are a pre-existing shard-routing shutdown thread-count artifact — the
  write test emits the identical 12 lines and still passes; my change adds no threads.

### Not yet done
- [ ] `ant jar` + `javap`-verify for rig deployment (Phase 3, off-box — deferred until the loadgen is re-provisioned).
- [ ] Phase 3 CPU-bound read A/B (needs loadgen re-provision, dynamic-snitch neutralized, memtable-resident arm).
- [ ] Commit (awaiting user go-ahead).
