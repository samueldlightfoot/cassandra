# CCX43 load-generator box (Hetzner Cloud, hel1 Helsinki) — hourly, DESTROY WHEN DONE

⚠️ SECRET — do NOT commit this file. Contains a root password. Scrub before any `git add`.

IPv4      62.238.35.142/32
IPv6      2a01:4f9:c013:f70b::/64
User      root
Password  Tpc9-Baseline#Load-2026xZ      (changed 2026-07-10 from the expired Hetzner temp pw)
Auth      SSH KEY installed (~/.ssh/id_ed25519) — `ssh root@62.238.35.142` works passwordless.

Specs: Ubuntu 26.04, 16 vCPU AMD EPYC-Milan (8 physical cores), 61 G RAM. Java 17 + sysstat installed.
Purpose: off-box load generator for the Phase-4.1 Cassandra baseline (see phase-4-poc/OFFBOX-HANDOFF.md).
COST: billed hourly — destroy the instance once the baseline curve is captured.
