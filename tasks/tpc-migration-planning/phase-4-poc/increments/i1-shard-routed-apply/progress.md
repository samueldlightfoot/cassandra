# Progress — I0+I1 shard-routed mutation apply

## 2026-07-10 — Plan produced
- Read the authoritative I1 inventory; verified all seams on-branch (no drift since 2026-07-07).
- Wrote task_plan.md: Phase 0 (I0 foundation, inert) → Phase 1 (routing, flag shard_routing) →
  Phase 2 (owner-check lock skip, flag skip_lock, misroutedPuts) → Phase 3 (build+verify).
- Pinned decisions resolved from design docs (oversubscription, sequential() executors, owner-check
  fallback, routing exclusions, flags). 3 minor open items flagged (expiry re-check, 2i guard, cell def).
- Gate metric stance adopted (working default): AND-gate server-side + client-CO; see
  ../../gate-reconciliation.md.
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

## 2026-07-10 — I0 code (inert foundation) written + compiles
Verified all routing-predicate + executor + shutdown API signatures on-branch (2 seam sweeps) BEFORE
coding. Then wrote/edited (all real source, not doc line numbers):
- `CassandraRelevantProperties`: `MUTATION_SHARD_ROUTING` + `MUTATION_SHARD_SKIP_LOCK` (both "false",
  startup-pinned) — placed alphabetically after MONITORING_*.
- `AbstractShardedMemtable.getShardBoundaries()` — public getter for the pinned boundaries.
- `concurrent/ShardExecutors.java` — N=availableProcessors `sequential().localAware().withJmx` executors;
  `instance` created ONLY when flag on (zero threads by default → truly inert); per-task shard-id
  thread-local (survives worker replacement); `currentShardId()`/`currentThreadIsOwnerOf(m)` (owner =
  `m % N`, so #memtable-shards > N is safe); `execute(m, task)`; `drainAndAwait`.
- `db/MutationShardRouting.java` — `ROUTING_ENABLED` global gate = master flag AND commitlog periodic
  (fail-fast startup check + WARN); pure `route(Mutation)→OptionalInt` predicate with the design-target
  §5/§9 exclusions (views/counters/CDC/legacy-2i+custom-non-SAI/local-system/non-sharded/multi-table
  disagreement), SAI kept routable.
- `StorageService` drain: `ShardExecutors.drainAndAwait` AFTER the MUTATION-stage drain, BEFORE flush +
  commitlog stop (:3956). No-op when routing disabled.
- **`ant build` SUCCESSFUL; both new classes present in build/classes/main.** Flag off = zero behavior
  change (no instance, ROUTING_ENABLED false, drain no-op).

### Explicitly deferred (NOT dropped — next I0 sub-step)
- **Micro-instrumentation** (increments.md §2 "enqueue→dequeue handoff latency + unparks/sec from day
  one"): ThreadPoolMetrics (queue depth, completed) come FREE via `withJmx`; the bespoke handoff-latency
  histogram + unpark counter still to add (unparks needs executor-internal instrumentation). Core skeleton
  first, instrument next.
- **Unit tests**: (1) `ShardExecutors` currentShardId/owner-check/%N-mapping/drain (pkg-private ctor +
  `unsafeSetInstance`); (2) `MutationShardRouting` predicate truth table — needs CQLTester/SchemaLoader
  (route() reads real Keyspace/CFS/memtable), one cell per exclusion (view, counter, cdc, legacy-2i,
  SAI-routable, local-system, skiplist-non-sharded, multi-table disagree, happy-path present).

## 2026-07-11 — I0 unit tests GREEN + a real bug caught pre-test
- **Bug caught while writing the executor test:** `execute`/`currentThreadIsOwnerOf` mod by the static
  `SHARD_COUNT`, but the ctor took a variable `shardCount` — a `new ShardExecutors(4)` with
  `SHARD_COUNT`=cores would index a 4-element array by `m % cores` → AIOOBE. Fixed: ctor always builds
  `SHARD_COUNT` executors (executor count == modulus, one source of truth). This is exactly the owner
  -check subtlety flagged for the Fable review.
- `ShardExecutorsTest` (5/5 green): unset-on-non-shard-thread, execute-runs-on-owner + owner-check
  true/false + wrap-around (1+n owns executor 1), modulo mapping (n+1 → executor 1), thread-local
  cleared after task (raw task sees UNSET), drainAndAwait terminates.
- `MutationShardRoutingTest` (5/5 green, CQLTester): trie table routes (shard == boundaries.getShardForKey),
  skiplist non-sharded skips, counter skips, legacy-2i skips, SAI routes.
- Both compiled + ran on macOS (`ant build-test` + `ant testsome`), 0 failures/errors/skips.

### Still deferred (NOT dropped)
- Predicate cells: materialized-view base (needs view + affecting update), CDC (needs global cdc_enabled),
  multi-table boundary disagreement, local-system keyspace.
- Micro handoff-latency histogram + unparks/sec (ThreadPoolMetrics come free via withJmx).

## 2026-07-11 — Phase 1 (I1 routing) wired + coordinator path proven
- **Wiring:** `StorageProxy.performLocally` (:2023, coordinator self-write) refactored to build the
  `LocalMutationRunnable` once, then route it to `ShardExecutors` when `ROUTING_ENABLED` &&
  `route()` present (batchlog store's String description is excluded), else `maybeExecuteImmediately`
  (byte-identical). `MutationVerbHandler.applyMutation` (replica) routes the `applyFuture` likewise, else
  `apply.run()`. Added `ShardExecutors.submittedTaskCount()` (routed-apply counter, doubles as micro).
- **Gate condition** at both sites: `ROUTING_ENABLED ? ShardExecutors.instance() : null` — one null-guard
  gates flag+commitlog+instance; falls through safely if somehow null.
- **Tests GREEN:**
  - flag-off default: `MutationShardRoutingTest.routingDisabledByDefault`.
  - flag-on coordinator end-to-end: `ShardRoutedMutationApplyTest` — 200 writes via `StorageProxy.mutate`
    routed (submitted count advanced by ≥200) + all read back. Own forked JVM sets the read-once flag.
  - flag-off multi-node regression: `SimpleReadWriteTest` in-JVM 40/40 (coordinator + replica messaging
    write path byte-identical with the wiring present).
- **Gotcha:** `CQLTester.execute()` applies mutations directly (bypasses StorageProxy); only `executeNet`
  / `StorageProxy.mutate` hit `performLocally`. `executeNet` also hit a PRE-EXISTING driver
  `ProtocolVersion.supportedVersions()` incompatibility (unrelated to routing) → used `StorageProxy.mutate`.

### Deferred to Phase 2/3
- Multi-node flag-on in-JVM dtest (exercises the `MutationVerbHandler` replica routing + `misroutedPuts`).
- Phase 2: step-2 owner-check lock skip (flag `skip_lock`) + `misroutedPuts` counter.
- Full flag-off regression suite + the predicate MV/CDC/multi-table/local-system cells + micro histogram.

## Next
Phase 2 (skip-lock + `misroutedPuts`), then the multi-node flag-on dtest. Model: Opus builds; Fable
reviews the owner-check-with-lock-fallback (the subtle piece — already caught one modulus bug there).

---

## HANDOFF — after I0+I1 (2026-07-11, committed + pushed)

Commits on `tpc-migration` (pushed): `a770ed590a` (I0 foundation), `b102227cec` (I1 routing).
Flag off by default; whole existing suite green (`SimpleReadWriteTest` 40/40 flag-off).

### As-built interfaces Phase 2 uses (verbatim)
- `ShardExecutors` (`o.a.c/concurrent/ShardExecutors.java`), all owner-check via statics:
  - `public static boolean currentThreadIsOwnerOf(int memtableShardId)` — pass the memtable's RAW
    shard index (0..M-1); it does `currentShardId() == floorMod(memtableShardId, SHARD_COUNT)` internally.
  - `public static int currentShardId()` (-1 = UNSET when not on a shard thread mid-task).
  - `public static int shardCount()`; `public static ShardExecutors instance()` (null when routing off).
  - `SHARD_COUNT = FBUtilities.getAvailableProcessors()` (final; executor count == modulus).
- Flags (`CassandraRelevantProperties`, read-once): `MUTATION_SHARD_ROUTING`
  ("cassandra.mutation.shard_routing","false"), `MUTATION_SHARD_SKIP_LOCK`
  ("cassandra.mutation.shard_routing.skip_lock","false") — SKIP_LOCK enum exists, NOT yet consumed.
- `MutationShardRouting.ROUTING_ENABLED` (static final) = routing flag AND periodic commitlog.
- `AbstractShardedMemtable.getShardBoundaries() -> ShardBoundaries`.
- Wiring gate at both call sites: `ROUTING_ENABLED [&& description instanceof Mutation] ? instance() : null`,
  then `route(m).ifPresent(shards.execute(shardId, runnable))` else original path.

### Deviations from plan (+ why)
- `ShardExecutors()` ctor takes NO shardCount arg — always builds `SHARD_COUNT` executors. The plan's
  variable-count implied `executors.length != SHARD_COUNT`, which AIOOBEs (execute/owner mod by SHARD_COUNT).
  Executor count and modulus are now one constant.
- Shutdown reuses the EXISTING drain site: `ShardExecutors.drainAndAwait(...)` is called in
  `StorageService` right after `Stage.shutdownAndAwaitMutatingExecutors` (before flush/commitlog). No new
  Stage mechanism (ShardExecutors is not a Stage).
- `commitlog_sync=periodic` enforced once via `ROUTING_ENABLED` (startup), not a per-op predicate check.
- Added `ShardExecutors.submittedTaskCount()` (routed-apply counter) — not in plan; used by the flag-on test.

### Tested / deferred
- Green: `ShardExecutorsTest` (owner-check, `m%N` mapping, thread-local reset, drain),
  `MutationShardRoutingTest` (predicate: trie routes, skiplist/counter/legacy-2i skip, SAI routes,
  `routingDisabledByDefault`), `ShardRoutedMutationApplyTest` (flag-on coordinator via `StorageProxy.mutate`,
  200 writes route + read back).
- Deferred: multi-node flag-on dtest (only path that exercises `MutationVerbHandler` replica routing +
  `misroutedPuts`); predicate cells for MV/CDC/multi-table-disagree/local-system; micro handoff-latency histogram.

### Phase 2 first tasks (not yet done)
- `TrieMemtable.MemtableShard`: add `final int shardIndex` — ctor at `TrieMemtable.java:541`, instantiation
  call at `:140` (both need editing). `put` (~:550): `if (SKIP_LOCK && currentThreadIsOwnerOf(shardIndex)) apply without writeLock else existing tryLock`.
- Add `misroutedPuts` to `TrieMemtableMetricsView` (field pattern at :41/:44/:47), inc when skip_lock on but owner-check fails.

### Gotchas (given)
- `ROUTING_ENABLED` and `ShardExecutors.instance` are read-once at class init from the property → a flag-on
  test must set it in `@BeforeClass` BEFORE those classes load and run in its OWN forked JVM (`ant testsome`).
  `ROUTING_ENABLED ⟹ instance()!=null` (both read the same flag).
- `CQLTester.execute()` applies mutations directly (bypasses StorageProxy) — only `executeNet`/
  `StorageProxy.mutate` hit `performLocally`. `executeNet` currently throws a PRE-EXISTING driver
  `ProtocolVersion.supportedVersions()` NoSuchMethodError → use `StorageProxy.mutate` in tests.
- Owner-check is a hint only: wrong/absent shard id → takes the lock (still correct). Single-writer per
  memtable shard holds because shard m is written only by executor `m % SHARD_COUNT`.

---

## HANDOFF — 2026-07-11 PIVOT: Phase 2 (skip_lock) SUPERSEDED

Two user directives — "RF=3 is the assumption even for single-node tests; an RF=1-only
mechanism means little" and "move the dispatch point upstream" — plus a correctness finding
redirect the increment. Work continues in **`../i5-inbound-shard-dispatch/`**.

**Finding:** `skip_lock` (owner skips the writeLock) is RF=1-only — at RF≥3 a skipping owner
races a lock-taking unrouted writer (hint/read-repair/LWT) on the `InMemoryTrie`. The lock only
excludes threads that take it. So **Phase 2 (step-2 owner-check lock skip) is dropped.** The
RF≥3-safe, production-analogous mechanism is what I1 already ships with `shard_routing` alone:
route the apply, KEEP the lock (single-writer makes it uncontended).

**What changes (see `../i5-inbound-shard-dispatch/{task_plan,findings}.md`):**
- Drop the `MUTATION_SHARD_SKIP_LOCK` flag/enum (unconsumed); no `MemtableShard.put` change.
- **Keep `misroutedPuts` — decoupled from skip_lock** (it's D6's skew discriminator + the gate's
  hurdle evidence): increment on shard-thread owner-check-fail-but-still-tryLock.
- Pull the upstream dispatch (increments.md's I5) FORWARD as I1's completion — at RF≥3, I1-alone
  ADDS a replica hop (netty→Stage.MUTATION→shard = 2 wakes vs trunk's 1); I5 restores parity.
- Gate becomes multi-node RF=3 (single-node is coordinator==replica → inbound dispatch inert).
Reviewed by Explore (seams) + Fable (design, PROCEED-WITH-CHANGES). Multi-node poc-criteria
amendment awaits user sign-off before any gate cell.
