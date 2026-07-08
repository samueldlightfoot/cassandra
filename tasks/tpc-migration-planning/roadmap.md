# Roadmap — from here to a working TPC architecture

Written 2026-07-07; reframed same day, **PoC-first**: the goal is a working PoC that
shows performance — the CEP follows with those numbers. High-level only; authority lives
in the phase specs.

## Build the capability

1. **Build the io_uring binding** (`phase-1-uring-binding/spec.md`): greenfield JNA layer
   in `org.apache.cassandra.io.uring` — sync facade, ring lifecycle, batched QD64,
   O_DIRECT + fixed buffers, fsync — all tests green on the rig.
2. **Benchmark it** (`phase-2-benchmark/spec.md`): fio ground truth vs JMH-through-binding
   on ext4 + XFS. Gates classify the PoC's I/O shape — ring-per-shard vs a small I/O
   helper pool; no outcome kills the program.
3. **Choose the designs** (`phase-3-execution-model/spec.md`): shard model (incl. skew
   stance, Accord coexistence, Paxos), non-blocking coordinator, hostile remediations,
   increments I1–I5 = the PoC build order. Buildable decisions, not surveys.

## Build the PoC (phase 4, in the fork)

4. **Pin baseline + success criteria before any increment code**: trunk numbers on the
   rig; "shows performance" = p99 ≤ trunk at throughput ≥ trunk (exact margins in
   `poc-criteria.md`).
5. **I1 — shard-routed mutation apply**: per-shard threads keyed by the existing
   `ShardBoundaries`; owner-check skips the memtable lock, tryLock fallback stays for
   non-routed writers (hints/repair/replay/Accord); `contendedPuts`+`misroutedPuts`
   are the health proof.
6. **I2 — shard-routed local reads**: same routing plus a per-shard io_uring ring for
   cache-miss chunk reads (the binding's first production consumer).
7. **I3 — non-blocking coordinator**: parked-Condition awaits become continuations
   (executor choice open: REQUEST_RESPONSE vs completion pool); the 128-thread NTR
   pool loses its reason to exist.
8. **I4 — per-shard write machinery**: per-shard commitlog (per the 3.3 decision) and
   per-shard `writeOrder`.
9. **I5 — inbound shard dispatch**: internode READ/MUTATION verbs go straight from the
   netty loop to shard inboxes, bypassing Stage.
   *Each of 5–9: implementation spec → build behind flag → tests flag-on → macro+micro
   A/B, tail-gated → hurdle log updated.*
10. **PoC verdict**: full-system A/B vs the step-4 criteria, per-increment attribution,
    honest tail analysis; `hurdles.md` complete — finding hurdles is the stated point.

## Take it upstream (phase 5)

11. **CEP with the PoC evidence**: draft mapped to the required sections, socialize with
    the named reviewers, `[DISCUSS]` on dev@ (expect months), `[VOTE]`.
12. **Land the increments as CEP phases**, now carrying the production freight deferred
    from the PoC (mixed-version, JMX compat, upgrade paths). End-game SEP/stage removal
    only after a release cycle of flag-on evidence.
13. **Revisit the conscious defers** — HOSTILE #5 (per-shard caches), shard-aware client
    protocol, maintenance-on-shard — only if post-I5 measurements say the bottleneck
    moved there.

## Standing rules

- Gates classify hurdles; they do not kill the build. The hurdle log is a first-class
  deliverable of the whole program.
- Every increment is flag-gated, tail-gated (p99, not throughput — the 2016 POC's
  +15%/2×-p99 lesson), and mergeable-shaped so PoC patches convert to CEP patches
  without a rewrite (the 2016 PoCs died unlanded; shape is the defense).
