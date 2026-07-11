#!/bin/bash
# sp.sh <rate> <concurrency> <threads> <dur> <tag>
RATE=$1; CC=$2; TH=$3; DUR=$4; TAG=$5
pkill -9 -f "[e]asy-stress-.*-all[.]jar" 2>/dev/null; sleep 1
setsid taskset -c 0-15 java -jar /opt/ces/cassandra-easy-stress-10-all.jar run KeyValue \
  --host 157.180.98.112 --no-schema --prometheusport 0 --readrate 0.0 \
  --rate "$RATE" --concurrency "$CC" --threads "$TH" --queue 2000000 --duration "${DUR}s" \
  </dev/null >"/tmp/sp_${TAG}.log" 2>&1 &
disown
( sleep 20; mpstat 1 30 >"/tmp/spcli_${TAG}.txt" 2>&1 ) </dev/null >/dev/null 2>&1 & disown
echo "launched rate=$RATE cc=$CC threads=$TH"
