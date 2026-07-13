# Task plan — reduce single-node routed write-path CPU overhead

Status: **PROPOSED — pending confirmation.** Evidence in `findings.md`.

## Goal

Cut the residual single-node routing CPU regression (**+12.5 pp over off at 179k ops/s**, after the
park removal) toward its structural floor by removing *recoverable waste* in the routed write path.
Target realistic landing: **~+4-6 pp over off** (findings.md: ~7-8 pp of the 12.5 is recoverable).
Parity is NOT a goal here — it requires deleting the hop (ingress routing), a separate, larger step.

## Decisions to pin BEFORE coding (need user sign-off)

- [ ] **"Reasonable" threshold = the stop condition.** If the bar is "≤ +5 pp over off", steps 1-3
      target it directly. If the bar is "≤ trunk" (the pinned PoC criterion), 1-3 cannot reach it —
      only hop-deletion can, and we should aim there instead. **Do not start step 2 until this is set.**
- [ ] **Step 3 classification.** `task_plan.md` of `nonblocking-write-path` lists "spin-before-park"
      under *Not doing / band-aids*. A queue-draining shard executor (batch the wakeup) is a
      legitimate dispatch efficiency now that the coordinator park is genuinely async — but confirm
      it is not re-classified as a band-aid. Note: *spinning* trades %sys→%usr (no CPU win); only
      *batching* the wakeup reduces CPU. Only pursue if it actually cuts cs/op.

## Step 1 — kill per-op waste (cheap, high-confidence, do first)

- [x] 1a. **DONE + rig-verified.** Allocation-free `containsIgnoreCase` (direct `contains` fast-path;
      lowercase a copy only if the name has an uppercase char). Rig: keyspace-check 4.01 → ~2.95%.
      NOTE: a first `TreeSet(CASE_INSENSITIVE_ORDER)` cut REGRESSED to 5.99% (comparator per-char case
      folding) — caught on the rig, reworked. See `progress.md` rig section + `lessons.md`.
- [ ] 1a. **Per-write keyspace lowercasing.** `SchemaConstants.isLocalSystemKeyspace` (`:134`) +
      `isVirtualSystemKeyspace` (`:151`) allocate a lowercased keyspace name per call (twice), on the
      `Keyspace.open` write path. Fix: case-insensitive membership over the tiny known set (no
      per-call allocation) OR memoize system-keyspace-ness once per keyspace. **Verify it's on the
      write hot path in BOTH arms** — likely an absolute win for off too, so measure whether it
      shrinks the routing *delta* or just lowers both (still worth it).
- [x] 1b. **DONE, reinterpreted (code, unit-verified; rig pending).** Shard threads ARE
      `CassandraThread`s → `ThreadLocalMetrics.get()` already fast-pathed; the real routing-specific
      `getEntryAfterMiss` was `ShardExecutors.CURRENT_SHARD` (plain `ThreadLocal<Integer>` per task).
      Switched to Netty `FastThreadLocal`. See `progress.md` "Session 2026-07-13". Original note below.
- [ ] 1b. **Metric threadlocal misses.** The shard thread repeatedly misses the metric threadlocals
      (`ThreadLocalMetrics.add` → `setInitialValue`/`getEntryAfterMiss`, ~2%). Reduce the per-task
      threadlocal churn (warm/reuse per shard thread, or avoid per-op threadlocal resolution).
      Files: `metrics/ThreadLocalMetrics.java`, `ThreadLocalHistogram.java`, `ThreadLocalTimer.java`.
- [ ] 1c. **(optional) Cheaper latency reservoir.** `DecayingEstimatedHistogramReservoir.findIndex`
      is ~1.5%/op. Only touch if 1a/1b don't move the needle enough — the metric is wanted; the cost
      is the reservoir. Lower priority.
- [x] 1d. **DONE (rig, 2026-07-13).** toLowerCase + `getEntryAfterMiss` frames GONE; matched-throughput
      flip-arm busy 68.56 → 67.41 (−1.15pp, modest — under the +2-4pp hope; frame-level −3.4pp targeted
      but the other ~95% has run variance). 1b routing-specific → shrinks the delta. See `progress.md`.

## Step 2 — allocation-free / specialized shard dispatch (structural; GATED)

- [x] 2a. **DONE (rig, 2026-07-13) — prize is SMALL on-CPU.** `asprof -e alloc`: dispatch machinery a
      minority of allocation (`AsyncPromise` ~6.7%, `LocalMutationRunnable`/routing partial); `Mutation`
      payload dominates (~66%, shared with off). On-CPU GC only ~4.4%. → Step 2's on-CPU payoff ~1-1.5pp;
      real value would be the p95+ GC-pause tail, not on-CPU. **Gate: skip for on-CPU; revisit for tail.**
- [ ] 2a. **Size the prize FIRST**: 20 s `asprof -e alloc` on the matched rung → per-op dispatch
      allocation bytes. If small, skip 2 (do the cheap 1 + reassess). This gates the surgery.
- [ ] 2b. Replace the general executor task path (`ShardExecutors.java`, the local-apply branch's
      `LocalMutationRunnable` in `StorageProxy.java`) with an allocation-free dispatch — reusable task
      objects and/or a specialized per-shard MPSC/SPSC queue (Scylla model), removing per-op task +
      promise + listener allocation. Keep the async-completion contract (`outcome()`/`writeResult()`).
- [ ] 2c. Re-profile — GC (`G1 trim_queue`) share down; CPU down; **allocation-driven p95+ tail**
      improves (check the client `--hdr` p95/p99, which the cpu profile undercounts).

## Step 3 — wakeup batching (uncertain payoff; do last, only if step-3 decision is YES)

- [ ] 3a. Make the shard executor drain its queue before re-parking (batch the wakeup: one unpark per
      N tasks instead of per task) to cut the futex/unpark churn (~cs/op 2.09 → toward off).
      `concurrent/ShardExecutors.java` (SEPWorker/SEPExecutor park policy). **Batching, not spinning.**
- [ ] 3b. Re-profile — cs/op + `%sys` down; confirm no latency regression from added queueing.

## Verify (each step + final)

Re-profile at matched throughput (offered ~180k → ~179k delivered, clean regime), routing ON + flip,
off-box loadgen, i1 methodology. Compare to the flip baseline (**71.0% CPU, 374.7k cs/s, 2.09 cs/op**)
and off (**58.5%**). Per-step: re-run the leaf-frame self-cost fold (findings.md method) to confirm
the targeted frames shrank and nothing new appeared. At-source: `nodetool tpstats` (NT Active low,
Shard Blocked=0, 0 drops). Client `--hdr` for the tail (cpu profile undercounts allocation tail).

**Rig/loadgen:** same as `nonblocking-write-path` re-profile (node `157.180.98.112` runs the flip,
routing ON; helper scripts `/root/{prep_flip,rig_capture,verify_flip}.sh`; loadgen was a fresh
hcloud ccx43 in hel1, deleted after use — **provision a new one, delete when done**, hourly billing).
The flip build must be redeployed with the step's changes: `ant jar` locally → rsync jar → swap
(baseline preserved as `…jar.tpc-migration-baseline`) → restart. Branch off `tpc-nonblocking-write`
(NOT pushed to origin — rsync-of-jar deploy).

## Not doing / out of scope

- **Ingress routing (hop-deletion)** — the real single-node parity fix, but a much larger
  architectural step (route the CQL request to the owning shard thread at the netty inbound loop; the
  ingress-throw hazard applies). Track separately; steps 1-3 are the interim. If the threshold is
  "≤ trunk", go here instead of step 2.
- Re-opening the multi-node RF=3 gate for the CPU question — it dilutes, doesn't resolve (findings.md).
