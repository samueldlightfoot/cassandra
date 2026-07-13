# Task plan — CQL-path ingress routing (single-node inbox hop)

Status: **NEW — design-first, not started.** Framing + the crux in `findings.md`. Read that +
`progress.md` (handoff) first.

## Goal

Decide and (if justified) build: route the CQL native-protocol request at the native-transport
inbound path to the owning shard thread, so the coordinator's local-replica apply runs inline on the
owner — the single-node parity lever that steps 1-3 could not reach. **Parity target = the pinned PoC
criterion: p99 ≤ trunk at throughput ≥ trunk** (`poc-criteria.md`).

## Phase 0 — orient + pin the crux (no code)

- [ ] Read prior art in `findings.md` order (design-target inbox-hop + §10; i5 sibling; shard-dispatch
      baseline; the flip). Restate the as-built native path and the exact single-node hop.
- [ ] Pin **the crux** (findings.md): does server-side CQL ingress routing delete a hop single-node, or
      only relocate work unless the client is shard-aware? This gates everything below.

## Phase 1 — Fable design pass (the deliverable of this increment; GATES all build)

- [ ] Write a design doc (`design-cql-ingress-routing.md`) covering:
      - The route decision point on the native inbound loop; how the partition key is obtained
        cheaply (prepared `ExecuteMessage` key vs `QueryMessage` parse) **without throwing on the loop**
        (`feedback_ingress_throw_kills_connection` — null-return + Stage fallback).
      - The allowlist: single-partition writes to a local replica only; everything else (batch, range,
        LWT, read, multi-partition, RF≥3 non-self) falls back. Correctness never depends on routing.
      - How coordinate-on-the-shard-thread stays non-blocking at RF≥3 (build on the flip's async
        remote-replica await; the shard thread must never block on the QUORUM wait).
      - The §10-style Scylla-mapping table; deviations with reason.
- [ ] **Settle the crux**: server-side-only vs shard-aware-client-protocol-first vs both/ordering.
      Quantify the server-side-only win (affinity + inline apply, no per-apply dispatch alloc) vs its
      risk (shard thread now runs coordinate → bottleneck). Recommend build/defer/protocol-first.
- [ ] **Fable adversarial critique** of the design (high-leverage, hard-to-reverse hot path — the
      justified Fable spend per `feedback_fable_expensive_reserve_high_leverage`). Verdict + changes.
- [ ] **DECISION GATE (user sign-off):** build server-side routing now, pursue the shard-aware protocol,
      or stop here with the design recorded. Do not build before this gate.

## Phase 2 — build skeleton (ONLY if Phase 1 says build; flag-gated, flag-off byte-identical)

- [ ] Route decision point at the native inbound loop (analogue of `ShardInboundRouter`): allowlist +
      ingress-throw-safe key decode + fallback. New flag (e.g. `cassandra.tpc.cql_ingress_routing`).
- [ ] Dispatch the request to the owning shard via `ShardExecutors.execute(ExecutorLocals, shard, …)`;
      owner-inline bypass when already on the owner. ExecutorLocals/tracing/warnings captured across the
      route (the flip already threads these — reuse).
- [ ] Guards mirrored from I5 where they apply (epoch-ahead, anything that would block the shard).

## Phase 3 — validate

- [ ] Single-node rig CPU at matched ~180k vs the Step-1 baseline (**flip+step1 ≈ 67.4%**, off 58.5%):
      does the hop actually shrink — coordinate+apply on one thread, apply inline, cs/op down? Frame-level
      self-cost fold + cs/op + %sys, per the shard-dispatch methodology. Flag-off regression-clean.
- [ ] If the win needs the shard-aware client (crux), record that the server-side-only number is the
      ceiling without it, and hand the protocol decision back to the user.

## Not doing / out of scope

- Re-doing I5 (internode inbound dispatch) — built; its open item is the multi-node RF=3 perf gate,
  tracked in `i5-inbound-shard-dispatch/`.
- Reads at ingress (I2 territory — read-miss-blocks-shard hazard).
- Building the shard-aware client driver — that's a protocol+driver effort; this increment only decides
  whether it is the prerequisite and scopes it.
