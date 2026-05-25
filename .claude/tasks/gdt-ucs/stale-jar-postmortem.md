# Post-mortem: stale-JAR invalidates Runs #001, #002, #003, and Phase 2 attempt 1

## TL;DR

On 2026-05-25, while debugging Phase 2 attempt 1's null result, we discovered that the Cassandra JAR on the rig (`build/apache-cassandra-6.0-alpha2-SNAPSHOT.jar`) was dated **May 17**. The earliest GDT commit (`d0e93963ad`, "Add DeathtimeClassifier") was dated **May 24**. So the JAR predated every line of GDT code in the repo.

**Cassandra loads its JAR at startup; it does not load loose `.class` files from `build/classes/main/`.** `ant -q build` recompiles `.class` files but does not refresh the JAR. None of our pre-2026-05-25 rebuilds re-jarred. Result: **every Run #001/#002/#003 "GDT" condition was running base UCS, byte-identical to the "baseline" condition modulo unused JVM args.**

All four prior runs measured baseline vs baseline.

## What this invalidates

| document | what it claimed | actual situation |
|---|---|---|
| `results-run-001.md` | "gdt p99 −9.73% vs baseline" | baseline vs baseline; both runs were base UCS. The 9.73% was inter-run noise. |
| `results-run-002.md` | "gdt p99 −2.43% vs baseline — Run #001 was noise" | Correctly identified Run #001 as noise, but for the wrong reason: assumed both runs had GDT enabled and differed by random variance, when actually neither run had GDT enabled at all. Conclusion ("Run #001 was noise") is still correct; the explanation needs revising. |
| Phase 2 attempt 1 results (`run_uuid d796f708...`) | "gdt vs baseline = −0.02% — TTL-aware classifier no help" | baseline vs baseline; the classifier never ran. No conclusion can be drawn about the TTL-aware classifier from this run. |
| `audit-why-no-signal.md` Finding #1 ("UCS already does deathtime-coherent selection via `maxTimestampDescending`") | True (read from source) | Still valid as a source-code-level observation. **But** the empirical claim that this *makes our classifier redundant* is unproven, because the comparison we made was baseline vs baseline. |

## What is still valid

- The harness/orchestrator/lifecycle code paths (lots of bugs found and fixed; those fixes are real).
- The workload calibration (50K rate, 2KB rows, 5-min phaseSeconds) is correct for the rig — calibration analysis was based on flush rate observations, not GDT-vs-baseline.
- The operational lessons (`operational-lessons.md`): noise floor on single 15-min runs is ~3-15%, cleanup procedure, etc. These properties of the rig, not the mechanism.
- The Phase 1 audit's source-code reading of UCS's `maxTimestampDescending` selection. The argument is unchanged; the empirical conclusion is unverified.

## Root cause

`build.xml`'s `_main-jar` target has uptodate checks that don't always trigger after a `.java` change, and the literal target most operators run (`ant build`) doesn't depend on `_main-jar` at all in the way one would expect. Plus: at some point this fork's `base.version` was bumped from `6.0` → `7.0-SNAPSHOT`, which means a fresh `ant jar` writes `apache-cassandra-7.0-SNAPSHOT.jar` while the old `apache-cassandra-6.0-alpha2-SNAPSHOT.jar` lingers. `cassandra.in.sh`'s glob `apache-cassandra*.jar` either picks the wrong one or errors out depending on count.

## Controls now in place to prevent recurrence

### Source-side (cassandra fork, branch `fdp-poc`)

- Commit `ec3770fd88` adds `GDT_POC_BUILD_REV` to `Controller.java` — a manually-bumped int logged at INFO-level on Controller class-init.
- **Discipline**: on every Java change in this fork, bump `GDT_POC_BUILD_REV`, then `ant -q jar`. Confirm the new rev appears in `system.log` of any subsequent run.

### Harness-side (`gdt-poc-harness`, `main`)

- Commit `c2d8869` adds three pre-flight checks that gate `gdt-poc run`:
  1. `cassandra_jar_unique` — exactly one `apache-cassandra-*.jar` in `build/`. Fails if zero or multiple (the actual bug we just hit: 6.0-alpha2 and 7.0-SNAPSHOT coexisting).
  2. `cassandra_jar_has_gdt_classifier` — inspects the JAR manifest for `DeathtimeClassifier`. Fails on a stale JAR even when the loose `.class` file exists.
  3. `cassandra_jar_freshness` — JAR mtime must be ≥ newest `src/java/**/*.java` mtime. Fails if any source has been edited since the JAR was built.

All three have unit tests. The harness refuses to launch a 50-minute bench if any fail. This is "fail closed" — operator discipline alone was insufficient and we shouldn't rely on it.

### Memory notes

- `~/.claude/projects/.../memory/feedback_cassandra_jar_rebuild.md` — captures the gotcha for future Claude Code sessions in this repo.

## Lesson for general bench discipline

Before claiming any feature-vs-baseline comparison, **produce direct evidence the feature actually ran**. Acceptable forms:
- A log line emitted only when the feature initializes.
- A metric that is zero when the feature is off and non-zero when it's on.
- A counter visible in `nodetool` or similar.

"The JVM args were correct" is not evidence. "The build said BUILD SUCCESSFUL" is not evidence. **No grep match for a feature-init line → the feature didn't run, regardless of what JVM args the run was invoked with.**
