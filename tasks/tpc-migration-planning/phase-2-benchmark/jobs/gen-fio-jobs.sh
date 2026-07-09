#!/usr/bin/env bash
# Phase 2 Level A — emit the pinned fio cell jobfiles + manifest (spec.md §1.1).
# Run locally; generated/ is rsync'd to the rig with the repo. 16 cells per fs:
# the §1.1 rows sum to 15; a5-psync-nj1 added so every Level B sync-write cell has
# a 1-thread native twin (flagged in progress.md).
#
# Pinning stance: single-job cells (the "one shard thread" arms) run on CPU $PIN_CPU;
# numjobs=50 cells float across all cores (today's architecture). Samplers live on CPU 0.
set -euo pipefail
cd "$(dirname "$0")"
OUT=generated
rm -rf "$OUT"; mkdir -p "$OUT"
MANIFEST="$OUT/manifest.tsv"
: > "$MANIFEST"

READ_RUNTIME=120
PIN_CPU=2

emit() {
  local fs=$1 cell=$2 kind=$3 cache=$4 body=$5
  local id="${fs}-${cell}" jf="$OUT/${fs}-${cell}.fio"
  { echo "# ${id} (generated — do not hand-edit)"; echo "$body"; } > "$jf"
  printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$fs" "$(basename "$jf")" "$kind" "$cache" >> "$MANIFEST"
}

for fs in ext4 xfs; do
  mnt=/bench-$fs
  rf=$mnt/uring-bench-read.dat        # 32g pseudo-random, shared with Level B
  sw=$mnt/uring-bench-seqwrite.dat    # 32g, overwritten in place each iteration
  rw5=$mnt/uring-bench-randwrite.dat  # 16g preallocated

  read_common="rw=randread
bs=4k
filename=$rf
thread=1
group_reporting=1
norandommap=1
randrepeat=0
time_based=1
runtime=${READ_RUNTIME}
ramp_time=5"

  # A1: 4k randread, O_DIRECT, cold
  for qd in 1 8 32 64; do
    emit $fs "a1-uring-qd$qd" read cold "[global]
ioengine=io_uring
direct=1
cpus_allowed=$PIN_CPU
$read_common
[job]
iodepth=$qd"
  done
  emit $fs "a1-psync-nj1" read cold "[global]
ioengine=psync
direct=1
cpus_allowed=$PIN_CPU
$read_common
[job]
iodepth=1"
  emit $fs "a1-psync-nj50" read cold "[global]
ioengine=psync
direct=1
$read_common
[job]
iodepth=1
numjobs=50"

  # A2: 4k randread, buffered, cold
  emit $fs "a2-uring-qd32" read cold "[global]
ioengine=io_uring
direct=0
cpus_allowed=$PIN_CPU
$read_common
[job]
iodepth=32"
  emit $fs "a2-psync-nj50" read cold "[global]
ioengine=psync
direct=0
$read_common
[job]
iodepth=1
numjobs=50"

  # A3: 4k randread, buffered, hot (driver primes at iter 1; invalidate=0 keeps cache)
  for qd in 1 32; do
    emit $fs "a3-uring-qd$qd" read hot "[global]
ioengine=io_uring
direct=0
invalidate=0
cpus_allowed=$PIN_CPU
$read_common
[job]
iodepth=$qd"
  done
  emit $fs "a3-psync-nj1" read hot "[global]
ioengine=psync
direct=0
invalidate=0
cpus_allowed=$PIN_CPU
$read_common
[job]
iodepth=1"

  # A4: 256k seq write, buffered — the 5.19 XFS fast-path / io-wq punt probe.
  # Size-bound (spec §1.2 writes) + end_fsync so throughput reflects storage, not cache.
  a4_common="rw=write
bs=256k
direct=0
filename=$sw
size=32g
end_fsync=1
thread=1
group_reporting=1
cpus_allowed=$PIN_CPU"
  emit $fs "a4-uring-qd32" write none "[global]
ioengine=io_uring
$a4_common
[job]
iodepth=32"
  emit $fs "a4-psync-nj1" write none "[global]
ioengine=psync
$a4_common
[job]
iodepth=1"

  # A5: 4k randwrite, O_DIRECT, preallocated file; ~16g total per iteration both engines
  a5_common="rw=randwrite
bs=4k
direct=1
filename=$rw5
size=16g
thread=1
group_reporting=1
norandommap=1
randrepeat=0"
  emit $fs "a5-uring-qd32" write none "[global]
ioengine=io_uring
cpus_allowed=$PIN_CPU
$a5_common
[job]
iodepth=32
io_size=16g"
  emit $fs "a5-psync-nj1" write none "[global]
ioengine=psync
cpus_allowed=$PIN_CPU
$a5_common
[job]
iodepth=1
io_size=16g"
  emit $fs "a5-psync-nj50" write none "[global]
ioengine=psync
$a5_common
[job]
iodepth=1
numjobs=50
io_size=328m"
done

echo "$(wc -l < "$MANIFEST") cells emitted into $OUT/"
