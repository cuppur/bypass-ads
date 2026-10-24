# Bypass Ads v1.0.0 · 源码版

基于 GKD 的 Android 广告自动跳过工具。本次发布整理当前已验证的源码基线，包含应用开屏识别、小程序本地 OCR 补查与持久化广告黑匣子。

## 主要功能

- 通用跳过结合专用规则，支持广告文字、控件描述、资源 ID、页面结构及旁边独立倒计时。
- 进入应用立即匹配，补强 QQ 音乐后台返回、同页晚出现广告及京东读书相关识别。
- Android 11+ 微信、支付宝小程序本地中文 OCR 补查：限定支持宿主的左上角广告出口，排除右上角退出小程序控件。
- 保守、激进、彻底疯狂三档策略；视觉自动点击仅在激进或彻底疯狂策略及证据满足时执行。
- 持久化黑匣子记录候选、等待、动作与结果验证；支持失败应用汇总、筛选、导出和手动补记漏跳。
- 修复实际无障碍连接状态同步及 Release / R8 模型反射初始化，接入像素风图标。

## 验证与边界

- 功能基线通过 240 项 JVM 回归及策略、安全排除、多源、隐私检查。
- 2026-10-04 真实验证：支付宝“星星充电”开屏广告约 1–2 秒自动关闭并回到小程序首页。
- 受控测试覆盖晚出现广告、后台返回、独立倒计时、视觉负向场景与结果验证；不等同于全部真实应用覆盖。
- 无可辨认文字、受保护画面、未知布局或服务断开仍可能漏跳。其他小程序需要分别验证。

## 下载与构建

这是**源码发布**。下方 Source code (zip / tar.gz) 为 GitHub 自动生成的源码包，**不是 Android 安装包**。
本次不上传完整规则自用 APK；仓库只带自写开发基线规则，完整覆盖需自行准备订阅并在本机构建。

- 环境：JDK 21+、Android SDK。
- 开发版：Windows 使用 `.\gradlew.bat :app:assembleGkdDebug`；Linux / macOS 使用 `./gradlew :app:assembleGkdDebug`。
- 完整规则自用版：`.\tools\build_selfuse.ps1 -SubscriptionPath "D:\rules\gkd.json5"`。
- 默认 `gkd` 构建不含 INTERNET 权限；模型随包内置，不自动上传画面与 OCR 原文。
- 构建缓存、设备原始数据、签名材料及未获再分发许可的第三方完整规则不在发布包中。

详见 [README](https://github.com/cuppur/bypass-ads#readme)、[诊断记录](https://github.com/cuppur/bypass-ads/blob/main/docs/AD_SKIP_DIAGNOSIS.md) 和 [更新记录](https://github.com/cuppur/bypass-ads/blob/main/CHANGELOG.md)。

基于 GKD 修改，许可证 GPL-3.0。
