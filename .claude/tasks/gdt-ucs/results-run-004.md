# Run #004 — First valid GDT-vs-baseline measurement

**Run UUID:** `d11453b1f8b74dad8f80adf7028a1dfb`
**Date:** 2026-05-25 18:30–19:18 UTC
**Branch state:** cassandra `fdp-poc` @ ec3770fd88 (build rev 2), gdt-poc-harness `main` @ c2d8869, cassandra-easy-stress `feature/mixed-ttl-workload` @ 700d0d4.

**Why this is the first valid run:** Runs #001, #002, #003 and "Phase 2 attempt 1" all loaded Cassandra from a May 17 JAR that **predated every GDT commit** (the earliest, `d0e93963ad`, was committed 2026-05-24). `ant -q build` recompiles `.class` files but does not refresh the JAR; nobody noticed. All four prior runs were therefore base UCS vs base UCS. See `stale-jar-postmortem.md` for the full root cause + the controls now in place.

## Config

- **Workload:** `MixedTTLKeyValue` (new, in `samueldlightfoot/cassandra-easy-stress` `feature/mixed-ttl-workload`), 50K ops/sec, 1M input partitions, 2KB rows, 50/50 read/write split, 15-min workload window per condition.
- **Workload knobs:** `ttlClasses=300,86400` (5 min and 1 day classes), `phaseSeconds=300` (phase boundaries every 5 min). gc_grace_seconds=0 on the keyvalue_mixed table.
- **Conditions:** baseline (UCS T4), gdt (UCS T4 + `gdt.enabled=true` + `classifier=min_local_deletion_time` + `base_window_micros=300_000_000`), twcs (TWCS, 1h window).
- **Rig:** Hetzner `65.108.227.158`, Ubuntu 22.04, 2× Samsung MZVL2512HCJQ in RAID-1 (md2), 31 GB heap.
- **Tripwire confirmation:** `GDT POC build rev 2 — Controller class loaded` appears in system.log at both baseline and gdt JVM startups. `GDT classifier: MinLocalDeletionTime (TTL-aware, base window = 300000000 us)` appears at the gdt condition's table initialization. Both confirm the code path actually ran.

## Headline numbers

| metric | baseline | gdt | twcs | gdt vs baseline |
|---|---:|---:|---:|---:|
| ops/sec | 50,012 | 50,001 | 50,009 | −0.02% |
| p99 read (ms) | 251.73 | 340.84 | 327.18 | **+35.4% (worse)** |
| DB WAF (Cass) | 1.421 | 1.287 | 1.405 | **−9.42%** |
| DB WAF (NVMe) | 1.462 | 1.338 | 1.448 | −8.45% |
| `bytes_compacted` (in 15-min window) | 37.10 GB | **24.91 GB** | 35.45 GB | **−32.84%** |
| keyvalue_mixed SSTables at end of condition | 49 | **61** | 9 | **+12** |
| keyvalue_mixed live bytes at end of condition | 45.02 GB | 45.14 GB | 45.13 GB | +120 MB |
| compactions fired on keyvalue_mixed | 32 | 24 | 8 | −25% |
| "fully expired SSTable dropped" log events | **0** | **0** | 0 | n/a |

## What the numbers actually mean

The headline 33% `bytes_compacted` reduction and 9.4% DB WAF reduction are **misleading**. Three observations together prove it's **deferred work, not real savings**:

1. **gdt ended with 12 MORE SSTables on disk** (61 vs 49) — those are uncompacted residue that will still need compacting on the next pass.
2. **Live keyvalue_mixed disk usage is essentially identical** across all three conditions (45.02 / 45.14 / 45.13 GB) — nothing was wholesale-dropped.
3. **Zero "fully expired SSTable" log events** — the wholesale-drop pathway (Benefit A in `gdt-first-principles/assessment.md`) never triggered.

So the gdt condition compacted less *because it deferred more*, not because it had less work to do. Over a long-enough run, gdt's WAF converges to baseline's.

## Specific mechanism observations

- **gdt fired 25% fewer compactions** (24 vs 32). Consistent with TTL-aware bucketing splitting the SSTable population across more buckets, each of which reaches the T=4 threshold less often.
- **gdt's per-compaction output was 10% smaller** (1.04 GB vs 1.16 GB avg). Could indicate slightly more dedup within class-pure buckets, but plausibly within noise given variance in compaction inputs.
- **No "fully expired SSTable" drops in either condition**, despite gc_grace_seconds=0 and the short-TTL class expiring within the run window. The most likely cause is that Cassandra's `expired_sstable_check_frequency_seconds` defaults to 600s, and we never overrode it — so the check fires at most once or twice in a 15-min run, and not necessarily at a moment when a class-pure short-TTL SSTable is fully expired.

## p99 regression

baseline p99 = 251.73 ms, gdt p99 = 340.84 ms — +35.4%. Direction matches the TWCS pattern (fewer-larger compactions = bigger pause spikes), but the magnitude is uncomfortably large.

**Caveat:** baseline p99 across our (now-known-to-be-bench-validity-varying) runs has drifted across the band [251, 302, 330, 407] ms — a ~50% range. Single-run p99 numbers carry that much noise. So +35% could be the real classifier overhead, could be noise, or could be a combination. Would need 3+ replicates to distinguish.

## Honest read

This run produced the **first methodologically valid GDT-vs-baseline data point in the investigation**, and it shows:
- **No measurable benefit** (the apparent benefits are deferral artifacts; live disk size is identical; expiry pathway didn't trigger).
- **A potential regression** on p99 (+35%, but within noise band).
- **No evidence of harm to throughput** (ops/sec within 0.02%).

Net: under this workload + the harness's default 15-min measurement window + Cassandra's default expired-SSTable-check interval, the TTL-aware classifier produces no net positive signal and a probable p99 regression. See `../gdt-first-principles/assessment.md` for what would be needed to potentially see Benefit A trigger, and `../gdt-first-principles/recommendation.md` for the strategic next-step recommendation.
