# Phase 1 Spec — JVM ↔ io_uring Binding (Greenfield Spike)

**Status:** Ready to execute once Phase 0 exit gate passes.
**Objective:** A correct, self-contained io_uring layer in `org.apache.cassandra.io.uring`
proving the JVM can drive io_uring file I/O — synchronous facade first, then batched
submission at QD 8–64 from one thread. This is the capability TPC requires (Phase 0 D4).
**Non-goals (explicit):** production read/write-path integration; cassandra.yaml config;
SQPOLL; eventfd/epoll reaping; Netty; IOSQE_IO_LINK chains; cancellation ops; aarch64
*testing* (design for it, test x86_64 only — rig is AMD).

Inherits Phase 0 decisions: D1 JNA raw syscalls + Unsafe (greenfield); D2 Unsafe ordering;
D3 arch assert; D4 benchmark framing. Kernel facts below were verified against man7.org,
kernel/liburing source, kernel.dk, docs.kernel.org (agent report 2026-07-05); anything an
implementer must re-verify against headers is marked ⚠ VERIFY.

---

## 1. Pinned kernel facts (implementation constants)

### 1.1 Syscalls — identical on x86_64 AND aarch64 (asm-generic table)
`io_uring_setup=425`, `io_uring_enter=426`, `io_uring_register=427`.
One constant set for both arches; availability probe still hard-fails on any other arch (D3).

### 1.2 Setup/enter/register flags (values verified against UAPI header)
- FEAT (read `io_uring_params.features` @ offset 20 after setup — MUST be read):
  `SINGLE_MMAP=1<<0` (5.4), `NODROP=1<<1` (5.5), `SUBMIT_STABLE=1<<2` (5.5).
  On the rig kernel (≥5.19 per Phase 0) all three are guaranteed; probe asserts them and
  records `features` in the availability report.
- Setup: `IORING_SETUP_CQSIZE=1<<3` (5.5), `CLAMP=1<<4`, `COOP_TASKRUN=1<<8` (5.19),
  `TASKRUN_FLAG=1<<9` (5.19), `SINGLE_ISSUER=1<<12` (6.0), `DEFER_TASKRUN=1<<13` (6.1).
- Enter: `IORING_ENTER_GETEVENTS=1<<0`.
- Opcodes: `READV=1`, `WRITEV=2`, `FSYNC=3` ⚠ VERIFY, `READ_FIXED=4`, `WRITE_FIXED=5`,
  `READ=22`, `WRITE=23` ⚠ VERIFY (all against `include/uapi/linux/io_uring.h`).
  Fsync flag: `IORING_FSYNC_DATASYNC=1<<0` ⚠ VERIFY.
- Register opcodes: `REGISTER_BUFFERS=0`, `UNREGISTER_BUFFERS=1`, `REGISTER_FILES=2`,
  `UNREGISTER_FILES=3`.
- mmap offsets: `IORING_OFF_SQ_RING=0`, `IORING_OFF_CQ_RING=0x8000000`,
  `IORING_OFF_SQES=0x10000000`.
- Limits: `IORING_MAX_ENTRIES=32768`; CQ default = 2× SQ; registered buffers portable
  floor 1024 buffers / 1 GiB each.

### 1.3 Struct layouts ⚠ VERIFY against `include/uapi/linux/io_uring.h` in sub-phase 1.1
Reference layout (all offsets bytes; validate empirically via the probe test in 1.1):
- `io_uring_params` (120 B): sq_entries@0, cq_entries@4, flags@8, sq_thread_cpu@12,
  sq_thread_idle@16, **features@20**, wq_fd@24, resv[3]@28, **sq_off@40** (40 B),
  **cq_off@80** (40 B). (Historic pitfall: omitting wq_fd+resv puts sq_off at 24 — wrong.)
- `io_sqring_offsets`: head 0, tail 4, ring_mask 8, ring_entries 12, flags 16, dropped 20,
  array 24.
- `io_cqring_offsets`: head 0, tail 4, ring_mask 8, ring_entries 12, overflow 16, cqes 20.
- SQE (64 B): opcode 0 (u8), flags 1 (u8), ioprio 2 (u16), fd 4 (s32), off 8 (u64),
  addr 16 (u64), len 24 (u32), rw_flags 28 (u32), user_data 32 (u64), buf_index 40 (u16).
- CQE (16 B): user_data 0 (u64), res 8 (s32), flags 12 (u32).

### 1.4 Behavioral contracts the implementation MUST honor
1. **EINTR is returned, not restarted** (kernel returns -EINTR directly; SA_RESTART does not
   apply; liburing does not swallow it). JVM signal activity makes this routine. → Every
   `io_uring_enter` call sits in a retry loop; on retry **recompute `to_submit` from ring
   state** (SQEs may already have been consumed by the interrupted call).
2. **Short reads happen on buffered reads** (post-2022 contract per QEMU/Axboe: "Short reads
   are rare but may occur. The remaining read request needs to be resubmitted."). → Sync
   facade loops: advance offset/buffer by `res`, resubmit remainder; 0 = EOF.
3. **Memory ordering** (liburing barrier usage, io_uring(7)):
   - SQ tail write → **release** (`Unsafe.putOrderedInt`)
   - CQ tail read → **acquire** (`Unsafe.getIntVolatile` — stronger, acceptable)
   - CQ head write → **release** (`putOrderedInt`)
   - SQ head read → plain is safe without SQPOLL; use `getIntVolatile` anyway (uniform, cheap)
   - Always-release on SQ tail even though non-SQPOLL could use a plain store — free on
     x86, correct everywhere.
4. **CQ overflow is a slow path, not data loss** (NODROP ≥5.5), but enter can return
   `EBUSY` (can't flush overflow) and, ≥5.19, `EBADR` (a CQE was dropped; error state
   clears). → Treat EBUSY as "drain CQ then retry"; treat EBADR as a hard error surfaced
   to the caller (an op's completion is lost — under our accounting that op must be failed).
   Size CQ to make this unreachable (see 1.5).
5. **EPERM from io_uring_setup** → diagnostic must name `kernel.io_uring_disabled` (sysctl
   ≥6.6; RHEL9 default-disables; Ubuntu/Debian default 0) and container seccomp as likely
   causes.
6. **Registered buffers**: must be anonymous memory (direct allocations qualify), pinned
   (RLIMIT_MEMLOCK charged) until unregister/ring close; the Java side must hold strong
   references from registration until unregister.
7. **Flag selection at ring create** (probe kernel version once):
   ≥6.1 → `SINGLE_ISSUER|DEFER_TASKRUN` (enforces submit==wait thread — matches our
   one-ring-per-thread model; a sync facade trivially satisfies the "must call enter to
   run task work" obligation); 5.19–6.0 → `COOP_TASKRUN`; else no flags. If setup with
   the chosen flags fails EINVAL, retry next tier down (kernel backports vary).

---

## 2. Design

### 2.1 Package layout (all new; no existing files modified in Phase 1)
```
src/java/org/apache/cassandra/io/uring/
  UringConstants.java   // every constant from §1.2/1.3, single source of truth
  UringNative.java      // JNA direct mapping — SEPARATE class from NativeLibraryLinux
  UringRing.java        // one ring; single-owner-thread; sync facade + batched API
  UringRings.java       // per-thread registry, shutdown, leak backstop
  UringAvailability.java// one-time probe: arch, OS, JNA, live setup, reason string
test/unit/org/apache/cassandra/io/uring/
  UringAvailabilityTest.java, UringRingTest.java, UringBatchTest.java,
  UringFixedBufferTest.java, UringLifecycleTest.java
test/microbench/org/apache/cassandra/test/microbench/uring/
  UringRawReadBench.java   // Phase 2 consumes this; 1.7 delivers the skeleton
```
Rationale for separate `UringNative`: JNA registers natives all-or-nothing per class
(`NativeLibraryLinux.java:30-44` documents the hazard) — an io_uring link failure must not
take down the existing libc bindings.

### 2.2 UringNative (JNA direct mapping)
- `Native.register(NativeLibrary.getInstance("c"))` in static init; catch
  `NoClassDefFoundError|UnsatisfiedLinkError|NoSuchMethodError` → `available=false`
  (mirror `NativeLibraryLinux.java:52-71`).
- JNA direct mapping does NOT support varargs → fixed-arity overloads, exactly the
  signatures used:
  `long syscall(long nr, int a, Pointer b)` (setup);
  `long syscall(long nr, int a, int b, int c, int d, Pointer e, long f)` (enter, sigmask
  NULL/0);
  `long syscall(long nr, int a, int b, Pointer c, int d)` (register);
  plus `Pointer mmap(Pointer, long, int, int, int, long)`, `int munmap(Pointer, long)`,
  `int close(int)`. All `throws LastErrorException`.
- mmap flags: `PROT_READ|PROT_WRITE`, `MAP_SHARED|MAP_POPULATE` ⚠ VERIFY MAP_POPULATE
  value (0x8000 on x86_64/aarch64) against headers.

### 2.3 UringRing — ownership & API
Single-owner-thread contract (asserted in debug: record owner thread at create, assert on
every call). Not thread-safe by design — TPC shape.

```java
public final class UringRing implements Closeable
{
    public static UringRing create(int sqEntries, int cqEntries) throws IOException;

    // --- sync facade (sub-phase 1.2) ---
    public int  readSync(int fd, long offset, ByteBuffer buf) throws IOException;  // full-read loop
    public int  writeSync(int fd, long offset, ByteBuffer buf) throws IOException; // full-write loop
    public void fsyncSync(int fd, boolean dataOnly) throws IOException;

    // --- batched/async (sub-phase 1.4) ---
    public long prepareRead(int fd, long offset, ByteBuffer buf);        // returns opId
    public long prepareWrite(int fd, long offset, ByteBuffer buf);
    public long prepareReadFixed(int fd, long offset, int bufIndex, int bufOffset, int len);
    public int  submit() throws IOException;                             // enter(n, 0, 0)
    public int  submitAndWait(int minComplete) throws IOException;
    public int  drainCompletions(CompletionHandler h);                   // non-blocking reap
    public int  awaitCompletions(int minComplete, CompletionHandler h) throws IOException;
    public int  inFlight();

    // --- fixed buffers (sub-phase 1.5) ---
    public void registerBuffers(ByteBuffer[] directAligned) throws IOException;
    public void unregisterBuffers() throws IOException;

    public interface CompletionHandler { void onComplete(long opId, int res); }
}
```

Semantics an implementing agent must NOT deviate from:
- **user_data = monotonically increasing long opId**; an in-flight slot table
  (`Op[] slots`, size = cqEntries, indexed `opId % slots.length` with occupancy check)
  holds `{opId, ByteBuffer ref, int origLen}`. The ByteBuffer strong ref IS the GC-pinning
  mechanism — held from prepare until its CQE is consumed. Completion demux validates
  `cqe.user_data` matches the slot; mismatch = state corruption = throw.
- **Backpressure:** `prepare*` throws `IllegalStateException` when
  `inFlight == slots.length` or SQ free space is exhausted (`sqTail - sqHead == sqEntries`
  checked with acquire read). Callers (TPC scheduler / benchmarks) must track capacity.
- **fd contract (documented in Javadoc):** caller keeps the `FileChannel` open until the
  op completes. The spike does not dup or register fds. (Registered files are follow-on
  work if Phase 2 shows fd-table lookup cost matters.)
- **Short reads in batched mode are NOT internally retried** — `res < origLen` is passed
  through to the handler; the sync facade loops, the async API reports. (A TPC scheduler
  must own resubmission policy; hiding it in the ring is the wrong layer.)
- **Negative `cqe.res` = -errno** → handler receives it raw; sync facade converts to
  `IOException(Errno.description)`.
- **close():** idempotent; requires `inFlight == 0` (throw IllegalStateException otherwise
  — in-flight kernel writes into freed mappings is UB); munmap all regions (2 or 3 per
  SINGLE_MMAP), close ringFd, mark closed. Constructor uses try/catch cleanup: any
  failure after `io_uring_setup` unmaps whatever mapped and closes ringFd before
  rethrowing.
- **mmap strategy:** read `features`; SINGLE_MMAP set (always, ≥5.4) → 2 mmaps (rings
  share one region sized `max(sqRingSize, cqRingSize)`, SQE array separate). Keep the
  3-mmap path as dead-simple fallback behind the feature check anyway (cheap, and makes
  the probe honest).
- **CQ sizing:** `create(sqEntries=64, cqEntries=256)` is the default for the spike
  (CQSIZE flag). 4× headroom makes overflow unreachable when reaping before each submit
  batch; CQEs are 16 B — 4 KiB total, negligible.

### 2.4 UringRings (lifecycle — sub-phase 1.3)
- `static UringRing threadLocal()` — lazy per-thread ring (64/256), registered in a
  `ConcurrentHashMap<Thread, UringRing>`.
- Shutdown hook (registered once) + `static void closeAll()`: closes rings of dead
  threads immediately and live-thread rings at JVM shutdown. Periodic reap of dead-thread
  entries on each new registration (walk map, `!thread.isAlive()` → close+remove).
- No Cleaner/finalizer on UringRing itself in the spike — the registry IS the backstop;
  document that a thread-local ring on a dying thread is reclaimed by the next
  registration sweep or closeAll. (Cleaner would need in-flight tracking to be safe;
  out of spike scope, noted as production TODO.)

### 2.5 UringAvailability (sub-phase 1.1)
Probe order, result + reason cached in static finals:
1. `os.arch` ∈ {amd64, x86_64, aarch64} else "unverified arch" (D3).
2. `NativeLibrary.osType == LINUX` else "not Linux".
3. `UringNative.isAvailable()` else "JNA link failure".
4. Live probe: `create(1, ...)` + close. EPERM → reason names `kernel.io_uring_disabled`
   sysctl + container seccomp. Record `features` word and chosen setup-flag tier
   (DEFER_TASKRUN / COOP_TASKRUN / none) in the reason string for the success case too —
   Phase 2 logs it per benchmark run.

---

## 3. Sub-phases

Ordering is strict; each delivers something testable on its own.

### 1.1 Constants + native layer + availability probe
Deliverable: `UringConstants`, `UringNative`, `UringAvailability`; a ring can be set up
and torn down on the rig.
Steps: derive every §1.2/1.3 constant from the rig's actual `include/uapi/linux/io_uring.h`
(record header version in progress.md); implement; **probe test** validates struct layout
empirically (setup a 4-entry ring → assert `sq_entries==4`, `cq_entries>=8`, features
contains SINGLE_MMAP|NODROP|SUBMIT_STABLE, sq_off/cq_off fields monotonically sane).
Acceptance: `UringAvailabilityTest` green on rig; SKIPS (not fails) on macOS; on Linux
with io_uring available it must run, not skip (assert both arms per `NativeLibraryTest`
idiom — outcome-assertion, not blanket Assume).

### 1.2 Ring core + sync facade
Deliverable: `UringRing.create/readSync/writeSync/fsyncSync/close`.
Must implement: §1.4 items 1 (EINTR loop), 2 (short-read/write loop), 3 (ordering), 4
(EBUSY/EBADR), constructor-failure cleanup, closed-state checks, direct-buffer-only guard
(heap buffer → IllegalArgumentException), buffer.position() honored (address+position,
position advanced by bytes transferred).
Acceptance (`UringRingTest`, all on real temp files, real FS — no jimfs, per DIO test
lesson): read at offset 0 and non-zero offset; read into non-zero buffer position;
read-back byte-equality after writeSync+fsyncSync (real-FS round-trip); EOF returns short;
errno path (read from closed/bad fd → IOException with errno text); closed-ring throws;
SQ index wrap (≥ 4×sqEntries sequential ops on a small ring). Green on rig.

### 1.3 Lifecycle registry
Deliverable: `UringRings` + shutdown hook.
Acceptance (`UringLifecycleTest`): thread-local identity on same thread; distinct rings on
distinct threads; dead-thread ring reclaimed by sweep (spawn thread, use ring, join, force
sweep, assert closed via package-private state); closeAll idempotent; no fd leak across
200 create/close cycles (compare `ls /proc/self/fd | wc -l` before/after — Linux-gated).

### 1.4 Batched submission + deferred reap ★ the spike's core deliverable
Deliverable: prepare*/submit/awaitCompletions/drainCompletions + slot table + demux.
Must implement: §2.3 semantics verbatim; setup-flag tiering (§1.4.7); recompute-to_submit
EINTR rule; user_data validation.
Acceptance (`UringBatchTest`, on rig): submit 64 reads (one enter), await all, byte-verify
every buffer against a pseudo-random file written by the test; interleaved
prepare/submit/drain at sustained inFlight≈48 for ≥100k ops without slot-table corruption
(opId/user_data mismatch would throw); backpressure throws at capacity; **single-thread
QD proof**: with 64 in-flight 4 KiB reads over an 8 GiB file (cache-cold via
posix_fadvise DONTNEED or O_DIRECT), demonstrate >4× the IOPS of the same thread doing
sequential pread-style readSync — this number is the headline the phase exists to produce
(record in progress.md; Phase 2 formalizes it).

### 1.5 O_DIRECT + registered buffers
Deliverable: READ_FIXED/WRITE_FIXED path + `registerBuffers`.
Must implement: buffers = `BufferUtil.allocateDirectAligned(chunkSize, blockSize)` (idiom:
`DirectThreadLocalByteBufferHolder.java:66`); alignment from `FileUtils.getBlockSize`
(`FileUtils.java:789`); O_DIRECT fds obtained by opening `FileChannel` with
`ExtendedOpenOption.DIRECT` + `NativeLibrary.getfd` (idiom: `ChannelProxy.java:218-221`,
`FileUtils.isDirectIOSupported` gate `FileUtils.java:745`); registration lifetime rule
§1.4.6 (strong refs held in UringRing until unregister; unregister refused while any
FIXED op in flight).
Acceptance (`UringFixedBufferTest`, rig): O_DIRECT batched reads (aligned offset/len)
byte-verified; misaligned O_DIRECT read surfaces -EINVAL cleanly; fixed vs non-fixed both
verified on ext4 AND /bench-xfs (mount from Phase 0).

### 1.6 fsync + write completion semantics
Deliverable: `IORING_OP_FSYNC` (+DATASYNC flag) in both sync and batched modes.
Acceptance: write+fsync+crash-consistency smoke (write, fsync, read-back via separate fd);
fsync of O_DIRECT-written file succeeds; batched write→fsync ordering documented (NO
IOSQE_IO_LINK in spike — caller orders by awaiting write completions before preparing
fsync; Javadoc states this explicitly).

### 1.7 Bench skeleton + rig verification sweep
Deliverable: `UringRawReadBench` JMH skeleton (params: qd ∈ {1,8,32,64}, mode ∈
{sync,batched}, direct ∈ {true,false}; body only — matrix execution is Phase 2);
runnable via `ant microbench -Dbenchmark.name=UringRawReadBench` (do NOT add to the
`microbench.exclude.pattern` blacklist, `build-bench.xml:26`).
Final sweep: full `ant test -Dtest.name=Uring*` on rig from a fresh `ant jar`
(verify class presence in the jar first — JAR-rebuild lesson); record kernel, features
word, flag tier, and all results in progress.md.

---

## 4. Exit gate (Phase 1 → Phase 2)

ALL of: 1.1–1.7 acceptance green on rig · the 1.4 single-thread QD proof recorded ·
macOS run shows SKIP not FAIL for all Uring tests · zero modifications outside
`io/uring/` + tests + bench (verify with `git diff --stat`).

## 5. Risks / known costs

- **JNA direct-mapping overhead per syscall** (~100–200 ns class of cost) is accepted for
  the spike; it biases AGAINST io_uring uniformly, so a positive QD result is
  conservative. If Phase 2 shows the JNA boundary dominating, the escalation path is a
  hand-JNI shim — which requires the native build machinery that doesn't exist in-tree;
  that cost goes into the Phase 3/4 effort estimate, not this spike.
- **Blocking enter is not interruptible from Java** — a hung device pins the thread
  (EINTR from JVM signals is the only wakeup). Acceptable for spike; production design
  (Phase 3) needs enter-with-timeout (`IORING_ENTER_EXT_ARG`) or IORING_OP_TIMEOUT noted
  as the mechanism.
- **RLIMIT_MEMLOCK** may cap registered buffers on the rig — check `ulimit -l` during 1.5;
  record in runbook.md.
