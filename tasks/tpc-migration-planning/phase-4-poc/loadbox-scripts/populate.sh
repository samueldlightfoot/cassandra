#!/bin/bash
pkill -9 -f "[e]asy-stress-.*-all[.]jar" 2>/dev/null; sleep 1
java -jar /opt/ces/cassandra-easy-stress-10-all.jar run KeyValue --host 157.180.98.112 --prometheusport 0 \
  --replication "{'class':'SimpleStrategy','replication_factor':1}" \
  --populate 62500 --readrate 0.0 --partitions 2000000 --threads 32 --rate 2000000 --duration 1s --drop
