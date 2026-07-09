# easy-cass-stress throughput investigation — findings (CORRECTED)

**Date:** 2026-07-09. **Context:** TPC PoC (tasks/tpc-migration-planning) needs to measure
Cassandra throughput. The investigation chased a supposed load-gen ceiling — but the real
finding is a **measurement-methodology error**, not a tool bug.

## TL;DR (FINAL — supersedes everything below)
- **`cassandra-easy-stress` output is RELIABLE.** Verified: at sane offered rates the client count
  matches `nodetool` Local write/read count **to the digit** (40k/50k/100k rungs = ratio 1.00). The
  mid-investigation "client ~2× inflated" claim was WRONG — it only breaks at `--rate 2000000`
  (extreme overload). (Also: the tool is `cassandra-easy-stress`, not "easy-cass-stress".)
- **The `Random.getText()` build-once change is NOT a fix** (server-side A/B: ~5%, noise, not 14×).
  Bytecode confirms `generate()` does the real work and is unchanged by building once. Dead end.
- **The REAL issue was too-low `--rate`.** The tool delivers only ~0.1–0.25× of nominal `--rate`,
  so `baseline_v1`'s rungs (offered 10k–130k) put only ~5–30k ops/s on the server and MutationStage
  stayed IDLE — Cassandra was never loaded. Achieved-vs-offered (server-side writes): 40k→5k,
  200k→27k, 800k→98k, 2M→231k. So the "16k write" is an accurate LOW-LOAD point, not capacity.
- **Fix = re-run with much higher `--rate` (or multiple client processes)** until Cassandra
  actually loads (MutationStage/CPU climb). Cross-check throughput server-side (client is fine
  except under extreme overload). Action: `../tpc-migration-planning/phase-4-poc/REBASELINE-HANDOFF.md`.
- **Net:** the multi-turn chase (connections/cores/processes/driver/value-gen/"2× inflated") was a
  string of measurement mistakes on my part. The tool was fine; the offered load was too low.

## Superseded (kept for the record, DO NOT trust the numbers)
The sections below were written mid-investigation using unreliable client-side throughput and a
now-retracted "14×" conclusion. The ruled-out table (connections/threads/rate/queue/io-size) is
still directionally useful, but every throughput figure in it is client-reported and ~2× high.

## Environment
- Rig `root@157.180.98.112` (key-auth SSH). Cassandra build `/root/repos/fork/cassandra-tpc`
  (tpc-migration@ae53c542dc = trunk-equiv), CPU-fenced cores 0–7; client easy-cass-stress on
  cores 8–11 (4 cores). Config: trie memtable, periodic commitlog, RF=1, 16G G1 heap.
- Stress tool `/root/repos/cassandra-easy-stress`, branch `feature/csv-latency`, driver-core
  **4.19.0** (v4). Invocation: `taskset -c 8-11 cassandra-easy-stress run KeyValue --host
  127.0.0.1 --prometheusport 0 --no-schema --readrate 0.0 --partitions 2000000 --threads 32
  --rate 2000000 --queue 2000000 --duration Ns`.

## What was RULED OUT (all measured, not assumed)
| Hypothesis | Test | Result |
|---|---|---|
| Driver connections | `--max-connections` 8→64 (measured 18→130 open conns to :9042) | flat ~42.5k |
| App threads | `--threads` 32→128 | flat ~34k |
| Rate limiter value | `--rate` 300k→1M→5M | flat ~44k |
| Queue fairness | `ArrayBlockingQueue(_, true)`→`false` (rebuilt) | ~41k (no change) |
| Shared Guava RateLimiter | bypass `acquire()` (rebuilt) | ~49k, p99→10s (no throughput gain) |
| Driver I/O threads | `NETTY_IO_SIZE` 8→32 (rebuilt) | ~30k (no gain) |
| Value SIZE | `--field.keyvalue.value='random(4,8)'` | ~30k (no gain — length wasn't the cost) |

Two subagent code analyses informed this (both in the parent session): (1) the tool uses
driver-core 4.19.0 with a fixed per-process `ioEventLoopGroup` of `cores×2`=8 threads that also
runs the completion callback inline — a real secondary factor but NOT the dominant cost; (2) the
`--max-connections/--max-requests` flags DO work (set to 8×32768=262k in-flight) so pooling was
never the cap.

## ROOT CAUSE
`src/main/kotlin/org/apache/cassandra/easystress/generators/functions/Random.kt`, `getText()`:
```kotlin
override fun getText(): String {
    val length = ThreadLocalRandom.current().nextInt(min.toInt(), max.toInt())
    val generator = RandomStringGenerator.Builder().withinRange(65, 90).build()  // ← per op!
    return generator.generate(length)
}
```
KeyValue writes a `random(100,200)` value per op (`KeyValue.kt` Random min=100 max=200); reads
generate nothing. Constructing a `RandomStringGenerator` via its Builder on every write is
CPU- and allocation-heavy. Evidence: async-profiler-style stack sampling of the client JVM
showed `RandomStringGenerator.generate` (76 samples) + `$Builder.build` (4) as the top RUNNABLE
frames, with heavy `StringBuilder`/`ByteBuffer`/`Arrays.copyOf` allocation. The allocation drove
client GC, which caused a **duration-dependent throughput decay** (20s→44k, 60s→16k) that
masked the effect in short tests and made every knob look like "no change".

Why length (`random(4,8)`) didn't help: the cost is the per-call `Builder().build()`, which is
length-independent.

## THE FIX (see the-fix.diff)
Build the generator once as a field; reuse it. 2 lines. Thread-safety: commons-text
`RandomStringGenerator` uses `ThreadLocalRandom` by default and is immutable after `build()`, so
one shared instance is safe for concurrent `generate()` across the 32 worker threads (VERIFY in
follow-up, but standard-safe).

## RESULT (fixed tool, single process, Cass 0–7 / client 8–11)
| | throughput | client cores 8–11 | Cassandra cores 0–7 | MutationStage |
|---|---|---|---|---|
| **unfixed**, 60s | ~16k/s | — | idle (~27%) | Active=0 |
| **fixed**, 60s | **~198–231k/s** | **~97% (388/400)** | **~61% (492/800)** | Active=0 |

The fix is ~14× and moves the bottleneck from the client's value generation onto the client
cores (97%), while Cassandra finally does real work (61% CPU). One fixed process nearly loads
Cassandra on the existing box — a second box is NOT required. p99 at these runs is meaningless
(offered 2M ≫ achieved → coordinated-omission; use rate-limited cells for real latency).

## Current rig state
- The fix is APPLIED but UNCOMMITTED on `feature/csv-latency` (`git diff` = only Random.kt).
  The shadowJar is rebuilt with the fix. All other experimental patches were reverted.
- Cassandra pinned to cores 0–7 (re-pinned live during tests); may have been moved by the
  `lt.sh` probe — re-assert before measuring.
- `/data/tpc-poc/lt.sh` = a load-test probe (args: VALGEN NPROCS CASS_CORES CLIENT_CORES DUR)
  reporting throughput + MutationStage + fence CPU (its mpstat parse needs fixing — use
  `mpstat -P ALL 1 2` and match `Average`).
