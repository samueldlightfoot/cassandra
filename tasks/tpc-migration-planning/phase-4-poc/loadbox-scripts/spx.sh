#!/bin/bash
# spx.sh <rr> <rate> <cc> <dur> <tag>
RR=$1; RATE=$2; CC=$3; DUR=$4; TAG=$5
pkill -9 -f "[e]asy-stress-.*-all[.]jar" 2>/dev/null; sleep 1
setsid taskset -c 0-15 java -jar /opt/ces/cassandra-easy-stress-10-all.jar run KeyValue \
  --host 157.180.98.112 --no-schema --prometheusport 0 --readrate "$RR" \
  --rate "$RATE" --concurrency "$CC" --threads 32 --queue 2000000 --duration "${DUR}s" \
  --hdr "/tmp/hdr_${TAG}" </dev/null >"/tmp/run_${TAG}.log" 2>&1 &
disown
echo "launched $TAG"
