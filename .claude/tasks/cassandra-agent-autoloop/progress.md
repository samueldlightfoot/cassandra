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
