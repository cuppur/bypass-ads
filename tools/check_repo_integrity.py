#!/usr/bin/env python3
"""Repository integrity gate.

Checks:
  1. important source files exist and are non-empty;
  2. the current branch contains f7001e7 (healthy history) ancestry;
  3. tracked file count is sane for this project;
  4. no accidental build artifacts are tracked;
  5. generated local rules, diagnostics and signing material stay local.
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HEALTHY = "f7001e7ce3da9809e740a8af4d7e2c7f49827f27"

IMPORTANT = [
    "app/src/main/kotlin/li/songe/gkd/App.kt",
    "app/src/main/kotlin/li/songe/gkd/a11y/A11yRuleEngine.kt",
    "app/src/main/kotlin/li/songe/gkd/bypass/BypassEngine.kt",
    "app/src/main/kotlin/li/songe/gkd/bypass/GkdBypassEngine.kt",
    "app/src/main/kotlin/li/songe/gkd/bypass/BypassRulePolicy.kt",
    "app/src/main/kotlin/li/songe/gkd/bypass/BypassOutcomeVerifier.kt",
    "app/src/main/kotlin/li/songe/gkd/bypass/BypassActionBudget.kt",
    "app/src/main/kotlin/li/songe/gkd/bypass/BypassRuleStack.kt",
    "app/src/main/kotlin/li/songe/gkd/data/BypassDetectionSession.kt",
    "app/src/main/kotlin/li/songe/gkd/db/AppDb.kt",
    "app/build.gradle.kts",
    "gradle/libs.versions.toml",
    "rules/bypass_overrides.json",
    "rules/safety_exclusions.json",
    "tools/build_splash_bundle.py",
    "tools/build_ad_bundle.py",
    "tools/collect_real_ad_samples.py",
    "tools/test_splash_policy.py",
    "tools/test_safety_exclusions.py",
    "docs/PROJECT_STATE.md",
]

FORBIDDEN_DIRECTORIES = {
    "build", "dist", ".gradle", ".kotlin", ".cache", "__pycache__",
    ".claude", ".idea", ".vscode", "gkd_subscriptions",
}
FORBIDDEN_SUFFIXES = {
    ".apk", ".aab", ".apks", ".jks", ".keystore", ".pyc",
    ".db", ".sqlite", ".sqlite3", ".log", ".hprof",
}
FORBIDDEN_NAMES = {
    "local.properties", "signing.properties", "bypass_splash_rules.local.json",
    "ui.xml", "ui_a11y.xml", "ui_texts.txt",
}


def forbidden_artifact(path: str) -> bool:
    parts = Path(path).parts
    name = parts[-1].lower()
    return (
        any(part.lower() in FORBIDDEN_DIRECTORIES for part in parts[:-1])
        or Path(name).suffix in FORBIDDEN_SUFFIXES
        or name in FORBIDDEN_NAMES
        or parts[0].lower().startswith(("tmp_", "gkd_", "compare_"))
    )


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, timeout=30
    ).stdout.strip()


def main() -> int:
    failures: list[str] = []

    for path in IMPORTANT:
        p = ROOT / path
        if not p.exists() or p.stat().st_size == 0:
            failures.append(f"missing/empty: {path}")

    tracked = git("ls-files").splitlines()
    if len(tracked) < 200:
        failures.append(f"tracked file count suspiciously low: {len(tracked)}")
    for path in tracked:
        if forbidden_artifact(path):
            failures.append(f"accidental tracked artifact: {path}")

    # healthy-history ancestry on the current branch
    merge_base = git("merge-base", HEALTHY, "HEAD")
    if not merge_base:
        failures.append(f"healthy history {HEALTHY[:8]} is not an ancestor of HEAD")
    elif not merge_base.startswith(HEALTHY[:12]):
        failures.append(f"merge-base mismatch: {merge_base}")

    if failures:
        print("REPO INTEGRITY FAILURES:")
        for f in failures:
            print("  -", f)
        return 1
    print(
        f"repo integrity ok: {len(tracked)} tracked files, "
        f"healthy ancestry {HEALTHY[:8]}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
