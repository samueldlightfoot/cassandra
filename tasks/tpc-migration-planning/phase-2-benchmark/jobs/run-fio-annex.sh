#!/usr/bin/env bash
# Phase 2 Level A ANNEX — supplementary cells OUTSIDE the pinned §1.1 matrix,
# run after the pinned sweep. Purpose: classify the G1 shortfall. The pinned
# a1-uring-qd32/64 cells saturate their single core (usr+sys=100%, ~3.8us CPU/op),
# so test whether per-op CPU is recoverable with registered/fixed buffers
# (fixedbufs=1 skips per-op page pinning; registerfiles=1 skips per-op fd lookup).
# Our Phase 1 binding supports both (prepareReadFixed). Same environment stance
# as run-fio-cells.sh. Results land next to the pinned cells with an -annex- id.
set -uo pipefail

RES=/data/results/uring_fio_v1
fail() { echo "PREFLIGHT FAIL: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail "must run as root"
if pgrep -x fio > /dev/null; then fail "fio still running — pinned sweep not finished"; fi
if pgrep -x java > /dev/null; then fail "java running"; fi

PREV_GOV=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)
echo performance | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null
PREV_IRQB=$(systemctl is-active irqbalance)
systemctl stop irqbalance
restore() {
  echo "$PREV_GOV" | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null
  [ "$PREV_IRQB" = active ] && systemctl start irqbalance
  echo "environment restored"
}
trap restore EXIT

for fs in ext4 xfs; do
  for iter in 1 2 3; do
    id="${fs}-annex-a1-uring-qd64-fixed"
    out="$RES/${id}-iter${iter}"
    d="${out}.d"; mkdir -p "$d"
    sync; echo 3 > /proc/sys/vm/drop_caches; sleep 2
    grep nvme /proc/interrupts > "$d/interrupts.txt"
    taskset -c 0 iostat -xz 1 > "$d/iostat.log" 2>&1 & S1=$!
    fio --output-format=json --output="${out}.json" \
        --name=job --ioengine=io_uring --fixedbufs=1 --registerfiles=1 \
        --rw=randread --bs=4k --direct=1 --iodepth=64 --thread=1 \
        --filename=/bench-$fs/uring-bench-read.dat --cpus_allowed=2 \
        --norandommap=1 --randrepeat=0 --time_based=1 --runtime=120 --ramp_time=5 \
        --group_reporting=1 > "$d/fio-stdout.log" 2>&1 \
      || echo "CELL FAIL $id iter$iter"
    kill $S1 2>/dev/null; wait $S1 2>/dev/null
    [ -s "${out}.json" ] || echo "CELL FAIL $id iter$iter (no json)"
    echo "DONE $id iter$iter $(date -u +%H:%M:%S)"
  done
done
echo "ANNEX COMPLETE $(date -u +%FT%TZ)"
