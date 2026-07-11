# Task: re-measure Cassandra throughput with correct (server-side) methodology

> ⛔ **SUPERSEDED / CLOSED (2026-07-10).** This plan's "client-reported throughput is ~2×
> inflated" claim is ALSO FALSE — client output matches `nodetool` in the clean regime.
> True: the value-gen fix is a red herring. Canonical understanding:
> `tasks/tpc-migration-planning/phase-4-poc/{STRESS-RUNBOOK,stress-tool-behaviour}.md` and
> the corrected `findings.md` in this dir. Do not act on the text below.

**Status:** the "value-generator fix" chased in the parent session is a RED HERRING (server-side
A/B: unfixed 132k vs fixed 139k w/s = noise, not 14×). The real finding: **client-reported
throughput is ~2× inflated; must measure server-side.** See findings.md TL;DR. Rig is UNFIXED/clean.

## Why this matters
Every throughput number in `tasks/tpc-migration-planning/phase-4-poc/poc-criteria.md` §8
(`baseline_v1`) and in the parent hurdle log A16 was **client-reported → ~2× inflated / unreliable**.
The whole "load-gen limited to 16k, Cassandra idle" story was a measurement artifact. Server-side,
the tool already drives ~132–140k w/s and loads Cassandra to ~61% CPU. Re-baseline from scratch
with the correct method.

## Phase 1 — pin the methodology
- [ ] **Ground-truth throughput = server-side only:** `nodetool tablestats <ks> | Local write
      count` (and read count) delta over a fixed wall-clock window while load runs. NEVER trust
      the client stdout "1min req/s" or Count/duration — it ran ~2× high here.
- [ ] Cross-check with `nodetool proxyhistograms` (coordinator latency) + MutationStage/ReadStage
      Active/Pending for saturation, and mpstat on the Cassandra fence for CPU.
- [ ] Decide the honest offered-load model (the rate-ladder in the parent driver reported client
      numbers — re-derive its rungs against server-side achieved).

## Phase 2 — find Cassandra's real ceiling (options per user: cheaper value, then more cores)
- [ ] Sweep 1→N stress processes (client cores 8–11) at high `--rate`, measuring SERVER-side
      write rate + Cassandra CPU + MutationStage. Find where Cassandra saturates (cores ~100% or
      MutationStage Active→32 Pending building) vs where the CLIENT caps.
- [ ] Is the value generator even worth touching? At ~135k w/s Cassandra is 61% CPU. Only if the
      CLIENT is proven the cap (server has headroom, client cores pegged) is value-gen cost worth
      revisiting — and measure it SERVER-side (the build-once change gave ~5%, likely not worth it).
- [ ] Option 2 (more client cores by shrinking Cassandra) only if the client caps below Cassandra
      saturation — trade-off in parent hurdles A16 (fewer Cassandra cores = less TPC-representative).
- [ ] Off-box load gen only if the 12-core box genuinely can't saturate Cassandra server-side.

## Phase 3 — RE-BASELINE (correct, server-side method)
- [ ] Re-capture `baseline_v1` curves (parent `baseline_driver.sh`) measuring SERVER-side
      throughput; supersede the client-reported §8 numbers.
- [ ] Re-examine the read/write delta and any real server-side write ceiling once load is driven
      hard and measured correctly (the earlier "write path serialization" was already retracted).
- [ ] Closes Phase 4.1 → I0/I1.

## Known-good stress parameters (empirically settled 2026-07-09)
Canonical invocation (throughput / saturation):
```
taskset -c <CLIENT_CORES> cassandra-easy-stress run KeyValue \
  --host 127.0.0.1 --prometheusport 0 --no-schema \
  --readrate <0.0..1.0> --partitions 2000000 --threads 32 \
  --rate 2000000 --queue 2000000 --duration <D>s
```
- **`--prometheusport 0`** — MANDATORY for scripted/concurrent runs (else :9500 bind collisions,
  hurdle A2 → silent rc=0 no-work failures).
- **`--rate`** — DEFAULT is 5000 (a hard cap). For max/saturation set "unlimited" (`2000000`);
  for a latency-at-target-load cell set it to the intended offered rate. A huge rate causes
  coordinated omission → client p99 meaningless; get latency from `proxyhistograms` / rate-
  controlled cells.
- **`--queue`** — defaults to `rate*2`; set explicitly (`2000000`) when overriding rate.
- **`--threads 32`** — fine; more did NOT help (not thread-bound).
- **connections: LEAVE DEFAULT.** `--max-connections`(8)/`--max-requests`(32768) already give
  262k in-flight; raising them does nothing (measured 18→130 conns, flat).
- **Driver `NETTY_IO_SIZE`** not a flag; default `cores×2` fine (raising didn't help).
- **`--no-schema`** to reuse a pre-populated keyspace; populate once with
  `--populate $((TOTAL/threads))` (PER-THREAD, hurdle A3) + `--rate 2000000`.
- **MEASURE SERVER-SIDE:** throughput = `nodetool tablestats <ks>` Local write/read count delta
  over a fixed window. Client stdout throughput ran ~2× HIGH — do NOT use it.
- **Single-client ceiling ≈ 132–140k w/s** at these params (client cores ~97%, Cassandra ~61%).
  To load Cassandra harder: add processes / client cores — NOT connections or rate.

## Pointers
- Full investigation + measurements: `findings.md` (this dir). The fix: `the-fix.diff`.
- Parent hurdle log (A1–A16, incl. the A16 corrections): `../tpc-migration-planning/phase-4-poc/hurdles.md`.
- Rig facts + stress-tool gotchas: memory `feedback_easy_cass_stress_scripted_run_gotchas`,
  `feedback_easy_cass_stress_hdr_semantics`, and `../tpc-migration-planning/runbook.md`.
- Load-test probe on rig: `/data/tpc-poc/lt.sh`.
