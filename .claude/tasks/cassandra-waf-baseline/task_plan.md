# Cassandra WAF baseline — task plan

## Goal

Produce a credible, reproducible measurement of Apache Cassandra's write amplification (DB WAF, SSD WAF, Total WAF) on Samsung PM9A3 datacenter NVMe across a defensible matrix of workloads and fill ratios, and contribute the result to the Apache Cassandra Jira as a community baseline.

This was pivoted into from `../nowa-feasibility/` after recognising that (a) the WAF measurement is a strict prerequisite of any future mechanism work (NoWA, FDP, alignment), and (b) on its own it's a higher-confidence deliverable than chasing a perf-positive mechanism result. See `findings.md` §1.

## Deliverable

A single ASF Jira ticket (subject: roughly "Characterization of Cassandra write amplification on enterprise NVMe") containing:
- Methodology description (workloads, fill ratios, instrumentation, statistical procedure)
- Tables of measured DB WAF, SSD WAF, Total WAF, throughput, p99 read latency for each condition × replicate
- Plots of WAF vs fill ratio for each workload
- Direct cross-reference to Lee/Ziegler/Leis (PVLDB 2026) Table 1 for the YCSB-A condition on PM9A3
- Open-sourced bench scripts + raw OCP/SMART data + analysis notebook
- Explicit scope statement and out-of-scope list

## Pre-implementation gates

- [x] **Gate A — DC NVMe rig provisioned** (2026-05-26, Hetzner HEL1 auction, IP 157.180.98.112). Both drives confirmed **Samsung PM9A3 960 GB (`MZQL2960HCJR-00A07`)** via `nvme id-ctrl` — the ideal case for paper comparability. S/Ns S64FNE0R401522 + S64FNE0R401526, firmware GDC5A02Q, NVMe 1.4.
- [x] **Gate B — OCP verified on the actual drives** (2026-05-26). OCP log page version 2 (OCP 2.0). `nvme ocp smart-add-log` returns full structured output. PMUW movement test: 10.000 GiB write → ΔPMUW = exactly 10.000 GiB → SSD WAF = 1.000 to within 0.003%. Counter is live and precise.
- [ ] **Gate C — methodology peer-checked**. Methodology section of the draft writeup reviewed before any measurement runs start. Reason: the measurement is only as defensible as its design.

## Phases

### Phase 1 — Provisioning + drive triage + OCP verification (CLOSED 2026-05-26)

- [x] Provision Hetzner box. **Result**: HEL1 auction box, Xeon E-2276G 6c/12t, 62 GiB ECC, 2× PM9A3 960 GB U.2, 157.180.98.112.
- [x] **Drive identity**. Both drives: `SAMSUNG MZQL2960HCJR-00A07`, firmware GDC5A02Q, NVMe 1.4, 960 GB marketing capacity / 894 GiB binary. Matched pair, S/Ns S64FNE0R401522 + S64FNE0R401526.
- [x] **Branch on identity** → **PM9A3 (ideal case)**. Direct paper-comparability anchor lands; same drive family as paper Table 1.
- [x] **OCP capability**. `nvme ocp smart-add-log` returns full structured output on both drives. Log page version 2 (OCP 2.0 — better than the agent's research suggested). PMUW, host bytes, percent free blocks, NUSE, PLP all reporting. **Gate B PASSED.**
- [x] **PMUW movement test**. 10 GiB fio sequential direct write to raw nvme0n1 (rescue-time naming, drive S64FNE0R401522). ΔPMUW = 10.000 GiB exactly, Δhost = 10.000 GB, SSD WAF = 1.000 (within 0.003%). Counter precision confirmed.
- [x] Document drive model, firmware rev, OCP spec version, PMUW behavior in `progress.md`. Done — see progress.md sections "Phase 1 results" and "Phase 1 complete".
- [x] **RAID decision**. **No RAID** — installimage configured with SWRAID 0, two drives independent. Documented rationale (clean per-drive OCP, paper comparability, no md/dm layer, commitlog-isolation mitigation per G5). Captured in progress.md "RAID + disk layout decision".

**Gate B PASSED. Phase 1 closed.**

### Phase 1.5 — OS install + rig setup (CLOSED 2026-05-26, not originally in plan)

Inserted between Phase 1 and Phase 2 because installimage was a discrete chunk of work needed before the harness can target a real OS.

- [x] Ubuntu 22.04.5 LTS installed via Hetzner installimage with custom config + postinstall script
  - DRIVE1 nvme0n1 partitioned: 1 GiB /boot, 50 GiB /, ~843 GiB /data
  - DRIVE2 nvme1n1 left untouched by installimage; formatted ext4 (label "commitlog") + mounted /commitlog by postinstall
  - SWRAID 0, no swap
- [x] Post-install: SSH key (ed25519) installed for root; password auth disabled; commitlog drive formatted + persisted in fstab via UUID
- [x] /data remounted with `noatime` and fstab updated (caught by user review — installimage default was `defaults`)
- [x] Tooling installed: `fio smartctl tmux htop curl jq` via apt; **`nvme-cli 2.16` built from source** (Ubuntu 22.04's apt-shipped 1.16 lacks the OCP plugin; required upgrading meson via pip first → `apt python3-pip` → `pip3 install --upgrade meson` → build libnvme + nvme-cli from upstream tags)
- [x] OCP verified on installed OS — `nvme ocp smart-add-log /dev/nvme1n1` (now the data drive after PCI re-enumeration swap) returns valid output; PMUW counter carries lifetime history from rescue (48.214 TB after install operations vs 48.207 TB at end of rescue PMUW test)
- [x] **Documented drive-name swap caveat** (rescue's nvme0n1 = installed-OS's nvme1n1, etc.). Implication: harness must reference drives by S/N, not device path. Recorded in progress.md.

### Phase 1.5 outputs (carry into Phase 2)

- Working rig at 157.180.98.112 (`cassandra-waf-rig`, key-auth only)
- Measurement device: `/dev/nvme1n1` (S/N S64FNE0R401522), `/data` partition (843 GiB ext4 noatime)
- Commitlog device: `/dev/nvme0n1` (S/N S64FNE0R401526), `/commitlog` (894 GiB ext4 noatime)
- Lifetime PMUW already captured as a baseline for cross-check
- OCP tooling installed + raw `nvme get-log -i 0xc0 -b` confirmed as a fallback path that doesn't require the built-from-source nvme-cli

### Phase 2 — Harness instrumentation (~2 days)

Extend `gdt-poc-harness` with OCP-aware measurement.

- [ ] New `OcpReader` helper in `gdt-poc-harness/src/gdt_poc/ocp.py`:
  - Wraps `nvme ocp smart-add-log <device> -o json`
  - Parses output → returns `{ pmuw_bytes, host_writes_bytes, ... }`
  - Handles error cases (device not OCP-capable, permission errors, parse failures)
- [ ] New `SmartReader` helper for plain NVMe SMART (data_units_written, data_units_read) for cross-check
- [ ] Wire into investigation lifecycle:
  - Pre-condition snapshot: read both counters at "T0" (start of measurement window, after steady-state warmup)
  - Periodic sampling thread: poll every 60s during the measurement window, write to JSONL
  - Post-condition snapshot: read at "T1" (end of measurement)
  - Compute deltas → SSD WAF = ΔPMUW / Δhost_writes; DB WAF = Δhost_writes / client_payload_bytes; Total WAF = ΔPMUW / client_payload_bytes
- [ ] Add prereq check: `check_ocp_available` extension to `prereqs.py` that verifies `nvme ocp smart-add-log` works on the configured device before the bench launches
- [ ] Add prereq check: `check_swap_off` to ensure no swap on the measurement drive
- [ ] Add prereq check: `check_drive_isolation` to verify Cassandra is the only meaningful writer
- [ ] Unit tests for OCP/SMART parsing + WAF math

### Phase 3 — Methodology + procedure scripts (~3 days)

- [ ] Pre-fill procedure: bulk-load Cassandra to target fill ratio via easy-cass-stress with disabled deletes. Validate it actually reaches the target via `nodetool tablestats`.
- [ ] Steady-state procedure: run the target workload for N drive-writes before opening the measurement window. Tunable per workload. Validate by checking that SSD WAF readings stabilize before declaring "steady state reached".
- [ ] Measurement window procedure: 30 min sustained workload with 60s OCP sampling. Document expected counter movement (PMUW should grow by ~30min × workload_write_rate × inflated_for_SSD_WAF).
- [ ] Workload selection (see `findings.md` §4):
  - **W1**: YCSB-A zipf 0.8 (paper-comparable)
  - **W2**: TWCS time-series write (Cassandra-realistic)
  - **W3 (optional)**: read-heavy mixed (YCSB-B equivalent)
- [ ] Fill ratio matrix:
  - 30% (low-pressure baseline)
  - 60% (typical production)
  - 80% (paper-comparable)
  - 90% (stress)
- [ ] Replicate plan: 3 replicates per `(workload, fill_ratio)` cell

### Phase 4 — Pilot run (~2 days)

A single end-to-end execution at one condition (recommend YCSB-A at 80% fill) to validate the full toolchain before committing to the matrix.

- [ ] Run the pilot
- [ ] Verify: pre-fill reaches target, steady-state warmup completes, OCP counters move sensibly, post-run analysis pipeline produces a clean SSD WAF + DB WAF + Total WAF
- [ ] Compare pilot result to the paper's PM9A3 in-place LeanStore (Table 1: SSD WAF 2.36) — should differ; if it matches almost exactly, suspicion of measurement artifact
- [ ] Validate: replicate the pilot 3× and check that the inter-run SSD WAF variance is ≤ 5%. If not, methodology has a problem.

**Decision gate after Phase 4**:
- Pilot clean + variance acceptable → proceed to Phase 5
- Variance > 10% → debug methodology, possibly increase warmup duration or fix isolation
- Counters don't move sensibly → stop, reassess

### Phase 5 — Full measurement matrix (~1-2 weeks wall time, mostly automated)

- [ ] 2 workloads × 4 fill ratios × 3 replicates = 24 runs
- [ ] Each run ~3 hours wall (1h pre-fill + 1.5h steady-state warmup + 0.5h measurement window)
- [ ] Total: ~72 hours of bench wall time, achievable in ~10 days with overnight automation
- [ ] Automated cleanup between runs: drop tables, drop SSTables, `nodetool truncate`, wait for compaction to drain, verify drive is at target initial state
- [ ] Raw OCP/SMART samples archived per run in JSONL

### Phase 6 — Analysis + plots (~3 days)

- [ ] For each `(workload, fill_ratio)` cell: compute mean + 95% CI of SSD WAF, DB WAF, Total WAF, ops/sec, p99 read across the 3 replicates
- [ ] Plot 1: SSD WAF vs fill ratio, one line per workload
- [ ] Plot 2: Total WAF vs fill ratio, one line per workload
- [ ] Plot 3: Throughput vs fill ratio, one line per workload
- [ ] Cross-reference table: YCSB-A 80% fill, our Cassandra number vs the paper's LeanStore in-place number on the same drive
- [ ] Identify outliers, investigate, decide whether to keep, discard, or replicate
- [ ] Notebook lives in `gdt-poc-harness/notebooks/cassandra-waf-baseline.ipynb` (or similar)

### Phase 7 — Jira writeup + community engagement (~3-5 days)

- [ ] Draft the Jira ticket using ASF CASSANDRA prefix
- [ ] Methodology section: explicit fill ratios, workloads, replicate count, steady-state procedure, instrumentation tools, drive model + firmware
- [ ] Out-of-scope section: explicit "we did NOT test [list]", "results are PM9A3-specific", "we did NOT propose a mechanism"
- [ ] Results section: tables + plots
- [ ] Discussion section: what the numbers mean for Cassandra operators; whether they suggest headroom for SSD-aware optimization; brief reference to the GDT/NoWA exploration that motivated this measurement
- [ ] Open the scripts + raw data publicly (likely a GitHub repo linked from the ticket)
- [ ] File the ticket, monitor reaction, respond to feedback

## Effort summary

| phase | status | wall time | focused effort |
|---|---|---|---|
| 1. Rig + OCP verify | **closed** | 1 day actual | ~3h actual |
| 1.5. OS install + rig setup (inserted) | **closed** | <1 day actual | ~2h actual |
| 2. Harness instrumentation | not started | 2 days | ~12 hours |
| 3. Methodology + procedure scripts | not started | 3 days | ~16 hours |
| 4. Pilot run | not started | 2 days | ~8 hours |
| 5. Measurement matrix | not started | 1-2 weeks wall | ~16 hours (mostly automation supervision) |
| 6. Analysis + plots | not started | 3 days | ~16 hours |
| 7. Jira writeup + engagement | not started | 3-5 days | ~16 hours |
| **Remaining total** | | **~3 weeks calendar** | **~75 focused hours** |

## Decision gates

- ~~**After Phase 1**: PMUW absent/broken → escalate to Hetzner, possibly different drive, or abandon~~ → **PASSED 2026-05-26**, PMUW working with high precision on PM9A3
- **After Phase 4 (pilot)**: variance > 10% → debug methodology before committing matrix time
- **During Phase 5**: if first 2-3 cells show SSD WAF identically ~1.0 across all conditions, consider truncating matrix — the headline finding (LSM is SSD-friendly) is established
- **Before Phase 7**: peer-review the writeup before filing

## Stop conditions

Reasons to stop the investigation entirely:
- PMUW unavailable on Hetzner PM9A3 (Gate B fail)
- Cannot reduce inter-run variance below ~10% (methodology problem we can't fix)
- Measurement matrix produces uniformly uninteresting result (all SSD WAF ≈ 1.0) AND we decide the negative finding isn't worth publishing — note: probably IS worth publishing as a one-line CASSANDRA-jira finding even then

## Operational changes required on the rig

- [x] ~~Break RAID-1 on data drive(s)~~ — n/a, rig was never configured with RAID (installimage SWRAID 0)
- [x] Move commitlog to a separate device — done at install time. nvme1n1 → `/commitlog`, fstab persisted.
- [x] `swapoff -a` on the measurement drive — done. No swap configured at install time; fstab swap entries purged by postinstall.
- [ ] Pin Cassandra to one CPU socket / NUMA node — n/a for the E-2276G (single socket, single NUMA node). Skip unless we move to a multi-socket box.
- [ ] Disable all background system services that write to the data drive (logging, etc.) — **Phase 2 work**. Need to characterize what apt/journald/systemd write rate looks like over 30 min idle and decide which can be moved off `/` or muted.
- [x] Fresh `mkfs.ext4` on the data drive before bench — done at install time. Mount options now `defaults,noatime`. Will refresh with another `mkfs.ext4` immediately before the matrix runs in Phase 5 to ensure no fragmentation pollution.
- [ ] Establish a "drive baseline" by running a no-Cassandra control bench (30 min idle + 30 min fio random write) to characterize background SSD activity — **Phase 2 work**.

## Out-of-scope (deliberate)

- Multi-drive comparison — we have only PM9A3 access on Hetzner. Single-drive results explicitly scoped as PM9A3-specific.
- FDP measurements — PM9A3 doesn't support FDP, would require provider switch. Out of scope for this baseline, possible follow-up.
- Mechanism implementation — explicitly NOT measuring "NoWA Cassandra" or "GDT Cassandra"; this is baseline characterization only.
- Recovery / streaming / repair WAF — bench targets primary write path; replica streaming has its own WAF profile and is a separate question.

## Review section

(To be filled at the end of Phase 7.)
