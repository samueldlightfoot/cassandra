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
