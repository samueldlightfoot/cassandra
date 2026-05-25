# GDT investigation — strategic recommendation

Written 2026-05-25 after Run #004 (the only methodologically valid run) showed a deferral-not-savings outcome and zero wholesale-drop events. Companion to `assessment.md` (first-principles analysis) and `../gdt-ucs/results-run-004.md` (the data).

## Effort to date (rough)

- 30–50 hours across: cassandra-agent-harness lib, gdt-poc-harness investigation layer, DeathtimeClassifier + UCS partition wiring + MinLocalDeletionTime classifier + tests in cassandra fork, MixedTTLKeyValue workload + tests in easy-cass-stress fork, build-rev tripwire, defensive jar checks, ~10 investigation docs, 5 bench runs (4 invalidated by the stale-JAR bug, 1 valid).

## Reusable output (independent of GDT outcome)

- `cassandra-agent-harness` — generic Python lib for driving Cassandra perf investigations under agent automation.
- `gdt-poc-harness` — a working pattern for "Investigation" classes on top of the lib.
- Defensive prereq checks (JAR uniqueness, JAR content, JAR freshness) — these prevent the entire class of "tested the wrong build" failures in any future investigation in this fork.
- `MixedTTLKeyValue` workload — could be useful for any future Cassandra TTL/expiry/compaction investigation.
- The build-rev tripwire pattern (bump-marker + class-init log) — generally applicable.

## The publishability constraint

For a blog post or CEP, a credible perf finding needs (a) a simple test, (b) a defensible measurement, and (c) a result that's robust to single-run variance. The paper's GDT claim was **~5% DB WAF reduction** on LeanStore with YCSB-A — against a LeanStore baseline that had **zero deathtime awareness**. UCS's baseline already has `maxTimestampDescending` selection, which is deathtime-aware in a coarse sense. So the achievable "GDT-on-UCS minus already-deathtime-aware-UCS" delta is plausibly 0–5%, before any workload contrivance.

Even a clean reproducible 3% would be a thin pitch: "3% on a TTL-heavy workload constructed to favour the mechanism" is not a strong CEP. And we don't yet have evidence we can hit even that without contrivance.

## What Run #004 actually told us

- The TTL-aware classifier **functions** (the code runs, the bucketing happens, compactions fire fewer times with smaller per-compaction inputs).
- It **does not produce real work reduction** in our 15-min measurement window — the apparent in-window savings is deferred to compactions that would happen later.
- It **does not trigger the wholesale-drop pathway** (Benefit A from the assessment) — zero "fully expired SSTable dropped" events. Likely cause is Cassandra's `expired_sstable_check_frequency_seconds = 600` default firing too rarely on a short run, but could also be insufficient class-purity.
- It introduces a **probable p99 regression** (+35% in this run, but within the ~50%-wide noise band we've observed across baseline runs).

## Recommendation: stop the perf-positive chase, write up the negative, pivot

### Why stop here

1. **Ceiling is low by construction.** UCS isn't a naive baseline; the gap GDT can close on top of it is small.
2. **Mechanism dependency is structural.** Benefit A requires conditions (class-pure SSTables fully TTL-expired, expired-check firing in time) that are hard to construct without contrivance.
3. **Sunk-cost trap is clearly in play.** We've already done 5 bench runs and built a lot of code. The temptation to "do one more round to justify the effort" is exactly the wrong instinct.
4. **The output you'd actually publish would be modest at best.** "3% on a constructed workload" isn't the result you want. The negative result is honest and serves the community.

### What to do (~5 hours total)

1. **~4h: Single coherent write-up**
   - Recap the paper's claim and modest expected ceiling
   - State the source-code observation that UCS already has `maxTimestampDescending` selection (this is the load-bearing argument)
   - Report Run #004 honestly: numbers in-window, plus the deferral correction (12 extra SSTables, identical live disk)
   - Note we never observed Benefit A trigger, and identify the `expired_sstable_check_frequency_seconds` configuration as the prime suspect
   - Be explicit that this is a *negative* finding about this specific approach on UCS, not about the GDT principle in general
   - Cite the operational lessons (stale-JAR + jar-freshness controls) — they're a genuine contribution for anyone running Cassandra perf benches against a fork

2. **~1h: Archive the Run #001/#002/#003 docs**
   - Don't delete — they're useful as a "what we thought we saw, and why we were wrong" story
   - Add a header note: "Invalidated. See stale-jar-postmortem.md."

3. **Decision: separately, pursue NoWA (paper §3) on the DC NVMe when/if it lands.** That's where the paper's bigger win lives. The entire harness substrate carries over for free. Before committing €56/mo, **re-read paper §3** to verify (a) NoWA's mechanism is purely host-side (any drive works) vs needing specific OCP firmware features, and (b) the actual gain number — I cited ~40% from memory and that may have been the FDP number, not NoWA's.

### What to NOT do

- Another GDT round. Even the cleanest version of the assessment's Step-1-through-4 plan would, in the best case, show ~3% on a contrived setup. That's not the publishable result, and we've established the ceiling.
- Treat the harness work as "wasted if GDT doesn't pan out." It isn't — it's substrate.

## NoWA vs FDP clarification (was conflated in earlier discussion)

- **FDP (Flexible Data Placement)**: SSD-side **hardware** feature, needs FDP-capable drives. Hetzner doesn't offer them. **Out of scope.**
- **NoWA (No Write Amplification, paper §3)**: a **host-side software** technique that makes writes SSD-aligned, using "compensation writes" to handle writes that cross zone boundaries. **Paper claims it works on commodity SSDs.**
- **The DC NVMe upgrade's role**: primarily **enables measurement**. Standard NVMe SMART exposes only `data_units_written` (host bytes); OCP-compliant DC drives expose a physical-write counter that lets you quantify SSD-side WAF. Consumer drives leave us blind on the relevant metric. **Whether NoWA's mechanism itself requires DC features (vs just being measurable on them) is an open question to verify before spending the money.**

## Memory entries that would help future sessions

- `feedback_cassandra_jar_rebuild.md` — written. Captures the JAR-rebuild gotcha.
- `project_stress_tool_naming.md` — written. Captures the easy-cass-stress fork-naming quirk.
- **Suggested**: a project memory recording "GDT-on-UCS investigation: recommended stop after Run #004. NoWA is the pivot target if user wants positive perf result." So a future session doesn't restart the GDT chase from scratch.
