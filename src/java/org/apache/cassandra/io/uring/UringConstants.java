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

/**
 * io_uring UAPI constants. Single source of truth for the {@code org.apache.cassandra.io.uring}
 * package; nothing here may be derived from any other class.
 * <p>
 * Every value was verified against {@code include/uapi/linux/io_uring.h} of the benchmark rig's
 * running kernel (6.8.0-124-generic, Ubuntu 22.04 HWE) via a compiled offsetof/sizeof probe on
 * 2026-07-08. Struct layouts are additionally validated empirically at runtime by
 * {@code UringAvailabilityTest} (a live 4-entry ring's reported fields must be self-consistent).
 * <p>
 * The io_uring UAPI is append-only: existing constants and struct offsets never change; new
 * kernels only add features. These values are therefore correct on every kernel this binding
 * accepts (the availability probe enforces the floor).
 */
public final class UringConstants
{
    private UringConstants() {}

    // syscall numbers — identical on x86_64 and aarch64 (asm-generic table)
    public static final long SYS_IO_URING_SETUP = 425;
    public static final long SYS_IO_URING_ENTER = 426;
    public static final long SYS_IO_URING_REGISTER = 427;

    // io_uring_params.features bits (read at offset PARAMS_FEATURES after setup)
    public static final int IORING_FEAT_SINGLE_MMAP = 1;       // 5.4
    public static final int IORING_FEAT_NODROP = 1 << 1;       // 5.5
    public static final int IORING_FEAT_SUBMIT_STABLE = 1 << 2; // 5.5

    // io_uring_setup flags
    public static final int IORING_SETUP_CQSIZE = 1 << 3;        // 5.5
    public static final int IORING_SETUP_CLAMP = 1 << 4;
    public static final int IORING_SETUP_COOP_TASKRUN = 1 << 8;  // 5.19
    public static final int IORING_SETUP_TASKRUN_FLAG = 1 << 9;  // 5.19
    public static final int IORING_SETUP_SINGLE_ISSUER = 1 << 12; // 6.0
    public static final int IORING_SETUP_DEFER_TASKRUN = 1 << 13; // 6.1

    // io_uring_enter flags
    public static final int IORING_ENTER_GETEVENTS = 1;

    // opcodes. Phase 1 emits only READ/WRITE (non-vectored, 5.6+), READ_FIXED/WRITE_FIXED
    // and FSYNC; READV/WRITEV are retained for completeness but unused.
    public static final int IORING_OP_READV = 1;
    public static final int IORING_OP_WRITEV = 2;
    public static final int IORING_OP_FSYNC = 3;
    public static final int IORING_OP_READ_FIXED = 4;
    public static final int IORING_OP_WRITE_FIXED = 5;
    public static final int IORING_OP_READ = 22;
    public static final int IORING_OP_WRITE = 23;

    // sqe.fsync_flags
    public static final int IORING_FSYNC_DATASYNC = 1;

    // io_uring_register opcodes
    public static final int IORING_REGISTER_BUFFERS = 0;
    public static final int IORING_UNREGISTER_BUFFERS = 1;
    public static final int IORING_REGISTER_FILES = 2;
    public static final int IORING_UNREGISTER_FILES = 3;

    // mmap offsets selecting which ring region an mmap of the ring fd maps
    public static final long IORING_OFF_SQ_RING = 0;
    public static final long IORING_OFF_CQ_RING = 0x8000000L;
    public static final long IORING_OFF_SQES = 0x10000000L;

    // limits
    public static final int IORING_MAX_ENTRIES = 32768;

    // struct io_uring_params (120 bytes; historic pitfall: omitting wq_fd + resv[3]
    // puts sq_off at 24 — wrong)
    public static final int PARAMS_SIZE = 120;
    public static final int PARAMS_SQ_ENTRIES = 0;
    public static final int PARAMS_CQ_ENTRIES = 4;
    public static final int PARAMS_FLAGS = 8;
    public static final int PARAMS_SQ_THREAD_CPU = 12;
    public static final int PARAMS_SQ_THREAD_IDLE = 16;
    public static final int PARAMS_FEATURES = 20;
    public static final int PARAMS_WQ_FD = 24;
    public static final int PARAMS_SQ_OFF = 40;   // struct io_sqring_offsets, 40 bytes
    public static final int PARAMS_CQ_OFF = 80;   // struct io_cqring_offsets, 40 bytes

    // struct io_sqring_offsets fields (relative to PARAMS_SQ_OFF)
    public static final int SQ_OFF_HEAD = 0;
    public static final int SQ_OFF_TAIL = 4;
    public static final int SQ_OFF_RING_MASK = 8;
    public static final int SQ_OFF_RING_ENTRIES = 12;
    public static final int SQ_OFF_FLAGS = 16;
    public static final int SQ_OFF_DROPPED = 20;
    public static final int SQ_OFF_ARRAY = 24;

    // struct io_cqring_offsets fields (relative to PARAMS_CQ_OFF)
    public static final int CQ_OFF_HEAD = 0;
    public static final int CQ_OFF_TAIL = 4;
    public static final int CQ_OFF_RING_MASK = 8;
    public static final int CQ_OFF_RING_ENTRIES = 12;
    public static final int CQ_OFF_OVERFLOW = 16;
    public static final int CQ_OFF_CQES = 20;

    // struct io_uring_sqe (64 bytes)
    public static final int SQE_SIZE = 64;
    public static final int SQE_OPCODE = 0;      // u8
    public static final int SQE_FLAGS = 1;       // u8
    public static final int SQE_IOPRIO = 2;      // u16
    public static final int SQE_FD = 4;          // s32
    public static final int SQE_OFF = 8;         // u64
    public static final int SQE_ADDR = 16;       // u64
    public static final int SQE_LEN = 24;        // u32
    public static final int SQE_RW_FLAGS = 28;   // u32 union (rw_flags / fsync_flags / ...)
    public static final int SQE_USER_DATA = 32;  // u64
    public static final int SQE_BUF_INDEX = 40;  // u16

    // struct io_uring_cqe (16 bytes)
    public static final int CQE_SIZE = 16;
    public static final int CQE_USER_DATA = 0;   // u64
    public static final int CQE_RES = 8;         // s32 (negative = -errno)
    public static final int CQE_FLAGS = 12;      // u32

    // mmap protection/flags for the ring regions
    public static final int PROT_READ = 1;
    public static final int PROT_WRITE = 2;
    public static final int MAP_SHARED = 1;
    public static final int MAP_POPULATE = 0x8000;

    // errno values this binding handles by name
    public static final int EINTR = 4;
    public static final int EAGAIN = 11;
    public static final int EBUSY = 16;
    public static final int EINVAL = 22;
    public static final int EPERM = 1;
    public static final int EBADR = 53;
}
