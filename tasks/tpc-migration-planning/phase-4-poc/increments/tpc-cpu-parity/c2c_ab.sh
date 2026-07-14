#!/bin/bash
# c2c_ab.sh [rate] — perf c2c HITM, trunk vs routing-alloc, swapping+prepping EACH arm (c2c.sh itself
# does not swap; a prior orchestrator forgot to, so both runs hit one jar). Interleaved 2 rounds because
# c2c HITM is run-to-run noisy on this single-L3 box. Off-box loadgen, ldlat=30.
set +e
LG=62.238.35.142; RIGIP=157.180.98.112
R=${1:-120000}; REC=${REC:-20}
OUT=/root/results_c2c; mkdir -p "$OUT"; SUMM="$OUT/c2c_ab.txt"; : > "$SUMM"
SSH="ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 root@$LG"
one(){
  SUF=$1; RND=$2
  /root/swap.sh "$SUF" >/dev/null 2>&1
  /root/prep_flip.sh >/dev/null 2>&1
  POOLS=$(grep -o 'pools=[A-Z]*' /root/results_flip/prep_state.txt 2>/dev/null | head -1)
  CPID=$(pgrep -f 'Cassandra[D]aemon' | head -1)
  $SSH "cassandra-easy-stress run KeyValue --host $RIGIP --no-schema --prometheusport 0 --readrate 0.0 \
    --rate $R --concurrency 3000 --threads 24 --queue 2000000 --duration 120s" > "$OUT/lg_ab_${SUF}_${RND}.log" 2>&1 &
  LGP=$!; sleep 35
  perf c2c record -a -u --ldlat=30 -o "$OUT/c2c_ab_${SUF}_${RND}.data" -- sleep "$REC" 2>>"$OUT/c2c_ab_${SUF}_${RND}.reclog"
  $SSH "pkill -f easy-stress" 2>/dev/null; kill "$LGP" 2>/dev/null; sleep 4
  perf c2c report -i "$OUT/c2c_ab_${SUF}_${RND}.data" --stdio -c pid,iaddr 2>/dev/null > "$OUT/c2c_ab_${SUF}_${RND}.report"
  RECN=$(grep -i "Total records"            "$OUT/c2c_ab_${SUF}_${RND}.report" | head -1 | awk '{print $NF}')
  HITM=$(grep -i "Load Local HITM"          "$OUT/c2c_ab_${SUF}_${RND}.report" | head -1 | awk '{print $NF}')
  SCL=$(grep -i "Total Shared Cache Lines"  "$OUT/c2c_ab_${SUF}_${RND}.report" | head -1 | awk '{print $NF}')
  printf "%-14s round%s %-9s records=%s LoadLocalHITM=%s sharedLines=%s\n" "$SUF" "$RND" "$POOLS" "$RECN" "$HITM" "$SCL" | tee -a "$SUMM"
}
for RND in 1 2; do one trunk $RND; one routing-alloc $RND; done
echo "=== C2C AB DONE $(date -u +%H:%M:%S) ===" | tee -a "$SUMM"
