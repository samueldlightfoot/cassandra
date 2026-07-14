#!/bin/bash
# contention_ab.sh — TrieMemtable per-shard writeLock contention, trunk vs routing-alloc, measured as a
# DELTA over a steady routed-load window (excludes startup/commitlog-replay + warmup transients, which
# include unrouted puts). GC-immune domain counter: trunk's shared MutationStage pool contends each shard
# lock; routing's single owner thread per shard should not. Co-located load (contention RATIO is
# co-location-invariant, like ins/op). Reader = /root/MemtableContention (JMX 7199).
set +e
OUT=/root/results_contention; mkdir -p "$OUT"; SUMM="$OUT/contention_ab.txt"; : > "$SUMM"
BASE=/root/repos/fork/cassandra-tpc-i1; CONF=/data/tpc-poc/conf
NT="CASSANDRA_CONF=$CONF $BASE/bin/nodetool"
read_ctr(){ java -cp /root MemtableContention 127.0.0.1 7199 2>/dev/null \
  | awk -F'[= ]' '/DERIVED/{for(i=1;i<=NF;i++){if($i=="contended")c=$(i+1);if($i=="uncontended")u=$(i+1)}print c,u}'; }
arm(){
  SUF=$1
  echo "===== $SUF $(date -u +%H:%M:%S) =====" | tee -a "$SUMM"
  /root/swap.sh "$SUF" >/dev/null 2>&1
  /root/prep_flip.sh >/dev/null 2>&1
  cassandra-easy-stress run KeyValue --host 127.0.0.1 --no-schema --prometheusport 0 --readrate 0.0 \
    --rate 90000 --concurrency 2000 --threads 24 --queue 2000000 --duration 180s > "$OUT/lg_${SUF}.log" 2>&1 &
  LGP=$!; sleep 45
  T0=$(read_ctr); echo "T0(+45s warmup): $T0" | tee -a "$SUMM"
  sleep 90
  T1=$(read_ctr); echo "T1(+90s window): $T1" | tee -a "$SUMM"
  echo "--- full counters at T1 ($SUF) ---" >> "$SUMM"
  java -cp /root MemtableContention 127.0.0.1 7199 2>/dev/null >> "$SUMM"
  eval $NT tpstats 2>/dev/null | awk 'NR==1||/Pool Name|Shard-|Native-Transport|MutationStage/' > "$OUT/tpstats_${SUF}.txt"
  kill "$LGP" 2>/dev/null; pkill -f easy-stress 2>/dev/null; sleep 6
  echo "$T0 $T1" | awk -v s="$SUF" '{dc=$3-$1;du=$4-$2;tot=dc+du;
    printf "%s WINDOW: dContended=%d dUncontended=%d total=%d contended/1k=%.2f contended%%=%.3f\n",
    s,dc,du,tot,(tot>0)?1000.0*dc/tot:0,(tot>0)?100.0*dc/tot:0}' | tee -a "$SUMM"
}
arm trunk
arm routing-alloc
echo "=== CONTENTION AB DONE $(date -u +%H:%M:%S) ===" | tee -a "$SUMM"
