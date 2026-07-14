# Findings — the TPC routing CPU delta is implementation immaturity, not architecture

**Thesis (user-driven, 2026-07-13):** the CQL-ingress-routing build runs **+6pp CPU vs stock trunk** at
matched sub-knee load. An earlier read blamed "the architecture" (thread-per-core has a structural tax).
That was premature and wrong-headed: **if thread-per-core is supposed to be *faster*, a +6pp regression is
evidence we are implementing TPC badly, not that TPC is bad.** This doc pins each hotspot to either (a) our
own overhead — with a specific cheaper thing ScyllaDB (mature TPC, Seastar) does instead — or (b) a genuine,
paper-acknowledged TPC cost with a known fix. Verdict: **~2.5–3pp is unambiguously ours and fixable; only
~0.3–0.7pp is the one real TPC steering tax; the rest is JVM dispatch + items to dig into.**

Inputs: `../cql-ingress-routing/perf-ab-methodology.md §RESULTS-VS-TRUNK-FIXED` (the A/B), rig profiles
`/root/results_prof/{cpu,alloc}_{trunk,routing}.collapsed` (async-profiler 4.4, matched 182k, 30s), also
copied to the mac scratchpad. Scylla source: `~/repos/scylladb` (Seastar submodule + C++).

---

## 1. What the Enberg-2019 paper actually claims (re-read) — it *helps* this case

`Documents/Reading/Thread_Per_Core_Architecture_Impact_on_Tail_Latency_Enberg_2019.pdf` (Sphinx vs Memcached).

- **TPC's promise is TAIL LATENCY, not CPU.** Sphinx beats Memcached by up to 71% on *tail*; the paper is
  explicit that TPC may *spend more* CPU (busy-polling, steering) to buy tail. So "routing CPU ≤ trunk" was
  never the thread-per-core claim — measuring CPU-parity as the PoC gate may be the wrong axis.
- **The paper's OWN named residual overhead is tiny in our profile.** §V: TPC is "held back by request
  steering [thread wake-ups] and OS interfaces." That is our `EpollEventLoop.wakeup` — **~0.2–0.4pp of our
  6pp**, and the paper says even Seastar pays it. The paper says *nothing* about redundant keyspace scans,
  heap-churny futures, or megamorphic dispatch — because those are not TPC costs. They are ours.
- **The paper's biggest lever is IRQ affinity** (pin NIC IRQs off the app cores + disable `irqbalance`) →
  lowest tail. **We have never tuned this on the rig.** Config-only, affects both arms and the tail gate.
- The paper's win **grows with cores/concurrency/contention** (shared-everything limits multicore scaling).
  Our 6-core, single-node, GC-dominated CPU test is the *worst case* for exhibiting the benefit.

---

## 2. The drift-proof gap decomposition (raw sample deltas — the honest ruler)

Both profiles: 30s, matched ~182k, same box. Raw counts are directly comparable (do NOT use each profile's
self-normalized %; both normalize to 100% and inflate frames that are 0 in the other arm). Routing captured
**21,327** CPU samples vs trunk **19,055** = **+11.9% CPU work** = the +6pp busy delta (2,272 added samples;
each ~0.0027pp).

| routing-added (raw Δ samples) | ~pp | class | Scylla does instead |
|---|---|---|---|
| `SchemaConstants.containsIgnoreCase` (apply-side keyspace scans) | ~1.2 | **OURS** | carries `schema_ptr`; table by UUID hash; zero per-op scan |
| sharded memtable apply (`TrieMemtable$MemtableShard.apply` +224, trie compares +156) | ~1.4 | **likely OURS** | per-shard memtable is lock-free single-writer (no CAS) |
| async future/listener (`ListenerList.notifyExclusive`160 `AsyncFuture.trySet`89 `appendListener`79) | ~0.9 | **OURS** | inline value futures; a local write allocs ZERO future machinery |
| megamorphic/`vtable`/`itable stub` (174+63) | ~0.6 | **grey** | monomorphic C++ templates (a genuine JVM↔C++ gap) |
| steering/scheduling (`EpollEventLoop.wakeup`83 `epoll_wait`82 `_raw_spin_unlock_irq`110) | ~0.7 | **~half genuine TPC tax** | pays cross-shard hop too; mitigates via shard-aware client |
| commitlog access (`CommitLog.add`80 `coverInMap`74 serialize 78) | ~0.6 | **investigate** | (surprising routing delta; may be a scheduling artifact) |

### Correction that this analysis forced (record it so nobody re-asserts the old number)
The metrics histogram is **NOT in the routing gap.** Raw: trunk `findIndex` **4.06%** vs routing **3.38%** —
trunk pays *more*. It is a Cassandra-wide cost on **both** arms (via `ThreadLocalHistogram.update →
DecayingEstimatedHistogramReservoir.findIndex`, recorded at several metric points per request). It does NOT
explain routing > trunk. It IS, separately, a ~4%-of-CPU absolute-perf target on **every** Cassandra node
(see §3.3) — but it is gap-neutral, so do not credit it to routing.

The async row above dropped its `recycler`117 frame (2026-07-14): it has no provenance in any teardown and is
plausibly Netty transport buffer pooling, not our futures. So the honest async-future bucket is **~0.9pp**
(328 samples), not the ~1.2 first booked. Don't re-add the recycler to the future cost without attributing it.

---

## 3. What Scylla does differently (four source teardowns) — the fixes

All four confirm: the routing-added costs are things a mature TPC engine avoids. File:line are ScyllaDB
`~/repos/scylladb` (Seastar under `seastar/`).

### 3.1 Async futures — Seastar allocates ~zero on a local write; we allocate 3 objects/write
- Seastar `future<T>`/`promise` are **inline value objects** — result-or-exception stored in the future's own
  frame (`seastar/include/seastar/core/future.hh` ~L565 `union any _u`), **no heap alloc** for the future/promise.
- The only heap object is **one continuation per genuine suspension** (`future.hh` ~L1645 `new continuation<…>`),
  with the lambda stored **inline** (`Func _func;` ~L1050). A promise holds exactly **one** `task* _task`
  (~L730) — **never a listener list**; the single-continuation case is one pointer.
- **Ready-future fast path** (`future.hh` ~L1680): `.then()` on an already-available future **runs the
  continuation inline and returns** — no `new continuation`, no scheduling, no alloc. `make_ready_future`
  (~L2145) builds such a future. A local single-shard write (`service/storage_proxy.cc` ~L3005-3006,
  `shards.size()==1 [[likely]]`) descends a chain of already-satisfied `.then()`s → **effectively zero future
  machinery allocated**; only a real await (commitlog fsync) allocates one continuation.

**Our side (verified at source):** `AbstractFuture.addListener` (`src/java/org/apache/cassandra/utils/
concurrent/AbstractFuture.java:419-421`) does `appendListener(new GenericFutureListenerList(listener))` — it
**allocates a listener-node for even the first/only listener** (also the constructor paths :131,:137).

> **CORRECTION (2026-07-14, Fable + concurrency map, source-verified) — the bare-listener fix is DEAD.**
> The claim below (originally here) that "the `listeners` field is already typed to permit a bare
> `GenericFutureListener`, so `addListener` just always wraps it" is **false**. The field is typed
> `ListenerList<V>` and its `AtomicReferenceFieldUpdater` is built with `ListenerList.class` (:103,:105), which
> runtime-`valueCheck`s every write — storing a bare listener throws `ClassCastException`. A real bare field
> means widening to `Object` + reconciling ~9 intrusive-stack sites (a core-primitive rewrite), and it saves
> ~zero on the hot path: the busiest attaches (`MutationVerbHandler:86`, `Dispatcher:593,700`,
> `ModificationStatement:746`) use `addCallback`/`map`, which allocate a **fused** callback node regardless.
> The old "deletes `notifyExclusive`+`appendListener` ~1.1pp" also conflates an **alloc** fix with a **drain**
> fix — `trySet` still CASes and `notify` still drains.

**Corrected fix — a three-tier "attach-on-done" family** (the Seastar ready-future idiom, applied where this
fork genuinely completes synchronously: local apply completes `writeResult` *before* `AbstractWriteResponse
Handler.outcome()` attaches on the inline path, so attaches land on an already-done future ~4–5× per write):
1. **Tier 1 (trivial, caller-level, do first):** in `outcome()` (`AbstractWriteResponseHandler.java:174`), if
   `writeResult.isDone()` compute the verdict inline and return `ImmediateFuture` — skip the per-write
   `AsyncPromise` + `scheduler.schedule` timer + `timer.cancel` + listener + drain. `computeVerdict()` is pure
   (:224-227). Zero concurrency risk. Also kills a per-write cross-thread timer schedule+cancel — a likely
   feeder of the `EpollEventLoop.wakeup` samples §2 booked as "TPC steering tax."
2. **Tier 2 (core, additive):** ready-path in `AsyncFuture.appendListener` — when `isDone(result) &&
   listeners == null`, resolve the executor as `notifyExclusive` does (`ListenerList.java:145-148`) and invoke
   `notifySelf` inline; never touch the field. Ordering-safe (a drain holds `NOTIFYING` in-field until done,
   :110-121, so null+done ⇒ all earlier listeners already ran). Low-moderate risk; gate on future/promise units.
3. **Tier 3 (optional):** done-checks in `addCallback`/`map` (`AbstractFuture.java:273-355`) before node
   construction, scoped to `notifyExecutor()==null && executor==null`; where node allocs actually die.
- **KILL:** bare-listener field; recycled node (pooling 24-byte TLAB objects is a wash).

### 3.2 Schema resolution — Scylla resolves once & carries a typed handle; we re-scan per op
- Prepared `modification_statement` holds `const schema_ptr s` bound at prepare (`cql3/statements/
  modification_statement.hh:64`); table via `s->table()` (`modification_statement.cc:292,359`) — no name lookup
  at execute. Across the shard hop schema travels as a `global_schema_ptr` (`schema/schema_registry.hh:167-181`).
- Apply resolves the table by **UUID hash**: `database::do_apply` → `find_column_family(m.column_family_id())`
  → `_tables_metadata.get_table(uuid)` (`replica/database.cc:1991-1992, 1173-1179`). **No `is_system_keyspace`
  anywhere on the apply path.** Where the concept exists it's a **size-2, case-SENSITIVE** `unordered_set`
  `.contains` at boot only (`replica/distributed_loader.cc:37-52`).

**Verdict — REFUTED as inherent.** Our per-mutation `MutationShardRouting.route → isLocalSystemKeyspace/
isVirtualSystemKeyspace → containsIgnoreCase` (case-insensitive linear scan over the system-keyspace name set,
per write; trunk = 0%) is added entirely by our route/apply layer. **Fix:** thread the already-resolved
`TableMetadata`/`Keyspace` handle through `MutationShardRouting.route` and the ConsensusMigration apply check;
if a system-keyspace distinction is truly needed, cache a **boolean flag on `TableMetadata`** (computed once)
instead of re-deriving keyspace identity per op. (Fix #1 already memoized the *decision*-side scan in
`CqlShardRouter`; this is the *apply*-side twin that fix #1 did not touch — it is the single biggest
routing-added frame at ~1.2pp.)

### 3.3 Metrics — Scylla is per-shard/lock-free; ours re-adds cross-core synchronization
- Seastar metrics registry is `static thread_local` (`seastar/src/core/metrics.cc:350`) — each shard owns its
  copy, tagged `this_shard_id()` (`metrics.cc:256,394-396`). `storage_proxy` is `sharded<>` so its write
  histogram is one-per-core. Aggregation is **lazy at scrape** (`estimated_histogram::merge`
  `utils/estimated_histogram.hh:532`; `metrics_api.hh:415`), never on the hot path.
- Per-op record is a **non-atomic bucket increment**: `stats.write.mark(...)` (`storage_proxy.cc:3387`) →
  `histogram.hh:516-523` → `approx_exponential_histogram::add` → `_buckets.at(find_bucket_index(n))++`
  (`estimated_histogram.hh:181-183`); `find_bucket_index` is **pure bit-math** (`:158-168`) — no decay, no CAS.
  The whole `mutate_begin→mutate_end` path takes **no lock/atomic/shared cache line** per op.

**Verdict — two separate points.**
- (a) *Gap-neutral but worth knowing:* our histogram is already `ThreadLocalHistogram` (per-thread, so not a
  contention violation on update), BUT it still calls `DecayingEstimatedHistogramReservoir.findIndex` per op,
  which is a **binary search** over bucket boundaries — ~4% CPU on **both** arms. Scylla's O(1) count-leading-
  zeros bucket index (`approx_exponential_histogram`) is a directly usable reference. **Biggest absolute-perf
  win in this whole doc, and upstreamable to stock Cassandra** — but it does not close the routing gap.
- (b) If any per-op metric in the TPC path is still a **shared/decaying** reservoir (striped/atomic across
  shard threads), that IS a shared-nothing violation — make it per-shard, merge lazily. Audit which metric
  points on the shard path are thread-local vs shared.

### 3.4 Memtable — Scylla's per-shard apply is lock-free single-writer (should be CHEAPER, not +1%)
- A Scylla memtable is one object **per shard** (`replica/memtable.hh:107`), partitions in a `double_decker`
  B+ tree (`memtable.hh:109-111`). Insert (`memtable.cc:786-795`): B+ tree lower_bound+emplace
  (`:225-246`) + row merge + LSA bump-alloc. **Zero** `std::atomic`/`mutex`/CAS in `memtable.cc` or
  `partition_version.cc` (grep = 0) — single-writer-per-shard makes synchronization unnecessary; the scary
  wrappers just flip a `thread_local` pointer / a `bool` (`allocation_strategy.hh:301-312`, `logalloc.hh:417-429`).
  Allocation is a **bump allocator** (`logalloc.cc:1727-1751`), no per-op malloc; compaction is out-of-band.

**Verdict — our +1.4pp is a smell, not an inherent sharding tax.** In a correct TPC design the per-shard
memtable apply should be **cheaper** than shared (single-writer removes atomics + malloc). Most likely culprit:
our `MemtableShard`/`AtomicBTreePartition` apply **still runs a CAS/`AtomicReference`/volatile update** even
though the shard now guarantees a single writer per partition. **Fix:** confirm the CAS is present on the
shard apply path; if the single-writer invariant holds (no concurrent flush/other writer on that partition),
add a single-writer fast path that skips the CAS. **Correctness gate:** must *prove* the invariant first.

### 3.5 The one genuine TPC tax — cross-shard steering wakeup
Scylla pays this too: a wrong-shard request costs a `bounce_to_shard` (`modification_statement.cc:279-281,
350-354`) + `invoke_on` inter-shard hop (`transport/server.cc:963-982`); it tracks it as a dedicated
`cross_shard_ops` metric (`storage_proxy_stats.hh:162`) and mitigates with a **shard-aware client** — the
handshake advertises `SCYLLA_SHARD`/`SCYLLA_SHARD_AWARE_PORT`/`SCYLLA_SHARDING_*` (`transport/server.cc:1433-
1443`) so drivers open a per-shard connection and land on the owner. This is exactly Enberg §V's "request
steering wakeup." **Our `EpollEventLoop.wakeup` (~0.2–0.4pp) is a genuine TPC cost, not a defect; the correct
fix is the shard-aware client protocol (as Scylla ships), not eliminating the hop.** Small single-node; its
real payoff is at scale (cross-core steering compounds with core count).

---

## 4. Bottom line
- **Too early to say "architecture."** Of the +6pp: ~2.5–3pp is unambiguously our implementation with a
  concrete, Scylla-proven cheaper path (schema handle, single-listener futures, memtable single-writer);
  ~0.3–0.7pp is the one honest TPC steering tax (shard-aware-client fix); the rest is a JVM dispatch gap +
  a commitlog delta to investigate.
- **The measurement ruler is too coarse** to see 1pp fixes (≈3pp session drift, 1.8pp thermal). Fix that first
  (instructions-per-op via `perf stat`, fixed CPU frequency, interleaved/2-node A/B) — see `task_plan.md`.
- **Two adjacent absolute-perf wins** fall out that are NOT gap-closers but help every node and are
  upstreamable: the `findIndex` O(1) histogram (~4% on both arms) and IRQ affinity (the paper's own lever).
