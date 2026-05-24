> **SUPERSEDED.** This script set was an initial bash version of the comparison
> driver. The investigation has been re-implemented as an `Investigation` on
> top of `cassandra-agent-harness`, in the new repo
> https://github.com/samueldlightfoot/gdt-poc-harness. Use `gdt-poc run`
> instead of `run_comparison.sh`. Kept here for reference / fallback only.

# GDT-aware UCS comparison scripts (superseded — see note above)

Three artifacts that together drive the Phase 3–7 measurement on the rig.

## On the rig — one-time setup

```bash
# Sync this branch onto the rig
cd ~/repos/fork/cassandra
git fetch origin && git checkout fdp-poc && git pull

# Build Cassandra with the GDT changes
ant -q build

# Confirm easy-cass-stress is on PATH or note its full path
ls ~/repos/easy-cass-stress/bin/easy-cass-stress
```

## Per run

```bash
.claude/tasks/gdt-ucs/scripts/run_comparison.sh \
    --cassandra-home   ~/repos/fork/cassandra \
    --easy-stress      ~/repos/easy-cass-stress/bin/easy-cass-stress \
    --data-dir         /var/lib/gdt-ucs/data \
    --commitlog-dir    /var/lib/gdt-ucs/commitlog \
    --results-dir      ~/runs/gdt-ucs \
    --workload         BasicTimeSeries \
    --duration         30m \
    --partitions       5000000 \
    --threads          64
```

Runs all 3 conditions (baseline → gdt → twcs) sequentially. ~1.5–2 hours
total for the defaults above (30m per condition + 5–10m setup/teardown).

The script is **not idempotent** — each invocation creates a fresh
`<run-uuid>` directory. Stop a partial run with Ctrl-C; restart fresh.

## After

```bash
.claude/tasks/gdt-ucs/scripts/compute_metrics.py ~/runs/gdt-ucs/<run-uuid>
```

Writes `summary.json` + `summary.md` into the run dir and prints the
markdown to stdout — the headline table ready for the blog post.

## Artifacts per condition

```
<run-uuid>/<condition>/
├── conf/                          # rendered cassandra.yaml + jvm-server.options
├── cassandra.log                  # full stdout/stderr from the Cassandra JVM
├── stress.log                     # easy-cass-stress stdout
├── stress.parquet                 # per-operation latencies (for plot generation)
├── pre.json                       # nodetool snapshot at run start
├── post.json                      # nodetool snapshot at run end
├── nodetool-compactionstats.txt   # final compaction queue state
└── condition.json                 # workload spec + compaction strategy used
```

## Honest gaps (PoC tradeoffs)

- `measure_db_waf.sh` uses `nodetool info` for `bytes_compacted`. If your
  Cassandra version doesn't expose that field, it falls back to 0 and the
  WAF estimate is then driven by `bytes_disk_used` deltas only.
- `compute_metrics.py` estimates `user_bytes_estimated` as
  `total_operations × 200B`. Replace with a precise sum from the parquet
  if the estimate-vs-actual gap matters for the blog write-up.
- No automatic re-run on failure — one condition failing won't stop the
  others, but it will produce an empty `summary` for that condition.
