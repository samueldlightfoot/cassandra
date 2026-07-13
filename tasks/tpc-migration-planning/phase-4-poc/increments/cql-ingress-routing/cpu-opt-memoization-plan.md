# CPU opt #1 — memoize CqlShardRouter's per-prepared-statement routing verdict

**Target (from the asprof differential):** `SchemaConstants.containsIgnoreCase` = 2.48% of routing CPU
(trunk 0), from TWO routing-only callers, BOTH per routed write:
- `CqlShardRouter:143` `isLocalSystemKeyspace(metadata.keyspace)` (the hazard predicate).
- `Schema.getKeyspaceInstance(name)` (called at `CqlShardRouter:148` for the transient-replica check) —
  for a user keyspace it runs `isVirtualSystemKeyspace` + `isLocalSystemKeyspace` scans + a
  `ClusterMetadata.current().schema.getKeyspace` lookup.
Plus per-request `getPartitionKeyBindVariableIndexes()` (a `short[]` alloc) and the instanceof/hasConditions/
isCounter/triggers checks.

**Key insight:** the routability verdict is INVARIANT per prepared statement. `ModificationStatement.metadata`
is a `final` snapshot (frozen at prepare); prepared statements are evicted+re-prepared on
`onAlterTable`/`onDropTable`/`onDropKeyspace` (QueryProcessor schema listener) → a schema change yields a NEW
statement object. So statement-object identity is a safe memoization key for anything derived from that snapshot.

## Design
A weak-keyed Caffeine cache **inside `CqlShardRouter`** (so flag-off stays byte-identical — nothing outside
the router changes), keyed by the `ModificationStatement` identity:
`Cache<CQLStatement, Plan> = Caffeine.newBuilder().weakKeys().build()`. Weak keys → entry collected when the
statement is GC'd (post-eviction) = automatic invalidation, bounded, no leak. Value holds no ref to the key.

`Plan { boolean routable; int pkIndex; TableMetadata metadata; Keyspace keyspace; }`, `NOT_ROUTABLE` singleton.

`computePlan(stmt)` (once per statement) runs the frozen-metadata checks: `!hasConditions`, single-column PK
(`pkIndexes.length==1`), `!isCounter`, `triggers.isEmpty`, `!isLocalSystemKeyspace(keyspace)`, and resolves
the `Keyspace` ref once. Routable → cache `pkIndex`+`metadata`+`keyspace`.

`routeShard` per request: fetch `prepared` (unchanged), get `Plan` from the cache (compute-on-miss), then:
- `!plan.routable` → fallback.
- **live** `partitionDenylistEnabled && denylistWritesEnabled` (JMX-mutable) → fallback.
- **live** `plan.keyspace.getReplicationStrategy().hasTransientReplicas()` → fallback.
  (`getReplicationStrategy()` reads `getMetadata().replicationStrategy` off the STABLE Keyspace object, so it
  sees ALTER KEYSPACE — which has NO prepared-statement invalidation hook — correctly.)
- extract key at `plan.pkIndex`, `decorateKey`, `MutationShardRouting.shardForKey(plan.metadata, key)`.

## Why correct
- Frozen-metadata checks: `metadata` snapshot immutable; any table change re-prepares → new statement → new
  weak-key entry. Keyspace NAME immutable per statement (drop → onDropKeyspace invalidates).
- Runtime-mutable checks kept LIVE per request: denylist config (JMX), transient replicas (ALTER KEYSPACE,
  re-read off the cached-but-live Keyspace object).
- Routing is optimization-only; `performLocally` re-decides the apply shard authoritatively via
  `MutationShardRouting.route` — a stale/wrong route can never corrupt, only mis-place coordinate.
- Ingress-throw-safe: `computePlan` runs inside the existing `try/catch(Throwable)→fallback`; Caffeine does not
  cache thrown exceptions. Named-value assertion is on `exec.options.getValues()` (per-request, outside compute).

## Validation
- `ant jar`; `MutationShardRoutingTest`, `ShardRoutedMutationApplyTest` green (no regression). Flag-off
  unaffected by construction.
- Perf re-measure (does the 2.48% go, does routing CPU move toward trunk) = a rig session, deferred.

## Not in scope (follow-ups)
- `shardForKey` per-request cost (1.02%): key-dependent, but its shard-boundary setup may be cacheable.
- Async-future allocation (target #2): Fable investigation, separate.
