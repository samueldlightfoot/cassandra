#!/usr/bin/env bash
# Phase 2 syscalls-per-op KPI, v2 (whole-run method — replaces the attach method,
# whose window had no op count and whose sync run exposed the syncOp short-success
# bug, now fixed). Runs each mode with JMH forks=0 (bench in the runner JVM) under
# `strace -c -f` for the WHOLE run, then computes enters/op from the JMH score:
# ops ≈ score x (warmup 3 s + measured 30 s). pread mode counts pread64 instead.
set -uo pipefail
REPO=/root/repos/fork/cassandra-tpc
RES=/data/results/uring_jmh_v1/strace2
BENCH_CPU=2
mkdir -p "$RES"
cd "$REPO" || exit 1

run_window() {
  local id=$1 params=$2
  echo "strace2 window: $id"
  sync; echo 3 > /proc/sys/vm/drop_caches; sleep 2
  strace -c -f -o "$RES/$id.strace" \
    taskset -c $BENCH_CPU ant microbench -Dno-build-test=true \
      -Dbenchmark.name=UringRawReadBench \
      -Djmh.args="-bm thrpt -f 0 -wi 1 -w 3 -i 1 -r 30 $params" \
      > "$RES/$id-ant.log" 2>&1
  grep -E "^UringRawReadBench.read|thrpt" "$RES/$id-ant.log" | tail -3 > "$RES/$id-score.txt"
  echo "WINDOW DONE $id"
}

RF=/bench-ext4/uring-bench-read.dat
run_window pread-qd1-direct    "-p file=$RF -p mode=pread -p qd=1 -p direct=true -p cold=true"
run_window sync-qd1-direct     "-p file=$RF -p mode=sync -p qd=1 -p direct=true -p cold=true"
run_window batched-qd64-direct "-p file=$RF -p mode=batched -p qd=64 -p direct=true -p cold=true"
echo "STRACE2 COMPLETE"
