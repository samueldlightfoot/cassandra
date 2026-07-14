# Progress — TPC CPU parity (Scylla-guided implementation fixes)

## HANDOFF (2026-07-14) — 1a landed; 1b bare-listener KILLED, redesigned (supersedes the 1b parts below)

**1a (apply-side schema handle) — DONE in the working tree (not committed, not yet dtested).** Cached
`boolean localSystem` on `Keyspace`, computed once at construction (`SchemaConstants.isLocalSystemKeyspace`),
exposed as `Keyspace.isLocalSystemKeyspace()`; `MutationShardRouting.route` now reads that field instead of a
per-mutation name-set scan. Removed the now-dead `SchemaConstants` import. `ant build` = BUILD SUCCESSFUL.
Semantically identical to the old call (system-ness is a function of the immutable name; a `Keyspace` is never
virtual). NOTE: the prior session had already given `SchemaConstants.containsIgnoreCase` a hot-path fast path
(direct `contains`, lowercase only on an uppercase char) — so 1a removes 2×(HashSet probe + full-string scan)
per mutation, not a full linear scan.

**1b (async future) — the inherited "single-listener bare field" fix is DEAD.** A Fable adjudication +
concurrency map (both source-verified) killed it:
- **Impossible as written, not just risky.** `AbstractFuture.listeners` is typed `ListenerList<V>` and its
  updater is built with `ListenerList.class` (`AbstractFuture.java:103,105`); `AtomicReferenceFieldUpdater`
  runtime-`valueCheck`s writes, so storing a bare `GenericFutureListener` throws `ClassCastException`. The
  ":103 comment already permits a bare listener" premise (task_plan §1b, findings §3.1) is **flatly false** —
  dead text. A real bare field means widening to `Object` + reconciling ~9 intrusive-stack sites — a
  core-primitive rewrite.
- **Wrong payoff, wrong mechanism.** The only hot-path `addListener(GenericFutureListener)` is
  `AbstractWriteResponseHandler:189`; the busiest attaches (`MutationVerbHandler:86`, `Dispatcher:593,700`,
  `ModificationStatement:746`) use `addCallback`/`map`, which allocate a **fused** node regardless. And the
  claimed "deletes notifyExclusive ~1.1pp" confuses an **alloc** fix with a **drain** fix — `trySet` still
  CASes and `notify` still drains; those frames don't vanish by removing a node.

**Corrected 1b = a three-tier "attach-on-done" family** (Seastar ready-future idiom, applied where this fork
actually completes synchronously — verified: local apply completes `writeResult` *before* `outcome()` attaches
on the inline path, so attaches land on an already-done future ~4–5× per write):
- **Tier 1 (trivial, caller-level, DO FIRST after 1a):** in `AbstractWriteResponseHandler.outcome()` (:174),
  if `writeResult.isDone()` compute the verdict inline and return `ImmediateFuture.success/failure` — skip the
  `AsyncPromise` + the `scheduler.schedule` timer + `timer.cancel` + listener + drain. `computeVerdict()` is
  documented pure (:224-227). Zero concurrency risk (not-done ⇒ today's slow path). Also kills a per-write
  cross-thread timer schedule+cancel — a likely feeder of the `EpollEventLoop.wakeup` samples currently
  misbooked as "genuine TPC steering tax."
- **Tier 2 (core, additive):** in `AsyncFuture.appendListener`, when `isDone(result) && listeners == null`,
  resolve the executor exactly as `notifyExclusive` does (`ListenerList.java:145-148`) and invoke
  `notifySelf` directly — never touch the field. Ordering-safe: a drain holds `NOTIFYING` in-field until done
  (`ListenerList.java:110-121`), so null+done ⇒ all earlier listeners already ran. Low-moderate risk; gate on
  the future/promise unit tests. Never-throw-on-ingress preserved by `notifyListener`'s catch (:157-168).
- **Tier 3 (optional, literal Seastar):** done-checks in `addCallback`/`map` (`AbstractFuture.java:273-355`)
  before node construction, scoped to `notifyExecutor()==null && executor==null`; `map` returns
  `ImmediateFuture` of the mapped value. This is where the node allocs actually die; auto-covers
  `ImmediateFuture` attach sites.
- **KILL:** the bare-listener field AND "recycled node" (pooling 24-byte TLAB objects is a wash).

**Numbers corrected.** The async bucket is **~0.9pp, not 1.2** — the "recycler" frame (~0.31pp, 26%) has no
provenance and is plausibly Netty transport buffer pooling, not our futures. Realistic 1b ceiling **~0.5–0.9pp**
+ part of the +16% alloc (G1/tail relief) + some wakeup samples. So **1a (~1.2pp, trivial) outranks 1b**, and
neither is measurable without the Phase 0 ruler (~3pp drift floor).

**Revised order:** Phase 0 (ruler) → 1a (done, commit + dtest) → 1b Tier 1 → 1b Tier 2/3 → re-measure →
Phase 3 memtable. findings §3.1 + task_plan §1b are corrected inline with a dated note.

**RF=3 note (kept honest):** attach-on-done is correct under RF=3 (it's a fast-path branch; remote-quorum
writes attach before completion and take the slow path). But the measured pp-win is weighted to the RF=1
inline-apply rig; under RF=3-with-remote-replicas the coordinator awaits remote acks, so the fast path fires
less. Don't over-claim the pp on the RF=1 number.

---

## HANDOFF (2026-07-13) — implement the CPU-gap fixes in a fresh context

**Premise (settled, do not re-litigate).** The CQL-ingress-routing build runs **+6pp CPU vs stock trunk** at
matched sub-knee (~182k). The user rejected the earlier "it's the architecture" read: a thread-per-core port
that is *slower* than shared-everything trunk is evidence of **our implementation immaturity**, not an
architecture verdict. A four-way ScyllaDB source teardown + a re-read of Enberg-2019 confirmed this: every
routing-added profile frame maps to something a mature TPC engine (Scylla/Seastar) does cheaply. **~2.5–3pp is
unambiguously ours and fixable; ~0.3–0.7pp is the one genuine TPC steering tax; the rest is a JVM dispatch gap
+ a commitlog delta to dig into.** Full analysis + source cites: `findings.md`. Phased fix plan: `task_plan.md`.

**(1) What's established.**
- The vs-trunk A/B (with the two prior CPU fixes) = `../cql-ingress-routing/perf-ab-methodology.md
  §RESULTS-VS-TRUNK-FIXED`: trunk ~55.5% (T1 54.6 / T2 56.4, +1.8pp thermal drift), routing-fixed 61.6%.
- The drift-proof decomposition (raw asprof-4.4 sample deltas, routing−trunk, matched 182k/30s; profiles at
  rig `/root/results_prof/{cpu,alloc}_{trunk,routing}.collapsed` + mac scratchpad): `findings.md §2`.
- The four Scylla teardowns (futures, schema resolution, metrics, memtable) with file:line: `findings.md §3`.
- **Verified at our source:** `AbstractFuture.addListener` (`src/java/org/apache/cassandra/utils/concurrent/
  AbstractFuture.java:419-421`) allocates a `GenericFutureListenerList` node for even the first listener; the
  `listeners` field (:103) already permits a bare listener — so the single-listener fix is a targeted change.
- **Correction baked in:** the metrics histogram (`findIndex`) is **NOT** in the routing gap (trunk 4.06% >
  routing 3.38% — a common cost on both arms). It is a separate ~4% *absolute* target. Don't credit it to routing.

**(2) The plan (task_plan.md), in order.**
- **Phase 0 (PREREQUISITE): fix the ruler.** Build an **instructions-per-op** harness (`perf stat` ÷ writes),
  pin CPU frequency (kills the 1.8pp thermal drift), interleaved/2-node A/B. Most fixes are ~1pp — invisible
  under the current ~3pp drift. Do this FIRST or you cannot measure any fix.
- **Phase 1: the clean "ours" wins.** (1a) apply-side schema handle — kill `containsIgnoreCase` ~1.2pp
  (thread the resolved `TableMetadata`/`Keyspace`, cache a boolean flag; this is the *apply*-side twin of the
  already-shipped `CqlShardRouter` decision-side memo). (1b) async future — single-listener bare field
  (mirror Netty `DefaultPromise`) + ready-future inline path (~1.2pp + the +16% alloc).
- **Phase 2: `findIndex` O(1)** (~4% on BOTH arms, gap-neutral, upstreamable to stock Cassandra).
- **Phase 3: memtable single-writer CAS removal** (~1.4pp) — behind a single-writer invariant proof (HIGH risk).
- **Phase 4: megamorphic dispatch** (~0.6pp, grey/JVM). **Phase 5: IRQ affinity** (config-only, the paper's
  lever, hits the tail). **Phase 6: `perf c2c` + concurrency sweep (+ optional Scylla-on-rig control)** — prove
  where TPC actually wins and whether CPU-parity-on-6-cores is even the right gate.

**(3) Rig state (verify before trusting — `157.180.98.112`).** Node UP routing-ON on `.jar.routing-fixed`
(md5 `6a346b24`, both flags, pools=ON, native active). Staged jars in `…/cassandra-tpc-i1/build/`:
`.jar.trunk` (50ddce8455, md5 745ce392), `.jar.routing-fixed` (054f14e484, 6a346b24), `.jar.routing-validated`
(bf5e4356, pre-fix routing — kept for revert). async-profiler 4.4 at `/opt/async-profiler-4.4-linux-x64/bin/
asprof`. Rig scripts: `/root/{build-routing-fixed,prof44,winrun,swap,abwin,prep_flip,rcnt}.sh`. Scylla source
for reference: `~/repos/scylladb` (Seastar submodule may need `git submodule update --init seastar`; note
macOS has no `timeout` — omit it).

**(4) Gotchas (cost real time — from the measurement session).** Build-trunk mirror needs the **full 40-char
SHA** (`git fetch origin <short-sha>` fails); push the branch to origin first. **`pkill -f "<pat>"` self-kills
the ssh** if the pattern is in your own remote command line ("ant" also matches `gradle-instrumentation-agent`).
`abwin` windows run **~77s wall** (sjk mxdump slow), so size loadgen runs generously and **profile in a
separate dedicated load run** (a too-short loadgen → idle profile). `prep_flip` **races its own `prep.done`
removal** — poll for the `"PREP DONE"` log line, not the marker file. Use the **raw sample deltas** for the
profile differential, never each profile's self-normalized % (both normalize to 100% and inflate 0-in-trunk
frames). Loadgen: off-box ccx43 hel1, `cassandra-easy-stress` KeyValue = the routable prepared-INSERT shape,
`--hdr` p99 the CO-corrected tail; **delete it when done** (bills hourly).

**(5) Branch.** Code fixes land on `tpc-migration` (the two prior CPU fixes are committed + pushed there;
`054f14e484` = memoize + async-fast-path). These findings docs are committed with this handoff.

**(6) Entry point for a fresh agent.** Read in order: THIS handoff → `findings.md` (§2 the decomposition, §3
the four Scylla teardowns = the fixes) → `task_plan.md` (Phase 0 first) → `../cql-ingress-routing/
perf-ab-methodology.md §RESULTS-VS-TRUNK-FIXED` (the numbers to beat). Then act. Starting prompt to paste:

> Implement the TPC CPU-parity fixes. Read `tasks/tpc-migration-planning/phase-4-poc/increments/
> tpc-cpu-parity/progress.md` (HANDOFF) → `findings.md` → `task_plan.md`. Context: the CQL-ingress-routing
> build is +6pp CPU vs trunk; a ScyllaDB teardown + Enberg-2019 re-read proved ~2.5–3pp is our implementation
> immaturity (not architecture), with concrete Scylla-referenced fixes. DO PHASE 0 FIRST — build an
> instructions-per-op harness (`perf stat` ÷ writes) + pin CPU frequency + interleaved/2-node A/B, because the
> fixes are ~1pp each and the current ~3pp drift hides them. THEN Phase 1: (1a) apply-side schema handle to
> kill the per-op `containsIgnoreCase` scan (thread the resolved TableMetadata through MutationShardRouting.
> route; the CqlShardRouter decision-side is already memoized, this is the apply-side twin), (1b) async future
> single-listener-bare-field (mirror Netty DefaultPromise; AbstractFuture:419 currently allocs a node per
> listener) + ready-future fast path. Measure each in instructions/op + asprof differential. Branch
> `tpc-migration`. Rig `157.180.98.112` (routing-fixed live; jars + asprof-4.4 staged); Scylla source
> `~/repos/scylladb`. Gotchas in the handoff §4. Delete any loadgen when done.

---

## PROGRESS LOG (2026-07-14) — 1a + 1b Tier 1 landed, verified, committed

Session outcome: the two lowest-risk local fixes are in; the rig ruler (Phase 0) is the gate to measuring them.

**Committed on `tpc-migration`:**
- `33c64e9167` — 1a route-side: `Keyspace.localSystem` cached flag; `MutationShardRouting.route` reads it.
- `a37e9e8f6d` — 1b Tier 1: `AbstractWriteResponseHandler.outcome()` fast path when `writeResult.isDone()`.
- `d2740c3d3e` — planning-doc correction (bare-listener killed; three-tier attach-on-done recorded).

**Verified locally (all fresh mtime, jdk17, 0 skips):** `MutationShardRoutingTest` 9/9 · `WriteResponseHandler
Test` 9/9 · `ShardRoutedReplicaApplyTest` (in-JVM, real routed write+read-back — exercises `route()` AND the
`outcome()` inline-done fast path) 1/1 · `ant build` BUILD SUCCESSFUL. **Not yet measured** (no CPU claim until
the Phase 0 ruler exists; ~3pp drift floor hides both).

**Next (unchanged order):** Phase 0 rig ruler (verify rig state first — `157.180.98.112`, bills hourly) →
measure 1a+Tier1 vs trunk in instructions/op → 1b Tier 2/3 → re-measure → Phase 3 memtable. Deferred: 1b
Tier 2 (`AsyncFuture.appendListener` ready-path) and `validateSafe` classification (needs the rig profile diff).
