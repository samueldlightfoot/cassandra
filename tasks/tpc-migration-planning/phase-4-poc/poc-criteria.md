# Phase 4.1 — PoC Success Criteria + Trunk Baseline (`poc-criteria.md`)

**Status:** methodology + criteria PINNED 2026-07-09; baseline numbers CAPTURING
(tables marked ⟨TBD⟩ filled from the rig run, then this doc is committed before any
increment code — spec.md 4.1 acceptance).
**Authority:** this doc is the reference every increment's A/B gates against
(increments.md §0 — flag-on vs flag-off on the SAME build). D9 (criteria before code)
is satisfied here.

---

## 1. The gate (D9, made concrete)

An increment **PASSES** iff, at **matched offered throughput** (within the measured
noise band, §5), on the primary KeyValue gate cells (§3):

- **p99 ≤ baseline** AND **p99.9 ≤ baseline** (client-measured, coordinated-omission
  corrected), AND
- **throughput ≥ baseline** at saturation (≥ 0.98× within spread).

A cell that misses files a **hurdle-log entry** (spec.md 4.3) WITH the shard-attribution
evidence (per-shard PendingTasks, misroutedPuts, GC-window overlay) before any remedy —
never a silent p99. This is the 2016 failure mode the gate exists to catch: the 10993 POC
posted +15% throughput with ~2× worse p99/p99.9 and would have passed a throughput-only
gate (increments.md §0).

**GC caveat (load-bearing, from the flush-pacing PoC):** raw aggregate p99 on this fast
NVMe rig is GC-dominated (~150 ms G1 pauses buried the flush signal there). Every gate
comparison overlays the GC log; a tail regression that lands only inside GC-adjacent
windows is a GC artifact, not an increment verdict — attribute before filing. GC profile
is captured per cell (§4) so the overlay is always possible.

## 2. Baseline build + config (trunk-equivalent = the flag-off reference)

- **Build:** `tpc-migration` @ `ae53c542dc`, rsync'd to `/root/repos/fork/cassandra-tpc`,
  `ant jar`. Verified trunk-equivalent on the serving path: `git diff trunk...tpc-migration
  -- src/` touches ONLY `io/uring/*` (never instantiated by the server until I2b wires a
  ring) + 2 inert enum lines in `CassandraRelevantProperties` (`URING_ENABLED`). So this
  ONE build is simultaneously the trunk baseline AND the flag-off arm of every future
  increment — same lineage, no cross-build confound. JAR verified before any result
  (mtime > source; a trunk sanity class present) per the flush-PoC #1 gotcha.
- **Config pins (violating any invalidates the comparison — increments.md §0):**
  `memtable: trie` (trunk default SKIPLIST is unsharded — no routing possible),
  `commitlog_sync: periodic`, `disk_access_mode: standard`, `auto_snapshot: false`
  (bench rig — DROP KEYSPACE otherwise fills disk with snapshots), tables non-Accord /
  no MV / no 2i / no counters.
- **Topology:** single node, **RF=1** (honest single-node: coordinator == the only
  replica — the caveat the whole plan carries; I5's macro effect only shows multi-node,
  not oversold here).
- **Storage (device separation, from the flush PoC):** data → `/data/tpc-poc/data`
  (`nvme1n1p3`); commitlog → `/commitlog/tpc-poc` (`nvme0n1p1`, separate device — keeps
  commitlog fsyncs off the SSTable device). Both PM9A3.
- **Heap / GC:** ⟨TBD: pinned MAX_HEAP_SIZE + collector⟩ (default G1; recorded, not
  defaulted-silently). `-Xlog:gc*` capture on for the GC overlay.
- **Page cache:** baseline runs with the full ~62 GiB host RAM (reads warm) — RECORDED,
  not controlled. Cache-miss behaviour is I2b's concern: its A/B uses a `--cgroup-mem`
  cap (flush-PoC technique) to force device reads. 4.1 baseline does not pretend to
  measure the miss path.

## 3. Workload set

Primary (the **gate** — easy-cass-stress `KeyValue`, the point-query read/write path that
exercises mutation apply + point reads + commitlog):

| Cell | Mix (`--readrate`) | Exercises | Gate |
|---|---|---|---|
| `kv-w` | 0.0 (write-only) | I1 apply, I4 commitlog/writeOrder | YES |
| `kv-rw` | 0.5 (balanced) | I1 + I2 + I3 coordinator | YES |
| `kv-r` | 0.9 (read-heavy) | I2 reads, I3 read coordinator | YES |

Secondary (characterization, NOT a gate — recorded to bound the primary's generality):
`BasicTimeSeries` (write/commitlog-heavy). RandomPartitionAccess reserved for I2b's
miss-storm cell.

Value size / partition count: ⟨TBD: pin from KeyValue defaults + record⟩. Partition key
space `--partitions` sized so the dataset ≫ heap only in the I2b miss cell; baseline uses
⟨TBD⟩.

## 4. Measurement protocol (3 independent signals — cross-check lesson)

1. **easy-cass-stress stdout summary** = authoritative counts/throughput (CSV is
   truncated by SIGTERM — memory; stdout is the record).
2. **Client-side percentiles (PRIMARY for the gate):** the fork's `--csv-latency`
   (100 ms windows: `epoch_s,count,p50_us,p99_us,p999_us,max_us`) is the primary tail
   signal. `--hdr <prefix>` writes `<prefix>-{mutations,reads,deletes}.txt` (standard HDR
   percentile distribution, **milliseconds**) as a corroborating full-distribution read.
   **Verified 2026-07-09** (source review, rig branch `feature/csv-latency`): all three
   client signals — stdout p99, `--csv-latency`, `--hdr` — record the SAME value,
   `endNanos − createdAtNanos` = coordinated-omission-corrected request latency (includes
   client queue wait), so they agree and are all CO-correct. **`--hdr` caveat:** its
   `-mutations.txt` blends populate-phase writes when populate + load share ONE invocation;
   our driver populates in a SEPARATE invocation so measured cells' `-mutations.txt` is
   clean, and `-reads.txt` is always clean (populate readrate=0). A tidy upstream fix
   (gate `HdrCollector.collect` on `populatePhase`) is available if we want to contribute
   it — not required for our use.
3. **Server-side cross-check:** `nodetool proxyhistograms` + `tablestats` + `tpstats` at
   cell end (verify-metrics-at-source: MutationStage is replica-level, not coordinator).
   proxyhistograms is server-truth and always ≤ the client CO number (strips queue+network)
   — the gate uses the client CO-corrected p99; proxyhistograms attributes client-vs-server.
4. **GC log** (`-Xlog:gc`) for the tail overlay (§1).

- **Load model = RATE LADDER (the tool is open-loop — hurdle A13).** Offering "unlimited"
  rate causes a coordinated-omission meltdown (millions of timeout errors, multi-second
  p99), NOT saturation. Instead: (a) **discovery** — climb fixed offered rates per mix
  (`10k…130k`), stop at the first rung with >1% errors OR achieved <90% of offered; the last
  clean rung is the sustainable max. (b) **measurement** — p99 at operating points (50%,
  80% of clean_max) with 3-iter noise bands, `--csv-latency`/`--hdr` for CO-corrected p99.
  Every cell records error% + achieved-vs-offered (a rung failing either is overload, not a
  data point). Deliverable = **p99-vs-offered-throughput curve per mix**; the gate compares
  curves (increment p99 ≤ baseline p99 at each matched offered rate). Note the tool's default
  `--rate 5000` still applies to any un-rated call (hurdle A12) — every cell sets `--rate`.
- **Duration:** warmup discarded + steady window; **3 iterations** per cell for the spread
  (noise band, §5). ⟨TBD: pin warmup + steady durations⟩.
- **CPU fence (co-located loadgen — memory + playbook; revised per hurdle A9):** Cassandra
  pinned to cores `0–7` (8 cores), easy-cass-stress to cores `8–11` (4 dedicated cores),
  disjoint. The client gets 4 cores because a 2-core client pegged as the bottleneck in the
  first attempt (~1–2k ops/s) — which would measure the load generator, not Cassandra.
  `mpstat -P 8,9,10,11` during each cell PROVES the client cores aren't saturated (else the
  number is a co-location artifact); a pre-sweep calibration cell must confirm client
  headroom at saturation before the full matrix runs. Snapshot per cell.
- **Environment (Enberg dominant tail variable — capture or the cell is invalid):**
  cpufreq governor set `performance` (restore-by-trap); irqbalance state + NVMe IRQ
  affinity + taskset mask recorded per cell; `intel_iommu=pt` NOT set (recorded — the
  ~17% IOMMU DMA tax from Phase 2 is a known untested lever, same for baseline and
  increments so it cancels).
- **Launch discipline (flush-PoC gotchas):** `setsid` detached launch (SSH drops
  mid-command); node teardown via `pkill -9 -f 'Cassandra[D]aemon'` self-exclusion trick
  (a bare `pkill -f CassandraDaemon` SIGKILLs the SSH shell — looks like a reboot); verify
  "ready" from a FRESH connection before walking away; check `uptime` first (box
  auto-reboots for kernel upgrades). Monitor greps cover startup failures (`unrecognized`,
  `usage:`, `error:`, `Exception`), not just terminal success (monitor-silence lesson).

## 5. Noise band + oversubscription (the honesty pins)

> **⚠️ 2026-07-10 — the "gate at 50%, knee too noisy" rule below is SUPERSEDED.** The 80%/knee
> noise was a **co-located-rig artifact**; off-box the knee is clean (0 err, stable p99), so the
> gate now uses a **primary loaded-tail point + secondary mid-load point** (increments.md's "the
> gate is the loaded tail"). The absolute rates here are also stale (mismeasured clean_max — see
> §8.1 banner). See `gate-reconciliation.md` + `offbox-baseline-clean.md`. The noise-band *tie
> rule* (delta inside the band = tie) still stands.

- **Noise band (measured `baseline_v1`):** at **50% of clean_max the p99 is stable and
  tight** (write 0.34–0.58ms, balanced 0.28–0.44ms, read 1.15–31ms) — these are the gate
  reference points. At **80% of clean_max (near the knee) p99 is highly unstable** across the
  3 iters (read: 33→263ms, an ~8× spread) — expected for near-saturation and NOT a usable
  gate point. **Gate rule:** compare increments at the 50%-of-clean_max operating points;
  a delta inside the measured 50%-point band is a tie. The knee/80% region is reported for
  shape only. An increment's "≤ baseline" is judged against `baseline_median` with this band.
- **Oversubscription (§8 item 5, user-pinned 2026-07-09, verbatim):** accept
  oversubscription for I1/I2/I4/I5 (N=cores shard threads atop the existing SEP pools),
  **documented per cell as a tail-win floor** — a muted number under oversubscription is a
  config consequence, not a program verdict (Enberg pinning note). `native_transport_max_threads`
  shrinks flag-on **only in the I3 A/B cell where the shrink IS the measured claim**;
  everywhere else NTR is unchanged. This shapes every A/B's honesty, so it is pinned HERE
  with the criteria, not per-increment.

## 6. Foreground-read caching model — PROVISIONAL (§8 item 8, do not hard-wire)

Restated from ../findings.md §5.1 so no increment silently inherits the buffered
assumption: the foreground-read caching model is **unsettled and performance is the
sovereign criterion**. Two arms, both first-class (design-target D2):

- **arm A** — buffered ring reads keeping the page cache (I2b default), vs
- **arm B** — DIO reads + expanded ChunkCache (the Scylla model; **user prior 2026-07-09
  = full-Scylla**).

Adjudicated by **I2b's `pool×A` / `pool×B` cells**, NOT here — 4.1 baseline is buffered/
warm-cache and makes no claim on the miss path. User directive: "if we nerf performance by
still including buffered io then it isn't an option." Any DIO-compaction cell must measure
read p99 **across a compaction boundary** (outputs go cache-cold at switchover). Background
writers (commitlog, compaction, flush, streaming, hints) target DIO in EITHER outcome
(DIO+ring never punts to iou-wrk on any fs — Phase 2 G3).

## 7. Rebase cadence (fork-drift risk, spec.md §4)

- Rebase `tpc-migration` onto `trunk` at **each increment boundary** (before writing the
  next increment's spec), so drift is paid in small increments, not one big-bang merge.
  Accord / CEP-45 churn is the likely friction (design-target §3.1 item 6).
- **Re-baseline trigger:** if a rebase changes the serving path (`git diff` on
  `src/.../db`, `service`, `net`, `io/util`, commitlog, memtable), re-run the §3 baseline
  before trusting any subsequent gate — the flag-off reference must track trunk.
- Between increments the build is `ant jar` (warm ~12 s); the JAR is re-verified each time.

---

## 8. Baseline results — `baseline_v1` (2026-07-09, rig 157.180.98.112)

> **⚠️ SUPERSEDED 2026-07-10 by `offbox-baseline-clean.md`** (off-box, single-process,
> `--concurrency 3000`). `baseline_v1`'s clean_max was mismeasured (Cassandra idle — §8.1 banner);
> real clean_max is ~15× higher (write ~246k, balanced ~180k, read ~120k). Use the clean doc for
> all operating points; this section is kept only as the record of the mismeasured pass.

Captured by `baseline_driver.sh` (rate-ladder model, §4). All cells **0 errors**. Client
CO-corrected latency (stdout/`--hdr`/`--csv-latency` agree). Raw per-cell output archived in
`baseline-results/`. Trunk-equivalent build `tpc-migration@ae53c542dc` (= flag-off reference).

### 8.1 Rate-ladder curve — clean sustainable max per mix (Phase A, knee = p99 < 50ms, err 0)
The x-axis is **achieved** ops/s (the tool delivers ~0.5× nominal `--rate` even when clean —
A14; offered shown for reproducibility). p99 in ms (client, CO-corrected).

| offered → | 10k | 20k | 40k | 60k | 90k | 130k | 180k | **clean_max (achieved)** |
|---|---|---|---|---|---|---|---|---|
| **write** ach | 5.5k | 11k | 16.1k | 16.3k | 14.7k | overload(165ms) | — | **14.7k** @ 9.2ms p99 |
| **write** p99 | 0.22 | 0.26 | 0.33 | 0.93 | 9.15 | — | — | (plateau ~16k) |
| **balanced** ach | 5.5k | 11k | 22.2k | overload(66ms) | — | — | — | **22.2k** @ 0.9ms p99 |
| **balanced** w/r p99 | .31/1.15 | .26/.27 | .56/.89 | — | — | — | — | |
| **read** ach | 5.5k | 11k | 22.2k | 33.6k | 50.1k | 72.9k | overload(3040ms, 2.6% err) | **72.9k** @ 47ms p99 |
| **read** w/r p99 | .26/.24 | .28/.27 | .44/.34 | 16.8/22 | 1.6/2.2 | 47/25 | — | |

> **⚠ SUPERSEDED — these numbers are accurate but at TOO-LOW LOAD; re-run required (2026-07-09).**
> The client counts here are RELIABLE (verified client==server to the digit at sane rates), but the
> offered `--rate` was far too low: `cassandra-easy-stress` delivers only ~0.1–0.25× of nominal
> `--rate`, so these rungs put ~5–30k ops/s on the server and **MutationStage stayed idle at every
> point** — Cassandra was never loaded. So "clean_max write ~16k / bal ~22k / read ~73k" are
> low-load operating points, NOT Cassandra's capacity (server-side writes go to ~231k at `--rate
> 2M`, still MutationStage-idle at ~100k). **Re-run at much higher `--rate` / multi-process per
> `REBASELINE-HANDOFF.md`.** All the mid-investigation "load-generator-limited / client-CPU-bound /
> ~2× inflated / 14× value-gen fix" conclusions were WRONG turns — see hurdles A16 final resolution.

### 8.2 Operating points — p99 at fixed offered rate, 3-iter noise band (Phase B) → the gate reference
p99 median [min–max across 3 iters], ms. **50% points are STABLE; 80% points sit near the knee
and are noisy** (see §5) — the gate uses the 50% points as the primary clean reference.

| Mix | 50% of clean_max (offered) | achieved | write p99 | read p99 |
|---|---|---|---|---|
| write    | 45k | 10.7k/s | **0.4** [0.34–0.58] | — |
| balanced | 20k | 14.0k/s | **0.31** [0.28–0.35] | **0.34** [0.33–0.44] |
| read     | 65k | 46.1k/s | 3.9 [1.15–24.8] | **2.9** [2.18–31.4] |
| write    | 80% → 72k | 9.4k/s | 20.5 [0.58–28.0] ⚠noisy | — |
| balanced | 80% → 32k | 21.5k/s | 15.9 [0.38–78.9] ⚠noisy | 0.63 [0.26–43.0] ⚠noisy |
| read     | 80% → 104k | 73.9k/s | 226 [33–263] ⚠noisy | 246 [22–282] ⚠noisy |

### 8.3 Environment snapshot
Kernel 6.8.0-124; JDK 17.0.19; G1, 16G heap (`-Xmx16G`), 4G newsize; governor `performance`;
irqbalance active; RF=1 single-node; memtable **trie** (MBean-verified), commitlog periodic,
disk_access_mode standard, auto_snapshot off; data `/data/tpc-poc` (nvme1n1p3), commitlog
`/commitlog/tpc-poc` (nvme0n1p1). Dataset 2M partitions (~391 MB live, warm-cache). Cassandra
cores 0–7, client 8–11. KeyValue default value size. GC overlay pending per-cell (gc.log
captured). `intel_iommu=pt` NOT set (same for baseline + increments).
