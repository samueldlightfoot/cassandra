# Plan — domain contention counters + routing-alloc vs trunk side-by-side (2026-07-14)

Continues the tpc-cpu-parity increment. Two deliverables the prior handoff left NOT-DONE.

## Key discovery (resolves a would-be code change)
The benchmark table `cassandra_easy_stress.keyvalue` uses `memtable='default'`, and the **live** config
`/data/tpc-poc/conf/cassandra.yaml` sets `default: inherits: trie` → **TrieMemtable** (not skiplist; the
repo `conf/` default is skiplist and misled an earlier read). TrieMemtable's per-shard `ReentrantLock`
contention counters — `Uncontended memtable puts`, `Contended memtable puts`, `Contention time` — are
**upstream** (present in `trunk`); only `Misrouted memtable puts` is fork-added. So both the trunk jar and
the routing-alloc jar already emit the contention counters. **TASK 1 needs no code change** — read the JMX
counters after a matched-load run per arm.

Mechanism under test: on trunk every shard is written by the whole shared MutationStage pool → `tryLock`
fails → `Contended memtable puts` climbs. Under routing each shard has ONE owner thread → `tryLock` always
wins → contended ~0. Domain-language, GC-immune, not confounded by the I/O-bound write knee (it's a ratio
per put, not a throughput).

## TASK 1 — contention counters (headline)
- Reader: `MemtableContention.java` (JDK17 on rig, JMX local port 7199) dumps all
  `org.apache.cassandra.metrics:type=TrieMemtable,*` counters for keyvalue.
- Per arm (trunk, routing-alloc): `swap` → `prep_flip` (truncate + counters reset on fresh node) →
  co-located loadgen 90k for a fixed window → read counters.
- Report: contended / (contended+uncontended) per 1k puts, contentionTime mean, misroutedPuts (routing only).
  Cross-check total puts vs `nodetool tablestats … Local write count`.
- Bonus: NTR-pool queue-wait from `nodetool tpstats` (Native-Transport-Requests / Shard-* pending+blocked).
- Expected: trunk N contended/1k, routing ~0.

## TASK 2 — routing-alloc vs trunk side-by-side (no such table exists with the fix jar)
All prior comparisons are trunk vs routing-newfixes (pre-alloc-fix). Produce one consolidated table:
1. ins/op — capstone-style, arms {trunk, routing-alloc}, 6×40s co-located @90k (same-session, drift-safe).
2. alloc — alloc_ab-style, arms {trunk, routing-alloc}, asprof `-e alloc` co-located.
3. c2c HITM — `c2c.sh` per arm, off-box loadgen, ldlat=30.
4. rate-ladder p50/p90 — off-box `--hdr`, fixed concurrency, rungs 80/120/160/200k, trunk vs routing-alloc.

## Facts / gotchas (this session)
- Rig `157.180.98.112`; live conf `/data/tpc-poc/conf`; data `/data/tpc-poc/data`; JMX local 7199.
- Loadgen box `62.238.35.142` UP (KEEP until user says done). Off-box for c2c + rate-ladder; co-located for
  ins/op + alloc (co-location-invariant).
- Jars in `…/cassandra-tpc-i1/build/`: `.jar.trunk` 745ce392, `.jar.routing-alloc` 81e13444 (verified md5).
- Rig currently live on routing-newfixes → swap to routing-alloc first.
- macOS has no `timeout`; `pkill -f <pat>` over SSH self-matches — use PID kill or split the literal.
