# Phase 1 Execution Findings — io_uring Binding (COMPLETE, exit gate PASSED 2026-07-08)

Handoff doc for the Phase 2 context. Everything a fresh session needs about the binding as
BUILT (spec.md/expected-changes.md describe it as designed; where they differ, this file wins).

## 1. Status and headline

- Phase 1 exit gate PASSED: 28 tests green on rig (0 skipped), macOS = SKIP-not-FAIL,
  QD proof recorded, diff contained to `io/uring` + tests + bench + the one sanctioned
  `CassandraRelevantProperties` line (`URING_ENABLED("cassandra.native.uring.enabled", "true")`).
- **★ Single-thread QD proof (UringBatchTest.testSingleThreadQueueDepthProof, rig):**
  cold 4 KiB reads, 8 GiB file on / (nvme1n1p2, ext4, PM9A3), one thread:
  - sync QD1 (readSync loop): **8,234 IOPS** (20,000 ops in 2,428 ms ≈ 121 µs/op)
  - batched QD64: **225,694 IOPS** (100,000 ops in 443 ms)
  - **ratio 27.41×** (gate was >4×). This is the capability TPC requires (Phase 0 D4).
- Bench skeleton smoke cell (qd=8, batched, buffered, 1 GiB, 2×2s iterations):
  122,574 ops/s → `build/test/output/jmh-result.json`.
- Rig probe reports: **features=0x3fff**, setup tier **SINGLE_ISSUER|DEFER_TASKRUN** (top tier).

## 2. What exists (file inventory)

```
src/java/org/apache/cassandra/io/uring/
  UringConstants.java    — all UAPI constants/offsets; single source of truth
  UringNative.java       — JNA direct mapping (package-private): setup/enter/register
                           syscall overloads, mmap/munmap/close, strerror, isMapFailed
  UringRing.java         — the ring: sync facade + batched API + fixed buffers (public)
  UringRings.java        — per-thread registry, sweep, shutdown hook (public)
  UringAvailability.java — cached one-time probe; features()/setupTier()/reason() (public)
src/java/org/apache/cassandra/config/CassandraRelevantProperties.java  [+2 lines: URING_ENABLED]
test/unit/org/apache/cassandra/io/uring/
  UringAvailabilityTest  (2)  UringRingTest (8)  UringLifecycleTest (5)
  UringBatchTest (7)  UringFixedBufferTest (6)
test/microbench/org/apache/cassandra/test/microbench/uring/UringRawReadBench.java
```
No build-file changes were needed (auto-compile/auto-discover confirmed in practice).

## 3. API surface as built (Phase 2 / PoC consumers read this, not the spec sketch)

```java
UringRing ring = UringRing.create(sqEntries, cqEntries);       // powers of 2, cq >= sq
// throws IOException("io_uring unavailable: <reason>") if UringAvailability fails

// sync facade — NOT mixable with in-flight batched ops (throws IllegalStateException);
// advances buf.position(); loops internally on short read/write; EOF returns short/0
int  n = ring.readSync(fd, offset, directBuf);
int  n = ring.writeSync(fd, offset, directBuf);                // 0-byte write => IOException
ring.fsyncSync(fd, dataOnly);

// batched — position/limit captured at prepare, NEVER mutated on completion;
// short reads passed through raw (res < origLen); negative res = -errno raw
long opId = ring.prepareRead(fd, offset, directBuf);           // strong ref held = GC pin
long opId = ring.prepareWrite(fd, offset, directBuf);
long opId = ring.prepareReadFixed(fd, offset, bufIndex, bufOffset, len);
long opId = ring.prepareWriteFixed(fd, offset, bufIndex, bufOffset, len);
long opId = ring.prepareFsync(fd, dataOnly);                   // ordering = caller's job
int consumed = ring.submit();                                  // ONE enter, no wait
int consumed = ring.submitAndWait(minComplete);                // enter + GETEVENTS
int drained  = ring.drainCompletions(handler);                 // non-blocking mmap'd reap
int drained  = ring.awaitCompletions(minComplete, handler);    // blocks via enter as needed
ring.inFlight();
ring.registerBuffers(uniformCapacityDirectBufs);               // pinned; refs held till unregister
ring.unregisterBuffers();                                      // refused while FIXED ops in flight
ring.close();                                                  // idempotent; refuses if inFlight>0

UringRing r = UringRings.threadLocal();                        // 64/256; sweeps dead threads
UringRings.closeAll();                                         // also runs as shutdown hook
```

Backpressure: `prepare*` throws IllegalStateException on ANY of: in-flight == cqEntries,
slot-index collision (per-index capacity, D-c), or SQ full of unsubmitted entries (== sqEntries).
Corruption (user_data/slot mismatch at demux) also throws IllegalStateException — distinguish
by message ("ring state corrupted").

## 4. Facts verified on the rig (zero deviations from spec §1.2/1.3)

- C probe (offsetof/sizeof, gcc, `/usr/src/linux-headers-6.8.0-124-generic/include/uapi/
  linux/io_uring.h`, LINUX_VERSION 6.8): syscalls 425/426/427; FSYNC=3, READ=22, WRITE=23,
  READ_FIXED=4, WRITE_FIXED=5; DATASYNC=1; setup flags incl. SINGLE_ISSUER=0x1000,
  DEFER_TASKRUN=0x2000; FEAT SINGLE_MMAP/NODROP/SUBMIT_STABLE = 1/2/4; register ops 0-3;
  mmap offsets 0/0x8000000/0x10000000; MAP_POPULATE=0x8000; params 120 B (features@20,
  sq_off@40, cq_off@80); sqring/cqring offset structs as pinned; SQE 64 B (user_data@32,
  buf_index@40, rw_flags==fsync_flags@28); CQE 16 B. Probe binary left at /root/uring_probe.
- Empirical layout test additionally reads kernel-written ring_entries/ring_mask back
  through our constants on a live mmap'd 4-entry ring (sq 4/mask 3, cq 8/mask 7).
- `sun.misc.Unsafe.putOrderedInt`/`getIntVolatile` present on rig JDK 17.0.19 (javap).
- ulimit -l = 8212268 KB. kernel.io_uring_disabled = 0.
- Tier fallback works by construction (EINVAL step-down loop) but on 6.8 the first tier
  (SINGLE_ISSUER|DEFER_TASKRUN) is accepted directly.

## 5. Implementation decisions taken within spec latitude (agent should not re-litigate)

1. **Availability probe structure:** raw-syscall setup probe determines features+tier
   (EINVAL step-down), THEN one real READ round-trip via package-private
   `UringRing.create(sq, cq, tier)` — the explicit-tier overload exists to avoid circular
   class-init between UringAvailability and UringRing. D-a's opcode-22 floor is therefore
   proven at probe time.
2. **EBADR** (dropped CQE, ≥5.19): sets a `poisoned` flag; enter throws IOException;
   `close()` is allowed despite phantom inFlight when poisoned. Unreachable in practice
   with CQ = 4× SQ.
3. **EBUSY**: surfaced as IOException telling the caller to drain and retry (not auto-drained
   inside enter — reap policy belongs to the caller layer).
4. **Sync facade uses the slot machinery** at QD1 and asserts inFlight==0 on entry —
   sync and batched modes are exclusive per ring at any instant (documented in Javadoc).
5. **Dead-thread ring with in-flight ops**: removed from registry but deliberately LEAKED
   with a warn log (unmap under in-flight kernel writes is UB; dead owner can never reap).
6. **SQE zeroed** (Unsafe.setMemory 64 B) before each field write — stale ioprio/flags/
   buf_index from a wrapped slot would corrupt ops otherwise.
7. **close() does NOT assert owner thread** (registry/shutdown-hook must close cross-thread);
   all other entry points assert ownership via `assert` (active under -ea, free in prod).
8. iovec array for registerBuffers: `Native.malloc` + free after the register syscall
   returns (kernel copies during the call). io_uring_params: pre-zeroed direct ByteBuffer
   via `Native.getDirectBufferPointer` (D-e as pinned).

## 6. Execution gotchas (cost time; don't rediscover)

- `ant test -Dtest.name="A,B"` (comma list) silently runs NOTHING — use a glob:
  `ant test -Dtest.name="Uring*"`.
- Rig has no `unzip`; verify jar contents with `jar tf`.
- Checkstyle import order: org.* group is contiguous-alphabetical (org.agrona before
  org.junit before org.slf4j, NO blank lines within), o.a.c its own last group.
- `ant rat-check` fails on pre-existing task-folder md (findings-tpc-paper.md) — NOT
  Phase 1 code; ignore/exclude, don't chase.
- JMH cell override works: `ant microbench -Dbenchmark.name=UringRawReadBench
  -Djmh.args="-p qd=8 -p mode=batched -p direct=false -p fileGiB=1 -wi 1 -w 1s -i 2 -r 2s"`.
  Results → `build/test/output/jmh-result.json`. The microbench fork already carries
  `--add-opens java.base/sun.nio.ch` (getfd works) and full jar+JNA classpath.
- Local (macOS) `ant build build-test` compiles everything incl. JMH annotation processing —
  catch errors locally before rsync; rig `ant jar` is ~12 s warm.
- Loop: edit local → `rsync -az --delete --exclude=build --exclude=.git ... 
  root@157.180.98.112:/root/repos/fork/cassandra-tpc/` → verify file landed → build/test
  (form with sshpass in runbook.md).

## 7. What Phase 2 must pick up (in priority order)

1. **Formalize the 27.4×** under the pinned matrix (phase-2 spec/expected-changes):
   fio cross-check first (device truth), then UringRawReadBench matrix
   qd{1,8,32,64} × mode{sync,batched} × direct{false,true} × fs{/bench-ext4,/bench-xfs}
   via the `dir` param. Gates G1-G4 with pre-committed consequences.
2. **IRQ/steering environment** (captured this session, NOT yet controlled): irqbalance
   ACTIVE, 26 nvme IRQ lines, watchdog on, **cpufreq governor = powersave**. Per the
   Enberg paper mapping (findings-tpc-paper.md §C1) this is the dominant tail variable —
   phase-2 preflight must pin governor (performance) + a steering stance and snapshot both
   per cell.
3. **Syscalls-per-op is the binding KPI** (phase-1 expected-changes §6): batched mode must
   show ≪1 enter/op under strace-window; the reap path is mmap-poll (drainCompletions),
   falling into enter(GETEVENTS) only when it must wait — measure and record the ratio.
4. **Known bias, don't "fix" silently:** JNA direct-mapping costs ~100-200 ns/syscall —
   uniform, biases AGAINST io_uring, so positive results are conservative. Escalation
   (hand-JNI) is a Phase 3/4 cost line, not Phase 2 work.
5. Bench-skeleton internals Phase 2 may improve: batched loop uses HashMap<Long,ByteBuffer>
   (boxing) per invocation; @OperationsPerInvocation=64 fixed; ring is created 128/512
   regardless of qd (qd enforced by the free-buffer pool). Cold-drop is per-iteration
   `NativeLibrary.trySkipCache` (buffered mode only). Fine for a skeleton; revisit only
   if JNA boundary doesn't dominate.
6. QD proof context for honesty: sync QD1 ≈ 121 µs/op is buffered-read latency INCLUDING
   one enter syscall each way; keep QD1 cells in the matrix (TPC loses at low depth —
   the honest floor, paper §C2).

## 8. Test-methodology notes (for phase-2/PoC test authors)

- Gate suites on `Assume.assumeTrue(UringAvailability.isAvailable())` in @BeforeClass —
  except availability itself, which outcome-asserts both platform arms (Linux MUST be
  available and fails loudly with the probe reason; non-Linux must be "not Linux").
- Real files on real FS only (jimfs rejects the fd path). `FileUtils.createDeletableTempFile`
  (tmp) / `FileUtils.createTempFile(prefix, suffix, dir)` (bench mounts).
- fds via `NativeLibrary.getfd(FileChannel)`; O_DIRECT via
  `FileChannel.open(path, READ, ExtendedOpenOption.DIRECT)` gated on
  `FileUtils.isDirectIOSupported(dir)`; alignment from `FileUtils.getBlockSize(dir)`;
  aligned buffers `org.agrona.BufferUtil.allocateDirectAligned(cap, align)`.
- /bench-ext4 and /bench-xfs arms Assume on mount presence (`new File("/bench-xfs").
  isDirectory() && isWritable()`) so they self-select on the rig, skip elsewhere.
