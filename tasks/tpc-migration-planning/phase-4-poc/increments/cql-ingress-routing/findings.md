# Findings — CQL-path ingress routing (single-node inbox hop)

Origin: 2026-07-13. The `shard-dispatch-overhead` increment reduced the single-node routed-write CPU
residual (flip 71.0% → ~67.4% @ 180k vs off 58.5%) but concluded steps 1-3 hit **diminishing
returns** — Step 2's on-CPU allocation prize is small, Step 3 (wakeup batching) can't cut cs/op
without a forbidden spin band-aid. The structural single-node parity lever is **ingress routing**:
route the request to the owning shard thread so coordinate + apply run on one thread, deleting the
cross-thread apply hop. This increment scopes that for the CQL native path. **Design-first, Fable-led,
before any code** (hard-to-reverse hot path).

## What is already built vs. what this increment is

Ingress routing = ScyllaDB's shard-per-core model: bind work to the core that owns the data; forward
over a lock-free queue if it lands elsewhere.

- **BUILT (do not redo): "I5 — inbound shard dispatch"** routes *internode* `MUTATION_REQ` at the
  messaging `InboundMessageHandler` to the owning shard. Designed, Fable-reviewed (PROCEED-WITH-CHANGES),
  implemented (`net/ShardInboundRouter.java`, Phase A/B1/B2/B3 committed), counters exposed. **Inert
  single-node** — a single-node write takes `StorageProxy.performLocally` (isSelf) and never reaches
  `InboundMessageHandler` (`i5-inbound-shard-dispatch/findings.md:41`). Its open work is the multi-node
  RF=3 3-arm perf gate, NOT design. This increment does not touch it.
- **THIS increment (new): the CQL native-protocol inbox hop.** Route the client's CQL request at the
  native-transport inbound path to the owning shard thread, so the coordinator's local-replica apply
  runs inline on the owner (no `performLocally`→ShardExecutor hop). This is design-target.md's "inbox
  hop from its arrival thread to its owning shard," whose end-state is a **shard-aware client protocol**
  (Scylla-style per-shard ports + `SCYLLA_NR_SHARDS` handshake). NOT built.

## The single-node hop, precisely (what we'd be deleting)

Today (flip + I1 routing), one single-node write:
1. Native-transport (NT) event-loop thread receives `QueryMessage`/`ExecuteMessage`.
2. NT thread runs the full coordinator (parse/prepare, replica plan, `mutate`→`performLocally`) and
   dispatches the apply to the owning `ShardExecutor` — **hop 1 (NT → shard)**.
3. Shard thread applies (memtable + commitlog), completes the write handler; the flip's async callback
   produces the response, flushed on the NT event loop — **hop 2 (shard → NT flush)**.
≈ 2.1 cs/op. Step 1 removed the routed-specific `getEntryAfterMiss` (CURRENT_SHARD JDK-TL probe) and
the per-write keyspace-name allocation; the residual is the two handoffs + async plumbing (~2.3%
irreducible) + the apply itself (shared with off).

## THE CRUX Fable must settle FIRST (before any build)

**Does CQL ingress routing actually delete a hop single-node, or only relocate work — unless the
client is shard-aware?**

- **Without a shard-aware client:** the request arrives on an arbitrary NT thread ≠ the owning shard.
  Routing the whole request NT → owning shard *replaces* the "NT→shard apply hop" with an "NT→shard
  request hop" — **same hop count**. What changes: coordinate + apply run on one thread (cache/TL
  affinity, apply inline so no per-apply dispatch object/promise), but the shard thread now also does
  the coordinator work → it can bottleneck if coordinate is heavy. **Uncertain, possibly-negative
  single-node win.**
- **With a shard-aware client** (per-shard ports; driver connects to the owning shard directly): the
  request *arrives on the owning shard's* event loop → coordinate + apply + response all on one thread,
  **0 cross-shard hops** — the real Scylla parity win. Requires a native-protocol extension + driver
  support (or a server-advertised shard map the driver honours).

So the design question is not "how to route" but **"is server-side CQL ingress routing worth building
without the shard-aware client protocol, or is the protocol the actual prerequisite?"** Fable should
pressure-test whether to (a) build server-side routing now (measure the affinity/inline-apply win),
(b) go straight for the shard-aware protocol, or (c) both, and in what order.

## CRUX VERDICT (settled 2026-07-13, verified on branch) — the premise above was wrong

The framing above (and design-target §0) undercounted the hops: it treated coordinate as running on
"the NT thread." It does not. `Dispatcher.dispatch()` runs on the netty loop and hands off to
`requestExecutor` — a **separate** NTR pool (`Dispatcher.java:143`; `processRequest` is "not expected to
execute on the netty event loop", `:663`). The flip stopped that thread parking; the loop→NTR handoff is
still a real hop. **Today's single-node routed write is 3 hops (loop→NTR→shard→loop), not 2.**

Consequently **server-side CQL routing DOES delete a hop single-node** — the loop→NTR hop — **when routed
on the netty loop via cheap prepared-`ExecuteMessage` key extraction** (the token IS knowable on the loop
from `getPartitionKeyBindVariableIndexes()`, `CQLStatement.java:49`, shipped to drivers already). 3→2
hops, no client protocol change. "Pure relocation" is true only for the *route-after-parse+bind-on-NTR*
variant, which nobody should build. The shard-aware protocol is **decoupled and later**: under kept-Netty
(design-target §3.2) per-shard ports buy affinity, not fewer hops; true 0-hop needs shard socket
ownership, which the PoC rejects. **User direction 2026-07-13: build server-side, protocol later.**
Full analysis + mechanism: `design-cql-ingress-routing.md`. Fable critique: PROCEED-WITH-CHANGES (6
source-verified fixes folded).

## MEASURED confirmation (2026-07-13, off-box rig, GREEN-LIGHT) — `measure-seam-attribution.md`

Pre-build seam attribution (`perf sched:sched_switch`, 214k delivered write-only, 74.2% CPU): the
NTR/coordinate pool (`SharedPool-Work`) is involved in **43.5% of all context switches (0.735/op)** of
cs/op ≈ 1.69. CQL routing bypasses that pool for routed writes (coordinate folds onto the shard's
already-scheduled apply run; switches-into-Shard stay ~0.383/op). Estimated net saving ~0.35–0.7 cs/op
(~20–40%) — far above the ≳0.4–0.5 green-light bar. Bounded risk (Phase-3 gate): folding coordinate onto
shards raises shard CPU. **The hop IS where the cost lives → build justified.**

## Hazards / constraints (pinned — memories)

- **Ingress-throw kills the connection** (`feedback_ingress_throw_kills_connection`): work pulled onto
  the netty inbound loop must NOT throw (closes the connection + leaks capacity, unlike inside a verb
  handler). The key-decode-to-compute-owning-shard step runs on the loop → null-returning lookups +
  try/catch → Stage fallback. This is the #1 build hazard.
- **RF=3 is the assumption even single-node** (`feedback_rf3_assumption_poc_validity`): the coordinator
  still fans out to remote replicas at RF≥3. Running coordinate on the shard thread must NOT block the
  shard on the remote-replica await (the flip already made that await async — build on it).
- **Correctness never depends on routing**: a wrong/absent shard id must fall back (Stage / lock), never
  miscompute. Only single-partition writes to a local replica route; batch / range / LWT / read /
  multi-partition fall back.
- **Async medium = Netty Futures** (`feedback_async_medium_netty_futures`): `AsyncPromise`/`AsyncFuture`,
  not RxJava/CompletableFuture.
- **Scylla = influence, not gospel** (`feedback_scylla_endgame_influence`): map each decision to Scylla's
  model in a §10-style table, deviate with reason.

## Prior art to read (do not start from a blank page)

- `phase-3-execution-model/design-target.md` — the **inbox hop** (client arrival thread → owning shard),
  the **shard-aware client protocol** vision (per-shard ports / `SCYLLA_NR_SHARDS`), and the §10
  Scylla-mapping table. THE governing design doc for this.
- `i5-inbound-shard-dispatch/{findings,task_plan,progress}.md` — the internode sibling: the
  `ShardInboundRouter` pattern, the loop-side/shard-side split, the ingress-throw fix, the guards
  (epoch-ahead, FORWARD_TO), the owner-inline bypass. Reuse the mechanism shape.
- `shard-dispatch-overhead/{findings,progress}.md` — why single-node hop optimization plateaued (the
  reason this increment exists); the rig baseline (67.4% @ 180k) any single-node win is measured against.
- `nonblocking-write-path/findings.md` — the flip (async coordinator): the request already returns a
  future and the remote-replica await is async — the substrate CQL ingress routing runs coordinate on.
- `increments.md` — the I1→I5 sequence and the RF=3 pivot rationale.

## As-built seams this builds on

- Native path: `transport/Dispatcher.java` (`processRequest`/`processRequestAsync` — the flip), the
  `Message.Request.execute` chain, `ModificationStatement.execute`. The route decision point would sit
  at the native inbound loop (analogue of `ShardInboundRouter` for internode).
- `concurrent/ShardExecutors.java` — `execute(ExecutorLocals, int shard, Runnable)`, `currentShardId`,
  `currentThreadIsOwnerOf` (owner-inline bypass). Step 1 made `CURRENT_SHARD` a FastThreadLocal.
- Shard-from-key: token → owning shard mapping (the same computation I1/I5 use; the memtable shard
  index vs the executor shard — reconcile which "shard" the native route targets).
