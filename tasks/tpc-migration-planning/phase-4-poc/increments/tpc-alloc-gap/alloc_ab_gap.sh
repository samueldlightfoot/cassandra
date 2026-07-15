#!/bin/bash
# alloc_ab_gap.sh — allocation A/B, trunk vs alloc-gap (Site A Dispatcher inline + map MapListener fusion +
# Site B RetryingMutationDispatch lazy promise), ONE session (cross-run alloc drifts). CO-LOCATED load
# (alloc/op is co-location-invariant). Matches sidebyside_colocated.sh load exactly for comparability to the
# recorded trunk 39,577 / routing-alloc 43,591 baseline.
set +e
OUT=/root/results_allocgap; mkdir -p "$OUT"; SUMM="$OUT/alloc_ab_gap.txt"; : > "$SUMM"
ASPROF=/opt/async-profiler-4.4-linux-x64/bin/asprof
arm(){
  SUF=$1
  echo "===== $SUF $(date -u +%H:%M:%S) =====" | tee -a "$SUMM"
  /root/swap.sh "$SUF" >/dev/null 2>&1
  /root/prep_flip.sh >/dev/null 2>&1
  PID=$(pgrep -f 'Cassandra[D]aemon' | head -1)
  echo "  pid=$PID live_md5=$(md5sum /root/repos/fork/cassandra-tpc-i1/build/apache-cassandra-7.0-SNAPSHOT.jar | cut -d' ' -f1)" | tee -a "$SUMM"
  cassandra-easy-stress run KeyValue --host 127.0.0.1 --no-schema --prometheusport 0 --readrate 0.0 \
    --rate 90000 --concurrency 2000 --threads 24 --queue 2000000 --duration 400s > "$OUT/lg_${SUF}.log" 2>&1 &
  LGP=$!; sleep 50
  $ASPROF -d 40 -e alloc -o collapsed -f "$OUT/alloc_${SUF}.collapsed" "$PID" 2>>"$OUT/prof_${SUF}.log"
  kill "$LGP" 2>/dev/null; pkill -f easy-stress 2>/dev/null; sleep 6
  # per-leaf-type aggregation (leaf = frame after the last ';', count = last whitespace field)
  awk '{
    n=$NF; line=$0; sub(/ [0-9]+$/,"",line); leaf=line; sub(/.*;/,"",leaf); tot+=n;
    if (leaf ~ /AsyncPromise_/) ap+=n;
    else if (leaf ~ /RunnableWithExecutor_/) rwe+=n;
    else if (leaf ~ /MapListener_/) ml+=n;
    else if (leaf ~ /GenericFutureListenerList_/) gfll+=n;
    else if (leaf ~ /AbstractFuture\$\$Lambda/) afl+=n;
  } END {
    printf "  TOTAL_alloc      = %d\n", tot;
    printf "  AsyncPromise     = %d\n", ap+0;
    printf "  RunnableWithExec = %d\n", rwe+0;
    printf "  MapListener      = %d\n", ml+0;
    printf "  GenericFutureLL  = %d\n", gfll+0;
    printf "  AbstractFut\$Lmbd = %d\n", afl+0;
  }' "$OUT/alloc_${SUF}.collapsed" | tee -a "$SUMM"
  # write-error sanity from the loadgen log
  echo "  loadgen errors: $(grep -icE 'error|exception|refused|timeout' "$OUT/lg_${SUF}.log")" | tee -a "$SUMM"
}
arm trunk
arm alloc-gap
echo "=== ALLOC AB GAP DONE $(date -u +%H:%M:%S) ===" | tee -a "$SUMM"
