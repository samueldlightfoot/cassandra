# Progress — TPC Migration Planning

## 2026-07-05 — Session 1: research & scoping

**What happened**
- Investigated CASSANDRA-10989 origins: it's "Move away from SEDA to TPC", still Open,
  never merged upstream (lived in DSE 6). The "XFS/AIO" line means libaio only worked
  properly on XFS + O_DIRECT; buffered I/O was synchronous — the real blocker for
  page-cache-heavy Cassandra.
- Verified io_uring (Linux 5.1, 2019) removes that blocker: async buffered I/O across
  filesystems, submitter never blocks (io-wq fallback). Caveat: native fast-path parity
  lagged per-FS — XFS async buffered writes only in 5.19 (2022).
- Verified Seastar has an io_uring backend (default "when available" recently; AIO default
  through Scylla 23.2.x). ScyllaDB sees only modest gains because they use O_DIRECT and
  bypass the page cache — the opposite of Cassandra's situation.
- **Key reframe:** io_uring is *more* valuable to Cassandra than to Scylla; the specific
  10989 I/O objection is obsolete. But I/O was never the 95% — JVM has no io_uring file
  binding, and the execution-model rewrite is the real multi-year cost.

**Artifacts**
- `findings.md` — full research + sources
- `task_plan.md` — phased plan; scoped as a **de-risking spike first**, not a full-port kickoff

**Decisions**
- Do NOT frame as "restart the rewrite." Start with a JVM↔io_uring file-I/O spike +
  benchmark, then decide. Likely fallback = ship an io_uring I/O layer without the full TPC.

**Open / next**
- [ ] User to confirm scope = spike only.
- [ ] Pick binding approach (Panama/FFI vs JNA) and rig kernel/FS baseline (need ≥5.19 for
      fair XFS buffered-write test; test ext4 too).
- [ ] Then start Phase 1 prototype.

**Notes for next session**
- Reuse `NativeLibrary.java` JNA patterns from the flush-write-pacing branch if going JNA.
- Cross-check Phase 2 benchmarks against existing flush-write-pacing rig numbers.

## 2026-07-05 — Session 2: per-phase spec production

**Scope directive from user:** produce fully-decomposed specs per phase (0-4), sequential,
sub-agents within each phase for code-verified detail. Also: the `iouring-poc` branch is
UNRELATED to this task — do not base work on it (memory saved; note embedded in phase-0 spec).

**Done so far**
- Three Phase 0 explorers completed: JDK/build envelope (11/17/21, FFM blocked), native
  binding infra map (NativeLibrary idiom, ChunkReader/DataComponent seams), rig facts
  (157.180.98.112 all-ext4, kernel NOT RECORDED, methodology + lessons inventory).
- `phase-0-baseline/spec.md` FINAL: decisions D1 (greenfield JNA raw-syscall binding;
  Panama/jasyncfio/Netty/liburing rejected with evidence), D2 (Unsafe ordering idiom),
  D3 (arch assert), D4 (TPC-shaped benchmark question); sub-phases 0.1 rig verification →
  0.2 kernel ≥5.19 → 0.3 XFS partition [USER GATE: destroys /commitlog] → 0.4 closure.
- Session restarted (skip-permissions); kernel-API-facts research agent relaunched.

**Next**
- [ ] Write `phase-1-uring-binding/spec.md` when kernel-facts report lands (greenfield
      binding: native layer, ring lifecycle, sync path, batching/async, O_DIRECT+fixed
      buffers, writes/fsync, Linux-gated tests; requirements informed by adversarial
      analysis: EINTR retry, short-read loops, SQ-full guard, FEAT checks, teardown registry,
      buffer pinning).
- [ ] Then Phase 2 (benchmark spec: agents for JMH infra + fio cross-check design),
      Phase 3 (SEDA/state inventory agents), Phase 4 (upstream/CEP path).

## 2026-07-05 — Session 2 (cont.): all five phase specs complete

**Agents run (9 total):** 3× Phase 0 explorers (JDK/build, native+IO seams, rig/harness),
1× kernel-API verification (relaunched after session restart), 3× Phase 3 explorers
(SEDA/executors, shared-state census, arrival topology), 1× upstream landscape (JIRA/CEP/
dev@). One PoC audit agent ran before user ruled iouring-poc out of scope; its output was
discarded from the plan.

**Specs written:**
- phase-0-baseline/spec.md — D1-D4 pinned; rig provisioning incl. same-device fs A/B fix;
  0.3 is a USER GATE (repartitions nvme0n1, destroys /commitlog contents).
- phase-1-uring-binding/spec.md — greenfield JNA binding; kernel constants + contracts
  (EINTR loop, short-read loop, ordering table, EBUSY/EBADR, flag tiers) pinned from
  man7/kernel/liburing primary sources; 7 sub-phases; QD proof is the headline.
- phase-2-benchmark/spec.md — fio-vs-JMH two-level design; pinned matrix; gates G1-G4
  with pre-committed consequences.
- phase-3-execution-model/spec.md — evidence-forced reframe ("scheduler must agree with
  the data structures"); 5 hostiles ranked w/ evidence; increments I1-I5; hedge-set
  requirement.
- phase-4-decision/spec.md — D5-D7 (one CEP, no stealth; "finish what the data
  structures started" framing; link-don't-absorb ticket strategy); 4 sub-phase docs
  ending in recommendation memo.

**Corrections during session:** CEP-27 / CASSANDRA-17020 guesses were wrong (verified
unrelated); ext4/XFS same-device confound caught and fixed in phase-0 0.3.

**Next session:** execute Phase 0 (needs user go for 0.3 repartition; 0.1/0.2 can start
immediately — SSH facts capture + kernel check).

## 2026-07-05 — Session 2 (cont.): Phase 0 EXECUTED and complete

User cleared the 0.3 gate ("WAF work is done and data can be purged"). Executed:
- 0.1 ✅ rig facts captured → runbook.md (kernel was 5.15.0-168; Ubuntu 22.04.5; PM9A3 disks;
  io_uring enabled; memlock 7.8G; java 17; Cassandra not running; commitlog held only 66 MB).
  Bonus trap found: fstab comments mislabel devices (enumeration swapped) — lsblk-by-UUID only.
- 0.2 ✅ kernel 5.15 < 5.19 → installed linux-generic-hwe-22.04 → **6.8.0-124-generic** after
  reboot. /etc backed up first.
- 0.3 ✅ nvme0n1 repartitioned (identity guards passed): p1 300G ext4 /commitlog (nodiscard),
  p2 290G XFS /bench-xfs (mkfs.xfs -K), p3 304G ext4 /bench-ext4 (nodiscard). UUID fstab
  entries; ONE reboot proved both new kernel and fstab persistence. /data untouched.
- 0.4 ✅ D1–D4 re-read against execution findings — no amendments needed (6.8 confirms the
  top Phase-1 flag tier). Phase 0 EXIT GATE PASSED.

All exploration reports also written out verbatim (8 findings-*.md files, indexed in
findings.md) per user request.

**Next:** Phase 1 execution (greenfield binding per phase-1-uring-binding/spec.md), starting
with sub-phase 1.1 (constants + UringNative + availability probe, verified against the rig's
6.8 headers).

## 2026-07-07 — Session 3: 10994/10993 comment threads folded into specs

User supplied both Jira links; full threads fetched via JIRA REST (10994: 2 comments;
10993: 39 comments + changelog — fixVersion groomed 5.x→6.x by M. Semb Wever 2026-03-21).
**No decision (D1–D7) flipped; phase structure and io_uring plan unchanged.** Gaps closed:
- phase-3 §3.1: load-skew/work-stealing stance now a required design decision (Weisberg's
  temporal-skew concern); off-shard maintenance choice now cites the 10994 consensus.
- phase-3 §3.2: blocking extension points (custom auth/triggers/2i/UDF) added to the
  inventory; escape-pool story required.
- phase-3 §3.4: every increment now carries a tail-latency (p99) gate — the 2016 POC
  (+15% throughput, ~2× worse p99, never root-caused) would have passed throughput-only.
- phase-4 §1: ticket facts updated; §4.3 adds Ariel Weisberg + Michael Semb Wever to
  socialization and pre-answers objection #4 (the tail regression); D6 hook sharpened
  with 10994's "cannot be avoided ... filesystems that aren't xfs" line + Ellis's
  "threadsafe memtables" quote; §5 names the sequencing inversion (2016 non-blocking-first
  vs this plan ownership-first).
- findings.md §7: full digest of both threads with numbers.

**Coverage review (user asked "does this take us to TPC?"):** No — by design it ends at
the phase-4 recommendation; implementation specs for I1–I5 are deliberately post-gate.
Three genuine in-scope gaps found and fixed in phase-3 spec:
- Accord coexistence promoted from 3.5 risk-line to REQUIRED design section (3.1 item 6),
  incl. the IMMEDIATE-vs-inbox choice and shared-state enumeration.
- Paxos/SERIAL (+ batchlog, hints) added to 3.1 item 4 cross-shard enumeration
  (2016 POC needed a dedicated PaxosWriteTask — first-class path, not detail).
- 3.4 measurement harness pinned: Phase 2 harness is raw-file and can't measure
  increments; macro = cassandra-easy-stress flag-on/off per runbook methodology, micro =
  per-increment counter (I1 contendedPuts etc.); both tail-gated.
Also pinned in 3.1 acceptance: coordinator-side routing ceiling (no shard-aware client
protocol) must be stated in the design doc. Known conscious defers (not gaps): HOSTILE #5
per-shard caches, post-CEP process, SEP deletion end-game.

**Next:** unchanged — Phase 1 execution (sub-phase 1.1).

## 2026-07-07 — Session 3 (cont.): PoC-first reframe

User corrected the program's goal: **a working TPC PoC showing performance comes FIRST;
the CEP follows with those numbers.** "I see no reasons why it won't work, it's just a
case of finding the potential hurdles." Restructure applied:
- phase-2 §3: gate consequences reframed — gates classify the PoC's I/O shape (G1 fail →
  keep a small I/O pool, 10994's compromise by measurement); nothing kills the program.
- phase-3: status/intro retargeted at the PoC build (audience = implementing agent
  first); 3.4 increments = PoC build order, production freight marked CEP-era (D10).
- **phase-4-poc/spec.md created** (D8 PoC-before-CEP but mergeable-shaped, D9 success
  criteria pinned before code: p99 ≤ trunk at throughput ≥ trunk, D10 production freight
  deferred): 4.1 baseline+criteria → 4.2 increment builds I1–I5 → 4.3 hurdles.md
  (first-class deliverable) → 4.4 poc-verdict.md.
- phase-4-decision/ **renamed phase-5-decision/** (git mv); spec renumbered 5.1–5.4,
  evidence pack now PoC-headlined, recommendation options re-keyed to PoC criteria
  (a/CEP, b/targeted, c/park-with-data).
- task_plan.md goal + table updated; roadmap.md rewritten (13 steps, PoC-first).
- Memory saved: tpc-poc-first (don't reintroduce go/no-go framing; no dev@ contact
  before poc-verdict.md exists).

**Next:** still Phase 1 execution (sub-phase 1.1) — the binding is a PoC prerequisite
either way.

## 2026-07-07/08 — Session 4: expected-changes layer (class-level, all phases)

**User directive:** flesh out each phase (md only) with expected code changes — classes
and what must change — closing all open questions before an agent codes, and surfacing
unthought-of questions.

**Executed:** 6 parallel code-sweep agents (phase-1 binding detail; phase-2 JMH/fio
harness; I1 mutation path + shard runtime; I2/I3 reads + coordinator awaits; I4/I5
commitlog/writeOrder + inbound dispatch; Accord + extension points). Raw reports saved
verbatim as findings files (indexed in findings.md); distilled into per-phase
`expected-changes.md` (phases 1–5; phase 4's is the master inventory with the
consolidated user-decision list in §8).

**Headline discoveries (beyond what the specs had):**
- Phase 1: two spec exemplars (NativeLibraryTest outcome-assert, sync_file_range) exist
  only on the flush-write-pacing branch, not here; `osType==LINUX` is a fallback default
  (use FBUtilities.isLinux); 6 binding decisions pinned (opcodes READ/WRITE=22/23,
  buffer contract, slot semantics, registerBuffers rules, params allocation,
  URING_ENABLED kill-switch).
- Phase 2: `-Djmh.args` pass-through exists; named benches bypass the exclude list; p999
  extractable from jmh-result.json; fork classpath already includes jar+JNA; cold-drop
  can run in-bench (root). UringRawWriteBench had no owner → assigned 2.2.
- I1: spec's `StorageProxy.java:1995` was the batchlog overload (real site :1918→:2025);
  Stage-executor-swap shortcut REJECTED (opaque runnables, mixed stage traffic);
  ShardBoundaries are PER-TABLE → node-global executors + per-(table,key) routing +
  owner-check-with-lock-fallback (single-writer is violated today by hints/read-repair/
  paxos/replay/Accord); trunk default memtable is SKIPLIST (PoC must pin trie);
  commitlog batch mode would block shard threads (pin periodic).
- I2: the pread seam is ONE method — ChannelProxy.read:174; default disk_access_mode
  resolves data=standard (ring-replaceable already), mmap gives the ring nothing,
  `direct` is rejected at startup → PoC pins `standard`; ChunkCache misses already load
  on the calling thread (Caffeine ImmediateExecutor).
- I3: read/write timeouts are ALREADY delivered async (Callback-Map-Reaper +
  invokeOnFailure=true) — the parked thread is redundant as a timer; response flush is
  thread-safe from any thread (Flusher CLQ); ExecutorLocals do NOT follow responses
  (capture required); full R/W/Paxos/misc await inventory tabled.
- I4: commitlog segment-id allocator is already global-static → per-shard logs replay
  UNCHANGED (only discardCompletedSegments' early break assumes one sequence); the
  position-before-memtable-put contract is what makes option (b) expensive; writeOrder
  census = 3 start sites + 5 barrier sites; per-shard allocators remove only 1 of 2
  contended CASes (global SubPool CAS needs slack batching, measure first).
- I5: reuse the ProcessSmallMessage task, swap only the executor (preserves expiry/
  exception/capacity handling); in-JVM dtest delivery BYPASSES InboundMessageHandler —
  shared router helper required in Instance.receiveMessageRunnable or dtest coverage is
  vacuous; MutationStage tpstats freeze flag-on (A/B must use messaging internalLatency).
- Accord: applies user-table writes from AccordExecutor threads TODAY via the same
  applyInternal (journal WAL, no commitlog double-logging, but global writeOrder groups);
  Accord already has token-sharded command stores + a THREAD_PER_SHARD mode → NEW third
  coexistence option: align the two shardings 1:1. Inbox-routing would REMOVE two
  existing Accord blocking hazards (memtable-pool stall, MV-lock sleep loop).

**Open decisions for user (full list: phase-4-poc/expected-changes.md §8):** Accord
end-state (route/align/exempt); commitlog (a) vs (b) [evidence favors (a)]; I3
continuation executor + cut-line; shard-thread CPU budget in A/Bs; I1 step-2 hard
no-lock variant?; FlushItem release audit as I3 step 0.

**Next:** unchanged — Phase 1 execution (sub-phase 1.1), now with
phase-1-uring-binding/expected-changes.md as the implementation companion.

## 2026-07-08 — Session 4 (cont.): Phase 4 step decomposition pinned

Reviewed whether Phase 4 needs further breakdown now that expected-changes exists.
Verdict: 4.1–4.4 skeleton unchanged; the non-uniformity lives INSIDE 4.2's increments.
Pinned in phase-4-poc/spec.md 4.2 (template requirement — ordered step list with
per-step done-criterion):

- **I0 named**: shard runtime foundation (expected-changes §1) was an unnamed increment
  shared by I1/I2/I5 — now its own gated step inside I1's patch (no flag/A/B; gate =
  tests green + shutdown ordering).
- **I2 → I2a/I2b**: routed reads on sequential() vs loop-replacement + ring; isolates
  the Phase 1/2 dependency in I2b. (Two flags already existed for exactly this A/B.)
- **I3 → steps 0–3**: release audit → future plumbing (inert flag-off) → coordinator
  composition → Dispatcher async + limiter (only step 3 has the macro A/B).
- **I4 → I4a/b/c**: commitlog / writeOrder / allocator (doc already said "I4.5").
- I1 (two flags) and I5 stay single increments.
- **§8 item 5 (CPU oversubscription) reclassified as a 4.1 gate** — it shapes every
  A/B's honesty, so it's pinned with the criteria, not in an increment spec.

## 2026-07-08 — Session 5: Enberg ANCS'19 TPC paper consumed; hotspot notes added

Read Enberg/Rao/Tarkoma "The Impact of Thread-Per-Core Architecture on Application
Tail Latency" (ANCS 2019, Sphinx KV vs Memcached) and mapped it phase-by-phase.
New doc: **findings-tpc-paper.md** (paper summary §1, cross-phase consequences C1–C5
§2, per-phase/per-increment hotspot map §3). Pointer sections added in place:

- phase-0 spec 0.1: new step 2b — capture IRQ/steering environment (irqbalance, NVMe
  IRQ affinity, softirqs, pidstat -w) — the paper's dominant tail variable.
- phase-1 expected-changes §6: syscalls-per-op is the binding KPI; poll-don't-enter
  reaping; SQPOLL out of scope (busy-poll waste).
- phase-2 expected-changes §5: keep QD1 cells (TPC loses at low depth — honest floor);
  per-cell IRQ snapshot + preflight assert; iou-wrk CPU placement not just count.
- phase-3 expected-changes §3.6: name the model shared-something; wake-up (µs) not
  enqueue (ns) is the steering cost → idle-strategy + MPSC-contention notes for the
  I2b loop; continuation-executor evidence line; option-(b) commitlog = paper's
  messaging weakness.
- phase-4 expected-changes §7.5: per-increment hotspots — I1 low-concurrency
  crossover expected (gate on loaded tail); **I2 readSync blocks the whole shard**
  (miss-storm A/B cell required; sync caps ring at QD1/shard — don't oversell I2b);
  I3 limiter sized from concurrency-vs-tail curve; I4c = Sphinx LSMA prior art;
  I5 nets wake cost vs Stage hop, environment capture load-bearing.
- phase-5 expected-changes item 5: paper as CEP evidence — gives 10993's 2×-p99 two
  nameable candidate mechanisms (IRQ environment; wake-up cost at low load), both
  controlled for by PoC construction.

Also this session (earlier): story-driver-to-memtable.html reconciled with the
expected-changes sweeps (lock skipped-not-deleted, :1995→:1918/:2025, I3 executor
open, PoC sequential() not MPSC); roadmap.md items 5 and 7 fixed for the same drift.

**Next:** unchanged — Phase 1 execution (sub-phase 1.1); phase-0 0.1 now includes the
IRQ environment capture.

## 2026-07-08 — Session 5 (cont.): Scylla shard-aware drivers added as prior art

findings-tpc-paper.md I5 section gained a "three steering strategies" prior-art note:
client-side (ScyllaDB shard-aware drivers — connection-per-shard, handshake advertises
SCYLLA_NR_SHARDS + shard-aware port 19042 with shard = source port % nr_shards, zero
in-node hop; caveat: connection imbalance becomes shard imbalance), server-side message
passing (Sphinx, our I1/I5 — pays the wake-up), NIC steering (research frontier).
Phase-5 expected-changes item 5 + findings phase-5 section now cite it: the deferred
shard-aware client protocol (roadmap item 13) has shipping prior art — a sequencing
choice with a known destination, picked up only if post-I5 profiles show the
netty→shard hop is the remaining bottleneck.

## 2026-07-08 — Session 6: Phase 1 EXECUTED and complete (exit gate PASSED)

**★ Headline (the number Phase 1 exists to produce): single-thread QD proof = 27.41×.**
Cold 4 KiB reads over an 8 GiB file, one thread: sync QD1 = 8,234 IOPS (20k ops / 2,428 ms);
batched QD64 = 225,694 IOPS (100k ops / 443 ms). Gate was >4×. PM9A3, ext4 (/), kernel 6.8.

**Sub-phases, all acceptance green on rig (28 tests, 0 failures, 0 skipped):**
- 1.1 ✅ Every ⚠ VERIFY constant confirmed against the rig's 6.8.0-124 UAPI header via a
  compiled offsetof/sizeof C probe (FSYNC=3, READ=22, WRITE=23, DATASYNC=1,
  MAP_POPULATE=0x8000, params features@20/sq_off@40/cq_off@80, SQE/CQE layouts — ZERO
  deviations from spec §1.2/1.3). `Unsafe.putOrderedInt/getIntVolatile` javap-verified on
  rig JDK 17.0.19. Empirical layout test reads kernel-written ring_entries/ring_mask back
  through our offset constants on a live mmap'd ring. Probe reports **features=0x3fff,
  tier=DEFER_TASKRUN** (top tier, as Phase 0 predicted for 6.8).
- 1.2 ✅ UringRing sync facade (8 tests): offsets, non-zero position, write+fsync+read-back
  via separate fd, EOF short, errno text surfaces, heap-buffer reject, closed-ring throws,
  SQ wrap (10 full wraps on a 4-entry ring).
- 1.3 ✅ UringRings registry (5 tests): thread-local identity, distinct per thread,
  dead-thread sweep reclaim, closeAll idempotent+recoverable, no fd leak over 200
  create/close cycles (/proc/self/fd delta < 10).
- 1.4 ✅ Batched core (7 tests): 64 reads consumed by ONE enter; 100k sustained ops at
  inFlight≈48 with zero slot-table corruption; both backpressure arms (slot capacity AND
  SQ-full); short read passed through raw (2048 of 4096, position untouched); QD proof above.
- 1.5 ✅ O_DIRECT + registered buffers (6 tests): FIXED vs non-FIXED byte-verified on tmp fs,
  **/bench-ext4 AND /bench-xfs**; misaligned O_DIRECT read surfaces raw -EINVAL(-22);
  registration rules (uniform capacity, no double-register, range validation,
  unregister-refused-with-FIXED-in-flight). memlock headroom re-confirmed (ulimit -l ≈ 7.8 GiB).
- 1.6 ✅ fsync semantics: sync+DATASYNC arms in 1.2's round-trip; batched write→await→fsync
  ordering (caller-ordered, no IOSQE_IO_LINK) in UringBatchTest.
- 1.7 ✅ UringRawReadBench skeleton runs on rig via
  `ant microbench -Dbenchmark.name=UringRawReadBench` + `-Djmh.args` cell override
  (smoke cell qd=8/batched/buffered/1GiB → 122,574 ops/s; jmh-result.json written).
  Params qd∈{1,8,32,64} × mode∈{sync,batched} × direct∈{false,true} × dir (bench-mount
  pointable) — matrix execution is Phase 2's.

**Exit gate:** all acceptance green on rig from fresh `ant jar` (all 10 Uring classes
verified inside the jar first) · QD proof recorded above · macOS run = SKIP not FAIL for
all suites (availability test outcome-asserts the "not Linux" arm, runs 1/skips 1) ·
`git diff --stat` clean: only new files under io/uring + tests + bench, plus the
sanctioned +2-line CassandraRelevantProperties URING_ENABLED addition (expected-changes D-f).

**Implementation notes (deviations/decisions within spec latitude):**
- UringAvailability live probe: tier detection via raw setup with EINVAL step-down, THEN one
  real READ round-trip through UringRing.create(4,8,tier) (explicit-tier package-private
  overload avoids circular class-init) — D-a opcode-22 floor proven at probe time.
- EBADR poisons the ring (poisoned flag permits close() despite phantom inFlight).
- Dead-thread ring with in-flight ops: removed from registry but deliberately LEAKED
  (unmapping under kernel writes is UB; owner dead = nobody can reap). Logged as warn.
- checkstyle/RAT green for all new code (pre-existing rat complaint about
  findings-tpc-paper.md is task-folder md, not code).
- Rig checked: gcc present; header = linux-headers-6.8.0-124-generic UAPI (LINUX_VERSION 6.8).

**IRQ environment snapshot (phase-0 0.1 step 2b, captured this session):** irqbalance
ACTIVE, 26 nvme IRQ lines, kernel watchdog on, cpufreq governor = powersave. Phase 2
preflight must decide the pinning/steering stance before any A/B cell (paper C1).

**Next:** Phase 2 execution (phase-2-benchmark/spec.md) — fio cross-check + JMH matrix
formalizing today's 27.4× under pinned methodology; UringRawReadBench is ready for it.

## 2026-07-08/09 — Session 7: Phase 2 EXECUTED and complete (exit gate PASSED)

**★ Headline: G1 fails narrowly and diagnostically — one pinned core drives 260k IOPS
(62% of the 50-thread/12-core baseline's 418k; 67% with fixed buffers), CPU-saturated at
~3.8 µs/op — so the PoC keeps a small I/O pool for cache-miss reads, chosen by
measurement.** Full adjudication in phase-2-benchmark/verdict.md; data tables in
findings.md §8/§9.

- Level A: 32 pinned fio cells + annex, 3 iters, 0 failures, spreads ≤5%. Level B: 30
  JMH cells, 0 failures. Both under pinned stance (governor performance, irqbalance
  stopped, per-cell IRQ snapshots, samplers on CPU 0, bench on CPU 2).
- Gates: G1 FAIL-narrow (62%/67% vs 70%) · G2 FAIL-attributed (B/A 0.66–0.70 on the G1
  shape; profile: 25–35% JNA trampoline+syscall stub, GC≈0 → hand-JNI is Phase 3 cost
  line) · G3 FAIL-and-premise-inverted (XFS punts ≤32 iou-wrk, ext4 ≤2; throughput
  0.99×; moot for DIO-bound background writers) · G4 PASS both levels.
- Counter-headline: the binding BEATS native fio 1.77–1.84× on hot batched reads
  (1.37M cached reads/s/core) and 1.68–1.72× on DIO batched writes (288k w/s,
  iostat-verified) — the SINGLE_ISSUER|DEFER_TASKRUN tier fio 3.28 can't set is worth
  real money. QD1 B/A = 1.00 everywhere.
- Syscall KPI: pread 1.00/op, ring sync 1.00 enter/op, batched qd64 0.25 enter/op
  (strace2 whole-run method; attach method deprecated in runbook).
- **Binding bug found+fixed:** syncOp treated io_uring_enter's documented
  signal-after-submit short-SUCCESS as corruption ("drained 0") — flaky-failure class
  on I2a's exact path. Wait-loop fix mirrors awaitCompletions; 28/28 tests green on rig.
  UringRing.java change is uncommitted on tpc-migration.
- Environment finds: cpufreq was powersave (drivers now pin performance + restore);
  ~17% of kernel CPU is Intel IOMMU DMA mapping (intel_iommu=pt = future rig lever);
  fio "cold buffered" time_based cells are cache-fill profiles (recorded).
- Session also (user-driven): target-I/O-state pinned PROVISIONAL — page cache for
  foreground reads, DIO for background writers, full-Scylla fallback if buffered nerfs
  performance (findings §5.1, memory, phase-2 §2.5, phase-3/4 expected-changes §8 item 8).
  G1-gate story explainer written (story-g1-single-thread-70pct.html) + story-explain
  skill gained rules 9/10 from two writing corrections.

**Next:** Phase 3 (execution-model design docs) per phase-3-execution-model/spec.md —
inputs now include verdict.md consequences: I/O-pool-for-misses in the shard model,
hand-JNI cost line, DIO+ring for background writers.

## Phase 2 → Phase 3 handoff (2026-07-09, for a fresh context)

Read first: phase-3-execution-model/spec.md + expected-changes.md (the plan — not
restated here), phase-2-benchmark/verdict.md (gate outcomes + consequences),
findings.md §5.1/§8/§9, phase-1-uring-binding/findings-execution.md (binding API).

**1. Deviations from the Phase 2 plan (all recorded in verdict.md/spec §2.5):**
- Matrix grew: +a5-psync-nj1 per fs (so every JMH sync-write cell has a 1-thread native
  twin) → 32 fio cells; +4-cell annex OUTSIDE the pinned matrix (fixedbufs+registerfiles,
  ids `*-annex-*`) to classify the G1 shortfall.
- JMH read bench gained a third mode `pread` (FileChannel.read positional — the psync
  twin) beyond the planned sync/batched.
- strace attach method (strace-window.sh) abandoned — perturbs timing, gave one garbage
  window; strace-window2.sh (whole-run `strace -c -f` around a `-f 0` JMH run) is the
  method of record.
- G2 attribution used `-Dprofiler.opts="event=cpu;output=collapsed"` (flamegraph default
  isn't machine-readable).

**2. As-built interfaces Phase 3/4 consume:**
- Binding API: unchanged from phase-1 findings-execution.md §3 EXCEPT syncOp semantics
  hardened (see 3). Ring creation `UringRing.create(int sqEntries, int cqEntries)`;
  batched: `prepareRead/Write[Fixed](…) → submit() / submitAndWait(min) →
  drainCompletions(handler) / awaitCompletions(min, handler)`; sync facade
  `readSync/writeSync(int fd, long offset, ByteBuffer direct)`, `fsyncSync(fd, dataOnly)`.
- Bench params (UringRawReadBench): `-p file=<path> -p qd={1,32,64} -p
  mode={pread,sync,batched} -p direct={true,false} -p cold={true,false}`.
  UringRawWriteBench: `-p file -p qd -p mode={pwrite,sync,batched} -p bs -p
  pattern={seq,rand} -p direct`; buffered arms fsync DATASYNC every 64 MiB
  (FSYNC_INTERVAL_BYTES) through the API under test.
- Scripts (phase-2-benchmark/jobs/): gen-fio-jobs.sh → generated/manifest.tsv
  (id\tfs\tjobfile\tkind\tcache); run-fio-cells.sh / run-jmh-cells.sh (env pinning +
  samplers + per-cell snapshots, restore-on-exit trap); make-bench-file.sh (idempotent
  bench files: read 32g pseudo-random SHARED fio↔JMH, seqwrite 32g, randwrite 16g
  prealloc, on both /bench-*); parse-results.py (medians, spreads, B/A twins, gate
  numbers; skips partial JSONs); strace-window2.sh; run-fio-annex.sh.
- Results layout: `<cell>.json` (+`-iter<n>` for fio) + `<cell>.d/`{iostat,pidstat,
  iouwrk-count,iouwrk-psr,meminfo}.log + interrupts/governor/irqbalance snapshots;
  sweep-level `env/` (smart-before/after, interrupts-start/end, stance.txt).

**3. Code changed this session (uncommitted on tpc-migration):**
- `UringRing.java` syncOp: after `enter(1,1,GETEVENTS)` + drain, now loops
  `while (drained == 0) { enter(pendingSubmissions(), 1, GETEVENTS); drained += … }` —
  io_uring_enter returns the submit count as short SUCCESS (not EINTR) when a signal
  lands after SQE consumption (man-documented); pre-fix syncOp threw "expected exactly 1
  completion, drained 0" as a FLAKY failure. 28/28 Uring tests green on rig post-fix.
  The `drained != 1` tripwire retained (still fires on real corruption).
- `UringRawReadBench.java` rewritten per phase-2 expected-changes §1.1 (shared file, in
  bench drop_caches cold, deterministic full-file priming for hot, pread mode);
  `UringRawWriteBench.java` NEW. Both compile+checkstyle clean; matrix-proven on rig.

**4. Tested / deferred / broken:**
- Tested: full A+B matrix (0 failed cells), 28 unit tests post-fix, syscall KPI, all
  headlines double-signaled (iostat/pidstat/score-arithmetic).
- Deferred: hand-JNI (Phase 3 cost line, attacks the profiled 25–35% JNA share);
  `intel_iommu=pt` rig lever (untested, ~17% of kernel CPU is IOMMU DMA mapping);
  bench-loop submit()-every-pass shape (0.25 enter/op instead of ~1/64 — bench-only).
- Broken: nothing known. Old strace/ dir on rig contains the garbage sync attach window
  — ignore it, strace2/ is canonical.
- Level B percentile caveat: `-bm sample` divides by @OperationsPerInvocation(64) —
  batched "latency" percentiles are smoothed batch-time/64; op-level tails come from fio
  clat only.

**5. Decisions made (don't re-litigate):**
- Steering stance: irqbalance STOPPED during sweeps (static > daemon-moved), governor
  performance, bench/single-job cells on CPU 2, samplers CPU 0, nj50 cells unpinned
  (today's-architecture arm). Recorded per cell; restore-by-trap.
- Gate consequences applied as pre-committed (verdict.md): I/O pool for cache-miss reads
  in the PoC; G1 rests on Level A with B as supporting evidence; hand-JNI = cost line
  NOT Phase 2/3 work; G3 recorded, not re-architected around.
- Target I/O state (user, findings §5.1): PROVISIONAL — page cache for foreground reads,
  DIO for ALL background writers; performance sovereign; full-Scylla fallback
  (DIO + expanded ChunkCache) if buffered reads nerf perf. Phase 4 §8 item 8 owns the
  A/B; poc-criteria.md must restate it (phase-4 spec 4.1 updated).
- A4 fsync-policy mismatch (JMH interval vs fio end_fsync) accepted — punt detection was
  the point.

**6. Assumptions Phase 3 treats as given (all measured this session):**
- One pinned core drives ~260k cold 4k DIO read IOPS (278k fixed-bufs) = 62–67% of the
  50-thread whole-box baseline (418k); CPU-bound (~3.8 µs/op, 82% sys), NOT device-bound.
  A busy shard raises proportionally less (20% core ≈ 52k) — the I/O-pool rationale.
- The binding's flag tier is worth real perf: beats fio 1.77–1.84× hot batched reads
  (1.37M/s/core), 1.68–1.72× DIO batched writes (288k w/s). QD1 through the binding is
  free (B/A 1.00). Page-cache hit via ring ≈ 1.4 µs/op (syscall) vs userspace-cache hit
  ≈ 0 — the §5.1 asymmetry.
- Buffered ring writes: no throughput win at any QD (writeback-bound), terrible
  completion tails (p99 14–20 ms), and XFS (not ext4) punts to iou-wrk on 6.8 —
  background writers must go DIO+ring (never punts, any fs).
- Syscalls/op: sync facade = 1.00 enter/op (a shard doing sync misses pays a syscall per
  miss — I2a's readSync-blocks-shard warning stands); batched ≤0.25 enter/op achievable
  without trying.
- Rig env: PM9A3 ~500k 4k read ceiling near QD50; 12 cores; kernel 6.8; fio 3.28 lacks
  SINGLE_ISSUER/DEFER_TASKRUN (understates the binding's kernel path).

## 2026-07-09 — Session 8: Phase 2 CLOSED; Phase 3 started

- Stopped the two wedged Phase 2 rig monitors (fio/JMH sweep watchers; both sweeps had
  completed Jul 8 but the monitors' poll loops swallow SSH failure silently). Lesson
  added to tasks/lessons.md: phase closeout stops its monitors; poll loops emit on
  transport failure.
- **User reiteration at close (recorded in findings.md §5.1 + memory):** goal is optimal
  performance; the small I/O pool (G1 consequence) must be tested, but the expected
  end-state is "similar to ScyllaDB" — full-Scylla arm (DIO + expanded ChunkCache) is
  the prior, not the fallback. Both arms stay first-class in Phase 3 designs.
- phase-3 spec updated with Phase 2 outcomes before design work: status header carries
  the G1/G2/G3 consequences; I2 increment reflects I/O-pool-default with pure-shard-ring
  as A/B comparator; 3.5 gains the hand-JNI cost line; stale `StorageProxy.java:1995`
  corrected to `:1918→:2025`.
- Phase 2 closure commit made (results, jobs, verdict, syncOp fix, spec patches).
- Phase 3 begun per spec §4: 3.1 design-target.md drafted first (sub-agent + adversarial
  review), then 3.2/3.3 in parallel, 3.4/3.5 last by main agent.

## 2026-07-09 — Session 8 (cont.): Phase 3 EXECUTED — all five design docs, exit gate passed

Execution per spec §4: sub-agent drafted 3.1 → adversarial review (PASS-WITH-FIXES,
6 findings) → amended; 3.2 + 3.3 drafted in parallel against accepted 3.1 →
adversarial reviews (3.2 PASS-WITH-FIXES: 3 blockers; 3.3 FAIL: 1 blocker + 5
co-fixes) → both amended by their original agents; 3.4/3.5 written by main agent.
(Note: both reviewer agents died at the spend limit AFTER writing their review
files — verdicts recovered from disk, nothing lost.)

**The decisions that now bind Phase 4:**
- design-target.md: shared-something model named; D1 shard model (N=cores,
  per-(table,key) routing, owner-check-with-lock-fallback, sequential()→custom loop
  at I2b); D2 two I/O arms (identical shard-model bytes; pool×A / pool×B primary
  adjudication cells); D3 off-shard census incl. small I/O pool (default 8, seam at
  ChannelProxy.read, terminal size zero) + UserCodeEscape pool + cdc/legacy-2i
  predicate exclusions + periodic-only routing; D4 five routing points (verified
  lines); D5 two dispatch patterns (scatter/gather, owner-forwarding), exclusion
  list = normative predicate; D6 strict ownership, PendingTasks+misroutedPuts
  detection, per-table `shards` option as the split lever; D7 Accord end-state =
  (a) inbox-route (leads with fixing two Accord blocking hazards), (c) PoC de facto.
- design-async-coordinator.md: no new pool — split terminal (writes inline on
  acking thread, read materialization + audit/FQL on requestExecutor); park guard +
  no-inline-local-work rule (execute() never mEI off park-licensed threads);
  cut-line ratified CL-aware (SERIAL reads behind the line — Paxos v1's missing
  async timeout FORCES all Paxos behind); ops+bytes backpressure, event-loop-owned
  tryAcquire, release-signalled WaitQueue, 1024 default; per-request deadline task
  is the timeout AUTHORITY (silent local-leg drops otherwise hang+leak); FlushItem
  audit DONE: safe as-is + three step-0 build requirements.
- design-hostiles.md: ShardedOpOrder(N) (flag chooses N, one code path);
  carrier OVERTURNED to OpOrder.Group owner field (Memtable.accepts public API is
  the binding constraint); composite issue-all-then-await-all at the 5-site census;
  commitlog (a) with review-forced coverage protocol — manager-banded segment ids +
  per-manager bound vectors + IntervalSet N-intervals (single pair + id-terminated
  discard RETRACTED as silent data loss); ONE sync service + W-SYNC watch item;
  async group-commit decided (CEP-era, after I3); allocator-per-shard unconditional,
  slack batching measurement-gated; H5 deferred with un-defer trigger.
- increments.md (★): order I0→I1→I2a→I2b→I3(0-3)→I4(b→a→c)→I5 with dependency
  graph; every entry has flag/claim/measurement(p99-gated)/rollback/deps/
  PoC-vs-CEP-era; harness pinned (tail gate cites the 2016 +15%-throughput-2×-p99
  lesson); hedge set = I3 (strong), I4c step 1 (quiet), I1 (conditional).
- effort.md: bands I0+I1 S-M, I2a S, I2b M, I3 L, I4 L (grew with the coverage
  protocol), I5 S; program ~6mo optimistic / ~9mo likely (or ~5-6mo wall-clock at
  two lanes); hand-JNI = M (3-5wk) priced-not-scheduled with pull triggers;
  10-risk register with owners; three-way comparison for Phase 5.

**Cross-doc consistency maintained:** design-target amended twice (carrier +
attribution tier + commitlog coverage clause from design-hostiles §6; hop-7 split
terminal from design-async F2); phase-4-poc/expected-changes.md folded to match
(predicate exclusions + periodic check into §1; §5 commitlog/OpOrder rows; §8
items 1-4,7 marked DECIDED/DONE — 5,6,8 remain open, 5 gates 4.1).

**Exit gate (spec §5): PASSED** — five docs exist; every §2.2 hostile chosen or
explicitly deferred (H5 + readOrdering with triggers); increments have
flag/measure/rollback/band; hedge set identified. Committed as Phase 3 closure.

**Open for user before Phase 4 code:** §8 item 5 (oversubscription stance — gates
4.1 criteria), item 6 (I1 step-2 hard no-lock variant?), item 8 (two-arm
adjudication — runs as I2b cells, user prior = full-Scylla recorded).

**Next:** Phase 4 (phase-4-poc/spec.md) — 4.1 baseline + criteria (needs the item-5
pin), then I0/I1 build.

## 2026-07-09 — Session 8 (cont.): the two open USER items answered

User pinned both remaining pre-Phase-4 decisions (phase-4 §8 updated):
- **Item 5 (CPU budget):** accept oversubscription for I1/I2/I4/I5, documented per
  cell as a tail-win floor; NTR shrinks only in I3's cell where the shrink is the
  claim. 4.1 pins this into poc-criteria.md.
- **Item 6 (I1 step 2):** owner-check skip only — no hard no-lock ceiling build.
Item 8 (I/O arms) remains open by design — adjudicated by I2b's pool×A / pool×B
cells; user prior = full-Scylla, recorded.

**Phase 4 is unblocked.** Next: /phase-start tasks/tpc-migration-planning/phase-4-poc
→ 4.1 baseline + poc-criteria.md (both pins restated there), then I0/I1.
