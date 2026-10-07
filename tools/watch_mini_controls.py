#!/usr/bin/env python3
"""Bounded USB sampling of mini-program top-left control metadata.

No clicks, screenshots or page bodies. The temporary UI dump is read in
memory and removed from the device. Only exit labels, short numeric labels,
blank compact controls and ancestor geometry are retained.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--out", type=Path, default=Path("build/diagnosis/mini-corner-controls.jsonl"))
    args = parser.parse_args()
    temporary = "/data/local/tmp/bypass-mini-corner.xml"

    def adb(*command):
        p = subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, timeout=20)
        return p.stdout.decode("utf-8", "replace")

    def bounds(n):
        v = list(map(int, re.findall(r"-?\d+", n.get("bounds", ""))))
        return v if len(v) == 4 else [0, 0, 0, 0]

    def geometry(n):
        return {"class": n.get("class"), "id": n.get("resource-id", ""), "bounds": bounds(n),
                "clickable": n.get("clickable"), "enabled": n.get("enabled"), "child_count": len(n)}

    args.out.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + min(max(args.seconds, 1), 600)
    previous = None
    print("Top-left metadata sampling started; no phone interaction", flush=True)
    try:
        while time.monotonic() < deadline:
            try:
                adb("shell", "uiautomator", "dump", temporary)
                tree = ET.fromstring(adb("exec-out", "cat", temporary))
                nodes = list(tree.iter("node"))
                host = nodes[0].get("package") if nodes else ""
                if host not in ("com.tencent.mm", "com.eg.android.AlipayGphone"):
                    time.sleep(0.2)
                    continue
                win = bounds(nodes[0])
                parents = {c: p for p in tree.iter() for c in p}
                controls = []
                for n in nodes:
                    x1, y1, x2, y2 = bounds(n)
                    if not (0 < x2-x1 <= 420 and 0 < y2-y1 <= 260 and
                            x1 >= win[0] and y1 >= win[1] and
                            (x1+x2)/2 <= win[0]+(win[2]-win[0])*0.45 and
                            (y1+y2)/2 <= win[1]+(win[3]-win[1])*0.3):
                        continue
                    text, desc = n.get("text", "").strip(), n.get("content-desc", "").strip()
                    exit_labels = [v for v in (text, desc) if re.fullmatch(
                        r"(?:关闭(?:广告|弹屏)?|關閉|跳过(?:广告)?|跳過|close|skip|[×✕✖xX])(?:\s*[（(]?\d{1,2}\s*[sS秒]?[）)]?)?", v, re.I)]
                    number_labels = [v for v in (text, desc) if re.fullmatch(r"[（(]?\s*\d{1,2}\s*(?:[sS秒]|秒后关闭)?\s*[）)]?", v)]
                    kind = "exit" if exit_labels else "short_number" if number_labels else "blank" if not text and not desc else None
                    if kind is None:
                        continue
                    row = dict(kind=kind, labels=exit_labels or number_labels, **geometry(n))
                    chain = []
                    parent = parents.get(n)
                    for _ in range(3):
                        if parent is None or parent.tag != "node": break
                        chain.append(geometry(parent))
                        parent = parents.get(parent)
                    row["ancestors"] = chain
                    controls.append(row)
                packet = {"host": host, "window": win, "total_nodes": len(nodes), "controls": controls[:90]}
                encoded = json.dumps(packet, ensure_ascii=False, sort_keys=True)
                if encoded != previous:
                    packet["sample_time"] = int(time.time()*1000)
                    with args.out.open("a", encoding="utf-8") as out:
                        out.write(json.dumps(packet, ensure_ascii=False)+"\n")
                    print(json.dumps({"host": host, "nodes": len(nodes),
                        "exits": sum(c["kind"] == "exit" for c in controls),
                        "short_numbers": sum(c["kind"] == "short_number" for c in controls),
                        "blank_controls": sum(c["kind"] == "blank" for c in controls)}, ensure_ascii=False), flush=True)
                    previous = encoded
            except (ET.ParseError, subprocess.TimeoutExpired):
                print("Current UI sample unavailable", flush=True)
            time.sleep(0.2)
    finally:
        adb("shell", "rm", "-f", temporary)
    print("Sampling finished; temporary device dump removed", flush=True)


if __name__ == "__main__":
    main()
