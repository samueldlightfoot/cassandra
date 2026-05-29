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

### 2026-05-29 — Phase D-1 (deterministic core) landed

Implementation: harness commit (next; staged locally).

Files:
- `src/cassandra_agent_harness/agent/stop_conditions.py` (~210 LoC, 5 stops + registry)
- `src/cassandra_agent_harness/agent/goals.py` (~430 LoC, dataclasses + YAML loader + 11-rule validator)
- `src/cassandra_agent_harness/agent/progress.py` (~215 LoC, `Gap`, `compute_gap`, `evaluate`)
- `src/cassandra_agent_harness/agent/__init__.py` (re-exports public API)
- `src/cassandra_agent_harness/cli.py` (`cah goal-validate`, `cah evaluate`)
- `tests/agent/test_goals.py` (20 tests)
- `tests/agent/test_progress.py` (11 tests)
- `tests/agent/test_stop_conditions.py` (12 tests)
- `tests/fixtures/goals/waf_baseline.yaml` (copied from this task folder's sketch)

End-to-end demo (against R5 + the WAF baseline goal sketch):

```
$ cah goal-validate tests/fixtures/goals/waf_baseline.yaml
# total_errors=0, blocking=0, advisory=0 → exit 0

$ cah evaluate tests/fixtures/goals/waf_baseline.yaml \
    --history tests/fixtures/runs/T4-LF4h tests/fixtures/runs/T16-LF4h
{
  "goal_met": false,
  "terminal_stop": null,
  "gap": {
    "missing_cells": [
      "ycsb_a_zipf_0.8+fill=0.04+T8",
      "ycsb_a_zipf_0.8+fill=0.8+T4",
      "ycsb_a_zipf_0.8+fill=0.8+T8",
      "ycsb_a_zipf_0.8+fill=0.8+T16"
    ],
    "missing_replicates": {
      "headline_paper_comparable": 3,
      "headline_cold_start": 2
    },
    "unmet_variance": [],
    "paper_comparable_status": "unmet"
  }
}
# exit 3 (goal not met; gap present)
```

This is the exact shape phase D-LLM's `propose_next_round(history, gap, goal)`
will consume. "Given gap X, pick a regime from {COLD_START_30M,
LOW_FILL_4H, HIGH_FILL_4H, T_SWEEP, REPLICATE_FOR_VARIANCE, STOP}"
becomes a tractable constrained question — the LLM is no longer
re-deriving what we're going for, just choosing tactically.

Design choices worth remembering:

- **YAML kwargs are filtered at call time, not validated at load.**
  `evaluate()` uses `inspect.signature` to drop unknown kwargs before
  calling each stop fn. So a goal authored against a newer harness
  version can have kwargs that older fns ignore, and vice versa. Avoids
  brittle version coupling between goal configs and Python code.
- **Stops that need round state take it as a parameter.** Two stops
  (`max_rounds_reached`, `no_progress_detected`) can't be computed from
  cell history alone — they need the loop driver's persisted state.
  Threading those in via `evaluate(..., rounds_completed=N,
  rounds_without_progress=N)` keeps `evaluate()` pure and stateless.
- **Rule 11 is advisory-only.** `is_advisory(err)` returns True for
  `rule==11`; `blocking_errors()` filters them out. The CLI emits WARN
  for advisory errors but exits 0 if no blockers are present.
- **`Goal` is a single concrete dataclass for now.** The plan called for
  `Goal` as an ABC with `WafBaselineGoal` as the concrete; I collapsed
  to one type because there's no second investigation yet. Refactor to
  ABC when a second goal shape lands (e.g., `TwcsTimeseriesGoal`).
- **Fixture copied, not symlinked.** `tests/fixtures/goals/waf_baseline.yaml`
  is a copy of the fork's sketch. If the sketch is updated, the harness
  fixture must be re-synced; the test `test_load_goal_round_trips_sketch_into_python_values`
  will fail if it drifts.

Library suite: 304 → 347 (+43: 20 goals + 11 progress + 12 stop_conditions).
Ruff clean on all touched files.

Next: Phase D-LLM (round_controller.py with `propose_next_round` LLM call +
`pursue(goal)` loop driver). Estimated ~300 LoC + ~150 LoC tests. The LLM
call has a constrained Pydantic output schema; tests use a fake LLM
client to verify regime selection matches gap shape.

### 2026-05-29 — Phase C (deterministic result reviewer) landed

Implementation: harness commit (next; staged locally).

Files:
- `src/cassandra_agent_harness/analysis/__init__.py` (new, re-exports)
- `src/cassandra_agent_harness/analysis/review.py` (new, ~480 LoC)
- `src/cassandra_agent_harness/cli.py` (`cah review` subcommand)
- `tests/analysis/__init__.py` + `tests/analysis/test_review.py` (20 tests against real R5 fixtures + synthetic edge cases)
- `tests/test_cli.py` (+3 review subcommand tests)
- `tests/fixtures/runs/{T4,T16}-LF4h/.../cell.json` + `summary.json` rsync'd from rig as committed fixtures

Design choices worth remembering:

- **Deterministic-only.** Findings prose + investigation outlook are
  rendered as `<!-- LLM, phase D -->` stubs. The phase C reviewer must
  not fluently describe what a number "means" — that risks
  fluent-but-wrong interpretations getting baked into the public Jira
  writeup. Phase D's LLM call fills the stubs.
- **f-strings, not Jinja.** One template, one renderer function.
  Jinja would add a dependency and a templates directory for a single
  template; the readability cost of f-string concat is small enough
  to not pay it.
- **Append-only safety by default.** CLI writes `review_draft.md`
  next to the run; `--append-to` appends and never rewrites. The test
  `test_review_command_appends_only_does_not_rewrite` verifies prior
  content of an existing `results.md` is preserved verbatim.
- **Multi-run round shape.** `review_round([T4, T16], round_id="R5")`
  matches the actual investigation pattern — R5 was two run_dirs
  treated as one round. `review_run(single)` is a thin wrapper.
- **Real-R5 fixtures are the strongest acceptance signal.** The
  rendered `## R5` stanza matches the human-written numbers to 4
  decimal places. This is exactly what the
  `feedback_fixture_test_against_real_output` lesson from Phase A
  predicts: synthetic-only tests would have validated a parser
  against my mental model of `cell.json`, not the real shape.

End-to-end smoke output (`cah review T4-LF4h T16-LF4h --round-id R5
--paper-ssd-waf-range 0.9,1.5 --paper-db-waf-range 1.0,4.0`):
6 cross-checks all pass, single-replicate caveat surfaces correctly,
prose stubs present.

Library suite: 281 → 304 passing. Ruff clean on touched files.

Next: Phase D (goal-driven loop) — the actual LLM-led piece, where
agent/goals.py + agent/progress.py + agent/round_controller.py wire
the watcher (A) + preflight (B) + reviewer (C) into pursue(goal).

### 2026-05-29 (late) — Phase B (preflight gatekeeper) landed

Implementation: harness commit (next; staged locally).

Files:
- `src/cassandra_agent_harness/prereqs/preflight.py` (~370 LoC)
- `src/cassandra_agent_harness/cli.py` (`cah preflight` subcommand)
- `tests/prereqs/test_preflight.py` (31 tests)
- `tests/test_cli.py` (+3 tests)

Design choices worth remembering:

- **`PreflightCheck` wraps the existing `Check = Callable[[], CheckResult]`
  callable rather than redefining it.** Provenance metadata
  (`derived_from_memory` slug, `severity`) lives on the wrapper. Means
  every existing check in `prereqs/checks.py` can be lifted into a
  profile by adding a one-line `PreflightCheck(...)` shim.
- **`run_preflight` is defensive.** Catches `Exception` from check
  callables and turns it into a failing `CheckResult`; never raises.
  The whole point of preflight is to refuse launch with a clear
  reason — a check that itself crashes can't escape that contract.
- **`check_drive_regime` searches the OCP snapshot dict for any of
  several candidate keys.** nvme-cli 2.x with the OCP plugin emits
  `"Percent free blocks": N`; the raw-binary fallback in the harness's
  own OCP parser doesn't expose this field at all. The check reports
  unparseable with a clear remediation pointing at nvme-cli 2.x
  install — better than silently passing on a snapshot that's missing
  the field.
- **YAML config schema mirrors what `runbook.md` already documents.**
  `PreflightConfig.from_yaml` takes `cassandra_home`, `cassandra_yaml`,
  `cassandra_jar`, `drive_serial`, `data_mount`, `cli_argv`,
  `required_cli_flags`, `expected_classes_in_jar`. No fancy schema
  validation — just `KeyError` on missing fields and `ValueError` on
  non-mapping top-level. The CLI exits 1 on either.
- **Two memories deliberately deferred** (with rationale in the plan):
  - `feedback_rsync_before_rig_launch` — needs workstation-side mtime
    comparison; not feasible from a rig-side preflight.
  - `feedback_cassandra_easy_stress_keyspace` — workload-spec concern,
    nothing to check against rig state. `check_cli_recognizes_flags`
    covers the analogous "CLI knows what we mean" risk.

End-to-end smoke validated the right thing: against a tmp config on
macOS, the 3 Linux-only checks (swap, OCP, drive-isolation) refuse with
clear `how_to_fix` strings while the 3 portable checks (auto_snapshot,
jar-class, CLI flags) pass. Exit code 1 propagates.

Library suite: 247 → 281 passing. Ruff clean.

Next: Phase C (deterministic result reviewer) or pause for review.

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
