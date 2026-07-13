# Design — CQL-path ingress routing (single-node inbox hop)

**Status:** design-first, Phase 1 deliverable. Build is Phase 2 and GATED on user sign-off after this
doc + the Fable critique. All file:line refs verified on branch `shard-dispatch-overhead` 2026-07-13.
User direction (2026-07-13): frame = **build server-side, protocol later**.

**Adversarial review outcome (Fable, 2026-07-13): PROCEED-WITH-CHANGES — folded in.** The crux and the
3→2 mechanism survived source-verified attack. Six required changes were verified against source and are
now incorporated (marked ⟵FABLE below): the owner-inline apply bypass is a *build item*, not reuse (§5);
the predicate needs a coordinate-hazard list §5 never covered — triggers, denylist, transient replication,
custom handler, named values (§4); loop-side prepared lookup must be cache-policy-neutral (§3); §6 now
specifies the backpressure fix; §11 gates on seam-attributed wake evidence, not a fabricated cs/op number;
§8 corrects its own overstatement (0-hop IS reachable under kept-Netty via shard-as-EventLoop).

---

## 0. Verdict up front

**The crux is settled, and the increment's own framing was wrong in a way that favors building.**
`findings.md` counted the single-node write as 2 hops by treating coordinate as running on "the NT
thread." It does not: `Dispatcher.dispatch()` runs on the netty event loop and hands the request to
`requestExecutor` — a *separate* NTR pool (`Dispatcher.java:143`; `processRequest` is documented "not
expected to execute on the netty event loop", `:663`). The flip stopped that thread from *parking*, but
the **loop→NTR handoff is still a real hop**. Today's single-node routed write is **3 hops**, not 2.

**Server-side CQL ingress routing — routing on the netty loop via cheap prepared-`ExecuteMessage` key
extraction — deletes the loop→NTR hop (3→2) for prepared single-partition local writes, with no client
protocol change.** This is a genuine cs/op reduction, not the "pure relocation" the increment feared.
"Pure relocation" is only true if you route *after* parse+bind on the NTR thread (design-target §4's
stated CQL point) — a variant nobody should build.

**Recommendation:** build server-side loop-routing (this doc's §2–§7), flag-gated, flag-off
byte-identical. Measure the single-node cs/op delta against the flip+step1 baseline (67.4% CPU @ 180k,
cs/op ~2.1). Treat the shard-aware client protocol as a **separate, later, and smaller** lever (§8): under
this design's kept-Netty decision it does not reduce hop count single-node — it buys loop↔shard affinity,
and true 0-hop needs shard socket ownership, which the PoC rejects.

---

## 1. The hop model, corrected and verified

Each row is a thread context; each arrow is a thread handoff (a wake).

### Today (flip + I1), routable single-partition local write — **3 hops**

| Thread | Work | Seam |
|---|---|---|
| netty loop | decode message; `dispatch()` submits `RequestProcessor` to NTR | `Dispatcher.java:120,143` |
| ↓ **hop A** | loop → NTR | |
| NTR (`requestExecutor`) | `processRequest`→`processRequestAsync`→ execute: prepared lookup, bind, replica plan, `mutate`→`performLocally` dispatches the apply | `Dispatcher.java:668,522`; `StorageProxy.java:1918→2025` |
| ↓ **hop B** | NTR → shard | |
| shard (`ShardExecutor`) | apply (memtable + commitlog); flip callback builds the `ResultMessage`, calls `flush()` | apply on shard; `Dispatcher.java:682,728` |
| ↓ **hop C** | shard → loop | |
| netty loop | Flusher writes the response to the socket | `Dispatcher.java:730` |

### With loop-routing (this design) — **2 hops**

| Thread | Work | Seam |
|---|---|---|
| netty loop | decode; **extract partition key, compute owner shard**; submit `RequestProcessor` to the **shard** executor (NTR bypassed) | route in `dispatch()`; `ShardExecutors.execute(locals, shard, task)` `:127` |
| ↓ **hop 1** | loop → shard | |
| shard (`ShardExecutor`) | execute coordinate (prepared lookup, bind, replica plan, remote legs async); local apply runs **inline** (owner-check bypass — no hop B); flip callback → `flush()` | coordinate on shard; `currentThreadIsOwnerOf` `:110` |
| ↓ **hop 2** | shard → loop | |
| netty loop | Flusher writes the response | `Dispatcher.java:730` |

**What changed:** hop A (loop→NTR) is deleted — coordinate no longer visits the NTR pool, removing one
context switch per routable write. Hop B (NTR→shard apply) is no longer a *thread* hop, because coordinate
already runs on the owner shard — but **only collapses to an inline apply once the owner-inline bypass is
built** (§5, a Phase-2 build item; ⟵FABLE): today `performLocally` unconditionally re-submits the apply to
the shard executor (`StorageProxy.java:2399-2410`), so without the bypass the apply *self-enqueues on the
owner's own inbox* — same thread, no context switch, but the apply runs as a later queued task (deferred
behind other requests' coordinates, lengthening completion + capacity holds). Hop C (shard→loop flush) is
**structural and kept** under kept-Netty: the client socket is owned by its netty event loop, so the
response write must return to the loop (it is CAS-coalesced — one loop wake drains many, `Dispatcher.java
:124-130`). 0-hop (removing hop C) needs the shard thread to own its socket — reachable under kept-Netty
via shard-as-EventLoop (§8; ⟵FABLE), out of PoC scope.

**Why this is a win, not relocation:** deleting hop A removes one park/unpark (SEP schedule) per routable
write — directly the cs/op the increment is trying to cut. The coordinator CPU that ran on NTR now runs on
the shard, so this trades a *handoff* for *more work on the shard thread*. Sign is empirical (§11): the
handoff saving vs. shard serialization under load. Distinct from steps 1–3, which chased on-CPU allocation
inside one thread; this removes a thread transition.

---

## 2. Route decision point — on the netty loop, inside `dispatch()`

The decision sits in `Dispatcher.dispatch(channel, request, forFlusher, backpressure)`
(`Dispatcher.java:120`), reached on the netty loop from `CQLMessageHandler.java:392` (V5+) and
`PreV5Handlers.java:96` (pre-V5). Today it ends in `executor.submit(new RequestProcessor(...))` (`:143`).

Routing inserts *before* that submit:

```
route = tryRouteToShard(channel, request)          // null on any non-routable / uncertain case
if (route != null)  ShardExecutors.instance().execute(route.locals, route.shard, new RequestProcessor(...))
else                requestExecutor.submit(new RequestProcessor(...))   // unchanged path
```

The routed task **is the same `RequestProcessor`** — no new coordinate code. `RequestProcessor.run` calls
`processRequest`, which the design-target's owner-inline apply bypass (I1) then keeps on this thread.
The only new loop work is `tryRouteToShard`: the key extraction (§3) + shard compute + allowlist (§4).

This is the direct analogue of I5's `ShardInboundRouter` at `InboundMessageHandler.java:429`, moved to
the native path. Loop-side/shard-side split mirrors I5 (§7).

---

## 3. Cheap, ingress-throw-safe key extraction (`ExecuteMessage` only)

The token is unknowable at decode, but for a **prepared** `ExecuteMessage` the partition key is
recoverable on the loop without parsing or executing:

1. **Cache-policy-neutral** prepared lookup (⟵FABLE). The prepared-statements cache is a Caffeine built
   with `.executor(ImmediateExecutor.INSTANCE)` and a `removalListener` that calls
   `SystemKeyspace.removePreparedStatement` — a system-table **write** (`QueryProcessor.java:144-148,169`).
   Under `ImmediateExecutor`, Caffeine maintenance (incl. size eviction) runs on the *calling* thread, so a
   normal `getPrepared` on the loop can trigger that write under cache pressure — a **block the catch-all
   cannot save** (it doesn't throw). The loop must use a policy-neutral read (`preparedStatements.asMap()
   .get(id)` — no maintenance, no eviction, no LRU touch); the LRU touch happens on the shard-side
   `getPrepared` in `execute`. Needs a new accessor (e.g. `QueryProcessor.getPreparedNoTouch`). Also route
   only when `ClientState.getCQLQueryHandler()` is `QueryProcessor` — a custom `custom_query_handler_class`
   runs arbitrary code on the loop (`:365`). Null / non-QueryProcessor / miss → fall back.
2. `prepared.statement.getPartitionKeyBindVariableIndexes()` — the `short[]` of PK-component bind indexes,
   returned iff every PK component is a bind marker (`CQLStatement.java:49`; `VariableSpecifications.java
   :90-109`; `ModificationStatement.java:287`; shipped to drivers, `ResultSet.java:602`). Null/empty (key
   not fully bound) → fall back. Nit: `VariableSpecifications` recomputes it with two allocs per call —
   cache the `short[]` on `QueryHandler.Prepared` to avoid per-request loop allocation (the class step-1
   removed). — ⟵FABLE nice-to-have.
3. Pull those indexes from `options.getValues()` (the bound `ByteBuffer`s, `ExecuteMessage.java:112`); build
   the key (single index → the buffer; composite → `CompositeType.build`). Any `UNSET`/short list → fall back.
   **Named-values guard** (⟵FABLE): `OptionsWithNames.getValues()` asserts `orderedValues != null`
   pre-`prepare()` (`QueryOptions.java:636-639`) — an `AssertionError` (an `Error`, not `Exception`). The
   router's `catch (Throwable)` handles it, but add an explicit `instanceof OptionsWithNames → fall back`
   *before* touching values (the enclosing `CQLMessageHandler.processRequest` catches only `Exception`,
   `:399` — never let extraction rely on a slip-through).
4. `TableMetadata` from `prepared.statement`; keyspace/CFS via **`Schema.instance.getKeyspaceInstance` +
   `ColumnFamilyStore.getIfExists`** — the null-returning pattern `MutationShardRouting.route` already uses
   (⟵FABLE), NOT `Keyspace.open` (which asserts on a dropped keyspace, `Keyspace.java:142-148`, and
   mishandles virtual tables). Then `dk = metadata.partitioner.decorateKey(key)`; `shard = cfs
   .getCurrentMemtable().getShardBoundaries().getShardForKey(dk)` — **the identical computation the apply's
   owner-check uses** (design-target §1), so route and apply agree mod N. Any null/throw → fall back.

**Ingress-throw safety (the #1 build hazard, `feedback_ingress_throw_kills_connection`):**
`tryRouteToShard` is wrapped in one `try { ... } catch (Throwable t) { return null; }`. Every lookup is
null-checked; the method **never throws and never blocks** (all steps are map/array reads — no CQL parse,
no I/O, no schema fetch). A null return means "not routed," i.e. the unchanged NTR path — never a dropped or
mis-answered request. A throw on this loop would close the connection and leak capacity; the catch-all
forbids it. This matches I5's loop-side discipline.

**`QueryMessage` (unprepared) is NOT routed.** Recovering its key needs a CQL parse — expensive and
throw-prone on the loop — so unprepared queries keep the 3-hop NTR path. Production drivers prepare;
`QueryMessage` writes are the rare, acceptable-to-not-optimize tail. Batches (`BatchMessage`) are excluded
by the allowlist (§4), independent of prepared-ness.

---

## 4. The routing predicate (allowlist) — single-partition local-replica writes only

**Governing principle (⟵FABLE):** ingress routing moves the *whole coordinate* to the shard thread, so the
predicate needs **two** hazard lists — design-target §5's **apply**-hazard list (below, part A) **plus** a
**coordinate**-hazard list §5 was never scoped to cover (part B), because I1 only routed the apply. Route
**iff all** hold; otherwise fall back. Correctness never depends on routing (design-target §1).

**Part A — apply hazards (reuse design-target §5 verbatim, do not re-derive):**
- **Prepared `ExecuteMessage`** (§3); handler is `QueryProcessor`; not `OptionsWithNames`. — else fall back.
- **`ModificationStatement`** (INSERT/UPDATE/DELETE), **not** a `BatchStatement`, **without conditions**
  (`statement.hasConditions()` false — LWT excluded). — else fall back.
- **Exactly one partition key** resolved in §3 (single-partition; multi-partition `IN` / batched keys fall
  back). — else fall back.
- **Table is routable** per design-target §5: not a view / MV-bearing, not counter, not `cdc=true`, not
  legacy-2i-bearing, not a local-system keyspace, memtable is sharded. Source of truth = design-target §5 /
  phase-4 §1 `MutationShardRouting`.
- **`commitlog_sync=periodic`** (startup-checked when the flag is on; batch/group park the shard inside the
  inline commitlog add — design-target §3 item 4). Flag-on + non-periodic → routing disabled globally.

**Part B — coordinate hazards (NEW; the blockers that reach a shard thread only because coordinate moves
there):**
- **No triggers on the table** — `TriggerExecutor.instance.execute(mutations)` runs arbitrary user code
  inline on the coordinate path (`StorageProxy.java:1310,1358`); the UserCodeEscape pool (design-target §3
  item 3) is unbuilt. Exclude `!metadata.triggers.isEmpty()`.
- **Partition denylist off (or the key permitted without a fetch)** — `partitionDenylist.isKeyPermitted`
  (`StorageProxy.java:385,1347`) does a synchronous **distributed read** on a cache miss (`PartitionDenylist
  .java:198-204,422`). Default-off; the predicate/global gate checks `getPartitionDenylistEnabled() &&
  getDenylistWritesEnabled()` → fall back if on.
- **Not a transient-replication / `additional_write_latency` keyspace** — the flip did NOT fully de-park
  coordinate: `maybeTryAdditionalReplicas` (`StorageProxy.java:1098`) parks on `writeResult.await(...)`
  (`AbstractWriteResponseHandler.java:521`) whenever `uncontacted` is non-empty. Empty for the PoC's RF=3
  full-replica keyspace (all naturals contacted), but non-empty under transient replicas / rapid-write
  protection → would park the shard. Exclude those keyspaces (or gate that await off on the routed path).
- **This node is a natural replica for the key.** Route only when there is a local apply to make inline; a
  pure coordinator forwarding to remote replicas gains no inline apply and only adds shard load — fall back.
  Check: token → write plan (naturals + pending, conservatively) `contains(self)`. Trivially true
  single-node; load-bearing multi-node.

A mismatch between the routed shard and the shard the apply later computes (epoch bump / memtable switch mid
flight) is **safe**: `currentThreadIsOwnerOf` returns false on the wrong shard, the apply takes the
tryLock fallback (design-target §1), and only the optimization is lost. `misroutedPuts` counts it (§11).

---

## 5. Coordinate-on-shard stays non-blocking at RF≥3

Running coordinate on the shard thread is safe **only** because the flip already made the write path
async. At RF≥3 the shard thread, inside `performLocally`/`mutate`, must:

- **Send remote MUTATION_REQ legs** via `MessagingService.send` — thread-safe from shard threads
  (design-target §8.2 hop 6, verified for I1's ack path). Enqueue-only, no block.
- **Apply locally inline — BUILD ITEM, not reuse (⟵FABLE).** `performLocally` today *unconditionally*
  re-submits the apply to the shard executor (`StorageProxy.java:2399-2410`) — the owner-inline bypass
  exists only on the internode path (`db/MutationVerbHandler.java:99`, `if (ShardExecutors
  .currentThreadIsOwnerOf(shardId))` → run inline). The native route must **add** the same guard at
  `performLocally`'s dispatch: `if (currentThreadIsOwnerOf(shardId)) run inline; else shards.execute(...)`.
  `CURRENT_SHARD` is set for the whole routed task (`shardTagged`, `ShardExecutors.java:135-148`), so the
  guard answers true trivially once added. This is what collapses hop B to an inline call; without it the
  apply self-enqueues (§1).
- **NOT block on the QUORUM await.** The flip drives completion off `AbstractWriteResponseHandler`'s
  callback with a scheduled timeout (flip commit `3ccf35950e`); the shard thread returns after dispatch and
  the `ResultMessage` is produced when acks arrive. The shard thread **never** calls `handler.get()` on the
  QUORUM wait. **Residual park (⟵FABLE):** `maybeTryAdditionalReplicas` still contains a real
  `writeResult.await(...)` (`AbstractWriteResponseHandler.java:521`) that fires for transient-replica /
  `additional_write_latency` keyspaces — excluded by §4 part B, so it cannot reach a routed shard thread.
  (`feedback_rf3_assumption_poc_validity`: RF≥3-correct via the flip + that exclusion.)

Thread-locals (tracing, client warnings, message params, client state) cross the loop→shard hop via
`ShardExecutors.execute(ExecutorLocals, shard, task)` (`:127`; executors are `localAware()`), and are
restored in the flip's callback exactly as they are today across NTR→completion (flip commit). No new
capture machinery — the route reuses the flip's.

**Remaining shard-thread blocking risks, and their guards (mirrored from I5, `i5-findings:47-50`):**
- **CDC block / batch-group fsync** — excluded by predicate (§4), so they can't reach a shard thread.
- **Memtable-pool allocation park** (`MemtableAllocator` `hasRoom`) — a pre-existing shard-thread hazard
  the hostiles design owns (design-target §8.1 HOSTILE #4); not introduced here.
- **Epoch-ahead TCM catch-up** — not a native-path concern (no inbound message epoch); the coordinator uses
  `ClusterMetadata.current()`. No guard needed here (unlike I5's internode `:48`).

---

## 6. Backpressure / capacity accounting (the `requestExecutor` bypass)

Bypassing `requestExecutor` skips its queue-time backpressure signal
(`requestExecutor.oldestTaskQueueTime()`, `Dispatcher.java:386`, used by `hasQueueCapacity`) and its
`ClientMetrics` active/pending accounting. The routed path must not become an unaccounted admission hole.

**The hole (⟵FABLE):** `hasQueueCapacity()` reads *only* `requestExecutor.oldestTaskQueueTime()`
(`Dispatcher.java:380-387`). Routed traffic empties that queue, so `Overload.QUEUE_TIME` — both the
throw-on-overload arm and the warning arm (`CQLMessageHandler.java:210,239`) — can **never fire** however
deep the shard inboxes get. What still protects: per-request shedding at `processRequestAsync:524-531`
(`startedAtNanos` is stamped when the shard picks the task up, so inbox age is measured on completion) and
bytes-in-flight capacity. So without a fix, overload degrades from *early backpressure* to *late shedding*.

**Decision (specified, not deferred):** the loop-side capacity ACQUIRE + native-transport queue-time check
stay the authority and run **before** the route decision, AND `hasQueueCapacity()` is extended to include
the **max shard-inbox oldest-task age** across shard executors (or, equivalently, a per-shard route gate:
inbox depth/age over a bound → fall back to NTR rather than routing onto a saturated shard). A routed
request's queue is then a first-class backpressure input, not a blind spot. Per-shard `ThreadPoolMetrics
PendingTasks` (design-target §1, free via `withJmx`) is the observable; the I5 coupling still applies
(`i5-findings:51`): capacity is held while queued, so a hot shard lengthens holds and can head-of-line-block
other requests on its connection. `ClientMetrics.markRequestDispatched()` (`:144`) fires on both paths.
Note the observability gap: `tpstats` Native-Transport-Requests goes dark for routed traffic — routed
counts must be exported separately.

---

## 7. Mechanism reuse from I5 — what carries over, what is CQL-specific

I5 (`net/ShardInboundRouter.java`, built + Fable-reviewed) is the internode sibling. Reuse its **shape**;
do not reuse its **code path** (different ingress: `InboundMessageHandler` vs `Dispatcher`).

| I5 (internode) | This increment (CQL native) |
|---|---|
| Route in `InboundMessageHandler` at `:429` on the netty loop | Route in `Dispatcher.dispatch` at `:120` on the netty loop |
| Key from a fully-deserialized small `MUTATION_REQ` | Key from prepared `ExecuteMessage` bind indexes (§3) |
| Loop-side: capacity, deserialize, `route()`; shard-side: apply | Loop-side: capacity, key extract, `route()`; shard-side: coordinate + apply |
| `ShardExecutors.execute(ExecutorLocals, shard, task)` | same overload (`:127`) |
| Owner-inline bypass for self-enqueue | same (`currentThreadIsOwnerOf`, `:110`) |
| Guards: epoch-ahead, FORWARD_TO → stage fallback | epoch/FORWARD_TO N/A (native path); allowlist + local-replica is the CQL guard set |
| Fallback: inbox-full → `stage.execute` | Fallback: non-routable/uncertain → `requestExecutor.submit` |

I5 is single-node-inert (a single-node write never reaches `InboundMessageHandler`, `i5-findings:41`);
this increment is the single-node-*active* sibling — they are complementary, not overlapping. The
multi-node RF=3 write exercises both: I5 on the two remote MUTATION_REQ legs, this on the coordinator-local
apply.

---

## 8. Why the shard-aware client protocol is decoupled, later, and smaller here

design-target §0 states the loop→NTR (arrival→shard) hop's "removal requires the out-of-scope shard-aware
protocol." §1 shows that is an **overstatement**: it assumed the token is unknowable until after parse+bind
on the coordinator thread, missing the prepared-`ExecuteMessage`-key-on-loop path, which removes the hop
*server-side*. Correcting that, the protocol's real role under **this design's kept-Netty decision**
(§3.2) is narrower than §10's "zero in-node hop" row implies:

- **Under kept-Netty, per-shard ports do not cut hop count single-node.** A shard-aware driver connecting
  to shard-i's port still lands on a *netty loop*, not the shard thread — so the request still pays a
  loop→shard hop and a shard→loop flush. Per-shard ports buy **loop↔shard core affinity** (cache-hot
  handoff), not hop deletion. This design already gets 2 hops server-side (§1); the protocol does not get
  fewer under kept-Netty.
- **True 0-hop (Scylla's number) needs the shard thread to own its client socket** — but that is reachable
  **without** reversing §3.2 (⟵FABLE, correcting this section's first draft). §3.2 rejects *Seastar-style
  transport takeover* (userspace TCP/DPDK), not shard socket ownership per se. The netty-native path:
  make each **shard executor BE a netty `EventLoop`** and register that shard's per-shard-port channels on
  it, so the socket-owning thread and the shard thread are the same thread → coordinate + apply + flush all
  on one thread, **0 hops**, Netty still the network layer. It changes the shard's blocking budget (a
  stalled shard stalls its own sockets — exactly Scylla's trade) and is CEP-era, but it is the recorded
  destination of hop C, not an impossibility.

**Consequence for sequencing:** the single-node parity lever is server-side loop-routing (this doc), not
the protocol. Build server-side now (deletes hops A/B today, zero client change). The shard-aware protocol
is a CEP-era item; its payoff is **larger than affinity** — paired with shard-as-EventLoop it removes hop C
too (0-hop) — which strengthens "protocol later," not weakens it. §9 records shard-as-EventLoop as hop C's
destination; §10's "zero in-node hop" is reachable under kept-Netty, just not in PoC scope.

---

## 9. Scylla-mapping (§10 style; influence, not gospel — `feedback_scylla_endgame_influence`)

| Decision here | Scylla's answer | Status + influence |
|---|---|---|
| Route on the netty loop; coordinate+apply on the shard; **hop C (flush) kept** | Shard reactor owns socket + storage → 0 hops | **TRANSITIONAL.** This design deletes hops A/B server-side; hop C is the kept-Netty residual. Recorded destination of hop C = **shard-as-EventLoop** (shard executor IS a netty `EventLoop` owning per-shard-port channels → 0 hops, Netty kept; §8) — reachable without §3.2 reversal, CEP-era. |
| Server-side key extraction from prepared metadata (no client change) | Shard-aware driver computes token→shard client-side | **DIVERGENT-BY-SEQUENCING.** We extract server-side because Netty stays (§3.2); the protocol is the later affinity lever, not the hop-deletion prerequisite §0 implied. |
| Allowlist = single-partition local-replica writes; everything else falls back | All ops shard-routed under the reactor | **TRANSITIONAL.** Cassandra's owner-check-with-lock-fallback (§1) lets us route incrementally; Scylla routes everything because it has no fallback path to preserve. |
| Coordinate-on-shard non-blocking via the flip's async await | Reactor futures/continuations throughout | **ALIGNED-IN-SHAPE.** The flip is Cassandra's continuation substrate; coordinate-on-shard is the same "never park a core" rule. |
| §0 "one inbox hop needs the protocol" | connection-per-shard, zero hop | **CORRECTED.** Under kept-Netty the protocol yields affinity, not zero hop; §10's row is Scylla's unified-reactor number — annotate as unreachable here without §3.2 reversal. |

---

## 10. Build sketch (Phase 2 — ONLY if the gate says build; flag-gated, flag-off byte-identical)

- **Flag:** `cassandra.tpc.cql_ingress_routing` (`CassandraRelevantProperties`), default off. Flag-off:
  `dispatch()` is byte-identical (no `tryRouteToShard` call).
- **`tryRouteToShard(channel, request)`** on the loop: the §3 extraction (policy-neutral prepared lookup,
  QueryProcessor-only, named-values guard, `getIfExists` keyspace/CFS) + §4 predicate parts A **and** B, one
  `catch (Throwable)→null`. Returns `{shard, locals}` or null. New class beside the route, analogue of
  `ShardInboundRouter`.
- **`dispatch()` edit:** the §2 branch — route or `requestExecutor.submit` — plus the §6 admission ordering
  (ACQUIRE/queue-time before the branch) and the §6 shard-inbox term in `hasQueueCapacity`.
- **`performLocally` owner-inline bypass (NEW build item, ⟵FABLE):** add the `currentThreadIsOwnerOf`
  guard at `StorageProxy.java:2399-2410`, mirroring `db/MutationVerbHandler.java:99`. Without it hop B does
  not collapse.
- **`QueryProcessor.getPreparedNoTouch` (NEW, ⟵FABLE):** `preparedStatements.asMap().get(id)` — a
  cache-policy-neutral read for the loop. Optionally cache the PK bind-index `short[]` on `Prepared`.
- **Reuse, do not rebuild:** `RequestProcessor` (unchanged), `ShardExecutors.execute(locals, shard, task)`
  (`:127`), the flip's thread-local capture/restore + async completion.
- **Counters:** routed-count and fallback-count **by reason** (not-prepared, not-QueryProcessor,
  named-values, not-single-partition, excluded-table [triggers/denylist/transient/MV/…], not-local-replica,
  shard-inbox-full, extract-threw), exposed like I5's — the fallback-reason histogram is the profile's
  primary diagnostic.

## 11. Validation (Phase 3)

- **Pre-build, day 1 (⟵FABLE):** run waker→wakee context-switch attribution on the *existing* baseline
  (`perf sched` / offwaketime) to decompose cs/op ~2.1 by seam. This answers the doc's biggest open risk
  (is the loop→NTR seam even where the cs lives?) in hours, before any build, and calibrates the expected
  win. Every later measurement needs this decomposition anyway.
- **Gate on seam-attributed evidence, NOT a fabricated cs/op target (⟵FABLE).** The 3→2 claim is about
  thread contexts; the hops amortize differently (hop C is CAS-coalesced, `Dispatcher.java:124-130`; NTR's
  SEP target spins sub-µs before parking, `i5-findings:22`; a per-write timer is scheduled onto the loop,
  `AbstractWriteResponseHandler.java:178-181`). So the pass bar is: the loop→NTR seam's cs contribution
  **disappears** in the attribution, and a flamegraph shows coordinate frames on `Shard-N`, not the NTR
  pool. Report the resulting cs/op and %sys as *outcomes*, not against a guessed ~1.4.
- **Per-shard utilization + loop self-cost (⟵FABLE):** record per-shard executor utilization (is coordinate
  serializing a shard? the PLAUSIBLE risk) and the loop-frame self-cost of `tryRouteToShard` (Caffeine get +
  key build + `decorateKey` + boundaries + the replica check — I5 budgeted its loop-side route work,
  `i5-findings:45`; single-node the replica check is trivially true).
- **Flag-off regression-clean** (byte-identical dispatch).
- **RF=3 single-node correctness:** the node is a replica for all keys; assert routed writes fan out to the
  two remote legs async and `misroutedPuts` stays flat.
- **Test must not be vacuous (⟵FABLE):** in-JVM dtest `coordinator().execute()` bypasses `Dispatcher`
  entirely (the same trap as `i5-findings:40`) — flag-on validation needs a **real native-transport client**
  (CQLTester `requireNetwork`, or a driver), else the route point is never exercised.
- **Sign honesty (from I5, `i5-findings:22`):** at low load `sequential()` park (~1–10µs) can exceed the
  saved NTR SEP schedule; at saturation affinity + the removed hop win. Gate on mechanism evidence + tail
  neutrality, never a headline p99. Multi-node is I5's track, not this one.

## 12. Risk register (post-Fable)

**Resolved by the Fable pass (folded above):**
1. *Is hop A the dominant cs/op term?* → RESOLVED-METHOD: the pre-build seam attribution (§11) answers it
   with a number before build; the gate is seam-attributed, not a guessed cs/op.
3. *Double prepared-cache lookup correctness window?* → CONFIRMED SAFE: eviction between the loop and shard
   lookups yields `PreparedQueryNotFoundException` → normal re-prepare, never a misroute.
4. *Backpressure hole?* → RESOLVED: signal genuinely lost; the §6 fix (shard-inbox age in `hasQueueCapacity`
   / per-shard route gate) closes it.
6. *Kept-Netty 0-hop trick?* → RESOLVED: shard-as-EventLoop reaches 0-hop with Netty kept (§8/§9).

**Open — carried to Phase 2/3:**
2. **Shard serialization of coordinate under load** (PLAUSIBLE, empirical). At 180k/12 ≈ 15k/shard/s the
   ~66µs/op budget fits coordinate+apply; at the higher parity loads the program targets, coordinate on N
   threads vs. today's up-to-128 NTR workers could bind first. Gated by §11's per-shard utilization; the
   §4-partB local-replica-only + light-coordinate predicate is the mitigation. Correctly gated, not a
   blocker.
5. **Fuzz the loop-side extraction.** `CompositeType.build` + the bind-index pulls run behind a single
   `catch (Throwable)`; fuzz malformed/edge `ExecuteMessage` values to prove no input blocks, corrupts, or
   slips a mis-decode past the guard. (Named-values `AssertionError` already guarded in §3.)
