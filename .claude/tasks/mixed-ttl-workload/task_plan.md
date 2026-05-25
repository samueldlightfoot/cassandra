# Mixed-TTL workload for easy-cass-stress — Phase 2 enabler

## Why this workload exists

Phase 2 of the GDT investigation added `MinLocalDeletionTime` —
a `DeathtimeClassifier` that buckets SSTables by their earliest
TTL-expiry. To measure whether it helps over plain UCS, we need
SSTables whose `minLocalDeletionTime` differs meaningfully across
flushes.

## The non-obvious design constraint (read this before coding)

`SSTableReader.getMinLocalDeletionTime()` returns the **earliest
expiry across all rows in the table**. If a single SSTable contains
rows from multiple TTL classes, the value is dominated by the
shortest-TTL row and tells you nothing about the other classes.

Enumerated cases:

| workload | all-SSTable minDelTime behaviour | classifier signal? |
|---|---|---|
| **A — uniform single TTL** | `≈ flushTime + ttl` for every SSTable. Monotonic with flush time. | None — collapses to `MaxTimestamp`. |
| **B — temporally phased classes** (one TTL class at a time, phase > flush period) | Each SSTable is class-pure → `minDelTime = flushTime + classTTL` differentiates. | **YES**. This is the case GDT can act on. |
| **C — uniformly mixed classes** | Every SSTable contains all classes → `minDelTime ≈ flushTime + shortestTTL`. Monotonic again. | None. |

**Conclusion:** for `MinLocalDeletionTime` to differentiate, the
workload must produce **class-pure flushes**. That requires either:
- Temporally phased writes (active class cycles over time, phase > memtable flush period), OR
- Sharded tables (one per class — but `default_time_to_live` already does this trivially).

Real-world analogues for case B exist: event ingestion with
TTL-strategy changes over time, periodic batch loads with different
retentions, cohorted writers. So phased is a defensible model.

## Workload design — `MixedTTLKeyValue`

Schema: identical to the existing `KeyValue` workload (`(key text PRIMARY KEY, value text)`).

**Workload parameters** (annotated `@WorkloadParameter`, default values shown):

| param | default | meaning |
|---|---|---|
| `ttlClasses` | `"300,86400"` | Comma-separated TTL values (seconds). Each is a "class". |
| `phaseSeconds` | `300` | Length of a phase in seconds. `0` = no phasing (uniform mix → case C). |

**Behaviour**:
- Each key is deterministically assigned to a TTL class via `hash(key) mod numClasses` (so reads find what was written, and overwrites keep the same TTL).
- **Writes**: when `phaseSeconds > 0`, only keys in the currently-active class are written. Active class advances every `phaseSeconds` seconds (`floor((now() - t0) / phaseSeconds) mod numClasses`).
- **Reads**: uniform across all classes (so we measure p99 across the whole dataset, including the long-lived data that GDT is meant to protect).
- **Deletes**: same key-selection as writes (phased), but apply `DELETE` (no TTL on delete).

**Sizing for our existing rig (Hetzner, 50K ops/sec, 2KB rows, 31GB heap → ~2GB flushes ~ every 46s)**:
- `phaseSeconds = 300` → 6–7 flushes per phase → flushes are reliably class-pure
- 15-min run = 3 phases at default = 3 classes worth of SSTables → enough for T=4 to fire

## Implementation checklist

- [ ] **task plan written** (this file)
- [ ] **Workload class** `src/main/kotlin/.../workloads/MixedTTLKeyValue.kt` — new file alongside `KeyValue.kt`. Use `@WorkloadParameter` so `--workload.MixedTTLKeyValue.phaseSeconds=300` works.
- [ ] **Build via gradle** (`./gradlew shadowJar` or whatever the existing CI uses)
- [ ] **Smoke check** locally: `bin/easy-cass-stress run MixedTTLKeyValue --duration 30s ...` or unit test if one fits the existing test pattern
- [ ] **Wire into gdt-poc-harness**: add `MixedTTLKeyValue` to `WORKLOAD_FIELD_PATHS` in `investigation.py` (field path = `keyvalue.value` — same as KeyValue since schema is identical)
- [ ] **Commit + push** easy-cass-stress and harness changes

## Out of scope (defer to Phase 3 if needed)

- Per-row classification at compaction time (i.e. don't bucket SSTables — split rows across output SSTables during compaction by TTL). This would relax the "class-pure flush" requirement but is a much larger change.
- Multiple tables. Cassandra already supports per-table `default_time_to_live`; you don't need GDT for that case.
- TTL on DELETE. Standard tombstones expire under `gc_grace_seconds`, not row TTL — separate mechanism, separate investigation.
