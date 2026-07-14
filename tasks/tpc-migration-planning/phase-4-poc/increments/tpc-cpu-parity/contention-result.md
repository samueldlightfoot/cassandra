# TASK 1 result — memtable per-shard contention counter (trunk vs routing-alloc)

**2026-07-14. The GC-immune domain-language result the Phase-6 methodology critique asked for
("trunk N/1k, routing ~0") — CONFIRMED.**

## What was measured
TrieMemtable's per-shard `MemtableShard.writeLock` contention, via the upstream counters
`Contended memtable puts` / `Uncontended memtable puts` (a put increments exactly one: `tryLock()`
succeeded → uncontended, else → contended). Read over JMX (`MemtableContention.java`, local port 7199)
as a **delta over a steady 90s routed-load window** after 45s warmup — the window excludes the
startup/commitlog-replay transient, whose applies run on non-shard threads and inflate the cumulative
count. Co-located loadgen @90k (contention RATIO is co-location-invariant). `contention_ab.sh`.

No code change: `Contended/Uncontended memtable puts` + `tryLock`-contention are **upstream** in
`TrieMemtable`; only `Misrouted memtable puts` is fork-added. Both the trunk jar (745ce392) and the
routing-alloc jar (81e13444) emit them. Benchmark table `keyvalue` uses `memtable='default'` →
`/data/tpc-poc/conf` sets `default: inherits: trie` → TrieMemtable (the sharded memtable the routing
work targets).

## Result — matched ~8.09M-put window (both arms within 0.03%)
| arm | Δcontended | Δtotal puts | **contended / 1k puts** | contended % | misrouted |
|---|---|---|---|---|---|
| **trunk** | 581,479 | 8,086,504 | **71.9** | 7.19% | (no counter) |
| **routing-alloc** | **0** | 8,088,684 | **0.00** | 0.000% | 0 |

Mechanism, in domain language: on trunk every memtable shard is written by the whole shared
MutationStage pool, so `tryLock()` fails ~72 times per 1,000 puts and the loser blocks (contention-time
mean 71ns). Under routing each shard has exactly one owner thread (`SequentialExecutorPlus[]`, indexed
`memtableShardId % SHARD_COUNT`), so in steady state `tryLock()` never fails — **zero** contended puts
over 8.09M. `misroutedPuts=0` confirms every routed apply landed on its owner shard.

Caveat on the cumulative counter (why the delta window matters): routing-alloc's *cumulative* contended
sat at 193,421 = 5.7% at T0 and did **not move** through the window (T1−T0 = 0). Those 193k are the
one-time startup transient (commitlog replay + ramp, unrouted), NOT steady-state contention. Reading the
lifetime counter would have wrongly reported routing at ~2–6%.

## NTR-pool queue-wait (secondary; no signal at this load)
`nodetool tpstats` under load: trunk `Native-Transport-Requests` and `MutationStage` show
AllTimeBlocked=0, Pending≈0 — the shared NTR pool is **not** a bottleneck at ~90k on 6 cores (box ~45%
busy at the write knee). Routing's `Shard-N` pools show small healthy pending queues (1–8), i.e. the
single-writer executors draining normally. So at this scale the contention that routing removes is the
**memtable shard-lock**, not NTR-pool queueing. (NTR-pool queue-wait would separate the arms only past
the point where the shared pool saturates — a bigger box, per the Phase-6 ceiling.)

## Honesty / scope
- Single-node RF=1: the most favorable case for sharding (100% routed, no unrouted RF=3 writers). Under
  RF=3, unrouted writers (hints/read-repair/LWT) legitimately take the shard lock and would add back some
  contended puts — bounded by the unrouted fraction. This counter is the replica-local upper bound.
- The magnitude (72/1k, 71ns each) is small in absolute CPU on 6 cores — consistent with Phase 6's
  "6 cores is near best-case for shared-everything." The RESULT is the **elimination** (→0), a clean
  mechanism proof, not a tail-at-scale claim.
