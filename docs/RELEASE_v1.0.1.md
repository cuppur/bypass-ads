# Bypass Ads v1.0.1

**简体中文** | [English](https://github.com/cuppur/bypass-ads/blob/main/docs/RELEASE_v1.0.1_EN.md)

本次新增英文界面，并提供中文、英文两个已签名的公开安装包。

### 下载

- **[Bypass-Ads-v1.0.1-zh.apk](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/Bypass-Ads-v1.0.1-zh.apk)**：中文界面。
- **[Bypass-Ads-v1.0.1-en.apk](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/Bypass-Ads-v1.0.1-en.apk)**：英文界面，不受手机系统语言影响。
- **[SHA256SUMS.txt](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/SHA256SUMS.txt)**：两个安装包的 SHA-256 校验值。

两个 APK 使用相同包名和签名，安装另一语言版本会替换现有版本，无法同时安装。
请先备份数据，采用覆盖安装，避免卸载导致本地数据丢失。

### 更新内容

- 增加显示层英文翻译，覆盖主要页面、设置、权限提示、通知和黑匣子原因说明。
- 翻译动态计数与多行诊断字段，保留原始规则、OCR 输入、诊断代码及未知内容。
- 补齐英文系统资源与无障碍说明，维护中英文 README 和公开构建脚本。
- 公开打包时隔离本机完整第三方规则包，并检查规则、语言、版本、权限与签名。

246 项 JVM 回归和策略、安全、隐私、多源检查通过。该结果包含功能与翻译逻辑验证，本轮没有连接手机，未进行英文界面真机验收。

### 规则与覆盖范围

**公开 APK 仅含仓库自写基础规则，覆盖有限。** 不含本机完整第三方订阅，可导入自行准备且许可允许使用的兼容规则。
两种语言使用同一套广告识别逻辑和本地 OCR 模型。英文界面不代表已验证所有海外应用。
小程序视觉功能需 Android 11+、支持的微信或支付宝宿主、激进或彻底疯狂策略以及足够广告证据。
无障碍或截图能力受限、未知布局等情况仍可能漏跳。

默认构建不含 INTERNET 权限，识别在本机完成。应用名称、导入的规则名称和系统界面可能保留原有语言。
自动生成的 Source code ZIP / TAR.GZ 是源码，不是安装包。
