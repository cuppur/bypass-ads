#!/usr/bin/env python3
"""Read finalized, non-content blackbox traces over USB.

The app mirrors each finished session into a local metadata JSON file. This
avoids reading a stale main SQLite database when its live WAL is private.
Only the newest 200 trace files are searched; full retained history and a
selected record are available through the Records page and its export.
"""
import argparse
import json
import re
import subprocess


def read_records(serial: str, package: str, host: str | None = None, limit: int = 20):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package):
        raise ValueError("Invalid app package")
    folder = f"/sdcard/Android/data/{package}/files/blackbox"
    p = subprocess.run(["adb", "-s", serial, "exec-out", "ls", folder], capture_output=True, timeout=30)
    names = [name for name in p.stdout.decode("utf-8", "replace").splitlines()
             if re.fullmatch(r"session-\d+-[A-Fa-f0-9-]+\.json", name)]
    records = []
    for name in sorted(names, reverse=True)[:200]:
        data = subprocess.run(["adb", "-s", serial, "exec-out", "cat", f"{folder}/{name}"], capture_output=True, timeout=30).stdout
        try:
            record = json.loads(data)
        except ValueError:
            continue
        if host is None or record.get("package_name") == host:
            records.append(record)
        if len(records) >= min(max(limit, 1), 200):
            break
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="app.bypassads")
    parser.add_argument("--host")
    parser.add_argument("--limit", type=int, default=20)
    args = parser.parse_args()
    print(json.dumps(read_records(args.serial, args.package, args.host, args.limit), ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
