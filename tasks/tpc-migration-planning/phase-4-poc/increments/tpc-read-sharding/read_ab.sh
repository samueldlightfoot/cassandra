#!/bin/bash
# read_ab.sh — off-box CPU-bound READ tail A/B: cql_read_routing ON vs OFF, SAME read-sharding jar,
# interleaved. Dataset pre-populated + flushed (sstable + page-cache resident). Toggles ONLY the read flag
# per arm via jvm-server.options + restart (NO truncate — the sstables persist a restart). readrate 1.0.
#
# Runs ON THE RIG (157.180.98.112), drives the loadgen. scp to /root, then:
#   RATE=<~0.85x knee> ROUNDS=5 bash /root/read_ab.sh
# Prereq on resume: loadgen re-provisioned + seeded; rig pubkey in loadgen authorized_keys; rig known_hosts
# entry for the loadgen IP cleared (reused-IP host-key trap).
set +e
LG=62.238.35.142
RATE=${RATE:-95000}; ROUNDS=${ROUNDS:-5}; DUR=${DUR:-90}; WARM=${WARM:-30}; CONC=${CONC:-3000}; THREADS=${THREADS:-32}
OUT=/root/results_readab; mkdir -p "$OUT"; SUMM="$OUT/read_ab.txt"; : > "$SUMM"
CONF=/data/tpc-poc/conf; BASE=/root/repos/fork/cassandra-tpc-i1
NT="CASSANDRA_CONF=$CONF $BASE/bin/nodetool"
SSHLG="ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o ServerAliveInterval=20 root@$LG"

restart_with(){  # $1 = true|false  (cql_read_routing)
  sed -i "s/-Dcassandra.tpc.cql_read_routing=.*/-Dcassandra.tpc.cql_read_routing=$1/" "$CONF/jvm-server.options"
  eval $NT drain >/dev/null 2>&1
  pkill -9 -f 'Cassandra[D]aemon' 2>/dev/null
  for w in $(seq 1 30); do pgrep -f 'Cassandra[D]aemon' >/dev/null || break; sleep 1; done
  export CASSANDRA_CONF=$CONF
  setsid "$BASE/bin/cassandra" -R >/tmp/cass_launch.log 2>&1 </dev/null &
  for w in $(seq 1 90); do eval $NT info 2>/dev/null | grep -q 'Native Transport active: true' && break; sleep 2; done
  sleep 5
  eval $NT disableautocompaction cassandra_easy_stress keyvalue >/dev/null 2>&1
  PID=$(pgrep -f 'Cassandra[D]aemon' | head -1)
  ACT=$(tr '\0' '\n' < /proc/$PID/cmdline | grep -oE 'cql_read_routing=[a-z]+')
  echo "  [restart] $ACT pid=$PID" | tee -a "$SUMM"
}
readcount(){ eval $NT tablestats cassandra_easy_stress.keyvalue 2>/dev/null | grep 'Local read count' | grep -oE '[0-9]+' | head -1; }
routedcount(){ eval $NT sjk mxdump -q "org.apache.cassandra.metrics:type=Client,name=ShardLocalReadRouted" 2>/dev/null | grep '"Count"' | grep -oE '[0-9]+' | head -1; }

runarm(){
  FLAG=$1; RND=$2; PFX="r_${FLAG}_r${RND}"
  [ "$FLAG" = "on" ] && restart_with true || restart_with false
  echo "===== read_routing=$FLAG round $RND rate=$RATE $(date -u +%H:%M:%S) =====" | tee -a "$SUMM"
  # WARMUP (fills chunk cache post-restart; not measured). FOREGROUND so it finishes before measuring.
  $SSHLG "taskset -c 0-15 cassandra-easy-stress run KeyValue --host 157.180.98.112 --no-schema --prometheusport 0 --readrate 1.0 --rate $RATE --concurrency $CONC --threads $THREADS --queue 2000000 --duration ${WARM}s >/root/warm_$PFX.log 2>&1"
  # MEASURE: mpstat on rig (bg), loadgen read FOREGROUND (blocks DUR).
  pkill -x mpstat 2>/dev/null; sleep 1
  setsid bash -c "mpstat 2 $((DUR/2)) > $OUT/mp_$PFX.log 2>&1" </dev/null &
  RC0=$(readcount); RT0=$(routedcount)
  $SSHLG "taskset -c 0-15 cassandra-easy-stress run KeyValue --host 157.180.98.112 --no-schema --prometheusport 0 --readrate 1.0 --rate $RATE --concurrency $CONC --threads $THREADS --queue 2000000 --duration ${DUR}s --hdr /root/$PFX >/root/lg_$PFX.log 2>&1"
  RC1=$(readcount); RT1=$(routedcount)
  pkill -x mpstat 2>/dev/null
  DELIV=$(( (RC1-RC0)/DUR )); DROUTED=$(( RT1-RT0 ))
  BUSY=$(awk '/all/ && $NF!="%idle"{b=100-$NF; s+=b; n++; if(b>mx)mx=b} END{if(n)printf "avg=%.1f max=%.1f", s/n, mx}' "$OUT/mp_$PFX.log")
  echo "  delivered=$DELIV r/s (offered=$RATE) routed_delta=$DROUTED  cass_busy: $BUSY %" | tee -a "$SUMM"
  echo "  client read rows [count p99ms rps] tail:" | tee -a "$SUMM"
  $SSHLG "grep -E '^[[:space:]]+0[[:space:]]' /root/lg_$PFX.log | tail -3" 2>/dev/null | sed 's/^/    /' | tee -a "$SUMM"
  rsync -az -e "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null" root@$LG:/root/${PFX}*eads*.txt "$OUT/" 2>/dev/null
}
for r in $(seq 1 $ROUNDS); do runarm on "$r"; runarm off "$r"; done
echo "=== READ AB DONE $(date -u +%H:%M:%S) ===" | tee -a "$SUMM"
# NOTE unresolved before running: knee.sh showed loadgen driving ~100k r/s but rig readcount/mpstat read
# idle — debug rig-side measurement (nodetool grep / mpstat overlap) FIRST, confirm cass CPU ~100% at knee,
# THEN trust this A/B. Merge the pulled *-reads.txt HDR files per arm across rounds for the p50->p9999 band.
