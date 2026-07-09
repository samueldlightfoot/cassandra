# Phase 4.1 RE-BASELINE — handoff for a clean context

**Write date:** 2026-07-09. Self-contained: you should not need the originating session's context.

---

## 0. AUTONOMOUS RUN — READ FIRST (operator is asleep; do NOT ask questions)
You are running unattended overnight. **Never block on a question; decide per the rules here and
proceed.** Your job: put Cassandra under real load, capture a trustworthy throughput+latency
baseline server-side, write it into `poc-criteria.md` §8, and commit. Work in this repo
(`/Users/samlightfoot/repos/fork/cassandra`) on branch `tpc-migration`; commit as you go.

**Ground rules**
- SSH key-auth: `ssh -o StrictHostKeyChecking=no root@157.180.98.112`. No password anywhere.
- **Reboot guard:** the box auto-reboots for kernel upgrades. Check `uptime` before each phase; if
  SSH dies with "connection refused", it rebooted — wait, re-launch Cassandra (§3), continue.
- **Every pkill uses the bracket self-exclusion trick** or you SIGKILL your own SSH shell:
  `pkill -9 -f "Cassandra[D]aemon"`, `pkill -9 -f "cassandra-easy-stress-.*-all[.]jar"`,
  `pkill -9 -f "[G]radleDaemon"`. (Hurdle A6.)
- **Long runs:** launch the driver with `setsid ... </dev/null >log 2>&1 & disown` so it survives
  SSH drops (hurdle A5); poll a log with a background waiter; grep for FATAL + populate/cell
  emptiness, not just success (monitor-silence lesson).
- **Measure throughput SERVER-SIDE** (§5). rc=0 ≠ success — assert cells did real work.
- Keep each cell's raw output under `/data/tpc-poc/results/rebaseline/`; rsync locally at the end.
- Tool is `cassandra-easy-stress` at `/root/repos/cassandra-easy-stress` (UNFIXED/clean — do NOT
  apply any Random.kt "fix"; it's a dead end).

**Pre-decided choices (do NOT deviate without logging why in progress notes):**
1. Saturation signal = **Cassandra fence CPU (cores 0–7) ≥ 85% avg**. (MutationStage Active is
   NOT reliable for writes — apply is ~11µs so the stage never queues; at 231k w/s it was still
   Active=0. Use CPU, plus Pending>0 if it ever appears.)
2. To load harder, **stack client PROCESSES** on cores 8–11, each at `--rate 2000000` (a single
   process delivers ~231k w/s max, client-core-bound). Sweep procs 1→6.
3. **If client cores 8–11 hit ≥90% before Cassandra cores 0–7 reach 85%** → the client is the cap.
   Re-pin live: `taskset -a -pc 0-5 $(pgrep -f 'Cassandra[D]aemon')` (Cassandra→6 cores 0–5),
   client→cores 6–11 (6 cores), and re-sweep. Log this as a config change in the results.
4. If even 6 client cores can't drive Cassandra 0–5 to 85% CPU → record the honest client-limited
   ceiling and proceed to Phase B at that ceiling (don't spin forever; note "off-box gen may be
   needed" and move on).
5. Value/workload: default KeyValue value (`random(100,200)`), `--partitions 2000000`,
   `--threads 32`. **START WITH A FRESH POPULATE** — the keyspace currently holds ~15GB of
   accumulated test overwrites (skewed sstables); drop + repopulate for a clean read dataset:
   `cassandra-easy-stress run KeyValue --host 127.0.0.1 --prometheusport 0 --replication
   "{'class':'SimpleStrategy','replication_factor':1}" --populate $((2000000/32)) --readrate 0.0
   --partitions 2000000 --threads 32 --rate 2000000 --duration 1s --drop` (note `--populate` is
   PER-THREAD, hurdle A3), then `nodetool flush cassandra_easy_stress` + wait for compactions to
   drain (`nodetool compactionstats` pending→0). Use `--no-schema` for all measurement cells after.

**Concrete plan:** §6 Step A (saturation discovery) → Step B (baseline curves at 3–4 load levels
below saturation, 3 iters, server-side throughput + proxyhistograms latency) → §6 Step C (rewrite
`poc-criteria.md` §8, commit). Then append a progress note to `../progress.md` and stop.

---

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

## 6. WHAT TO RE-RUN (the action — concrete, autonomous)
First: preflight (§3), confirm Cassandra up + on cores 0–7 + keyspace populated (re-populate if
`Local write count`≈0). Build a driver script on the rig (adapt `baseline_driver.sh` — swap its
client-stdout throughput parse for the §5 SERVER-side method: bracket each cell with `lwc`/`lrc`
count reads; fix its mpstat parse: `mpstat -P ALL 1 2`, match `Average`, CPU field `$3`). Launch it
`setsid`-detached, monitor a log. Cells are 40s each (server-side rate over the middle 30s), 3
iters where noted.

**Step A — saturation discovery (find the load that loads Cassandra).**
For each mix (write `--readrate 0.0`, balanced `0.5`, read `0.9`): run **P = 1,2,3,4,5,6 processes**,
each `taskset -c <client-cores> ... --rate 2000000 --queue 4000000 --duration 40s`, concurrently.
Per P record: SERVER-side total throughput (Local write+read count delta), **Cassandra cores 0–7 avg
CPU**, client cores avg CPU, MutationStage/ReadStage Active+Pending. Stop increasing P when Cassandra
cores 0–7 ≥ **85%** (= saturated; note the throughput = `MAX[mix]`) OR client cores ≥ 90% first
(client-bound → apply pre-decision §0.3: re-pin Cassandra to 0–5, client to 6–11, restart Step A for
that mix). If 6 procs on 6 client cores still can't reach 85% Cassandra CPU, record the client-limited
ceiling as `MAX[mix]` and move on (§0.4). Known anchor: 1 proc ≈ 231k w/s @ Cassandra ~61% CPU.

**Step B — reference curves (the baseline itself).**
For each mix, at the saturating config from Step A, capture p99-vs-throughput at **4 load levels ≈
{40, 60, 80, 95}% of MAX[mix]** (dial load by process count and/or `--rate`; verify achieved
server-side). 3 iters each. Per cell record: server-side achieved throughput, `nodetool
proxyhistograms` (coordinator R/W p50/p95/p99/p99.9, µs = the authoritative latency), client
`--csv-latency`/`--hdr` (labelled client-observed), Cassandra + client CPU, error count (must be 0),
GC log overlay. These curves ARE the new baseline.

**Step C — write results + commit.**
Rewrite `poc-criteria.md` §8 with the server-side curves; state the load config (procs, cores,
rates) and whether the ceiling is Cassandra (85% CPU) or client. Re-examine the read/write delta
with correct numbers. rsync `/data/tpc-poc/results/rebaseline/` → `phase-4-poc/rebaseline-results/`
(gitignored). Commit `poc-criteria.md` + driver. Append a progress note to `../progress.md`.

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
