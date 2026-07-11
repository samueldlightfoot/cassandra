# Lessons

## Explainers: a threshold is not explained until you name what it bounds
2026-07-08, TPC Phase 2 G1 explainer. First draft said "that is the 70%: most of the box
from one core" — user: "not sticking... what is, specifically? if we hit 63% and are cpu
bound, why is that an issue?" A gate number is only explained when you state (1) the
specific scenario it bounds (here: the hot-partition-on-one-shard regression, NOT box-wide
throughput — box-wide the number doesn't matter at all), (2) what concretely happens on
each side of the line (helper pool vs pure shard model), and (3) why the measured failure
mode matters (bench = ring's best case; a real shard's core is mostly query work).
Rule: before writing "that's why X%", ask — in which scenario does the system regress if
this number is missed, and who pays? If the answer isn't in the doc, the number is
decoration. Applies to every gate/SLO/threshold writeup, not just HTML explainers.

## Never paste chat-register prose into a learning doc
2026-07-08, same explainer, second correction ("these two paragraphs are just difficult to
read"). The patched paragraphs were my chat summary pasted in: 35-40-word sentences, 5+
referents each, freshly minted abstractions standing for whole earlier arguments ("the
deleted cost class", "the compromise shape"), and the key arithmetic buried mid-sentence.
Chat register maximizes propositions per word; teaching register minimizes retrievals per
sentence. They are opposites — re-expand before porting. Codified as story-explain SKILL.md
rule 10 with the evidence (Kintsch & Keenan 1973, Haviland & Clark 1974, Cowan 2001).

## Phase closeout must stop the phase's monitors — and monitors must emit on poll failure
2026-07-09, TPC Phase 2→3 transition. Two persistent rig-watching monitors (fio + JMH
sweep) were still "running" a day after both sweeps completed. Double failure: (1) the
phase-closeout/handoff ritual archived data and wrote docs but never TaskStop'd the
monitors it had armed; (2) the monitors were designed to self-exit on 'SWEEP COMPLETE',
which WAS in both logs — they were wedged because the poll loop hides SSH failure
(`|| { sleep 60; continue; }`), so a dead credential/network path loops silently forever,
indistinguishable from "still watching". Rules: (a) phase closeout checklist includes
"stop every monitor this phase armed" — self-terminating design is not proof of
termination; (b) poll loops must emit after N consecutive poll FAILURES (not just log
staleness) — extends [monitor silence is not success] to the transport layer.

## A benchmark tool's control flags: read the source before theorizing (and one process)
2026-07-10, TPC 4.1 stress baseline. Lost 4h+ (8h+ across sessions) mis-driving
cassandra-easy-stress: passed `--rate` alongside `--maxwlat/--maxrlat` (the latter is an
optimizer that CONTROLS the rate to a latency SLO — passing `--rate` too hand-drives it and
defeats it), stacked 3 processes (3 uncoordinated optimizers on shared client cores →
stall), and inherited a handoff/memory full of FALSE "tool delivers only 0.1-0.25× of
offered" numbers (actually ~0.94 in the clean regime; the low figures were a skewed-dataset
+ client-side-measurement artifact). Root fixes: (1) for latency-defined saturation use ONE
process + `--maxwlat/--maxrlat` ALONE, no `--rate`, measured server-side; (2) benchmark on a
FRESH dataset; (3) never over-drive `--rate` (shared-RateLimiter busy-spin collapse: delivered
DROPS, latency = CO noise). Meta-rule: when a tool behaves "weirdly", read its source
(RateLimiter/optimizer/metrics units) BEFORE running more experiments — I confirmed
`--maxwlat` is in ms, is CO-corrected, and only adjusts when utilization ≥0.9 in ~15 min of
reading, after hours of black-box probing. And correct the memory/handoff that propagated the
false model, or the next session repeats it. Runbook: phase-4-poc/STRESS-RUNBOOK.md.

## Don't compress a "generalization" caveat into a "no-benefit" claim (2026-07-10)
Wrote that a 6-physical-core box makes TPC "barely matter" / load box was "the wrong spend."
Overreach: I took a red-team subagent's *generalization* point (6-core single-NUMA won't show
many-core/NUMA scaling) and inflated it into TPC being *not beneficial* at low core count —
which the project's OWN docs contradict (`effort.md:83-84`: the per-shard lock / global
cachelines / parked-coordinator are a tax "at every core count, growing with core counts";
`increments.md:103`: benefit measured directly via JMX contended-puts, gate = the loaded tail).
Rules:
- Distinguish EXISTENCE of an effect from its MAGNITUDE/generalization. "Muted at small scale"
  ≠ "absent." Say which one you mean; they lead to opposite decisions (don't-bother vs
  conservative-lower-bound).
- Before asserting where a system's benefit does/doesn't appear, GROUND it in the project's own
  stated mechanism + gate (grep the design docs), not abstract reasoning or a subagent's
  compressed verdict. Subagent conclusions are inputs to verify, not facts to relay.
- A benefit that is load-gated (shows under contention) is not core-count-gated. Cassandra TPC's
  PoC lever (lock/cacheline contention under load) is present at 4-6 cores; low load can even
  REGRESS (routing-hop crossover) — so measure the loaded tail, not sub-knee.

## Check the load tool's concurrency default before blaming the box (2026-07-10)
Spent the session concluding a single stress process "caps ~100k" and needing multi-process /
off-box to drive Cassandra. The real cause: `cassandra-easy-stress --concurrency` defaults to
100 (a per-thread in-flight semaphore). Little's law: throughput = in-flight ÷ latency, so 100
in-flight ≈ 100k at ~1ms — a self-imposed cap, not the box/tool/connections. Raising it to 3000
let ONE off-box process hit 253k @ Cassandra 97%. Rules:
- For any load generator, FIND AND CHECK the concurrency/in-flight/pool-size knob and its DEFAULT
  before concluding a throughput ceiling. Read the tool's `--help` + source param defaults early.
- Distinguish the RATE limiter (`--rate`, offered) from the CONCURRENCY limiter (in-flight).
  A low in-flight cap throttles delivery no matter how high `--rate` is.
- When a user is "baffled" by a workaround (here: multi-process), treat it as a strong signal to
  re-derive the mechanism from source, not to defend the workaround.
- The off-box decision was still right (clean isolation), but justify it by isolation, not by a
  per-process throughput cap that didn't exist.

## Code comments must be self-contained — no task-doc refs or internal codenames (2026-07-10)
Wrote source comments referencing the planning docs ("TPC I0", "I1 step 2", "design-target §5/§9",
"poc-criteria §5", "increments.md §2"). User: "stop adding comments with 'TPC I0' — these docs won't
be committed with the code." The `tasks/` planning folder is NOT part of the committed repo, so any
comment pointing at it (or at a program-internal increment codename) is a dangling reference to a
future reader of the codebase. Rules:
- A committed code comment must stand alone. Explain the WHY in terms of the code/system itself
  (behaviour, invariant, hazard), never "see design-target §X" or "I0/I1/increment N".
- Drop program/planning codenames entirely from source: not "TPC I0", not "the PoC gate", not
  "(D6's discriminator)". Those belong in `tasks/*.md`, which ships separately.
- When translating a plan into code, actively rewrite each rationale from doc-speak into
  self-contained prose. The plan's shorthand is for me; the comment is for the next maintainer.
- Same rule for commit messages and public Javadoc.
