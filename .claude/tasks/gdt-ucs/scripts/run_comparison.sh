#!/bin/bash
# Drive the 3-condition GDT-aware UCS comparison on a single rig.
#
# Conditions:
#   baseline   UCS T4, GDT off
#   gdt        UCS T4, GDT on (-Dunified_compaction.gdt.enabled=true)
#   twcs       TimeWindowCompactionStrategy (performance ceiling for time-series)
#
# Usage:
#   run_comparison.sh \
#       --cassandra-home /root/repos/fork/cassandra \
#       --easy-stress    /root/repos/easy-cass-stress/bin/easy-cass-stress \
#       --data-dir       /var/lib/gdt-ucs/data \
#       --commitlog-dir  /var/lib/gdt-ucs/commitlog \
#       --results-dir    /root/runs/gdt-ucs \
#       --workload       BasicTimeSeries \
#       --duration       30m \
#       --partitions     5000000 \
#       --threads        64 \
#       [--conditions baseline,gdt,twcs]
#
# Output: <results-dir>/<run-uuid>/<condition>/
#   stress.log, stress.parquet, pre.json, post.json, cassandra-system.log,
#   nodetool-compactionstats.txt, condition.json (summary).

set -euo pipefail

# ---- args -----------------------------------------------------------------

CASSANDRA_HOME=""
EASY_STRESS=""
DATA_DIR=""
COMMITLOG_DIR=""
RESULTS_DIR=""
WORKLOAD="BasicTimeSeries"
DURATION="30m"
PARTITIONS=5000000
THREADS=64
CONDITIONS="baseline,gdt,twcs"
TEMPLATE_DIR="$(cd "$(dirname "$0")/.." && pwd)/conf"

while [[ $# -gt 0 ]]; do
    case $1 in
        --cassandra-home) CASSANDRA_HOME=$2; shift 2 ;;
        --easy-stress)    EASY_STRESS=$2; shift 2 ;;
        --data-dir)       DATA_DIR=$2; shift 2 ;;
        --commitlog-dir)  COMMITLOG_DIR=$2; shift 2 ;;
        --results-dir)    RESULTS_DIR=$2; shift 2 ;;
        --workload)       WORKLOAD=$2; shift 2 ;;
        --duration)       DURATION=$2; shift 2 ;;
        --partitions)     PARTITIONS=$2; shift 2 ;;
        --threads)        THREADS=$2; shift 2 ;;
        --conditions)     CONDITIONS=$2; shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

for required in CASSANDRA_HOME EASY_STRESS DATA_DIR COMMITLOG_DIR RESULTS_DIR; do
    if [[ -z "${!required}" ]]; then
        echo "ERROR: --${required,,} is required" >&2; exit 2
    fi
done

RUN_UUID=$(uuidgen | tr -d '-' | head -c 12)
RUN_DIR=$RESULTS_DIR/$RUN_UUID
mkdir -p "$RUN_DIR"
echo "Run UUID: $RUN_UUID"
echo "Run dir:  $RUN_DIR"

# ---- helpers --------------------------------------------------------------

log() { echo "[$(date -u +%H:%M:%S)] $*"; }

write_cassandra_yaml() {
    local condition=$1 conf_dir=$2
    local hints=$conf_dir/hints saved=$conf_dir/saved_caches
    mkdir -p "$hints" "$saved" "$DATA_DIR" "$COMMITLOG_DIR"
    sed -e "s|__DATA_DIR__|$DATA_DIR|" \
        -e "s|__COMMITLOG_DIR__|$COMMITLOG_DIR|" \
        -e "s|__HINTS_DIR__|$hints|" \
        -e "s|__SAVED_CACHES_DIR__|$saved|" \
        -e "s|__LISTEN_ADDRESS__|127.0.0.1|" \
        "$TEMPLATE_DIR/cassandra.yaml.template" > "$conf_dir/cassandra.yaml"
}

write_jvm_options() {
    # The condition determines the GDT system property only — all other JVM
    # options stay constant for cross-condition comparability.
    local condition=$1 conf_dir=$2
    local gdt_flag="false"
    [[ "$condition" == "gdt" ]] && gdt_flag="true"
    cat > "$conf_dir/jvm-server.options" <<EOF
-Xms8G
-Xmx8G
-XX:+UseG1GC
-Dcassandra.skip_default_role_setup=true
-Dunified_compaction.gdt.enabled=$gdt_flag
EOF
}

start_cassandra() {
    local conf_dir=$1 log_path=$2
    CASSANDRA_CONF=$conf_dir nohup "$CASSANDRA_HOME/bin/cassandra" -f \
        >"$log_path" 2>&1 &
    echo $! > "$conf_dir/cassandra.pid"
}

wait_ready() {
    local timeout=300 elapsed=0
    while ! "$CASSANDRA_HOME/bin/nodetool" status 2>/dev/null | grep -q '^UN '; do
        if (( elapsed >= timeout )); then
            echo "ERROR: Cassandra not UN after ${timeout}s" >&2; return 1
        fi
        sleep 2; elapsed=$((elapsed + 2))
    done
}

stop_cassandra() {
    local conf_dir=$1
    local pid
    pid=$(cat "$conf_dir/cassandra.pid" 2>/dev/null || true)
    [[ -n "$pid" ]] && "$CASSANDRA_HOME/bin/nodetool" drain 2>/dev/null || true
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
    [[ -n "$pid" ]] && wait "$pid" 2>/dev/null || true
}

create_table_with_strategy() {
    local condition=$1
    local strategy_json
    case "$condition" in
        baseline|gdt)
            strategy_json='{"class":"UnifiedCompactionStrategy","scaling_parameters":"T4"}'
            ;;
        twcs)
            strategy_json='{"class":"TimeWindowCompactionStrategy","compaction_window_unit":"HOURS","compaction_window_size":"1"}'
            ;;
    esac
    # easy-cass-stress can take --compaction directly; we don't need to issue
    # DDL ourselves. Set the env var for the run.
    export GDT_COMPACTION_STRATEGY=$strategy_json
}

run_condition() {
    local condition=$1
    local cond_dir=$RUN_DIR/$condition
    local conf_dir=$cond_dir/conf
    mkdir -p "$cond_dir" "$conf_dir"

    log "[$condition] preparing"
    rm -rf "$DATA_DIR"/* "$COMMITLOG_DIR"/* || true
    mkdir -p "$DATA_DIR" "$COMMITLOG_DIR"
    cp -r "$CASSANDRA_HOME/conf/." "$conf_dir/"  # base conf as starting point
    write_cassandra_yaml "$condition" "$conf_dir"
    write_jvm_options    "$condition" "$conf_dir"

    log "[$condition] starting cassandra"
    start_cassandra "$conf_dir" "$cond_dir/cassandra.log"
    wait_ready

    log "[$condition] pre-snapshot"
    "$(dirname "$0")/measure_db_waf.sh" "$CASSANDRA_HOME" "$cond_dir/pre.json"

    log "[$condition] running easy-cass-stress ($WORKLOAD, $DURATION)"
    create_table_with_strategy "$condition"
    "$EASY_STRESS" "$WORKLOAD" \
        -d "$DURATION" \
        -p "$PARTITIONS" \
        -t "$THREADS" \
        --compaction "$GDT_COMPACTION_STRATEGY" \
        --parquet "$cond_dir/stress.parquet" \
        > "$cond_dir/stress.log" 2>&1 || {
            log "[$condition] stress FAILED — see stress.log"
        }

    log "[$condition] flush + post-snapshot"
    "$CASSANDRA_HOME/bin/nodetool" flush 2>/dev/null || true
    sleep 5
    "$(dirname "$0")/measure_db_waf.sh" "$CASSANDRA_HOME" "$cond_dir/post.json"
    "$CASSANDRA_HOME/bin/nodetool" compactionstats > "$cond_dir/nodetool-compactionstats.txt" 2>&1 || true

    log "[$condition] stopping cassandra"
    stop_cassandra "$conf_dir"

    cat > "$cond_dir/condition.json" <<EOF
{
  "condition": "$condition",
  "workload": "$WORKLOAD",
  "duration": "$DURATION",
  "partitions": $PARTITIONS,
  "threads": $THREADS,
  "compaction_strategy": $(printf '%s' "$GDT_COMPACTION_STRATEGY" | python3 -c 'import sys, json; print(json.dumps(sys.stdin.read().strip()))')
}
EOF
    log "[$condition] done → $cond_dir"
}

# ---- main loop ------------------------------------------------------------

IFS=',' read -ra COND_ARR <<< "$CONDITIONS"
for cond in "${COND_ARR[@]}"; do
    run_condition "$cond"
done

log "all conditions complete: $RUN_DIR"
