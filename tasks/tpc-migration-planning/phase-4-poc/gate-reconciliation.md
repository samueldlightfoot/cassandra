# Gate operating-point reconciliation (poc-criteria §5/§8 vs increments.md)

**Date:** 2026-07-10. Resolves the apparent conflict over WHERE and HOW to gate, using the
clean off-box baseline (`offbox-baseline-clean.md`).

## The apparent conflict
- **poc-criteria §5 / §8.2:** gate at **50% of clean_max** (stable, tight noise band). The
  **80%/knee region is reported for shape only, NOT a gate point** — measured 3-iter spreads
  there were ~8× (read 33→263 ms), "too noisy to gate."
- **increments.md I1 (:103) + §0:** *"the gate is the loaded tail"*; acceptance = p99 ≤ flag-off,
  and at **low load I1 is expected to REGRESS** (routing adds a hop when a MUTATION permit is
  free) — "one low-load cell characterizes the crossover; it does NOT gate."

So §5 gates at mid-load and shuns the knee; increments.md says the *real* signal lives at the
loaded tail and low load can even mislead. Genuinely opposed — as written.

## The resolving insight: §5's retreat was a co-located MEASUREMENT artifact
§5 avoided the knee because the knee was **noisy** — but that noise (§8.2's 8× spreads) was
measured on the **co-located 4-core-client rig, where the knee was client-contaminated** (client
and Cassandra saturated together; CO latency = seconds). The **off-box clean baseline removes
that noise**: at/near the knee we now get **0 errors and stable p99** —
- write 246k @ 88% CPU → p99 1.9 ms, 0 err
- read 120k @ 80% CPU → p99 3.3 ms, 0 err
No 8× spread. **The empirical premise behind "don't gate at the knee" no longer holds off-box.**
⇒ We can gate at the loaded tail (increments.md) *because* the better rig made it clean. The
conflict dissolves in favour of increments.md, enabled by the hardware change §5 predates.

## Also: §5/§8.2's NUMBERS are stale (mismeasured clean_max)
§8.1 already carries a correction banner: `baseline_v1`'s clean_max (~write 16k) was **Cassandra
idle** — the load model was wrong. So §8.2's "50% of clean_max" points (10–46k achieved, p99
0.34 ms) are **near-idle**, not mid-load. Real clean_max (off-box) is ~**15× higher**. Any gate
point stated as an absolute rate in §8 must be replaced by the clean baseline.

## Reconciled gate (proposed)
Gate each increment at **two loaded points per mix**, plus one non-gating characterization point:

| role | target load | write | balanced 0.5 | read 0.9 | gates? |
|---|---|---|---|---|---|
| **Primary — loaded tail** | ~80–90% CPU (near knee) | ~240k (246k@88%) | ~170k (~90%) | ~115k (120k@80%) | **YES** |
| **Secondary — mid-load** | ~55–70% CPU | ~180k (57%) | ~130k (69%) | ~90k (59%) | **YES** |
| Characterization — low load | ~35–40% CPU | ~120k (37%) | ~80k (44%) | ~50k (37%) | no (I1 crossover) |

- **Primary** is the loaded tail where TPC's contention win manifests (increments.md).
  Now gateable because off-box makes it clean.
- **Secondary** keeps §5's stability spirit — a corroborating clean mid-load point.
- **Low-load** is retained precisely to *observe* I1's expected routing-hop regression, and
  explicitly does NOT gate (increments.md). Prevents a low-load tie/regression from reading as failure.
- **Balanced primary at ~170k, not 180k:** 180k was 96% CPU (tails starting to blow, 8.2 ms).
  Back off to ~90% for a clean-but-loaded point; report 180k for shape.
- Verdict rule unchanged (§5): increment p99 ≤ baseline_median within the **3-iter noise band**
  at each gating point; a delta inside the band is a tie. **Noise bands not yet captured** — the
  single-pass clean baseline gives the points; the A/B run does 3 iters at each.

## Metric decision — analyzed (sub-agent + source verification, 2026-07-10)
poc-criteria §4.2 pins the gate on **client CO-corrected p99** (`--hdr`), with proxyhistograms for
attribution. A critical sub-agent argued to KEEP §4.2 (client-CO primary) on the grounds that
server-side is "structurally blind to shard-inbox queue wait." **That claim was verified FALSE
against the fork source:** `StorageProxy.java:534` records write latency = `nanoTime() -
requestTime.startedAtNanos()` in the `finally` AFTER `responseHandler.get()` (`:1004`), which blocks
until the local apply acks. So the server-side ClientRequest timer (= `proxyhistograms`) **spans the
full coordinator path including the shard-executor hop + its queue wait.** Both metrics see I1's
routing-hop regression. Corrected picture:
- The A−B difference is ONLY **client-generator queue wait + ~<1 ms network** — NOT shard-queue wait.
- **Server-side (B) is coordinated-omission-immune by construction** (the server times exactly what
  it serves; no open-loop hiding to correct, no fragile `--hdr` warmup processing). It directly
  measures Cassandra's served-request tail incl. the I1 hop, and is the cleanest matched-throughput
  A/B comparator (same clock, no client variance). Weakness: bucketed (coarse) + decaying.
- **Client-CO (A)** adds end-to-end honesty (client backpressure) but near the loaded-tail gate point
  its queue wait couples to capacity/offered-rate — a confound exactly where we gate. The stdout p99
  column is unusable (startup+decay); A must come from the processed `--hdr` histogram.

**Recommendation (departs from §4.2 — user's call):**
1. **AND-gate BOTH.** Pass a cell only if server-side p99 ≤ trunk **and** client-CO p99 ≤ trunk,
   each within its 3-iter band. Either regressing = fail. Robust to each metric's blind spot.
2. **Primary = server-side (B)**; client-CO (A) = mandatory end-to-end guard. (Sub-agent picked A
   primary, but its reason was the false shard-blindness claim.)
3. The AND structure makes "which is primary" **low-stakes** — both must pass regardless; primary
   only sets the headline number + attribution order.
4. Handle B's coarseness: read p99 as a band across straddling buckets, add p99.9, idle-settle rungs.

## What this changes / TODO
1. **poc-criteria §5 + §8** — replace stale clean_max/50%-point numbers with the off-box clean
   baseline; change the gate rule from "50%-only (knee too noisy)" to "**primary loaded-tail +
   secondary mid-load** (knee now clean off-box)"; keep the noise-band tie rule.
2. **Capture 3-iter noise bands** at the 6 gating points (2 per mix) on the next box session,
   with proper `--hdr` processing (warmup-skipped) + server proxyhist.
3. Then the increment (I1) A/B runs the identical points; verdict per the band rule.
