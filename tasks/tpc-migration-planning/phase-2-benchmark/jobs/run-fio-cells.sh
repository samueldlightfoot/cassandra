#!/usr/bin/env bash
# Phase 2 Level A sweep driver — runs ON THE RIG as root under nohup.
# Per cell x 3 iterations: cache prep (cold drop / hot prime / write settle),
# per-cell env snapshot (IRQ table, governor), samplers on CPU 0, one fio
# invocation writing JSON, failure-text scan. Environment pinned for the whole
# sweep: governor=performance, irqbalance STOPPED (static steering stance,
# recorded); both restored on exit. fs arms interleaved (thermal/wear spread).
set -uo pipefail   # NOT -e: one bad cell must not kill the sweep; failures are logged

REPO=/root/repos/fork/cassandra-tpc
JOBS=$REPO/tasks/tpc-migration-planning/phase-2-benchmark/jobs/generated
RES=/data/results/uring_fio_v1
ENV=$RES/env
SAMPLER_CPU=0

fail() { echo "PREFLIGHT FAIL: $*" >&2; exit 1; }

# ---------- preflight ----------
[ "$(id -u)" = 0 ] || fail "must run as root"
[ -f "$JOBS/manifest.tsv" ] || fail "no manifest at $JOBS"
if pgrep -x java > /dev/null; then fail "java process running — rig must be quiet"; fi
pkill -f 'bpftrace|biolatency|biosnoop' 2>/dev/null
for t in fio iostat pidstat taskset smartctl dd; do
  command -v "$t" > /dev/null || fail "missing tool: $t"
done
G=$((1024*1024*1024))
for mnt in /bench-ext4 /bench-xfs; do
  [ "$(stat -c%s $mnt/uring-bench-read.dat 2>/dev/null || echo 0)" -eq $((32*G)) ] || fail "$mnt read file wrong size"
  [ "$(stat -c%s $mnt/uring-bench-seqwrite.dat 2>/dev/null || echo 0)" -eq $((32*G)) ] || fail "$mnt seqwrite file wrong size"
  [ "$(stat -c%s $mnt/uring-bench-randwrite.dat 2>/dev/null || echo 0)" -eq $((16*G)) ] || fail "$mnt randwrite file wrong size"
done
mkdir -p "$ENV"

# ---------- pin environment (restored by trap) ----------
PREV_GOV=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)
echo performance | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null
[ "$(sort -u /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor)" = performance ] || fail "governor did not take"
PREV_IRQB=$(systemctl is-active irqbalance)
systemctl stop irqbalance
restore() {
  echo "$PREV_GOV" | tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor > /dev/null
  [ "$PREV_IRQB" = active ] && systemctl start irqbalance
  echo "environment restored (governor=$PREV_GOV irqbalance=$PREV_IRQB)"
}
trap restore EXIT

# ---------- sweep-level env snapshot ----------
uname -a               > "$ENV/uname.txt"
fio --version          > "$ENV/fio-version.txt"
mount | grep bench     > "$ENV/mounts.txt"
blockdev --getra /dev/nvme0n1 > "$ENV/readahead.txt"
smartctl -a /dev/nvme0n1      > "$ENV/smart-before.txt"
cat /proc/interrupts          > "$ENV/interrupts-start.txt"
echo "governor=performance irqbalance=stopped(prev=$PREV_IRQB) sampler_cpu=$SAMPLER_CPU" > "$ENV/stance.txt"

# ---------- cell order: interleave fs arms ----------
mapfile -t E4 < <(awk -F'\t' '$2=="ext4"' "$JOBS/manifest.tsv")
mapfile -t XF < <(awk -F'\t' '$2=="xfs"'  "$JOBS/manifest.tsv")
CELLS=()
for i in "${!E4[@]}"; do CELLS+=("${E4[$i]}" "${XF[$i]}"); done
echo "sweep: ${#CELLS[@]} cells x 3 iterations, start $(date -u +%FT%TZ)"

for line in "${CELLS[@]}"; do
  IFS=$'\t' read -r id fs jf kind cache <<< "$line"
  for iter in 1 2 3; do
    out="$RES/${id}-iter${iter}"
    d="${out}.d"; mkdir -p "$d"

    case "$kind:$cache" in
      read:cold)
        sync; echo 3 > /proc/sys/vm/drop_caches; sleep 2 ;;
      read:hot)
        if [ "$iter" = 1 ]; then
          sync; echo 3 > /proc/sys/vm/drop_caches
          dd if="/bench-$fs/uring-bench-read.dat" of=/dev/null bs=1M status=none
        fi ;;
      write:*)
        sync; echo 3 > /proc/sys/vm/drop_caches; sleep 10 ;;   # writeback settle (spec §4)
    esac

    grep nvme /proc/interrupts > "$d/interrupts.txt"
    cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor > "$d/governor.txt"
    systemctl is-active irqbalance > "$d/irqbalance.txt" 2>&1

    taskset -c $SAMPLER_CPU iostat -xz 1  > "$d/iostat.log"  2>&1 & S1=$!
    taskset -c $SAMPLER_CPU pidstat -w 1  > "$d/pidstat.log" 2>&1 & S2=$!
    ( while :; do c=$(ps -eLo comm | grep -c iou-wrk); echo "$(date +%s) $c"; sleep 1; done ) \
        > "$d/iouwrk-count.log" 2>&1 & S3=$!
    ( while :; do echo -n "$(date +%s) "; ps -eLo psr,comm | awk '/iou-wrk/{print $1}' | sort -n | uniq -c | tr '\n' ' '; echo; sleep 1; done ) \
        > "$d/iouwrk-psr.log" 2>&1 & S4=$!
    ( while :; do echo -n "$(date +%s) "; awk '/^(Dirty|Writeback):/{printf "%s %s ", $1, $2}' /proc/meminfo; echo; sleep 1; done ) \
        > "$d/meminfo.log" 2>&1 & S5=$!

    if ! fio --output-format=json --output="${out}.json" "$JOBS/$jf" > "$d/fio-stdout.log" 2>&1; then
      echo "CELL FAIL $id iter$iter (fio exit nonzero)"
    fi

    kill $S1 $S2 $S3 $S4 $S5 2>/dev/null
    wait $S1 $S2 $S3 $S4 $S5 2>/dev/null
    if grep -Eiq 'error|invalid|usage|permission denied' "$d/fio-stdout.log"; then
      echo "SUSPECT OUTPUT $id iter$iter:"; grep -Ei 'error|invalid|usage|permission denied' "$d/fio-stdout.log"
    fi
    [ -s "${out}.json" ] || echo "CELL FAIL $id iter$iter (no json)"
    echo "DONE $id iter$iter $(date -u +%H:%M:%S)"
  done
done

cat /proc/interrupts     > "$ENV/interrupts-end.txt"
smartctl -a /dev/nvme0n1 > "$ENV/smart-after.txt"
echo "SWEEP COMPLETE $(date -u +%FT%TZ)"
