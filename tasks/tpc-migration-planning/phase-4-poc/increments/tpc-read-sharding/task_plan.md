# Task plan — Read-path sharding (route single-partition reads to their owning shard)

**The pivot after Tiers 0+1 exhausted write-only work on this box.** Goal: give a single-partition SELECT the
same shard-routing treatment writes get, so (a) a read-heavy workload can be CPU-bound on hardware we own —
the first regime where the tail is mechanism-driven not a GC lottery — and (b) it exercises the paper's larger
read-tail win half. Rationale + constraints + rig state: `read-sharding-start.md`.

## §Scope (from the routing map — anchors approximate, RE-VERIFY with grep before coding)
Line numbers are from a broad Haiku Explore pass; confirm each before editing.

| Concern | Anchor (verify) | Note |
|---|---|---|
| Write-only routability gate | `transport/CqlShardRouter.java` `routeShard(Message.Request)` ~L112-176; `computePlan(ModificationStatement)` ~L187-211 | Hard read gate = `instanceof ModificationStatement` ~L131 → SELECT hits `fallback()` ~L213. Reusable: keyspace lookup, single-column-PK check ~L195, system-ks exclusion ~L203 |
| Ingress dispatch branch | `transport/Dispatcher.java` `dispatch(...)` ~L122-163; route branch ~L148-159; NTR fallback ~L161 | Already generic on `Message.Request` — extends to reads once `routeShard` returns a shard for SELECTs |
| Shard executor API | `concurrent/ShardExecutors.java` `execute(ExecutorLocals, int, Runnable)` ~L127; `execute(int, Runnable)` ~L117; `currentThreadIsOwnerOf(int)` ~L110; `SHARD_COUNT=getAvailableProcessors` ~L50 | Sufficient as-is; takes `Runnable` only (no async return) |
| SELECT dispatch + key | `cql3/statements/SelectStatement.java` `execute(...)` ~L369; partition keys → `DecoratedKey` ~L821-849 | Full partition key present at dispatch (today unused for routing) |
| Local read apply (today) | `service/StorageProxy.java` `read(Group,...)` ~L2582; `LocalReadRunnable.runMayThrow` ~L3149; `command.executeLocally(controller)` ~L3162. `AbstractReadExecutor.makeRequests` ~L138-170 → `Stage.READ.maybeExecuteImmediately(new LocalReadRunnable(...))` | Local read runs on shared `Stage.READ`, NOT a shard. Read path is SYNCHRONOUS (no `readAsync` analog to `mutateAsync`) |

**Files/methods that would change:** `CqlShardRouter` (extend `routeShard`/add `computeReadPlan(SelectStatement)`
+ the CL gate); `Dispatcher.dispatch` (allow reads through the route branch); `StorageProxy`/`AbstractReadExecutor`
(a `ShardReadRunnable` that runs `executeLocally` on the owning shard executor and completes the existing
`ReadCallback`, replacing `Stage.READ` for the routable case).

**THE correctness gate (design-shaping):** route reads ONLY when `CL ∈ {ONE, LOCAL_ONE}` OR `RF==1`; SERIAL
reads never route. Reads at QUORUM/LOCAL_QUORUM must contact multiple replicas → cannot read only the local
owner shard. This is the read analog of the write path's RF=3 safety rule.

## Phases

### Phase 0 — scope + design (read the map, then design; Fable only if a hot-path concurrency primitive is touched)
- [ ] Confirm the partition key/token is available at ingress dispatch for a single-partition SELECT.
- [ ] Decide: generalize `CqlShardRouter` to reads (shared token→shard logic) vs a read-specific router.
- [ ] Design where the local read runs on the owning shard, and how the coordinator's replica-selection /
      digest / read-repair path stays correct under RF=3 (routing must not bypass multi-replica reads).
- [ ] Decide the async read shape (if needed): does a `SelectStatement.executeAsync` exist, or is the read path
      synchronous today? Netty-aligned futures only if built.

### Phase 1 — implement single-partition read routing
- [ ] Route routable single-partition reads to the owning shard executor; keep multi-partition / range /
      non-routable reads on the shared NTR pool.
- [ ] Ingress path must not throw (null-returning lookups + try/catch→fallback).

### Phase 2 — correctness gate
- [ ] In-JVM dtest (real multi-node cluster, runs on macOS) — single-partition reads correct at RF=1 AND RF=3,
      at CL ONE and CL>ONE; ingress can't throw; digest/read-repair unaffected.
- [ ] `ant build` + relevant unit/dtest green; `ant jar` + javap-verify.

### Phase 3 — CPU-bound read A/B (the payoff — needs the RIGHT regime)
- [ ] Re-provision the off-box loadgen (deleted). Pre-populate a LARGE dataset so reads miss cache and
      deserialize sstable data (a hot 2M-row set served from memtable would be CPU-trivial like writes were).
- [ ] Find the CPU-bound READ knee (ramp until cass CPU ≈ 90–100%, mpstat-proven).
- [ ] HDR band (p50→p9999) at 0.8–0.95× the knee, trunk vs read-sharding build, INTERLEAVED, MANY rounds
      (Tier-1's 2-round windowed p99 was underpowered → full HDR-histogram merge). + max CPU-bound read
      throughput. Keep contention counter + c2c as GC-immune corroboration.
- [ ] Delete the loadgen box when done (bills hourly).

## Review section
- Is the read test genuinely CPU-bound (mpstat) with reads hitting sstables (not a cached hot set)? If not, the
  tail is a GC lottery again (the Tier-1 trap) and the result is void.
- RF=3 read correctness preserved (digest/read-repair/replica-selection)? Ingress can't throw?
- Honest read: even a CPU-bound single-L3 box may show no tail win — that's a valid isolation that justifies
  the big box, not a failure.
