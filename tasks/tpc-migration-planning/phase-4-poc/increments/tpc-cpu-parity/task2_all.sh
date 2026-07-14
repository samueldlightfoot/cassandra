#!/bin/bash
# task2_all.sh — full routing-alloc vs trunk side-by-side, one detached run:
#   1. ins/op + allocation (co-located)   sidebyside_colocated.sh
#   2. rate-ladder p50/p90/p99 (off-box)   sweep.sh per arm
#   3. perf c2c HITM (off-box)             c2c.sh per arm
# Sequential (shared rig). Leaves per-metric summaries; final consolidation done off-rig.
set +e
M=/root/results_sxs/task2.marker; : > "$M"
echo "$(date -u +%H:%M:%S) TASK2 START" | tee -a "$M"
bash /root/sidebyside_colocated.sh
echo "$(date -u +%H:%M:%S) colocated done" | tee -a "$M"
DUR=90 CONC=3000 bash /root/sweep.sh trunk 80000 120000 160000 200000
DUR=90 CONC=3000 bash /root/sweep.sh routing-alloc 80000 120000 160000 200000
echo "$(date -u +%H:%M:%S) sweep done" | tee -a "$M"
REC=20 bash /root/c2c.sh trunk 120000
REC=20 bash /root/c2c.sh routing-alloc 120000
echo "$(date -u +%H:%M:%S) c2c done" | tee -a "$M"
echo "$(date -u +%H:%M:%S) TASK2 DONE" | tee -a "$M"
