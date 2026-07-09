# Phase 4 Hurdle Log (`hurdles.md`)

Living log (spec.md 4.3). **Second deliverable of the program** — finding these IS the
point. Also structured to seed a centralised Cassandra-benchmark runbook (user directive
2026-07-09). Each entry: Symptom → Cause → Fix → Prevention (what's now baked into the
driver so it can't recur). Severity: `blocks-PoC` / `costs-perf` / `rig-op` (operational,
wastes rig time but not a program finding).

Increment-level hurdles (I0–I5) will be appended below the operational section as the
build proceeds.

---

## A. Operational hurdles — 4.1 baseline bring-up (2026-07-09)

Every one of these produced a WRONG or WASTED run that looked fine at a glance. The
common thread: **`rc=0` / "it ran" is not evidence of success** — verify at the source.

### A1. `rc=0` with zero work done — the master trap  · `rig-op`
- **Symptom:** the first full sweep "completed" in ~1 min (vs ~50 min expected); every
  cell logged `rc=0`; all 12 result dirs empty; throughput parsed as 0 ops/s.
- **Cause:** the stress tool exited 0 after an early exception (see A2) — it prints a
  stack trace and returns success. The driver logged `rc` but didn't gate on it.
- **Fix:** fail-fast gates in the driver — after populate, `grep -qiE "Address already in
  use|Exception in thread|Could not reach"` the stdout and `exit 5`; after the FIRST
  saturation cell, `exit 6` if it produced `< 100 ops/s`. Never run N cells blind.
- **Prevention:** baked into `baseline_driver.sh` (POPULATE + PHASE-A gates). General
  rule: a bench driver must assert *positive evidence of work* (rows written, ops>0), not
  just process exit code. Ties to the standing "monitor silence is not success" lesson.

### A2. easy-cass-stress Prometheus port 9500 collision  · `blocks-PoC`
- **Symptom:** every stress invocation (incl. populate) threw
  `java.net.BindException: Address already in use` at `Metrics.<init>` / `HTTPServer`,
  then exited 0 with no work.
- **Cause:** easy-cass-stress starts a Prometheus HTTP exporter on a FIXED port
  (default **9500**, env `CASSANDRA_EASY_STRESS_PROM_PORT`) on every run. A leftover/
  concurrent stress JVM already held :9500, so all subsequent runs failed to bind.
- **Fix:** pass **`--prometheusport 0`** — the `if (httpPort > 0)` guard in `Metrics.kt`
  skips the endpoint entirely ("Not setting up prometheus endpoint."). We capture metrics
  via stdout/`--hdr`/`--csv-latency`/`nodetool`, so the exporter is dead weight anyway.
- **Prevention:** `PROM="--prometheusport 0"` on ALL three invocations; preflight asserts
  `:9500` is free after killing strays. **This is the #1 silent-failure landmine for
  concurrent or retried easy-cass-stress runs — always disable the exporter for scripted
  sweeps.**

### A3. `--populate N` is PER THREAD, not total  · `rig-op`
- **Symptom:** `--populate 5000000 --threads 48` started populating **240,000,000** rows
  (ETA ~130 hours); looked like a "stall".
- **Cause:** in `WorkloadRunner`/`Run.kt`, `--populate` = rows **per thread**; total =
  `N × threads` (progress bar sized `max * threads`; each thread pulls from a SHARED
  sequential key generator → the keys are distinct, so it really does write N×threads
  distinct partitions).
- **Fix:** to populate `T` total distinct partitions with `k` threads, pass
  **`--populate $((T / k))`** and `--partitions T`.
- **Prevention:** driver computes populate count as `PARTS / THREADS`; a populate
  watchdog (`timeout`) + the A1 row-count gate stop any runaway early.

### A4. Heap override applied too late (16G → 31G)  · `rig-op`
- **Symptom:** pinned `MAX_HEAP_SIZE="16G"` but the JVM ran `-Xmx31744M` (½ RAM default).
- **Cause:** the override was APPENDED to the end of `cassandra-env.sh`, after
  `calculate_heap_sizes` had already computed `-Xmx`/`-Xms` from the default and added
  them to `JVM_OPTS`. Late assignment is a no-op.
- **Fix:** set `MAX_HEAP_SIZE`/`HEAP_NEWSIZE` **near the TOP** of `cassandra-env.sh`
  (before the guard/`calculate_heap_sizes`), or uncomment the provided slots.
- **Prevention:** driver-adjacent conf build sets them at line 2. Verify with
  `tr '\0' '\n' </proc/$(pgrep -f Cassandra[D]aemon)/cmdline | grep -E '^-Xm[xs]'`.

### A5. SSH channel hangs on detached launch  · `rig-op`
- **Symptom:** launching Cassandra with `... bin/cassandra -f ... &` inside an SSH command
  hung the SSH call for the full 2-min tool timeout even though the JVM started.
- **Cause:** `-f` (foreground) + `&` kept the detached child holding the SSH channel's
  stdout/stderr open; ssh waits for all channels to close.
- **Fix:** launch **without `-f`** — `bin/cassandra -R` self-daemonizes and returns
  immediately (ssh closes). For scripts/sweeps use `setsid ... </dev/null >log 2>&1 &
  disown`.
- **Prevention:** node launch uses `taskset -c <cores> bin/cassandra -R` (no `-f`).

### A6. `pkill -f <pattern>` kills its own SSH shell  · `rig-op`
- **Symptom:** `pkill -9 -f "baseline_driver.sh"` returned exit 255 (SSH dropped); nothing
  after it ran.
- **Cause:** `pkill -f` matches full command lines — including the remote shell running
  the pkill, whose args contain the literal pattern. It SIGKILLs its own parent.
- **Fix:** the self-exclusion bracket trick — `pkill -9 -f "baseline_driver[.]sh"` (the
  `[.]` makes the running command line not match its own regex). Same for
  `"easy-stress-.*-all[.]jar"`, `"[G]radleDaemon"`, `"Cassandra[D]aemon"`.
- **Prevention:** all pkill patterns in the driver + ops use the bracket trick. (The task
  runbook already had this for `CassandraDaemon`; extend to EVERY pattern.)

### A7. Leftover / zombie stress JVMs across launches  · `rig-op`
- **Symptom:** processes from earlier (even rejected/interrupted) launch attempts stayed
  alive — an orphaned populate held :9500 (→ A2) and kept writing.
- **Cause:** detached `setsid` runs + a foreground populate that outlived its parent
  driver; no preflight cleanup. Multiple driver generations overlapped.
- **Fix:** preflight kills any `easy-stress-.*-all[.]jar` + `[G]radleDaemon` and asserts
  `:9500` free BEFORE starting; abort if not clean.
- **Prevention:** `=== PREFLIGHT ===` block in the driver. General rule: a bench driver
  owns the box — start from a proven-clean slate, never assume idle.

### A8. Gradle daemon squatting inside the CPU fence  · `costs-perf`
- **Symptom:** a Gradle daemon (`GradleDaemon`) had run ~2 h on **core 5** — inside
  Cassandra's 0–9 fence — stealing cycles from the node under test.
- **Cause:** a prior `./gradlew shadowJar` (building the stress tool) left a persistent
  daemon; it isn't pinned and lands anywhere.
- **Fix:** preflight kills `[G]radleDaemon`; build the stress jar with `--no-daemon` or
  kill the daemon after building.
- **Prevention:** preflight cleanup + rule "no unaccounted java process on the box before
  a sweep" (the task runbook's existing "drivers preflight-fail on any java process").

### A9. Load-generator is the bottleneck (CPU-fence sizing)  · `blocks-PoC` (methodology)
- **Symptom:** populate crawled at ~1–2k ops/s on 2 client cores (10–11) with 48 threads.
- **Risk:** if the client (pinned to 2 cores, oversubscribed with 48 threads) saturates
  before Cassandra, the "saturation" numbers measure the LOAD GENERATOR, not Cassandra —
  the classic co-location artifact (CPU-fence lesson).
- **Fix (in progress):** re-pinned Cassandra to **cores 0–7** (live `taskset -a -pc 0-7`,
  mask `ff`), giving the client **cores 8–11 (4 dedicated cores)** exclusively; reduce
  client threads to a sane async count (~24–32). **Calibrate before the full sweep:** run
  one short saturation cell and confirm via `mpstat -P 8,9,10,11` that the client cores
  have headroom (not pegged) — else the number is a client artifact.
- **Prevention:** driver `STRESS_CPUS=8-11`, Cassandra 0–7; mpstat headroom capture per
  cell is already in the driver; ADD a pre-sweep calibration gate that fails if client
  cores > ~85% busy at saturation.

### A10. Config pins must be PROVEN active, not assumed  · `blocks-PoC`
- **Symptom (potential):** a yaml edit can silently not take (e.g. memtable stays
  SkipList), invalidating every A/B without any error.
- **Fix / proof recipe (do this every bring-up):**
  - trie memtable ACTIVE: `nodetool sjk mxdump | grep 'type=TrieMemtable'` → must show
    `type=TrieMemtable,keyspace=<ks>,scope=<table>` MBeans (Contended/Uncontended puts).
  - heap / disk_access_mode / commitlog_sync / auto_snapshot / RF: grep the
    `Config.java:...- Node configuration:` line in `system.log` (single dump of the whole
    resolved config).
  - data/commitlog dirs: same dump (`data_file_directories`, `commitlog_directory`).
- **Prevention:** bring-up checklist below (§C).

### A12. easy-cass-stress default `--rate 5000` throttles every run  · `blocks-PoC`
- **Symptom:** "saturation" cells (no `--rate` set) delivered only ~1.3k ops/s with BOTH
  the client (~92% idle) and Cassandra (~98% idle) nearly idle and per-op latency 0.33 ms —
  effective concurrency <1. Populate crawled at ~3.7k/s for the same reason.
- **Cause:** `Run.kt` defaults **`var rate = 5000L`** and installs a producer-side
  `rateLimiter.acquire(1)` per op. "No `--rate`" does NOT mean unlimited — it means capped
  at 5000/s. (The sub-5000 *actual* is ramp/overhead within short windows under the cap.)
- **Fix:** set a high `--rate` (e.g. **500000**) on saturation/populate/warmup to actually
  find the ceiling. Confirmed: same cell with `--rate 500000` → **~46k ops/s** (35× the
  throttled number). `--max-connections 8 --max-requests 2048` added only ~4% → driver
  in-flight is NOT the main limit; ~46k is the client's real ceiling on 4 cores here.
- **Prevention:** driver `RATE_SAT=500000` applied to populate/warmup/saturation; reference
  cells intentionally set `--rate = 70% × measured_max` (a controlled offered load — that's
  correct). **Honesty note:** at ~46k both fences may still have headroom, so the saturation
  number can be a CLIENT ceiling, not Cassandra's max — record mpstat at saturation and
  label it; A/B validity holds because both arms compare at the SAME offered rate.

### A13. "Unlimited" offered rate → coordinated-omission MELTDOWN, not saturation  · `blocks-PoC`
- **Symptom:** running each mix at `--rate 500000` ("find the max") gave garbage — sat-w:
  25.8M errors, p99 5,611ms; sat-rw: 60M errors, p99 6,250ms; sat-r: 20M errors, p99
  6,731ms — while SERVER-side read latency was healthy (0.22ms local, 8ms p99 proxyhist).
- **Cause:** the tool is **open-loop** (rate-limited producer, not fixed-concurrency
  closed-loop). Offering 500k/s when the server serves ~100k/s overflows the client's
  in-flight/driver queues → timeout exceptions flood and measured latency is pure queueing
  (coordinated omission). The "throughput" number is an overload-collapse artifact, and
  the p99 is meaningless. A controlled rate cell (ref-w @107k) on the SAME setup was clean:
  **0 errors, p99 239ms** — proof the tool + server are fine; only the offered rate was wrong.
- **Fix:** never offer unlimited. Use a **rate ladder**: climb fixed offered rates per mix,
  stop at the first rung with >1% errors OR achieved <90% of offered — the last clean rung
  is the sustainable max. Measure p99 at operating points BELOW it (this is exactly the
  "p99 at matched throughput" the gate wants; the deliverable is a p99-vs-throughput curve).
- **Prevention:** driver rewritten to a `LADDER` + `OVERLOAD_ERRPCT` discovery phase, then
  operating-point measurement at `REF_FRACS` % of clean_max with 3-iter noise bands. General
  rule for open-loop stress tools: a saturation number without an error-rate + achieved-vs-
  offered check is worthless. Also fixed the throughput parse (sum writes col $1 + reads col
  $4; errors = col $10 — the old parser counted writes only, mis-reporting read/mixed cells).

### A14. Achieved throughput ≈ 53% of nominal `--rate` even when healthy  · `rig-op`
- **Symptom:** first ladder version aborted (false FATAL) — rung `off=10000 achieved=5499/s
  err=0% wp99=0.24ms` was flagged OVERLOAD by an `achieved < 90% of offered` test, though it
  was perfectly clean. Warmup showed the same ratio: 40k offered → ~21k achieved, 0 errors.
- **Cause:** the tool's `--rate` is a nominal producer target; sustained achieved throughput
  runs ~0.5–0.55× of it even with zero errors and sub-ms latency (rate-limiter pacing /
  per-op overhead). "achieved < offered" is therefore NORMAL, not overload.
- **Fix:** judge overload by **errors (>1%) and p99 blowup (>500ms)** — the meltdown had 25M
  errors and 5000ms p99, unmistakable. Track clean_max by the **offered** rate of the highest
  clean rung; plot/compare the curve against **achieved** throughput (the real ops/s Cassandra
  served). Reference cells set offered = clean_max_offered × frac.
- **Prevention:** driver uses `OVERLOAD_ERRPCT` + `P99_CEIL_MS` (not achieved-vs-offered);
  x-axis of every curve is achieved, offered is just the knob. To probe a target achieved X,
  offer ~1.9X.

### A15. Stress output is `|`-separated column GROUPS — awk field indices shift  · `rig-op`
- **Symptom:** a balanced cell logged `rp99=165222ms` (165s in a 60s cell — impossible) and
  false-FATAL'd; achieved throughput under-counted (missed the reads column).
- **Cause:** each data row is `W.cnt W.p99 W.1m | R.cnt R.p99 R.1m | D... | E.cnt E.1m` —
  the **`|` pipes are their own whitespace-delimited fields**. So reads are `$5/$6` (not
  `$4/$5`), deletes `$9`, errors `$13`. My awk used `$4/$5/$10` → `$4="|"→0`, and `$5` (reads
  COUNT, 165222) got logged as read-p99.
- **Fix:** pipe-aware indices — writes `$1,$2`; reads `$5,$6`; deletes `$9`; errors `$13`.
  VERIFIED against real captured cells before relaunch (`lad-rw-r10000` → rp99 0.24ms, not
  165222). The underlying data was healthy all along; only the parser was wrong.
- **Prevention:** parse_cell documents the layout; verify any stress-output parser against a
  real row before trusting a gate built on it. (Consider `-F'[|[:space:]]+'` to drop pipes.)

### A11. Minor tool gotchas  · `rig-op`
- `--hdr <prefix>` writes `<prefix>-{mutations,reads,deletes}.txt` (NOT `<prefix>.hdr`);
  values are ms, CO-corrected (same as `--csv-latency`/stdout). `-mutations.txt` is
  contaminated by populate only when populate + load share ONE invocation. (Full detail in
  memory `feedback_easy_cass_stress_hdr_semantics`.)
- `run <Workload> --help` / `-h` tries to CONNECT/run instead of printing help; get the
  real flag list from `@Parameter` annotations in `commands/Run.kt`.
- `scp` can leave a stale rig copy if it silently no-ops — **md5sum-compare** the deployed
  driver against local before launching (a stale driver runs old logic → wrong results).
- easy-cass-stress `--csv` summary is truncated by SIGTERM (use stdout); keyspace is
  `cassandra_easy_stress`; the PATH wrapper resolves to
  `/root/repos/cassandra-easy-stress/build/libs/*-all.jar` on branch `feature/csv-latency`
  (reverts to `main` between sessions — check `git branch` + rebuild).

---

## B. Working recipes (the positive patterns — for the centralised runbook)

- **SSH:** key-auth works — `ssh -o StrictHostKeyChecking=no root@157.180.98.112` (no
  password on the command line).
- **Node launch (CPU-fenced, detached, returns immediately):**
  `CASSANDRA_CONF=<conf> taskset -c 0-7 bin/cassandra -R` (no `-f`).
- **Node teardown:** `pkill -9 -f 'Cassandra[D]aemon'` (bracket trick).
- **Re-pin a running node's cores live (no restart):** `taskset -a -pc 0-7 $(pgrep -f
  'Cassandra[D]aemon')` (mask `ff` = cores 0–7).
- **Disable the stress exporter (always, for scripted runs):** `--prometheusport 0`.
- **Populate T distinct partitions with k threads:** `--populate $((T/k)) --partitions T`.
- **Three latency signals (cross-check):** stdout summary (authoritative throughput) +
  `--csv-latency` (100 ms windows, primary tail) + `nodetool proxyhistograms` (server
  truth). GC log overlay for tail attribution (raw p99 is G1-noise on fast NVMe).

## C. Bring-up checklist (run before every baseline / A/B sweep)

1. `uptime` (box auto-reboots for kernel upgrades — a run dying with "connection refused"
   is a reboot, not a bug).
2. Preflight-clean: kill `easy-stress-.*-all[.]jar`, `[G]radleDaemon`; assert `:9500`
   free; confirm ONLY `CassandraDaemon` is the running java proc.
3. Governor `performance` (record); irqbalance state recorded.
4. Verify JAR is freshly built from synced source (`ant jar`; mtime > source; class present
   via `jar tf`). md5-verify any deployed driver vs local.
5. Node up + config PROVEN (§A10): trie MBeans, heap, disk_access_mode, dirs, RF.
6. CPU fence: Cassandra 0–7, client 8–11 disjoint; calibrate one short cell + `mpstat`
   proves client headroom BEFORE the full matrix.
7. Fail-fast gates armed (populate rows > 0; first cell ops > 0).
