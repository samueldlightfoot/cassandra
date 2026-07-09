# Adversarial review — design-async-coordinator.md (2026-07-09)

Reviewer scope: spec.md §2.1/§3.2 acceptance, expected-changes.md §3.2/§3.6 pins,
findings-i2-i3 raw inventory, design-target.md D1–D7. All findings below re-verified
against the working tree (file:line cited). Tags: [BLOCKER] = invalidates a stated
claim or produces a hang/leak; [GAP] = missing coverage the implementing agent would
hit; [NIT] = wording/consistency.

---

## A. Strict-REQUEST_RESPONSE under load

**A1 [BLOCKER] — QueryEvents relocation puts FQL/audit BinLog appends on
REQUEST_RESPONSE.** §6 step 3 moves `QueryEvents.notifyQuerySuccess/Failure` into the
completion helper, which for writes runs on RR (§1). QueryEvents listeners run
synchronously in `notify*` (`QueryEvents.java:67-85`); `AuditLogManager.querySuccess`
(`AuditLogManager.java:262`) and FQL both terminate in `BinLog.logRecord`
(`BinLog.java:309-323`): **`blocking` defaults to true (`BinLogOptions.java:44`) and
`put()` loops `sampleQueue.offer(record, 1, SECONDS)` (`BinLog.java:222-232`) — an
unbounded park on an ArrayBlockingQueue** whenever the Chronicle appender falls behind,
plus mmap'd Chronicle writes (page-fault I/O) even when not blocking. With audit/FQL
enabled, all P RR threads can wedge for seconds — exactly the starvation the doc's own
rule forbids — and this park is at a site the §1 guard never touches (queue put, not an
inventoried primitive). Fix: FQL/audit-enabled requests dispatch their completion (or
at least the notify step) to requestExecutor, or the guard-exemption/offload is stated
explicitly.

**A2 [BLOCKER] — the digest-mismatch continuation transitively performs a full local
disk read on RR.** §1 certifies read protocol steps ("mismatch retry ... only send
messages and compose futures. Non-blocking"). False for the local-replica leg:
`readRepair.startRepair` → `AbstractReadRepair.sendReadCommand`, whose self branch is
`Stage.READ.maybeExecuteImmediately(new LocalReadRunnable(...))`
(`AbstractReadRepair.java:100-102`). When a READ permit is free, mEI runs the runnable
**inline on the calling thread** (`SEPExecutor.maybeExecuteImmediately`) — i.e. the RR
thread executes `executeLocally` end-to-end: ChunkCache miss loads synchronously on the
caller (Caffeine ImmediateExecutor, `ChunkCache.java:152`), mmap index page faults
under default `mmap_index_only`, device-latency pread. Same shape for the R3 mismatch
branch generally (it runs where the last digest response landed = RR). Not a
park-primitive, so the §1 guard is blind to it. Fix: the mismatch/repair-read local
branch must `execute` (never mEI) when on a non-park-licensed thread, or the mismatch
branch dispatches to requestExecutor like materialization.

**A3 [GAP] — "Writes complete entirely on REQUEST_RESPONSE" is factually wrong for
local acks, and the continuation-executor set is understated.** `performLocally`
invokes `handler.onResponse(null)` on the **MUTATION-stage thread** — or inline on the
NTR thread via mEI (`StorageProxy.java:2023-2066`) — and `LocalReadRunnable` calls
`handler.response(...)` on its executing thread (READ stage / shard / I/O pool / NTR).
Whenever the local leg is the completing ack (common: coordinator-is-replica, CL.ONE /
last-arriving quorum ack local), the write terminal (encode + flush enqueue + metrics +
QueryEvents) runs on MUTATION/NTR, not RR — and the promise can complete
**synchronously during dispatch** (addCallback fires inline on NTR while executeAsync
is still on the stack). The doc's "stage-agnostic between RR and IR" (§1, §5) must
become "executor-agnostic across RR/IR/MUTATION/READ/shard/I-O-pool/NTR", the §1 guard
thread-set reasoning re-checked against that set, and the completion helper stated
reentrancy-safe for synchronous completion. (Also feeds F1.)

**A4 [GAP] — starvation risk carries no measurement.** §9's micro counters (ops gauge,
rejection meter, NTR size, guard violations, FlushItem assert) include **no
REQUEST_RESPONSE health observable** (PendingTasks / oldest-task-age on RR). RR also
delivers responses for everything behind the line — Paxos (PAXOS_*_RSP), batch
store/remove, truncate, hint, repair RSP (`Verb.java:201-286`) — whose parked NTR
waiters depend on RR liveness; write-completion CPU on RR at saturation delays exactly
those deliveries. The A/B needs the RR gauge to falsify/confirm the doc's central bet.

**A5 — Flusher claim VERIFIED, no finding.** Both `LegacyFlusher` and
`ImmediateFlusher` run only via `eventLoop.execute` (`Flusher.java:128`) /
`eventLoop.schedule` (`:373`); `item.release()` at `:268` is inside
`flushWrittenChannels` inside `run()`. Enqueue is CLQ + CAS-schedule from any thread.
The §8 item-3 claim holds for both flusher variants.

---

## B. The park guard

**B1 [GAP] — the insertion list contradicts its own "every park primitive in the §3
inventory" claim.** §1 lists 7 sites. Missing from the list but present in §3:
`PaxosPrepare.awaitUntil` synchronized monitor (`PaxosPrepare.java:433`, P3),
`PaxosCommit` ConditionAsConsumer (`PaxosCommit.java:134-141`, P3), the `Paxos.java`
awaitUntil callers (P3), `PaxosState.lock` deadline lock (`PaxosState.java:428`, P5),
`describeSchemaVersions` latch (`StorageProxy.java:2878`, M2), and Accord's
`awaitAndGet` (`AccordService.java:1135-1138`, M5). Behind-the-line paths are exactly
where a future mis-drawn cut-line would park a continuation thread — the guard's whole
point. Enumerate all of them or re-scope the claim.

**B2 [GAP] — state what the guard is structurally blind to.** The guard catches
inventoried park primitives only. It cannot see: mmap page faults (index reads under
default `mmap_index_only` — see A2), synchronous ChunkCache miss loads
(`ChunkCache.java:152`), Caffeine sync loads generally (AuthCache), JNI/native calls,
`BinLog.put`'s ArrayBlockingQueue park (A1), `MemtableAllocator` hasRoom WaitQueue
park, commitlog `awaitAvailableSegment`, and the MV `Thread.sleep(10)` loop. The doc
sells the guard as "a contributor who blocks a continuation gets an immediate red
test" — overclaimed; add a stated blind-spot list so reviewers don't rely on it for
these classes.

**B3 — transitively-blocking continuations found (rollup):** (i) digest-mismatch →
`startRepair` → inline local disk read on RR (A2 — the doc schedules this on RR);
(ii) speculative retry firing thread doing local I/O (E3); (iii) QueryEvents →
BinLog park (A1). No blocking found in: R4 composition (repair mutations to self go
via `sendRR` → messaging self-delivery on Stage.MUTATION, `BlockingPartitionRepair.java:156/:176`
— not inline), hint machinery (verified async, `submitHint` = `Stage.MUTATION.submit`),
W1 terminal for remote acks.

---

## C. Cut-line completeness

**C1 [GAP] — BatchMessage contradiction.** §2 converts "unlogged batches of plain
mutations", but §0 gives `executeAsync` overrides to **QueryMessage/ExecuteMessage
only**. Driver-sent batches arrive as `BatchMessage` — they fall through to the sync
default and never convert, contradicting §2 (or BatchMessage needs the override plus
its own QueryEvents/metrics relocation — `notifyBatchSuccess` is a separate event).
Pick one and state it.

**C2 [GAP] — SERIAL/LOCAL_SERIAL reads pass a statement-shape predicate.** The §2
predicate is stated as statement-level ("single-partition reads"). A single-partition
SELECT at SERIAL goes `readWithPaxos` → P3 monitor parks. The predicate must consult
per-request `QueryOptions.getConsistency()` (and `serialConsistency` for writes is
irrelevant — conditions are statement-level). Unstated; an implementing agent
following §0 literally converts SERIAL reads into RR-parking continuations.

**C3 [GAP] — per-query permission checks are a request-path park the inventory
omits.** M4 covers auth *messages* (SASL) on authExecutor. But every converted request
runs `checkAccess` → role/permission `AuthCache.get` sync Caffeine load → internal
SELECT → R1 blocking read — on the **requestExecutor** thread, inside `executeAsync`'s
synchronous prefix. Safe only because NTR keeps its park license — but the doc never
defines where the sync prefix ends (parse/prepare/checkAccess/guardrails on NTR,
dispatch after), and the "every blocking await on the request path" claim misses this
site. State the prefix boundary and add the row.

**C4 [NIT] — coordinator-level metrics relocation unstated.** `mutate`'s catch/finally
block (`writeMetrics.addNano`, timeout/failure/unavailable marks,
`updateCoordinatorWriteLatencyTableMetric`, `StorageProxy.java:1040-1054`) and the read
equivalents live in the blocking span; §6 relocates only Dispatcher-level
metrics/QueryEvents. The completion functions need a stated home for these or
coordinator metrics silently stop covering converted traffic.

**C5 — checked, no finding:** truncate/describe (M1/M2 behind line); conditional
batches excluded by "non-conditional" (cas() unreachable flag-on); counter statements
excluded by "non-counter" (W4); MV-bearing tables via `mutateAtomically` branch +
predicate (W5); aggregation/range excluded explicitly (§2 clarification 1); paging =
one request per page, no cross-page await; prepared-statement cache is non-blocking
(miss → unprepared error); CL.ANY covered (E2).

---

## D. Back-pressure

**D1 [GAP] — resume wiring for the ops limit is unstated and the machinery cited
doesn't do what the doc assumes.** §4 says non-throw overload "pauses frame delivery
via the existing WaitQueue/autoread machinery". Verified: the existing QUEUE_TIME
non-throw path resumes via a **time-delay wakeup** (`queueBackpressure.markAndGetDelay`
→ `scheduleConnectionWakeupTask`, `CQLMessageHandler.java:240-263`), while
bytes-in-flight resumes via **release-signalled WaitQueue**. A counted ops limit needs
release→signal wiring from the FlushItem cleanup consumer (event loop) to the paused
connections — new code §4 never specifies — or it inherits the delay-based wakeup
(polling; oscillation with the bytes throttle when both trip). Also unpinned: the
check lives in `CQLMessageHandler.processRequest` (event loop) but the acquire in
`Dispatcher.dispatch` — check-then-act across threads means either double-counting or
overshoot beyond 1024; say which side owns the CAS.

**D2 [GAP] — exactly-once release has uncovered leak paths.** §8's audit covers the
happy path and the response/timeout race (trySuccess). Not covered: (i)
`executor.submit` throwing RejectedExecutionException after acquire (shutdown window)
— acquired, no FlushItem, permanent slot leak; (ii) `toFlushItem`/`response.encode`
throwing **on the completing thread** (§8 moves encode there) — today's NTR try/catch
produced an error response; the doc must state a catch-around-toFlushItem →
error-FlushItem obligation or the slot and the request envelope leak; (iii) event-loop
rejection at `Flusher.start()` during shutdown — enqueued items never released.
(i)/(iii) are shutdown-bounded, (ii) is not. The shutdown fast-path
(`Dispatcher.java:110-122`) does produce a FlushItem — fine if acquire precedes it,
but then (i) applies; order acquire after the fast-path and state it.

**D3 — double-throttle check, no structural oscillation found:** bytes and ops release
at the same cleanup point, so the two limits pause/resume coherently provided D1's
signal wiring exists; 1024 ops × max message size is bounded above by the existing
bytes limit, so ops-limit doesn't extend memory exposure.

---

## E. Timeout story

**E1 [BLOCKER] — a converted request whose local leg is silently dropped can NEVER
complete: response hang + permanent ops-slot leak.** Verified:
`DroppableRunnable.run()` (LocalReadRunnable base) and `LocalMutationRunnable.run()`
both **return without signalling the handler** when dequeued past deadline
(`StorageProxy.java:3142-3151` read-drop; `:3186-3204` write-drop→hint). Today the
parked NTR thread's own `awaitUntil(deadline)` catches this. Under I3, §5's deadline
task covers **local-only** requests ("no remote callback registered") — but a mixed
local+remote request (RF=3 CL.ALL; or QUORUM with one remote ack + one remote failure,
success now requiring the local leg) has all its remote callbacks delivered and removed
from the callback map, the reaper holds nothing, the local leg never signals → the
promise never completes, no response is flushed, and §4's in-flight slot leaks forever
(leak = permanent throttle, per §8's own analysis). Fix is small and also fixes E4:
the one-shot deadline task must arm on **every converted request**, cancelled on
completion — not only local-only ones.

**E2 — CL.ANY semantics VERIFIED preserved.** Today's CL.ANY catch calls
`hintMutations` → `submitHint` = `Stage.MUTATION.submit` **fire-and-forget**
(`StorageProxy.java:1006-1010, :1066-1087`) and returns success without awaiting the
hint write. The doc's exceptional-branch move is semantics-identical (success after
hint *submission*, both before and after). No finding — but note the exceptional
branch must swallow the WTE and complete the response successfully for CL.ANY; §5
implies it, one clarifying line wouldn't hurt.

**E3 [GAP] — the speculation task's "a message send — non-blocking" claim is false
when the uncontacted candidate is self.** `sendReadCommand`/`makeRequests` self branch
is mEI-inline (A2's mechanism); `maybeTryAdditionalReplicas` write speculation to a
local extra replica goes `performLocally` → mEI. Self is usually contacted first, but
the dynamic snitch can rank self out of the initial contacts — then the R2/W2 task
executes a **full local disk read / mutation apply incl. commitlog add** inline on
`ScheduledExecutors.scheduledFastTasks`, a shared scheduler that §5 also makes carry
the deadline tasks (whose lateness then compounds E1). Route the self-leg via
`execute`, or dispatch speculation fire to requestExecutor.

**E4 [GAP] — reaper tick coarseness unstated: converted-path timeouts arrive up to
~1s late.** Verified: reaper period = `getMinRpcTimeout()/2`
(`RequestCallbacks.java:70-71, :302-306`; min over read 5s/range 10s/write 2s/counter
5s/truncate 60s ⇒ 2s/2 = **1s** at defaults), and `expire()` is a periodic sweep —
`onExpired` fires at the first tick *after* the deadline. Today the parked thread
throws at the deadline precisely; under I3 "reaper as the timeout authority" makes
client-observed timeout exceptions and CL.ANY hint-then-success up to a full tick
late, and holds §4 ops slots longer exactly when overloaded. invokeOnFailure coverage
itself VERIFIED (`ReadCallback.java:285`, `AWRH.java:403`). Fix folds into E1's
per-request deadline task (which then makes reaper delivery the backup, not the
authority — restate the DECISION accordingly).

---

## F. Cross-doc consistency

**F1 [GAP] — I/O-pool reschedule × continuation executor: composes, but only under
A3's correction.** design-target §3 item 5: a shard-side miss resubmits the whole read
task to the I/O pool; the pool thread then calls `handler.response` → R1's promise
completes **on the I/O pool thread** and the digest-check continuation runs there
(same-thread compose). Functionally fine — but the async doc nowhere admits pool/shard
threads as continuation executors (§1 says RR/IR), and cheap continuations on pool
threads occupy slots sized for device reads (§3-item-5 sizing assumed blocking reads
only). One paragraph stating the composition and its cost closes this.

**F2 [NIT] — design-target hop 7 says completion happens at the RR callback site
("becomes the continuation-completion site"); this doc splits terminals (writes RR,
read materialization NTR) and adds a new RR→requestExecutor hand-off that hop table
8.2 doesn't show.** Not a contradiction — an evolution — but sync the hop table or CEP
reviewers will find the seam.

**F3 — checked, consistent:** hints fallback-covered (D5 row) matches §5 verbatim-move;
CL.ANY branch consistent (E2); D4's five routing points untouched (materialization
dispatch and timer tasks are not token-routing points); cut-line exclusion list matches
design-target §5's predicate list (views/CDC/legacy-2i/counters/paxos/batchlog/range);
Paxos-behind-flag matches D5's "excluded from I3" line; NTR-shrink-as-A/B-cell matches
expected-changes §3.2/open-question 6.

---

## G. Acceptance audit (spec §3.2)

| Acceptance item | Verdict |
|---|---|
| Call-site inventory of every blocking await on the request path, continuation replacement sketched | **MET with holes** — §3 carries the full findings inventory (R1-R4/W1-W6/P1-P5/M1-M6, verified complete vs findings-i2-i3 §4, plus line-drift corrections and the AsyncFuture discovery). Holes: C3 (per-query AuthCache park not inventoried) and A1 (the design itself *creates* a new request-path blocking site by relocating QueryEvents). |
| Timeout/hint paths, speculative retry, CASContention covered | **MET on paper, E1/E3/E4 falsify parts** — hint paths verified sound; timeout authority and speculation claims have the defects above. |
| Back-pressure story stated | **MET** (§4) with D1/D2 wiring gaps. |
| Extension-point inventory + contract + escape-pool story | **MET** (§7 table; escape pool via design-target §3 item 3 — containment argument is sound: auth by executor selection verified at `Dispatcher.java:125-129`, triggers pre-future, QueryHandler never enters async path, UDF on requestExecutor). |

---

## VERDICT: **PASS-WITH-FIXES**

The conversion primitive, cut-line, and back-pressure home survive review; the three
blockers are local amendments, not redesigns — but all three must land in the doc
before I3 build starts:

1. **[BLOCKER E1]** Deadline task per converted request (not local-only) — silent
   local-leg drops (`DroppableRunnable`/`LocalMutationRunnable`) otherwise hang the
   response and permanently leak §4 ops slots. Restates §5's DECISION (reaper becomes
   backup, task becomes authority — also resolves E4's 1s-late timeouts).
2. **[BLOCKER A1]** FQL/audit: `BinLog.put` (block=true default) via relocated
   QueryEvents parks RR threads — offload notify (or the whole completion) to
   requestExecutor when audit/FQL listeners are registered.
3. **[BLOCKER A2]** Digest-mismatch repair's local read runs inline on RR via
   `AbstractReadRepair.java:102` mEI — the mismatch branch must dispatch off RR (or
   the self-leg must `execute`, never mEI, from non-park-licensed threads).

GAPs to fix in the same pass: A3+F1 (executor set is RR/IR/MUTATION/READ/shard/pool/NTR
— restate §1's claim, guard set, reentrancy), A4 (RR health gauge in §9), B1 (guard
list omits P3/P5/M2/M5), B2 (guard blind-spot list), C1 (BatchMessage override),
C2 (CL-aware predicate for SERIAL reads), C3 (sync-prefix boundary + AuthCache row),
D1 (ops-limit release→signal wiring + acquire/check ownership), D2 (leak paths:
post-acquire rejection, encode-throw on completing thread), E3 (speculation self-leg).
NITs: C4, F2.
