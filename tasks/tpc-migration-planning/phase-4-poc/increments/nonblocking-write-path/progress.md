# Progress — non-blocking TPC write path

## Phase 1 handoff (START of implementation) — 2026-07-12

**No source written yet** — git diff at handoff = planning docs only (`findings.md`,
`task_plan.md`, `../i1-single-node-perf/{findings,progress}.md`, `tasks/lessons.md`). This
is a start-of-phase handoff, not a post-build one. Sections 1/3 are therefore thin.

**(1) Deviations from plan:** none yet.

**(2) Interfaces Phase 1 will modify** (verified at HEAD, copied — these are current, not
as-built):
- `AbstractWriteResponseHandler.java:130` `public void get() throws WriteTimeoutException,
  WriteFailureException, RetryOnDifferentSystemException` — the blocking consume; `:137`
  `condition.await(timeoutNanos, NANOSECONDS)`; `condition` = `newOneTimeCondition()` (`:83`,
  a `Condition.Async`). `signal()` at `:346` **already runs an optional `callback`** (`:360-361`,
  field `:86`, ctor arg `:120-124`) — that callback hook is the async seam.
- `StorageProxy.java:1007` `responseHandler.get()` — the CQL-write `mutate` loop (the park).
  Other `get()` sites to KEEP working via a sync shim: `:926` (paxos), `:1666`/`:1715`
  (batchlog), `:3104`.
- Routing branch `StorageProxy.java:2074-2085`:
  `ShardExecutors shards = MutationShardRouting.ROUTING_ENABLED && description instanceof Mutation
  ? ShardExecutors.instance() : null;` … `stage.maybeExecuteImmediately(localMutationRunnable)`.
  Routing-ON → apply to `Shard-N`, worker parks in `get()`. Routing-OFF → inline, no park.
- `maybeTryAdditionalReplicas` has its own `condition.await` at `AbstractWriteResponseHandler.java:449`.
- Response flush is ALREADY async — `Dispatcher.flush` → `Flusher` on the netty event loop
  (`voidPromise`). Drive it from the completion callback; do not change flush itself.

**(3) Tested/stubbed/deferred:** nothing built. Phases 2 (commitlog-as-future) and 3
(memtable async backpressure) deferred. Kept memtable lock NOT touched (correct).

**(4) Decisions + rationale:**
- Async medium = **plain Netty-style Futures** — in-package `AsyncPromise`/`AsyncFuture`
  (they `extend io.netty…Future/Promise`). **No RxJava/Reactive Streams, no JDK
  `CompletableFuture`.** Verified; see `task_plan.md` "Design constraint" + memory
  `feedback_async_medium_netty_futures`. Treat as given.
- Fix 1 (coordinator) first: it's the profiled +28 pp rendezvous and pure coordination
  (no async-I/O). Fix 2 next. Multi-node perf gated until after Fix 1.
- Keep `get()` as a `future.sync()` shim for unconverted callers (batchlog/paxos).

**(5) Gotchas:**
- Multiple `get()` sites (above) — don't break batchlog/paxos while converting the CQL write.
- CQL **synchronous `ResultMessage` contract**: the async result must still yield a
  `ResultMessage`; the chain `ModificationStatement.execute → Message.Request.execute →
  Dispatcher.processRequest` currently returns synchronously.
- **Timeout**: `condition.await(timeoutNanos)` → a scheduled timer (`eventLoop.schedule`);
  on fire, complete the promise with `WriteTimeoutException`.
- **Typed-exception fidelity**: WriteTimeout/WriteFailure/Unavailable/RetryOnDifferentSystem
  must ride the future and surface identically to `get()`'s throws.
- **Thread-locals**: Tracing / ClientWarn / MessageParams / ClientState are set on the NT
  worker but the callback fires on a `Shard-N` thread — capture at dispatch, restore in the
  callback.
- Phase 1 removes only the COORDINATOR park; the callback may fire from a shard thread that
  itself briefly parked (memtable backpressure / commitlog lag) — acceptable, that's Phase 2/3.

**(6) Assumptions to treat as given** (don't re-audit — see `findings.md`):
- Shard apply is already mostly non-blocking (`tryLock` fast path; periodic commitlog
  non-blocking unless >15 s lag); `onResponse`/remote-acks/flush already async.
- Infra: loadgen **torn down**. Rig node `157.180.98.112` is UP in **OFF arm** with
  **autocompaction disabled** on `cassandra_easy_stress.keyvalue` (run `enableautocompaction`
  + restart before reuse). Re-profiling Fix 1 needs a fresh loadgen (~10 min, `hcloud` —
  see agent-common `rig/cloud.md`; token at `~/repos/agent-common/.secrets/hcloud.token`).
- Over SSH use `pkill -f 'Cassandra[D]aemon'` (self-excluding) — plain `CassandraDaemon`
  kills your own shell.

**(7) Entry point for a fresh agent**

Read, in order: **this `progress.md`** → `task_plan.md` (same dir; Phase 1 steps + the
async design constraint) → `findings.md` (same dir; "Fix 1" section = the seam) →
`../i1-single-node-perf/findings.md` (the +28 pp measurement + asprof root cause = the WHY).
Then source: `src/java/org/apache/cassandra/service/AbstractWriteResponseHandler.java`,
`.../service/StorageProxy.java` (~1007, 2026-2085), `.../utils/concurrent/{Future,Promise,
AsyncPromise,AsyncFuture}.java`, `.../transport/{Dispatcher,Flusher}.java`,
`.../cql3/statements/ModificationStatement.java`.

Starting prompt (paste):

> Phase 1 of the non-blocking TPC write path. Read
> `tasks/tpc-migration-planning/phase-4-poc/increments/nonblocking-write-path/{progress.md,task_plan.md,findings.md}`
> and `../i1-single-node-perf/findings.md` first. Goal: remove the coordinator's blocking
> `AbstractWriteResponseHandler.get()` park (`StorageProxy.java:1007`) that causes the
> profiled +28 pp shard-routing rendezvous, by completing an in-package `AsyncPromise`
> (Netty-aligned — NO RxJava / `CompletableFuture`) from `signal()`/timeout and driving the
> already-async `Dispatcher` flush from its callback. Work on a branch off `tpc-migration`.
> FIRST ACTION: read `AbstractWriteResponseHandler.java` (get/signal/callback @ 130/346/360)
> and `StorageProxy.mutate` (@1007) + the `performLocally` routing branch (@2074-2085), then
> propose the exact async seam — where the future is created, how it threads up
> `ModificationStatement.execute → Dispatcher.processRequest`, and how timeout / typed
> exceptions / thread-locals are handled — and confirm before editing. Keep `get()` as a
> sync shim for the batchlog/paxos callers (`:926,:1666,:1715,:3104`).

## Phase 1a — foundational seam landed (2026-07-12)

**Branch:** `tpc-nonblocking-write` (off `tpc-migration`). `ant build` green; tests green:
`WriteResponseHandlerTest` 7/0/0, `WriteResponseHandlerTransientTest` 4/0/0.

**(1) Built (1a only), all in `AbstractWriteResponseHandler`:** replaced the one-time
`Condition` with `private final AsyncPromise<Void> writeResult`; `signal()` →
`writeResult.trySuccess(null)`; `get()` = `writeResult.await(timeout)` + extracted
`checkOutcome()` (the post-await verdict, moved verbatim); `decrementResponseOrExpired` →
`writeResult.isSuccess()`; `maybeTryAdditionalReplicas` awaits the promise. Added
`public Future<Void> writeResult()`. **No consumer yet → coordinator still parks in `get()`**
(verified: 1a is a pure refactor, zero behavior change).

**(2) Deviation from plan:** 1a said the promise "carries the write outcome (success or typed
exception)". As built it carries only **"terminal reached"** (`trySuccess(null)`); the typed
verdict stays in `checkOutcome()`, run by the consumer. Reason: `checkOutcome`'s success path
writes the **ClientWarn thread-local** (`CoordinatorWriteWarnings.update`), so running it at
`signal()` time on the shard thread would land the warning on the wrong thread. Keeping it
consumer-side preserves exact behavior for the sync callers now; 1c/1d run it on the shard
thread *with thread-locals restored* — where the cross-thread hop is handled anyway.

**(3) Thread model (verified — answers "what thread unblocks it"):** completion runs **inline
on `Shard-N`** (plain `AsyncPromise`, `notifyExecutor()` null → listener fires on the
`trySuccess` thread); socket write stays on the Netty loop. Two-thread rendezvous →
single-thread continuation; coordinator leaves the critical section. Full trace in `findings.md`
("Thread model" + the park×oversubscription×zero-amortization synthesis).

**(4) Next — 1c (the actual thread-move + async `ResultMessage`), GATED on user check-in:**
- Add an async execute variant returning `Future<ResultMessage>`; `Message.Request.executeAsync`
  defaults to `ImmediateFuture.success(execute(...))` so **only the write path** threads a real
  future (additive/opt-in — no other Message type changes behavior).
- `StorageProxy.mutate` → return the composed future (`FutureCombiner` for N mutations).
- `Dispatcher.processRequest`/`RequestProcessor` call `executeAsync`, flush from the listener,
  **return without blocking the NT-worker**.
- 1d: scheduled-timer timeout (nobody awaits on the async path → `ScheduledExecutors`);
  typed-exception fidelity into the future; **capture/restore** Tracing/ClientWarn/
  MessageParams/QueryState across the NT-worker→`Shard-N` hop.
- Keep `get()` as the parking shim for batchlog/paxos (`:926,:1666,:1715,:3104`) — unchanged.

**(5) Carried-forward gotcha:** the sync shim's timeout is the bounded `writeResult.await(timeout)`.
The async path has nobody awaiting, so 1c/1d **must** add the scheduled timer or a timed-out
write never completes its promise (hangs the client request).

## Paused before Brick 1 — scope discovery (2026-07-12)

Paused at user request during research; **no Brick code written**, working tree clean (only
the 1a change + doc updates). Two findings materially reshape 1c/1d — record before resuming:

**(A) The async entry point is NOT `mutate` alone.** The normal (non-LWT, non-batch) CQL write
flows `ModificationStatement.executeWithoutCondition` → `StorageProxy.mutateWithTriggers`
(:1227) → `dispatchMutationsWithRetryOnDifferentSystem` (:1269) → `mutate` (:978). That middle
method is a `while(true)` **retry loop**: it splits into Accord vs normal
(`splitMutationsIntoAccordAndNormal`), kicks off the **already-async Accord arm**
(`mutateWithAccordAsync` → `IAccordResult`, blocked on `accordResult.awaitAndGet()` :1328),
calls the synchronous `mutate(normal)` (:1297), and `continue`s on
`RetryOnDifferentSystemException`/`CoordinatorBehindException` (:1301,:1309). So a correct async
path must thread through this loop + compose the Accord future — not just async-ify `mutate`.
For the PoC workload (plain INSERT, no txns) Accord is empty and the loop runs once, so the hot
path is just `mutate`'s `get()` park — but the retry/Accord semantics must be preserved (RF=3
correctness: RetryOnDifferentSystem — see memory `feedback_rf3_assumption_poc_validity`).

**(B) `CoordinatorWarnings`/`CoordinatorWriteWarnings` are a bare `FastThreadLocal`, NOT in
`ExecutorLocals`.** Backed by `service/thresholds/CoordinatorWarningsState` (`FastThreadLocal<S>`).
`ExecutorLocals` carries only Tracing + ClientWarn (+ artificial-latency flag), which DO
auto-propagate to `Shard-N` (executors are `localAware()`; `ShardExecutors.execute(locals,…)`
exists). But coordinator warnings do **not** follow — the Dispatcher flip must manually
capture/restore them (or route `done()`/`update()` back to the origin thread), else warnings
land on the wrong thread-local. This is the sharpest 1d hazard.

**Supporting map (from research):** `Message.Request.execute` (Message.java:252) is a `final`
template wrapping tracing around abstract `execute(qstate,rt,traceRequest)` (:250); the 3
write-carriers (`QueryMessage`/`ExecuteMessage`/`BatchMessage`) funnel through
`QueryProcessor.processStatement` (:312) → `statement.execute`; non-LWT returns **null** →
mapped to `ResultMessage.Void` upstream (async result must complete with null/Void).
`FutureCombiner.nettySuccessListener(Collection<Future<?>>)` = fail-slow combine (waits all,
first failure after) — matches `mutate`'s collect-all semantics. `ImmediateFuture.success(null)`
for sync-wrap. Full map in the research-agent transcript (session tasks dir).

**Revised brick plan (for resume):**
- Brick 1 = `mutateAsync` (async twin of `mutate` :978) — still the foundation, additive, safe.
- Brick 2 = async up-threading now includes `dispatchMutationsWithRetryOnDifferentSystem`'s
  retry loop + Accord-future composition + `mutateWithTriggers`, then `ModificationStatement`/
  `QueryProcessor`/`Message.Request.executeAsync` (default sync-wrap). Bigger than first scoped.
- Brick 3 = Dispatcher flip + finding (B) capture/restore + timeout timer + `maybeTry` async.
- Open design fork for the user: async-ify the full retry/Accord loop now (complete), or
  async only the hot `mutate` path and keep retry/Accord synchronous-but-correct (PoC-minimal)?

## Brick 1 landed — `mutateAsync` (2026-07-12)

Compiles; `WriteResponseHandlerTest` **9/0/0** (7 prior + 2 new). Additive — `mutate` byte-identical.

**Built:**
- `AbstractWriteResponseHandler`: extracted the pure verdict out of `checkOutcome()` into
  package-private `computeVerdict()` (throws the typed write exception; no thread-local side effect,
  so safe on the shard thread). `get()`/`checkOutcome()` behaviour unchanged (the warnings side
  effect still runs consumer-side in `checkOutcome`).
- `StorageProxy.mutateAsync(mutations, cl, requestTime) -> Future<Void>`: dispatch + speculative
  retry as in `mutate`, combine the handlers' `writeResult()`s via
  `FutureCombiner.nettySuccessListener` (fail-slow), then `.map` each handler's `computeVerdict()` so
  the first failure fails the future. Synchronous `Unavailable`/`Overloaded` → `ImmediateFuture.failure`.
- Tests: `writeResultCompletesAndVerdictPassesWhenClMet`, `writeResultVerdictThrowsWhenClUnreachable`.

**Known gaps (by design, deferred):** (a) the returned future does NOT self-complete on write timeout
— needs the scheduled timer (1d); only a caller enforcing its own timeout may await it. (b) `mutateAsync`
still calls the bounded-await `maybeTryAdditionalReplicas` (speculative path) — async-ified in 1d.
(c) no metrics/hint/latency bookkeeping — the consumer applies it when wired.

**The earlier fork has collapsed:** to actually free the NT-worker the retry loop can't stay
synchronous (it would block), so Brick 2 must async the `dispatchMutationsWithRetryOnDifferentSystem`
retry via async recursion + compose the already-async Accord future. "PoC-minimal keep-retry-sync"
isn't viable — it wouldn't free the thread. So Brick 2 = async-recursive retry, no real fork.

## Handoff — start of Brick 2 (up-threading) — 2026-07-12

Diff vs `tpc-migration` (branch `tpc-nonblocking-write`): 3 files, +141/-18 —
`AbstractWriteResponseHandler.java`, `StorageProxy.java`, `WriteResponseHandlerTest.java`. Bricks
1a+1 done. Nothing above `mutateAsync` is converted yet; the CQL path still runs fully synchronous.

**(1) Deviations from plan**
- Promise carries **"terminal reached"**, not the typed verdict (plan 1a said "carries the outcome").
  Verdict split into `computeVerdict()`; run separately. Why: `checkOutcome`'s success-warning path
  writes the `ClientWarn` thread-local — unsafe on the shard thread until 1d restores it.
- `mutate` left **byte-identical**; `mutateAsync` added alongside (not `mutate`-delegates-to-async).
  Why: preserving `mutate`'s exact timeout-per-handler + tracing-on-caller semantics; dedupe later.
- The "keep retry/Accord synchronous (PoC-minimal)" fork is **rejected** — a sync retry loop would
  just re-park the NT-worker. Brick 2 must async the retry loop (async recursion) + compose Accord.

**(2) As-built interfaces (verbatim; Brick 2 consumes these)**
- `AbstractWriteResponseHandler` (`src/java/org/apache/cassandra/service/`):
  - `public Future<Void> writeResult()` — terminal completion. Completed only by `signal()` via
    `writeResult.trySuccess(null)`. **NEVER fails** and **does NOT self-complete on timeout**.
    `isSuccess()` == "terminal reached", NOT "write succeeded".
  - `void computeVerdict() throws WriteTimeoutException, WriteFailureException, RetryOnDifferentSystemException`
    — package-private (o.a.c.service). Pure: throws the typed write exception on failure, returns on
    success. No thread-local side effect → safe on the shard thread. (Also throws unchecked
    `CoordinatorBehindException`.) `get()`/`checkOutcome()` behaviour unchanged.
- `StorageProxy` (`src/java/org/apache/cassandra/service/`, o.a.c.service — same package, so
  `computeVerdict()` is reachable):
  - `public static Future<Void> mutateAsync(List<? extends IMutation> mutations, ConsistencyLevel consistencyLevel, Dispatcher.RequestTime requestTime)` (:1073) — combines handler `writeResult()`s
    via `FutureCombiner.nettySuccessListener` then `.map(computeVerdict per handler)`. Sync
    `Unavailable`/`Overloaded` → `ImmediateFuture.failure`.
  - Consumers to thread through (all currently `void`/sync): `mutateWithTriggers(...)` (:1283) →
    `dispatchMutationsWithRetryOnDifferentSystem(List<? extends IMutation>, ConsistencyLevel, Dispatcher.RequestTime, PreserveTimestamp)` (:1325, `while(true)` retry; Accord arm
    `mutateWithAccordAsync`→`IAccordResult`, `awaitAndGet()` :1387-ish; `mutate(normal)` :1353-ish).
- Above StorageProxy (from research, verify at site): `ModificationStatement.executeWithoutCondition`
  (:668, calls `mutateWithTriggers`, returns **null**); `QueryProcessor.processStatement` (:312,
  funnels all 3 write msgs → `statement.execute`); `Message.Request` has a **`final` template**
  `execute(QueryState,RequestTime)` (:252) wrapping tracing around abstract
  `execute(QueryState,RequestTime,boolean)` (:250) — add `executeAsync` **alongside** (can't override
  the final one). Write msgs: `QueryMessage`/`ExecuteMessage`/`BatchMessage`. Non-LWT → `null` → mapped
  to `ResultMessage.Void` upstream.

**(3) Tested / deferred**
- Tested: `WriteResponseHandlerTest` **9/0/0** (2 new: `writeResultCompletesAndVerdictPassesWhenClMet`,
  `writeResultVerdictThrowsWhenClUnreachable`). `mutateAsync` itself is NOT behaviourally tested
  (needs wiring → write dtest).
- Deferred to 1d/Brick 3: timeout self-completion (scheduled timer), `maybeTryAdditionalReplicas`
  async (still bounded-await inside `mutateAsync`), metrics/hint/latency bookkeeping (consumer),
  `CoordinatorWarnings`/`CoordinatorWriteWarnings` capture/restore, `ExecutorLocals.current()` carried
  to the shard so verdict/tracing on the shard thread is correct.

**(4) Decisions**
- Async medium = in-package Netty `AsyncPromise`/`Future` (given). Combine = `FutureCombiner.nettySuccessListener` (fail-slow, matches `mutate`'s collect-all). `Future.map` catches a
  throwing mapper → fails the future (verified `AbstractFuture.java:345-348`), so `computeVerdict()`
  throwing propagates the verdict.

**(5) Gotchas**
- Awaiting `mutateAsync(...)` **without** a timeout **hangs** on a write timeout (no signal, no timer).
- `writeResult()` success ≠ write success — always run `computeVerdict()`.
- `CoordinatorWarnings`/`CoordinatorWriteWarnings` = bare `FastThreadLocal`
  (`service/thresholds/CoordinatorWarningsState`), **NOT** in `ExecutorLocals` — won't follow to shard.
- `Message.Request.execute` is `final` — add `executeAsync`, don't try to override.
- Accord arm is **already async** (`IAccordResult`); compose it, don't re-block on `awaitAndGet()`.
- Ingress/throw hazard (memory `feedback_ingress_throw_kills_connection`) applies once wired.

**(6) Treat as given**
- Brick 1a+1 verified — build + 9/0/0. `ExecutorLocals` carries Tracing+ClientWarn and
  `ShardExecutors.execute(ExecutorLocals, int, Runnable)` exists (dispatch at StorageProxy `~:2130`
  local-apply routing branch currently uses the no-locals `execute`). Don't re-audit the research map
  (in `progress.md` "Paused before Brick 1" + `findings.md`).

**(7) Entry point for a fresh agent**

Read in order: **this `progress.md`** (esp. "Paused before Brick 1", "Brick 1 landed", this handoff)
→ `findings.md` (same dir; "Thread model" + the 2 fixes) → `task_plan.md` (same dir; 1b/1c/1d).
Then source: `src/java/org/apache/cassandra/service/AbstractWriteResponseHandler.java`
(`writeResult()`/`computeVerdict()` @158/184), `.../service/StorageProxy.java` (`mutateAsync` @1073,
`dispatchMutationsWithRetryOnDifferentSystem` @1325, `mutateWithTriggers` @1283),
`.../cql3/statements/ModificationStatement.java` (@668), `.../cql3/QueryProcessor.java` (@312),
`.../transport/Message.java` (@250/252), `.../utils/concurrent/{FutureCombiner,ImmediateFuture}.java`.

Starting prompt (paste):

> Brick 2 of the non-blocking TPC write path (branch `tpc-nonblocking-write`, off `tpc-migration`).
> Read `tasks/tpc-migration-planning/phase-4-poc/increments/nonblocking-write-path/{progress.md,findings.md,task_plan.md}`
> first — Bricks 1a+1 are done (build + WriteResponseHandlerTest 9/0/0): `AbstractWriteResponseHandler`
> exposes `Future<Void> writeResult()` (terminal) + `void computeVerdict()` (typed verdict), and
> `StorageProxy.mutateAsync(...)->Future<Void>` (@1073) exists. Goal: thread that future UP through
> `dispatchMutationsWithRetryOnDifferentSystem` (@1325 — async the `while(true)` retry via async
> recursion + compose the already-async Accord `IAccordResult`) → `mutateWithTriggers` (@1283) →
> `ModificationStatement.executeWithoutCondition` (@668) → `QueryProcessor.processStatement` (@312) →
> a new `Message.Request.executeAsync` (alongside the `final` `execute` template @252; default
> sync-wraps via `ImmediateFuture.success(execute(...))`; only Query/Execute/Batch override). All
> additive/sync-wrapping — the Dispatcher is NOT flipped in Brick 2, so no behaviour changes yet
> (that's Brick 3, the pause point). Do NOT add the timeout timer / thread-local capture / bookkeeping
> yet (1d/Brick 3). FIRST ACTION: read `dispatchMutationsWithRetryOnDifferentSystem` (@1325) in full
> and propose the exact async-recursion shape for the retry loop + how the Accord future composes,
> and confirm before editing.

## Brick 2 landed — async up-threading to `Message.Request.executeAsync` (2026-07-12)

`ant build` green; `WriteResponseHandlerTest` **9/0/0** (unchanged). Diff vs `tpc-migration`: 8 new-code
files, **+850/-18** (the −18 are all Bricks 1a/1 in `AbstractWriteResponseHandler`; Brick 2 is **purely
additive** — every sync method is byte-identical, verified `git diff` shows zero removals in StorageProxy/
statement/message files). The async path is **built end-to-end but dormant**: nothing calls `executeAsync`
yet (Dispatcher not flipped — that's Brick 3), so **zero behaviour change**.

**(1) Built — the additive async chain (each layer sync-wraps by default; only the write carriers thread a real future):**
- `StorageProxy.dispatchMutationsWithRetryOnDifferentSystemAsync(mutations, cl, rt, pts) -> Future<Void>`
  (@~1385) + private `RetryingMutationDispatch` helper. The `while(true)` retry loop is **async recursion**
  (`attempt()` re-invoked from an arm's completion callback → stack unwinds between iterations); the Accord
  arm composes via `IAccordResult.addCallback` (non-blocking), **not** `awaitAndGet()`. Normal arm resolves
  before Accord is inspected; a normal-arm retry (RetryOnDifferentSystem/CoordinatorBehind) abandons the
  pending Accord result exactly as sync `continue` does. Failure merge / `retry_new_protocol` / `pts`
  threading / `clearCachedSerializationsForRetry` / retry metrics — all preserved.
- `StorageProxy.mutateWithTriggersAsync(...) -> Future<Void>` (@~1326): non-atomic path returns the async
  dispatch future; atomic/MV/trigger path runs sync + wraps. Denylist/trigger/view prelude **duplicated**
  from `mutateWithTriggers` (kept sync byte-identical; dedupe when the path goes live in Brick 3).
- `CQLStatement.executeAsync(...)` default = `ImmediateFuture.success(execute(...))`; `ModificationStatement`
  overrides → `executeWithoutConditionAsync` (LWT stays sync-wrapped via `executeWithCondition`).
- `QueryProcessor.processStatementAsync` + `processAsync`/`processPreparedAsync` (both overloads; preserve
  the `regularStatementsExecuted`/`preparedStatementsExecuted` counters); `QueryHandler.processAsync/
  processPreparedAsync/processBatchAsync` defaults sync-wrap (QueryProcessor overrides all but batch).
- `Message.Request.executeAsync(qstate, rt)` **2-arg template** (runs the tracing-session lifecycle, stops
  the session on settle via `addCallback`, sets tracingId on success) around a **3-arg abstract**
  `executeAsync(qstate, rt, traceRequest)` default = `ImmediateFuture.success(execute(...))`. `QueryMessage`/
  `ExecuteMessage`/`BatchMessage` override the 3-arg, porting their QueryEvents/ErrorMessage/metadata bodies
  into an `AsyncPromise` + `addCallback` (failure→`ErrorMessage` for Exceptions, propagate for Errors —
  mirrors each sync `catch (Exception e)`).

**(2) Design reviewed adversarially** (subagent, vs the sync original) — retry/Accord control flow confirmed
**faithful** (retry conditions, exception typing via raw `normalFuture.cause()`, ordering, `pts` threading,
`unchecked` surfacing, no double-completion). Two divergences:
- **[handled] stack-trace trace on failure** — sync's outer catch traces `getStackTraceAsToString` for every
  exception it rethrows; `finish()` now does the same on the failure path.
- **[deferred, documented] Accord deadline** — sync `awaitAndGet()` self-times-out at Accord's deadline;
  `addCallback` fires only on real completion, so a lost coordination could hang. Folded into the Brick 3
  timeout timer: **that timer must bound the Accord arm too**, not just the outer promise. Noted in the
  `dispatchMutationsWithRetryOnDifferentSystemAsync` javadoc.

**(3) Tested / deferred**
- Tested: build green; `WriteResponseHandlerTest` 9/0/0; sync path byte-identical (diff). The async methods
  themselves are **not behaviourally tested** — dormant until Brick 3 wires the Dispatcher; validate then via
  a write dtest (the message overrides + async tracing teardown especially).
- Deferred to Brick 3 (1d): flip `Dispatcher.processRequest`/`RequestProcessor` to call `executeAsync` and
  flush from the callback without blocking the NT-worker; scheduled **timeout timer** (bounding **both** the
  outer promise **and** the Accord arm); `mutate()`-level bookkeeping (CL.ANY hint-swallow, timeout/unavail/
  latency metrics — still absent from `mutateAsync`); `maybeTryAdditionalReplicas` async (still bounded-await
  inside `mutateAsync`); `CoordinatorWarnings`/`CoordinatorWriteWarnings` + `ExecutorLocals` capture/restore
  across the NT-worker→`Shard-N` hop; dedupe the `mutateWithTriggersAsync` prelude.

**(4) Gotchas for Brick 3**
- The whole async path is **dormant** — `git grep executeAsync` shows only the new defs, no callers. Brick 3's
  job is the single call-site flip in the Dispatcher + the deferred hardening above.
- `mutateAsync`/`dispatchMutationsWithRetryOnDifferentSystemAsync` **hang on a write timeout** (no signal, no
  timer) — the Brick 3 timer is load-bearing, not optional.
- Message overrides catch `Exception`→`ErrorMessage` but **propagate `Error`/`Throwable`** (fail the future) —
  mirrors sync; Brick 3's Dispatcher flip must handle a failed `executeAsync` future (shouldn't happen from
  these overrides, but the default path can fail).
- Async tracing teardown (`Message.Request.executeAsync` 2-arg) stops the session on settle via `addCallback`
  and sets tracingId via `map` — verify under real tracing when wired.

**(5) Entry point for Brick 3**: read this section → `findings.md` "Fix 1" + "Thread model" → source
`transport/{Dispatcher,Flusher}.java` (the flip target), `Message.java` (`executeAsync` @~289), `StorageProxy`
(`dispatchMutationsWithRetryOnDifferentSystemAsync`, `mutateAsync`), `AbstractWriteResponseHandler`
(`writeResult`/`computeVerdict`). Then wire the Dispatcher to `executeAsync`, add the timeout timer (Accord
arm included), and the capture/restore.

**The exact flip site (verified):** `Dispatcher.processRequest` (the 5-arg `ServerConnection` overload,
`Dispatcher.java:365`) calls `Message.Response response = request.execute(qstate, requestTime);` at **:429**,
then does **post-execute work at :431-441** that all currently runs on the NT-worker *after* the write:
`CoordinatorWarnings.done()` / `CoordinatorWriteWarnings.done()` (:431-435, **bare `FastThreadLocal`, NOT in
`ExecutorLocals`** — the sharpest hazard), `response.setStreamId`, `setWarnings(ClientWarn.instance.getWarnings())`,
`attach`, `applyStateTransition` (:437-440). Brick 3 = swap :429 to `executeAsync(...) -> Future<Response>` and
move :431-441 into the future's completion callback with those thread-locals captured at dispatch / restored in
the callback; the void `processRequest` (:480) and `flush` (:485) must be driven from the callback instead of a
synchronous return, so the NT-worker returns immediately (this is the park removal that Fix 1 targets). The
`catch (Throwable)` fallback in the 3-arg `processRequest` (:447-459) also finalizes warnings — mirror it for a
failed future. `RequestProcessor.run()` (:317) is the submit path.

## Brick 3 — starting prompt (paste into a fresh context)

> Brick 3 (the behaviour-change brick + pause point) of the non-blocking TPC write path — branch
> `tpc-nonblocking-write` off `tpc-migration`. Read
> `tasks/tpc-migration-planning/phase-4-poc/increments/nonblocking-write-path/{progress.md,findings.md,task_plan.md}`
> first, especially progress.md "Brick 2 landed" (as-built async chain) + "Brick 1a"/"Brick 1 landed", and
> memory `feedback_ingress_throw_kills_connection`, `feedback_async_medium_netty_futures`,
> `feedback_rf3_assumption_poc_validity`. Bricks 1a/1/2 are done: a **dormant, purely-additive** async write
> path exists end-to-end — `Message.Request.executeAsync(qstate,rt) -> Future<Response>` (Message.java ~:289,
> 2-arg template runs the tracing-session lifecycle around a 3-arg abstract; Query/Execute/Batch override the
> 3-arg) → `QueryProcessor.processStatementAsync`/`processAsync`/`processPreparedAsync` →
> `ModificationStatement.executeWithoutConditionAsync` → `StorageProxy.mutateWithTriggersAsync` →
> `dispatchMutationsWithRetryOnDifferentSystemAsync` (async-recursive retry loop + Accord via `addCallback`) →
> `mutateAsync` → `AbstractWriteResponseHandler.writeResult()`/`computeVerdict()`. Nothing calls `executeAsync`
> yet (`git grep` = internal chaining only), so today there is zero behaviour change.
>
> GOAL: flip the coordinator to the async path so the Native-Transport worker stops parking on the ack (the
> profiled +28 pp shard-routing rendezvous). Steps:
> 1. `Dispatcher.processRequest` (Dispatcher.java:365): swap `request.execute(qstate,rt)` (:429) to
>    `executeAsync(...)`; move the post-execute work (:431-441 — CoordinatorWarnings/CoordinatorWriteWarnings
>    `done()`, setStreamId, `setWarnings(ClientWarn.getWarnings())`, attach, applyStateTransition) and the
>    `flush` (:485) into the future's completion callback; return the NT-worker immediately. Mirror the
>    `catch (Throwable)` warnings-finalize (:447-459) as a future-failure handler.
> 2. Timeout: `mutateAsync`/`dispatchMutationsWithRetryOnDifferentSystemAsync` **do not self-complete on write
>    timeout** — add a scheduled timer (Netty `eventLoop.schedule`/`ScheduledExecutors`) that completes the
>    promise with `WriteTimeoutException`. It **must also bound the Accord arm** (`addCallback` doesn't enforce
>    Accord's deadline the way sync `awaitAndGet()` did — see the `dispatchMutationsWithRetryOnDifferentSystemAsync`
>    javadoc).
> 3. Thread-locals: capture Tracing + ClientWarn + MessageParams + QueryState + **CoordinatorWarnings/
>    CoordinatorWriteWarnings** (the last two are bare `FastThreadLocal`, NOT in `ExecutorLocals` — won't follow
>    to `Shard-N`) at dispatch; restore around the callback. `ExecutorLocals` already carries Tracing+ClientWarn.
> 4. Restore the `mutate()`-level bookkeeping the async path omits: CL.ANY hint-swallow, timeout/unavailable
>    metrics, latency + `updateCoordinatorWriteLatencyTableMetric` (see sync `mutate` StorageProxy:980-1059).
> 5. `maybeTryAdditionalReplicas` (AbstractWriteResponseHandler:449) still bounded-await inside `mutateAsync` —
>    make the speculative retry a scheduled callback.
> 6. Do NOT throw on the netty inbound loop (closes the connection + leaks capacity) — null-returning lookups +
>    try/catch→fallback for any verb work pulled onto the loop.
> 7. Verify (task_plan 1e): re-profile i1 — `futex`/`unpark` share and the ~620k cs/s should collapse; CPU at
>    matched throughput returns toward the OFF arm (expect *toward*, not *to* — the dispatch-to-shard wakeup
>    remains by design single-node; see findings.md "Why the single-node regression was this severe"). Correctness
>    on write/timeout/error/tracing paths; a write dtest exercising the now-live async path (message overrides +
>    async tracing teardown especially, which Brick 2 could not test while dormant). Rig facts: node
>    `157.180.98.112` OFF-arm, autocompaction disabled on `cassandra_easy_stress.keyvalue` (re-enable + restart
>    before reuse); fresh loadgen via `hcloud` (~10 min); over SSH use `pkill -f 'Cassandra[D]aemon'`.
>
> This brick DOES change behaviour and is the perf pause point — land the Dispatcher flip + timer + thread-locals,
> then STOP and re-profile before the mutate()-bookkeeping/maybeTry polish if results are conclusive.

## Brick 3 landed — Dispatcher flipped to async (2026-07-13). PERF PAUSE POINT (pre-profile).

The coordinator now dispatches the CQL write and frees the Native-Transport worker; the finalize +
flush run on the completing thread. `ant build` green; `WriteResponseHandlerTest` 9/0/0; a live
native-protocol write dtest passes (see (3)). **The async path is now LIVE** (the Dispatcher calls
`executeAsync`), so this is a behaviour change. NOT yet re-profiled — that is the next action.

**(0) The plan was re-scoped by a Fable critique BEFORE coding** (task_plan.md "rev 2"). Rev 1 had a
plan-invalidating bug; all 8 findings verified at source and folded in. Key correction: `mutateAsync`'s
`FutureCombiner.nettySuccessListener` overrides `notifyExecutor()` to `GlobalEventExecutor.INSTANCE`
(FutureCombiner.java:224), so the WHOLE write up-chain would have funnelled onto one JVM-global thread
the instant the path went live — falsifying findings.md's "inline on Shard-N" model. Also: the
approved `performLocally` locals one-liner was a NO-OP (ShardExecutors are already `localAware()`).

**(1) Built (rev 2):**
- `StorageProxy.mutateAsync` (:1074): replaced the GEE-funnelling combiner with a sequential
  `andThenAsync` chain of `handler.outcome()` in index order — runs INLINE on the completing thread,
  and surfaces the first index-order failure (matches sync `get()` loop). Size-1 (the PoC) = the single
  handler's future, zero overhead. `FutureCombiner` import removed.
- `AbstractWriteResponseHandler.outcome()` (:163): terminal (via `writeResult`) + `computeVerdict`,
  plus a scheduled deadline timer that fails with the exact `throwTimeout()` WriteTimeoutException.
  `writeResult` untouched (never fails, no self-timeout) so `maybeTryAdditionalReplicas` /
  `decrementResponseOrExpired.isSuccess()` are unaffected.
- Timer distribution: `Dispatcher.RequestTime` now carries an optional `ScheduledExecutorService`
  (the request's `channel.eventLoop()`, supplied by `RequestProcessor`); `outcome()` and the Accord
  backstop arm there, falling back to `ScheduledExecutors.scheduledFastTasks` for internal callers.
  Avoids a single-thread arm/cancel bottleneck on the hot path.
- `RetryingMutationDispatch`: `attempt()` guarded by `result.isDone()` (no zombie re-dispatch after the
  Accord backstop fires); `armAccordDeadline()` armed once, only when `accordResult != null` (zero cost
  on the normal-only PoC path).
- `mutateWithTriggersAsync`: **CL.ANY gated to the synchronous path** (preserves its hint-and-succeed
  guarantee, which `mutateAsync` omits). CL.ANY parks the caller — rare, acceptable.
- `Message.executeAsync` (2-arg, :305): self-carrying tracing teardown — captures locals post-
  `newSession`, runs `stopSession()` under `try (captured.get())` so it works on any completion thread.
- `Dispatcher`: new `processRequestAsync` + `finalizeResponse`/`finalizeSuccess`/`finalizeFailure`
  (mirrors the sync 431-441 / catch 455-467 / finally reset, on the completing thread with the request
  context restored). void 5-arg `processRequest` flipped to dispatch→callback→flush, with a last-resort
  try/catch that still flushes an ErrorMessage (an unflushed response hangs the client + leaks in-flight
  bytes). Static 4-arg `processRequest` left intact (InitialConnectionHandler STARTUP on the netty loop).
- In-flight async-request `AtomicLong` inc at dispatch / dec at flush; `isDone()` consults it so
  `Server.close(force=false)` drain waits for outstanding responses (else RSTs on decommission/drain).
- Thread-local bundle: `ExecutorLocals` + `CoordinatorWarnings`/`CoordinatorWriteWarnings`
  (new `captureAndClear()`/`restore()` on `CoordinatorWarningsState`) captured on the NT worker,
  restored around the finalize; `ClientWarn.resetWarnings()` runs INSIDE the restored-locals window.
  MessageParams skipped (finalize doesn't read it; shard resets it).

**(2) Deviation / bug caught in validation (NOT in review):** the NT worker sets request-scoped
thread-locals (captureWarnings, warnings init, `newSession` → TraceState) but the teardown moved to
the completion thread — so the pooled worker leaked its TraceState into the next request and tripped
`Tracing.newSession`'s `-ea assert`. Fix: after capturing the bundle, `ExecutorLocals.clear()` on the
NT worker (both the normal and sync-throw paths). See `tasks/lessons.md` "Async request paths: clear
request-scoped thread-locals on the ORIGIN thread".

**(3) Tested / deferred:**
- `ant build` green; `WriteResponseHandlerTest` 9/0/0.
- `NonBlockingWriteDispatchTest.singleNodeCarriersConcurrencyAndTracing` **PASS** (routing ON, native
  protocol): QUERY/EXECUTE/BATCH carriers, 500 concurrent writes (no hang), 5 traced writes (session
  recorded, no assert leak), read-back correct. This is the shard-completion hop + the empirical
  Brick 1/2 validation the dormant code never had.
- `NonBlockingWriteDispatchTest.quorumWritesCompleteOnRemoteAck` (3-node RF=3, the non-shard messaging-
  thread completion where the bundle-restore is load-bearing): **NOT run locally** — needs loopback
  aliases 127.0.0.2/.3 (`sudo ifconfig lo0 alias ...`). Deferred to Linux/CI.
- Still deferred (post-profile, per prompt steps 4/5): `mutate()`-level metrics (timeout/unavailable
  marks + latency + `updateCoordinatorWriteLatencyTableMetric`, absent from `mutateAsync`);
  `maybeTryAdditionalReplicas` async (still bounded-await, only when `liveUncontacted()` non-empty);
  coordinator write-warnings fidelity (async runs `computeVerdict`, not `checkOutcome`).

**(4) NEXT ACTION — re-profile i1 (task_plan 1e). Not yet done.** Steps:
- `ant jar` (NOT just `ant build` — the jar isn't rebuilt by `build`; verify class inside + timestamp),
  rsync to rig, confirm the flip is present on the rig BEFORE walking away.
- Rig `157.180.98.112` is OFF-arm with autocompaction disabled on `cassandra_easy_stress.keyvalue`
  (re-enable + restart before reuse); fresh loadgen via `hcloud`; over SSH `pkill -f 'Cassandra[D]aemon'`.
- Expect: `futex`/`unpark` share + ~620k cs/s collapse; CPU at matched throughput moves TOWARD the OFF
  arm (not to it — the dispatch→shard wakeup remains single-node). Watch for NEW hotspots: GEE (must be
  gone — the combiner fix), and the deadline-timer lock (must be distributed across event loops).
- If conclusive, THEN do the deferred mutate()-bookkeeping / maybeTry polish.

**(5) Build-env note:** a stale incremental compile across commit `9017e18fa1` (ProtocolVersion.
supportedVersions() return type List→ImmutableList) caused a NoSuchMethodError in native dtests until
`ant clean build`. If native dtests fail on `supportedVersions`, clean-build first.

## Re-profile (Brick 3 verification, task_plan 1e) — HANDOFF for a fresh context

The code is landed + locally validated (see "Brick 3 landed" above). This is a **rig-only** task: deploy
the flipped build and re-run the i1 write profile with shard routing ON, to confirm the coordinator↔shard
park/unpark rendezvous is gone. **No code changes expected** — if you find yourself editing src/, stop and
re-read, the flip is done. Branch `tpc-nonblocking-write` (off `tpc-migration`), working tree uncommitted.

**What you're verifying.** Enabling `cassandra.mutation.shard_routing` regressed single-node writes
(i1 findings.md): at a matched **178.8k ops/s**, routing ON cost **+28.5 pp CPU (58.5%→87.1%)** and
**10× p50 (14→149 µs)**, with **~620k context-switches/s (~3.5/op)** from a per-write futex park —
the NT worker parked on the ack; the shard unparked it. Brick 3 removes that park (the worker dispatches
and returns; finalize runs on the completing thread). **Expect, routing ON + flip vs the OFF arm:** the
`futex`/`unpark` share and the ~620k cs/s collapse, CPU-at-matched-throughput moves *toward* the OFF arm
(NOT to it — the dispatch→shard wakeup is single-node by design; findings.md "Why the single-node
regression was this severe"). **New-hotspot watch (both must be absent):** `GlobalEventExecutor` (the
combiner funnel — fixed, must not appear) and single-thread contention on the deadline timer (armed on
per-channel EventLoops now, must not serialize).

**Baseline numbers to beat** (i1 findings.md table; same workload, matched throughput):
| arm | ops/s | p50 | p99 | CPU | cs/s |
|---|---|---|---|---|---|
| off (routing off) | 178.8k | 14 µs | 310 µs | 58.5% | — |
| i1 (routing on, NO flip) | 178.8k | 149 µs | 642 µs | 87.1% | ~620k |
| **ON + flip (this run)** | target 178.8k | **←toward off** | ←toward off | ←toward off | **←collapse** |

**Deploy (traps are load-bearing — memory `feedback_cassandra_jar_rebuild`, `feedback_rsync_before_rig_launch`):**
1. `ant jar` (NOT `ant build` — build does not rebuild the JAR). Verify: jar mtime is fresh AND the flip
   is inside — e.g. `unzip -p build/apache-cassandra-*.jar …/transport/Dispatcher.class | javap -p -` and
   confirm `processRequestAsync`; likewise `AbstractWriteResponseHandler` has `outcome`.
2. rsync repo to rig BEFORE launching; after start, prove the flip is actually running on the rig (log
   line / a canary) BEFORE walking away. rsync-then-forget cost 4h before.
3. Enable routing on the rig: `cassandra.mutation.shard_routing=true` (+ periodic commitlog, the default).
   This is the ON arm; the flip is always compiled-in but only hops under routing.

**Rig facts** (memory `reference_bench_rig_topology`, `feedback_cpu_fence_colocated_loadgen`,
`project_stress_tool_naming`, `feedback_easy_cass_stress_scripted_run_gotchas`; canonical runbooks
`…/phase-4-poc/STRESS-RUNBOOK.md` + `stress-tool-behaviour.md`):
- Cassandra node `157.180.98.112`, currently OFF-arm, **autocompaction DISABLED on
  `cassandra_easy_stress.keyvalue` — re-enable (`nodetool enableautocompaction`) + restart before reuse.**
- Rig is 6 PHYSICAL cores / 12 HT, single L3/NUMA → a co-located loadgen ALWAYS contends. Run the loadgen
  **off-box**: fresh hcloud node (~10 min; `~/repos/agent-common/rig/cloud.md`, token
  `~/repos/agent-common/.secrets/hcloud.token`). CPU-fence Cassandra vs any local gen and prove headroom
  with mpstat, else a latency delta is a co-location artifact.
- Over SSH kill with `pkill -f 'Cassandra[D]aemon'` (self-excluding; plain `CassandraDaemon` kills your shell).
- Stress tool is easy-cass-stress; keyspace `cassandra_easy_stress`. THE trap: never `--rate` WITH
  `--maxwlat`; for latency-defined saturation use ONE process + `--maxwlat` ALONE (no `--rate`), measure
  server-side. Use `--hdr`, `--prometheusport 0`, `--populate` per-thread. CSV is truncated by SIGTERM —
  use stdout summary.
- Read the benchmark playbook (memory `reference_cassandra_benchmark_playbook`) and GC caveats
  (`feedback_gc_dominates_cassandra_tail_benchmarks`) before the first run.

**Method:** reproduce the i1 workload at matched 178.8k ops/s (or its `--maxwlat` saturation regime),
routing ON + flipped build, off-box loadgen, async-profiler (wall+cpu) on the Cassandra process. Compare
the profile's futex/unpark/park frames + `vmstat` cs/s + CPU% + p50/p99 against the table above. Verify
metrics at source (`nodetool tpstats`/`proxyhistograms`) not just the exporter
(memory `feedback_verify_metrics_at_source`).

**If conclusive** (park gone, CPU toward off): proceed to the deferred post-profile polish (mutate()-level
metrics, `maybeTryAdditionalReplicas` async, write-warnings fidelity — see "Brick 3 landed" (3)). Then the
3-node dtest on Linux/CI. **If a new hotspot appears** (GEE or timer lock) that's a code bug — stop and fix.

### Re-profile — starting prompt (paste into a fresh context)

> Re-profile the non-blocking TPC write path (Brick 3 verification) — branch `tpc-nonblocking-write` off
> `tpc-migration`. Read `tasks/tpc-migration-planning/phase-4-poc/increments/nonblocking-write-path/progress.md`
> ("Brick 3 landed" + "Re-profile … HANDOFF") and `../i1-single-node-perf/findings.md` (the +28.5 pp / ~620k
> cs/s baseline) first, plus memory `reference_cassandra_benchmark_playbook`, `reference_bench_rig_topology`,
> `feedback_cpu_fence_colocated_loadgen`, `feedback_rsync_before_rig_launch`, `feedback_monitor_silence_is_not_success`,
> `feedback_cassandra_jar_rebuild`, `feedback_easy_cass_stress_scripted_run_gotchas`, `feedback_verify_metrics_at_source`.
> The Dispatcher async flip is LANDED + locally validated (native write dtest passes) — this is a RIG-ONLY
> task, no src/ edits expected. GOAL: deploy the flipped build with `cassandra.mutation.shard_routing=true`
> and re-run the i1 write profile to confirm the per-write futex park is gone: the `futex`/`unpark` share +
> ~620k cs/s should collapse and CPU-at-matched-throughput (178.8k ops/s) should move TOWARD the OFF arm
> (not to it). Watch for two NEW hotspots that would be code bugs: `GlobalEventExecutor` and single-thread
> deadline-timer contention (both must be absent). FIRST ACTIONS: `ant jar` + verify `processRequestAsync`
> is inside the jar; re-enable autocompaction on the rig + restart; spin a fresh off-box hcloud loadgen;
> rsync + confirm the flip is running on `157.180.98.112` BEFORE the run. Compare futex/park frames +
> vmstat cs/s + CPU% + p50/p99 to the baseline table in the handoff; verify at source via nodetool.

## Re-profile DONE — Brick 3 VERIFIED (2026-07-13). Park removed; both hotspots absent.

Full results + method in `findings.md` "Re-profile results — Fix 1 / Brick 3 … VERIFIED". Summary:

**Matched-throughput (≈179k ops/s, server-side):** CPU **87.1% → 71.0%** (off=58.5; closed 56% of
the i1→off gap — *toward* off, not *to* it, as predicted); **cs/op 3.47 → 2.09** (−40%); **%sys 23.3
→ 14.9** (futex-syscall collapse). At the *same* offered rate the flip delivers **more** (rung 1:
offered 200k → 194k vs i1 178.8k) — removing the park raised the write knee. Two write-only rungs
(offered 200k near-knee, 180k matched/clean), `--concurrency 3000 --threads 32`, off-box hcloud
ccx43 loadgen, i1 methodology (truncate + autocompaction off + fresh JVM/rung).

**Park gone (proof):** WALL `AbstractWriteResponseHandler` **0.38–0.39%** (was the dominant per-write
park in i1); CPU coordinator↔shard signalling **16.5% → 2.6–3.0%**; futex+unpark+cond_signal **~19%
→ ~8.9%**. At source: **`nodetool tpstats` Native-Transport-Requests Active=1 / Pending=0 /
Blocked=0** (workers freed), 12 Shard pools carry the apply (Blocked=0, 0 dropped). Residual CPU park
7.9% = idle SEPWorker/TPE churn (the by-design dispatch→shard wakeup), NOT the write rendezvous.

**Both forbidden hotspots ABSENT:** `GlobalEventExecutor` **0.00%** (the `andThenAsync` combiner fix
holds — no JVM-global funnel); deadline timer **1.58–1.75%** (per-channel EventLoops, not a
serialization lock). Async path live on the hot path: `processRequestAsync` 32%, `outcome()` 11%.

**Measurement gap (honest):** server-side coordinator write-latency is disabled under the flip
(`mutateAsync` omits `updateCoordinatorWriteLatencyTableMetric`, a deferred item) → `proxyhistograms`
Write = 0, so the flip's p50/p99 can't be given in the baseline's server µs units. Latency
*improvement* proven mechanistically (park gone; apply-side 4µs), not quantified server-side.
Wiring that metric is the first deferred-polish item and would fill the p50/p99 row directly.

**Rig / infra state left behind:**
- Node `157.180.98.112` is UP running the **flipped build, routing ON** (`shard_routing=true` in
  `/data/tpc-poc/conf/jvm-server.options`), autocompaction currently DISABLED on
  `cassandra_easy_stress.keyvalue` (from the last rung; a restart resets it to default-on).
- Flip jar deployed at `cassandra-tpc-i1/build/apache-cassandra-7.0-SNAPSHOT.jar`
  (sha256 `888d10f7…`); the tpc-migration baseline jar preserved beside it as
  `…jar.tpc-migration-baseline` — swap back + set the flag `false` to restore the OFF/i1 build.
- Helper scripts on the rig: `/root/prep_flip.sh` (fresh restart+truncate+disable), `/root/rig_capture.sh`
  (asprof cpu+wall + vmstat + mpstat + throughput + proxyhistograms/tpstats), `/root/verify_flip.sh`.
- **hcloud loadgen `tpc-loadgen` DELETED** (hourly billing). Branch `tpc-nonblocking-write` is NOT
  pushed to origin — deploy was rsync-of-jar, not a rig rebuild.

**NEXT (post-profile polish, per Brick 3 prompt steps 4/5):** wire the `mutate()`-level metrics
(timeout/unavailable marks + latency + `updateCoordinatorWriteLatencyTableMetric` — the last also
restores server-side p50/p99 measurability); `maybeTryAdditionalReplicas` async (still bounded-await);
coordinator write-warnings fidelity. THEN the 3-node RF=3 dtest
(`NonBlockingWriteDispatchTest.quorumWritesCompleteOnRemoteAck`) on Linux/CI. The multi-node RF=3
gate remains the go/no-go decision point.
