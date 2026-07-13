# Progress — CQL-path ingress routing (single-node inbox hop)

## Start-of-context handoff (2026-07-13) — design not started

This increment was spun up when `shard-dispatch-overhead` concluded that single-node CPU steps 1-3
hit diminishing returns and the structural parity lever is **ingress routing**. User picked the target:
**design the CQL-native-path single-node inbox hop, Fable-led, before any code.** Do NOT redo I5
(internode inbound dispatch — already built + Fable-reviewed; inert single-node).

### State inherited (verify before trusting — memory recalls reflect write-time)

- **Branch:** `shard-dispatch-overhead` (off `tpc-nonblocking-write`, not pushed). Carries: the flip
  (async coordinator write path), I1 (local-apply shard routing: `ShardExecutors`,
  `currentThreadIsOwnerOf`), I5 (internode inbound dispatch: `net/ShardInboundRouter.java`), and Step 1
  (per-write alloc/threadlocal reductions: `SchemaConstants` allocation-free check + `CURRENT_SHARD`
  FastThreadLocal). `git log --oneline` for the exact commits.
- **Rig `157.180.98.112`:** UP running the flip+step1 jar (sha `19e44ac9`, routing ON), autocompaction
  disabled on `cassandra_easy_stress.keyvalue` (restart resets it). Loadgen deleted (provision fresh,
  hourly). Deploy recipe + capture scripts: `shard-dispatch-overhead/task_plan.md` "Verify" +
  `/root/{prep_flip,rig_capture,verify_flip}.sh`. Single-node routed-write baseline to beat:
  **flip+step1 ≈ 67.4% CPU @ 180k, off 58.5%, cs/op ~2.1.**
- **I5 status:** built (Phase A/B1/B2/B3), Fable-reviewed; open work = multi-node RF=3 3-arm perf gate
  (separate, in `i5-inbound-shard-dispatch/`). Not this increment.

### The one thing to get right FIRST (the crux — findings.md)

**Does server-side CQL ingress routing delete a hop single-node, or only relocate work unless the
client is shard-aware?** Without a shard-aware client the request arrives on an NT thread ≠ the owning
shard, so routing NT→owner *replaces* the apply hop with a request hop (same count) — it relocates
coordinate onto the shard + makes the apply inline (affinity + less alloc), but risks bottlenecking the
shard with coordinator work. The true hop-deletion needs the **shard-aware client protocol** (per-shard
ports / `SCYLLA_NR_SHARDS`, or a server-advertised shard map the driver honours). **The design's central
job is to decide whether server-side routing is worth building without that protocol, or whether the
protocol is the actual prerequisite.** Everything else (route point, key-decode, allowlist, fallback)
is mechanism that only matters after this is settled.

### Reading order for the fresh context

`findings.md` (this dir — framing, the crux, hazards, prior-art pointers) → `task_plan.md` (this dir —
phases; Phase 1 Fable design is the deliverable, Phase 2 build is GATED on it) → then the prior art in
findings.md order: `../../phase-3-execution-model/design-target.md` (inbox hop + shard-aware protocol +
§10 Scylla map — THE governing doc), `../i5-inbound-shard-dispatch/findings.md` (the internode sibling's
mechanism to reuse), `../shard-dispatch-overhead/findings.md` (why we're here + the rig baseline),
`../nonblocking-write-path/findings.md` (the flip = the async substrate).

### Pinned hazards (memories — do not rediscover)

`feedback_ingress_throw_kills_connection` (key-decode on the loop must not throw → null-return +
Stage fallback — the #1 build hazard), `feedback_rf3_assumption_poc_validity` (coordinate-on-shard must
not block on the RF≥3 QUORUM await — build on the flip's async await), `feedback_async_medium_netty_futures`,
`feedback_scylla_endgame_influence`, `feedback_fable_expensive_reserve_high_leverage` (Fable IS justified
here — hard-to-reverse hot-path design).

## Start prompt (paste into a fresh context)

> Design CQL-path ingress routing — single-node inbox hop. New increment
> `tasks/tpc-migration-planning/phase-4-poc/increments/cql-ingress-routing/`. Read its `progress.md` →
> `findings.md` → `task_plan.md`, then `../../phase-3-execution-model/design-target.md` (inbox hop +
> shard-aware client protocol + §10 Scylla map) and `../i5-inbound-shard-dispatch/findings.md` (the
> already-built internode sibling — reuse its mechanism, do NOT redo it). Context: single-node routed-write
> CPU steps 1-3 hit diminishing returns (flip+step1 ≈ 67.4% @ 180k vs off 58.5%); the structural parity
> lever is routing the CQL request to the owning shard at native ingress so coordinate+apply run on one
> thread. FIRST settle the crux with me (findings.md): does server-side routing delete a hop single-node,
> or only relocate work unless the client is shard-aware (per-shard ports)? THEN produce a design doc
> (route point on the native inbound loop; ingress-throw-safe key decode + Stage fallback; allowlist =
> single-partition local-replica writes only; coordinate-on-shard non-blocking at RF≥3 via the flip's
> async await; §10 Scylla map) and run a Fable adversarial critique of it. Build is Phase 2 and is GATED
> on a user decision after the design + crux verdict — do NOT write production code before that gate.
> Branch off `shard-dispatch-overhead`.
