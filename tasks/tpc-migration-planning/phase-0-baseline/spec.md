# Phase 0 Spec — Baseline, Decision Records, Rig Provisioning

**Status:** Ready to execute. All facts below verified against code/docs on 2026-07-05 (three
explorer sweeps). Every claim carries a file:line or command ref.
**Purpose:** Pin the decisions every later phase depends on, so no downstream agent re-litigates
them, and provision the rig so Phase 2 is unblocked.

> Note: a local branch `iouring-poc` exists in this repo. Per user direction it is **unrelated
> to this task and out of scope** — do not base work on it, rebase it, or cite its results.
> This note exists solely so future sessions don't rediscover it and rebuild the plan around it.

---

## 1. Pinned decision records

### D1. Binding technology: JNA raw-syscall + Unsafe ring access (greenfield, in-tree)

io_uring's three syscalls (`io_uring_setup`/`enter`/`register`) have **no libc wrappers** —
binding options are raw `syscall(2)` or liburing. Decision matrix:

| Alternative | Verdict | Reason (verified) |
|---|---|---|
| **JNA raw syscalls via libc `syscall()` + Unsafe for ring memory** | **CHOSEN** | Works on JDK 11/17/21; matches the established `NativeLibrary` idiom (`NativeLibraryLinux.java:56` `Native.register` against libc); zero new dependencies; the flush-write-pacing `sync_file_range` addition is the exact in-tree recipe for a new Linux-only call |
| Panama FFM (`java.lang.foreign`) | REJECTED | Max supported JDK is **21** (`build.xml:48` `java.supported=11,17,21`; hard fail `build.xml:225-227`). FFM is preview-only ≤21; zero FFM usage in-tree; `--enable-preview` conflicts with the multi-JDK build. Revisit when trunk moves to JDK 22+ |
| jasyncfio / third-party Java io_uring lib | REJECTED | New dep + bundled JNI `.so`; adds OWASP+Snyk+LICENSE burden (`.build/build-owasp.xml`, `.snyk`) and an external abstraction we'd fight when shaping a TPC-specific API |
| Netty 4.2 `transport-native-io_uring` | REJECTED for file I/O | Network transport, not a file-I/O API; Cassandra is on Netty 4.1.130; the 4.1→4.2 upgrade is a separate large effort |
| Vendored liburing `.so` | REJECTED | No native build machinery exists in-tree (no `native/` dir, no `cc` tasks — verified by find/grep); raw syscalls remove the need entirely |

Known JNA constraint to design around: **JNA direct mapping does not support varargs** — the
binding must declare fixed-arity `syscall()` overloads per signature actually used.

### D2. Memory-ordering idiom: Unsafe volatile/ordered access on mmap'd ring memory

The io_uring userspace contract requires acquire-loads on kernel-written indices and
release-stores when advancing SQ tail / CQ head (verify exact contract in Phase 1 against
io_uring(7)/liburing barriers). The codebase has **zero** VarHandle/fence usage (repo-wide
grep); its release idiom is `Atomic*FieldUpdater.lazySet` (e.g. `ShareableBytes.java:106`),
which cannot address off-heap memory. The JDK-11-safe equivalent for raw addresses is
`Unsafe.getIntVolatile` (acquire) / `Unsafe.putOrderedInt` (release) — Unsafe is already an
established pattern (`MemoryUtil.java:30-53`, `Memory.java:30-45`).
**Pin: Unsafe volatile/ordered ops; do not introduce VarHandle in this task** (note as future
cleanup if trunk drops JDK 11).

### D3. Architecture portability: assert-or-support at availability-probe time

Syscall numbers and struct layouts must be treated as per-arch facts, not constants copied
from one machine. Phase 1 must verify io_uring syscall numbers for x86_64 AND aarch64 against
kernel headers (they are expected to be identical, 425/426/427, post-table-unification — the
kernel-facts research confirms/refutes) and the availability probe must hard-fail on any arch
not explicitly verified. Rig is AMD x86_64 (direct_io runbook :22), so Phase 2 needs x86_64
only; aarch64 correctness matters for upstreaming.

### D4. Benchmark framing: Phase 2 measures the TPC-shaped I/O question

First-principles: under thread-per-core, one shard thread owns all I/O for its core. With
synchronous `pread64` that thread serialises to **QD1** — it cannot saturate NVMe alone. The
current architecture gets high QD for free from ~50 concurrent request threads. Therefore the
question that decides TPC's I/O layer is NOT "does io_uring speed up today's Cassandra" but:
**"can 1 thread + ring at QD 8–64 match/beat N threads + pread64 on this rig — buffered and
O_DIRECT, cache-cold and cache-hot, ext4 and XFS?"** Phase 2's matrix is built around that
A/B (full matrix in phase-2 spec). Expect single-op cache-hot ring I/O to LOSE to pread64
(syscall parity + ring overhead) — the win condition is batching + real device I/O.

---

## 2. Verified current state (evidence base)

### 2.1 JDK / build envelope
- JDK 11 default, 11/17/21 supported, hard-fail otherwise: `build.xml:47-48,225-227`.
- Per-JDK conf exists only for 11/17/21: `conf/jvm{11,17,21}-server.options`; ant resource
  sets `build.xml:258,295,339`. CI derives its matrix from the same properties
  (`.jenkins/Jenkinsfile:239-242`).
- JNA **5.13.0** (`.build/parent-maven-pom.xml:555-565`); jnr-ffi 2.2.13 also present
  (transitive, Chronicle). New deps go in `.build/cassandra-deps-maven-pom.xml` +
  `.build/parent-maven-pom.xml`; `lib/` is resolver output, gitignored (`.gitignore:16`).
- Zero `java.lang.foreign`/`MemorySegment` usage in `src/`+`test/` (grep-verified).

### 2.2 Native binding infrastructure (the template Phase 1 extends)
- Dispatch: `NativeLibrary.java:100-108` picks `NativeLibraryDarwin`/`NativeLibraryLinux`
  into `NativeLibraryWrapper` (`:80`). Full syscall surface: `NativeLibraryWrapper.java:34-45`.
- The `sync_file_range` addition on this branch is the canonical recipe for a new Linux-only
  call: interface method (`NativeLibraryWrapper.java:40`) + Linux native decl
  (`NativeLibraryLinux.java:77,104-107`) + Darwin `UnsatisfiedLinkError` stub
  (`NativeLibraryDarwin.java:103-107`) + try/degrade facade (`NativeLibrary.java:291-329`).
- errno via `LastErrorException.getErrorCode()` (`NativeLibrary.java:156-169`); fd extraction
  via reflection `NativeLibrary.getfd(FileChannel)` (`:434-469`, returns −1 on failure).
- All-native-or-nothing JNA class-linking hazard documented at `NativeLibraryLinux.java:30-44`
  — a new io_uring binding class must be a SEPARATE class so a link failure can't take down
  the existing libc bindings.

### 2.3 I/O seams (where an io_uring layer plugs in — Phase 1/3 reference)
- **Reader seam:** `ChunkReader.readChunk(long, ByteBuffer)` (`ChunkReader.java:39`), selected
  in `FileHandle.Builder.complete(...)` (`FileHandle.java:445-536`); non-mmap uncompressed
  reads flow `BufferManagingRebufferer` → `SimpleChunkReader`; compressed direct reads flow
  `CompressedChunkReader.Direct`.
- **Writer seam:** `DataComponent.buildWriter(...)` factory (`DataComponent.java:98-139`) with
  total `EnumMap<OperationType, DirectIoSupport>` eligibility (`:50-96`).
- **Read path is fully synchronous today** — zero async machinery (grep: no
  `AsynchronousFileChannel`/`CompletableFuture` reads in `src/java`).
- Aligned buffers: `BufferUtil.allocateDirectAligned` (+ attachment-based free) —
  `DirectThreadLocalByteBufferHolder.java:66`, `DirectCompressedSequentialWriter.java:128`;
  raw address via `MemoryUtil.getAddress` (`MemoryUtil.java:79`).
- Block-size probing: `FileUtils.getBlockSize`/`isDirectIOSupported` (`FileUtils.java:789,745`).

### 2.4 Rig (157.180.98.112 — Hetzner, replaces decommissioned 65.108.227.158)
Authoritative record: `/Users/samlightfoot/repos/direct_io/docs/runbook.md` (SSH access :5-15).
- 64 GB RAM; bench standard = 12 GB cgroup + 4 GB heap (runbook :22-23).
- 2×894 GB Samsung NVMe, **not RAID**: `/` 50 GB (`nvme1n1p2`), `/data` 843 GB ext4
  (`nvme1n1p3`), `/commitlog` 880 GB ext4 (whole `nvme0n1`) (runbook :24, §9.3).
- **All ext4. No XFS partition exists.** Readahead 256 KB.
- **Kernel version, distro, mount options: NOT RECORDED — verified in sub-phase 0.1.**
- Cassandra runs in place from `/root/repos/fork/cassandra` (branch `fdp-poc`); use an
  isolated clone for new branches (runbook :163-166). easy-cass-stress NOT currently
  installed (runbook :19,205).
- WAF work on this rig is measurement-complete (writeup pending) — repurposing the commitlog
  disk is acceptable **with user sign-off** (sub-phase 0.3 gate).

---

## 3. Sub-phases

> Execution rules for every sub-phase: append results to `../progress.md`; record every
> rig fact discovered into `../runbook.md` (create in 0.1); never trust a bench/test result
> without verifying the running artifact (`ant jar`, check timestamp, `unzip -l | grep` the
> class — lessons: cassandra-jar-rebuild).

### 0.1 Rig baseline verification → deliverable: `tasks/tpc-migration-planning/runbook.md`

Steps (all over SSH per direct_io runbook :5-15; alias `box` does NOT work from scripts):
1. Capture: `uname -r`, `cat /etc/os-release`, `mount | grep nvme`, `lsblk -o NAME,SIZE,FSTYPE,MOUNTPOINT`,
   `java -version` (rig JDK), `nproc`, `cat /sys/block/nvme*n1/queue/read_ahead_kb`,
   `uname -m` (expect x86_64).
2. io_uring probe: `grep io_uring_setup /proc/kallsyms | head -1` (present since 5.1 — expect yes);
   check `sysctl kernel.io_uring_disabled` (exists ≥6.6; must be 0 or absent).
2b. IRQ/steering environment (Enberg ANCS'19: IRQ affinity was their dominant tail
   variable, bigger than the app architecture — `../findings-tpc-paper.md` C1):
   `systemctl status irqbalance` (record enabled/disabled — do not change yet),
   `grep nvme /proc/interrupts`, `cat /proc/irq/default_smp_affinity`, and under a
   later baseline load: per-thread ctx-switch rate (`pidstat -w`) for
   NTR/MutationStage/netty threads + `/proc/softirqs` deltas. These are the "before"
   numbers for TPC's claimed mechanisms; every phase-2/4 A/B must run in a recorded,
   unchanged IRQ regime.
3. Write ALL of it into `tasks/tpc-migration-planning/runbook.md` (new file), including the
   working SSH invocation, the isolated-clone path chosen for this task
   (`/root/repos/fork/cassandra-tpc`), and a **concrete rsync command** (gap: referenced as
   practice in the flush task, never written down), e.g.:
   `rsync -az --delete --exclude=build --exclude=.git /Users/samlightfoot/repos/fork/cassandra/ root@157.180.98.112:/root/repos/fork/cassandra-tpc/`
   (verify flags against what actually works; record the verified form).

Acceptance: runbook.md exists with every field above filled; no "TBD" entries.

### 0.2 Kernel decision → deliverable: kernel ≥ 5.19 running, or recorded n/a

Decision rule (no judgment left to executor):
- If `uname -r` ≥ 5.19 → record and done.
- If < 5.19 → upgrade via the distro's supported mechanism (Ubuntu: `apt install
  linux-generic-hwe-<ver>`; Debian: backports kernel), reboot, re-verify, record old→new in
  runbook.md. Rationale: the XFS async-buffered-write fast path landed in 5.19 (LWN 896909);
  benchmarking XFS buffered writes on an older kernel measures the io-wq slow path and
  invalidates the Phase 2 matrix.
- If already ≥ 6.x, additionally record whether `IORING_SETUP_DEFER_TASKRUN` (6.1+) and
  registered ring fds are available — Phase 1 may use them.

Acceptance: runbook.md records kernel version and the explicit statement "meets/upgraded-to
≥ 5.19"; rig rebooted cleanly; Cassandra dataset on /data intact (`df -h /data` unchanged ±1%).

### 0.3 XFS bench partition → deliverable: `/bench-xfs` mounted ✅ GATE CLEARED

**Gate CLEARED by user 2026-07-05:** "The WAF work is done and data can be purged along
with any other disk/partition setups for such, if needed." Plan still touches ONLY
`nvme0n1`; `/data` is left intact (not needed for this task, conservative default).

Pinned plan (chosen because /data holds the dataset and / is 50 GB). **Both bench
partitions MUST live on the same physical NVMe** — putting XFS on nvme0n1 while ext4
benches ran on nvme1n1 would confound the filesystem A/B with a device A/B:
1. Stop Cassandra if running; unmount `/commitlog` (`nvme0n1`).
2. Repartition `nvme0n1`: p1 = 300 GB (recreate `/commitlog`, ext4),
   p2 = 290 GB XFS bench, p3 = 290 GB ext4 bench.
3. `mkfs.ext4 -E nodiscard /dev/nvme0n1p1` and `/dev/nvme0n1p3` — nodiscard REQUIRED
   (lesson: mkfs-ext4-trim-undoes-precondition).
4. `mkfs.xfs -K /dev/nvme0n1p2` — `-K` is XFS's skip-discard equivalent; record
   `xfs_info` output in runbook.md.
5. Mount `/commitlog`, `/bench-xfs`, `/bench-ext4`; add all to `/etc/fstab`; record
   mount options (default, no special opts — record what defaults resolve to).
6. Update rig Cassandra yaml `commitlog_directory` if the device name changed.

Acceptance: `lsblk` shows all three partitions mounted; `xfs_info /bench-xfs` recorded;
`/data` untouched; fstab survives reboot (verify with one reboot); Phase 2 fs A/B uses
ONLY `/bench-xfs` vs `/bench-ext4` (same device).

### 0.4 Decision-record closure → deliverable: this spec marked FINAL

1. Re-read D1–D4 against anything discovered in 0.1–0.3; amend only with evidence.
2. Copy any amended decision verbatim into `../findings.md` under a "Phase 0 outcomes" heading.
3. Mark checkboxes in `../task_plan.md` Phase 0; append handoff summary to `../progress.md`.

Acceptance: no decision in this spec says "pending"; task_plan.md Phase 0 fully checked.

---

## 4. Exit gate (Phase 0 → Phase 1)

ALL of: runbook.md complete · kernel ≥ 5.19 verified · /bench-xfs mounted · D1–D4 final.

## 5. Risks

- **Kernel upgrade bricks remote box** — Hetzner rescue system is the recovery path; snapshot
  /etc and record GRUB default before reboot; do 0.1 fully before 0.2 so facts aren't lost.
- **Repartition typo hits nvme1n1 (dataset disk)** — the plan names `nvme0n1` explicitly;
  executor must `lsblk` immediately before every destructive command and match serials
  against runbook.md.
