# START HERE — Phase 4.1 baseline runs (6/6 repin) — new-context handoff

**Write date:** 2026-07-10. Self-contained. Read this + the two stress docs, then execute.
Supersedes the load-model in `REBASELINE-HANDOFF.md` (which prescribed the WRONG `--rate 2M`
+ multi-process method — see its correction banner).

**Read first (mandatory, ~10 min):**
1. `STRESS-RUNBOOK.md` — one-page how-to + the `--rate`+`--maxlat` trap.
2. `stress-tool-behaviour.md` — full mechanism, the delivery curve, the maxrlat analysis.

---

## 0. The one-paragraph situation
We must produce Cassandra's **server-side p99-vs-throughput** baseline (the PoC gate =
increment p99 ≤ trunk at matched throughput). The blocker all along was the **load
generator**, not Cassandra. On the old 8-core-Cassandra / 4-core-client co-located fence,
the client walls at **~50k/s on its own queueing latency** (client CO p99 100 ms) and
**~160k/s on throughput collapse**, while **Cassandra is only 30–90% CPU with sub-ms
server-side p99** (measured: 0.31 ms write p99 at 47k). So the client can't cleanly drive
Cassandra to its own saturation. **Decision: give the client more cores (repin 6/6); if it
still bottlenecks, go off-box.**

## 1. Key findings from the investigation (evidence, so you don't re-derive)
- **Two latencies diverge ~300×.** `--maxwlat`/`--maxrlat` govern the **client's**
  coordinated-omission-corrected p99 (op-creation→complete, incl. generator queue wait),
  NOT Cassandra's. At the converged ~47k point: client p99 ≈ 100 ms, **server
  `proxyhistograms` write p99 = 310 µs, Cassandra ~30% CPU.** [measured]
- **Clean delivery ≈ offered ≤ ~100k** (single proc, ratio 0.94–0.96, fresh dataset,
  server-side). Old "0.1–0.25× delivery" claims were skewed-dataset artifacts — FALSE. [measured]
- **Client throughput curve:** 40k→37.7k(45%CPU) · 100k→95.8k(64%) · 200k→162k(**99%**,
  knee) · 400k→130k · 800k→108k (collapse: delivered DROPS, Cassandra starves). [measured]
- **`--maxwlat` works single-process** (climbs cleanly from default 5000, converges,
  oscillates around the SLO with ~100–150 s period from the sticky decaying reservoir) —
  the earlier "stall" was **3 stacked processes**, not the tool. Units are **ms** (verified
  from source). NEVER pass `--rate` with `--maxwlat` (it hand-drives the rate the optimizer
  is supposed to control). [measured+source]
- Full mechanism (shared Guava RateLimiter across N generator threads, fair queues, async
  fire-and-forget, collapse cause) is in `stress-tool-behaviour.md`.

## 2. Rig access + current state (verify before running)
- SSH (key auth, no password): `ssh -o StrictHostKeyChecking=no root@157.180.98.112`.
- Reboot guard: box auto-reboots for kernel upgrades — check `uptime`; if SSH refused, wait,
  relaunch Cassandra, continue.
- Cassandra: `/root/repos/fork/cassandra-tpc`, `CASSANDRA_CONF=/data/tpc-poc/conf`, currently
  pinned **0–7** (you will repin to 0–5). Verify up: `CASSANDRA_CONF=/data/tpc-poc/conf
  bin/nodetool info | grep Uptime`. Trie memtable, RF=1, periodic commitlog, 16G G1 (pins valid).
- Keyspace `cassandra_easy_stress` table `keyvalue`, ~2M partitions. It has accumulated
  write overwrites from tonight's probes — **do a fresh populate** before the real curve
  (drop+repopulate 2M; see runbook / REBASELINE-HANDOFF §0.5 populate recipe — but with the
  CORRECT single-process, `--rate` only for the throwaway populate).
- Tool: `/root/repos/cassandra-easy-stress` `feature/csv-latency`, UNFIXED/clean. PATH wrapper
  `cassandra-easy-stress`.
- Preflight (bracket-safe!): `pkill -9 -f "[e]asy-stress-.*-all[.]jar"; pkill -9 -f
  "[G]radleDaemon"`; assert `:9500` free; only `CassandraDaemon` java proc; governor=performance.

## 3. Step 1 — REPIN to 6/6
```
# Cassandra -> cores 0-5 (all threads), live, no restart:
taskset -a -pc 0-5 $(pgrep -f "Cassandra[D]aemon")
# verify:
taskset -pc $(pgrep -f "Cassandra[D]aemon")   # -> 0-5
# client fence is now cores 6-11 (use taskset -c 6-11 for the stress process)
```
Record this fence change in results (it changes Cassandra's core budget vs the old 0–7).

## 4. Step 2 — VALIDATE whether 6 client cores lift the wall (decision gate)
Single process, latency-governed, NO `--rate`, client on 6 cores:
```
taskset -c 6-11 cassandra-easy-stress run KeyValue --host 127.0.0.1 --no-schema \
  --prometheusport 0 --readrate 0.0 --maxwlat 100 \
  --partitions 2000000 --threads 32 --queue 2000000 --duration 600s --hdr /tmp/w.hdr
```
Watch (detached + poll a log; see runbook): every ~20s log server write rate (`nodetool`
Local write count delta), Cassandra CPU (mpstat **0-5**), client CPU (mpstat **6-11**),
optimizer offered rate, and **capture `nodetool proxyhistograms` at the converged tail**.

**Decide from the client-vs-server gap at convergence:**
- ✅ **6 cores is enough** if, at the SLO, the client can drive Cassandra to a real knee —
  i.e. Cassandra CPU climbs toward ~80%+ and/or server-side write p99 rises off ~sub-ms as
  throughput grows, with delivered≈offered (client not collapsing). Then `--maxwlat` is now
  meaningfully governing Cassandra → proceed to Step 3.
- ❌ **Still client-bound → go OFF-BOX** if the gap is still huge (client p99 100 ms while
  server p99 stays sub-ms and Cassandra < ~60% CPU): 6 co-located cores still can't feed
  Cassandra. Provision an off-box generator (separate machine on the private net) and re-run.

## 5. Step 3 — the baseline curve (the gate deliverable)
Once the client can drive Cassandra (Step 4 ✅), capture **Cassandra's server-side
p99-vs-throughput** curve per mix (write `--readrate 0.0`, balanced `0.5`, read `0.9`):
- **Primary method:** sweep the latency target single-process — `--maxwlat`/`--maxrlat` ∈
  {e.g. 10, 25, 50, 100 ms} (both flags set; no `--rate`). Each converges to one
  (throughput, latency) point. At each, record **server-side** throughput (`nodetool` delta)
  + **`proxyhistograms`** p99 (the DB's real latency) + CPU + errors. 3 iters, average over
  ≥2 oscillation cycles.
- **Cross-check / fallback:** clean fixed `--rate` rungs (≤ client-clean-max, no `--maxlat`)
  reading server-side `proxyhistograms` — gives the same curve independent of the optimizer.
- These curves ARE the new baseline. Rewrite `poc-criteria.md §8` with them (server-side
  throughput + proxyhistogram p99 + noise band, 0-error cells), state the fence (6/6) and
  whether the ceiling is Cassandra (CPU/latency knee) or still client.

## 6. Measurement rules (unchanged, non-negotiable)
- **Server-side is the authority.** Throughput = `nodetool tablestats cassandra_easy_stress`
  Local write/read count delta ÷ elapsed. Latency = `nodetool proxyhistograms` (µs).
  Saturation signal = Cassandra fence CPU + MutationStage/ReadStage Active/Pending.
- rc=0 ≠ success; assert positive work. `--prometheusport 0` always. `--populate` PER-THREAD.
  Detached runs + poll (foreground ssh dies at 2-min tool timeout). Bracket every `pkill`.

## 7. Pointers
- Tool: `STRESS-RUNBOOK.md`, `stress-tool-behaviour.md` (this dir).
- Old (corrected) handoff: `REBASELINE-HANDOFF.md`. Retracted investigation:
  `tasks/easy-cass-stress-perf-fix/` (findings/task_plan flagged false).
- Gate concept + config pins: `poc-criteria.md`. Hurdle log: `hurdles.md`.
- Increment build order (after 4.1 closes): `../phase-3-execution-model/increments.md`.
- Memory: `feedback_easy_cass_stress_scripted_run_gotchas` (corrected), `lessons.md`.
