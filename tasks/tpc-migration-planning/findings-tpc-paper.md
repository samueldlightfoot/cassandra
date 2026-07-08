# Findings — Enberg/Rao/Tarkoma ANCS'19 mapped to our phases

Source: P. Enberg, A. Rao, S. Tarkoma, "The Impact of Thread-Per-Core Architecture on
Application Tail Latency", ANCS 2019 (https://penberg.org/papers/tpc-ancs19.pdf).
Read 2026-07-08. They built Sphinx, a TPC shared-nothing KV store (Memcached-protocol
subset), and A/B'd it against stock Memcached on commodity Linux — the closest published
analogue to what our PoC does. Sphinx source: github.com/penberg/sphinx branch `ancs19`.

This doc: §1 what the paper establishes; §2 standing consequences for the whole program;
§3 the per-phase/per-increment hotspot map. Each phase's `expected-changes.md` carries a
short pointer section; the reasoning lives here.

## 1. What the paper establishes

Sphinx's architecture (their §IV.A) — note how much matches our target design:
- Data partitioned per core by `MurmurHash3(key) % nthreads` (ours: `ShardBoundaries`
  over Murmur3 tokens); one pinned thread per core, run-to-completion event loop,
  async syscalls + epoll.
- OS resources partitioned too: sockets per core via `SO_REUSEPORT` so kernel-side
  socket-lock contention disappears (we keep netty loops; the analogue is I5's
  netty→shard routing).
- Misdirected requests forwarded by user-space message passing: **bounded lock-free
  SPSC queue per thread pair** (avoids producer-side cache-line sharing) + **eventfd
  wake-ups** (chosen over signals — a signal handler takes a process-wide spinlock).
- Per-thread log-structured memory allocator (LSMA), 2 MiB huge-page segments to cut
  TLB pressure, FIFO whole-segment expiry (our analogue: I4c per-shard
  MemtableAllocator + region slabs).

Headline results (their §IV.C, fig 3):
- **Up to 71%/67% lower update tail** (legacy/modern hw) and up to 54%/20% lower read
  tail vs Memcached — **but only in the IRQ-affinity-configured + irqbalance-disabled
  configuration** (NIC IRQs pinned to dedicated cores, app threads on the rest).
- In the default environment (irqbalance on, no affinity) the gap shrinks to ≤16%, and
  **IRQ configuration moved tail latency more than the application architecture did**
  in several cells. Disabling irqbalance alone helped Memcached up to 23% and Sphinx
  ~0% ("a robust optimization" for the SEDA-style design; TPC needs full affinity).
- **TPC loses at low concurrency:** Sphinx's read AND update tails are *higher* than
  Memcached's at small connection counts — "a weakness of the thread-per-core model
  for workloads that do not distribute work to all cores". Steering cost is paid on
  every request; the contention win only materializes under load.

Held-back-by list (their §V) — the costs, in their observed priority order:
1. **Request steering = thread wake-ups.** Software steering "suffer[s] from high
   overheads because of thread wake-ups" (they cite ScyllaDB's memory-barrier
   experience). The queue is cheap; waking a parked consumer is the tax.
2. **OS interfaces.** Syscall crossings (costlier post-Meltdown/Spectre), epoll's
   notify-then-`recv` double touch, packets bouncing across up to 3 cores
   (NIC RX queue → softirq core → app-thread core), IPIs when notification crosses
   cores. Their prescription is exactly asynchronous batched interfaces — io_uring
   post-dates the paper but is the direct answer.
3. **Message passing copies payloads.** Early YCSB results: their approach "is
   inefficient for larger request sizes because packet steering involves a memory
   copy".
4. **Shared-nothing skew ceiling:** only one core can serve a hot partition; skewed
   workloads are throughput-limited by that core (their §III.A).
5. **Blocking is architecture-negating** (via their Arachne critique): blocking
   syscalls and page faults on a TPC thread stall every request behind them; TPC
   threads must use async interfaces and avoid paging.

## 2. Standing consequences for our program (cross-phase)

- **C1 — Environment before architecture.** Their single biggest tail lever was IRQ
  affinity, not the app rewrite. Every A/B we run must *record and pin*: irqbalance
  state, NIC + NVMe IRQ affinity (`/proc/interrupts`), softirq distribution, JVM
  taskset mask, loadgen fencing (we already have the CPU-fence lesson). A flag-on/off
  pair run in different IRQ regimes is invalid. Corollary for interpretation: part of
  the 2016 CASSANDRA-10993 2×-p99 mystery may simply be an uncontrolled steering/IRQ
  environment — our PoC controls for it.
- **C2 — The wake-up is the unit of steering cost.** Every routed hand-off is
  enqueue + unpark; `LockSupport.unpark` of a parked thread is our eventfd (futex
  syscall, possible IPI, scheduler wake). Uncontended enqueue is ~tens of ns; waking
  an idle consumer is ~1–10 µs. Hotspot engineering therefore concentrates on
  park/unpark discipline (idle strategy of the shard loop), not queue microstructure.
- **C3 — Expect a low-load regression; gate on loaded tail.** Today's inline path
  (`maybeExecuteImmediately`, zero hops) beats any routed design at low concurrency —
  the paper measured exactly this crossover. A/Bs need a low-concurrency cell so the
  regression is *characterized*, and `poc-criteria.md` should gate on tail at target
  throughput, tolerating a bounded low-load cost. This is the paper's answer to "why
  did routing make p99 worse at QD1" — it's expected, not a bug.
- **C4 — Skew is the design's structural risk.** Shared-nothing's ceiling is the hot
  shard. Our per-shard backlog gauges (ThreadPoolMetrics PendingTasks) +
  `misroutedPuts` are the health instruments; the phase-3 skew stance should cite the
  paper rather than re-derive the problem.
- **C5 — Never block a shard thread.** Any block (lock-fallback wait, page fault,
  synchronous ring wait) serializes the whole shard — 8 shard threads have 4× less
  blocking absorption than 32 MutationStage workers. Every increment that puts work on
  a shard thread must answer "what can block here and for how long".

## 3. Per-phase hotspot map

### Phase 0 — baseline

- Capture the C1 environment facts in the baseline snapshot: `irqbalance` status, NIC
  and NVMe IRQ affinity, `/proc/interrupts` + `/proc/softirqs` deltas under load,
  per-thread context-switch rate (`pidstat -w`) for NTR/MutationStage/netty threads.
  These are the "before" numbers for TPC's claimed mechanisms (fewer switches, fewer
  cross-core bounces) — without them, increments can't attribute their wins.

### Phase 1 — io_uring binding

The binding is the paper's item-2 prescription (async, batched, no per-op syscall).
Its KPI is **syscalls per op** — everything else is secondary:
- Submission: one `io_uring_enter` per *batch*, never per op (QD64 batched mode
  already specced).
- Completion reaping: the CQ is mmap'd — poll it with acquire loads (the D2 Unsafe
  design); only fall into `enter(GETEVENTS)` when empty *and* the caller must wait.
  A hot loop that syscalls to reap forfeits the point.
- `strace-window.sh` (phase 2) is the verification instrument: batched mode should
  show ≪1 enter/op; record the ratio in results.
- SQPOLL (kernel-side submission polling) stays out of scope: it burns a core
  busy-polling — the paper's Shenango citation is precisely the "state-of-the-art
  systems waste CPU busy-polling" critique. Revisit only with phase-2 data.
- Registered buffers (1.5) are the per-op pin/translate saving — the storage analogue
  of their copy complaint (item 3).

### Phase 2 — benchmark

- **Keep the QD1 cells** (already in the matrix): the paper predicts the ring's win
  appears at depth, and sync-QD1 ≈ pread minus syscall overhead. A result table
  without low-QD cells would overstate the ring.
- **Record IRQ config per cell** (C1): their results *flipped* between IRQ
  configurations. NVMe completion IRQs/softirqs landing on the pinned bench core vs
  elsewhere changes tail. One `grep nvme /proc/interrupts` snapshot per cell into the
  results dir; assert irqbalance state in `run-*-cells.sh` preflight.
- `pidstat -w` + iou-wrk sampling (already planned) now have a thesis to test:
  iou-wrk kernel workers are unpinned — they are the storage analogue of the paper's
  packet-bounce problem. Record *which CPUs* they run on, not just their count.

### Phase 3 — execution-model design docs

- **design-target.md:** name the model with the paper's vocabulary — ours is
  **shared-something**: per-shard memtable shard / commitlog manager / OpOrder /
  allocator (shared-nothing slices) atop shared SSTables, chunk cache, compaction
  pools (shared-everything remainder). This framing pre-answers "why not full
  shared-nothing like Scylla" (skew ceiling + page-cache reliance) in one line.
- **Skew stance (3.1 item 4):** cite the paper's shared-nothing throughput ceiling as
  the reason the stance exists (C4).
- **Shard loop / inbox (3.1 item 2):** two paper-informed attention points for the
  custom loop that replaces `sequential()` at I2b:
  - *Idle strategy is the hotspot* (C2). Park-after-every-drain pays a futex wake per
    message at low load; pure spin burns the core (Shenango critique). Use an
    adaptive spin→yield→park idle strategy (netty/Aeron precedent) and make the
    park/unpark rate an observable.
  - *MPSC vs SPSC:* Sphinx used per-producer-pair SPSC queues to avoid producer
    cache-line sharing; our single MPSC inbox takes tail-CAS contention from P
    producers (netty loops + NTR + REQUEST_RESPONSE). Acceptable for the PoC —
    but record it as the known next bottleneck if inbox enqueue shows up in profiles.
- **design-async-coordinator.md (3.2 decision 1):** the paper's steering-cost finding
  is an evidence line for completing continuations on the thread that already holds
  the response cache-hot (REQUEST_RESPONSE) rather than adding one more hop to a
  dedicated completion pool — *provided* the non-blocking-all-the-way-down rule holds.
  If the pool wins anyway (blocking risk), size the extra hop consciously.
- **design-hostiles.md (3.3):** commitlog option (b) [log-writer core] adds a
  per-mutation cross-core round trip — exactly the paper's message-passing weakness
  (items 1+3). One more independent reason the evidence favors (a).

### Phase 4 — PoC increments

- **I0 (shard runtime).** The hand-off is the product: measure
  enqueue→dequeue-latency and unparks/sec as first-class micro metrics from day one
  (C2). Pinning: the paper's threads are *pinned and dedicated*; the JVM can't pin
  per-thread portably — at minimum taskset the whole JVM (rig runbook) and record it.
  Open question §8-5 (oversubscription) should be read with the paper in hand: their
  entire result assumes dedicated cores; shard threads competing with 32 SEP workers
  + 128 NTR threads for 8 cores re-introduces the scheduler noise TPC exists to
  remove. Recommendation stands (accept for I1, shrink pools where the claim is
  made) — but say in the criteria that unpinned/oversubscribed cells put a *floor*
  on the tail win we can demonstrate, so a muted I1 result is not a program verdict.
- **I1 (mutation routing).** Set gate expectations from C3: the routed hop costs µs
  where the uncontended `tryLock` costs ns — I1's *median* may regress slightly, and
  its low-concurrency tail almost certainly will. The win TPC claims is the *loaded
  tail* (no lock convoys, no SEP queue-time variance) — which is exactly what the
  program gates on (p99 at throughput ≥ trunk). Include one low-load cell to
  characterize the crossover; don't let it gate.
- **I2a/I2b (routed reads / ring).** The sharpest paper warning in the plan (C5):
  `ChannelProxy.read` → `readSync` on a shard thread **blocks the shard** for the
  full device latency (~80–100 µs NVMe) — every queued mutation and read on that
  shard waits. Today 32 READ workers absorb that; 8 shard threads don't. Attention
  points for the implementer:
  - I2a (sequential(), page-cache hits mostly) is safe; the hazard arrives with
    cache *misses* routed to shard threads. The A/B must include a **miss-storm cell**
    (cold chunk cache) and watch per-shard PendingTasks — if backlog explodes, that's
    a hurdle-log entry, not a silent p99 number.
  - Sync-per-thread caps the ring at QD1 per shard: phase-2 data will show how much
    of the ring's value that leaves. The full win needs multiple in-flight reads per
    shard — i.e. continuations through the read path, which is out of PoC scope.
    State this in the increment spec so nobody oversells I2b; a cheap intermediate
    if profiles justify it: batch-submit adjacent read tasks from the inbox in one
    `enter`, reap together (keeps the sync facade per task, amortizes the syscall).
  - The io-wq fallback threads (buffered-IO misses that can't complete inline) are
    unpinned kernel workers — phase-2's iou-wrk placement data feeds this.
- **I3 (async coordinator).** The outstanding-ops limiter is not just backpressure
  bookkeeping — their fig 3 shows tail growing monotonically with concurrent
  connections; the limiter is what bounds the PoC's tail under overload. Size it from
  the measured concurrency-vs-tail curve, not a constant. Continuation-executor
  choice: see phase-3 note (cache-hot completion vs extra hop).
- **I4 (commitlog/writeOrder/allocator).** I4c is Sphinx's LSMA, one-for-one:
  per-thread allocator, slab segments, whole-segment reclaim — cite it as prior art
  in the CEP. The residual global `SubPool.allocated` CAS is a shared-everything
  leak the paper predicts will limit scalability *eventually* — the step-2
  measure-first condition is right; the paper just tells us what to look for
  (CAS retry/cache-line profile on the allocation path at high core counts).
  Commitlog: option (b)'s per-mutation hop = paper item 1+3; favors (a).
- **I5 (inbound dispatch).** A replica-bound message today touches: NIC RX-queue
  core → softirq core → netty inbound loop → (I5) shard thread — up to 4 cores,
  vs the paper's 3-core bounce complaint. I5 removes the Stage hop but *adds* the
  netty→shard hand-off; net win depends on the wake cost (C2) and on IRQ/loop
  affinity (C1) — which is why the rig environment capture is load-bearing for I5's
  A/B specifically. The large-message Stage fallback is aligned with their
  payload-copy finding (item 3) — keep it.

  **Prior art — the three steering strategies.** Getting a request onto its owning
  core can happen (i) client-side, (ii) server-side, or (iii) in the NIC:
  - *Client-side — ScyllaDB shard-aware drivers.* Scylla owns each client connection
    on exactly one shard for its lifetime (no NTR-pool equivalent; the connection's
    shard runs coordinator logic). Their forked drivers exploit this: the CQL
    handshake advertises `SCYLLA_NR_SHARDS`, the sharding algorithm, and a
    shard-aware port (19042) where shard assignment is deterministic
    (`source port % nr_shards`); the driver opens one connection per shard per node,
    computes token → shard with the server's algorithm, and sends each statement on
    the owning shard's connection — zero in-node hop. Non-shard-aware clients land on
    an arbitrary shard and Scylla pays an internal SMP-queue hop to the owner —
    structurally the same hand-off as our I1. Caveat when citing as precedent:
    connection-per-shard makes connection imbalance into shard imbalance; their
    drivers spend real effort on connection distribution. Our keep-netty/keep-NTR PoC
    dodges that skew class but always pays the one in-node hop.
  - *Server-side message passing* — Sphinx, and our PoC (I1/I5): cheap to adopt
    (no protocol change), pays the wake-up per steered request (C2).
  - *NIC steering* — the paper's "programmable NIC offload" future work; flow-based
    steering today can't see keys.
  A shard-aware CQL protocol extension (Scylla's strategy on Cassandra) is the known
  end-state for eliminating the in-node hop — it sits in the roadmap's conscious
  defers (item 13), to be picked up only if post-I5 profiles show the netty→shard
  hand-off is the remaining bottleneck.

### Phase 5 — decision / CEP

- **Motivation ammunition:** independent, peer-reviewed evidence that TPC + data
  partitioning cuts KV-store tail latency up to 71% on commodity Linux — same
  hardware class, same kernel family as our rig. Use it next to the 10993 history.
- **The 2016 objection gets a mechanism:** 10993's "2× p99, never root-caused" now
  has two candidate explanations the CEP can name — (i) uncontrolled IRQ/steering
  environment (the paper's dominant variable), (ii) wake-up steering costs at
  under-distributed load (their low-concurrency weakness). Our PoC design answers
  both by construction: environment pinned and recorded per cell (C1), tail gates at
  target throughput with characterized low-load cells (C3).
- **Objection pre-answers:** "TPC hurts at low load" → yes, by design, characterized
  and bounded (C3). "Hot partitions starve a core" → shared-nothing ceiling is known
  (C4); shared-something scope + skew stance + backlog/misrouted instruments.
- **Defers section gets prior art:** the deferred shard-aware client protocol is not
  speculative — ScyllaDB ships it today (shard-aware drivers + deterministic
  shard-assignment port; see the I5 prior-art note above). The CEP can state the
  full steering ladder — PoC pays the in-node hop (server-side steering), the
  protocol extension eliminates it (client-side), NIC offload is the research
  frontier — and show the deferral is a sequencing choice with a known destination,
  not an unsolved problem.
