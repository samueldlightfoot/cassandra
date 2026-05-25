# Operational Lessons — Reducing Time to First Meaningful Result

Accumulated learnings from getting the GDT investigation to a measurable
first result. Each entry is something that *would have saved hours* if known
at the start, framed as actionable rules for the next investigation.

Read this before designing a new comparison run. Each section is independent.

---

## 1. Calibrate flush rate BEFORE picking the GDT bucket width

**Rule:** measure actual flushes/minute from a short pilot run, then size GDT
`base_window_micros` so `bucket_width × flush_rate ≥ T` where T is the UCS
compaction threshold (4 for T4).

**Why it bit us:** initial guess of 60s bucket against an observed 1.3
flushes/min gave ~1.3 SSTables per bucket — below T=4. GDT silently under-fires
compactions. Output looks like a huge GDT win on `bytes_compacted` but it's
just deferral; SSTable count balloons. Misleading.

**How to apply:**
1. Quick 2-min pilot at intended rate + row size
2. Count flushes: `grep -c "Completed flushing" /path/to/system.log`
3. Pick `base_window_micros = (T × 2) × seconds_per_flush × 1_000_000` for ~2T flushes/bucket headroom

For our run: 1.3 flushes/min → 1 every 46s → bucket ≥ 4×46×2 = 368s. We use 300s.

---

## 2. Cassandra version-specific nodetool output

**Rule:** never trust that a `nodetool` field exists across versions. Verify on
the target build before depending on it.

**Why it bit us:** the parser looked for `Compacted:` in `nodetool info`.
That field doesn't exist in 6.0-alpha2. Result: `bytes_compacted_delta = 0`
for every condition in run #001, even though `nodetool compactionstats` showed
40+ GiB compacted. Cassandra-side DB WAF measurement was unusable.

**How to apply (for future investigations):**
- `nodetool compactionstats` has `data compacted` (cumulative bytes) — reliable since 4.x
- `nodetool tablestats` has per-table info but NOT cumulative bytes_compacted
- `nodetool info` is version-dependent — don't rely on specific fields
- For anything critical, query JMX directly via Jolokia: MBean
  `org.apache.cassandra.metrics:type=Compaction,name=BytesCompacted`

The library now parses `compactionstats` `data compacted` line. Fix is in
cassandra-agent-harness `capture/nodetool.py:_parse_bytes_compacted`.

---

## 3. easy-cass-stress quirks that produce silent failures

Three independent bugs each killed an early test run. None of them produced
a non-zero exit code — they just made the workload do nothing.

**Rule a:** `random(N, N)` throws because `ThreadLocalRandom.nextInt(min, max)`
requires `min < max`. Workers die silently → 0 writes. **Use `random(N, N+1)`.**

**Rule b:** `--rate 10000000` (10M) hangs the RateLimiterOptimizer's "stepping"
phase. Workers idle indefinitely. **Use ≤ ~500K for any practical rate cap.**

**Rule c:** invoking the binary via a symlink wrapper breaks easy-cass-stress's
JAR-discovery script (it does `cd $(dirname $0)/..` and `$0` is the symlink
path). **Always use the real binary path, not a symlinked copy.**

**Detection in the harness:** if `total_operations ≈ 0` after a workload claims
to have run, suspect one of these three before suspecting Cassandra.

---

## 4. Cassandra startup readiness — gossip vs CQL

**Rule:** `wait_ready` must probe both gossip (via `nodetool status`) AND the
native CQL port (TCP `9042`). Gossip-UN happens before CQL is listening.

**Why it bit us:** on a fast restart (warm caches, ~10s startup), Cassandra
reported UN to nodetool before CQL transport was accepting connections. The
workload immediately tried to connect → "Connection refused" → workload failed
in a few seconds instead of running for the configured duration.

The library now does both checks (`lifecycle/node.py:_native_transport_ready`).

---

## 5. Wipe data + commitlog dir between conditions

**Rule:** for any per-condition comparison, set `NodeConfig.clean_data_dirs=True`
so each condition starts disk-zero. Otherwise the previous condition's SSTables
+ compaction history bias the new condition.

**Symptom of forgetting:** the first condition's `disk_used_delta` ≈ user-bytes
(makes sense, fresh disk). Subsequent conditions' `disk_used_delta` ≈ small
number (the pre-snapshot already captured the previous condition's footprint).

We also need each condition's `cassandra_conf` to be a fresh per-condition
directory (already done — `cassandra_conf / condition.name`). System.log is
still shared across conditions (see #6).

---

## 6. system.log is shared across conditions (known limitation)

**Rule:** if you need to grep system.log for per-condition activity, use
TIMESTAMP filters, not assumption-of-isolation. Cassandra writes to its
default `cassandra_home/logs/system.log`, ignoring our per-condition conf
dir.

**Workaround:** the orchestrator captures stdout/stderr to per-condition
`conf-runs/<cond>/logs/cassandra.{stdout,stderr}.log` files. Those ARE
isolated. system.log contents must be sliced by timestamp.

**Fix candidate (deferred):** template a per-condition `logback.xml` that
redirects to a per-condition log path. Would need a small library change to
`_render_conf` and a logback template per investigation.

---

## 7. Rate-cap vs throughput-saturation distinction

**Rule:** if `ops_per_second ≈ --rate` exactly across conditions, throughput
differentiation is IMPOSSIBLE in that run. The cap is the bottleneck, not the
hardware. Reading p99 or latency comparisons is still valid; reading
throughput comparison is meaningless.

**For real throughput differentiation:** uncap (use rate ≥ 2× expected
hardware ceiling, e.g., 200K for a rig sustaining ~50K). Then `ops_per_second`
reflects actual saturation.

**For just measuring p99 / DB WAF under stress:** the cap is fine — it
normalizes load across conditions, which actually helps the comparison.

---

## 8. TWCS needs to span multiple windows to behave like TWCS

**Rule:** TWCS run duration must be at least `2 × compaction_window_size`.
Below that, all data falls in one window and TWCS degenerates to
STCS-within-window (often worse than UCS T4).

For our 15-min runs: `compaction_window_unit=MINUTES, compaction_window_size=5`
(gives 3 windows) would be a fair test. With our current
`compaction_window_unit=HOURS, compaction_window_size=1`, all 15 min of
data lives in one window — TWCS has nothing to differentiate.

Run #001's TWCS p99 (385ms, worse than baseline) is from this mis-configuration.
Note: for the reproducibility re-run we'll keep the same config so the result
is comparable to run #001 even if it's a flawed ceiling.

---

## 9. Run duration vs compaction event count

**Rule:** 15-min run at saturation generates ≥ 30 compaction events on this
rig. That's enough for meaningful comparison. Longer runs (30m, 1h) give more
data but the GDT effect is detectable already at 15m.

| Run duration | Approx. compactions | Disk peak |
|---|---:|---:|
| 5m | ~10 | 25 GB |
| 15m | ~40 | 75 GB |
| 30m | ~80 | 150 GB |
| 60m | ~160 | 300 GB |

15m is the sweet spot: enough events for measurement, fits comfortably in
255 GB free disk, finishes in ~50 min wall clock for 3 conditions.

---

## 10. Reproducibility check before broader conclusions

**Rule:** any single-run finding is a hypothesis until reproduced. Always
re-run the exact same config and check the result is within ~20% of the
first. Below that → noise. Stable → real signal.

For run #001's 9.73% p99 reduction: we need to see the re-run land in the
5-15% range to call it real.

---

## 11. Cleanup procedure between runs (consolidated)

The state that accumulates on the rig and needs wiping for a clean re-run:

```bash
# Per-condition data + commitlog (already auto-wiped by NodeConfig.clean_data_dirs=True)
rm -rf /var/lib/gdt-ucs/data/*
rm -rf /var/lib/gdt-ucs/commitlog/*

# Per-condition rendered config (orchestrator overwrites this anyway, but be explicit)
rm -rf /root/repos/fork/cassandra/conf-runs

# Cassandra's shared logs (NOT auto-cleaned — accumulates across runs!)
rm -f /root/repos/fork/cassandra/logs/system.log
rm -f /root/repos/fork/cassandra/logs/debug.log
rm -f /root/repos/fork/cassandra/logs/gc.log*

# Previous run's orchestrator log
rm -f /root/run2.log

# (Optional) old results dirs — keep recent ones for comparison
# ls -dt /root/repos/gdt-poc-harness/results/* | tail -n +10 | xargs rm -rf
```

Disk free should be > 200 GB before launching a 15-min × 3 run with 2KB rows.

---

## 12. What "ultimate confidence before launch" actually means

Pre-launch checklist that would have saved 4 of our 5 failed attempts:

1. **2-min smoke against same config** — confirms workload writes data, parser
   produces numbers. If smoke shows 0 ops or 0 writes, fail fast.
2. **`nodetool info` field presence check** — verify any nodetool field our
   parser depends on exists in the target Cassandra version.
3. **Disk free vs estimated peak** — `df -h` vs `rate × duration × row_size × 2`.
4. **Hardware ceiling vs rate cap** — if cap << hardware, we're rate-limited;
   if cap >> hardware, we're throughput-limited. Pick deliberately.
5. **GDT bucket calibration** — `bucket_width × flush_rate ≥ T`. If not,
   GDT under-fires.
6. **`cah check` or equivalent prereq sweep** on the rig — catches missing
   binaries, Python version, etc.

---

## 13. Hetzner consumer NVMe limits we've confirmed

For the rig (`65.108.227.158`, 2× Samsung MZVL2512HCJQ in RAID-1):

- **No OCP support** (confirmed: log pages 0xC0 and 0xC1 return INVALID_LOG_PAGE).
  Cannot measure SSD WAF directly. Standard SMART `data_units_written` only.
- **No FDP support** (consumer drive — FDP is datacenter feature).
- **No spare device** for `blkdiscard`-based experiments — both drives are in
  md2 RAID-1 carrying root + swap + boot.
- **Hardware ceiling ~50K ops/sec** for this workload (BasicTimeSeries, 64
  threads, 2KB rows). Higher rates probably feasible with tuning but 50K is the
  observed sustained rate.

For SSD WAF measurement, need to switch to a DC-NVMe Hetzner instance
(~€56/mo extra). Pending.
