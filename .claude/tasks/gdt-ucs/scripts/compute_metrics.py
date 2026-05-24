#!/usr/bin/env python3
"""Compute headline comparison metrics from a `run_comparison.sh` output dir.

Reads each condition's pre/post snapshots and stress.parquet, computes:
  - bytes_compacted_delta
  - bytes_disk_used_delta
  - user_bytes_written       (from easy-cass-stress ops × avg row size)
  - db_waf                   ((compacted + disk_used delta) / user_bytes)
  - ops_per_second, p99_ms   (from stress log summary)

Prints a markdown table comparing all conditions, and writes summary.json
in the run dir.

Usage:
    compute_metrics.py <run-dir>

The output is intentionally simple — full per-operation latency CDFs can be
done later with a notebook against the parquet files.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def parse_stress_summary(stress_log: Path) -> dict:
    """Best-effort extraction of headline numbers from an easy-cass-stress log."""
    if not stress_log.exists():
        return {}
    text = stress_log.read_text(errors="replace")
    result: dict = {}
    patterns = [
        ("ops_per_second", r"(?:Ops/sec|throughput)\s*[:=]\s*([\d.,]+)"),
        ("total_operations", r"(?:Total operations|operations)\s*[:=]\s*([\d_,]+)"),
        ("p99_latency_ms", r"p99\s*[:=]\s*([\d.]+)\s*ms"),
        ("mean_latency_ms", r"mean\s*[:=]\s*([\d.]+)\s*ms"),
        ("errors", r"(?:errors|failures)\s*[:=]\s*(\d+)"),
    ]
    for key, pattern in patterns:
        m = re.search(pattern, text, re.IGNORECASE)
        if m:
            raw = m.group(1).replace(",", "").replace("_", "")
            try:
                result[key] = float(raw)
            except ValueError:
                continue
    return result


def load_snapshot(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}


def summarise_condition(cond_dir: Path) -> dict:
    pre = load_snapshot(cond_dir / "pre.json")
    post = load_snapshot(cond_dir / "post.json")
    stress = parse_stress_summary(cond_dir / "stress.log")

    compacted_delta = post.get("bytes_compacted", 0) - pre.get("bytes_compacted", 0)
    disk_delta = post.get("bytes_disk_used", 0) - pre.get("bytes_disk_used", 0)

    # Crude estimate of user bytes: total_operations × avg_row_size_bytes (assumed 200B).
    # easy-cass-stress doesn't trivially report bytes-issued; the parquet has per-op
    # rows that can be summed for a precise number. Defer the precise version to v2.
    total_ops = stress.get("total_operations", 0)
    user_bytes_estimated = int(total_ops * 200)

    if user_bytes_estimated > 0:
        db_waf = (compacted_delta + max(disk_delta, 0)) / user_bytes_estimated
    else:
        db_waf = None

    return {
        "ops_per_second": stress.get("ops_per_second"),
        "p99_latency_ms": stress.get("p99_latency_ms"),
        "mean_latency_ms": stress.get("mean_latency_ms"),
        "total_operations": stress.get("total_operations"),
        "bytes_compacted_delta": compacted_delta,
        "bytes_disk_used_delta": disk_delta,
        "user_bytes_estimated": user_bytes_estimated,
        "db_waf_estimated": db_waf,
        "errors": stress.get("errors", 0),
    }


def render_markdown(per_condition: dict) -> str:
    lines = ["# GDT-aware UCS comparison\n"]
    lines.append("| condition | ops/sec | p99 ms | DB WAF (est) | compacted Δ (bytes) | errors |")
    lines.append("|---|---:|---:|---:|---:|---:|")
    for name in ("baseline", "gdt", "twcs"):
        s = per_condition.get(name)
        if s is None:
            lines.append(f"| {name} | — | — | — | — | — |")
            continue
        ops = f"{s['ops_per_second']:.0f}" if s.get("ops_per_second") else "—"
        p99 = f"{s['p99_latency_ms']:.2f}" if s.get("p99_latency_ms") else "—"
        waf = f"{s['db_waf_estimated']:.2f}" if s.get("db_waf_estimated") is not None else "—"
        cmp_d = f"{s['bytes_compacted_delta']:,}"
        errs = s.get("errors", 0)
        lines.append(f"| {name} | {ops} | {p99} | {waf} | {cmp_d} | {errs} |")
    lines.append("")
    headline = compute_headline(per_condition)
    if headline:
        lines.append("## Headline\n")
        for k, v in headline.items():
            lines.append(f"- **{k}**: {v}")
    return "\n".join(lines)


def compute_headline(per_condition: dict) -> dict:
    out = {}
    baseline = per_condition.get("baseline", {})
    gdt = per_condition.get("gdt", {})
    twcs = per_condition.get("twcs", {})

    def safe_pct(better, worse):
        if not better or not worse or worse == 0:
            return None
        return round(100.0 * (1.0 - better / worse), 2)

    waf_pct = safe_pct(gdt.get("db_waf_estimated"), baseline.get("db_waf_estimated"))
    p99_pct = safe_pct(gdt.get("p99_latency_ms"), baseline.get("p99_latency_ms"))
    if waf_pct is not None:
        out["gdt_vs_baseline_db_waf_pct"] = f"{waf_pct}%"
    if p99_pct is not None:
        out["gdt_vs_baseline_p99_pct"] = f"{p99_pct}%"

    if twcs.get("db_waf_estimated") and gdt.get("db_waf_estimated"):
        gap = (gdt["db_waf_estimated"] - twcs["db_waf_estimated"]) / twcs["db_waf_estimated"]
        out["gdt_vs_twcs_db_waf_gap"] = f"{gap * 100:.2f}%"
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: compute_metrics.py <run-dir>", file=sys.stderr)
        return 2
    run_dir = Path(sys.argv[1])
    if not run_dir.exists():
        print(f"No such dir: {run_dir}", file=sys.stderr)
        return 2

    per_condition = {}
    for cond_dir in sorted(p for p in run_dir.iterdir() if p.is_dir()):
        per_condition[cond_dir.name] = summarise_condition(cond_dir)

    summary = {"conditions": per_condition, "headline": compute_headline(per_condition)}
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2))
    md = render_markdown(per_condition)
    (run_dir / "summary.md").write_text(md)
    print(md)
    return 0


if __name__ == "__main__":
    sys.exit(main())
