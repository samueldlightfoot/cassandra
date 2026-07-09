# Adversarial review — design-target.md (2026-07-09)

Reviewer scope: spec.md §2.1/§2.2/§3.1; expected-changes.md §3.1/§3.6; the two raw
sweeps (findings-shared-state.md, findings-arrival-topology.md); phase-2 verdict.md +
../findings.md §5.1; phase-4-poc/expected-changes.md §0–§3/§7.5/§8. All code claims
below re-checked against the working tree this session (two sub-agent sweeps + direct
spot-checks); file:line cited where the doc and the tree disagree.

Tags: [BLOCKER] design wrong or acceptance unmet · [GAP] missing state/path needing a
stated fate · [NIT].

---

## A. Forgotten state (full findings-shared-state.md walk, not just spec §2.2)

- **[GAP] Per-table read OpOrder `readOrdering` has no fate.** Raw sweep §2, first
  bullet: `ColumnFamilyStore.java:299` `public final OpOrder readOrdering`, started for
  EVERY read in `ReadExecutionController.forCommand` (`ReadExecutionController.java:137,
  145-146` — note the 2i case starts TWO groups, base + index CFS) and held for the
  whole query; barrier users at `CFS.java:1442` (snapshot/discard), `:3366`
  (`awaitNewBarrier`), `:3515`. This is the read-path twin of HOSTILE #1 — a per-table
  shared CAS hit by all N shards on every read — and §8.1 does not mention it (the
  spec's condensed §2.2 dropped it; the doc inherited the omission). It needs a stated
  fate (per-shard orders + composite barriers like writeOrder, or
  stays-global-atomic-with-rationale), AND a lifecycle statement for the I2 reschedule:
  when a shard-side read aborts at the rebufferer and the whole task resubmits to the
  I/O pool (§3 item 5), the aborted attempt's ReadExecutionController/readOrdering
  group must be closed and a new one started on the pool thread — group-across-threads
  handling is unstated.
- **[NIT]** Per-manager `SimpleCachedBufferPool` (sweep §1, commitlog bullet): N
  per-shard segment managers ⇒ N segment-buffer pools; the memory multiplication isn't
  in §8.1 row 2's sub-decision list (cap, sync topology, failure policy are). One line
  for design-hostiles.
- All other raw-sweep rows map to a §8.1 fate or are read-only/immutable (checked:
  MV/counter locks, Tracker, Ref counting, ChunkCache/BufferPool/CacheService incl.
  row-cache-invalidation deferral, metrics, Schema/TCM/Tracing/Gossiper/MonitoringTask,
  PartitionDenylist, per-segment appendOrder → subsumed by per-shard segments).

## B. Forgotten paths (writer-population census vs the doc)

Census method: traced every non-standard `Keyspace.apply`/`mutation.apply` entry in
src/java (sub-agent sweep, 23 populations). Most are covered by §3's blanket
("everything off-shard … covered by the owner-check fallback") — that blanket is legal
under D1's principle. The exceptions:

- **[BLOCKER] CDC block-on-space lands on shard threads at I1, not I4** (§3 item 4:
  "under I4 that would land on a shard thread"). Wrong: `CommitLog.instance.add` runs
  inline in `beginWrite` on the APPLYING thread
  (`CassandraKeyspaceWriteHandler.java:99`), and `CommitLogSegmentManagerCDC.allocate`
  calls `throwIfForbidden` at `:178/:186`. The moment I1 routes an apply to a shard
  thread, the whole commitlog add — including the CDC block — runs there. The DECISION
  (cdc=true excluded by routing predicate) is right, but the stated timing licenses an
  implementer to defer the exclusion to I4; and phase-4 §1's `MutationShardRouting`
  predicate (the I0 build spec) does NOT list the cdc check (only sharded-memtable /
  boundary-agreement / `updatesAffectView` / local-system). Fix: correct the timing
  sentence and state that this doc AMENDS the phase-4 §1 predicate (see E1).
- **[GAP] Hint application contradicts D4's "these five are the only routing points".**
  §5's hints row claims delivery applies "route per-key through shard inboxes" citing
  `HintsDispatcher.java:284` — but (i) that site is the local-self/rehint corner on the
  DISPATCHING node (comment at `:276-283`), and it is not one of D4's five points; (ii)
  the dominant delivery apply is on the RECEIVING node via `HintVerbHandler`
  (`HINT_REQ` → Stage.MUTATION, `Verb.java:206`; `hint.applyFuture()` at
  `HintVerbHandler.java:110`) — HINT_REQ is not on the I5 allowlist and the handler is
  not a routing point, so in the PoC hints do NOT route; they hit the fallback. Either
  add routing points (dispatcher local-apply site + HintVerbHandler body, mirroring
  MutationVerbHandler) or restate the row's PoC column as fallback-only. As written,
  D4 and D5 disagree.
- **[GAP] 2i index-table memtable writes have no stated fate — and they poison the D6
  discriminator.** Normal-path legacy-2i writes apply a mutation to the INDEX CFS
  inline on the applying thread (`CassandraIndex.insert` `:536` →
  `indexCfs.getWriteHandler().write` `:547` → `cfs.apply`), keyed by the indexed VALUE
  — a different token than the base key, so on a shard thread these index-memtable
  puts are systematically off-owner: correct via tryLock fallback, but every one
  increments `misroutedPuts`, which D6 designates as the flat-line that distinguishes
  skew from stale routing. Any 2i-bearing table makes that signal permanently non-flat.
  Also, §3 item 3's "built-in indexers … are certified non-blocking" is contradicted by
  this same path: `indexCfs.apply` can park on MEMORY_POOL `hasRoom`
  (`MemtableAllocator.java:170-198`) and block in commitlog allocation — the exact
  hazards the doc enumerates for Accord. Needed: a fates-table-style line for index-CFS
  writes (exclude 2i-bearing tables from routing like CDC/MV, or accept off-owner +
  exempt index tables from the misroutedPuts signal), and downgrade "certified
  non-blocking" to "no blocking beyond the standard write-path budget". (SAI is fine:
  it writes parallel per-memtable concurrent index structures, not the memtable —
  `StorageAttachedIndex.java:1026-1028` — see [NIT] below.)
- **[GAP] Commitlog `batch`/`group` sync modes block the applying (= shard) thread on
  fsync for every routed write** (`CommitLog.java:337` per phase-4 §0's own pin). The
  bench pins `periodic`, but the DESIGN states no fate for the other modes — same
  on-shard-blocking class the doc handles for CDC and MV. One sentence needed (exclude
  by predicate when sync mode is batch, or declare it an accepted stall + I4 concern).
- Covered adequately (verified, no finding): read-repair replica applies
  (READ_REPAIR_REQ → MUTATION, fallback; listed in §1's populations); commitlog replay
  (`CommitLogReplayer.java:330`, Stage.MUTATION multi-thread → fallback; §1 lists
  "replay"; replay-order under I4 delegated with evidence); batchlog replay local
  applies (`BatchlogManager.java:618` → fallback; end-state in §5); view build
  (`ViewBuilderTask` → `mutateMV` local apply `StorageProxy.java:1175`, MV tables
  excluded); streaming write-path applies (`CassandraStreamReceiver.java:245`
  `requiresWritePath` = CDC/MV/streamToMemtable — all excluded table classes →
  fallback); truncate (TRUNCATE_REQ → MUTATION stage; memtable discard rides
  flush/barrier machinery — composite-barrier census is design-hostiles' §8.1-row-1
  scope); paxos commit apply (§5 row); schema/system-table writes (local-system
  keyspaces excluded, full-range boundaries); internal CQL applies (fallback).
- **[NIT]** SAI per-memtable index structures remain multi-writer concurrent structures
  under TPC (all shards write them); not in the sweep, but a one-line
  stays-shared-with-rationale would preempt the obvious CEP reviewer question.
- **[NIT]** Drop+recreate same-name table: verified no stale-boundary hazard (new CFS,
  fresh `cachedShardBoundaries` — `Keyspace.dropCf`/`createColumnFamilyStore`); the doc
  never says it, but D1's "from THIS memtable's own boundaries" makes it moot. No
  action.

## C. Internal consistency (D1–D7 cross-check)

- **[GAP] D6's pre-identified hot-shard remedy is arithmetically inert under D1's own
  mapping.** D6: "shard threads are also outnumber-able vs cores if a hot shard needs
  splitting (N is a config, not an architecture)". But shard-id =
  `getShardForKey(key) % N` with per-table shardCount defaulting to cores (D1): once
  N ≥ table shardCount the mapping is the identity, so raising N adds idle threads and
  the hot memtable shard still lands on exactly one thread. Actually splitting a hot
  shard requires raising the TABLE's `shards` option above N — which is
  memtable-pinned and only takes effect at a memtable switch — none of which the doc
  says. The revisit-trigger remedy list should name the real lever (per-table shard
  count + memtable switch, or boundary re-splitting), not N.
- **[NIT]** §8.2 hop 3 says "REPLACED" flatly; it is replaced only for routable traffic
  — the §5 exclusion list (views, counters, CDC, batchlog via :1995, non-sharded
  memtables…) keeps flowing through `maybeExecuteImmediately` at the same sites. Row 5
  gets the partial-replacement wording right; row 3 should match.
- **[NIT]** §1 names executors `"Shard-"+i` while claiming to restate expected-changes
  §3.1 item 2, which pins `"MutationShard-"+i`. It matches phase-4 §1's working name
  instead. Pick one and say the pin was amended.
- **[NIT]** §3 item 5 reasons about "N≈8 shard threads" and sizes the pool "default 8";
  D1 says N = cores and the G1 baseline machine is 12-core. State which P the sizing
  assumed or make it cores-relative.
- **[NIT]** D1 declares oversubscription "accepted for I1" citing phase-4 §8 item 5 —
  but that item is flagged USER and "must be pinned with the criteria before any
  increment code". The doc adopts the recommendation without noting it still needs
  user ratification.
- D2/D3 pool-in-both-arms, §8.1-vs-§7 (writeOrder attribution, commitlog/AccordJournal,
  MV via predicate, ChunkCache arm-B addendum), D5-vs-D4 (except hints, above), and
  §8.1 row 3's "removed" (mismatch removed, lock retained) are all consistent —
  checked pairwise, no further findings.

## D. Code contradiction (working-tree verification)

The five load-bearing routing claims all CONFIRMED in the tree: StorageProxy `:1918` →
performLocally general overload `:2023-2066` (mEI `:2025`), `:1995`-area overload's
callers are exactly batchlog `:1659/:1675`; AbstractReadExecutor `:168` with `cfs` at
`:77`; MutationVerbHandler `:80-83` applyFuture().addCallback; InboundMessageHandler
`:429` + on-loop small deserialize `:161-216`; SelectStatement `:821/:839/:847`. Also
confirmed: Verb MUTATION_REQ `:202`, READ_REQ/RANGE_REQ shared serializer `:228-230`,
Accord verbs IMMEDIATE `:326-383`; MessagingService.send / OutboundConnection.enqueue
`:336` any-thread; Flusher CLQ+CAS drain; SEP queues unbounded `SEPExecutor.java:104-108`;
AccordExecutor no-blocking contract `:119-122`; TxnWrite `:150`; shutdownBeforeCommitlog
`Stage.java:163-184`; trigger site `:1250`; CDC `throwIfForbidden :215-231`;
discardCompletedSegments break `CommitLog.java:382`. Contradictions found:

- **[BLOCKER]** (same as B1) — "CDC … under I4 would land on a shard thread": the tree
  says I1 (`CassandraKeyspaceWriteHandler.java:99` runs CommitLog.add on the applying
  thread).
- **[GAP]** (same as B3) — "built-in indexers … certified non-blocking": CassandraIndex
  writes through full `cfs.apply` on the applying thread and can park
  (`MemtableAllocator.java:170-198`); the certification is false as stated.
- **[NIT]** Paxos v1 contention sleeps cited at `StorageProxy.java:849/:962/:1115/:1164`
  — none of those lines sleep (`:849` is a PAXOS_PROPOSE stage lambda, `:1115` is
  mutateMV). The actual `sleepUninterruptibly` sites are `:643/:710/:749`. Semantics of
  the paxos exclusion hold; the doc's header claim that ALL load-bearing refs were
  re-verified 2026-07-09 is falsified by this row.
- **[NIT]** `PaxosState.java:88` is the class declaration; the (key,table) keying
  evidence is the `ACTIVE` map at `:94` with `Key` at `:154`.
- **[NIT]** `RangeCommandIterator.java:223` uses `Stage.READ.execute`, not mEI —
  consistent with the doc's point, just noting for precision.

## E. Buildability (I0/I1 from this doc + phase-4 §1 alone)

- **[GAP] §9's "this document's decisions instantiate exactly phase-4 §1's I0" is false
  as written**: the §5 routing predicate adds a cdc=true exclusion that phase-4 §1's
  `MutationShardRouting` spec does not contain. An implementer told (§9, spec §3.1)
  that phase-4 §1 is the authoritative I0 inventory builds the predicate without it.
  Either edit phase-4 §1 or add an explicit "this doc amends phase-4 §1's predicate:
  + cdc=true check" line.
- **[NIT]** §3 item 5's arm-A `RWF_NOWAIT` inline probe is "optional (design-level)" —
  a decision that looks made but has no owner or adjudication cell (nothing in phase-4
  §7 decides whether the PoC builds it). Assign it (build/skip, or name the profile
  observation that triggers it).
- **[NIT]** The Phase 4 test matrix is implied but never stated: two independent axes
  exist (arm A vs arm B; pool-dispatch default vs pure-per-shard-ring comparator) and
  the doc uses "the A/B" for both. One sentence enumerating the cells Phase 4 must run
  (pool×A, pool×B, ring-comparator×?) would prevent a mis-built bench plan.
- Otherwise yes: I0/I1 are buildable from this doc + phase-4 §1/§2 — executor recipe,
  N, owner registry, shutdown ordering, predicate (with the fix above), fallback
  semantics, misroutedPuts, honest-baseline note are all pinned. Deferred items
  (commitlog option formal decision, ShardedOpOrder mechanics, I3 executor choice) are
  properly other documents' scope per spec §3.3/§3.2, not deferrals-in-disguise.

## F. Acceptance audit (spec §3.1 acceptance, item by item)

1. "Every §2.2 row has a stated fate" — **MET** (8/8 in §8.1, each tagged
   owned-by-shard / message-passed / stays-global-atomic). Note the letter is met but
   the raw sweep exceeds §2.2 — see A's readOrdering gap.
2. "Every §2.1 handoff point mapped to removed/kept/replaced" — **MET** (8/8 in §8.2;
   hop 3 wording imprecise, see C).
3. "Skew stance chosen with its trigger metric" — **MET** (strict ownership;
   PendingTasks ≥10× median >1 s + flat misroutedPuts) — but the trigger's
   misroutedPuts leg is degraded on 2i-bearing tables (B3) and the named remedy is
   inert as written (C1).
4. "Accord coexistence choice stated with shared-state enumeration" — **MET** ((a)
   chosen, 6-row enumeration, (b)/(c) dispositioned, residual risks named).
5. "State the ceiling explicitly … reviewers learn it from the doc" — **MET** (§0).
6. "No open questions bar" — **MET with exceptions**: the RWF_NOWAIT "optional" (E2)
   and the un-ratified oversubscription USER item (C-nit) are open questions wearing
   decided clothes.

---

## VERDICT: PASS-WITH-FIXES

- [BLOCKER] CDC block-on-space timing wrong ("under I4" — it lands on shard threads at
  I1 via `CassandraKeyspaceWriteHandler.java:99`); cdc exclusion missing from phase-4
  §1's predicate spec that §9 claims to instantiate exactly.
- [GAP] Per-table `readOrdering` OpOrder (CFS.java:299, every read) has no §8.1 fate;
  I2 pool-resubmit group lifecycle unstated.
- [GAP] Hints: D5's row contradicts D4's "only five routing points" — HintVerbHandler
  (HINT_REQ→MUTATION, :110) and HintsDispatcher:284 are fallback, not routed; row must
  be restated or points added.
- [GAP] 2i index-CFS memtable writes (different token, inline via CassandraIndex:547):
  no stated fate; permanently inflate `misroutedPuts` (breaks D6's discriminator);
  "certified non-blocking" is false for CassandraIndex.
- [GAP] Commitlog batch/group sync modes block shard threads per write — no stated
  fate/exclusion (only a bench pin).
- [GAP] D6's hot-shard remedy ("outnumber threads vs cores") is inert under the
  `% N` mapping with per-table shardCount = cores; the real lever (per-table shards
  option + memtable switch) is unstated.
