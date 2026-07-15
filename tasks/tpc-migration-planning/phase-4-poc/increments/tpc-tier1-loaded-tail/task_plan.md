# Task plan — Tier 1: enter the loaded / CPU-bound / IRQ-configured regime, measure the tail

**The first real ROI test on hardware we own.** Every prior comparison sat in TPC's LOSS regime (I/O-bound
@45% CPU, no IRQ config). The paper is explicit: TPC loses at low concurrency and wins only under load with the
environment configured. This phase recreates that regime on the 6C/12T box and measures the loaded tail +
max CPU-bound throughput, trunk vs alloc-gap (5348018527 = routing + the 3 async fixes).

**Read as:** if routing wins loaded tail and/or throughput once the cores saturate → that is the PoC ROI on
owned hardware. If it does not — because a single-L3 box's coordination tax is too small — that is a clean,
honest isolation that the win needs cross-NUMA scale, and it *justifies* the final big-box run instead of
guessing. Either outcome is a result.

**Live hypothesis from Tier 0 (test cleanly, don't pre-believe):** the co-located alloc ramp showed alloc-gap
p99 ~0.7–2 ms vs trunk ~30–140 ms at matched throughput — 50–100×, far too large to be the 1.3% alloc delta.
Co-located tail is contaminated, so this is only a hypothesis: the async fast paths may cut completing-thread
work / listener hops enough to move the loaded tail. This phase is where that gets a clean off-box test.

## The regime to build (all three, or the test is still in the loss regime)

### 1. Remove the I/O knee so the 6 cores actually saturate — METHOD IS A DECISION (validity trade-off)
Current knee is I/O-bound at ~45% cass CPU (delivered caps ~0.70× offered on flush/GC stalls). commitlog is
already `periodic` 10s (acks before fsync), so the knee is most likely **flush (memtable→sstable) + GC**, not
per-write commitlog fsync. Candidate methods (both arms MUST use the identical config — that keeps the
comparison valid; representativeness is the only question):
- **(a) `durable_writes=false` on the test keyspace** — drops the commitlog entirely. Simple, per-keyspace,
  trivially reversible. But flushes remain, so may not fully remove the knee on its own.
- **(b) commitlog_directory + a fresh data_file_directory on tmpfs** (44 GB free RAM) — puts commitlog AND
  sstable flush in RAM → no disk I/O in the write path → cleanest CPU saturation. Needs a fresh small keyspace
  (existing 386 GB data won't fit); fine for a synthetic write-saturation test.
- **(c) huge memtable + few flushes** — delay flush so the window is pure apply. Fragile; GC still bites.
- **Plan of record (pending confirm):** start with (a); if `mpstat` still shows cass < ~90% busy at high
  concurrency, escalate to (b) (tmpfs data+commitlog, fresh keyspace). Drive writes until cass CPU ≈ 100%.

### 2. IRQ affinity + irqbalance off (the paper's single biggest tail lever, C1)
- `systemctl stop irqbalance` (currently active) + disable.
- Identify the NIC IRQs (the off-box loadgen's NIC) and pin their smp_affinity OFF the shard cores, onto a
  dedicated housekeeping core. Record the shard-core pinning first (rig pins cass via AllowedCPUs; loadgen is
  off-box so all 12 rig cores serve shards) — reserve 1–2 cores for IRQ/housekeeping, pin NIC IRQs there.
- Verify with `/proc/interrupts` deltas that NIC IRQs land only on the housekeeping core(s).

### 3. High concurrency to load all shard threads
- Off-box loadgen (co-located contends — [[reference_bench_rig_topology]]); drive concurrency up until the
  new CPU-bound knee. Prove cass headroom + loadgen-not-fenced with `mpstat` on both boxes.

## Setup steps
- [ ] Provision a fresh off-box loadgen (hcloud; old box `62.238.35.142` was deleted). Same-region low-RTT
      (hel1, ~0.46 ms was prior). Install easy-cass-stress; authorize rig pubkey; open loadgen→rig:9042.
      (Consult `agent-common/rig/*.md` runbooks; hcloud token is env-var in `.secrets/hcloud.token`.)
- [ ] Apply the chosen I/O-knee removal (a or b) to the rig conf; restart; confirm write path is not disk-bound.
- [ ] irqbalance off + NIC IRQ pinning; verify via `/proc/interrupts`.
- [ ] Find the new CPU-bound knee (ramp until cass CPU ~100% via `mpstat`, or delivered plateaus).

## Measurement (off-box, CO-corrected, interleave arms — GC noise)
- [ ] Full HDR band (p50/p90/p99/p999/p9999) at **0.8–0.95× the new knee**, ≥90s/rung, trunk vs alloc-gap,
      arms interleaved (swap.sh + prep per arm), 2–3 rounds. NOT a single p99.
- [ ] Max sustained CPU-bound throughput per arm (the latency-defined saturation single-process form:
      `--maxwlat` ALONE, no `--rate` — [[feedback_easy_cass_stress_scripted_run_gotchas]]).
- [ ] Keep the domain contention counter (72/1k → 0) + c2c (−27%) as GC-immune mechanism corroboration
      alongside whatever the loaded tail shows.
- [ ] mpstat on rig + loadgen every run: prove cass saturated AND loadgen has headroom (else co-location/fence
      artifact — [[feedback_cpu_fence_colocated_loadgen]]).

## Validity gates / traps (from memory)
- Never `--rate` WITH `--maxwlat`; for latency-defined saturation use ONE process + `--maxwlat` ALONE.
- `--hdr` writes `<prefix>-mutations.txt` in ms, CO-corrected; populate contaminates it within one invocation.
- rsync repos to rig + verify behaviour on rig BEFORE walking away ([[feedback_rsync_before_rig_launch]]).
- Monitor greps must cover startup failures, not just terminal success ([[feedback_monitor_silence_is_not_success]]).
- `auto_snapshot: false` already? verify (DROP KEYSPACE keeps disk otherwise).

## Review section
- Is the regime genuinely CPU-bound (mpstat proof), or still I/O/GC-capped? If capped, the tail result is void.
- Both arms identical config except the jar? (swap.sh only swaps the jar.)
- Honest read: a single-L3 box showing NO tail win is a VALID result that justifies the big box, not a failure.
