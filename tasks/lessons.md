# Lessons

## Performance attribution: measure before you conclude, verify magnitude within bounds
2026-07-12, I1 single-node A/B. I predicted i1 would be "neutral within noise," then when the
data showed +28.5pp CPU I attributed it to "a dispatch hop" — twice stating a mechanism without
measuring it. User: "I can't see that being the cause for 30% more CPU" and "verify all your
conclusions with real numbers and data to ensure within expected bounds." The dispatch-hop story
was ~6× too small (a context switch is ~1-5µs → ~4pp, not 28pp). Rules:
- **Never state a performance cause or magnitude from code-reading/intuition alone.** Attribution
  requires a profile (async-profiler A/B), a decomposition (mpstat %usr vs %sys), or a counter
  (vmstat cs/s). "It's probably X" about CPU/latency is a hypothesis, label it as one.
- **Sanity-check the arithmetic before asserting.** +28pp on 12 cores = +3.4 cores = ~19µs-CPU/op.
  Ask "does my proposed cause plausibly cost that much?" If a single context switch can't, the
  story is wrong or incomplete — keep digging (it was a park/unpark *rendezvous per write* +
  condition machinery, ~19% futex/unpark + 16.5% WaitQueue.signal, all profiler-verified).
- **The subagent can be confidently wrong too** — it claimed "MutationStage + Shard oversubscription"
  but tpstats showed MutationStage idle (1 completed). Verify a load-bearing subagent claim against
  live state before building on it.

## Never `pkill -f CassandraDaemon` (or any `-f <pattern>`) in an SSH command whose own text contains the pattern
2026-07-12, I1 profiling. Ran `pkill -9 -f CassandraDaemon` inside an `ssh root@rig '...'` command.
`-f` matched the remote SSH shell's own argv (which literally contained "CassandraDaemon") and killed
the session (exit 255) mid-restart, taking cassandra down without relaunching. Already documented in
agent-common gotchas + methodology §10; I hit it anyway. Rule: over SSH, use a **self-excluding**
pattern `Cassandra[D]aemon` (the bracket makes the regex match the process but not the literal
command text), or `pgrep -x`/resolved-PID kill. This applies to EVERY `pkill -f` run over SSH.

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

## Commit messages: no phase/increment numbers (2026-07-12) — REPEAT OFFENSE
Despite the rule above, wrote commit subjects like "I5 Phase B1: ...", "I5 Phase B3: ...", and
"Planning notes: I5 Phase B2 ...". User: "you've put phase numbers in commit messages again." The
phase/increment taxonomy (Phase A/B/C, B1/B2/B3, I5, I1, TPC I0, D6) lives ONLY in `tasks/` and is
meaningless to any future reader of the git history. Hard rules for EVERY commit, including
"Planning notes: ..." ones:
- Subject = what the change does in repo-durable terms (e.g. "Route inbound mutations to the owning
  shard executor at ingress"). No "Phase X", no increment codename.
- If a stable handle is needed, use the real feature/flag name (`cassandra.tpc.inbound_shard_dispatch`).
- Check the subject for a phase/increment token BEFORE committing — this is the recurring failure point.

## Async request paths: clear request-scoped thread-locals on the ORIGIN thread (2026-07-13)
Flipping the CQL Dispatcher to async, I moved the per-request teardown (ClientWarn/coordinator-
warnings reset, Tracing.stopSession) onto the completion thread — and forgot the origin. A pooled
Native-Transport worker sets request-scoped thread-locals (captureWarnings, CoordinatorWarnings.init,
and `Tracing.newSession` sets TraceState) before dispatching. In the sync path the same worker also
ran the `finally` teardown, so it stayed clean. Once teardown moves to the completing thread, the
worker keeps the stale state and carries it into its NEXT request → `Tracing.newSession`'s
`assert get() == null` fires under `-ea`. Rules:
- When teardown of an origin-thread thread-local moves to another thread, the origin must STILL clear
  its own copy (capture the bundle, then `ExecutorLocals.clear()` + `captureAndClear()` the origin).
  Capture ≠ clear.
- `ClientWarn.set`/`captureWarnings` preserve the current `traceState` (they only swap the clientWarn
  slot), so a stale TraceState is NOT overwritten by the next request's setup — it must be cleared.
- This class of leak is invisible to code review and unit tests; it only shows up when the LIVE traced
  path runs repeatedly. A dtest that fires several traced requests through the real native protocol
  (not `coordinator().execute()`, which bypasses the Dispatcher) is what caught it. Always exercise a
  newly-activated dormant path end-to-end before trusting it.

## macOS: single-node in-JVM native dtests run; multi-node needs loopback aliases (2026-07-13)
A 1-node `distributed.Cluster` dtest with `NATIVE_PROTOCOL` + the datastax driver runs on macOS
(only binds 127.0.0.1). A 3-node one fails `failed to bind to /127.0.0.2:7012` — macOS doesn't bind
127.0.0.2+ by default. Bind with `sudo ifconfig lo0 alias 127.0.0.2 up` (etc.) or defer multi-node
to Linux/CI. Not a code failure. Complements [[feedback_macos_multinode_dtest_unreproducible]]
(CCM/TCM) and [[feedback_injvm_dtest_runs_on_macos]].

## Allocation-free ≠ cheaper CPU: TreeSet(CASE_INSENSITIVE_ORDER) is not a free swap (2026-07-13)
To kill a per-write `toLowerCaseLocalized` allocation in the system-keyspace checks, the first cut
made the name sets `TreeSet(String.CASE_INSENSITIVE_ORDER)` — allocation-free `contains`. Profiling
on the rig showed it was a REGRESSION: the keyspace-check CPU went 4.01% → 5.99%. The comparator does
`Character.toUpperCase`/`toLowerCase` per char, and `TreeSet.contains` does ~log(n) compares per
lookup, ×~8 lookups per `getKeyspaceInstance` — more CPU than the allocate-lowercased-copy + hash it
replaced. The allocation was gone (good for the GC tail) but on-CPU got worse.
- Rule: removing an allocation can COST CPU if the replacement does per-char/per-compare work. For
  allocation-free case-insensitive membership on a hot path, prefer a direct hash `contains(name)`
  fast-path and only lowercase-a-copy when the input actually contains an uppercase char (rare) —
  not a comparator that case-folds every char of every compare.
- Rule: a "cheap, obvious" micro-opt still needs the re-profile. This one looked like free
  low-hanging fruit and was net-negative on CPU until measured. The frame-level self-cost fold caught
  it where aggregate busy% (±1pp noise) could not. Reinforces [[feedback_cassandra_jar_rebuild]] /
  [[feedback_verify_metrics_at_source]]: prove the win at the frame level, per-arm, before claiming it.

## A new dropwizard metric `type` must be registered in metricGroups, or `<clinit>` kills every request (2026-07-13)
CQL ingress routing built fine, unit-tested green, but the first native request on the rig died: a fresh
metric group `CqlShardRouting` (via `new DefaultNameFactory("CqlShardRouting")`) is not in
`CassandraMetricsRegistry.metricGroups` (a hand-maintained `ImmutableSet` of every allowed metric `type`),
so `Metrics.counter(...)` → `verifyUnknownMetric` throws `IllegalStateException: Unknown metric group`.
That throw is inside the class's static initializer, so it becomes `ExceptionInInitializerError` then
`NoClassDefFoundError: Could not initialize class …` on EVERY subsequent access — the node stays up but
serves nothing while the feature is enabled. Invisible to unit + in-JVM dtests (they never register the
group under a live registry the way the running node does).
- Rule: adding metrics under a brand-new `type` string requires ALSO adding it to
  `CassandraMetricsRegistry.metricGroups` (the class comment says so) — or the build fails at runtime, not
  compile. Prefer reusing an existing registered group: the internode sibling `ShardInboundRouter` puts its
  counters under `MessagingMetrics.TYPE_NAME` ("Messaging"); the CQL/native analogue is
  `ClientMetrics.TYPE_NAME` ("Client"). Name the counters distinctively (`CqlIngressRouted`) to avoid
  collision within the shared group. Reuse is the smaller change (no core-registry / virtual-table churn).
- Rule: a metric registration that runs lazily on first request (static field on a route class loaded from
  `dispatch()`) is a live-path landmine a green unit suite hides. Exercise the real native transport
  (drive one prepared write through a socket, watch the node's log for `<clinit>`/`NoClassDefFoundError`)
  before trusting a newly-flagged path — the same "dormant path end-to-end" rule as the async teardown leak.

## Check the documented runbook before declaring an operational blocker (2026-07-13)
- Mistake: hit "loadgen deleted, must provision" and reported it as a BLOCKER needing the user, after
  `hcloud context list` showed no active context → I concluded "hcloud unauthed." User corrected:
  "Why is hcloud not auth'd. Instructions should be in runbook."
- Reality: the project CLAUDE.md (`tasks/tpc-migration-planning/CLAUDE.md`) points at
  `~/repos/agent-common/CLAUDE.md`, whose `rig/cloud.md` documents the whole flow: token at
  `~/repos/agent-common/.secrets/hcloud.token`, used via `export HCLOUD_TOKEN=$(cat …)`. `hcloud` reads
  the env var — an empty `context list` is EXPECTED (env-var auth, not a persisted CLI context), not
  "unauthed." Provisioning a fresh ccx43 loadgen is routine and scripted there.
- Rule: before surfacing any access/auth/provisioning/tooling gap as a blocker or an
  AskUserQuestion, READ the operational runbooks the project CLAUDE.md links (here: agent-common/rig/*.md,
  gotchas.md). "The tool looks unauthed / the box is gone" is usually a documented, self-serviceable
  step, not a user decision. Reserve the user's attention for genuine choices.
- Rule: don't infer "unauthed" from one auth surface (`context list`) — check how the token is actually
  supplied (env var, config file, secret path) per the runbook.
