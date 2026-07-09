#!/usr/bin/env bash
# Phase 2 — create the shared bench files on the rig (spec §1.1: the SAME 32g
# pseudo-random file serves Level A (fio) and Level B (JMH) so cold reads hit
# identical layouts). Idempotent: skips files that already exist at full size.
set -euo pipefail

mk() {
  local path=$1 size=$2 bytes=$3
  if [ -f "$path" ] && [ "$(stat -c%s "$path")" -eq "$bytes" ]; then
    echo "exists: $path"
    return
  fi
  rm -f "$path"
  fio --name="mk-$(basename "$path")" --filename="$path" --rw=write --bs=1M \
      --size="$size" --refill_buffers=1 --end_fsync=1 --thread=1 \
      --output-format=terse > /dev/null
  echo "created: $path ($(stat -c%s "$path") bytes)"
}

G=$((1024*1024*1024))
for mnt in /bench-ext4 /bench-xfs; do
  [ -d "$mnt" ] || { echo "PREFLIGHT FAIL: $mnt missing" >&2; exit 1; }
  mk "$mnt/uring-bench-read.dat"      32g $((32*G))
  mk "$mnt/uring-bench-seqwrite.dat"  32g $((32*G))
  mk "$mnt/uring-bench-randwrite.dat" 16g $((16*G))
done
sync
df -h /bench-ext4 /bench-xfs
