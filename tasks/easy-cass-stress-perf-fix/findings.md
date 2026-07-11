# easy-cass-stress throughput investigation — CLOSED / SUPERSEDED

**Status (2026-07-10): closed. Most of the original content was WRONG.** The corrected,
canonical understanding of the tool now lives in:
- `tasks/tpc-migration-planning/phase-4-poc/STRESS-RUNBOOK.md` — one-page how-to.
- `tasks/tpc-migration-planning/phase-4-poc/stress-tool-behaviour.md` — mechanism + evidence.

Read those, not this. This file is kept only as a record of what was chased and retracted.

## What this investigation got WRONG (do not reuse)
- ❌ **"The tool delivers only ~0.1–0.25× of nominal `--rate`" / "~0.5–0.55× even when
  clean"** (e.g. "40k→5k, 200k→27k, 800k→98k, 2M→231k"). FALSE. On a **fresh** dataset,
  measured server-side, a single process delivers **≈ offered** in the clean regime
  (40k→37.7k, 100k→95.8k = ratio 0.94–0.96). The old low numbers came from the **skewed
  ~15 GB dataset** (compaction pressure) + client-side measurement + over-driving `--rate`.
- ❌ **"Fix = offer 4–10× the target `--rate`."** No. Offer roughly what you want (clean
  regime), or use `--maxwlat/--maxrlat` to let latency pick the rate.
- ❌ **"Random.getText() build-once = 14× fix."** Dead end (~noise). `the-fix.diff` is NOT
  applied and must not be; the rig tool stays UNFIXED/clean. (The doc itself had already
  retracted this.)
- ❌ **The "ruled-out" throughput table (~30–49k flat across every knob).** Measured at
  `--rate ~2M` — i.e. inside the **collapse regime** on the skewed dataset. It only shows
  "once you over-drive past the knee, nothing recovers throughput," which is expected.

## What was actually TRUE (kept)
- The tool is `cassandra-easy-stress` (`/root/repos/cassandra-easy-stress`,
  `feature/csv-latency`, driver-core 4.19.0), pinned to client cores 8–11; Cassandra 0–7.
- Client output IS reliable at sane rates (matches `nodetool` counts) — only degrades under
  extreme overload.
- Random.getText() rebuild-per-op is real but NOT the bottleneck; the fix is noise.

## The actual root cause of the whole rabbit hole
Two compounding measurement errors, not a tool bug: (1) benchmarking on a **skewed/growing
dataset**, and (2) **over-driving `--rate`** (and later stacking processes / passing `--rate`
together with `--maxwlat`), which puts the open-loop generator into a shared-RateLimiter
**contention collapse** where delivered throughput *drops* and latency becomes
coordinated-omission noise. Correct method: single process, `--maxwlat/--maxrlat` alone,
fresh dataset, measured server-side. See the runbook.
