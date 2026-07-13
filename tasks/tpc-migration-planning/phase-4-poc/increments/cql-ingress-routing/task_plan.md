# Task plan — CQL-path ingress routing (single-node inbox hop)

Status: **NEW — design-first, not started.** Framing + the crux in `findings.md`. Read that +
`progress.md` (handoff) first.

## Goal

Decide and (if justified) build: route the CQL native-protocol request at the native-transport
inbound path to the owning shard thread, so the coordinator's local-replica apply runs inline on the
owner — the single-node parity lever that steps 1-3 could not reach. **Parity target = the pinned PoC
criterion: p99 ≤ trunk at throughput ≥ trunk** (`poc-criteria.md`).

## Phase 0 — orient + pin the crux (no code)

- [x] Read prior art in `findings.md` order (design-target inbox-hop + §10; i5 sibling; shard-dispatch
      baseline; the flip). Restated the as-built native path and the exact single-node hop.
- [x] Pin **the crux** (findings.md): **SETTLED** — verified 3-hop reality on branch; server-side
      loop-routing DELETES the loop→NTR hop (3→2) for prepared single-partition local writes, no client
      protocol change. The premise's "2 hops / pure relocation" conflated the netty loop with the NTR pool.

## Phase 1 — Fable design pass (the deliverable of this increment; GATES all build) — DONE

- [x] Wrote `design-cql-ingress-routing.md`: route point in `Dispatcher.dispatch` on the loop; cheap
      ingress-throw-safe key extraction from prepared `ExecuteMessage`; allowlist (§4 parts A apply-hazards
      + B coordinate-hazards); coordinate-on-shard non-blocking via the flip + owner-inline apply;
      §10-style Scylla map; build sketch; validation.
- [x] **Settled the crux**: recommend **build server-side now** (deletes hops A/B, zero client change);
      shard-aware protocol decoupled + later (buys 0-hop via shard-as-EventLoop, larger than affinity).
- [x] **Fable adversarial critique** — verdict **PROCEED-WITH-CHANGES**; 6 required changes verified at
      source and folded (owner-inline bypass = build item; coordinate-hazard predicate; policy-neutral
      prepared lookup; seam-attributed validation; backpressure fix; §8 shard-as-EventLoop correction).
- [ ] **DECISION GATE (user sign-off):** build server-side routing now, pursue the shard-aware protocol,
      or stop here with the design recorded. **← WE ARE HERE.** Do not build before this gate.

## Phase 2 — build skeleton — BUILT (2026-07-13, compiles + unit-tested)

- [x] Route decision point in `Dispatcher.dispatch()` on the netty loop (analogue of `ShardInboundRouter`).
      New flag `cassandra.tpc.cql_ingress_routing` (`CQL_INGRESS_ROUTING`, default off); `CqlShardRouter
      .ENABLED` = flag AND `MutationShardRouting.ROUTING_ENABLED`. Flag-off ⇒ ENABLED false ⇒ byte-identical
      NTR-pool submit.
- [x] `transport/CqlShardRouter.java` — ingress-throw-safe (`catch(Throwable)→fallback`) key extraction:
      policy-neutral `QueryProcessor.getPreparedNoTouch` (asMap, no eviction write on loop), single-column
      PK via `getPartitionKeyBindVariableIndexes`, coordinate-hazard predicate (LWT/conditions, counter,
      triggers, local-system, denylist-write-gate, transient replication), shard via
      `MutationShardRouting.shardForKey`. Non-QueryProcessor handler + named-values → fallback.
- [x] Dispatch to owner via `ShardExecutors.execute(ExecutorLocals.current(), shard, RequestProcessor)`;
      **owner-inline apply bypass added to `StorageProxy.performLocally`** (mirrors `MutationVerbHandler:99`)
      — collapses hop B. `getPreparedNoTouch` added to `QueryProcessor`; `shardForKey` to `MutationShardRouting`.
- [x] Verified: full `ant build` SUCCESSFUL; `MutationShardRoutingTest` 9/9 (incl. 2 new `shardForKey`
      tests); `ShardRoutedMutationApplyTest` 1/1 (performLocally change regression-clean).
- **Skeleton scope (noted):** single-column PK only (composite = fallback, follow-up); §6 shard-inbox
      backpressure term in `hasQueueCapacity` NOT yet added (Phase 3 hardening); apply-hazards (view/CDC/2i)
      deferred to `performLocally`'s authoritative check by design (loop only guards coordinate hazards).
- **Not locally testable (i5-findings:40 — in-JVM bypasses `Dispatcher`):** routeShard firing end-to-end
      and the owner-inline bypass actually collapsing hop B need the real native path → Phase 3 rig.

## Phase 3 — validate — DONE (2026-07-13, rig `157.180.98.112`) — PASS

- [x] **Bug found + fixed on the rig** (unit tests vacuous): unregistered metric group `CqlShardRouting`
      → `<clinit>` throw killed every native request. Fixed by reusing the `Client` group (mirrors I5).
      Uncommitted in `transport/CqlShardRouter.java`.
- [x] **(a) Route fires on native path:** `CqlIngressRouted` 0 → 8.38M ≈ 100% of delivered writes.
- [x] **(b) Writes read back correct** (cqlsh sample + COUNT; well-formed key/value).
- [x] **(c) Hop deleted:** SharedPool switch share 43.5% → **0.0%** (`sched:sched_switch` matrix, exact
      baseline metric); wakeup rollup hopA/hopB → 0.0%. cs/op 1.69 → 1.154.
- [x] **(d) Flag-off regression-clean:** same jar flag-off → Routed=0, SharedPool back to 45.0%, throughput
      + CPU restored.
- [x] **(e) Shards not saturated:** Blocked=0, ~74% CPU / 26% idle; coordinate folded on (shard share
      40.9%→50.7%) without backlog. Tail neutral-to-favorable (client p99 191 on ≤ 234 off).
- Crux was already settled server-side (no shard-aware client needed); §8 records the protocol as the
      later 0-hop lever. Full evidence + same-jar A/B table in `progress.md` (Session 2026-07-13 later).

## Phase 3 hardening — follow-ups (not gating)

- [ ] Commit the metrics fix + the Phase-2 build (both uncommitted on `shard-dispatch-overhead`).
- [ ] Composite-PK routing (skeleton is single-column-PK only); §6 `hasQueueCapacity` shard-inbox term.
- [ ] If a throughput headline is wanted: matched-saturation A/B sweep (Phase-3 gated on mechanism +
      tail-neutrality, not a throughput number — the hand-aligned windows here were within noise).

## Not doing / out of scope

- Re-doing I5 (internode inbound dispatch) — built; its open item is the multi-node RF=3 perf gate,
  tracked in `i5-inbound-shard-dispatch/`.
- Reads at ingress (I2 territory — read-miss-blocks-shard hazard).
- Building the shard-aware client driver — that's a protocol+driver effort; this increment only decides
  whether it is the prerequisite and scopes it.
