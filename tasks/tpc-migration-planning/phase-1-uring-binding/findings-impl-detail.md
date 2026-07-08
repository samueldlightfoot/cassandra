# Findings — Phase 1 implementation-level detail (agent report, 2026-07-07)

Verbatim exploration report; conclusions distilled into `expected-changes.md`.
Verified against tpc-migration @ 50ddce8455.

**Branch caveat first:** this tree does NOT contain the flush-write-pacing additions the
Phase 0 findings describe. `NativeLibraryLinux` has no `sync_file_range` (natives are only
mlockall…getpid, `NativeLibraryLinux.java:73-81`), and `NativeLibraryTest` has no
`testSyncFileRange` outcome-assertion test. Spec citations that referenced those must be
re-anchored.

## 1. JNA idioms in-tree

- **JNA version: 5.13.0** — `lib/jna-5.13.0.jar`, `lib/jna-platform-5.13.0.jar`; declared at
  `.build/parent-maven-pom.xml:555-565`. Already a runtime dep; no build change needed.
- **Register pattern** — `NativeLibraryLinux.java:52-71`: static block
  `Native.register(com.sun.jna.NativeLibrary.getInstance("c", Collections.emptyMap()))`,
  sets `available=true`, catches `NoClassDefFoundError` / `UnsatisfiedLinkError` /
  `NoSuchMethodError` (each logged differently). All-or-nothing linking hazard documented
  in class javadoc `NativeLibraryLinux.java:30-44`. Natives are
  `private static native ... throws LastErrorException` (`:73-81`) wrapped by public
  `callXxx` methods.
- **errno** — `NativeLibrary.errno(RuntimeException)` at `NativeLibrary.java:152-165`: casts
  to `LastErrorException.getErrorCode()`. Message conversion:
  `wrappedLibrary.callStrerror(errno).getString(0)` — usage exemplar
  `NativeLibrary.java:251-259` (trySkipCache). Every `tryXxx` distinguishes
  `UnsatisfiedLinkError` (degrade silently) from `LastErrorException` (log/throw), e.g.
  `trySync` `NativeLibrary.java:325-350`.
- **OS detection API for the probe** — `NativeLibrary.osType` public static field
  (`NativeLibrary.java:56`), set by string-match `getOsType()` (`:136-150`). ⚠ Pitfall:
  unknown OSes **fall back to LINUX** (`:148-149`), so `osType == LINUX` is not a strict
  Linux check. Tests use `FBUtilities.isLinux` (`FBUtilities.java:128`,
  `os.name.contains("linux")`) — the probe should use `FBUtilities.isLinux` or `OS_NAME`
  directly, not `osType == LINUX` alone.
- **getfd** — two overloads: `public static int getfd(FileChannel)`
  `NativeLibrary.java:379-391` and `getfd(FileDescriptor)` `:398-414`; returns −1 on
  failure. Mechanism: reflected fields cached at `:79-96` (`sun.nio.ch.FileChannelImpl.fd`
  → `FileDescriptor.fd` int). **Works with `ExtendedOpenOption.DIRECT`**: DIRECT-opened
  channels are still `sun.nio.ch.FileChannelImpl` (DIRECT only changes flags/alignment
  checks inside the same class), so the reflection is option-agnostic. Module access is
  already wired: `--add-opens java.base/sun.nio.ch=ALL-UNNAMED` on JDK 17/21
  (`build.xml:321,395`, `conf/jvm17-server.options:86`, `conf/jvm21-server.options:109`),
  `--add-exports` on JDK 11 (`build.xml:273`). Usage exemplar:
  `ChannelProxy.getFileDescriptor()` `ChannelProxy.java:218-221`.

## 2. Direct buffer address + alignment idioms

- **Canonical address getter: `MemoryUtil.getAddress(ByteBuffer)`** —
  `src/java/org/apache/cassandra/utils/memory/MemoryUtil.java:79-83`:
  `unsafe.getLong(buffer, DIRECT_BYTE_BUFFER_ADDRESS_OFFSET)`, guarded by
  `assert buffer.getClass() == DIRECT_BYTE_BUFFER_CLASS`. Slices from Agrona's aligned
  allocator pass this assert (a `DirectByteBuffer.slice()` is still `DirectByteBuffer`;
  its `address` field already includes the slice offset). **Address ignores position** —
  the ring must compute `getAddress(buf) + buf.position()`. Alternative in-tree:
  `FastByteOperations.java:220,266` (same trick). Wrapping a raw address as a ByteBuffer:
  `MemoryUtil.getByteBuffer(address, length)` `:110-120`.
- **Aligned allocation** — Agrona 1.17.1 (`lib/agrona-1.17.1.jar`)
  `BufferUtil.allocateDirectAligned(capacity, alignment)`; the spec's citation
  `DirectThreadLocalByteBufferHolder.java:66` is **still accurate** on this tree. Note the
  cleanup idiom: aligned buffers are slices; free via
  `MemoryUtil.clean((ByteBuffer) ((DirectBuffer) buffer).attachment())`
  (`DirectThreadLocalByteBufferHolder.java:71-75`; `MemoryUtil.clean` guards at
  `MemoryUtil.java:330-359`).
- **Block size** — `FileUtils.getBlockSize(File directory)` **confirmed at
  `FileUtils.java:789-792`** (cached per-directory, `probeBlockSize` `:794-809`, via
  `Files.getFileStore(...).getBlockSize()` `:811-816`). `FileUtils.isDirectIOSupported(File)`
  confirmed at `FileUtils.java:745-773`.

## 3. Unsafe ordering primitives

- **No absolute-address release/acquire precedent exists in src** — zero hits for
  `putOrdered*` in `src/java`; no `VarHandle`, no explicit fences. The closest patterns:
  `AtomicXFieldUpdater.lazySet` (heap fields) and **Agrona
  `UnsafeBuffer.putIntVolatile/putIntOrdered`** used by `InMemoryTrie`
  (`db/tries/InMemoryTrie.java:28,116-131`) — but wrapping heap `byte[]`, not native memory.
- **Mechanism on the JDK floor: `sun.misc.Unsafe.putOrderedInt(null, absoluteAddress, v)`
  and `getIntVolatile(null, absoluteAddress)`** — verified present on the local JDK 17
  (javap: `putOrderedInt(Object,long,int)`, `getIntVolatile(Object,long)`, plus
  `loadFence/storeFence/fullFence` as fallback). Present in 11 and 21 as well (removal
  happened only after 21); one-line verify on the rig JDK during 1.1. Passing `null` as
  the object makes the long an absolute address — standard Unsafe contract, same
  convention MemoryUtil already relies on for `copyMemory(null, addr, …)`
  (`MemoryUtil.java:280`).
- **Obtaining Unsafe** — copy the 3-line reflection idiom `MemoryUtil.java:47-53`
  (`Unsafe.class.getDeclaredField("theUnsafe")`, `setAccessible(true)`). NB:
  `MemoryUtil.unsafe` is `protected` (`:37`) — not reachable from
  `org.apache.cassandra.io.uring`; the new class must own its copy.
- **Drop-in alternative (D2 pins Unsafe, but note it):** Agrona
  `UnsafeBuffer(long address, int length)` with `putIntOrdered(int,int)` /
  `getIntVolatile(int)` — verified present in the bundled 1.17.1 via javap. Gives the
  exact release/acquire semantics over the mmap'd rings with bounds checks and no
  hand-rolled Unsafe.

## 4. Off-heap struct construction

- **In-tree idiom: `Native.malloc`/`Native.free` via `MemoryUtil.allocate/free`**
  (`MemoryUtil.java:85-93`) + Unsafe for stores; zeroing via `unsafe.setMemory`
  (`MemoryUtil.setByte(long,int,byte)` `:100-103` — malloc does NOT zero; io_uring_params
  must be explicitly memset to 0).
- **`com.sun.jna.Memory` is NOT used anywhere** in src/test (grep: zero hits; the only
  `new Memory(` is Cassandra's own `io/util/Memory.java:89`, unrelated). Its GC-lifetime
  pitfall (allocation freed when the `Memory` object is collected) therefore has no
  in-tree precedent to copy — avoid it.
- **Getting a `Pointer` for JNA params:** no `new Pointer(long)` precedent in-tree, but the
  constructor is public in JNA 5.13; `new Pointer(MemoryUtil.allocate(120))` is the
  minimal-glue path. Equally valid: allocate a direct ByteBuffer and pass
  `Native.getDirectBufferPointer(buf)` (keeps GC ownership in the buffer).
- SQE array / rings come from `mmap` (kernel-owned), so only `io_uring_params` (120 B) and
  the (optional) iovec array for `REGISTER_BUFFERS` need Java-side native allocation.

## 5. Test idioms

- **`NativeLibraryTest` (test/unit/org/apache/cassandra/utils/NativeLibraryTest.java:28-44)
  does NOT contain the outcome-assertion exemplar the spec cites.** On this branch it is
  only `testSkipCache` (no assertion) and `getPid` (`assertTrue(pid > 0)` — which does
  assert cross-platform behavior). The `testSyncFileRange` platform-conditional assert
  lives only on the flush-write-pacing branch. The *pattern* is fully described in the
  spec itself; the coding agent writes it fresh — no code to copy.
- **Linux gating**: `Assume.assumeTrue(FBUtilities.isLinux)` — exemplars
  `StartupChecksTest.java:316` (with message), `SystemInfoTest.java:167`,
  `FBUtilitiesTest.java:415`. `FBUtilities.isLinux` at `FBUtilities.java:128`. There are
  **no** `isDirectIOSupported` gates in any test — because on macOS
  `ExtendedOpenOption.DIRECT` opens succeed (O_DIRECT is advisory/silently ignored there;
  documented in-tree at `DirectCompressedSequentialWriterTest.java:1217-1221`). So Uring
  tests gate on `UringAvailability` alone.
- **Temp files**: `FileUtils.createDeletableTempFile` (used by `NativeLibraryTest.java:33`) /
  `FileUtils.createTempFile(prefix, suffix, dir)` (`FileUtils.java:751,796`).
- **/proc/self/fd: no precedent anywhere** in src or test (grep empty). The 1.3 fd-leak
  test writes it fresh — `java.nio.file.Files.list(Path.of("/proc/self/fd")).count()`,
  Linux-gated. Note `java.nio.file.Paths` is checkstyle-banned in **src** only; the
  **test** checkstyle has `illegalClasses=""` (`.build/checkstyle_test.xml:54-57`), so
  tests are unrestricted (even `java.io.File` is legal in tests).
- **Discovery/wiring: none needed.** `test/unit/**` compiled via `build.xml:1256` and
  `test/microbench/**` via `build.xml:1260` in the same javac;
  `ant test -Dtest.name=Uring*` resolves through
  `<fileset dir="${test.unit.src}" includes="**/${test.name}.java"/>` (`build.xml:1644`);
  default `ant test` runs everything under `test/unit` (`build.xml:1455`). New packages
  need zero registration.

## 6. Build / checkstyle friction

- **License header**: standard 17-line ASF header on every new file; enforced by Apache
  RAT (`.build/build-rat.xml:41-116`, fails at `:112` on "Unapproved licenses"), file set
  = git-tracked files (`:29-33`).
- **Checkstyle** (src rules `.build/checkstyle.xml`, run by `ant checkstyle`; test rules
  `.build/checkstyle_test.xml`, `ant checkstyle-test` — both filesets cover the new code:
  `build-checkstyle.xml:44` = src/java, `:60` = all of `test/` including microbench):
  - `sun.misc.Unsafe` and `com.sun.jna` are **NOT restricted** anywhere (IllegalImport
    lists: `checkstyle.xml:102-104` = junit.framework, org.jboss.byteman + JDK classes;
    no import-control file exists).
  - Rules that WILL bite the binding: `blockSystemClock` (`checkstyle.xml:72-78` — the
    QD-proof test must use `org.apache.cassandra.utils.Clock.Global.nanoTime()`; note
    this rule is absent from checkstyle_test.xml, but Clock.Global is still the house
    style); `blockSystemPropertyUsage` in **both** src (`checkstyle.xml:179-184`) and
    test (`checkstyle_test.xml:116-121`) — any probe property must go through
    `CassandraRelevantProperties`; banned imports in src incl. `java.io.File`,
    `java.nio.file.Paths`, `java.util.concurrent.CompletableFuture`, `Executors`,
    `CountDownLatch`, `Semaphore` (`checkstyle.xml:104`) and `IllegalInstantiation` of
    `java.lang.Thread` (`checkstyle.xml:106-108`); `ImportOrder` groups
    java/javax/com/net/org/accord/o.a.c-bottom, separated; `AvoidStarImport`. Escape
    hatch: `// checkstyle: permit this import` / suppression comments
    (`checkstyle.xml:41-64`).
  - `Runtime.getRuntime().addShutdownHook` is not blocked; precedents `PathUtils.java:714`,
    `StorageService.java:774`.
- **No registration anywhere else**: no `module-info.java` in src/test; OWASP scans
  dependencies only — no new dep; jar target has no package enumeration.

## 7. Microbench skeleton wiring

- `ant microbench -Dbenchmark.name=X` → `<jmh>` macro (`build-bench.xml:52-54,92-129`):
  runs `org.openjdk.jmh.Main` with regex arg `.*microbench.*${benchmark.name}`
  (`build-bench.xml:126`) against compiled `${test.classes}`. The **exclude pattern
  (confirmed `build-bench.xml:26`)** is applied only when `benchmark.name` is blank
  (`:123-124` `if:blank`) — a named bench always runs. Don't name anything matching
  `(instance.*Bench)`.
- **JMH 1.37** (`.build/parent-maven-pom.xml:668-669`, test scope; annprocess `:673-674`).
- JVM args for the fork: `${java-jvmargs}` + `${_std-test-jvmargs}` + `-Xmx1G`
  (`build-bench.xml:103-110`) — all add-exports/opens inherited; JMH
  `@Fork(jvmArgsAppend=...)` for extras.
- **Best exemplar to copy:
  `test/microbench/org/apache/cassandra/test/microbench/ChecksumBench.java`** — standalone
  (no CQLTester), full annotation set (`:45-51`), `@Param` (`:56`), `@Setup` (`:61`),
  per-`@Benchmark` `@Fork(value=1, jvmArgsAppend=...)` (`:68-71`). **No existing
  microbench does file I/O or O_DIRECT** (grep: zero hits) — the bench's temp-file
  plumbing is greenfield.

## 8. Adversarial pass — code-level decisions the spec left open

Verified-moot items: JNA callback-free design confirmed (nothing in the API needs
`com.sun.jna.Callback`); shutdown-hook conflict with StorageService drain
(`StorageService.java:774`) is real but moot for the standalone spike — JVM shutdown
hooks run concurrently/unordered, so in Phase 3 a ring could be munmapped while
drain-triggered writes are in flight; production TODO. `UringRings` vs Netty
`FastThreadLocal`: FTL is in-tree but gives no benefit for a
`ConcurrentHashMap<Thread, UringRing>` registry keyed for sweep/closeAll.

### Open questions surfaced (behavior-determining; now pinned in expected-changes.md D-a..D-f)

1. **Sync/batched facade opcode choice**: spec pins constants for both READV/WRITEV (5.1+)
   and READ/WRITE (5.6+, values 22/23 ⚠) but never says which `readSync`/`prepareRead`
   emit. Non-vectored READ/WRITE is the natural fit but raises the effective kernel floor
   to 5.6 — and neither the availability probe nor §1.4.7 acknowledged an opcode-driven
   kernel floor.
2. **Batched-mode length + position contract**: `prepareRead(fd, offset, buf)` has no
   `len` — presumably `len = buf.remaining()`, `addr = getAddress(buf)+buf.position()`.
   Who advances `position()`? `readSync` and `prepareRead+await` differ observably —
   must be written down.
3. **Slot-occupied-but-not-at-capacity**: with slots indexed `opId % slots.length`, slot s
   can still be occupied by opId k while `inFlight < slots.length`. Spec's backpressure
   clause only covered `inFlight == slots.length`.
4. **`registerBuffers` with heterogeneous capacities**: kernel permits per-iovec lengths.
   Uniform-size requirement is the simpler validation. Double-register: kernel EBUSY
   semantics changed in 5.13 — use explicit IllegalStateException.
5. **io_uring_params allocation idiom**: `Native.malloc` + `new Pointer(addr)` + explicit
   zeroing vs direct-ByteBuffer + `Native.getDirectBufferPointer` (pre-zeroed, GC-safe).
6. **Kill-switch property**: idiom exists —
   `NATIVE_EPOLL_ENABLED("cassandra.native.epoll.enabled", "true")`
   (`CassandraRelevantProperties.java:421`, consumed via `.getBoolean()` at
   `NativeTransportService.java:138`). Checkstyle forces the enum route in src and tests.
