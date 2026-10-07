# Bypass Ads maintenance handover

`main` is the sole development baseline. Bypass Ads 1.0.0 is based on GKD,
with continuous node matching, native-app foreground-return handling,
mini-program local OCR fallback, persistent blackbox records and custom icons.
The architecture is documented in [PROJECT_STATE](docs/PROJECT_STATE.md).

## Build

- Debug: `.\gradlew.bat :app:assembleGkdDebug`
- FullTools: `.\gradlew.bat :app:assembleFulltoolsDebug`
- Self-use release: `.\tools\build_selfuse.ps1 -SubscriptionPath <path>`

The self-use script generates the local rule bundle, runs policy/JVM gates,
builds signed R8 release, verifies reflected ML Kit constructors, and writes
`dist/Bypass-Ads-v1.0.0-selfuse.apk`. The stable signing key is outside the
repository at `%USERPROFILE%\.bypass-ads\signing\`. Never commit the key,
passwords, third-party subscription bodies or locally generated full bundles.
The source-only CI build uses the self-written fixture and requires no secrets.

## Runtime and maintenance

Default builds have no INTERNET permission. Android 11+ mini-program visual
recognition uses a bundled Chinese OCR model and processes images in memory.
Require top-left ad evidence and adjacent countdown; never target the
top-right mini-program exit. Preserve pre-action freshness, action ownership
and post-action verification when changing the matcher.

Run the four `tools/test_*.py` policy gates, `tools/check_repo_integrity.py`,
and `.\gradlew.bat :app:testGkdDebugUnitTest`. Current JVM coverage is 240 tests.
Device tools accept an explicit connected-device serial; see [tools](tools/README.md).
Do not uninstall or clear app data during upgrade checks. Keep controlled
fixture results separate from real-ad results; use the blackbox to investigate
misses without retaining full UI trees or raw page content.

The latest real-ad verification and known boundaries are in
[AD_SKIP_DIAGNOSIS](docs/AD_SKIP_DIAGNOSIS.md).
