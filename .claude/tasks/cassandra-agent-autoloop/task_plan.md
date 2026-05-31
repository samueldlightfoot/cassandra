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

- [x] `src/cassandra_agent_harness/agent/watcher.py`  *(new, ~330 LoC including docstrings + serialization helpers)*
  - `Severity` StrEnum (`HEARTBEAT`, `INFO`, `WARN`, `FATAL`, `SUCCESS`)
  - `WatchRule` — name, severity, compiled regex, description
  - `WatchEvent` — timestamp, rule_name, severity, snippet (truncated to 200 chars)
  - `WatchStatus` — state (`watching|escalate|abort|ok`), reason, evidence tuple
  - `WatchState` — mutable, persisted: `log_offset`, `last_event_ts`, `last_heartbeat_ts`, `events_seen`, `terminal`
  - `scan_lines(lines, *, rules, now)` — pure: line → WatchEvent stream
  - `evaluate(state, events, *, now, silence_threshold_s)` — pure: state-machine step
  - `watch_once(run_dir, ...)` — IO wrapper: tail launch.log from persisted offset, update `.watch_state.json` atomically, return current status
  - `DEFAULT_RULES`: 15 rules covering every row of the failure-mode catalog in findings.md §5 (FATAL: unrecognized args, usage dump, command not found, traceback, error: prefix, OOM, JVM fatal; WARN: JMX refused, cells_succeeded=0; HEARTBEAT: ops/s, compaction, flush, prefill/warmup; SUCCESS: measurement window closed, cells_succeeded≥1)
  - Heartbeat-silence rule: silence > `silence_threshold_s` (default 600s) since last heartbeat → ESCALATE. Computed fresh each call, never persisted, so it auto-clears on heartbeat resumption.
- [x] `src/cassandra_agent_harness/agent/__init__.py`  *(modify)* — re-export watcher public API
- [x] `src/cassandra_agent_harness/cli.py`  *(modify)*
  - Added `cah watch <run_dir> [--launch-log] [--success-marker] [--silence-threshold-s]` subcommand
  - One JSON object on stdout per invocation; exit code reflects state
  - Exit codes: 0=ok, 1=abort, 2=escalate, 3=watching (documented as module constants)
- [x] `tests/agent/test_watcher.py`  *(new)* — 46 tests across 3 layers (scan_lines pure, evaluate pure, watch_once IO + idempotence + corrupt-state-file recovery)
- [x] `tests/test_cli.py`  *(modify)* — 4 new tests covering exit codes 0/1/3 and missing run_dir handling
- [x] `tests/fixtures/runs/{T4-LF4h,T16-LF4h}/launch.log` — real R5 launch logs (T4 + T16, 4h cells, both `All 1 cells succeeded`) rsync'd from rig 2026-05-29 and committed as fixtures. Replay-tested via 6 new parametrized tests in `test_watcher.py` (no false positives; terminal `ok` driven by `all_cells_succeeded` rule; default silence threshold survives 4h cell).

**Integration with `/loop`** (operates on a local path; rsync from rig is a thin wrapper):

```bash
# in a /loop body that polls every 5 min:
rsync -q root@RIG:/data/results/T16-LF4h-.../launch.log $LOCAL_RUN_DIR/
cah watch $LOCAL_RUN_DIR
# exit code 0=ok 1=abort 2=escalate 3=watching
```

Status persisted at `$LOCAL_RUN_DIR/.watch_state.json` (atomic write via
`tmp` + `rename`); the JSON payload on stdout carries the same status
for inline parsing by the loop body.

**Acceptance criteria — actual state**:
1. ✅ Clean-startup fixture lines produce no FATAL/WARN events (false-positive test).
2. ✅ `unrecognized arguments: --foo` → `WatchStatus(state="abort", reason="fatal rule fired: cli_unrecognized_arguments")` in one call.
3. ✅ Silence above threshold yields `escalate`; `terminal` stays `None` so the state auto-clears on heartbeat resumption.
4. ✅ CLI exit codes verified end-to-end via smoke tests: 0 on `.complete`, 1 on `abort`, 3 on `watching`. Exit 2 on `escalate` covered by unit tests.
5. ✅ 100% ruff clean on new files; type-annotated throughout.
6. ✅ Library suite: 247 passed (was 232; watcher tests grew to 57 + 4 new CLI tests).
7. ✅ R5 replay smoke (2026-05-29): both real T4 and T16 launch.logs replay-test green; CLI exits 0 with `all_cells_succeeded` evidence on both.

**Decision gate after A**: if watcher catches one real-world incident in
R5 wrap-up or a follow-on run, proceed to B. If it produces false positives,
fix rules before B.

### Phase B — Preflight gatekeeper

**Goal**: every `feedback_*` operational memory becomes a Python check the
harness runs before any pilot launch, refusing on failure with a structured
reason.

**Files to create / modify**:

- [x] `src/cassandra_agent_harness/prereqs/preflight.py`  *(new, ~370 LoC)*
  - `Severity` StrEnum (`BLOCK`, `WARN`)
  - `PreflightCheck(name, severity, derived_from_memory, check: Check)` — wraps the existing `Check = Callable[[], CheckResult]` callable with metadata
  - `PreflightResult(preflight, result)` — pairs the result back with its `PreflightCheck` for context (provenance follows the data)
  - `PreflightReport` — `.results`, `.blocking_failures`, `.warnings`, `.passed`
  - `PreflightConfig` dataclass + `.from_yaml(path)` loader; raises `KeyError` on missing required fields and `ValueError` on non-mapping top-level
  - `run_preflight(checks)` — defensive: catches `Exception` from check callables and turns it into a failing CheckResult; never raises
- [x] Individual check implementations (one function per memory):
  - [x] `check_jar_contains_class(jar_path, fqcn)` ← `feedback_cassandra_jar_rebuild` — uses `zipfile` (jar is a zip); rejects corrupt jars
  - [x] `check_drive_regime(serial, expected_percent_free_range)` ← `feedback_mkfs_ext4_trim_undoes_precondition` — defensively searches the OCP snapshot dict for `Percent free blocks` / `percent_free_blocks` / etc.; reports unparseable when nvme-cli raw-binary fallback is the only path
  - [x] `check_auto_snapshot_disabled(cassandra_yaml_path)` ← `feedback_cassandra_drop_keyspace_snapshots` — YAML parse + assert value is exactly `False` (not just falsy)
  - [x] `check_cli_recognizes_flags(cli_argv, required_flags)` ← `feedback_monitor_silence_is_not_success` ("+10s" angle) — invokes `<cli> --help` with a 30s timeout and greps for each required flag
  - [x] Wraps existing `check_swap_off`, `check_ocp_available(serial)`, `check_drive_isolation(serial, mount)` with the PreflightCheck metadata
  - [ ] `check_rsync_synced(local_paths, rig)` ← `feedback_rsync_before_rig_launch` — **DEFERRED**: requires a workstation-side entry point to compare local vs rig mtimes. Pragmatic deferral until preflight gains a workstation mode. Documented as advisory.
  - [ ] `check_keyspace_name(expected="cassandra_easy_stress")` ← `feedback_cassandra_easy_stress_keyspace` — **DEFERRED**: this is a workload-spec concern, not a rig-state concern. The keyspace name is hard-coded in cass-stress invocations; preflight has nothing to verify against rig state. The `cli_recognizes_flags` check covers the analogous "the CLI knows what we mean" risk.
- [x] `src/cassandra_agent_harness/cli.py`  *(modify)*
  - `cah preflight --profile {cold_start,low_fill,high_fill,paper_comparable} --config <yaml> [--allow-warnings]` subcommand
  - Exit code 0 if all BLOCK pass and no WARN fails (or `--allow-warnings`); 1 on any BLOCK failure or config-load failure; 2 on warnings-only (unless `--allow-warnings`)
  - Per-check structured log output (level reflects severity); summary log carries `profile`, `passed`, `block_failures`, `warnings`, `total`
- [x] `tests/prereqs/test_preflight.py` *(new, ~390 LoC, 31 tests)* — one or more tests per check (pass/fail/edge), profile factory smoke tests, `PreflightConfig.from_yaml` round-trip + rejection, defensive runner exception-handling test, BLOCK-vs-WARN report contract
- [x] `tests/test_cli.py` *(modify, +3 tests)* — exit 1 on block failure, exit 1 on missing config, exit 1 on invalid config
- [x] **Wired into `waf-baseline-poc`** *(2026-05-31, waf-baseline-poc commit `1389829`)* — pilot/matrix CLI now replaces the 3-check Gate B with `run_preflight(profile)`. Profile auto-inferred from `--fill-fraction` (≤0.10 → low_fill, ≥0.50 → high_fill, else cold_start), explicit `--preflight-profile` overrides. PreflightConfig derived deterministically from existing CLI args (no new YAML file needed). Every refused launch now reports each check's memory provenance (`from feedback_*`). `--skip-prereqs` preserved as the dry-run escape hatch. +12 tests in waf-baseline-poc's test_cli.py; suite 34 → 46.
- [ ] Update `runbook.md` §7 to say "this is now executable: `cah preflight --profile <name>`" — separate small commit; doesn't block phase B closure.

**Acceptance criteria — actual state**:
1. ✅ Every `feedback_*` memory in scope either has a corresponding check function (5 of 7) or is documented as deferred with reason (2 of 7: `feedback_rsync_before_rig_launch`, `feedback_cassandra_easy_stress_keyspace`).
2. ✅ `cah preflight --profile cold_start --config <good.yaml>` returns exit 0 when checks pass (verified via smoke on tmp config); returns exit 1 when checks fail with structured `how_to_fix` per failure. Drive-regime check parametrized for `low_fill_profile` (80-100% free) and `high_fill_profile` (0-15% free); covered by mocked-OCP tests.
3. ✅ The library exposes `run_preflight` + the profile factories; **pilot-launcher wiring landed 2026-05-31** in waf-baseline-poc commit `1389829`. The pilot CLI now refuses launch on any BLOCK failure with structured per-check `how_to_fix` and memory provenance.
4. ✅ Library suite: 281 passed (was 247; +34: 31 preflight + 3 CLI).
5. ✅ End-to-end smoke (`cah preflight --profile cold_start` on a tmp config): 3 Linux-only checks fail loudly with clear messages, 3 portable checks pass; exit code 1 propagates correctly.

**Decision gate after B**: B has been pre-validated by exactly the
scenario the plan called out — the YAML/jar/CLI-flag checks pass on a
real-shape config, while every Linux-only rig check refuses with a
specific `how_to_fix`. Proceed to C.

### Phase C — Result reviewer (deterministic-only)

**Goal**: given a completed `<run_dir>` (post-`.complete`), parse the
artifacts, run cross-checks, compute variance vs prior rounds, emit a draft
`## Rn` markdown stanza for `results.md` and updated headline numbers for
`summary.md`. **No LLM prose generation in this phase** — prose is
templated with computed numbers slotted in.

**Files to create / modify**:

- [x] `src/cassandra_agent_harness/analysis/__init__.py` *(new)* — re-exports the public API.
- [x] `src/cassandra_agent_harness/analysis/review.py`  *(new, ~480 LoC)*
  - Data model: `CellWaf`, `WorkloadSummary`, `CellReview`, `CrossCheck`, `RunReview` (all frozen dataclasses except RunReview)
  - Parsers: `parse_cell(cell_json_path)`, `parse_run(run_dir)` — handles the real `cell.json` shape (`measurement.waf`, `measurement_workload_summary`, `cell` metadata block)
  - Cross-check implementations (4 of the 4 planned):
    - `check_workload_counts_consistent(cell, tolerance=0.01)` ← `feedback_cross_check_workload_counts` (BLOCK; writes+reads vs total)
    - `check_csv_not_authoritative(cell)` ← `feedback_cass_stress_csv_truncated` (WARN; regression guard that writes_count came from stdout path)
    - `check_paper_comparable_range(cell, ssd_waf_range, db_waf_range)` ← compare to caller-supplied PVLDB Table 1 ranges (WARN; out-of-range is informative not blocking)
    - `check_inter_replicate_variance(cells, cv_threshold=0.10)` — std/mean across replicates of same `(workload, fill, ucs_t)` group (BLOCK at n≥2; informational at n=1)
  - `review_run(run_dir)` / `review_round(run_dirs, round_id=...)` — bundles parsing + cross-checks into a `RunReview`
  - `render_round_section(review, title=...)` — emits the `## Rn` markdown stanza using f-strings (Jinja considered overkill for one template). **Findings prose and investigation outlook are rendered as `<!-- LLM, phase D -->` placeholders** — the deterministic reviewer must not invent prose interpretations.
- [x] `src/cassandra_agent_harness/cli.py`  *(modify)*
  - `cah review <run_dir> [<run_dir>...] [--round-id Rn] [--title T] [--paper-ssd-waf-range lo,hi] [--paper-db-waf-range lo,hi] [--draft-file PATH] [--append-to PATH]` subcommand
  - **Append-only safety**: default writes `<first-run_dir>/review_draft.md`; `--append-to` appends and never rewrites (the file's prior content is preserved verbatim).
  - Exit code 0 on all-cross-checks-pass; 1 on any BLOCK failure or missing input.
- [x] `tests/analysis/test_review.py` *(new)* — 20 tests:
  - Real-R5 parsing of T4 + T16 cell.json with expected headline numbers
  - Cross-checks pass against real R5 data (writes+reads exactly equals total on both cells; SSD WAF and DB WAF in paper-comparable range)
  - Synthetic edge cases: divergent counts, zero total, zero writes, out-of-range paper comparable, single vs multi replicate variance
  - Renderer: verifies `## R5` header, both cell IDs, real headline numbers, cross-check table, LLM stub markers
- [x] `tests/test_cli.py` *(modify, +3 tests)* — review subcommand: writes draft for real R5 fixture; append-only safety against existing results.md; exit 1 on missing run_dir.

**Acceptance criteria — actual state**:
1. ✅ Fed the R5 T4+T16 run dirs, produces a `## R5` stanza whose numbers table matches the human-written R5 section to 4 decimal places (verified end-to-end). Cross-check rows + caveats are auto-generated; findings and outlook are deliberately `<!-- LLM, phase D -->` stubs.
2. ✅ Cross-check failures surface clearly in the rendered Caveats section. The test `test_render_round_section_includes_failed_cross_checks_in_caveats` validates this with a deliberately-failing paper-range check.
3. ✅ CLI never overwrites `results.md` without `--append-to`. The `test_review_command_appends_only_does_not_rewrite` test verifies the prior content of an existing `results.md` is preserved byte-for-byte and the new section is concatenated after it.
4. ✅ Library suite: 304 passed (was 281; +23: 20 review + 3 CLI).
5. ✅ End-to-end smoke: `cah review T4-LF4h T16-LF4h --round-id R5 --paper-ssd-waf-range 0.9,1.5 --paper-db-waf-range 1.0,4.0` produces a `## R5` stanza with 6 cross-checks all green, 0 blocking failures, single-replicate caveat surfaced correctly.

**Decision gate after C**: the reviewer-rendered R5 stanza matches the
human-written one numerically. The deliberate stubs for findings +
outlook are the right semantics (phase C reviewer must not invent
prose interpretations). Proceed to D.

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

- [x] `src/cassandra_agent_harness/agent/goals.py` *(new, ~430 LoC)*
  - `class Goal` (abstract base): `outcomes`, `coverage`, `budgets`, `stop_conditions`, `methodology`
  - `class WafBaselineGoal(Goal)` — domain-specific dataclass; YAML serializable
  - `class Outcome` — `id`, `cell: CellSpec`, `replicates: int`, `max_inter_replicate_cv: float`, `check_fn: Callable`
  - `class Coverage` — `workloads`, `fill_fractions`, `ucs_t_values` (cross-product expanded via `coverage_cells()`)
  - `class Budgets` — `max_rounds`, `max_wall_hours`, `max_drive_writes_tb` (all required, all positive)
  - `def load_goal(path: Path) -> Goal` — YAML loader, dispatches on top-level `investigation:` field
  - `def validate_goal(goal: Goal) -> list[ValidationError]` — see "Goal validation" table below
  - `def estimate_total_wall_hours(goal) -> float`, `def estimate_total_writes_tb(goal) -> float` — used by validation rules 6 + 7
- [x] `src/cassandra_agent_harness/agent/progress.py` *(new, ~215 LoC)*
  - `class Progress` — `goal_met: bool`, `terminal_stop: StopReason | None`, `gap: Gap | None`
  - `class Gap` — structured description of what's missing: `missing_cells: list[Cell]`, `missing_replicates: dict[Cell, int]`, `unmet_variance: list[Cell]`, `paper_comparable_status: Literal["unmet", "out_of_range", "ok"]`
  - `def evaluate(history: History, goal: Goal) -> Progress` — purely deterministic; no LLM
  - `def goal_met(history, goal) -> bool` — all outcomes satisfied + coverage filled + cross-checks pass + paper-comparable cell in range (if `require_paper_comparable_in_range`)
- [x] `src/cassandra_agent_harness/agent/stop_conditions.py` *(new, ~210 LoC)*
  - `class StopReason` — `name`, `evidence: dict`, `recoverable: bool`
  - Each stop condition is `(history, goal) -> StopReason | None`:
    - `MatrixSaturated` — N consecutive cells in the same regime with std/mean below threshold (default 6 cells, 2%)
    - `VarianceUnreducible` — replicates of one cell exceed `max_replicates` while CV still > target (default 3 replicates, CV 10%)
    - `NoProgressDetected` — 3 consecutive rounds with no gap closure (gap size stable or growing)
    - `MaxRoundsReached` — hard cap from `goal.budgets.max_rounds`
    - `DriveLifetimeApproached` — `pmuw_added / drive_endurance` above threshold (default 5%)
- [x] `src/cassandra_agent_harness/agent/round_controller.py` *(new, ~440 LoC)*
  - `Regime` StrEnum (`COLD_START_30M`, `LOW_FILL_4H`, `HIGH_FILL_4H`, `T_SWEEP`, `REPLICATE_FOR_VARIANCE`, `STOP`); `cells_for_regime(regime, gap, goal) -> list[CellSpec]` is the deterministic dispatch — LLM picks the regime, code picks the cells. No LLM choice over continuous parameters.
  - `LlmClient` `Protocol` with one method `choose_regime(request) -> LlmChoice`. Two bundled implementations: `FakeLlmClient` for tests (returns a configured regime); `HeuristicLlmClient` for the no-API-key default (deterministic precedence — replicates > paper-comparable > high-fill coverage > low-fill coverage > STOP). Real Anthropic SDK wiring is a thin follow-up; the protocol is in place.
  - `propose_next_round(history, gap, goal, *, llm_client)` — invokes the client, validates the regime against `available_regimes`, materializes cells via `cells_for_regime`. If the LLM's pick yields no cells (other than STOP) it falls back to the heuristic — bounds the worst-case "bad LLM pick" cost to one iteration.
  - `pursue(goal, *, history, results_dir, llm_client, auto=False, executor=None)` — the loop driver. Persists `<results_dir>/.pursue_state.json` (rounds_completed, rounds_without_progress, last_gap_size, started_at) for resumability. Without an executor, always returns `PAUSED_FOR_APPROVAL` after writing the plan. With executor + auto=True, iterates evaluate → propose → execute → re-evaluate until goal-met or terminal-stop. Hard cap on max_iterations guards against pathological LLM loops.
  - `LoopOutcome` carries `OutcomeKind` (GOAL_MET, STOPPED, PAUSED_FOR_APPROVAL, MAX_ROUNDS, PROPOSAL_INVALID) + the proposal + plan_path.
  - `render_round_plan(proposal, progress, goal, state) -> str` — short, human-reviewable markdown (regime, rationale, current gap, proposed cells, stop risks closed, approval instructions). Same f-string approach as phase C.
- (template merged into `round_controller.render_round_plan`; a separate `.j2` file would have added a templates directory for one function.)
- [x] `src/cassandra_agent_harness/cli.py` *(modify)*
  - `cah goal-validate <goal.yaml>` — runs `validate_goal()`, exits 0 on no blocking errors, 1 otherwise; structured log per rule (advisory rule 11 emits WARN, all others ERROR)
  - `cah evaluate <goal.yaml> --history <run_dir>... [--rounds-completed N] [--rounds-without-progress N]` — runs the goal validator first (refuses on blocking errors), then `evaluate()`; prints `Progress` + `Gap` as pretty-printed JSON to stdout. Exit codes: 0 (goal met), 1 (error / validation block), 2 (terminal stop fired), 3 (goal not met, gap present)
  - `cah pursue <goal.yaml> --history <run_dir>... --results-dir <path> [--llm heuristic]` — runs the goal validator first (refuses on blocking), then `pursue()`. Defaults to HeuristicLlmClient so it works without an API key. Always pauses after one proposal (executor wiring deferred — actually shelling out to the pilot CLI from inside pursue() is an executor-process-management concern; the CLI's job is to write the plan and let the operator launch the cells). Exit codes: 0 (goal met / paused for approval), 1 (error / validation block), 2 (terminal stop fired).
- [x] `tests/agent/test_goals.py` *(new, 20 tests)* — one test per validation rule (1–11) + sketch-YAML smoke (validates clean) + loader edge cases (non-mapping top-level, malformed stop entries)
- [x] `tests/agent/test_progress.py` *(new, 11 tests)* — real R5 + sketch goal → expected gap; synthetic full-goal-met; paper-comparable out-of-range path; unmet-variance path; stop conditions wired through evaluate()
- [x] `tests/agent/test_stop_conditions.py` *(new, 12 tests)* — each of 5 stops with histories that trigger and don't; real R5 as the "well within budget" control case for all 5
- [x] `tests/agent/test_round_controller.py` *(new, 17 tests)* — `cells_for_regime` per regime (low/high fill predicates, T-sweep grouping, REPLICATE_FOR_VARIANCE uses outcome counts); HeuristicLlmClient precedence on R5 + sketch gap; `propose_next_round` (fake-client passthrough, bad-pick → heuristic fallback, out-of-available raises); `pursue` (no-executor pauses, goal-met short-circuit, max_rounds_reached via injected state, executor + auto=True iterates to goal-met, STOP proposal exits cleanly); `render_round_plan` contains regime + cells + gap.
- [x] `tests/fixtures/goals/waf_baseline.yaml` *(new)* — the sketch lifted directly from `cassandra-agent-autoloop/goals_waf_baseline_sketch.yaml`; validation passes clean (rule 0 errors)
- [ ] `docs/goal_driven_loop.md` — **deferred**; the in-code docstrings + this plan are sufficient until phase D-LLM lands

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

**Acceptance criteria — D-1 + D-LLM combined**:
1. ✅ `validate_goal()` returns `[]` for `goals_waf_baseline_sketch.yaml`; every rule 1–11 has a dedicated test that violates the rule and asserts the right `ValidationError.rule` fires.
2. ✅ Given the real R5 T4+T16 history + the WAF baseline goal, `evaluate()` reports exactly the hand-written expected gap: `missing_replicates={"headline_paper_comparable": 3, "headline_cold_start": 2}`, `missing_cells=[…0.04+T8, 0.80+T4, 0.80+T8, 0.80+T16]`, `paper_comparable_status="unmet"`.
3. ✅ HeuristicLlmClient picks `REPLICATE_FOR_VARIANCE` on the real R5 + sketch gap (because missing_replicates non-empty has top precedence). FakeLlmClient round-trips arbitrary regimes; bad picks fall back to the heuristic. (`test_round_controller.py` covers all three.)
4. ✅ `pursue(goal, auto=False)` writes `<results_dir>/rounds/R1_plan.md` and terminates with `OutcomeKind.PAUSED_FOR_APPROVAL`. End-to-end smoke against R5 produces a 5-cell round (3 paper-comparable + 2 cold-start replicates) that closes both the replicate gap AND the paper-comparable-unmet status in one shot.
5. ✅ `pursue(goal, auto=True)` with an executor and a synthetic 2-replicate trimmed goal iterates and returns `GOAL_MET` once the executor's replicates satisfy the outcome. Max-rounds-reached path covered by injecting a state file that pushes `rounds_completed` to the budget cap.
6. ✅ `cah evaluate <bad-goal.yaml>` exits 1 with structured validation errors before touching history (`test_evaluate_refuses_invalid_goal` covers this through the evaluator's pre-flight validation).
7. ✅ Proposer takes structured `ProposalRequest(goal_name, gap, available_regimes, rounds_completed)` and returns a typed `LlmChoice(regime, rationale)`. Output validated against `available_regimes` (raises on out-of-set). Pydantic schema not needed yet because the bundled clients return typed dataclasses directly; lands when the real Anthropic SDK client is wired (~30 LoC follow-up).

**Status: D landed.** End-to-end smoke chains all 5 phases:

```
cah pursue ────► proposes regime + cells from gap
   │
   ▼
waf-baseline pilot ────► preflight refuses on rig-state mismatch
   │
   ▼
WafBaselineRunner ────► reset → prefill → warmup → measure → cell.json
   │
   ▼
cah watch ────► tails launch.log; exit code = terminal state
   │
   ▼
cah review ────► cell.json → results.md stanza + cross-checks
   │
   ▼
cah evaluate ────► history → Gap → loop back to pursue
```

**Decision gate after D**: end-to-end demo against R5 produces the
right next-round proposal (REPLICATE_FOR_VARIANCE with the
paper-comparable + cold-start cells). One remaining piece is wiring
the real Anthropic SDK as an alternative `LlmClient` — a ~30 LoC
follow-up against the existing `LlmClient` Protocol; the heuristic
client runs the whole loop without it. The architecture is shipped.

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
