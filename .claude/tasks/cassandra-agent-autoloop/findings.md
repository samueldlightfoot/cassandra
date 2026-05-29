# cassandra-agent-autoloop — findings

## 1. Why this exists

Rounds R1 → R5 of the WAF baseline investigation are entirely
human-orchestrated above the cell level. The harness automates one cell
end-to-end (reset → prefill → warmup → measure → capture). But choosing
which cells to run next, verifying the rig is sane before launch,
mid-run abort decisions, and writing the per-round results section are
all human work.

That meta-loop is the right target for an autonomous-orchestration layer.
This file captures the design priors that informed `task_plan.md`.

## 2. Patterns from the field (2025–2026) that are working

Grouped by what they actually do, not by vendor.

### 2.1 Planner / executor split with persistent scratchpad
Cognition Devin, Claude Code itself, OpenHands, Manus.io. One LLM owns
judgment + state, delegates bounded execution to sub-runs with fresh
context. Planner commits everything to disk so it survives context
compaction; executor returns structured summaries.

**Apply here**: round controller is the planner; `WafBaselineRunner` is
the executor. Watcher is a sub-executor. The task folder (`task_plan.md`
/ `findings.md` / `progress.md`) is the persistent scratchpad — already
exists.

### 2.2 Fan-out / fan-in for independent sub-questions
Anthropic Research, OpenAI Deep Research, the `Agent` subagent tool.
Orchestrator decomposes into N independent sub-queries, parallel spawn,
synthesize.

**Apply here**: only for genuinely independent research questions
("compare our SSD WAF to closest analogues in PVLDB 2024–2026"). Not for
sequential reasoning. Cells are not independent — they share rig state.

### 2.3 Skill library (Voyager pattern)
Voyager (Minecraft), SWE-agent's command library, Claude Code's
user-defined skills. Agent builds up a library of named, parameterized
procedures.

**Apply here**: every `feedback_*` memory is a candidate skill. Phase B
(preflight gatekeeper) converts the operational lessons from prose-in-
memory to executable Python invariants.

### 2.4 Critic / actor with a verifiable signal
SWE-agent (tests pass/fail), Cursor background agents (compile + tests),
Reflexion-style coding agents. Critic must be grounded in external
signal — pure LLM-on-LLM critique collapses into sycophancy after ~3
rounds.

**Apply here**: phase C reviewer is the critic; its signal is grounded
in the existing cross-check rules (`cross_check_workload_counts`,
variance threshold, paper-comparable range). Phase D's LLM proposer is
the actor, validated by deterministic `should_stop()`.

### 2.5 Sleep-loop / long-running scheduled agents
Claude Code `ScheduleWakeup` + `/loop` skill, Codex background agents,
GitHub Copilot Workspace long-task mode, Manus autonomous runs.

**Apply here**: cells are 4h+. Foreground chat is the wrong shape. The
watcher (phase A) is `/loop`-shaped from day one.

### 2.6 Workflow with agentic escape
The pattern under almost every shipping production agent. Deterministic
state machine; LLM is called only at named branch points; LLM output is
constrained to a small enum of next-actions.

**Apply here**: the whole architecture. Phases A/B/C are pure
deterministic code. Phase D's LLM call has output constrained to a
6-value regime enum (`RoundProposal` Pydantic-validated).

## 3. Patterns that were considered and rejected

### 3.1 Multi-agent debate / society of minds
CrewAI-style Researcher + Critic + Manager + Writer roles.

Beyond ~3 roles it tends to perform worse than a single agent — the
personas paraphrase each other. The wins reported in papers are usually
from the *external grounding* (a verifier, a test runner) rather than
from the debate itself.

### 3.2 LLM picking continuous parameters
Examples: agents picking `--measurement-window-s = 12345` or
`--fill-fraction = 0.37`.

LLMs pick plausible-looking numbers but they aren't principled. The
named-regime enum (`COLD_START_30M`, `LOW_FILL_4H`, `HIGH_FILL_4H`,
`T_SWEEP`, `REPLICATE_FOR_VARIANCE`, `STOP`) constrains the output to
choices the methodology already justifies.

### 3.3 Tree-of-thoughts / parallel candidate matrices
Useful for cheap tasks (code completions). Each cell costs ~4h of bench
wall time and consumable SSD writes. Cannot afford to fan out.

### 3.4 Self-improving prompt rewriting
The agent rewrites its own prompt based on outcome. Drift + Goodhart
problems are well-documented. Out of scope.

### 3.5 LLM-led prose for results.md before deterministic reviewer is solid
Risk: fluent-but-wrong interpretation paragraphs that get baked into the
public Jira write-up. Phase C deliberately keeps prose templated, with
computed numbers slotted in. LLM prose can come later, on top of phase
C, with phase C's cross-checks as a guardrail.

## 4. Why the existing harness is well-shaped for this

Looking at `cassandra_agent_harness/`:

- `orchestrator/run.py` is already resumable with the `.complete` marker
  → autonomous loop can re-enter without re-running cells.
- `orchestrator/investigation.py` already separates "what conditions" from
  "how to set up + run a condition" → the round controller writes
  conditions, the runner consumes them.
- `agent/failures.py` already provides `FailureRecord` with a `retryable`
  flag → watcher's `ABORT` decisions can hand off cleanly.
- `agent/tasks.py` already scaffolds the task folder triplet → matches
  the user's tasks/<slug>/{task_plan,findings,progress}.md convention.
- `prereqs/checks.py` has the foundational checks (swap, OCP, drive
  isolation) → phase B builds on these.
- `capture/measurement.py` has `is_steady_state` → the runner already
  adapts to drive behavior; the round controller doesn't need to
  micromanage warmup.

The gap is squarely above the runner: a meta-loop, not a refactor.

## 5. Failure-mode catalog (extends `agent/failures.py`)

The watcher needs to map run-log symptoms to outcomes. Initial list
(from runbook.md §7 + memory `feedback_monitor_silence_is_not_success`):

| symptom | outcome | `FailureClass` |
|---|---|---|
| `unrecognized arguments: --foo` in `launch.log` within 10s | ABORT | `SUBPROCESS_FAIL` |
| `usage:` prefix in `launch.log` | ABORT | `SUBPROCESS_FAIL` |
| `command not found` | ABORT | `SUBPROCESS_FAIL` |
| `Traceback` in `launch.log` | ABORT | `UNKNOWN` (new: `INVESTIGATION_FAIL`) |
| `OOMError` in cass-stress / Cassandra log | ABORT | `WORKLOAD_FAIL` / `NODE_START_FAIL` |
| `JVM crashed` / `# A fatal error has been detected` | ABORT | `NODE_START_FAIL` |
| `connection refused` for JMX | ESCALATE | `JMX_UNREACHABLE` (already retryable) |
| 600s with no `ops/s` / `flush completed` / `compaction completed` | ESCALATE | `WORKLOAD_TIMEOUT` |
| `cells_succeeded=0` in `summary.json` after `.complete` | ESCALATE | new: `CELL_NO_PROGRESS` |
| `MeasurementWindow closed` + `cells_succeeded=1` | OK terminal | n/a |

Reasoning for split: ABORT = "kill the run now, evidence is clear",
ESCALATE = "weird but not unambiguously fatal — page human or LLM
escalation". The watcher must never silently retry without one of those
two outcomes.

## 6. Stop conditions (will live in `agent/stop_conditions.py`)

From `task_plan.md` (cassandra-waf-baseline):

- **MatrixSaturated** — first 2–3 cells of a new regime show SSD WAF
  identically ≈ 1.0 → truncate matrix; the headline finding is
  established.
- **VarianceUnreducible** — std/mean of SSD WAF across replicates of
  the same cell > 10% after 3 replicates → methodology problem;
  STOP and surface to human.
- **DriveLifetimeApproached** — total PMUW + planned next-round writes
  > some fraction of drive endurance → STOP for hardware reasons.
- **MaxRoundsReached** — hard cap (default 10 rounds) to prevent
  runaway loops.
- **HeadlineFindingEstablished** — replicates of the headline regime
  produce a tight CI; ≥ 6 cells confirming the same number → STOP and
  move to writeup phase.

The proposer LLM is shown these and must justify why none apply before
proposing a new round.

## 7. Goal-driven loop (refinement of phase D, added 2026-05-28)

The original Phase D framing was "LLM picks regime each round". That
makes the LLM re-derive what we're going for on every iteration.

The refined framing — **goal-driven loop** — declares the goal once,
uses a deterministic evaluator to compute the gap between current state
and goal, and gives the LLM a tighter job: *given this specific gap and
the regime enum, which regime closes it most efficiently?*

### 7.1 Shape

```
declare GoalSpec   ──►   validate_goal()   ──►   pursue() loop:
                                                  ├─ evaluate(history, goal) → Progress(goal_met | terminal_stop | gap)
                                                  ├─ if goal_met: return GOAL_MET
                                                  ├─ if terminal_stop: return STOPPED(reason)
                                                  ├─ propose_next_round(history, gap, goal) → RoundProposal      (LLM)
                                                  ├─ approval gate (unless --auto)
                                                  ├─ run_round(proposal)                                          (existing runner)
                                                  └─ review_round() → append to results.md                        (phase C)
```

Two consequences worth being explicit about:

- **The LLM call's primary input is `gap`, not `history`.** History is
  there as context but the prompt foregrounds the gap. This collapses
  the LLM's job from open-ended planning to constrained tactical
  selection — easier to evaluate, harder to drift, easier to test
  with canned fixtures.
- **Cells are derived deterministically from the gap.** The LLM picks
  the *regime*; `Regime.cells_for(gap, goal)` constructs the specific
  cells. This keeps continuous-parameter selection out of LLM hands
  (per section 3.2 rejection).

### 7.2 Goal validation is non-negotiable

Sloppy goals → infinite spin. The `validate_goal()` function refuses
to let `pursue()` start unless the goal meets a structured discipline
encoded as 10 hard rules + 1 advisory warning. The rules are itemized
in `task_plan.md` Phase D → "Goal validation rules"; the design
principle behind them, in plain terms:

1. **Bounded** — every budget set and positive. No infinite loops.
2. **Falsifiable** — every outcome must have a Python check function
   resolvable in the registry. "Publishable" is not a check; "headline
   cell has 3 replicates with CV ≤ 10%" is.
3. **Internally consistent** — claims about paper comparability must
   match the matrix that's actually scheduled.
4. **Methodologically sound** — `require_cross_checks_pass` cannot be
   disabled. The `feedback_cross_check_workload_counts` lesson is not
   optional for publishable goals.
5. **Reachable in principle** — wall-time and drive-write estimates
   from the goal must fit inside the declared budgets (with a 50% buffer
   on wall time, no buffer on drive writes — wear is not negotiable).
6. **Terminal-condition coverage** — the required `StopReason`s must be
   present so any failure mode of the loop has a code path that ends
   it.

The CLI exposes validation as a standalone command
(`cassandra-agent-harness goal validate <yaml>`) so a goal can be
checked before any rig time is spent.

### 7.3 What this changes about open questions

Most of the open questions in section 8 (formerly "section 7") about
"how does the round controller see state?" are resolved by the
goal-driven shape:

- The LLM proposer's input is now `(gap, goal)`, both deterministic
  and audit-able. We don't have to decide "should it read nvme directly
  vs results.md" because all rig-state access flows through `evaluate()`
  which is in our control.
- "Model selection" becomes less load-bearing — the proposer is making
  a constrained choice from 6 regimes, not synthesizing a plan. Sonnet
  is probably sufficient for the proposer once the prompt has been
  tuned.

What's *not* resolved: where the watcher runs (local vs rig), and how
`pursue()` survives losing SSH mid-cell. Those are still in section 8.

### 7.4 Why this isn't over-engineered

The temptation is to skip the goal spec and just have the LLM read
`results.md` each round. The case for the goal spec:

- It's the only way to make `--auto` safe. Without a structured stop
  condition tied to a structured goal, `--auto` mode is unbounded.
- It makes the loop testable. Fixture-based tests of `evaluate()` and
  `propose_next_round()` are only possible when the inputs are
  structured data, not free-text markdown.
- It externalizes "what are we doing?" from the agent's head to a YAML
  file, which means a future session (or a different model) can pick
  up the same investigation without re-deriving the goal.

The cost is one dataclass file (`agent/goals.py`, ~250 LoC) and a
~10-rule validator. Worth it for what it buys.

## 8. Open questions to resolve during implementation

- Where does the watcher run? Local (SSH + `tail -f` over the
  connection) or on the rig (push status file back via SSH)? Tentative:
  local, because the user's `/loop` runs in their Claude Code session
  on the workstation. Trade-off: SSH connection drops are a new failure
  mode the watcher itself has to handle.
- How does phase D's LLM see the rig state? Read-only `nvme` /
  `nodetool` queries via SSH, or only what's already in `results.md` /
  `summary.json`? Tentative: only what's persisted to disk + a snapshot
  of `nvme ocp smart-add-log` at proposal time. Keeps the LLM's view
  of state explicit and audit-able.
- Phase D model: `claude-opus-4-7` for proposal quality, or
  `claude-sonnet-4-6` for cost? Tentative: opus for proposal, sonnet
  fine for cross-check summarization in phase C if we ever add prose
  there.

## 9. Cross-references

- `task_plan.md` — phase breakdown + file-level scope
- `progress.md` — session log (created on first work session)
- Memory index `MEMORY.md` for the operational lessons being codified
- `/Users/samlightfoot/repos/cassandra-agent-harness/` — code home
- `/Users/samlightfoot/repos/waf-baseline-poc/` — investigation app, wires the new CLIs in
- `/Users/samlightfoot/repos/fork/cassandra/.claude/tasks/cassandra-waf-baseline/runbook.md` §7 — pre-launch checklist, spec source for phases A and B
- Anthropic "Building effective agents" (2024) — the workflow-vs-agent
  taxonomy this plan is grounded in
- Cognition writeup on Devin — the planner-executor-scratchpad pattern
- Voyager paper (NeurIPS 2023) — skill-library pattern reference
