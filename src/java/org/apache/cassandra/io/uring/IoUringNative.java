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

import java.util.Collections;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNA bindings for Linux io_uring syscalls. io_uring provides asynchronous I/O via shared
 * submission/completion ring buffers between userspace and kernel, eliminating per-operation
 * syscall overhead when batching.
 *
 * These are raw syscall wrappers invoked via libc's syscall() function since io_uring_setup,
 * io_uring_enter, and io_uring_register are not in libc — they are direct syscalls.
 *
 * Requires Linux 5.6+ (x86_64). Falls back gracefully on unsupported platforms.
 */
public class IoUringNative
{
    private static final Logger logger = LoggerFactory.getLogger(IoUringNative.class);

    private static volatile boolean available;

    // x86_64 syscall numbers (from arch/x86/entry/syscalls/syscall_64.tbl)
    static final int SYS_IO_URING_SETUP    = 425;
    static final int SYS_IO_URING_ENTER    = 426;
    static final int SYS_IO_URING_REGISTER = 427;

    // io_uring_setup flags
    static final int IORING_SETUP_SQPOLL   = 1 << 1;
    static final int IORING_SETUP_CQSIZE   = 1 << 3;

    // io_uring_enter flags
    static final int IORING_ENTER_GETEVENTS = 1;

    // io_uring opcodes (SQE)
    static final int IORING_OP_NOP         = 0;
    static final int IORING_OP_READV       = 1;
    static final int IORING_OP_WRITEV      = 2;
    static final int IORING_OP_READ_FIXED  = 4;
    static final int IORING_OP_WRITE_FIXED = 5;
    static final int IORING_OP_READ        = 22;
    static final int IORING_OP_WRITE       = 23;

    // io_uring_register opcodes
    static final int IORING_REGISTER_BUFFERS   = 0;
    static final int IORING_UNREGISTER_BUFFERS = 1;
    static final int IORING_REGISTER_FILES     = 2;
    static final int IORING_UNREGISTER_FILES   = 3;

    // SQE flags
    static final int IOSQE_FIXED_FILE = 1;

    // mmap constants
    static final int PROT_READ  = 0x1;
    static final int PROT_WRITE = 0x2;
    static final int MAP_SHARED = 0x01;
    static final int MAP_POPULATE = 0x08000;

    // mmap offsets for io_uring ring buffers (from include/uapi/linux/io_uring.h)
    static final long IORING_OFF_SQ_RING = 0L;
    static final long IORING_OFF_CQ_RING = 0x8000000L;
    static final long IORING_OFF_SQES    = 0x10000000L;

    // SQE struct size
    static final int SQE_SIZE = 64;

    // CQE struct size
    static final int CQE_SIZE = 16;

    // io_uring_params struct offsets
    static final int PARAMS_SIZE = 120;
    static final int PARAMS_SQ_ENTRIES_OFF  = 0;
    static final int PARAMS_CQ_ENTRIES_OFF  = 4;
    static final int PARAMS_FLAGS_OFF       = 8;
    static final int PARAMS_SQ_OFF_OFF      = 24; // start of sq_off struct (io_sqring_offsets)
    static final int PARAMS_CQ_OFF_OFF      = 64; // start of cq_off struct (io_cqring_offsets)

    // io_sqring_offsets field offsets (relative to sq_off start)
    static final int SQ_OFF_HEAD        = 0;
    static final int SQ_OFF_TAIL        = 4;
    static final int SQ_OFF_RING_MASK   = 8;
    static final int SQ_OFF_RING_ENTRIES = 12;
    static final int SQ_OFF_FLAGS       = 16;
    static final int SQ_OFF_DROPPED     = 20;
    static final int SQ_OFF_ARRAY       = 24;

    // io_cqring_offsets field offsets (relative to cq_off start)
    static final int CQ_OFF_HEAD        = 0;
    static final int CQ_OFF_TAIL        = 4;
    static final int CQ_OFF_RING_MASK   = 8;
    static final int CQ_OFF_RING_ENTRIES = 12;
    static final int CQ_OFF_OVERFLOW    = 16;
    static final int CQ_OFF_CQES        = 20;

    // SQE field offsets within a 64-byte SQE
    static final int SQE_OPCODE_OFF    = 0;  // u8
    static final int SQE_FLAGS_OFF     = 1;  // u8
    static final int SQE_IOPRIO_OFF    = 2;  // u16
    static final int SQE_FD_OFF        = 4;  // s32
    static final int SQE_OFF_OFF       = 8;  // u64 (offset)
    static final int SQE_ADDR_OFF      = 16; // u64 (addr/buf)
    static final int SQE_LEN_OFF       = 24; // u32
    static final int SQE_RW_FLAGS_OFF  = 28; // u32 (union: rw_flags, fsync_flags, etc.)
    static final int SQE_USER_DATA_OFF = 32; // u64
    static final int SQE_BUF_INDEX_OFF = 40; // u16 (union start)

    // CQE field offsets
    static final int CQE_USER_DATA_OFF = 0;  // u64
    static final int CQE_RES_OFF       = 8;  // s32
    static final int CQE_FLAGS_OFF     = 12; // u32

    static
    {
        try
        {
            Native.register(com.sun.jna.NativeLibrary.getInstance("c", Collections.emptyMap()));
            available = true;
        }
        catch (NoClassDefFoundError e)
        {
            logger.debug("JNA not found. io_uring will be disabled.");
        }
        catch (UnsatisfiedLinkError e)
        {
            logger.debug("Failed to link C library for io_uring. io_uring will be disabled.");
        }
        catch (NoSuchMethodError e)
        {
            logger.debug("Obsolete version of JNA present; io_uring will be disabled.");
        }
    }

    // libc syscall() — used to invoke io_uring syscalls which aren't in libc
    private static native long syscall(long number, Object... args) throws LastErrorException;

    // libc mmap/munmap — for mapping the ring buffers
    private static native Pointer mmap(Pointer addr, long length, int prot, int flags, int fd, long offset) throws LastErrorException;
    private static native int munmap(Pointer addr, long length) throws LastErrorException;
    private static native int close(int fd) throws LastErrorException;

    /**
     * io_uring_setup(2) — create an io_uring instance.
     *
     * @param entries number of SQ entries (rounded up to power of 2 by kernel)
     * @param params  pointer to io_uring_params struct (120 bytes, zeroed before call)
     * @return ring file descriptor on success, or throws LastErrorException
     */
    static int ioUringSetup(int entries, Pointer params) throws LastErrorException
    {
        return (int) syscall(SYS_IO_URING_SETUP, entries, params);
    }

    /**
     * io_uring_enter(2) — submit I/O requests and/or wait for completions.
     *
     * @param ringFd      ring file descriptor from io_uring_setup
     * @param toSubmit    number of SQEs to submit
     * @param minComplete minimum number of completions to wait for
     * @param flags       IORING_ENTER_GETEVENTS to wait
     * @return number of SQEs consumed on success
     */
    static int ioUringEnter(int ringFd, int toSubmit, int minComplete, int flags) throws LastErrorException
    {
        return (int) syscall(SYS_IO_URING_ENTER, ringFd, toSubmit, minComplete, flags, Pointer.NULL, 0);
    }

    /**
     * io_uring_register(2) — register resources (files, buffers) with the ring.
     *
     * @param ringFd ring file descriptor
     * @param opcode IORING_REGISTER_FILES, IORING_REGISTER_BUFFERS, etc.
     * @param arg    pointer to array of resources
     * @param nrArgs number of resources
     * @return 0 on success
     */
    static int ioUringRegister(int ringFd, int opcode, Pointer arg, int nrArgs) throws LastErrorException
    {
        return (int) syscall(SYS_IO_URING_REGISTER, ringFd, opcode, arg, nrArgs);
    }

    /**
     * mmap a region of the io_uring ring buffer.
     */
    static Pointer mmapRing(long length, int fd, long offset)
    {
        return mmap(Pointer.NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, fd, offset);
    }

    /**
     * Unmap a previously mmap'd ring region.
     */
    static void munmapRing(Pointer addr, long length)
    {
        if (addr != null && addr != Pointer.NULL)
            munmap(addr, length);
    }

    /**
     * Close a file descriptor.
     */
    static void closeFd(int fd)
    {
        if (fd >= 0)
        {
            try
            {
                close(fd);
            }
            catch (LastErrorException e)
            {
                logger.warn("Failed to close fd {}: errno {}", fd, e.getErrorCode());
            }
        }
    }

    static boolean isAvailable()
    {
        return available;
    }
}
