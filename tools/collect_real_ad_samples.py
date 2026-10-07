#!/usr/bin/env python3
"""Collect real ad samples from a device while the user uses the phone.

Usage:
    python collect_real_ad_samples.py --serial 479901bd [--out samples.json] [--seconds N]

The user simply plays with the phone (open apps, watch ads). The script
reads the app's finalized metadata traces (formal or debug build) and records
only the non-content fields per session: package, activity, strategy at
session start, session id, result, action attempts, final candidate type,
confirmed latency, and rule origin. At the end it prints a grouped summary
(WECHAT / AGGRESSIVE / CRAZY ...).

Privacy: no chat content, no input text, no passwords, no SMS, no card
numbers, and no full accessibility trees are collected. Only the session
metadata rows are read.
"""

import argparse
import json
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from read_ad_blackbox import read_records

PKG = "app.bypassads"

SELECT_COLS = (
    "session_id, package_name, activity_name, strategy_mode, result, "
    "action_attempts, candidate_type, confirmed_latency_ms, rule_origin, "
    "start_time, end_time"
)


def read_sessions(serial: str, since_ms: int, limit: int = 200, package: str = PKG) -> list[dict]:
    # The private WAL may not be readable over USB. Never infer live outcomes
    # from the standalone main database; the app writes these after finalizing.
    columns = [c.strip() for c in SELECT_COLS.split(",")]
    return [{key: r.get(key) for key in columns}
            for r in read_records(serial, package, limit=limit)
            if r.get("end_time", 0) > since_ms]


def summarize(samples: list[dict]) -> dict:
    summary: dict[str, Counter] = defaultdict(Counter)
    for s in samples:
        pkg = s.get("package_name", "?")
        host = "WECHAT" if pkg == "com.tencent.mm" else "ALIPAY" if pkg == "com.eg.android.AlipayGphone" else "OTHER"
        summary[host][s.get("result", "?")] += 1
        summary["by_strategy"][s.get("strategy_mode", "?")] += 1
        summary["by_candidate"][s.get("candidate_type") or "?"] += 1
    return {k: dict(v) for k, v in summary.items()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="479901bd", help="adb serial")
    parser.add_argument("--package", default=PKG, help="Formal or debug app package")
    parser.add_argument("--out", default="real_ad_samples.json", help="output file")
    parser.add_argument("--seconds", type=int, default=0, help="collect for N seconds (0 = until Ctrl+C)")
    args = parser.parse_args()

    samples: list[dict] = []
    # Host/device clocks can differ. Identify newly finalized sessions by ID,
    # rather than silently dropping results earlier than the PC's wall clock.
    seen_ids = {r["session_id"] for r in read_sessions(args.serial, 0, limit=50, package=args.package)}
    print(f"collecting new finalized traces on {args.serial} ... press Ctrl+C to stop (every 5s)")
    deadline = time.time() + args.seconds if args.seconds else None
    try:
        while True:
            if deadline and time.time() > deadline:
                break
            new_samples = [r for r in read_sessions(args.serial, 0, limit=50, package=args.package)
                           if r["session_id"] not in seen_ids]
            for s in new_samples:
                samples.append(s)
                seen_ids.add(s["session_id"])
            time.sleep(5)
    except KeyboardInterrupt:
        pass

    report = {
        "collectedAt": datetime.now().isoformat(timespec="seconds"),
        "sessionCount": len(samples),
        "samples": samples,
        "summary": summarize(samples),
    }
    out = Path(args.out)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{len(samples)} sessions -> {args.out}")
    print("summary:", json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
