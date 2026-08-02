# Result — Read-path sharding (Variant A) A/B

**Date:** 2026-07-16. **Rig:** Intel Xeon E-2276G, 6 physical / 12 HT, 62GB. **Loadgen:** off-box AMD EPYC
ccx43 (16 vCPU, deleted after the run). **Dataset:** `cassandra_easy_stress.keyvalue`, ~8.57M partitions,
1.8GB, 4 sstables, flushed, page-cache-resident (each read is CPU work, not disk). **Method:** same
read-sharding jar, flag `cassandra.tpc.cql_read_routing` toggled on/off per arm + restart, interleaved rounds,
off-box loadgen, CO-corrected HDR read latency + rig `nodetool`/`mpstat`/`pidstat`. Routing proven firing
(`ShardLocalReadRouted` ≈ `Local read count`, ~100%).

## Verdict: read-path sharding is a NET REGRESSION on this box, at every load level

| Load | OFF (baseline `Stage.READ`) | ON (routed to shard executors) |
|---|---|---|
| Light ~99k r/s (3 rounds) | p99 **4ms**, 34% CPU, stable | p99 **27ms**, +57% CPU, stable |
| Near-knee ~180k (2 rounds) | 184k, p99 **≤80ms**, **0 errors**, 52% CPU | 167k, p99 **~2s**, **~1.3M timeouts**, 70% CPU |
| Overload 400k offered (2 rounds) | 260k, p99 **0.8s**, 52k err | 226k, p99 **14s**, 2.7M err |

All numbers reproduced to <1% across rounds. Routing lowers the sustainable read-throughput ceiling, raises
tail latency by 1–2 orders of magnitude, and burns more CPU for the same work.

## Why (architecture)

Routing pins every read to one of **12 single-threaded shard executors on 6 physical cores** (unpinned,
floating). Load is uneven by partition hash and a single-thread executor cannot shed to idle siblings → head-of-
line queueing that grows without bound under load. The baseline `Stage.READ` both **load-balances** across its
pool and runs the read **inline** on the coordinate thread (`maybeExecuteImmediately`, zero handoff), so it
never head-of-line-blocks. On a **shared-L3** box with **floating** shard threads, the cache locality the paper
relies on does not exist (all cores share L3; no core affinity), so only the serialization cost remains →
**load-balancing beats locality for reads here.**

The code is correct and fires (in-JVM dtest passes at RF=1/3 × CL ONE/QUORUM/ALL; counters confirm ~100%
routed). It is the *mechanism* that does not pay on this hardware. This mirrors the write-path outcome (writes
memtable-only ≈ 5% CPU): the 6-core shared-L3 box is the wrong instrument for a thread-per-core locality win.
The paper's read-tail win needs private per-core caches (multi-L3 / NUMA) and enough cores that one-executor-
per-core is not oversubscribed — i.e. the deferred **big-box** test.

## Open question (next work): what the +57% CPU actually is

Initially blamed on submit-path allocations + a contended counter. A Fable review corrected this: at +24µs/read
those cost <0.5µs (~2%). The real cost is the **cross-thread handoff paid TWICE per read** — routing swaps a
*blocking* read onto a single-threaded executor mid-request, so the coordinator parks/unparks on submit AND
again on completion, where the baseline runs inline and never parks — plus working-set migration, HT
cycle-stretch, and runqueue delay (~24 runnable threads on 6 cores → the 27ms p99). The collapse is capacity
exhaustion (routed ceiling ≈168k ≈ the 167k delivered), not a queue-policy bug. Two fixes are worth making —
(a) fast-fail on the silent expiry-drop that fuels the timeout storm, (b) exception-free fallback when a shard
is backed up — but they only make routing *do no harm* (self-disable under load), not win. Full corrected
analysis + the one `perf stat` experiment that confirms it: `perf-bug-hunt-plan.md` (v2).

**Strategic:** Variant A (mid-request thread-swap of a blocking read) is structurally the weakest design on any
hardware. The deferred big-box test also measures nothing as currently built — the shard is chosen from
*memtable* boundaries but the data is sstable-resident behind a *global* chunk/page cache with *unpinned*
threads, so there is no data affinity to exploit. A real read-locality win needs ingress-routing the whole read
(one handoff, state built hot on the owning core — the Accord end-state, needs an async read path) plus pinned
shard threads and shard-partitioned read state, or a memtable-resident workload.

## Bottleneck note (answered a mid-run question)

The box could not be driven past ~66% mpstat "busy" at its sustainable max (~265k reads/s). This is **not** a
loadgen limit (loadgen idle at ~4/16 cores) nor a software cap — it is the **6 physical cores**: 7.8/12 logical
cores busy = all 6 physical saturated + ~1.8 HT siblings; the remaining HT siblings cannot add throughput, so
mpstat pins at ~66%. "66% = physically maxed" on this HT box. Throughput is closed-loop concurrency/latency
limited below that; only ~1.5× overload (offered 400k) briefly drives 97–100% peak.
