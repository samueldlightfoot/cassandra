# Phase 3 — Expected Changes (design-doc inputs)

Companion to `spec.md`. Phase 3 produces DOCUMENTS, not code — this file therefore
inventories what each design doc must decide, now grounded in the 2026-07-07
code-verified sweeps (`findings-accord-extension-points.md` here;
`../phase-4-poc/findings-i1-*.md`, `findings-i2-i3-*.md`, `findings-i4-i5-*.md` for the
increment-level facts). The class-level change inventories the designs feed live in
`../phase-4-poc/expected-changes.md`. Everything below either PINS a previously-open
point or states the decision menu with the evidence that forces it.

## 3.1 design-target.md — what the sweeps changed

1. **Shard model is now concretely shaped by a verified fact:** `ShardBoundaries` are
   per-table (pure function of shardCount × partitioner × keyspace ranges × epoch;
   equal across tables only within one keyspace at one epoch —
   findings-i1 §2). Therefore: **node-global N shard threads, per-(table,key) shard-id
   computation from each table's current memtable's pinned boundaries** — NOT one global
   boundary object. Memtables pin boundaries for life; during a memtable switch two live
   memtables with different boundaries accept writes; local-system keyspaces get
   full-range boundaries. The design doc states this and the consequence: routing can
   never be assumed perfect → the owner-check-with-lock-fallback guard (I1) is a design
   principle, not an implementation detail.
2. **Shard runtime primitive pinned:** for the PoC, per-shard executors are
   `executorFactory().localAware().withJmx("request").sequential("MutationShard-"+i)`
   (ExecutorLocals propagation + ThreadPoolMetrics JMX for free); hand-rolled
   MPSC-inbox loops (`ManyToOneConcurrentLinkedQueue`, the netty-loop precedent) are the
   later optimization once the ring is integrated (I2 needs the shard loop to also drive
   the ring — that is the point at which `sequential()` gets replaced by a custom loop).
   The "swap Stage.MUTATION's executor" shortcut is REJECTED with evidence
   (opaque Runnables; the stage carries hints/paxos/batchlog/read-repair/replay traffic —
   findings-i1 §3).
3. **Accord coexistence (item 6) now has a THREE-way decision menu** (was two), from
   findings-accord-extension-points.md:
   - (a) **Inbox-route Accord's local applies/reads** through TPC shard inboxes — extra
     hop per apply; preserves I1/I4 single-writer; **removes two existing Accord
     blocking hazards** (memtable-pool stall `MemtableAllocator.java:170-198`, MV-lock
     sleep loop `Keyspace.java:516-529` — both violate AccordExecutor's own no-blocking
     contract `AccordExecutor.java:119-122`). An argument FOR that nobody had made.
   - (b) **Align the two shardings** — Accord already runs token-range-partitioned
     command stores (`command_store_shard_count` default = P, `AccordService.java:477`)
     with a `THREAD_PER_SHARD` executor mode (`AccordConfig.java:158`, not default).
     Make command-store splits use `ShardBoundaries` and pin executor:store 1:1 onto TPC
     shard threads — the shard thread IS the command-store thread. Newly visible option;
     must be explicitly evaluated.
   - (c) **Exempt accord-managed tables** from I1/I4 single-writer claims (status quo,
     owner-check fallback absorbs Accord writers; PoC measures non-Accord tables).
   Facts to carry into the section: Accord's final memtable apply runs on AccordExecutor
   threads via the SAME `Keyspace.applyInternal` (`TxnWrite.java:150`); no double-logging
   (makeDurable=false; AccordJournal is the WAL); Accord starts global writeOrder groups
   (`CassandraKeyspaceWriteHandler.java:47`) and writes its system tables under
   writeOrder from its own threads (`AccordKeyspace.java:361`); the two shard maps use
   DIFFERENT splitters today (EvenSplit-per-keyspace vs size-weighted local-range
   splits); migration-mode tables have two writer populations by design
   (`splitMutationsIntoAccordAndNormal`). **PoC pragmatics (pin now): the Phase 4
   workload runs on non-Accord tables; option (c) is the PoC's de facto state; the
   design doc must still choose the END-state (a)/(b) for the CEP.**
4. **Load-skew stance:** unchanged requirement; the per-shard backlog metric now has a
   concrete home — each `sequential()` executor's ThreadPoolMetrics PendingTasks gauge
   (free via `withJmx`), plus the new `misroutedPuts` counter as the boundary-health
   signal.
5. **Routing-point facts verified:** coordinator write `StorageProxy.java:1918→2025`
   (spec's `:1995` was the batchlog overload — corrected); replica write
   `MutationVerbHandler.applyMutation:83`; reads `AbstractReadExecutor.java:168` (holds
   `cfs` at `:77` — everything needed in scope); inbound
   `InboundMessageHandler.java:429`. Token knowable post-deserialize for MUTATION_REQ /
   single-partition READ_REQ; READ_REQ shares its serializer with RANGE_REQ →
   `instanceof SinglePartitionReadCommand` guard.
6. **What stays off-shard:** unchanged, plus two additions from the census — the
   **extension-point escape pool** (one new off-shard pool for custom `Index.Indexer` +
   triggers; auth/UDF already have pools — findings-accord §4) and **CDC's block-on-space**
   (`CommitLogSegmentManagerCDC.throwIfForbidden` — decide async-allocate vs accepted
   stall before I4 puts CommitLog.add on shard threads).

## 3.2 design-async-coordinator.md — inventory delivered, decisions remaining

The complete blocking-await inventory the spec's acceptance demands now EXISTS
(findings-i2-i3 §4: R1-R4 reads, W1-W6 writes, P1-P5 Paxos, M1-M6 misc — each with
parks-on / signalled-by / continuation shape). Key discoveries that reshape the design:

- **Timeouts are already async-capable on the two hot paths:** the Callback-Map-Reaper
  fires `onFailure(TIMEOUT)` via INTERNAL_RESPONSE because `ReadCallback` and
  `AbstractWriteResponseHandler` both override `invokeOnFailure()` → true
  (ReadCallback.java:285, AWRH.java:403, RequestCallbacks.java:149-158). The parked
  thread is redundant as a timer. Paxos v1 latches are NOT covered (default false).
- **Response flush is already thread-safe from arbitrary threads** (Flusher
  ConcurrentLinkedQueue + CAS-scheduled event-loop drain, Flusher.java:118-140) — the
  continuation CAN complete a request off-NTR. Remaining audit: FlushItem/payload
  release refcounts when the completing thread isn't NTR (pre-code verification task).
- **ClientWarn/Tracing (ExecutorLocals) do NOT follow responses** — REQUEST_RESPONSE
  runs with the replica message's locals (InboundMessageHandler.java:429). Continuations
  must capture request locals and install around completion; Dispatcher's warning
  attach/reset (:438/:466/:473) relocates into the completion helper.
- **Hint machinery does not depend on the blocking span**; idealCL accounting is already
  response/expiry-driven. Client-latency metrics + QueryEvents move to the continuation
  (RequestTime carries the timestamps).
- **Backpressure moves:** NTR queue-age signal (`Dispatcher.hasQueueCapacity`,
  :356-359) goes dead; nothing counts outstanding coordinator ops → design must add an
  outstanding-ops limit at dispatch (home: Dispatcher/ClientResourceLimits). This IS
  I3's micro counter.

Decisions this doc must make (menus in findings-i2-i3 OPEN QUESTIONS):
1. **Continuation executor:** strict-REQUEST_RESPONSE (requires converting every nested
   await in flagged paths — read repair R4 and digest-mismatch retry included — and
   tolerating INTERNAL_RESPONSE-delivered timeouts) vs a dedicated completion executor
   that tolerates residual blocking. The starvation rule: continuations on
   REQUEST_RESPONSE must be non-blocking all the way down (the pool is only P threads;
   the in-tree TCM-fetch blocking at ResponseVerbHandler:89-133 is a bug-shaped
   precedent, not a license).
2. **I3 PoC cut-line** (proposed, to ratify): single-partition reads + plain mutations
   only; Paxos (v1+v2), counters (Striped-lock+read W4), batch/batchlog (W3), truncate,
   auth (M4), schema ops stay blocking behind the flag.
3. **Extension points:** contract per §3.1 item 6 above — built-ins non-blocking;
   custom auth stays on authExecutor; triggers/custom-indexers get the escape pool.

## 3.3 design-hostiles.md — the decision menus, fact-forced

- **CommitLog (HOSTILE #2):** both options now have concrete inventories
  (findings-i4-i5 §2 + inventory tables in `../phase-4-poc/expected-changes.md`).
  The load-bearing facts: (i) CommitLogPosition is required BEFORE the memtable put
  (`CassandraKeyspaceWriteHandler.java:47-53`, `accepts` :101-108) — so option (b)
  [dedicated log-writer core] forces an async restructure of the beginWrite contract or
  a forbidden blocking hop; (ii) the segment-id allocator is ALREADY a global static
  (`CommitLogSegment.java:70-92`) — so option (a) [N per-shard managers] gets
  globally-unique, union-sortable segment ids for free and **replay works unchanged**;
  the only single-sequence assumption to fix is `discardCompletedSegments`'s early break
  (`CommitLog.java:382`). (iii) In default periodic mode, add() doesn't wait for fsync —
  (b)'s hop is pure allocation latency; (b) does NOT add fsync serialization but does
  add a per-mutation cross-core round trip + single-core memcpy ceiling.
  **Evidence points to (a); the doc decides ONE and writes it.** Sub-decisions: memtable
  CL-bound pairs (single conservative pair vs per-shard), sync-thread topology (1
  iterating N vs N), cap sharing (shared atomic recommended), CDC size tracker shared,
  failure policy stays global (per-shard isolation would be a semantic change).
- **writeOrder (HOSTILE #1):** full census delivered (findings-i4-i5 §3): 3 start()
  sites (incl. Accord's `AccordKeyspace.java:361`), 5 barrier/awaitNewBarrier sites
  (flush `ColumnFamilyStore.java:1247-1286`, `:3365`, segment force-recycle
  `AbstractCommitLogSegmentManager.java:371`, `CassandraIndex.java:681`,
  `DistributedSchema.java:365`). The MV comment's global-order requirement is NOT
  semantically required under shard routing (base+view share one group on the owning
  shard) provided every barrier site becomes composite over N orders and every start()
  is shard-attributable. `OpOrder.Group` has no owner back-ref (:143) → decide: owner
  field on Group vs shard id in `CassandraWriteContext` (recommend context — no
  public-class API change). Non-shard writers get the attribution rule:
  current-thread-shard else token-hash%N; the Accord case inherits the 3.1 choice.
- **Memtable memory (HOSTILE #4):** the sweep splits the contention in two
  (findings-i4-i5 §4): per-shard allocators remove the sibling `Region.nextFreeOffset`
  CAS but NOT the global `SubPool.allocated` CAS (`MemtablePool.java:155`) — that needs
  a shard-local slack-batching layer (step 2, optional, measure first). Cap/cleaner stay
  global. The doc scopes step 1 (allocator-per-shard) and conditions step 2 on
  measurement.

## 3.4 increments.md — harness + template updates

- The pinned harness facts now include: **MutationStage/ReadStage tpstats FREEZE under
  I5** (SEP gauges stop meaning anything flag-on) → per-increment counters must be
  memtable-level (I1 contended/uncontended/misrouted), messaging `internalLatency`
  (still recorded), and the new shard-executor ThreadPoolMetrics. The A/B recipe per
  increment lives in `../phase-4-poc/expected-changes.md` §7 (workload pins: `memtable:
  trie` — trunk default is skiplist!, `commitlog_sync: periodic`, `disk_access_mode:
  standard`, non-Accord tables, no MVs/2i on the bench tables).
- **I1's honest baseline:** today a local apply can run INLINE on the NTR thread with
  zero hops when a MUTATION permit is free (`SEPExecutor.maybeExecuteImmediately`);
  routing always costs one hop. The increment's p99 gate measures against that — state
  it in the increment entry.
- Dependency-edge addition: **I2 must consume the SAME boundaries source as I1**
  (`cfs.localRangeSplits(n)`); I5 requires the I5 dtest seam (shared executor-selection
  helper also called from `Instance.receiveMessageRunnable` — in-JVM dtest delivery
  bypasses `InboundMessageHandler` entirely, verified).

## 3.5 effort.md — new inputs

Verified size signals for banding: I1 ≈ 2 new classes + 5 modified files (smallest);
I2 ≈ 2-3 files modified + ring integration (ChannelProxy one-method seam); I3 = the
widest blast radius (ReadCallback/AWRH/StorageProxy/Dispatcher/Message + read-repair
conversion — the R4/nested-await requirement is the hidden cost); I4(a) ≈ 10
commitlog-internal files + composite-barrier changes at 5 sites + allocator step;
I5 ≈ 2 files + helper + dtest seam (smallest after I1). Risk-register additions from
the sweeps: Accord thread-budget re-derivation under TPC (~2P Accord threads + P shard
threads today), two-WAL device interference (AccordJournal fsync cadence inherits
`commitlog_sync_period`), CEP-45 mutation tracking not in tree yet (watch trunk).

## 3.6 TPC hotspot inputs from Enberg ANCS'19 (`../findings-tpc-paper.md`)

Paper-sourced evidence lines each design doc must absorb:

- **design-target.md — name the model:** ours is **shared-something** in the paper's
  taxonomy — per-shard memtable shard / commitlog manager / OpOrder / allocator
  (shared-nothing slices) atop shared SSTables, chunk cache, and maintenance pools.
  One-line pre-answer to "why not full shared-nothing like Scylla": skew ceiling +
  Cassandra's page-cache reliance. Skew stance (3.1 item 4) cites the paper's
  shared-nothing hot-core ceiling instead of re-deriving it.
- **Shard loop (3.1 item 2) — the wake-up is the unit of steering cost** (their #1
  held-back-by finding; wake ≈ µs vs enqueue ≈ tens of ns). Two consequences for the
  custom loop that replaces `sequential()` at I2b: (i) the **idle strategy is the
  hotspot** — park-after-every-drain pays a futex wake per message at low load, pure
  spin burns the core (their Shenango critique); adaptive spin→yield→park
  (netty/Aeron precedent), with park/unpark rate as an observable. (ii) **MPSC inbox
  producer contention** is the known next bottleneck: Sphinx used per-producer-pair
  SPSC queues to avoid producer cache-line sharing; our single MPSC tail takes CAS
  contention from P producers — fine for the PoC, record as a measured risk.
- **design-async-coordinator.md (3.2 decision 1):** steering cost favors completing
  continuations on the thread already holding the response cache-hot
  (REQUEST_RESPONSE) over adding one more hop to a completion pool — *iff* the
  non-blocking-all-the-way-down rule holds. If blocking risk forces the pool, the
  extra wake per response is a conscious, sized cost.
- **design-hostiles.md (3.3):** commitlog option (b)'s per-mutation cross-core round
  trip is exactly the paper's message-passing weakness (wake-ups + payload copies) —
  one more independent reason the evidence favors (a).
- **Load-skew stance:** unchanged, now with a citation and a prediction — under skew
  the hot shard's PendingTasks gauge is the leading indicator, `misroutedPuts` the
  boundary-health check.

## Open questions this phase must close (rollup)

The full per-increment lists live in the three phase-4 findings files; the ones that are
genuinely DESIGN decisions (not implementation choices already pinned in
`../phase-4-poc/expected-changes.md`):

1. Accord end-state: (a) inbox-route / (b) align shardings / (c) exempt — §3.1 above.
2. Commitlog architecture: (a) per-shard managers vs (b) log-writer core — §3.3 above
   (evidence favors (a)).
3. I3 continuation executor: strict-REQUEST_RESPONSE vs dedicated completion pool.
4. I3 PoC cut-line ratification (single-partition reads + plain mutations).
5. writeOrder shard-identity carrier: Group field vs CassandraWriteContext (recommend
   context).
6. Skew stance (unchanged from spec) + whether shard threads oversubscribe cores in the
   PoC (I1 open question #1 — affects every increment's measurement).
