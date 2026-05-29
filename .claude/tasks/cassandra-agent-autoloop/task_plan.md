# cassandra-agent-autoloop — task plan

## Goal

Add a meta-loop above the existing `WafBaselineRunner` so that a single Claude
Code session can autonomously drive a multi-round WAF investigation:
pre-flight → launch → mid-run watch → result review → next-round proposal,
with **propose-approve-execute** as the default control flow (`--auto` for
unsupervised) and code-gated stop conditions.

This is the **autonomous-orchestration layer**, not a rewrite of the harness.
The Investigation ABC, `WafBaselineRunner`, `.complete`-marker resume
contract, `is_steady_state`, and prereq gates all stay as-is.

See `findings.md` for the rationale, examples of working patterns, and the
explicit list of patterns that were considered and rejected.

## Scope decisions (committed; redirectable)

### Worth building (this plan)

| phase | module | wall | focused | why |
|---|---|---|---|---|
| **A. Watcher** | `agent/watcher.py` | 1–2 d | ~6 h | Codifies `feedback_monitor_silence_is_not_success` into a background process. Smallest useful primitive; lands before anything else depends on it. |
| **B. Preflight gatekeeper** | `prereqs/preflight.py` | 2–3 d | ~10 h | Codifies every `feedback_*` operational lesson into a checked invariant. Prevents the 4h-cost class of mistake. |
| **C. Result reviewer (deterministic)** | `analysis/review.py` | 2–3 d | ~10 h | Parses `cell.json` + `summary.json`, runs cross-checks, computes variance, emits a draft `## Rn` markdown stanza. No LLM prose yet. |
| **D. Goal-driven loop** | `agent/goals.py` + `agent/progress.py` + `agent/stop_conditions.py` + `agent/round_controller.py` | ~1 wk | ~16 h | Declare goal once → deterministic evaluator computes gap → LLM picks regime to close gap → loop until `goal_met` or `terminal_stop`. Refuses to start on any goal-validation failure. |

### Not worth (now)

- Multi-agent debate / critic-actor with LLM-only critic (no grounding signal → sycophancy).
- LLM-led prose generation for `results.md` before deterministic reviewer (phase C) is solid — risks fluent but wrong interpretations.
- A full skill library beyond what `feedback_*` memories already encode.
- Tree-search / multiple-candidate matrix generation — your rounds are too expensive (~4h each) for fan-out.
- Multi-investigation generalization — keep this WAF-baseline-specific for now; generalize only after phase D ships.
- Continuous-parameter selection by LLM (e.g. picking `--measurement-window-s`). Use named regimes.

### Already-decided architecture priors

- **One LLM at the steering wheel.** No multi-persona committee. Subagents only for bounded research (e.g., "compare our SSD WAF to closest analogues in PVLDB 2024–2026") via `Agent` spawn.
- **Deterministic core, LLM at branch points only.** Phase D is the only LLM-led step; A/B/C are pure code.
- **Goal-driven, not turn-driven.** `pursue(goal)` iterates `evaluate → propose → approve → execute → review` until `goal_met` or a `terminal_stop` fires. `validate_goal()` refuses unbounded or unfalsifiable goals at startup — the loop will not start on a sloppy goal.
- **Stop conditions live in code.** Each stop is `(history, goal) -> StopReason | None`. The LLM proposes regimes; `should_stop()` and `goal_met()` decide whether the loop continues.
- **Propose–approve–execute by default.** `--auto` flag flips it to autonomous; never the default until the loop has demonstrated reliability over ≥5 supervised rounds.
- **Sleep-loop runtime.** Cells are 4h+. Use `ScheduleWakeup` / `/loop` skill, not a foregrounded chat.
- **Constrained output space, gap-conditioned input.** The round-controller LLM call takes the structured `Gap` as its primary input and picks from `{cold_start_30m, low_fill_4h, high_fill_4h, t_sweep, replicate_for_variance, stop}`. Each regime is version-controlled Python with a `cells_for(gap, goal)` constructor.

## Phases

### Phase A — Watcher (MVP, lands first)

**Goal**: a long-running process that watches a launching pilot run, applies
the existing `+10s` / `+60s` / heartbeat rules from `runbook.md` §7, and
emits one of `OK | ABORT | ESCALATE` to a status file.

**Files to create / modify** (in `cassandra-agent-harness/`):

- [ ] `src/cassandra_agent_harness/agent/watcher.py`  *(new, ~200 LoC)*
  - `class WatchRule` — name, severity, regex(es), grep-target file
  - `class WatchEvent` — timestamp, rule, snippet, severity
  - `class WatchStatus` — `OK`, `ABORT(reason)`, `ESCALATE(reason, evidence)`
  - `def watch_run(run_dir, *, until_marker=".complete", poll_s=30, rules=DEFAULT_RULES) -> Iterator[WatchEvent]`
  - `DEFAULT_RULES` derived from `runbook.md` §7: `unrecognized arguments`, `usage:`, `error:`, `Traceback`, `Exception`, `command not found`, `OOMError`, `JVM crashed`, `connection refused`, `cells_succeeded=0`. Plus heartbeat: `ops/s=`, `compaction completed`, `flush completed`.
  - Heartbeat absence rule: if no heartbeat-match in last `silence_threshold_s` (default 600s), ESCALATE.
- [ ] `src/cassandra_agent_harness/cli.py`  *(modify)*
  - Add `cassandra-agent-harness watch <run_dir>` subcommand
  - Streaming output: one JSON event per line on stdout, exit code reflects terminal status
- [ ] `tests/test_agent_watcher.py`  *(new)*
  - Fixture-based: feed canned `launch.log` content, assert rule fires
  - Use the actual R4 / R5 launch.log files (rsync a copy to `tests/fixtures/runs/`) so we know the rules match real-world output
  - Tests for: clean run (heartbeat OK), startup-arg-error (ABORT inside 10s), silent hang (ESCALATE after 600s), `Traceback` mid-run (ABORT)
- [ ] `tests/fixtures/runs/`  *(new dir)* — anonymized copies of R3 / R4 launch logs

**Integration with `/loop`**:

```bash
# user kicks off
/loop 5m cassandra-agent-harness watch /data/results/T16-LF4h-... --rig 157.180.98.112
```

Status file `<run_dir>/watch_status.json` updated atomically each poll;
the `/loop` body reads it and either re-schedules or surfaces an escalation.

**Acceptance criteria**:
1. Replays an actual R4 launch.log through the rules → emits zero false-positives.
2. Replays an injected `unrecognized arguments: --foo` → emits `ABORT` within 10s simulated time.
3. Replays a 700s silent window → emits `ESCALATE` exactly once (not repeatedly).
4. The CLI subcommand exits 0 on `.complete`, exits 1 on `ABORT`, exits 2 on `ESCALATE`.
5. 100% mypy strict on the new module. Library suite remains green.

**Decision gate after A**: if watcher catches one real-world incident in
R5 wrap-up or a follow-on run, proceed to B. If it produces false positives,
fix rules before B.

### Phase B — Preflight gatekeeper

**Goal**: every `feedback_*` operational memory becomes a Python check the
harness runs before any pilot launch, refusing on failure with a structured
reason.

**Files to create / modify**:

- [ ] `src/cassandra_agent_harness/prereqs/preflight.py`  *(new, ~300 LoC)*
  - `class PreflightCheck` — `name`, `severity` (`block` | `warn`), `derived_from_memory` (slug), `run() -> CheckResult`
  - `class CheckResult` — `name`, `ok: bool`, `evidence: dict`, `remediation: str | None`
  - `def run_preflight(profile: PreflightProfile, *, rig: RigHandle) -> list[CheckResult]`
  - Profiles: `cold_start`, `low_fill`, `high_fill`, `paper_comparable` — each is a list of checks (and their parameters)
- [ ] Individual check implementations (one function per memory):
  - `check_rsync_synced(local_paths, rig)` ← `feedback_rsync_before_rig_launch`
  - `check_jar_contains_class(jar_path, fqcn)` ← `feedback_cassandra_jar_rebuild`
  - `check_drive_regime(serial, expected_percent_free_range)` ← `feedback_mkfs_ext4_trim_undoes_precondition`
  - `check_auto_snapshot_disabled(cassandra_yaml_path)` ← `feedback_cassandra_drop_keyspace_snapshots`
  - `check_keyspace_name(expected="cassandra_easy_stress")` ← `feedback_cassandra_easy_stress_keyspace`
  - `check_swap_off()` ← already exists in `prereqs/checks.py`, wrap it
  - `check_ocp_available(serial)` ← already exists, wrap it
  - `check_drive_isolation(serial)` ← already exists, wrap it
  - `check_cli_recognizes_flags(cli_invocation, required_flags)` ← `feedback_monitor_silence_is_not_success` (the "verify flags exist before launch" angle)
- [ ] `src/cassandra_agent_harness/cli.py`  *(modify)*
  - `cassandra-agent-harness preflight --profile <name> --config <yaml>` subcommand
  - Exit code 0 if all `block` checks pass; 1 if any `block` fails; warn-only output if only `warn` checks fail
- [ ] `tests/test_preflight.py` *(new)* — one test per check, using mocks for the rig calls
- [ ] **Wire into `waf-baseline-poc`**: the existing pilot CLI calls `run_preflight(profile=...)` before launching the runner; if it fails, refuses with structured reason printed to stderr
- [ ] Update `runbook.md` §7 to say "this is now executable: `cassandra-agent-harness preflight --profile <name>`"

**Acceptance criteria**:
1. Every `feedback_*` slug in the memory index either has a corresponding check function or is documented as "advisory, not auto-checkable" with reason.
2. Running `preflight --profile low_fill` against the current rig state passes when the rig is set up correctly, fails with a clear remediation when (e.g.) the percent_free is wrong.
3. The pilot launcher in `waf-baseline-poc` refuses to launch on `block`-level failure.
4. CI / library suite green.

**Decision gate after B**: if preflight catches at least one issue that would
have cost time on the next round, proceed to C.

### Phase C — Result reviewer (deterministic-only)

**Goal**: given a completed `<run_dir>` (post-`.complete`), parse the
artifacts, run cross-checks, compute variance vs prior rounds, emit a draft
`## Rn` markdown stanza for `results.md` and updated headline numbers for
`summary.md`. **No LLM prose generation in this phase** — prose is
templated with computed numbers slotted in.

**Files to create / modify**:

- [ ] `src/cassandra_agent_harness/analysis/__init__.py` *(new)*
- [ ] `src/cassandra_agent_harness/analysis/review.py`  *(new, ~400 LoC)*
  - `class CellReview` — parsed `cell.json` + computed fields (Δ PMUW, headline SSD/DB/Total WAF, p99, throughput, regime tag)
  - `class CrossCheck` — name, ok, evidence
  - Cross-check implementations:
    - `check_workload_counts_consistent(cell)` ← `feedback_cross_check_workload_counts` (≥3 independent signals)
    - `check_csv_not_authoritative(cell)` ← `feedback_cass_stress_csv_truncated` (prefer stdout summary)
    - `check_paper_comparable_in_range(cell)` ← compare to PVLDB Table 1 ranges from `findings.md` §8
    - `check_variance_vs_replicates(cells)` — if N replicates, std/mean < threshold
  - `def review_run(run_dir, history_dir) -> RunReview`
  - `def render_results_section(run_review) -> str` — emits the `## Rn` markdown stanza using a Jinja template (or just an f-string)
- [ ] `src/cassandra_agent_harness/analysis/templates/results_rn.md.j2`  *(new)* — mirrors the existing R1–R4 sections in `results.md`
- [ ] `src/cassandra_agent_harness/cli.py`  *(modify)*
  - `cassandra-agent-harness review <run_dir> [--history <results_md>] [--append-to <results_md>]` subcommand
- [ ] `tests/test_analysis_review.py` *(new)* — feed real R3 + R4 cell.json fixtures, assert the rendered stanza matches the on-disk one to within whitespace
- [ ] **Carefully**: do not silently overwrite `results.md`. The CLI writes a draft to `<run_dir>/review_draft.md` by default; only with `--append-to` does it touch `results.md`, and even then it appends, never rewrites.

**Acceptance criteria**:
1. Fed the R3 run dir, produces a `## R3` stanza materially equivalent to the human-written one (numbers + cross-check table; allow stylistic difference in prose).
2. Cross-check failures surface clearly (e.g. if workload counts diverge by > 5% across signals, the stanza includes a `### Caveats` block flagging it).
3. The CLI never overwrites `results.md` without `--append-to`.

**Decision gate after C**: if the reviewer-rendered R3/R4 stanzas pass a
side-by-side reading with the originals, proceed to D.

### Phase D — Goal-driven loop (LLM at branch points only, constrained)

**Goal**: declare a structured `Goal` once; a deterministic evaluator
computes the gap between current state and the goal; a loop driver
iterates `evaluate → propose → approve → execute → review` until the
goal is met or a terminal stop fires. The LLM's role is *tactical*:
given a specific `Gap` and the regime enum, pick the regime that
closes the gap most efficiently. Same control flow as before
(propose–approve–execute by default; `--auto` flips it to
unsupervised), but the loop is now goal-bounded instead of
turn-bounded.

**Files to create / modify**:

- [ ] `src/cassandra_agent_harness/agent/goals.py` *(new, ~250 LoC)*
  - `class Goal` (abstract base): `outcomes`, `coverage`, `budgets`, `stop_conditions`, `methodology`
  - `class WafBaselineGoal(Goal)` — domain-specific dataclass; YAML serializable
  - `class Outcome` — `id`, `cell: CellSpec`, `replicates: int`, `max_inter_replicate_cv: float`, `check_fn: Callable`
  - `class Coverage` — `workloads`, `fill_fractions`, `ucs_t_values` (cross-product expanded via `coverage_cells()`)
  - `class Budgets` — `max_rounds`, `max_wall_hours`, `max_drive_writes_tb` (all required, all positive)
  - `def load_goal(path: Path) -> Goal` — YAML loader, dispatches on top-level `investigation:` field
  - `def validate_goal(goal: Goal) -> list[ValidationError]` — see "Goal validation" table below
  - `def estimate_total_wall_hours(goal) -> float`, `def estimate_total_writes_tb(goal) -> float` — used by validation rules 6 + 7
- [ ] `src/cassandra_agent_harness/agent/progress.py` *(new, ~200 LoC)*
  - `class Progress` — `goal_met: bool`, `terminal_stop: StopReason | None`, `gap: Gap | None`
  - `class Gap` — structured description of what's missing: `missing_cells: list[Cell]`, `missing_replicates: dict[Cell, int]`, `unmet_variance: list[Cell]`, `paper_comparable_status: Literal["unmet", "out_of_range", "ok"]`
  - `def evaluate(history: History, goal: Goal) -> Progress` — purely deterministic; no LLM
  - `def goal_met(history, goal) -> bool` — all outcomes satisfied + coverage filled + cross-checks pass + paper-comparable cell in range (if `require_paper_comparable_in_range`)
- [ ] `src/cassandra_agent_harness/agent/stop_conditions.py` *(new, ~150 LoC)*
  - `class StopReason` — `name`, `evidence: dict`, `recoverable: bool`
  - Each stop condition is `(history, goal) -> StopReason | None`:
    - `MatrixSaturated` — N consecutive cells in the same regime with std/mean below threshold (default 6 cells, 2%)
    - `VarianceUnreducible` — replicates of one cell exceed `max_replicates` while CV still > target (default 3 replicates, CV 10%)
    - `NoProgressDetected` — 3 consecutive rounds with no gap closure (gap size stable or growing)
    - `MaxRoundsReached` — hard cap from `goal.budgets.max_rounds`
    - `DriveLifetimeApproached` — `pmuw_added / drive_endurance` above threshold (default 5%)
- [ ] `src/cassandra_agent_harness/agent/round_controller.py` *(new, ~250 LoC)*
  - `def pursue(goal, *, auto=False, results_dir) -> Outcome` — the loop driver
  - `def propose_next_round(history, gap, goal) -> RoundProposal` — the only LLM call; prompt has `gap` as the central input; output is JSON-validated `RoundProposal` from a fixed regime enum; max 2 parse-retries then `ESCALATE`
  - `class Regime(StrEnum)` — `COLD_START_30M`, `LOW_FILL_4H`, `HIGH_FILL_4H`, `T_SWEEP`, `REPLICATE_FOR_VARIANCE`, `STOP`. Each maps to a constructor `Regime.cells_for(gap, goal) -> list[Cell]` so the LLM picks the regime and the *cells* are derived deterministically from the gap.
  - `class RoundProposal` — `regime`, `cells`, `expected_gap_closure: str` (what gap fields will shrink), `rationale: str`, `closes_stop_risk: list[str]`
  - `class Outcome` — `GOAL_MET`, `STOPPED(reason)`, `PAUSED_FOR_APPROVAL`, `MAX_ROUNDS`
- [ ] `src/cassandra_agent_harness/agent/round_plan_template.md.j2` *(new)* — renders proposal to a human-reviewable plan, mirrors the structure of `r5_plan_v2.md`
- [ ] `src/cassandra_agent_harness/cli.py` *(modify)*
  - `cassandra-agent-harness goal validate <goal.yaml>` — runs `validate_goal()`, exits 0 on `[]`, 1 otherwise; prints structured errors
  - `cassandra-agent-harness evaluate <goal.yaml> --history <dir>` — one-shot evaluator; prints `Progress` + `Gap` as JSON
  - `cassandra-agent-harness pursue <goal.yaml> [--auto] [--max-rounds N]` — the loop entrypoint; runs `validate_goal()` first and refuses to start on any error
- [ ] `tests/test_goals.py` *(new)* — one test per validation rule (the table below), one test per `Outcome` check function
- [ ] `tests/test_progress.py` *(new)* — feed canned histories of varying completeness, assert `Gap` structure matches hand-written expected fixtures
- [ ] `tests/test_stop_conditions.py` *(new)* — synthetic histories that trigger each stop; assert no false positives on the real R1–R5 history
- [ ] `tests/test_round_controller.py` *(new)* — fake LLM client; assert regime selection matches expected on canned `(history, gap)` pairs
- [ ] `tests/fixtures/goals/waf_baseline.yaml` *(new)* — the actual goal for this investigation (track the shape in `goals_waf_baseline_sketch.yaml` in this task folder first)
- [ ] `docs/goal_driven_loop.md` *(new)* — explains the goal/evaluator/loop-driver shape, the validation rules, the regime enum, and the propose–approve–execute flow

#### Goal validation rules (enforced by `validate_goal()`; CLI refuses to `pursue` if any fire)

| # | rule | reason | enforcement |
|---|---|---|---|
| 1 | `budgets.max_rounds`, `max_wall_hours`, `max_drive_writes_tb` all set and positive | Unbounded loops are forbidden by design — sloppy goals → infinite spin | error |
| 2 | Every `Outcome` has a `check_fn` resolvable in the registry | Outcomes must be falsifiable; "publishable" without a check is not a goal | error |
| 3 | `paper_comparable.cell` is in either `outcomes` or `coverage.cells()` | Internal consistency — can't claim paper comparability for a cell you never run | error |
| 4 | `methodology.require_cross_checks_pass == True` | Cross-checks are non-negotiable for publishable results (see `feedback_cross_check_workload_counts`) | error |
| 5 | Required stop conditions present: `MatrixSaturated`, `VarianceUnreducible`, `NoProgressDetected`, `MaxRoundsReached`, `DriveLifetimeApproached` | Terminal-condition coverage gap → loop can spin uselessly | error |
| 6 | `estimate_total_wall_hours(goal) ≤ budgets.max_wall_hours × 1.5` | Goal unreachable within budget — must be tightened or budget raised | error |
| 7 | `estimate_total_writes_tb(goal) ≤ budgets.max_drive_writes_tb` | Same as above, for drive endurance | error |
| 8 | Every `outcomes[*].max_inter_replicate_cv ≤ 0.20` | A CV target looser than 20% is a non-goal | error |
| 9 | `coverage` is non-empty OR `outcomes` is non-empty | Vacuous goal | error |
| 10 | No duplicate `Outcome.id`s | Goal malformed | error |
| 11 | `methodology.require_paper_comparable_in_range == False` (advisory) | LSM may land outside paper range for principled reasons; flag for discussion rather than fail goal | warn |

**Acceptance criteria**:
1. `validate_goal()` returns `[]` for `goals_waf_baseline_sketch.yaml`; returns the corresponding `ValidationError` when each rule is violated (one test per rule, 10 error rules + 1 warn).
2. Given R1–R5 history + the WAF baseline goal, `evaluate()` reports a `Gap` whose `missing_replicates` and `missing_cells` match a hand-written expected-gap fixture.
3. Given a canned `(history, gap)` where the gap is "2 replicates missing of cold-start T4 cell", the LLM proposer picks `Regime.REPLICATE_FOR_VARIANCE` with the right cell parameters.
4. `pursue(goal, auto=False)` writes `rounds/Rn_plan.md` and terminates with `Outcome.PAUSED_FOR_APPROVAL`, awaiting the approval marker file.
5. `pursue(goal, auto=True)` on a synthetic goal where matrix saturates after 4 cells terminates with `Outcome.STOPPED(MatrixSaturated)` within 4 rounds — does not loop forever.
6. `cassandra-agent-harness pursue <bad-goal.yaml>` exits 1 with structured validation errors and does not launch any cells.
7. The proposer's prompt has `gap` as the primary input and the regime enum as the constrained output space; output is Pydantic-validated with max 2 parse-retries before `ESCALATE`.

**Decision gate after D**: run one full goal-driven round end-to-end in
supervised mode against the live rig. If the human approves the
proposal without rewriting it and the round completes cleanly, the loop
is autonomous enough to consider `--auto` mode for the next 1–2 rounds
on the same goal.

## Implementation order

1. Phase A (watcher) — land before R5 finishes if possible; uses it on R5 wrap-up.
2. Phase B (preflight) — land after A; deploys against R6 (whatever that is).
3. Phase C (reviewer) — uses R3+R4+R5 as test fixtures.
4. Phase D (round controller) — uses A+B+C as deterministic tools.

## Cross-references

- Existing harness: `/Users/samlightfoot/repos/cassandra-agent-harness/` — code lives here, lib `cassandra_agent_harness`.
- Investigation app: `/Users/samlightfoot/repos/waf-baseline-poc/` — wires the new CLIs into the pilot launcher; the actual `goals/waf_baseline.yaml` lives here, not in the library.
- Goal sketch (this task folder): `goals_waf_baseline_sketch.yaml` — the proposed goal shape for the current WAF investigation, used to sanity-check the dataclass design in phase D before committing to a real config file.
- Memory the new code codifies:
  - `feedback_monitor_silence_is_not_success` (→ phase A)
  - `feedback_rsync_before_rig_launch` (→ phase B)
  - `feedback_cassandra_jar_rebuild` (→ phase B)
  - `feedback_mkfs_ext4_trim_undoes_precondition` (→ phase B)
  - `feedback_cassandra_drop_keyspace_snapshots` (→ phase B)
  - `feedback_cassandra_easy_stress_keyspace` (→ phase B)
  - `feedback_cass_stress_csv_truncated` (→ phase C)
  - `feedback_cross_check_workload_counts` (→ phase C)
  - `feedback_cassandra_db_waf_compression` (→ phase C, disclosure block in stanza template)
- Runbook §7 (pre-launch checklist) is the spec source for phase A's rules and phase B's profile-`low_fill`/`high_fill` checks.
- Existing failure taxonomy `agent/failures.py` — phase A's `ABORT` reasons should map onto `FailureClass` where possible.

## Review

(Filled at end of phase D.)
