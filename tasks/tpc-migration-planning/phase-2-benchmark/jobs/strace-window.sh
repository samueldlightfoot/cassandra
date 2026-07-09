#!/usr/bin/env bash
# Phase 2 §1.2 — syscalls-per-op proof, run AFTER the measured sweeps (never during).
# For each JMH mode, launch one long un-measured cell, attach `strace -c -f` to the
# forked JMH JVM for a 20 s window, and save the syscall summary. Batching proof:
# batched-qd64 must show io_uring_enter ≪ completed ops (poll-reap path); pread mode
# shows ~1 pread64/op; sync mode ~1 enter/op (KPI from phase-1 expected-changes §6).
set -uo pipefail
REPO=/root/repos/fork/cassandra-tpc
RES=/data/results/uring_jmh_v1/strace
BENCH_CPU=2
mkdir -p "$RES"
cd "$REPO" || exit 1
command -v strace > /dev/null || { echo "PREFLIGHT FAIL: no strace"; exit 1; }

run_window() {
  local id=$1 params=$2
  echo "strace window: $id"
  sync; echo 3 > /proc/sys/vm/drop_caches; sleep 2
  taskset -c $BENCH_CPU ant microbench -Dno-build-test=true \
    -Dbenchmark.name=UringRawReadBench \
    -Djmh.args="-bm thrpt -wi 1 -w 5 -i 1 -r 90 $params" > "$RES/$id-ant.log" 2>&1 &
  local ant_pid=$!
  local fork_pid=""
  for _ in $(seq 1 120); do
    fork_pid=$(pgrep -f ForkedMain | head -1)
    [ -n "$fork_pid" ] && break
    sleep 1
  done
  if [ -z "$fork_pid" ]; then
    echo "WINDOW FAIL $id: no ForkedMain appeared"
    kill "$ant_pid" 2>/dev/null; wait "$ant_pid" 2>/dev/null
    return 1
  fi
  sleep 15   # be inside the measured 90 s iteration, past setup/warmup
  timeout 20 strace -c -f -p "$fork_pid" > "$RES/$id.strace" 2>&1
  # the benchmark score for ops/s context over the same run
  wait "$ant_pid" 2>/dev/null
  grep -A3 "Benchmark.*thrpt" "$RES/$id-ant.log" | head -8 > "$RES/$id-score.txt"
  echo "WINDOW DONE $id"
}

RF=/bench-ext4/uring-bench-read.dat
run_window pread-qd1-direct   "-p file=$RF -p mode=pread -p qd=1 -p direct=true -p cold=true"
run_window sync-qd1-direct    "-p file=$RF -p mode=sync -p qd=1 -p direct=true -p cold=true"
run_window batched-qd64-direct "-p file=$RF -p mode=batched -p qd=64 -p direct=true -p cold=true"
echo "STRACE WINDOWS COMPLETE"
