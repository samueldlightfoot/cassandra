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

## Next steps

- Begin Phase 2: extend `gdt-poc-harness` with `OcpReader` + `SmartReader` + pre-flight checks. Implementation target: S/N-based device resolution (not path-based, to be robust to enumeration swaps). Both nvme-cli 2.x subprocess path and raw log-page-parse path should be implemented; harness can pick whichever is available.
- Once Phase 2 lands, run the §5.3 zone-sweep (small) on the new rig as a methodology verification.

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
