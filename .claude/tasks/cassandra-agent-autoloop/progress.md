# cassandra-agent-autoloop — progress

## Session log

### 2026-05-28

- Plan drafted in `task_plan.md` after a strategy discussion about agent
  orchestration patterns; rationale + rejected alternatives in
  `findings.md`.
- Scope committed: 4 phases (A. Watcher → B. Preflight → C. Reviewer →
  D. Round controller). Explicit cuts: multi-agent debate, LLM-led prose
  before phase C, tree-search, LLM continuous-parameter selection,
  multi-investigation generalization.
- No code changes yet. Awaiting user sign-off on the plan before phase
  A implementation starts.

### 2026-05-29 (afternoon) — Phase A closed against real R5 logs

R5 T16 finished (01:52Z, DB WAF 1.74, −36% vs T4). Pulled both
launch.logs off the rig as fixtures in `tests/fixtures/runs/` and
replayed the watcher against them — two real-world gaps exposed and
fixed in the same loop:

1. **Plain-English success message.** The wrapper's terminal line is
   `All 1 cells succeeded` (English prose), not a JSON
   `cells_succeeded: 1`. The original `cells_succeeded_nonzero` rule
   only matched the JSON shape, so a fully-clean R5 cell would never
   reach terminal `ok`. Added a new `all_cells_succeeded` SUCCESS rule
   matching `r"All \d+ cells? succeeded"`.

2. **launch.log is sparse by design.** A 4h cell only emits ~9 lines
   to launch.log (transitions only); cass-stress per-tick output lives
   in `stress.log`. The original 600s silence threshold would emit a
   false-positive ESCALATE 10 minutes after prefill. Raised default
   silence threshold from 600s → 18000s (5h, longer than max
   measurement window). The CLI flag `--silence-threshold-s` now
   documents the trade-off: lower it (e.g., 600s) when tailing
   stress.log for tight monitoring.

Also added four new HEARTBEAT rules to bridge the gaps between major
transitions: `prereq_pass` (`[PASS]`), `bootstrap_schema`
(bootstrapping schema / WAF baseline announce), `steady_state_reached`,
and tightened `prefill_progress` to a word-boundary match so it
doesn't fire on unrelated mentions of "prefill" in human prose.

Tests added: 6 parametrized replay tests over both T4 and T16 cells
(no false-positive FATAL/WARN; terminal `ok` driven by
`all_cells_succeeded`; default silence threshold survives a 4h cell).
Suite: 236 → 247.

Plan acceptance criterion #7 (deferred at first landing) now closed.
Phase A is done.

Lesson surfaced (worth promoting to lessons.md): **fixture-test against
real production output, not just synthetic.** Synthetic fixtures gave
46 green tests, all of which would have happily passed on a watcher
that mis-classifies every real R5 run. The synthetic shape ("ops/s=...")
matched what the documentation suggested cass-stress emits; reality
(in launch.log) is much sparser. The watcher would have shipped broken
without the replay step.

### 2026-05-29 — Phase A (watcher) landed in cassandra-agent-harness

Implementation lives at `cassandra-agent-harness@HEAD` (workstation
checkout; not yet pushed). Files:

- `src/cassandra_agent_harness/agent/watcher.py` (new, ~330 LoC)
- `src/cassandra_agent_harness/agent/__init__.py` (re-export public API)
- `src/cassandra_agent_harness/cli.py` (added `watch` subcommand + 4 exit-code constants)
- `tests/agent/test_watcher.py` (46 tests)
- `tests/test_cli.py` (4 new tests)

Design choices worth remembering:

- **Pure core + IO wrapper.** `scan_lines` and `evaluate` take no IO and
  no clock; both accept `now` as an injected callable. `watch_once` is
  the thin wrapper that reads `launch.log` from the persisted offset
  and writes `.watch_state.json` atomically. Tests cover all three
  layers separately.
- **Severity enum, not parallel rule lists.** One `DEFAULT_RULES` tuple
  of `WatchRule(name, severity, pattern, description)`; the state
  machine branches on `Severity`. Easier to extend (just add a rule)
  and to test (one `severity_assignment` parametrize covers all rules).
- **FATAL/SUCCESS are sticky; WARN/silence are not.** Terminal decisions
  persist into `state.terminal`; ESCALATE is recomputed every call so
  it auto-clears when heartbeats resume. Avoids the "stuck in alarm"
  failure mode.
- **Snippet truncation at 200 chars.** Bounded memory in the persisted
  state file even for pathological log lines.
- **Corrupt state file → fresh start.** `_load_state` catches JSON
  errors and returns `WatchState()`. The watcher recovers itself; the
  loop body doesn't need to.

Acceptance criteria status: 6/7 met (all unit + integration acceptance);
the seventh — replay against real R3/R4 launch.log files — is deferred
until R5 finishes and the logs can be rsync'd local.

Library suite: 236 passed (was 232). Lint clean.

Smoke-tested the `cah watch` binary against three tmpdir fixtures
(clean → exit 3, fatal → exit 1, marker → exit 0). All three JSON
payloads and exit codes match expectations.

Next: stage and commit the harness changes; then optionally start
Phase B (preflight gatekeeper) or pause for review.

### 2026-05-28 (update — goal-driven refinement)

- User asked: "can we provide a goal to the harness/orchestrator and
  loop until it gets there or detects a terminal condition?" Answer:
  yes, and it's the cleaner framing of phase D.
- Refined phase D from "LLM picks regime each round" to **goal-driven
  loop**: declare structured `Goal` once → deterministic `evaluate()`
  computes `Gap` → LLM picks regime to close gap → loop until
  `goal_met` or terminal stop.
- Added goal-validation discipline: `validate_goal()` enforces 10 hard
  rules + 1 advisory warning (table in `task_plan.md` Phase D). The
  CLI's `pursue` command refuses to start on any error — sloppy goals
  cannot launch the loop.
- New files added to phase D scope: `agent/goals.py`, `agent/progress.py`,
  `agent/stop_conditions.py` (extracted; was previously inline in
  round_controller); plus `tests/test_goals.py`, `tests/test_progress.py`,
  `tests/test_stop_conditions.py`.
- Wrote `goals_waf_baseline_sketch.yaml` in this task folder — the
  proposed goal shape for the current WAF investigation, used to
  sanity-check the dataclass design before committing a real config
  to `waf-baseline-poc/goals/waf_baseline.yaml`.
- Updated `findings.md`: added §7 ("Goal-driven loop (refinement of
  phase D)"); renumbered "Cross-references" to §9.
- Still no code changes. Awaiting user review of the sketch YAML before
  phase A starts.

## Test results

(filled in as phases land)

## Lessons surfaced

(promoted to ../lessons.md if cross-cutting)

## Next action

User reviews `task_plan.md`. On approval, start phase A:
`cassandra_agent_harness/agent/watcher.py` + tests with R3/R4 fixture
launch logs. Land it in time to be useful for R5 wrap-up or the next
round, whichever comes first.
