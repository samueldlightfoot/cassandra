# Result — Tier 1: the small box cannot show the win with write-only load (two clean findings)

Stood up the off-box loaded regime (fresh ccx43 loadgen, durable_writes=false, irqbalance off) and measured.
Two findings, both clean; neither is the hoped-for tail win, and together they redirect the roadmap.

## Finding 1 — a write-only CPU-bound regime is UNREACHABLE on this box
Decoupled continuous `mpstat 2` (101 samples over a full run) while the server write counter climbed
**+14.4M / 100s = ~144k writes/s**: **max busy 10%, nearly all samples <2%.** Loadgen drove up to **275k
req/s** off-box (0 errors). With durable_writes=false a write is a memtable put — CPU-trivial. The old "45% CPU
knee" was commitlog + flush + GC; remove the I/O and the write apply barely uses CPU. **Writes cannot saturate
these cores** — you'd need read load (deserialize/merge/decompress) to make them CPU-bound, but the fork routes
writes only. Confirms roi-path.md's scaling-ladder abandonment from the other direction.

## Finding 2 — off-box, trunk vs alloc-gap is parity; the tail is GC-noise with NO signal
Clean off-box A/B (interleaved, 2 rounds, rate 100k, delivered≈60k, 0 errors):
| arm | round 1 p99 | round 2 p99 | delivered | cass CPU |
|---|---|---|---|---|
| trunk | 103 ms | 56 ms | 60,200 w/s | 12% avg / 43–51% max |
| alloc-gap | 109 ms | **5.3 ms** | 60,200 w/s | 12% avg / 44–52% max |

p99 swings **5–109 ms within a single arm** across two rounds — a GC/flush-stall lottery, not a discriminator.
Delivered + CPU are identical across all four runs. **Parity, no tail signal.** The Tier-0 co-located 50–100×
p99 hint (trunk 30–140 ms vs alloc-gap 0.7–2 ms) was a **co-location + profiling artifact** — off-box it
vanishes. The regime is bursty flush/GC-dominated (low avg CPU, periodic stalls hitting both arms equally).

## What this means (redirect, not failure)
The mechanism is proven (Tier 0: contention 72/1k→0, c2c −27%); the small single-L3 box simply cannot host the
regime where it pays: writes can't saturate CPU, the coordination tax is tens of ns, and GC stalls drown any
µs-scale signal. **No further write-path optimization on this box will expose the win.** The two levers that
can:
1. **Read-path sharding** — the only way to build a CPU-bound test on owned hardware (reads are CPU-heavy) AND
   exercises the paper's larger win half (read-tail 54%/20%) AND matches real workloads. **The pivot.**
2. **Big box ≥32-core / multi-NUMA (Tier 3)** — the definitive tail-at-scale proof; still last, now with a
   cleanly-justified reason (we've shown the small box can't show it).

io_uring pairs with reads (async-batched cache-miss I/O), after read-sharding. Concurrent GC stays dead-last —
it would launder the GC tail rather than prove the architecture (Scylla, the end-game reference, has no GC).

## Rig / cost state
Loadgen `tpc-loadgen` (62.238.35.142) **DELETED** (billing stopped). Rig restored: durable_writes=true,
irqbalance active, live jar = alloc-gap (5348018527). Scripts/data: `rig:/root/{tail_ab2.sh,results_tier1/}`.
