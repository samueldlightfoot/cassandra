# Progress — I0+I1 shard-routed mutation apply

## 2026-07-10 — Plan produced
- Read the authoritative I1 inventory; verified all seams on-branch (no drift since 2026-07-07).
- Wrote task_plan.md: Phase 0 (I0 foundation, inert) → Phase 1 (routing, flag shard_routing) →
  Phase 2 (owner-check lock skip, flag skip_lock, misroutedPuts) → Phase 3 (build+verify).
- Pinned decisions resolved from design docs (oversubscription, sequential() executors, owner-check
  fallback, routing exclusions, flags). 3 minor open items flagged (expiry re-check, 2i guard, cell def).
- Gate metric stance adopted (working default): AND-gate server-side + client-CO; see
  ../tpc-migration-planning/phase-4-poc/gate-reconciliation.md.
- **Awaiting: user OK on the plan before writing code (plan-mode-default).**

## 2026-07-10 — phase-start review + plan patched (user OK'd, chose "a")
- Re-read the authoritative inventory (findings-i1) + governing docs (increments.md §0/§2/§3,
  poc-criteria §5, gate-reconciliation, design-target §5/§9). Independent seam sweep: all 11 seams
  confirmed on-branch.
- **Two inconsistencies fixed in the plan (design-target §5/§9 normative predicate):**
  1. CDC (`cdc=true`) exclusion was MISSING → added to the pinned predicate.
  2. legacy-2i was mis-filed as open-item "confirm scope" → promoted to a pinned exclusion
     (value-token ≠ base-token); SAI stays routable; residual = custom non-SAI Index user-code guard.
  Also folded `commitlog_sync` into a **startup guard** (increments.md §2 "periodic startup check").
- **Two citation/as-built fixes:** MemtableShard ctor is at `TrieMemtable.java:541` (arg touches :541
  + call site :140), NOT :133-143. Shutdown-before-commitlog ordering ALREADY EXISTS
  (`Stage.shutdownBeforeCommitlog` / `mutatingExecutors()` / `shutdownAndAwaitMutatingExecutors()`) —
  I0 hooks it, no new ordering code.
- User approved ("a"): patch plan (done) → start I0 code.

## Next
Phase 0 code (I0, inert): flags in CassandraRelevantProperties → boundaries getter on
AbstractShardedMemtable → ShardExecutors.java → MutationShardRouting.java → unit tests. Nothing routes
yet (dead code, zero behavior change). Read real source before writing each (not doc line numbers alone).
