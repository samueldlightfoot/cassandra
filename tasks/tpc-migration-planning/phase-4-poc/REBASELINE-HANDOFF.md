# Phase 4.1 RE-BASELINE — handoff for a clean context

**Write date:** 2026-07-09. **Read this first, act on §6.** Self-contained: you should not
need the originating session's context.

## 1. Why we're re-running (CORRECTED premise)
`cassandra-easy-stress` client output is **reliable** — verified: at sane offered rates the client
count matches `nodetool` Local write/read count **to the digit** (40k, 50k, 100k rungs all ratio
1.00). The earlier "client is ~2× inflated" claim was WRONG — it was an artifact of driving the
tool at `--rate 2000000` (extreme overload), where the client's periodic reporting breaks down.
Client numbers are trustworthy at non-overload rates.

**The real problem: `baseline_v1`'s offered `--rate` was far too low, so Cassandra was never
loaded.** The tool delivers only ~0.1–0.25× of the nominal `--rate`, so the baseline's rungs
(offered 10k–130k) delivered only ~5–30k w/s and MutationStage stayed idle at every point — the
"clean_max write ~16k / bal ~22k / read ~73k" figures are accurate LOW-LOAD points, NOT Cassandra's
capacity. Achieved-vs-offered measured (server-side, writes): 40k→5k, 200k→27k, 800k→98k, 2M→231k;
MutationStage idle throughout up to ~100k+. So re-run with **much higher `--rate` (or multiple
client processes)** to actually push Cassandra toward saturation, and pick gate operating points
where Cassandra — not the client — is the thing under test.

**This is a scoped redo of the NUMBERS + offered-load levels, not the framework.** Full corrected
load-gen investigation: `../../easy-cass-stress-perf-fix/findings.md` (note: its "14× value-gen
fix" is a DEAD END — ignore).

## 2. What's INVALID (redo) vs VALID (keep)
| Invalid → redo | Valid → keep as-is |
|---|---|
| `poc-criteria.md` §8 throughput figures + curves | Config pins (trie memtable MBean-proven, RF=1, periodic commitlog, disk_access_mode standard, 16G G1 heap, auto_snapshot off) |
| clean_max per mix + 50/80% operating points | CPU-fence method + environment capture (§4) |
| read/write delta magnitude conclusions | The 15 hurdles (`hurdles.md`) incl. A16 corrections |
| Any client-stdout throughput number anywhere | The gate CONCEPT (D9): increment p99 ≤ trunk at matched load |
|  | rate-ladder DESIGN (just needs server-side measurement) |

## 3. Rig access + current state
- SSH (key auth, no password): `ssh -o StrictHostKeyChecking=no root@157.180.98.112`.
- **Cassandra:** `/root/repos/fork/cassandra-tpc` (branch tpc-migration@ae53c542dc = trunk-equiv on
  the serving path). Run with `CASSANDRA_CONF=/data/tpc-poc/conf`. Should be UP, pinned to cores
  **0–7**, listening `127.0.0.1:9042`. Data `/data/tpc-poc`, commitlog `/commitlog/tpc-poc`.
  Verify: `CASSANDRA_CONF=/data/tpc-poc/conf bin/nodetool info | grep Uptime`.
- **Keyspace:** `cassandra_easy_stress`, table `keyvalue`, ~2M partitions populated (may need
  re-populate — see §5). Confirm trie memtable still active:
  `nodetool sjk mxdump | grep -c 'type=TrieMemtable'` (>0).
- **Load gen:** `/root/repos/cassandra-easy-stress`, branch `feature/csv-latency`, **UNFIXED/clean**
  (`git diff` empty — the "value-gen fix" was a red herring, do NOT reapply). PATH wrapper
  `cassandra-easy-stress` → `build/libs/*-all.jar`. Client fence = cores **8–11**.
- Preflight (hurdles A2/A6/A7/A8): `pkill -9 -f "easy-stress-.*-all[.]jar"; pkill -9 -f
  "[G]radleDaemon"`; assert `:9500` free; only `CassandraDaemon` java proc; governor=performance.

## 4. Known-good stress parameters (settled — don't re-derive)
```
taskset -c 8-11 cassandra-easy-stress run KeyValue \
  --host 127.0.0.1 --prometheusport 0 --no-schema \
  --readrate <0.0|0.5|0.9> --partitions 2000000 --threads 32 \
  --rate <R> --queue 2000000 --duration <D>s
```
- `--prometheusport 0` MANDATORY (else :9500 collisions → silent rc=0 no-work; hurdle A2).
- `--rate`: default **5000** is a hard cap (A12). Use `2000000` for saturation; a specific value
  for a fixed-offered-load latency cell.
- connections: **leave default** (8×32768 = 262k in-flight; raising them is flat — A16). Don't touch.
- `--threads 32` (more doesn't help). `--no-schema` to reuse the keyspace.
- populate (if needed): `--populate $((2000000/32))` (PER-THREAD! A3) `--rate 2000000 --drop`.

## 5. Measurement rule
Client stdout throughput is FINE at non-overload rates (matches server exactly). But **cross-check
with server-side** and use it as the authority when pushing hard (client reporting degrades under
extreme overload, e.g. `--rate 2M`):
```
lwc(){ nodetool tablestats cassandra_easy_stress | awk '/Local write count/{print $NF}'; }
B=$(lwc); <run cell>; A=$(lwc); echo "server write rate = $(( (A-B)/duration ))/s"   # brackets the run
```
Per cell also capture: `nodetool proxyhistograms` (coordinator R/W latency, µs — server truth),
`nodetool tpstats | grep -E '^(Mutation|Read)Stage'` (Active/Pending = the SATURATION signal — the
baseline never got these off idle), mpstat on Cassandra cores 0–7 (CPU headroom). The offered rate
must be high enough that MutationStage/ReadStage actually build and/or Cassandra CPU climbs — that's
the whole point of the re-run. Keep client `--csv-latency`/`--hdr` for latency but avoid overload
rates where latency becomes coordinated-omission noise.

## 6. WHAT TO RE-RUN (the action)
**Step A — bottleneck map (new, load-bearing).** For each mix (write r=0, balanced r=0.5, read
r=0.9), sweep **process count** 1→N (each `taskset -c 8-11`, `--rate 2000000`) and record
server-side throughput + Cassandra CPU (0–7) + MutationStage Active/Pending + client CPU (8–11).
Goal: find where **Cassandra saturates** (cores→~100% or MutationStage Active→32 with Pending
building) vs where the **client** caps first. Known start point: 1 client ≈ 135k w/s, Cassandra
~61% CPU — so ~2 procs or a core shift likely saturates it. If the client caps before Cassandra:
apply option 2 (shrink Cassandra to e.g. 0–5, client 6–11 — trade-off recorded in hurdles A16),
re-map. Off-box load gen only if the 12-core box genuinely can't saturate Cassandra.

**Step B — reference curves (the actual baseline).** Once you know the load config that makes
Cassandra the bottleneck (or the honest client ceiling), capture, per mix, server-side p99-vs-
throughput at a few controlled offered rates below saturation, 3 iters for a noise band. This
replaces `poc-criteria.md` §8. Reuse/adapt `baseline_driver.sh` (rate-ladder) BUT swap its
client-stdout parse for the §5 server-side method; fix its mpstat parse (`mpstat -P ALL 1 2`,
match `Average`, CPU is `$3`). The rig probe `/data/tpc-poc/lt.sh` already reports throughput +
MutationStage + fence CPU (its mpstat parse needs the same fix).

**Step C — rewrite `poc-criteria.md` §8** with server-side numbers; note the load config used and
whether the ceiling is Cassandra or client. Re-examine the read/write delta with correct numbers
(the earlier "write-path serialization" claim was already retracted — see hurdles A16).

## 7. Done criteria
- §8 repopulated with SERVER-side throughput + proxyhistogram latency + a noise band, 0-error cells.
- The load config that saturates Cassandra (or the honest client-limited ceiling) is documented.
- Gate operating points chosen where Cassandra — not the client — is the thing under test, OR the
  client-limited caveat is explicit (so TPC server-side gains aren't silently invisible).
- Then Phase 4.1 is genuinely closed → proceed to I0/I1 (increments.md build order).

## 8. Pointers
- Corrected load-gen investigation + ruled-out table: `../../easy-cass-stress-perf-fix/findings.md`
  (the `the-fix.diff` there is a DEAD END — ignore).
- Baseline driver + config method: `poc-criteria.md`, `baseline_driver.sh` (this dir).
- Hurdle log (A1–A16, all corrections): `hurdles.md`.
- Rig facts: `../runbook.md`. Stress gotchas: memory `feedback_easy_cass_stress_scripted_run_gotchas`.
- Increment build order (after 4.1 closes): `../phase-3-execution-model/increments.md`.
