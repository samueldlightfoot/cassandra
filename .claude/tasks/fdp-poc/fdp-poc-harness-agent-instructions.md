# Agent Instructions: FDP PoC Benchmark Harness

**Companion to:** `rfc-cassandra-fdp-placement-hints-v2.md`
**Deliverable:** A fully automated benchmark harness that runs the Phase 1 measurement matrix end-to-end with one command.
**Audience:** A coding agent (Claude Code or equivalent) executing on the test rig.

This document is execution-oriented. It tells the agent *what* to build and *how to validate* what it builds. Design choices that have been settled in the parent RFC are not re-litigated here — when in doubt, the RFC wins.

---

## What the harness must do

End-to-end, in one command, the harness:

1. Verifies the test rig meets prerequisites (kernel version, NVMe FDP capability, disk space, Cassandra binary present, cassandra-easy-stress installed).
2. For each of the three run conditions (baseline / coarse-hints / full-hints):
   1. Wipes the device cleanly (`blkdiscard`) so each run starts from an identical state.
   2. Starts a single Cassandra node with the appropriate JVM flags to enable/disable hint emission.
   3. Captures device state at run start (OCP SMART log, FDP config).
   4. Calibrates run duration if not already calibrated for this device.
   5. Runs the cassandra-easy-stress workload.
   6. Captures device state at run end.
   7. Captures Cassandra-side metrics (compaction stats, per-life-class meters).
   8. Tears down Cassandra cleanly.
3. Aggregates all three runs into a single results bundle (parquet files, JSON metadata, OCP logs).
4. Produces a summary table with the headline numbers.

The harness must be **idempotent** (rerunning produces the same output structure), **resumable** (a partial run can be continued without redoing completed conditions), and **self-documenting** (every artifact records the inputs that produced it).

---

## Repository layout

Create a new repository (sibling to the Cassandra fork) named `fdp-poc-harness`. Layout:

```
fdp-poc-harness/
├── README.md                          # Human-facing quick start
├── pyproject.toml                     # uv / pip project metadata
├── harness/
│   ├── __init__.py
│   ├── __main__.py                    # CLI entry point
│   ├── config.py                      # Run configuration dataclasses
│   ├── prereqs.py                     # Prerequisite checks
│   ├── device.py                      # NVMe / OCP / FDP operations
│   ├── cassandra.py                   # Node lifecycle (start/stop/wait-ready)
│   ├── workload.py                    # cassandra-easy-stress invocation
│   ├── metrics.py                     # Cassandra JMX / metrics extraction
│   ├── calibration.py                 # Duration calibration logic
│   └── results.py                     # Result bundling and summary
├── conf/
│   ├── cassandra.yaml.template        # Base Cassandra config
│   ├── jvm-server-baseline.options    # JVM options: hints disabled
│   ├── jvm-server-coarse.options      # JVM options: coarse hint mapping
│   └── jvm-server-full.options        # JVM options: full hint mapping
├── analysis/
│   ├── analyse.py                     # Post-run analysis driver
│   ├── queries.sql                    # DuckDB queries against parquet output
│   └── notebook.ipynb                 # Jupyter notebook for plot generation
└── tests/
    ├── test_device.py
    ├── test_calibration.py
    └── test_results.py
```

Language: **Python 3.11+**. Use `uv` for environment management if available, falling back to `pip`. Avoid heavyweight frameworks; prefer the standard library plus `duckdb`, `pandas`, `pyarrow`, and a thin process-management library (`subprocess` is fine for most things).

---

## Phase A: prerequisites

The harness must verify, before any benchmark run starts:

| Check | How |
|---|---|
| Linux kernel ≥ 6.6 | `uname -r`, parse and compare |
| `nvme-cli` installed and on `PATH` | `which nvme` |
| Target device exposes FDP | `nvme fdp configs $DEVICE`; require ≥1 configuration, ≥4 RUHs |
| Target device exposes OCP SMART log | `nvme ocp smart-add-log $DEVICE`; if this fails, fall back to standard SMART and warn |
| `blkdiscard` available and device supports it | `lsblk -D` shows non-zero `DISC-GRAN` |
| Cassandra binary present at the configured path | Filesystem check |
| `cassandra-easy-stress` on `PATH` | `which cassandra-easy-stress` |
| Java 17+ available | `java -version` |
| Disk free space for results bundle | At least 10 GB free at `RESULTS_DIR` |

The check function returns a structured report `[(name, ok, detail)]`. The harness refuses to start a run if any check fails, but supports a `--skip-prereqs CHECK_NAME,...` flag for development iteration.

Sample CLI:

```
fdp-poc check
fdp-poc check --device /dev/nvme1n1 --cassandra-home /opt/cassandra
```

---

## Phase B: device operations

A `device.py` module providing functions for the lifecycle operations on the NVMe device.

### Required functions

```python
def discard(device: str) -> None:
    """blkdiscard the device. Raises if the device is mounted."""

def smart_snapshot(device: str) -> dict:
    """Capture OCP SMART log as a dict. Falls back to standard SMART if OCP unavailable."""

def fdp_config(device: str) -> dict:
    """Capture FDP configuration: RU size, RUH count, current statistics."""

def host_bytes_written(device: str) -> int:
    """Return cumulative host bytes written since power-on, from SMART."""

def physical_bytes_written(device: str) -> int:
    """Return cumulative physical (NAND) bytes written since power-on, from OCP SMART.

    Returns 0 and warns if the device does not expose this counter. WAF computation
    falls back to host-only metrics in that case (still useful for relative comparison
    but cannot produce absolute WAF numbers).
    """

def write_amplification(before: dict, after: dict) -> float | None:
    """Compute SSD WAF from two SMART snapshots, or None if the device does not expose
    physical write counters."""
```

### Implementation notes

- All `nvme` commands run via `subprocess.run(..., capture_output=True, check=True)`. Parse JSON output where available (`-o json`); fall back to text parsing where not.
- OCP SMART log field names vary by vendor firmware. Build a small parser that knows about Samsung, Kioxia, Solidigm, and Micron variants — fail loudly on an unknown vendor rather than silently mis-attributing fields.
- Snapshots are dicts with all numeric fields preserved as integers (not floats). The result bundle stores raw snapshots so analysis can be re-run if the parsing logic improves later.

### Validation

Unit tests with mocked subprocess calls covering: a Samsung OCP response, a Kioxia OCP response, a non-OCP device (graceful fallback), and a device that fails `blkdiscard` (proper error propagation).

---

## Phase C: Cassandra node lifecycle

A `cassandra.py` module managing a single-node Cassandra instance for the duration of a run.

### Configuration model

The harness operates one Cassandra node, configured per-run. The configuration delta between runs is **JVM options only** — the data directory, listen addresses, and storage paths stay constant. This means:

- The system property `cassandra.fdp.hints.enabled` and the system property `cassandra.fdp.hints.mode` (one of `disabled`, `coarse`, `full`) are read by the `DeathtimeClassifier` to select the hint mapping.
- Three JVM options files in `conf/`, one per condition. The harness symlinks the active one to `jvm-server.options` before each run.

### Required functions

```python
def start(config: CassandraConfig) -> CassandraHandle:
    """Start a Cassandra node. Returns a handle once `nodetool status` reports UN."""

def stop(handle: CassandraHandle, timeout: int = 120) -> None:
    """Issue nodetool drain, then stop. Force-kill after timeout."""

def wait_ready(handle: CassandraHandle, timeout: int = 300) -> None:
    """Poll until JMX is reachable, schema is loaded, and the node is UN."""

def jmx_query(handle: CassandraHandle, mbean: str, attribute: str) -> Any:
    """Read a JMX attribute. Used by metrics.py for compaction stats."""

def nodetool(handle: CassandraHandle, *args) -> str:
    """Invoke nodetool, return stdout."""

def flush_and_drain(handle: CassandraHandle) -> None:
    """Trigger a memtable flush across all keyspaces and wait for it to complete.
    Used immediately before the post-run SMART snapshot to ensure all writes have
    actually hit the device.
    """
```

### Implementation notes

- Cassandra is started with explicit `CASSANDRA_HOME`, `CASSANDRA_CONF`, and a writable `data_file_directories` path. The harness creates a clean `data_file_directories` mount on the test device before each run.
- The `data_file_directories` mount point lives on the test device and is mounted with `xfs` (or `ext4`, configurable). The mount happens after `blkdiscard` and before Cassandra start.
- Commit log path: should live on the **test device** too, since commit log writes will also pick up FDP hints if the JNR-FFI plumbing is invoked for the commit log writer. The harness should verify this is the case and document it in the run metadata. If commit log is configured separately to avoid the entanglement, that's a deliberate choice that should be noted.
- For deterministic comparison, the harness sets a fixed `concurrent_compactors` and `concurrent_writes` in `cassandra.yaml`. These values become part of the run metadata.

### Validation

Integration test that boots a real Cassandra (via testcontainers or local install) and verifies the lifecycle functions work end-to-end. This is the slow test; mark it appropriately.

---

## Phase D: cassandra-easy-stress invocation

A `workload.py` module that wraps cassandra-easy-stress.

### Required functions

```python
def run_workload(spec: WorkloadSpec, output_dir: Path) -> WorkloadResult:
    """Invoke cassandra-easy-stress with the given spec. Streams stdout to a log file.

    Captures:
    - The parquet output (--parquet)
    - Final summary stats (parsed from stdout)
    - Exit code

    Returns a WorkloadResult with paths to the artifacts and parsed summary.
    """
```

### `WorkloadSpec` shape

```python
@dataclass
class WorkloadSpec:
    workload_name: str          # "KeyValue", "BasicTimeSeries", etc.
    duration: str               # cassandra-easy-stress duration string, e.g. "30m"
    read_ratio: float           # 0.0 to 1.0
    partitions: int             # -p flag
    compaction_strategy: str    # JSON string for --compaction
    threads: int                # -t flag
    rate: int | None = None     # ops/sec cap, None for unlimited
    host: str = "127.0.0.1"
    parquet_filename: str = "rawlog.parquet"
```

### Phase 1 spec (locked)

```python
PHASE_1_KEYVALUE_SPEC = WorkloadSpec(
    workload_name="KeyValue",
    duration="<from calibration>",
    read_ratio=0.5,
    partitions=<from calibration>,
    compaction_strategy='{"class":"UnifiedCompactionStrategy","scaling_parameters":"T4"}',
    threads=64,
    rate=None,
)
```

Threads = 64 is a reasonable default for a modern multi-core test rig; if the agent observes CPU saturation, log this prominently and reduce.

### Validation

Unit tests covering: stdout parsing for the summary line, parquet file presence verification, exit code propagation, and timeout handling.

---

## Phase E: duration calibration

A `calibration.py` module that determines, for a given device + Cassandra config, the duration needed to drive cumulative writes to 4× device capacity.

This is per-device, one-time work. Results cached in `results/<device-uuid>/calibration.json`.

### Algorithm

1. Run a short (5 minute) workload at the same threads/partitions as Phase 1.
2. Measure cumulative host writes from SMART before/after.
3. Compute writes/second.
4. Required duration = 4 × device_capacity_bytes / writes_per_second.
5. Add 20% safety margin to ensure steady state.

The calibration also estimates the right partition count to fill the device to ~80% capacity post-compression. This requires a second short run with monotonic inserts (no reads, no updates) until the data directory reaches the target size.

### Required functions

```python
def calibrate_duration(device: str, base_config: CassandraConfig) -> CalibrationResult:
    """Returns the calibration result with recommended duration and partition count."""

def load_or_calibrate(device: str, base_config: CassandraConfig) -> CalibrationResult:
    """Load cached calibration if it exists for this device+config combo, else calibrate."""
```

### Implementation notes

- Calibration runs use `cassandra.fdp.hints.enabled=false` so the calibration itself isn't influenced by the hint behaviour we're trying to measure.
- The calibration result is keyed by `(device_serial, cassandra_version, compaction_strategy_hash)`. Changing any of these invalidates the cache.

---

## Phase F: results bundling

A `results.py` module that produces the final results bundle.

### Bundle structure

```
results/
└── <run-uuid>/
    ├── manifest.json                  # Run metadata (timestamps, versions, configs)
    ├── prereqs.json                   # Output of the prerequisite checks
    ├── calibration.json               # Calibration used for this run
    ├── baseline/
    │   ├── smart-before.json
    │   ├── smart-after.json
    │   ├── fdp-config.json
    │   ├── cassandra.log              # JVM stdout/stderr
    │   ├── stress.log                 # cassandra-easy-stress stdout
    │   ├── stress.parquet             # Per-operation latency
    │   ├── metrics-end.json           # JMX metrics at run end
    │   └── compaction-stats.json      # nodetool compactionstats -H output
    ├── coarse/
    │   └── ... (same structure)
    ├── full/
    │   └── ... (same structure)
    ├── summary.json                   # Computed headline numbers
    └── summary.md                     # Human-readable summary
```

### `manifest.json` shape

```json
{
  "run_uuid": "...",
  "started_at": "2026-MM-DDTHH:MM:SSZ",
  "completed_at": "...",
  "host": {
    "hostname": "...",
    "kernel": "6.X.Y",
    "cpu_model": "...",
    "memory_gb": 64
  },
  "device": {
    "path": "/dev/nvme1n1",
    "model": "...",
    "serial": "...",
    "firmware": "...",
    "capacity_gb": 1920,
    "fdp_supported": true,
    "ru_size_mb": 512,
    "ruh_count": 8
  },
  "cassandra": {
    "version": "5.x-SNAPSHOT",
    "branch": "fdp-poc",
    "commit": "<sha>"
  },
  "workload": {
    "spec": { ... },
    "calibration": { ... }
  },
  "conditions": ["baseline", "coarse", "full"]
}
```

### `summary.json` shape

```json
{
  "conditions": {
    "baseline": {
      "host_bytes_written": <int>,
      "physical_bytes_written": <int>,
      "ssd_waf": <float | null>,
      "db_waf": <float>,
      "read_p99_ms": <float>,
      "compaction_throughput_mbps": <float>,
      "bytes_by_life_class": { "NOT_SET": <int>, "SHORT": 0, ... }
    },
    "coarse": { ... },
    "full": { ... }
  },
  "headline": {
    "baseline_ssd_waf": <float>,
    "full_ssd_waf": <float>,
    "ssd_waf_reduction_pct": <float>,
    "coarse_captures_pct_of_full": <float>
  }
}
```

### `summary.md`

A human-readable markdown summary suitable for direct inclusion in the lightfoot.dev blog post. Tables for the headline metrics, a paragraph of interpretation, and a footer noting the manifest UUID for traceability.

---

## CLI design

The harness exposes a small set of commands:

```
fdp-poc check                            # Run prerequisite checks, report status
fdp-poc calibrate                        # Run calibration only
fdp-poc run                              # Run the full Phase 1 matrix
fdp-poc run --conditions baseline,full   # Subset of conditions
fdp-poc resume <run-uuid>                # Continue an interrupted run
fdp-poc summarise <run-uuid>             # Regenerate summary from raw artifacts
fdp-poc analyse <run-uuid>               # Run the analysis notebook
```

All commands accept `--device`, `--cassandra-home`, and `--results-dir` flags. Reasonable defaults from environment variables: `FDP_DEVICE`, `CASSANDRA_HOME`, `FDP_RESULTS_DIR`.

A typical invocation:

```bash
FDP_DEVICE=/dev/nvme1n1 CASSANDRA_HOME=/opt/cassandra fdp-poc run
```

Should complete the full matrix without further intervention.

---

## Failure modes the harness must handle

These are the failure modes the harness explicitly handles rather than crashing on:

| Failure | Handling |
|---|---|
| Cassandra fails to start | Capture logs, mark condition as failed, continue to next condition |
| Cassandra OOM during run | Same as above; record OOM in summary |
| Device runs out of space | Stop the workload cleanly, capture state, mark partial run |
| nvme-cli command failure | Retry once, then propagate as a clear error |
| `blkdiscard` fails (device mounted) | Refuse to start the run, with explicit error message |
| Stress tool exits non-zero | Capture stderr, mark condition as failed, continue |
| Network blip during JMX poll | Retry with exponential backoff (max 30s) |
| Parquet file missing after run | Mark condition as failed; the workload may have crashed before flushing |

A failed condition does not abort the run — the other conditions still execute. The final summary explicitly flags which conditions completed.

---

## Observability of the harness itself

The harness logs to stdout in structured JSON (one record per line) so output is machine-parseable. Each record has:

```json
{
  "timestamp": "...",
  "level": "INFO|WARN|ERROR",
  "phase": "prereqs|calibration|condition|results",
  "condition": "baseline|coarse|full|null",
  "message": "...",
  "data": { ... }
}
```

A second log file `<run-uuid>/harness.log` captures the same records for archival.

---

## Tests the harness must include

Mandatory tests, all runnable via `pytest`:

1. **Prerequisite check unit tests** — mocked subprocess, verifies each check's pass/fail logic.
2. **Device parsing tests** — fixture-based: OCP responses from each supported vendor, verifies field extraction.
3. **Calibration tests** — synthetic stress results, verifies duration computation.
4. **Results bundling tests** — assembles a fake run directory, verifies summary computation matches expected.
5. **End-to-end smoke test** — runs the full harness against a containerised Cassandra with a stub device. Slow but high value; marked `@slow` so it can be excluded from fast iteration.

Coverage target: 80% line coverage on the non-end-to-end code. The end-to-end test is the integration smoke test; coverage isn't the right metric for it.

---

## What the harness does NOT do

Things the agent should not implement, even if they look like obvious extensions:

- **No multi-node clusters.** Phase 1 is single-node. Multi-node introduces network IO, replication, repair — all confounders we don't want for the first measurement.
- **No automatic CEP/Jira filing.** The harness produces artifacts; the writeup is a separate manual task.
- **No public hosting.** Results live on the test rig; the harness has no upload logic.
- **No CI integration.** This runs on a single test rig under operator supervision. CI integration is premature.
- **No abstraction over benchmark tools.** It runs cassandra-easy-stress. If we later add `fio` or another tool, that's a new module, not a refactor.

---

## Acceptance criteria for the harness

The harness is done (for Phase 1 purposes) when:

1. `fdp-poc check` passes on the target rig.
2. `fdp-poc calibrate` produces a stable calibration that survives a second invocation (cache hit).
3. `fdp-poc run` completes the full matrix end-to-end without manual intervention.
4. The resulting bundle includes all three conditions with non-empty SMART logs, non-empty parquet files, and a computed `summary.json` with non-null headline numbers (assuming the device exposes physical write counters).
5. `fdp-poc resume` correctly continues after a deliberate mid-run interruption (kill the harness during the `coarse` condition; rerun with `resume`; verify only the remaining conditions execute).
6. The test suite passes.

---

## Sequencing recommendation for the agent

Build in this order. Each step produces a working subset before the next:

1. Repository skeleton, CLI scaffolding, prerequisite checks. Result: `fdp-poc check` works.
2. Device operations module with unit tests. Result: can capture SMART snapshots and run `blkdiscard`.
3. Cassandra lifecycle module. Result: can start/stop a node from the harness.
4. Workload module. Result: can run cassandra-easy-stress and capture parquet output.
5. Calibration. Result: can compute duration and partition count for the target device.
6. Full `run` command tying it all together. Result: a complete unattended run.
7. Results bundling and `summarise`. Result: human-readable output exists.
8. `resume` and failure-mode handling. Result: the harness is robust to interruption.
9. Analysis notebook and DuckDB queries. Result: post-run analysis works.
10. Final integration smoke test. Result: confidence the whole thing works end-to-end.

Steps 1-7 are the critical path. Steps 8-10 harden the harness; if time is tight, they can be deferred until after the first Phase 1 result is in hand.

---

## Notes for the agent on style and judgment

- **Prefer subprocess over libraries** where the tool exists as a binary (nvme, blkdiscard, nodetool). Library wrappers introduce dependencies for marginal benefit.
- **Don't reinvent retry logic.** Use `tenacity` for retries with backoff.
- **All paths are `pathlib.Path`, not strings.**
- **Dataclasses for configuration, not dicts.** Type-checkable, easier to refactor.
- **No silent fallbacks.** If a device doesn't support OCP SMART, log it loudly and propagate the absence into the summary.
- **Time everything.** Each phase records start and end timestamps. Calibration data accumulates more value over time than any single number.
- **Resist scope creep.** This is a Phase 1 harness. Generalisations for future devices/workloads/strategies belong in Phase 2.

If a design question is not answered by this doc or the parent RFC, stop and ask the user. Do not make architecture-level decisions unilaterally.

---

## References

- Parent RFC: `rfc-cassandra-fdp-placement-hints-v2.md`
- UCS investigation findings: previously delivered
- cassandra-easy-stress documentation: https://apache.github.io/cassandra-easy-stress/
- Lee, Ziegler, Leis. *How to Write to SSDs*. PVLDB Vol. 19 No. 7, 2026.
