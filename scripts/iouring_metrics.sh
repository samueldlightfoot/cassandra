#!/bin/bash

# io_uring Performance Metrics Collector
#
# Wraps any command (JMH benchmark, cassandra-stress, etc.) and captures
# OS-level metrics that explain io_uring behavior: syscall counts, context
# switches, kernel time, I/O latency, page cache, and io_uring ring stats.
#
# Usage:
#   ./scripts/iouring_metrics.sh --benchmark                    # Full A/B test (build + run + compare)
#   ./scripts/iouring_metrics.sh --name "test_name" -- <command> # Wrap any command
#   ./scripts/iouring_metrics.sh --compare results/dir1 results/dir2
#
# Examples:
#   # Automated A/B benchmark (recommended)
#   ./scripts/iouring_metrics.sh --benchmark
#   ./scripts/iouring_metrics.sh --benchmark --compression lz4 --forks 2 --light
#   ./scripts/iouring_metrics.sh --benchmark --skip-build --iterations 5
#
#   # Manual: wrap any command
#   ./scripts/iouring_metrics.sh --name baseline -- java -jar build/test/jmh/microbench.jar IoUringReadBench
#   ./scripts/iouring_metrics.sh --compare results/baseline results/iouring

set -euo pipefail

# ============================================================
# Configuration
# ============================================================

RESULTS_BASE="${RESULTS_BASE:-$HOME/results/iouring}"
IS_LINUX=false
[[ "$(uname -s)" == "Linux" ]] && IS_LINUX=true

# Tool availability (detected in pre-flight)
HAS_PERF=false
HAS_STRACE=false
HAS_BIOSNOOP=false
HAS_IOSTAT=false

# ============================================================
# PID tracking for cleanup
# ============================================================

MONITOR_PIDS=()
STRACE_PID=""
PERF_PID=""
CMD_PID=""

# ============================================================
# Usage
# ============================================================

print_usage() {
    cat <<'EOF'
io_uring Performance Metrics Collector

Usage:
  iouring_metrics.sh [options] -- <command>
  iouring_metrics.sh --benchmark [benchmark-options]
  iouring_metrics.sh --compare <dir1> <dir2>

Options:
  --name <name>          Test name (required unless --compare/--benchmark)
  --pid <pid>            Attach to existing process instead of child
  --light                Skip strace (reduces overhead ~5-15%)
  --biosnoop             Enable block I/O tracing (requires root + BPF)
  --poll-interval <sec>  Polling interval for /proc metrics (default: 1)
  --compare <dir1> <dir2>  Compare two result directories side-by-side
  -h, --help             Show this help

Benchmark mode (--benchmark):
  --benchmark            Build JMH jar and run IoUringReadBench A/B test
  --compression <type>   Compression to test: none, lz4, both (default: both)
  --forks <n>            JMH forks (default: 1)
  --warmup <n>           JMH warmup iterations (default: 5)
  --iterations <n>       JMH measurement iterations (default: 10)
  --jmh-args <args>      Extra JMH arguments (passed through)
  --skip-build           Skip 'ant build' (use existing build)

Metrics collected:
  1. Syscall profile (strace -c)    — proves io_uring_enter vs pread64
  2. Context switches (/proc)       — io_uring reduces voluntary switches
  3. CPU perf counters (perf stat)  — cycles, CPI, cache misses
  4. CPU time split (/proc)         — user vs kernel time ratio
  5. iostat (1s intervals)          — device throughput, latency, queue depth
  6. Page cache (/proc/meminfo)     — O_DIRECT keeps cache flat
  7. Block I/O trace (biosnoop)     — per-I/O latency (optional)
  8. io_uring ring stats (/proc)    — SQ/CQ head/tail (kernel 5.12+)
EOF
}

# ============================================================
# Helpers
# ============================================================

log() {
    local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $*"
    echo "$msg"
    [[ -n "${LOG_FILE:-}" ]] && echo "$msg" >> "$LOG_FILE"
}

die() {
    echo "FATAL: $*" >&2
    exit 1
}

fmt_num() {
    # Format number with commas: 1234567 -> 1,234,567
    printf "%'d" "$1" 2>/dev/null || echo "$1"
}

# ============================================================
# Cleanup trap
# ============================================================

cleanup() {
    local exit_code=$?

    # Kill all tracked monitor PIDs
    for pid in "${MONITOR_PIDS[@]+"${MONITOR_PIDS[@]}"}"; do
        kill "$pid" 2>/dev/null && wait "$pid" 2>/dev/null || true
    done

    [[ -n "$STRACE_PID" ]] && kill "$STRACE_PID" 2>/dev/null && wait "$STRACE_PID" 2>/dev/null || true
    [[ -n "$PERF_PID" ]] && kill "$PERF_PID" 2>/dev/null && wait "$PERF_PID" 2>/dev/null || true

    if [[ $exit_code -ne 0 && -n "${LOG_FILE:-}" ]]; then
        log "Script exited with error code $exit_code"
    fi
}

trap cleanup EXIT INT TERM

# ============================================================
# Pre-flight: detect tools
# ============================================================

detect_tools() {
    if $IS_LINUX; then
        command -v perf &>/dev/null && HAS_PERF=true
        command -v strace &>/dev/null && HAS_STRACE=true
        command -v biosnoop-bpfcc &>/dev/null && HAS_BIOSNOOP=true
        command -v iostat &>/dev/null && HAS_IOSTAT=true
    fi
}

# ============================================================
# Metric collectors
# ============================================================

# 1. Syscall profile via strace -c
start_strace() {
    local target_pid=$1
    if ! $HAS_STRACE || $LIGHT_MODE; then
        log "strace: skipped ($( $LIGHT_MODE && echo "--light mode" || echo "not available"))"
        return
    fi

    strace -c -f -p "$target_pid" -o "$RESULTS_DIR/syscalls.txt" 2>/dev/null &
    STRACE_PID=$!
    log "strace: PID $STRACE_PID attached to $target_pid"
}

stop_strace() {
    if [[ -n "$STRACE_PID" ]]; then
        kill -INT "$STRACE_PID" 2>/dev/null || true
        wait "$STRACE_PID" 2>/dev/null || true
        STRACE_PID=""
    fi
}

# 2. Context switches from /proc/PID/status
start_context_switch_monitor() {
    local target_pid=$1
    local interval=$2

    if ! $IS_LINUX; then return; fi

    (
        echo "timestamp,elapsed_s,vol_total,nonvol_total,vol_delta,nonvol_delta" > "$RESULTS_DIR/context_switches.csv"
        local start_time
        start_time=$(date +%s)
        local prev_vol=0 prev_nonvol=0
        local first=true

        while kill -0 "$target_pid" 2>/dev/null; do
            local now
            now=$(date +%s)
            local elapsed=$((now - start_time))

            local vol nonvol
            vol=$(grep -c 'voluntary_ctxt_switches' /proc/"$target_pid"/status 2>/dev/null | head -1 || echo 0)
            vol=$(awk '/^voluntary_ctxt_switches/ {print $2}' /proc/"$target_pid"/status 2>/dev/null || echo 0)
            nonvol=$(awk '/^nonvoluntary_ctxt_switches/ {print $2}' /proc/"$target_pid"/status 2>/dev/null || echo 0)

            if $first; then
                first=false
                prev_vol=$vol
                prev_nonvol=$nonvol
            fi

            local vol_delta=$((vol - prev_vol))
            local nonvol_delta=$((nonvol - prev_nonvol))

            echo "$now,$elapsed,$vol,$nonvol,$vol_delta,$nonvol_delta" >> "$RESULTS_DIR/context_switches.csv"

            prev_vol=$vol
            prev_nonvol=$nonvol
            sleep "$interval"
        done
    ) &
    MONITOR_PIDS+=($!)
    log "Context switch monitor: PID $!"
}

# 3. CPU perf counters via perf stat
start_perf_stat() {
    local target_pid=$1

    if ! $HAS_PERF; then
        log "perf stat: skipped (not available)"
        return
    fi

    # Check if perf_event_paranoid allows attachment
    local paranoid
    paranoid=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || echo 99)
    if [[ "$paranoid" -gt 1 ]] && [[ $(id -u) -ne 0 ]]; then
        log "perf stat: skipped (perf_event_paranoid=$paranoid, need <=1 or root)"
        return
    fi

    # --pid follows children automatically in recent perf versions.
    # perf stat writes results to stderr; -o redirects that to file.
    perf stat -e cycles,instructions,cache-references,cache-misses,context-switches,cpu-migrations,task-clock \
        --pid "$target_pid" -o "$RESULTS_DIR/perf_stat.txt" 2>/dev/null &
    PERF_PID=$!
    log "perf stat: PID $PERF_PID attached to $target_pid"
}

stop_perf_stat() {
    if [[ -n "$PERF_PID" ]]; then
        kill -INT "$PERF_PID" 2>/dev/null || true
        wait "$PERF_PID" 2>/dev/null || true
        PERF_PID=""
    fi
}

# 4. CPU time split from /proc/PID/stat
start_cpu_time_monitor() {
    local target_pid=$1
    local interval=$2

    if ! $IS_LINUX; then return; fi

    (
        echo "timestamp,elapsed_s,user_ticks,system_ticks,user_delta,system_delta" > "$RESULTS_DIR/cpu_time.csv"
        local start_time
        start_time=$(date +%s)
        local prev_utime=0 prev_stime=0
        local first=true
        local clk_tck
        clk_tck=$(getconf CLK_TCK)

        while kill -0 "$target_pid" 2>/dev/null; do
            local now
            now=$(date +%s)
            local elapsed=$((now - start_time))

            # Fields 14 (utime) and 15 (stime) in /proc/PID/stat
            local stat_line
            stat_line=$(cat /proc/"$target_pid"/stat 2>/dev/null || echo "")
            if [[ -z "$stat_line" ]]; then break; fi

            local utime stime
            utime=$(echo "$stat_line" | awk '{print $14}')
            stime=$(echo "$stat_line" | awk '{print $15}')

            if $first; then
                first=false
                prev_utime=$utime
                prev_stime=$stime
            fi

            local u_delta=$((utime - prev_utime))
            local s_delta=$((stime - prev_stime))

            echo "$now,$elapsed,$utime,$stime,$u_delta,$s_delta" >> "$RESULTS_DIR/cpu_time.csv"

            prev_utime=$utime
            prev_stime=$stime
            sleep "$interval"
        done
    ) &
    MONITOR_PIDS+=($!)
    log "CPU time monitor: PID $!"
}

# 5. iostat
start_iostat() {
    if ! $HAS_IOSTAT; then
        log "iostat: skipped (not available)"
        return
    fi

    iostat -xz 1 > "$RESULTS_DIR/iostat.log" 2>&1 &
    MONITOR_PIDS+=($!)
    log "iostat: PID $!"
}

# 6. Page cache monitor
start_page_cache_monitor() {
    if ! $IS_LINUX; then return; fi

    (
        echo "timestamp,elapsed_s,cached_mb,buffers_mb,active_mb" > "$RESULTS_DIR/page_cache.csv"
        local start_time
        start_time=$(date +%s)

        while true; do
            local now
            now=$(date +%s)
            local elapsed=$((now - start_time))

            local cached buffers active
            cached=$(awk '/^Cached:/ {print int($2/1024)}' /proc/meminfo)
            buffers=$(awk '/^Buffers:/ {print int($2/1024)}' /proc/meminfo)
            active=$(awk '/^Active:/ {print int($2/1024)}' /proc/meminfo)

            echo "$now,$elapsed,$cached,$buffers,$active" >> "$RESULTS_DIR/page_cache.csv"
            sleep 5
        done
    ) &
    MONITOR_PIDS+=($!)
    log "Page cache monitor: PID $!"
}

# 7. biosnoop (optional)
start_biosnoop() {
    if ! $BIOSNOOP_ENABLED; then return; fi
    if ! $HAS_BIOSNOOP; then
        log "biosnoop: skipped (biosnoop-bpfcc not available)"
        return
    fi

    biosnoop-bpfcc > "$RESULTS_DIR/biosnoop.txt" 2>/dev/null &
    MONITOR_PIDS+=($!)
    log "biosnoop: PID $!"
}

# 8. io_uring ring stats from /proc/PID/fdinfo
start_uring_ring_monitor() {
    local target_pid=$1

    if ! $IS_LINUX; then return; fi

    (
        echo "timestamp,elapsed_s,ring_fds,sq_entries,cq_entries" > "$RESULTS_DIR/uring_ring_stats.csv"
        local start_time
        start_time=$(date +%s)

        while kill -0 "$target_pid" 2>/dev/null; do
            local now
            now=$(date +%s)
            local elapsed=$((now - start_time))

            local ring_count=0 total_sq=0 total_cq=0

            for fdinfo in /proc/"$target_pid"/fdinfo/*; do
                if grep -q 'SqSize' "$fdinfo" 2>/dev/null; then
                    ring_count=$((ring_count + 1))
                    local sq cq
                    sq=$(awk '/SqSize/ {print $2}' "$fdinfo" 2>/dev/null || echo 0)
                    cq=$(awk '/CqSize/ {print $2}' "$fdinfo" 2>/dev/null || echo 0)
                    total_sq=$((total_sq + sq))
                    total_cq=$((total_cq + cq))
                fi
            done

            echo "$now,$elapsed,$ring_count,$total_sq,$total_cq" >> "$RESULTS_DIR/uring_ring_stats.csv"
            sleep 5
        done
    ) &
    MONITOR_PIDS+=($!)
    log "io_uring ring monitor: PID $!"
}


# ============================================================
# Summary: parse and display results
# ============================================================

generate_summary() {
    local results_dir=$1
    local summary_file="$results_dir/summary.txt"

    {
        echo "============================================================"
        echo "io_uring Metrics: $(basename "$results_dir")"
        echo "Date: $(date '+%Y-%m-%d %H:%M:%S')"
        echo "============================================================"
        echo ""

        # --- Syscall Profile ---
        if [[ -f "$results_dir/syscalls.txt" ]]; then
            echo "--- Syscall Profile ---"
            local pread_count io_uring_count
            pread_count=$(awk '/pread64/ {gsub(/[^0-9]/, "", $4); print $4}' "$results_dir/syscalls.txt" 2>/dev/null || echo 0)
            io_uring_count=$(awk '/io_uring_enter/ {gsub(/[^0-9]/, "", $4); print $4}' "$results_dir/syscalls.txt" 2>/dev/null || echo 0)

            # Show top 10 syscalls by count
            awk 'NR > 2 && $4 ~ /^[0-9]/ { printf "  %-25s %s calls\n", $NF, $4 }' "$results_dir/syscalls.txt" 2>/dev/null \
                | sort -t' ' -k2 -rn | head -10

            echo ""
            if [[ "${pread_count:-0}" -gt 0 ]] && [[ "${io_uring_count:-0}" -gt 0 ]]; then
                local ratio=$((pread_count / io_uring_count))
                echo "  pread64:        $(fmt_num "$pread_count")"
                echo "  io_uring_enter: $(fmt_num "$io_uring_count")"
                echo "  Ratio:          ${ratio}:1 (reads per ring enter)"
            elif [[ "${pread_count:-0}" -gt 0 ]]; then
                echo "  pread64:        $(fmt_num "$pread_count") (no io_uring_enter — FileChannel mode)"
            elif [[ "${io_uring_count:-0}" -gt 0 ]]; then
                echo "  io_uring_enter: $(fmt_num "$io_uring_count") (no pread64 — io_uring mode)"
            fi
            echo ""
        fi

        # --- Context Switches ---
        if [[ -f "$results_dir/context_switches.csv" ]]; then
            echo "--- Context Switches ---"
            awk -F',' '
                NR == 1 { next }
                { vol = $3; nonvol = $4; elapsed = $2; lines++ }
                END {
                    if (lines > 0 && elapsed > 0) {
                        printf "  Voluntary:      %d (avg %d/s)\n", vol, vol/elapsed
                        printf "  Involuntary:    %d (avg %d/s)\n", nonvol, nonvol/elapsed
                    }
                }
            ' "$results_dir/context_switches.csv"
            echo ""
        fi

        # --- CPU perf stat ---
        if [[ -f "$results_dir/perf_stat.txt" ]]; then
            echo "--- CPU (perf stat) ---"
            while IFS= read -r line; do
                echo "  $line"
            done < <(grep -E 'cycles|instructions|cache-|context-switches|cpu-migrations|task-clock' "$results_dir/perf_stat.txt" 2>/dev/null || true)

            # Compute CPI
            local cycles instructions
            cycles=$(awk '/cycles/ && !/cache/ {gsub(/,/, ""); print $1}' "$results_dir/perf_stat.txt" 2>/dev/null | head -1)
            instructions=$(awk '/instructions/ {gsub(/,/, ""); print $1}' "$results_dir/perf_stat.txt" 2>/dev/null | head -1)
            if [[ -n "$cycles" ]] && [[ -n "$instructions" ]] && [[ "$instructions" -gt 0 ]]; then
                local cpi
                cpi=$(awk "BEGIN {printf \"%.2f\", $cycles / $instructions}")
                echo "  CPI:            $cpi"
            fi
            echo ""
        fi

        # --- CPU Time ---
        if [[ -f "$results_dir/cpu_time.csv" ]]; then
            echo "--- CPU Time ---"
            awk -F',' '
                NR == 1 { next }
                NR == 2 { first_u = $3; first_s = $4 }
                { last_u = $3; last_s = $4; elapsed = $2 }
                END {
                    if (elapsed > 0) {
                        clk = 100  # assume CLK_TCK=100 (standard on Linux)
                        user_ms = (last_u - first_u) * 1000 / clk
                        sys_ms = (last_s - first_s) * 1000 / clk
                        total = user_ms + sys_ms
                        if (total > 0) {
                            printf "  User:    %d ms (%.1f%%)\n", user_ms, 100.0 * user_ms / total
                            printf "  System:  %d ms (%.1f%%)\n", sys_ms, 100.0 * sys_ms / total
                        }
                    }
                }
            ' "$results_dir/cpu_time.csv"
            echo ""
        fi

        # --- iostat summary ---
        if [[ -f "$results_dir/iostat.log" ]]; then
            echo "--- I/O (iostat) ---"
            # Extract avg read throughput and latency from iostat
            awk '
                /^[a-z]/ && NF >= 14 {
                    # Device line: ... rkB/s ... r_await
                    reads += $6; r_await += $10; count++
                }
                END {
                    if (count > 0) {
                        printf "  Avg read throughput: %.1f MB/s\n", reads / count / 1024
                        printf "  Avg read latency:    %.2f ms\n", r_await / count
                        printf "  Samples:             %d\n", count
                    } else {
                        print "  No device I/O detected"
                    }
                }
            ' "$results_dir/iostat.log"
            echo ""
        fi

        # --- Page Cache ---
        if [[ -f "$results_dir/page_cache.csv" ]]; then
            echo "--- Page Cache ---"
            awk -F',' '
                NR == 1 { next }
                NR == 2 { start = $3 }
                { end_val = $3 }
                END {
                    if (NR > 1) {
                        printf "  Start:  %d MB\n", start
                        printf "  End:    %d MB\n", end_val
                        printf "  Delta:  %+d MB\n", end_val - start
                    }
                }
            ' "$results_dir/page_cache.csv"
            echo ""
        fi

        # --- io_uring Ring Stats ---
        if [[ -f "$results_dir/uring_ring_stats.csv" ]]; then
            local max_rings
            max_rings=$(awk -F',' 'NR > 1 { if ($3 > max) max = $3 } END { print max+0 }' "$results_dir/uring_ring_stats.csv")
            if [[ "$max_rings" -gt 0 ]]; then
                echo "--- io_uring Ring Stats ---"
                echo "  Ring fds found: $max_rings"
                awk -F',' '
                    NR > 1 && $3 > 0 {
                        printf "  [t=%ds] rings=%d sq_total=%d cq_total=%d\n", $2, $3, $4, $5
                    }
                ' "$results_dir/uring_ring_stats.csv" | tail -5
                echo ""
            fi
        fi

        # --- biosnoop ---
        if [[ -f "$results_dir/biosnoop.txt" ]]; then
            local bio_lines
            bio_lines=$(wc -l < "$results_dir/biosnoop.txt")
            echo "--- Block I/O (biosnoop) ---"
            echo "  Lines captured: $bio_lines"
            echo ""
        fi

        # --- Command Output (tail) ---
        if [[ -f "$results_dir/command_output.log" ]]; then
            local cmd_lines
            cmd_lines=$(wc -l < "$results_dir/command_output.log")
            echo "--- Command Output (last 20 lines of $cmd_lines total) ---"
            tail -20 "$results_dir/command_output.log" | while IFS= read -r line; do
                echo "  $line"
            done
            echo ""
        fi

        echo "--- Output Files ---"
        ls -lhS "$results_dir"/ 2>/dev/null | tail -n +2 | while IFS= read -r line; do
            echo "  $line"
        done

        echo ""
        echo "Results: $results_dir/"
        echo "============================================================"

    } | tee "$summary_file"
}

# ============================================================
# Compare mode
# ============================================================

compare_results() {
    local dir1=$1 dir2=$2

    echo "============================================================"
    echo "io_uring Metrics Comparison"
    echo "  A: $(basename "$dir1")"
    echo "  B: $(basename "$dir2")"
    echo "============================================================"
    echo ""

    # Syscalls
    if [[ -f "$dir1/syscalls.txt" ]] && [[ -f "$dir2/syscalls.txt" ]]; then
        echo "--- Syscall Profile ---"
        printf "  %-25s %15s %15s %10s\n" "Syscall" "A" "B" "Delta"
        printf "  %-25s %15s %15s %10s\n" "-------" "-" "-" "-----"

        for syscall in pread64 io_uring_enter read write mmap; do
            local a_count b_count
            # strace -c format: %time  seconds  usecs/call  calls  errors  syscall
            # Column 4 is 'calls'. Use head -1 to avoid multiline matches.
            a_count=$(grep -w "$syscall" "$dir1/syscalls.txt" 2>/dev/null | head -1 | awk '{print $4}' | tr -cd '0-9')
            b_count=$(grep -w "$syscall" "$dir2/syscalls.txt" 2>/dev/null | head -1 | awk '{print $4}' | tr -cd '0-9')
            a_count=${a_count:-0}
            b_count=${b_count:-0}
            [[ "$a_count" -eq 0 ]] && [[ "$b_count" -eq 0 ]] && continue

            local delta=$(( b_count - a_count ))
            local sign=""
            [[ $delta -gt 0 ]] && sign="+"
            printf "  %-25s %15s %15s %10s\n" "$syscall" "${a_count:-0}" "${b_count:-0}" "${sign}${delta}"
        done
        echo ""
    fi

    # Context switches
    if [[ -f "$dir1/context_switches.csv" ]] && [[ -f "$dir2/context_switches.csv" ]]; then
        echo "--- Context Switches ---"
        printf "  %-20s %15s %15s %10s\n" "Metric" "A" "B" "Delta"
        printf "  %-20s %15s %15s %10s\n" "------" "-" "-" "-----"

        local a_vol b_vol a_nonvol b_nonvol
        a_vol=$(awk -F',' 'END {print $3}' "$dir1/context_switches.csv" 2>/dev/null || echo 0)
        b_vol=$(awk -F',' 'END {print $3}' "$dir2/context_switches.csv" 2>/dev/null || echo 0)
        a_nonvol=$(awk -F',' 'END {print $4}' "$dir1/context_switches.csv" 2>/dev/null || echo 0)
        b_nonvol=$(awk -F',' 'END {print $4}' "$dir2/context_switches.csv" 2>/dev/null || echo 0)

        local vol_delta=$((b_vol - a_vol))
        local nonvol_delta=$((b_nonvol - a_nonvol))

        printf "  %-20s %15s %15s %+10d\n" "Voluntary" "$a_vol" "$b_vol" "$vol_delta"
        printf "  %-20s %15s %15s %+10d\n" "Involuntary" "$a_nonvol" "$b_nonvol" "$nonvol_delta"
        echo ""
    fi

    # CPU time
    if [[ -f "$dir1/cpu_time.csv" ]] && [[ -f "$dir2/cpu_time.csv" ]]; then
        echo "--- CPU Time ---"
        printf "  %-20s %15s %15s\n" "Metric" "A" "B"
        printf "  %-20s %15s %15s\n" "------" "-" "-"

        for label_dir in "A:$dir1" "B:$dir2"; do
            local label="${label_dir%%:*}"
            local d="${label_dir#*:}"
            awk -F',' -v lbl="$label" '
                NR == 2 { first_u = $3; first_s = $4 }
                { last_u = $3; last_s = $4 }
                END {
                    user_ms = (last_u - first_u) * 10
                    sys_ms = (last_s - first_s) * 10
                    total = user_ms + sys_ms
                    if (total > 0)
                        printf "  %-20s user=%dms (%.1f%%) sys=%dms (%.1f%%)\n", lbl, user_ms, 100.0*user_ms/total, sys_ms, 100.0*sys_ms/total
                }
            ' "$d/cpu_time.csv" 2>/dev/null
        done
        echo ""
    fi

    # Page cache
    if [[ -f "$dir1/page_cache.csv" ]] && [[ -f "$dir2/page_cache.csv" ]]; then
        echo "--- Page Cache ---"
        printf "  %-20s %15s %15s\n" "Metric" "A" "B"
        printf "  %-20s %15s %15s\n" "------" "-" "-"

        for label_dir in "A:$dir1" "B:$dir2"; do
            local label="${label_dir%%:*}"
            local d="${label_dir#*:}"
            awk -F',' -v lbl="$label" '
                NR == 2 { start = $3 }
                { end_val = $3 }
                END {
                    if (NR > 1)
                        printf "  %-20s start=%dMB end=%dMB delta=%+dMB\n", lbl, start, end_val, end_val-start
                }
            ' "$d/page_cache.csv" 2>/dev/null
        done
        echo ""
    fi

    echo "============================================================"
}

# ============================================================
# Parse arguments
# ============================================================

TEST_NAME=""
TARGET_PID=""
LIGHT_MODE=false
BIOSNOOP_ENABLED=false
POLL_INTERVAL=1
COMPARE_MODE=false
COMPARE_DIR1=""
COMPARE_DIR2=""
CMD_ARGS=()

# Benchmark mode options
BENCHMARK_MODE=false
BENCH_COMPRESSION="both"
BENCH_FORKS=1
BENCH_WARMUP=5
BENCH_ITERATIONS=10
BENCH_JMH_ARGS=""
BENCH_SKIP_BUILD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --name)
            TEST_NAME="$2"; shift 2 ;;
        --pid)
            TARGET_PID="$2"; shift 2 ;;
        --light)
            LIGHT_MODE=true; shift ;;
        --biosnoop)
            BIOSNOOP_ENABLED=true; shift ;;
        --poll-interval)
            POLL_INTERVAL="$2"; shift 2 ;;
        --compare)
            COMPARE_MODE=true
            COMPARE_DIR1="$2"
            COMPARE_DIR2="$3"
            shift 3 ;;
        --benchmark)
            BENCHMARK_MODE=true; shift ;;
        --compression)
            BENCH_COMPRESSION="$2"; shift 2 ;;
        --forks)
            BENCH_FORKS="$2"; shift 2 ;;
        --warmup)
            BENCH_WARMUP="$2"; shift 2 ;;
        --iterations)
            BENCH_ITERATIONS="$2"; shift 2 ;;
        --jmh-args)
            BENCH_JMH_ARGS="$2"; shift 2 ;;
        --skip-build)
            BENCH_SKIP_BUILD=true; shift ;;
        -h|--help)
            print_usage; exit 0 ;;
        --)
            shift; CMD_ARGS=("$@"); break ;;
        -*)
            die "Unknown option: $1" ;;
        *)
            die "Unexpected argument: $1 (put command after --)" ;;
    esac
done

# ============================================================
# Compare mode: just compare and exit
# ============================================================

if $COMPARE_MODE; then
    [[ -d "$COMPARE_DIR1" ]] || die "Directory not found: $COMPARE_DIR1"
    [[ -d "$COMPARE_DIR2" ]] || die "Directory not found: $COMPARE_DIR2"
    compare_results "$COMPARE_DIR1" "$COMPARE_DIR2"
    exit 0
fi

# ============================================================
# Benchmark mode: build, run A/B, compare
# ============================================================

if $BENCHMARK_MODE; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    CASSANDRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
    BENCH_NAME="IoUringReadBench"

    # Determine timestamp-based name if none given
    if [[ -z "$TEST_NAME" ]]; then
        TEST_NAME="bench-$(date '+%Y%m%d-%H%M%S')"
    fi
    BENCH_RESULTS_BASE="$RESULTS_BASE/$TEST_NAME"
    mkdir -p "$BENCH_RESULTS_BASE"
    LOG_FILE="$BENCH_RESULTS_BASE/benchmark.log"

    log "============================================================"
    log "io_uring A/B Benchmark"
    log "============================================================"
    log ""
    log "Cassandra dir: $CASSANDRA_DIR"
    log "Compression:   $BENCH_COMPRESSION"
    log "JMH forks:     $BENCH_FORKS"
    log "Warmup iters:  $BENCH_WARMUP"
    log "Measure iters: $BENCH_ITERATIONS"
    log "Extra JMH args: ${BENCH_JMH_ARGS:-none}"
    log "Light mode:    $LIGHT_MODE"
    log ""

    # Step 1: Build Cassandra (microbench classes compile with test build)
    if ! $BENCH_SKIP_BUILD; then
        log "=== Step 1: Building Cassandra ==="
        (cd "$CASSANDRA_DIR" && ant build) >> "$LOG_FILE" 2>&1 || die "ant build failed (see $LOG_FILE)"
        log "Build complete"
    else
        log "=== Step 1: Skipping build (--skip-build) ==="
    fi

    # Determine compression params to iterate over
    case "$BENCH_COMPRESSION" in
        both) COMPRESSIONS=("none" "lz4") ;;
        none|lz4) COMPRESSIONS=("$BENCH_COMPRESSION") ;;
        *) die "Unknown compression: $BENCH_COMPRESSION (use none, lz4, or both)" ;;
    esac

    # Build common JMH args
    # ant microbench uses: -Dbenchmark.name=<pattern> -Djmh.args="<extra args>"
    JMH_COMMON_ARGS="-f $BENCH_FORKS -wi $BENCH_WARMUP -i $BENCH_ITERATIONS"
    [[ -n "$BENCH_JMH_ARGS" ]] && JMH_COMMON_ARGS="$JMH_COMMON_ARGS $BENCH_JMH_ARGS"

    # Step 2: Run A/B for each compression type
    RESULT_PAIRS=()
    for comp in "${COMPRESSIONS[@]}"; do
        log ""
        log "=== Compression: $comp ==="

        for uring_val in false true; do
            if [[ "$uring_val" == "false" ]]; then
                run_label="${comp}-baseline"
            else
                run_label="${comp}-iouring"
            fi
            run_dir="$BENCH_RESULTS_BASE/$run_label"
            mkdir -p "$run_dir"

            log ""
            log "--- Run: $run_label ---"

            # Build JMH args: param selection + common args
            # NOTE: Do NOT pass -rf/-rff here — build-bench.xml already sets them.
            # Results go to build/test/jmh-result.json; we copy them after.
            JMH_PARAMS="-p useIoUring=$uring_val -p compression=$comp $JMH_COMMON_ARGS"
            log "ant microbench -Dbenchmark.name=$BENCH_NAME -Djmh.args=\"$JMH_PARAMS\""

            # Build args to pass to ourselves in normal mode
            SELF_ARGS=(--name "$TEST_NAME/$run_label")
            $LIGHT_MODE && SELF_ARGS+=(--light)
            $BIOSNOOP_ENABLED && SELF_ARGS+=(--biosnoop)
            [[ "$POLL_INTERVAL" != "1" ]] && SELF_ARGS+=(--poll-interval "$POLL_INTERVAL")
            SELF_ARGS+=(-- ant -f "$CASSANDRA_DIR/build.xml" microbench "-Dbenchmark.name=$BENCH_NAME" "-Djmh.args=$JMH_PARAMS" -Dno-build-test=true)

            # Re-invoke ourselves in normal mode for this run
            "$0" "${SELF_ARGS[@]}" 2>&1 | tee -a "$LOG_FILE"
            RUN_EXIT=${PIPESTATUS[0]}

            if [[ $RUN_EXIT -ne 0 ]]; then
                log "WARNING: Run $run_label exited with code $RUN_EXIT"
            fi

            # Copy JMH JSON results from default output location
            JMH_DEFAULT_RESULT="$CASSANDRA_DIR/build/test/jmh-result.json"
            if [[ -f "$JMH_DEFAULT_RESULT" ]]; then
                cp "$JMH_DEFAULT_RESULT" "$run_dir/jmh_results.json"
                # Also copy to the metrics directory
                METRICS_RUN_DIR="$RESULTS_BASE/$TEST_NAME/$run_label"
                cp "$JMH_DEFAULT_RESULT" "$METRICS_RUN_DIR/jmh_results.json" 2>/dev/null || true
                log "JMH results copied to $run_dir/jmh_results.json"
            fi
        done

        # Track pairs for comparison
        RESULT_PAIRS+=("$comp")
    done

    # Step 3: Compare each baseline vs io_uring pair
    log ""
    log "============================================================"
    log "=== A/B Comparisons ==="
    log "============================================================"

    for comp in "${RESULT_PAIRS[@]}"; do
        baseline_dir="$RESULTS_BASE/$TEST_NAME/${comp}-baseline"
        iouring_dir="$RESULTS_BASE/$TEST_NAME/${comp}-iouring"

        if [[ -d "$baseline_dir" ]] && [[ -d "$iouring_dir" ]]; then
            log ""
            log "--- Compression: $comp ---"
            compare_results "$baseline_dir" "$iouring_dir" | tee -a "$LOG_FILE"
        fi
    done

    # Step 4: Consolidate JMH results
    log ""
    log "=== JMH Results Summary ==="
    log ""

    for comp in "${RESULT_PAIRS[@]}"; do
        for variant in baseline iouring; do
            run_dir="$RESULTS_BASE/$TEST_NAME/${comp}-${variant}"
            jmh_json="$run_dir/jmh_results.json"
            if [[ -f "$jmh_json" ]]; then
                log "[$comp-$variant] JMH JSON: $jmh_json"
                # Extract key metrics: benchmark name, mode, score, unit
                python3 -c "
import json, sys
with open('$jmh_json') as f:
    data = json.load(f)
for r in data:
    name = r['benchmark'].split('.')[-1]
    score = r['primaryMetric']['score']
    unit = r['primaryMetric']['scoreUnit']
    err = r['primaryMetric'].get('scoreError', 0)
    print(f'  {name}: {score:.2f} ± {err:.2f} {unit}')
" 2>/dev/null || log "  (install python3 to parse JMH JSON, or view $jmh_json directly)"
            fi
        done
    done

    log ""
    log "============================================================"
    log "All results: $BENCH_RESULTS_BASE/"
    log "Full log:    $LOG_FILE"
    log "============================================================"

    exit 0
fi

# ============================================================
# Normal mode: validate args
# ============================================================

[[ -z "$TEST_NAME" ]] && die "Missing --name <test_name>"
[[ ${#CMD_ARGS[@]} -eq 0 ]] && [[ -z "$TARGET_PID" ]] && die "No command given (use -- <command>) and no --pid specified"

RESULTS_DIR="$RESULTS_BASE/$TEST_NAME"
mkdir -p "$RESULTS_DIR"
LOG_FILE="$RESULTS_DIR/metrics.log"

# ============================================================
# Phase 0: Pre-flight
# ============================================================

detect_tools

log "============================================================"
log "io_uring Metrics: $TEST_NAME"
log "============================================================"
log ""
log "Platform:  $(uname -s) $(uname -r)"
log "Tools:     perf=$HAS_PERF strace=$HAS_STRACE biosnoop=$HAS_BIOSNOOP iostat=$HAS_IOSTAT"
log "Options:   light=$LIGHT_MODE biosnoop=$BIOSNOOP_ENABLED poll=${POLL_INTERVAL}s"
if [[ -n "$TARGET_PID" ]]; then
    log "Target:    PID $TARGET_PID (pre-specified)"
else
    log "Command:   ${CMD_ARGS[*]}"
fi
log ""

# Write config for reproducibility
cat > "$RESULTS_DIR/test_config.txt" <<EOF
test_name=$TEST_NAME
date=$(date '+%Y-%m-%d %H:%M:%S')
platform=$(uname -s) $(uname -r)
command=${CMD_ARGS[*]}
target_pid=${TARGET_PID:-auto}
light_mode=$LIGHT_MODE
biosnoop=$BIOSNOOP_ENABLED
poll_interval=$POLL_INTERVAL
tools_perf=$HAS_PERF
tools_strace=$HAS_STRACE
tools_biosnoop=$HAS_BIOSNOOP
tools_iostat=$HAS_IOSTAT
EOF

# ============================================================
# Phase 1: Start non-PID monitors
# ============================================================

log "=== Phase 1: Start monitors ==="

start_iostat
start_page_cache_monitor
start_biosnoop

# ============================================================
# Phase 2: Run command and attach PID-specific monitors
# ============================================================

log ""
log "=== Phase 2: Run command ==="

if [[ -n "$TARGET_PID" ]]; then
    # Pre-specified PID: attach monitors and run command (or just wait)
    MONITOR_TARGET=$TARGET_PID
    log "Attaching to PID $MONITOR_TARGET"

    start_strace "$MONITOR_TARGET"
    start_perf_stat "$MONITOR_TARGET"
    start_context_switch_monitor "$MONITOR_TARGET" "$POLL_INTERVAL"
    start_cpu_time_monitor "$MONITOR_TARGET" "$POLL_INTERVAL"
    start_uring_ring_monitor "$MONITOR_TARGET"

    if [[ ${#CMD_ARGS[@]} -gt 0 ]]; then
        log "Running: ${CMD_ARGS[*]}"
        CMD_START_TIME=$(date +%s)
        "${CMD_ARGS[@]}" 2>&1 | tee "$RESULTS_DIR/command_output.log"
        CMD_EXIT=$?
        CMD_END_TIME=$(date +%s)
        log "Command exited with code $CMD_EXIT ($(( CMD_END_TIME - CMD_START_TIME ))s)"
    else
        log "No command given, monitoring PID $TARGET_PID until it exits..."
        while kill -0 "$TARGET_PID" 2>/dev/null; do
            sleep 1
        done
        CMD_EXIT=0
        log "PID $TARGET_PID exited"
    fi
else
    # Launch command in background, attach monitors to its PID directly.
    # strace -f follows forks, so it captures the JMH-forked child JVM too.
    # /proc monitors on the parent (ant JVM) are fine for A/B comparison
    # since both runs have identical overhead — the delta is what matters.
    log "Running: ${CMD_ARGS[*]}"
    START_EPOCH=$(date +%s)

    "${CMD_ARGS[@]}" > "$RESULTS_DIR/command_output.log" 2>&1 &
    CMD_PID=$!
    log "Command PID: $CMD_PID"

    sleep 1

    if kill -0 "$CMD_PID" 2>/dev/null; then
        MONITOR_TARGET=$CMD_PID
        log "Monitoring PID: $MONITOR_TARGET"
        start_strace "$MONITOR_TARGET"
        start_perf_stat "$MONITOR_TARGET"
        start_context_switch_monitor "$MONITOR_TARGET" "$POLL_INTERVAL"
        start_cpu_time_monitor "$MONITOR_TARGET" "$POLL_INTERVAL"
        start_uring_ring_monitor "$MONITOR_TARGET"
    else
        log "WARNING: Command exited within 1s, check $RESULTS_DIR/command_output.log"
    fi

    # Wait for command to finish
    wait "$CMD_PID" || true
    CMD_EXIT=$?
    END_EPOCH=$(date +%s)
    DURATION=$((END_EPOCH - START_EPOCH))
    log "Command exited with code $CMD_EXIT (${DURATION}s)"
fi

# ============================================================
# Phase 3: Stop monitors
# ============================================================

log ""
log "=== Phase 3: Stop monitors ==="

stop_strace
stop_perf_stat

# Kill remaining monitors (iostat, page cache, context switches, cpu time, ring stats, biosnoop)
for pid in "${MONITOR_PIDS[@]+"${MONITOR_PIDS[@]}"}"; do
    kill "$pid" 2>/dev/null && wait "$pid" 2>/dev/null || true
done
MONITOR_PIDS=()

log "All monitors stopped"

# ============================================================
# Phase 4: Summary
# ============================================================

log ""
log "=== Phase 4: Summary ==="
log ""

generate_summary "$RESULTS_DIR"
