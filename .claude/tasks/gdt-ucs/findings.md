# GDT-aware UCS — findings

## Pivot context

Originally `.claude/tasks/fdp-poc/` planned full FDP placement-hints PoC.
Blocked by the rig having no FDP-capable hardware (Samsung MZVL2512HCJQ
consumer SSDs in RAID-1, no spare device, OCP unlikely). Pivoted to GDT-aware
UCS as the salvageable Cassandra-side insight from the same paper.

## Source paper

Lee, Ziegler, Leis. *How to Write to SSDs*. PVLDB Vol. 19 No. 7, 2026.
Local copy of extracted text: `/tmp/nowa-paper.txt`.

Key passages for this slice:

- §4 "Deathtime-Based GC": *"To minimize WA during GC, we group pages by
  their expected invalidation time ('deathtime') when placing them, derived
  from DB semantics."*
- Table 1 (YCSB-A zipf 0.8, 800 GB, Samsung PM9A3 in LeanStore):
  DB WAF 0.62 → 0.59 with GDT alone (5% relative), OPS 380K → 458K (20%).
- §5.4 NoWA and §5.5 FDP placement hints are downstream optimisations that
  *build on* GDT; we extract GDT alone for this PoC.

## UCS gaps relevant to GDT

From `.claude/tasks/fdp-poc/ucs-investigation-findings.md`:

- Q1 cross-cutting: persisted `sstableLevel` is always 0 for UCS — UCS
  recomputes "level" from density at scheduling time.
- Q4: no arrival-order signal exists; `Descriptor.generation` is the best
  static proxy. For this PoC we use `SSTableReader.maxTimestamp()` instead
  — it's already exposed and behaves monotonically for non-back-dated writes.
- Q5: shard membership does not affect lifetime; per-shard tracking is not
  needed for the classifier.

## Rig characteristics (Hetzner box, 65.108.227.158)

- Ubuntu 22.04 jammy
- Kernel 6.8.0-106 (>= 6.6, qualified)
- 2× Samsung MZVL2512HCJQ 512 GB consumer NVMe, RAID-1 (root + boot + swap)
- Python 3.10.12 (note: cassandra-agent-harness requires 3.11+)
- `nvme-cli`: not installed
- `gh`: not installed
- `git`, `java`, `blkdiscard`: installed
- `~/repos/easy-cass-stress/bin/easy-cass-stress` present
- `~/repos/fork/cassandra/` present (existing DIO fork checkout)
- `~/repos/fork/cassandra-easy-stress/` present (older naming)
- Idle (58-day uptime, 0.01 load) — good for benchmark stability

## Naming nit — easy-cass-stress vs cassandra-easy-stress

The project was renamed in early 2025. The `cassandra-agent-harness`
`workload/easy_stress.py` defaults to `cassandra-easy-stress`; the rig has
`easy-cass-stress`. Either patch the library default or supply the binary
explicitly via `WorkloadSpec.binary` (the library accepts this).
Not blocking for this slice since we're driving stress directly via shell
for Phases 4–6.
