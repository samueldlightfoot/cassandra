# R5 — Multi-hour T4 vs T16 at low fill (steady-state DB WAF)

## Goal

Measure DB WAF for T4 vs T16 with the compaction pyramid fully populated. R3/R4 cold-start runs were T-invariant because the 30-min window only fired 1-4 L0→L1 compactions and never reached L2. A 4-hour window at 5k ops/s ≈ 70 GB host-written, which should push the pyramid into L2/L3 territory for T4 and let T's write-amp savings appear.

This is the second potentially-insightful result of the investigation. The first (R4) showed SSD WAF stays at 1.0 even at percent_free=5. R5 tests whether DB WAF differentiates between T values once the pyramid is real.

## Conditions (decided 2026-05-28)

| parameter | value | rationale |
|---|---|---|
| Cells | T4, T16 only | Sharpest contrast; cuts wall time vs T4+T8+T16 |
| Run length per cell | 4 hours | ~70 GB written → enough for L1→L2 compactions at T4 |
| Fill regime | Low-fill (`percent_free_blocks ≈ 99`) | Isolates DB-side effect; SSD WAF behaviour at this regime already established (R2/R3) |
| Workload | YCSB-A 50/50 r/w, zipf 0.8, 1 KB values, 64 threads, 5k ops/s | Same as R2-R4 for comparability |
| Reset between cells | Drop keyspace + force-drop SSTables + restart Cassandra | Per existing reset_cassandra logic |
| Pre-launch discipline | Full (rsync + 10s verify + monitor) | Per memory's 4h-incident rules |

Total wall: ~8h for two cells back-to-back (plus reset overhead).

## Current rig state (observed 2026-05-28 17:04 UTC)

- `cassandra-waf-rig` (157.180.98.112), up 2 days
- Cassandra: inactive
- `/data`: 5.9 GB used / 829 GB partition (`/dev/nvme1n1p3`, ext4)
- `/commitlog`: 532K used / 894 GB (`/dev/nvme0n1`, ext4)
- **PMUW lifetime: 50.169 TB** (was 48.214 TB after install, so ~2 TB across R1-R4)
- **`percent_free_blocks = 5` ← drive is still in HIGH-FILL state from R4 preconditioning**

This is a problem for R5: we want low-fill. Two paths:

### Path A — Reset SSD to low-fill by TRIMming the partition

`mkfs.ext4` TRIMs by default (per `feedback_mkfs_ext4_trim_undoes_precondition`). We can intentionally use that property to restore `percent_free_blocks` to ~99:

```bash
ssh root@cassandra-waf-rig '
  systemctl stop cassandra 2>/dev/null
  umount /data
  mkfs.ext4 -F -L data /dev/nvme1n1p3      # NO -E nodiscard → TRIMs the partition
  mount /data
  nvme ocp smart-add-log /dev/nvme1n1 | grep "Percent free"
'
```

Expect `Percent free blocks = 99` afterwards. Verify before launch.

### Path B — Skip the reset, run R5 at high-fill

Cheaper but contaminates the comparison: would be conflating "steady-state pyramid" with "high SSD pressure." Reject.

**Decision: Path A.** TRIM the partition between R4 and R5, verify percent_free_blocks ≈ 99 before either cell.

## Pre-launch checklist (mandatory, per memory)

Before EACH cell launch (T4, then T16):

1. **rsync repos to rig**:
   ```bash
   rsync -av --delete /Users/samlightfoot/repos/waf-baseline-poc/ root@cassandra-waf-rig:/root/waf-baseline-poc/ --exclude '.venv' --exclude '__pycache__' --exclude '.git'
   rsync -av --delete /Users/samlightfoot/repos/cassandra-agent-harness/ root@cassandra-waf-rig:/root/cassandra-agent-harness/ --exclude '.venv' --exclude '__pycache__' --exclude '.git'
   ```
2. **Verify the CLI flag we depend on is present on the rig**:
   ```bash
   ssh root@cassandra-waf-rig 'cd /root/waf-baseline-poc && python -m waf_baseline --help 2>&1 | grep -E "ucs-scaling|duration"'
   ```
   Must show `--ucs-scaling-parameters` and whatever duration flag we use. If missing → STOP, do not launch.
3. **Verify drive state**:
   ```bash
   ssh root@cassandra-waf-rig 'nvme ocp smart-add-log /dev/nvme1n1 | grep -E "Percent free|Physical media units written"'
   ```
   Confirm `Percent free blocks = 99` (low fill) before cell start. Record lifetime PMUW for the run.
4. **Launch**:
   ```bash
   ssh root@cassandra-waf-rig 'cd /root/waf-baseline-poc && nohup python -m waf_baseline ... > /data/results/<cell-id>/launch.log 2>&1 &'
   ```
5. **10-second post-launch verification** (per `feedback_monitor_silence_is_not_success`):
   ```bash
   sleep 10
   ssh root@cassandra-waf-rig '
     pgrep -af waf_baseline || echo "DEAD: process not running"
     tail -20 /data/results/<cell-id>/launch.log
   '
   ```
   Must see: process alive, no `unrecognized arguments`, no `usage:`, no `error:`, no Python traceback, no "command not found." If ANY of these appear → STOP, do not arm monitor and walk away.
6. **Confirm workload counter movement at ~60s**:
   ```bash
   ssh root@cassandra-waf-rig 'tail -50 /data/results/<cell-id>/stress.log | grep -E "ops/s|writes_count"'
   ```
   Must show non-zero op rate. If zero → STOP.
7. **Arm the monitor with broad grep patterns** covering startup failures:
   - Success indicators: `MeasurementWindow closed`, `cells_succeeded=1`, `WafResult`, `cell.json written`
   - Failure indicators: `unrecognized arguments`, `usage:`, `error:`, `Traceback`, `Exception`, `OOMError`, `JVM crashed`, `connection refused`, `command not found`
   - Heartbeat: any one of `compaction completed`, `flush completed`, `ops/s=`

## Sequence

```
[R4 cleanup]
1. Stop cassandra on rig
2. mkfs.ext4 -F /dev/nvme1n1p3    # TRIM the partition → percent_free_blocks → 99
3. Mount /data, verify percent_free=99

[T4 cell — 4h]
4. Pre-launch checklist (rsync, --help check, drive state)
5. Launch T4 cell with --ucs-scaling-parameters T4 --duration 4h
6. 10s post-launch verification
7. 60s workload-counter verification
8. Arm monitor
9. Wait ~4.3h (4h window + flush/reset)

[Inter-cell reset]
10. Drop keyspace, force-drop SSTables, verify /data ≈ 6 GB (system tables only)
11. (Optional) mkfs again if percent_free drifted from 99

[T16 cell — 4h]
12. Pre-launch checklist (rerun)
13. Launch T16 cell with --ucs-scaling-parameters T16 --duration 4h
14. 10s + 60s verification
15. Arm monitor
16. Wait ~4.3h

[Collect]
17. Pull cell.json + stress.log + ycsb_a.csv for both cells
18. Append results to results.md as R5 entry
```

## Open items to verify BEFORE launching

These are things I haven't yet checked on the local waf-baseline-poc + rig state:

- [ ] Does waf-baseline-poc support `--duration` (or equivalent) for non-default run length? R3/R4 used 30-min windows; need to know what flag controls it.
- [ ] Does the cassandra.yaml on the rig still have `auto_snapshot: false` from R4? (Required for clean keyspace drop without filling /data with snapshots.)
- [ ] What's the memtable threshold actually set to? Plan assumes default ~256 MB → ~17 flushes per 30-min ≈ 137 flushes per 4h. If it's larger, flush count drops; if smaller, L0 fills faster.
- [ ] Confirm reset_cassandra logic between cells correctly drops the `cassandra_easy_stress` keyspace (per `feedback_cassandra_easy_stress_keyspace` — earlier defaults made reset a no-op).
- [ ] PMUW capacity check: ~70 GB per cell × 2 + commitlog writes + any compaction overhead = well under drive endurance, but record for the run log.

## Expected outcomes

- **If T4 DB WAF > T16 DB WAF** (e.g., T4 ≈ 2.0+, T16 ≈ 1.5): expected, validates UCS theory, gives us a publishable steady-state number alongside R4.
- **If both stay near 1.36**: 4h still wasn't long enough; pyramid hasn't built. Means either (a) increase to 8h, or (b) pre-populate keyspace.
- **If both rise to similar steady-state value**: surprising. Would suggest something other than T governs steady-state DB WAF (write rate, memtable threshold, etc.). Worth investigating.

## Risk register

| risk | mitigation |
|---|---|
| Forgot to rsync, T4 fails silently for 4h | Step 1 of pre-launch checklist + step 2 (`--help` check) catches this |
| Monitor grep too narrow, miss startup failure | Step 7 includes broad failure patterns covering known startup-fail modes |
| Drive ends R5 at high-fill if mkfs TRIM doesn't fire | Step 3 verifies `percent_free=99` before launch; if not, run `fstrim /data` or re-mkfs |
| `auto_snapshot=true` regression fills /data | Step listed in "Open items" — verify before starting T4 |
| Window ends mid-compaction → measurement noisy | Existing harness includes `nodetool flush` at window close; check it's still wired in |
| 4h too short for T to differentiate | Pre-stated as a possible outcome; if both numbers identical, escalate to 8h or pre-populate |

## After R5

Once T4 and T16 cells are recorded:
- Append `## R5` section to `results.md` with same structure as R2/R3/R4
- Update `summary.md` §5 conclusion if R5 produces a T-differentiation (currently summary says T-effect needs steady-state; R5 either confirms or refutes that)
- Update Future Work §8 — item 4 (steady-state DB WAF) is either done or escalated
- If R5 shows clean T-differentiation → potentially also publishable alongside the SSD WAF floor finding

## Files updated by this run

- `/Users/samlightfoot/repos/fork/cassandra/.claude/tasks/cassandra-waf-baseline/results.md` (new R5 section)
- `/Users/samlightfoot/repos/fork/cassandra/.claude/tasks/cassandra-waf-baseline/progress.md` (session log entries)
- Possibly `summary.md` (if R5 changes the headline)
- Rig: `/data/results/T4-LF4h-<timestamp>/` and `/data/results/T16-LF4h-<timestamp>/`
