# Explorer report — Native binding infra + I/O plumbing seams (2026-07-05)

Branch state: `HEAD` == `trunk` (`50ddce8455`); flush-write-pacing delta is staged/working-tree
only (13 files, +220/-2).

## 1. NativeLibrary architecture

- `NativeLibrary` — public final static facade, private ctor (`NativeLibrary.java:135`).
  Dispatch via `NativeLibraryWrapper wrappedLibrary` (`:80`) chosen by OSType
  (`:100-108`): MAC → Darwin; LINUX/AIX/OTHER/default → Linux. OS detection = string match on
  `os.name` (`getOsType()`, `:140-154`).
- `NativeLibraryWrapper` — `@Shared` interface; methods (`NativeLibraryWrapper.java:34-45`):
  `isAvailable, callMlockall, callMunlockall, callFcntl, callPosixFadvise, callSyncFileRange`
  (branch-added), `callOpen, callFsync, callClose, callStrerror, callGetpid`.
- Public wrappers in NativeLibrary: `isAvailable():175`, `jnaMemoryLockable():180`,
  `tryMlockall():185`, `trySkipCache(...):216,232,246` (POSIX_FADV_DONTNEED),
  `trySyncFileRange(...):291` (ADDED), `tryFcntl:331`, `tryOpenDirectory:356`, `trySync:380`,
  `tryCloseFD:407`, `getfd(FileChannel):434`, `getfd(FileDescriptor):453`, `getProcessID():474`,
  `isEnabled():493`.
- Linux natives (`NativeLibraryLinux.java:73-82`): mlockall, munlockall, fcntl, posix_fadvise,
  **sync_file_range (ADDED :77)**, open, fsync, close, strerror, getpid.
- JNA linkage: static block `Native.register(...)` sets `available=true`
  (`NativeLibraryLinux.java:52-71`); catches NoClassDefFoundError / UnsatisfiedLinkError /
  NoSuchMethodError. **All-native-or-nothing linking hazard documented at
  `NativeLibraryLinux.java:30-44`** — one unlinkable native fails the whole class (why per-OS
  wrappers exist; why a new io_uring binding must be a separate class).
- Darwin `callSyncFileRange` throws `UnsatisfiedLinkError` unconditionally
  (`NativeLibraryDarwin.java:103-107`, ADDED) — the "not on this platform" pattern.
- errno: `errno(RuntimeException)` casts to `LastErrorException.getErrorCode()`
  (`NativeLibrary.java:156-169`). Each `tryXxx` distinguishes UnsatisfiedLinkError (degrade)
  from LastErrorException (log errno).
- fd extraction: reflects `sun.nio.ch.FileChannelImpl.fd` → `FileDescriptor.fd`
  (`NativeLibrary.java:434-469`, cached fields `:83-96`), −1 on failure.

Branch additions (exact): constants `SYNC_FILE_RANGE_WAIT_BEFORE/WRITE/WAIT_AFTER`
(`NativeLibrary.java:76-78`); `trySyncFileRange(int,long,long,int)` (`:291-329`, NoSpamLogger
WARN 10min); interface method (`NativeLibraryWrapper.java:40`); Linux native decl+impl
(`NativeLibraryLinux.java:77,104-107`); Darwin stub (`NativeLibraryDarwin.java:103-107`).

Dead/legacy: `O_DIRECT=040000`, `F_NOCACHE`, `F_GETFL/F_SETFL` private+unused
(`NativeLibrary.java:63-66`); `tryFcntl` has no production callers.

## 2. How O_DIRECT is achieved today

Via JDK `com.sun.nio.file.ExtendedOpenOption.DIRECT`, NOT NativeLibrary. Three consumers:
1. Compaction/background writes — `DirectCompressedSequentialWriter` passes DIRECT to super
   (`DirectCompressedSequentialWriter.java:103`) → `SequentialWriter.openChannel(file, extraOptions...)`
   (`SequentialWriter.java:123-161`).
2. Commitlog — `DirectIOSegment.DirectIOSegmentBuilder.build()` opens WRITE,READ,CREATE,DSYNC,DIRECT
   (`DirectIOSegment.java:182-188`).
3. Reads — `ChannelProxy.openOptions(IOMode.DIRECT)` → `{READ, DIRECT}` (`ChannelProxy.java:69-80`).

Support probe: `FileUtils.isDirectIOSupported(File)` (`FileUtils.java:745-773`); block size
`getBlockSize` (cached/dir, `:789-792`), `probeBlockSize` (`:794-809`), via
`Files.getFileStore(path).getBlockSize()` (`:811-816`).

Alignment:
- Write: `DirectCompressedSequentialWriter` stages in block-aligned `writeBuffer`
  (`BufferUtil.allocateDirectAligned(bufferSize, blockSize)`, `:128`); flushes whole blocks
  `flushLimit = logicalPos & -blockSize` (`:283`); finalize pads (`BitUtil.align`), writes,
  truncates (`flushFinalWithPadding`, `:312-334`). Buffer floor = maxChunkWrite+CRC+blockSize
  (`:120-126`). mark/resetAndTruncate throw under O_DIRECT (`:338-347`).
- Read: `CompressedChunkReader.DirectRandomAccessReader` aligns down `chunk.offset & -blockSize`,
  reads length+delta, slices (`CompressedChunkReader.java:124-168`).

Config gates (Config.java): `disk_access_mode = mmap_index_only` (`:130`);
`background_write_disk_access_mode = standard` (`:416`, validated `DatabaseDescriptor.java:3500-3520`);
`direct_write_buffer_size = 1MiB` (`:422`); `commitlog_disk_access_mode = legacy` (`:486`,
resolver `DatabaseDescriptor.java:1842-1910`); `compaction_read_disk_access_mode = auto` (`:487`,
`DatabaseDescriptor.java:695-708`); `flush_compression = fast` (`:484`). Branch-added:
`trickle_fsync=false` (`:549`), `trickle_fsync_interval=10240KiB` (`:551`),
`trickle_fsync_mode` enum fsync|writeback (`:552`, `:1359-1363`).

Note: memtable FLUSH classified `UNSUPPORTED_POLICY` for O_DIRECT (`DataComponent.java:75`).

## 3. Write path plumbing

- `SequentialWriter extends BufferedDataOutputStreamPlus implements Transactional`
  (`SequentialWriter.java:44`); holds `fchannel:53`, inherited buffer, option `:66`.
- Buffer from `option.allocateBuffer()` (`:192` → `SequentialWriterOption.java:108-111`) —
  BufferType OFF_HEAP/ON_HEAP, NOT BufferPool.
- flush/fsync: `flushData()` `:307-321`; `doFlush(int)` `:277-295`; `syncDataOnlyInternal()`
  = `SyncUtil.force(fchannel,false)` `:221-231`; `syncInternal()` `:271-275`; full fsync via
  TransactionalProxy `:478-516`.
- Branch pacing hook: fields `lastWritebackPosition/writebackFd` (`:71-72`);
  `trySyncFileRangeInternal()` (`:239-264`); hook in doFlush (`:282-291`):
  `if (option.trickleFsyncMode() != writeback || !trySyncFileRangeInternal()) syncDataOnlyInternal();`
  Propagation: `IOOptions.java:37`; `CompressedSequentialWriter.createWriterOption` (`:88-93`).
- **Writer factory seam**: `DataComponent.buildWriter(...)` (`DataComponent.java:98-139`)
  branches on `getBackgroundWriteDiskAccessMode()==direct && isDirectWriteSupported(opType)` →
  Direct/Compressed/Checksummed writer. Eligibility = total `EnumMap<OperationType, DirectIoSupport>`
  (`:50-96`; enum in `DirectIoSupport.java`), new OperationType fails at class-load (`:91-94`).
- Ctor-level seam: protected ctor takes `OpenOption... extraOpenOptions` (`:195-204`).

## 4. Read path plumbing

- `FileHandle extends SharedCloseableImpl` (`FileHandle.java:50`): channel + rebuffererFactory +
  compression metadata + DiskAccessMode; `createReader(...)` `:194-227`; LimitingRebufferer wrap
  `:267-279`.
- `ChannelProxy` (`ChannelProxy.java:44`): refcounted FileChannel; `IOMode {BUFFERED, DIRECT}`
  `:47-51`; positional `read(ByteBuffer,long)` `:169-180`; `map` `:194-204`;
  `getFileDescriptor()` = NativeLibrary.getfd `:218-221`.
- `RandomAccessReader` (`:33`, @NotThreadSafe) delegates to `Rebufferer` (`:41`);
  `reBufferAt` `:78-88`.
- **Core read seam**: `Rebufferer.rebuffer(long) → BufferHolder` (`Rebufferer.java:28-72`);
  `RebuffererFactory.instantiateRebufferer(boolean)`; `ChunkReader.readChunk(long, ByteBuffer)`
  (`ChunkReader.java:31-39`) — synchronous fill-the-buffer contract.
- Dispatch switch: `FileHandle.Builder.complete(...)` (`FileHandle.java:445-536`):
  length==0 → EmptyRebufferer `:488`; mmap → MmappedRegions + `CompressedChunkReader.Mmap` /
  `MmapRebufferer` `:490-503`; else compressed → `CompressedChunkReader.Direct` (if direct) or
  `.Standard` `:509-518`; uncompressed → `SimpleChunkReader` `:522-523`; `maybeCached` →
  `chunkCache.wrap` `:538-543`. `Builder.ioMode()` maps DiskAccessMode→IOMode `:450-469`.
- Non-cached non-mmap reads: `BufferManagingRebufferer` (`:34`), buffer from
  `BufferPools.forChunkCache().get(...)` (`:45`), `source.readChunk(offset, buffer)` (`:77-82`);
  Aligned iff power-of-two (`SimpleChunkReader.java:57-64`).
- DiskAccessMode enum: `{auto, mmap, mmap_index_only, standard, legacy, direct}`
  (`Config.java:1370-1382`).
- **No async file-read machinery in production**: zero hits for AsynchronousFileChannel /
  AsynchronousChannelProxy / io_uring / AsyncChunkReader / CompletableFuture<...Buffer> in src/java.

## 5. Test patterns for native/Linux-gated code

- `NativeLibraryTest.testSyncFileRange()` (`:42-62`): runs on ALL platforms, asserts the
  platform-conditional outcome (osType==LINUX && isAvailable && fd>=0 → assertTrue else
  assertFalse). Real FileChannel + temp file + getfd. **Outcome-assertion idiom, not Assume-skip.**
- `SequentialWriterTest.writebackTrickleModeReadBack()` (`:201-223`): trickleFsync writeback
  mode, 256 KiB in 4 KiB chunks, finish(), then byte-identical real-FS read-back
  (`assertArrayEquals(data, readFileToByteArray(...))`). No Assume, no jimfs, no reflection;
  platform-agnostic by design (Linux exercises sync_file_range, elsewhere the fdatasync fallback —
  identical on-disk bytes either way).

## 6. Buffer / memory utilities (ring-relevant)

- `BufferPool` (chunked off-heap; macro-chunks via `allocateDirectAligned(MACRO_CHUNK_SIZE)`
  `:464`; page-aligned helper `:1099-1111`; cleaned via DirectBuffer attachment `:1570`).
  `BufferPools.forChunkCache()` used by read path.
- `SimpleCachedBufferPool` — commitlog; Direct-IO variant over-sizes by fsBlockSize−1 and aligns
  (`DirectIOSegment.java:192-209`).
- `MemoryUtil` — Unsafe-based: `pageSize():74-77`, `getAddress(ByteBuffer):79`,
  `allocate(long):85`, `free(long):90`, `getByteBuffer(address,len):110,115` (wraps raw address
  as ByteBuffer — what a ring completion needs), `clean(ByteBuffer)`.
- `Memory` (`:66,81,219-221`) — manually-freed off-heap, 8-byte aligned only. `SafeMemory` —
  refcounted.
- **Primary aligned allocator = Agrona `BufferUtil.allocateDirectAligned(capacity, alignment)`**:
  `DirectCompressedSequentialWriter.java:128`; `DirectThreadLocalByteBufferHolder.java:66`
  (thread-local, block-keyed, growable; `getBuffer(int)` aligns via `BitUtil.align`, `:52-69`);
  `DirectThreadLocalReadAheadBuffer.java:38`. Backing buffer reached via
  `((DirectBuffer) buffer).attachment()` for cleanup.

## Extension-point summary table

| Concern | Seam | file:line |
|---|---|---|
| New native opcode bindings | interface + Linux native + Darwin stub (mirror sync_file_range) | `NativeLibraryWrapper.java:40`; `NativeLibraryLinux.java:77,104`; `NativeLibraryDarwin.java:103` |
| try/degrade + errno + fd | `trySyncFileRange` template; `errno()`; `getfd` | `NativeLibrary.java:291,156,434` |
| Ring-backed writer selection | `DataComponent.buildWriter` + DirectIoSupport map | `DataComponent.java:98-139,50-96` |
| Writer open flags | `SequentialWriter` ctor extraOpenOptions → openChannel | `SequentialWriter.java:195,123` |
| Ring-backed reader | `ChunkReader.readChunk` impl chosen in `FileHandle.Builder.complete`; or ChannelProxy/IOMode; or Rebufferer | `ChunkReader.java:39`; `FileHandle.java:505-525,450-469`; `ChannelProxy.java:69,169` |
| Aligned/registered buffers | `BufferUtil.allocateDirectAligned` + `MemoryUtil.getAddress/getByteBuffer` | `DirectThreadLocalByteBufferHolder.java:66`; `MemoryUtil.java:79,110` |
| Block-size probing | `FileUtils.getBlockSize/isDirectIOSupported` | `FileUtils.java:789,745` |
