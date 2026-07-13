# Progress — CQL-path ingress routing (single-node inbox hop)

## CPU FIXES IMPLEMENTED (2026-07-13) — awaiting rig re-measure

Two fixes from the CPU-hunt, committed on `tpc-migration`, unit-tested, NOT yet perf-validated (rig re-run
pending). See `cpu-opt-memoization-plan.md` + `perf-ab-methodology.md` §RESULTS-VS-TRUNK CPU-hunt.

- **#1 Memoize the routing verdict** (`CqlShardRouter`, commit `4d27780d47`). The invariant part of the
  predicate (two `isLocalSystemKeyspace` scans + keyspace lookup = the profiled 2.48%) now computed once per
  prepared statement in a weak-keyed cache (statement identity; re-prepare on schema change → new object →
  fresh verdict). Runtime-mutable gates (denylist config, transient replicas via the live-read cached
  `Keyspace`) stay per-request. Flag-off untouched. Tests: MutationShardRoutingTest 9/9, ShardRoutedMutationApplyTest 1/1.
- **#2 Single-mutation async-write fast path** (`StorageProxy.mutateAsync`, commit `cb036fbc9c`, from the
  Fable investigation). For `responseHandlers.length == 1` (every routed single-partition write) return the
  one handler's `outcome()` directly instead of seeding `ImmediateFuture.success(null)` + one `andThenAsync`
  — drops a future+listener+lambda per write (~1.4–1.8pp of the +18% alloc). QUORUM await + write-timeout
  timer untouched; batches/empty unchanged. Tests: WriteResponseHandler(Transient) 9/9+4/4, StorageProxyTest 5/5.
- **Fable's other findings (not done):** #2b replace `ExecuteMessage.promise` adapter with map/recover
  (~1.0–1.4pp, medium risk — must preserve the success-wrap-Exception/fail-Throwable split); #2c `OptionalInt`
  →int sentinel (~0.35pp). TRAPS (do not touch): `AWRH.outcome` timer (= the flip's write timeout),
  `Dispatcher.finalized` callback (thread-local restore), listener executor-affinity, listener-list presizing.
- **NEXT:** rig re-measure — trunk vs routing(+both fixes), same A/B, does routing CPU move toward trunk 58%
  and does the 2.48% + the async-alloc go? Re-provision the ccx43 loadgen; jars: `.jar.trunk` still staged,
  rebuild routing from `tpc-migration` tip (now carries both fixes) → new validated jar.

## vs-TRUNK MEASUREMENT DONE (2026-07-13) — PoC-criterion read + CPU-hunt

Full data + method in `perf-ab-methodology.md` §RESULTS-VS-TRUNK. Headlines:

- **Baseline correction (load-bearing):** the handoff below said build "parent-of-flip `55a1a71f0a`" as
  pre-TPC trunk — **WRONG**, it still carries I1+I5+step1. Real pre-TPC trunk = the fork point
  **`50ddce8455`** (= local `trunk` tip, zero drift). User-confirmed. Trunk jar built on the rig (same
  JDK 17/ant as routing), verified no TPC/io-uring classes; staged as `…build/…jar.trunk`.
- **Same-session A/B (trunk vs routing, G1, off-box ccx43):**
  sub-knee CPU **58.0% (trunk) vs 64.9% (routing)**; cs/op 1.94 vs 1.62 (−16%, mechanism holds);
  p99 **200.3 vs 218.1ms**; sat peak **272.8k vs 277.7k**; Blocked=0 both.
- **PoC verdict:** **throughput ≥ trunk PASSES** (matched sub-knee; sat 277.7k ≥ 272.8k). **p99 ≤ trunk is
  UNANSWERABLE on G1** — mean service time identical (11.5≈11.75ms); the +9% p99 gap is entirely GC
  (p99≈18× mean, `MaxGCPauseMillis=300`). On raw numbers routing's tail is slightly worse. Routing costs
  ~7pp CPU vs stock trunk (drift-caveated; interleaved would firm it).
- **CPU-hunt (asprof 3.0 differential, routing−trunk @182k):** routing = +20% CPU / +18% alloc. Targets:
  (1) **`SchemaConstants.containsIgnoreCase` 2.48%** — per-request keyspace scans from `CqlShardRouter:143`
  `isLocalSystemKeyspace` + extra `Schema.getKeyspaceInstance`; invariant per `TableMetadata` → **memoize**
  the routability/shard decision per prepared statement (~2.5pp, low-risk, the clean next fix). (2) async-
  future alloc ~7pp (flip's `AsyncPromise`/`AsyncFuture`/`ListenerList` churn + `OptionalInt` boxing). (3)
  `DecayingEstimatedHistogramReservoir.findIndex` 1.82% (hot-path metrics histogram).
- **Rig end state:** routing-ON restored (flags on, 12 shard pools, native active). Loadgen DELETED.
  Jars preserved incl. `…jar.trunk`, `…jar.routing-validated`.
- **NEXT (candidates, user to steer):** (a) implement the memoization fix + re-measure; (b) ZGC tail arm
  to make the p99 gate answerable (non-gen ZGC on JDK 17 now, or generational after a JDK 21 upgrade);
  (c) interleaved A/B/B/A to settle the CPU delta.

## PHASE 4 HANDOFF (2026-07-13) — vs-TRUNK measurement (fresh context) [SUPERSEDED by the section above; baseline note in it was wrong — parent-of-flip ≠ trunk]

Mechanism (Phase 3) + the clean flip+step1-vs-routing A/B are DONE (see the two 2026-07-13 sections
below and `perf-ab-methodology.md` §RESULTS). **The one thing left to answer the actual PoC criterion
(`poc-criteria.md`: p99 ≤ trunk at throughput ≥ trunk) is a clean TRUNK baseline on the same rig.**
Everything measured so far is vs **flip+step1** (the async-write branch), NOT trunk.

**(1) What's established (clean, rig, 2026-07-13):** flip+step1 (flag-off) = 65.5% CPU @ 182k, cs/op 2.10,
peak 310k, client p99 ~193ms. CQL routing (flag-on) = 60.1% @ 182k, cs/op 1.54, peak 298k, p99 ~194ms.
Regime crossover: routing −5.4pp CPU at moderate load, −4% peak at saturation, tail-neutral.

**(2) The task:** build a TRUNK jar, deploy to the rig, run the SAME A/B protocol, get trunk's CPU /
sub-knee throughput / saturation peak / p99. Then the PoC read: is routing-ON's p99 ≤ trunk's at
throughput ≥ trunk's? Report vs trunk with the same variance discipline.

**(3) Trunk-baseline choice (confirm with user):** for a clean "only the TPC stack differs" comparison,
build the **parent of the flip commit `3ccf35950e`** (pre-TPC trunk on this lineage) rather than the
`trunk` branch tip (avoids unrelated trunk drift). `git log 3ccf35950e~1 --oneline -1` to see it.

**(4) p99 apples-to-apples (CRITICAL):** the async flip does NOT record `ClientRequest.Write.Latency`
(proxyhistograms + JMX read 0 on our branch); TRUNK DOES. So do NOT compare trunk-server-p99 to
branch-client-p99. Use the **client `--hdr` (CO-corrected) p99 for ALL arms** as the common denominator —
OR first fix the flip's metric (stop the ClientRequest.Write timer on the async completion path) so every
arm has a server p99. The steady console p99 (~193ms here) is GC/flush-dominated; a big-op workload or
GC read is needed if the tail must be de-noised (`gc_dominates_cassandra_tail`).

**(5) Method + harness (all on the rig):** identical to `perf-ab-methodology.md`. Per arm: `prep_flip`
(fresh JVM + truncate + autocompaction off) → 3× `/root/abwin.sh <tag> <warm>` 60s windows (warm 40/2/2)
→ 1 saturation window. Sub-knee `--rate 180000 --concurrency 3000 --threads 32 --readrate 0.0 --hdr`;
saturation `--rate 350000 --concurrency 4000 --threads 40`. Off-box ccx43 hel1 loadgen (cloud-init builds
easy-cass-stress; recipe in `perf-ab-methodology.md` + `agent-common/rig/cloud.md`) — **delete when done**.
`abwin.sh` = CLEAN window (mpstat busy% + vmstat cs/op + tablestats throughput; NO profiling inside it).

**(6) Trunk-specific gotchas:** trunk has NO shard executors — `prep_flip.sh` verifies `Shard-N` pools
and expects ON; on trunk it will read `pools=OFF` — EXPECTED, not a failure (edit the check or ignore).
Trunk ignores `-Dcassandra.mutation.shard_routing` / `cassandra.tpc.cql_ingress_routing` (harmless
no-ops; comment them out of `jvm-server.options` for cleanliness). Loadgen `--hdr` files: confirm where
ecs writes them (they didn't appear last run — used the steady console p99 instead).

**(7) Rig deploy:** current live jar = `bf5e4356` (validated routing, both flags on). **Back it up before
swapping in trunk** (`cp …jar …jar.routing-validated`), and RESTORE it after (rig should end in the
validated routing state). Existing backups: `.jar.pre-cql-ingress`=`19e44ac9` (flip+step1),
`.jar.step1`, `.jar.tpc-migration-baseline`. Raw A/B data in `/root/results_ab/`. Scripts:
`/root/{abwin,rcnt,prep_flip,seam_switch,seam_run}.sh`.

**(8) Harness lessons (cost real time this session — don't repeat):** NO `asprof`/`perf` inside the CPU
window (inflated busy% ~14pp → false "CPU flat"); each ecs invocation is a COLD JVM (whole-run `--hdr`
p99 is cold-contaminated — read steady console p99 or use a long run + mid-run window); the 3rd 60s window
overruns a 300s loadgen (use ≥400s for 3 windows, or the window catches the run ending → garbage);
FRESH `prep_flip` per arm (matched JVM + table state); table-state matters at saturation, not sub-knee.

**(9) Branch:** work is on **`tpc-migration`** (consolidated, pushed to origin). `shard-dispatch-overhead`
+ `tpc-nonblocking-write` deleted. Commit trunk-measurement notes here on `tpc-migration`.

**Start prompt to paste:**
> vs-trunk measurement for CQL ingress routing. Read `tasks/tpc-migration-planning/phase-4-poc/increments/
> cql-ingress-routing/progress.md` (PHASE 4 HANDOFF) → `perf-ab-methodology.md` §RESULTS. Everything
> measured is vs flip+step1; the PoC criterion needs TRUNK. Build the pre-TPC trunk (parent of flip commit
> `3ccf35950e` — confirm with me), deploy to rig `157.180.98.112` (back up the live `bf5e4356` routing jar,
> restore after), run the SAME A/B protocol (`prep_flip` → 3× `abwin.sh` sub-knee windows + 1 saturation,
> off-box ccx43 loadgen, delete after). Use CLIENT `--hdr`/steady console p99 for all arms (the flip broke
> server `ClientRequest.Write.Latency`). Answer: is routing-ON p99 ≤ trunk at throughput ≥ trunk? Branch
> `tpc-migration`. Harness gotchas in the handoff §8 — no profiling in the CPU window.

## PHASE 3 HANDOFF (2026-07-13) — rig validation of the built skeleton

Phase 2 is built, compiles, unit-tested, uncommitted on `shard-dispatch-overhead`. Phase 3 = validate the
flag-on native path on the rig. Read the entry point (§7 below) before acting.

**(1) Plan deviations (+ why).** The design's §4 part-A/part-B split was collapsed: the loop predicate only
excludes COORDINATE-time hazards (LWT/counter/triggers/local-system/denylist-write-gate/transient-replica),
because `StorageProxy.performLocally` already re-runs the authoritative `MutationShardRouting.route(mutation)`
on the shard thread, so apply-hazards (views/CDC/legacy-2i) are handled there — the loop needn't re-check them.
This made `CqlShardRouter` smaller/safer than §10 implied. Scope narrowed to **single-column PK only** (composite
= fallback) to avoid `CompositeType` assembly on the loop. §6 `hasQueueCapacity` shard-inbox backpressure term
NOT built (deferred to Phase-3 hardening).

**(2) As-built interfaces (verbatim).**
- Flag: `CassandraRelevantProperties.CQL_INGRESS_ROUTING("cassandra.tpc.cql_ingress_routing", "false")`.
  Master gate `CqlShardRouter.ENABLED = CQL_INGRESS_ROUTING.getBoolean() && MutationShardRouting.ROUTING_ENABLED`
  (so ALSO needs `-Dcassandra.mutation.shard_routing=true` + periodic commitlog). Read once at startup.
- `transport/CqlShardRouter.routeShard(Message.Request request) -> OptionalInt` (never throws; empty ⇒ NTR path).
  `CqlShardRouter.routedCount()` / `fallbackCount()` (longs).
- JMX metrics: type=`CqlShardRouting`, names `Routed` and `Fallbacks` (Counters). Rig check =
  `nodetool sjk mxdump` or the metrics exporter for `...CqlShardRouting...Routed`. **Routed>0 proves the loop
  route fired on the native path** — the single thing in-JVM dtests can't show.
- `QueryProcessor.getPreparedNoTouch(MD5Digest id) -> QueryHandler.Prepared` (static; `asMap().get`, no eviction).
- `MutationShardRouting.shardForKey(TableMetadata metadata, DecoratedKey key) -> OptionalInt`.
- `Dispatcher.dispatch()`: routes when `CqlShardRouter.ENABLED && !isAuthQuery` and `routeShard` present +
  `ShardExecutors.instance()!=null`, via `shards.execute(ExecutorLocals.current(), shard, new RequestProcessor(...))`;
  else the original `executor.submit(...)`. Flag-off ⇒ ENABLED false ⇒ original path unchanged.
- `StorageProxy.performLocally` owner-inline bypass: `if (ShardExecutors.currentThreadIsOwnerOf(shardId)) localMutationRunnable.run(); else shards.execute(...)`.

**(3) Tested / deferred.** Tested: `ant build` OK; `MutationShardRoutingTest` 9/9 (2 new shardForKey tests);
`ShardRoutedMutationApplyTest` 1/1 (performLocally regression-clean). Flag-off byte-identical by construction.
NOT tested locally (i5-findings:40 — in-JVM bypasses `Dispatcher`): routeShard firing, owner-inline collapsing
hop B. Deferred: composite-PK routing, §6 backpressure term. Not committed.

**(4) Decisions.** Loop guards coordinate-hazards only (rationale above). Route the whole `RequestProcessor`
(not a new coordinate) so no coordinate code changes. `ExecutorLocals.current()` snapshots loop locals (≈none;
matches requestExecutor's localAware submit).

**(5) Gotchas.** `CassandraRelevantProperties` enum MUST stay alphabetical by constant NAME (a `<clinit>` check
fails the build otherwise — cost one build). `ant build` ≠ jar; deploy needs `ant jar` + verify class is IN the
jar (`feedback_cassandra_jar_rebuild`). In-JVM dtests are vacuous for the route point. easy-cass-stress is NOT
on the rig (build it; or provision a loadgen per `agent-common/rig/cloud.md`, token at `.secrets/hcloud.token`,
`HCLOUD_TOKEN` env — empty `hcloud context list` is NOT unauthed). Loadgen bills hourly — delete when done.

**(6) Assumptions given.** Baseline seam split: `SharedPool` (coordinate/NTR pool) = **43.5% of context switches
(0.735/op)** at 214k/74.2%CPU/cs-op≈1.69 (`measure-seam-attribution.md` §RESULTS). Phase-3 success = routed run
shows `SharedPool` switch share DROP (coordinate moved onto shards), writes correct, flag-off clean, shards not
CPU-saturated (the bounded risk). Rig `157.180.98.112` runs the flip+step1 baseline jar (routing ON, no CQL flag);
`/root/perf_wake.sh`, `/root/seam_run.sh`, `prep_flip.sh` staged. Deploy recipe: shard-dispatch-overhead task_plan
"Verify" (`ant jar` → rsync → swap `…jar.tpc-migration-baseline` → restart) + add `-Dcassandra.tpc.cql_ingress_routing=true`
to `/data/tpc-poc/conf/jvm-server.options`.

**(7) Entry point for a fresh agent.** Read in order: THIS `progress.md` (top section) → `task_plan.md` (Phase 2
BUILT + Phase 3 items) → `design-cql-ingress-routing.md` (§1 hop model, §5 non-blocking, §11 validation) →
`measure-seam-attribution.md` §RESULTS (the baseline to beat). Then the as-built source:
`transport/CqlShardRouter.java`, the `Dispatcher.dispatch()` branch, `StorageProxy.performLocally` bypass.
Starting prompt to paste:

> Phase 3 — validate the built CQL ingress-routing skeleton on the rig. Read
> `tasks/tpc-migration-planning/phase-4-poc/increments/cql-ingress-routing/progress.md` (PHASE 3 HANDOFF) →
> `task_plan.md` → `design-cql-ingress-routing.md` (§11) → `measure-seam-attribution.md` §RESULTS. Branch
> `shard-dispatch-overhead` (uncommitted Phase-2 build). FIRST action: `ant jar` locally, verify `CqlShardRouter`
> is in the jar, rsync-deploy to rig `157.180.98.112`, restart with BOTH `-Dcassandra.mutation.shard_routing=true`
> and `-Dcassandra.tpc.cql_ingress_routing=true`. Then drive prepared single-partition writes (easy-cass-stress
> KeyValue — build on rig or provision a loadgen per agent-common/rig/cloud.md) and confirm: (a) `CqlShardRouting.Routed`
> counter climbs (route fires on the native path), (b) writes read back correct, (c) re-run `/root/seam_run.sh` — the
> `SharedPool` switch share drops vs the 43.5% baseline (hop deleted), (d) flag-off regression-clean, (e) shards not
> CPU-saturated. Gate on mechanism evidence + tail-neutrality, not a headline p99 (i5-findings:22). Delete any loadgen when done.


## Session 2026-07-13 (later) — PHASE 3 VALIDATED ON RIG — PASS

Deployed the fixed jar to `157.180.98.112`, drove prepared single-partition KeyValue writes from an
off-box ccx43 loadgen (hel1, deleted after), routing ON. **All five Phase-3 gates pass. Mechanism proven
on the real native path — the thing in-JVM dtests cannot show.**

- **BUG FOUND + FIXED (rig-only, unit tests were vacuous).** First flag-on boot: every native request died
  at `dispatch()` — `CqlShardRouter.<clinit>` threw `IllegalStateException: Unknown metric group:
  CqlShardRouting` (`CassandraMetricsRegistry.verifyUnknownMetric`, its 279-line static `metricGroups`
  whitelist). A metric `type` not in that set fails registration; the failed `<clinit>` then
  `NoClassDefFoundError`s every request (node up but serves nothing). **Fix:** reuse the registered `Client`
  group (`ClientMetrics.TYPE_NAME`) with distinctive names `CqlIngressRouted` / `CqlIngressFallbacks` — this
  is what the I5 sibling does (`ShardInboundRouter` registers under `MessagingMetrics.TYPE_NAME`), so design
  §10's "exposed like I5's" already implied group-reuse. Smaller than adding to the core registry, no
  `JmxVirtualTableMetricsTest` impact. **Fix is uncommitted** in `transport/CqlShardRouter.java` (3 lines +
  1 import) alongside the Phase-2 build. Lesson recorded.
- **(a) Route fires on the native path — DECISIVE.** `CqlIngressRouted` counter climbed 0 → 8,384,242 over
  a 43 s window ≈ 195k/s ≈ **100% of delivered writes**; `CqlIngressFallbacks` moved only +37 (driver
  control/reads). ecs KeyValue = prepared `INSERT INTO keyvalue (key,value) VALUES (?,?)`, single-column PK
  bound at index 0 — exactly the router's routable shape; its `SELECT` reads are non-`ModificationStatement`
  so they correctly fall back.
- **(b) Writes read back correct.** cqlsh sample of routed rows: well-formed keys (`001.6.862229`) + values
  (100–200-char text per the ecs FieldGenerator); COUNT=339,650 (< write count because ecs recycles keys —
  no loss). Unprepared cqlsh writes coexist correctly on the NTR path.
- **(c) SharedPool switch share dropped 43.5% → 0.0% (hop A DELETED).** Reproduced the exact baseline metric
  (`perf sched:sched_switch` waker⇒wakee matrix, "switches touching SharedPool"; my parser reproduces the
  baseline's 43.5% from `switchmatrix4.txt` before applying it). Routing-ON: **touching SharedPool = 0.0%**
  (NTR pool completely bypassed; `tpstats` NTR-completed 181 vs 19.6M flag-off). Cross-confirmed by
  `seam_run.sh` wakeup rollup: **hopA epoll→SharedPool 0.0%, hopB SharedPool→Shard 0.0%**, hopC (flush,
  structural) 10.4% — the new pattern is `epoll⇒Shard-N` (hop 1) + `Shard-N⇒epoll` (hop 2), the design's
  2-hop model exactly. cs/op 1.69 → **1.154** (cs/s 361k → 223k).
- **(d) Flag-off regression-clean (same jar, flag commented).** `Routed`=0 (router inert), touching
  SharedPool back to **45.0%**, NTR-completed 19.6M, delivered 216k, CPU 74.4%, cs/op 1.461, shards
  Blocked=0. Reverts to the baseline path exactly.
- **(e) Shards NOT saturated (the bounded risk did not bite).** Routing-ON: shard switch share rose
  40.9%→50.7% (coordinate folded on, as predicted) but all-time Blocked=0, ~74% CPU (26% idle), no core
  pegged, Pending transient (tens, no backlog).
- **Tail-neutral-to-favorable.** Clean 0-error runs: client CO-corrected p99 flag-on **191 ≤ flag-off 234**
  (same units); client steady rate ~161k both. Honest caveat: hand-aligned server tablestats showed 193k
  (on) vs 216k (off) — comparable within window-boundary noise; a definitive matched-throughput A/B (to
  quantify any small shard-serialization throughput cost) needs a saturation sweep, beyond the Phase-3
  mechanism gate. One flag-on window (`run_seam2`) hit 855k client CO drops (over-offer variance, not a
  Cassandra fault) — its seam share is throughput-independent so hopA=0 still holds.
- **Same-jar A/B (the cleanest comparison, ~200k offered, ~74% CPU):**
  | metric | flag-OFF | flag-ON |
  |---|---|---|
  | Routed delta | 0 | 8.38M (~100% of writes) |
  | SharedPool switch share | 45.0% | **0.0%** |
  | Shard switch share | 39.6% | 50.7% |
  | cs/op | 1.461 | **1.154** |
  | client p99 (CO-corr) | 234 | **191** |
  | shard Blocked | 0 | 0 |
- **Rig end state:** fixed jar (sha `bf5e4356`) deployed at `/root/repos/fork/cassandra-tpc-i1/build/…jar`
  (baseline preserved `.jar.pre-cql-ingress` = sha `19e44ac9`); both flags live in
  `/data/tpc-poc/conf/jvm-server.options`; node UP routing-ON, serves cleanly. Capture script
  `/root/seam_switch.sh` (new) + `/root/rcnt.sh` (counter reader); raw artifacts in `/root/results_seam/`
  (`switchmatrix_ssr1`/`off1`, `wakematrix_ssr_wake`, `mpstat_*`, `tpstats_*`).
- **NEXT (Phase-3 hardening, not gating):** commit the metrics fix + Phase-2 build; composite-PK routing;
  §6 `hasQueueCapacity` shard-inbox term; if a throughput number is wanted, a matched-saturation A/B sweep.

### Clean performance A/B (2026-07-13 later) — REGIME CROSSOVER (`perf-ab-methodology.md` §RESULTS)

The Phase-3 seam numbers proved the *mechanism* cleanly but the throughput/CPU/p99 were noisy (hand-aligned
unequal windows, over-driven regime). Redone properly: same jar, only the flag differs, fresh `prep_flip`
per arm, 3× 60s sub-knee windows + 1 saturation window, off-box loadgen. Two harness bugs fixed: profiling
(`asprof`+`perf`) *inside* the CPU window inflated busy% ~14pp (removed — cs/op from vmstat instead); the
flip records no `ClientRequest.Write.Latency` (server p99 unavailable, both arms — unbiased).
- **Sub-knee ~182k:** CPU **65.5% → 60.1% (−5.4pp)**, cs/op **2.10 → 1.54 (−27%)**, %usr −2.8 / %sys −2.8,
  Routed ≈100%/window, shards Blocked=0. Flag-off reproduces the flip+step1 baseline (65.5% ≈ documented
  67.4%, cs/op 2.10 ≈ 2.1), validating the harness. **Routing is a real moderate-load CPU win** — matching
  the earlier context-switch arithmetic (the first "CPU flat" read was profiling contamination).
- **Saturation (matched loaded table):** peak deliverable **310k (off) → 298k (on) = −4%**, both 98% CPU,
  cs/op 0.446 → 0.716 (routing +60% switches/op), Blocked=0. The design's bounded risk (shard serialization
  under load) is real but modest: coordinate on 12 shard threads + per-request loop↔shard handoff can't
  batch like the wide NTR pool, capping peak ~4% lower.
- **Tail:** client CO-corrected steady p99 ~193ms (off) ≈ ~194ms (on) — **neutral**, GC/flush-dominated.
- **Verdict:** CQL routing WINS at moderate load (−5.4pp CPU, tail-neutral), COSTS ~4% peak throughput at
  saturation. Crossover. NB the A/B is vs **flip+step1**, not trunk — isolates the routing increment; a
  vs-trunk PoC-criterion read needs a separate trunk build.


## Session 2026-07-13 — crux settled, design doc written, Fable critique in flight

- **Crux SETTLED (verified on branch), and it overturns the increment's premise.** findings.md counted
  2 hops by conflating the netty loop with the NTR pool. Verified: coordinate runs on `requestExecutor`
  (a separate NTR pool, `Dispatcher.java:143`/`:663`), so today's single-node routed write is **3 hops**
  (loop→NTR→shard→loop). **Server-side routing ON the netty loop — via cheap prepared-`ExecuteMessage`
  key extraction (`getPartitionKeyBindVariableIndexes`, `CQLStatement.java:49`) — deletes the loop→NTR
  hop (3→2), no client protocol change.** "Pure relocation" is only the route-after-parse+bind variant.
  The shard-aware protocol is decoupled/later: under kept-Netty (design-target §3.2) per-shard ports buy
  affinity, not fewer hops; 0-hop needs shard socket ownership (PoC rejects). Verdict recorded in
  findings.md "CRUX VERDICT" section.
- **User direction (2026-07-13):** frame = **build server-side, protocol later** (design doc's headline
  recommendation).
- **Design doc written:** `design-cql-ingress-routing.md` — corrected hop model (§1), route point in
  `Dispatcher.dispatch` (§2), ingress-throw-safe key extraction (§3), allowlist reusing design-target §5
  (§4), coordinate-on-shard non-blocking via the flip + owner-inline apply (§5), backpressure bypass (§6),
  I5 mechanism reuse (§7), why the protocol is decoupled (§8), Scylla map (§9), build sketch (§10),
  validation (§11), risk register for Fable (§12).
- **Fable adversarial critique DONE — verdict PROCEED-WITH-CHANGES.** Confirmed the crux + 3→2 mechanism
  against source; found 6 real defects (all verified by me at source before folding):
  1. **Owner-inline apply bypass does NOT exist in `performLocally`** (`StorageProxy.java:2399-2410` is
     unconditional; bypass exists only at `db/MutationVerbHandler.java:99`). It's a BUILD ITEM, not reuse.
  2. **Predicate was apply-scoped, missing coordinate-time blockers** — triggers (`TriggerExecutor` inline
     `:1310/1358`), partition denylist (sync distributed read on miss), transient-replication await
     (`maybeTryAdditionalReplicas`→`writeResult.await` `AbstractWriteResponseHandler.java:521`), custom
     QueryHandler, named-values `OptionsWithNames`. Added §4 part B (coordinate-hazard list).
  3. **Loop-side `getPrepared` can run a synchronous system-table write** (Caffeine `ImmediateExecutor`
     + removalListener → `removePreparedStatement`, `QueryProcessor.java:144-169`). Use `asMap().get()`.
  4. cs/op arithmetic is hops≠context-switches; "~1.4" was a guess → §11 gates on seam-attributed evidence.
  5. Backpressure hole real (`hasQueueCapacity` reads only NTR queue) → §6 specifies the fix.
  6. My §8 over-claimed 0-hop unreachable → shard-as-EventLoop reaches it with Netty kept (strengthens
     "protocol later"). Corrected §8/§9.
  All 6 folded into `design-cql-ingress-routing.md` (marked ⟵FABLE). Nice-to-haves noted (short[] cache,
  full-write-plan replica check, tpstats-goes-dark observability, in-JVM-dtest vacuousness).
- **Gate decision (user, 2026-07-13): MEASURE FIRST, then build.** Pre-build seam attribution run on the
  rig (off-box ccx43 loadgen, provisioned + torn down per `agent-common/rig/cloud.md`).
- **MEASURE-FIRST DONE — GREEN-LIGHT** (`measure-seam-attribution.md` §RESULTS). `perf sched:sched_switch`
  at 214k delivered / 74.2% CPU / cs/op≈1.69: the NTR/coordinate pool (`SharedPool-Work`) touches **43.5%
  of all context switches (0.735/op)**. CQL routing bypasses it (coordinate folds onto the shard's
  already-scheduled apply run). Est. net saving ~0.35–0.7 cs/op (~20–40%) — well above the bar. Bounded
  risk (Phase-3): shard CPU rises as coordinate folds on. **The loop→NTR hop is where the cost lives.**
  Method correction learned: coordinate on `SharedPool` cross-wakes shards on other cores, so the pool's
  cost shows as its OWN core scheduling (43.5%), not as `SharedPool→Shard` switch pairs. Also: the
  `hcloud`-unauthed false-blocker → lesson in `tasks/lessons.md` + [[feedback_check_runbook_before_blocker]].
- **Gate PASSED (user, 2026-07-13): "Build Phase 2 now."**
- **PHASE 2 BUILT + VERIFIED (2026-07-13).** 6 changes on `shard-dispatch-overhead`:
  1. `CassandraRelevantProperties.CQL_INGRESS_ROUTING` flag (default off; alphabetical-order gotcha hit +
     fixed — enum constants must sort by NAME).
  2. `transport/CqlShardRouter.java` (new) — loop-side route: ingress-throw-safe (`catch(Throwable)`),
     policy-neutral prepared lookup, single-column-PK extraction, coordinate-hazard predicate, shardForKey.
  3. `Dispatcher.dispatch()` — route-or-NTR branch guarded by `CqlShardRouter.ENABLED` (flag-off identical).
  4. `StorageProxy.performLocally` — owner-inline apply bypass (collapses hop B; mirrors MutationVerbHandler:99).
  5. `QueryProcessor.getPreparedNoTouch` (asMap — no eviction write on the loop).
  6. `MutationShardRouting.shardForKey(metadata, key)` (loop-side shard compute).
  **Key design realization:** `performLocally` already re-decides the apply shard authoritatively via
  `MutationShardRouting.route(mutation)`, so the loop predicate only needs to catch COORDINATE-time
  hazards (LWT/counter/triggers/denylist/transient); apply hazards fall to performLocally.
  **Verified:** `ant build` SUCCESSFUL; `MutationShardRoutingTest` 9/9 (2 new shardForKey tests);
  `ShardRoutedMutationApplyTest` 1/1 (regression-clean). NOT committed (awaiting user).
- **NEXT — Phase 3 (rig validation):** deploy flag-on jar, drive prepared writes on the real native path
  (in-JVM can't — i5-findings:40), confirm routeShard fires (CqlShardRouting.Routed counter > 0) + writes
  correct + owner-inline collapses hop B (re-run the seam attribution → SharedPool switches should drop) +
  flag-off regression-clean + shard-CPU headroom (the bounded risk). Skeleton gaps to harden: composite-PK
  routing, §6 `hasQueueCapacity` shard-inbox term.

## Start-of-context handoff (2026-07-13) — design not started

This increment was spun up when `shard-dispatch-overhead` concluded that single-node CPU steps 1-3
hit diminishing returns and the structural parity lever is **ingress routing**. User picked the target:
**design the CQL-native-path single-node inbox hop, Fable-led, before any code.** Do NOT redo I5
(internode inbound dispatch — already built + Fable-reviewed; inert single-node).

### State inherited (verify before trusting — memory recalls reflect write-time)

- **Branch:** `shard-dispatch-overhead` (off `tpc-nonblocking-write`, not pushed). Carries: the flip
  (async coordinator write path), I1 (local-apply shard routing: `ShardExecutors`,
  `currentThreadIsOwnerOf`), I5 (internode inbound dispatch: `net/ShardInboundRouter.java`), and Step 1
  (per-write alloc/threadlocal reductions: `SchemaConstants` allocation-free check + `CURRENT_SHARD`
  FastThreadLocal). `git log --oneline` for the exact commits.
- **Rig `157.180.98.112`:** UP running the flip+step1 jar (sha `19e44ac9`, routing ON), autocompaction
  disabled on `cassandra_easy_stress.keyvalue` (restart resets it). Loadgen deleted (provision fresh,
  hourly). Deploy recipe + capture scripts: `shard-dispatch-overhead/task_plan.md` "Verify" +
  `/root/{prep_flip,rig_capture,verify_flip}.sh`. Single-node routed-write baseline to beat:
  **flip+step1 ≈ 67.4% CPU @ 180k, off 58.5%, cs/op ~2.1.**
- **I5 status:** built (Phase A/B1/B2/B3), Fable-reviewed; open work = multi-node RF=3 3-arm perf gate
  (separate, in `i5-inbound-shard-dispatch/`). Not this increment.

### The one thing to get right FIRST (the crux — findings.md)

**Does server-side CQL ingress routing delete a hop single-node, or only relocate work unless the
client is shard-aware?** Without a shard-aware client the request arrives on an NT thread ≠ the owning
shard, so routing NT→owner *replaces* the apply hop with a request hop (same count) — it relocates
coordinate onto the shard + makes the apply inline (affinity + less alloc), but risks bottlenecking the
shard with coordinator work. The true hop-deletion needs the **shard-aware client protocol** (per-shard
ports / `SCYLLA_NR_SHARDS`, or a server-advertised shard map the driver honours). **The design's central
job is to decide whether server-side routing is worth building without that protocol, or whether the
protocol is the actual prerequisite.** Everything else (route point, key-decode, allowlist, fallback)
is mechanism that only matters after this is settled.

### Reading order for the fresh context

`findings.md` (this dir — framing, the crux, hazards, prior-art pointers) → `task_plan.md` (this dir —
phases; Phase 1 Fable design is the deliverable, Phase 2 build is GATED on it) → then the prior art in
findings.md order: `../../phase-3-execution-model/design-target.md` (inbox hop + shard-aware protocol +
§10 Scylla map — THE governing doc), `../i5-inbound-shard-dispatch/findings.md` (the internode sibling's
mechanism to reuse), `../shard-dispatch-overhead/findings.md` (why we're here + the rig baseline),
`../nonblocking-write-path/findings.md` (the flip = the async substrate).

### Pinned hazards (memories — do not rediscover)

`feedback_ingress_throw_kills_connection` (key-decode on the loop must not throw → null-return +
Stage fallback — the #1 build hazard), `feedback_rf3_assumption_poc_validity` (coordinate-on-shard must
not block on the RF≥3 QUORUM await — build on the flip's async await), `feedback_async_medium_netty_futures`,
`feedback_scylla_endgame_influence`, `feedback_fable_expensive_reserve_high_leverage` (Fable IS justified
here — hard-to-reverse hot-path design).

## Start prompt (paste into a fresh context)

> Design CQL-path ingress routing — single-node inbox hop. New increment
> `tasks/tpc-migration-planning/phase-4-poc/increments/cql-ingress-routing/`. Read its `progress.md` →
> `findings.md` → `task_plan.md`, then `../../phase-3-execution-model/design-target.md` (inbox hop +
> shard-aware client protocol + §10 Scylla map) and `../i5-inbound-shard-dispatch/findings.md` (the
> already-built internode sibling — reuse its mechanism, do NOT redo it). Context: single-node routed-write
> CPU steps 1-3 hit diminishing returns (flip+step1 ≈ 67.4% @ 180k vs off 58.5%); the structural parity
> lever is routing the CQL request to the owning shard at native ingress so coordinate+apply run on one
> thread. FIRST settle the crux with me (findings.md): does server-side routing delete a hop single-node,
> or only relocate work unless the client is shard-aware (per-shard ports)? THEN produce a design doc
> (route point on the native inbound loop; ingress-throw-safe key decode + Stage fallback; allowlist =
> single-partition local-replica writes only; coordinate-on-shard non-blocking at RF≥3 via the flip's
> async await; §10 Scylla map) and run a Fable adversarial critique of it. Build is Phase 2 and is GATED
> on a user decision after the design + crux verdict — do NOT write production code before that gate.
> Branch off `shard-dispatch-overhead`.
