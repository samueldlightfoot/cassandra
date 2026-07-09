# Runbook — tpc-migration-planning rig facts

Captured 2026-07-05 (sub-phase 0.1). Update whenever a fact is re-verified or changed.

## Access
- Rig: `root@157.180.98.112` (Hetzner; replaced decommissioned 65.108.227.158)
- Working SSH from scripts: `sshpass -p 'uum5BURBX7q_Nc' ssh -o StrictHostKeyChecking=accept-new root@157.180.98.112 '<cmds>'`
  (interactive alias `box` does NOT work from scripts)
- rsync (verified form TBD on first use):
  `rsync -az --delete --exclude=build --exclude=.git /Users/samlightfoot/repos/fork/cassandra/ root@157.180.98.112:/root/repos/fork/cassandra-tpc/`
- Isolated clone path for this task: `/root/repos/fork/cassandra-tpc` (do NOT touch
  `/root/repos/fork/cassandra` — that's the fdp-poc in-place install)

## Hardware / OS
- Ubuntu 22.04.5 LTS (Jammy); kernel at capture: **5.15.0-168-generic**
  → BELOW 5.19 floor → sub-phase 0.2 upgrade to HWE kernel executed (see below)
- x86_64, `nproc` = 12, RAM 62 GiB
- 2× Samsung **MZQL2960HCJR-00A07 (PM9A3 960GB, datacenter NVMe)**, NOT RAID
- Readahead 256 KB both disks; GRUB_DEFAULT=0
- Java on rig: OpenJDK **17.0.19** (Ubuntu)
- `ulimit -l` = 8211816 KB (~7.8 GiB memlock — ample for registered buffers)

## io_uring
- `io_uring_setup` present in kallsyms; `kernel.io_uring_disabled = 0` (enabled)

## Disk layout (BEFORE 0.3 repartition)
- `nvme0n1` 894.3G — whole-disk ext4, mounted `/commitlog` (**66 MB used** at capture;
  WAF work closed by user 2026-07-05, purge authorized)
- `nvme1n1` 894.3G — p1 1G `/boot` ext4 · p2 50G `/` ext4 (30% used) ·
  p3 843.3G `/data` ext4 (304G used — WAF dataset, left intact, purge authorized if needed)
- Mount opts: `/commitlog` and `/data` are `rw,noatime,stripe=32`

## Disk layout (AFTER 0.3 — target)
- `nvme0n1p1` 300G ext4 (`-E nodiscard`) → `/commitlog`
- `nvme0n1p2` 290G XFS (`mkfs.xfs -K`) → `/bench-xfs`
- `nvme0n1p3` ~290G ext4 (`-E nodiscard`) → `/bench-ext4`
- fs A/B benches use ONLY /bench-xfs vs /bench-ext4 (same physical device)

## Process facts
- Cassandra NOT running at capture; no java processes
- Rig cassandra.yaml uses mount point `/commitlog` → unchanged by repartition
  (device becomes p1; mount point identical)

## fstab / packages (captured 2026-07-05, pre-repartition)
- fstab is UUID-based; /commitlog entry: `UUID=edf1f179-127f-406d-8524-28a080dc1cc9 /commitlog ext4 defaults,noatime 0 2`
- ⚠ fstab COMMENTS are stale/mislabeled: they say `# /dev/nvme0n1p1..p3` for /boot,/,/data but
  those live on **nvme1n1** (device enumeration swapped since install). ALWAYS trust
  lsblk-by-UUID, never fstab comments, before destructive ops.
- xfsprogs 5.13.0-1ubuntu2.1 installed
- HWE kernel candidate: linux-generic-hwe-22.04 = **6.8.0-124.124~22.04.1** (not installed at capture)

## Phase 1 execution facts (2026-07-08)
- UAPI header used for constant verification: `/usr/src/linux-headers-6.8.0-124-generic/include/uapi/linux/io_uring.h`
  (LINUX_VERSION 6.8; `/usr/include/linux/io_uring.h` from linux-libc-dev also present but the
  running-kernel header is canonical). gcc present; probe binary left at /root/uring_probe.
- ALL spec §1.2/1.3 constants verified — zero deviations (FSYNC=3, READ=22, WRITE=23,
  DATASYNC=1, MAP_POPULATE=0x8000; params 120B features@20 sq_off@40 cq_off@80; SQE 64B, CQE 16B).
- Live probe on rig: features=0x3fff, setup tier=SINGLE_ISSUER|DEFER_TASKRUN (top tier).
- `sun.misc.Unsafe.putOrderedInt/getIntVolatile` present on rig JDK 17.0.19 (javap-verified).
- ulimit -l = 8212268 KB (~7.8 GiB) re-confirmed for registered buffers.
- IRQ environment (phase-0 0.1 step 2b): irqbalance ACTIVE; 26 nvme IRQ lines in
  /proc/interrupts; kernel watchdog=1; cpufreq governor=powersave. Phase 2 preflight must
  pin the steering stance (and consider performance governor) before A/B cells.
- Build/test loop: rsync (form in Access above, verified working) → `ant jar` (12 s warm)
  → `ant test -Dtest.name="Uring*"` (comma-separated test.name does NOT work; use glob).
- Bench: `ant microbench -Dbenchmark.name=UringRawReadBench -Djmh.args="-p qd=8 ..."`
  works; results land in build/test/output/jmh-result.json.
- QD proof (recorded in progress.md session 6): sync QD1 8,234 IOPS vs batched QD64
  225,694 IOPS = 27.41×, cold 4 KiB reads, 8 GiB file on / (nvme1n1p2, ext4).

## Phase 2 execution facts (2026-07-08)
- fio: **3.28** (apt, preinstalled); io_uring + psync engines confirmed via `fio --enghelp`.
- Reverse rsync (pull results, expected-changes §4.2):
  `rsync -az -e "sshpass -p 'uum5BURBX7q_Nc' ssh -o StrictHostKeyChecking=accept-new" root@157.180.98.112:/data/results/uring_fio_v1/ tasks/tpc-migration-planning/phase-2-benchmark/results/uring_fio_v1/`
  (same form for uring_jmh_v1)
- Live bench mount options (expected-changes §4.3): /bench-ext4 `rw,noatime,stripe=32`;
  /bench-xfs `rw,noatime,attr2,inode64,logbufs=8,logbsize=32k,sunit=256,swidth=256,noquota`.
- Readahead /dev/nvme0n1 = 512 sectors (256 KB). SMART baseline pre-sweep: 38°C, 4% used,
  44.8 TB written, spare 100%.
- CPU split (expected-changes §4.4): bench/single-job fio on CPU 2, samplers on CPU 0,
  numjobs=50 fio cells unpinned (today's-architecture arm). Steering stance: governor
  performance + irqbalance STOPPED during sweeps, both restored by driver trap; per-cell
  /proc/interrupts + governor + irqbalance snapshots in each cell's .d/ dir.
- Bench files (make-bench-file.sh, idempotent): <mnt>/uring-bench-read.dat 32g pseudo-random
  (shared by fio and JMH), uring-bench-seqwrite.dat 32g (overwrite target),
  uring-bench-randwrite.dat 16g (preallocated). On BOTH /bench-ext4 and /bench-xfs.
- Results dirs on rig: /data/results/uring_fio_v1/ and /data/results/uring_jmh_v1/
  (per-cell JSON + <cell>.d/ sampler dirs + env/ snapshot + sweep.log).
- A stray idle Gradle daemon (easy-cass-stress) was running on the rig — kill before
  sweeps; drivers preflight-fail on any java process.
- **IOMMU tax (profiled 2026-07-09):** ~17% of the O_DIRECT-read kernel CPU on this rig
  is Intel IOMMU per-op DMA mapping (xas_find/clflush/iommu_map in collapsed profile).
  `intel_iommu=pt` (or off) on the kernel cmdline is an untested lever that would lower
  the per-op floor for BOTH fio and the binding — consider before any Phase 4 A/B rig.
- strace method: attach (`-p`) windows perturb timing and gave a garbage sync window
  (and exposed the syncOp signal bug — see phase-1 findings-execution). Use
  strace-window2.sh (whole-run `strace -c -f` around a `-f 0` JMH run; ops from score).
- async-profiler: `ant microbench-with-profiler` self-installs 2.9 into
  build/async-profiler (rig has internet); `-Dprofiler.opts="event=cpu;output=collapsed"`
  writes collapsed-cpu.csv under build/test/profiler/<benchmark-params-dir>/.

## Phase 0 execution results (2026-07-05, post-reboot)
- Kernel: **6.8.0-124-generic** (HWE; upgraded from 5.15.0-168) — meets ≥5.19 floor AND ≥6.1
  → Phase 1 top flag tier available (SINGLE_ISSUER|DEFER_TASKRUN); registered ring fds available.
- Reboot clean (back in ~1.5 min); fstab persistence PROVEN (all three mounts auto-mounted).
- Partitions live: nvme0n1p1 300G ext4 /commitlog · p2 290G XFS /bench-xfs (sectsz=4096,
  agcount=16) · p3 304G ext4 /bench-ext4. UUIDs in fstab (see /etc/fstab tail).
- /data intact post-reboot (282G used vs 304G pre-reboot — ~22G freed, consistent with
  deleted-but-open files released by reboot; dataset present, /dev/nvme1n1p3 unchanged).
- /etc backup: /root/etc-backup-20260705.tgz (938 KB).
- kernel.io_uring_disabled = 0 on 6.8 (still enabled).
