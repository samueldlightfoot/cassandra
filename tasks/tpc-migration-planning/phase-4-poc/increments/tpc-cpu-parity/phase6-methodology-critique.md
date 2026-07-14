# Phase 6 methodology — Fable adversarial critique + resolutions (2026-07-14)

Fable was consulted on the Phase 6 tail-at-scale measurement plan (high-leverage, hard-to-reverse
claim). Its critique and how each point is resolved / actioned below. Verbatim critique archived at
the end.

## The critique, ranked, with resolutions

### §0 Baseline asymmetry — THE headline threat. **RESOLVED (fix not portable) + one honest caveat.**
Concern: parity is "routing+2 fixes ≈ trunk"; if the fixes port to trunk, the honest baseline is
trunk+fixes and routing may still be net-negative.
- **Terminal-write fast path (the dominant fix): NOT portable — branch-added machinery.** Source-verified:
  trunk has ZERO `outcome()`/`computeVerdict`/`writeResult`-AsyncPromise in AbstractWriteResponseHandler
  (106 insertions branch-added) and ZERO `.outcome()` call sites in StorageProxy. The fix removes a cost
  that only the routing/consensus-migration layer introduced (matches attribution: trunk `outcome()`=0
  samples, ScheduledFutureTask alloc 7 vs routing-fixed 474). So "trunk+fix" ≡ "trunk"; the baseline is
  fair. The fix is an **architectural-layer correction** (removes overhead the branch added), not a general
  optimization that would also speed up trunk. This is exactly the "architectural enabler" case Fable said
  to prove with evidence — proven.
- **1a route schema-handle: not portable** — `MutationShardRouting` doesn't exist on trunk. (Tiny anyway,
  24 samples.)
- **containsIgnoreCase (prior session, in BOTH routing arms): IS portable** — `SchemaConstants` is general.
  Trunk pays ~466 `toLowerCaseLocalized` samples (~1.8% CPU) the fork avoids. The truly-fair baseline
  "trunk+containsIgnoreCase" is ~1.8% faster → routing's +1.4% NS becomes ~+3.2%. Honest caveat; still
  single-digit-% near-parity, and containsIgnoreCase is upstreamable (a Cassandra-wide win). **Record, don't
  hide.**
- **RF=3 sharpening — parity is RF=1-conditional.** The fast path fires only when `writeResult.isDone()` at
  `outcome()` time. Under RF=3 the coordinator awaits remote acks → not terminal → slow path (timer+promise)
  runs per coordinator write. So at RF=3 routing re-incurs the branch-added `outcome()` cost that trunk never
  pays. Magnitude TBD by the (deferred) multi-node RF=3 gate. This is a threat to the *parity result itself*,
  not just a caveat — state it plainly.

### §2 The rig's honest ceiling — **ADOPTED as the framing.**
6 physical cores / single L3 / single NUMA is near the *best case for shared-everything* (an L3-resolved
HITM bounce is tens of ns; the thesis penalty comes from cross-socket/CCX transfers + lock convoys with
dozens of contenders). Therefore:
- A **null result proves nothing** about 32–128-core boxes (expected, uninformative).
- A **large positive result is suspicious** (delta should be small at 6 cores → a big win is likely GC /
  backpressure / a comparison bug, not locality).
- What this rig **can** prove: (a) mechanism existence (c2c HITM — DONE); (b) no-regression; (c) a
  **core-count scaling slope** (knee at 2..6 physical cores, SMT off: trunk sublinear, routing closer to
  linear) — the strongest thesis-shaped evidence available here.
- **Writeup must say:** "tail-at-scale requires ≥32 cores / multi-NUMA; this rig demonstrates the mechanism
  (and, if run, the scaling slope), NOT the end-state tail." "TPC wins the tail" from this box does not
  survive review.

### §1 p99 demotion — **half-right; execute properly.**
Don't assert "p99 is GC noise" — prove/handle it:
- Measure at **0.8–0.95×knee**, not ⅓-knee (no queueing tail exists at 40–85k). [confirmed: at 40k trunk
  p99=1.14ms clean, GC only at p999=128ms.]
- Compare **full HDR distributions**: contention queueing lives in the ~1–20ms band; GC/flush stalls
  plateau at 100ms+. "Routing shifts mass out of the 1–20ms band at 0.9×knee" is defensible; a single p99
  number inside the stall plateau is not.
- **GC profiles DIFFER between builds** (attribution alloc totals: trunk 39,287 vs routing-newfixes 45,040
  = routing allocs ~15% MORE). So p99 is NOT clean common-mode — and the confound runs *against* routing
  (more alloc → more GC). If routing's tail is not worse despite higher alloc, that's fine; if worse, suspect
  alloc not architecture. (Optional: one high rung under ZGC to shrink GC out of the p99 band.)

### §3/§5 Confounds to control (actioned in analysis + caveats)
- **Compaction debt across an ascending ladder** contaminates later rungs. auto-compaction is disabled
  (prep_flip) so no compaction runs, but SSTables accumulate; both arms run the same ladder so it is
  symmetric. Note it; ideally reset/randomize rung order for a publication run.
- **Unsharded shared bottleneck may set the knee** — if the single commitlog/flush saturates at the knee,
  BOTH builds hit the same ceiling and the memtable/pool win is invisible in throughput (false null). Profile
  what saturates at the knee before reading knee-equality as "no headroom win."
- **Verify routing's shard threads are affinity-pinned** — unpinned shard threads migrate on a 12-HT box
  crowded with compaction/flush/GC threads, destroying the locality being measured (false-null generator).
- **Client-side ms-resolution hides µs deltas** — p50 0.78ms includes 0.46ms RTT; a tens-of-µs contention
  delta is below visibility. Prefer server-side coordinator write histograms; ideally domain counters
  (AtomicBTreePartition CAS-retries, NTR pool queue-wait): "trunk N retries/1k writes, routing ~0" is
  GC-immune and in domain language.
- **Loadgen headroom** — confirm the 16-vCPU loadgen isn't itself the knee at 300k (mpstat), else both
  curves flatten identically (false null).
- **Interleaved A/B (N≥3, alternating)** for any published latency delta; a single ascending pass is anecdote.

### §3 Cleaner probes, ranked (for the next, bigger-box round)
1. trunk+portable-fixes baseline (validity precondition — resolved above for the dominant fix).
2. **Core-count scaling ladder** (best thesis-shaped evidence this rig can give).
3. Server-side µs histograms + domain contention counters (CAS-retry, pool-wait) at 0.8–0.95×knee.
4. perf c2c — keep as corroboration; symbol-aggregate by CODE (JIT map), SMT off, multi-window.
5. Hot-partition skew run (zipfian) — where trunk burns CAS hardest AND where routing can LOSE (one shard
   serializes a hot partition). Publish the trade-off before a reviewer finds it.
6. read/mixed — mostly moot (reads aren't sharded); only second-order "do off-loaded writes help read tail?".
7. Reject: pin-to-fewer-cores to "force sharing" (fewer contenders = LESS bouncing; amplifies scheduler
   queueing, not the tested contention). Only SMT-off pinning is worth it.

### §4 RF=1 confound — RF=1 is the MOST FAVORABLE case for sharding.
Maximizes the sharded fraction of per-op work; RF=3 adds unsharded messaging + remote-ack coordination +
shared outbound-connection queues (Amdahl shrinks the win), and RF=3's network latency floor buries µs
deltas. Caveat wording to adopt: "single-node RF=1 isolates the replica-local apply path (what the sharding
changes); it is an UPPER BOUND on the sharded fraction — end-to-end RF=3 benefit is strictly smaller. The
replica-local memtable-apply part does carry over (every replica applies)."

## Verbatim critique
See progress.md ATTRIBUTION/PHASE6 sections for the distilled actions; the full Fable text is preserved in
the session transcript. Key line: "Frame the deliverable as mechanism-plus-slope, with tail-at-scale
explicitly deferred to a bigger box."
