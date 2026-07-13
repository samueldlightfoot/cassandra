# vs-TRUNK measurement plan — CQL ingress routing PoC criterion

**Goal:** answer the actual PoC criterion (`poc-criteria.md` §1): **is routing-ON p99 ≤ trunk at
throughput ≥ trunk?** Everything measured so far is vs flip+step1, not trunk.

## Decisions (user-confirmed 2026-07-13)
- **Trunk baseline = stock trunk `50ddce8455`** (= local `trunk` tip = the exact fork point this whole
  branch forked from; zero drift; no io/uring, no TPC source). The handoff's "parent-of-flip
  `55a1a71f0a`" was WRONG — that still carries I1+I5+step1 (verified: `ShardExecutors.java` +
  `MutationShardRouting.java` present). Serving-path-identical to `poc-criteria.md §2`'s declared
  trunk-equivalent (which only differed from trunk by the inert io/uring spike).
- **Arms = TRUNK then ROUTING-ON, same session, back-to-back** (same rig/loadgen/day; no cross-session
  drift in the headline comparison). Flag-off (flip+step1) already characterized; not re-run.

## Toolchain / safety pins
- Build trunk in a **SEPARATE clone** `/root/repos/fork/cassandra-trunk` (NOT `build-i1.sh` — it
  `rm -rf`s the repo and would nuke the live routing jar + backups in `cassandra-tpc-i1/build/`).
- Same rig JDK/ant as the routing jar (a macOS-built jar would risk a compiler confound).
- `50ddce8455` not on the shallow rig repo → `git fetch --depth 1 origin 50ddce8455` in the fresh clone.
- Verify the trunk jar is really trunk: `unzip -l | grep -E 'CqlShardRouter|ShardExecutors|MutationShardRouting|io/uring'` → MUST be empty.
- Verify `lib/` identical between clones (TPC work added no deps) before jar-swap; else run trunk from its own BASE.

## Steps
0. **[done]** Confirm baseline lineage + rig state.
1. **Build trunk** (background): fresh depth-1 clone → fetch+checkout `50ddce8455` → submodule → `ant realclean jar`.
   Verify jar mtime fresh + trunk-only (no TPC/io-uring classes).
2. **Preserve live routing jar**: `cp build/…jar build/…jar.routing-validated` (handoff §7).
3. **Provision loadgen**: off-box ccx43 hel1, cloud-init builds easy-cass-stress (`agent-common/rig/cloud.md`;
   token `.secrets/hcloud.token`, `HCLOUD_TOKEN` env). CPU-fenced, off-box. **Delete when done.**
4. **Calibrate** one clean sub-knee rate (~180k): delivered≈offered, ~0 CO drops. `--rate 180000
   --concurrency 3000 --threads 32 --readrate 0.0 --hdr`. Saturation arm: `--rate 350000 --concurrency 4000 --threads 40`.
5. **Arm TRUNK**: swap jar `…jar.trunk`→`…jar`; comment out the two TPC flags in
   `/data/tpc-poc/conf/jvm-server.options` (trunk ignores them; comment for cleanliness). `prep_flip`
   (NB: it will read `shard_pools=OFF` on trunk — EXPECTED, not a failure). Loadgen ≥400s run →
   3× `abwin.sh <tag> 40` 60s windows + 1 saturation window. Pull client `--hdr`/steady console p99.
6. **Arm ROUTING-ON**: swap `…jar.routing-validated`→`…jar`; restore both flags; `prep_flip`; SAME protocol.
7. **PoC read**: routing-ON p99 ≤ trunk p99 AND routing-ON peak deliverable ≥ trunk peak? Report mean±range.
8. **Teardown**: restore live routing jar as `…jar`, both flags ON, node UP routing-ON; **delete loadgen**;
   write results into `perf-ab-methodology.md` §RESULTS-VS-TRUNK + `progress.md`. Commit notes on `tpc-migration`.

## Harness gotchas (handoff §8 — do not repeat)
- NO `asprof`/`perf` inside the CPU window (inflated busy% ~14pp). `abwin.sh` is already clean (mpstat+vmstat only).
- Each ecs invocation is a COLD JVM → whole-run `--hdr` p99 is cold-contaminated; use steady console p99 or a
  long run + mid-run window. The 3rd 60s window overruns a 300s loadgen → use ≥400s run for 3 windows.
- FRESH `prep_flip` per arm (matched JVM + table state). Table-state matters at saturation, not sub-knee.
- p99: flip records NO `ClientRequest.Write.Latency`; trunk DOES. Do NOT compare trunk-server-p99 to
  routing-client-p99. Use CLIENT `--hdr`/steady console p99 for BOTH arms (common denominator).
