# Progress — TPC CPU parity (Scylla-guided implementation fixes)

## HANDOFF (2026-07-14, late) — two alloc fast paths landed+committed; NEXT: contention counters + trunk-vs-latest side-by-side

Session did: attribution of the CPU-parity win, Phase 6 (c2c mechanism + honest ceiling), root-caused the residual
alloc gap and **shipped two async fast paths**, and killed the scaling-ladder line (confounded). Detail below in
the dated sections; this handoff is only what a fresh context can't re-derive.

**(1) Deviations from plan.**
- Went past the plan: after Phase 6 showed routing's tail is masked by its ~15%-higher allocation, root-caused it
  (async dispatch listener/callback nodes) and implemented+committed **two attach-on-done fast paths**
  (`addCallback(BiConsumer)`, `map`) — not in the original increment plan.
- **Scaling ladder ABANDONED (I/O-confound).** The write knee is I/O/commitlog-bound, NOT CPU-bound: trunk at 2
  pinned cores delivered 143,745/s ≈ the 6-core knee (~140k); at the knee cass box is only ~45% busy. So a
  core-scaling ladder on write throughput cannot show CPU/contention scaling **on any box** (a bigger box won't
  fix it). `scaling_ladder.sh` exists but is a dead end for this metric.
- **Bigger box dropped.** ccx63 (48 vCPU) hit the Hetzner dedicated-core limit; user confirmed 16 vCPU is the
  realistic customer ceiling, so >16 vCPU is out of scope.

**(2) As-built interfaces (verbatim — the next phase builds on these).**
- `ListenerList.drainAfterInline(AtomicReferenceFieldUpdater<? super T, ListenerList> updater, T in)` — new
  package-private static. Releases a `null->NOTIFYING` claim and drains re-entrant pushes; = `notify()`'s re-drain
  tail. Callers must already hold NOTIFYING.
- `AsyncFuture.addCallback(BiConsumer<? super V, Throwable> callback)` — new `@Override`, returns
  `AbstractFuture<V>`. Fast path guard (verbatim): `if (isDone() && notifyExecutor() == null &&
  listenersUpdater.compareAndSet(this, null, NOTIFYING))` → run `if (isSuccess()) callback.accept(getNow(), null);
  else callback.accept(null, cause());` in try, `catch (Throwable t) { ExecutionFailure.handle(t); }`, `finally {
  ListenerList.drainAfterInline(listenersUpdater, this); }`, `return this;` else `return super.addCallback(callback)`.
- `AsyncFuture.map(AbstractFuture<T> result, Function<? super V, ? extends T> mapper, @Nullable Executor executor)`
  — new `protected @Override`. Same guard plus `&& executor == null`; body `if (isSuccess())
  result.trySet(mapper.apply(getNow())); else result.tryFailure(cause());`, catch → `result.tryFailure(t);
  ExecutionFailure.handle(t);`, finally drainAfterInline, `return result` else `super.map(...)`.
- Pattern for any further fast paths (flatMap/andThenAsync/addCallback(FutureCallback)): SAME guard, invoke the
  callback DIRECTLY (no wrapper lambda — it re-allocates what you save), drainAfterInline in finally.

**(3) Tested / deferred / not-done.** Green: `AsyncPromiseTest` 9/9 (incl. re-entrant `addCallback` + `map`,
order `[0,2,1]` — the case that killed the reverted Tier-2), `ImmediateFutureTest` 3/3, `ant build`, `ant jar`.
Rig-confirmed under write load: `CallbackBiConsumerListener` alloc **654→0**, total alloc down, **0 write errors**.
Deferred (low ROI): `map`'s `result` alloc (unavoidable), `AsyncPromise` per-handler alloc (~620, architectural),
flatMap/andThenAsync/`addCallback(FutureCallback)` fast paths (same pattern, not hot). **NOT DONE (next phase):
contention counters + trunk-vs-latest side-by-side (there is NO current-vs-trunk comparison with the alloc-fix jar
— all existing comparisons are trunk vs routing-newfixes, pre-fix).**

**(4) Decisions + rationale.** Fast path lives in `AsyncFuture`, NOT `AbstractFuture`: `SyncFuture` shares
`listenersUpdater` but uses a DIFFERENT protocol (`synchronized` + `pushExclusive`, no NOTIFYING), so the
CAS-claim would be wrong for it. Safety = the fast path reduces to `notify()`'s exact NOTIFYING claim/drain; a
re-entrant add sees NOTIFYING, defers, drains in order. §0 baseline is fair (the `outcome()` machinery is
branch-added, trunk has zero) — recorded in the ATTRIBUTION section; RF=1-conditional caveat stands.

**(5) Gotchas.** (a) `pkill -f <pat>` over SSH SELF-MATCHES the ssh command line (it contains `<pat>`) → kills its
own session → rc 255. Use PID kill, or split the literal: `P=$(printf 'scal'; printf 'ing_ladder'); pkill -f "$P"`.
(b) Rig SSH intermittently drops longer commands (Helsinki link) — retry 2-3×. (c) `unzip` absent on rig — inspect
jar classes with `javap -cp <jar> <FQCN>`. (d) `ant build` ≠ jar; use `ant jar`, then javap-verify the class is
inside. (e) alloc/op & ins/op are co-location-invariant → co-located loadgen fine for those; TAIL needs off-box.

**(6) Assumptions to treat as given.**
- **Loadgen box UP — KEEP IT (user directive), bills hourly:** `tpc-scale-lg` = `62.238.35.142` (ccx43, 16 vCPU /
  8 phys AMD EPYC-Milan, hel1, RTT 0.46ms). easy-stress at `/usr/local/bin/cassandra-easy-stress` (jar md5
  ae2b804e). Rig's pubkey authorized on it; `rig -> loadgen` rsync works. loadgen→rig:9042 OPEN.
- Rig `157.180.98.112`: cassandra UP, currently on **routing-newfixes (1ff5de1b)** — swap to the fix jar for the
  next work. SMT restored to ON. Staged jars in `…/build/`: `.jar.trunk` 745ce392, `.jar.routing-newfixes`
  1ff5de1b, **`.jar.routing-alloc` 81e13444 = newfixes + BOTH fast paths** (`drainAfterInline` javap-verified in-jar).
- Rig scripts: `swap.sh` `prep_flip.sh` `capstone.sh`/`ipo.sh` (ins/op) `prof44.sh` `alloc_ab.sh` `c2c.sh`
  `sweep.sh` `profcache.sh`. Data dirs `/root/results_{ipo,prof_attr,c2c,sweep,profcache,alloc}/`.
- Write knee is I/O/commitlog-bound (~140k, cass ~45% busy) — do NOT use write-throughput-knee for CPU/contention.
- Code committed on `tpc-migration` (NOT pushed): `d0eb25ad2d` addCallback, `7db2bb3c09` map, `309c33b096` docs.

**(7) Entry point.** Read in order: THIS handoff → `phase6-methodology-critique.md` (why counters, not tail) →
`findings.md §3.1/§3.4` (async + memtable-CAS teardowns) → source `AsyncFuture.java` (the 2 fast paths),
`ListenerList.java:133` (`drainAfterInline`), and for the CAS target `AtomicBTreePartition`/`TrieMemtable$Memtable
Shard.apply`. Then act. Paste-able prompt:

> Continue TPC alloc/contention work. Read `tasks/tpc-migration-planning/phase-4-poc/increments/tpc-cpu-parity/
> progress.md` (top HANDOFF), then `phase6-methodology-critique.md` and `findings.md §3.1/§3.4`. Two alloc fast
> paths (addCallback + map) are landed+committed on `tpc-migration` and the `.jar.routing-alloc` (81e13444) is
> staged on rig `157.180.98.112`; loadgen box `62.238.35.142` (ccx43) is UP. TASK 1 — **domain contention
> counters**: count CAS-retries on the shared memtable apply (`AtomicBTreePartition`/`TrieMemtable$MemtableShard`)
> and NTR-pool wait, trunk vs routing-alloc at matched load — GC-immune, NOT confounded by the I/O-bound write
> knee (unlike the abandoned scaling ladder); goal "trunk N retries/1k writes, routing ~0". TASK 2 — produce the
> **current(routing-alloc) vs trunk side-by-side**: ins/op (capstone.sh), alloc (alloc_ab vs trunk), c2c HITM,
> rate-ladder p50/p90 — one consolidated table (none exists with the fix jar). Swap to `.jar.routing-alloc` first.
> Keep the loadgen box; delete it only when the user says done. Rig/box facts + gotchas in this handoff §5/§6.

---

## RESIDUAL-ALLOC FIX (2026-07-14) — attach-on-done fast path for addCallback(BiConsumer); Tier-3 landed safely

Phase 6 found routing's tail is masked by its **15%-higher allocation** (45,040 vs trunk 39,287 alloc samples).
Root-caused by diffing the attribution alloc profiles: the routing-added allocation is dominated by the **async
future/listener/callback machinery** — types ~absent on trunk: `ListenerList$$Lambda` 1,131 · `CallbackBi
ConsumerListener` 661 · `AsyncPromise` 659 · `ListenerList$RunnableWithExecutor` 539 (~52% of the ~5,753-sample
gap). Call paths: the fork's async CQL dispatch + shard-routing chain (`Dispatcher.processRequestAsync→
addCallback`, `ModificationStatement.executeAsync→map`, `ShardExecutors.shardTagged`) — each attach allocates a
fused node where trunk (more synchronous) allocates none. This is the deferred **Tier-3 "attach-on-done"** from
findings §3.1.

**Landed (this session): the fast path for `addCallback(BiConsumer)`** — the single biggest node
(`CallbackBiConsumerListener` 661, what the hot Dispatcher paths use). In `AsyncFuture.addCallback(BiConsumer)`:
when `isDone() && notifyExecutor()==null` and we can claim the empty slot (`CAS null→NOTIFYING`), run the
callback inline (mirroring `CallbackBiConsumerListener.run` + its `ExecutionFailure.handle` path) and skip the
node; `ListenerList.drainAfterInline` releases + drains re-entrant adds. **Safe because it reduces to `notify()`'s
exact NOTIFYING claim/drain protocol** — a re-entrant add sees NOTIFYING, defers, drains in order (firing nested
was the reverted Tier-2 bug). NO lambda wrapper (that would re-allocate what it saves — escape analysis won't
strip it once it crosses into the claim method), so the callback is invoked directly.

**Verified.** `ant build` clean. New focused tests (`AsyncPromiseTest`): already-done success/failure fire inline
once with correct `(value,null)`/`(null,cause)`; not-done falls through to node path; **re-entrant callback fires
once, deferred, order [0,2,1]** (the Tier-2-killer case). `AsyncPromiseTest` 7/7 (4 original run the full
`AbstractTestAsyncPromise` recursive/already-done harness = no regression), `ImmediateFutureTest` 3/3 (always-done
→ exercises the fast path). Files: `AsyncFuture.java`, `ListenerList.java`, `AsyncPromiseTest.java`. NOT committed.

**Alloc drop CONFIRMED on rig** (asprof alloc A/B, same session, co-located ~90k/s — alloc/op is co-location-
invariant; jar `.jar.routing-alloc` md5 81e13444, `drainAfterInline` javap-verified in-jar): `CallbackBiConsumer
Listener` **654 → 0** (raw: 16 stacks → 0 — the fast path fires on the hot dispatch path; the attaches are
inline-completion, not executor-bound). Total alloc 44,326 → 43,718 (**−608 same-session**, and total went DOWN
not up ⇒ the no-lambda design added nothing). Integration smoke: 4.88M writes, **0 errors**. So the fast path
eliminates its whole allocation type and closes ~654/5,753 ≈ 11% of the routing-vs-trunk alloc gap.
**Deferred (next alloc targets):** `map`'s listener-lambda (`RunnableWithExecutor` ~480 + Lambda; `result` alloc
unavoidable, only the listener node is saveable) and the `AsyncPromise` per-handler alloc (620, largely
architectural). perf/tail benefit of the reduced alloc needs a bigger box to show (this rig's tail is GC-pause-
frequency bound).

---

## PHASE 6 RESULT (2026-07-14) — mechanism WINS (c2c −31% HITM); net latency does NOT on this rig; tail-at-scale deferred to a bigger box

Trunk vs routing-newfixes, off-box loadgen (`62.238.35.142`, hel1, RTT 0.46ms). Fable-critiqued methodology
(`phase6-methodology-critique.md`): lead on the GC-independent mechanism + honest-ceiling, not on p99.

**(A) Mechanism — perf c2c: routing WINS, −31% cross-core contention (the clean, GC-independent result).**
Matched load (total mem records within 0.3%: trunk 1,105,309 · routing 1,108,858), 20s, ldlat=30, single-socket
(all HITM = Local):
| | trunk | routing-newfixes | Δ |
|---|---|---|---|
| Load Local HITM (cross-core hit-modified) | 8,596 | 5,901 | **−31.4%** |
| shared cache lines | 4,162 | 2,842 | −31.7% |
| HITM rate (per record) | 0.778% | 0.532% | −31.6% |
Per-shard routing bounces ~⅓ fewer modified cache lines across cores at matched write load — the shared-
everything→per-shard mechanism, real and measurable even on 6 cores. (Symbols are JIT-unresolved, so this is a
count/rate result, not per-line named; `perf c2c` is the right instrument because it measures cross-core
coherence traffic, which plain cache-miss VOLUME does not — confirmed below.)

**(B) Latency/throughput — rate ladder (fixed `--concurrency 3000`, 90s/rung): NO tail win; marginally worse.**
Delivered is IDENTICAL per rung (both ramp-limited to ~0.70× offered — an I/O/stall-bound knee, not CPU: at the
knee loadgen 21% + cass box 45% busy, neither saturated). p50 at parity; p90 + the 1–20ms "contention band" mass
are marginally HIGHER on routing:
| rate | deliv | p50 T/R | p90 T/R | 1–20ms% T/R (HDR band) |
|---|---|---|---|---|
| 80k | 56.6k | 0.69/0.72 | 0.88/0.94 | 1.25 / 3.75 |
| 120k | 84.8k | 0.76/0.79 | 0.97/1.07 | 5.63 / 15.00 |
| 160k | 113k | 0.82/0.83 | 1.11/1.28 | 18.1 / 25.0 |
| 200k | 140k | 0.85/0.84 | 1.36/1.45 | 28.75 / 28.75 |
This is the OPPOSITE of the thesis prediction and is consistent with routing's residual **15%-higher allocation**
(attribution alloc totals: trunk 39,287 vs routing 45,040) feeding more young-GC pause events. p99/p999 are pure
GC noise (single windows, no clean signal). **Caveat:** single ascending pass — directional, not conclusive;
needs interleaved N≥3 to confirm (Fable §5).

**(C) Why the mechanism win doesn't pay off here (cache-miss profile, asprof `-e cache-misses`, symbolic).**
GC dominates and is EQUAL across arms (G1 total cache-miss: trunk 160,782 vs routing 162,970; `G1ParScan
ThreadState::trim_queue` alone ~108k = the single top source, ~19% of all misses on both). The −31% HITM is a
tens-of-ns cache-coherence saving; on 6 cores / single L3 it is tiny in absolute terms and swamped by 0.46ms RTT
+ GC pauses. Memtable work is comparable volume (trunk shared `InMemoryTrie.attachChild` 41k; routing
`InMemoryTrie.applyContent` + per-shard `TrieMemtable$MemtableShard.apply`) — sharding changes cross-core SHARING
(HITM), not total miss volume, which is why c2c catches the win and cache-miss volume doesn't.

**(D) Honest ceiling + baseline (the defensible claim).** This rig proves the **mechanism (−31% HITM) + no
material regression**, NOT tail-at-scale. The tail win needs (1) ≥32 cores / multi-NUMA to grow the contention
delta past the RTT/GC floor, and (2) closing routing's residual allocation gap so GC stops masking it.
Baseline is FAIR (§0): the parity fix removes 106 lines of branch-added `outcome()` machinery trunk never had
(source-verified) — RF=1-conditional (fast path can't fire when RF=3 awaits remote acks) and with a portable-
`containsIgnoreCase` caveat (fair "trunk+that" baseline ⇒ routing ~+3%, not +1.4%). Next instrument = core-count
**scaling slope** (`/root/scaling_ladder.sh`, SMT-off, pinning-verified) on a bigger box. Full critique +
resolutions: `phase6-methodology-critique.md`. Rig data: `/root/results_{c2c,sweep,profcache}/`.

---

## ATTRIBUTION RESULT (2026-07-14) — the win is the outcome() fast path; the route fix is real but tiny; a prior §2 frame was mis-booked

**Method.** asprof 4.4 CPU+alloc collapsed, 3 arms (trunk / routing-fixed / routing-newfixes) in ONE session,
matched co-located ~94k/s, cpu 60s + alloc 40s each. RAW inclusive sample deltas (not self-normalized %).
Scripts `/root/{prof_attr.sh,analyze_attr.sh}`; data `/root/results_prof_attr/{cpu,alloc}_<arm>.collapsed`.
CPU sample totals: trunk 25,262 · routing-fixed 24,945 · routing-newfixes 23,326 (totals are for frame
attribution only — the ins/op capstone is the authoritative per-op gap; this session's fix_delta = 1a+Tier1).

**(1) outcome() terminal-write fast path (1b Tier 1) = essentially the ENTIRE win.** Confirmed on three signals:
- CPU: `AbstractWriteResponseHandler.outcome` inclusive **1061 → 90** (−971, −92%). Under it: `AsyncPromise`
  **249 → 0**, timer `schedule` **821 → 0**. The per-write cross-thread timer schedule was the single biggest
  frame the fix removed (bigger than the promise alloc itself).
- Wakeup relief: `EpollEventLoop.wakeup` **869 → 758** (−111), landing between routing-fixed (+188 over trunk)
  and trunk (681) — the per-write timer schedule+cancel wakeup, dropped as the handoff predicted.
- Alloc: outcome()'s five-object chain (`ScheduledFutureTask` 466 + `AsyncPromise` 169 + two lambdas 355 +
  `GenericFutureListenerList` 146 = 1136) collapses to **one `ImmediateFuture` (155)**. `ScheduledFutureTask`
  TOTAL alloc **474 → 6** (= trunk's 7) — the per-write deadline timer is fully eliminated, back to trunk.

**(2) route schema-handle (1a) = landed, but only ~24 samples.** `MutationShardRouting.route → isLocalSystem
Keyspace` is a caller of that method at **24 samples** in routing-fixed and **ABSENT** in routing-newfixes —
the fix did exactly what it was designed to. But 24 samples (~1‰) is a rounding error next to outcome(). The
coarse `route&containsIgnoreCase` grep (220→192, ~unchanged) is a TRANSITIVE artifact: `route → Schema.get
KeyspaceInstance → isLocalSystemKeyspace → containsIgnoreCase` (schema resolution inside route), which 1a
never touched.

**(3) CORRECTION — the §2 "~1.2pp containsIgnoreCase routing tax" was mis-attributed; do NOT chase it.**
`containsIgnoreCase` shows 719 (fork) vs **0 (trunk)**, which §2 booked as routing-added. It is not. It is a
NAMING artifact of a fork refactor (`SchemaConstants.java`, this branch vs trunk) that is **cheaper than trunk**:
trunk does `contains(toLowerCaseLocalized(name))` — an unconditional per-call lowercase alloc — measured at
`toLowerCaseLocalized` **466 (trunk) → 0 (routing-fixed) / 16 (newfixes)**. The fork tries the direct set
`contains(name)` first and only lowercases on a genuine uppercase char, so trunk's 466 lowercasing samples
vanish; the `containsIgnoreCase` label just re-homes the `RegularImmutableSet.contains` work trunk also pays.
Net: trunk's `isLocalSystemKeyspace` inclusive (466) ≥ newfixes' (384) — the fork's schema check WINS. The
membership check was never a routing gap contributor; both routing arms already carry the optimization (prior
session). Removing it from the routing backlog. (This is the same self-normalized-% trap §2 warned about, hit
one level down at frame naming.)

**(4) validateSafe:318 = GAP-NEUTRAL (classified, as requested).** `validateSafeToExecuteNonTransactionally &
containsIgnoreCase`: **89 (routing-fixed) vs 86 (routing-newfixes)** = equal within noise; held all along,
untouched by the fixes. Leave it. (It's the same cheap fork schema check as #3, not a further win.)

**Bottom line.** This session's 4,758 ins/op improvement is the outcome() terminal-write fast path, full stop
(promise + deadline-timer + listener machinery per local write → one `ImmediateFuture`), corroborated on CPU,
alloc, and wakeup. The route fix is a genuine but negligible confirmation. Attribution DONE → pivot to Phase 6.

---

## HANDOFF (2026-07-14, end of day) — CPU parity REACHED; attribute the win, then pivot to Phase 6

**Result (settled, do not re-run to re-confirm):** the two committed fixes bring the routing build to
statistical CPU parity with trunk in instructions/op. Same-session capstone (3 arms × 6×40s, ~94k/s
co-located, RF=1): trunk 102,225 · **routing-newfixes 103,698 (+1.4%, t≈1.2 NS)** · routing-fixed 108,456
(+6.1%, t≈4.9). Fixes cut **4,758 ins/op (p<0.005) = 76% of the gap.** Full table + caveats: the RIG RESULT
section at the bottom of this file. So "close the CPU gap" is **done** at this operating point; the remaining
CPU backlog is re-prioritized (Phase 3 memtable DROPPED — not worth HIGH risk for a closed gap; Phase 2
findIndex reframed as an independent *upstream* win, not a parity gate; Tier 2/3 async stay deferred).

**Next phase (agreed): (1) attribute, then (2) pivot to the TPC win case.**
- **(1) asprof attribution** (rig warm, ~15 min): CPU-profile routing-newfixes vs routing-fixed under matched
  load (`/root/prof44.sh`, asprof 4.4), diff **raw sample deltas** (NOT self-normalized %). Confirm the 4,758
  ins/op maps to the two fixes: the `MutationShardRouting.route → isLocalSystemKeyspace/containsIgnoreCase`
  frame → ~0, and `AbstractWriteResponseHandler.outcome`'s `AsyncPromise` alloc + `…schedule`/`cancel` timer
  frames → ~0. Also classify `ConsensusMigrationMutationHelper.validateSafeToExecuteNonTransactionally:318`
  (held all along): if its `containsIgnoreCase` frame is equal on both arms it's gap-neutral (leave it), else
  it's a further route-side win.
- **(2) Phase 6 — prove the win** (`task_plan.md §Phase 6`): CPU parity was only the floor. Show TPC's actual
  promise (Enberg: tail-at-scale/contention): **concurrency sweep** (off-box loadgen, client `--hdr` p99 per
  rung, trunk vs routing) to find the rung where trunk's tail blows up but routing stays flat; **`perf c2c`**
  trunk vs routing to visualize cross-core cache-line bouncing (trunk's shared NTR pool / shared memtable vs
  per-shard). NOTE: tail needs an **off-box** loadgen (co-located contaminates the tail) — provision ccx43
  hel1, **delete it when done (bills hourly)**. ins/op did NOT need one; the tail does.

**(1) Deviations from the plan.**
- 1a did the **route-side only** (a flag on `Keyspace`, not `TableMetadata` — `route` already holds the
  `Keyspace`). `validateSafe` was NOT touched (likely gap-neutral; deferred to the profile above).
- 1b: the planned "single-listener bare field" was **impossible as written** (the `listeners` field is
  `ListenerList`-typed; the updater rejects a bare listener at runtime). Redesigned as a 3-tier attach-on-done
  family. **Only Tier 1 landed.** Tier 2 (`AsyncFuture.appendListener` inline ready-path) was implemented,
  **double-fired re-entrant listeners** (`AsyncPromiseTest`), and was **reverted**. Tier 3 shares that hinge →
  not attempted. See findings §3.1 + task_plan §1b for the safe-but-marginal Tier-2 form.
- Phase 0 ruler: chose **instructions/op via `perf stat -p <cass_pid>`** and realized it's frequency- AND
  co-location-invariant → **co-located loadgen, no 2-node/interleaved rig, no turbo change** (simpler than the
  planned interleaved A/B).

**(2) As-built interfaces (verbatim).**
- `Keyspace.java`: `private final boolean localSystem;` set in BOTH terminal ctors as
  `this.localSystem = SchemaConstants.isLocalSystemKeyspace(metadata.name);`. New public getter
  `public boolean isLocalSystemKeyspace() { return localSystem; }`.
- `MutationShardRouting.route`: the per-mutation check is now `if (keyspace.isLocalSystemKeyspace())` (was
  `SchemaConstants.isLocalSystemKeyspace(keyspaceName)`); the `SchemaConstants` import was removed.
- `AbstractWriteResponseHandler.outcome()`: prepended
  `if (writeResult.isDone()) { try { computeVerdict(); return ImmediateFuture.success(null); } catch (Throwable t) { return ImmediateFuture.failure(t); } }`
  before the existing promise+timer path (new import `…concurrent.ImmediateFuture`).

**(3) Tested / deferred / broken.** Green: `MutationShardRoutingTest` 9/9, `WriteResponseHandlerTest` 9/9,
`AsyncPromiseTest` 4/4 (post-revert), `ImmediateFutureTest` 3/3, in-JVM `ShardRoutedReplicaApplyTest` 1/1,
`ant build`. Deferred: `validateSafe` classify · Tier 2 (safe form marginal) · Tier 3 · Phase 2 findIndex
(upstream) · Phase 3 (dropped). Broken: none.

**(4) Rig gotchas (cost real time).**
- `ant jar` OVERWRITES the live classpath jar → corrupts the *running* JVM's lazy class-loading
  (`NoClassDefFoundError` server-side; `nodetool tablestats` parse goes empty). Harmless because each arm
  restarts via `prep_flip.sh` — but never measure the live node right after a build without restarting.
- Loadgen **floats (no taskset)** to deliver ~94k co-located; `taskset`-ing it to 4 HTs makes the *loadgen*
  CPU-bound (~14k). `--rate` alone (never with `--maxlat`), `--prometheusport 0`, `--readrate 0.0` for writes.
  `mkdir -p /root/results_ipo` before redirecting into it.
- **Cross-run absolute ins/op drifts ~3%** (GC/warmup on fresh restart) → only **same-session** arm deltas are
  trustworthy; always put compared arms in ONE run (why the capstone did all 3 together).
- `swap.sh` prints a harmless `cut: '"'` error; the `cp` works. macOS has no `timeout`. Rig repo was at old
  `c5c2994` → `rsync -az --delete src/` mac→rig before `ant jar` (already done for the current jars).

**(5) Assumptions to treat as given.** Rig `157.180.98.112` (`cassandra-waf-rig`), governor=performance, perf
6.8.12, asprof 4.4 at `/opt/async-profiler-4.4-linux-x64/bin/asprof`. Repo `/root/repos/fork/cassandra-tpc-i1`.
Staged jars in `…/build/`: `.jar.trunk` (md5 745ce392, **verified TPC-free** via `jar tf`), `.jar.routing-fixed`
(6a346b24, prior 2 fixes), `.jar.routing-newfixes` (**1ff5de1b = +1a+Tier1**, `javap`-verified). **Rig is
currently live on routing-newfixes.** New scripts: `/root/{ipo.sh, capstone.sh}`; data `/root/results_ipo/`
(`cap_summary.txt` = the capstone). Existing: `/root/{swap.sh, prep_flip.sh, abwin.sh, prof44.sh, winrun.sh}`.
Code fixes committed+... on branch `tpc-migration` (NOT pushed to origin). **No loadgen box is provisioned**
(co-located); Phase 6 tail work must provision one and delete it.

**(6) Entry point.** Read in order: THIS handoff → the **RIG RESULT** section (bottom of this file) → `findings.md`
§3.1–3.2 (what the fixes target) → `task_plan.md` §Phase 6 (the pivot) → source: `Keyspace.java:196-210,275-306`,
`MutationShardRouting.java:98-104`, `AbstractWriteResponseHandler.java:174-204`. Then act. Paste-able prompt:

> Continue TPC CPU-parity → Phase 6. Read `tasks/tpc-migration-planning/phase-4-poc/increments/tpc-cpu-parity/
> progress.md` (top HANDOFF + the RIG RESULT section), then `findings.md` and `task_plan.md §Phase 6`. Context:
> two fixes (Keyspace system-keyspace cache + AbstractWriteResponseHandler.outcome() terminal-write fast path)
> reached CPU parity with trunk in instructions/op (routing +1.4% NS vs +6.1% before; 76% of the gap closed,
> capstone in `/root/results_ipo/cap_summary.txt`). FIRST run the asprof attribution: profile routing-newfixes
> vs routing-fixed on rig `157.180.98.112` (`/root/prof44.sh`, asprof 4.4, matched load), diff RAW sample
> deltas, confirm the `isLocalSystemKeyspace/containsIgnoreCase` route frame and the `outcome()` AsyncPromise+
> timer frames dropped to ~0, and classify `validateSafe:318` (gap-neutral or a further win). THEN pivot to
> Phase 6 (prove the TPC tail-at-scale win): concurrency sweep with an OFF-BOX loadgen (`--hdr` p99 per rung,
> trunk vs routing) + `perf c2c` trunk vs routing. Rig facts + gotchas in this handoff §4/§5. Jars staged
> (`.jar.{trunk,routing-fixed,routing-newfixes}`); rig live on routing-newfixes. Delete any loadgen box when done.

---

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

**Update (same session): 1b Tier 2 attempted & reverted.** The inline ready-path in `AsyncFuture.appendListener`
double-fired re-entrant listeners (`AsyncPromiseTest` → `order.size()`=count+2). The `NOTIFYING` sentinel is
load-bearing for re-entrant adds (a listener that adds a listener while firing must defer to the single
drainer), which "fire inline, never touch the field" violates. Reverted (tree clean, `AsyncPromiseTest` 4/4
again). Safe form claims the field (`CAS null→NOTIFYING` + re-drain tail) but saves little; gated on the rig
showing `notifyExclusive` still matters after Tier 1. **Landed this session: 1a + Tier 1 only.**

---

## RIG RESULT (2026-07-14) — the fixes close 76% of the per-op gap; routing ~= trunk

Built an **instructions-per-op ruler** (Phase 0): `perf stat -e instructions,… -p <cass_pid> -- sleep N`
÷ server-side write delta. Frequency- and co-location-invariant (perf -p isolates the process; instruction
COUNT per op is what the fixes change), so a co-located loadgen suffices — no second billing box, no turbo
change needed. Scripts on rig: `/root/{ipo.sh,capstone.sh}`; data `/root/results_ipo/`.

**Capstone A/B (3 arms × 6×40s windows, one session, all ~94k/s co-located, RF=1):**
| arm | ins/op (n=6) | sd | vs trunk |
|---|---|---|---|
| trunk (745ce392) | 102,225 | 2.2% | — |
| routing-newfixes (1ff5de1b = +1a+Tier1) | 103,698 | 2.2% | **+1.4% (t≈1.2, NOT significant)** |
| routing-fixed (6a346b24, prior) | 108,456 | 2.0% | +6.1% (t≈4.9, significant) |

**fix_delta = 4,758 ins/op (p<0.005) → closes 76% of the +6.1% gap.** Routing-newfixes is statistically at
parity with trunk. This 2.6–4.4% effect was invisible under the old %-busy metric (buried in ~3pp thermal
drift) — the ruler is what made it legible, validating the Phase-0-first ordering.

**Honest caveats:** one operating point (co-located ~94k/s, RF=1). Not yet confirmed near-knee/off-box (182k),
nor with tail (p99), nor RF=3. Cross-run absolute ins/op drifts ~3% (GC/warmup) — only same-session arm
deltas are trustworthy (why the capstone put all 3 in one run). Ins/op includes epoll-spin/GC background
(~2% window sd) equal across arms. Rig left on routing-newfixes; no loadgen box provisioned (co-located).
