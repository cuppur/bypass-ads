<div align="center">
  <img src="app/src/main/res/drawable-nodpi/bypass_launcher_image.png" width="112" alt="Bypass Ads 图标" />
  <h1>Bypass Ads</h1>
  <p><strong>简体中文</strong> | <a href="README_EN.md">English</a></p>
  <p>基于 GKD 的 Android 广告自动跳过工具，支持小程序本地视觉识别与广告诊断黑匣子。</p>
  <p>
    <a href="https://github.com/cuppur/bypass-ads/actions/workflows/ci.yml"><img src="https://github.com/cuppur/bypass-ads/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
    <a href="https://github.com/cuppur/bypass-ads/releases"><img src="https://img.shields.io/github/v/release/cuppur/bypass-ads" alt="最新源码版本" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0" /></a>
  </p>
</div>

## 项目简介

Bypass Ads 通过 Android 无障碍服务识别广告出口并尝试关闭广告，覆盖应用开屏、
后台返回后再次出现的广告，以及部分全屏广告和营销弹窗。对于控件树无法读取的
微信、支付宝小程序开屏广告，提供完全在设备本地运行的中文 OCR 补查。

**v1.0.1 提供中文、英文两个签名安装包。** 前往 [Releases](https://github.com/cuppur/bypass-ads/releases/latest) 下载：

| 安装包 | 界面语言 | 下载 |
| --- | --- | --- |
| `Bypass-Ads-v1.0.1-zh.apk` | 中文 | [中文 APK](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/Bypass-Ads-v1.0.1-zh.apk) |
| `Bypass-Ads-v1.0.1-en.apk` | English | [English APK](https://github.com/cuppur/bypass-ads/releases/download/v1.0.1/Bypass-Ads-v1.0.1-en.apk) |

系统要求：Android 8.0+、64 位 ARM 或 x86_64；小程序视觉补查需要 Android 11+。

两者使用同一包名与签名，安装另一语言会替换现有版本，不能同时安装；覆盖安装通常保留数据。
**公开 APK 只包含仓库自写的基础规则，覆盖有限。** 它们不含本机完整第三方规则包；
可导入自行准备的兼容规则。已有完整规则自用版的用户请先备份数据；不要卸载后再升级。
升级不会主动清除已导入的规则。校验文件见 Release 的 `SHA256SUMS.txt`。

## 能做什么

- **即时与持续匹配**：进入应用后立即识别，支持后台返回和同一页面稍后出现的广告。
- **通用识别与专用规则结合**：综合文字、控件描述、资源 ID、页面结构和广告证据定位出口，支持“关闭”旁边带独立倒计时的布局。
- **小程序本地视觉补查**：Android 11+ 使用随包内置的中文 OCR 模型，识别支持的微信、支付宝小程序左上角广告出口；排除右上角退出小程序控件。
- **结果验证**：区分“系统接受了点击”和“广告已确认关闭”，结合新鲜页面或画面证据核对结果。
- **广告黑匣子**：按广告会话记录识别来源、等待原因、动作尝试与验证结果；支持失败应用数量汇总、筛选、导出和手动补记漏跳。
- **本地规则与教学**：支持本地规则导入，以及从失败诊断进入教学与验证流程。
- **离线运行**：默认 `gkd` 构建不含 `INTERNET` 权限。OCR 模型内置，识别画面和原始 OCR 文本只在内存处理，不自动上传。

## 三种跳过策略

| 策略 | 行为 |
| --- | --- |
| 保守 | 使用专用规则及明确的跳过语义；小程序视觉通道只观察，不自动点击。 |
| 激进 | 在确认广告上下文后，进一步尝试关闭类出口，并允许证据满足的小程序视觉点击。 |
| 彻底疯狂 | 在强广告上下文中，额外允许受限的结构型出口与坐标补救；仍受执行预算和结果验证约束。 |

策略切换立即生效。更积极的策略不能保证覆盖所有广告，建议先使用保守模式，
再根据实际漏跳情况调整；小程序视觉自动跳过需要选择激进或彻底疯狂。

## 开始使用

1. 从 Release 下载对应语言的 APK，或按下文自行构建并安装。正式包名为 `app.bypassads`，Debug 包名为 `app.bypassads.debug`。
2. 在应用引导下开启无障碍服务及首页“自动跳过广告”开关。
3. 按需设置通知、电池优化与后台运行权限，确保服务持续连接。
4. 在广告设置中选择策略；使用小程序视觉补查时，同时开启通用识别与视觉补查开关。
5. 遇到漏跳时，进入“记录”查看原因、失败应用数量，或手动补记一次漏跳，保留后续排查依据。

## 构建

需要 **JDK 21+** 与 Android SDK。使用 `local.properties` 配置 `sdk.dir`。

### 开发版

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleGkdDebug
```

Linux / macOS：

```sh
./gradlew :app:assembleGkdDebug
```

输出：`app/build/outputs/apk/gkd/debug/app-gkd-debug.apk`。
开发版使用仓库自写基线规则；如果本地存在生成的完整规则包，构建会一并打包。

### 本机完整规则自用版

在 Windows PowerShell 中，提供自行准备的 GKD 格式订阅文件：

```powershell
.\tools\build_selfuse.ps1 -SubscriptionPath "D:\rules\gkd.json5"
```

脚本生成本地规则包，运行策略和 JVM 检查，构建经过 R8 优化的 Release，
核对权限、包名、版本、规则哈希与 ML Kit 反射注册构造方法，最后验签并输出：

```text
dist/Bypass-Ads-v1.0.1-selfuse.apk
```

签名优先复用 `GKD_STORE_*` 配置；否则在
`%USERPROFILE%\.bypass-ads\signing\` 创建并复用长期自用密钥。
签名材料只存本机。未配置正式签名时，Gradle 可产出未签名 Release，
自用构建脚本会拒绝将其作为正式安装包交付。

### 可公开分发的中英文安装包

```powershell
.\tools\build_public.ps1
```

脚本分别以 `-Pbypass.uiLanguage=zh` 和 `en` 构建，使用独立生成的公开资源目录，排除完整本地规则包；
使用外部长期密钥签名，检查公开规则、语言、版本、权限和签名，输出至 `dist/public/`。
密码通过进程环境传给签名工具，不写入源码或发布文件。也可用 `-Languages en` 仅构建英文版。
英文版只翻译显示层，不改写规则、识别关键词、OCR 输入或原始诊断代码。
应用名称、用户导入的规则名称、第三方页面文字以及系统界面可能仍保留其原有语言。

### 构建类型

| 类型 | 用途 |
| --- | --- |
| `gkd` | 默认离线产品构建，不含 `INTERNET` 权限。 |
| `fulltools` | 高级工具构建，包含本机 HTTP 检查等网络能力及 `INTERNET` 权限。 |

## 已验证范围与限制

当前功能基线通过 **246 项 JVM 回归**，包含翻译占位符、多行诊断、原始证据保留及原有广告逻辑测试；策略、安全排除、多源、隐私和仓库完整性检查也已通过。
GitHub CI 另行检查默认构建、Release / R8、FullTools 和设备测试代码的编译。

2026-10-04 的真实手机验证中，支付宝“星星充电”开屏广告在约 **1–2 秒**内自动关闭，
并回到小程序首页。另有受控测试覆盖晚出现广告、后台返回、独立倒计时、右上角退出控件排除和结果验证。

这些结果不代表全部应用或小程序都能跳过。广告布局更新、没有可辨认出口文字、
系统禁止截图、服务断开等情况仍可能造成漏跳。微信、支付宝中的不同小程序需要分别验证。
详细依据见 [广告跳过诊断](docs/AD_SKIP_DIAGNOSIS.md)。

## 规则与仓库内容

- 仓库跟踪自写基线规则 `app/src/main/assets/bypass_splash_rules.json`、补强规则和安全排除配置。
- 完整本地规则包（full local bundle）`bypass_splash_rules.local.json` 由本机构建生成，不提交到 GitHub。
- 未获再分发许可的第三方规则正文及包含这些规则的自用 APK 不公开分发。
- 构建缓存、APK、设备原始数据、日志与签名材料不进入 Git 源码仓库；经校验的公开 APK 只作为 Release 附件提供。CI 会检查误加入 Git 的本地产物。
- `main` 是唯一开发基线。GitHub 源码 ZIP 仅包含已提交的文件。

## 文档与反馈

- [版本更新](CHANGELOG.md) · [发布说明](https://github.com/cuppur/bypass-ads/releases)
- [广告跳过诊断与已知边界](docs/AD_SKIP_DIAGNOSIS.md)
- [权限说明](docs/PERMISSIONS.md) · [项目状态](docs/PROJECT_STATE.md)
- [维护交接](HANDOVER.md) · [构建与诊断工具](tools/README.md)
- [提交问题](https://github.com/cuppur/bypass-ads/issues/new/choose) · [参与贡献](CONTRIBUTING.md)

反馈漏跳时，请说明应用或小程序名称、宿主、策略、广告出口的文字与位置，
并优先提供“记录”中的诊断信息。分享前检查是否含有个人信息。

## 致谢与许可证

本项目基于 [GKD](https://github.com/gkd-kit/gkd) 修改，沿用 GKD 1.12.1 基线，
保留上游版权声明，依据 [GPL-3.0](LICENSE) 发布。

软件按现状提供，不保证跳过所有广告。使用者需遵守适用法律法规，并自行确认所用第三方规则的许可范围。
