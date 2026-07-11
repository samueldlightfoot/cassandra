# STRESS RUNBOOK — cassandra-easy-stress (THE source of truth for driving load)

One page. Read before any load test. Mechanism/evidence: `stress-tool-behaviour.md`.
**2026-07-10 major correction:** the old "single process caps ~100k, stack multiple
processes" model was WRONG — it was the **`--concurrency` default of 100**. One process with
`--concurrency` raised saturates Cassandra. Multi-process is NOT needed. Details below.

---

## ⛔ THE TWO RULES

1. **`--concurrency` is THE throughput knob.** It sizes the in-flight-request semaphore
   (per WorkloadRunner). **Default is 100** — that alone caps a single process at ~100k ops/s
   regardless of `--rate`/`--threads`/box size (Little's law: in-flight ÷ latency). Raise it
   to **2000–4000** and one process drives Cassandra to its knee.
2. **Never pass `--rate` together with `--maxrlat`/`--maxwlat`.** `--rate` is the RateLimiter's
   target throughput; `--maxwlat` is an optimizer that *controls* that throughput to a latency
   SLO. Passing both = hand-driving the thing you're testing. For a fixed operating point use
   `--rate` alone; for latency-governed saturation use `--maxwlat` alone. (`--maxwlat` governs
   the CLIENT's CO-corrected p99, not Cassandra's — see stress-tool-behaviour.md §5.)

---

## ✅ THE method — one process, driven by rate + concurrency (measure server-side)

```
taskset -c 0-15 cassandra-easy-stress run KeyValue \
  --host <cassandra-ip> --no-schema --prometheusport 0 \
  --readrate <0.0|0.5|0.9> \
  --rate <target> --concurrency 3000 --threads 32 --queue 2000000 \
  --duration 80s
```
- **To drive Cassandra to its knee:** `--concurrency 3000` (or 4000), `--rate` a bit above the
  deliverable knee. One process is enough (measured: 253k w/s @ Cassandra 97%, load box 41%).
- **For a clean sub-knee operating point:** set `--rate` **at/below** the deliverable knee so
  delivered ≈ offered with ~0 coordinated-omission drops (see the CO caveat below).
- Result = converged **server-side** throughput (`nodetool tablestats` count delta) +
  latency (see "Measuring latency"). Measure at steady state (skip ~20–30 s warmup).

### Measured concurrency effect (single process, off-box, write-only) [2026-07-10]
| config | delivered | Cassandra CPU | load-box CPU | note |
|---|---:|---:|---:|---|
| `--concurrency 100` (default) | ~100k | ~30% | low | the old false "ceiling" |
| `--rate 300000 --concurrency 3000` | **253k** | **97%** | 41% | saturates Cassandra, 1 proc |
| `--rate 500000 --concurrency 4000` | 260k | 94% | 53% | saturates; more CO drops |

## ⛔ NEVER
- Leave `--concurrency` at the default (100) and conclude the tool/box "can't go faster."
- **Stack multiple processes to get throughput** — unnecessary; raise `--concurrency` instead.
  (Old guidance in `stress-tool-behaviour.md` §4/§8 and `REBASELINE-HANDOFF.md` is RETRACTED.)
- `--rate 2000000` or any rate ≫ deliverable → coordinated-omission queue-drop storm.
- Combine `--rate` with `--maxwlat`/`--maxrlat`. Trust `rc=0` or client latency under overload.

## ⚠️ Coordinated-omission (CO) drops — read the `Errors` column
When `--rate` > deliverable, the per-thread generator queue fills and ops are **dropped**
(counted as `Errors`, ~thousands/s) — these are client-side CO drops, NOT Cassandra failures.
E.g. `--rate 300k` when the knee is ~253k → ~800k drops over the run. Two clean regimes:
- **Sub-knee operating point:** `--rate` ≤ knee → delivered ≈ offered, ~0 errors. Use for gate points.
- **Knee-probing:** accept some drops; read Cassandra CPU (≈90–97%) + server delivered as the
  saturation signal, not the client's offered rate.

## 📏 Measuring latency (server-side is the authority — but pick the right read)
- **Throughput:** `nodetool tablestats <ks>` Local write/read count delta ÷ elapsed. Exact per window.
- **Server-side latency:** `nodetool proxyhistograms` (coordinator R/W, µs) OR the JMX timer
  `org.apache.cassandra.metrics:type=ClientRequest,scope=Write,name=Latency` via
  `nodetool sjk mxdump -q '<mbean>'` (gives Mean, 99th, 999th, **Count**).
  ⚠️ **Both use a DECAYING reservoir** — percentiles bleed between back-to-back rungs and are
  bucketed (…1109/1331/1597/1916/2299/2759/3311/3973/4768… µs). For clean per-rung p99:
  **40 s idle-settle before the rung + read at steady state** (see `clat.sh`).
- **Client-side latency (`--hdr`):** full-resolution, per-run (no decay), CO-corrected
  end-to-end. Off-box on Hetzner-internal (<1 ms), client p99 ≈ server service time + tiny
  constant below the knee — a good clean cross-check. Writes `<prefix>-{mutations,reads,deletes}.txt` (ms).
- Saturation signal = **Cassandra CPU** (mpstat, all cores) + errors climbing. CPU% is the
  most reliable saturation marker (throughput reads vary with measurement window).

## 🔧 Tool internals that matter [source: Run.kt / WorkloadRunner.kt]
- `--concurrency` (default 100) → per-thread in-flight semaphore. **The throughput lever.**
- Connections: **8** per node (hardcoded `maxConnections=8`), 32768 max-requests/connection —
  not the bottleneck.
- `--threads` (default 1) → N independent generator threads, each own RateLimiter-fed queue.
- `--rate` (default 5000) → Guava RateLimiter target (shared per process).
- `--prometheusport 0` MANDATORY when scripting (else :9500 collides → silent rc=0 no-work).
- `--populate N` is PER-THREAD (total = N × threads). Tool exits rc=0 even after exceptions.

## 🧭 Rig facts (off-box setup, 2026-07-10)
- Cassandra: `157.180.98.112`, all **6 physical cores** (pinned 0-11), `CASSANDRA_CONF=/data/tpc-poc/conf`.
  Native bound `0.0.0.0:9042`, firewall allows the load box only. Fresh 2M dataset before each curve.
- Load box: **CCX43** `62.238.35.142` (16 vCPU EPYC, 8 phys cores), key auth, jar at `/opt/ces/`,
  drivers `/opt/ces/{drive,sp}.sh`. **Bills hourly — delete when done.**
- One CCX43 process saturates the 6-core Cassandra with headroom → the off-box premise holds,
  and (now) without stacking processes.

### Trunk saturation knees (server-side, 2026-07-10)
write-only ~253k @ 97% (1 proc, cc3000) · balanced(0.5) ~180–200k @ ~100% · read-heavy(0.9)
~115–123k @ ~100%. Full curve: `offbox-baseline-results.md`.
