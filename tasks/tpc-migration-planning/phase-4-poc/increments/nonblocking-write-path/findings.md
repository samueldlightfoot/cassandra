# Write-path blocking audit — toward a non-blocking TPC write path

## Why this exists

Enabling `cassandra.mutation.shard_routing` regressed single-node writes by **+28.5 pp
CPU (58.5→87%) and 10× p50 (14→149 µs) at matched throughput** (see
`../i1-single-node-perf/findings.md`). Root cause (async-profiler, both arms): a
**synchronous coordinator↔shard park/unpark rendezvous per write** — the coordinator
parks waiting for the shard to apply; the shard signals/unparks it → a futex storm
(~620k cs/s).

Agreed invariant: **no shard/TPC thread ever blocks (lock, sync I/O, or waiting on
another thread), and nothing in the write pipeline parks** — cross-thread work is async
with future-based completion. This audits **every** blocking site on the write path and
tags each **convertible-now (pure coordination)** vs **needs async-I/O substrate**.
(File:line are subagent-reported and cross-verified between readers; re-confirm at the
site before editing.)

## TL;DR

The **response side is already async** — the CQL client flush runs on the Netty event
loop (`Flusher`, `voidPromise`, nothing awaited), `onResponse` is a lock-free counter +
a callback-capable `signal()`, and remote-replica acks are future-driven. The regression
comes from **one park that is a deliberate choice**: the coordinator *consuming* the ack
via `get()`. **Two fundamental fixes are convertible now with no async-I/O infra**; only
the memtable-backpressure park needs a real async substrate.

## Blocking sources (ranked, most-fundamental first)

| # | Site | file:line | Thread | Hot/write? | Class | Fix |
|---|---|---|---|---|---|---|
| 1 | **Coordinator consumes ack via `get()` park — THE rendezvous** | `AbstractWriteResponseHandler.java:130` ← `StorageProxy.java:1006` | Native-Transport worker (coordinator) | **yes** (routing on) | **convertible now** | async-complete the write; drive the (already-async) flush from the completion callback |
| 2 | **Commitlog completion not exposed as a future** — durability parks the apply thread *inside* `beginWrite` | `Keyspace.java:549` → `CommitLog.java:337` → `CommitLogSegment.java:497` (`waitForSync`) | apply thread (shard; or worker if inline) | batch/group: **yes**; periodic: only under >15 s lag | **convertible now** (syncer already off-thread) | make `CommitLog.add` return a completion keyed on `(segment,position)`, completed by the syncer's `syncComplete.signalAll` |
| 3 | **Memtable allocator backpressure park** (pool full) | `MemtableAllocator.java:196` ← `TrieMemtable.java:582` | shard thread | only when pool full (sustained pressure) | **needs async infra** | yield the shard on pool-full, resume on reclaim; or per-shard memory reservation |
| 4 | Kept memtable per-shard lock | `TrieMemtable.java:559` | shard thread | `tryLock` every put; blocks only on cross-thread contention | **correctly kept** | elide only once *all* writers route through shards (inbox-route end-state) |
| 5 | `maybeTryAdditionalReplicas` short park | `AbstractWriteResponseHandler.java:449` | coordinator | speculative-retry path | convertible with #1 | fold into the async completion |
| 6 | Segment-roll park + `synchronized(this)` | `AbstractCommitLogSegmentManager.java:302,339` | apply thread | ~every 32 MB | convertible with #2 | register a completion instead of parking |

**Already async — no change:** client CQL response flush (`Dispatcher`/`Flusher` on the
event loop, `voidPromise`), `onResponse` signalling (lock-free `decrementAndGet` +
`signal()` + optional `callback`), remote-replica acks (future callback in
`MutationVerbHandler.applyMutation`). **Excluded from routing entirely** (never reach a
shard thread): counters, materialized views, CDC, legacy 2i, and — crucially —
**batch/group commitlog** (routing runs only under `commitlog_sync=periodic`, because
batch/group park on fsync every write).

## The two convertible-now fixes

### Fix 1 — Coordinator: stop parking on the ack (removes the measured +28 pp)

Today: `StorageProxy.mutate` loops `responseHandler.get()` (`:1006`) →
`condition.await(timeout)` (`AbstractWriteResponseHandler.java:130`). With routing off the
local apply runs **inline on the worker** (`maybeExecuteImmediately`), the condition is
already signalled, and `get()` doesn't park. With routing on the apply is handed to a
`Shard-N` thread and the worker **parks** until `onResponse → signal → unpark`.

The condition is already `Condition.Async` and `signal()` already fires an optional
`callback` — the park is a *consumption choice*, not structural. **Seam:** complete an
`AsyncPromise` from `signal()`/`onFailure`/timeout in `AbstractWriteResponseHandler`,
return it from `mutate`/`mutateWithTriggers`, thread it up through
`ModificationStatement.execute` → `Dispatcher.processRequest`, and drive `Dispatcher.flush`
(already off-thread) from the completion callback. **Hard parts:** the CQL synchronous
`ResultMessage` contract; timeout becomes a scheduled timer; typed-exception fidelity;
`maybeTryAdditionalReplicas`; and Tracing/ClientWarn/MessageParams thread-locals must be
captured/restored across the hop. **No async-I/O substrate needed.**

#### Thread model — where the async completion runs (verified at source)

Completion does **not** move to a new pool; it runs **inline on the `Shard-N` apply thread**
as a continuation of the apply. `writeResult` is a plain `AsyncPromise` whose
`notifyExecutor()` is null, so its listener fires on the thread that calls `trySuccess()` —
the shard thread inside `signal()`. That thread: applies the mutation → `checkOutcome()`
(verdict) → builds the `ResultMessage` → hands it to the `Flusher`. The socket write stays on
the **Netty event loop** (already async, `voidPromise`). Nothing parks.

Per write, before → after:
- **Today:** NT-worker dispatches to `Shard-N` and **parks**; `Shard-N` applies then
  **unparks** the coordinator (futex); the coordinator wakes behind a ~40-deep runqueue →
  verdict → flush.
- **After:** NT-worker dispatches to `Shard-N`, attaches the listener, **returns to serve the
  next request** (no park); `Shard-N` applies → listener runs inline → verdict → flush enqueue;
  Netty loop writes. Two-thread rendezvous → single-thread continuation; the coordinator leaves
  the critical section, so there is no unpark to storm on.

Cost that moves with it: the completion touches thread-locals set on the NT-worker
(Tracing, ClientWarn, MessageParams, QueryState). On `Shard-N` they are absent → Phase 1
captures them at dispatch and restores them around the callback (capture/restore, not redesign).

#### Why the single-node regression was this severe (park × oversubscription × zero-amortization)

The +28 pp / 10× p50 is a *product*, not one cost: (1) the per-write **park**; (2) **thread
oversubscription** — 28–40 runnable on 12 HW threads, so each unpark queues behind the
runqueue; (3) **zero amortization** — RF=1 single-node has no remote-ack wait to hide the
rendezvous behind and I5 is inert. Decomposition: of the +135 µs p50, only ~19 µs is added CPU
(futex + dispatch); the other ~116 µs is off-CPU **scheduler wait**. CPU and latency have
different dominant causes — CPU is futex/dispatch, latency is runqueue queueing.

Consequence for verification: Phase 1 removes the **park**, not the **hop**. Expect CPU to move
**toward** the off arm, not **to** it, single-node — the dispatch-to-shard wakeup remains by
design (it only pays off at RF≥3 / under lock contention / with I5). Don't read a residual
single-node gap as failure.

### Fix 2 — Commitlog: expose completion as a future

Durability is done *inside* `beginWrite` (`Keyspace.java:549`) **before** the memtable
write and before `applyFuture()` is returned — so callback-chaining `applyFuture()` does
**not** offload the fsync wait; the thread has already parked. The fsync itself and the
syncer thread (`PERIODIC-COMMIT-LOG-SYNCER`) are **already off the write path**. **Seam:**
convert the park-based `syncComplete` `WaitQueue` (`CommitLogSegment.java:497,372`) into a
callback registry — `CommitLog.add` returns a registerable completion keyed on
`(segmentId, position)` that the syncer completes at `signalAll`; `applyInternal` then
chains append → memtable → complete-on-sync, and the local ack (#1's callback) fires from
it. **No new I/O pool** — only turning a park into a callback. Bonus: this also unlocks
**batch/group commitlog** under routing (they currently force `routing_enabled=false`).

## Needs async-I/O substrate (longer horizon)

- **#3 Memtable allocator backpressure** — parks the shard thread when the memtable pool
  is full (writes outrunning flush; deadlock-safe today via `OpOrder.markBlocking`). The
  single deepest blocker under sustained pressure. Requires async backpressure (yield the
  shard thread on pool-full and resume on reclaim) or a per-shard memory reservation that
  guarantees the thread never waits. Cannot be a local edit.
- **(Reads, noted for the broader goal)** — a page-cache-miss read is a *synchronous* disk
  read; a fully non-blocking shard thread on the read path needs async I/O (io_uring /
  async chunk reads). Out of scope for the write path but the same substrate as #3.

## Recommended ordering

1. **Fix 1 (coordinator async).** Highest value — directly removes the profiled rendezvous,
   pure coordination, no infra. Re-profile after: the `futex`/`unpark` share and the
   ~620k cs/s should collapse, and CPU-at-matched-throughput should fall back toward the
   off arm.
2. **Fix 2 (commitlog future).** Removes the residual apply-thread parks (periodic lag,
   segment roll) and unlocks batch/group under routing. Medium surface, still no async-I/O.
3. **Fix 3 (memtable async backpressure).** The deep one; do it when the async-I/O
   substrate lands.
4. **Kept lock (#4):** leave as-is; revisit at the inbox-route end-state.

Only after Fix 1 (and ideally Fix 2) is a multi-node perf test meaningful — before that
it would re-confirm a known, fixable coordination cost.

## Re-profile results — Fix 1 / Brick 3 (async Dispatcher flip) VERIFIED (2026-07-13)

Rig `157.180.98.112`, flipped build (`processRequestAsync`/`outcome()` sha256-matched inside the
loaded jar), `shard_routing=true`, off-box loadgen (hcloud ccx43, hel1). i1 methodology (TRUNCATE +
autocompaction off + fresh JVM per rung). Two write-only KeyValue rungs, `--concurrency 3000
--threads 32`; async-profiler cpu+wall on the Cassandra process, vmstat cs, mpstat CPU, `nodetool`
at source. Raw artifacts in the session scratchpad (`results_flip/`).

### Matched-throughput comparison (all ≈178.8–179.3k ops/s, server-side delivered)

| arm | ops/s | CPU% | %usr | %sys | cs/s | cs/op |
|---|---|---|---|---|---|---|
| off (routing off) | 178.8k | 58.5 | 43.9 | 10.2 | — | — |
| i1 (routing on, no flip) | 178.8k | 87.1 | 59.0 | 23.3 | ~620k | 3.47 |
| **flip (routing on + async)** | **179.3k** | **71.0** | **51.3** | **14.9** | **374.7k** | **2.09** |

- **CPU closed 56% of the i1→off gap** (87.1→71.0; off=58.5) — *toward* off, not *to* it, exactly
  the predicted single-node outcome (the dispatch→shard wakeup remains by design).
- **cs/op −40%** (3.47→2.09); **%sys −8.4 pp** (23.3→14.9) = the futex-syscall collapse.
- At the *same* offered rate the flip delivers **more** (rung 1: offered 200k → **194k** delivered
  vs i1's 178.8k, 0 client errors) — removing the park raised the write knee.

### The park is gone (profile + at-source)

- **WALL `AbstractWriteResponseHandler` = 0.38–0.39%** of wall time (both rungs). In i1 the
  Native-Transport worker spent the bulk of each write's wall parked there (the +135 µs p50). Gone.
- **CPU coordinator↔shard signalling** (`WaitQueue`/`Awaitable`/`Condition.signal`): **16.5% → 2.6–3.0%**;
  **futex + unpark + cond_signal**: **~19% → ~8.9%**.
- **`nodetool tpstats`: Native-Transport-Requests Active=1, Pending=0, Blocked=0** — workers freed,
  not parked on the ack (in i1 they parked per write). 12 Shard pools carry the apply (~1.17M each
  ≈ 14M = NT completed), **Blocked=0, zero dropped messages**; MutationStage idle (apply is on
  shards under routing ON). Apply-side latency (tablestats Local write) = 4 µs.
- Residual CPU park **7.9%** = idle **SEPWorker/ThreadPoolExecutor** parking when their queues drain
  — benign executor churn (the dispatch→shard wakeup), **not** the write-completion rendezvous.

### Both forbidden hotspots ABSENT (the two Brick-3 rev-2 fixes hold under load)

- **`GlobalEventExecutor` = 0.00%** — the sequential `andThenAsync` combiner (replacing
  `FutureCombiner.nettySuccessListener`, whose `notifyExecutor()` was `GEE.INSTANCE`) prevents the
  JVM-global funnel. Zero GEE frames in either rung.
- **Deadline timer = 1.58–1.75%** (armed on per-channel EventLoops) — present but not a
  serialization hotspot; no single-thread lock contention.
- **Async path is live on the hot path** (runtime proof the flip is engaged, not just compiled-in):
  `processRequestAsync` 32%, `outcome()` 11%, `mutateAsync` 10%.

### Measurement gap (honest limitation)

Server-side **coordinator write-latency is disabled under the flip** — `mutateAsync` omits
`updateCoordinatorWriteLatencyTableMetric` (a documented deferred item), so `nodetool
proxyhistograms` Write = 0. The flip's p50/p99 can't be filled in the same server-side µs units as
the baseline table. The latency *improvement* is proven mechanistically (park gone; WALL AWRH 0.39%;
apply-side 4 µs), not quantified server-side. Client `--hdr` p50 = 0.91 ms is RTT-dominated (not a
µs proxy); p95+ is the GC-pause tail (0 CO drops → not over-drive), the same noise the baseline left
ungated. **To quantify server-side latency, wire the deferred write-latency metric.**

### Verdict

Brick 3 works. The per-write futex park behind the +28.5 pp / 10× regression is removed; CPU at
matched throughput moves 56% toward off; cs/op drops 40%; both code-bug hotspots (GEE, timer
contention) are absent. The residual single-node gap to off is the by-design dispatch→shard wakeup
that RF≥3 + ingress routing is meant to hide — the multi-node RF=3 gate remains the decision point.
Next: the deferred post-profile polish (mutate()-level metrics incl. the write-latency metric,
`maybeTryAdditionalReplicas` async, write-warnings fidelity), then the 3-node dtest on Linux/CI.

### Residual +12.5 pp decomposed → new increment `shard-dispatch-overhead`

A CPU-profile decomposition of the residual (flip 71.0% vs off 58.5% at 179k) splits it ~half
recoverable waste / ~half structural handoff floor. Smoking guns: a per-write `toLowerCaseLocalized`
of the keyspace name (`SchemaConstants.isLocalSystemKeyspace`/`isVirtualSystemKeyspace` via
`Keyspace.open`, ~1.8% of all CPU) and shard-thread metric-threadlocal misses. Steps 1-3 (metric
waste → allocation-free dispatch → wakeup batching) can plausibly reach ~+4-6 pp over off, not parity
— parity needs deleting the hop (ingress routing). Full decomposition, sites, and plan in
`../shard-dispatch-overhead/{findings,task_plan,progress}.md`. **RF=3 does not resolve this** — it
hides the hop's latency, not its CPU.
