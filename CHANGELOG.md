# Bypass Ads 更新记录

## 1.0.1（2026-10-24）

- 新增英文界面及英文系统资源，提供中文、英文两个签名公开 APK。
- 补充动态文本、权限提示、通知和黑匣子原因说明的显示层翻译；保留匹配规则与原始诊断数据。
- README 增加完整英文说明、语言选择下载入口和公开基础规则覆盖说明。
- 新增公开构建与 APK 检查脚本，防止打包本机第三方完整规则。

English: adds English UI, signed Chinese/English public APKs, bilingual documentation,
localization regression tests and checks that exclude private full rule bundles.

## 1.0.0 当前基线（2026-10-07）

- 增强通用开屏识别、相邻独立倒计时、控件 ID / 描述识别和执行后验证。
- 补强京东读书、QQ 音乐以及应用后台返回、同页晚出现广告的匹配。
- 增加微信、支付宝小程序左上角广告出口的本地中文 OCR 补查，排除右上角退出控件。
- 增加持久化广告黑匣子、失败应用数量汇总、筛选、导出和漏跳补记。
- 同步实际无障碍连接状态，修复 Release / R8 下模型反射初始化。
- 更换用户提供的像素风图标，覆盖传统、圆形和自适应图标。
- 240 项 JVM 回归与 Python 策略、安全、多源和隐私检查通过。

真实验证与覆盖边界见 [广告跳过诊断](docs/AD_SKIP_DIAGNOSIS.md)。
自用完整规则构建见 [README](README.md)；包含未获再分发许可规则的 APK 仅限本机自用。

本项目源自 GKD 1.12.1；原始上游变更记录可查阅
[gkd-kit/gkd](https://github.com/gkd-kit/gkd)。
