# I1 findings — pointer + on-branch verification

**Authoritative inventory:** `../../findings-i1-mutation-apply.md`
(2026-07-07 agent report: full local-apply call graph, ShardBoundaries lifecycle, per-shard
runtime options, flag idiom, counters, 11-item adversarial pass, resolved + open questions).

**On-branch verification (2026-07-10, tpc-migration — re-verified by an independent seam sweep):**
all cited seams confirmed present; corrections/clarifications below.
- StorageProxy.java:1918 (performLocally call, coordinator self-write), :2025 (maybeExecuteImmediately,
  RequestCallback overload), :3169-3233 (LocalMutationRunnable deadline→submitHint).
- TrieMemtable.java:191 (getShardForKey), :495 (MemtableShard class), :513 (writeLock), :550 (put),
  :553 (tryLock), :556/:560 (uncontended/contended), :563 (contentionTime).
- **MemtableShard ctor is at :541** `(TableMetadataRef, MemtableAllocator, TrieMemtableMetricsView)` —
  NO shard-index arg today; instantiation call at :140. (Earlier ":133-143" named the instantiation
  region, not the ctor.) Adding the index touches BOTH :541 and :140.
- AbstractShardedMemtable.java:53 (protected boundaries, no getter — confirmed).
- MutationVerbHandler.java:80 (applyMutation), :82 (MessageParams.capture on stage thread), :83
  (applyFuture().addCallback), :54 (expiry check in doVerb).
- **Shutdown-before-commitlog ordering ALREADY EXISTS** — Stage.java:163-184 `shutdownBeforeCommitlog`
  flag → `mutatingExecutors()` → `shutdownAndAwaitMutatingExecutors()`. I0 hooks this site
  (ShardExecutors isn't a Stage → drain at the same caller / extend the filter), no new ordering code.
- ExecutorFactory: .localAware() :95/:228, .withJmx() :72/:242, .sequential() :53 — confirmed.
- TrieMemtableMetricsView: uncontendedPuts/contendedPuts/contentionTime fields (:41/:44/:47); add
  `misroutedPuts` as a sibling field + ctor init.
- CassandraRelevantProperties: (key,default) ctor idiom, getBoolean() :841, getInt(int) :971,
  NATIVE_EPOLL_ENABLED :421 boolean precedent — confirmed.
- ShardExecutors.java + MutationShardRouting.java: DO NOT exist yet (I0 creates them).

**Normative predicate correction (design-target §5/§9, E1 review amendment):** the routing predicate
MUST exclude CDC (`cdc=true`) and legacy-2i-bearing tables — both were missing/under-specified in the
first plan draft. §5's exclusion list is the predicate's source of truth. SAI stays routable.
