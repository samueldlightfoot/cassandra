#!/bin/bash
# tail_ab2.sh — clean OFF-BOX loaded-tail A/B, trunk vs alloc-gap, interleaved. Box is NOT CPU-bound
# (durable_writes=false → memtable-only writes, ~5-10% CPU at 144k/s), so this is a moderate-load off-box
# tail comparison (the thing Tier 0 couldn't do co-located), testing the Tier-0 co-located p99 hint cleanly.
# Robust/decoupled: rig-side mpstat time-series to a file per arm; loadgen --hdr + final summary for client tail.
# Runs ON THE RIG, drives loadgen 62.238.35.142. Usage: RATE=100000 ROUNDS=2 bash tail_ab2.sh
set +e
LG=62.238.35.142
RATE=${RATE:-100000}; ROUNDS=${ROUNDS:-2}; DUR=${DUR:-90}; CONC=${CONC:-3000}; THREADS=${THREADS:-32}
OUT=/root/results_tier1; mkdir -p "$OUT"; SUMM="$OUT/tail_ab2.txt"; : > "$SUMM"
NT="CASSANDRA_CONF=/data/tpc-poc/conf /root/repos/fork/cassandra-tpc-i1/bin/nodetool"
SSHLG="ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o ServerAliveInterval=20 root@$LG"

runarm(){
  SUF=$1; RND=$2; PFX="t_${SUF}_r${RND}"
  echo "===== $SUF round $RND rate=$RATE $(date -u +%H:%M:%S) =====" | tee -a "$SUMM"
  /root/swap.sh "$SUF" >/dev/null 2>&1; /root/prep_flip.sh >/dev/null 2>&1
  pkill -x mpstat 2>/dev/null
  setsid bash -c "mpstat 2 $((DUR/2+40)) > $OUT/mp_$PFX.log 2>&1" </dev/null &
  C0=$(eval $NT tablestats cassandra_easy_stress.keyvalue 2>/dev/null | grep 'Local write count' | grep -oE '[0-9]+')
  # launch loadgen, wait for it to finish (DUR + margin)
  $SSHLG "setsid bash -c 'taskset -c 0-15 cassandra-easy-stress run KeyValue --host 157.180.98.112 --no-schema --prometheusport 0 --readrate 0.0 --rate $RATE --concurrency $CONC --threads $THREADS --queue 2000000 --duration ${DUR}s --hdr /root/$PFX > /root/lg_$PFX.log 2>&1' </dev/null; echo lg_launched"
  sleep $((DUR+15))
  C1=$(eval $NT tablestats cassandra_easy_stress.keyvalue 2>/dev/null | grep 'Local write count' | grep -oE '[0-9]+')
  pkill -x mpstat 2>/dev/null
  DELIV=$(( (C1-C0)/(DUR+15) ))
  BUSY=$(awk '/all/ && $NF!="%idle"{b=100-$NF; s+=b; n++; if(b>mx)mx=b} END{printf "avg=%.1f max=%.1f", s/n, mx}' "$OUT/mp_$PFX.log")
  # client CO-corrected tail: the per-interval p99 (2nd col: count p99 req/s) at steady state — last 6 rows
  LGSUM=$($SSHLG "grep -E '^[[:space:]]*[0-9]{5,}' /root/lg_$PFX.log 2>/dev/null | tail -6; echo errcol:; grep -E '^[[:space:]]*[0-9]{5,}' /root/lg_$PFX.log 2>/dev/null | tail -1 | awk '{print \$NF}'")
  { echo "  server delivered=$DELIV w/s (offered=$RATE)  cass_busy: $BUSY %"; echo "  client rows [count p99ms req/s | ...]:"; echo "$LGSUM" | sed 's/^/    /'; } | tee -a "$SUMM"
  $SSHLG "for p in \$(pgrep java); do kill -9 \$p; done" >/dev/null 2>&1; sleep 4
}
for r in $(seq 1 $ROUNDS); do runarm trunk "$r"; runarm alloc-gap "$r"; done
echo "=== TAIL AB2 DONE $(date -u +%H:%M:%S) ===" | tee -a "$SUMM"
