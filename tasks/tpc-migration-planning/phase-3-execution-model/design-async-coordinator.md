# Phase 3.2 — Non-Blocking Coordinator (design-async-coordinator.md)

**Status:** decided 2026-07-09. Inputs: spec.md §3.2 (scope/acceptance);
expected-changes.md §3.2 + §3.6 (pinned discoveries — timeouts already async on the two
hot paths, Flusher thread-safe from any thread, ExecutorLocals do NOT follow responses,
NTR queue-age backpressure goes dead); `../phase-4-poc/findings-i2-i3-reads-coordinator.md`
(the complete await inventory, extended not re-surveyed); design-target.md (accepted 3.1 —
D1 shard model, D4 routing points, §3 item 5 I/O-pool reschedule seam, D2 arms);
`../findings-tpc-paper.md` (steering-cost evidence). All load-bearing file:line refs
re-verified against the working tree 2026-07-09. Drift found (findings were 2026-07-07):
`RequestCallbacks` reaper `:64→:63` (scheduleWithFixedDelay `:70-72→:71`); `submitHint`'s
`Stage.MUTATION.submit` `:3387→:3392`; `Dispatcher.hasQueueCapacity` `:356-359→:352-358`;
`ClientWarn` COW list `:94-95→:102`, resetWarnings body `:85`; `ExecutorLocals.create`
`:113-117→:119`; and one misattribution corrected — findings' "BlockingPartitionRepair
.awaitRepairs (:96-112, Future.get :102)" is actually the `PartitionRepair` interface
default at `BlockingReadRepair.java:96` (Accord-variant `repairFuture.get` at `:260`);
the concrete park is `BlockingPartitionRepair.awaitRepairsUntil`
(`BlockingPartitionRepair.java:190-207`, latch.await `:196/:207`, latch ctor `:104`).
**New verified fact the findings missed: `BlockingPartitionRepair` already
`extends AsyncFuture<Object>` (`BlockingPartitionRepair.java:61-62`)** — the R4
conversion is mostly free (§3 R4, §5).

**Amended same day per adversarial review (review-design-async-coordinator.md,
PASS-WITH-FIXES).** Blockers landed: E1 — the per-request deadline task is now the
timeout AUTHORITY (silent local-leg drops otherwise hang the promise and permanently
leak ops slots; also fixes the reaper's up-to-1s-late timeouts, §5); A1 — FQL/audit
completions offload to requestExecutor (`BinLog.put` parks, §1/§6); A2/E3 — the
**no-inline-local-work rule** (self legs `execute()`, never mEI, from
non-park-licensed threads, §1). GAPs landed: A3/F1 (completing-executor set restated
honestly + reentrancy), A4 (RR health gauge), B1/B2 (guard list completed +
blind-spot list), C1/C2/C3 (BatchMessage override, CL-aware predicate, sync-prefix
boundary + M7 AuthCache row), D1/D2 (ops-limit CAS ownership + release→signal wiring
+ leak paths), C4 (coordinator-metrics relocation), F2 (hop-table evolution note).
Review-cite correction: QueryEvents lives at `cql3/QueryEvents.java` (review said
`audit/`) — listener set `:49`, sync notify loop `:67-85`.

Audience: the implementing agent first. Every section ends in a DECISION.

---

## 0. Scope and the conversion primitive

I3 converts the coordinator's blocking replica-RTT awaits into future compositions. It
is separable from shard routing and valuable alone: with awaits converted, an NTR
thread's job shrinks to parse/bind/dispatch (~µs) and the 128-thread pool
(`native_transport_max_threads`, `Config.java:352`) loses its raison d'être — only
parking justifies 128 threads on ≤16 cores.

**The conversion primitive.** `RequestCallback` already delivers responses
asynchronously (`ResponseVerbHandler.java:62-87`: `callbacks.remove` `:64`,
`cb.onFailure` `:80`, `cb.onResponse` `:85` — inline on REQUEST_RESPONSE, no further
hop). `ReadCallback` and `AbstractWriteResponseHandler` each grow an `AsyncPromise`
completed where `condition.signalAll()` fires today (`ReadCallback.java:242/:281`,
`AbstractWriteResponseHandler.java:359`); the result-check bodies of
`awaitResults` (`ReadCallback.java:134-214`) and `get()` (`AWRH.java:130-194`) become
the completion functions. The blocking methods stay (behind-the-line callers keep
using them, §2) — precedent: `BlockingPartitionRepair` already carries both shapes
(AsyncFuture + latch) in one class.

**Where the flag branches (pinned here):** `Message.Request` gains
`Future<Response> executeAsync(QueryState, RequestTime)` with default
`= immediateFuture(execute(...))` (`Message.java:250` — no async path exists today).
`QueryMessage`/`ExecuteMessage` **and `BatchMessage`** override it (C1: driver-sent
unlogged batches arrive as `BatchMessage`, not QueryMessage — without its own
override, §2's "unlogged batches convert" would be a dead letter; its
`QueryEvents.notifyBatchSuccess` relocation rides the same completion helper, §6) and
take the async path ONLY for requests inside the cut-line (§2 predicate — statement
shape AND per-request consistency level); everything else falls through to the
synchronous `execute` on the NTR thread, byte-for-byte today's behavior.
`Dispatcher.RequestProcessor` (`Dispatcher.java:131`) becomes: capture continuation
context (§6) → `executeAsync` → `addCallback(completionHelper)`.

**The synchronous prefix, bounded precisely (C3):** everything up to and including
the `StorageProxy.read`/`mutate` entry runs synchronously on the dispatch thread
inside `executeAsync` — parse/prepare lookup, **`checkAccess` (whose
role/permission `AuthCache.get` is a sync Caffeine load that can miss into an
internal SELECT → a full R1 blocking read — §3 row M7)**, guardrails, bind, token
computation. The async boundary is the StorageProxy call: only the replica RTT and
what follows it become a future. The prefix parks are lawful because the dispatch
thread (NTR/requestExecutor) keeps its park license (§1). This is the
Message/Dispatcher half of I3's blast radius the effort model already prices.

DECISION: conversion primitive = promise-per-handler completed at the existing signal
sites; flag branch = statement-and-CL predicate behind `Message.Request.executeAsync`
(QueryMessage/ExecuteMessage/BatchMessage overrides), default-sync; sync prefix ends
at the StorageProxy entry.

---

## 1. Continuation executor — DECISION A1

**DECISION: continuations complete inline on whichever thread delivers the final
signal — no new completion pool is created; read terminal materialization (and, when
audit/FQL listeners are registered, the whole completion) dispatches to the shrunken
NTR `requestExecutor`, the design's park-licensed executor.** "Strict
REQUEST_RESPONSE" is the honest headline for remote-ack traffic — the dominant case —
but the full completing-executor set is stated below (per review A3/F1), and the
non-blocking rule binds ALL of it.

The paper evidence (../findings-tpc-paper.md §3, C2): the wake-up is the unit of
steering cost — completing on the thread already holding the response cache-hot
(REQUEST_RESPONSE) beats adding a hop to a completion pool (wake ≈ µs vs enqueue ≈
tens of ns), *iff* the non-blocking-all-the-way-down rule holds. The rule is real:
REQUEST_RESPONSE is only P threads (`Stage.java:52`); a continuation that parks there
consumes the very worker needed to deliver the response that would unblock it — P
concurrent parks = distributed self-deadlock until timeout. The in-tree TCM fetch on
REQUEST_RESPONSE (`ResponseVerbHandler.maybeFetchLogs` `:89-133`,
`fetchLogFromPeerOrCMS` `:115/:129`) is a bug-shaped precedent, not a license.

So the rule's feasibility decides the split, path by path:

- **Writes:** terminal completion is trivial — build `VoidMessage`/error, attach
  warnings, flush (Flusher is thread-safe from any thread, `Flusher.java:118-140`).
  Nothing can park. **Writes complete inline on the acking thread with zero extra
  hops** — REQUEST_RESPONSE when the last-needed ack is remote (the dominant case;
  the paper's cache-hot argument banked in full), but the local leg acks from the
  MUTATION stage or inline on NTR (`performLocally` → mEI,
  `StorageProxy.java:2023-2066`, invoking `handler.onResponse(null)` on its executing
  thread) — see the executor set below.
- **Read protocol steps** (response arrival → digest check → mismatch retry → repair
  wait): all converted to compositions (§3 R1/R3/R4) — remote sends and future
  composition are non-blocking, **but the mismatch/repair local read leg is NOT a
  send**: `readRepair.startRepair` → `AbstractReadRepair.sendReadCommand` self branch
  is `Stage.READ.maybeExecuteImmediately(LocalReadRunnable)`
  (`AbstractReadRepair.java:100-102`) — with a free READ permit, mEI runs the full
  local disk read INLINE on the calling thread (ChunkCache miss loads synchronously,
  `ChunkCache.java:152`; mmap index page faults; device-latency pread). On RR that is
  the starvation scenario. Governed by the **no-inline-local-work rule** below.
- **Read terminal materialization** (consume the `PartitionIterator`, build the
  ResultSet, flush) **cannot be certified non-blocking**: consumption is pull-based
  and lazily triggers short-read protection (`ShortReadPartitionsProtection.java:191`)
  and replica-filtering protection (`ReplicaFilteringProtection.java:175`) — each an
  additional replica round awaited via `ReadCallback.awaitResults` mid-iteration —
  plus close-time repair blocking (`concatAndBlockOnRepair`,
  `StorageProxy.java:2607-2620`). Converting those requires an async resolver rewrite
  (push-based iteration through DataResolver) — XL, out of PoC scope, and SRP/RFP
  triggering is data-dependent (any limited query can short-read), so it cannot be
  excluded by predicate. Materialization therefore dispatches to `requestExecutor`
  (one enqueue into a hot SEP pool — under load the wake amortizes to ~ns because SEP
  workers spin between tasks), where parking is lawful. This is also where non-trivial
  result-building CPU belongs — large ResultSets on a P-thread response pool would be
  a starvation vector even without parks.

**The completing-executor set, honestly (A3/F1).** Flag-on, a continuation can run
on: **RR** (remote acks), **IR** (reaper timeouts), **MUTATION stage** (local write
ack via `performLocally`), **READ stage** (local read leg's `handler.response`),
**NTR itself** (mEI-inline local legs — the promise can complete SYNCHRONOUSLY while
`executeAsync` is still on the dispatch stack), **the timer thread** (§5 deadline
task), and post-I2 **shard threads and the small I/O pool** (design-target §3 item 5:
a pool thread finishing a rescheduled read calls `handler.response` → R1's promise
completes there and the digest-check continuation runs on the pool thread —
functionally fine; the cost is a cheap continuation occupying a slot sized for
blocking device reads, accepted and measured, one more reason continuations must stay
µs-cheap). Three consequences:
1. Continuations are **executor-agnostic**: no continuation may assume RR identity,
   thread-locals, or stage semantics — everything request-scoped travels in the
   captured `ContinuationContext` (§6).
2. The completion helper is **reentrancy-safe**: it may run synchronously under the
   dispatch call (addCallback on an already-complete promise) — locals install/reset
   is strictly scoped (§6 step 5), flush-enqueue is safe from NTR (§8), and the
   helper never recurses into dispatch.
3. The non-blocking rule binds the whole set except the park-licensed executors
   (NTR/requestExecutor); the guard's thread-set is RR + IR (+ shard threads at I2)
   — MUTATION/READ stages tolerate parks today (memtable-pool waits) and are not
   signal-delivery pools, so they are not guard-declared, but continuations must
   still be non-blocking because the SAME continuation can run on RR.

**The no-inline-local-work rule (A2/E3, normative for every converted path):** from a
non-park-licensed thread, local replica work is NEVER submitted via
`maybeExecuteImmediately` — always `execute()`. Concretely, flag-on: the
digest-mismatch repair read's self leg (`AbstractReadRepair.java:102`), speculation's
self legs (R2 data request, W2 extra local write via `performLocally`), and any other
`mEI` reachable from a continuation switch on
`NonBlockingStage.isNonBlockingThread()` (true → `execute()`). Cost: one stage hop on
a rare path (mismatch rate, speculation-to-self rate). The mEI fast path is preserved
where it matters — dispatch-time submission from NTR.

**FQL/audit offload (A1, blocker fix):** relocated `QueryEvents.notify*` runs
listeners synchronously (`cql3/QueryEvents.java:67-85`); audit and FQL both terminate
in `BinLog.put`, which with the default `block=true` **loops
`sampleQueue.offer(record, 1, SECOND)` — an unbounded park** whenever the Chronicle
appender falls behind (`BinLog.java:222-232`, `:309`; blocking field `:99/:156`),
plus mmap'd Chronicle writes even when non-blocking. DECISION: the completion helper
checks listener registration ONCE per completion (`QueryEvents.instance`'s listener
set, `:49` — a size check on a CopyOnWriteArraySet, ~free); **if listeners are
registered, the ENTIRE completion dispatches to requestExecutor** (whole-completion
offload, not just the notify — keeps the helper single-shaped and puts the
Chronicle page-fault I/O off RR too). Clusters without audit/FQL (the perf-sensitive
case) pay nothing; audit clusters pay one hop per request, dwarfed by the BinLog
write itself.

**Deadlock-freedom by construction:** threads that park (NTR/requestExecutor) are
pool-disjoint from threads that signal (REQUEST_RESPONSE for responses,
INTERNAL_RESPONSE for reaper timeouts) — the same disjointness that makes today's
128-thread design safe, kept at 1/8th the thread count. The rules above exist to
keep the signal-delivery pools out of the park set transitively.

**Enforcement mechanism (what stops a future contributor from blocking a
continuation):**
1. **Park-site guard, mechanical:** a `NonBlockingStage.assertLegalPark()` static
   (checks a registered thread-set: REQUEST_RESPONSE, INTERNAL_RESPONSE, and later
   shard threads) inserted at **every park primitive in the §3 inventory** (B1 —
   behind-the-line sites included, because a future mis-drawn cut-line parking a
   continuation thread is exactly what the guard exists to catch):
   `ReadCallback.awaitUntil` (`:122-126`); `AWRH.get` (`:137`) and `:449`;
   `AbstractPaxosCallback.await` (`:53-60`, P1);
   `PaxosPrepare.awaitUntil` (`PaxosPrepare.java:433`, P3);
   `PaxosCommit.Async.awaitUntil` (`PaxosCommit.java:134-141`, P3);
   `PaxosState.lock` (`PaxosState.java:428/:474`, P5);
   `ContentionStrategy.doWaitForContention` (`ContentionStrategy.java:175-183`, P4);
   `CounterMutation.grabCounterLocks` (`:199-209`, W4);
   `BlockingPartitionRepair.awaitRepairsUntil` (`:190-207`, R4);
   `TruncateResponseHandler.get` (M1); the `describeSchemaVersions` latch await
   (`StorageProxy.java:2878`, M2); and `AccordService.coordinate`'s `awaitAndGet`
   (`AccordService.java:1135-1138`, M5). Enabled by default in tests/dtests (assert +
   ERROR log in production, not a throw). One explicit exemption-with-comment at
   `ResponseVerbHandler.maybeFetchLogs` (M3): pre-existing, acknowledged in-tree,
   ticketed — the guard makes it visible instead of licensing imitations.
2. **The guard's structural blind spots, stated (B2) — reviewers must NOT rely on
   the guard for these classes.** It sees inventoried park primitives only; it
   cannot see: **mmap page faults** (index reads under default `mmap_index_only` —
   mitigated by the no-inline-local-work rule keeping local reads off RR, and by the
   PoC's `disk_access_mode: standard` pin / arm-B's cache-only index access);
   **synchronous ChunkCache miss loads** (`ChunkCache.java:152` ImmediateExecutor —
   same mitigation, plus the I2 I/O-pool reschedule seam); **Caffeine sync loads
   generally** (AuthCache — confined to the sync prefix on park-licensed threads,
   §0/M7); **JNI/native calls** (review-time contract, no mechanical check);
   **`BinLog.put`'s ArrayBlockingQueue park** (A1 offload above);
   **`MemtableAllocator` hasRoom parks, commitlog `awaitAvailableSegment`, the MV
   `Thread.sleep(10)` loop** (apply-side — unreachable from coordinator
   continuations flag-on because I3 routes no applies; they are I1's C5 budget,
   design-target §3/§5). Each class has a named owner-mitigation; the guard is the
   backstop for the primitive-park class only.
3. **Contract comment** at the `Stage.REQUEST_RESPONSE` declaration
   (`Stage.java:52`) stating the rule and pointing at the guard — the canonical
   location, per the invariant-comment lesson.
4. **The cut-line itself** (§2) is drawn so nothing inside the flag has an
   unconverted nested await; anything unconvertible is outside the flag and never
   produces a continuation.
5. **The no-inline-local-work rule** (above) closes the transitive-blocking class
   the guard can't see (inline mEI turns a submission into an inline disk read).

---

## 2. I3 PoC cut-line — DECISION A2 (ratified, with two clarifications)

**DECISION: RATIFIED — flag-on converts single-partition reads + plain mutations
only.** Clarifications: (1) "single-partition reads" includes multi-command
`SinglePartitionReadCommand.Group`s (IN queries, paging pages) — `fetchRows`'s
per-command sequential awaits (`StorageProxy.java:2691/:2704`) become `allOf`
composition, same machinery, no reason to carve them out; range/aggregation reads
stay out. (2) "plain mutations" = non-conditional, non-counter, non-batchlog-atomic
writes; unlogged batches of plain mutations convert (they are today's
`mutate(...)` loop, W1, via the `BatchMessage` override — §0); logged batches do not
(W3). (3) **The predicate is CL-aware, not statement-shape-only (C2):** a
single-partition SELECT at `SERIAL`/`LOCAL_SERIAL` goes `readWithPaxos` → the P3
monitor parks — statement shape alone would convert it into an RR-parking
continuation. The predicate consults the per-request
`QueryOptions.getConsistency()`: SERIAL/LOCAL_SERIAL reads fall behind the line
(sync path) regardless of shape. (Write-side `serialConsistency` needs no check —
conditional writes are excluded statically by statement shape.)

**Behind-the-line mechanism flag-on (per the acceptance demand — they still block; on
which thread; why that's safe):** the §0 flag predicate means excluded operations
never enter the async path at all — `executeAsync`'s default runs the synchronous
`execute` on the **NTR/requestExecutor thread** (auth on `authExecutor`,
`Dispatcher.java:81-84`), which parks exactly as today. Safe because:
- NTR keeps its park license and its default size: **flag-on does NOT change
  `native_transport_max_threads` defaults** — the pool shrinks only in A/B cells
  where the shrink IS the measured claim (findings-i2-i3 open question 6, ratified).
  A mixed workload (plain writes + Paxos) flag-on therefore has identical blocking
  capacity for the Paxos fraction.
- Nothing behind the line shares a completion path with converted traffic — no
  continuation, no REQUEST_RESPONSE exposure, no new signal dependencies. The reaper
  still delivers their `onFailure` where `invokeOnFailure()==true` and their parked
  thread still self-times-out where not (Paxos v1, §5).

Per-exclusion rationale (thread it parks flag-on → why it can't convert in the PoC):
| Excluded op | Parks (flag-on) | Why excluded |
|---|---|---|
| Paxos v1 (P1/P2) | NTR, on CountDownLatch + WRH condition | `AbstractPaxosCallback` does not override `invokeOnFailure()` (default false, `RequestCallback.java:55`) → **no reaper timeout exists**; async conversion requires new timer machinery per round. See §5 consequence. |
| Paxos v2 (P3-P5) | NTR, on monitor/Condition + contention sleeps + PaxosState lock | Multi-round state machine with `synchronized` awaitUntil (`PaxosPrepare.java:433`), thread-sleeping backoff (P4), and a local deadline lock (P5) — a first-class conversion project of its own (end-state: per-round owner-forwarding, design-target D5). |
| Counters (W4) | COUNTER_MUTATION stage on Striped `tryLock` + read under lock (`CounterMutation.java:73,199-209`), NTR on W1 condition | Lock-then-read RMW is not convertible by future composition alone (findings adversarial #4). |
| Logged batches (W3) | NTR, two+ sequential WRH conditions (`:1663`, `:1712`) | Convertible in principle (three-stage chain) but stacked on batchlog semantics — excluded for PoC minimality; unlogged batches convert. |
| Truncate (M1), schema-version describe (M2) | NTR | Rare ops paths; zero benefit. |
| Auth (M4) | authExecutor (SELECT → R1 park; BCrypt CPU; sync AuthCache load) | Own pool by design; stays. |
| Range reads | NTR (lazy consumption of `RangeCommandIterator`) | Pull-driven cross-shard scan; same async-resolver dependency as SRP/RFP. |
| MV writes, CDC tables, legacy-2i tables | as today (W5 is async at coordinator; local apply hazards are I1's problem) | Consistent with design-target §5's routing-predicate exclusion list; `mutateAtomically` branch puts MV-updating writes behind the line anyway (`StorageProxy.java:1260-1261`). |
| Accord txns (M5) | NTR via `awaitAndGet` (`AccordService.java:1135-1138`) | Underlying core already async; routing `IAccordResult` into the completion plumbing is a later step, not I3 (findings adversarial #10). |

---

## 3. The await inventory with continuation replacements (acceptance artifact)

Every blocking await on the coordinator request path (findings-i2-i3 §4, re-verified
2026-07-09). "RR" = REQUEST_RESPONSE, "IR" = INTERNAL_RESPONSE.

### Reads
| # | Site (verified) | Parks on | Continuation replacement | Cut-line side |
|---|---|---|---|---|
| R1 | `ReadCallback.awaitResults` → `condition.awaitUntil(deadline)` `ReadCallback.java:126` (condition `:71`) | one-time Condition; signalled `:242` (RR), `:281` failure, reaper timeout via IR | `AsyncPromise<ResolveResult>` completed at `:242/:281`; the `:134-214` result-check body (incl. failure snapshot `ImmutableMap.copyOf` `:185`) becomes the completion function, running on the completing thread (executor-agnostic — §1 set); per-request deadline task is the timeout authority (§5) | **CONVERTED** |
| R2 | Speculation window: `SpeculatingReadExecutor.maybeTryAdditionalReplicas` (`AbstractReadExecutor.java:305,321+`) — short await on R1's condition | same condition, sample-latency deadline | One-shot scheduled task at now+threshold on `ScheduledExecutors.scheduledFastTasks`; on fire, if promise incomplete → send data request — **self leg via `execute()`, never mEI (§1 rule; E3)**; cancelled on completion (§5) | **CONVERTED** |
| R3 | `fetchRows` orchestration `StorageProxy.java:2649-2718`: `awaitResponses` `:2691`, `awaitReadRepair` `:2704`; digest-mismatch retry inside `awaitResponses` (`AbstractReadExecutor.java:424-460`: `handler.awaitResults` `:428`, mismatch → `readRepair.startRepair` `:451`) | R1's condition, then R4 | Per-command chain: R1 future `.thenCompose(digest-check)` — mismatch branch starts repair and composes the full-data future (two-stage compose, findings adversarial #3); **the repair read's self leg dispatches via `execute()` — the `AbstractReadRepair.java:102` mEI would otherwise run a full local disk read inline on RR (§1 rule; A2)**; group = `allOf` over commands; terminal materialization dispatched to requestExecutor (§1) | **CONVERTED** (single-partition groups; SERIAL reads excluded by CL predicate, §2) |
| R4 | `BlockingReadRepair.awaitWrites` `:165-174` → `BlockingPartitionRepair.awaitRepairsUntil` `:190-207` (latch `:104`); close-time `concatAndBlockOnRepair` → `ReadRepair::awaitWrites` `StorageProxy.java:2620` | countdown latch per repair set; MUTATION_RSP on RR | **`BlockingPartitionRepair` already extends `AsyncFuture` (`:61-62`)** — compose it directly into the read chain; async deadline = one scheduled task completing it at the `awaitRepairsUntil` deadline (no reaper coverage: `invokeOnFailure` not overridden). The `:2620` close-time await stays as belt-and-braces — it runs during materialization on the park-licensed executor and the future is already complete in the non-repair case | **CONVERTED** (must be — or the read path still parks; acceptance requirement met) |

### Writes
| # | Site | Parks on | Continuation replacement | Cut-line side |
|---|---|---|---|---|
| W1 | `AWRH.get` `condition.await(timeoutNanos)` `AbstractWriteResponseHandler.java:137` (condition `:83`), per-mutation from the `mutate` loop `StorageProxy.java:1004` | one-time Condition; `signal()`→`:359` (RR); reaper via IR (`invokeOnFailure` `:403` = true) | Promise completed at `:359`/failure path; `mutate` becomes `allOf(handlers)` → terminal completion inline on the acking thread — RR for remote acks, MUTATION/NTR for the local leg (§1 executor set); CL.ANY hint-on-timeout moves into the exceptional branch (§5); per-request deadline task covers silent local-leg drops (§5, E1) | **CONVERTED** |
| W2 | Write speculation `maybeTryAdditionalReplicas` `:430-464`, `condition.await(timeout, MICROSECONDS)` `:449` | same condition | Scheduled one-shot, identical shape to R2 — extra local write via `performLocally` with `execute()`, never mEI (§1 rule; E3) | **CONVERTED** |
| W3 | Logged batch: `syncWriteToBatchlog` `handler.get()` `:1663` → `syncWriteBatchedMutations` `wrapper.handler.get()` `:1712`; Accord branch `awaitAndGet` `:1564` | two+ sequential WRH conditions | (three-stage chain when converted, CEP-era) | **BLOCKING behind flag** — NTR (§2) |
| W4 | Counter: `mutateCounter` `:2082`; leader-local `grabCounterLocks` `lock.tryLock` `CounterMutation.java:209` + read under lock; remote-leader forward parks W1 | Striped lock + WRH condition | (end-state: owner-forwarding serializes RMW, design-target D5) | **BLOCKING behind flag** — COUNTER_MUTATION/NTR |
| W5 | MV: `mutateMV` sends async (`asyncWriteBatchedMutations` `:1214`); the view-lock sleep hazard (`Keyspace.java:~516-529`) is replica-apply-side, not a coordinator await | n/a on NTR | No coordinator conversion needed; MV tables behind the line via the `mutateAtomically` branch + routing predicate | **BLOCKING behind flag** (by table) |
| W6 | Hints on CL.ANY timeout: `mutate` catch `:1006` → `hintMutations` `:1010/:1066-1087`; `submitHint` = `Stage.MUTATION.submit` `:3392` (async) | n/a (already async) | Unchanged; invoked from the write future's exceptional branch (§5) | **CONVERTED trivially** (no park to remove) |

### SERIAL (Paxos) — all behind the flag
| # | Site | Parks on | Cut-line side |
|---|---|---|---|
| P1 | v1 `AbstractPaxosCallback.await` (`:53-60`, latch `:44`); callers `preparePaxos` `StorageProxy.java:826`, `proposePaxos` `:862` | CountDownLatch — **no reaper coverage** (invokeOnFailure=false) | **BLOCKING behind flag** — NTR; the latch's own `await(timeout)` remains the ONLY timeout (§5 consequence) |
| P2 | v1 commit `responseHandler.get()` `:923` (+ `expired()` hints `:913-917`) | WRH condition | **BLOCKING behind flag** — NTR |
| P3 | v2 `Paxos.cas/read` `awaitUntil` `Paxos.java:811/:859/:934/:1056/:1092`; `PaxosPrepare.awaitUntil` = synchronized monitor (`PaxosPrepare.java:433`); `PaxosCommit` ConditionAsConsumer (`PaxosCommit.java:134-141`) | monitor/Condition | **BLOCKING behind flag** — NTR |
| P4 | Contention backoff `waitForContention` `Paxos.java:849/:962/:1115/:1164` → `doWaitForContention` sleeping thread (`ContentionStrategy.java:175-183`) | Clock.waitUntil sleep | **BLOCKING behind flag** — NTR (async needs a delayed-retry scheduler; CEP-era) |
| P5 | `PaxosState.lock` `lock.lock(deadline)` `PaxosState.java:428` (coordinator-as-replica) | custom deadline lock | **BLOCKING behind flag** — NTR |

### Misc
| # | Site | Cut-line side |
|---|---|---|
| M1 | Truncate `TruncateResponseHandler.get` from `truncateBlocking` `StorageProxy.java:3084` | **BLOCKING behind flag** — NTR, rare |
| M2 | `describeSchemaVersions` `latch.await` `:2878` | **BLOCKING behind flag** — ops path, NTR |
| M3 | TCM fetch ON RR: `ResponseVerbHandler.maybeFetchLogs` `:89-133` | Not an NTR park — pre-existing RR blocking hazard; guard-exempted + ticketed (§1) |
| M4 | Auth (`Dispatcher.java:81-84`; `PasswordAuthenticator` SELECT→R1, BCrypt, sync AuthCache) | **BLOCKING behind flag** — authExecutor (§7) |
| M5 | Accord `awaitAndGet` (`AccordService.java:1135-1138`) over an async core | **BLOCKING behind flag** — NTR; later step routes `IAccordResult` into the same completion plumbing |
| M6 | ClusterMetadata reads = immutable snapshots | Non-issue (resolved) |
| M7 | **Per-query permission check (added per review C3):** every converted request runs `checkAccess` → role/permission `AuthCache.get` — a sync Caffeine load whose miss runs an internal SELECT → full R1 blocking read, ON THE CALLER | **BLOCKING in the sync prefix** — dispatch thread (NTR/requestExecutor), inside `executeAsync`'s synchronous prefix (§0). Acceptable: park-licensed pool, cache-hit steady state (auth caches are long-TTL), identical to today's placement. Never reachable from a continuation (the prefix precedes the future). |

DECISION: this table is normative for I3's implementation; a park site not listed here
that appears during build gets added here first (the guard in §1 is how it announces
itself).

---

## 4. Back-pressure — DECISION A3

Today (`CQLMessageHandler.java:195-235+`): per-frame on the event loop —
bytes-in-flight vs `native_transport_max_request_data_in_flight[_per_ip]`
(`Config.java:365-367`, `ClientResourceLimits` `:43-45`), optional rate limiter, and
`dispatcher.hasQueueCapacity()` = NTR queue-age vs `native_transport_timeout ×
threshold` (`Dispatcher.java:352-358`). Under I3 the NTR queue never builds — the
queue-age signal (`Overload.QUEUE_TIME`) goes structurally dead; bytes-in-flight
survives (it releases at flush, §8) but bounds only retained request payloads. Nothing
counts outstanding coordinator operations — which is exactly what now holds replica
work in flight.

**DECISION:**
- **Counting unit: BOTH.** Bytes-in-flight machinery kept unchanged (it bounds memory);
  a NEW **outstanding-ops counter** (unit = dispatched-but-not-flushed requests) bounds
  concurrency — ops is the unit because replica RTT slots, callback-map entries, and
  tail latency all scale with op count, not payload bytes (the paper's fig-3
  tail-vs-concurrency curve is per-connection-count; expected-changes §3.6: size from
  that curve). Bytes alone would let millions of tiny ops through.
- **Home and CAS ownership (D1):** `ClientResourceLimits` gains a global
  `ResourceLimits.Concurrent`-style ops counter (mirroring `GLOBAL_LIMIT`,
  `ClientResourceLimits.java:43`) with a per-endpoint sibling. **The event loop owns
  the acquire**: a single atomic `tryAcquire` at the existing per-frame check site in
  `CQLMessageHandler.processRequest` (where bytes-acquire and the old
  `hasQueueCapacity()` call already live) — there is no check-then-act across threads
  because the tryAcquire IS the check, and rejection is decided on the same thread
  that today decides bytes/queue-age overload. `Dispatcher.dispatch` does no
  counting (its shutdown fast-path therefore runs on already-acquired requests —
  covered by the release paths in §8 item 5). **Release in the FlushItem cleanup
  consumer** (§8) — fires exactly once per request, on the event loop, after the
  response is flushed.
- **Resume wiring (D1 — the cited machinery does NOT do this by itself):** the
  existing non-throw QUEUE_TIME path resumes via a **time-delay wakeup**
  (`queueBackpressure.markAndGetDelay` → `scheduleConnectionWakeupTask`,
  `CQLMessageHandler.java:241/:257`) — polling, and prone to oscillation against the
  bytes throttle if the ops limit inherited it. The ops limit instead copies the
  bytes-in-flight pattern: a **release-signalled global WaitQueue** (new ops
  WaitQueue mirroring `GLOBAL_QUEUE`, `ClientResourceLimits.java:44`); the cleanup
  consumer's release signals it from the event loop, paused connections resume
  through the same `AbstractMessageHandler.WaitQueue` mechanics as bytes
  (`AbstractMessageHandler.java:127-162`). Bytes and ops release at the same cleanup
  point, so the two throttles pause/resume coherently (review D3: no structural
  oscillation; ops×max-message-size is bounded above by the bytes limit).
- **Default and relationship to today's brake:** yaml key
  `native_transport_max_in_flight_requests`, default **1024 global; per-IP default
  unset**. Rationale: today's implicit steady-state cap is 128 executing (thread count)
  plus an age-bounded queue — effective in-flight under load is a few hundred; Little's
  law on the rig class (≈200k ops/s × ~1ms) wants ≈200 in flight to saturate, so 1024
  is ~5× headroom without letting the tail curve run away. NOT a constant to trust: the
  I3 A/B sweeps it (this gauge/limit IS I3's micro counter — expected-changes §3.2) and
  the PoC sizes it from the measured concurrency-vs-tail curve.
- **Rejection behavior:** identical dual behavior to bytes-in-flight, reusing the
  existing `Overload` plumbing: client opted `THROW_ON_OVERLOAD` → immediate
  **`OverloadedException`** (wire error code `OVERLOADED` — same exception the shutdown
  fast-path uses, `Dispatcher.java:110-122`) + `ClientMetrics` rejection meter; else
  pause frame delivery via the existing WaitQueue/autoread machinery
  (`AbstractMessageHandler.java:127-162`). No new error code.
- **What replaces `hasQueueCapacity`'s signal:** its call site in
  `CQLMessageHandler.processRequest` switches to the ops-limit check flag-on
  (`Overload.QUEUE_TIME` → the ops-limit reason); flag-off the old signal keeps
  working. `Dispatcher.hasQueueCapacity` itself is not deleted in the PoC — it is
  simply never consulted flag-on.

---

## 5. Timeout, hint, speculation, contention — DECISION A4

**The per-request deadline task is the timeout AUTHORITY; the reaper is backup.**
(Restated per review E1/E4 — the original "reaper as authority, deadline task for
local-only" was a hang+leak.) Two verified facts force this:
1. **Silent local-leg drops never signal the handler (E1, blocker).**
   `DroppableRunnable.run()` returns without any handler callback when dequeued past
   deadline (`StorageProxy.java:3142-3151`), and `LocalMutationRunnable.run()`
   converts to a hint and returns, likewise without signalling (`:3186-3204`). Today
   the parked NTR thread's own `awaitUntil(deadline)` catches this. Under I3, a mixed
   local+remote request (RF=3 CL.ALL; or QUORUM where success comes to require the
   local leg after one remote ack + one remote failure) can have ALL its remote
   callbacks delivered and removed from the callback map — the reaper then holds
   nothing, the local leg never signals, the promise never completes: **no response
   is ever flushed and the §4 ops slot leaks permanently** (a leak is a permanent
   throttle, §8). The deadline task closes this for every shape.
2. **The reaper tick is coarse (E4):** period = `getMinRpcTimeout()/2`
   (`RequestCallbacks.java:70-71`, `defaultExpirationInterval` `:302-306`) — **1s at
   defaults** (min over write 2s) — and `expire()` is a periodic sweep, so
   reaper-delivered timeouts arrive up to a full tick late. Today's parked thread
   throws at the deadline precisely; reaper-as-authority would make client-observed
   timeouts (and CL.ANY hint-then-success) up to ~1s late and hold ops slots longer
   exactly when overloaded.

DECISION: **every converted request arms one one-shot deadline task at
`requestTime.computeDeadline` when its promise is created, cancelled on completion.**
The task fires `tryFailure(timeout)` — precise, shape-independent, and the ops-slot
release rides the normal completion path. Reaper-driven `onFailure(TIMEOUT)`
(`RequestCallbacks.java:149-157`; both hot-path callbacks opt in —
`ReadCallback.java:285`, `AWRH.java:403`) and response-delivered failures remain the
FAST path — usually earlier and carrying per-replica detail — the deadline task is
the guarantee. Exactly-once semantics come free from the §8 promise guard
(`trySuccess`/`tryFailure`). Cost: one schedule+cancel per converted request on
`ScheduledExecutors.scheduledFastTasks`; if cancellation churn shows in profiles, a
wheel timer is the drop-in upgrade (same note as speculation below — and the deadline
tasks must not share a saturated queue with speculation fire, which the wheel also
fixes). Continuations remain **executor-agnostic** (§1 set) — IR delivery is just one
more completing thread.

**Deadline→hint (`LocalMutationRunnable`) — verified `StorageProxy.java:3180-3218`:
UNCHANGED, no continuation form needed.** The deadline check and hint conversion run
inside `run()` at dequeue time on the MUTATION stage (later: shard thread) —
`if (now > deadline)` → self-dropped-message metric → `HintRunnable` via
`submitHint` (`:3392`, async) — entirely independent of whether a coordinator thread
is parked. Same for the wider hint machinery (pinned): CL.ANY `hintMutations` on WTE
(`:1006-1010/:1066-1087`) moves verbatim into the write future's exceptional branch
(it is the CATCH of today's `get()` — under I3 it is the `exceptionally` of the
`allOf`, which **swallows the WTE and completes the response SUCCESSFULLY after hint
submission** — semantics-identical to today: success after hint *submission*, never
awaiting the hint write; review E2 verified); dead-replica hints at send time
(`:1913`) and Paxos-commit hints (`:917`) are pre-park and untouched. No hint logic
depends on the blocking span.

**Speculative retry (R2/W2):** replace the short condition-await with a cancellable
one-shot scheduled task (threshold from the same speculation sampler): on fire, if
the promise is incomplete, send the extra data request / extra-replica write; cancel
on promise completion. **The fire body is non-blocking only for remote candidates —
when the dynamic snitch has ranked self out of the initial contacts, the extra
replica can be SELF, and the self leg goes through the same mEI-inline machinery as
A2 (a full local disk read / mutation apply on the firing thread). Per the §1
no-inline-local-work rule, speculation self legs submit via `execute()`, never mEI
(E3)** — the timer thread never does storage I/O. Cancellation is per-request;
`scheduledFastTasks` is the PoC vehicle — if cancellation churn shows in profiles, a
wheel timer is the drop-in upgrade (netty `HashedWheelTimer` precedent in-tree). The
speculation-rate metrics hooks move with the task.

**CASContention (P4) + the Paxos-v1 latch consequence (stated, per the acceptance):**
`waitForContention`'s sleeping backoff and v1's latches stay blocking. **Because
`AbstractPaxosCallback.invokeOnFailure()` is default-false, v1 has NO async timeout
delivery — there is nothing to hang a continuation's exceptional completion on without
building new per-round timer machinery. That fact alone forces all of Paxos behind the
cut-line**; it is not an optimization choice. The v1-reaper opt-in (or v2-only
conversion with a delayed-retry scheduler for P4) is CEP-era work, sequenced with
design-target D5's per-round owner-forwarding end-state.

DECISION: the per-request deadline task is the timeout authority for converted paths
(reaper + `invokeOnFailure` and response delivery are the fast path); hints and
deadline→hint logic move location (into completion functions) but not form; CL.ANY
completes successfully after hint submission; speculation/repair self legs obey the
no-inline rule; Paxos stays blocking for want of async timeout coverage.

---

## 6. ExecutorLocals and observability — DECISION A5

Pinned discovery (findings adversarial #1, re-verified): ClientWarn/Tracing live in
`ExecutorLocals` (`ExecutorLocals.java:56-57`, `current()` `:95`, `create` `:119`);
SEP submission propagates them, but `ResponseVerbHandler` runs with the **replica
message's** locals (`InboundMessageHandler.java:429`) — the originating request's
state is NOT present on RR. `ClientWarn.State` is thread-shareable
(CopyOnWriteArrayList, `ClientWarn.java:102`); `MessageParams` is a separate
FastThreadLocal (`db/MessageParams.java:32`).

**DECISION — the capture point is the request thread, immediately after
`ClientWarn.instance.captureWarnings()` (`Dispatcher.java:381`) and tracing setup,
inside the async `processRequest` variant:** a per-request `ContinuationContext`
{`ExecutorLocals.current()`, `MessageParams` snapshot, `RequestTime`, the
`FlushItemConverter`, channel} is created there and travels with the promise. That is
the earliest point at which all request locals exist and the last point the request
thread is guaranteed to be current. The completion helper (one static, used by success
AND error AND timeout paths) does:
1. install captured locals (`ExecutorLocals.Impl.set` shape, `:49`) + MessageParams;
2. run the relocated Dispatcher logic — warnings attach (`response.setWarnings`
   `:438`, error path `:466`), `ErrorMessage.fromException` conversion (`:447-475`);
3. record client-latency metrics + `QueryEvents.notifyQuerySuccess/Failure`
   (today NTR-side at `QueryMessage.java:114/:119/:128`; `notifyBatchSuccess` for the
   BatchMessage override) — `RequestTime` is immutable and carries
   `enqueuedAtNanos/startedAtNanos` (`Dispatcher.java:135-160` verified), so
   end-to-end latency stays computable on any thread; without this move, audit/FQL
   would report dispatch-only latency (findings adversarial #11). **When QueryEvents
   listeners are registered (audit/FQL), the whole helper runs on requestExecutor —
   the §1 A1 offload — because listeners terminate in `BinLog.put`'s blocking
   queue**;
4. `toFlushItem` + `flush` (`Dispatcher.java:483-485` relocated), with the
   catch-around-encode obligation (§8 item 5);
5. finally: reset locals (`ClientWarn.instance.resetWarnings()` — today's `:473` —
   plus MessageParams reset) so the borrowed RR/IR/NTR worker leaks nothing —
   strictly scoped, making the helper safe for synchronous (reentrant) completion
   under the dispatch stack (§1).

**Coordinator-level metrics relocate too (C4):** the `mutate` catch/finally block —
`writeMetrics.addNano`, per-CL marks, timeout/failure/unavailable marks,
`updateCoordinatorWriteLatencyTableMetric` (`StorageProxy.java:1040-1054`, verified)
— and the read-side equivalents live inside today's blocking span. They move into
the StorageProxy-level completion functions (the `allOf` terminal / `exceptionally`
branches of §3 W1/R3), NOT the Dispatcher helper — they are coordinator metrics, not
transport metrics, and must fire for converted traffic or ClientRequestMetrics
silently stops covering it.

Trace writes from continuations come from the captured `traceState` (already passed
explicitly on the read path, `AbstractReadExecutor.java:94` pattern — safe).
Monitoring/slow-query is migration-safe (MonitorableImpl uses approxTime, no thread
capture — findings adversarial #8).

---

## 7. Extension points — DECISION A6 (coordinator-side containment)

Contract inherited from design-target §3 item 3 (SAI certified non-blocking with its
stays-shared structures; `CassandraIndex` NOT certified → predicate-excluded tables;
triggers/custom-indexers → UserCodeEscape pool; custom auth stays on authExecutor).
This section answers the coordinator-side question: which awaits can a custom
extension introduce ON the request path, and what contains each.

| Extension point | Await it can introduce on the request path | Containment |
|---|---|---|
| `IAuthenticator`/`IAuthorizer`/`IRoleManager` (arbitrary: LDAP, HTTP...) | Blocking network/IO during SASL exchange; internal SELECTs (→ R1 park); sync `AuthCache` Caffeine loads on the caller | **authExecutor, unchanged** (`Dispatcher.java:81-84`; default 4 threads, `Config.java:356`; <1 → falls back to requestExecutor — a park-licensed pool either way). Auth messages never take the async path (M4 behind the line); the dispatch-time executor select (`:125-129`) precedes any continuation, so custom auth code can never reach RR. |
| Triggers (`ITrigger`, arbitrary user code) | Anything — runs coordinator-side pre-apply via `TriggerExecutor` augment inside `mutateWithTriggers` (`StorageProxy.java:~1250-1260`) | Runs on the **dispatch thread (NTR) BEFORE the write future is created** — trigger blocking parks NTR exactly as today, never a continuation. Flag-on unchanged; end-state = UserCodeEscape pool (design-target). Note: trigger-augmented mutations divert to `mutateAtomically` (`:1260-1261`) → behind the line entirely. |
| Custom `QueryHandler` (`-Dcassandra.custom_query_handler_class`) | Fully synchronous `Response` construction — anything | The async path exists only in the stock messages' `executeAsync` overrides (§0); a custom QueryHandler keeps today's synchronous NTR execution byte-for-byte. Contract line for the CEP: QueryHandler gains an OPTIONAL async interface later; sync implementations remain valid and park-licensed. |
| UDF/UDA | CPU (sandboxed Java) during row evaluation | Executes during result materialization — which A1 places on the park-licensed requestExecutor, never RR. UDF's own executor/threads config (`Config.java:738-739` area) unchanged. |
| Custom `Index.Indexer` / `Index.searcher` | Coordinator-side: index SEARCHES are replica-side (out of I3 scope — I2/I5 territory); coordinator sees only normal R1/W1 awaits | No coordinator-side containment needed beyond the cut-line; apply-side containment is design-target's (predicate exclusion for non-certified indexes + UserCodeEscape). |
| Custom `ConfigurationLoader`/seed providers etc. | Startup-only | Not on the request path. |

DECISION: no extension point can place user code on a continuation thread — auth by
executor selection, triggers by running pre-future on the dispatch thread, custom
QueryHandlers by never entering the async path, UDFs by materialization placement.
The §1 park-site guard is the backstop for anything this table missed.

---

## 8. FlushItem / payload-release audit — DECISION A7 (the pre-code verification, done)

Audited 2026-07-09: `Flusher.java` (whole file), `CQLMessageHandler.toFlushItem/cleanup`
(`:481-512`), `PreV5Handlers.LegacyDispatchHandler.toFlushItem/releaseItem`
(`:100-140`), `Dispatcher.flush` (`:488-501`).

**Finding: SAFE AS-IS for thread-migration — with one obligation I3 step 0 must
implement (exactly-once completion), and no pre-existing bug found.**

1. **Enqueue side (the part that changes thread):** `Flusher.enqueue` is a
   ConcurrentLinkedQueue add (`Flusher.java:118,137-140`) + CAS-guarded event-loop
   schedule (`:124-129`); `Dispatcher.flush`'s flusher lookup is a ConcurrentHashMap
   with putIfAbsent races handled (`:86,:491-497`). Fully thread-safe from any
   completing thread. ✓
2. **`toFlushItem` moves to the completing thread:** the V5 converter
   (`CQLMessageHandler.java:481-498`) does `response.encode(...)` — buffer from
   `CBUtil.allocator` (netty pooled, thread-safe; `Message.java:381/:406`,
   `Envelope.java:92`) — plus codahale size metrics. No handler mutable state touched.
   The pre-V5 converter allocates nothing. Safe off-NTR. ✓
3. **Release side does NOT change thread — the load-bearing observation:** cleanup
   consumers run inside `flushWrittenChannels` → `item.release()` (`Flusher.java:268`),
   and the Flusher Runnable ONLY ever executes on the channel's event loop (`:128`
   execute, `:373` schedule). So `CQLMessageHandler.cleanup` (`:501-512`) — which
   mutates the **plain, unsynchronized** `channelPayloadBytesInFlight` and returns
   reserve capacity — and pre-V5 `releaseItem` — which additionally toggles autoread —
   remain event-loop-confined under I3 exactly as today. The plain-field accounting
   that looks race-prone is safe because the producer side of those fields is also the
   event loop (frame processing). No fix needed. ✓
4. **THE obligation (I3 step 0): exactly-once FlushItem production per request.**
   Everything hangs off `cleanup`: request-envelope release
   (`flushItem.request.release()` `:504`), response release (`:505`), bytes-in-flight
   return (`:510`), and — per §4 — the new ops-counter release. Today the synchronous
   path structurally guarantees exactly one response per request (the
   try/catch/finally in `processRequest`). Under I3 the guarantee must come from the
   promise: **completion goes through `trySuccess`/`tryFailure` (AsyncPromise
   semantics) so a response-then-late-timeout race completes once**; a
   double-completion would double-release capacity (accounting corruption), a
   never-completion leaks capacity and can wedge a paused connection's autoread. The
   completion helper is the single funnel (success, error, timeout, §5 deadline task)
   and carries a test-mode assert counting FlushItems per streamId. The pre-dispatch
   error release path (`CQLMessageHandler.java:402`) fires only before dispatch — no
   overlap. ✓
5. **Leak paths beyond the happy path and their coverage (D2).** The ops slot (§4)
   and request envelope must be released even when no FlushItem is ever produced.
   Mechanism: the slot release is **idempotent per request** — a CAS-once `release()`
   on the per-request slot handle, normally invoked by the FlushItem cleanup
   consumer, invocable directly on paths that die before a FlushItem exists.
   Covered paths:
   - **Post-acquire `executor.submit` rejection** (RejectedExecutionException in the
     shutdown window — acquire happens on the event loop BEFORE dispatch, §4, and
     the `Dispatcher.dispatch` shutdown fast-path (`:110-122`) runs on
     already-acquired requests): catch at the dispatch site → direct slot release +
     inline error response (the fast-path's own FlushItem shape). Shutdown-bounded.
   - **`toFlushItem`/`response.encode` throwing ON the completing thread** (not
     shutdown-bounded — §8 item 2 moved encode there; today NTR's try/catch produced
     an error response): the completion helper wraps toFlushItem in a catch →
     builds an error FlushItem via `ErrorMessage.fromException`; if THAT throws,
     direct slot release + channel close (connection-fatal, matches existing
     protocol-error handling). Stated as a helper obligation, §6 step 4.
   - **Event-loop rejection at `Flusher.start()` during shutdown** (enqueued item
     never drained): accepted-risk, shutdown-bounded — the connection is closing and
     per-endpoint accounting dies with it; noted, not engineered around.

DECISION: no code fix precedes I3; the audit converts into three build requirements —
promise-guarded exactly-once completion; ops-counter release inside the existing
cleanup consumers (§4) so it inherits the same exactly-once guarantee; and the
idempotent slot handle + catch-around-encode covering the D2 leak paths.

---

## 9. Buildability summary

Order of work (I3 within phase-4's increment frame):
1. **Step 0:** completion-helper skeleton (reentrancy-safe, listener-offload branch,
   catch-around-encode) + exactly-once promise guard + idempotent slot handle (§8);
   `NonBlockingStage.assertLegalPark()` guard + full park-site insertion (§1); ops
   counter/limit + release-signalled WaitQueue in ClientResourceLimits, event-loop
   tryAcquire + cleanup release (§4); per-request deadline-task machinery (§5). All
   flag-independent scaffolding, testable alone.
2. Handler promises: `ReadCallback` + `AWRH` grow AsyncPromises completed at the
   existing signal sites; blocking methods retained for behind-line callers (§0, §3).
3. `Message.Request.executeAsync` + statement-and-CL predicate (§2) on
   QueryMessage/ExecuteMessage/BatchMessage + `Dispatcher` RequestProcessor async
   branch + ContinuationContext capture (§6).
4. Read chain: `fetchRows` composition, digest-retry compose (self leg via
   `execute()`, §1 rule), R4 AsyncFuture composition, materialization dispatch to
   requestExecutor (§1, §3).
5. Write chain: `mutate` allOf + CL.ANY hint exceptional branch + speculation timers
   (self legs via `execute()`) + coordinator-metrics relocation (§3, §5, §6).

Micro counters (the increment's evidence, per expected-changes §3.4): outstanding-ops
gauge + rejection meter (§4); NTR pool size/utilization flag-on vs flag-off;
**REQUEST_RESPONSE health — PendingTasks + oldest-task-age on the RR SEP executor,
first-class in every I3 A/B cell (A4): RR also delivers the responses that unblock
everything still parked behind the line (PAXOS_*_RSP, batch store/remove, truncate,
hint, repair RSPs — `Verb.java:201-286`), so completion work added to RR at
saturation delays exactly those deliveries — this gauge is what falsifies or
confirms the §1 bet**; park-guard violation counter (expected: zero);
FlushItems-per-request assert (test mode). Macro: the standard A/B per the harness
pins, plus one cell that shrinks `native_transport_max_threads` flag-on — the shrink
is the measurable claim, not the default (§2).

Ceiling, stated for reviewers: I3 removes the coordinator's parked-RTT thread cost; it
does not make result materialization asynchronous (pull-based resolver stays), does
not touch Paxos/counters/batches (cut-line, §2), and completes pre-TPC on
RR/requestExecutor — under full TPC the same promises complete into shard inboxes
(design-target §8.2 hop 7), which is a completion-executor swap, not a redesign.
(F2: hop 7's "becomes the continuation-completion site" evolves under this doc into
the split terminal — writes inline on the acking thread, read materialization on
requestExecutor, one new RR→requestExecutor hand-off design-target's §8.2 table
doesn't show; design-target should absorb this one-line evolution at its next
amendment rather than leave the seam for CEP reviewers to find.)
