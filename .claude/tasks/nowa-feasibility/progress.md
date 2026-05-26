# NoWA feasibility — progress log

## 2026-05-25 — Initial scoping (this session)

- Re-read paper §5.3, §5.4, §5.5 in full after user pushback on the "compensation writes ~ 5%" framing.
- Corrected my framing: NoWA = alignment + open-zone discipline + compensation writes. Headline is SSD WAF 1.96 → 1.07 (−45%) + 11% throughput, not 5%.
- User noted Cassandra already has DIO compaction reads/writes. Re-scoped the port: 6–8 weeks (was 2–3 months).
- Drafted `task_plan.md` with three pre-implementation gates and four phases (A: alignment; B: writer cap; C: compensation writes; D: SSD-iq; E: bench+write-up).
- Drafted `findings.md` with full technical delta + recommendation hierarchy.
- Spawned background research agent for Hetzner DC NVMe lineup + OCP/FDP support. Result merged into `findings.md` §6.

### Gate 0 result (drive availability)

- **OCP measurement: available** via Samsung PM9A3 (Hetzner's "Datacenter Edition" sticker, MZQL2-series U.2 across AX/PX/auction). Cost: ~+€60/mo vs current consumer 2x2TB.
- **Bonus**: PM9A3 is the *same drive family* the paper uses in its primary Table 1 and Figure 13 results. Our numbers would be directly comparable.
- **FDP: unavailable on Hetzner**. The FDP-capable Samsung PM9D3/PM9D3a is not in their lineup. The "FDP hints instead of NoWA" alternative requires switching providers (Equinix Metal, Latitude.sh) — ~$150-300/mo step up.

**Gate 0 result: PASS on the NoWA path, FAIL on the FDP-hints alternative.**

**Decision pending from user**: whether to (a) commit to the rig upgrade and proceed to Gate 1 (measure current Cassandra SSD WAF on PM9A3), or (b) switch providers for the cheaper FDP-hints path, or (c) shelve.

## 2026-05-26 — Parked behind WAF baseline measurement

User pivoted to a measurement-first deliverable: see `../cassandra-waf-baseline/`. The baseline WAF measurement is a strict prerequisite of any NoWA / FDP mechanism work (we cannot meaningfully argue for a mechanism without knowing the baseline it would improve over), and it stands on its own as a Cassandra Jira contribution regardless of whether mechanism work follows.

This task is **parked, not abandoned**. The implementation gotcha catalogue (§10 of `findings.md`) remains useful regardless of which path comes next, and the rig-provisioning + harness instrumentation work is shared with the WAF baseline task. If the WAF measurement shows ≥ 1.4 SSD WAF at production fill conditions, this task is unparked with concrete motivation. If WAF is ≤ 1.2, this task is closed.
