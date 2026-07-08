# Explorer report — Rig + harness facts (2026-07-05)

Richest source: `/Users/samlightfoot/repos/direct_io/docs/runbook.md` (the DIO compaction
runbook — authoritative rig record; the ONLY runbook.md on the machine at the time).
Superseded in part by `../runbook.md` (this task's runbook, live rig capture 2026-07-05).

## A. Rig facts (from direct_io runbook; live-verified values in ../runbook.md)

- 157.180.98.112 (IPv6 2a01:4f9:3090:2ed3::2); `sshpass -p 'uum5BURBX7q_Nc' ssh root@...`
  (runbook :5-15); alias `box` interactive-only (:9). Replaced decommissioned 65.108.227.158
  ("fdp-poc / WAF benchmark box", :29-31) — **do not reuse old-rig throughput numbers**
  (gotcha #9, :563).
- 64 GB RAM; bench standard 12 GB cgroup + 4 GB heap (`MAX_HEAP_SIZE` in cassandra-env.sh) (:22-23).
- 2× 894 GB Samsung NVMe, NOT RAID; `/`=nvme1n1p2 50 GB; `/data`=nvme1n1p3 843 GB
  (data_file_directories); `/commitlog`=nvme0n1 880 GB dedicated (:24, §9.3). All ext4.
  Readahead 256 KB (:25).
- Cassandra runs in place from `/root/repos/fork/cassandra` (branch fdp-poc); no /opt symlink,
  no deploy script (:16, :29-39). For new branches use isolated clone
  `/root/repos/fork/cassandra-<ticket>`, `ant jar` (:163-166).
- OS/kernel/mounts were NOT RECORDED in direct_io runbook → captured live 2026-07-05:
  Ubuntu 22.04.5, kernel 5.15.0-168, see ../runbook.md.

## B. Harness facts

**B1. easy-cass-stress** (load generator; NOT installed on rig at survey time, :19,205):
- Install: `git clone https://github.com/samueldlightfoot/cassandra-easy-stress.git easy-cass-stress`
  (fork keeps OLD name), `git checkout feature/csv-latency`, `./gradlew shadowJar` (:209-217).
  Must use shadowJar fat jar; installDist thin jar is NOT used by the wrapper (:223).
- Wrapper: `bin/cassandra-easy-stress` (:219-221; §9.4 path `/root/repos/fork/easy-cass-stress/bin/...`).
- Profile `KeyValue` (capitals — gotcha #46). Keyspace `cassandra_easy_stress`.
- Standard read invocation (:739-761): `run KeyValue --host 127.0.0.1 -p 1m -r 1.0 --pg sequence
  --rate 10000 -t 1 -d 2m --no-schema --hdr <prefix>`; `--pg sequence` REQUIRES `-t 1`;
  `--csv-latency <path>` = 100ms-window CSV.
- Rate limiter ramps 1000 ops/s steps → first ~10 s is warmup artifact (gotcha #32).
- **CSV truncated on SIGTERM — end runs by duration; HDR files overwritten on re-run** (gotcha #36).
- Prometheus port 9500 default; stale runs leave it bound → BindException (§2.3, gotcha #4).
  Flush design used 9500 reader / 9501 writer.

**B2. cassandra-agent-harness** (orchestration lib, repo `/Users/samlightfoot/repos/cassandra-agent-harness`;
API recorded in `tasks/flush-write-pacing/harness-integration.md`):
- `Investigation` subclass = condition matrix; arms = pure yaml via `NodeConfig.yaml_overrides`
  (config.py:30). Orchestrator run.py:53 iterates arms, resumes on `.complete`, capture hooks
  (investigation.py:63-85). Load: `run_easy_stress` (easy_stress.py:82) /
  `launch_easy_stress_async` + `WorkloadHandle.stop()` (:186-224).
- Known gaps G1-G7 (harness-integration.md:80-151): no interval sampler (meminfo/iostat), no
  cold-start drop_caches/cgroup wrapper on node.start(), no HDR/windowed-p99 parser, WorkloadSpec
  lacks keyspace/prometheus_port/csv_latency/hdr fields, no resolved-config banner capture, no
  system.log tailer, no syscall tracer window.

**B3. Rig-side wrapper scripts** (direct_io §4-5, §7; live in ~/scripts, scp'd):
- `benchmark.sh` (7-phase: Pre-flight→Restore→Start[drop caches+cgroup]→Monitors→Warmup 2m→
  Baseline 1m→During 7m→Summary; flags incl. --csv-latency --biosnoop --compaction-throughput
  --warmup-duration --cgroup-mem --max-heap --skip-restore --skip-start).
- `run_scan_test.sh`, `run_dio_vs_buffered.sh`, `run_dio_buffer_sweep.sh`. Plots local:
  `plot_combined.py`, `plot_cql_scatter.py`, `plot_biosnoop.py`, `plot_latency_over_time.py`.
- Anything >10 min under `nohup` (SSH drops, gotcha #18); monitor `tail -f /tmp/benchmark.log`.

## C. Established benchmark methodology (reusable)

- Flush-write-pacing 3-arm design (`tasks/flush-write-pacing/benchmark-design.md`): one workload,
  arms vary ONLY the mechanism; measured reader + unmeasured writer on separate keyspaces/ports;
  per-arm cold-start protocol (kill stale → yaml → drop caches + cgroup restart +
  disableautocompaction + capture resolved config → warmup → baseline → during-event + samplers →
  summary windows p99/p999); metrics = CSV windows sliced by event epochs (headline), HDR overall,
  /proc/meminfo Dirty+Writeback 1/s, iostat -xz 1, strace window, log-parsed throughput.
  Outputs per arm: reads_windows.csv, *-reads.txt (HDR), meminfo.log, iostat.log,
  flush_events.log, syscalls.txt, cassandra_startup_params.log, test_config.txt.
- DIO compaction bench = the parent 7-phase pipeline (~12-15 min/run).

## D. JMH microbench infra

- Dir: `test/microbench/org/apache/cassandra/test/microbench/` (`build.xml:85`).
- JMH **1.37** (`.build/parent-maven-pom.xml:667-675`).
- Targets (`.build/build-bench.xml`, imported `build.xml:2376`): `ant microbench` (suite),
  `ant microbench-test` (smoke f=1 wi=0 i=1), `ant microbench-with-profiler` (async-profiler 2.9).
  **One benchmark: `ant microbench -Dbenchmark.name=<Regex>`** (filter `.*microbench.*${name}`;
  exclude blacklist only applied when name blank — `build-bench.xml:122-127`). Extra flags via
  `-Djmh.args`. Output `${build.test.output.dir}/jmh-result.json`; forks `-Xmx1G`.
- Closest file-I/O example: `sstable/SSTableReadingFileBench.java` (AverageTime, ms, 10+10 iters,
  @Fork(1)). **Gotcha: the file-I/O benches are on `microbench.exclude.pattern`**
  (CASSANDRA-18873, `build-bench.xml:26`) — bare `ant microbench` skips them; -Dbenchmark.name
  bypasses.
- No io_uring/raw-syscall/sync_file_range JMH bench exists.

## E. Operational lessons (paid-for; bake into every bench)

- `ant jar` NOT `ant build`; check jar timestamp; **grep the class inside the jar** before
  trusting results.
- rsync repo to rig + verify patched binary BEFORE walking away (cost 4h on 2026-05-27).
- Heap vs cgroup: always pin MAX_HEAP_SIZE=4G; JVM auto-calc reads host 64GB not cgroup →
  31GB heap → OOM/GC storms. Re-set after every branch switch.
- Cold start every iteration: `echo 3 > /proc/sys/vm/drop_caches` + cgroup restart; never reuse
  warm system (LRU state is a hidden variable).
- Kill ALL stale eBPF monitors pre-run (cachestat-bpfcc → up to 32% throughput regression).
  biosnoop: no -d flag (use timeout), ~3s compile, 524MB/10min. bitesize-bpfcc HANGS on this box.
- Never `pkill -f CassandraDaemon` (kills own SSH); `pgrep -x java | head -1` + kill -9.
  nodetool not on PATH non-interactive. Poll artifacts (log lines), not process names.
- `mkfs.ext4 -E nodiscard` on preconditioned partitions; re-check free-block% after mkfs.
- `auto_snapshot: false` on bench rigs (DROP/TRUNCATE snapshots eat disk).
- df -h /data not / ; hardlink snapshots never cp.
- Capture `Node configuration` block from system.log per run (only record of RESOLVED config).
- Verify at source: nodetool tpstats/tablestats, ≥2-3 independent signals per headline number.
- Linux-gated tests SKIP on macOS; BUILD SUCCESSFUL ≠ passed; real syscall paths verified on rig.

## Gaps identified (→ closed by phase-0 execution)

1. Kernel version → captured: 5.15.0-168 (below 5.19 → HWE upgrade per 0.2).
2. OS/mounts → captured (../runbook.md).
3. XFS partition → created in 0.3 (same-device ext4 twin added).
4. Concrete rsync command → pinned in ../runbook.md (verify on first use).
5. liburing availability → irrelevant (raw syscalls, D1).
