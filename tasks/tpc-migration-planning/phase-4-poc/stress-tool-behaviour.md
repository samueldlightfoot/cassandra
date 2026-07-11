# cassandra-easy-stress — behaviour reference (READ BEFORE BENCHMARKING)

> **⚠️ 2026-07-10 RETRACTION — read `STRESS-RUNBOOK.md` first.** The core model in §1–§4/§8
> below ("single process is client-CORE-bound ~150–160k; stack multiple processes / repin /
> go off-box to exceed it") is **WRONG**. The single-process ceiling was the tool's
> **`--concurrency` default of 100**, not cores, not the RateLimiter, not fair-queue
> contention. Measured off-box: ONE process with `--concurrency 3000` delivers **253k w/s at
> Cassandra 97% CPU** (load box 41%). Do NOT stack processes for throughput — raise
> `--concurrency`. The delivery-collapse and multi-process sections here are superseded;
> keep them only as a record of the wrong turn.

Canonical, durable notes on how the load generator actually behaves, so we stop
re-deriving it. Confidence tags: **[measured]** = observed this rig, **[source]** =
read from code, **[reasoned]** = inferred. Rig: single co-located Hetzner box, Cassandra
pinned cores 0–7, client pinned cores 8–11 (4 cores).

Tool: `/root/repos/cassandra-easy-stress` @ `feature/csv-latency` (UNFIXED/clean — the old
"Random.kt value-gen fix" is a dead end, do not reapply). Java driver-core 4.19.0 (v4).
log4j output → `/root/.cassandra-easy-stress/stress.log`.

---

## 0. TL;DR — the method (and the rules behind it)

**THE method for loading Cassandra to a latency target:**
> **ONE** stress process, governed by `--maxrlat <ms>` **and** `--maxwlat <ms>`, started
> from a **low** initial `--rate`, run **long** (~4–6 min) so it converges, measured
> **server-side** (`nodetool` count deltas + `proxyhistograms`).

Why each rule:

1. **One process only — never stack.** Each process runs its own `RateLimiter` + its own
   latency optimizer. Stacking N processes = N uncoordinated controllers fighting over a
   shared client-core budget; they overshoot and never agree. (My earlier 3-process
   `--maxrlat` run stalled for exactly this reason — not the units.) [reasoned+measured]
2. **`--maxrlat`/`--maxwlat` are in MILLISECONDS.** Verified from source (§3). `--maxrlat
   100` = 100 ms p99 target. Set **both** so whichever mix you run is governed. [source]
3. **Start from a low `--rate`** (e.g. 20k). The optimizer only *raises* the rate when
   utilization (delivered/offered) ≥ 0.9, so it must begin in the clean regime where
   delivered≈offered and climb; starting high overshoots into collapse where the
   controller freezes (§5). [source]
4. **Governed latency is coordinated-omission-corrected** (queue-wait-inclusive) — the
   right signal for saturation, but it means the target p99 is reached around the client/
   Cassandra knee. Server-side `proxyhistograms` p99 will read lower; report both. [source]
5. **Measure server-side.** Client stdout counts are reliable in the clean regime (match
   `nodetool` to ~1.00) but degrade under overload; the client "Latency (p99)" column is
   ms and CO-inclusive. Cassandra latency authority = `proxyhistograms`. [measured+source]
6. **Never over-drive raw `--rate`.** Above the knee, delivered *drops* while client CPU
   pegs on lock contention (§1/§2). `--rate 2000000` is the worst case (~13% delivery).
   Only use fixed raw `--rate` for points you know are below the knee. [measured]

**This box's ceiling is client-CORE-bound (~150–160k ops/s), not process-count-bound**
(§4). If the gate needs Cassandra pushed past its *own* saturation, that needs more client
cores (repin) or an off-box generator — call it out so TPC gains aren't masked.

---

## 1. Architecture (how load is generated) [source]

Per process, `--threads N` creates **N independent `WorkloadRunner`s**, each on its own
thread (`Run.kt` ~511). Each runner:

- Owns a `RequestQueue` = one **generator thread** + one bounded **fair**
  `ArrayBlockingQueue(--queue, fair=true)` (`RequestQueue.kt:60`).
- Generator loop: `rateLimiter.acquire(1)` → build op (stamps `createdAtNanos`) →
  `queue.offer(op)` (`RequestQueue.kt:80-95`). Queue full → `metrics.errors.mark()` and the
  op is **dropped** (coordinated-omission handling).
- The runner thread consumes its queue and fires **async** `session.executeAsync`
  fire-and-forget with a callback. Concurrency is bounded by the driver in-flight/
  connection-pool limit (8×32768 = 262k), not thread count.

**All N generator threads in a process share ONE Guava `RateLimiter`** (`Run.kt:570`,
one per process). Guava serialises `acquire()` on an internal monitor.

**Why it collapses above the knee:** at high `--rate` the N generators hammer `acquire(1)`
on the single shared limiter monitor **and** contend on N fair queues (fair locks are
slow). On 4 client cores this lock contention burns the CPU, so generators can't produce
and consumers starve → **delivered drops while CPU pegs, Cassandra starves** (its CPU
*falls*). Not a Cassandra limit. [reasoned from §2]

---

## 2. Delivery vs raw offered rate — the core curve [measured 2026-07-10]

1 process, `--threads 32`, KeyValue write-only, 4 client cores, 2M partitions, **fresh**
391 MB dataset. Server-side delivered = `nodetool` Local write count delta over 20s.

| offered `--rate` | delivered | ratio | Cassandra CPU (0–7) | client CPU (8–11) | client p99 (ms, CO) |
|-----------------:|----------:|------:|--------------------:|------------------:|--------------------:|
| 40k   | 37.7k | **0.94** | 26% | 45% | 27 |
| 100k  | 95.8k | **0.96** | 50% | 64% | 189 |
| 200k  | 162k  | 0.81 | 90% | **99%** | 814 |
| 400k  | 130k  | **0.33** | 63% | 97% | **9442** |
| 800k  | 108k  | **0.13** | 65% | 99% | 3287 |

- **Clean closed-loop (≤100k):** delivered≈offered; take fixed-rate measurements here.
- **Knee (~200k offered → ~160k delivered):** client cores (99%) and Cassandra (90%)
  saturate together — the honest joint ceiling on this box.
- **Collapse (>200k):** delivered *falls*, CPU pegged on contention, latency = seconds.

---

## 3. Latency units & coordinated omission [source]

- Timer fed in **nanoseconds**: `OperationCallback.kt:64`
  `context.timer(...).update(endNanos - op.createdAtNanos, NANOSECONDS)`. Note it uses
  **`createdAtNanos`** (op creation in the generator, before queueing), NOT send-time — so
  the latency **includes queue wait ⇒ it is coordinated-omission-corrected.**
- Optimizer converts to ms: `RateLimiterOptimizer` `getReadLatency() =
  selects.snapshot.get99thPercentile() * (1/MILLISECONDS.toNanos(1))`. So `--maxrlat`/
  `--maxwlat` compare against a **millisecond** p99. `--maxrlat 100` = 100 ms.
- Console "Latency (p99)" column: `SingleLineConsoleReporter.kt:42` = `MILLISECONDS`,
  line 84 `convertDuration(...)` → **ms**. `--hdr`/`--csv-latency` also ms.
- Timers use the default dropwizard **ExponentiallyDecayingReservoir** (~5-min half-life):
  p99 is **sticky** — early high-latency samples decay slowly, so give the optimizer time
  and don't trust the first ~60s of governed p99.

---

## 4. Multi-process behaviour [reasoned+measured — SETTLED, do not stack]

Each process is an independent generator (own `RateLimiter`, own pool, own optimizer).
Stacking spreads the shared-limiter contention of §1, **but** all processes share the same
client cores, so aggregate is bounded by client **cores, not process count** — a single
process already drives the 4 client cores to 99% (Cassandra 90%) at ~200k offered (§2).
Adding processes just time-shares those cores. **And** with `--maxrlat` each process
optimizes independently → uncoordinated controllers. ⇒ **Use one process.** To exceed the
core-bound ceiling: repin (Cassandra 0–5, client 6–11) or generate off-box.

---

## 5. The `--maxrlat` / `--maxwlat` optimizer — how to use it correctly [source+measured]

`RateLimiterOptimizer.kt`, wired at `Run.kt:476`, `Timer().schedule(10000, 5000)` (first
tick 10s, then every 5s). Targets the CO-corrected client p99 (§3), in **ms**.

Control law (`getNextValue` + `optimizeRateLimit`):
- Step phase: ramp `rate/10 → --rate` over ~10 ticks (~55s), **latency-blind**.
- Then: p99 > target → cut 10%; within 90% of target → hold; below target → raise ≤5%/tick.
- **Gate:** it only *raises* if utilization (delivered/offered) ≥ 0.9, and only *lowers*
  if utilization ≥ 0.9 ("throughput well below limit → limit isn't the problem"). So when
  the client under-delivers, the controller is frozen **in both directions**.

**Failure modes to avoid (these bit us):**
- **Multiple processes** → uncoordinated optimizers → overshoot + stall. Use one. [measured]
- **High initial `--rate`** → the latency-blind step phase overshoots into collapse (§2)
  where delivered≪offered, utilization<0.9, controller frozen → stuck at a trickle.
  Fix: **start low** (~20k) so it climbs from the clean regime with utilization≥0.9.
- **Reading p99 too early** → sticky decaying reservoir (§3) hasn't settled. Run ≥4 min.

**Correct single-process converge behaviour [measured 2026-07-10]:** from a low start it
raises 5%/tick through the clean regime and settles (hunting/oscillating) at the SLO. A
single-process `--maxwlat 100` write-only run climbed cleanly (no stall) and converged at
**~47k/s** delivered (oscillating 27–70k as it hunts) with client write p99 ≈ 100 ms.

**⚠️ BUT — on this co-located box it governs the CLIENT's latency, not Cassandra's
[measured]:** at that converged point, **server-side `proxyhistograms` write p99 = 310 µs**
with Cassandra at **~30% CPU** — a ~300× gap below the 100 ms client p99. The client
CO-corrected latency is dominated by the generator's own queueing (fair queue + shared
limiter + async dispatch on 4 co-located cores), which saturates at ~47k/s while Cassandra
is nearly idle (its own knee is ~160k, §2). So:
- `--maxrlat/--maxwlat` is a clean, correct *client-latency* governor and converges fine
  single-process — but here it finds the **load-generator's** comfortable rate, not
  Cassandra's saturation.
- For a *Cassandra* latency/throughput baseline, drive throughput (clean-regime `--rate`)
  and read **server-side proxyhistograms** — that's the DB's real p99-vs-throughput curve.
- The oscillation is amplified by the sticky decaying reservoir (§3): after an overshoot the
  high p99 decays slowly, so the optimizer over-cuts, then p99 clears and it re-climbs
  (period ~100–150 s). Average over ≥2 cycles for a stable number.

---

## 6. Known operational gotchas (consolidated) [measured — see hurdles.md, memory]

- `--prometheusport 0` MANDATORY when scripting, else :9500 collides → silent rc=0 no-work.
- `--populate N` is **PER-THREAD**: total = N × `--threads`.
- The tool **exits rc=0 even after exceptions** — never trust exit code; assert positive
  work server-side; grep logs for `Usage:`, `Address already in use`, `Exception`.
- Client CSV truncated by SIGTERM — use stdout/`--hdr`.
- Client stdout rows use `\r`; parse with `tr '\r' '\n'` first.
- **`pkill` self-kill:** always bracket the pattern (`[e]asy-stress-.*-all[.]jar`,
  `[r]ebaseline_driver`) or you SIGKILL your own SSH shell.
- `nodetool tablestats` can intermittently return empty under heavy load — retry the read.
- Long remote commands: launch **detached** (`setsid … </dev/null >log 2>&1 & disown`) and
  poll a log; a foreground `ssh` dies on the 2-min tool timeout (exit 255).

---

## 7. Reconciliation with the older investigation (`tasks/easy-cass-stress-perf-fix/findings.md`)

That doc is internally contradictory; absorb it as follows:
- **DISCARD its achieved-vs-offered numbers** ("40k→5k … 2M→231k") — they disagree with the
  clean §2 probe (40k→**37.7k**, 100k→**95.8k**). The old figures were on the **skewed
  ~15 GB dataset** + client-side measurement. §2 (fresh, server-side) supersedes them.
- **DISCARD the "Random.getText() 14× fix"** — its own TL;DR retracts it (noise). Tool stays
  UNFIXED.
- **KEEP the ruled-out table** but note it was all at `--rate ~2M` (collapse regime): every
  knob flat ~30–49k, incl. **bypassing the RateLimiter** (still ~49k) ⇒ once past the knee
  nothing recovers throughput (consistent with §1; the collapse isn't *only* the limiter —
  fair-queue + driver in-flight + contaminated data all contributed). Rule unchanged:
  **stay below the knee / use the optimizer.**

---

## 8. What this means for the Phase-4.1 baseline

- **Method:** single process, `--maxrlat 100 --maxwlat 100` (operator's SLO), low initial
  `--rate`, ~4–6 min, per mix (w / rw / r). Read the converged **server-side** throughput
  (`nodetool` count delta) + **`proxyhistograms`** p99 = the saturation point at the SLO.
- For a p99-vs-throughput **curve**, sweep the target (e.g. `--maxrlat` 10/25/50/100) —
  each single-process run converges to one (throughput, p99) point below saturation.
- This box's write ceiling is ~150–160k (client+Cassandra saturate together); record it as
  a **client-influenced ceiling** and flag if true Cassandra-isolated saturation needs more
  client cores / off-box gen.
