# Research report — Upstream landscape: 10989 / CEP / dev@ (verified 2026-07-05)

## 1. CASSANDRA-10989 current state (JIRA REST API)

- "Move away from SEDA to TPC" — meta-ticket, filed 2016-01-08 by **Aleksey Yeschenko**;
  TPC proposal credited to **Benedict Elliott Smith** (NGCC talk).
- Open / Unresolved. No assignee, no fix version, **zero comments** (REST /comment total: 0).
  16 votes, 96 watchers. Last activity 2019-04-16 (likely bulk edit — real activity earlier).
- Structure via links: contains CASSANDRA-8457 "nio MessagingService" (Resolved); contains
  CASSANDRA-10994 "stage 1" (Open; sub-tasks: 10993 "fully non-blocking read/write paths,
  eliminate stages" Open; 5863 "in-process page cache" Resolved); supersedes 8520 "Prototype
  thread per core" (Won't Fix). Duplicates: 10528 (RxJava), 10575 (Netty storage protocol).
- Description explicitly rejects Linux AIO ("only properly implemented for xfs") — pre-io_uring;
  the hook for a new proposal.
- **Bottom line: abandoned upstream (DataStax TPC stayed in DSE). Revival = NEW proposal.**

## 2. CEP process (cwiki: Cassandra Enhancement Proposals)

- Discretionary but "highly recommended … for significant user-facing or changes that cut
  across multiple subsystems" — TPC/scheduler rework qualifies unambiguously.
- Required sections: Scope; Goals & non-goals; Approach; Operational implications (migration,
  config, tooling & metrics); Test plan (performance, correctness, failure, boundary);
  Timeline; Mailing list/Slack; Related JIRAs.
- Flow: wiki page (next free number) → `[DISCUSS] CEP-N` on dev@ → `[VOTE]` → consensus =
  3 binding +1, no binding vetoes, 72 h. No mandated DISCUSS duration; large CEPs take months.

## 3. Related modern CEPs/tickets (2022–2026)

- Corrections: **CEP-27** = Generic API for Internal Data Collections (Draft) — unrelated.
  **CASSANDRA-17020** = cqlshlib failure (Resolved) — unrelated.
- **No CEP exists** (any status) for thread-per-core, io_uring, async I/O, direct I/O, or
  scheduler rework (full index checked). Nearest-adjacent: CEP-41 rate limiter (Draft), CEP-49
  hw-accelerated compression (Adopted), CEP-11/19 memtable, CEP-26 UCS.
- io_uring JIRAs (only 2 in project): **CASSANDRA-19887** "Support Netty io_uring Transport"
  (Triage Needed, 2024-09-02, reporter **Sam Lightfoot**; comments: SL 2026-03-24 on YDB
  io_uring/IOMMU blog; Alan Wang 2026-03-27). **CASSANDRA-21175** "Upgrade to Netty 4.2"
  (Triage Needed, 2026-02-15, Dmitry Konstantinov; rationale includes io_uring access).
- **Direct I/O incremental precedent** (landing I/O changes without a CEP): umbrella
  CASSANDRA-14466 "Enable Direct I/O" (In Progress); shipped 18464 (commitlog 5.0), 19987
  (compaction reads 6.0), 21147 (cursor compaction 6.0), 21134 (background writes, Resolved
  2026-06-24, 6.0-alpha2/7.0); open 19988, 19707, 21382, 20087, 13778.
- Commitlog sharding: no dedicated ticket.
- Adopted CEPs touching execution path on trunk: CEP-15 Accord (modules/accord submodule;
  20608 JDK21 compile fixed 6.0-alpha1), CEP-45 Mutation Tracking (e.g. 20926). Trunk = 7.0
  series (21301 fixVersion 7.0).

## 4. JDK roadmap

- trunk + cassandra-6.0 build.xml: `java.supported = 11,17,21` (line 48).
- CASSANDRA-18831 JDK21 support — Resolved 6.0-alpha1. CASSANDRA-20681 "JDK17 production-ready
  for 5.0" — Resolved (dev thread 2025-05-26).
- **CASSANDRA-21171 "JDK25 support" — Patch Available** (updated 2026-06-24, reporter Simon
  Karalyus, assignee Jeremiah Jordan). No fixVersion yet.
- Dev thread "[DISCUSS] How we handle JDK support" (May 2025): Jon Haddad pushing faster JDK
  drops; policy timetable cited "6.0: 17+21 (2025); 7.0: 21+24 (2026); 8.0: 24+27 (2027)";
  motivations: generational ZGC, FFM/arenas, virtual threads.
- No recorded decision to require JDK 22+. FFM io_uring binding would have to be optional/
  conditionally-loaded (JDK 25 path once 21171 lands) — same pattern as netty-native/epoll.
  UNVERIFIED whether trunk-7.0 drops 11/17 (cf. 18688 script-level constraints).

## 5. dev@ temperature (ponymail API, 2024-01→2026-07; control "compaction" = 651 hits)

- **io_uring: zero mentions.** "epoll" only in a ppc64le test-failure thread (early 2024).
- **"thread per core": zero phrase hits.** TPC hits = TPC-C/TPC-H benchmarks in Nov-2025
  "SQL support in Cassandra" threads.
- SEDA: 23 mentions, all in ONE thread ("Generic Purpose Rate Limiter", Jan–Sep 2024 → CEP-41).
  Jeff Jirsa 2024-01-19: *"The SEDA model is bad at back pressure and deferred cost makes it
  non-obvious which resource to slow to ensure stability."*
- Read: nobody is discussing TPC/io_uring — **greenfield, not contested; zero momentum to
  ride**. Perf-architecture energy: direct I/O, JDK modernization, Accord, mutation tracking.

Verification caveats: lists.apache.org threads pulled via ponymail REST (JS-rendered pages);
JIRA via public REST/JQL (can miss attachment-only mentions).
