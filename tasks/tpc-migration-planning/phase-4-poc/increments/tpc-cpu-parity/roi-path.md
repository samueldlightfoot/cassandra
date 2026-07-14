# The ROI path — why the win isn't in the numbers yet, and the tiers to get it (2026-07-14)

Strategic synthesis after the contention counter + side-by-side (`contention-result.md`,
`sidebyside-result.md`) and a re-read of `../../findings-tpc-paper.md` (Enberg/Rao/Tarkoma ANCS'19).
The mechanism is proven; the *return* isn't visible yet. This explains why, and lays out the tiers to
surface it — ordered so nothing needs a big box until the very end.

## The headline we can ALREADY claim (don't undersell it)
The current build holds **parity with trunk on every axis that matters** — ins/op −0.28%, p50/p90 flat
across the whole rate ladder, delivered-throughput identical — **while running in thread-per-core's
single worst-case regime and carrying a self-inflicted allocation handicap.** Maintaining equal
performance under all of the headwinds below is itself the result: a thread-per-core port that matches
shared-everything trunk here, with the coordination mechanism already firing (memtable contention
72/1k → 0, cross-core HITM −27%), is a build that has *paid the migration's cost and kept parity* before
any of the levers that make TPC win have been pulled. The win is upside from a proven-safe baseline, not
a hoped-for rescue of a regression.

## The hardware reality (stated precisely)
Rig `157.180.98.112` (cassandra-waf-rig) = **Intel Xeon E-2276G, 6 physical cores / 12 hyperthreads,
single socket, single L3, single NUMA.** Cassandra sees 12 logical CPUs → ~12 shard threads on 6 cores.
The constraint that suppresses the TPC win here is **not the core count per se — it is the single
L3 / single NUMA**: an L3-resolved cache-line bounce costs tens of ns, so the coordination tax that
per-shard routing removes is tiny in absolute terms. TPC's largest wins come from eliminating
*cross-socket / cross-CCX / cross-NUMA* transfers and deep lock convoys, none of which exist on this box.

## Why there is no ROI in the numbers yet — every measurement was in TPC's LOSS regime
The paper is explicit that TPC **loses at low concurrency** and that its win **only materialises under
load, with the environment configured**. We have satisfied none of its preconditions:

1. **The cores are never loaded.** The write knee is I/O/commitlog-bound at **~45% cass CPU** — offered
   concurrency doesn't convert to CPU work (delivered caps at ~0.70× offered on flush/GC stalls). We
   measure at ~45% busy; the paper's win needs cores saturated. *"TPC loses at low concurrency… the
   contention win only materialises under load."*
2. **No IRQ affinity / irqbalance-off** (paper's C1). Their **single biggest tail lever** — *"IRQ
   configuration moved tail latency more than the application architecture did."* Untouched here.
3. **No async batched I/O** (io_uring — paper's item-2 prescription, our Phase 1, not yet built). TPC on
   epoll/blocking pays the request-steering wake-up cost without the compensating async-batching win.
4. **Self-inflicted +10% allocation gap** (async future machinery) feeds young-GC, and **GC dominates the
   tail** on this box — so any tail win is masked by our own alloc regression.
5. **Single L3 / single NUMA** (above) — best case for shared-everything; and **write-only sharding**:
   the fork routes single-partition writes only (`Dispatcher.java:145`), reads fall through to the shared
   NTR pool, so the paper's read-tail half (54%/20%) is structurally unavailable to a read/mixed test.

Net: the null ROI is **predicted**, not disappointing. We proved the mechanism exists; we have not yet
put it in the regime where it pays.

---

## The tiers (ordered; >32-core run is LAST by directive)

### Tier 0 — FIX FIRST (precondition for any visible tail win): close the +10% alloc gap
Routing allocates ~+10% vs trunk (AsyncPromise per-handler ~670 samples + map-path listener node
`RunnableWithExecutor` ~540 + lambdas). As long as routing out-allocates trunk, its tail is GC-masked and
will read equal-or-worse regardless of the contention win. **This is the one perf task that gates every
downstream number, and it is a hot-path, hard-to-reverse async design — the right place to spend Fable.**
Goal: routing alloc ≤ trunk. Runs on the current box.

### Tier 1 — CURRENT BOX, enter the loaded/CPU-bound/IRQ-configured regime (first real ROI, ~days)
Recreate the regime the paper says TPC wins in, on the 6C/12T box:
- **Remove the I/O knee** so the 6 cores actually saturate: commitlog on tmpfs, or `durable_writes=false`,
  or batched commitlog. Drive writes until cass CPU ≈ 100%.
- **IRQ affinity + irqbalance off** (C1): NIC IRQs pinned off the shard cores.
- **High concurrency** to load all shard threads.
- Measure **loaded p99/p999 at 0.8–0.95× the new CPU-bound knee** (full HDR band, not a single p99) +
  **max sustained CPU-bound throughput**, trunk vs routing.
- **Read as:** if routing wins loaded tail and/or throughput once cores are saturated, that is the PoC
  ROI on hardware we already own. If it does not — because a single-L3 box's coordination tax is too
  small — that is a **clean, honest isolation** that the win needs cross-NUMA scale, and it *justifies*
  the final big-box run rather than guessing at it.
- Keep the domain contention counter (72/1k → 0) and c2c (−27%) as the GC-immune mechanism corroboration
  alongside whatever the loaded tail shows.

### Tier 2 — CURRENT BOX, structural features that grow the win (weeks; code, not hardware)
Both run on the existing box and unlock parts of the paper's ROI we can't reach today:
- **io_uring binding (Phase 1)** — the paper's central async-I/O prescription; KPI = **syscalls/op**.
  Without it TPC forfeits the OS-interface win (syscall crossings, epoll double-touch, packets bouncing
  across up to 3 cores). This is where the write-path tail win should grow even at modest core count.
- **Read-path sharding** — today write-only; the paper's read-tail win (54%/20%) needs reads routed to
  their owning shard. Real workloads are read-heavy, so this is likely the larger ROI lever long-term and
  the community will ask for it. Scope/verify against `CqlShardRouter` (which currently returns a shard
  only for routable single-partition writes).

### Tier 3 — FINAL VALIDATION ONLY: the ≥32-core / multi-NUMA run (deferred to the end, by directive)
The headline chart TPC is sold on — **throughput-per-core scaling curve** (trunk sublinear/plateaus,
routing near-linear) + **loaded tail at scale** (where trunk's cross-NUMA bouncing and lock convoys blow
up p99 and routing stays flat). This is the only measurement that *proves* tail-at-scale, and it is
explicitly the last step: we run it once Tiers 0–2 have (a) removed the alloc mask, (b) shown the loaded
mechanism pays or cleanly shown it needs scale, and (c) added the async-I/O + read-path machinery so the
big-box number reflects the real end-state, not a half-built one.

---

## Recommendation / sequencing
1. **Tier 0 (alloc gap) — start now**, Fable-assisted design (hot path, hard to reverse, gates the tail).
2. **Tier 1 (current-box loaded test) — immediately after / in parallel** on the measurement side.
   Cheapest path to a first ROI number or a justified big-box decision.
3. **Tier 2 (io_uring, read sharding)** — the substantive build-out that grows the win.
4. **Tier 3 (>32-core)** — final validation only, once the above are in.

The reframe to hold onto: **stop reporting at the 45%-CPU I/O knee — that is TPC's loss regime. Parity
there is already a win; the ROI lives one regime over (CPU-bound + loaded + IRQ-configured), which we
haven't sampled.**
