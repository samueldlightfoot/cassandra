# Do we need a separate load-generator box? — decision analysis

**Date:** 2026-07-10. **Question:** before spending money, is a separate off-box load
generator *definitely* needed to produce a reasonably-accurate Cassandra p99-vs-throughput
baseline for the TPC PoC? Method: measured the rig, then ran 3 adversarial sub-agents
(pro-no-box / cheaper-dodges / red-team). This is the synthesis.

---

## Verdict (headline)

**A separate load generator IS needed to measure Cassandra near saturation on the current
box — no single-box option survives.** The reason is the *gate*, not box size: the PoC gate
is the **loaded tail** (`../phase-3-execution-model/increments.md` I1 — "the gate is the
loaded tail"; at low load TPC is expected to *regress* on the routing-hop crossover), so the
signal only exists near the knee, which a co-located generator can't reach cleanly on 6
physical cores.

**A 6-core Cassandra + off-box generator is a LEGITIMATE, if conservative, PoC.** The project's
own thesis (`../phase-3-execution-model/effort.md:83-84`) is that the per-shard lock, global
writeOrder/commitlog cachelines, and 128-parked-thread coordinator are a tax "at **every core
count**, growing with core counts" — present and JMX-measurable at 6 cores (I1 measures
`TrieMemtable` contended puts / contention time directly), just *smaller* in magnitude. The
genuinely core-count/NUMA-dependent effects are exactly the ones the program already **defers
to CEP-era** (`design-hostiles:741`, arm-B ChunkCache), so their absence here doesn't
undermine the PoC — it makes the 6-core number a **conservative lower bound**, not a null.

**Recommendation:** if buying, an off-box generator (**CCX43/16, not CCX33/8** — under-spec'd)
on the current Cassandra box is a defensible spend that yields a valid conservative PoC. A
larger-core Cassandra box is *better* (generalizable magnitude, exercises NUMA/many-core
routing) but is **not required** for a valid gate. Decide ambition (§4) accordingly.

> **CORRECTION (superseding an earlier draft of this doc):** an earlier version claimed the
> 6-core box makes TPC "barely matter" and that the load box was "likely the wrong spend."
> That was an overreach — it compressed the red-team's *generalization* caveat into a false
> *no-benefit* claim, contradicting `effort.md:84` / `increments.md:103`. TPC's PoC benefit is
> **load-gated, not core-count-gated**; it is present at 4-6 cores. Corrected throughout.

---

## 1. The hard physical wall (why no single-box trick works)

The box is a **Xeon E-2276G: 6 physical cores / 12 HT threads, single socket, ONE shared
12 MiB L3, single NUMA node** (physical core N = logical CPUs {N, N+6}). Consequences:
- Every co-located split tested was actually **HT-sibling-contended** (client shared physical
  cores with Cassandra). There is no clean physical split that leaves both sides meaningful.
- A **clean disjoint** client (2 physical cores) maxes at **~93k delivered while Cassandra
  sits at only 44% CPU** — it physically cannot drive Cassandra to its knee. To push harder
  you must HT-overlap Cassandra's own cores → contention in the exact near-knee region the
  gate cares about.
- The single shared L3 + one memory controller mean client↔Cassandra contend on cache/
  bandwidth **regardless of pinning**, and the E-2276G has **no Intel CAT** to partition L3.

**Sub-agent sweep of every no-box dodge — all fail for near-knee data:**

| Dodge | Verdict |
|---|---|
| More efficient loadgen (nosqlbench / native driver) | Partial — could reach knee from 2 cores but still HT/L3-contends; number is client-contaminated |
| Big-value / low-op workload | Fails — changes what's measured (bandwidth, not per-op efficiency = the TPC signal) |
| cgroups / cpuset / IRQ / CAT isolation | Fails — can't manufacture isolation the silicon lacks (shared L3, no CAT) |
| Driver tuning (threads/token-aware/batch/no-compress) | Partial — constant-factor shave, doesn't remove the 6-core wall |
| Oversubscribe near-knee | Fails — proven-benign only sub-knee; poisons the busy-core region that matters |
| Coordinator-only / record-replay / time-multiplex | Fails/partial — physical contention or destroyed steady-state remain |

## 2. Why sub-knee co-located ISN'T a free pass (the tempting shortcut)

We *measured* that at sub-knee load, co-location does **not** inflate server-side p99
(HT-overlap 446µs vs disjoint 535µs at ~37k; server latency is coordinator-clocked, immune to
client-core starvation). Tempting: just compare trunk vs increment at a 50%-load point (which
is exactly what `poc-criteria.md §5/§8` currently pins). **Two reasons this collapses:**
1. **Uninformative.** Sub-knee, Cassandra is 24–47% idle — no queueing, no contention, nothing
   for TPC to fix. Trunk and increment both post ~300–500µs and tie. The gate "passes" while
   measuring nothing TPC changes → an evidentially empty GO signal for a multi-year decision.
2. **Common-mode cancellation breaks.** TPC's whole point is changing CPU efficiency; on a
   shared box the two builds leave *different* residual headroom for the co-located client, so
   the "noise" becomes a function of the treatment and does **not** cancel in the delta. The
   coupling is real: at the joint knee client (99%) and Cassandra (90%) saturate *together*.

⇒ The interesting signal lives near the knee; near the knee, co-location confounds the exact
variable under test. Staying sub-knee dodges the contention only by dodging the measurement.

## 3. Box size: magnitude/generalization, NOT existence of benefit

A clean off-box baseline here measures a **6-physical-core, single-L3, single-NUMA** Cassandra.
It is tempting (and an earlier draft did) to say TPC "can't be shown" on such a box. That is
**wrong**, per the program's own docs:
- **`../phase-3-execution-model/effort.md:83-84`:** the per-shard lock, global writeOrder/
  commitlog cachelines, and 128-parked-thread coordinator are a tax "at **every core count**,
  growing with core counts." Present at 6 cores.
- **`../phase-3-execution-model/increments.md:103-104` (I1 gate):** benefit = per-shard lock
  contention → ~0, measured *directly* via JMX `TrieMemtable` contended puts / contention time.
  A mechanism-level signal that needs only >1 shard thread under load, not many cores.

So the PoC benefit is **load-gated, not core-count-gated** — it appears at 4-6 cores under
load. What the small box costs us is **magnitude and generalization**: contention is superlinear
in cores, so a 6-core delta *understates* the many-core win; and the effects that are strictly
high-core/NUMA (`design-hostiles:741`, arm-B per-shard/NUMA ChunkCache, cross-NUMA cacheline
cost) won't appear — but those are **explicitly deferred to CEP-era and are not in the PoC
gate**. Net: a 6-core near-knee result is a **conservative lower bound** on TPC's benefit, which
is a *valid* PoC outcome (if TPC wins even here, it wins bigger on real iron; a null here does
not rule out a many-core win — state that limit). The undersized-Cassandra-box concern is about
**how strong a claim the result supports**, not whether a benefit can be observed at all.

## 4. The decision the user must make (before spending)

**Q: What must the gate prove?** Note a live tension to resolve first: `poc-criteria §5`
currently pins the gate at 50%-of-clean_max (sub-knee), but `increments.md:103` says "the gate
is the **loaded tail**" and expects a low-load *regression*. These disagree; the loaded-tail
framing is the one that actually tests TPC. Reconcile before measuring.

- **(A) Only a 50%-load relative A/B** (as `poc-criteria §5` reads today): co-location is
  tolerable → **no box needed** — but sub-knee is where TPC has least to fix and I1 even predicts
  a regression, so this risks an uninformative or misleading result (§2). Cheap, weak.
- **(B) Loaded-tail near-knee + saturation throughput** (what I1 actually calls the gate): **a
  box is needed** — the current box can't reach it co-located.

**Then, given (B):**
- **Defensible & cheapest:** keep this Cassandra box + add an **off-box generator (CCX43/16
  dedicated vCPU, verify `%steal`≈0 + mpstat headroom; CCX33/8 is under-spec'd).** Yields a
  **valid, conservative (6-core lower-bound)** PoC — pair the macro loaded-tail gate with the I1
  JMX micro-metrics (contended puts / contention time → ~0) for mechanism-level evidence that
  holds regardless of box size. This is a reasonable spend.
- **Stronger, not required:** a **larger-core Cassandra box** (16+ real cores, ideally 2-socket /
  NUMA) — bare-metal auction is cost-effective — driven by a generous co-located or off-box
  generator. Gives a *generalizable* magnitude and exercises the many-core/NUMA routing, but is
  an upgrade to the claim's strength, **not a prerequisite** for a valid gate.

## 5. Evidence appendix (all measured 2026-07-10, server-side, fresh 2M/346 MB dataset)

- Topology: Xeon E-2276G, 6 phys / 12 HT, 1 socket, 12 MiB shared L3, 1 NUMA. core N = {N,N+6}.
- Contamination test (Cassandra = 4 phys cores): disjoint 2-phys client 50k→37.7k, Cass 24%,
  srv p50/95/99 = 14/29/535µs. HT-overlap client 50k→37.5k, Cass 47%, = 17/72/446µs (tail held).
  Disjoint client MAX (120k offered) → 92.9k, Cass **44%** (client-bound, knee unreachable).
- 6/6 HT-shared raw-rate: 100k→78k (Cass 43%, srv 1.6ms), 160k→121k (73%, 2.8ms), 220k→142k
  (89%, 4.0ms). `--maxwlat 100`: converged ~47k, Cass 30%, srv 0.37ms, client 121ms (governs
  the *client's* queueing, not Cassandra).
- Historical (Cass 0-7 / client 8-11, HT-shared): 100k→95.8k, 200k→162k joint knee (both
  90–99% CPU), collapse >200k. ⇒ 6-phys-core write knee is ≥162k, never driven cleanly.

**Sub-agent verdicts:** pro-no-box → "my case collapses, box needed"; cheaper-dodges → "no
dodge survives on the 6-core wall"; red-team → "needed-but-insufficient & possibly
misdirected — the Cassandra box is the binding constraint; if buying a load box, CCX43 not
CCX33."
