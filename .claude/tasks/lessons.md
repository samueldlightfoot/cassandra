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
