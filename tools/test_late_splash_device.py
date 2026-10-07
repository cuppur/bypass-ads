#!/usr/bin/env python3
"""Debug-only on-device gate; restores other accessibility services in finally.

Requires installed app.bypassads.debug and testad debug APKs. UI dumps are
read in memory and removed from the device; only result tokens are emitted.
"""
import argparse
import json
import subprocess
import time
import tempfile
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--only", help="Comma-separated scene names for focused regression")
    parser.add_argument("--no-warm-return", action="store_true")
    parser.add_argument("--strategy", type=int, choices=(0, 1, 2), default=1)
    parser.add_argument("--visual-off", action="store_true", help="Verify the local visual toggle with Canvas-only scenes")
    parser.add_argument("--generic-off", action="store_true")
    parser.add_argument("--master-off", action="store_true")
    args = parser.parse_args()

    def adb(*command: str) -> str:
        result = subprocess.run(["adb", "-s", args.serial, *command], capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", "replace")[:200])
        return result.stdout.decode("utf-8", "replace").strip()

    def result_token(scene: str) -> str:
        path = "/data/local/tmp/bypass-late-test.xml"
        try:
            adb("shell", "uiautomator", "dump", path)
            xml = adb("exec-out", "cat", path)
            if f"测试已跳过 ({scene.upper()})" in xml:
                return "SKIPPED"
            if "测试已跳过" in xml:
                return "STALE_RESULT"
            if "错误：发生了不应执行的动作" in xml:
                return "MISCLICK"
            return "NO_ACTION" if f"BypassTestScene:{scene}" in xml else "STALE_ROOT"
        finally:
            adb("shell", "rm", "-f", path)

    services = adb("shell", "settings", "get", "secure", "enabled_accessibility_services")
    enabled = adb("shell", "settings", "get", "secure", "accessibility_enabled")
    component = "app.bypassads.debug/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
    activity = "app.bypassads.testad/.MainActivity"
    results = []
    try:
        # A fresh debug install defaults to CONSERVATIVE. The close scenario
        # explicitly requires AGGRESSIVE, so establish and verify the fixture.
        adb("shell", "am", "start", "-n", "app.bypassads.debug/li.songe.gkd.MainActivity")
        store_path = "/sdcard/Android/data/app.bypassads.debug/files/store/store.json"
        state = None
        for _ in range(20):
            try:
                state = json.loads(adb("exec-out", "cat", store_path))
                break
            except (ValueError, RuntimeError):
                time.sleep(0.3)
        if state is None:
            # createTextFlow drops the initial default value, so a new debug
            # install has no file until a preference changes. This is a fixture
            # for the disposable debug app, never the user's formal settings.
            state = {}
            print("Fresh debug install: initialize the controlled default fixture", flush=True)
        state.update(bypassAdStrategyMode=args.strategy, enableMatch=not args.master_off, enableGenericFallback=not args.generic_off, enableAutomator=True,
                     enableMiniProgramVisualSkip=not args.visual_off)
        # A force-stopped enabled service may stay unbound when the setting
        # is written with the same value. Establish a real disable/enable edge.
        adb("shell", "settings", "delete", "secure", "enabled_accessibility_services")
        time.sleep(0.5)
        adb("shell", "am", "force-stop", "app.bypassads.debug")
        adb("shell", "mkdir", "-p", "/sdcard/Android/data/app.bypassads.debug/files/store")
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "debug-fixture.json"
            config.write_text(json.dumps(state), encoding="utf-8")
            adb("push", str(config), store_path)
        print(f"Fixture: strategy={args.strategy}; matching={not args.master_off}; generic={not args.generic_off}; visual={not args.visual_off}", flush=True)
        # Isolate the engine so another ad skipper cannot produce a pass.
        adb("shell", "settings", "put", "secure", "enabled_accessibility_services", component)
        adb("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        adb("shell", "am", "start", "-n", "app.bypassads.debug/li.songe.gkd.MainActivity")
        # HyperOS may leave the newly enabled service disconnected after a
        # force-stop. A fixed sleep must not turn that into misleading matcher
        # failures, especially while the user is testing a real mini-program.
        ready = False
        for _ in range(20):
            dump = adb("shell", "dumpsys", "accessibility")
            bound = dump.partition("Bound services:")[2].partition("Enabled services:")[0]
            configured = json.loads(adb("exec-out", "cat", store_path))
            pid = adb("shell", "pidof", "app.bypassads.debug")
            logs = adb("logcat", "-d", "--pid=" + pid, "-v", "brief", "-s", "BypassVisual:D") if pid.isdecimal() else ""
            if (adb("shell", "settings", "get", "secure", "enabled_accessibility_services") == component
                    and "Bypass Ads" in bound and configured.get("enableAutomator") is True
                    and ("LOCAL_MODEL_WARM_END" in logs if not args.visual_off else "pkg=" in logs)):
                ready = True
                break
            time.sleep(0.5)
        if not ready:
            raise RuntimeError("Debug accessibility fixture not ready; no scene started: " +
                               str({"bound": "Bypass Ads" in bound, "enableAutomator": configured.get("enableAutomator"),
                                    "pid": pid, "visual_worker": "pkg=" in logs}))
        print("Debug accessibility service connected; visual worker ready", flush=True)
        for scene, wait, expected in (("a", 3, "SKIPPED"), ("b", 3, "SKIPPED"), ("d", 3, "SKIPPED"),
                                      ("e", 3, "SKIPPED"), ("w", 3, "NO_ACTION"), ("ah", 3, "SKIPPED"),
                                      ("ad", 14, "SKIPPED"), ("ag", 14, "SKIPPED"),
                                      ("af", 15, "SKIPPED"), ("ae", 14, "SKIPPED"), ("ai", 6, "SKIPPED"),
                                      ("aj", 5, "SKIPPED"), ("ak", 4, "NO_ACTION"),
                                      ("al", 5, "SKIPPED"), ("am", 4, "NO_ACTION"), ("ap", 16, "SKIPPED"),
                                      ("aq", 5, "SKIPPED")):
            if scene == "aq" and not args.only:
                continue  # Requires a temporary, locally captured header-only calibration fixture.
            if args.only and scene not in args.only.split(","):
                continue
            visual_positive = scene in {"aj", "al", "ap", "aq"}
            if args.master_off or (visual_positive and (args.visual_off or args.generic_off or args.strategy == 0)):
                expected = "NO_ACTION"
            if scene == "ai" or (visual_positive and expected == "SKIPPED"):
                from read_ad_blackbox import read_records
                before_ids = {r["session_id"] for r in read_records(args.serial, "app.bypassads.debug", "app.bypassads.testad", 200)}
            adb("shell", "am", "start", "--activity-single-top", "-n", activity, "--es", "scenario", scene)
            time.sleep(wait)
            actual = result_token(scene)
            row = {"scene": scene, "expected": expected, "actual": actual, "passed": actual == expected}
            if scene == "ai":
                records = [r for r in read_records(args.serial, "app.bypassads.debug", "app.bypassads.testad", 5)
                           if r["session_id"] not in before_ids]
                trace = next((r for r in records if any("rule=107" in e for e in r.get("timeline", []))), {})
                row.update(delay_scheduled=any("ACTION_DELAY_SCHEDULED:rule=107 delay_ms=2200" in e for e in trace.get("timeline", [])),
                           same_window_preserved=any("WINDOW_EVENT:SAME_ACTIVITY" in e for e in trace.get("timeline", [])),
                           single_attempt=trace.get("action_attempts") == 1,
                           confirmed=trace.get("result") == "SUCCESS_CONFIRMED")
                row["passed"] = row["passed"] and all(row[k] for k in
                    ("delay_scheduled", "same_window_preserved", "single_attempt", "confirmed"))
            elif visual_positive and expected == "SKIPPED":
                records = [r for r in read_records(args.serial, "app.bypassads.debug", "app.bypassads.testad", 5)
                           if r["session_id"] not in before_ids]
                trace = next((r for r in records if r.get("candidate_type") == "LOCAL_VISUAL_EXIT"), {})
                row.update(visual_channel=trace.get("candidate_type") == "LOCAL_VISUAL_EXIT",
                           rechecked=any("VISUAL_RECHECK:TWO_FRESH_FRAMES" in e for e in trace.get("timeline", [])),
                           single_attempt=trace.get("action_attempts") == 1,
                           confirmed=trace.get("result") == "SUCCESS_CONFIRMED")
                row["passed"] = row["passed"] and all(row[k] for k in
                    ("visual_channel", "rechecked", "single_attempt", "confirmed"))
            results.append(row)
            print(json.dumps(row), flush=True)
        if args.no_warm_return:
            return 0 if results and all(row["passed"] for row in results) else 1
        pid_before = adb("shell", "pidof", "app.bypassads.testad")
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(2)
        adb("shell", "am", "start", "--activity-single-top", "-n", activity)
        time.sleep(1)
        waiting = result_token("ae") == "NO_ACTION"
        time.sleep(13)
        actual = result_token("ae")
        pid_after = adb("shell", "pidof", "app.bypassads.testad")
        row = {"scene": "warm_return_delayed", "actual": actual,
               "same_process": pid_before == pid_after, "observed_new_ad_wait": waiting,
               "passed": actual == "SKIPPED" and pid_before == pid_after and waiting}
        results.append(row)
        print(json.dumps(row), flush=True)
    finally:
        if services == "null":
            adb("shell", "settings", "delete", "secure", "enabled_accessibility_services")
        else:
            adb("shell", "settings", "put", "secure", "enabled_accessibility_services", services)
        if enabled == "null":
            adb("shell", "settings", "delete", "secure", "accessibility_enabled")
        else:
            adb("shell", "settings", "put", "secure", "accessibility_enabled", enabled)
        print("Original accessibility settings restored", flush=True)
    return 0 if all(row["passed"] for row in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
