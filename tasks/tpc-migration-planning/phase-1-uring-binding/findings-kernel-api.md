# Research report — io_uring kernel API facts, primary-sourced (2026-07-05)

Sources: man7.org (io_uring_setup(2), io_uring_enter(2), io_uring_register(2), io_uring(7),
open(2)), torvalds/linux + axboe/liburing on GitHub, kernel.dk/io_uring.pdf, docs.kernel.org,
LWN, patchwork/lore, Red Hat/Ubuntu docs. UNVERIFIED items flagged.

## 1. Syscall numbers — identical on x86_64 AND aarch64
`io_uring_setup=425`, `io_uring_enter=426`, `io_uring_register=427` in BOTH
`arch/x86/entry/syscalls/syscall_64.tbl` and `include/uapi/asm-generic/unistd.h` (arm64),
cross-checked in `scripts/syscall.tbl`. One constant set works for both.

## 2. IORING_FEAT flags (read `params.features` @ offset 20 after setup)
| Flag | Value | Kernel | Absence consequence |
|---|---|---|---|
| SINGLE_MMAP | 1<<0 | 5.4 | must do 3 mmaps (SQ ring @0, CQ ring @0x8000000, SQEs @0x10000000); present → 2 mmaps (rings share one region) |
| NODROP | 1<<1 | 5.5 | overflow drops CQEs (counted in overflow field); present → kernel buffers overflow, enter may return -EBUSY; since 5.19 -EBADR if a CQE was dropped anyway (clears error state) |
| SUBMIT_STABLE | 1<<2 | 5.5 | absent: indirect data (iovecs) must stay stable until COMPLETION not submit |
| EXT_ARG | 1<<8 | 5.11 | enables io_uring_getevents_arg (timeout+sigmask) on enter |
All of the first three always set ≥5.5; assert at startup anyway. Others: RW_CUR_POS/CUR_PERSONALITY 5.6,
FAST_POLL 1<<5 5.7, POLL_32BITS 5.9, SQPOLL_NONFIXED 1<<7 + EXT_ARG 5.11, NATIVE_WORKERS 5.12,
RSRC_TAGS 5.13, CQE_SKIP/LINKED_FILE 5.17, REG_REG_RING 1<<13 6.3.

## 3. Registered buffers (IORING_REGISTER_BUFFERS=0; READ_FIXED=4/WRITE_FIXED=5)
- Since 5.1. iovec array; pages locked, charged to RLIMIT_MEMLOCK (skipped with CAP_IPC_LOCK).
  buf_index selects; addr/len must fall WITHIN the indexed buffer (sub-ranges legal).
- Why faster (io_uring.pdf §8.1): eliminates per-I/O kernel map/unmap (get_user_pages/put) —
  biggest at high-IOPS O_DIRECT. **Concrete % gains: UNVERIFIED (no number in primary sources).**
- Limits: man page 1 GiB/buffer (EFAULT above); UIO_MAXIOV=1024 EINVAL entry reflects pre-5.13;
  current mainline `IORING_MAX_REG_BUFFERS = 1<<14` (16384) and SZ_1T cap (rsrc.c).
  **Portable floor: ≤1024 buffers, ≤1 GiB each.** Version of the raises: UNVERIFIED.
- Lifetime: pinned from registration; teardown auto-unregisters (possibly async). Java side must
  keep VA mapping + strong refs alive while registered. File-backed memory rejected
  (EOPNOTSUPP/EFAULT) — anonymous only (direct allocations qualify).
- Pre-5.13 (re)registration waits for ring idle; BUFFERS2/UPDATE (incremental, tagged) since 5.13.

## 4. SQPOLL
- Privileges: pre-5.11 privileged (-EPERM; CAP_SYS_ADMIN era) + required fixed files;
  5.11: CAP_SYS_NICE ok + SQPOLL_NONFIXED; 5.13+: no special privileges.
- sq_thread_idle ms before kthread sleeps (default 1 s). Wakeup: kernel sets
  IORING_SQ_NEED_WAKEUP (1<<0) in SQ flags → app calls enter with IORING_ENTER_SQ_WAKEUP (1<<1).
  liburing skips the syscall entirely for SQPOLL rings unless flagged.
- Advisability: one never-sleeping kthread per ring (unless ATTACH_WQ); can hurt latency
  (liburing issues #345, #729). For per-shard rings: plain enter + DEFER_TASKRUN is the modern
  recommendation; SQPOLL only with dedicated polling cores. (Judgment synthesized from cited sources.)

## 5. Task-run flags
- COOP_TASKRUN (1<<8, 5.19): avoids IPI/forced reschedule for completion task_work; do NOT use
  if the waiting thread isn't the submitting thread. TASKRUN_FLAG (1<<9, 5.19) exposes
  IORING_SQ_TASKRUN in SQ flags.
- SINGLE_ISSUER (1<<12, 6.0): single submitting task, kernel-enforced (-EEXIST on violation).
- DEFER_TASKRUN (1<<13, 6.1): task work runs only when app waits. REQUIRES SINGLE_ISSUER and
  enter-from-submitting-thread; app must periodically wait or completions may not be delivered.
- **Recommended for one-ring-per-shard-thread: SINGLE_ISSUER|DEFER_TASKRUN on ≥6.1; COOP_TASKRUN
  on 5.19–6.0; plain below.** A sync facade trivially satisfies DEFER_TASKRUN obligations.
  liburing wiki: "Not sharing a ring between threads is the recommended way."

## 6. O_DIRECT via io_uring
- Alignment identical to pread O_DIRECT (io_uring drives the same ->read_iter path — mild
  inference). Typically logical block size (512); query ioctl(BLKSSZGET) or statx STATX_DIOALIGN (6.1+).
- IORING_OP_READ/WRITE (5.6) = non-vectored READV/WRITEV (5.1); no O_DIRECT semantic difference;
  READ avoids marshalling an iovec through JNA.
- Inline vs punt: sqe issued non-blocking first (NOWAIT infra); fs returns -EAGAIN → punt to
  io-wq. -EAGAIN causes: unallocated blocks (writes), required writeback, un-lockable i_rwsem,
  congested device. NOWAIT DIO merged ~4.13/4.14 for xfs/ext4/btrfs — present in every
  io_uring-capable kernel. O_DIRECT reads of allocated extents generally submit inline;
  allocating/extending O_DIRECT writes are the classic punt. IOSQE_ASYNC forces punt.
- Finer ext4-vs-XFS punt differences: UNVERIFIED — benchmark punt rates per fs (Phase 2 does).

## 7. Short reads on buffered IORING_OP_READ — REAL, must resubmit remainder
- History: 5.1/5.2-era bug (head-cached/tail-uncached short reads) fixed by commit 9d93a3f5a0c
  "punt short reads to async context"; 227c0c9673d8 "internally retry short reads" (5.9-era;
  exact tag UNVERIFIED).
- Current contract (QEMU 2022, confirmed w/ Axboe; btrfs bug precedent): "Short reads are rare
  but may occur. The remaining read request needs to be resubmitted."
- liburing does NOT auto-retry. QEMU pattern (shorten iovec, advance offset, resubmit) is the
  reference. Short reads also break IOSQE_IO_LINK chains (-ECANCELED for the rest).

## 8. CQ sizing
- Default cq_entries = 2 × sq_entries (io_uring_fill_params; "possible for the application to
  drive a higher depth than the SQ ring, since sqes are only used at submission time").
- IORING_SETUP_CQSIZE (1<<3, 5.5 per liburing docs — man7 version UNVERIFIED): app-chosen,
  > entries, rounded to power of two. CLAMP (1<<4) clamps oversize. Caps: IORING_MAX_ENTRIES
  32768, MAX_CQ 65536.
- QD-64/ring: sq=64 → default cq=128 fine if reap-before-submit; in-flight may exceed sq_entries
  (slots recycle at submit) — size CQ ≥ max un-reaped; overflow non-fatal ≥5.5 but slow
  (kernel-side alloc) — size to never overflow.

## 9. enter min_complete / GETEVENTS / EINTR
- GETEVENTS (1<<0): waits until ≥min_complete CQEs AVAILABLE in the ring (already-present count;
  returns immediately if satisfied). Must be set to wait at all. min_complete=0 + GETEVENTS =
  flush available, return.
- Sync facade: enter(fd, 1, 1, GETEVENTS) — one syscall submits and waits. Batched:
  enter(fd, N, k, GETEVENTS). Return value = SQEs consumed. Remaining completions reaped from
  the ring without syscalls.
- **EINTR returned to userspace, NOT restarted**: kernel sets -EINTR directly in io_cqring_wait
  (patch queued 5.4; original converted -ERESTARTSYS → -EINTR) → **SA_RESTART does not help**;
  liburing passes it up. JVM signal activity (safepoints/GC/profiling) makes EINTR routine —
  the binding MUST loop, and MUST recompute to_submit from ring state on retry (SQEs may have
  been consumed before the interrupt).

## 10. Memory ordering contract (from io_uring(7) + liburing barriers)
| Access | Ordering | liburing comment |
|---|---|---|
| Write SQ tail (after SQE fill) | store-release (mandatory under SQPOLL; syscall orders it otherwise — always use release, free on x86, stlr on arm64) | "Ensure kernel sees the SQE updates before the tail update" |
| Read SQ head (free-space check) | load-acquire under SQPOLL; plain otherwise | "we could overwrite a SQE before the kernel finished reading it" |
| Read CQ tail (peek) | load-acquire | "pairs with the kernel side publishing CQEs... guarantees contents of [head, tail)" |
| Write CQ head (after consume) | store-release | "kernel only sees the new head after the CQEs have been read" |
| Read SQ flags (NEED_WAKEUP/TASKRUN) | READ_ONCE / volatile | liburing sq_ring_needs_enter |
Java mapping used by this task (Phase 0 D2): Unsafe.getIntVolatile (≥acquire) /
Unsafe.putOrderedInt (release). VarHandle equivalent noted as future cleanup.

## 11. kernel.io_uring_disabled sysctl (6.6 cycle)
0 = enabled (upstream default) · 1 = disabled for unprivileged outside io_uring_group
(EPERM from setup; CAP_SYS_ADMIN exempt) · 2 = disabled for all (EPERM; existing instances usable).
Ubuntu backported the sysctl (Focal/Jammy/Mantic, Oct 2023) but default stays 0; Debian default 0
(explicit statement UNVERIFIED). **RHEL 9 default-disables** (Tech Preview). Docker default
seccomp has blocked the three syscalls since mid-2023 (version specifics UNVERIFIED) — relevant
only in containers. Binding must map EPERM from setup to a clear diagnostic.
Rig verified 2026-07-05: `kernel.io_uring_disabled = 0`.

## UNVERIFIED summary
Fixed-buffer % gains; versions of 1024→16384 / 1GiB→1TB raises; release tag of 227c0c9673d8;
fine ext4-vs-XFS punt differences; CQSIZE intro version from man7; Debian default & Docker
seccomp versions.
