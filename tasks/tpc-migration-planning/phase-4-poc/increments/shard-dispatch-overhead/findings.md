# Findings — routed write-path CPU decomposition (single-node)

## Why this exists

Removing the coordinator↔shard park (the `nonblocking-write-path` increment / "Brick 3")
took the single-node routing regression from **+28.5 pp CPU → +12.5 pp** at matched
throughput (off 58.5% → i1-no-flip 87.1% → flip **71.0%** @ 179k ops/s). The park is gone;
what remains is the **dispatch→shard hop itself**. This increment decomposes that residual
+12.5 pp into *recoverable waste* vs *irreducible handoff*, to decide whether optimization can
make single-node "reasonable" or whether only deleting the hop (ingress routing) can.

**Bottom line: roughly half the residual is recoverable waste** — led by genuinely dumb per-write
costs — **and roughly half is the structural handoff floor.** Optimization (steps 1-3) can
plausibly reach ~**+4-5 pp over off** (≈8% relative), not parity. Parity needs hop-deletion.

## The measurement

Source: async-profiler `-e cpu` (1 ms), matched-throughput rung `flip2` (179.3k ops/s server-side,
0 CO drops), routing ON + flip, off-box loadgen, i1 methodology. Raw collapsed profile was in the
session scratchpad (`results_flip/prof_cpu_flip2.txt`, 154 376 samples); the numbers below are the
durable extract. Method: **leaf-frame self-cost** (last frame before the count → additive, no
double-counting), bucketed by category. Cross-checked against the `nonblocking-write-path` re-profile
section (WALL `AbstractWriteResponseHandler` 0.39% = park gone; GEE 0.00%; timer 1.6%).

### Self-cost buckets (% of on-CPU samples)

| bucket | % on-CPU | nature | recoverable? |
|---|---|---|---|
| metric / lookup waste | **10.2** | per-op metric + schema lookups | mostly YES |
| GC / allocation | 3.9 | dispatch allocates task+promise/op (on-CPU is a lower bound) | YES (step 2) |
| kernel: wake + net + syscall | 16.6 | net is shared w/ off; futex/unpark is the delta | wake partly (step 3) |
| async plumbing (new) | 2.3 | promise/future/listener — the mechanism | NO (irreducible) |
| apply (memtable/commitlog) | 18.4 | same work off does | shared, not overhead |
| other (CQL parse/exec, JIT stubs) | 48.6 | mostly shared with off | shared |

Cross-checks that reconcile: `%sys` delta flip−off = **+4.7 pp** (14.9 vs 10.2) ≈ the futex/wake
slice of the kernel bucket; `%usr` delta = **+7.4 pp** (51.3 vs 43.9) ≈ metric waste + GC + async
plumbing. cs/op **2.09** (flip) vs **3.47** (i1) — the return-park round-trip removed, the dispatch
wakeup remains.

## Smoking guns (the recoverable waste, with sites)

1. **Per-write keyspace lowercasing — `1.8%` of ALL CPU.** Every write calls `Keyspace.open` →
   `Schema.getKeyspaceInstance` → `SchemaConstants.isLocalSystemKeyspace(name)`, which allocates a
   lowercased copy of the keyspace name to do a case-insensitive system-keyspace check —
   **twice** per call (`SchemaConstants.java:134` `LOCAL_…contains(toLowerCaseLocalized(name))`
   **plus** the nested `isVirtualSystemKeyspace` at `:151`). Name is constant, answer is always
   `false`, recomputed + allocated per op. Profile fingerprint:
   `LocalizeString.toLowerCaseLocalized;LocalizeString.toLowerCaseLocalized;String.toLowerCase`
   under `SchemaConstants.isLocalSystemKeyspace`/`isVirtualSystemKeyspace` ← `Schema.getKeyspaceInstance`
   ← `Keyspace.open`. **Caveat: `Keyspace.open` is on the off path too** — this likely helps *both*
   arms (absolute win), so it may not shrink the routing *delta* much; verify per-arm. Fix: make the
   `*_SYSTEM_KEYSPACE_NAMES` sets case-insensitive (or `equalsIgnoreCase` over the tiny known set), or
   memoize system-keyspace-ness on the Keyspace/KeyspaceMetadata (compute once, not per write).

2. **Per-op latency histogram + metric threadlocal churn — ~3.5%.**
   `ThreadLocalHistogram.update` (`ThreadLocalHistogram.java:62`) → `DecayingEstimatedHistogramReservoir.findIndex`
   (**1.5%**, a per-update binary search over bucket bounds) + `ThreadLocalMetrics.add` threadlocal
   counter, whose `ThreadLocal.setInitialValue`/`getEntryAfterMiss`/`ConcurrentHashMap.get` (**~2%**)
   show the **shard thread repeatedly missing metric threadlocals it doesn't have warm** — this part
   IS routed-specific (the apply moved to a thread without the coordinator's metric TLs). Some
   histogram cost is wanted (we do want latency metrics), but the reservoir is expensive per-op and
   the threadlocal misses are avoidable. Files: `metrics/ThreadLocalMetrics.java`,
   `metrics/ThreadLocalHistogram.java`, `metrics/ThreadLocalTimer.java`,
   `metrics/DecayingEstimatedHistogramReservoir.java`.

3. **Dispatch allocation → GC (3.9% on-CPU, undercounts the tail).** The hop allocates a task object
   + `AsyncPromise`/listener nodes per write (G1 `trim_queue_to_threshold` 2.8%). On-CPU GC is a
   *lower bound* — allocation's real price is the p95+ GC-pause tail. **Size it with a 20 s `asprof
   -e alloc` run before committing to step 2** (that number decides whether the allocation-free
   dispatch surgery is worth it). Files: `concurrent/ShardExecutors.java`, `db/MutationShardRouting.java`,
   and the local-apply routing branch in `service/StorageProxy.java` (`LocalMutationRunnable`).

## Recoverable vs floor (the decision number)

The routing *delta* is ~18% of the flip's on-CPU (apply + CQL work are shared). Splitting the +12.5 pp:

- **~7-8 pp recoverable** — metric/lowercase waste (~5 pp), dispatch allocation/GC (~1.5 pp),
  batchable wakeups (~1.5 pp).
- **~4-5 pp floor** — async plumbing + the non-batchable enqueue→wake→dequeue of a cross-core handoff.

So steps 1-3, fully successful, land around **71 → ~64-66** (+4-6 pp over off). Better and defensible
as a tax; **not parity**. (These pp are derived estimates from on-CPU shares × 71% util; treat as
±2 pp, refine by re-profiling after each step.)

## The architectural caveat (why 1-3 have a floor)

The hop exists only because coordinator logic runs on the Native-Transport thread and hands the apply
to a *different* thread. The stated end-state — **route the request to the owning shard thread at
ingress** so coordinate-and-apply run on one thread — *deletes* the hop for the local-replica case,
which is the single-node case (owner == only replica), and it's lock-free. **Steps 1-3 optimize a hop
the end-state architecture removes.** They are the pragmatic interim; hop-deletion (ingress routing)
is the thing that reaches parity-or-better single-node. Weigh step 2's cost against bringing ingress
routing forward instead. See `nonblocking-write-path/findings.md` (I5 / inbox-route end-state) and
memory `feedback_ingress_throw_kills_connection` (the ingress hazard).

## Why RF=3 does not resolve this (recorded, so it isn't re-litigated)

RF≥3 hides the hop's *latency* behind the QUORUM network wait but **not its CPU** (latency-hiding ≠
cost-hiding — memory `feedback_propose_fix_not_measurement`). The coordinator spends the hop CPU
every write regardless of RF; RF=3 only *dilutes* it as a share of total (coordinator hop vs
coordinator + 3× replica apply). A +12.5 pp coordinator premium would still be there at RF=3, just
averaged smaller — it would not "fix" the delta. This is why the plan is to reduce the cost
single-node now, not to hope multi-node absorbs it.
