#!/usr/bin/env python3
"""Phase 2 collector (expected-changes §2 collect-results.sh, in python).

Parses pulled results dirs:
  results/uring_fio_v1/<cell>-iter<n>.json   (+ <cell>-iter<n>.d/ samplers)
  results/uring_jmh_v1/<cell>.json           (+ <cell>.d/ samplers)
Emits results/summary-fio.csv, results/summary-jmh.csv, and prints the headline
tables + G1-G4 gate numbers (spec §1.1/§2.4). Median of 3 fio iterations; >10%
spread flagged. JMH sample-mode percentile key "99.9" existence is asserted on
first real output (fixture-vs-real lesson).
"""
import csv
import json
import statistics
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent / "results"
FIO = BASE / "uring_fio_v1"
JMH = BASE / "uring_jmh_v1"


def fio_rows():
    for f in sorted(FIO.glob("*-iter*.json")):
        cell, it = f.stem.rsplit("-iter", 1)
        try:
            j = json.loads(f.read_text())
        except json.JSONDecodeError:
            # in-flight cell snapshotted by a mid-sweep pull; final pull re-fetches it complete
            print(f"SKIP partial json: {f.name}", file=sys.stderr)
            continue
        job = j["jobs"][0]
        kind = "write" if cell.split("-")[1] in ("a4", "a5") else "read"
        s = job[kind]
        pct = s["clat_ns"]["percentile"]
        yield {
            "cell": cell, "iter": int(it), "kind": kind,
            "iops": s["iops"], "bw_MBps": s["bw_bytes"] / 1e6,
            "p50_us": pct["50.000000"] / 1e3, "p99_us": pct["99.000000"] / 1e3,
            "p999_us": pct["99.900000"] / 1e3,
            "usr_cpu": job["usr_cpu"], "sys_cpu": job["sys_cpu"], "ctx": job["ctx"],
            "error": job["error"],
            "iouwrk_max": iouwrk_max(FIO / f"{f.stem}.d" / "iouwrk-count.log"),
        }


def iouwrk_max(path):
    if not path.exists():
        return None
    vals = []
    for line in path.read_text().splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            vals.append(int(parts[1]))
    return max(vals) if vals else None


def jmh_rows():
    if not JMH.exists():
        return
    for f in sorted(JMH.glob("*.json")):
        cell = f.stem
        for entry in json.loads(f.read_text()):
            pm = entry["primaryMetric"]
            row = {
                "cell": cell, "jmh_mode": entry["mode"],
                "score": pm["score"], "unit": pm["scoreUnit"],
                "params": json.dumps(entry.get("params", {}), sort_keys=True),
                "iouwrk_max": iouwrk_max(JMH / f"{cell}.d" / "iouwrk-count.log"),
            }
            if entry["mode"] == "sample":
                sp = pm["scorePercentiles"]
                assert "99.9" in sp, f"{cell}: no 99.9 key in scorePercentiles: {list(sp)[:12]}"
                row.update(p50=sp["50.0"], p99=sp["99.0"], p999=sp["99.9"])
            yield row


def write_csv(path, rows):
    rows = list(rows)
    if not rows:
        return rows
    keys = sorted({k for r in rows for k in r}, key=lambda k: (k != "cell", k))
    with open(path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)
    return rows


def fio_median(rows):
    cells = {}
    for r in rows:
        cells.setdefault(r["cell"], []).append(r)
    out = {}
    for cell, its in cells.items():
        iops = sorted(x["iops"] for x in its)
        med = statistics.median(iops)
        spread = (iops[-1] - iops[0]) / med if med else 0
        out[cell] = {
            "iops": med, "spread": spread, "n": len(its),
            "p99_us": statistics.median([x["p99_us"] for x in its]),
            "p999_us": statistics.median([x["p999_us"] for x in its]),
            "bw_MBps": statistics.median([x["bw_MBps"] for x in its]),
            "ctx": statistics.median([x["ctx"] for x in its]),
            "iouwrk_max": max([x["iouwrk_max"] or 0 for x in its]),
            "errors": sum(1 for x in its if x["error"]),
        }
    return out


def jmh_thrpt(rows):
    """cell -> ops/s from thrpt entries."""
    out = {}
    for r in rows:
        if r["jmh_mode"] == "thrpt":
            assert "ops/s" in r["unit"], r
            out[r["cell"]] = r["score"]
    return out


def pct(a, b):
    return f"{100.0 * a / b:.1f}%" if b else "n/a"


def main():
    fio = write_csv(BASE / "summary-fio.csv", fio_rows())
    jmh = write_csv(BASE / "summary-jmh.csv", jmh_rows())
    med = fio_median(fio)
    thr = jmh_thrpt(jmh)

    print("== fio cells (median of iterations; spread = (max-min)/median) ==")
    for cell in sorted(med):
        m = med[cell]
        flag = " <<< SPREAD>10%" if m["spread"] > 0.10 else ""
        err = f" ERRORS={m['errors']}" if m["errors"] else ""
        print(f"{cell:28s} iops={m['iops']:>10.0f} bw={m['bw_MBps']:>8.1f}MB/s "
              f"p99={m['p99_us']:>8.1f}us p999={m['p999_us']:>9.1f}us "
              f"iouwrk<={m['iouwrk_max']:<3d} n={m['n']} spread={m['spread']:.1%}{flag}{err}")

    print("\n== headline 1: 1-thread io_uring vs 50-thread psync (A1, the TPC question) ==")
    for fs in ("ext4", "xfs"):
        base = med.get(f"{fs}-a1-psync-nj50", {}).get("iops")
        for qd in (32, 64):
            u = med.get(f"{fs}-a1-uring-qd{qd}", {}).get("iops")
            if u and base:
                print(f"{fs}: uring-qd{qd} {u:,.0f} vs psync-nj50 {base:,.0f} -> {pct(u, base)} (G1 needs >=70% at QD>=32)")

    print("\n== headline 4/G3: A4 buffered-write punt (iou-wrk max) + throughput ==")
    for fs in ("ext4", "xfs"):
        u, p = med.get(f"{fs}-a4-uring-qd32"), med.get(f"{fs}-a4-psync-nj1")
        if u and p:
            print(f"{fs}: uring iou-wrk<={u['iouwrk_max']} bw={u['bw_MBps']:.0f}MB/s | "
                  f"psync-nj1 bw={p['bw_MBps']:.0f}MB/s | ratio {u['bw_MBps']/p['bw_MBps']:.2f}x (G3 xfs needs ~0 punt AND >=1.5x)")

    print("\n== headline 5/G4 (fio side): hot QD1 — uring-qd1 should LOSE to psync-nj1 ==")
    for fs in ("ext4", "xfs"):
        u, p = med.get(f"{fs}-a3-uring-qd1"), med.get(f"{fs}-a3-psync-nj1")
        if u and p:
            print(f"{fs}: uring-qd1 {u['iops']:,.0f} vs psync-nj1 {p['iops']:,.0f} -> {pct(u['iops'], p['iops'])} (expect <100%)")

    if thr:
        # B cell -> its fio twin (same mechanism through the binding vs native)
        twins = {}
        for fs in ("ext4", "xfs"):
            twins.update({
                f"{fs}-b1-pread-qd1-direct-cold":    f"{fs}-a1-psync-nj1",
                f"{fs}-b1-sync-qd1-direct-cold":     f"{fs}-a1-uring-qd1",
                f"{fs}-b1-batched-qd1-direct-cold":  f"{fs}-a1-uring-qd1",
                f"{fs}-b1-batched-qd32-direct-cold": f"{fs}-a1-uring-qd32",
                f"{fs}-b1-batched-qd64-direct-cold": f"{fs}-a1-uring-qd64",
                f"{fs}-b3-pread-qd1-buffered-hot":   f"{fs}-a3-psync-nj1",
                f"{fs}-b3-sync-qd1-buffered-hot":    f"{fs}-a3-uring-qd1",
                f"{fs}-b3-batched-qd1-buffered-hot": f"{fs}-a3-uring-qd1",
                f"{fs}-b3-batched-qd32-buffered-hot": f"{fs}-a3-uring-qd32",
                f"{fs}-b4-pwrite-qd1":   f"{fs}-a4-psync-nj1",
                f"{fs}-b4-sync-qd1":     f"{fs}-a4-psync-nj1",
                f"{fs}-b4-batched-qd32": f"{fs}-a4-uring-qd32",
                f"{fs}-b5-pwrite-qd1":   f"{fs}-a5-psync-nj1",
                f"{fs}-b5-sync-qd1":     f"{fs}-a5-psync-nj1",
                f"{fs}-b5-batched-qd32": f"{fs}-a5-uring-qd32",
            })
        print("\n== headline 3/G2: B/A ratio per shape (JVM tax; flag <0.8) ==")
        for b, a in twins.items():
            if b in thr and a in med:
                ratio = thr[b] / med[a]["iops"]
                flag = " <<< G2 FLAG" if ratio < 0.8 else ""
                print(f"{b:36s} {thr[b]:>10.0f} ops/s / {a:22s} {med[a]['iops']:>10.0f} iops = {ratio:.2f}{flag}")

        print("\n== headline 2: JMH batched QD64 vs fio psync-nj50 (Level-B TPC question) ==")
        for fs in ("ext4", "xfs"):
            b = thr.get(f"{fs}-b1-batched-qd64-direct-cold")
            a = med.get(f"{fs}-a1-psync-nj50", {}).get("iops")
            if b and a:
                print(f"{fs}: JMH batched-qd64 {b:,.0f} ops/s vs fio psync-nj50 {a:,.0f} -> {pct(b, a)}")

        print("\n== G4 (Level B): hot QD1 ring sync must be SLOWER than pread ==")
        for fs in ("ext4", "xfs"):
            s = thr.get(f"{fs}-b3-sync-qd1-buffered-hot")
            p = thr.get(f"{fs}-b3-pread-qd1-buffered-hot")
            if s and p:
                verdict = "PASS (sync slower)" if s < p else "SUSPECT HARNESS"
                print(f"{fs}: sync {s:,.0f} vs pread {p:,.0f} ops/s -> {pct(s, p)} {verdict}")


if __name__ == "__main__":
    sys.exit(main())
