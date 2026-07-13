# Progress — routed write-path CPU overhead reduction

## Start-of-task handoff (2026-07-13) — decomposition done, no code yet

Founded from the `nonblocking-write-path` re-profile: the async Dispatcher flip removed the
coordinator↔shard **park** (+28.5 pp → +12.5 pp over off at 179k ops/s), and a CPU-profile
decomposition split the residual into recoverable waste (~half) vs the structural handoff floor
(~half). No source written yet — this is a start handoff.

**Key numbers (durable — the scratchpad profile is gone):**
- Matched-throughput (179.3k ops/s): off **58.5%** / i1-no-flip **87.1%** / flip **71.0%** CPU.
  cs/op flip **2.09** vs i1 3.47. Full table + method in `findings.md`.
- Residual +12.5 pp ≈ ~7-8 pp recoverable + ~4-5 pp floor. Steps 1-3 target ~+4-6 pp landing.
- Self-cost buckets: metric/lookup waste 10.2%, GC/alloc 3.9%, kernel(wake+net+sys) 16.6%,
  async plumbing 2.3% (irreducible), apply 18.4% (shared), other 48.6% (shared).

**Two smoking guns confirmed at source (step 1 targets):**
- `SchemaConstants.isLocalSystemKeyspace` (`:134`) + `isVirtualSystemKeyspace` (`:151`): **two
  per-write `toLowerCaseLocalized` allocations** of the keyspace name (via `Keyspace.open` →
  `Schema.getKeyspaceInstance`), ~1.8% of ALL CPU. Constant name, always-false answer, per op.
  Caveat: on the off path too → likely helps both arms; verify it shrinks the *delta*.
- `ThreadLocalHistogram.update` → `DecayingEstimatedHistogramReservoir.findIndex` (~1.5%) +
  metric-threadlocal **misses** on the shard thread (`setInitialValue`/`getEntryAfterMiss`, ~2%).
  The threadlocal-miss part is routed-specific (apply moved to a thread without warm metric TLs).

**Decisions pending (block step 2 — see `task_plan.md`):**
1. Pin the "reasonable" threshold (≤ +5 pp → 1-3 target it; ≤ trunk → only hop-deletion reaches it).
2. Confirm step 3 (queue-draining executor) is not a band-aid; batching cuts CPU, spinning doesn't.
3. Step 2 (allocation-free dispatch) is GATED on a `asprof -e alloc` run sizing the per-op allocation.

**Architectural note (don't lose this):** steps 1-3 optimize a hop the end-state architecture
*deletes*. Ingress routing (route the request to the owning shard thread at the netty inbound loop)
removes the hop for the local-replica = single-node case → parity-or-better, lock-free. 1-3 are the
pragmatic interim; if the threshold is "≤ trunk", go to ingress routing instead of step 2.

## Session 2026-07-13 — Step 1 implemented + locally verified (branch `shard-dispatch-overhead`)

User directive: "do the steps as low-hanging fruit; keep a running list of things Fable could
investigate for other perf areas." Threshold left as a tax (~+5 pp), not parity — parity stays the
separate ingress-routing job.

**Done (code, unit-verified; rig re-profile still pending):**
- **1a — per-write keyspace lowercasing removed.** `SchemaConstants`: the three system-keyspace
  name sets are now case-insensitive (`TreeSet` w/ `String.CASE_INSENSITIVE_ORDER`, allocation-free
  `contains`), and `isLocal/isVirtual/isReplicated/isNonVirtualSystemKeyspace` pass the raw name
  through instead of `toLowerCaseLocalized`. Keyspace names are ASCII-validated (`\w+`), so
  CASE_INSENSITIVE_ORDER == the old `Locale.US` lowercasing — behaviour preserved. New
  `SchemaConstantsTest` (5 cases) pins case-insensitivity. Blast radius checked: all external
  consumers use `.contains()`/`.without()`/copy-to-HashSet — no order/case dependence.
- **1b — reinterpreted, then fixed.** The finding's "shard thread misses *metric* threadlocals" was
  imprecise: shard `sequential()` executors are backed by `LocalAwareSingleThreadExecutorPlus` →
  `NamedThreadFactory` → **`CassandraThread`s**, so `ThreadLocalMetrics.get()` already takes its fast
  per-thread-field path. The real routing-specific `getEntryAfterMiss` source is
  **`ShardExecutors.CURRENT_SHARD`** — a plain `java.lang.ThreadLocal<Integer>` get/set *per routed
  task* (callers: `MutationVerbHandler:99`, `TrieMemtable:556`), which on a FastThreadLocalThread
  still probes the JDK `ThreadLocalMap`. Switched it to Netty `FastThreadLocal<Integer>` (array
  index; `-1..N` box via IntegerCache so no alloc). `ShardExecutorsTest` (5 cases) unchanged & green.

**Local verification:** `ant build` clean; `ShardExecutorsTest` 5/5, `SchemaConstantsTest` 5/5,
`ShardRoutedMutationApplyTest` 1/1 — all 0 fail/0 err, fresh XML. NOT yet committed; NOT yet on rig.

**Next = the gate for everything downstream (1d + 2a in one rig session):** deploy the Step-1 build
and, on one fresh loadgen, (i) re-profile CPU at the matched ~179k rung to confirm the toLowerCase +
`getEntryAfterMiss` frames are gone and CPU dropped **in both arms** (verify it shrinks the routing
*delta*, not just both), and (ii) run `asprof -e alloc` to size per-op dispatch allocation — that
number gates Step 2. Deploy recipe in `task_plan.md` "Verify".

**Fable-candidate list (higher-leverage, hard-to-reverse hot-path design calls — batch for a Fable
critique rather than spawning ad hoc):**
- **Step 2 — allocation-free per-shard dispatch** (reusable task objects / SPSC-MPSC queue, Scylla
  model). Hot path + hard to reverse → Fable-worthy *once 2a sizes the prize as material*.
- **Ingress routing (hop deletion)** — the architecture that *deletes* this hop for the local-replica
  case; the parity path. Biggest leverage; ingress-throw hazard. Fable-worthy at design time.
- (minor, probably not Fable) `DecayingEstimatedHistogramReservoir.findIndex` per-op binary search
  (~1.5%, the 1c reservoir cost) — cheaper bucket lookup; measure-first, likely a solo change.

## Rig re-profile (1d) + alloc sizing (2a) — 2026-07-13 — DONE

Node `157.180.98.112`, fresh hcloud ccx43 loadgen in hel1 (**deleted** after; loadgen ≤36% busy =
server-bound = valid). Matched ~180k write-only (`--rate 180000 --concurrency 3000 --threads 32`,
`--readrate 0.0`), i1 methodology (truncate + autocompaction off + fresh JVM/rung). All four captures
on ONE loadgen → drift-controlled. `results_flip/*` on the rig. Method: leaf-frame self-cost fold.

**Captures (busy% = 100−idle; ksArea = predicate ∪ lowercasing samples; both % of on-CPU):**

| tag | jar | delivered | busy% | usr% | cs/s | ksArea | getEntryAfterMiss | toLower |
|---|---|---|---|---|---|---|---|---|
| A old-flip | 888d10f7 | 180.0k | 68.56 | 49.51 | 378k | **4.01%** | **2.35%** | 2.62% |
| B1 new-flip v1 (TreeSet) | bccf0a48 | 179.4k | 70.05 | 51.06 | 367k | **5.99%** ⚠ | 0 | 0 |
| B2 new-flip v2 | 19e44ac9 | 176.5k | 67.77 | 48.86 | 377k | 2.90% | 0 | 0 |
| B3 new-flip v2 (matched) | 19e44ac9 | 179.9k | **67.41** | 48.43 | 377k | 3.00% | 0 | 0 |

**Headline (drift-controlled, matched ~180k, A vs B3):** flip-arm busy **68.56 → 67.41 (−1.15pp)**,
usr −1.08pp, cs/s flat. Targeted frames: keyspace-check **4.01 → ~2.95% (−1.05pp)**,
`getEntryAfterMiss` **2.35 → 0 (−2.35pp)**, toLower allocation eliminated (alloc profile = 0).

**1a v1 was a REGRESSION — caught and fixed on the rig.** The first cut made the name sets
`TreeSet(String.CASE_INSENSITIVE_ORDER)`. That removed the allocation but the comparator's per-char
`Character.toUpperCase/toLowerCase` over ~8 compares/lookup cost MORE than the allocate-and-hash it
replaced: ksArea 4.01 → **5.99%**. Reworked to plain `ImmutableSet` + `containsIgnoreCase` (direct
`contains` fast-path; lowercase a copy only if the name has an uppercase char — never on the
all-lowercase write hot path). v2 ksArea = **2.90–3.00%**, below the old baseline. Lesson in
`tasks/lessons.md`.

**1b (getEntryAfterMiss → 0) is routing-specific** (`CURRENT_SHARD` is only touched on the routed
dispatch path), so it shrinks the routing *delta*, not just both arms. 1a helps both arms (shared
`Keyspace.open`). Off-arm not separately captured: the delta-shrink follows from 1b being
routing-only; 1a's ~1pp off-arm effect is below the aggregate busy% noise (~±1pp) anyway.

**Honest read:** real and in the right direction, but **modest (~−1 to −1.5pp aggregate)** — short of
the plan's optimistic +2-4pp for step 1. The frame reductions (−3.4pp targeted) exceed the aggregate
drop because the other ~95% (Mutation apply/CQL/commitlog) has run-to-run variance larger than the
signal. The bigger recoverable chunks are Step 2 (dispatch alloc) and Step 3 (wakeup batching).

**2a — alloc sizing (gates Step 2):** `asprof -e alloc` on new-flip. Dispatch machinery is a MINORITY
of allocation — `AsyncPromise`/`DefaultPromise` ~6.7%, `LocalMutationRunnable`/routing partial; the
`Mutation` payload dominates (~66%, shared with off). On-CPU GC only ~4.4% total. → **Step 2's on-CPU
prize is small (~1-1.5pp); its real payoff would be the p95+ GC-pause tail, not on-CPU.** Gate = LOW
priority for on-CPU CPU; worth it only if the allocation-driven tail is the target.

**Rig state left behind:** node UP running the **v2 new-flip jar** (sha `19e44ac9`, routing ON),
autocompaction disabled on `cassandra_easy_stress.keyvalue` (restart resets to default-on). Jars on
rig at `…/build/`: active `…jar` = v2 (`19e44ac9`); `…jar.step1` = v1 TreeSet (`bccf0a48`);
`…jar.flip-prestep1` = prior flip (`888d10f7`). Loadgen deleted. Branch `shard-dispatch-overhead`
(off `tpc-nonblocking-write`), not pushed.

## Entry point for a fresh agent

Read, in order: **this `progress.md`** → `findings.md` (same dir — the decomposition, sites, the
recoverable/floor split) → `task_plan.md` (same dir — steps 1-3, the pinned decisions) →
`../nonblocking-write-path/findings.md` "Re-profile results" (the park-removal baseline these numbers
build on). Then source for step 1: `schema/SchemaConstants.java` (`:132-160`), `db/Keyspace.java`
(`open`/`getKeyspaceInstance` caller), `metrics/{ThreadLocalMetrics,ThreadLocalHistogram,ThreadLocalTimer,
DecayingEstimatedHistogramReservoir}.java`. For steps 2-3: `concurrent/ShardExecutors.java`,
`db/MutationShardRouting.java`, the `LocalMutationRunnable` local-apply branch in `service/StorageProxy.java`.

Rig/loadgen facts + deploy recipe: `task_plan.md` "Verify" + `../nonblocking-write-path/progress.md`
"Re-profile … HANDOFF". Branch off `tpc-nonblocking-write` (not pushed to origin; rsync-of-jar deploy).

Starting prompt (paste):

> Reduce the single-node routed write-path CPU overhead — new increment
> `tasks/tpc-migration-planning/phase-4-poc/increments/shard-dispatch-overhead/`. Read its
> `progress.md` → `findings.md` → `task_plan.md`, and `../nonblocking-write-path/findings.md`
> "Re-profile results" first. Context: the async Dispatcher flip removed the coordinator↔shard park
> (+28.5 pp → +12.5 pp over off at 179k ops/s); a CPU-profile decomposition shows ~half the residual
> is recoverable waste, ~half is the handoff floor. Branch off `tpc-nonblocking-write`.
> FIRST: confirm the two pinned decisions with me (the "reasonable" threshold; step-3 band-aid
> classification) — these gate the work. THEN start Step 1 (cheap, high-confidence): kill the
> per-write `toLowerCaseLocalized` in `SchemaConstants.isLocalSystemKeyspace`/`isVirtualSystemKeyspace`
> (`:134`/`:151`, on the `Keyspace.open` write path — case-insensitive set or memoize per keyspace),
> and the shard-thread metric-threadlocal misses (`ThreadLocalMetrics`/`ThreadLocalHistogram`). Build
> + unit-test, then re-profile on the rig at matched throughput (deploy recipe in task_plan "Verify":
> `ant jar` → rsync jar → swap → restart; fresh hcloud ccx43 loadgen in hel1, delete when done).
> Compare the leaf-frame self-cost fold to the flip baseline (71.0% CPU, 2.09 cs/op) — confirm the
> toLowerCase/threadlocal-miss frames are gone and CPU dropped, in BOTH arms (verify it shrinks the
> routing delta, not just both). Step 2 (allocation-free dispatch) is GATED on a `asprof -e alloc`
> run sizing the per-op allocation — do that before the surgery. Do NOT chase parity single-node;
> the target is a "reasonable" regression per the pinned threshold — parity needs ingress routing
> (hop-deletion), tracked separately.
