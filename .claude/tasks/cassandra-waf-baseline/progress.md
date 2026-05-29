# Cassandra WAF baseline — progress

## 2026-05-26 — Task created (pre-bench)

- Pivoted from `../nowa-feasibility/` after recognising that the baseline measurement is (a) a strict prerequisite to any mechanism work and (b) a higher-confidence deliverable on its own (~90% chance of publishable artifact vs ~30% chance of 10% NoWA gain).
- Decision: target an ASF Cassandra Jira post as the primary deliverable. The artifact is a Cassandra-on-PM9A3 WAF characterization, not a mechanism implementation.
- Drafted full `task_plan.md` (7 phases, Gates A/B/C, 3-4wk calendar) and `findings.md` (methodology + decision tree + paper-comparability anchors).
- `nowa-feasibility` task to be marked as gated on the outcome of this measurement (will note in that task's progress).
- MEMORY.md to be updated with a pointer to this task.

## Status

- **Phase 1 (Rig + OCP)**: **Gates A + B PASSED 2026-05-26.** Details below.
- **Phase 2 (Harness)**: not started. Can begin now that Gate B confirms drive viability.
- **Phases 3–7**: not started.

## 2026-05-26 — Phase 1 results

**Rig**: Hetzner HEL1 auction box, IPv4 157.180.98.112, root.
- CPU: Intel Xeon E-2276G, 6c/12t, 3.8 GHz base / 4.9 GHz boost
- RAM: 62 GiB ECC (no swap by default in rescue)
- 2× NVMe drives, no RAID configured
- Power-on hours: 41,447 (4.7 cumulative years from previous tenants — auction box, sanitized but used)

**Drive identity (Gate A — drive variant confirmed)**:
- nvme0n1: SAMSUNG `MZQL2960HCJR-00A07`, S/N S64FNE0R401522, firmware `GDC5A02Q`, NVMe 1.4
- nvme1n1: SAMSUNG `MZQL2960HCJR-00A07`, S/N S64FNE0R401526, firmware `GDC5A02Q`, NVMe 1.4
- Both confirmed **PM9A3 960 GB U.2** — same family + same SKU as the paper's primary benchmark drive
- Marketing 960 GB / lsblk 894.3 GiB (just SI vs binary; this is the standard 7% OP variant)
- `nvme list` shows 1.15 GB pre-existing namespace utilization (sanitized leftover from prior tenant)
- Both drives in matched state (firmware, capacity, similar lifetime metrics)

**OCP capability (Gate B — PASSED)**:
- `nvme ocp smart-add-log` returns full structured data on both drives
- OCP log page version **2** (= OCP 2.0 compliant, better than 1.0 per agent research)
- Log page GUID: `0xafd514c97c6f4f9ca4f2bfea2810afc5`
- Lifetime PMUW (nvme0n1): 48,196,878,213,120 bytes = 48.2 TB
- Lifetime PMUW (nvme1n1): 44,209,317,171,200 bytes = 44.2 TB
- Both drives: Percent free blocks = 99
- PLP working, 0 incomplete shutdowns, available_spare 100%, percentage_used 3%
- 2.1% of rated endurance used → ~98% lifetime remaining (plenty for the bench)

**Lifetime SSD WAF (incidental)**: nvme0n1 = 48.2 TB physical / 41.77 TB host = **1.154**. Not Cassandra-specific but useful calibration — the drive has not lived a SSD-WAF-friendly life under previous tenants.

**PMUW movement test (Gate B confirmation — PASSED with very high precision)**:
- Method: `fio --rw=write --bs=1M --size=10G --direct=1 --buffer_compress_percentage=0 --refill_buffers --end_fsync=1` against `/dev/nvme0n1` raw
- Throughput: 1441 MiB/s = 1511 MB/s sustained, 7.1s wall time
- ΔPMUW = 10,737,418,240 bytes = **exactly 10.0 GiB** (10 × 2^30)
- Δhost data_units_written = 10,737,664,000 bytes ≈ 10.0 GB
- **SSD WAF for the test = 1.000 (within 0.003%)** — exact expected behaviour for sequential write to a 99%-free drive
- NUSE moved 1.15 GB → 11.5 GB, confirming write actually landed

**Verdict**: Gates A + B both passed. Drive identity is the ideal case (PM9A3 → direct paper comparability), OCP is functional with high precision, sustained sequential write throughput is within PM9A3 spec.

## RAID + disk layout decision (load-bearing for the writeup)

Confirmed 2026-05-26: **no RAID, two drives split data/commitlog** for the bench.

### Final layout

| device | partition | size | mount | purpose |
|---|---|---|---|---|
| `/dev/nvme0n1` | p1 | 1 GiB | `/boot` | kernel + grub |
| `/dev/nvme0n1` | p2 | 50 GiB | `/` | OS root, system logs, apt cache |
| `/dev/nvme0n1` | p3 | ~843 GiB | `/data` | Cassandra `data_file_directories` — **this is the measurement device for OCP/SMART** |
| `/dev/nvme1n1` | raw → ext4 | ~894 GiB | `/commitlog` | Cassandra `commitlog_directory` |

- No swap
- No software RAID (`SWRAID 0` in installimage config)
- ext4 with `noatime` mount option on `/commitlog`; installimage default options on `/data` (will revisit before measurement runs)

### Why two drives instead of RAID-0 or mirror

This is the choice the writeup needs to defend. Reasoning:

1. **Clean per-drive OCP attribution**. PMUW + `data_units_written` are read per-device. On `/dev/nvme0` we see ONLY Cassandra's SSTable writes (memtable flush + compaction output + system tables). Commitlog's continuous sync-append stream is on a different physical device and doesn't contaminate the measurement.
2. **Direct paper comparability**. Lee/Ziegler/Leis Table 1 reports single-drive SSD WAF on PM9A3. Our `/dev/nvme0` number is comparable to theirs without RAID-layer reasoning.
3. **No md/dm layer between Cassandra DIO and the device**. The investigation has explicitly worked to remove indirection (DIO compaction, no swap, fresh mkfs). Introducing a stripe or mirror layer would partially undo this.
4. **Commitlog-on-separate-device is a known measurement-killer mitigation**. Per `../nowa-feasibility/findings.md` §10 (G5), co-located commitlog can contribute 30–50% of total write volume with frequency imbalance against compaction writes. The whole point of NoWA-style discipline assumes the data device sees an undisturbed write stream — same applies for *measuring* the baseline.
5. **RAID-0 would split the SSTable write stream across two SSDs at stripe granularity**. Each drive sees half the writes at half the granularity — not what Cassandra would do natively. SSD WAF becomes a function of stripe size, not Cassandra's write pattern.

### Caveats to disclose in the writeup

- Single-drive layout sacrifices redundancy. This is a bench rig, not a production deployment. State explicitly that "production Cassandra typically uses RAID or multiple data directories, and SSD WAF behaviour on those topologies may differ from this single-drive measurement."
- Commitlog WAF is NOT measured by this design — only data-drive WAF. The OCP delta on `/dev/nvme1` will include commitlog if we capture it, but the headline is the `/dev/nvme0` number.
- Single-host result. No replication / streaming WAF measured here.

## 2026-05-26 — Phase 1 complete

### Installed OS state

- Ubuntu 22.04.5 LTS, kernel 5.15.0-168-generic
- Hostname: `cassandra-waf-rig`
- SSH: root key-auth only (password auth disabled), ed25519 pubkey installed
- Tooling: `nvme-cli 2.16` (built from source — see "Operational debt" below), `smartctl 7.2`, `fio 3.28`, plus `tmux htop curl jq`
- No swap (`swapoff -a` + fstab purged of swap entries)

### Drive identity vs device naming — **important caveat**

After reboot from rescue → installed OS, Linux re-enumerated the NVMe PCI devices and **swapped their kernel device names**. Mounts are unaffected (fstab uses UUIDs) but device paths have shifted:

| Physical drive (S/N) | rescue path | installed-OS path | role |
|---|---|---|---|
| `S64FNE0R401522` | `/dev/nvme0n1` | `/dev/nvme1n1` | **Measurement device** — `/data` lives on `/dev/nvme1n1p3` |
| `S64FNE0R401526` | `/dev/nvme1n1` | `/dev/nvme0n1` | Commitlog device — `/commitlog` on `/dev/nvme0n1` |

**For OCP measurement: target `/dev/nvme1n1` (or `/dev/nvme1`).** The PMUW counter on that drive carries the history of the rescue-time PMUW test (verified: lifetime PMUW = 48,214,167,187,456 bytes vs 48,207,615,631,360 at end of rescue test → +6.1 GiB delta from install + first-boot operations, exactly as expected).

The harness must reference drives by **serial number, not device path**, to avoid mistakes if device names shift further (e.g., across kernel updates). Plan: add an `OcpReader` constructor that takes a S/N and resolves it to the current device path via `nvme list` lookup.

### Final mount layout (verified)

```
/dev/nvme1n1p1  1.0 GiB   /boot       ext4 defaults
/dev/nvme1n1p2  50 GiB    /           ext4 defaults
/dev/nvme1n1p3  843.3 GiB /data       ext4 defaults,noatime   ← Cassandra data dir, MEASUREMENT DEVICE
/dev/nvme0n1    894.3 GiB /commitlog  ext4 defaults,noatime   ← Cassandra commitlog, ISOLATED
```

Fstab `findmnt --verify` clean.

### OCP verification on installed OS

- `nvme ocp smart-add-log /dev/nvme1n1` returns full structured output (Physical Media Units Written, Read, Percent free blocks, etc.)
- Log page version 2 (OCP 2.0)
- PMUW counter live, matches expected post-install increment

### Operational debt — encode in setup automation

Ubuntu 22.04 ships nvme-cli 1.16 which **lacks the OCP plugin**. To get OCP smart-add-log working we had to:

1. `apt install python3-pip meson ninja-build pkg-config libjson-c-dev libhugetlbfs-dev uuid-dev libssl-dev gcc git python3-dev`
2. `pip3 install --upgrade --no-cache-dir meson` (Ubuntu's meson 0.61 is too old; libnvme needs ≥ 0.62; installs to `/usr/local/bin/meson`)
3. `git clone --depth 1 -b v1.16 https://github.com/linux-nvme/libnvme.git`
4. `meson setup --prefix=/usr .build && ninja -C .build && ninja -C .build install && ldconfig`
5. `git clone --depth 1 -b v2.16 https://github.com/linux-nvme/nvme-cli.git`
6. `meson setup --prefix=/usr .build && ninja -C .build && ninja -C .build install`

**Fallback (no nvme-cli upgrade needed)**: `nvme get-log /dev/nvme1n1 -i 0xc0 -l 512 -b` returns the raw OCP log page bytes. The harness can parse this directly per OCP DC SSD Spec — avoids the build-from-source dependency. Worth implementing this path in the harness for portability.

### Phase 1 outcome — Gates A + B PASSED, Phase 1 closed

Ready to begin Phase 2: harness OCP instrumentation.

## 2026-05-26 — Phase 2 progress (harness OCP instrumentation)

Library work, not the GDT app — these are reusable across investigations and land in `cassandra-agent-harness` (commit `a7cbaeb` on `main`):

### OCP/SMART capture
- `cassandra_agent_harness.capture.ocp` rewritten:
  - **Real nvme-cli 2.x JSON field names** (existing scaffold used snake_case keys that don't match real output — silent regression caught by adding fixture-validated tests). Real format: `{"Physical media units written": {"hi": N, "lo": N}}` with spaces and nested hi/lo.
  - **Raw binary log page fallback** via `nvme get-log -i 0xc0 -l 512 -b` — required because Ubuntu 22.04's stock nvme-cli 1.16 lacks the OCP plugin (building 2.x from source is fine for the rig but not portable).
  - **`find_device_by_serial()`** for stable device resolution. The rig observed nvme0n1↔nvme1n1 swap between rescue and installed OS due to PCI re-enumeration; path-based references would have broken silently. Harness always resolves S/N → device at runtime.
- `cassandra_agent_harness.capture.smart`: helpers added for `data_units_read_bytes`, `percent_used`, `available_spare` (useful for prereq checks).
- `cassandra_agent_harness.capture.waf`: new `compute_waf()` returning a `WafResult` dataclass with all three layers (SSD WAF, DB WAF, Total WAF). Each layer independently `None` when its inputs are absent, so partial inputs produce partial results rather than failure.

### Prereq checks
- `check_swap_off(swaps_path=...)` — reads `/proc/swaps`; testable via the arg.
- `check_ocp_available(serial)` — resolves S/N → device → OCP snapshot, validates PMUW non-zero. The gating check for any WAF measurement: if it fails, refuse to launch the bench.

### Test fixtures
Four real fixtures captured from the rig (`tests/capture/fixtures/`):
- `ocp_pm9a3_nvme_cli_2_16.json` — actual nvme-cli 2.16 OCP JSON output
- `ocp_pm9a3_raw_logpage.bin` — raw 512-byte binary log page from same drive ~1s later
- `smart_pm9a3_nvme_cli_2_16.json` — standard SMART log
- `nvme_list_pm9a3.json` — for testing S/N-based device resolution

Tests validate parser against these real fixtures — guards against the "looks right but doesn't match real output" failure mode that the original scaffold hit.

### Status
- **116/116 library tests passing.**
- Library committed + pushed to `origin/main` (`a7cbaeb`).
- **End-to-end smoke test PASSED on real rig** (2026-05-26):
  - `check_swap_off` → ok
  - `check_ocp_available("S64FNE0R401522")` → ok, resolves to `/dev/nvme1n1`, PMUW=48.22 TB lifetime
  - `check_ocp_available("S64FNE0R401526")` → ok, resolves to `/dev/nvme0n1`, PMUW=44.23 TB lifetime
  - Live data-drive snapshot: SSD WAF = **1.1539** (lifetime, prior-tenants history — not Cassandra-specific yet)

### Rig setup additions discovered during smoke test (capture in installimage automation)

- Ubuntu 22.04 ships Python 3.10; library requires Python 3.11+ (uses `StrEnum`). Two options on the rig:
  - Install Python 3.11 via deadsnakes PPA (used here): `add-apt-repository -y ppa:deadsnakes/ppa && apt install -y python3.11 python3.11-venv python3.11-dev`
  - Or relax the library `requires-python` to `>=3.10` and replace `StrEnum` with `enum.Enum + str` mixin (not done; deadsnakes path is acceptable)
- **Do NOT install `.[dev]` on the rig.** `ruff` has no prebuilt wheel for Ubuntu 22.04 + Python 3.11 from deadsnakes; pip tries to compile from Rust source which requires a toolchain we don't want to install. The first attempt spun for 26 min at 99% CPU before being killed. Use plain `pip install -e .` on the rig; run dev tooling (pytest, ruff) only on the developer machine.
- `python3-venv` is not installed by default on Ubuntu 22.04. Add `python3.11-venv` (or `python3-venv`) via apt in the rig setup automation.

## 2026-05-26 — Phase 3 partial: measurement primitives landed

Library commit `891a0b7` on `origin/main`. Two foundational primitives:

### `MeasurementWindow` (cassandra_agent_harness.capture.measurement)
Context manager bracketing a section of bench execution with pre/post OCP+SMART snapshots + a background sampler thread polling counters at a configurable cadence. Final `MeasurementWindowResult` bundles bracket deltas (the headline WAF) plus the time series (steady-state detection + transient diagnostics).

Key contract:
- `__enter__` takes T0 snapshots, starts sampler
- `__exit__` guarantees sampler is stopped (10s join timeout)
- `finalise(client_payload_bytes=...)` produces the `WafResult` — idempotent, must be called inside the with block to capture the post snapshot
- Sampler errors recorded per-sample, not propagated — transient OCP issues don't kill the bench
- `latest_waf_estimate()` and `waf_time_series()` for in-window introspection (used by steady-state detector)

### `is_steady_state` (same module)
Pure function over `(timestamp, ssd_waf)` series. Returns True when the last N samples agree within tolerance of each other. Used during pre-measurement warmup to make the "wait for free-block pool to settle" rule adaptive instead of fixed.

### Testing
15 new tests including threading-sensitive ones (sampler liveness, sampler-error resilience, finalise idempotency) using mocked snapshot fns + very short intervals to avoid real sleep. **Full library suite: 131/131 passing.**

### Phase 3 remaining
- [x] Pre-fill helper — runs easy-cass-stress write-only until target fill ratio
- [x] Cassandra clean-state reset helper — drops keyspace + wait for compaction drain between cells
- [x] `check_drive_isolation` prereq (deferred from Phase 2)
- [x] `WafBaselineInvestigation` class wiring everything together (probably a new app, since GDT is parked)
- [ ] Workload specs for YCSB-A zipf 0.8 + TWCS time-series — **TODOs explicit in runner**, deferred to Phase 4 (pilot needs them anyway)

## 2026-05-27 — Phase 3 closed (library substrate complete)

All five Phase 3 library primitives landed across four commits on cassandra-agent-harness origin/main. Library tests: 116 (Phase 2) → 175 (Phase 3 end), zero regressions.

| commit | scope |
|---|---|
| `891a0b7` | MeasurementWindow + is_steady_state |
| `33c8373` | check_drive_isolation + serial_for_device + parent_namespace_path |
| `c94004e` | bench module: reset_cassandra + prefill_to_target |
| `b6e774c` | WafBaselineRunner scaffold + matrix shape |

### What's wired vs what's stubbed

**Wired** (testable + verified on live rig where applicable):
- All capture primitives (OCP/SMART/WAF math/MeasurementWindow)
- All prereq checks (swap_off, ocp_available, drive_isolation)
- All bench helpers (reset_cassandra, prefill_to_target, wait_for_compaction_quiet)
- WafBaselineRunner matrix shape (cell ordering, persistence, error capture)

**Stubbed in WafBaselineRunner with explicit TODO markers** — investigation-app-level decisions:
- `_build_measurement_workload(workload_name)` — needs YCSB-A profile + TWCS schema decisions
- `_build_prefill_workload(target)` — needs write-only profile decision
- `_launch_workload_async()` + `_stop_workload()` — easy-cass-stress is currently sync-invoked in `workload.easy_stress.run_easy_stress`; for the warmup→measure pattern we need an async variant. Either extend the existing function or construct the subprocess directly inline.

These belong in a new `waf-baseline-poc` app that imports the library scaffold — same pattern as `gdt-poc-harness` extends the library for GDT investigation.

### Phase 4 kickoff items (next session)

1. **Create `waf-baseline-poc` app** that imports `WafBaselineRunner` and supplies the four TODO methods with concrete decisions
2. **Workload profile decisions**:
   - YCSB-A: KeyValue workload, zipf 0.8, 50/50 read/write, 1 KB rows, 1M partitions — matches paper config
   - TWCS: BasicTimeSeries workload, time-window compaction, monotonic timestamps
   - Pre-fill: same KeyValue with read_ratio=0, deletes off
3. **Async workload launcher** — likely simplest path is to construct the easy-cass-stress subprocess directly in the runner and stash a Popen handle for SIGTERM-on-stop
4. **Pilot run** on the rig: single cell (YCSB-A at 80% fill) to validate the full toolchain end-to-end before committing to the matrix

## 2026-05-27 — Phase 4 progress: async launcher + app scaffold landed

### Library: async workload launch
Commit `fe61017` on `cassandra-agent-harness:main`:
- `launch_easy_stress_async(spec, output_dir) → WorkloadHandle` alongside `run_easy_stress`
- `WorkloadHandle.stop(sigterm_timeout=30s)` — SIGTERM → wait → SIGKILL escalation, idempotent if the process already exited naturally, closes stdout fd
- Wired into `WafBaselineRunner._launch_workload_async` + `_stop_workload` — two of the four runner TODOs eliminated
- 7 new tests including the SIGKILL fallback path; library 175 → 182

### App: waf-baseline-poc (local repo at `/Users/samlightfoot/repos/waf-baseline-poc`)
Initial commit `347e846`. Investigation-app layer subclassing `WafBaselineRunner`:
- `workloads.py`: spec factories for YCSB-A (KeyValue, 50/50, UCS T4) + TWCS (BasicTimeSeries, 95/5, TWCS 1h) + prefill (write-only variant of either)
- `runner.py`: `WafBaselinePoc(WafBaselineRunner)` overriding the two remaining workload-spec TODOs
- `cli.py`: `waf-baseline pilot|matrix` with all three Gate B prereqs (swap_off, ocp_available, drive_isolation) enforced before any cell runs
- 29 tests passing locally

**No remote yet** — repo lives only on the dev box. Push to a github.com/samueldlightfoot/... fork when ready.

### What's now possible
The full pipeline — prereqs → reset → prefill → warmup-to-steady-state → measurement → persist — can run end-to-end with concrete workload specs. Pilot mode `waf-baseline pilot --workload ycsb_a_zipf_0.8 --fill-fraction 0.80` is the natural next step.

### Phase 4 next step

**Run the pilot.** Single cell, validates the full toolchain end-to-end on the live rig. Need to:
1. Install Cassandra on the rig (currently the rig is set up but has no Cassandra checkout)
2. Build Cassandra (ant jar)
3. Install easy-cass-stress on the rig
4. rsync waf-baseline-poc + library to the rig + install
5. Run `waf-baseline pilot` and watch what happens

The pilot is bounded — even at a long pre-fill + warmup + 30 min measurement, single cell ≤ 3-4 hours wall. If it works, we proceed to the matrix. If it breaks somewhere, we know what to fix.

## 2026-05-27 — Phase 4 continued: rig fully prepped, pilot ready to launch

### Naming correction (load-bearing)
User confirmed: `cassandra-easy-stress` is the NEW name (post-Apache donation); `easy-cass-stress` is the OLD name. Earlier memory had the labels inverted. Library + app default binary corrected to `cassandra-easy-stress`. Library commit `6a65a4d` on `cassandra-agent-harness:main`; app commit `da28d11` (local).

### Rig setup (157.180.98.112)
All four prerequisites for the pilot are in place:

| component | location | notes |
|---|---|---|
| OpenJDK 17 + ant | apt-installed | builds Cassandra |
| Cassandra fork | `/root/repos/fork/cassandra` | branch `fdp-poc`, built via `ant jar`, JAR is `apache-cassandra-7.0-SNAPSHOT.jar` (the `base.version` was bumped to 7.0 in an earlier commit; expected) |
| `cassandra-easy-stress` | `/root/repos/cassandra-easy-stress` | gradle `shadowJar` build; fat jar at `build/libs/cassandra-easy-stress-10-all.jar` |
| PATH wrapper | `/usr/local/bin/cassandra-easy-stress` | shell script that `exec`s the absolute path — symlink failed because the launcher script uses `dirname $0` and only works from its install directory |
| Library + app installs | `/root/cassandra-agent-harness/.venv` | editable installs of both; `waf-baseline --help` works |

### Cassandra config edits (rig-local, not in the source repo)

`/root/repos/fork/cassandra/conf/cassandra.yaml` edited to point at our split-drive layout:

| key | value |
|---|---|
| `data_file_directories` | `[/data]` |
| `commitlog_directory` | `/commitlog` |
| `hints_directory` | `/data/hints` |
| `saved_caches_directory` | `/data/saved_caches` |

Other settings stay default. Original at `conf/cassandra.yaml.orig` for rollback.

### Boot verification
Cassandra started cleanly on the rig:
- `nodetool status` returned `UN` (Up/Normal) within ~30s
- `/data` accumulated 740K (system keyspace data), `/commitlog` 184K (commit log)
- Drained + stopped cleanly via `nodetool drain` + SIGTERM

### Pilot procedure
Documented in `waf-baseline-poc/RUNBOOK.md`. Top-line:
```bash
# 1. Start Cassandra (wait for UN)
nohup ./bin/cassandra -f -R > /data/logs/cassandra.stdout 2> /data/logs/cassandra.stderr &

# 2. Run pilot
.venv/bin/waf-baseline pilot \
    --cassandra-home /root/repos/fork/cassandra \
    --data-mount /data \
    --measurement-drive-serial S64FNE0R401522 \
    --results-dir /data/results/pilot-$(date -u +%Y%m%dT%H%M%SZ) \
    --workload ycsb_a_zipf_0.8 \
    --fill-fraction 0.80 \
    --measurement-duration 30m

# 3. nodetool drain + kill -TERM after the pilot finishes
```

Expected wall time: **~3-4 hours total** (1.5-2.5h pre-fill, <1h warmup typically, 30 min measurement). Operator should monitor `tail -f /data/logs/cassandra.stdout` and the harness stdout.

### Phase 4 status: ready to launch
All technical pieces in place. The pilot itself is operator-triggered when ready — long-running and needs monitoring. After the pilot returns a clean `WafResult`, we have validation of the full toolchain and can decide whether to commit to the 24-cell matrix.

## 2026-05-27 — Phase 4 smoke run on rig: PIPELINE VALIDATED

Before committing to a 3-4h real pilot, ran a `waf-baseline pilot --smoke --fill-fraction 0.055` end-to-end on the live rig to validate every code path in ~90 seconds. **The pipeline works.**

### Two bugs surfaced + fixed

1. **`_run_workload` defaulted to None**, so the pre-fill loop's `write_chunk()` callback raised NotImplementedError. Library fix: default to the synchronous `run_easy_stress` from the workload module. Pre-fill needs blocking semantics so the loop can observe completed writes before re-checking fill.

2. **Pre-fill chunk duration was hardcoded to "60m"** in `workloads.prefill_workload()`. Each iteration ran cass-stress for an hour before the harness's fill loop got to re-check anything — first smoke attempt timed out after iter 13/80 with the cell unfinished. Fix: added `WafBaselineConfig.prefill_chunk_duration` (default `"5m"` for production; CLI `--prefill-chunk-duration`); `--smoke` preset overrides to `"30s"`.

### Smoke run result (`cell.json` from `/data/results/smoke-20260527T122004Z/`)

```
prereqs:      all 3 PASS (swap_off, ocp_available, drive_isolation)
reset:        dropped [keyvalue, sensor_data], compaction drained in 2.34s
prefill:      iterations=0 (used 50.4 GB >= target 48.9 GB, bailed)
measurement:  60s window, 12 OCP samples
RESULT:       SSD WAF = 1.20 (host 2.05 MB Δ -> PMUW 2.46 MB Δ)
              DB WAF = None  (client_payload_bytes not wired yet)
              Total = None
success:      True
elapsed:      92.4s
```

The 1.20 SSD WAF is **not** a meaningful baseline number — warmup never reached steady state in 30s (timed out on safety net) and prefill was a no-op. But the number is REAL: it came from real PMUW counters reading a real workload writing to a real PM9A3.

### What this validates
- All three Gate B prereqs pass on real hardware
- Reset (cqlsh DROP + nodetool flush + compaction-quiet wait) works
- Pre-fill loop logic (bail-on-target, iterate, history tracking)
- Async workload launch via `WorkloadHandle` (SIGTERM on stop)
- MeasurementWindow with periodic sampler thread
- OCP PMUW delta + SMART data_units_written delta → SSD WAF computed correctly
- Cell artifact persistence (cell.json with raw OCP snapshots preserved)
- Summary.json + result-dir layout

### What's NOT validated by smoke
- Steady-state detection (warmup needed only 4 samples but had ≤3 before timeout)
- Long pre-fill at production target (would have taken ~60min in smoke; works in principle since iter 13 was healthy progress)
- Client payload byte tracking → DB WAF + Total WAF (TODO documented in runner)
- High-fill SSD WAF behaviour (smoke ran at ~5.7%, production target is 80%+)

### Commits
- Library `531694e` on `cassandra-agent-harness:main` — pushed
- App `5332a4a` on `waf-baseline-poc` (local) — CLI knobs + --smoke preset

### Phase 4 next step
The **real pilot is now a tested code path**. Two follow-ups before committing to it:
1. Wire `client_payload_bytes` from the cass-stress output CSV into the WafResult so DB WAF + Total WAF land
2. Optionally: a longer smoke at moderate fill (e.g., 10% target, --warmup-max-s 600) to validate steady-state detection actually fires

Then: real pilot at YCSB-A 80% fill (~3-4h wall, operator-monitored).

## 2026-05-27 — client_payload_bytes wired + prometheus port collision fixed

Library commit `99e04ff` on `cassandra-agent-harness:main`.

### Three changes:

1. **`client_payload_bytes` now lands in WafResult.** `_warmup_then_measure` captures the workload summary from `WorkloadHandle.stop()`, extracts `writes_count`, multiplies by new `WafBaselineConfig.row_size_bytes` (default 1024). DB WAF and Total WAF now compute end-to-end.
2. **`CellArtifacts.measurement_workload_summary` field**: persists the workload's parsed summary (writes_count, reads_count, ops/sec, p99_latency_ms) into `cell.json` alongside the WAF numbers — needed for analysis + the eventual Jira post.
3. **Prometheus port collision fix**: cass-stress's Prometheus HTTPServer binds default port 9500 without SO_REUSEADDR. Back-to-back warmup→measurement launches collided on TIME_WAIT and the second JVM crashed with BindException. Workaround in `_launch_workload_async`: each invocation gets a fresh `--prometheusport` from a monotonic counter (starts at 19500, increments per launch).

### Smoke re-run after fixes (cell.json from `/data/results/smoke-20260527T130244Z/`)

```
Workload summary:
  writes_count:        74,441
  reads_count:         75,076
  ops_per_second:      1924.22
  p99_latency_ms:      0.35
  total_operations:    149,517

WafResult:
  host_bytes_written:   2,560,000  (Δ in 60s on /data drive)
  physical_bytes:       2,535,424  (Δ PMUW on same drive)
  client_payload_bytes: 76,227,584 (74441 × 1024)
  SSD WAF = 0.9904
  DB WAF  = 0.0336
  Total   = 0.0333
```

**Honest read of the smoke numbers:**
- SSD WAF ≈ 1.0 is exactly what we expect at this fill level (99% free blocks, drive's free-block pool is fat, no internal GC pressure)
- DB WAF < 1.0 is real but **misleading** at this short (60s) window: commitlog is on a separate device, and most of the 74K writes are still in the memtable waiting for flush. They haven't hit `/data` yet. At a real 30-minute steady-state window this resolves into a meaningful number because the memtable-flush cycle reaches equilibrium and Δhost on `/data` ≈ bytes the workload actually pushed through to SSTables.

**Validated end-to-end with real WAF computation**: every code path between Gate B prereqs and persisted `cell.json` works on real PM9A3 hardware including the DB-WAF math.

### Phase 4 status
Pipeline is fully tested. Real pilot at YCSB-A 80% fill is now a tested code path; operator can fire it when ready per `waf-baseline-poc/RUNBOOK.md`.

## 2026-05-27 — First real 30-min WAF measurement (after orphan-JVM fix)

### Bug found in production: orphaned JVMs after _stop_workload()

First 30-min run (`window30-20260527T130837Z`) revealed that cassandra-easy-stress's launcher script ends with `java -jar` (NOT `exec java -jar`), so the JVM is a bash *child* not a bash replacement. SIGTERM to the bash leader killed bash but left the JVM orphaned and writing. The warmup JVM (port 19500) ran for the entire 22-minute span of the subsequent measurement window, contaminating Δhost on /data.

Library fix (`cassandra-agent-harness:main` commit `3e28c41`):
- `launch_easy_stress_async` Popen now uses `start_new_session=True`
- `WorkloadHandle.stop` uses `os.killpg(getpgid(pid), SIGTERM)` then SIGKILL, signaling the whole process group instead of just the bash leader
- 3 new tests; library: 183 → 186

Verified the fix by re-running and observing no orphan JVMs in `ps`.

### Clean v2 result (`window30v2-20260527T135616Z`)

Same conditions as v1, this time without contamination:

| metric | v1 (contaminated) | v2 (clean) |
|---|---:|---:|
| writes_count | 4,425,126 | 4,423,663 |
| ops_per_second | 5000.01 | 5000.0 |
| p99_latency_ms | 39.8 | 0.68 |
| host_bytes_written | 17.40 GB | 11.89 GB |
| physical_bytes (PMUW) | 17.40 GB | 11.89 GB |
| client_payload_bytes | 4.53 GB | 4.53 GB |
| **SSD WAF** | 1.0001 | **0.9999** |
| **DB WAF** | 3.84 | **2.62** |
| **Total WAF** | 3.84 | **2.62** |

The p99 latency in v1 was 39.8 ms (terrible) vs 0.68 ms in v2 (excellent) — direct confirmation that the orphan workload was creating contention. DB WAF dropped from 3.84 → 2.62 (≈ 32% lower) which is consistent with the orphan contributing ~half the host writes for ~22/30 of the window.

### What v2 means (and what it doesn't)

This is the **first real, defensible WAF measurement on the rig**. Caveats:
- Single replicate, single workload (YCSB-A zipf 0.8, 50/50 r/w, 1KB rows)
- Low fill (~7% — accumulated from prior tests; still far below paper's 90% condition)
- Steady state never reached during warmup (10-min cap fired; at this throughput on a near-empty drive the SSD's free-block pool depletion is far slower than 10 min)
- Single 30-min window, no replicates

But the **shape is right**:
- SSD WAF ≈ 1.0 at low fill (free-block pool is enormous, no GC pressure) — exactly what Lee/Ziegler/Leis predict
- DB WAF ≈ 2.6 for UCS T4 — reasonable for LSM (flush 1x + compaction 1.5-2x + index/stats files)
- Total WAF dominated by the DB layer at this fill — also expected; the SSD's contribution scales with fill

### Throughput concern for the real pilot

At 5 MB/s client × 2.6 DB WAF = ~13 MB/s on /data. Growing /data from 23 GB to 80% (~665 GB) would take ~14 hours of pure prefill at this rate. The current prefill workload config (KeyValue, 64 threads, 1M partitions, default rate-limit) isn't fast enough for a 24-cell production matrix.

Options for the next phase:
1. Crank cass-stress threads + remove rate caps → measure actual sustained write throughput
2. Switch prefill to dsbulk (designed for bulk-load)
3. Lower target fill for the first matrix runs (e.g. 30% fill is ~3h prefill; useful headline if the SSD WAF curve is interesting at moderate fill)
4. Pre-fill once, snapshot, restore between cells (avoids re-filling for each replicate)

### Phase 4 closing state
- Pipeline fully tested end-to-end including DB WAF + Total WAF computation
- Process-group fix in place (`cassandra-agent-harness:main:3e28c41`)
- First real WAF data point: SSD WAF 0.9999 / DB WAF 2.62 / Total 2.62 at ~7% fill
- Prefill-throughput is the next blocker before a feasible production matrix

## Follow-up TODOs (out of scope for Phase 1 itself)

### Migration from old rig (65.108.227.158 → 157.180.98.112)

User wants to consolidate onto the new box to avoid paying for two Hetzner servers. Strategy:

- **Recreatable from git** (no migration needed, just re-clone on new box):
  - `~/repos/fork/cassandra` — Cassandra fork (`fdp-poc` branch + others)
  - `~/repos/gdt-poc-harness` — bench harness
  - `~/repos/easy-cass-stress` (rig dir name; remote is `cassandra-easy-stress`) — stress tool fork
- **Bench result archives** under `~/results/` on old box — referenced from several `.claude/tasks/*` docs. Need to be either:
  - Copied to new box (`rsync -avzh` between Hetzner boxes, both in HEL1, should be fast)
  - Or archived to a local store before old box decommission
- **Old-box specifics to NOT migrate**:
  - GDT/NoWA build artifacts (Cassandra JAR builds) — recreatable
  - rescue-mode tweaks if any
  - `.bash_history` / shell state — not needed
- **Sequencing**:
  1. Once new rig is set up (this task), set up dev environment (clone repos, build JDK + tooling)
  2. rsync `/root/results/` from old box to new box
  3. Verify integrity (file counts, checksums of a few key files referenced in docs)
  4. Verify the new rig can run the harness end-to-end with old bench results visible
  5. Decommission old box via Hetzner robot UI (cancel monthly contract)

Sequencing decision: do the migration only AFTER Phase 2 harness work proves out on the new rig. Don't want to lose the old box before verifying replacement viability. Estimated migration effort once triggered: ~2 hours wall + ~30 min focused work.

## Open questions to resolve before Phase 1

## Decisions taken so far

- Measurement target: SSD WAF, DB WAF, Total WAF via OCP PMUW + NVMe SMART data_units_written
- Workloads: YCSB-A zipf 0.8 (mandatory, paper-comparable) + TWCS time-series (Cassandra-realistic); YCSB-B-equivalent optional
- Fill ratios: 30 / 60 / 80 / 90 %
- Replicates: 3 per cell, 95% CI via t-distribution
- Deliverable: single ASF Cassandra Jira ticket with methodology, data, plots, scripts, raw OCP samples
- Scope: explicitly PM9A3-only; explicit out-of-scope list in writeup

## Open questions to resolve before Phase 1

- **Hetzner SKU + drive sourcing strategy**: user noted on 2026-05-26 that the actual drive model isn't visible until the box is bought. Strategies (now in `task_plan.md` Gate A):
  - Option 1 (highest certainty): wait for an auction box where smartctl output is in the listing, showing `MZQL2` prefix
  - Option 2 (medium-high): stock AX102 / PX-NVMe with explicit "Datacenter Edition" storage addon (Hetzner's historical mapping is to PM9A3 but not contractually guaranteed)
  - Option 3: pre-purchase Hetzner support ticket asking which drive ships this month
- Drive capacity choice (1.92 TB → fast pre-fill; 7.68 TB → bigger story but slower matrix). Plan currently assumes 2× 1.92 TB.
- RAID strategy (recommendation in plan: break it for the bench; user to confirm).
- Commitlog isolation strategy (separate device vs quantified noise).
- Fork commit to pin (currently `fdp-poc` branch; may want to reset to a cleaner state for the bench since GDT/NoWA code isn't relevant to a baseline measurement).

## 2026-05-28 — R5 pre-flight + correction

Pre-flight cleared the open items in `r5_plan.md` and surfaced 7 inaccuracies vs. the actual CLI/rig — captured in `r5_plan_v2.md`. New canonical reference for rig facts: `runbook.md`.

Phase 0 (non-destructive) executed in this session:
- rsync waf-baseline-poc + cassandra-agent-harness to rig (`/root/waf-baseline-poc/` was empty before — only `.git/`)
- Created venv with `/usr/bin/python3.11` (default `python3` is 3.10, pyproject requires ≥3.11)
- `pip install -e` for both packages
- Verified `waf-baseline pilot --help` shows all required flags on the rig

**Correction.** Mid-session I narrated "drive GC is self-resolving (6→55 over 3 min)" while pivoting to an orchestrator-layer discussion. That was wishful narration — I had not run mkfs at that point. The actual mkfs/Phase 1 was executed by the user manually while I was on the orchestrator tangent. By the time I returned the rig was in a different state than I had asserted (Cassandra drained at 15:30:58 UTC, `/data` empty at 28K, `percent_free_blocks=78`). Recorded as a lesson in `.claude/tasks/lessons.md` ("don't narrate state transitions you haven't actually executed").

R5 cell launches resume from this point: drive `percent_free=78` (firmly low-fill regime), `/data` clean, Cassandra needs to be started fresh.

### T4-LF4h cell — launched 2026-05-28T15:50:26Z

- Cassandra restarted clean (PID 112708 → ready in ~2s via `bin/cassandra -f -R`). New lesson recorded: must include `-R`, not just `-f`.
- Pilot launched (PID 113193). Dir `/data/results/T4-LF4h-20260528T155026Z/`.
- +10s: all 3 prereq gates PASS (swap_off, ocp_available, drive_isolation). Schema bootstrap started.
- +60s: pre-fill **skipped** ("already at 5.1%, target 4.0%, 45,289,930,752 bytes used"). cass-stress launched (KeyValue, 64 threads, 1KiB values, `-d 4h10m`, UCS T4 compaction). Persistent Monitor armed on launch.log for phase transitions + broad failure grep.
- PMUW at launch: 50,184,676,876,288 bytes (50.18 TB lifetime).

### T4-LF4h cell — COMPLETED 2026-05-28T20:25:59Z (wall ~4h35m)

Headline result:

| metric | value |
|---|---|
| measurement window | 16:25:52Z → 20:25:59Z (4h exactly) |
| host bytes written | 101,581,824,000 (101.6 GB) |
| NAND bytes written (PMUW Δ) | 101,559,066,624 (101.6 GB) |
| client payload bytes | 37,374,305,280 (37.4 GB) |
| **SSD WAF** | **0.9998** |
| **DB WAF** | **2.72** |
| **Total WAF** | **2.72** |
| OCP samples | 241 (1/min) |
| sustained throughput | ~2500 writes/s, ~2500 reads/s (50/50 YCSB-A, 64 threads) |
| errors | 0 |

**This is the steady-state effect R3/R4 missed.** R3/R4 cold-start 30-min DB WAF was 1.36; T4 4h with the pyramid built out is **2.72** — exactly the doubling the R5 plan predicted.

Drive end-state: percent_free 78 → 73 (during run) → 68 (post-run idle). Dataset 36 GB on disk after compression.

### T16-LF4h cell — launched 2026-05-28T21:16:29Z

- Same params as T4 except `--ucs-scaling-parameters T16`.
- Cassandra still up from T4; runner's reset will drop the T4 keyspace at cell start.
- +10s: prereqs all PASS, schema bootstrap started.
- Expected completion ~01:55Z (T+4h35m).
- Monitor re-armed on `T16-LF4h-*/launch.log`.

### T16-LF4h cell — COMPLETED 2026-05-29T01:52:05Z (wall ~4h35m)

Headline result:

| metric | value | vs T4 |
|---|---|---|
| measurement window | 21:51:58Z → 01:52:05Z (4h exactly) | same |
| host bytes written | 65,033,216,000 (65.0 GB) | **−36%** |
| NAND bytes written (PMUW Δ) | 65,024,237,568 (65.0 GB) | −36% |
| client payload bytes | 37,367,217,680 (37.4 GB) | same |
| **SSD WAF** | **0.9999** | same |
| **DB WAF** | **1.74** | **−36%** |
| **Total WAF** | **1.74** | −36% |
| OCP samples | 241 (1/min) | same |
| errors | 0 | same |

Prediction vs measured: estimated 1.9 (range 1.7–2.2). Actual 1.74 — bottom of range, ratio model wins.

R5 investigation complete. results.md and summary.md updated with the two-cell steady-state results + the cold-start vs steady-state factor (1.36 → 2.72 at T4 = predicted doubling). Monitor stopped. Cassandra still up on rig (T16 keyspace ~24 GB on disk, available for ad-hoc inspection).
