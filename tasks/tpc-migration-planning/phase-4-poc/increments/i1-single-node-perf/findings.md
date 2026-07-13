# Findings — I1 single-node A/B (shard-routed mutation apply)

## Bottom line

On a **valid** single-node RF=1 write-only A/B (identical datasets, autocompaction
off, fresh JVM per arm), enabling `cassandra.mutation.shard_routing` costs **+28.5 pp
CPU (58.5% → 87%) and 10× median write latency (p50 14 µs → 149 µs) at matched
throughput (178.8k ops/s)**. Root-caused via async-profiler to a **synchronous
coordinator↔shard park/unpark rendezvous per write**. This is not a bug — it is the
mechanism working as designed — but single-node RF=1 exposes a cost the design (RF≥3
network waits + I5 ingress routing) is meant to hide. **The go/no-go decision belongs
to the multi-node RF=3 test, not this one.**

## The measurement (why it's valid this time)

Two earlier attempts were invalid — the dataset grew unbounded during the run because
easy-cass-stress `run` ignores `--partitions` (wrote 50.9M partitions / 14 GB with
`--partitions 2000000`), so compaction tax aliased onto arm order. Fixed by exploiting
the write-only nature: **TRUNCATE to empty before every cell** (identical start, equal
growth — tripwire: both arms start 1 MB, end 4756/4757 MB) + **disableautocompaction**
during the window (isolates the write-apply path from compaction noise) + per-cell node
restart (fresh JVM, empty proxyhistograms). See `../../../` methodology docs and
agent-common `benchmarking/methodology.md` §2/§8, `cassandra/stress-tooling.md` §3.

| arm | achieved | srv p50 | p95 | p99 | rig CPU | %usr | %sys |
|---|---|---|---|---|---|---|---|
| off | 178.8k | 14 µs | 310 µs | 642 µs | 58.5% | 43.9 | 10.2 |
| i1  | 178.8k | 149 µs | 642 µs | 1597 µs | 87.1% | 59.0 | 23.3 |

Context switches (vmstat, i1 cell): **~620k/s ≈ 3.5 per op**; runnable threads spike to
28–40 on 12 cores.

## Root cause (async-profiler, both arms profiled identically)

The apply moves off the coordinator thread onto a per-core `Shard-N` executor. The
coordinator then **parks blocked on the write-response handler** waiting for that apply
to ack; the shard thread applies the mutation and **signals/unparks the coordinator** —
a futex round-trip per write. OFF applies inline on the coordinator thread, so the
handler is signalled same-thread with no park.

Confirmed by the hottest i1 stack:
```
ShardExecutors lambda → StorageProxy$LocalMutationRunnable.run   (shard thread applies)
  → WriteResponseHandler.onResponse → AbstractWriteResponseHandler.signal
  → Condition.signalAll → LockSupport.unpark → pthread_cond_signal → futex_wake_[k]
```

CPU attribution, OFF → I1 (share of total on-CPU samples; i1 total ≈ 2× off):

| CPU in… | OFF | I1 | what it is |
|---|---|---|---|
| `ShardExecutors` dispatch subtree | 0.07% | 22.9% | the routing + task machinery |
| `WaitQueue`/`Awaitable`/`Condition.signal` | 1.5% | 16.5% | coordinator↔shard signalling (**+usr half**) |
| `futex` + `unpark`/`cond_signal` | ~5% | ~19% | kernel wakeup (**+sys half**) |
| `getThreadLocalMetrics`/histogram/`toLowerCase` | small | ~6% | per-task metrics on shard tasks |

Every number reconciles: +3.4 cores / 178k ≈ 19 µs-CPU/op for a 2-thread rendezvous +
condition machinery; +135 µs p50 = park-wait behind a ~40-deep runqueue.

## Why single-node RF=1 amplifies it (and RF≥3 should not — HYPOTHESIS)

- **RF=1 single-node**: the coordinator parks *solely* to wait for the local shard apply
  → the rendezvous is pure added cost on every write.
- **RF≥3 (CL=QUORUM)**: the coordinator already parks waiting for *remote* replica acks;
  the local shard's signal arrives during that unavoidable network wait, so the
  rendezvous is largely hidden. Additionally, replica-side applies arrive via messaging
  where **I5 (ShardInboundRouter) routes at ingress** so the work runs on the shard
  thread end-to-end (no coordinator round-trip). I5 is **inert single-node** (no inbound
  mutation messages), so none of this engages here.

This is mechanistic inference from the stack, **not proven** — it is exactly what the
multi-node RF=3 gate must measure.

## Verdict / next

- **Not a regression to "fix" and not a blocker** — it's the cost side of TPC laid bare
  by a single-node harness that exercises none of the benefit (I5 inert, no RF≥3 wait to
  amortize against, no memtable-lock contention at 2M random partitions).
- **Do not headline the single-node −28 pp as the mechanism's cost** — it is an RF=1
  artifact of the coordinator-local write path.
- **Open question worth a follow-up** (cheap, single-node): would a spin-before-park or
  queue-draining/batched shard executor collapse the wakeup storm (the ~19% futex/unpark)
  even single-node? The dispatch policy, not shard routing per se, is what generates
  620k cs/s.
- **Decision gate remains the multi-node RF=3 run** (off / I1 / I1+I5), judged on tail at
  matched throughput.

## Repro / method

- Harness: `/root/orchestrator_tight.sh` on rig 157.180.98.112 (build
  `/root/repos/fork/cassandra-tpc-i1`, conf `/data/tpc-poc/conf`).
- Profiles: `asprof start -e cpu -i 1000000 <pid>` / `sleep 20` / `asprof stop -o
  collapsed -f <out> <pid>`, at `perf_event_paranoid=1`, on a steady 200k write load.
  Collapsed captures saved in session scratchpad (`prof_i1_cpu.txt`, `prof_off_cpu.txt`).
- A/B attribution method documented in agent-common `tools/async-profiler.md`.
