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
