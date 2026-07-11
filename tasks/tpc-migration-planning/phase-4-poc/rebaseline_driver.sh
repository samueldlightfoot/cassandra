#!/usr/bin/env bash
# Phase 4.1 RE-BASELINE driver v2 — LATENCY-DEFINED SATURATION (--maxrlat/--maxwlat).
#
# Corrected model (agreed w/ operator): raw --rate 2M busy-spins the client rate limiter
# (client cores peg at 100% delivering ~25k, latency = coordinated-omission noise). Instead
# use the tool's RateLimiterOptimizer: it targets client p99 (read/write) and only raises the
# offered rate when utilization >=90% of the current limit -> it converges to the max
# sustainable throughput at a latency SLO WITHOUT overloading. Optimizer ticks every 5s
# (10s delay), step-ramps then fine-tunes <=5%/step, so it needs ~2-3 min to converge.
#
# Per mix:
#   Phase S (saturation): P_S clean streams, each auto-rate to SLO_MS p99 -> MAX[mix] at the
#     SLO. Measure server-side throughput (nodetool count deltas) + proxyhistograms (authoritative
#     server latency) + CPU over the converged tail. Flag if client-bound.
#   Phase R (reference curve): fixed offered rate = {40,60,80,95}% of MAX split across P_S streams
#     (offered<=deliverable => no busy-spin), 3 iters -> p99-vs-throughput gate operating points.
set -u

ECS=/root/repos/cassandra-easy-stress/bin/cassandra-easy-stress
CDIR=/root/repos/fork/cassandra-tpc
export CASSANDRA_CONF=/data/tpc-poc/conf
OUT=/data/tpc-poc/results/rebaseline
HOST=127.0.0.1
KS=cassandra_easy_stress
REPL="{'class':'SimpleStrategy','replication_factor':1}"
PARTS=2000000
THREADS=32
QUEUE=2000000
GCLOG="$CDIR/logs/gc.log"

CLIENT_CPUS="8-11"
CASS_MP="0,1,2,3,4,5,6,7"
CLIENT_MP="8,9,10,11"

# --- load / SLO model ---
SLO_MS=100               # latency SLO: saturation == client p99 reaches this (operator's 100ms)
MIXES="w:0.0 rw:0.5 r:0.9"
P_S=3                    # clean streams stacked for saturation + reference
INIT_RATE=60000          # optimizer starting ceiling per stream (step-ramps here in ~55s, then climbs/cuts)
S_SETTLE=250; S_MEAS=100                 # saturation cell: converge ~200s, measure final ~100s
R_FRACS="40 60 80 95"    # reference operating points as % of MAX
R_ITERS=3
# NB: optimizer step-ramps rate/10 -> rate over ~55s even with no latency flags, so settle PAST it.
R_SETTLE=65; R_MEAS=45   # reference cell timing (fixed rate == converged plateau after ramp)
CLIENT_BOUND_CPU=93      # if client fence >= this AND p99 well under SLO -> flag client-limited

mkdir -p "$OUT"
LOG="$OUT/sweep.log"
TSV="$OUT/results.tsv"
say(){ echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$LOG"; }
nt(){ "$CDIR"/bin/nodetool "$@" 2>/dev/null; }
lwc(){ nt tablestats "$KS" | awk '/Local write count/{print $NF; exit}'; }
lrc(){ nt tablestats "$KS" | awk '/Local read count/{print $NF; exit}'; }
cpu_busy(){ awk '/^Average:[[:space:]]+[0-9]/{s+=100-$NF; n++} END{if(n>0)printf "%.1f", s/n; else print "0"}' "$1"; }
ph_val(){ awk -v pc="$1" -v col="$2" '$1==pc{print $col; exit}' "$3"; }

if [ ! -f "$TSV" ]; then
  echo -e "phase\tmix\tP\tper_rate\tflags\titer\tel\tsrv_tot\tsrv_w\tsrv_r\tcass_cpu\tcli_cpu\tmutA\tmutP\trdA\trdP\tph_wp95\tph_wp99\tph_wmax\tph_rp95\tph_rp99\tph_rmax\tcli_err\tgc_n\tgc_maxms" > "$TSV"
fi

# run one cell: P procs with RUNFLAGS; settle; measure real window; emit TSV row; set LAST_*
# args: phase label readrate P per_rate RUNFLAGS settle meas iter
run_cell(){
  local phase="$1" label="$2" rr="$3" P="$4" per_rate="$5" flags="$6" settle="$7" meas="$8" iter="$9"
  local d="$OUT/$label"; mkdir -p "$d"; local i pids=()
  for i in $(seq 1 "$P"); do
    local hdr=""; [ "$i" = 1 ] && hdr="--hdr $d/lat.hdr"
    taskset -c "$CLIENT_CPUS" "$ECS" run KeyValue --host "$HOST" --no-schema --prometheusport 0 \
      --readrate "$rr" --partitions "$PARTS" --threads "$THREADS" --queue "$QUEUE" \
      $flags --duration "$(( settle + meas + 10 ))s" $hdr > "$d/p$i.stdout" 2>&1 &
    pids+=($!)
  done
  sleep "$settle"
  local Bw Br Aw Ar gcB gcA t0 t1 el
  Bw=$(lwc); Br=$(lrc); t0=$(date +%s)
  gcB=$(wc -l < "$GCLOG" 2>/dev/null || echo 0)
  mpstat -P "$CASS_MP" 1 "$meas"   > "$d/mpstat_cass.log" 2>&1 &   local mpc=$!
  mpstat -P "$CLIENT_MP" 1 "$meas" > "$d/mpstat_cli.log"  2>&1 &   local mpl=$!
  : > "$d/stages.log"
  local s n=$(( meas/5 )); [ "$n" -lt 1 ] && n=1
  for s in $(seq 1 "$n"); do nt tpstats | grep -E '^(Mutation|Read)Stage' >> "$d/stages.log"; sleep 5; done
  wait "$mpc" "$mpl" 2>/dev/null
  Aw=$(lwc); Ar=$(lrc); t1=$(date +%s); el=$((t1-t0)); [ "$el" -lt 1 ] && el=1
  nt proxyhistograms > "$d/proxyhistograms.txt" 2>/dev/null
  gcA=$(wc -l < "$GCLOG" 2>/dev/null || echo 0)
  wait "${pids[@]}" 2>/dev/null

  local srv_w=$(( (Aw-Bw)/el )) srv_r=$(( (Ar-Br)/el )); local srv_tot=$(( srv_w + srv_r ))
  local cass_cpu cli_cpu
  cass_cpu=$(cpu_busy "$d/mpstat_cass.log"); cli_cpu=$(cpu_busy "$d/mpstat_cli.log")
  local mutA mutP rdA rdP
  mutA=$(awk '/^MutationStage/{if($2>m)m=$2}END{print m+0}' "$d/stages.log")
  mutP=$(awk '/^MutationStage/{if($3>m)m=$3}END{print m+0}' "$d/stages.log")
  rdA=$(awk '/^ReadStage/{if($2>m)m=$2}END{print m+0}' "$d/stages.log")
  rdP=$(awk '/^ReadStage/{if($3>m)m=$3}END{print m+0}' "$d/stages.log")
  local ph_wp95 ph_wp99 ph_wmax ph_rp95 ph_rp99 ph_rmax
  ph_wp95=$(ph_val 95% 3 "$d/proxyhistograms.txt"); ph_wp99=$(ph_val 99% 3 "$d/proxyhistograms.txt"); ph_wmax=$(ph_val Max 3 "$d/proxyhistograms.txt")
  ph_rp95=$(ph_val 95% 2 "$d/proxyhistograms.txt"); ph_rp99=$(ph_val 99% 2 "$d/proxyhistograms.txt"); ph_rmax=$(ph_val Max 2 "$d/proxyhistograms.txt")
  local cli_err=0 e
  for i in $(seq 1 "$P"); do
    e=$(awk '/^[[:space:]]+[0-9]/{e=$13} END{print e+0}' "$d/p$i.stdout" 2>/dev/null)
    cli_err=$(( cli_err + ${e:-0} ))
  done
  local gc_n=0 gc_maxms=0
  if [ "$gcA" -gt "$gcB" ]; then
    sed -n "$((gcB+1)),${gcA}p" "$GCLOG" 2>/dev/null | grep -oE 'Pause [A-Za-z ]*[0-9.]+ms' > "$d/gc_win.txt" 2>/dev/null || true
    gc_n=$(wc -l < "$d/gc_win.txt" 2>/dev/null || echo 0)
    gc_maxms=$(grep -oE '[0-9.]+ms' "$d/gc_win.txt" 2>/dev/null | tr -d 'ms' | sort -gr | head -1); [ -z "$gc_maxms" ] && gc_maxms=0
  fi
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$phase" "$mix_name" "$P" "$per_rate" "$flags" "$iter" "$el" "$srv_tot" "$srv_w" "$srv_r" "$cass_cpu" "$cli_cpu" \
    "$mutA" "$mutP" "$rdA" "$rdP" "$ph_wp95" "$ph_wp99" "$ph_wmax" "$ph_rp95" "$ph_rp99" "$ph_rmax" "$cli_err" "$gc_n" "$gc_maxms" >> "$TSV"
  say "  [$phase] $label i$iter: srv=${srv_tot}/s (w${srv_w}/r${srv_r}) cassCPU=${cass_cpu}% cliCPU=${cli_cpu}% ph_wp99=${ph_wp99}us ph_rp99=${ph_rp99}us mutP=${mutP} rdP=${rdP} err=${cli_err} gc=${gc_n}/${gc_maxms}ms"
  LAST_TOT=$srv_tot; LAST_CASS=$cass_cpu; LAST_CLI=$cli_cpu; LAST_ERR=$cli_err; LAST_RP99=$ph_rp99; LAST_WP99=$ph_wp99
}

maxflags(){ # $1=mix -> echo the --maxr/wlat flags for this mix
  case "$1" in
    w)  echo "--maxwlat $SLO_MS" ;;
    rw) echo "--maxrlat $SLO_MS --maxwlat $SLO_MS" ;;
    r)  echo "--maxrlat $SLO_MS --maxwlat $SLO_MS" ;;
  esac
}

# ================= PREFLIGHT =================
say "===== RE-BASELINE v2 START ($(uptime -p)) SLO=${SLO_MS}ms P_S=$P_S ====="
pkill -9 -f "easy-stress-.*-all[.]jar" 2>/dev/null; pkill -9 -f "[G]radleDaemon" 2>/dev/null; sleep 2
ss -ltn 2>/dev/null | grep -q ':9500 ' && { say "FATAL: :9500 bound"; exit 2; }
nt info >/dev/null 2>&1 || { say "FATAL: node not answering"; exit 2; }
[ "$(nt sjk mxdump 2>/dev/null | grep -c 'type=TrieMemtable')" -gt 0 ] || say "WARN: TrieMemtable MBean not found"
say "  clean slate; governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor); $(nt info | grep -iE 'Uptime' | tr -d '\t')"

# ================= FRESH POPULATE =================
PER_THREAD=$(( PARTS / THREADS ))
say "===== FRESH POPULATE (drop + $PARTS parts = $PER_THREAD/thread x $THREADS) ====="
taskset -c "$CLIENT_CPUS" "$ECS" run KeyValue --host "$HOST" --prometheusport 0 \
  --replication "$REPL" --populate "$PER_THREAD" --readrate 0.0 --partitions "$PARTS" \
  --threads "$THREADS" --rate 200000 --queue "$QUEUE" --duration 1s --drop \
  > "$OUT/populate.stdout" 2>&1
grep -qiE "Address already in use|Exception in thread|Could not reach|Usage:" "$OUT/populate.stdout" \
  && { say "FATAL: populate errored"; tail -5 "$OUT/populate.stdout" | tee -a "$LOG"; exit 5; }
nt flush "$KS"
say "  flushed; draining compactions..."
for _ in $(seq 1 180); do
  pend=$(nt compactionstats | awk '/pending tasks:/{print $NF; exit}')
  [ "${pend:-0}" = 0 ] && break; sleep 5
done
say "  populate done: $(nt tablestats "$KS" | grep -m1 'Space used (live)' | tr -d '\t'); Lwc=$(lwc) Lrc=$(lrc)"

# ================= PER-MIX: SATURATION then REFERENCE =================
declare -A MAX_TOT
for m in $MIXES; do
  mix_name="${m%%:*}"; rr="${m##*:}"; mf=$(maxflags "$mix_name")
  say "===== MIX $mix_name (readrate=$rr) — PHASE S (auto-rate to ${SLO_MS}ms) ====="
  run_cell S "$mix_name-S" "$rr" "$P_S" "auto" "--rate $INIT_RATE $mf" "$S_SETTLE" "$S_MEAS" 1
  MAX_TOT[$mix_name]=$LAST_TOT
  ci=$(printf "%.0f" "$LAST_CLI"); pr=$(printf "%.0f" "$LAST_RP99"); pw=$(printf "%.0f" "$LAST_WP99")
  # p99 in us; SLO in ms -> compare us to SLO*1000*0.6 (well-under => client-bound suspicion)
  underslo=$(awk -v r="$LAST_RP99" -v w="$LAST_WP99" -v s="$SLO_MS" 'BEGIN{th=s*1000*0.6; print ((r+0<th)&&(w+0<th))?1:0}')
  if [ "$ci" -ge "$CLIENT_BOUND_CPU" ] && [ "$underslo" = 1 ]; then
    say "  >>> WARN MIX $mix_name likely CLIENT-BOUND (cliCPU=${LAST_CLI}%, ph p99 r=${LAST_RP99}us w=${LAST_WP99}us << ${SLO_MS}ms). MAX=${MAX_TOT[$mix_name]}/s is a client-limited ceiling; off-box gen may be needed."
  else
    say "  >>> MAX[$mix_name] = ${MAX_TOT[$mix_name]}/s at client p99~${SLO_MS}ms (cassCPU=${LAST_CASS}% cliCPU=${LAST_CLI}% ph_rp99=${LAST_RP99}us ph_wp99=${LAST_WP99}us)"
  fi

  say "===== MIX $mix_name — PHASE R (reference curve, ${R_ITERS} iters @ ${R_FRACS}% of MAX) ====="
  MX=${MAX_TOT[$mix_name]}
  for frac in $R_FRACS; do
    per=$(( MX * frac / 100 / P_S )); [ "$per" -lt 1000 ] && per=1000
    for it in $(seq 1 "$R_ITERS"); do
      run_cell R "$mix_name-R-f$frac-i$it" "$rr" "$P_S" "$per" "--rate $per" "$R_SETTLE" "$R_MEAS" "$it"
    done
    say "  [R] $mix_name f${frac}% (per_rate=$per x$P_S): last srv=${LAST_TOT}/s ph_rp99=${LAST_RP99}us ph_wp99=${LAST_WP99}us err=${LAST_ERR}"
  done
done

say "===== SWEEP COMPLETE ====="; touch "$OUT/SWEEP_COMPLETE"
