#!/usr/bin/env bash
# Phase 2 Level B sweep driver — runs ON THE RIG as root under nohup.
# 15 cells per fs mirroring the fio Level A shapes through the Phase 1 binding
# (spec §1.1-B). Same environment stance as run-fio-cells.sh: governor=performance,
# irqbalance stopped, bench JVM pinned to CPU $BENCH_CPU (the same core fio's
# single-job cells used), samplers on CPU $SAMPLER_CPU. Cache prep (cold drops /
# hot priming / write settles) happens INSIDE the bench (JMH @Setup), matching the
# fio driver's mechanisms. iou-wrk workers are unpinned — their placement is measured.
set -uo pipefail   # NOT -e: one bad cell must not kill the sweep

REPO=/root/repos/fork/cassandra-tpc
RES=/data/results/uring_jmh_v1
ENV=$RES/env
BENCH_CPU=2
SAMPLER_CPU=0
ANT=ant

fail() { echo "PREFLIGHT FAIL: $*" >&2; exit 1; }

# ---------- preflight ----------
[ "$(id -u)" = 0 ] || fail "must run as root"
cd "$REPO" || fail "no repo"
if pgrep -x java > /dev/null; then fail "java process running — rig must be quiet"; fi
pkill -f 'bpftrace|biolatency|biosnoop' 2>/dev/null
G=$((1024*1024*1024))
for mnt in /bench-ext4 /bench-xfs; do
  [ "$(stat -c%s $mnt/uring-bench-read.dat 2>/dev/null || echo 0)" -eq $((32*G)) ] || fail "$mnt read file wrong size"
  [ "$(stat -c%s $mnt/uring-bench-seqwrite.dat 2>/dev/null || echo 0)" -eq $((32*G)) ] || fail "$mnt seqwrite file wrong size"
  [ "$(stat -c%s $mnt/uring-bench-randwrite.dat 2>/dev/null || echo 0)" -eq $((16*G)) ] || fail "$mnt randwrite file wrong size"
done
mkdir -p "$ENV"

# fresh build once; every cell then runs with -Dno-build-test=true (JAR rebuild lesson)
echo "building: ant jar + build-test ($(date -u +%H:%M:%S))"
$ANT jar > "$ENV/ant-jar.log" 2>&1 || fail "ant jar failed (see $ENV/ant-jar.log)"
$ANT build-test > "$ENV/ant-build-test.log" 2>&1 || fail "ant build-test failed"
JAR=$(ls build/apache-cassandra-*.jar | grep -v sources | head -1)
[ "$(jar tf "$JAR" | grep -c 'io/uring/Uring')" -ge 5 ] || fail "Uring classes missing from $JAR"
ls build/test/classes/org/apache/cassandra/test/microbench/uring/UringRawWriteBench.class > /dev/null \
  || fail "UringRawWriteBench not compiled"

# ---------- pin environment (restored by trap) ----------
PREV_GOV=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)
echo performance | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null
[ "$(sort -u /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor)" = performance ] || fail "governor did not take"
PREV_IRQB=$(systemctl is-active irqbalance)
systemctl stop irqbalance
restore() {
  echo "$PREV_GOV" | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null
  [ "$PREV_IRQB" = active ] && systemctl start irqbalance
  echo "environment restored (governor=$PREV_GOV irqbalance=$PREV_IRQB)"
}
trap restore EXIT

uname -a > "$ENV/uname.txt"
java -version > "$ENV/java-version.txt" 2>&1
cat /proc/interrupts > "$ENV/interrupts-start.txt"
smartctl -a /dev/nvme0n1 > "$ENV/smart-before.txt"
echo "governor=performance irqbalance=stopped(prev=$PREV_IRQB) bench_cpu=$BENCH_CPU sampler_cpu=$SAMPLER_CPU" > "$ENV/stance.txt"

# ---------- cell table ----------
# id|bench|jmh -p params (no spaces in values; -Djmh.args splits on whitespace)
CELLS=()
for fs in ext4 xfs; do
  rf=/bench-$fs/uring-bench-read.dat
  sw=/bench-$fs/uring-bench-seqwrite.dat
  rw=/bench-$fs/uring-bench-randwrite.dat
  # A1 mirrors: 4k randread O_DIRECT cold
  CELLS+=("$fs-b1-pread-qd1-direct-cold|UringRawReadBench|-p file=$rf -p mode=pread -p qd=1 -p direct=true -p cold=true")
  CELLS+=("$fs-b1-sync-qd1-direct-cold|UringRawReadBench|-p file=$rf -p mode=sync -p qd=1 -p direct=true -p cold=true")
  CELLS+=("$fs-b1-batched-qd1-direct-cold|UringRawReadBench|-p file=$rf -p mode=batched -p qd=1 -p direct=true -p cold=true")
  CELLS+=("$fs-b1-batched-qd32-direct-cold|UringRawReadBench|-p file=$rf -p mode=batched -p qd=32 -p direct=true -p cold=true")
  CELLS+=("$fs-b1-batched-qd64-direct-cold|UringRawReadBench|-p file=$rf -p mode=batched -p qd=64 -p direct=true -p cold=true")
  # A3 mirrors: 4k randread buffered hot
  CELLS+=("$fs-b3-pread-qd1-buffered-hot|UringRawReadBench|-p file=$rf -p mode=pread -p qd=1 -p direct=false -p cold=false")
  CELLS+=("$fs-b3-sync-qd1-buffered-hot|UringRawReadBench|-p file=$rf -p mode=sync -p qd=1 -p direct=false -p cold=false")
  CELLS+=("$fs-b3-batched-qd1-buffered-hot|UringRawReadBench|-p file=$rf -p mode=batched -p qd=1 -p direct=false -p cold=false")
  CELLS+=("$fs-b3-batched-qd32-buffered-hot|UringRawReadBench|-p file=$rf -p mode=batched -p qd=32 -p direct=false -p cold=false")
  # A4 mirrors: 256k seqwrite buffered
  CELLS+=("$fs-b4-pwrite-qd1|UringRawWriteBench|-p file=$sw -p mode=pwrite -p qd=1 -p bs=262144 -p pattern=seq -p direct=false")
  CELLS+=("$fs-b4-sync-qd1|UringRawWriteBench|-p file=$sw -p mode=sync -p qd=1 -p bs=262144 -p pattern=seq -p direct=false")
  CELLS+=("$fs-b4-batched-qd32|UringRawWriteBench|-p file=$sw -p mode=batched -p qd=32 -p bs=262144 -p pattern=seq -p direct=false")
  # A5 mirrors: 4k randwrite O_DIRECT preallocated
  CELLS+=("$fs-b5-pwrite-qd1|UringRawWriteBench|-p file=$rw -p mode=pwrite -p qd=1 -p bs=4096 -p pattern=rand -p direct=true")
  CELLS+=("$fs-b5-sync-qd1|UringRawWriteBench|-p file=$rw -p mode=sync -p qd=1 -p bs=4096 -p pattern=rand -p direct=true")
  CELLS+=("$fs-b5-batched-qd32|UringRawWriteBench|-p file=$rw -p mode=batched -p qd=32 -p bs=4096 -p pattern=rand -p direct=true")
done

echo "sweep: ${#CELLS[@]} JMH cells, start $(date -u +%FT%TZ)"

for entry in "${CELLS[@]}"; do
  IFS='|' read -r id bench params <<< "$entry"
  d="$RES/${id}.d"; mkdir -p "$d"
  sync; echo 3 > /proc/sys/vm/drop_caches; sleep 2

  grep nvme /proc/interrupts > "$d/interrupts.txt"
  cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor > "$d/governor.txt"
  systemctl is-active irqbalance > "$d/irqbalance.txt" 2>&1

  taskset -c $SAMPLER_CPU iostat -xz 1 > "$d/iostat.log" 2>&1 & S1=$!
  taskset -c $SAMPLER_CPU pidstat -w 1 > "$d/pidstat.log" 2>&1 & S2=$!
  ( while :; do c=$(ps -eLo comm | grep -c iou-wrk); echo "$(date +%s) $c"; sleep 1; done ) \
      > "$d/iouwrk-count.log" 2>&1 & S3=$!
  ( while :; do echo -n "$(date +%s) "; ps -eLo psr,comm | awk '/iou-wrk/{print $1}' | sort -n | uniq -c | tr '\n' ' '; echo; sleep 1; done ) \
      > "$d/iouwrk-psr.log" 2>&1 & S4=$!
  ( while :; do echo -n "$(date +%s) "; awk '/^(Dirty|Writeback):/{printf "%s %s ", $1, $2}' /proc/meminfo; echo; sleep 1; done ) \
      > "$d/meminfo.log" 2>&1 & S5=$!

  if ! taskset -c $BENCH_CPU $ANT microbench -Dno-build-test=true \
        -Dbenchmark.name=$bench \
        -Djmh.args="-bm thrpt,sample $params" > "$d/ant.log" 2>&1; then
    echo "CELL FAIL $id (ant exit nonzero)"
  fi

  kill $S1 $S2 $S3 $S4 $S5 2>/dev/null
  wait $S1 $S2 $S3 $S4 $S5 2>/dev/null

  if [ -f build/test/output/jmh-result.json ]; then
    mv build/test/output/jmh-result.json "$RES/${id}.json"
  else
    echo "CELL FAIL $id (no jmh-result.json)"
  fi
  if grep -Eq 'Exception|<failure>|FAILED|Unable|unavailable' "$d/ant.log"; then
    echo "SUSPECT OUTPUT $id:"; grep -E 'Exception|<failure>|FAILED|Unable|unavailable' "$d/ant.log" | head -5
  fi
  echo "DONE $id $(date -u +%H:%M:%S)"
done

cat /proc/interrupts > "$ENV/interrupts-end.txt"
smartctl -a /dev/nvme0n1 > "$ENV/smart-after.txt"
echo "SWEEP COMPLETE $(date -u +%FT%TZ)"
