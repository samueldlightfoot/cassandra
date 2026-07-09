# Adversarial review — design-hostiles.md (2026-07-09)

Reviewer posture: refute, not admire. Every load-bearing claim re-verified against the
working tree (fresh greps + direct reads, file:line cited). Sections per review angle;
findings tagged [BLOCKER]/[GAP]/[NIT].

---

## A. The carrier overturn (owner-on-Group vs shard-id-in-context)

**Verdict: overturn JUSTIFIED — but on partially misstated evidence.**

- **Cross-instance isAfter-garbage claim: VERIFIED.** Group ids are per-OpOrder-instance,
  starting at 0 (`OpOrder.java:174-183`) and `Barrier.isAfter` is raw id subtraction
  (`orderOnOrBefore.id - group.id >= 0`, `:377-382`). Two OpOrders' id spaces overlap;
  evaluating a foreign group returns garbage, not an error. Iterate-and-try is indeed
  impossible. Sole `isAfter` caller on writeOrder barriers is
  `AbstractMemtableWithCommitlog.accepts:85` (fresh grep) — the dispatch-site claim holds.
- [GAP] **"the context is not in scope there" is imprecise — and hides the stronger
  argument.** `CFS.apply` LITERALLY takes the context:
  `apply(PartitionUpdate update, CassandraWriteContext context, boolean updateIndexes)`
  (`ColumnFamilyStore.java:1515`), extracts group+position at `:1519-1520`, and calls
  `data.getMemtableFor(opGroup, commitLogPosition)`. So a context-carried shard id IS in
  scope one frame above the barrier check and could be threaded down. What actually kills
  the context option is that threading it requires changing `Tracker.getMemtableFor` AND
  `Memtable.accepts(OpOrder.Group, CommitLogPosition)` — the **public pluggable Memtable
  API** (`Memtable.java:375`, CEP-11 surface) — a breaking interface change for
  out-of-tree memtable implementations, vs an additive package-internal field. The doc
  should make THAT the stated evidence; as written, a reviewer who opens CFS.apply sees
  the claim falsified at first glance.
- **Costs: adequately stated.** Group construction verified at exactly two sites
  (field-init `:97`, barrier swap `:399`) — per-barrier-epoch, cost-nil claim holds. One
  mechanical note the doc glosses: `Group` is a `static final class` (`:143`), so the
  owner must be passed explicitly at both construction sites (no implicit outer
  reference) — trivial, consistent with "wiring is mechanical".
- **readOrdering shares the Group class** — the added field is inert for plain OpOrders
  (readOrdering, per-segment `appendOrder`, `HintsBuffer.appendOrder`, journal orders);
  no non-write-path cost found. §1.6's reuse claim survives this angle.

## B. Composite barrier correctness (5 sites)

- **Census: EXACT MATCH.** Fresh grep for `writeOrder` finds precisely 3 `start()` sites
  (`CassandraKeyspaceWriteHandler.java:47,:107`, `AccordKeyspace.java:361`) and 5
  barrier sites (`ColumnFamilyStore.java:1247`, `:3365`,
  `AbstractCommitLogSegmentManager.java:371`, `CassandraIndex.java:681`,
  `DistributedSchema.java:365`). No misses.
- **Phase-separation reasoning: SOUND.** Verified against `isAfter` semantics — an
  un-issued barrier returns true for all groups (`OpOrder.java:377-378`), so a write
  landing on a not-yet-issued constituent during the issue loop is classified pre-barrier
  and awaited; issue-all-before-await-all is exactly the needed invariant. Site 1's
  today-sequence (`Flush` ctor: newBarrier `:1247` → switchOut `:1260` → seal `:1266` →
  issue `:1271`; `Flush.run`: markBlocking+await `:1285-1286`) maps cleanly; sites 2-5
  are awaitNewBarrier (issue+await) — uniform treatment preserves each site's semantics.
  Composite issue takes N monitors strictly sequentially — no lock-ordering hazard.
- [GAP] **Site 3's trigger is misattributed.** §1.3 labels it "Rare path (cap pressure)"
  and §1.5 says "§2.3's shared cap keeps its frequency at today's level." Wrong:
  `forceRecycleAll` (which contains the `:371` awaitNewBarrier) is triggered by **table
  drop** (`ColumnFamilyStore.java:3360`, the CASSANDRA-16986 resurrection guard) and
  `StorageService.java:4022` — cap pressure goes `maybeFlushToReclaim → flushDataFrom`
  and never touches writeOrder. Frequency/hazard framing in §1.5 needs correcting (the
  hazard itself — a shard thread parked in `awaitAvailableSegment` during a
  force-recycle — is real but drop-table-correlated, not cap-correlated).
- [NIT] Under option (a), `CommitLog.forceRecycleAllSegments` iterates N managers, each
  calling the composite awaitNewBarrier → N sequential composite awaits per drop.
  Harmless (rare path), unstated.

## C. Forgotten writers/readers

- **writeOrder census: complete** (angle B grep). No missed start/barrier sites in
  streaming, view build, sstable import, or repair — those paths reach writeOrder only
  through `beginWrite`/`createEmptyContext`, which the attribution rule covers.
- [NIT] **"these paths are rare" (rule 3) is wrong for one caller:**
  `createContextForRead` is invoked per-read for 2i-indexed reads
  (`ReadExecutionController.java:152`) — per-op on 2i workloads, not rare. Correctness
  unaffected (composite awaits all N; rule 3 spreads); the bench has no 2i. Also
  uncensused rule-3 consumers: `CompactionTask.java:631`,
  `SecondaryIndexManager.java:1118/:1763/:1833`, and **`AccordKeyspace.java:399`**
  (createContextForRead — the doc's Accord census cites only `:361`).
- **Legacy-2i pass-through checked:** `CassandraTableWriteHandler.write` passes the BASE
  write's context/group into `indexCfs.apply` (`CassandraIndex.java:547` →
  `CassandraTableWriteHandler.java:34-40`). Owner-dispatch works (same group, same
  constituent); attribution mismatch (base-key shard vs value-key index shard) is
  efficiency-grade per §0. No composite break — but note the index memtable's CL-position
  check reuses the BASE position, so it inherits finding D1 below identically.
- [GAP] **§1.6's readOrdering barrier census is incomplete.** Doc lists four sites
  (CFS `:1442,:3366,:3515`, CassandraIndex `:683`); fresh grep finds a fifth:
  **`service/accord/journal/SegmentCompactor.java:61`**
  (`cfs.readOrdering.awaitNewBarrier()`). CEP-era relevance only (readOrdering is
  deferred), but the "made concrete" fate row is quoting a wrong census.

## D. CommitLog (a) sub-decisions

- [BLOCKER] **Sub-decision (i) — the single conservative CL-bound pair — is UNSAFE, not
  conservative. It produces silent data loss.** The whole memtable/commitlog coverage
  protocol rests on an invariant stated verbatim in the code the doc cites: *"Because we
  issue the barrier after taking LastCommitLogPosition and mutations take their position
  after taking the opGroup, this condition also ensures the given commit log position is
  greater than the chosen upper bound"* (`AbstractMemtableWithCommitlog.java:81-84`) —
  i.e. **position order = time order**. That holds per single append sequence; it is
  FALSE across N managers, whose active segment ids skew (manager i filling segment 100
  while manager j is on 105). Concrete loss chain, every step verified:
  1. Flush seals `U = LastCommitLogPosition(max over managers) = (105,p)`
     (`ColumnFamilyStore.java:1467-1480`), issues the composite barrier.
  2. Post-barrier write W attributed to shard i: rejected by `isAfter` (`accepts:85`) →
     NEW memtable — with position **(100,q) < U** from manager i's still-active segment.
  3. Old memtable flushes; its sstable claims covered interval `[lower, U] ∋ (100,q)`.
  4. `discardCompletedSegments(T, lower, U)` with the doc's own id-terminated fix reaches
     manager i's segment 100; `markClean(T, lower, U)` computes
     `end = Integer.MAX_VALUE` because `100 < U.segmentId`
     (`CommitLogSegment.java:558-567`) → the ENTIRE segment is marked clean for T,
     **including W**. Once segment 100 stops allocating, `removeCleanFromDirty` frees it
     → segment deleted while W exists only in the unflushed new memtable.
  5. Independently, even with the segment on disk, `CommitLogReplayer`'s per-table
     covered-interval filter skips (100,q) as "already persisted". Crash before the new
     memtable flushes ⇒ W is gone.
  The doc's two safety claims are each wrong in direction: "markClean over-coverage per
  table is harmless" (§2.2-i) misses post-barrier writes below max-upper in laggy
  managers, and "at worst it replays some already-flushed positions" (§2.4) is the exact
  opposite of the actual failure (it SKIPS unflushed positions). Cross-manager
  CommitLogPositions are *comparable* but not *time-ordered* — the doc's "globally
  comparable (§2 shared id namespace)" (§1.3 site 1) conflates the two. **Per-shard bound
  pairs are correctness-required, not a reclamation optimization** — the doc's rejection
  framing ("correct reclamation... unmeasured win") mis-scores the alternative. Note the
  existing sstable metadata field is an `IntervalSet<CommitLogPosition>` — it can carry N
  per-manager intervals without a format change; the invasive part is memtable/CFS
  plumbing, which is exactly what must be re-costed. The id-terminated discard fix is
  only safe AFTER bounds are per-manager (as specified today it widens the loss surface
  from one manager's segments to all N).
- [GAP] **`CommitLog.getCurrentPosition()` under N managers is never defined**, and its
  four callers need different answers: the flush seal (`ColumnFamilyStore.java:1475`),
  memtable `approximateCommitLogLowerBound` (`AbstractMemtableWithCommitlog.java:36`),
  offline memtable creation (`CFS:528`), replayer filter (`CommitLogReplayer.java:160`).
  A max definition feeds the blocker; a min definition livelocks the
  `setCommitLogUpperBound` CAS loop (it retries until `getCurrentPosition() ≥` the
  tentative CAS-maxed bound — unreachable if in-flight writes on a leading manager have
  already raised it). Also `mayContainDataBefore` (`AMWC:123-126`, consumed at
  `CFS:1131` by `flushDataFrom` — the force-recycle/cap-reclaim path) compares a
  manager-i-derived approximate bound against a manager-j-derived recycle position:
  cross-manager, not time-meaningful → force-recycle can skip flushing a table whose
  data sits in a laggy manager's old segments. All of this is the same root cause as the
  blocker and must be resolved by the same per-manager-bounds rework.
- [GAP] **"in the required periodic mode nobody waits on sync latency" (§2.2-ii) is
  overstated.** `PeriodicCommitLogService.maybeWaitForSync` parks ANY writer — including
  shard threads — whenever `lastSyncedAt` lags `blockWhenSyncLagsNanos`
  (`PeriodicCommitLogService.java:36-44`), and `lastSyncedAt` advances only after a FULL
  sync pass (`AbstractCommitLogService` SyncRunnable sets it after `commitLog.sync(true)`,
  `:185-190`). One thread doing N sequential fsyncs multiplies pass duration ~×N, so the
  probability of crossing the lag threshold — a node-wide, C5-grade shard-thread park —
  grows with N. The doc's CEP-era "N sync threads knob if the pass lags sync_period"
  under-classifies this: the observable (`waitingOnCommit` + sync-lag warnings) should be
  a named PoC watch item with the ×N mechanism stated, since the doc's own H2 test
  strategy would not exercise slow-fsync devices.
- **Replay union-sort: VERIFIED.** `recoverSegmentsOnDisk` directory-lists and sorts by
  segment id via `CommitLogSegmentFileComparator`; filenames are id-unique. Claim holds.
- **CDC: VERIFIED.** `CDCSizeTracker` is per-manager-instance today
  (`CommitLogSegmentManagerCDC.java:54-59`) and all N managers hard-link into one
  cdc_raw whose size the DirectorySizeCalculator-based recalc scans — sharing ONE tracker
  is indeed required and the doc's sub-decision (iv), including the
  cdc-exclusion-predicate interaction with design-target D3 item 4, checks out.
- **Shared cap (iii) and global failure policy: consistent with the tree**
  (`unusedCapacity` reads full total against own `size`; `handleCommitError` global).
  No findings.

## E. Memtable memory (H4)

No blocker. Verified: one allocator per memtable (`AbstractAllocatorMemtable.java:120`)
handed to every shard (`TrieMemtable.java:129,:140-141`); no external `getAllocator()`
consumers outside the memtable hierarchy (grep clean); `SubPool.tryAllocate` is
addAndGet-with-post-correction (`MemtablePool.java:153-170`) — it already transiently
exceeds `limit` by design, so 1 MiB slack chunks acquired through the same path break no
`used ≤ limit` invariant, and `needsCleaning` on global `allocated` (slack-inclusive)
is conservative exactly as the doc claims. Flush/discard tie to the H1 composite (§3.3)
is consistent with design-target's flush story and correctly identifies why await-all
(not await-own) is required for discard safety.

- [NIT] The slack bound "N shards × 1 MiB × 2 subpools per active memtable" is
  per-TABLE; a many-user-table deployment multiplies it by active-memtable count. Still
  small vs multi-GiB pools; state the multiplier.

## F. Internal + cross-doc consistency

- [GAP] **The carrier overturn silently contradicts design-target's accepted text in two
  places** — §8.1 row 1 ("shard identity travels in `CassandraWriteContext`") and the D7
  fate table ("carried in `CassandraWriteContext` (I4 pin)") — the very rows this doc's
  header declares "constraints here, not reopened." The overturn is right (angle A), but
  the doc must carry an explicit design-target amendment note (precedent: design-target
  §9 normatively amends phase-4 §1) rather than leaving two accepted documents asserting
  opposite carriers.
- **threadId%N third tier:** a necessary refinement (rule-2 requires a token;
  `createEmptyContext` has none), not a contradiction — but it amends design-target's
  stated two-tier rule and belongs in the same amendment note.
- **Group-commit verdict:** consistent with design-target D3 item 4 (flag-on+batch
  disables routing at startup, loudly; async group-commit CEP-era). No contradiction.
- **§1.6 "zero new machinery":** survives angle A (the owner field ships with I4), modulo
  the SegmentCompactor census miss (angle C).

## G. Acceptance audit (spec §3.3)

- H1: chosen design ✓ (§1 CHOSEN), failure/upgrade ✓, test strategy ✓.
- H2: failure/upgrade ✓, test strategy ✓ — but the CHOSEN design's sub-decision (i)
  fails on correctness (D blocker), so "each note ends with a chosen design" is met in
  form, not in substance, for H2. The H2 test list would likely CATCH the bug only by
  luck (the replay-union test hard-stops after writes but doesn't stage the
  flush-then-write-then-crash sequence across skewed managers) — add a targeted test:
  skewed managers, flush T, post-flush writes to T on the laggy manager, crash, assert
  presence after replay.
- H4: chosen ✓, failure/upgrade ✓, tests ✓.
- H3: correctly pointed at design-target D1 ✓. H5: defer rationale + un-defer trigger ✓.

---

## Verdict: **FAIL**

One BLOCKER: **H2 sub-decision (i)** — the single conservative CL-bound pair (and the
undefined `getCurrentPosition` semantics underpinning it) is a silent-data-loss design,
not a conservative one; per-manager bounds (or a vector upper bound via the existing
`IntervalSet` sstable metadata) are correctness-required, and the id-terminated discard
fix is only safe on top of them. §2.2-i, §2.4's replay-safety direction, §1.3 site 1's
"globally comparable" note, and the H2 test list all need rework together.

Fixes required alongside (would otherwise be PASS-WITH-FIXES):
1. Restate the carrier-overturn evidence: context IS in scope at `CFS.apply:1515`; the
   binding constraint is the public `Memtable.accepts` interface (A).
2. Add the explicit design-target amendment note for the carrier + threadId%N tier (F).
3. Correct site 3's trigger (table drop, not cap pressure) in §1.3/§1.5 (B).
4. Reclassify the periodic sync-lag park as a named PoC watch item with the ×N
   sequential-fsync mechanism (D).
5. Add `SegmentCompactor.java:61` to the §1.6 readOrdering census; correct "rare" for
   `createContextForRead` on 2i reads; add `AccordKeyspace.java:399` to the Accord
   census (C).
