# Phase 1 — Expected Code Changes

Companion to `spec.md`. Implementation-level inventory verified against this branch
(tpc-migration @ 50ddce8455) on 2026-07-07. Closes the code-level decisions the spec
left open; an implementing agent should need zero discovery beyond ⚠ VERIFY items
(rig-header checks).

**Branch caveat (spec correction):** this tree does NOT contain the flush-write-pacing
additions. `NativeLibraryLinux` has no `sync_file_range` (natives = mlockall…getpid,
`NativeLibraryLinux.java:73-81`) and `NativeLibraryTest` has NO outcome-assertion
exemplar (only `testSkipCache` + `getPid`). The 1.1 acceptance's "per NativeLibraryTest
idiom" means: write the platform-conditional assert pattern fresh from the spec's own
description — there is no code to copy.

## 1. New files (complete list — nothing outside these)

```
src/java/org/apache/cassandra/io/uring/
  UringConstants.java  UringNative.java  UringRing.java  UringRings.java  UringAvailability.java
src/java/org/apache/cassandra/config/CassandraRelevantProperties.java   [MODIFIED — one enum line, see D-f]
test/unit/org/apache/cassandra/io/uring/
  UringAvailabilityTest.java  UringRingTest.java  UringBatchTest.java
  UringFixedBufferTest.java   UringLifecycleTest.java
test/microbench/org/apache/cassandra/test/microbench/uring/UringRawReadBench.java
```
Zero build-file changes: `test/unit/**` + `test/microbench/**` auto-compile
(`build.xml:1256,1260`); `ant test -Dtest.name=Uring*` auto-discovers (`build.xml:1644`);
JMH resolves by regex (`build-bench.xml:126`); named benches bypass the exclude list.
JNA 5.13.0 already bundled (`lib/jna-5.13.0.jar`, `parent-maven-pom.xml:555-565`).

## 2. Pinned implementation decisions (were open; now closed)

- **D-a. Opcodes: non-vectored `READ=22`/`WRITE=23`** (no iovec allocation), NOT
  READV/WRITEV. This raises the binding's effective kernel floor to 5.6 — acceptable
  (rig = 6.8; Phase 0 already requires ≥5.19). The availability probe's live-ring test
  therefore submits one real READ op (not just setup/teardown), which proves the opcode
  floor at probe time. Spec §1.2's READV/WRITEV constants stay in UringConstants for
  completeness but nothing emits them in Phase 1.
- **D-b. Batched-mode buffer contract:** `prepare*` captures `addr = MemoryUtil.
  getAddress(buf) + buf.position()` and `len = buf.remaining()` AT CALL TIME; the ring
  NEVER mutates buffer position/limit on completion — the handler owns the buffer.
  Only the sync facade advances `position()` (by bytes transferred). This observable
  sync-vs-batched difference is stated in UringRing's class Javadoc.
- **D-c. Slot occupancy ≠ capacity:** with slots indexed `opId % slots.length`, a slot
  can be occupied while `inFlight < slots.length` (oldest op still in flight).
  `prepare*` finding its slot occupied throws the SAME `IllegalStateException` as
  full-capacity backpressure (documented: effective capacity is per-index, not just
  global). Not a corruption case — corruption-throw remains reserved for user_data
  mismatch at demux.
- **D-d. `registerBuffers` requires uniform capacities** (validation stays trivial:
  `bufOffset + len <= capacity`); calling it while buffers are already registered
  throws `IllegalStateException` (don't rely on kernel EBUSY semantics that changed
  in 5.13).
- **D-e. `io_uring_params` (120 B) allocation idiom:** direct ByteBuffer +
  `Native.getDirectBufferPointer(buf)` — pre-zeroed by `allocateDirect` (malloc is NOT
  zeroed and the struct must be), GC-owned, no free path to get wrong; it only needs to
  outlive the setup call. `Native.malloc` + `new Pointer(addr)` (the `MemoryUtil.
  allocate` idiom, `MemoryUtil.java:85-93`) is reserved for the iovec array in 1.5 if
  needed. `com.sun.jna.Memory` is used nowhere in-tree — do not introduce it.
- **D-f. Kill-switch now:** add `URING_ENABLED("cassandra.native.uring.enabled", "true")`
  to `CassandraRelevantProperties` (idiom: `NATIVE_EPOLL_ENABLED`,
  `CassandraRelevantProperties.java:421`, consumed via `.getBoolean()` as at
  `NativeTransportService.java:138`). Checked as step 0 of the availability probe.
  Checkstyle forces the enum route in src AND tests (`blockSystemPropertyUsage`,
  `checkstyle.xml:179-184`, `checkstyle_test.xml:116-121`) — a raw
  `System.getProperty` would not pass anyway.

## 3. Per-class implementation notes

### UringNative
- Register: static block `Native.register(NativeLibrary.getInstance("c",
  Collections.emptyMap()))`, catch `NoClassDefFoundError|UnsatisfiedLinkError|
  NoSuchMethodError` → `available=false` (copy `NativeLibraryLinux.java:52-71`
  including the per-error logging split). Separate class so a uring link failure can't
  take down libc natives (hazard doc: `NativeLibraryLinux.java:30-44`).
- Natives `private static native … throws LastErrorException`, public wrappers. errno:
  `LastErrorException.getErrorCode()` (idiom `NativeLibrary.java:152-165`); message via
  `strerror(...).getString(0)` (idiom `NativeLibrary.java:251-259`). JNA zeroes
  last-error before each direct-mapped call — matches raw syscall(2) semantics.
- Fixed-arity syscall overloads exactly as spec §2.2; `mmap/munmap/close` alongside.

### UringConstants
All spec §1.2/1.3 values; ⚠ VERIFY items resolved against the rig's
`/usr/include/liburing/io_uring.h` or kernel UAPI header in 1.1 (record header
version in progress.md). MAP_POPULATE=0x8000 ⚠ VERIFY stays.

### UringRing
- Ordering primitives: `sun.misc.Unsafe.putOrderedInt(null, absAddr, v)` (release) and
  `getIntVolatile(null, absAddr)` (acquire) — verified present on JDK 11/17/21
  (javap-checked on 17; one-line re-verify on rig JDK in 1.1). **No absolute-address
  release/acquire precedent exists in src** — this is greenfield. Obtain Unsafe via the
  `theUnsafe` reflection idiom copied from `MemoryUtil.java:47-53` (the shared field is
  `protected`, unreachable from `io.uring` — own a private copy). Fallback if D2 ever
  revisited: Agrona 1.17.1 `UnsafeBuffer(long,int)` `putIntOrdered/getIntVolatile`
  (in classpath, javap-verified).
- Buffer addresses: `MemoryUtil.getAddress(buf)` (`MemoryUtil.java:79-83`) — works on
  Agrona aligned slices (slice address includes offset); **ignores position** — always
  add `buf.position()`. Aligned buffers freed via the slice's attachment:
  `MemoryUtil.clean((ByteBuffer)((DirectBuffer)buf).attachment())` (idiom
  `DirectThreadLocalByteBufferHolder.java:71-75`).
- Timing in tests/bench: `Clock.Global.nanoTime()` — raw `System.nanoTime` is
  checkstyle-blocked in src (`checkstyle.xml:72-78`).

### UringAvailability
Probe order per spec §2.5 with two corrections from research:
1. Step 0 = `URING_ENABLED.getBoolean()` (D-f).
2. The "is Linux" arm uses `FBUtilities.isLinux` (`FBUtilities.java:128`) — NOT
   `NativeLibrary.osType == LINUX`, because unknown OSes fall back to LINUX
   (`NativeLibrary.java:148-149`).
3. Live probe submits one real READ (D-a) on a temp file, then closes.

### UringRings
Plain `ConcurrentHashMap<Thread, UringRing>` per spec (FastThreadLocal evaluated —
no benefit for a sweep/closeAll-keyed registry). Shutdown hook via
`Runtime.getRuntime().addShutdownHook` (not blocked; precedents `PathUtils.java:714`,
`StorageService.java:774`). Known production TODO (moot for standalone spike): JVM
shutdown hooks run unordered vs StorageService drain — a ring could munmap under
drain-triggered writes; Phase 3's design owns that.

### Tests
- Linux gating: `Assume.assumeTrue(FBUtilities.isLinux)` (exemplars
  `StartupChecksTest.java:316`, `SystemInfoTest.java:167`). Gate on
  `UringAvailability` only — never on DIO support: macOS `ExtendedOpenOption.DIRECT`
  opens SUCCEED (advisory; documented `DirectCompressedSequentialWriterTest.java:1217-1221`).
- `NativeLibrary.getfd` works on DIRECT-opened channels (still `FileChannelImpl`;
  reflection is option-agnostic; `--add-opens java.base/sun.nio.ch` wired on 17/21 —
  `build.xml:321,395`).
- Temp files: `FileUtils.createDeletableTempFile` (as `NativeLibraryTest.java:33`).
- fd-leak test: no /proc/self/fd precedent in-tree — write fresh with
  `Files.list(Path.of("/proc/self/fd")).count()`. (`java.nio.file.Paths` is banned in
  src only; test checkstyle has empty illegalClasses — `checkstyle_test.xml:54-57`.)
- Thread spawning in tests: `IllegalInstantiation` bans `new Thread` in src
  (`checkstyle.xml:106-108`); tests are exempt, but prefer `ExecutorFactory` idiom.

### Checkstyle/licensing gotchas (will fail the build if missed)
ASF 17-line header on every file (RAT, `.build/build-rat.xml:112`); import order
java/javax/com/net/org/o.a.c-last with blank-line separation; no star imports; banned
in src: `java.io.File`, `java.nio.file.Paths`, `java.util.concurrent.CompletableFuture`,
`Executors`, `CountDownLatch`, `Semaphore` (`checkstyle.xml:104`). `sun.misc.Unsafe`
and `com.sun.jna` are unrestricted. Suppression escape: `// checkstyle: permit this import`.

### UringRawReadBench (1.7 skeleton)
Exemplar: `ChecksumBench.java` (standalone, full annotation set, `@Param`, per-method
`@Fork(jvmArgsAppend=...)`). No existing microbench does file I/O — temp-file plumbing
is greenfield; Phase 2's expected-changes.md owns the full bench design (params,
cold/hot, O_DIRECT idioms). Don't name anything matching the exclude regex
(`instance.*Bench`).

## 4. Questions closed by this research (summary)

Opcode choice (D-a) · batched position contract (D-b) · slot-occupancy semantics (D-c) ·
registerBuffers rules (D-d) · params allocation (D-e) · kill-switch property (D-f) ·
osType pitfall → FBUtilities.isLinux · Unsafe availability on JDK floor + acquisition
idiom · address-ignores-position + slice cleanup · getfd-under-DIRECT · macOS DIRECT
is advisory (skip logic) · zero build wiring needed · checkstyle constraint list ·
spec-citation drift (NativeLibraryTest exemplar, sync_file_range) documented above.

## 5. Remaining ⚠ VERIFY at execution (rig, sub-phase 1.1 — not user decisions)

- All spec §1.2/1.3 ⚠ constants against the rig's 6.8 UAPI header (FSYNC=3, READ=22,
  WRITE=23, DATASYNC flag, MAP_POPULATE, struct offsets via the empirical probe test).
- `sun.misc.Unsafe.putOrderedInt/getIntVolatile` present on the RIG's exact JDK (javap
  one-liner; verified locally on 17).
- `ulimit -l` headroom for 1.5 registered buffers (record in runbook.md — spec §5).

## 6. TPC hotspot notes (Enberg ANCS'19 — full mapping in `../findings-tpc-paper.md` §3)

The binding's performance KPI is **syscalls per op** — the paper's OS-interface
findings (syscall crossings costlier post-Meltdown/Spectre, notify-then-read double
touches) are what io_uring exists to remove:
- One `io_uring_enter` per **batch**, never per op; reap completions by polling the
  mmap'd CQ with acquire loads (the D2 design) — fall into `enter(GETEVENTS)` only
  when empty and the caller must wait. A reap path that syscalls per completion
  forfeits the point; phase-2's `strace-window.sh` verifies (target: ≪1 enter/op in
  batched mode — record the measured ratio).
- SQPOLL stays out of scope for the PoC: it dedicates a core to busy-polling — the
  paper's Shenango citation is exactly this waste. Data-informed revisit only.
- Registered buffers (1.5) are the per-op pin/translate saving — the storage analogue
  of the paper's payload-copy complaint.
