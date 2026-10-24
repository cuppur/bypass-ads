# Bypass Ads v1.0.1

[简体中文](https://github.com/cuppur/bypass-ads/releases/tag/v1.0.1) | **English**

This release adds an English interface and signed public APKs in Chinese and English.

### Downloads

- **[Bypass-Ads-v1.0.1-en.apk](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/Bypass-Ads-v1.0.1-en.apk)**: English UI, independent of the device language.
- **[Bypass-Ads-v1.0.1-zh.apk](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/Bypass-Ads-v1.0.1-zh.apk)**: Chinese UI.
- **[SHA256SUMS.txt](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/SHA256SUMS.txt)**: SHA-256 checksums for both APKs.

Both APKs share a package name and signing certificate. Installing one replaces the other;
they cannot coexist. Back up your data and upgrade in place rather than uninstalling.

### Changes

- English display translations for main screens, settings, permission prompts,
  notifications and blackbox explanations, including dynamic counts and multiline records.
- Original rules, OCR input, diagnostic codes and unknown content remain unchanged.
- English Android resources, bilingual README and a reproducible public build script.
- Public APK checks for rule contents, UI language, version, permissions and signatures.

246 JVM tests and policy, safety, privacy and multi-source checks pass.
No phone was connected during this release, so the English screens have not
been visually verified on a physical device.

### Scope and limitations

**Public APKs include only the small self-authored baseline rule set. Coverage is limited.**
The personal full third-party subscription bundle is not distributed. Import compatible
rules you are authorized to use for broader coverage.

Both languages use the same matcher and local OCR model. English UI does not imply verified
coverage of all international apps. Mini-program visual actions require Android 11+, a
supported WeChat or Alipay host, Aggressive/Crazy mode and sufficient ad evidence.
Unknown layouts, accessibility restrictions or protected images can still cause misses.
App names, imported rule content and Android system screens may retain their original language.

The default build has no INTERNET permission. Recognition runs locally. Source code ZIP/TAR
archives are not Android installers.

Built on GKD, licensed under GPL-3.0. See the [README](https://github.com/cuppur/bypass-ads/blob/main/README_EN.md) for setup and build instructions.
