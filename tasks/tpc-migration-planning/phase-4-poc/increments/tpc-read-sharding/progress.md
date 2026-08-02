# Progress — Read-path sharding

## 2026-07-15 — Phase-start: inherited-interface restatement (no code yet)

Fresh increment. Read `read-sharding-start.md`, `task_plan.md`, `roi-path.md`, and both prior results
(`tpc-alloc-gap/result.md`, `tpc-tier1-loaded-tail/result.md`). **Re-verified every code anchor with grep** —
all the Haiku-Explore anchors held up (±a few lines). Live rig jar = alloc-gap (`5348018527`); loadgen deleted.

### As-built interfaces confirmed
- `CqlShardRouter.routeShard` L112 gates reads OUT at `instanceof ModificationStatement` L131; `computePlan`
  L187; `fallback()` L213. Routes `ExecuteMessage` (prepared) only.
- `Dispatcher.dispatch` L122, route branch L148-159, NTR fallback L161.
- `ShardExecutors.execute(int,Runnable)` L117 / `execute(locals,int,Runnable)` L127; `currentThreadIsOwnerOf`
  L110; `SHARD_COUNT = getAvailableProcessors` L50 (12 shard executors on 6C/12T).
- `MutationShardRouting.shardForKey(TableMetadata, DecoratedKey)` L144 — reusable for reads; returns empty
  unless the current memtable is an `AbstractShardedMemtable` (Trie on the rig).
- Local read today: `AbstractReadExecutor.makeRequests` L138 → `Stage.READ.maybeExecuteImmediately(new
  LocalReadRunnable(...))` **L168** (the swap point).
- `StorageProxy.LocalReadRunnable` L3130 — builds its own `ReadExecutionController` INSIDE `runMayThrow`
  L3161, runs `executeLocally`, completes the `ReadCallback` handler L3184. (So a bespoke `ShardReadRunnable`
  may be unnecessary — reuse `LocalReadRunnable`, just change the submit target.)
- `StorageProxy.read` L2582 returns a `PartitionIterator` **synchronously**; `readWithConsensus` = the SERIAL
  branch (Paxos/Accord) that must never route. `SinglePartitionReadCommand.partitionKey()` L497.
- `SelectStatement.getPartitionKeyBindVariableIndexes()` L245 — the SAME method the write path uses; only
  needed for the ingress-routing variant (B), not the local-read variant (A).

### The one refinement vs the start prompt (design decision recorded in findings.md)
The start prompt frames the **CL gate** (route only `CL∈{ONE,LOCAL_ONE}` or `RF==1`) as universal to read
routing. Verifying the code shows the gate binds only to the **ingress-coordinate-routing** variant (which
blocks a single-writer shard thread on the whole synchronous read, including remote-replica RTT). The
**local-read-only** variant (swap `Stage.READ`→shard executor at `makeRequests:168`) is correct at every
CL/RF with no gate — the coordinator still contacts all replicas and does digest/read-repair; only the local
read's CPU+disk moves to the owning shard. Decision: **Variant A first** (see findings.md §Decision).

### Fable review (DONE) — one revert-class flaw + gate omissions, all verified at source
Fable confirmed Variant-A-now is the right call (routing point, CL/RF argument, B deferral all check out) but
**not as drafted**. Findings folded into findings.md §Variant A concrete shape + §Measurement-plan fixes:
- **Revert-class (mandatory gate):** route a local read ONLY when `currentShardId()==UNSET`. A routed write
  coordinate can trigger a synchronous auth read on the shard thread; submitting its local portion back to the
  same shard executor self-stalls that shard for 5s → dropped with no callback. Invisible to the bench + RF=3
  dtest (AllowAllAuthenticator). Guard verified: `ShardExecutors.currentShardId()`/`UNSET`.
- **Gate omissions:** exclude local-system keyspaces (`ShardBoundaries.NONE`→shard 0 for all keys, verified
  `MutationShardRouting.java:100-101`) and `indexQueryPlan()!=null` (verified `ReadCommand.java:341`); wrap
  submit in try/catch → `Stage.READ` for `RejectedExecutionException` during drain.
- **Correctness confirmed:** thread-swap is correct at all CL/RF (coordinator path untouched); `LocalReadRunnable`
  is thread-self-contained (`MessageParams`/monitoring/`ReadCallback` all thread-agnostic; locals propagate via
  `localAware()`); no self-deadlock (read OpOrder group closes before `response()`).
- **Measurement landmines:** (1) dynamic snitch eats local-read latency incl. shard-queue wait
  (`StorageProxy.java:3195`) → de-selects self → mechanism turns off mid-A/B; disable snitch or monitor
  local-read fraction. (2) sstable-only dataset designs OUT the one proven single-L3 mechanism (memtable trie
  node reuse) — add a memtable-resident read arm; `ShardExecutors` have no core pinning, capping the win.
- **Pre-agreed fallback:** if the A/B tail is read/write mutual-stall dominated, pivot to a separate per-core
  sharded read pool (same hash, not the write executors).

## 2026-07-15 — Phase 1 (implement) + Phase 2 (correctness) DONE, both green

### Implemented (4 files; compiles clean, `ant build`)
- `CassandraRelevantProperties`: new `CQL_READ_ROUTING` (`cassandra.tpc.cql_read_routing`, default false).
- `ShardExecutors.isShardThread()` — additive guard (`CURRENT_SHARD != UNSET`).
- `service/reads/ShardReads.java` (new) — the read analog of `CqlShardRouter`: `ENABLED` =
  `CQL_READ_ROUTING && MutationShardRouting.ROUTING_ENABLED`; `submitLocalRead(ReadCommand, Runnable)` applies
  the 5 gates (enabled · not-a-shard-thread · not-local-system · `indexQueryPlan()==null` · `shardForKey`
  present), submits the existing `LocalReadRunnable` to the owning shard executor, catches
  `RejectedExecutionException`→fallback; `ShardLocalReadRouted/Fallbacks` counters.
- `AbstractReadExecutor.makeRequests`: `if (!(ShardReads.ENABLED && ShardReads.submitLocalRead(command,
  localRead))) Stage.READ.maybeExecuteImmediately(localRead);` — flag-off is byte-identical to trunk.

### Scoping decision surfaced during Phase 2 (recorded, not a gap for the PoC)
`makeRequests` is COORDINATOR-side: my change routes only the read the coordinator runs *as a replica*.
Remote replica reads arrive via the internode read verb handler (untouched, stays on `Stage.READ`) — the read
analog of the write path's `MutationVerbHandler` replica routing, deliberately out of scope. The single-node
PoC bench and the big-box test are one Cassandra process (coordinator == replica for every key), so
coordinator-local routing covers 100% of the measurement scenarios. Multi-node replica-read routing is a
clean follow-up, not needed here. Correct at RF=3 regardless (coordinator still contacts all replicas).

### Phase 2 gate — GREEN
- `ShardRoutedLocalReadTest` (new, 3-node in-JVM, TrieMemtable): **PASS** (1 test, 0 failures). Correct results
  at **RF=1 and RF=3**, **CL ONE / QUORUM / ALL**; asserts routed counter grew ≥200 (path exercised, not
  silently falling back). Digest resolution runs on the coordinator over 200 QUORUM rows.
- `ShardRoutedReplicaApplyTest` (write path): **PASS** — unregressed by the read changes.
- `ShardExecutorsTest`: **5/5 PASS**.
- Teardown "Invariant failed" lines are a pre-existing shard-routing shutdown thread-count artifact — the
  write test emits the identical 12 lines and still passes; my change adds no threads.

### Not yet done
- [ ] Phase 3 CPU-bound read A/B — IN PROGRESS, PAUSED (state below).
- [x] Commit: `b979cfea78` (feature+test), `c608cce2d1` (docs) on `tpc-migration`.

## 2026-07-15 — Phase 3 setup DONE, A/B PAUSED mid-flight (loadgen deleted to stop billing)

### Rig state (persists — bare metal, not billed)
- Read-sharding jar built on rig, staged `build/apache-cassandra-7.0-SNAPSHOT.jar.read-sharding`
  (md5 a3b67fc826a86e58d25dc5540dc1225c), **live** and running. `javap` confirmed `ShardReads` +
  `CQL_READ_ROUTING` inside.
- Flags in `/data/tpc-poc/conf/jvm-server.options` L217-219: `mutation.shard_routing=true`,
  `cql_ingress_routing=true`, `cql_read_routing=true` (A/B toggles ONLY the last one).
- **Read routing PROVEN firing on the rig:** `ShardLocalReadRouted=100892` vs `ShardLocalReadFallbacks=3`
  after a 100k-read probe (>99.99% routed). Mechanism works end-to-end off the dtest.
- Dataset: 10M writes → ~8.57M partitions, **1.8GB, 4 sstables, flushed, autocompaction disabled**,
  page-cache resident (box has 62GB). `cassandra_easy_stress.keyvalue`. Reads deserialize sstables → the
  CPU-bound regime. Survives restarts (on disk), so the A/B toggles the flag + restarts without repopulating.
- Scripts on rig: `/root/knee.sh` (knee probe), `/root/swap.sh`, `/root/prep_flip.sh`. A/B harness saved in
  this folder as `read_ab.sh` (scp to /root on resume).

### Loadgen — DELETED (billing stopped). Re-provision recipe (verified this session)
1. `HCLOUD_TOKEN=$(cat ~/repos/agent-common/.secrets/hcloud.token)`; `hcloud server create --name tpc-loadgen
   --type ccx43 --image ubuntu-24.04 --location hel1 --ssh-key mac-ed25519 --user-data-from-file <cloud-init>`
   (cloud-init: openjdk-17-jre-headless, sysstat, htop → `/root/READY`). Reassigns IP **62.238.35.142**.
2. Seed stress: the fat jar is `cassandra-easy-stress-10-all.jar` (117MB) — relay rig→mac→loadgen (rig lacks
   the mac key), drop at loadgen `/root/ecs-all.jar`, wrapper `/usr/local/bin/cassandra-easy-stress` =
   `exec java -jar /root/ecs-all.jar "$@"`.
3. **Reused-IP host-key trap (cost this session):** clear the RIG's stale entry
   `ssh root@rig 'ssh-keygen -f /root/.ssh/known_hosts -R 62.238.35.142'`, add the rig pubkey
   (`~/.ssh/id_ed25519.pub`) to the loadgen `authorized_keys`, and use
   `-o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no` on every rig→loadgen ssh/rsync.

### THE blocker to resolve FIRST on resume (before trusting any A/B number)
Knee probe anomaly: the loadgen drove **~100k reads/s** (count → 9.59M, client p99 21ms, 0 errors) but the
rig-side `readcount`/`mpstat` read **idle** (`delivered=0`, `cass_busy 0.1%`). Contradiction ⇒ a rig-side
MEASUREMENT bug (nodetool grep returning stale/empty, or mpstat window not overlapping load), NOT a load
problem — the mac-driven diagnostic proved reads work + route at p99 ~1ms. **Debug the rig-side measurement,
confirm cass CPU ~90-100% at the knee (the whole point — a CPU-bound read regime), THEN run the A/B.** If the
box can't be pushed to ~100% with pure reads, that itself is a finding (reads I/O-bound here → io_uring).

### A/B plan (harness = read_ab.sh)
- Same jar, toggle `cql_read_routing` on/off per arm + restart (no truncate). `readrate 1.0`, conc 3000,
  threads 32, warm 30s, measure 90s, ~5 interleaved rounds. RATE ≈ 0.85× confirmed knee.
- Capture per arm: delivered r/s, routed_delta (≈delivered when on, ≈0 when off), cass CPU (mpstat),
  and the `*-reads.txt` HDR files — merge across rounds for the p50→p9999 band.
- Fable's dynamic-snitch measurement fix is **moot single-node** (one replica per key → nothing to de-select
  to). Memtable-resident arm (repopulate-per-arm, no flush) is the secondary run if the sstable arm is null.

## 2026-07-16 — Resume: blocker RESOLVED (measurement is sound), loadgen re-provisioned

**The knee-probe anomaly was a transient rig-side glitch, not a bug.** Disproven three ways:
- Live counters at rest: `Local read count` (tablestats) = 13,855,703 vs `ShardLocalReadRouted` = 13,855,704 —
  identical, so ~100% of local reads route and the tablestats counter DOES increment on every read.
- Co-located debug probe (`/root/read_debug.sh`, ~17k r/s): `readcount_delta=544457` tracked `routed_delta=575935`;
  deltas move together. Measurement plumbing works.
- Off-box loadgen probe (25s, conc 500): client read count **328100 == rig readcount delta 328100 exactly**.
  The off-box path reaches the rig and readcount tracks it 1:1. The prior "delivered=0 / cass_busy 0.1%" was
  `nodetool tablestats` returning empty in that one window (bash arithmetic on empty → 0), NOT reads failing.

**Reads are page-cache-resident CPU work** (client p99 <1ms; dataset 1.8GB in 9GB buff/cache), ~106µs cass CPU
per read (co-located: 1.8 cores @ 17k r/s). So the box IS read-CPU-bindable — needs ~100k r/s → off-box loadgen.

**Loadgen re-provisioned** (`tpc-loadgen`, ccx43, 16 vCPU, IP reused = 62.238.35.142): java17 + `ecs-all.jar` +
wrapper, rig pubkey baked into cloud-init authorized_keys (rig→loadgen direct rsync works). Robust knee sweep
staged at `/root/knee_sweep.sh` (off-box mpstat = pure cass CPU; adds pidstat cass-cores + raw RC0/RC1).
NB: launch the loadgen via a **backgrounded rig→loadgen ssh** (clean var interpolation) — NOT `setsid bash -c`
with nested quotes (that garbled `$RATE`→empty and reproduced the fake "delivered=0" anomaly).

### Knee sweep (conc 3000, thr 32, warm 25s, meas 30s; off-box loadgen, all read_routing ON jar)
| offered | delivered r/s | cass_busy | cass_cores /12HT | client p99 |
|---|---|---|---|---|
| 60k | 48.4k | 31.2% | 3.7 | 1.09ms |
| 100k | 81.2k | 48.5% | 5.7 | 3.89ms |
| 140k | 115.1k | 66.6% | 7.8 | 60.7ms |
| 180k | 145.2k | 80.6% | 9.4 | 481.8ms |

Latency knee ≈ offered 120–140k: p99 flat (1–4ms) until ~81k delivered ≈ 5.7 cores (the box has **6 physical**
cores / 12 HT), then explodes once demand exceeds physical cores. cass tops at 9.4/12 logical (loadgen-limited
to ~145k delivered) — but 9.4 logical > 6 physical ⇒ box is effectively CPU-saturated. Valid CPU-bound read
regime; "90-100% mpstat busy" is unreachable on an HT box (HT cores under-report), 80% here = full physical
saturation. delivered/offered ≈ 0.71–0.82, so offered 115k → delivered only 82k (comfortable, p99 ~2ms);
the loaded knee (delivered ~115k) needs offered ~140k.

### A/B round 1 — COMFORTABLE anchor (offered 115k, both arms delivered 82k, ~7.39M reads/arm)
**The client-stdout p99 is a windowed junk value (ON 4.46 vs OFF 1.34) — IGNORE it; trust the HDR file.**
CO-corrected read percentiles (ms) from the `-reads.txt` HdrHistogram:
| arm | p50 | p90 | p99 | p99.9 | p99.99 | cass_busy |
|---|---|---|---|---|---|---|
| OFF (Stage.READ) | 0.75 | 1.02 | 2.47 | 50.59 | 71.30 | 31.2% |
| ON (routed) | 0.82 | 1.16 | 2.26 | **26.61** | 64.23 | 47.5% |

Routing costs ~0.07–0.14ms at p50/p90 (the cross-thread handoff — OFF's `maybeExecuteImmediately` runs the
read inline on the coordinate thread; ON always hands off to a floating shard executor) but **halves p99.9
(50.6→26.6ms)**. NB: this single-round p99.9 "win" did NOT reproduce in the 3-round loaded run below — treat
as noise. CPU already higher for ON (47.5 vs 31.2%).

### A/B rounds 1–3 — LOADED knee (offered 140k, all 6 arms delivered 98.9k±0.02k) — VERDICT: routing LOSES
Delivered is **identical** (98.96k) across all 6 arms while cass CPU splits cleanly by flag — proof the ceiling
is closed-loop client concurrency/latency, NOT cass compute (the cheaper OFF arm would out-deliver ON if CPU-bound):
| arm | delivered | cass_busy | p50 | p90 | **p99** | p99.9 | p99.99 |
|---|---|---|---|---|---|---|---|
| OFF ×3 | 98.97k | **34.1–34.7%** | 0.78 | 1.06 | **3–5** | 54–57 | 72–81 |
| ON ×3 | 98.97k | **54.3–55.4%** | 0.87 | 1.28 | **26–27** | 60–64 | 80–91 |

Routing is worse at **every** percentile and burns **~57% more CPU** for identical throughput. Worst at **p99:
~6–8× (27 vs 4ms)**, rock-consistent across 3 rounds. Root cause: routing forces every read onto one of **12
single-threaded shard executors on 6 physical cores** (2× oversubscribed); load is uneven by partition hash and
a single-threaded executor can't shed to idle siblings → head-of-line queueing on hot shards. OFF's shared
`Stage.READ` pool load-balances AND runs the read inline (no handoff). On a shared-L3 box with unpinned floating
shard threads, the locality payoff is absent while the serialization cost is real → **load-balancing beats
locality for reads here.** This is hazard #1 (findings.md) realised as read-behind-read HOL, not read-vs-write.

### Concurrency sweep — REFRAMES the operating point (fixed offered 400k, thr 48, read_routing OFF jar)
| conc | delivered | cass_busy | cass_cores | loadgen_cores |
|---|---|---|---|---|
| 1000 | 267k | 66.1% | 7.7/12 | 4.3/16 |
| 3000 | 265k | 66.5% | 7.8/12 | 4.1/16 |
| 6000 | 266k | 66.5% | 7.8/12 | 4.2/16 |
| 10000 | 263k | 66.5% | 7.8/12 | 3.9/16 |
| 16000 | 268k | 66.1% | 7.7/12 | 3.8/16 |

**Box max ≈ 265k reads/s**, invariant across concurrency 1k→16k, loadgen idle (~4/16 cores). The bottleneck is
the **6 physical cores**: 7.8 logical cores busy = all 6 physical saturated + ~1.8 HT siblings; the remaining HT
siblings can't add throughput, so **mpstat pins at ~66% and never reaches 100%** under CPU-bound reads — "66% =
physically maxed" on this HT box. NOT the loadgen, NOT concurrency, NOT a software cap. Corrects the earlier
"90-100% busy" target: unattainable by construction on 6C/12T.

**This means the 140k-offered A/B above ran at only ~99k delivered ≈ 37% of the 265k ceiling — LIGHT load, not
loaded.** Routing already loses there; re-ran at saturation to give the paper's high-load claim its fair shot.

### A/B — SATURATION / overload (offered 400k = ~1.5× box max, thr 48, 2 rounds) — routing COLLAPSES
| arm | delivered r1/r2 | cass avg/max | client p99 (stdout) | client errors (timeouts) |
|---|---|---|---|---|
| ON (routed) | 226k / 227k | 76% / 96.7% | ~14s | ~2.6–2.7M |
| OFF (Stage.READ) | 260k / 261k | 65% / 99.8% | 818ms | ~52k |

HDR of *successful* reads (excludes the timeouts, so understates ON): OFF p50 37–314ms / p99 371–789ms; ON p50
**3.3–3.6s** / p99 **13–14s**. Both rounds reproduce to <1%. At overload the routing arm's max throughput is
capped ~15% lower (226k vs 260k — 12 shard executors can't absorb the burst), its median read is 3.5s, and it
sheds **51× more reads to timeout**. `Stage.READ` degrades gracefully; per-shard single-thread queues do not.
Both arms reach ~97–100% peak CPU here — so the box CAN be driven to 100%, but only by offering ~1.5× the
sustainable max; the 66% conc-sweep plateau was the sustainable knee.

### A/B — near-knee (offered 280k, thr 48, 2 rounds) — DECISIVE: baseline stable, routing collapses
| arm | delivered r1/r2 | cass avg/max | client p99 | client errors |
|---|---|---|---|---|
| OFF (Stage.READ) | 184.0k / 184.4k | 51.7% / 83–88% | 31–80ms | **0** |
| ON (routed) | 166.6k / 167.1k | 70% / 96–98% | ~2000ms | **~1.3M** |

At the **same** offered load where the baseline is completely stable (0 errors, ≤80ms p99, 52% CPU with
headroom), the routing arm is already in collapse (1.3M timeouts, 2s p99, pegged 98% CPU). Routing's sustainable
read-throughput ceiling is far below the baseline's — it can't hold a load `Stage.READ` shrugs off. Reproduced
to <0.5% across both rounds.

## VERDICT — read-path sharding (Variant A) is a NET REGRESSION on this box, across the whole load range
| load | OFF baseline | ON routed |
|---|---|---|
| light ~99k | p99 4ms, stable, 34% CPU | p99 27ms, +57% CPU |
| near-knee ~180k | p99 ≤80ms, **0 err**, 52% CPU | p99 2s, **1.3M err**, 70% CPU |
| overload 400k | p99 0.8s, 52k err, 260k deliv | p99 14s, 2.7M err, 226k deliv |

**Mechanism:** routing forces every read onto one of 12 single-threaded shard executors on 6 physical cores
(2× oversubscribed, unpinned/floating). Load is uneven by partition hash and a single-thread executor cannot
shed to idle siblings → head-of-line queueing that grows without bound under load. `Stage.READ` load-balances
across its pool AND runs the read inline on the coordinate thread (no handoff), so it both avoids the handoff
cost and never head-of-line-blocks. On a **shared-L3** box with **floating** shard threads the locality payoff
is absent (all cores share L3; no core affinity) while the serialization cost is real → load-balancing beats
locality for reads here. Code is correct and fires (dtest + counters); it is the *mechanism* that doesn't pay
on this hardware. The paper's read-tail win needs private per-core caches (multi-L3 / NUMA, ≥ enough cores that
1-executor-per-core isn't oversubscribed) — i.e. the deferred big-box test. Small box is now exhausted for
reads too, mirroring writes (memtable-only ≈ 5% CPU). Gate classification: hardware hurdle, not a code defect.

Raw data on rig: `/root/results_readab/{comfortable_115k,loaded_140k,nearknee_280k,sat_400k}/`, summaries
`read_ab_{loaded140k,sat400k,nearknee280k}.txt`; scripts `/root/{knee_sweep,conc_sweep,read_ab,pctl_reads}.sh`.
Loadgen `tpc-loadgen` DELETED (billing stopped); rig jar + dataset persist.

## 2026-07-16 — Perf-bug audit DONE, hunt plan written (`perf-bug-hunt-plan.md`)
User: "clearly perf bugs in the read path — plan to find them." Static audit of the routed submit path (my read
+ a subagent tracing the executor internals) found the shard executors are **plain JDK `ThreadPoolExecutor`
(core=1, unbounded `LinkedBlockingQueue`), NOT `SEPExecutor`** — so no inline path; every read pays a cross-core
park/unpark handoff + 3 allocs + a contended global `AtomicLong`. Ranked bugs (fix order): #5 `AtomicLong
submitted`→`LongAdder` (free/certain); #3 per-task `shardTagged` lambda → tag on worker start; #2 per-task
`ExecutionFailure.suppressing` wrapper → `TaskFactory.standard()` on read path; #1/#4 unbounded `LinkedBlockingQueue`
→ bounded queue so overload FALLS BACK instead of collapsing. NEXT: user go/no-go on Phase 1.

### Fable critique (2026-07-16) — OVERTURNED the audit ranking; plan revised to v2
- **Arithmetic:** +24µs/read at light load. Allocs+CAS = <0.5µs (~2%) → cleanup, not perf. Real cost = **TWO
  handoffs/read** (audit missed the 2nd: shard-worker `response()`→`signalAll` unparks the NTR thread blocked in
  `ReadCallback.awaitResults`; baseline runs inline BEFORE awaiting → parks 0×) + working-set migration + HT
  cycle-stretch + runqueue delay (~24 runnable threads / 6 cores → the 27ms p99). Delta SHRINKS under load
  (24→16µs) = park/unpark fingerprint. Collapse = capacity exhaustion (168k cap ≈ 167k delivered), not queue.
- **Fixes regraded:** #1 broken as written (default handler BLOCKS submitter, not throws; MPSC can't drop into a
  JDK TPE) → use exception-free `getPendingTaskCount()` pre-check. #2 WRONG (propagate() allocates nothing;
  would silently drop `ClientWarn`/trace for ~0ns) → reject. #3 correctness-risk (`CURRENT_SHARD` gates write
  lock-elision) → drop. #5 → delete field, don't LongAdder. **MISSED high-value fix: silent expiry drop**
  (`DroppableRunnable` drops expired queued read without `handler.onFailure` → coordinator waits full timeout
  holding NTR → the 1.3M-timeout storm) → one-line fast-fail. Correct set = (a) expiry fast-fail + (b) exception-
  free fallback; nothing else pre-measure.
- **Experiment:** NOT a flamegraph (blind to off-CPU park → would confirm the wrong story) → **`perf stat` A/B
  at 99k** (cycles/instructions/ctx-switches/migrations per-read) with a pre-registered decision tree.
- **Strategic:** Variant A is structurally weakest (mid-request thread-swap of a blocking read). **Big-box as
  designed measures nothing** — shard chosen from MEMTABLE boundaries but data is sstable-resident behind a
  GLOBAL chunk/page cache, threads unpinned → no affinity to exploit. Sound design = **ingress-route the WHOLE
  read** (1 handoff, state hot, no mid-request swap = the Accord end-state, needs async read path). See
  `perf-bug-hunt-plan.md` v2.

## 2026-07-16 — Collapse fix IMPLEMENTED + dtest-green (user: solve the blatant perf bugs)
Fixed the unbounded-queue collapse (the most blatant, safe, routing-scoped bug from Fable's list). The shard
executors are single-threaded with an **unbounded** `LinkedBlockingQueue`, so a backed-up shard grows the queue
into multi-second latency + expired-read drops; the existing `Stage.READ` fallback never fired because the queue
never rejects (and its default handler BLOCKS the submitter, so `withQueueLimit` alone would be worse). Fix =
**exception-free bounded submission**: `ShardReads.submitLocalRead` now sheds a read back to `Stage.READ` when
its owning shard already has ≥ `MAX_QUEUE` queued reads. New accessor `ShardExecutors.pendingTasks(shard)`
(cheap `getPendingTaskCount`=`LinkedBlockingQueue.size()`); new prop `cassandra.tpc.cql_read_routing_max_queue`
(default 128; 0 disables). At 128×~50µs ≈ 6.4ms max shard-queue wait ≪ 5s read timeout, so routed reads never
sit long enough to hit the silent expiry-drop → that timeout-storm path is mooted at the routing layer without
touching the general `DroppableRunnable`. 22 insertions / 3 files. `ant jar` clean; **`ShardRoutedLocalReadTest`
PASS** (Tests run 1, Failures 0, Errors 0, 9.78s — routing still fires + correct at RF=1/3×CL ONE/QUORUM/ALL;
dtest load stays under 128 so routing isn't shed). NOT yet perf-validated (needs loadgen re-provision) — the
+57% CPU (two-handoff, architectural) is untouched by design; this fix converts collapse → graceful fallback.
Deferred (Fable): AtomicLong is noise (skip); expiry fast-fail mooted by the cap; #2/#3 rejected.

## 2026-07-16 — INCREMENT CONCLUDED (user: bank result, pause)
Code shipped: `CQL_READ_ROUTING` flag + `ShardReads` + `ShardExecutors.isShardThread()` + call site + dtest
(commits `b979cfea78`, `c608cce2d1`), correct & flag-off byte-identical to trunk. Measurement: **net regression
across the full load range** (see `result.md`), diagnosis corrected by Fable (two-handoff cost, not allocs; +
a silent expiry-drop bug). No further Variant-A investment. Fixes (a) expiry fast-fail + (b) exception-free
fallback and the sound redesign (ingress-route whole read + affinity gap) are documented in
`perf-bug-hunt-plan.md` v2 for a future session. Rig LEFT: cass running baseline (`read_routing=false`), jar +
dataset + 4 result sets persist; loadgen deleted. Docs NOT committed (awaiting user).

---

# PHASE HANDOFF → Phase 3 (CPU-bound read A/B) — 2026-07-16

Code is done, committed, correct, and proven routing on the rig. Phase 3 (the perf A/B) is the only open work.

**(1) Deviations from plan.** (a) No new `ShardReadRunnable` class — reused `StorageProxy.LocalReadRunnable`
unchanged (it already builds its own controller + completes the callback), so routing is a submit-target
change. (b) No `CqlShardRouter` generalization — reads route at the executor (`makeRequests`), not ingress, so
`SelectStatement` pk-extraction was never needed. (c) Added a dedicated flag `CQL_READ_ROUTING` (plan implied
reusing existing flags) so the A/B isolates read routing from write routing on one binary. (d) The CL gate the
plan called "design-shaping" was NOT built — Variant A is correct at all CL/RF without it (coordinator still
contacts all replicas). All deviations = simpler + lower-risk; rationale in findings.md.

**(2) As-built interfaces (verbatim).**
- `org.apache.cassandra.service.reads.ShardReads`:
  - `public static final boolean ENABLED` = `CQL_READ_ROUTING.getBoolean() && MutationShardRouting.ROUTING_ENABLED`
  - `public static boolean submitLocalRead(ReadCommand command, Runnable localRead)` — true = routed (caller
    does nothing more), false = caller runs `Stage.READ.maybeExecuteImmediately(localRead)`. Never throws.
  - `public static long routedCount()` / `public static long fallbackCount()`
- Call site (`AbstractReadExecutor.makeRequests`, the only caller):
  `if (!(ShardReads.ENABLED && ShardReads.submitLocalRead(command, localRead))) Stage.READ.maybeExecuteImmediately(localRead);`
- `ShardExecutors.isShardThread()` → `public static boolean` (`CURRENT_SHARD.get() != UNSET`).
- Config key: `cassandra.tpc.cql_read_routing` (enum const `CQL_READ_ROUTING`, default `false`).
- JMX counters (group `type=Client`): `ShardLocalReadRouted`, `ShardLocalReadFallbacks`.
- Gates in `submitLocalRead`, in order: `ENABLED` · `ShardExecutors.instance()!=null` · `!isShardThread()` ·
  `instanceof SinglePartitionReadCommand` · `!isLocalSystemKeyspace` · `indexQueryPlan()==null` ·
  `MutationShardRouting.shardForKey(metadata, partitionKey)` present · then `shards.execute(shard, localRead)`
  with `RejectedExecutionException`→fallback.

**(3) Tested / deferred.** Tested: `ShardRoutedLocalReadTest` (RF=1/3 × CL ONE/QUORUM/ALL, routed counter
grows). Deferred by design: replica-side read routing (internode read verb handler stays on `Stage.READ` —
only coordinator-local reads route; irrelevant to single-process bench/big-box). Not broken, but UNPROVEN:
the perf win (Phase 3).

**(4) Decisions.** Variant A over B (sync read path ⇒ B blocks the shard thread on remote RTT). Same-jar
flag-toggle A/B (isolates read routing, removes jar-swap as a variable). Coordinator-local scope (covers all
single-process measurement targets).

**(5) Gotchas.** (a) `ENABLED` is read ONCE at startup → each A/B arm needs a restart to flip the flag. (b)
Dataset is sstable-resident (flushed) so it survives the restart — do NOT `prep_flip.sh` (it truncates); the
A/B restart must NOT truncate. (c) Reused-IP host-key trap: `StrictHostKeyChecking=no` is insufficient when a
CONFLICTING key exists — clear the RIG's `known_hosts` entry AND add rig pubkey to loadgen; use
`-o UserKnownHostsFile=/dev/null`. (d) `--concurrency` (not `--rate`) is the throughput knob; never pass
`--rate` with `--maxrlat`. (e) Teardown "Invariant failed" dtest lines are pre-existing shard-routing noise.

**(6) Assumptions given.** Read routing is correct (dtest-proven) and fires on the rig (counter-proven) —
Phase 3 is pure measurement, no correctness re-litigation. The box CAN be read-CPU-bound (runbook knees ~100%
@ ~115–123k read-heavy). Fable's snitch fix is moot single-node.

**(7) ENTRY POINT for a fresh agent.** Read in order:
1. this `progress.md` (esp. the "Phase 3 setup DONE, A/B PAUSED" section above + this handoff)
2. `findings.md` (design of record) and `task_plan.md` §Phase 3
3. `read_ab.sh` (staged A/B harness, this folder) and rig `/root/knee.sh`
4. source: `src/java/org/apache/cassandra/service/reads/ShardReads.java`

Ready-to-paste starting prompt:
> Resume tpc-read-sharding Phase 3 (CPU-bound read A/B). Read this increment's progress.md (Phase 3 sections +
> PHASE HANDOFF), findings.md, and read_ab.sh. Rig `157.180.98.112` still holds the live read-sharding jar
> (all flags on) + the flushed 10M-partition `cassandra_easy_stress.keyvalue` dataset; read routing is proven
> firing. FIRST ACTION: re-provision the loadgen (recipe in progress.md), then DEBUG the knee-probe anomaly
> (loadgen drove ~100k r/s but rig `mpstat`/`nodetool` read idle — fix the rig-side measurement) and CONFIRM
> cass CPU ~90–100% under pure reads before trusting any tail number. Then run read_ab.sh (read-on vs
> read-off, same jar, interleaved) at ~0.85× the confirmed knee and merge the *-reads.txt HDR files.
> DELETE the loadgen when done (bills hourly).
