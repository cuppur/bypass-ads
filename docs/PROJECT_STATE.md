# Bypass Ads project state

Current baseline: `main` (2026-10-07). Version: 1.0.0.
Application ID: `app.bypassads`.

## Runtime

The product uses the GKD matcher, selector and action runtime, plus a bounded
local visual fallback for mini-program splash ads on Android 11+.
Rules are layered as clean bundled base + Local Import + Teach. Supported
strategies are Conservative, Aggressive, and Crazy. Matching starts immediately
and supports late ads and native-app foreground returns.

The visual fallback uses bundled Chinese ML Kit OCR. It requires a supported
WeChat/Alipay mini-program host, a top-left ad exit, ad evidence and an adjacent
short countdown. Conservative mode observes; Aggressive/Crazy may act. The
top-right mini-program exit capsule is excluded. Fresh frames, shared action
ownership and post-action verification limit stale or duplicate actions.

## Records

Persistent ad sessions distinguish confirmed success, failure and unresolved
outcomes. The blackbox records reason codes, matching source, waits, attempts
and verification; Records offers failed-app counts, filters, export and manual
miss reports. Retention is seven days / 2000 sessions, with the latest 500 in UI.

## Build and boundaries

- Default `gkd` has no `INTERNET` permission. OCR runs locally with its model
  packaged in the APK. Images and raw OCR text remain in memory.
- `fulltools` is optional for local advanced tooling.
- `tools/build_selfuse.ps1` produces signed R8 builds and checks ML Kit
  reflection constructors. Signing material stays outside the repository.
- Full third-party rule inputs, generated local bundles and self-use APKs
  remain ignored. The repository tracks the self-written baseline fixture,
  overrides, safety exclusions, source, tests and tooling.
- No cloud recognition, telemetry or automatic upload is used.

## Verification

Run the four Python policy gates, `tools/check_repo_integrity.py`, and
`:app:testGkdDebugUnitTest`. Current regression coverage is 240 JVM tests.
The real Alipay 星星充电 sample was confirmed closed in about 1–2 seconds;
controlled TestAd coverage must remain separate from real-app coverage.
See [AD_SKIP_DIAGNOSIS.md](AD_SKIP_DIAGNOSIS.md) for evidence and limitations.
