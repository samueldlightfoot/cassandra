# Cassandra WAF baseline — runbook

Canonical rig-facts + CLI-invocation reference. **Update this file whenever rig state, paths, or flags change** so future sessions don't rediscover the same answers. If you find yourself running `find / -name cassandra.yaml` or guessing a flag, this file is failing — fix it.

Last verified: **2026-05-28** by R5 pre-flight (see `progress.md` for the verification commands).

## 1. Rig

| field | value |
|---|---|
| Host | `cassandra-waf-rig` (Hetzner) |
| IP | `157.180.98.112` |
| SSH | `ssh root@157.180.98.112` (no SSH config alias on the dev workstation — use IP) |
| OS | Linux (Debian/Ubuntu family) |
| RAM | 62 GiB, swap **off** (verified `free -h` + `swapon --show`) |
| Python | `python3` is 3.10.12; `/usr/bin/python3.11` is 3.11.15 — **always use python3.11** for venvs (pyproject requires ≥3.11) |
| systemd unit for Cassandra | **none** — daemon runs foreground from `bin/cassandra -f`; `systemctl is-active cassandra` is misleading, use `pgrep -af CassandraDaemon` |

## 2. Drives

| field | value |
|---|---|
| Measurement drive | `/dev/nvme1n1`, partition `/dev/nvme1n1p3`, mount `/data` (ext4, 829 GB partition) |
| Measurement drive serial | `S64FNE0R401522` |
| Drive model | Samsung PM9A3 MZQL2960HCJR-00A07, FW `GDC5A02Q` |
| Commitlog drive | `/dev/nvme0n1`, mount `/commitlog` (ext4, 894 GB) — serial `S64FNE0R401526` |
| OCP log read | `nvme ocp smart-add-log /dev/nvme1n1` (look for `Physical media units written` and `Percent free blocks`) |
| PMUW reset behaviour | counters are **not user-resettable**; use deltas across a window |
| TRIM gotcha | `mkfs.ext4` TRIMs by default and undoes preconditioning; use `-E nodiscard` to preserve high-fill state. We exploit the default behaviour intentionally to *restore* low-fill (see R5). |

## 3. Software paths on rig

| component | path |
|---|---|
| Cassandra checkout | `/root/repos/fork/cassandra` (built; jar at `build/apache-cassandra-7.0-SNAPSHOT.jar`). **Launch line**: `cd /root/repos/fork/cassandra && nohup bin/cassandra -f -R > /data/cassandra-startup-$(date -u +%Y%m%dT%H%M%SZ).log 2>&1 < /dev/null & disown` — the `-R` is mandatory (refuses to start as root otherwise) and `-f` is required to keep it in the foreground for `nohup` to control. Ready in ~30s; `bin/nodetool status` returns UN when up. |
| Cassandra config | `/root/repos/fork/cassandra/conf/cassandra.yaml` (`auto_snapshot: false` ✓, defaults otherwise) |
| Cassandra logs | `/root/repos/fork/cassandra/logs/` |
| easy-cass-stress | `/root/repos/cassandra-easy-stress/` (binary at `bin/cassandra-easy-stress`) |
| cassandra-agent-harness | `/root/cassandra-agent-harness/` (editable install) |
| waf-baseline-poc | `/root/waf-baseline-poc/` (editable install, venv at `.venv` with python3.11) |
| Results root | `/data/results/` (per-cell subdirs, see §6) |

## 4. CLI invocation — `waf-baseline pilot`

**Module / entry point**: package is `waf_baseline_poc` (note underscore + `_poc`), console script is `waf-baseline` (with hyphen). On the rig:

```
/root/waf-baseline-poc/.venv/bin/waf-baseline pilot ...
```

**Required args** (will refuse to launch without these):

| flag | rig value |
|---|---|
| `--cassandra-home` | `/root/repos/fork/cassandra` |
| `--data-mount` | `/data` |
| `--measurement-drive-serial` | `S64FNE0R401522` |
| `--results-dir` | `/data/results` |

**Two duration knobs — pick both:**

| flag | meaning | typical value |
|---|---|---|
| `--measurement-window-s` | seconds the measurement window stays open | `1800` (30 min) for R2-R4; **`14400` (4h) for R5** |
| `--measurement-duration` | cass-stress `--duration` arg (free-form: `30m`, `4h`, `4h10m`, etc.). MUST be ≥ window so cass-stress doesn't exit before SIGTERM | `30m` default; **`4h10m` for R5** |

**Other knobs that matter:**

| flag | meaning | default | when to set |
|---|---|---|---|
| `--warmup-max-s` | cap on SSD-WAF-steady warmup before window opens | `14400` (4h) | leave default; at low-fill it exits fast anyway |
| `--warmup-sample-interval-s` | warmup OCP sample period | `300` (5 min) | leave default |
| `--measurement-sample-interval-s` | OCP sample period during window | `60` (1 min) | leave default |
| `--ucs-scaling-parameters` | UCS T value (T4/T8/T16/...) | `T4` | R3+ T-sweep cells |
| `--workload` | `ycsb_a_zipf_0.8` or `twcs_timeseries` | `ycsb_a_zipf_0.8` | YCSB for paper-comparable |
| `--fill-fraction` | target dataset fraction of `/data` | `0.80` | **`0.04` for R2-R5** (~33 GB target, small pyramid) |
| `--keyspaces-to-drop` | soft-reset target | `cassandra_easy_stress` | leave default; the *table* is `keyvalue`, the *keyspace* is `cassandra_easy_stress` |
| `--prefill-chunk-duration` | per-prefill-iteration duration | `5m` | leave default |
| `--skip-prereqs` | bypass Gate B | off | **never use for real runs** |
| `--smoke` | smoke-preset (~5 min, non-meaningful WAF) | off | for toolchain validation only |

**Prereq gates** (run before launch, refuse on failure):
1. `check_swap_off` — `/proc/swaps` empty.
2. `check_ocp_available` — drive responds to `nvme ocp smart-add-log` with PMUW.
3. `check_drive_isolation` — the measurement drive serial is what `/data` is actually mounted on.

## 5. Cell launch template

```bash
ssh root@157.180.98.112 '
  CELL_ID=T4-LF4h-$(date -u +%Y%m%dT%H%M%SZ)
  mkdir -p /data/results/$CELL_ID
  cd /root/waf-baseline-poc
  nohup .venv/bin/waf-baseline pilot \
    --cassandra-home /root/repos/fork/cassandra \
    --data-mount /data \
    --measurement-drive-serial S64FNE0R401522 \
    --results-dir /data/results \
    --workload ycsb_a_zipf_0.8 \
    --fill-fraction 0.04 \
    --ucs-scaling-parameters T4 \
    --measurement-window-s 14400 \
    --measurement-duration 4h10m \
    > /data/results/$CELL_ID/launch.log 2>&1 &
  echo "CELL_ID=$CELL_ID PID=$!"
'
```

The harness writes its own structured artifacts under `/data/results/<run>/` (see §6). Capture the wrapper's stdout/stderr in `launch.log` because the harness does **not** create that file itself.

## 6. Artifact layout (harness output)

Per pilot run, under `--results-dir`:

```
/data/results/<cell_id>/
├── bootstrap/                    # prereq + initial-state captures
├── warmup/                       # warmup samples
├── ycsb_a_zipf_0.8__fill_004__rep_1/
│   ├── cell.json                 # final WafResult
│   ├── stress.log                # cass-stress stdout
│   └── ycsb_a.csv                # cass-stress metrics csv (truncated by SIGTERM — use stdout summary, not CSV row counts)
└── summary.json                  # top-level wrapper summary
```

Cell IDs in `results.md` so far: `T4-HF-...`, `T8-HF-...`, `T16-HF-...` (R4 high-fill T-sweep). R5 uses `T4-LF4h-...`, `T16-LF4h-...`.

## 7. Pre-launch checklist (mandatory — see memory `feedback_monitor_silence_is_not_success`)

1. **rsync repos to rig** (`feedback_rsync_before_rig_launch`):
   ```bash
   rsync -a --delete --exclude '.venv' --exclude '__pycache__' --exclude '.git' --exclude '.pytest_cache' \
     /Users/samlightfoot/repos/waf-baseline-poc/ root@157.180.98.112:/root/waf-baseline-poc/
   rsync -a --delete --exclude '.venv' --exclude '__pycache__' --exclude '.git' \
     /Users/samlightfoot/repos/cassandra-agent-harness/ root@157.180.98.112:/root/cassandra-agent-harness/
   ```
2. **Reinstall on rig** (if dependencies / pyproject changed):
   ```bash
   ssh root@157.180.98.112 '/root/waf-baseline-poc/.venv/bin/pip install -q -e /root/cassandra-agent-harness -e /root/waf-baseline-poc'
   ```
3. **CLI sanity** on rig:
   ```bash
   ssh root@157.180.98.112 '/root/waf-baseline-poc/.venv/bin/waf-baseline pilot --help' | grep -E "ucs-scaling|measurement-window-s|fill-fraction"
   ```
4. **Drive state**:
   ```bash
   ssh root@157.180.98.112 'nvme ocp smart-add-log /dev/nvme1n1 | grep -E "Percent free|Physical media units written"'
   ```
   Record PMUW; verify `Percent free blocks` matches the regime you want.
5. **Cassandra state**:
   ```bash
   ssh root@157.180.98.112 'pgrep -af CassandraDaemon; /root/repos/fork/cassandra/bin/nodetool status 2>&1 | head -10'
   ```
6. **Launch with `nohup ... > launch.log 2>&1 &`**.
7. **10-second post-launch verify** — grep `launch.log` for `unrecognized arguments|usage:|error:|Traceback|Exception|command not found`; if process not in `pgrep -af waf-baseline`, abort.
8. **60-second workload-counter verify** — `launch.log` must show non-zero `ops/s` / `prefill` / `warmup` progress.

## 8. Reset between cells

The pilot runner soft-resets between cells by default: drop `cassandra_easy_stress`, force-drop SSTables, restart Cassandra. Verify after each cell:

- `du -sh /data/cassandra_easy_stress` → should not exist
- `df -h /data` → back to ~6 GB (system tables only)
- `nvme ocp smart-add-log /dev/nvme1n1 | grep "Percent free"` → record any drift

To force low-fill (e.g., between R4 high-fill and R5 low-fill): stop Cassandra, `umount /data`, `mkfs.ext4 -F /dev/nvme1n1p3` (no `-E nodiscard` so the format TRIMs the partition), `mount /data`, verify `Percent free blocks = 99`.

## 9. Cross-references

- Methodology + theory: `findings.md`
- Per-run measurements: `results.md`
- Headline conclusions: `summary.md`
- Session log: `progress.md`
- Active plan: `r5_plan_v2.md` (R5 in flight; supersedes `r5_plan.md`)
- Auto-memory facts the rig commonly hits:
  - `feedback_rsync_before_rig_launch` — always rsync before launching a bench
  - `feedback_monitor_silence_is_not_success` — grep broad failure patterns at +10s
  - `feedback_mkfs_ext4_trim_undoes_precondition` — TRIM gotcha
  - `feedback_cassandra_easy_stress_keyspace` — keyspace is `cassandra_easy_stress` not `keyvalue`
  - `feedback_cass_stress_csv_truncated` — use stdout, not CSV
  - `feedback_cassandra_drop_keyspace_snapshots` — `auto_snapshot: false` requirement
  - `feedback_cassandra_jar_rebuild` — `ant build` ≠ `ant jar`; verify class is in the jar
