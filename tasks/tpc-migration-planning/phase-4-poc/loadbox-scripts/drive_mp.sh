#!/bin/bash
# drive_mp.sh <readrate> <per_proc_rate> <nprocs> <dur_s> <tag>
RR=$1; RATE=$2; N=$3; DUR=$4; TAG=$5
pkill -9 -f "[e]asy-stress-.*-all[.]jar" 2>/dev/null; sleep 1
for i in $(seq 1 $N); do
  setsid taskset -c 0-15 java -jar /opt/ces/cassandra-easy-stress-10-all.jar run KeyValue \
    --host 157.180.98.112 --no-schema --prometheusport 0 \
    --readrate "$RR" --rate "$RATE" --partitions 2000000 --threads 16 --queue 1000000 \
    --duration "${DUR}s" </dev/null >"/tmp/run_${TAG}_$i.log" 2>&1 &
  disown
done
( sleep 20; mpstat 1 30 >"/tmp/cli_${TAG}.txt" 2>&1 ) </dev/null >/dev/null 2>&1 & disown
echo "launched N=$N procs tag=$TAG per_proc_rate=$RATE rr=$RR total_offered=$((RATE*N))"
