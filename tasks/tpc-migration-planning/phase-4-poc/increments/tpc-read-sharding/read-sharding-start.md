# Read-path sharding — start prompt (fresh-context handoff, 2026-07-15)

**Read this, then `../tpc-cpu-parity/roi-path.md`, the two prior results (`../tpc-alloc-gap/result.md`,
`../tpc-tier1-loaded-tail/result.md`), and this folder's `task_plan.md`. Then scope against `CqlShardRouter`
before cutting code.**

## Why we are here (the pivot, fully grounded — not speculative)
The TPC mechanism is PROVEN (memtable contention 72/1k → 0, cross-core c2c HITM −27%). But two prior
increments closed the book on extracting a visible win from WRITES on the current box:
- **Tier 0 (alloc gap) DONE** — 3 async fixes landed (−1.3% alloc; gap +10.4%→+9.0%), committed. The
  `AsyncPromise→ImmediateFuture` swap is a WASH, and ~62% of the gap is non-future routing allocation these
  fixes can't touch. Alloc reduction alone will NOT unmask the tail. (`../tpc-alloc-gap/result.md`)
- **Tier 1 (loaded tail) DONE** — two clean findings: (1) **a write-only CPU-bound regime is UNREACHABLE on
  this box** — without the commitlog a write is a memtable put (CPU-trivial); the box idles at ~5–10% CPU even
  at 144k w/s (loadgen drove 275k). The "45% knee" WAS commitlog+flush+GC. (2) **Off-box, trunk vs the TPC
  build is parity** — identical throughput/CPU; the p99 is a GC/flush-stall lottery (swings 5–109 ms within a
  single arm), no signal. The Tier-0 co-located 50–100× p99 hint was a co-location+profiling ARTIFACT.
  (`../tpc-tier1-loaded-tail/result.md`)

**So writes are exhausted on this hardware.** Reads are the pivot for three grounded reasons:
1. **Writes can't saturate CPU here** → the paper's CPU-bound win regime is unreachable with writes. Reads ARE
   CPU-heavy (deserialize / merge / decompress / row iteration) → a read-heavy workload CAN saturate the cores
   on hardware we already own.
2. **Off-box the write-path fixes are tail-neutral** → no more write-path signal to chase on this box.
3. **Reads exercise the paper's LARGER win half** (read-tail 54%/20%, vs write's 20%), and match real
   (read-heavy) workloads — the community will ask for it regardless.

## The sequence (do NOT reorder — each unlocks the next; big box + concurrent-GC stay last)
1. **Read-path sharding** (THIS increment) — route single-partition reads to their owning shard. The enabler:
   the first workload that can be CPU-bound on this box AND exercises the mechanism on the read tail.
2. **io_uring** — after reads: async-batched I/O for cache-MISS reads (KPI syscalls/op). Bigger lift (native
   binding); pays only when reads hit disk. Out of scope here.
3. **Big box ≥32-core / multi-NUMA (Tier 3)** — the definitive tail-at-scale proof; still LAST by user
   directive. Tier 1 STRENGTHENED its justification (we cleanly isolated that the small box can't show it).
4. **Concurrent GC — dead last / maybe never.** It launders the GC tail rather than proving the architecture
   (Scylla, the end-game reference, has no GC). User wants it last.

## Concrete scope — route single-partition reads to their owning shard
> Anchors below are from a broad routing-map pass (Haiku Explore) — **re-verify each file:line with grep
> before coding; treat as approximate.** Full detail in task_plan.md §Scope.

Today `CqlShardRouter.routeShard(Message.Request)` returns a shard **only for routable single-partition
writes**: the hard gate is an `instanceof ModificationStatement` check (`CqlShardRouter.java` ~L131) — SELECTs
fail it and hit `fallback()`, so `Dispatcher.dispatch` (~L148-159 routes writes to a shard executor; ~L161
falls everything else through to the shared NTR pool). Read sharding = give a routable single-partition SELECT
the same treatment. The partition key is already extractable at dispatch (`SelectStatement` ~L821-849), and
the shard-executor API (`ShardExecutors.execute(locals, shardId, Runnable)` ~L127) is already sufficient.

**THE design-shaping constraint (surfaced by the map): the RF>1 read-correctness gate.** Writes route at any
CL (replicas are contacted separately via the write response handler). **Reads at CL>ONE (QUORUM/LOCAL_QUORUM)
MUST contact multiple replicas** for a correct result — you cannot just read the local owner shard. So read
routing is valid **only when `CL ∈ {ONE, LOCAL_ONE}` OR `RF==1`**; SERIAL reads (Paxos/Accord) never route.
Everything else must fall through to the existing coordinator read path unchanged. This is the read analog of
the write path's RF=3 safety rule ([[feedback_rf3_assumption_poc_validity]]) and it means the PoC win is a
CL=ONE / RF=1 read on the owning shard, degrading correctly at higher CL.

The other new piece: today the local read runs on the shared `Stage.READ` pool
(`AbstractReadExecutor.makeRequests` → `Stage.READ.maybeExecuteImmediately(new LocalReadRunnable(...))`), and
the read path is **synchronous** (no `readAsync` analog to `mutateAsync`). Read routing needs a shard-aware
local-read runner (a `ShardReadRunnable` submitting `command.executeLocally(controller)` on the owning shard
executor, completing the existing `ReadCallback`) instead of `Stage.READ`.

## Hard constraints (inherited — violating any has cost a revert before)
- **Ingress must not throw** — work pulled onto the netty inbound loop uses null-returning lookups +
  try/catch→fallback; a throw closes the connection + leaks capacity ([[feedback_ingress_throw_kills_connection]]).
- **RF=3 correctness** — reads at CL>ONE contact remote replicas; routing to the owning shard must keep the
  multi-replica read path correct (don't optimize away the coordinator's replica selection / digest / repair).
  The mechanism must be correct under RF=3 even in single-node tests ([[feedback_rf3_assumption_poc_validity]]).
- **Netty-aligned futures only** — the async read path (if one is built) uses in-package AsyncPromise/AsyncFuture,
  NOT RxJava/CompletableFuture ([[feedback_async_medium_netty_futures]]).
- **Measure in the RIGHT regime** — the whole point is a CPU-bound READ test. That needs a dataset large enough
  that reads MISS the memtable and deserialize real sstable data (not a hot 2M-row set served from cache), and
  a read-heavy mix. Prove cass CPU ≈ saturated with `mpstat` before trusting any tail number
  ([[feedback_cpu_fence_colocated_loadgen]]). The Tier-1 tail was GC-noise BECAUSE the box was ~idle; a
  CPU-bound read test is what makes the tail mechanism-driven instead of a GC lottery.

## Measurement plan (learn from Tier 1's traps)
- **Loadgen box was DELETED** — re-provision a fresh ccx43 in hel1 (`agent-common/rig/cloud.md`; hcloud token
  env-var at `~/repos/agent-common/.secrets/hcloud.token`). It reassigns the same IP `62.238.35.142`. Seed
  easy-cass-stress rig→loadgen. DELETE IT when done (bills hourly).
- **Stress method** (STRESS-RUNBOOK): `--rate` DEFAULTS TO 5000 — always set it. `--concurrency 3000
  --threads 32`, one process saturates. For reads use `--readrate 1.0` (or a mix), and PRE-POPULATE a large
  dataset so reads hit sstables. Read cass CPU (`mpstat`, all cores) as the saturation signal.
- **Build a CPU-bound read knee first** (ramp until cass CPU ≈ 90–100%), THEN measure the HDR band
  (p50→p9999) at 0.8–0.95× the knee, trunk vs the read-sharding build, INTERLEAVED, MANY rounds (Tier 1's
  2-round p99 was underpowered — the tail needs a full HDR-histogram merge across many interleaved rounds, not
  a single windowed p99). Keep the contention counter + c2c as GC-immune corroboration.
- Co-located alloc/ins-per-op still valid for the co-location-invariant metrics.

## Rig / build state (as of 2026-07-15)
- Rig `157.180.98.112` (E-2276G 6C/12T, single L3/NUMA), conf `/data/tpc-poc/conf` (TrieMemtable), data
  `/data/tpc-poc/data`, JMX local 7199. **Restored to clean state: durable_writes=true, irqbalance active.**
- Live jar = **alloc-gap (5348018527)** = routing + the 3 Tier-0 async fixes. Staged jars in
  `/root/repos/fork/cassandra-tpc-i1/build/`: `.jar.trunk` (745ce392, TPC-free), `.jar.alloc-gap`
  (5348018527), `.jar.routing-alloc` (81e13444, pre-Tier-0). Build tree `/root/repos/fork/cassandra-tpc-i1/`.
- Build loop: edit mac tree → `rsync -az --delete src/ mac→rig` → `ant jar` (NOT `ant build`) →
  `javap -cp <jar> <FQCN>` verify → `cp jar .jar.<suffix>` → `swap.sh <suffix>` → `prep_flip.sh`.
- Rig scripts: `swap.sh`, `prep_flip.sh` (restart+truncate+disable autocompaction), `tail_ab2.sh` (Tier-1
  off-box tail A/B — adapt for reads), plus the Tier-0 `alloc_ab*.sh`, `c2c_ab.sh`, `MemtableContention.java`.
- **Loadgen `62.238.35.142` DELETED.** Re-provision for any off-box test.
- Code committed on `tpc-migration` (NOT pushed): Tier-0 fixes `5ddb809e`, docs `f3f5a79d`, Tier-1 docs
  `918ecdec`. Independent open item: RF=3 multi-node gate; Phase 2 findIndex (upstream).

## Gate for this increment
- Correctness: single-partition reads still return correct results under RF=1 AND RF=3 (in-JVM dtest that
  forms a real multi-node cluster — [[feedback_injvm_dtest_runs_on_macos]]); ingress path can't throw.
- `ant build` + relevant unit/dtest green; then the CPU-bound read A/B (trunk vs read-sharding).
- Read as: if read routing wins the loaded read tail once cores saturate → first real PoC ROI on owned
  hardware. If not → a clean isolation that even CPU-bound-reads need cross-NUMA scale (justifies the big box).
