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
import java.nio.ByteBuffer;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.misc.Unsafe;

import static org.apache.cassandra.io.uring.IoUringNative.CQE_RES_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.CQE_SIZE;
import static org.apache.cassandra.io.uring.IoUringNative.CQ_OFF_CQES;
import static org.apache.cassandra.io.uring.IoUringNative.CQ_OFF_HEAD;
import static org.apache.cassandra.io.uring.IoUringNative.CQ_OFF_RING_MASK;
import static org.apache.cassandra.io.uring.IoUringNative.CQ_OFF_TAIL;
import static org.apache.cassandra.io.uring.IoUringNative.IORING_ENTER_GETEVENTS;
import static org.apache.cassandra.io.uring.IoUringNative.IORING_OFF_CQ_RING;
import static org.apache.cassandra.io.uring.IoUringNative.IORING_OFF_SQES;
import static org.apache.cassandra.io.uring.IoUringNative.IORING_OFF_SQ_RING;
import static org.apache.cassandra.io.uring.IoUringNative.IORING_OP_READ;
import static org.apache.cassandra.io.uring.IoUringNative.IORING_REGISTER_FILES;
import static org.apache.cassandra.io.uring.IoUringNative.IORING_UNREGISTER_FILES;
import static org.apache.cassandra.io.uring.IoUringNative.PARAMS_CQ_ENTRIES_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.PARAMS_CQ_OFF_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.PARAMS_SIZE;
import static org.apache.cassandra.io.uring.IoUringNative.PARAMS_SQ_ENTRIES_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.PARAMS_SQ_OFF_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.SQE_ADDR_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.SQE_FD_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.SQE_LEN_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.SQE_OFF_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.SQE_OPCODE_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.SQE_SIZE;
import static org.apache.cassandra.io.uring.IoUringNative.SQE_USER_DATA_OFF;
import static org.apache.cassandra.io.uring.IoUringNative.SQ_OFF_ARRAY;
import static org.apache.cassandra.io.uring.IoUringNative.SQ_OFF_HEAD;
import static org.apache.cassandra.io.uring.IoUringNative.SQ_OFF_RING_MASK;
import static org.apache.cassandra.io.uring.IoUringNative.SQ_OFF_TAIL;
import static org.apache.cassandra.io.uring.IoUringNative.closeFd;
import static org.apache.cassandra.io.uring.IoUringNative.ioUringEnter;
import static org.apache.cassandra.io.uring.IoUringNative.ioUringRegister;
import static org.apache.cassandra.io.uring.IoUringNative.ioUringSetup;
import static org.apache.cassandra.io.uring.IoUringNative.mmapRing;
import static org.apache.cassandra.io.uring.IoUringNative.munmapRing;

/**
 * Manages a single io_uring instance: the submission queue (SQ), completion queue (CQ),
 * and the shared SQE array. Each ring is NOT thread-safe — use one per thread via
 * {@link IoUringContext}.
 *
 * The ring is set up by calling io_uring_setup(2), then mmap'ing three regions:
 * 1. SQ ring (head, tail, mask, array of SQE indices)
 * 2. CQ ring (head, tail, mask, array of CQEs)
 * 3. SQE array (pre-allocated submission queue entries)
 *
 * To perform I/O: fill an SQE, advance the SQ tail, then call io_uring_enter(2).
 * On completion, read the CQE result and advance the CQ head.
 */
public class IoUringRing implements Closeable
{
    private static final Logger logger = LoggerFactory.getLogger(IoUringRing.class);
    private static final Unsafe unsafe;

    static
    {
        try
        {
            java.lang.reflect.Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        }
        catch (Exception e)
        {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int ringFd;
    private final int sqEntries;
    private final int cqEntries;

    // mmap'd regions
    private final Pointer sqRingPtr;
    private final long sqRingSize;
    private final Pointer cqRingPtr;
    private final long cqRingSize;
    private final Pointer sqesPtr;
    private final long sqesSize;

    // SQ ring field addresses (absolute pointers into mmap'd memory)
    private final long sqHeadAddr;
    private final long sqTailAddr;
    private final long sqMask;
    private final long sqArrayAddr;

    // CQ ring field addresses
    private final long cqHeadAddr;
    private final long cqTailAddr;
    private final long cqMask;
    private final long cqesAddr;

    private final long sqesBaseAddr;

    private volatile boolean closed;

    /**
     * Create and initialize an io_uring ring.
     *
     * @param entries requested number of SQ entries (kernel rounds up to power of 2)
     * @throws IOException if io_uring_setup fails
     */
    public IoUringRing(int entries) throws IOException
    {
        Memory params = new Memory(PARAMS_SIZE);
        params.clear();

        int fd;
        try
        {
            fd = ioUringSetup(entries, params);
        }
        catch (LastErrorException e)
        {
            throw new IOException("io_uring_setup failed: errno " + e.getErrorCode(), e);
        }
        this.ringFd = fd;

        this.sqEntries = params.getInt(PARAMS_SQ_ENTRIES_OFF);
        this.cqEntries = params.getInt(PARAMS_CQ_ENTRIES_OFF);

        // Read sq_off (io_sqring_offsets) from params
        int sqOff = PARAMS_SQ_OFF_OFF;
        int sqHeadOff    = params.getInt(sqOff + SQ_OFF_HEAD);
        int sqTailOff    = params.getInt(sqOff + SQ_OFF_TAIL);
        int sqRingMaskOff = params.getInt(sqOff + SQ_OFF_RING_MASK);
        int sqArrayOff   = params.getInt(sqOff + SQ_OFF_ARRAY);

        // Read cq_off (io_cqring_offsets) from params
        int cqOff = PARAMS_CQ_OFF_OFF;
        int cqHeadOff    = params.getInt(cqOff + CQ_OFF_HEAD);
        int cqTailOff    = params.getInt(cqOff + CQ_OFF_TAIL);
        int cqRingMaskOff = params.getInt(cqOff + CQ_OFF_RING_MASK);
        int cqCqesOff    = params.getInt(cqOff + CQ_OFF_CQES);

        // mmap the SQ ring (SQ and CQ may share the same mmap since IORING_OFF_SQ_RING == 0
        // and IORING_OFF_CQ_RING == 0x8000000, but we map them separately for clarity)
        this.sqRingSize = sqArrayOff + (long) sqEntries * Integer.BYTES;
        this.sqRingPtr = mmapRing(sqRingSize, ringFd, IORING_OFF_SQ_RING);

        this.cqRingSize = cqCqesOff + (long) cqEntries * CQE_SIZE;
        this.cqRingPtr = mmapRing(cqRingSize, ringFd, IORING_OFF_CQ_RING);

        this.sqesSize = (long) sqEntries * SQE_SIZE;
        this.sqesPtr = mmapRing(sqesSize, ringFd, IORING_OFF_SQES);

        // Compute absolute addresses for ring fields
        long sqBase = Pointer.nativeValue(sqRingPtr);
        this.sqHeadAddr  = sqBase + sqHeadOff;
        this.sqTailAddr  = sqBase + sqTailOff;
        this.sqMask      = unsafe.getInt(sqBase + sqRingMaskOff);
        this.sqArrayAddr = sqBase + sqArrayOff;

        long cqBase = Pointer.nativeValue(cqRingPtr);
        this.cqHeadAddr  = cqBase + cqHeadOff;
        this.cqTailAddr  = cqBase + cqTailOff;
        this.cqMask      = unsafe.getInt(cqBase + cqRingMaskOff);
        this.cqesAddr    = cqBase + cqCqesOff;

        this.sqesBaseAddr = Pointer.nativeValue(sqesPtr);
    }

    /**
     * Submit a single IORING_OP_READ and block until it completes.
     * This is the sync wrapper for the PoC — no batching yet.
     *
     * @param fd       file descriptor to read from
     * @param offset   file offset to read at
     * @param buffer   direct ByteBuffer to read into (position is used as start)
     * @param length   number of bytes to read
     * @return number of bytes read, or negative on error
     * @throws IOException if submission or completion fails
     */
    public int readSync(int fd, long offset, ByteBuffer buffer, int length) throws IOException
    {
        if (closed)
            throw new IOException("Ring is closed");
        if (!buffer.isDirect())
            throw new IllegalArgumentException("Buffer must be direct for io_uring");

        long bufAddr = getDirectBufferAddress(buffer);

        // Fill SQE
        int sqTail = unsafe.getIntVolatile(null, sqTailAddr);
        int index = (int) (sqTail & sqMask);

        // Write the SQE index into the SQ array
        unsafe.putInt(sqArrayAddr + (long) index * Integer.BYTES, index);

        // Fill the SQE at sqes[index]
        long sqeAddr = sqesBaseAddr + (long) index * SQE_SIZE;
        // Zero out the SQE first to avoid stale fields
        unsafe.setMemory(sqeAddr, SQE_SIZE, (byte) 0);

        unsafe.putByte(sqeAddr + SQE_OPCODE_OFF, (byte) IORING_OP_READ);
        unsafe.putInt(sqeAddr + SQE_FD_OFF, fd);
        unsafe.putLong(sqeAddr + SQE_OFF_OFF, offset);
        unsafe.putLong(sqeAddr + SQE_ADDR_OFF, bufAddr + buffer.position());
        unsafe.putInt(sqeAddr + SQE_LEN_OFF, length);
        unsafe.putLong(sqeAddr + SQE_USER_DATA_OFF, 1L); // tag for identification

        // Advance SQ tail (store-release so kernel sees the SQE)
        unsafe.putOrderedInt(null, sqTailAddr, sqTail + 1);

        // Submit and wait for 1 completion
        try
        {
            ioUringEnter(ringFd, 1, 1, IORING_ENTER_GETEVENTS);
        }
        catch (LastErrorException e)
        {
            throw new IOException("io_uring_enter failed: errno " + e.getErrorCode(), e);
        }

        // Read CQE
        int cqHead = unsafe.getIntVolatile(null, cqHeadAddr);
        int cqTail = unsafe.getIntVolatile(null, cqTailAddr);

        if (cqHead == cqTail)
            throw new IOException("No CQE available after io_uring_enter with min_complete=1");

        int cqIndex = (int) (cqHead & cqMask);
        long cqeAddr = cqesAddr + (long) cqIndex * CQE_SIZE;

        int result = unsafe.getInt(cqeAddr + CQE_RES_OFF);

        // Advance CQ head (store-release)
        unsafe.putOrderedInt(null, cqHeadAddr, cqHead + 1);

        if (result < 0)
            throw new IOException("io_uring read failed: errno " + (-result));

        // Update buffer position to reflect bytes read
        buffer.position(buffer.position() + result);

        return result;
    }

    /**
     * Register file descriptors with the ring for use with IOSQE_FIXED_FILE.
     * Registered fds skip the per-op fd lookup in the kernel.
     */
    public void registerFiles(int[] fds) throws IOException
    {
        Memory fdArray = new Memory((long) fds.length * Integer.BYTES);
        for (int i = 0; i < fds.length; i++)
            fdArray.setInt((long) i * Integer.BYTES, fds[i]);

        try
        {
            ioUringRegister(ringFd, IORING_REGISTER_FILES, fdArray, fds.length);
        }
        catch (LastErrorException e)
        {
            throw new IOException("io_uring_register(REGISTER_FILES) failed: errno " + e.getErrorCode(), e);
        }
    }

    /**
     * Unregister previously registered file descriptors.
     */
    public void unregisterFiles() throws IOException
    {
        try
        {
            ioUringRegister(ringFd, IORING_UNREGISTER_FILES, Pointer.NULL, 0);
        }
        catch (LastErrorException e)
        {
            throw new IOException("io_uring_register(UNREGISTER_FILES) failed: errno " + e.getErrorCode(), e);
        }
    }

    @Override
    public void close()
    {
        if (closed)
            return;
        closed = true;

        munmapRing(sqRingPtr, sqRingSize);
        munmapRing(cqRingPtr, cqRingSize);
        munmapRing(sqesPtr, sqesSize);
        closeFd(ringFd);
    }

    int ringFd()
    {
        return ringFd;
    }

    int sqEntries()
    {
        return sqEntries;
    }

    int cqEntries()
    {
        return cqEntries;
    }

    private static final long DIRECT_BUFFER_ADDRESS_OFFSET;

    static
    {
        try
        {
            java.lang.reflect.Field addressField = java.nio.Buffer.class.getDeclaredField("address");
            DIRECT_BUFFER_ADDRESS_OFFSET = unsafe.objectFieldOffset(addressField);
        }
        catch (NoSuchFieldException e)
        {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static long getDirectBufferAddress(ByteBuffer buffer)
    {
        return unsafe.getLong(buffer, DIRECT_BUFFER_ADDRESS_OFFSET);
    }
}
