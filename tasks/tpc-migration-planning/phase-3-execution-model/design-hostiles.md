# Phase 3.3 — Hostile Remediation Notes (design-hostiles.md)

**Status:** decided 2026-07-09. Inputs: spec.md §3.3 (scope + acceptance);
expected-changes.md §3.3 and §3.6 (decision menus, paper evidence); the raw inventories
in `../phase-4-poc/findings-i4-i5-commitlog-dispatch.md` (writeOrder census §3,
commitlog facts §1-§2, memtable memory §4, adversarial findings §7); design-target.md
(accepted 2026-07-09 — D2, D3 item 4, D5, D7, §8.1 fate rows are constraints here, not
reopened); `../findings-tpc-paper.md` §2-§3 (Enberg ANCS'19 — steering cost, Sphinx
LSMA prior art).

All load-bearing file:line refs re-verified against the working tree 2026-07-09.
Drift found vs findings-i4-i5: `DistributedSchema.java` lives at
`schema/DistributedSchema.java:365` (findings cited a tcm/compatibility path; line
correct); `CommitLogSegment.getNextId` is `:143` (was `:145`);
`advanceAllocatingFrom` is `:298` (was `:297`); the discard early break is `if` at
`CommitLog.java:382`, `break` at `:383`; `Keyspace.applyInternal` declaration is
`:447` (the beginWrite/put body at `:549-577` as cited). Everything else matches.

**Amended 2026-07-09 per adversarial review (review-design-hostiles.md, verdict FAIL
→ this revision):** H2 sub-decision (i) reworked — the single conservative CL-bound
pair was a silent-data-loss design (the review's §D loss chain, reproduced and
resolved in §2.2-i); replaced by per-manager bound vectors + manager-banded segment
ids, `getCurrentPosition()` defined, §2.4's replay story rebuilt, §2.1 fact 2 and
the H2 test list corrected with it. Co-fixes: carrier-overturn evidence restated
honestly (§1.2 — context IS in scope at `CFS.apply:1515`; the binding constraint is
the public `Memtable.accepts` interface); explicit design-target amendment notes
added (§6); site-3 trigger corrected to table drop (§1.3/§1.5); periodic sync-lag
park named as PoC watch item W-SYNC (§2.2-ii); census corrections (§0 rule 3, §1.6
+ `SegmentCompactor.java:61`, Accord `:399`); slack-bound per-table multiplier
stated (§3.2).

Audience: the implementing agent first. Each hostile section ends with CHOSEN DESIGN,
FAILURE/UPGRADE BEHAVIOR, and TEST STRATEGY. HOSTILE #3 is solved by design-target
itself (routing + owner-check fallback, D1); HOSTILE #5 is deferred in §4 with
rationale and an un-defer trigger.

---

## 0. One attribution rule, three consumers (cross-cutting invariant)

HOSTILE #1 and #2 (and #4's flush lifecycle) all need the same answer to "which shard
does this write belong to when it isn't running on a shard thread". Pin it once:

**The shard attribution of a write is computed ONCE, in
`CassandraKeyspaceWriteHandler.beginWrite` (`CassandraKeyspaceWriteHandler.java:42`),
and used for BOTH the writeOrder group start and the commitlog manager selection:**

1. `currentShardId() >= 0` (the applying thread is a shard thread) → that shard.
   Routed writes get this for free; it matches the memtable-shard owner by
   construction (D4 routing).
2. else token-hash: `mutation.key().getToken()` hashed `% N` — TCM/ShardBoundaries-free
   (required: the first `CommitLog.add` is `persistLocalMetadata()` at
   `CassandraDaemon.java:325`, before the ring is usable — findings-i4-i5 §7.1).
3. else (no token in scope — `createEmptyContext` at
   `CassandraKeyspaceWriteHandler.java:102,107`) → `threadId % N` (deterministic,
   spreads; these contexts carry no commitlog position). Consumer census (corrected
   per review — NOT all rare): `createContextForRead` runs **per-read on 2i-indexed
   reads** (`ReadExecutionController.java:152`, via
   `CassandraKeyspaceWriteHandler.createContextForRead:127`) and from Accord's CFK
   loader (`AccordKeyspace.java:399`); the `createContextForIndexing` callers
   (`CompactionTask.java:631`, `SecondaryIndexManager.java:1118,1763,1833`) are the
   rare ones. All are correctness-covered by composite await-all (§1); rule 3 only
   spreads the `register()` CAS. The bench has no 2i, so the per-read case does not
   touch the PoC's numbers.

Accord inherits D7: PoC = (c) exempt, so Accord threads are plain non-shard writers —
their user-table applies (`TxnWrite.java:150` → same `beginWrite`) and system-table
group starts (`AccordKeyspace.java:361`) take rule 2; Accord's empty read contexts
(`AccordKeyspace.java:399`) take rule 3. CEP end-state (a) makes user-table applies
rule-1 native; `AccordKeyspace.java:361/:399` stay rules 2/3 forever.

Consistency between the two consumers is an efficiency property, not a correctness
property: barriers await ALL N orders (§1) and every commitlog manager keeps its CAS
machinery as fallback (§2), so a mismatch merely costs contention. This is
design-target §1's owner-check-with-fallback principle applied to the hostiles.

---

## 1. HOSTILE #1 — Keyspace.writeOrder (`Keyspace.java:100-102`)

The one node-global `OpOrder` whose `Group.register()` CAS
(`OpOrder.java:206-216`) is the shared cacheline every write on every core hits.

### 1.1 Shape: `ShardedOpOrder` (one class, not N loose OpOrders)

**CHOSEN: a `ShardedOpOrder` wrapper owning `OpOrder[] orders` (length N, ctor
param), replacing the `Keyspace.writeOrder` field.** Not N loose OpOrders scattered
at call sites, because:
- the 5 barrier sites need composite issue/await semantics exactly once, centrally
  (hand-rolled loops at 5 sites is how one site gets the ordering wrong);
- the class must be writeOrder-agnostic so the per-table `readOrdering` fate row
  (design-target §8.1, last row) can instantiate the SAME machinery CEP-era — see
  §1.6;
- flag-off degenerates cleanly: **the I4 flag chooses N** — flag-off constructs
  `ShardedOpOrder(1)`, which is semantically byte-identical to today's single OpOrder
  (one order, composite-of-1 barriers). One code path, no flag branches at use sites,
  and the A/B compares N=1 vs N=cores on identical code.

API surface (all delegating to plain OpOrder internals, which are untouched except
§1.2's Group field):
- `Group start(int shard)` → `orders[shard].start()` (`OpOrder.java:105`).
- `CompositeBarrier newBarrier()` → one `OpOrder.Barrier` per order.
- `CompositeBarrier`: `issue()` = issue all N; `markBlocking()` = mark all N;
  `await()` = await all N; `isAfter(Group)` = dispatch to the group's own
  constituent (§1.2); `awaitNewBarrier()` = issue-all then await-all
  (mirrors `OpOrder.java:131`).

**Composite ordering semantics (the part to not get wrong):** `issue()` MUST complete
on ALL N constituents before `await()` (or `markBlocking()`) touches ANY. Each
constituent `issue()` is independently synchronized on its own order
(`OpOrder.java:389-402`); the loop order across constituents is irrelevant — a write
that starts on order *j* after order *i* was issued but before *j* was issued lands
in *j*'s pre-barrier group, is (correctly) classified before-the-barrier by
`isAfter`, and is (correctly) waited for by `await()` on *j*. What would be WRONG is
awaiting order *i* while order *j* is un-issued: *j*'s pre-barrier group is still
open-ended, so "await returned" would not mean "all pre-barrier writes finished".
`CompositeBarrier` enforces the phase separation internally (issue loop completes
before await loop begins; assert-guarded); `markBlocking()` likewise runs only after
full issue, preserving today's flush sequence
(`ColumnFamilyStore.java:1271` issue … `:1285-1286` markBlocking+await).

### 1.2 Carrier: owner back-reference ON `OpOrder.Group` — recommendation OVERTURNED

expected-changes §3.3 recommended carrying shard identity in `CassandraWriteContext`
(no public-class API change). **Overturned, with the evidence stated honestly
(restated per review §A):** the context IS in scope one frame above the barrier
check — `CFS.apply(PartitionUpdate, CassandraWriteContext, boolean)`
(`ColumnFamilyStore.java:1515`) extracts group+position at `:1519-1520` and calls
`Tracker.getMemtableFor(opGroup, commitLogPosition)` (`Tracker.java:390`) →
`AbstractMemtableWithCommitlog.accepts(opGroup, commitLogPosition)`
(`AbstractMemtableWithCommitlog.java:71`) → `barrier.isAfter(opGroup)` (`:85`). So a
context-carried shard id COULD be threaded down — **what kills the context option is
that threading it requires changing `Tracker.getMemtableFor` AND
`Memtable.accepts(OpOrder.Group, CommitLogPosition)`, the public pluggable Memtable
API (`Memtable.java:375`, CEP-11 surface) — a breaking interface change for
out-of-tree memtable implementations, versus an additive package-internal field.**
The dispatch itself is unavoidable: `Barrier.isAfter` is raw group-id arithmetic
(`OpOrder.java:375-383`) — ids are per-OpOrder-instance monotonic, so evaluating a
group against another order's barrier returns garbage, not an error; the composite
CANNOT safely iterate-and-try. The group must self-identify. Therefore:

**CHOSEN: `OpOrder.Group` gains a final owner back-reference set at construction.**
Groups are constructed at exactly two sites, both inside OpOrder — field-init
`OpOrder.java:97` and the barrier swap `:399` — and `Group` is a `static final
class` (`:143`), so the owner is passed explicitly at both sites (no implicit outer
reference; mechanical, package-internal). `ShardedOpOrder`
assigns each constituent an index; `CompositeBarrier.isAfter(g)` =
`barriers[g.owner().index()].isAfter(g)`. Additive field on a public class, no
signature changes anywhere; one reference per Group, and Groups are per-barrier-epoch
objects (one live per order between barriers), not per-op — cost is nil. The field
is inert for every plain OpOrder (readOrdering, per-segment `appendOrder`,
`HintsBuffer` orders) — review §A verified no non-write-path cost.
`CassandraWriteContext` (`CassandraWriteContext.java:26-59`) is **unchanged**: it
already carries the group (`close()` at `:55` closes it; Group.close is
self-contained, `OpOrder.java:222`), and the group now self-identifies. Nothing else
needs the shard id post-start because §0 computes it once up front, and §2.2-i's
banded positions self-identify their commitlog manager independently.

**This decision amends design-target.md** (which pinned the context carrier in two
accepted rows) — amendment notes for the main agent in §6.

### 1.3 The five barrier sites, composite semantics per site (complete census)

Census from findings-i4-i5 §3, re-verified. Every site becomes a call on the
`ShardedOpOrder` — mechanical, because §1.1 centralizes the semantics:

| # | Site | Today | Composite semantics |
|---|---|---|---|
| 1 | **Flush / memtable switch** — `ColumnFamilyStore.java:1247` `newBarrier()`, `:1260` `oldMemtable.switchOut(writeBarrier, commitLogUpperBound)`, `:1266` `setCommitLogUpperBound`, `:1271` `issue()`, `:1285-1286` `markBlocking(); await()` | one barrier, one order | `newCompositeBarrier()`; the memtable stores the composite (its `writeBarrier` field, `AbstractMemtableWithCommitlog.java:55-60` `switchOut`) and `accepts` dispatches per §1.2. `issue()` = all N (any order). The commitlog upper bound becomes a per-manager VECTOR sealed slot-by-slot before issue (§2.2-i — cross-manager positions are sort-keys, not a time order; the `accepts` CAS-race `:101-106` runs per slot, selected by the position's band). `markBlocking()`+`await()` after full issue = drain all N. Post-await guarantee identical to today: every write started before the barrier, on ANY shard order, is in the flushed memtable — this is also what makes §3's per-shard allocator discard safe. |
| 2 | **CFS invalidate/shutdown** — `ColumnFamilyStore.java:3365` `awaitNewBarrier()` | issue+await one | `awaitNewBarrier()` = issue all, await all. (`:3366` is per-table `readOrdering` — untouched in PoC, §1.6.) |
| 3 | **Commitlog segment force-recycle** — `AbstractCommitLogSegmentManager.java:371`, inside `forceRecycleAll:359` | issue+await one | await ALL N, even though the caller is one per-shard manager (§2): with §0 rule-1/rule-2 attribution consistent, manager *i*'s in-flight writers hold only `orders[i]` groups and a single-order await would suffice — but the invariant is efficiency-grade, not correctness-grade (§0), so the site uses the uniform await-all. **Trigger (corrected per review): TABLE DROP, not cap pressure** — `CFS.invalidate` → `CommitLog.instance.forceRecycleAllSegments(droppedTables)` (`ColumnFamilyStore.java:3360`, the CASSANDRA-16986 resurrection guard) and `StorageService.java:4022`; cap pressure goes `maybeFlushToReclaim → flushDataFrom` and never touches writeOrder. Under (a), `forceRecycleAllSegments` iterates N managers ⇒ N sequential composite awaits per drop — harmless (drop-rate path), stated so nobody profiles it as a surprise. |
| 4 | **Index invalidation** — `CassandraIndex.java:681` (`:683` is readOrdering — untouched) | issue+await one | await all N. Rare (index drop). |
| 5 | **Keyspace unload** — `schema/DistributedSchema.java:365` | issue+await one | await all N. Rare (schema change). |

The three `start()` sites map per §0: `CassandraKeyspaceWriteHandler.java:47` (rule
1/2), `:107` (rule 3), `AccordKeyspace.java:361` (rule 2, D7-inherited). Group close
is unchanged (`CassandraWriteContext.close:55`; try-with-resources at
`AccordKeyspace.java:361`).

### 1.4 MV global-order argument, restated for the record

The comment at `Keyspace.java:100-101` ("defined globally since we need to order
writes across Keyspaces in the case of Views") over-claims. writeOrder's actual job
is barrier coverage — "a flush barrier misses no in-flight write in any keyspace" —
not cross-write ordering; OpOrder provides no cross-write ordering to miss. Under
composite barriers that job holds regardless of which constituent a write registered
with (§1.3 site 1). The MV-specific ordering (base read vs view generation,
`Keyspace.java:549-577`, `pushViewReplicaUpdates:568`; `TableViews.java:173-201`)
happens inside ONE group today and inside ONE shard-local group under owner-routing:
design-target D5's end-state routes the base mutation to the base key's owner shard,
where base-read + view generation run under that shard's single group — ordering is
trivial within an owner, which is what dissolves the global-order rationale
(findings-i4-i5 §3 conclusion, adopted).

**Flag-off / mixed-increment behavior:** I1 on + I4 off = `ShardedOpOrder(1)` — all
routed writes still start groups on the one order; global order preserved,
byte-equivalent to trunk semantics. I4 on = N orders + composite barriers as above.
PoC MV posture: MV-bearing tables are EXCLUDED from routing by predicate (D5), so
their writes arrive off-shard and take §0 rule 2 — safe under composite barriers by
the §1.4 argument (barrier coverage is attribution-independent).

### 1.5 What can block a shard thread here (C5 audit)

`start()` never blocks (spin on CAS, `OpOrder.java:105-113`). `Barrier.await` blocks
— but no barrier site runs on a shard thread: flush runs on the flush pool, sites
2-5 on their existing threads (all off-shard, D3). The one indirect hazard near
site 3: a shard thread inside `CommitLog.add` can park in `awaitAvailableSegment`
(`AbstractCommitLogSegmentManager.java:339`) while segments are scarce — that is
today's behavior, unchanged in kind; its frequency tracks segment availability
(allocation rate vs the §2.2-iii shared cap and the AllocatorRunnable), while
site 3 itself fires on table drop (corrected per review), not on cap pressure. The
periodic sync-lag park is a separate, real shard-thread hazard — named watch item
W-SYNC, §2.2-ii.

### 1.6 readOrdering reuse (the design-target fate row, made concrete)

`readOrdering` is per-table (`ColumnFamilyStore.java:299`), started per read
(`ReadExecutionController.forCommand`, group at `ColumnFamilyStore.java:2105`),
barrier users at `ColumnFamilyStore.java:1442,3366,3515`,
`CassandraIndex.java:683`, and `service/accord/journal/SegmentCompactor.java:61`
(census completed per review — five sites). CEP-era, if read-path profiles show the per-table CAS
(diluted today — deferred with rationale in design-target §8.1), each table's
`readOrdering` becomes a `ShardedOpOrder(N)` instantiated from the SAME class:
`start(shard)` with §0's rule (currentShardId else token-hash; reads always have a
key at the controller), the five barrier sites become composite calls, and the
`Group` owner field from §1.2 already dispatches — **zero new machinery**. This works
precisely because §1.1 kept ShardedOpOrder free of writeOrder-specific logic (no
commitlog coupling, no static state; N and nothing else in the ctor). The I2
reschedule lifecycle (design-target §3 item 5) is unaffected: groups never cross
threads; each attempt opens/closes its own.

### CHOSEN DESIGN (H1)
`ShardedOpOrder(N)` replaces `Keyspace.writeOrder`; N = I4 flag (1 off, cores on).
Composite barriers issue-all-then-await-all at the five census sites; `OpOrder.Group`
carries an owner back-reference (carrier recommendation overturned — the binding
constraint is the public `Memtable.accepts` interface, §1.2; design-target amended
per §6); attribution per §0; MV safety by owner-routing
end-state + attribution-independent barrier coverage now; readOrdering reuses the
class unchanged CEP-era.

### FAILURE / UPGRADE BEHAVIOR (H1)
Process-local, no persistence, no wire impact. Flag-off = N=1 = trunk semantics on
the same code path; flag flip requires restart (the field is static final — same as
today). Mixed-version clusters unaffected (node-local). Failure modes: a leaked group
(unclosed) wedges ITS shard's barrier — today it wedges the node's one barrier;
strictly no worse, and the wedged-shard identity is now diagnosable (barrier await
exposes which constituent is stuck — add that to the composite's toString). A
crashed flush mid-composite-issue leaves some orders on a new group — harmless,
identical to today's crash-mid-issue (barriers are process-lifetime constructs;
restart replays from the commitlog).

### TEST STRATEGY (H1)
- **Unit/burn:** extend the existing OpOrder burn test (`LongOpOrderTest` pattern)
  to `ShardedOpOrder`: P producer threads starting groups with mixed rule-1/rule-2
  attribution across N orders, a barrier thread issuing composites; assert no
  operation started before a composite's issue completes after its await returns,
  and `isAfter` dispatch never misclassifies (owner-field correctness).
- **Memtable switch race:** targeted unit test — writers racing `switchMemtable`
  with attributions deliberately mismatched to the memtable shard; assert the old
  memtable contains exactly the pre-barrier writes (exercises `accepts` composite
  dispatch + upper-bound CAS, `AbstractMemtableWithCommitlog.java:85,101-106`).
- **Flag matrix:** full write+flush+MV+2i unit suites at N=1 and N=4 (parameterized);
  in-JVM dtest write/flush/restart at both (runs on macOS — memory note).
- **Site 3 under load:** concurrent writes racing table drops
  (`forceRecycleAllSegments`, `ColumnFamilyStore.java:3360`) at N=4 — the
  composite `awaitNewBarrier` at `AbstractCommitLogSegmentManager.java:371` runs
  once per manager per drop; assert no post-drop resurrection (the CASSANDRA-16986
  guard holds) and no wedge.

---

## 2. HOSTILE #2 — CommitLog

### 2.1 Decision: (a) N per-shard segment managers — (b) rejected

**CHOSEN: option (a).** One `AbstractCommitLogSegmentManager` instance per shard
(same class, N instances behind `CommitLog`), `add()` selecting `managers[shardId]`
by §0's attribution. The evidence, all verified:

1. **The position-before-put contract kills (b)'s economics.**
   `CommitLogPosition` must exist BEFORE the memtable put:
   `beginWrite` starts the group `:47` then `addToCommitLog` `:53` →
   `CommitLog.instance.add` `:99`
   (`CassandraKeyspaceWriteHandler.java`), and `accepts` needs the position to route
   old-vs-new memtable (`AbstractMemtableWithCommitlog.java:71,101-108`). A dedicated
   log-writer core therefore means either a blocking shard→writer→shard round trip
   per mutation (forbidden — C5) or restructuring the
   `beginWrite`→`applyInternal` (`Keyspace.java:447,549`) contract into a
   position-continuation — a bigger write-path blast radius than (a)'s
   commitlog-internal changes.
2. **(a)'s id problem is mostly pre-solved — but not "for free" (corrected per
   review; findings-i4-i5 RESOLVED-1 was incomplete).** The existing static
   allocator (`idBase`/`nextId`/`replayLimitId`, `CommitLogSegment.java:70-92`,
   `getNextId():143`, assigned `:155`) gives N managers globally-unique,
   union-sortable ids — which keeps the replay FILE machinery (directory scan,
   id-sort, filename uniqueness) unchanged. What it does NOT give is a safe
   coverage protocol: interleaved ids from a shared counter let one manager's
   covered interval numerically contain another manager's positions, which is the
   §2.2-i loss chain. The fix — per-manager monotonic counters in disjoint id BANDS
   — replaces the shared counter but keeps every consumer of "unique, sortable
   long id" working (§2.2-i, §2.4).
3. **Default sync mode makes (b)'s hop pure loss.** In `periodic` mode `add()` does
   not wait for fsync (`PeriodicCommitLogService.java:36-45` blocks only when sync
   lags `blockWhenSyncLagsNanos`), so (b)'s cross-core hop buys nothing — it is
   allocation latency added to every write, plus a single-core memcpy ceiling for
   all mutation payloads (findings-i4-i5 §2 option-b analysis).
4. **Paper corroboration:** (b)'s per-mutation cross-core round trip is exactly
   Enberg's message-passing weakness — wake-ups (their held-back item 1) + payload
   copies (item 3) — `../findings-tpc-paper.md` §3 Phase-3 note.

Per-manager state that simply multiplies (findings §1 inventory): `allocatingFrom`,
`activeSegments`, `availableSegment`, `segmentPrepared`, one `AllocatorRunnable`
segment-preparation thread each, one `SimpleCachedBufferPool` each
(`AbstractCommitLogSegmentManager.java:95,88,83,85,115`). N managers ⇒ N buffer
pools + N in-flight preallocated segments: **segment-buffer footprint multiplies by
N** (design-target §8.1 sub-decision) — budget line: N × segment_size (32 MiB
default) ≈ 384 MiB at N=12; acceptable on the rig class, an operator-visible note
for the CEP, and the reason `commitlog_segment_size` may want to shrink when N is
large (CEP-era tuning, not PoC). Each segment keeps its own `allocatePosition` CAS
and `appendOrder` (`CommitLogSegment.java:103,101`) — under routing they are
near-single-writer (only §0 rule-2/3 interlopers), and they stay as the fallback
guard per design-target §1's principle: correctness never depends on routing.

### 2.2 Sub-decisions (the four from expected-changes §3.3)

**(i) Memtable CL bounds — CHOSEN: per-manager bound VECTORS + manager-banded
segment ids.** (REWORKED per review §D BLOCKER — the previous revision chose a
single conservative pair; that design silently loses data.)

*Why the single pair is unsafe, for the record.* The coverage protocol rests on the
invariant written at `AbstractMemtableWithCommitlog.java:81-84`: a position
comparison implies a time order ("mutations take their position after taking the
opGroup … the given commit log position is greater than the chosen upper bound").
That holds per single append sequence and is FALSE across N managers whose active
segments skew. The loss chain (review §D, every step verified): flush seals
`U = max over managers`; a post-barrier write W on a laggy manager gets position
`< U` from that manager's still-active segment and goes to the NEW memtable
(rejected by `isAfter`, `accepts:85`); the old memtable's sstable claims interval
`[lower, U] ∋ W`; `markClean(T, lower, U)` on the laggy segment computes
`end = Integer.MAX_VALUE` because its id `< U.segmentId`
(`CommitLogSegment.java:558-567`) → the whole segment marked clean for T →
segment deleted with W only in the unflushed new memtable; independently the
replayer's `shouldReplay` = `!persisted.contains(position)`
(`CommitLogReplayer.java:496-499`) skips W as covered. Crash ⇒ W gone. **Doctrine
this forces (also corrects §1.3 site 1's earlier wording): cross-manager
CommitLogPositions are COMPARABLE (a valid sort key) but never TIME-ORDERED; time
order exists only within one manager's sequence.**

*The chosen design, two parts that only work together:*

1. **Manager-banded segment ids.** Replace the shared counter with per-manager
   monotonic counters in disjoint numeric bands: `id = (managerIndex << 56) |
   counter` (bits 62-56 = manager, N ≤ 127; counter seeded per band at startup =
   `max(currentTimeMillis, max on-disk counter in band + 1)` — the existing static
   scan at `CommitLogSegment.java:83-92` generalized per band; `currentTimeMillis`
   fits in 42 bits, so 56-bit counters never overflow). Pre-flag legacy ids have
   zero high bits = band 0, so manager 0 CONTINUES the legacy sequence and bands
   1..N-1 open fresh — no migration step. Banding makes every position
   self-identify its manager (`band(position.segmentId)`), which is what lets all
   position-taking public signatures stay unchanged, and makes per-manager
   intervals numerically DISJOINT — without banding, interleaved ids from the
   shared counter let manager A's interval `[100,105]` contain manager B's segment
   103, and the replayer skips B's unflushed writes (same disease as the pair,
   §2.1 fact 2). Consequential fix: the replay-limit filter compares raw ids
   (`CommitLogSegment.shouldReplay(name)` = `id < replayLimitId`,
   `CommitLogSegment.java:227-230`, wired at `CommitLog.java:82`) —
   `replayLimitId` becomes per-band, compared within the file's band.

2. **Per-manager bound vectors in the memtable.** `commitLogUpperBound` becomes an
   `AtomicReference<CommitLogPosition>[N]` (still one array shared across a table's
   and its 2i's memtables, as the single ref is today); `commitLogLowerBound`
   likewise a vector injected from the predecessor's sealed uppers;
   `approximateCommitLogLowerBound` a vector of per-manager current positions at
   construction. `accepts(opGroup, position)` selects the slot by the position's
   band — **signature unchanged** (`Memtable.java:375`), consistent with §1.2's
   interface stance; same for `mayContainDataBefore(position)` (`Memtable.java:389`,
   `AbstractMemtableWithCommitlog.java:123-126`, consumer `flushDataFrom` at
   `ColumnFamilyStore.java:1131` — the force-recycle position is manager-scoped and
   now compares against its own band's slot, fixing the review's cross-manager
   `mayContainDataBefore` corollary). The flush seal (`setCommitLogUpperBound`,
   `ColumnFamilyStore.java:1467-1480`) loops the slots, slot m CAS-racing against
   in-flight band-m writers using `getCurrentPosition(m)` — no cross-manager
   comparison, so the review's min-definition livelock cannot arise; all N slots
   seal before composite issue (today's `:1266`-before-`:1271` ordering preserved).
   Within one band the `:81-84` invariant holds again: a post-barrier write's
   position exceeds ITS manager's sealed slot, `markClean(T, lower[m], upper[m])`
   never reaches it (segment-id guard `:559` or in-segment interval end), and the
   sstable's persisted intervals never claim it.

*Flush metadata and discard, on top of the vectors:* the flushed sstable records N
intervals `[lower[m], upper[m]]` — the flush-side builder is ALREADY a
multi-interval `IntervalSet.Builder` loop (`ColumnFamilyStore.java:2454-2473` →
`MetadataCollector.commitLogIntervals:343`), and `IntervalSet<CommitLogPosition>`
already serializes N intervals (compaction unions sets today,
`MetadataCollector.java:187`; replay unions across sstables,
`CommitLogReplayer.persistedIntervals:338-360`) — **no sstable format change**,
band-disjointness is what makes `contains()` manager-exact. PostFlush
(`ColumnFamilyStore.java:1198-1199`) fans `discardCompletedSegments(id, lower[m],
upper[m])` per manager; each manager iterates ITS segments with TODAY'S
`contains(upperBound)` terminator (`CommitLog.java:382-383`) unchanged — **the
previous revision's id-terminated break is RETRACTED** (the review is right that it
widened the loss surface; on per-manager bounds no new terminator is needed at
all).

*Interface evolution (additive, not breaking):* `Memtable.getCommitLogLowerBound()`
/ `getFinalCommitLogUpperBound()` (`Memtable.java:383,386`) and the
`FlushablePartitionSet` accessors gain interval-set-returning siblings with
defaults that wrap the legacy single pair — out-of-tree CEP-11 memtables keep
compiling and get N=1 semantics; in-tree `AbstractMemtableWithCommitlog` overrides
with the vectors. Same reasoning as §1.2: additive over breaking.

*`getCurrentPosition()` DEFINED (review §D gap — the four callers get different
answers):* new primitive `getCurrentPosition(int manager)`; the no-arg
`CommitLog.getCurrentPosition()` (`CommitLog.java:258-260`) is REMOVED (any survivor
must name a manager or take the vector — a max across managers is a sort key, not a
time, and feeding it to coverage logic is exactly the blocker). Caller disposition:
flush seal (`ColumnFamilyStore.java:1475`) → per-slot `getCurrentPosition(m)`;
memtable approximate lower bound (`AbstractMemtableWithCommitlog.java:36`) and
offline memtable creation (`ColumnFamilyStore.java:528`) → vector snapshots; the
replayer's durable-memtable filter (`CommitLogReplayer.java:160`) → N intervals
`[NONE, current(m)]`. Two more single-position artifacts inventoried and N-ified:
**truncation records** (`SystemKeyspace.saveTruncationRecord:838`, consumed as
`builder.add(NONE, truncatedAt)` at `CommitLogReplayer.java:359-360`) must record
per-band positions — new records carry the vector; legacy single-position records
are correct as-is because they could only be written in band-0-only eras (pre-flag,
or N=1 after a flip whose restart already replayed-and-deleted the other bands'
files); and the archiver's `snapshotCommitLogPosition`
(`CommitLogReplayer.java:147`) — PoC restricts snapshot-position restore to N=1
(startup check), vector form CEP-era (restore-with-snapshot is outside the PoC
bench scope).

*Reclamation quality (the original sub-decision's actual subject):* per-manager
bounds give exact per-manager reclamation — no pinned-segment conservatism at all,
strictly better than the pair design it replaces. The review's re-costing stands:
the plumbing (vectors, seal loop, PostFlush fan-out, additive interface methods) is
the price of correctness, not an optimization to defer.

**(ii) Sync-thread topology — CHOSEN: ONE sync service iterating N managers.** One
`AbstractCommitLogService` (one `SyncRunnable`, fields `lastSyncedAt:62`,
`pending:66`, `haveWork:70`, loop `:158-225`) whose sync pass fans
`segmentManager.sync(flush)` over `managers[0..N)`. Rationale: (1) preserves today's
device flush pressure shape — N sequential fsyncs per period from one thread vs N
concurrent fsync streams; the commitlog device is SHARED with AccordJournal, whose
fsync cadence inherits `commitlog_sync_period` (design-target D7 table, effort.md
risk) — one iterating thread keeps the two WALs' interference additive rather than
multiplicative; (2) in `periodic` mode writers do not wait for fsync on the common
path, so serializing N fsyncs costs no foreground latency *while the sync pass keeps
up* — see W-SYNC below; (3) `CommitLogMetrics`
attaches one service (`CommitLogMetrics.attach`, wired `CommitLog.java:135`) —
unchanged; only manager-side gauges need aggregation over N (findings §7.5).
Per-segment waiter machinery (`syncComplete:123`, `waitForSync:497`,
`lastSyncedOffset:109`) is untouched — it is already per-segment.

**Named PoC watch item W-SYNC (reclassified per review — previously an understated
CEP-era knob).** The mechanism: `PeriodicCommitLogService.maybeWaitForSync` parks
ANY writer — shard threads included — whenever `lastSyncedAt` lags
`blockWhenSyncLagsNanos` (`PeriodicCommitLogService.java:36-45`; config
`periodic_commitlog_sync_lag_block`, `Config.java:489`), and `lastSyncedAt` advances
only after a FULL sync pass (`SyncRunnable` sets it after `commitLog.sync(true)`,
`AbstractCommitLogService.java:185-190`). One thread doing N sequential fsyncs
multiplies pass duration ~×N — a slow shard's fsync delays every other manager's
sync credit — so the probability of crossing the lag threshold grows with N, and
crossing it is a node-wide, C5-grade shard-thread park. Watched at the I4 A/B (the
H2 test suite does not exercise slow-fsync devices, so the bench is the instrument):
`commitlog.waitingOnCommit` count/timer (must stay zero in periodic cells) and
sync-pass duration vs `commitlog_sync_period`. **Threshold: any `waitingOnCommit`
occurrences on shard threads, or pass-duration p99 > half the sync period, fails the
cell into the hurdle log** and escalates to the recorded remedies — per-manager
`lastSyncedAt` (so one laggy manager only blocks its own band's writers) and/or N
sync threads — before the cell is accepted.

**(iii) Cap sharing — CONFIRMED: one shared size atomic.** Today each manager's
`unusedCapacity()` (`AbstractCommitLogSegmentManager.java:447`) reads the FULL
configured total against its own `size` (`:105`) — N naive managers would each
assume the whole budget (N× overshoot; findings §7.3). Fix: hoist the `AtomicLong`
into one shared accounting object owned by `CommitLog`; all managers `addSize`
against it and `unusedCapacity`/`maybeFlushToReclaim` (`:248`) consult it.
`commitlog_total_space` keeps its operator meaning (node total). Cap-pressure
reclamation (`maybeFlushToReclaim → flushDataFrom` — NOT the site-3 barrier, per
review) then fires on whichever manager allocates past the shared limit, flushing
tables whose memtables `mayContainDataBefore` that manager's reclaim position
(band-scoped per §2.2-i) — frequency ≈ today's.

**(iv) CDC size tracker — CONFIRMED: shared; and CDC only ever meets the UNROUTED
path.** With CDC enabled node-wide, ALL N managers are `CommitLogSegmentManagerCDC`
(as the single manager is today), sharing ONE `CDCSizeTracker`
(`sizeInProgress` AtomicLong, `CommitLogSegmentManagerCDC.java:289,295`) so
`cdc_total_space` stays a node total; hard-link/index naming is segment-id-based
(`createSegment:240`) ⇒ collision-free. Interaction with design-target D3 item 4
(cdc=true tables excluded from shard routing): `throwIfForbidden`
(`:178,:186 → :215`) fires only for mutations on cdc-enabled tables, and those
mutations never run on shard threads — so the CDC block-on-space stall stays
off-shard by construction, while non-CDC mutations routed to shard threads pass
through the CDC manager's allocate without ever reaching the throw. No async
allocation needed for the PoC (CEP-era per design-target).

**Failure policy — CONFIRMED: stays global.** `handleCommitError`
(`CommitLog.java:577-580`) keeps node-wide semantics: one failed shard log
stops/kills the node exactly as one log does today. Per-shard isolation would be a
semantic change operators haven't asked for — don't (findings §7.2 adopted).

### 2.3 Sync modes and the delegated CEP-era decision: async group-commit

**PoC (restating design-target D3 item 4, normative here):** shard routing requires
`commitlog_sync: periodic`. Batch/group modes park the writer inside `add()`
(`finishWriteFor` `CommitLog.java:337` → `maybeWaitForSync` →
`Allocation.awaitDiskSync` `CommitLogSegment.java:740` parking on per-segment
`syncComplete:123`) — a per-write fsync-latency park on a shard thread, C5-fatal.
Flag-on + batch/group ⇒ routing disabled at startup with a clear log line, not a
silent cliff.

**DELEGATED DECISION, made: batch/group under routing is SOLVED CEP-era by async
group-commit, not killed.** Killing it would permanently subtract a durability mode
from routed tables — an operator-facing regression a CEP should not carry when the
async shape is cheap and the machinery mostly exists. The shape:

- The durability contract of batch/group is "the ACK implies fsync", not "the
  memtable put waits for fsync" — visibility-before-durability already exists
  cluster-wide under the periodic default. So: on a shard thread, `add()` allocates,
  writes, `markWritten()` and returns WITHOUT waiting; the memtable put proceeds
  inline; the **response ack is deferred** until sync covers the position.
- Mechanism: `Allocation` gains an optional completion callback registered against
  its segment keyed by position — the per-segment waiter list that
  `waitForSync:497`/`syncComplete:123` implement today, with parked-thread signals
  replaced by callback dispatch when the sync thread advances `lastSyncedOffset`
  (`sync():305-374` already batch-signals exactly this set). The existing
  group-commit services keep their roles: `GroupCommitLogService` cadence groups
  callbacks per interval; `BatchCommitLogService.requestExtraSync` still nudges the
  sync thread.
- The callback completes the mutation's response continuation — this is I3's
  machinery (the replica-side `applyFuture` chain / coordinator continuation), which
  is WHY this is sequenced CEP-era after I3: without the async coordinator there is
  no continuation to defer, only a thread to park. Completion executor follows I3's
  decision (design-async-coordinator); the sync thread only enqueues, never runs
  user-visible work.
- Read-visibility semantics change slightly for batch mode (data visible in the
  memtable before its fsync, as periodic already allows); state this in the CEP's
  behavioral-changes section — it is the documented cost of batch-mode-with-routing,
  and operators who reject it can run batch without routing (the modes remain
  supported-but-antithetical to routed tables until they opt in).

### 2.4 Replay, archiving, disk access modes

**Replay (REWRITTEN per review — the file machinery is unchanged, the coverage
semantics are §2.2-i's):**
- **File discovery/order unchanged:** `recoverSegmentsOnDisk`
  (`CommitLog.java:183-219`) directory-lists and sorts by id
  (`CommitLogSegmentFileComparator`, `CommitLogSegment.java:664`); banded ids stay
  unique longs, filenames (`CommitLog-<version>-<id>.log`) never collide in the one
  shared directory. Sort order becomes band-major rather than roughly-chronological
  — irrelevant to correctness (mutation merge is timestamp-resolved; replay order
  was never a documented guarantee).
- **Per-mutation coverage is EXACT, not conservative:** `shouldReplay` =
  `!persisted.contains(position)` (`CommitLogReplayer.java:496-499`, applied at
  `:313`) against per-table interval sets unioned from sstable metadata +
  truncation records (`persistedIntervals:338-360`). With band-disjoint per-manager
  intervals (§2.2-i) every position tests against its own manager's coverage —
  neither skipping unflushed data (the retracted design's failure) nor systematic
  re-replay. The previous revision's "at worst it replays some already-flushed
  positions" claim is RETRACTED: the single-pair failure was skipping UNFLUSHED
  positions, the opposite direction.
- **The coarse prefilter stays safe, conservatively:** `globalPosition =
  firstNotCovered(cfPersisted.values())` (`CommitLogReplayer.java:183,375-381`) is
  the min over tables of each set's first covered-run end — across bands this min
  is a valid lower bound (band-major comparison), it just prefilters less; exact
  per-mutation filtering does the rest. Per-band globalPositions are a CEP-era
  efficiency refinement, not a PoC need.
- **Replay-limit filter per band** (`CommitLogSegment.shouldReplay:227-230` via
  `CommitLog.java:82`) — §2.2-i part 1's consequential fix; without it, files in
  bands above the limit's band would be silently excluded from replay.
- **PITR cutoff is unaffected by banding:** point-in-time filtering compares
  MUTATION timestamps (`pointInTimeExceeded`, `CommitLogReplayer.java:501-506`,
  applied `:295`), not segment-id time — `idBase`'s currentTimeMillis seed was only
  ever a uniqueness device.
- Replay apply is already parallel (`Stage.MUTATION.submit`,
  `CommitLogReplayer.java:330`) and never re-logs (`writeCommitLog=false`); after
  I1, replay applies route through shard executors like any mutation. One honest
  note for the CEP: a single partition's writes CAN span two shard logs (rule-1
  owner vs rule-2 hash attribution differ), so replay's per-partition arrival order
  is no longer single-log order — harmless for the same timestamp-resolution
  reason, stated so nobody rediscovers it as a surprise.

**Archiving/PITR:** one `CommitLogArchiver` already shared via `commitLog.archiver`
(`maybeArchive:196`, invoked from `AbstractCommitLogSegmentManager.java:329`);
`archivePending` is keyed by segment name (unique) — N managers share it unchanged.
Restore + PITR scan the directory union id-sorted — unchanged. External/CDC consumer
contract (`CommitLogReader`, `minPosition` filter) — unaffected: naming + id-sort
preserved.

**`commitlog_disk_access_mode`:** resolution and validation are node-global config
(`DatabaseDescriptor.java:1842-1912`); every manager builds the same segment type
via its own `createSegmentBuilder` (`AbstractCommitLogSegmentManager.java:123-145`).
`direct` (`DirectIOSegment`) and `mmap` (`MemoryMappedSegment`) both compose with N
managers; the implications are footprint, not correctness — N preallocated
aligned buffers / N mmap'd active segments (the §2.1 budget line), and under
`direct` the one iterating sync thread issues N aligned flushes per pass
(`DirectIOSegment` flush alignment is per-segment-internal). No mode-specific code
changes. G3 consequence unchanged: commitlog stays on its existing write path in
both I/O arms (design-target D2 invariant).

### CHOSEN DESIGN (H2)
Option (a): N per-shard `CommitLogSegmentManager`s (CDC-variant when enabled) behind
`CommitLog`, selected by §0 attribution; shared: ONE size-cap atomic, ONE sync
service iterating N (with watch item W-SYNC), ONE archiver, ONE CDC size tracker,
global failure policy. Coverage protocol: **manager-banded segment ids
(per-manager counters in disjoint id bands) + per-manager memtable bound vectors +
N per-manager intervals in the existing `IntervalSet` sstable metadata** (§2.2-i;
single-pair design and id-terminated discard RETRACTED); per-manager discard keeps
today's `contains` terminator; `getCurrentPosition()` replaced by the per-manager
primitive with the caller disposition table; truncation records and replay-limit
filter N-ified per band. PoC requires `periodic`; batch/group under routing lands
CEP-era as async group-commit (ack-deferral continuations on the existing
per-segment sync machinery), sequenced after I3.

### FAILURE / UPGRADE BEHAVIOR (H2)
Flag-off = 1 manager (same class, N=1, all ids band 0 — pre-flag legacy ids ARE
band-0 ids, so flag-off is byte-identical to trunk including the id sequence).
Segment file format unchanged; sstable metadata format unchanged (same
`IntervalSet<CommitLogPosition>` field, more intervals in it); only the number of
concurrently-active segment files grows. **Restart with the flag flipped either
direction replays cleanly**: the replayer is directory-scan + id-sort + per-table
interval filtering, all N-agnostic, and `recoverSegmentsOnDisk` discards replayed
files at the end of startup — so after any flip's first restart no foreign-band
files remain (this is also what keeps legacy single-position truncation records
correct, §2.2-i). `nodetool drain` before flipping is recommended (empty log), not
required. Downgrade to trunk code: trunk replays banded files correctly (id sort +
persisted intervals as written); trunk's `idBase = max(onDiskId)+1` then seeds
absurdly high — cosmetically ugly, functionally sound (ids stay unique, monotonic);
the CEP documents drain-before-downgrade as the clean path. Mixed-version clusters:
node-local, no wire impact. Crash: per-segment recovery semantics unchanged (sync
markers per segment); a crash mid-write in shard i's segment is today's torn-tail
case in one file, others unaffected. Commit error on any shard's log → global
failure policy (stop/die) as today. Startup: N managers constructed and started at
`CassandraDaemon.java:273` before TCM; §0's rules 2/3 need no ring, and the first
write (`persistLocalMetadata`, `:325`) lands via rule 2. Node-death cases that
leave orphan segments in cdc_raw behave as today (shared tracker recalculates on
restart via the existing recalc executor).

### TEST STRATEGY (H2)
- **Existing suite, parameterized N∈{1,4}:** CommitLogTest, CommitLogReaderTest,
  CommitLogArchiverTest, CommitLogSegmentManagerCDCTest, CDCTestReplayer,
  SegmentReaderTest, CommitLogDescriptorTest, CommitLogFailurePolicyTest
  (`test/unit/.../db/commitlog/`) — they assume filename pattern + id ordering,
  both preserved; green at N=4 is the headline regression gate.
- **New: the review-§D loss-sequence test (the blocker's regression test, highest
  value in this list)** — stage skewed managers (advance manager j several segments
  while manager i stays on an old one), flush table T, then write W to T via
  manager i, hard-stop, replay, assert W present; variant asserts manager i's
  segment was NOT deleted by T's discard. This is exactly the sequence the retracted
  single-pair design loses; the generic replay-union test below would catch it only
  by luck (review §G).
- **New: replay-union test** — writes attributed across all N managers (force rule-2
  spread), hard-stop, restart, assert full recovery + covered-interval filtering
  with sstables present (flush half the tables first).
- **New: banded-id unit tests** — per-band counter seeding incl. legacy band-0
  continuation from pre-flag files; per-band `replayLimitId` filtering; comparator
  band-major sort; `accepts`/`mayContainDataBefore` slot selection by band.
- **New: truncation under N** — skewed managers, truncate T, restart, assert no
  resurrection of truncated data from any band AND post-truncate writes to T
  survive replay (exercises the vector truncation record).
- **New: per-manager discard test** — flush and assert exact segment reclamation in
  EVERY manager (per-manager bounds mean no pinned-but-clean segments; a regression
  presents as one manager's segments never discarding).
- **New: shared-cap test** — N managers filling toward `commitlog_total_space`;
  assert node-total (not N×) enforcement and that cap-pressure reclamation
  (`maybeFlushToReclaim → flushDataFrom`) flushes the right tables via the
  band-scoped `mayContainDataBefore` under concurrent writes.
- **New: CDC exhaustion at N** — shared tracker hits `cdc_total_space`, assert
  `CDCWriteException` on the unrouted path and shard-thread traffic (non-CDC tables)
  unaffected.
- **dtest:** in-JVM restart/replay with flag flipped both directions across the
  restart (upgrade/downgrade equivalence); CDC dtest at N=4.
- **CEP-era (async group-commit):** batch-mode dtest asserting ack-implies-fsync via
  kill-after-ack + replay presence; latency A/B group vs periodic under routing.

---

## 3. HOSTILE #4 — Memtable memory

Two distinct contention sources per allocation (findings-i4-i5 §4, verified):
sibling-shard `Region.nextFreeOffset` bumps (`NativeAllocator.java:324,345`;
`SlabAllocator` equivalent) exist because ONE `MemtableAllocator` per memtable
(`AbstractAllocatorMemtable.java:120`) is handed to ALL its shards
(`TrieMemtable.java:129,140`); and the global `SubPool.allocated` addAndGet
(`MemtablePool.java:155`, updater `:265`) hit by every allocation of every memtable
node-wide.

### 3.1 Step 1 — one allocator per shard (unconditional, ships with I4)

**CHOSEN:** `TrieMemtable` (and sharded memtables generally) creates one
`MemtableAllocator` per `MemtableShard` — `generatePartitionShards`
(`TrieMemtable.java:133-143`, allocator handed to every shard at `:140`) takes an
allocator array instead of the single `:129` allocator. This removes the sibling `nextFreeOffset` CAS entirely: each shard
fills its own regions (per-region `currentRegion` CAS `NativeAllocator.java:254`
stays — it is per-shard now and ~1 MiB-granular). Unsharded memtables (skiplist)
and full-range-boundary tables (local-system keyspaces — 1 shard) are unchanged.

Lifecycle N-ification (mechanical): `setDiscarding`/`setDiscarded`
(`AbstractAllocatorMemtable.java:167,173`) iterate the array; size/ownership
accounting sums (`owns` is a LongAdder per SubAllocator,
`MemtableAllocator.java:115` — summing N is the same class of cheap);
`ownershipRatio` and `flushLargestMemtable`'s usedRatio math
(`AbstractAllocatorMemtable.java:257-326`) consume the sums. Accepted cost: region
tail waste multiplies — up to shardCount partially-filled regions per memtable per
subpool instead of one (bounded: N × ~1 MiB; system tables are 1-shard so the
many-small-tables case does not multiply). Export the existing NativeAllocator
"waste" figure as a per-memtable gauge so the cost is observed, not assumed.

**Stays global (unchanged):** `MEMORY_POOL` (`AbstractAllocatorMemtable.java:61`),
SubPool `limit`/`cleanThreshold`, `needsCleaning`/`nextClean`, the cleaner
(`flushLargestMemtable` wired at `:86`) and `hasRoom` WaitQueue
(`MemtablePool.java:55`). Step 1 does NOT partition the memory budget — every
allocation still settles against the global SubPool — so step 1 introduces NO new
imbalance or starvation mode; it only de-shares region bump pointers.

**Blocking audit (C5):** `MemtableAllocator.SubAllocator.allocate` can PARK on pool
exhaustion (`MemtableAllocator.java:176-198`, `hasRoom` signal `:186`) — on a shard
thread this stalls the shard. Unchanged by this design (same park exists today for
every writer; under D7(a) it moves OFF Accord's threads onto shard threads where it
is handled once for all writers): the mitigation is the global cleaner + cap sizing,
same as today, and the park is visible via the existing blockedOnAllocation metrics.
Recorded as the write-path stall everyone shares; per-shard slack (step 2) does not
remove it (exhaustion is exhaustion), it only removes the healthy-path CAS.

### 3.2 Step 2 — shard-local slack batching (CONDITIONAL on step-1 measurement)

**Shape (built only if triggered):** each per-shard SubAllocator acquires budget
from the global SubPool in region-sized chunks (1 MiB) into a shard-local counter;
per-allocation accounting touches only the local counter; unspent slack returns on
memtable discard (`setDiscarded` path). Escape hatch: when a local acquire fails,
fall through to today's direct `tryAllocate` against the global pool before parking
— so a hot shard can always reach (limit − outstanding-slack).

**The measurement, named (expected-changes demands it):** step 2 is decided at
**I4 step-1's own A/B** (allocator-per-shard on vs off, write-heavy bench cell,
N = cores, `memtable: trie` pinned per phase-4 §7): profile signal =
`MemtablePool$SubPool.tryAllocate` / `allocatedUpdater.addAndGet`
(`MemtablePool.java:155`) appearing as (i) a top-10 CPU frame in async-profiler on
shard threads, or (ii) the dominant HITM cache line in `perf c2c` on the allocation
path, with step 1 already ON. Below that bar, step 2 is not built (the paper predicts
the shared-everything leak binds *eventually* at high core counts —
`../findings-tpc-paper.md` §3 I4 note — so the trigger is re-armed at CEP-scale
hardware, not closed). Prior art to cite in the CEP: Sphinx's per-thread LSMA
(per-thread allocator, slab segments, whole-segment reclaim) is this design
one-for-one.

**Failure mode when one shard exhausts while siblings idle (step 2 only):** hot
shard's local acquire fails while idle shards hold slack. CHOSEN: **accepted
imbalance, no reclaim/steal machinery.** Bound: outstanding slack ≤ N shards ×
1 MiB × 2 subpools **per active memtable, i.e. multiplied by the active-memtable
(table) count** (review §E nit): 12 shards × 2 × 100 active user tables ≈ 2.4 GiB
worst-case nominal — but only sharded, actively-written memtables hold slack
(1-shard system tables don't multiply, idle memtables acquire none), so the
practical figure is N × 2 MiB × hot-table count, tens of MiB against multi-GiB
pools (<1%) on any realistic hot set — and the slack-held gauge is the check on
that assumption; the direct-tryAllocate escape hatch means the hot shard blocks only when the
pool is genuinely within-slack of full, at which point the cleaner is already
triggered (`cleanThreshold` fires on global `allocated`, which includes handed-out
slack — conservative by design, flushing ≤1% early). Cross-shard slack stealing is
complexity without a measured victim — rejected; the slack-held gauge (below) is the
watch.

### 3.3 Flush interaction — the composite-barrier tie (H1 × H4)

Per-shard allocators make memtable discard a fan-out: at flush, `switchMemtable`
issues the COMPOSITE barrier (§1.3 site 1); only after `await()` returns — meaning
every pre-barrier writer on EVERY shard order has closed — may
`setDiscarding`/`setDiscarded` run over the N allocators. This is precisely why
§1.3's barriers await ALL N orders rather than "the shard's own order": §0
attribution is not correctness-grade, so a writer touching shard j's allocator may
hold a group on order k ≠ j (hash-attributed off-shard writer); composite-await-all
drains it regardless. H4's discard safety is therefore a direct consumer of H1's
composite semantics — the two ship in the same increment (I4) and their tests
overlap deliberately (below). PostFlush's `discardCompletedSegments` then fans out
per manager over the sealed bound vectors (§2.2-i) — the three hostiles meet in
exactly this sequence, and its ordering is unchanged from today's
(`ColumnFamilyStore.java:1244-1290` flush transaction).

### CHOSEN DESIGN (H4)
Step 1 unconditional: one `MemtableAllocator` per `MemtableShard` (removes sibling
`nextFreeOffset` CAS; lifecycle/accounting summed over N; region waste ×N accepted
and gauged). Cap + cleaner + `hasRoom` stay global. Step 2 (shard-local 1 MiB slack
batching with direct-allocate escape hatch, no stealing) built only if the named
`MemtablePool.java:155` CAS signal fires at I4 step-1's A/B; exhaustion imbalance
accepted with a stated <1% bound.

### FAILURE / UPGRADE BEHAVIOR (H4)
Process-local, no persistence, no wire impact; flag-off = single allocator
(byte-identical). Pool-exhaustion behavior unchanged in kind (global cap is the
authority; the park at `MemtableAllocator.java:186` fires for the same reasons at
the same fill levels — step 1 adds ≤ region-waste, step 2 adds ≤ slack, both
gauged). A shard-thread death mid-allocation leaks at most its current region +
slack until memtable discard (same leak class as a dead writer today). Metrics:
live-data/ownership figures are sums — JMX values keep their meaning; two new
gauges (region-waste, slack-held) are additive.

### TEST STRATEGY (H4)
- **Unit:** allocator-per-shard accounting — concurrent puts across shards, assert
  liveDataSize == Σ shard allocators and SubPool.allocated returns to baseline
  after discard (leak detection); SlabAllocator + NativeAllocator + HeapPool
  variants (`memtable_allocation_type` matrix).
- **Flush lifecycle race (the H1 tie):** writers (mixed attribution, §0 rules 1/2)
  racing `switchMemtable`; assert zero allocations land in a discarding allocator
  and no group outlives the composite await — extends the §1 memtable-switch test
  with allocator assertions.
- **Step 2 (if built):** slack-return-on-discard leak test; starvation test — fill
  the pool from one hot shard with siblings idle, assert allocation succeeds down
  to (limit − slack-bound) and the cleaner fires at the conservative threshold.
- **Burn:** long-running write+flush loop at N=4 with small
  `memtable_heap_space`/cleaner pressure — the historical OpOrder/memtable burn
  shape — watching for accounting drift (allocated vs owns reconciliation at every
  discard).
- **A/B (the step-2 gate):** phase-4 I4 write-heavy cell, step-1 on/off, profiles
  captured per the §3.2 named signal.

---

## 4. HOSTILE #5 — ChunkCache / BufferPool / CacheService: DEFERRED

Deferred with rationale (spec §3.3 allows exactly this): the shared caches are the
**least-bad** global state on the hot path — Caffeine's internals are striped and
mostly lock-free, and BufferPool already has a thread-local front layer
(`BufferPool.java:172-183`), so no sweep has produced evidence of a hostile-grade
per-op shared cacheline comparable to writeOrder's `register()` or the commitlog
CAS. Remediating now would be speculative engineering against an unmeasured cost —
and the RIGHT remediation depends on the unresolved I/O-state question:
design-target D2's arm B (full-Scylla: expanded ChunkCache as the primary data
cache) would make per-shard/NUMA sharding of the ChunkCache both more valuable and
more natural (Scylla's per-shard cache analogue — the CEP-era option design-target
§2 and §8.1 already record), whereas arm A keeps the cache small in front of the
page cache and likely never needs it. **Un-defer trigger:** post-I2 profiles at an
accepted A/B cell (either arm) showing ChunkCache/Caffeine internals (read-buffer
drain, eviction, table contention) or BufferPool's global layer as a top-frame
cost on shard threads — or arm B winning the Phase 4 adjudication, which reopens
this as the per-shard-cache design task alongside the ChunkCache expansion itself.
Until then: stays-global-atomic, per design-target D2/D3.

---

## 5. Acceptance closure

- HOSTILE #1: chosen design (ShardedOpOrder, owner-on-Group carrier, composite
  barriers at the 5-site census, §0 attribution), failure/upgrade, tests — §1.
- HOSTILE #2: chosen design (option (a) + four sub-decisions incl. the reworked
  coverage protocol of §2.2-i, W-SYNC watch item, global failure policy, async
  group-commit verdict), replay/archiving/disk-mode covered, failure/upgrade,
  tests incl. the loss-sequence regression test — §2.
- HOSTILE #4: chosen design (step 1 unconditional, step 2 measurement-gated with
  the signal named, imbalance accepted with stated multiplier and bound, flush tie
  to §1), failure/upgrade, tests — §3.
- HOSTILE #3: solved by design-target D1 (routing + owner-check fallback) — no
  section needed here by spec.
- HOSTILE #5: explicit defer with rationale and un-defer trigger — §4.
- readOrdering fate row honored: §1.6 states concretely how the same machinery
  covers it CEP-era (five-site census).
- Cross-doc consistency: deltas against design-target.md carried as explicit
  amendment notes — §6.

---

## 6. Normative amendments to design-target.md (for the main agent to apply)

This doc's decisions supersede three pinned statements in the ACCEPTED
design-target.md; per the review (§F) the contradictions must be explicit, not
silent. Precedent: design-target §9 amends phase-4 §1 the same way. Do not treat
design-target as authoritative on these three points until amended:

1. **§8.1 row 1 (writeOrder fate), carrier clause.** Replace "shard identity
   travels in `CassandraWriteContext`" with: "shard identity is a final owner
   back-reference on `OpOrder.Group`, set at both construction sites
   (`OpOrder.java:97,399`); additive package-internal change; the public
   `Memtable.accepts` signature (`Memtable.java:375`) and `CassandraWriteContext`
   are unchanged — design-hostiles §1.2."
2. **D7 fate table, writeOrder row.** Replace "carried in `CassandraWriteContext`
   (I4 pin)" with "carried by the `OpOrder.Group` owner field (I4 pin;
   design-hostiles §1.2)". In the same row and §8.1 row 1, the attribution rule
   "currentShardId else token-hash%N" gains a third tier: "else threadId%N for
   token-less empty contexts (`createEmptyContext`/`createContextForRead` —
   design-hostiles §0 rule 3; note `createContextForRead` is per-read on
   2i-indexed reads, not rare)."
3. **§8.1 row 2 (CommitLog fate), replay clause.** Replace "the global static
   segment-id allocator (`CommitLogSegment.java:70-92,145`) gives union-sortable
   ids and unchanged replay" and "the `discardCompletedSegments` early break
   (`CommitLog.java:382`) is the one single-sequence fix" with: "replay FILE
   machinery is unchanged, but the coverage protocol requires manager-banded
   segment ids (per-manager counters in disjoint id bands) + per-manager memtable
   bound vectors + N per-manager intervals in the existing IntervalSet sstable
   metadata; discard becomes a per-manager fan-out keeping today's `contains`
   terminator — design-hostiles §2.2-i. This also supersedes findings-i4-i5
   RESOLVED-1's 'replay works unchanged iff the global static allocator is shared'
   as incomplete: file-sort survives, coverage does not."
