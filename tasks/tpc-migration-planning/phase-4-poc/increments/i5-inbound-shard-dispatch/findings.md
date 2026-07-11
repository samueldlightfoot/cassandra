# Findings — I5 inbound shard dispatch (pulled forward) + I1-close

Origin: 2026-07-11 pivot. Two directives — "RF=3 is the assumption even for single-node
tests; an RF=1-only mechanism means little" and "move the dispatch point upstream" — plus a
correctness finding (below) redirect the increment away from late-routing + lock-skip toward
ingress routing with the lock kept. Grounded by two subagent reviews: an Explore seam-verify
and a Fable design pressure-test (verdict PROCEED-WITH-CHANGES).

## The correctness finding that forces the pivot
`skip_lock` (owner shard thread *skips* the memtable writeLock on an owner-check) is safe only
at RF=1. At RF≥3 unrouted writers exist — hint delivery (`HintVerbHandler`), read-repair, LWT
commit — that write the same table's memtable on their own threads and take the writeLock. A
`ReentrantLock` excludes only threads that also take it, so a *skipping* owner races a
*lock-taking* hint thread on the `InMemoryTrie` (single-mutator contract violated). No cheap
RF≥3-safe owner-skip exists: a presence-counter/seqlock needs a StoreLoad fence on the fast
path (≈ the CAS it removes); biased locking is gone from the JDK; the real fix is routing every
writer population through the shard (CEP-era). Prize ≈ 20–40 ns against a µs-scale apply. So:
**drop skip_lock; keep the lock (single-writer routing makes it uncontended).**

## Hop accounting — why I5 is I1's completion, not a later increment
Replica write path, RF≥3 (each wake = a thread handoff):

| Arm | Path | Wakes |
|---|---|---|
| trunk / flag-off | netty loop → Stage.MUTATION (apply inline on SEP worker) | 1 |
| I1 flag-on, no I5 | netty loop → Stage.MUTATION (doVerb prologue) → shard executor (apply) | **2** |
| I1 + I5 flag-on | netty loop → shard executor (doVerb + apply inline) | 1 |

I1-alone **adds** a wake at RF≥3 and plausibly fails its own multi-node tail gate; I5 restores
parity. Vs *trunk* the win is queue discipline + core affinity, not hop count — µs-scale wake
delta against 0.3–3 ms p99s. Sign flips with load and placement: low load worse
(`sequential()` park ≈ 1–10 µs vs SEP spin sub-µs); saturation better via affinity; NIC-IRQ /
loop core placement decides the sign, and **loopback (colocated JVMs) bypasses the IRQ path
entirely** so it cannot express that variable. → gate I5 on mechanism evidence + tail-neutrality,
never a headline p99.

## Verified seams (Explore, on-branch 2026-07-11)
- **Dispatch seam:** `InboundMessageHandler.java:429` `header.verb.stage.execute(ExecutorLocals.create(state), task)` — NOT drifted. Small messages are fully deserialized at `:171` *before* dispatch, so the partition key is knowable; large messages deserialize on-stage (not routable — matches the D4 small-message allowlist).
- **Verb→Stage:** `Verb.java:202` MUTATION_REQ→MUTATION; `:228/:230` READ_REQ + RANGE_REQ→READ, sharing `ReadCommand.serializer`.
- **In-JVM dtest gotcha:** delivery bypasses `InboundMessageHandler`, going `MessagingService.inboundSink.accept()` → `doVerb` (`Instance.java:577/:593`). A router at `:429` is **not** exercised by in-JVM dtests without a separate hook at the inboundSink / `receiveMessageRunnable` seam — else flag-on dtests are vacuous.
- **Single-node linchpin:** a single-node write never reaches `InboundMessageHandler`; the coordinator==replica case takes `performLocally` (isSelf branch, `StorageProxy.java:1861/:1921`); only remote replicas go through `sendWriteWithCallback`. **Inbound dispatch is inert single-node** — which is why the directive forces multi-node RF=3.
- **Pre-apply work on the Stage thread today:** expiry (`MutationVerbHandler.java:56`), `MessageParams.reset()` (`:63`), size validation (`:64`), `WriteThresholds` (`:65`), `forwardToLocalNodes` (`:67-69`), TCM token-ownership/epoch/schema (`AbstractMutationVerbHandler.java:59-64`, with a **blocking** peer/CMS fetch at `:93/:139/:189`), `MessageParams.capture()` (`:84`).

## Where each concern must run under I5 (Fable)
- **Stays on the netty loop:** capacity ACQUIRE (`:145/:238` — the back-pressure authority), arrival-expiry (`:135`), deserialize (`:161-216`), tracing/ExecutorLocals (needs a new `ShardExecutors.execute(ExecutorLocals, int, Runnable)` overload — the executors are `localAware()`), plus the router's own `route()` lookups (new per-message loop work — account for it).
- **Moves to the shard thread, safe:** execution-expiry (`:444` — now measures the shard inbox, honest per-shard drop accounting), `MessageParams` (`FastThreadLocal`; reset + capture coherent on one thread), capacity RELEASE (`:467-470`; atomic add + WaitQueue signal, already called from foreign threads today).
- **Must NOT silently move — two loop-side fallback guards:**
  - **TCM blocking catch-up.** `checkTokenOwnership`/`checkSchemaVersion` do a blocking peer/CMS fetch when the message epoch is ahead — stalls the whole shard for a network RTT during topology change (violates the no-blocking-on-shard invariant). Guard: `if (message.epoch().isAfter(ClusterMetadata.current().epoch)) → stage.execute` (cheap volatile read, rare path).
  - **forwardToLocalNodes** (multi-DC `FORWARD_TO`). Forwarding to other replicas would queue behind a hot shard → cross-node head-of-line. Guard: FORWARD_TO-bearing → stage fallback.
- **Self-enqueue trap:** with I5, `doVerb` already runs on the owning shard thread; the as-built `applyMutation` re-submits the apply to the *same* shard queue. Add an owner-inline bypass: `if (ShardExecutors.currentShardId() == shardId) apply.run()`.
- **Ordering:** trunk guarantees none (Stage.MUTATION is a concurrent SEP pool; applies commute by timestamp). Per-shard serial execution strictly strengthens it. Watch item: capacity is held while a message is queued, so a hot shard lengthens holds → endpoint-reserve exhaustion pauses reading that connection, head-of-line-blocking *other shards'* messages on it. Detect via per-shard PendingTasks (D6) + I5's fallback count.

## Multi-node methodology (Fable)
- **3 nodes, RF=3, CL=QUORUM both directions.** Every node is a replica for every key → each write exercises coordinator-local apply (I1's `performLocally`) plus two remote MUTATION_REQ legs (I5's seam). QUORUM (not ONE) puts one remote leg in every measured latency — the path I5 changes — and is the production-analogous CL.
- **Hint-flood correctness cell (non-gate):** pause one node mid-run, resume, let hint delivery flood concurrently with foreground routed writes → assert `misroutedPuts` flat, no `InMemoryTrie` corruption, all data readable. This is the RF≥3 coexistence proof the single-node rig can't produce.
- **Physical placement:** minimal honest = 3 separate same-DC machines + the off-box loadgen (post `offbox-baseline-clean.md`). Colocated 3-JVM = loopback, no NIC IRQ path — records queue-discipline + affinity only; the sign-deciding IRQ variable becomes unmeasurable. If colocation is forced, record loopback as a first-class caveat.
- **New confounds vs single-node:** network jitter (record per-cell RTT), 3 independent GC processes (the GC overlay is the union of all three logs), per-node compaction drift, hint residue between rungs (assert hints dirs empty pre-cell), rolling-restart cache resets (flags are startup-pinned). Hygiene: same build on all nodes, all flip together (never measure mixed), re-warm per arm, interleave arm order (A,B,B,A) across 3 iterations.
- **Keep the single-node track.** I5 is single-node-inert, so I2a/I2b/I3/I4 mechanism-isolation cells are untouched by I5 landing early. Multi-node is an *added* track; it must be pinned as an explicit `poc-criteria.md` amendment **before** I5's gate cells run (D9 criteria-before-code).
