# Why this bug only surfaces on Reaper's `caas_repair.cluster` table

## The one-sentence answer

The bug needs a write pattern that **replaces a collection column on every
overwrite**, and Reaper's `caas_repair.cluster` table is one of the few
production tables that does this — every time Reaper updates a cluster row,
the `seed_hosts SET<TEXT>` column is replaced wholesale.

## A bit more

The leak fires during a partition **merge**. For it to happen, three things
must be true at once:

1. The write overwrites an existing row.
2. The overwrite contains a **collection column** (`SET`, `MAP`, or `LIST`) that
   is **replaced**, not appended to.
3. Something throws between the shadow-deletes and the matching inserts.

Reaper's `caas_repair.cluster` write checks (1) and (2) on every update.
Almost no other production table does. Condition (3) is rarer still — it
needs memory pressure or a cleaner-interrupt landing in a narrow window —
which is why only a small number of clusters running this exact workload see
it.

## Why this write produces the bad pattern

Reaper writes the cluster row with a vanilla CQL insert that looks roughly
like:

```sql
INSERT INTO caas_repair.cluster (name, partitioner, ..., seed_hosts, ...)
VALUES (?, ?, ..., {?, ?, ?}, ...);
```

CQL semantics require the `seed_hosts SET<TEXT>` assignment to **replace**
any prior set. The server cannot replace a multi-cell value atomically, so it
materialises the operation as two parts:

- a **complex deletion** on the `seed_hosts` column at timestamp `ts - 1`,
  shadowing whatever was there before, and
- the new SET elements as cells at timestamp `ts`.

When this write is applied on top of an existing row that already had
`seed_hosts` elements, the merge inside `BTreePartitionUpdater` runs:

1. The four simple TEXT columns merge in place — each cell is cloned but the
   on-heap accumulator stays at zero (`NativeCell` wrappers are the same
   size before and after).
2. The complex deletion at `ts - 1` shadows the previous SET cells, so the
   reconciler calls `delete(existing)` for each one. The accumulator goes
   **negative**.
3. `Reconciler.merge` also reports the tree-structure shrink via
   `onAllocatedOnHeap(retainedTreeSize - existingTreeSize)`. Negative again.
4. Then the new SET cells are inserted one by one. Each insert pushes the
   accumulator back up by the cloned cell's size.

After step 4 the accumulator is back at zero. But between steps 2 and 4 it
is transiently negative. If anything throws in that window —
`cloner.clone()` failing under offheap pressure, an interrupt from the
memtable cleaner, an indexer fault — the `addAll` / `mergePartitions`
`finally` block reports that partial value to the allocator, which dutifully
subtracts it from `owns`.

## Why other Reaper tables are fine

Reaper has many tables (`repair_run`, `repair_unit`, `repair_schedule`,
`leader`, ...). Most don't have a collection column that is replaced on
every write. Some use scalar columns only; some use frozen collections (the
server treats those as opaque blobs); some use incremental updates like
`seed_hosts = seed_hosts + {?}`, which generate an additive cell, not a
shadow-then-insert.

Only `caas_repair.cluster` writes the full-replace pattern on a hot path.

## Why other applications' tables are fine

The same logic applies. Apps overwhelmingly:

- use scalar columns,
- declare collections as `frozen<...>` (atomic, no shadow-then-insert),
- or mutate collections incrementally (`SET col = col + {...}`), which the
  server compiles to a single additive cell.

Any application that does write a full collection replacement repeatedly to
the same partition under `offheap_objects` would be exposed to the same
leak, but in practice this combination is rare.

## Why only 2 clusters out of 1000+

The write pattern is necessary but not sufficient. The merge has to be
**interrupted** while the accumulator is negative. The interruptions are
themselves rare events:

- `NativeAllocator` allocation under heap pressure can throw or block.
- A blocking allocate waiting for room can be interrupted by the memtable
  cleaner when the memtable flushes.
- A long-running operation can be cancelled by Cassandra's executor pools.

The two affected clusters are the two largest Reaper-managed deployments (36
and 120+ nodes). Their characteristics line up with raising the
interruption rate:

- **More sidecars writing the same partition.** Every Cassandra node runs a
  Reaper sidecar, and every sidecar writes the cluster row on each `getClusters`
  cache miss (every ~10s). Thirty-six sidecars produce thirty-six independent
  client sessions, all converging on the same memtable allocator on every
  replica.
- **More native-transport handlers.** More concurrent writers on each replica
  means more contention on the offheap allocator and a higher probability of
  a write blocking on `awaitThrowUncheckedOnInterrupt`.
- **RF=2 + CL=QUORUM = CL=ALL.** Every write must be acknowledged by both
  replicas. The per-replica write rate is effectively double what it would
  be at RF=3 + CL=QUORUM.

The other ~998 Reaper-managed clusters write the same shape but at lower
rates, and never push the allocator hard enough to trip the rare exception
that completes the recipe.

## Why `offheap_objects` and not the other allocation types

The exception path requires the writer to go through
`NativeAllocator.allocate` (the offheap-objects allocator). The heap-buffers
allocators don't throw from the same call sites. So `heap_buffers`,
`offheap_buffers`, and `unslabbed_heap_buffers` are not vulnerable to this
specific trigger even when they hit the same shadow-then-insert pattern.
