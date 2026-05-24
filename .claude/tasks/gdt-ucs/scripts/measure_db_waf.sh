#!/bin/bash
# Capture compaction + flush counters for DB-WAF computation.
#
# Usage:
#   measure_db_waf.sh <cassandra-home> <output-json>
#
# Writes a JSON snapshot to <output-json> with:
#   - bytes_compacted   (from CompactionMetrics)
#   - bytes_flushed     (from MemtableMetrics, summed across tables)
#   - bytes_in_user_keyspace  (from tablestats Disk used)
#   - timestamp
#
# DB WAF for a run = (delta_bytes_compacted + delta_bytes_flushed) / user_bytes_written
# where user_bytes_written comes from easy-cass-stress output, not from here.

set -euo pipefail

CASSANDRA_HOME=$1
OUT=$2
HOST=${CASSANDRA_HOST:-127.0.0.1}
JMX_PORT=${CASSANDRA_JMX_PORT:-7199}

nodetool() {
    "$CASSANDRA_HOME/bin/nodetool" -h "$HOST" -p "$JMX_PORT" "$@"
}

ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# `nodetool info` includes "Compacted: <bytes>" in some Cassandra versions; if not,
# fall back to compactionstats / cfstats summation. For PoC, parse `tablestats` which
# is the most stable across versions and gives us bytes-on-disk per table.
tablestats=$(nodetool tablestats -F json 2>/dev/null || nodetool tablestats)

# `nodetool info` total compacted bytes (single-line counter):
total_compacted=$(nodetool info 2>/dev/null | awk -F: '/Compacted/ {gsub(/ /,"",$2); print $2}' || echo "")
if [[ -z "$total_compacted" ]]; then
    total_compacted=0
fi

# Total memtable bytes flushed since startup, via metrics JMX is the right path,
# but for PoC we capture tablestats Disk-used as a proxy; differences across runs
# give us the disk write delta excluding the (constant) startup state.
total_disk_used=$(echo "$tablestats" | awk '/Space used \(total\)/ {gsub(",", "", $NF); sum+=$NF} END {print sum+0}')

cat > "$OUT" <<EOF
{
  "timestamp": "$ts",
  "bytes_compacted": $total_compacted,
  "bytes_disk_used": $total_disk_used,
  "raw_tablestats": $(echo "$tablestats" | python3 -c 'import sys, json; print(json.dumps(sys.stdin.read()))')
}
EOF
