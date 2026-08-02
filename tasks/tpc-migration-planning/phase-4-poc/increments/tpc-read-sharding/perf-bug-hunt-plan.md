# Plan — Read-path routing perf-bug hunt (v2, after Fable critique)

**Why.** The read A/B (`result.md`) showed routing burns **+24µs CPU/read (~+57%) at light load** and **collapses**
under load (~1.3M timeouts where the baseline holds 0). We first blamed the routed submit path's allocations +
a contended counter. A Fable review **overturned that ranking**: those cost <0.5µs/read (~2% of the delta) — the
real cost is the **cross-thread handoff, paid twice per read**, plus working-set migration, HT cycle-stretch,
and runqueue delay. This v2 records the corrected diagnosis, the pre-code experiment that discriminates it, and
the two fixes worth making (neither is in the original top-3).

## Corrected diagnosis (Fable, code-verified)

Per-read arithmetic: OFF 99k@34% ≈ 41µs/read; ON 99k@54% ≈ 65µs/read ⇒ **+24µs/read**. Cost of the suspects:
- Allocations (`shardTagged` lambda, LBQ node, `OptionalInt`) net ~2–3 objects/read ≈ **~100ns**. The baseline
  inline path *also* allocates the `ExecutionFailure` wrapper (`SEPExecutor.maybeExecuteImmediately`→`toExecute`),
  so the delta is even smaller. Two–three orders of magnitude short of 24µs.
- `AtomicLong` CAS: ~100–300ns. Noise.
- **Two handoffs/read** (the dominant cost): (1) submit → `LinkedBlockingQueue.offer`→`notEmpty.signal()` unparks
  the shard worker; (2) shard worker finishes → `handler.response()`→`condition.signalAll()` unparks the NTR
  thread blocked in `ReadCallback.awaitResults` (StorageProxy.java:3184, ReadCallback.java:126–136). The baseline
  runs the read **inline on the NTR thread before it awaits**, so it parks **zero** times. ~4 futex + 2–3 context
  switches/read, direct ~6–12µs + cache/TLB refill + cross-core working-set migration ~5–15µs → covers 16–24µs.
- Fingerprint confirming handoff (not alloc): the delta **shrinks under load** (24→16µs) as busy workers skip the
  submit-side park — alloc/CAS costs would be load-invariant per read. HT confound: past 6 physical cores, part
  of the +24µs is the *same instructions running slower*, not extra work.

**Collapse = capacity exhaustion, not queue policy.** Routed capacity ≈ 8.4 cpu-s/s ÷ 50µs/read ≈ **168k r/s ≈
the 167k delivered** at the knee. No queue change recovers that. The unbounded queue + a **silent expiry drop**
(below) convert a ~7%-over-knee into 2s p99 + 1.3M timeouts.

## Fixes — corrected list and priority

| do? | change | why | note |
|---|---|---|---|
| **(a) YES** | **Fast-fail on expiry drop**: `DroppableRunnable.run` (StorageProxy.java:3551) drops an expired queued read WITHOUT `handler.onFailure` → coordinator waits the full read timeout holding an NTR thread → the timeout storm + NTR starvation. Respond failure at drop time. | Caps the collapse damage regardless of queue depth. **Higher value than the entire original top-3.** | audit missed this |
| **(b) YES** | **Exception-free bounded submission**: pre-check `getPendingTaskCount()` in `ShardReads.submitLocalRead`, fall back to `Stage.READ` when the owning shard is backed up. | Prevents unbounded queue growth without the broken rejection path. | NOT `withQueueLimit` — see below |
| ~~#1 as written~~ | ~~`withQueueLimit(N)` → REE → fallback~~ | **Broken:** default handler is `blockingExecutionHandler` — it BLOCKS the submitter, only throws on shutdown. `withQueueLimit` alone blocks NTR threads = worse. `ManyToOneConcurrentLinkedQueue` can't drop into a JDK TPE (needs a `BlockingQueue`). | replaced by (b) |
| ~~#2 TaskFactory.standard~~ | ~~drop localAware wrapper~~ | **Reject — factually wrong.** `ExecutorLocals.propagate()` allocates nothing; Standard allocates the SAME wrapper. Saving ~0ns; cost = routed reads silently lose `ClientWarn` (tombstone/oversize warnings) + trace. | client-visible regression |
| ~~#3 worker-side tag~~ | ~~set CURRENT_SHARD once~~ | **Drop.** `CURRENT_SHARD` gates the write path's memtable-lock elision (`currentThreadIsOwnerOf`); a wrong answer = silent memtable corruption. Saves 24 bytes/read. | correctness risk ≫ reward |
| optional | delete `AtomicLong submitted` | redundant with `routed.inc()` (LongAdder). Delete, don't LongAdder-ify. | cleanup only |

Fixes 2/3/5 combined address <2% of the delta. Only (a)+(b) before re-measuring.

## Phase 1 (revised) — the ONE pre-code experiment

**`perf stat` A/B at the fixed 99k light-load point** (NOT a flamegraph — flamegraphs are blind to off-CPU park
time and smear stall cycles, so they'd "confirm" the allocation story). Collect `cycles, instructions,
context-switches, cpu-migrations` (+ `syscalls:sys_enter_futex` if available), 60s/arm, converted **per-read**,
with the mpstat %usr/%sys split. Pre-registered decision tree:
- **instructions/read ≈ flat, cycles/read +50–60%, +2–3 ctx-switches/read, %sys up** → handoff/scheduling/HT
  dominates, allocations exonerated → ship only (a)+(b), skip the rest, and take the big-box design gap seriously.
- **instructions/read up ≥30%** → real software work is unaccounted → THEN a flamegraph is warranted.

Needs the loadgen re-provisioned (recipe in progress.md). One no-code run decides the whole fix set.

## Phase 2 — apply (a)+(b), re-A/B

Light load: does routing reach graceful (fall-back) behavior? Near-knee/overload: timeouts ≈ baseline instead of
25–50×? Note (b) makes routing **self-disable** under sustained overload (reads bounce to `Stage.READ`) — correct
"do no harm," but state plainly that on this box the feature turns itself off exactly in the regime the paper's
win lives in. Acceptable only because the win was never available here.

## Strategic corrections (Fable) — bigger than the bug fixes

1. **Variant A is structurally the weakest point in the design space, on any hardware** — it thread-swaps a
   *blocking* read mid-request onto a *single-threaded* executor: two handoffs + HOL fragility, and (on shared L3)
   zero locality income to pay for them.
2. **The deferred big-box test measures nothing as currently designed.** The shard is chosen from the **current
   memtable's** boundaries (`MutationShardRouting.shardForKey`), but the benchmark data is **sstable-resident
   behind a global chunk/page cache**, with **unpinned** shard threads. There is no data affinity to exploit. A
   real read-locality win needs pinned shard threads + shard-partitioned read state (chunk cache at least), OR a
   memtable-resident read workload. Pin this BEFORE spending on the big-box rig.
3. **The structurally sound design is to ingress-route the WHOLE read** (coordinate + local read + response
   serialization on the shard thread): one handoff instead of two, request state built hot on the owning core, no
   mid-request thread-swap. This is the program's stated Accord inbox-route end-state — deferred only because the
   read path is synchronous (a shard thread would block on remote-replica RTT at CL>ONE / RF>1). It pairs with an
   async read path (→ io_uring). Intermediate on the big box: **per-L3/NUMA SEP pools** (keep work conservation +
   multi-thread absorption of service-time variance, gain coarse locality). Work-stealing = re-derives SEP; skip.

**Bottom line:** the perf "bugs" are mostly not where we first looked. Ship (a) expiry fast-fail + (b) exception-
free fallback (do-no-harm), gate on the one `perf stat` experiment, and treat the strategic question — Variant A
vs ingress-route-the-whole-read, and fixing the shard↔data-affinity gap — as the real decision before the big box.
