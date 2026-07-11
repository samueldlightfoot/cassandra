# OFFBOX-HANDOFF — Phase 4.1 baseline on a separate load box

**Write date:** 2026-07-10. Supersedes `START-HERE-baseline-6core.md` (co-located path —
now closed: proven that a co-located generator can't cleanly baseline Cassandra on this box;
see `progress.md` 2026-07-10 and the evidence table there).

**⚠️ CORE-COUNT CORRECTION:** the box is a Xeon E-2276G = **6 PHYSICAL cores / 12 HT threads**
(single socket, ONE shared 12 MiB L3, single NUMA node). Earlier "12 cores" text conflated HT
threads with cores. "Cassandra keeps all cores" = all **6 physical** cores. This changes load-box
sizing (below) and raises a deeper caveat — see `LOADGEN-DECISION-ANALYSIS.md`.

**⚠️ READ FIRST:** `LOADGEN-DECISION-ANALYSIS.md` (same dir) — the adversarial analysis of
*whether* to buy a load box at all. Its conclusion may redirect this spend toward a larger
**Cassandra** box. Don't provision off-box until that scope decision is made.

**Status: BLOCKED on the user's scope decision (see analysis doc), then provisioning.**

---

## 0. Why off-box (one paragraph)
The 12-core Cassandra box can't host both a full-core-budget Cassandra AND an isolated load
generator with CPU headroom. Co-located, either Cassandra's knee is client-contaminated
(client pegged 99%) or Cassandra is core-starved (6/6) and unrepresentative — and even
sub-knee the co-located client injects tail latency (22% CO, srv p99 1.6ms at 43% CPU),
which is fatal for a p99-based gate. Off-box removes the generator from Cassandra's cores
entirely, so Cassandra keeps all **6 physical cores** and its knee is unambiguously its own.

## 1. Load box — DECIDED (user, 2026-07-10): dedicated **AMD Ryzen 7 1700X**, same Hetzner DC
- **Why this over CCX:** 1700X = **8 physical cores / 16 threads** (more physical cores than the
  6-core Cassandra box). Dedicated bare-metal ⇒ **no vCPU steal / hypervisor jitter** (a CCX
  risk) and ~€30–40/mo vs €250+ for the CCX tier. Sized estimate: clean Coffee-Lake number was
  ~46k write-ops/s per physical core; derate ~0.6× for Zen 1 IPC → ~28k/core × 8 ≈ **~200–230k
  ops/s** — enough to drive the ~162–200k 6-core Cassandra knee and locate it. If marginal,
  multi-process is now safe (off-box, doesn't steal Cassandra cores).
- **Location/network:** same Hetzner **Helsinki** DC, on the **internal network** (<1 ms). Use a
  Hetzner **vSwitch** (Robot) with a private subnet (e.g. `10.0.0.0/24`) so 9042 never faces the
  public internet. Bandwidth is trivial (~200k ops/s × ~150 B ≈ 30 MB/s). (Server-side p99 is
  coordinator-clocked, so the network path can't inflate it regardless — the private link is for
  steady *delivery* + security.)
- **Software on the load box:** JDK 11+ (matches the tool), the `cassandra-easy-stress` fork at
  `feature/csv-latency` (clone `~/repos/cassandra-easy-stress`, `./gradlew shadowJar`, PATH
  wrapper), `sysstat` (mpstat), `governor=performance`, same `taskset`/`pkill`-bracket discipline.

## 1b. Cassandra-side connectivity (DO AT BRINGUP — needs a restart; NOT done yet)
Currently Cassandra binds **127.0.0.1:9042 only** (`listen_address`/`rpc_address: localhost` in
`/data/tpc-poc/conf/cassandra.yaml`; verified live `LISTEN 127.0.0.1:9042`). To accept the
off-box driver:
1. Assign the Cassandra box a private vSwitch IP (say `10.0.0.1`), load box `10.0.0.2`.
2. In `cassandra.yaml`: set `rpc_address: 10.0.0.1` (native bind). Leave `broadcast_rpc_address`
   unset so it defaults to `rpc_address` — the v4 driver reads it from `system.local`/`peers` to
   build its pool; if it still advertised `localhost` the off-box driver would fail to connect.
   `listen_address` (internode) can stay `localhost` for this single RF=1 node.
3. Restart Cassandra (`CASSANDRA_CONF=/data/tpc-poc/conf`), confirm `ss -ltnp | grep 9042` shows
   `10.0.0.1:9042`, and from the load box `nc -z 10.0.0.1 9042`.
4. Firewall: box has no nft/iptables rules today — restrict 9042 to the load box IP (private
   subnet already isolates it; add a rule if using public IPs instead of a vSwitch).
5. Stress tool: use `--host 10.0.0.1` (NOT 127.0.0.1). Everything else in §3 unchanged.

## 2. What's already done (don't redo)
- Cassandra (`157.180.98.112`, `/root/repos/fork/cassandra-tpc`,
  `CASSANDRA_CONF=/data/tpc-poc/conf`) is repinned to **cores 0–11 (full)** — the off-box
  target config. Trie memtable, RF=1, periodic commitlog, 16 G G1.
- Dataset is **fresh**: dropped + repopulated 2M partitions (346 MB, compaction drained).
  Recipe if you need to redo it (note `--populate` is PER-THREAD, `--rate 2M` OK for the
  throwaway populate only): see `REBASELINE-HANDOFF.md` §0.5.
- Rig helper scripts on the box: `/tmp/rung.sh <offered_rate> <secs>` (raw-rate rung,
  server-side delivered + both-fence CPU + proxyhistograms) and `/tmp/pollx.sh` (live poll).
  Re-create from this repo if the box rebooted (they're in /tmp).

## 3. Method (the baseline curve = the gate deliverable)
Run the generator **on the load box**, `--host <cassandra-ip>`. Cassandra now owns all 12
cores, so read **server-side** and treat the generator as external.
1. **Find Cassandra's knee:** raw `--rate` rungs (NO `--maxlat`) climbing until Cassandra
   fence CPU (all 12 cores now) hits ~85–90% and/or server p99 turns up. Confirm at each rung
   that the **load box** has CPU headroom (mpstat) — if the load box saturates first, it's
   too small; get more cores. Delivered = `nodetool tablestats` count delta; p99 =
   `nodetool proxyhistograms` (µs).
2. **Baseline curve per mix** (`--readrate` 0.0 / 0.5 / 0.9), 3–4 load levels below the knee,
   3 iters each: record server-side throughput + proxyhistogram p50/p95/p99 + CPU + errors
   (0-error cells only). This is the p99-vs-throughput baseline the PoC gate compares against.
3. Rewrite `poc-criteria.md §8` with the curve; state the fence (Cassandra 0–11, off-box gen,
   load-box core count + proven headroom) and that the ceiling is now Cassandra's own knee.

## 4. Rules that still hold (from STRESS-RUNBOOK / stress-tool-behaviour)
- **Server-side is the authority** — `nodetool` count deltas + `proxyhistograms`. rc=0 ≠ work.
- `--prometheusport 0` always. `--populate` PER-THREAD. `--host <cassandra-ip>` (not 127.0.0.1).
- Launch detached (`setsid … </dev/null >log 2>&1 & disown`) + poll a log; bracket every
  `pkill` pattern. Parse client stdout with `tr '\r' '\n'`.
- `--maxrlat/--maxwlat` govern the CLIENT's CO-corrected p99 — good for finding the client's
  comfortable rate, but for the *Cassandra* baseline drive raw `--rate` and read server-side.
- Off-box, the shared-RateLimiter collapse (>knee) still exists per process; stay ≤ knee, and
  if one process can't reach Cassandra's knee even with headroom, add processes (now safe —
  they no longer steal Cassandra's cores) or raise `--threads`.

## 5. Pointers
- Evidence for the off-box decision: `progress.md` 2026-07-10 entry.
- Tool mechanism: `stress-tool-behaviour.md`; one-page how-to: `STRESS-RUNBOOK.md`.
- Gate concept + config pins: `poc-criteria.md`. Hurdle log: `hurdles.md`.
- Memory: `feedback_cpu_fence_colocated_loadgen` (this is exactly that lesson realized),
  `feedback_easy_cass_stress_scripted_run_gotchas`.
