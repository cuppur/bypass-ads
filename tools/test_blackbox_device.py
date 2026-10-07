#!/usr/bin/env python3
"""Check one persistent ad/one failure record under real timer events."""
import argparse
import json
import subprocess
import time
import tempfile
from pathlib import Path
from read_ad_blackbox import read_records


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    def adb(*command):
        p = subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, timeout=30)
        if p.returncode:
            raise RuntimeError(p.stderr.decode("utf-8", "replace")[:200])
        return p.stdout.decode("utf-8", "replace").strip()
    services = adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
    enabled = adb("shell", "settings", "get", "secure", "accessibility_enabled")
    try:
        component = "app.bypassads.debug/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        store_path = "/sdcard/Android/data/app.bypassads.debug/files/store/store.json"
        adb("shell", "am", "start", "-n", "app.bypassads.debug/li.songe.gkd.MainActivity")
        time.sleep(1)
        config = json.loads(adb("exec-out", "cat", store_path))
        config.update(bypassAdStrategyMode=1, enableMatch=True, enableGenericFallback=True,
                      enableAutomator=True, automatorMode=1)
        adb("shell", "am", "force-stop", "app.bypassads.debug")
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / "debug-fixture.json"
            fixture.write_text(json.dumps(config), encoding="utf-8")
            adb("push", str(fixture), store_path)
        adb("shell", "am", "start", "-n", "app.bypassads.debug/li.songe.gkd.MainActivity")
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", component)
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        time.sleep(4)
        config = json.loads(adb("exec-out", "cat", store_path))
        if not all(config.get(key) for key in ("enableAutomator", "enableMatch", "enableGenericFallback")):
            raise RuntimeError("Debug accessibility fixture did not become ready")
        print("Fixture: AGGRESSIVE; matching/generic/service enabled", flush=True)
        before = {r["session_id"] for r in read_records(args.serial, "app.bypassads.debug", "app.bypassads.testad", 100)}
        adb("shell", "am", "start", "--activity-single-top", "-n", "app.bypassads.testad/.MainActivity", "--es", "scenario", "l")
        # Scene L changes a separate timer every second for 25 seconds.
        # This outlives both the click budget and the 15-second session limit.
        time.sleep(22)
        records = [r for r in read_records(args.serial, "app.bypassads.debug", "app.bypassads.testad", 100)
                   if r["session_id"] not in before]
        passed = len(records) == 1 and records[0]["result"] == "FAILURE_CONFIRMED" and records[0]["action_attempts"] == 2
        print(json.dumps({"scene": "persistent_ad_with_timer_events", "new_records": len(records),
                          "results": [r["result"] for r in records], "attempts": [r["action_attempts"] for r in records],
                          "passed": passed}), flush=True)
        # Clear the failed ad, then show another in the SAME Activity.
        # Stable absence must release suppression and re-arm exhausted rules.
        adb("shell", "am", "start", "--activity-single-top", "-n", "app.bypassads.testad/.MainActivity", "--es", "scenario", "w")
        time.sleep(2)
        after_failure = {r["session_id"] for r in read_records(args.serial, "app.bypassads.debug", "app.bypassads.testad", 100)}
        adb("shell", "am", "start", "--activity-single-top", "-n", "app.bypassads.testad/.MainActivity", "--es", "scenario", "a")
        time.sleep(3)
        next_ad = [r for r in read_records(args.serial, "app.bypassads.debug", "app.bypassads.testad", 100)
                   if r["session_id"] not in after_failure]
        next_passed = len(next_ad) == 1 and next_ad[0]["result"] == "SUCCESS_CONFIRMED"
        print(json.dumps({"scene": "new_ad_after_failed_ad_cleared", "new_records": len(next_ad),
                          "results": [r["result"] for r in next_ad], "passed": next_passed}), flush=True)
        return 0 if passed and next_passed else 1
    finally:
        for key, original in (("enabled_accessibility_services", services), ("accessibility_enabled", enabled)):
            if original == "null":
                adb("shell", "settings", "delete", "secure", key)
            else:
                adb("shell", "settings", "put", "secure", key, original)
        print("Original accessibility settings restored", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
