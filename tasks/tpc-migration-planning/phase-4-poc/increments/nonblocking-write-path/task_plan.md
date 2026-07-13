# Task plan — non-blocking TPC write path

Status: **PROPOSED — pending confirmation.** Findings/audit in `findings.md`.

Goal: eliminate blocking on the write path so shard/TPC threads never park and the
coordinator never blocks on a shard. Ordered by value; each phase is independently
shippable and re-profiled.

## Design constraint — async medium

**Plain Netty-style Futures only. No RxJava / Reactive Streams; no JDK `CompletableFuture`.**
The in-package `o.a.c.utils.concurrent.Promise` literally `extends
io.netty.util.concurrent.Promise` and `Future extends io.netty.util.concurrent.Future`
(listeners via Netty `GenericFutureListener`); `AsyncPromise`/`AsyncFuture` are these — the
substrate already *is* Netty futures, and there is zero RxJava in the tree. Rationale:
reuse what's here (no new framework); Reactive Streams' per-operator allocation/backpressure
adds exactly the per-op CPU we're removing; the RxJava TPC branch (DSE, the CASSANDRA-10993
debate) never merged to trunk — stack-trace opacity + debugging cost. **When an async design
question arises, Netty's patterns are the reference** (`Promise`/`Future`/`addListener`,
`EventLoop.schedule` for timeouts, `FastThreadLocal`/explicit capture for context).

## Phase 1 — Coordinator: async write completion (removes the measured +28 pp)

The convertible-now fix for the profiled rendezvous. **Architecturally significant** — it
async-ifies the CQL write execution path, touching the synchronous `ResultMessage`
contract. Also a prerequisite for the end-state where coordinator work itself runs on a
shard thread (must not block there).

- [ ] 1a. `AbstractWriteResponseHandler`: expose an `AsyncPromise`/future completed by
      `signal()` / `onFailure()` / timeout, carrying the write outcome (success or typed
      exception: WriteTimeout, Unavailable, …). Keep `get()` as a thin `future.sync()`
      shim for callers not yet converted.
- [ ] 1b. `StorageProxy.mutate` / `mutateWithTriggers`: return the future instead of
      looping `get()`. Handle the multi-mutation batch case (compose N futures).
- [ ] 1c. Thread the future up: `ModificationStatement.execute` → `CQLStatement`/
      `Message.Request.execute` → `Dispatcher.processRequest`; drive the (already-async)
      `Dispatcher.flush` from the completion callback.
- [ ] 1d. Hard parts: timeout as a netty `eventLoop.schedule` timer (not `await(timeout)`);
      typed-exception fidelity through the future; `maybeTryAdditionalReplicas` speculative
      retry via scheduled callback; capture/restore Tracing + ClientWarn + MessageParams
      thread-locals across the hop.
- [ ] 1e. Verify: re-profile i1 — `futex`/`unpark` share and ~620k cs/s collapse, CPU at
      matched throughput returns toward the off arm; correctness on write tests, timeout,
      error, tracing paths.

## Brick 3 — Dispatcher flip (behaviour change; perf pause point) — IMPLEMENTATION PLAN (rev 2, post-Fable-critique)

The async chain (Bricks 1a/1/2) is dormant. Brick 3 flips the coordinator onto it. An adversarial
Fable review of rev 1 found a **plan-invalidating** bug (finding #1 below) plus several real
correctness gaps; rev 2 folds them in. All findings verified at source before adopting.

### What the critique changed (verified)
- **#1 CRITICAL — the combiner funnels every write onto ONE global thread.** `mutateAsync` combines
  via `FutureCombiner.nettySuccessListener`, whose `notifyExecutor()` is overridden to
  `GlobalEventExecutor.INSTANCE` (FutureCombiner.java:224-227); `ListenerList.notifyExclusive`
  (:145-151) submits the combined future's listeners there. So the whole up-chain (verdict → retry
  loop → statement/query maps → the planned finalize) would run on GEE for EVERY write — falsifying
  findings.md's "inline on Shard-N" model and the perf premise. **Fix:** replace the combiner with
  **sequential index-order chaining** of per-handler outcome futures via `andThenAsync` (runs inline
  on the completing thread; size==1 = the single handler's future, zero overhead). This also fixes
  **#4** (index-order-first-failure now matches sync's sequential `get()` loop, not chronological-
  first).
- **#2 CRITICAL — tracing teardown leaks.** `Tracing.stopSession()` no-ops (skips `sessions.remove`)
  when TraceState is absent (Tracing.java:204-215) and `newSession` asserts `get()==null` under
  `-ea`. `Message.executeAsync`'s stopSession callback runs on the completing thread, which lacks
  the coordinator's locals (GEE today; a messaging/timer thread even after #1). **The rev-1
  `performLocally` one-liner is a NO-OP** — `ShardExecutors` are already `localAware()` and
  `TaskFactory.LocalAware` wraps every `execute()` with `ExecutorLocals.propagate()` (TaskFactory.java:139-146),
  so locals already flow to Shard-N. **Fix:** make teardown self-carrying — capture
  `ExecutorLocals.current()` in `Message.executeAsync` after `newSession`, run the stopSession
  callback under `try (captured.get()) { stopSession(); }`. Drop the performLocally change.
- **#3 HIGH — CL.ANY semantic break.** Sync `mutate` hints+returns-success for CL.ANY on
  WriteTimeout/WriteFailure (StorageProxy.java:1012-1017); `mutateAsync` doesn't. **Fix:** gate
  CL.ANY writes to the sync path in `mutateWithTriggersAsync` (rare CL; keeps the guarantee).
- **#5 HIGH — zombie retry.** If the Accord backstop fails `result` at deadline and Accord later
  returns `retry_new_protocol`, `onAccordComplete`/`onNormalComplete` call `attempt()` → real
  re-dispatch for an already-completed request. **Fix:** guard every retry re-entry with
  `if (result.isDone()) return;`.
- **#6 MED-HIGH — timer on single-threaded `scheduledFastTasks`** would serialize arm/cancel per
  write on one lock and muddy the re-profile. **Fix:** arm the per-request write-deadline timer on
  the **channel's `EventLoop`** (distributes across cores), threaded via `Dispatcher.RequestTime`
  (nullable `ScheduledExecutorService`; `RequestProcessor` supplies `channel.eventLoop()`; internal
  callers fall back to `scheduledFastTasks`). Precise `WriteTimeoutException` still built by the
  handler's `throwTimeout()`.
- **#7 MED — drain breaks.** `Server.close(force=false)` spins on `dispatcher.isDone()`
  (Server.java:206) = NT executor idle; post-flip that's true with writes in flight → `closeAll()`
  RSTs them. **Fix:** an in-flight async-request counter (AtomicLong) inc at dispatch / dec in the
  finalize; `isDone()` also checks it.
- **#8 MED — a throw inside the finalize is swallowed** by `safeExecute` → no FlushItem → client
  hang + permanent bytes-in-flight leak. **Fix:** the completion callback wraps finalize+flush in a
  last-resort try/catch that still flushes an ErrorMessage.
- **#9 warnings edges:** run `ClientWarn.resetWarnings()` INSIDE the restored-locals window (else it
  nulls the completing thread's own state); warnings raised on a locals-less completion thread (e.g.
  `checkMixedTimeSourceHandling` during a retry) are dropped — accepted, documented.

### Steps (rev 2)
1. **`mutateAsync`**: replace `FutureCombiner.nettySuccessListener(...).map(computeVerdict)` with
   sequential `andThenAsync` chaining of `handler.outcome()` in index order (inline; index-order-
   first-failure). `outcome()` = new `AbstractWriteResponseHandler` method: on `writeResult` signal
   run `computeVerdict` (success/typed-fail); arm a deadline timer (step 6) that fails with
   `throwTimeout()`'s WriteTimeoutException; cancel timer on completion; `writeResult` itself stays
   untouched (so `maybeTryAdditionalReplicas`/`decrementResponseOrExpired.isSuccess()` unchanged).
2. **`new processRequestAsync(...) -> Future<Response>`** in Dispatcher (mirrors private 4-arg up to
   `execute`): queue-time early-timeout returns the same un-finalized ErrorMessage; captureWarnings +
   init both coordinator-warnings; backpressure switch; validateNewMessage; `requests.inc()`;
   `exec = request.executeAsync(qstate, rt)`; capture finalize bundle; return `exec` composed with a
   completion that restores the bundle, runs the finalize, maps failure→ErrorMessage. Whole body in
   try/catch→ImmediateFuture.failure (ingress safety, though it runs on the NT worker not the loop).
3. **void 5-arg `processRequest`**: call `processRequestAsync`; in its completion callback build the
   FlushItem and `flush()` inside a last-resort try/catch (finding #8); inc/dec the in-flight counter
   (#7). NT worker returns immediately (the park removal). Static 4-arg `processRequest` untouched
   (InitialConnectionHandler STARTUP).
4. **Thread-local bundle** captured on the NT worker after `executeAsync`, restored around the
   finalize: `ExecutorLocals` (Tracing+ClientWarn) via `get()`/`close()`, `resetWarnings()` INSIDE
   that window (#9); `CoordinatorWarnings`/`CoordinatorWriteWarnings` via `captureAndClear()`/
   `restore()` before `done()`. MessageParams skipped (finalize doesn't read it; shard resets it).
   NO performLocally change (#2). For the shard-completion case locals already flow via `propagate()`;
   the bundle covers non-shard completions (RF≥3/timer).
5. **Self-carrying tracing teardown** in `Message.executeAsync` (#2): capture locals post-`newSession`,
   run stopSession under `try (captured.get())`.
6. **Timeout** on the channel EventLoop via `RequestTime` (#6); **Accord backstop** armed only when
   `accordResult != null`, guarded by `result.isDone()` (#5).
7. **CL.ANY gate** to sync in `mutateWithTriggersAsync` (#3). **In-flight counter** + `isDone()` (#7).

### Deferred to after the re-profile (written known-divergences)
- `mutate()`-level metrics: timeout/unavailable marks + latency + `updateCoordinatorWriteLatencyTableMetric`
  (mutateAsync omits them; CL.ANY is NOT deferred — gated above).
- `maybeTryAdditionalReplicas` still bounded-await (only when `liveUncontacted()` non-empty →
  transient replication; safe to defer).
- Coordinator **write-warnings fidelity** (`checkOutcome` update skipped) + warnings raised on
  locals-less completion threads (#9b).
- Admission control shape change (#10): NT-thread cap no longer bounds concurrency; add an overload
  case to verification.

### Verify (1e)
Re-profile i1 (RF=1, routing ON): `futex`/`unpark` + ~620k cs/s collapse; CPU at matched throughput
moves *toward* the OFF arm (not to it). Watch for a NEW hotspot (GEE — must be gone; timer lock — must
be distributed). Plus: a write dtest on the live async path; a **tracing-ON RF≥2** dtest (finding #2);
an overload/timeout case (#6/#10).

## Phase 2 — Commitlog completion as a future (removes residual apply-thread parks)

- [ ] 2a. Convert the park-based `syncComplete` WaitQueue (`CommitLogSegment.waitForSync`)
      into a callback registry; `CommitLog.add` returns a completion keyed on
      `(segmentId, position)`, completed by the existing syncer thread's `signalAll`.
- [ ] 2b. `Keyspace.applyInternal`: chain append → memtable → complete-on-sync; local ack
      fires from the completion (feeds Phase 1's callback). Handle segment-roll (#6) the
      same way.
- [ ] 2c. Bonus: allow `commitlog_sync=batch/group` under routing (currently gated off).
- [ ] 2d. Verify: apply thread shows no commitlog park under lag/roll; durability tests.

## Phase 3 — Memtable async backpressure (needs async-I/O substrate)

- [ ] Deferred until the async-I/O substrate exists. Yield the shard thread on pool-full,
      resume on reclaim; or per-shard memory reservation. (`MemtableAllocator.allocate`.)

## Not doing

- Eliding the kept memtable lock (`TrieMemtable`) — correct to keep until *all* writers
  route through shards (inbox-route end-state). Near-zero contention today via `tryLock`.
- Band-aids (spin-before-park / batch on the coordinator `get()`) — masks the symptom;
  the target is genuine async per the invariant.

## Gate to resume perf testing

A multi-node RF=3 test only becomes meaningful **after Phase 1** (ideally Phase 2) — before
that it re-confirms a known, fixable coordination cost.
