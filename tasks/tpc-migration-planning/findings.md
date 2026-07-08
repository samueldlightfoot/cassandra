# Findings — TPC Migration & io_uring

Research date: 2026-07-05. Sources cited inline; all verified at source (not from memory).

## 1. What CASSANDRA-10989 actually is

- **Title:** "Move away from SEDA to TPC" (Thread-Per-Core). Opened 08/Jan/2016, last
  updated 16/Apr/2019. **Still Open / Unresolved** in Apache Cassandra.
- It is a **design proposal**, not a bug.
- TPC was implemented in **DataStax DSE 6** but **never merged upstream**. There is no
  OSS base to build on.

### The XFS/AIO line (often misremembered)
The design doc says:
> "One notable exception from the original proposal is that we cannot, unfortunately, use
> linux AIO for file I/O, as it's only properly implemented for xfs."

Phrasing is the reverse of "XFS is broken" — XFS was the *one* filesystem where libaio
worked; everything else was the problem. Because AIO couldn't be relied on generally, the
TPC design punted cache-missing I/O to a separate threadpool.

## 2. The real 2016-era blocker (deeper than "XFS-only")

Classic Linux AIO (`libaio` / `io_submit`) had two hard limits:
1. **Only `O_DIRECT` is truly async.** Buffered I/O runs synchronously inside `io_submit()`.
2. **Even with O_DIRECT, only some filesystems implement the async path.** On ext2/3/4,
   jfs, nfs, `io_submit()` silently blocks and waits instead of erroring — you *think*
   you have async I/O but get synchronous behavior. XFS had the most complete
   implementation, hence "only properly implemented for xfs." Even on XFS, appends /
   allocation / metadata could still block.

**Why this specifically blocked Cassandra:** Cassandra is heavily page-cache / mmap /
buffered dependent (unlike ScyllaDB). Adopting libaio would have forced abandoning the
page cache. That dilemma is what stalled the I/O portion of the design.

## 3. io_uring — what changed since 2019

- Merged in **Linux 5.1 (May 2019)** — ~1 month after 10989's last update.
- **Async for both buffered AND direct I/O**, across filesystems (not just XFS).
- **Submitting thread never blocks on any filesystem.** Anything that would block is
  offloaded to kernel worker threads (**io-wq**) and the call returns immediately. This
  replaces libaio's silent-block failure mode with graceful degradation.

### Important caveat: native fast-path parity lagged, filesystem-by-filesystem
"Never blocks the caller" ≠ "true inline async on every FS." Two paths exist:
- **Fast path** — genuinely non-blocking inline submission (`IOCB_NOWAIT`); requires the
  filesystem to support it for that operation.
- **Slow path** — punt to io-wq worker threads (real thread doing blocking work;
  context-switch overhead).

Native fast-path support was added per-FS, per-operation, over years — **XFS kept leading**:
- io_uring core: 5.1 (2019)
- async buffered *reads*: ~5.7 (2020)
- async buffered *writes* on **XFS**: **5.19 (June 2022)** — LWN series literally titled
  *"io-uring/xfs: support async buffered writes"*; before it, buffered writes ran only in
  the slow path via io-workers. XFS reported **>3× throughput at io-depth 1** once on the
  fast path.

**Verdict:** The app-visible XFS-only constraint is gone. A residual XFS *performance*
edge and filesystem-specific fast-path work persisted well into the 2020s.

## 4. Does Seastar use io_uring? — Yes, cautiously

- Seastar has had an **io_uring reactor backend since ~2020** (network + buffered disk +
  direct disk in one interface).
- Became the **default "when available"** recently, but **AIO stayed default through
  ScyllaDB 23.2.x** — deliberate slow rollout. `Reactor backend: io_uring` exercised in
  2023–2024 test logs.
- **Why cautious matters:** ScyllaDB's own writeup — io_uring is *"a bit faster than
  linux-aio, but nothing revolutionary"* **for O_DIRECT**. Scylla bypasses the page cache,
  so the one transformational io_uring feature (async *buffered* I/O) gives them nothing.

## 5. The reframe for Cassandra specifically

The io_uring cost/benefit is **inverted vs ScyllaDB**:
- Scylla = O_DIRECT everywhere → io_uring gain modest → slow to adopt.
- Cassandra = page-cache / mmap / buffered heavy → io_uring's async buffered I/O is
  **exactly the missing capability** from 2016 → genuinely valuable, arguably *more* so
  than to Scylla, because Cassandra *wants* to keep the page cache.

So the specific I/O objection written into 10989 is genuinely obsolete.

## 6. Honest reality check — I/O was never the 95%

| Blocker | Status post-io_uring |
|---|---|
| libaio buffered-async / XFS-only | ✅ Solved by io_uring |
| Execution-model rewrite (SEDA → shard-per-core, thread-local state, message passing, lock removal) | ❌ Untouched — the multi-year bulk |
| **JVM has no io_uring in the JDK** | ⚠️ Partial. Netty io_uring transport is in **4.2 but network-only**. Async *file* I/O via io_uring from Java needs **Panama/FFI bindings written by us**, or Project Loom's planned io_uring file support (not shipped). |
| TPC never merged to OSS (lived in DSE 6) | ❌ No upstream base |

**JVM file-I/O row is the practical crux.** No drop-in exists. But this branch already
adds JNA bindings in `NativeLibrary.java` — adding io_uring file bindings (JNA or
Panama/FFI) is the same muscle and is a self-contained, testable spike.

## 7. The 10994/10993 comment threads (fetched via JIRA REST 2026-07-07)

Ticket facts: 10994 "Move away from SEDA to TPC, stage 1" — Open, 2 comments (both
2016-01-11). 10993 "Make read and write requests paths fully non-blocking" — Open,
39 comments (2016-01 → 2017-05); fixVersion re-groomed 5.x → 6.x by Michael Semb Wever
on 2026-03-21 (still tracked upstream, not tombstoned).

### 10994 — the two comments
- **Ariel Weisberg:** (a) TPC pays off most for small tasks on in-memory data ("all
  writes, some reads"); big tasks like compaction lose nothing staying on plain threads.
  (b) Advocates NON-uniform cores — dedicated file-I/O and network-I/O threads/cores can
  beat fully homogeneous designs even counting cross-core messaging. (c) "schedule
  hundreds of thousands of disk IO requests asynchronously ... seems like a hard thing to
  make happen from Java" — the 2016 form of exactly what Phase 1/2 exists to prove
  io_uring now solves. (d) **"My biggest concern with TPC is load skew especially
  temporal skew"** — with static ownership and no work stealing, one slowed core
  eventually holds all outstanding requests.
- **Aleksey Yeschenko:** agrees to keep compaction/flush/streaming off the loops until
  numbers justify more; fully-homogeneous cores would buy single-reader data structures
  and unified per-core prioritisation, but the implicit cost is "the amount of work
  necessary to move *everything* to TPC". Ownership partitioning deliberately deferred
  to stage 2 (stage 1: "every worker thread will be able to serve requests for any token").

### 10993 — what 39 comments settle
- **The style war (explicit FSMs vs chained futures vs RxJava)** consumed the ticket.
  Resolution: start with RxJava "to prove out if moving to TPC is even worth the effort"
  (Luciani, 2016-08); the work then vanished into DSE 6. Aleksey's constraint stands:
  *"One does not outsource project's core competency to an external library"* — async
  STYLE was negotiable, an external dependency for core execution management was not.
- **Tyler Hobbs POC benchmark (2016-08; in-memory reads, single node, c4.3xlarge):**
  POC 229k reads/s vs trunk 199k (**+15%**); median ~16 ms vs ~22 ms; **tails ~2× worse**
  (p95 71 vs 47 ms, p99 160 vs 111 ms, p99.9 435 vs 225 ms). Never root-caused. FSM and
  RxJava prototypes tied on throughput.
- **Jonathan Ellis:** the real win is "not needing to use threadsafe memtables", not the
  async-style choice — 2016's own conclusion pointing at what phase-3 now calls "make the
  scheduler agree with the data structures".
- **Netty friction (historical):** Hobbs couldn't merge a custom event loop into Netty's
  (`NioEventLoop` is final + `instanceof` check in `AbstractNioChannel.isCompatible`);
  piggybacked an `EventLoop.cycle()` task per netty tick instead. At saturation the netty
  task-queue overhead measured ~1% — the plan's separate-shard-threads design sidesteps
  this entirely.
- **Extension-point problem (from the description):** custom auth implementations, 2i
  hooks, triggers, UDF/UDAs may block and cannot be forced non-blocking → need an
  off-loop escape pool.

### Consequences folded into the specs (2026-07-07)
1. Phase 3.1: the load-skew stance (strict ownership vs bounded work-stealing) is now a
   required design decision — Weisberg's temporal-skew scenario is the test case.
2. Phase 3.2: blocking extension points (auth/triggers/2i/UDF) added to the call-site
   inventory with an escape-pool requirement.
3. Phase 3.4: every increment now carries a TAIL-latency (p99) gate — the 10993 POC would
   have PASSED a throughput-only gate and shipped a 2× p99 regression.
4. Phase 5 (the CEP phase; numbered 4 when written): tail numbers pre-answered as objection #4; Michael Semb Wever added to
   socialization; D6 hook sharpened with 10994's "cannot be avoided, as we have to
   support filesystems that aren't xfs" line; Aleksey's no-external-library rule cited
   in support of the greenfield in-tree binding (D1).
5. Sequencing inversion made explicit: 2016 chose non-blocking-first/ownership-later and
   its POC lost the tail; this plan is ownership-first (I1 before I3) — consistent with
   Ellis's "threadsafe memtables" comment and usable as the answer to "DSE tried this".

## Sources
- CASSANDRA-10989 — https://issues.apache.org/jira/browse/CASSANDRA-10989
- CASSANDRA-10994 / 10993 comment threads — JIRA REST, fetched 2026-07-07
- io_uring (5.1 date) — https://en.wikipedia.org/wiki/Io_uring
- Efficient IO with io_uring (Axboe) — https://kernel.dk/io_uring.pdf
- async buffered writes for XFS (5.19) — https://lwn.net/Articles/896909/
- async buffered reads — https://lwn.net/Articles/821274/
- io_submit blocks (Cloudflare) — https://blog.cloudflare.com/io_submit-the-epoll-alternative-youve-never-heard-about/
- ScyllaDB "io_uring & eBPF" — https://www.scylladb.com/2020/05/05/how-io_uring-and-ebpf-will-revolutionize-programming-in-linux/
- ScyllaDB Database Internals: Working with IO — https://www.scylladb.com/2024/11/25/database-internals-working-with-io/
- Seastar io_uring backend patch — https://groups.google.com/g/seastar-dev/c/HgituclCGOA
- Netty io_uring transport — https://github.com/netty/netty-incubator-transport-io_uring
- Async IO with Java + Panama/io_uring — https://javapro.io/2025/10/16/async-io-with-java-and-panama-unlocking-the-power-of-io_uring/

## Full exploration reports (written out 2026-07-05)

All agent exploration/research reports are preserved verbatim next to their phase specs:

- `phase-0-baseline/findings-jdk-build.md` — JDK 11/17/21 envelope, FFM blocked, JNA 5.13.0
  idiom, dep/license machinery, no in-tree native builds
- `phase-0-baseline/findings-io-seams.md` — NativeLibrary architecture, O_DIRECT plumbing,
  writer/reader seams (DataComponent.buildWriter, ChunkReader/FileHandle), test idioms,
  aligned-buffer utilities, extension-point table
- `phase-0-baseline/findings-rig-harness.md` — rig facts, easy-cass-stress + agent-harness +
  wrapper scripts, established bench methodology, JMH infra, operational lessons
- `phase-1-uring-binding/findings-kernel-api.md` — primary-sourced io_uring facts: syscall
  numbers (both arches), FEAT flags, registered buffers, SQPOLL, task-run flag tiers,
  O_DIRECT punt rules, short-read contract, CQ sizing, EINTR semantics, ordering table,
  io_uring_disabled sysctl
- `phase-3-execution-model/findings-seda-stages.md` — full Stage/executor inventory, SEP
  scheduling model, verb→stage table, non-stage pools, inline-execution paths, thread census
- `phase-3-execution-model/findings-shared-state.md` — hot-path shared-state census with
  shardability classification; the 5 TPC hostiles ranked with fix shapes; token-machinery seeds
- `phase-3-execution-model/findings-arrival-topology.md` — CQL + internode inbound topology,
  8 thread-handoff points, blocking-coordinator evidence, earliest token-known points,
  prior-art absence
- `phase-5-decision/findings-upstream.md` — 10989 state (dormant, zero comments), CEP process,
  no competing CEP, io_uring JIRAs (19887 is ours), DIO umbrella precedent, JDK roadmap,
  dev@ temperature (greenfield)

Second sweep (2026-07-07/08, "expected code changes" round — 6 agents, class-level,
distilled into per-phase `expected-changes.md` files):

- `phase-1-uring-binding/findings-impl-detail.md` — JNA/Unsafe/buffer idioms with exact
  anchors, checkstyle/build friction, test wiring, the 6 code-level decisions the spec
  left open; branch caveat: flush-write-pacing exemplars absent here
- `phase-4-poc/findings-i1-mutation-apply.md` — full local-apply call graph (both entry
  points), ShardBoundaries lifecycle (per-table!), shard-runtime options, lock-deletion
  safety analysis (owner-check + fallback), routing predicate, skiplist-default trap
- `phase-4-poc/findings-i2-i3-reads-coordinator.md` — read call graph to the
  ChannelProxy.read seam, disk_access_mode table (mmap gives the ring nothing; direct
  rejected), complete coordinator blocking-await inventory (R/W/P/M tables), reaper
  timeout discovery, Dispatcher/Flusher thread-safety, ExecutorLocals hazard
- `phase-4-poc/findings-i4-i5-commitlog-dispatch.md` — commitlog chain + every shared
  structure, option (a)/(b) fact base (global segment-id allocator ⇒ replay unchanged),
  writeOrder census (3 start sites, 5 barrier sites), allocator two-CAS split, I5
  allowlist + dtest-delivery-bypass discovery
- `phase-3-execution-model/findings-accord-extension-points.md` — Accord topology
  (token-sharded command stores, THREAD_PER_SHARD mode exists), write path (journal WAL,
  no double-logging, same applyInternal), hostile-by-hostile touch table, extension-point
  census, the 9 new coexistence questions incl. the align-shardings option

Rig live-capture facts: `runbook.md`. Phase 0 execution results: `progress.md`.
