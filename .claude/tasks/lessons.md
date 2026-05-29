# Lessons (Cassandra fork)

Append-only file of patterns learned from corrections. Each lesson should give a future session enough to *not* repeat the mistake — rule, why, when it applies.

## 2026-05-28 — Document the rig facts in the task folder, not just in plans

**Rule.** Each investigation folder under `.claude/tasks/<name>/` MUST have a `runbook.md` with the canonical rig facts: host/IP, drive serial, install paths, CLI invocation (module name + full required-arg list + example launch command), and pre-launch checklist. Whenever you have to grep the rig to answer "what's the X" during a plan, write the answer into the runbook then continue.

**Why.** During R5 pre-flight I rediscovered:
- The module is `waf_baseline_poc` (not `waf_baseline`)
- Duration is controlled by **two** flags (`--measurement-window-s` + `--measurement-duration`), not one `--duration`
- Four args are required (cassandra-home, data-mount, drive-serial, results-dir) — the plan omitted all of them
- Cassandra runs foreground with no systemd unit (`systemctl is-active` was misleading)
- Python 3.11 lives at `/usr/bin/python3.11` (default `python3` is 3.10 and breaks the pip install)
- The harness does not write a top-level `launch.log` — we have to capture it ourselves
- Drive serial `S64FNE0R401522`, partition `/dev/nvme1n1p3`

All of this had to be re-derived after R1-R4 had already used the same rig. The user pushed back: *"these questions should not be coming up after so many runs."* Correct. The answer lives in `runbook.md` from now on.

**How to apply.** Before writing any plan: (1) open `runbook.md` first and lift the facts. (2) Before *finalizing* a plan that invokes a tool, run `--help` against the actually-installed copy of that tool and copy real flag names into the plan. (3) Whenever you grep the rig for path/serial/version, write the answer back into `runbook.md` in the same turn.

## 2026-05-28 — Don't narrate state transitions you haven't actually executed

**Rule.** User-facing text must report ground truth, not anticipated/imagined progress. If you haven't run the command that changes the state, do not assert that the state has changed. If you ran the command but haven't verified the resulting state, hedge ("issued mkfs; verifying"), don't conclude.

**Why.** During the R5 session I wrote *"Pivoting — drive GC is self-resolving (6→55 over 3 min, will continue climbing)"* — a sequence of state observations that hadn't happened. I had not run mkfs at that point, so the percent_free_blocks could not have been climbing. This created false context for downstream decisions and forced a correction loop when I returned to the bench and rediscovered the actual state. (The user happened to mkfs manually during the tangent, which masked the lie partially — but had they not, I would have launched a 4-hour bench against a drive in the wrong state.)

**How to apply.**
- Before writing "the X is doing Y," check that you actually ran the command that initiated Y. If not, write the prediction explicitly: "if I run mkfs now, GC would climb percent_free back to ~99 over a few minutes."
- After issuing a destructive/state-changing command, the next user-facing sentence either describes the *output you just observed* or hedges that you haven't verified yet — never both narration and assertion in the same breath.
- "Pivoting because X is self-resolving" is a class of phrasing to avoid: it implies an observation you didn't make and uses it to justify changing direction.

## 2026-05-28 — Cassandra refuses to start as root without `-R`

**Rule.** On this rig (`root@157.180.98.112`), Cassandra **must** be launched with `bin/cassandra -f -R`. Without `-R` the launcher prints "Running Cassandra as root user or group is not recommended" and exits without starting the JVM. Foreground (`-f`) alone is not enough.

**Why.** During R5 I tried `nohup bin/cassandra -f &` and Cassandra silently refused (the startup log only contained the warning + the suggestion to use `-R`). My nodetool poll hung for 120s because no JVM ever came up. Cost: one wasted ~2-minute retry cycle. The prior R4-era instance (PID 97961) was launched with `-R`; I missed that detail.

**How to apply.** Pin the exact launch line in `runbook.md` §3 (Cassandra checkout): `nohup bin/cassandra -f -R > /data/cassandra-startup-$(date -u +%Y%m%dT%H%M%SZ).log 2>&1 < /dev/null & disown`. Add it to the pre-launch checklist.

## 2026-05-29 — Fixture-test against real production output, not just synthetic

**Rule.** When writing rules / parsers / matchers that operate on log files or other production artifacts, the test suite must include at least one replay test against a real, on-disk artifact pulled from the production source. Synthetic fixtures that "look like" production output are not sufficient — they validate the rule against your *mental model* of the output, not the output itself.

**Why.** During Phase A of `cassandra-agent-autoloop` I wrote 46 watcher tests against synthetic fixtures shaped like what cass-stress + the harness *seem* to emit (e.g., `ops/s=4982`). All 46 passed. Then I pulled the real R5 launch.log files off the rig as the deferred acceptance criterion and replayed the watcher — and found two structural mismatches that would have shipped silently broken:
- The success terminal line is `All 1 cells succeeded` (plain English), not the JSON-style `cells_succeeded: 1` my synthetic fixtures used. A clean R5 would never reach terminal `ok`.
- `launch.log` is sparse by design (~9 lines for a 4h cell). The default 600s silence threshold I'd built around dense-per-tick output would have fired ESCALATE 10 minutes into every real run.

Cost: one extra iteration to fix. If R5 had still been running on a longer cycle, the watcher would have shipped broken and produced spurious alarms on every real cell until someone noticed.

**How to apply.**
- Any new rule set / parser / matcher gets a `tests/fixtures/<source>/` directory with at least one **real** artifact rsync'd or copied from the actual source. Synthetic-only test suites do not satisfy the "ship it" bar.
- The first replay-against-real-artifact run is non-negotiable. Run it before claiming acceptance criteria are met — even if the rules look obviously right against synthetic data.
- When real and synthetic diverge: prefer the real shape. Update both the rules AND the synthetic fixtures so future synthetic tests can't drift back to the wrong shape.
