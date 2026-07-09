# Lessons

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
