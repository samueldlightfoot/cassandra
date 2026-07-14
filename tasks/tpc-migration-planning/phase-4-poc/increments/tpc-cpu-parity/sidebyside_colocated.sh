#!/bin/bash
# sidebyside_colocated.sh — trunk vs routing-alloc, ins/op (6x40s) + allocation (asprof 40s) in ONE
# co-located load window per arm (both metrics are co-location-invariant). One invocation so arm deltas
# are drift-comparable. Produces the current-jar side-by-side the prior handoff lacked.
set +e
OUT=/root/results_sxs; mkdir -p "$OUT"; SUMM="$OUT/sxs_summary.txt"; : > "$SUMM"
ASPROF=/opt/async-profiler-4.4-linux-x64/bin/asprof
arm(){
  SUF=$1
  echo "===== $SUF $(date -u +%H:%M:%S) =====" | tee -a "$SUMM"
  /root/swap.sh "$SUF" >/dev/null 2>&1
  /root/prep_flip.sh >/dev/null 2>&1
  PID=$(pgrep -f 'Cassandra[D]aemon' | head -1)
  cassandra-easy-stress run KeyValue --host 127.0.0.1 --no-schema --prometheusport 0 --readrate 0.0 \
    --rate 90000 --concurrency 2000 --threads 24 --queue 2000000 --duration 400s > "$OUT/lg_${SUF}.log" 2>&1 &
  LGP=$!; sleep 50
  for i in $(seq 1 6); do /root/ipo.sh "sxs_${SUF}_$i" 40 | tee -a "$SUMM"; done
  $ASPROF -d 40 -e alloc -o collapsed -f "$OUT/alloc_${SUF}.collapsed" "$PID" 2>>"$OUT/prof_${SUF}.log"
  awk -F";" -v s="$SUF" '{split($NF,a," "); c[a[1]]+=a[2]; t+=a[2]}
    END{printf "%s TOTAL_alloc=%d\n", s, t;
        for(k in c) if(k ~ /CallbackBiConsumer|AsyncPromise_|ListenerList|RunnableWithExecutor/)
          printf "  %8d  %s\n", c[k], k}' "$OUT/alloc_${SUF}.collapsed" | sort -k1 -rn | tee -a "$SUMM"
  kill "$LGP" 2>/dev/null; pkill -f easy-stress 2>/dev/null; sleep 6
}
arm trunk
arm routing-alloc
echo "===== INS/OP STATS =====" | tee -a "$SUMM"
awk -F'ins/op=' '/IPO sxs_/ && /ins\/op=/{tag=$1; sub(/.*IPO sxs_/,"",tag); sub(/_[0-9]+:.*/,"",tag);
  v=$2+0; s[tag]+=v; ss[tag]+=v*v; n[tag]++}
END{for(k in n){m=s[k]/n[k]; sd=sqrt(ss[k]/n[k]-m*m); printf "%s: n=%d mean=%.0f sd=%.0f (%.1f%%)\n",k,n[k],m,sd,100*sd/m; M[k]=m}
  d=M["routing-alloc"]-M["trunk"]; if(M["trunk"]>0) printf "GAP routing-alloc - trunk = %.0f (+%.2f%%)\n", d, 100*d/M["trunk"]}' "$SUMM" | tee -a "$SUMM"
echo "=== SXS COLOCATED DONE $(date -u +%H:%M:%S) ===" | tee -a "$SUMM"
