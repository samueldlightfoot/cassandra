#!/usr/bin/env bash
# Phase 4.1 trunk baseline capture (poc-criteria.md §4) — RATE-LADDER load model.
# The tool is open-loop rate-limited; offering "unlimited" (--rate 500000) causes a
# coordinated-omission MELTDOWN (millions of timeout errors, multi-second p99 — hurdle
# A13). Correct model: climb a rate ladder per mix, find the clean sustainable max
# (errors ~0 AND achieved ~= offered), then measure p99 at operating points below it with
# 3-iter noise bands. Deliverable = p99-vs-offered-throughput curve per mix.
set -u

ECS=/root/repos/cassandra-easy-stress/bin/cassandra-easy-stress
CDIR=/root/repos/fork/cassandra-tpc
export CASSANDRA_CONF=/data/tpc-poc/conf
OUT=/data/tpc-poc/results/baseline_v1
HOST=127.0.0.1
PROM="--prometheusport 0"
REPL="{'class':'SimpleStrategy','replication_factor':1}"
PARTS=2000000
STRESS_CPUS=8-11          # client 4 cores; Cassandra pinned 0-7 (hurdle A9)
THREADS=32
POP_RATE=200000          # populate/warmup can push hard (write-only, we don't measure them)
POP_TIMEOUT=900
LADDER="10000 20000 40000 60000 90000 130000 180000"   # offered ops/s rungs (nominal; tool delivers ~half — judge by achieved)
LADDER_DUR=60s
OVERLOAD_ERRPCT=1        # overload = >1% errors OR p99 blowup. NOT achieved<offered: the tool
P99_CEIL_MS=50          #   under-delivers vs nominal --rate (~53%) even when clean (hurdle A14). 50ms
                        #   captures the knee (write curve was <1ms to 60k offered, 178ms at 90k).
REF_FRACS="50 80"        # operating points as % of clean_max
REF_ITERS=3
REF_DUR=90s
MIXES="w:0.0 rw:0.5 r:0.9"

mkdir -p "$OUT"; LOG="$OUT/sweep.log"
say(){ echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$LOG"; }
nt(){ "$CDIR"/bin/nodetool "$@" 2>/dev/null; }

# parse a finished cell's stress.stdout -> "achieved_ops errpct wp99 rp99"
# NB: rows are pipe-separated column GROUPS -> "W.cnt W.p99 W.1m | R.cnt R.p99 R.1m | D... | E.cnt E.1m"
# so with whitespace FS the '|' are their OWN fields: writes=$1,$2 reads=$5,$6 deletes=$9 errors=$13 (hurdle A15).
parse_cell(){ # $1=dir  $2=dur_seconds
  local f="$1/stress.stdout" secs="$2"
  awk -v secs="$secs" '/^[[:space:]]+[0-9]/{w=$1;wp=$2;r=$5;rp=$6;e=$13} END{
    if(w=="") {print "0 100 0 0"; exit}
    tot=w+r; errpct=(tot+e>0)?100*e/(tot+e):0;
    printf "%d %.2f %s %s\n", tot/secs, errpct, wp, rp }' "$f"
}

env_snap(){ local d="$1"; mkdir -p "$d"
  cat /proc/interrupts > "$d/interrupts.txt" 2>/dev/null
  cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor > "$d/governor.txt" 2>/dev/null
  systemctl is-active irqbalance > "$d/irqbalance.txt" 2>/dev/null; }

run_cell(){ # $1=label $2=readrate $3=rate $4=dur   -> writes stress.stdout etc; echoes parse_cell
  local label="$1" rr="$2" rate="$3" dur="$4"; local d="$OUT/$label"; mkdir -p "$d"; env_snap "$d"
  mpstat -P 0,1,2,8,9,10,11 5 > "$d/mpstat.log" 2>&1 & local MP=$!
  taskset -c "$STRESS_CPUS" "$ECS" run KeyValue --host "$HOST" --no-schema $PROM \
    --readrate "$rr" --partitions "$PARTS" --threads "$THREADS" --rate "$rate" --duration "$dur" \
    --hdr "$d/lat.hdr" --csv-latency "$d/lat_win.csv" > "$d/stress.stdout" 2>&1
  kill "$MP" 2>/dev/null
  nt proxyhistograms > "$d/proxyhistograms.txt" 2>/dev/null
  nt tpstats > "$d/tpstats.txt" 2>/dev/null
  parse_cell "$d" "${dur%s}"
}

# ---------- PREFLIGHT ----------
say "=== PREFLIGHT ==="
pkill -9 -f "easy-stress-.*-all[.]jar" 2>/dev/null; pkill -9 -f "[G]radleDaemon" 2>/dev/null; sleep 2
ss -ltn 2>/dev/null | grep -q ':9500 ' && { say "FATAL: :9500 bound"; exit 2; }
nt info >/dev/null 2>&1 || { say "FATAL: node not answering"; exit 2; }
say "  clean slate; governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor) irqbalance=$(systemctl is-active irqbalance)"
nt info | grep -iE 'Heap Memory|Uptime' | sed 's/^/  /' | tee -a "$LOG"

# ---------- POPULATE ----------
PER_THREAD=$(( PARTS / THREADS ))
say "=== POPULATE ($PARTS = $PER_THREAD/thread x $THREADS) ==="
timeout "$POP_TIMEOUT" taskset -c "$STRESS_CPUS" "$ECS" run KeyValue --host "$HOST" $PROM \
  --replication "$REPL" --populate "$PER_THREAD" --readrate 0.0 --partitions "$PARTS" \
  --threads "$THREADS" --rate "$POP_RATE" --duration 1s --drop > "$OUT/populate.stdout" 2>&1
grep -qiE "Address already in use|Exception in thread|Could not reach" "$OUT/populate.stdout" && { say "FATAL: populate errored"; tail -3 "$OUT/populate.stdout"|tee -a "$LOG"; exit 5; }
nt flush cassandra_easy_stress 2>/dev/null
say "  populated; $(nt tablestats cassandra_easy_stress | grep -m1 'Space used (live)' | tr -d '\t')"

# ---------- WARMUP (discarded) ----------
say "=== WARMUP (90s @ 40k, discarded) ==="
taskset -c "$STRESS_CPUS" "$ECS" run KeyValue --host "$HOST" --no-schema $PROM \
  --readrate 0.5 --partitions "$PARTS" --threads "$THREADS" --rate 40000 --duration 90s > "$OUT/warmup.stdout" 2>&1
say "  warmup done"

# ---------- PHASE A: rate-ladder discovery (find clean_max per mix) ----------
say "=== PHASE A: RATE LADDER (find clean sustainable max/mix) ==="
declare -A CMAX_OFF CMAX_ACH
for m in $MIXES; do
  name="${m%%:*}"; rr="${m##*:}"; CMAX_OFF[$name]=0; CMAX_ACH[$name]=0
  for rate in $LADDER; do
    read ach errpct wp99 rp99 < <(run_cell "lad-$name-r$rate" "$rr" "$rate" "$LADDER_DUR")
    over=$(awk -v e="$errpct" -v wp="$wp99" -v rp="$rp99" -v ec="$OVERLOAD_ERRPCT" -v pc="$P99_CEIL_MS" 'BEGIN{print (e>ec || wp+0>pc || rp+0>pc)?1:0}')
    say "  lad $name off=${rate} ach=${ach}/s err=${errpct}% wp99=${wp99}ms rp99=${rp99}ms $([ "$over" = 1 ] && echo OVERLOAD || echo ok)"
    [ "$over" = 1 ] && break
    CMAX_OFF[$name]=$rate; CMAX_ACH[$name]=$ach
  done
  say "  clean_max[$name]: offered=${CMAX_OFF[$name]} achieved=${CMAX_ACH[$name]} ops/s"
  [ "${CMAX_OFF[$name]}" -lt 1 ] && { say "FATAL: no clean rung for $name — even the lowest rung overloaded (broken run)"; exit 6; }
done

# ---------- PHASE B: measurement at operating points (3 iters, noise band) ----------
say "=== PHASE B: OPERATING POINTS (${REF_FRACS}% of clean_max, ${REF_ITERS} iters) ==="
for m in $MIXES; do
  name="${m%%:*}"; rr="${m##*:}"
  for frac in $REF_FRACS; do
    rate=$(( CMAX_OFF[$name] * frac / 100 ))
    for it in $(seq 1 $REF_ITERS); do
      read ach errpct wp99 rp99 < <(run_cell "ref-$name-f$frac-i$it" "$rr" "$rate" "$REF_DUR")
      say "  ref $name f${frac}%(off=${rate}) i$it: ach=${ach}/s err=${errpct}% wp99=${wp99}ms rp99=${rp99}ms"
    done
  done
done

say "=== DONE ==="; touch "$OUT/SWEEP_COMPLETE"
