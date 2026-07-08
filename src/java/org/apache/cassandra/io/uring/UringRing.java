/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.io.uring;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.apache.cassandra.utils.memory.MemoryUtil;

/**
 * One io_uring instance owned by exactly one thread. NOT thread-safe by design — the
 * thread-per-core shape this binding exists to serve. The owner is recorded at creation and
 * asserted (debug) on every call.
 * <p>
 * Two API layers:
 * <ul>
 *   <li>Sync facade ({@code readSync}/{@code writeSync}/{@code fsyncSync}): loops internally on
 *       short reads/writes, advances the buffer's {@code position()} by bytes transferred, and
 *       converts negative CQE results to {@link IOException}. May not be interleaved with
 *       in-flight batched ops.</li>
 *   <li>Batched ({@code prepare*}/{@code submit}/{@code awaitCompletions}/{@code drainCompletions}):
 *       captures the buffer's address range AT PREPARE TIME and NEVER mutates position/limit on
 *       completion — the {@link CompletionHandler} owns the buffer. Short reads are passed through
 *       ({@code res < origLen}), not retried: resubmission policy belongs to the caller (a TPC
 *       scheduler), not this layer. Negative {@code res} is {@code -errno}, delivered raw.</li>
 * </ul>
 * Buffers must be direct; the strong reference held in the in-flight slot table from prepare
 * until CQE consumption is the GC-pinning mechanism. Callers must keep the file open until the
 * op completes — fds are not duplicated or registered.
 * <p>
 * Batched write→fsync ordering is the CALLER's job: await the write completions before preparing
 * the fsync (no IOSQE_IO_LINK in this layer).
 */
public final class UringRing implements Closeable
{
    private static final sun.misc.Unsafe unsafe;

    static
    {
        try
        {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (sun.misc.Unsafe) field.get(null);
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }
    }

    private static final class Op
    {
        final long opId;
        final ByteBuffer buf; // strong ref = GC pin until CQE consumed (may be null for fsync)
        final int origLen;
        final boolean fixed;

        Op(long opId, ByteBuffer buf, int origLen, boolean fixed)
        {
            this.opId = opId;
            this.buf = buf;
            this.origLen = origLen;
            this.fixed = fixed;
        }
    }

    public interface CompletionHandler
    {
        /** @param res bytes transferred, or negative -errno */
        void onComplete(long opId, int res);
    }

    private final Thread owner;
    private final int ringFd;
    private final int sqEntries;   // as reported by the kernel (may be rounded up)
    private final int cqEntries;
    private final int features;

    // mmap regions (cqRingPtr == sqRingPtr under FEAT_SINGLE_MMAP)
    private final Pointer sqRingPtr;
    private final long sqRingSize;
    private final Pointer cqRingPtr;
    private final long cqRingSize;
    private final Pointer sqesPtr;
    private final long sqesSize;

    // absolute addresses of the shared-with-kernel ring fields
    private final long sqHeadAddr;
    private final long sqTailAddr;
    private final long sqArrayAddr;
    private final long sqesAddr;
    private final long cqHeadAddr;
    private final long cqTailAddr;
    private final long cqesAddr;
    private final int sqMask;
    private final int cqMask;

    // local (single-threaded) state
    private int sqTailLocal;       // mirrors the published SQ tail
    private long nextOpId = 1;     // user_data; 0 never issued
    private final Op[] slots;      // in-flight table, indexed opId % slots.length
    private int inFlight;
    private int fixedInFlight;
    private boolean closed;
    private boolean poisoned;      // EBADR: a completion was lost; accounting is unrecoverable

    private ByteBuffer[] registeredBuffers; // strong refs, registration lifetime (§1.4.6)

    public static UringRing create(int sqEntries, int cqEntries) throws IOException
    {
        if (!UringAvailability.isAvailable())
            throw new IOException("io_uring unavailable: " + UringAvailability.reason());
        return create(sqEntries, cqEntries, UringAvailability.setupTier());
    }

    /**
     * Tier is explicit so {@link UringAvailability}'s probe can construct a ring without
     * re-entering its own static initialisation. Steps down the tiers on EINVAL (backports vary).
     */
    static UringRing create(int sqEntries, int cqEntries, UringAvailability.SetupTier tier) throws IOException
    {
        if (Integer.bitCount(sqEntries) != 1 || Integer.bitCount(cqEntries) != 1)
            throw new IllegalArgumentException("ring sizes must be powers of two: sq=" + sqEntries + " cq=" + cqEntries);
        if (cqEntries < sqEntries)
            throw new IllegalArgumentException("cqEntries must be >= sqEntries");
        if (sqEntries > UringConstants.IORING_MAX_ENTRIES)
            throw new IllegalArgumentException("sqEntries > IORING_MAX_ENTRIES");

        ByteBuffer params = ByteBuffer.allocateDirect(UringConstants.PARAMS_SIZE) // pre-zeroed, GC-owned
                                      .order(ByteOrder.nativeOrder());
        Pointer paramsPtr = Native.getDirectBufferPointer(params);

        int ringFd = -1;
        UringAvailability.SetupTier[] tiers = UringAvailability.SetupTier.values();
        for (int i = tier.ordinal(); i < tiers.length; i++)
        {
            for (int off = 0; off < UringConstants.PARAMS_SIZE; off += 8)
                params.putLong(off, 0L);
            params.putInt(UringConstants.PARAMS_FLAGS, tiers[i].setupFlags | UringConstants.IORING_SETUP_CQSIZE);
            params.putInt(UringConstants.PARAMS_CQ_ENTRIES, cqEntries);
            try
            {
                ringFd = (int) UringNative.ioUringSetup(sqEntries, paramsPtr);
                break;
            }
            catch (LastErrorException e)
            {
                if (e.getErrorCode() != UringConstants.EINVAL || i == tiers.length - 1)
                    throw new IOException("io_uring_setup failed: " + UringNative.errnoDescription(e.getErrorCode()), e);
            }
        }
        return new UringRing(ringFd, params);
    }

    private UringRing(int ringFd, ByteBuffer params) throws IOException
    {
        this.owner = Thread.currentThread();
        this.ringFd = ringFd;
        this.sqEntries = params.getInt(UringConstants.PARAMS_SQ_ENTRIES);
        this.cqEntries = params.getInt(UringConstants.PARAMS_CQ_ENTRIES);
        this.features = params.getInt(UringConstants.PARAMS_FEATURES);

        int sqHeadOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_HEAD);
        int sqTailOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_TAIL);
        int sqMaskOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_RING_MASK);
        int sqArrayOff = params.getInt(UringConstants.PARAMS_SQ_OFF + UringConstants.SQ_OFF_ARRAY);
        int cqHeadOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_HEAD);
        int cqTailOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_TAIL);
        int cqMaskOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_RING_MASK);
        int cqCqesOff = params.getInt(UringConstants.PARAMS_CQ_OFF + UringConstants.CQ_OFF_CQES);

        long sqSize = sqArrayOff + (long) sqEntries * 4;
        long cqSize = cqCqesOff + (long) cqEntries * UringConstants.CQE_SIZE;

        Pointer sqRing = null;
        Pointer cqRing = null;
        Pointer sqes = null;
        long sqRingSz;
        long cqRingSz;
        long sqesSz = (long) sqEntries * UringConstants.SQE_SIZE;
        boolean singleMmap = (features & UringConstants.IORING_FEAT_SINGLE_MMAP) != 0;
        try
        {
            if (singleMmap)
            {
                sqRingSz = Math.max(sqSize, cqSize);
                cqRingSz = sqRingSz;
                sqRing = UringNative.map(sqRingSz, ringFd, UringConstants.IORING_OFF_SQ_RING);
                if (UringNative.isMapFailed(sqRing))
                    throw new IOException("mmap(SQ+CQ ring) failed: " + UringNative.errnoDescription(Native.getLastError()));
                cqRing = sqRing;
            }
            else
            {
                // dead-simple fallback for pre-5.4 kernels; kept so the probe stays honest
                sqRingSz = sqSize;
                cqRingSz = cqSize;
                sqRing = UringNative.map(sqRingSz, ringFd, UringConstants.IORING_OFF_SQ_RING);
                if (UringNative.isMapFailed(sqRing))
                    throw new IOException("mmap(SQ ring) failed: " + UringNative.errnoDescription(Native.getLastError()));
                cqRing = UringNative.map(cqRingSz, ringFd, UringConstants.IORING_OFF_CQ_RING);
                if (UringNative.isMapFailed(cqRing))
                    throw new IOException("mmap(CQ ring) failed: " + UringNative.errnoDescription(Native.getLastError()));
            }
            sqes = UringNative.map(sqesSz, ringFd, UringConstants.IORING_OFF_SQES);
            if (UringNative.isMapFailed(sqes))
                throw new IOException("mmap(SQEs) failed: " + UringNative.errnoDescription(Native.getLastError()));
        }
        catch (Throwable t)
        {
            unmapQuietly(sqRing, singleMmap ? Math.max(sqSize, cqSize) : sqSize);
            if (!singleMmap)
                unmapQuietly(cqRing, cqSize);
            unmapQuietly(sqes, sqesSz);
            closeQuietly(ringFd);
            throw t;
        }

        this.sqRingPtr = sqRing;
        this.sqRingSize = sqRingSz;
        this.cqRingPtr = cqRing;
        this.cqRingSize = cqRingSz;
        this.sqesPtr = sqes;
        this.sqesSize = sqesSz;

        long sqBase = Pointer.nativeValue(sqRing);
        long cqBase = Pointer.nativeValue(cqRing);
        this.sqHeadAddr = sqBase + sqHeadOff;
        this.sqTailAddr = sqBase + sqTailOff;
        this.sqArrayAddr = sqBase + sqArrayOff;
        this.sqMask = unsafe.getInt(sqBase + sqMaskOff);
        this.cqHeadAddr = cqBase + cqHeadOff;
        this.cqTailAddr = cqBase + cqTailOff;
        this.cqesAddr = cqBase + cqCqesOff;
        this.cqMask = unsafe.getInt(cqBase + cqMaskOff);
        this.sqesAddr = Pointer.nativeValue(sqes);

        this.sqTailLocal = unsafe.getIntVolatile(null, sqTailAddr);
        this.slots = new Op[cqEntries];
    }

    // ------------------------------------------------------------------ sync facade

    /** Full-read loop; advances {@code buf.position()}; @return total bytes read (short only at EOF) */
    public int readSync(int fd, long offset, ByteBuffer buf) throws IOException
    {
        checkSyncPreconditions(buf);
        int total = 0;
        while (buf.hasRemaining())
        {
            int res = syncOp(UringConstants.IORING_OP_READ, fd, offset + total,
                             MemoryUtil.getAddress(buf) + buf.position(), buf.remaining(), 0);
            if (res < 0)
                throw syncFailure("read", fd, res);
            if (res == 0)
                break; // EOF
            buf.position(buf.position() + res);
            total += res;
        }
        return total;
    }

    /** Full-write loop; advances {@code buf.position()}; @return total bytes written (== initial remaining) */
    public int writeSync(int fd, long offset, ByteBuffer buf) throws IOException
    {
        checkSyncPreconditions(buf);
        int total = 0;
        while (buf.hasRemaining())
        {
            int res = syncOp(UringConstants.IORING_OP_WRITE, fd, offset + total,
                             MemoryUtil.getAddress(buf) + buf.position(), buf.remaining(), 0);
            if (res < 0)
                throw syncFailure("write", fd, res);
            if (res == 0)
                throw new IOException("io_uring write returned 0 bytes (fd " + fd + ')');
            buf.position(buf.position() + res);
            total += res;
        }
        return total;
    }

    public void fsyncSync(int fd, boolean dataOnly) throws IOException
    {
        checkOwner();
        checkOpen();
        if (inFlight != 0)
            throw new IllegalStateException("sync facade may not be interleaved with in-flight batched ops");
        int res = syncOp(UringConstants.IORING_OP_FSYNC, fd, 0, 0, 0,
                         dataOnly ? UringConstants.IORING_FSYNC_DATASYNC : 0);
        if (res < 0)
            throw syncFailure("fsync", fd, res);
    }

    private void checkSyncPreconditions(ByteBuffer buf)
    {
        checkOwner();
        checkOpen();
        checkDirect(buf);
        if (inFlight != 0)
            throw new IllegalStateException("sync facade may not be interleaved with in-flight batched ops");
    }

    /** Issues one op and waits for its CQE; @return raw res (negative = -errno) */
    private int syncOp(int opcode, int fd, long fileOffset, long addr, int len, int opFlags) throws IOException
    {
        long opId = enqueueSqe(opcode, fd, fileOffset, addr, len, opFlags, 0);
        slots[slotIndex(opId)] = new Op(opId, null, len, false);
        inFlight++;
        enter(1, 1, UringConstants.IORING_ENTER_GETEVENTS);
        SyncResult result = new SyncResult();
        int drained = drainCompletions((completedId, res) -> {
            if (completedId != opId)
                throw new IllegalStateException("sync op demux mismatch: expected " + opId + ", got " + completedId);
            result.res = res;
        });
        if (drained != 1)
            throw new IllegalStateException("sync op expected exactly 1 completion, drained " + drained);
        return result.res;
    }

    private static final class SyncResult
    {
        int res;
    }

    private IOException syncFailure(String op, int fd, int negErrno)
    {
        return new IOException("io_uring " + op + " failed (fd " + fd + "): " + UringNative.errnoDescription(-negErrno));
    }

    // ------------------------------------------------------------------ batched API

    /** @return opId; buffer position/limit are read now and never mutated (handler owns the buffer) */
    public long prepareRead(int fd, long offset, ByteBuffer buf)
    {
        checkBatchPreconditions(buf);
        long opId = enqueueSqe(UringConstants.IORING_OP_READ, fd, offset,
                               MemoryUtil.getAddress(buf) + buf.position(), buf.remaining(), 0, 0);
        slots[slotIndex(opId)] = new Op(opId, buf, buf.remaining(), false);
        inFlight++;
        return opId;
    }

    public long prepareWrite(int fd, long offset, ByteBuffer buf)
    {
        checkBatchPreconditions(buf);
        long opId = enqueueSqe(UringConstants.IORING_OP_WRITE, fd, offset,
                               MemoryUtil.getAddress(buf) + buf.position(), buf.remaining(), 0, 0);
        slots[slotIndex(opId)] = new Op(opId, buf, buf.remaining(), false);
        inFlight++;
        return opId;
    }

    /** Read into a registered buffer (must be O_DIRECT-friendly: aligned offset/len for O_DIRECT fds) */
    public long prepareReadFixed(int fd, long offset, int bufIndex, int bufOffset, int len)
    {
        return prepareFixed(UringConstants.IORING_OP_READ_FIXED, fd, offset, bufIndex, bufOffset, len);
    }

    public long prepareWriteFixed(int fd, long offset, int bufIndex, int bufOffset, int len)
    {
        return prepareFixed(UringConstants.IORING_OP_WRITE_FIXED, fd, offset, bufIndex, bufOffset, len);
    }

    private long prepareFixed(int opcode, int fd, long offset, int bufIndex, int bufOffset, int len)
    {
        checkOwner();
        checkOpen();
        checkCapacity();
        if (registeredBuffers == null)
            throw new IllegalStateException("no buffers registered");
        if (bufIndex < 0 || bufIndex >= registeredBuffers.length)
            throw new IllegalArgumentException("bufIndex " + bufIndex + " out of range");
        ByteBuffer buf = registeredBuffers[bufIndex];
        if (bufOffset < 0 || len < 0 || bufOffset + len > buf.capacity())
            throw new IllegalArgumentException("range [" + bufOffset + ", +" + len + ") exceeds registered buffer capacity " + buf.capacity());
        long opId = enqueueSqe(opcode, fd, offset, MemoryUtil.getAddress(buf) + bufOffset, len, 0, bufIndex);
        slots[slotIndex(opId)] = new Op(opId, buf, len, true);
        inFlight++;
        fixedInFlight++;
        return opId;
    }

    /** Prepare an fsync; ordering vs earlier writes is the caller's job (await write CQEs first) */
    public long prepareFsync(int fd, boolean dataOnly)
    {
        checkOwner();
        checkOpen();
        checkCapacity();
        long opId = enqueueSqe(UringConstants.IORING_OP_FSYNC, fd, 0, 0, 0,
                               dataOnly ? UringConstants.IORING_FSYNC_DATASYNC : 0, 0);
        slots[slotIndex(opId)] = new Op(opId, null, 0, false);
        inFlight++;
        return opId;
    }

    /** Submit all prepared SQEs with one io_uring_enter; @return SQEs consumed */
    public int submit() throws IOException
    {
        checkOwner();
        checkOpen();
        return enter(pendingSubmissions(), 0, 0);
    }

    /** Submit all prepared SQEs and wait until at least {@code minComplete} CQEs are available */
    public int submitAndWait(int minComplete) throws IOException
    {
        checkOwner();
        checkOpen();
        return enter(pendingSubmissions(), minComplete, UringConstants.IORING_ENTER_GETEVENTS);
    }

    /** Non-blocking reap of available CQEs; @return number delivered to the handler */
    public int drainCompletions(CompletionHandler handler)
    {
        checkOwner();
        checkOpen();
        int head = unsafe.getInt(cqHeadAddr); // we are the only writer of head
        int tail = unsafe.getIntVolatile(null, cqTailAddr); // acquire
        int drained = 0;
        while (head != tail)
        {
            long cqe = cqesAddr + (long) (head & cqMask) * UringConstants.CQE_SIZE;
            long userData = unsafe.getLong(cqe + UringConstants.CQE_USER_DATA);
            int res = unsafe.getInt(cqe + UringConstants.CQE_RES);
            head++;
            unsafe.putOrderedInt(null, cqHeadAddr, head); // release before handler can reuse the slot
            complete(userData, res, handler);
            drained++;
        }
        return drained;
    }

    /** Blocks (enter+GETEVENTS) until at least {@code minComplete} CQEs are reaped; @return number delivered */
    public int awaitCompletions(int minComplete, CompletionHandler handler) throws IOException
    {
        checkOwner();
        checkOpen();
        if (minComplete > inFlight)
            throw new IllegalArgumentException("awaiting " + minComplete + " completions with only " + inFlight + " in flight");
        int drained = drainCompletions(handler);
        while (drained < minComplete)
        {
            enter(pendingSubmissions(), minComplete - drained, UringConstants.IORING_ENTER_GETEVENTS);
            drained += drainCompletions(handler);
        }
        return drained;
    }

    public int inFlight()
    {
        return inFlight;
    }

    // ------------------------------------------------------------------ fixed buffers

    /**
     * Registers direct buffers with the kernel (pinned until unregister/close; charged against
     * RLIMIT_MEMLOCK). All buffers must have the same capacity. Strong references are held by
     * this ring from registration until {@link #unregisterBuffers()}.
     */
    public void registerBuffers(ByteBuffer[] directAligned) throws IOException
    {
        checkOwner();
        checkOpen();
        if (registeredBuffers != null)
            throw new IllegalStateException("buffers already registered");
        if (directAligned == null || directAligned.length == 0)
            throw new IllegalArgumentException("no buffers");
        int capacity = directAligned[0].capacity();
        for (ByteBuffer buf : directAligned)
        {
            checkDirect(buf);
            if (buf.capacity() != capacity)
                throw new IllegalArgumentException("registered buffers must have uniform capacity");
        }

        long iovecs = Native.malloc(16L * directAligned.length); // struct iovec { void *base; size_t len; }
        if (iovecs == 0)
            throw new IOException("malloc failed for iovec array");
        try
        {
            for (int i = 0; i < directAligned.length; i++)
            {
                unsafe.putLong(iovecs + 16L * i, MemoryUtil.getAddress(directAligned[i]));
                unsafe.putLong(iovecs + 16L * i + 8, capacity);
            }
            try
            {
                UringNative.ioUringRegister(ringFd, UringConstants.IORING_REGISTER_BUFFERS,
                                            new Pointer(iovecs), directAligned.length);
            }
            catch (LastErrorException e)
            {
                throw new IOException("io_uring buffer registration failed: " + UringNative.errnoDescription(e.getErrorCode()), e);
            }
            registeredBuffers = directAligned.clone();
        }
        finally
        {
            Native.free(iovecs); // kernel copies the iovec array during the register call
        }
    }

    public void unregisterBuffers() throws IOException
    {
        checkOwner();
        checkOpen();
        if (registeredBuffers == null)
            throw new IllegalStateException("no buffers registered");
        if (fixedInFlight != 0)
            throw new IllegalStateException("cannot unregister with " + fixedInFlight + " FIXED ops in flight");
        try
        {
            UringNative.ioUringRegister(ringFd, UringConstants.IORING_UNREGISTER_BUFFERS, Pointer.NULL, 0);
        }
        catch (LastErrorException e)
        {
            throw new IOException("io_uring buffer unregistration failed: " + UringNative.errnoDescription(e.getErrorCode()), e);
        }
        registeredBuffers = null;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Idempotent; refuses (IllegalStateException) while ops are in flight — the kernel could
     *  otherwise complete writes into unmapped memory. Callable from any thread: the
     *  {@link UringRings} registry closes rings of dead threads and closes at JVM shutdown. */
    @Override
    public void close()
    {
        if (closed)
            return;
        if (inFlight != 0 && !poisoned)
            throw new IllegalStateException("cannot close with " + inFlight + " ops in flight");
        closed = true;
        unmapQuietly(sqesPtr, sqesSize);
        unmapQuietly(sqRingPtr, sqRingSize);
        if (cqRingPtr != sqRingPtr)
            unmapQuietly(cqRingPtr, cqRingSize);
        closeQuietly(ringFd);
    }

    boolean isClosed()
    {
        return closed;
    }

    public int features()
    {
        return features;
    }

    public int sqEntries()
    {
        return sqEntries;
    }

    public int cqEntries()
    {
        return cqEntries;
    }

    // ------------------------------------------------------------------ internals

    /** Writes one SQE and publishes it with a release store of the SQ tail; @return opId */
    private long enqueueSqe(int opcode, int fd, long fileOffset, long addr, int len, int opFlags, int bufIndex)
    {
        int sqHead = unsafe.getIntVolatile(null, sqHeadAddr); // acquire
        if (sqTailLocal - sqHead == sqEntries)
            throw new IllegalStateException("SQ full (" + sqEntries + " unsubmitted); call submit()");

        long opId = nextOpId++;
        int index = sqTailLocal & sqMask;
        long sqe = sqesAddr + (long) index * UringConstants.SQE_SIZE;
        unsafe.setMemory(sqe, UringConstants.SQE_SIZE, (byte) 0);
        unsafe.putByte(sqe + UringConstants.SQE_OPCODE, (byte) opcode);
        unsafe.putInt(sqe + UringConstants.SQE_FD, fd);
        unsafe.putLong(sqe + UringConstants.SQE_OFF, fileOffset);
        unsafe.putLong(sqe + UringConstants.SQE_ADDR, addr);
        unsafe.putInt(sqe + UringConstants.SQE_LEN, len);
        unsafe.putInt(sqe + UringConstants.SQE_RW_FLAGS, opFlags);
        unsafe.putLong(sqe + UringConstants.SQE_USER_DATA, opId);
        unsafe.putShort(sqe + UringConstants.SQE_BUF_INDEX, (short) bufIndex);

        unsafe.putInt(sqArrayAddr + (long) index * 4, index);
        sqTailLocal++;
        unsafe.putOrderedInt(null, sqTailAddr, sqTailLocal); // release: SQE contents visible before tail
        return opId;
    }

    private int pendingSubmissions()
    {
        int sqHead = unsafe.getIntVolatile(null, sqHeadAddr); // acquire
        return sqTailLocal - sqHead;
    }

    /**
     * io_uring_enter with the §1.4 contracts: EINTR retried with to_submit recomputed from ring
     * state (the interrupted call may already have consumed SQEs); EBUSY treated as "CQ needs
     * draining" and surfaced as such; EBADR poisons the ring (a completion was dropped).
     */
    private int enter(int toSubmit, int minComplete, int flags) throws IOException
    {
        while (true)
        {
            try
            {
                return (int) UringNative.ioUringEnter(ringFd, toSubmit, minComplete, flags);
            }
            catch (LastErrorException e)
            {
                int errno = e.getErrorCode();
                if (errno == UringConstants.EINTR)
                {
                    toSubmit = pendingSubmissions();
                    continue;
                }
                if (errno == UringConstants.EBUSY)
                    throw new IOException("io_uring_enter EBUSY: CQ overflow pending; drain completions and retry", e);
                if (errno == UringConstants.EBADR)
                {
                    poisoned = true;
                    throw new IOException("io_uring dropped a CQE (EBADR); ring accounting is unrecoverable — close and recreate", e);
                }
                throw new IOException("io_uring_enter failed: " + UringNative.errnoDescription(errno), e);
            }
        }
    }

    private void complete(long userData, int res, CompletionHandler handler)
    {
        int index = (int) (userData % slots.length);
        Op op = slots[index];
        if (op == null || op.opId != userData)
            throw new IllegalStateException("CQE user_data " + userData + " does not match slot " + index
                                            + " (" + (op == null ? "empty" : "opId " + op.opId) + "): ring state corrupted");
        slots[index] = null;
        inFlight--;
        if (op.fixed)
            fixedInFlight--;
        handler.onComplete(userData, res);
    }

    private int slotIndex(long opId)
    {
        return (int) (opId % slots.length);
    }

    private void checkBatchPreconditions(ByteBuffer buf)
    {
        checkOwner();
        checkOpen();
        checkDirect(buf);
        checkCapacity();
    }

    private void checkCapacity()
    {
        if (inFlight == slots.length)
            throw new IllegalStateException("at capacity: " + inFlight + " ops in flight");
        int index = slotIndex(nextOpId);
        if (slots[index] != null)
            throw new IllegalStateException("slot " + index + " still occupied by opId " + slots[index].opId
                                            + " (effective capacity is per-index, not just global)");
    }

    private void checkDirect(ByteBuffer buf)
    {
        if (!buf.isDirect())
            throw new IllegalArgumentException("io_uring requires direct buffers");
    }

    private void checkOpen()
    {
        if (closed)
            throw new IllegalStateException("ring is closed");
    }

    private void checkOwner()
    {
        assert owner == Thread.currentThread()
            : "UringRing owned by " + owner + " used from " + Thread.currentThread();
    }

    private static void unmapQuietly(Pointer ptr, long size)
    {
        if (ptr == null || UringNative.isMapFailed(ptr))
            return;
        try
        {
            UringNative.unmap(ptr, size);
        }
        catch (LastErrorException ignored)
        {
        }
    }

    private static void closeQuietly(int fd)
    {
        if (fd < 0)
            return;
        try
        {
            UringNative.closeFd(fd);
        }
        catch (LastErrorException ignored)
        {
        }
    }
}
