# R5 — Multi-hour T4 vs T16 at low fill (steady-state DB WAF) — v2

Supersedes `r5_plan.md`. Goal/rationale/expected outcomes unchanged; this revision fixes CLI inaccuracies, captures real rig state, and tightens the verification sequence.

## What changed vs v1

1. **Module name.** v1 said `python -m waf_baseline`; the real package is `waf_baseline_poc` (entry point `waf-baseline` via `[project.scripts]`). The console script will be on `$PATH` once the package is installed in editable mode on the rig.
2. **Duration flag.** v1 said `--duration 4h`. The runner has two separate knobs:
   - `--measurement-window-s` — how long the measurement window stays open (default 1800s = 30 min). **This is what R3/R4 actually varied; for R5 set to 14400 (4h).**
   - `--measurement-duration` — `cassandra-easy-stress --duration` arg (default 30m). Must be ≥ window. Set to `4h10m` (10-min safety margin so the workload doesn't exit before SIGTERM at window close).
   - `--warmup-max-s` — cap on SSD-WAF-steady-state warmup before the window opens (default 4h). At low-fill SSD WAF is ≈1.0 from t=0, so warmup should exit fast on its own; leave at default.
3. **Required args.** v1's launch command omitted four required positional/required-named args. Resolved values for this rig:
   | arg | value |
   |---|---|
   | `--cassandra-home` | `/root/repos/fork/cassandra` |
   | `--data-mount` | `/data` |
   | `--measurement-drive-serial` | `S64FNE0R401522` |
   | `--results-dir` | `/data/results` |
4. **Rig state.** v1 said "Cassandra: inactive." Actually Cassandra is **running** (PID 97961, started 10:34:48 May 28, foreground from `/root/repos/fork/cassandra`, no systemd unit). Keyspace `cassandra_easy_stress` exists with 5.79 GiB load (R4 leftover). The R5 mkfs step will destroy /data and therefore the running Cassandra's storage — Cassandra must be stopped first.
5. **waf-baseline-poc not on rig.** `/root/waf-baseline-poc/` on the rig contains only `.git` and `.gitignore`. Source was wiped or never landed. Rsync from local before launch is mandatory.
6. **No `launch.log` in prior R4 dirs.** The harness writes `bootstrap/`, `warmup/`, `summary.json`, and per-replicate subdirs — not a top-level `launch.log`. v1's "tail launch.log" check assumed a file the harness doesn't create. v2 uses our own `nohup … > /data/results/<cell>/launch.log 2>&1 &` to capture the wrapper's stdout/stderr.
7. **memtable defaults.** v1 assumed ~256 MB. Real defaults: `memtable_heap_space` ≈ ¼ of `-Xmx31744M` = ~7.9 GB; `memtable_cleanup_threshold = 1/(memtable_flush_writers+1) = 1/3` → first flush ≈ 2.6 GB. At 5k ops/s × 1 KB = 5 MB/s, that's ~520s per flush → ~28 flushes per 4h cell. Acceptable compaction cadence; doesn't change the test design.

## Resolved open items

| v1 open item | resolution |
|---|---|
| `--duration` flag | Use `--measurement-window-s 14400` + `--measurement-duration 4h10m` |
| `auto_snapshot` still false | Verified: `auto_snapshot: false` in `/root/repos/fork/cassandra/conf/cassandra.yaml` |
| Memtable threshold | Defaults (heap_space ≈ 7.9 GB, flush ≈ 2.6 GB, ~28 flushes per 4h cell) — fine |
| Reset drops `cassandra_easy_stress` | `--keyspaces-to-drop` defaults to `cassandra_easy_stress` (correct keyspace name) |
| PMUW headroom | Drive lifetime 50.169 TB, 2× ~70 GB cells = 0.14 TB added — trivial |

## Conditions (unchanged from v1)

| parameter | value |
|---|---|
| Cells | T4, T16 |
| Run length per cell | 4 hours measurement window |
| Fill regime | Low-fill (target `percent_free_blocks ≈ 99` post-mkfs) |
| Workload | `ycsb_a_zipf_0.8`, default cass-stress params (5k ops/s, 64 threads, 1 KB) |
| Reset between cells | Default soft reset (drop `cassandra_easy_stress`, force-drop SSTables, restart Cassandra) per pilot harness |

## Pre-launch state (snapshot 2026-05-28 17:22 UTC)

- Rig 157.180.98.112 reachable via IP (no `cassandra-waf-rig` SSH alias — use IP)
- Cassandra running (PID 97961) from `/root/repos/fork/cassandra`
- `/data`: 5.9 G used / 829 G; keyspace `cassandra_easy_stress` 5.79 GiB
- `/dev/nvme1n1` percent_free_blocks = **5** (HIGH FILL state from R4 preconditioning)
- PMUW lifetime: 50.169 TB
- `/root/waf-baseline-poc` on rig: **empty** (source not present)
- `/root/cassandra-agent-harness` on rig: **present**
- `/root/repos/cassandra-easy-stress` on rig: present

## Sequence

### Phase 0 — Pre-flight from this workstation (no destructive ops yet)

1. **rsync waf-baseline-poc to rig**
   ```bash
   rsync -av --delete --exclude '.venv' --exclude '__pycache__' --exclude '.git' --exclude '.pytest_cache' \
     /Users/samlightfoot/repos/waf-baseline-poc/ root@157.180.98.112:/root/waf-baseline-poc/
   ```
2. **rsync cassandra-agent-harness to rig** (keep them in sync; rig already has it but local may have changes)
   ```bash
   rsync -av --delete --exclude '.venv' --exclude '__pycache__' --exclude '.git' \
     /Users/samlightfoot/repos/cassandra-agent-harness/ root@157.180.98.112:/root/cassandra-agent-harness/
   ```
3. **Install package on rig** (pip install -e for both)
   ```bash
   ssh root@157.180.98.112 'set -e
     cd /root && python3 -m venv waf-baseline-poc/.venv 2>/dev/null || true
     /root/waf-baseline-poc/.venv/bin/pip install -U pip
     /root/waf-baseline-poc/.venv/bin/pip install -e /root/cassandra-agent-harness
     /root/waf-baseline-poc/.venv/bin/pip install -e /root/waf-baseline-poc
   '
   ```
4. **Verify CLI on rig**
   ```bash
   ssh root@157.180.98.112 '/root/waf-baseline-poc/.venv/bin/waf-baseline pilot --help' | grep -E "ucs-scaling-parameters|measurement-window-s|measurement-duration|fill-fraction"
   ```
   Must show all four flags. If missing → STOP.

### Phase 1 — Reset SSD to low-fill (DESTRUCTIVE, needs user sign-off)

5. **Stop Cassandra cleanly**
   ```bash
   ssh root@157.180.98.112 'cd /root/repos/fork/cassandra && bin/nodetool drain && pkill -TERM -f CassandraDaemon; sleep 10; pgrep -f CassandraDaemon || echo STOPPED'
   ```
6. **Unmount /data**
   ```bash
   ssh root@157.180.98.112 'umount /data && lsblk /dev/nvme1n1p3'
   ```
   If umount fails (busy), find the holder with `lsof /data` / `fuser -vm /data` and stop it before retrying.
7. **mkfs.ext4 (deliberately WITHOUT -E nodiscard → TRIMs partition → restores percent_free_blocks)**
   ```bash
   ssh root@157.180.98.112 'mkfs.ext4 -F -L data /dev/nvme1n1p3'
   ```
8. **Remount + verify free state**
   ```bash
   ssh root@157.180.98.112 '
     mount /data
     df -h /data
     nvme ocp smart-add-log /dev/nvme1n1 | grep -E "Percent free|Physical media units written"
   '
   ```
   Expect: `Percent free blocks = 99` (low fill). Record PMUW. If percent_free is anything other than ~99 → STOP and re-evaluate.

### Phase 2 — T4 cell (4h)

9. **Start Cassandra** (foreground daemon as before)
   ```bash
   ssh root@157.180.98.112 'cd /root/repos/fork/cassandra && nohup bin/cassandra -f > /data/cassandra-startup.log 2>&1 &'
   ```
   Wait ~30s, then `bin/nodetool status` until UN.
10. **Pre-launch checklist**
    - Drive state: `nvme ocp smart-add-log /dev/nvme1n1 | grep -E "Percent free|Physical media units written"` — confirm 99% free, record PMUW as `T4_pmuw_start`.
    - Cassandra healthy: `bin/nodetool status` → UN.
    - No leftover keyspace: `bin/cqlsh -e "DESCRIBE KEYSPACES"` should NOT list `cassandra_easy_stress` (mkfs wiped it).
11. **Launch T4**
    ```bash
    ssh root@157.180.98.112 'mkdir -p /data/results/T4-LF4h-$(date -u +%Y%m%dT%H%M%SZ) && export RUN_DIR=$(ls -d /data/results/T4-LF4h-* | tail -1) && cd /root/waf-baseline-poc && nohup .venv/bin/waf-baseline pilot \
        --cassandra-home /root/repos/fork/cassandra \
        --data-mount /data \
        --measurement-drive-serial S64FNE0R401522 \
        --results-dir /data/results \
        --workload ycsb_a_zipf_0.8 \
        --fill-fraction 0.04 \
        --ucs-scaling-parameters T4 \
        --measurement-window-s 14400 \
        --measurement-duration 4h10m \
        > $RUN_DIR/launch.log 2>&1 &
        echo RUN_DIR=$RUN_DIR PID=$!'
    ```
12. **10-second post-launch verification** (per `feedback_monitor_silence_is_not_success`)
    ```bash
    sleep 10
    ssh root@157.180.98.112 '
      pgrep -af waf-baseline || echo "DEAD: process not running"
      tail -40 /data/results/T4-LF4h-*/launch.log
    '
    ```
    Reject if any of: `unrecognized arguments`, `usage:`, `error:`, `Traceback`, `Exception`, `command not found`, process not running.
13. **60-second workload-counter verification**
    ```bash
    sleep 60
    ssh root@157.180.98.112 'tail -100 /data/results/T4-LF4h-*/launch.log | grep -E "ops/s|writes_count|prefill|warmup"'
    ```
    Must show non-zero progress. If silent → STOP.
14. **Arm monitor** (background poller that grep's for both success and failure indicators, alerts on either)
    - Success: `MeasurementWindow closed`, `cells_succeeded=1`, `WafResult`, `cell.json written`
    - Failure: `unrecognized arguments`, `usage:`, `error:`, `Traceback`, `Exception`, `OOMError`, `JVM crashed`, `connection refused`, `command not found`, `cells_succeeded=0`
    - Heartbeat: any of `compaction completed`, `flush completed`, `ops/s=`
15. **Wait ~4h20m** (4h window + flush/snapshot collection at close)

### Phase 3 — Inter-cell reset + T16 cell (4h)

16. The pilot runner does soft reset itself (drop `cassandra_easy_stress`, force-drop SSTables, restart Cassandra). Verify after T4 finishes:
    - `du -sh /data/cassandra_easy_stress` → should not exist
    - `df -h /data` → back to ~6 GB used
    - `nvme ocp smart-add-log /dev/nvme1n1 | grep "Percent free"` → drift check; if dropped below 99 by more than a few points, re-mkfs (Phase 1 steps 5-8) before T16.
17. Repeat Phase 2 steps 10-15 with `--ucs-scaling-parameters T16` and run dir `T16-LF4h-...`.

### Phase 4 — Collect + write-up

18. Pull from rig: `cell.json`, `summary.json`, `launch.log`, and the per-cell `ycsb_a*` subdirs for both T4 and T16.
19. Append `## R5` section to `results.md` matching the format used for R2/R3/R4.
20. Update `summary.md` §5 and Future Work §8 if R5 shows clean T-differentiation.

## Risk register (delta from v1)

| risk | mitigation |
|---|---|
| Cassandra running on /data → umount fails | Step 5: `nodetool drain` + SIGTERM before umount; verify no java PID before mkfs |
| pip install on rig pulls new versions, breaks runner | Use `pip install -e .` with no `-U` for the package itself; existing wheels stay |
| Pilot runner doesn't re-create cassandra_easy_stress after mkfs (no keyspace to drop) | First-run flow handles cold start; pilot's bootstrap phase creates schema via cass-stress prefill |
| `--measurement-duration 4h10m` parser rejects "h10m" | Validated against argparse `str` type in `cli.py` (TODO: confirm by reading parser; fallback `--measurement-duration 15000s` = 4h10m) |
| Re-mkfs between cells regresses percent_free silently | Step 16 includes drift check + conditional re-mkfs |

## Open verification items remaining (need to do BEFORE Phase 1)

- [ ] Confirm `--measurement-duration 4h10m` parses (vs needing seconds). Read `src/waf_baseline_poc/cli.py` to confirm.
- [ ] Confirm the runner stops Cassandra at workload completion (so that Cassandra is restarted from clean state for the next cell). Read `src/waf_baseline_poc/runner.py`.
- [ ] Confirm Cassandra `-f` foreground start over SSH survives the SSH session ending — may need `setsid` or systemd-run.

## Decision point for user

**Before Phase 1 (destructive) starts** — I'm pausing here to ask:

1. Proceed with **mkfs.ext4** path A (TRIM partition to restore percent_free=99)? This wipes the R4 keyspace currently on /data.
2. Or hold and let user inspect the v2 plan first?

Default if user says "go": run Phase 0 (rsync + pip install + CLI verify), then stop again before Phase 1 destructive mkfs for explicit go-ahead.
