#!/bin/bash
# drive.sh <readrate> <rate> <dur_s> <tag> — launch a stress run detached, log to /tmp/run_<tag>.log
RR=$1; RATE=$2; DUR=$3; TAG=$4
pkill -9 -f "[e]asy-stress-.*-all[.]jar" 2>/dev/null; sleep 1
LOG=/tmp/run_${TAG}.log
setsid taskset -c 0-15 java -jar /opt/ces/cassandra-easy-stress-10-all.jar run KeyValue \
  --host 157.180.98.112 --no-schema --prometheusport 0 \
  --readrate "$RR" --rate "$RATE" --partitions 2000000 --threads 32 --queue 2000000 \
  --duration "${DUR}s" --hdr "/tmp/${TAG}.hdr" </dev/null >"$LOG" 2>&1 &
disown
echo "launched tag=$TAG rate=$RATE readrate=$RR dur=${DUR}s"
