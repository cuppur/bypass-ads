<div align="center">
  <img src="app/src/main/res/drawable-nodpi/bypass_launcher_image.png" width="112" alt="Bypass Ads icon" />
  <h1>Bypass Ads</h1>
  <p><a href="README.md">简体中文</a> | <strong>English</strong></p>
  <p>An Android ad-skipping app built on GKD, with local mini-program OCR and an ad diagnostics blackbox.</p>
  <p>
    <a href="https://github.com/cuppur/bypass-ads/actions/workflows/ci.yml"><img src="https://github.com/cuppur/bypass-ads/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
    <a href="https://github.com/cuppur/bypass-ads/releases"><img src="https://img.shields.io/github/v/release/cuppur/bypass-ads" alt="Latest release" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0" /></a>
  </p>
</div>

## Overview

**Bypass Ads** is an Android ad-skipping app built on GKD. It uses Accessibility
services to find ad exits, with an on-device OCR fallback for supported WeChat
and Alipay mini-program splash ads. The default build works offline without
the `INTERNET` permission.

## Download and install

Download the signed **v1.0.1** APK from [Releases](https://github.com/cuppur/bypass-ads/releases/latest):

- [English APK](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/Bypass-Ads-v1.0.1-en.apk)
- [Chinese APK](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/Bypass-Ads-v1.0.1-zh.apk)
- [SHA-256 checksums](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/SHA256SUMS.txt)

**Public APKs include only the small, self-authored baseline rule set. Coverage
is limited.** Import compatible rules you are authorized to use for broader coverage.
The full local bundle of third-party rules used in personal builds is not distributed.
The automatically generated Source code ZIP/TAR files are source archives, not installers.

The English and Chinese APKs share the same package (`app.bypassads`) and signing
certificate. Installing one replaces the other; they cannot coexist. Back up your
data before upgrading an existing personal build. Upgrade in place rather than
uninstalling; imported rules are not deliberately cleared by the upgrade.

## Features

- Immediate matching, including ads shown after returning from the background
  or appearing later on the same screen.
- Dedicated and generic rules using control text, descriptions, IDs, layout and
  ad evidence, including a separate countdown next to a close button.
- Bundled, local Chinese OCR on Android 11+. For supported mini-program hosts,
  it checks top-left ad exits and excludes the top-right mini-program exit capsule.
- Post-action verification that distinguishes an accepted tap from confirmed closure.
- A persistent ad blackbox with reasons, attempts, verification, failed-app counts,
  filtering, export and manual missed-ad reports.
- Local imports and rule teaching. No automatic upload of screenshots or raw OCR text.

## Strategies and setup

| Strategy | Behavior |
| --- | --- |
| Conservative | Established dedicated rules and explicit skip controls. Visual checks observe without tapping. |
| Aggressive | Also permits close-type exits and limited retries when ad evidence is sufficient; allows validated visual exits. |
| Crazy | Adds restricted structural and coordinate fallbacks in strong ad contexts, with action budgets and verification. |

Install the APK, enable its accessibility service, and turn on **Automatic ad skipping**
on Home. Review notification and battery settings to keep the service connected.
For automatic mini-program visual exits, enable generic detection and visual checking,
and choose Aggressive or Crazy. Open **Records** to diagnose misses or report an undetected ad.

English is selected at build time and stays enabled regardless of the device language.
The English APK localizes app-authored screens, prompts, notifications and blackbox
explanations. It does not translate installed-app names, imported rule content,
third-party page content or Android system screens, and it does not change the matcher.
The OCR model and recognition coverage are the same in both APKs; an English UI
does not imply verified coverage of all English-language apps or ads.

## Build from source

Requires **JDK 21+** and Android SDK. Set `sdk.dir` in `local.properties`.

```sh
# Linux / macOS: English development build
./gradlew :app:assembleGkdDebug -Pbypass.uiLanguage=en
```

```powershell
# Windows: English development build
.\gradlew.bat :app:assembleGkdDebug "-Pbypass.uiLanguage=en"

# Signed public Chinese + English APKs, with only the public rule fixture
.\tools\build_public.ps1

# Personal build using your own local subscription
.\tools\build_selfuse.ps1 -SubscriptionPath "D:\rules\gkd.json5"
```

Use `-Pbypass.uiLanguage=zh` for Chinese (the default). Public APKs are written to
`dist/public/`. The public build script uses a separate generated public asset tree and never moves or modifies the local full rule
bundle. It verifies the packaged fixture, language,
version, permissions, R8 model registrars and signature, then writes `SHA256SUMS.txt`.
Signing keys and passwords stay outside the repository. Do not redistribute
third-party rules without permission.

The default `gkd` build has no network permission. The optional `fulltools` build
includes network-enabled local debugging tools and is not the public APK offered here.

## Verification and limitations

The current baseline passes 246 JVM tests, including localization placeholders,
multiline records, preserved raw evidence and the existing ad logic. Policy, safety,
privacy and multi-source checks also pass.
A real Alipay Star Charge splash ad was confirmed to close in about 1–2 seconds
on 2026-10-04. Controlled fixture tests cover late ads, foreground returns,
adjacent countdowns and excluded navigation controls.

These results do not establish universal coverage. New layouts, inaccessible controls,
protected screenshots, unreadable text or a disconnected service can still cause misses.
Other real apps and mini-programs require separate verification.

## Documentation and license

- [Release notes (English)](docs/RELEASE_v1.0.1_EN.md) · [Changelog](CHANGELOG.md)
- [Project state](docs/PROJECT_STATE.md) · [Maintenance](HANDOVER.md) · [Tools](tools/README.md)
- [Detailed diagnosis (Chinese)](docs/AD_SKIP_DIAGNOSIS.md) · [Permissions (Chinese)](docs/PERMISSIONS.md)
- [Report an issue](https://github.com/cuppur/bypass-ads/issues/new/choose)

Based on [GKD](https://github.com/gkd-kit/gkd), with upstream copyright notices
retained. Licensed under [GPL-3.0](LICENSE). Provided as-is, without a guarantee
that every ad can be skipped.
