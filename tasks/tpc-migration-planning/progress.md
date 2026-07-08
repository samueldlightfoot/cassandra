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
