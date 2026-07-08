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
 * JNA direct mapping of the raw syscalls the io_uring binding needs.
 * <p>
 * Deliberately a separate class from {@link org.apache.cassandra.utils.NativeLibraryLinux}:
 * JNA registers a class's natives all-or-nothing, so a link failure here must not take down
 * the existing libc bindings (and vice versa).
 * <p>
 * JNA direct mapping does not support varargs, so {@code syscall(2)} is declared once per
 * arity actually used. All natives declare {@link LastErrorException} — JNA zeroes errno
 * before each direct-mapped call and throws when the return value is -1, matching raw
 * syscall(2) semantics. {@code mmap} returns {@code MAP_FAILED} (-1), not NULL, on error,
 * which JNA's Pointer error check does not recognise — callers must check
 * {@link #isMapFailed(Pointer)}.
 */
final class UringNative
{
    private static final Logger logger = LoggerFactory.getLogger(UringNative.class);

    private static final boolean available;

    static
    {
        boolean success = false;
        try
        {
            Native.register(com.sun.jna.NativeLibrary.getInstance("c", Collections.emptyMap()));
            success = true;
        }
        catch (NoClassDefFoundError e)
        {
            logger.warn("JNA not found. io_uring binding will be disabled.");
        }
        catch (UnsatisfiedLinkError e)
        {
            logger.error("Failed to link the C library against JNA. io_uring binding will be unavailable.", e);
        }
        catch (NoSuchMethodError e)
        {
            logger.warn("Obsolete version of JNA present; unable to register C library for io_uring. Upgrade to JNA 3.2.7 or later");
        }
        available = success;
    }

    private UringNative() {}

    // io_uring_setup(u32 entries, struct io_uring_params *p)
    private static native long syscall(long number, int entries, Pointer params) throws LastErrorException;

    // io_uring_enter(int fd, u32 to_submit, u32 min_complete, u32 flags, sigset_t *sig, size_t sigsz)
    private static native long syscall(long number, int fd, int toSubmit, int minComplete, int flags, Pointer sig, long sigsz) throws LastErrorException;

    // io_uring_register(int fd, u32 opcode, void *arg, u32 nr_args)
    private static native long syscall(long number, int fd, int opcode, Pointer arg, int nrArgs) throws LastErrorException;

    private static native Pointer mmap(Pointer addr, long length, int prot, int flags, int fd, long offset) throws LastErrorException;

    private static native int munmap(Pointer addr, long length) throws LastErrorException;

    private static native int close(int fd) throws LastErrorException;

    private static native Pointer strerror(int errnum);

    static boolean isAvailable()
    {
        return available;
    }

    /** @return ring fd (>= 0); throws LastErrorException on failure */
    static long ioUringSetup(int entries, Pointer params) throws LastErrorException
    {
        return syscall(UringConstants.SYS_IO_URING_SETUP, entries, params);
    }

    /** @return number of SQEs consumed (or CQEs available with GETEVENTS); throws LastErrorException on failure */
    static long ioUringEnter(int ringFd, int toSubmit, int minComplete, int flags) throws LastErrorException
    {
        return syscall(UringConstants.SYS_IO_URING_ENTER, ringFd, toSubmit, minComplete, flags, Pointer.NULL, 0L);
    }

    /** @return 0 on success; throws LastErrorException on failure */
    static long ioUringRegister(int ringFd, int opcode, Pointer arg, int nrArgs) throws LastErrorException
    {
        return syscall(UringConstants.SYS_IO_URING_REGISTER, ringFd, opcode, arg, nrArgs);
    }

    /** @return mapped address; callers MUST check {@link #isMapFailed(Pointer)} — errno is in the thrown exception only for -1-returning natives, so on MAP_FAILED use {@link Native#getLastError()} */
    static Pointer map(long length, int fd, long offset) throws LastErrorException
    {
        return mmap(Pointer.NULL, length,
                    UringConstants.PROT_READ | UringConstants.PROT_WRITE,
                    UringConstants.MAP_SHARED | UringConstants.MAP_POPULATE,
                    fd, offset);
    }

    static boolean isMapFailed(Pointer p)
    {
        return p == null || Pointer.nativeValue(p) == -1L;
    }

    static int unmap(Pointer addr, long length) throws LastErrorException
    {
        return munmap(addr, length);
    }

    static int closeFd(int fd) throws LastErrorException
    {
        return close(fd);
    }

    /** Human-readable message for an errno value; never throws. */
    static String errnoDescription(int errno)
    {
        try
        {
            Pointer msg = strerror(errno);
            return msg == null ? "errno " + errno : msg.getString(0) + " (errno " + errno + ')';
        }
        catch (Throwable t)
        {
            return "errno " + errno;
        }
    }
}
