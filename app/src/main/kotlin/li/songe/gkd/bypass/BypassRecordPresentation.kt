package li.songe.gkd.bypass

/** Human-readable black-box stages. Unknown codes remain available for diagnosis. */
internal data class BypassTimelinePresentation(
    val elapsed: String?,
    val title: String,
    val code: String,
)

internal object BypassRecordPresentation {
    fun countsAsUnsuccessful(record: BypassSessionRecord): Boolean =
        record.hasAdEvidence && !record.isSuccess && record.result != BypassSessionResult.OPEN

    fun filter(records: List<BypassSessionRecord>, label: String, packageName: String?): List<BypassSessionRecord> =
        records.filter { record ->
            (packageName == null || record.packageName == packageName) && when (label) {
                "已跳过" -> record.isSuccess
                "未成功" -> countsAsUnsuccessful(record)
                "待诊断" -> !record.isSuccess && !countsAsUnsuccessful(record)
                else -> true
            }
        }

    fun summary(record: BypassSessionRecord): String = when (record.result) {
        BypassSessionResult.SUCCESS_CONFIRMED ->
            "动作后重新检查，确认原广告出口及同一区域广告证据已消失。"
        BypassSessionResult.MISCLICK_SUSPECTED ->
            "动作后进入外部页面，已停止后续点击。"
        BypassSessionResult.OPEN -> "仍在观察当前页面，尚无最终结果。"
        BypassSessionResult.UNRESOLVED -> rejectionReason(record) ?: when (record.reason.name) {
            "ACTION_NO_EFFECT" -> "点击后再次检查，广告出口仍存在；本次未成功。"
            "UNKNOWN" -> "观察结束，但没有足够信息确认广告是否关闭。"
            else -> reason(record.reason.name)
        }
        BypassSessionResult.FAILURE_CONFIRMED -> rejectionReason(record) ?: reason(record.reason.name)
    }

    private fun rejectionReason(record: BypassSessionRecord): String? = record.timeline
        .lastOrNull { it.contains("CLOSE_CANDIDATE_REJECTED:") || it.contains("CANDIDATE_REJECTED:") }
        ?.substringAfterLast(':')
        ?.let(::reason)
        ?.takeIf { record.actionAttempts == 0 }
        ?.takeUnless { record.timeline.any { it.substringAfter('|').startsWith(BypassBlackbox.EXECUTION_ALLOWED_MARKER) } }

    fun reason(code: String): String = when (code) {
        "NO_RULE_FOR_APP" -> "当前应用没有可用的广告规则。"
        "CATEGORY_DISABLED" -> "对应广告类别已关闭。"
        "MASTER_DISABLED" -> "自动跳过广告已暂停。"
        "APP_DISABLED" -> "此应用的广告保护已关闭。"
        "GLOBAL_EXCLUDED" -> "排除规则阻止了这次操作。"
        "ACTIVITY_MISMATCH" -> "规则不适用于当前页面。"
        "SELECTOR_NO_MATCH" -> "已有规则，但没有找到对应的广告出口控件。"
        "TARGET_FOUND_NOT_CLICKABLE" -> "找到了目标，但当前无法点击。"
        "ACTION_FAILED" -> "系统没有接受点击动作。"
        "ACTION_NO_EFFECT" -> "系统接受了动作，但复查时广告出口仍在。"
        "WAITING_FOR_DELAY", "ACTION_TOO_EARLY" -> "页面仍在加载，等待点击时机。"
        "ACCESSIBILITY_NODE_MISSING" -> "未能捕获广告出口控件，可能未向无障碍服务暴露；需要再次采样确认。"
        "EVENT_MISSED" -> "没有收到足够的页面变化信息。"
        "MISCLICK_SUSPECTED" -> "动作后进入外部页面，已停止后续点击。"
        "VISUAL_CAPTURE_UNAVAILABLE" -> "系统未提供小程序画面，无法完成视觉补查。"
        "VISUAL_RECOGNITION_UNAVAILABLE" -> "本地文字识别暂时不可用。"
        "VISUAL_TARGET_STALE" -> "复查时出口已变化或倒计时将结束，未点击旧位置。"
        "AD_EVIDENCE_INSUFFICIENT", "NO_AD_CONTEXT" -> "发现关闭候选，但广告证据不足，未执行点击。"
        "STRATEGY_REJECTED", "STRATEGY_GATE" -> "候选超出当前策略允许范围，未执行点击。"
        "SENSITIVE_ACTIVITY", "SENSITIVE_SEMANTIC" -> "当前页面或控件涉及敏感操作，已阻止自动点击。"
        "OUTSIDE_WINDOW" -> "此规则的有效识别窗口已结束。"
        "TOO_LARGE" -> "候选区域过大，不能确认是广告关闭按钮。"
        "NAVIGATION_CONTROL" -> "这是小程序顶部的退出按钮，会关闭整个小程序，已阻止自动点击。"
        "NEGATIVE_SEMANTIC" -> "候选包含普通功能含义，已阻止自动点击。"
        "RULE_LEVEL" -> "此规则需要更高的策略级别。"
        "BUDGET_EXHAUSTED" -> "本次允许的动作次数已用尽。"
        "UNKNOWN" -> "记录没有提供足够信息确定原因。"
        else -> "诊断代码：$code"
    }

    fun candidate(type: BypassExitCandidateType?): String = when (type) {
        BypassExitCandidateType.SKIP_TEXT -> "跳过文字"
        BypassExitCandidateType.CLOSE_TEXT -> "关闭文字"
        BypassExitCandidateType.CLOSE_DESC -> "关闭描述"
        BypassExitCandidateType.CLOSE_VIEW_ID -> "关闭控件 ID"
        BypassExitCandidateType.CLOSE_ICON -> "关闭图标"
        BypassExitCandidateType.STRUCTURAL_CLOSE -> "控件布局定位"
        BypassExitCandidateType.COORDINATE_FALLBACK -> "相对坐标定位"
        BypassExitCandidateType.DEDICATED_EXIT -> "应用专用出口"
        BypassExitCandidateType.CURATED_MINI_EXIT -> "已核对的小程序广告布局"
        BypassExitCandidateType.LOCAL_VISUAL_EXIT -> "本地视觉识别的广告出口"
        null -> "尚未识别出口"
    }

    fun origin(code: String?): String = when (code) {
        "BUNDLED_DEDICATED" -> "内置应用专用规则"
        "BYPASS_OVERRIDE" -> "内置补强规则"
        "BUNDLED_GLOBAL", "BUNDLED_GENERIC", "GENERIC_FALLBACK" -> "内置通用跳过规则"
        "LOCAL_IMPORT_DEDICATED", "LOCAL_IMPORT_GLOBAL", "LOCAL_IMPORTED", "IMPORTED" -> "本地导入规则"
        "TEACH_NODE", "TEACH_COORDINATE", "TEACH_RULE", "TAUGHT" -> "本地教学规则"
        null -> "未记录"
        else -> code
    }

    fun timeline(raw: String): BypassTimelinePresentation {
        val timed = Regex("^\\+?(\\d+)ms[|:]\\s*(.*)$").matchEntire(raw)
        val code = timed?.groupValues?.get(2) ?: raw
        val parts = code.split(':')
        val title = when (parts.firstOrNull()) {
            "ACTIVITY_IDENTIFIED" -> "补全页面名称，继续记录同一条广告"
            "SESSION_OPEN" -> "开始观察广告"
            "TARGET_FOUND" -> "找到广告出口：${candidateName(parts.getOrNull(1))}"
            "ACTION_ATTEMPT" -> "发出动作：${action(parts.drop(1).joinToString(":"))}"
            "ACTION_RESULT" -> if (parts.getOrNull(1) == "ACCEPTED") "系统已接受动作" else "系统未接受动作"
            "ACTION_ACCEPTED" -> "系统已接受动作"
            "ACTION_REJECTED", "ACTION_FAILED" -> "系统未接受动作"
            "WAITING_FOR_DELAY" -> "等待规则规定的点击时机"
            "TARGET_FOUND_NOT_CLICKABLE" -> "目标当前不可点击"
            "STRATEGY" -> "使用${strategyName(parts.getOrNull(1))}策略"
            "CLOSE_CANDIDATE_REJECTED", "CANDIDATE_REJECTED" ->
                reason(parts.lastOrNull().orEmpty())
            "OUTCOME", "VERIFY_RESULT" -> when (parts.getOrNull(1)) {
                "SUCCESS_CONFIRMED" -> "复查确认广告已关闭"
                "ACTION_NO_EFFECT", "FAILURE_CONFIRMED" -> "复查确认广告出口仍在"
                "MISCLICK_SUSPECTED" -> "复查发现进入外部页面，停止点击"
                else -> "复查未能确认广告是否关闭"
            }
            "FINAL" -> "观察结束：${reason(parts.getOrNull(1).orEmpty())}"
            "UNRESOLVED" -> "观察结束，结果未确认"
            "SELECTOR_NO_MATCH" -> "规则未找到匹配控件"
            "MATCHER" -> reason(parts.getOrNull(1).orEmpty())
            "AD_EVIDENCE" -> when (parts.getOrNull(1)) {
                "USER_REPORTED_AD" -> "用户补记一次漏跳，尚未自动捕获控件"
                "CLOSE_WITH_COUNTDOWN" -> "发现关闭按钮及相邻倒计时"
                "VERIFIED_MINI_AD_LAYOUT" -> "命中已核对的小程序广告布局"
                "SKIP_CONTROL" -> "发现跳过广告控件"
                "VISUAL_AD_EXIT" -> "画面中发现出口、广告标识和相邻倒计时"
                else -> "已记录广告证据"
            }
            "CANDIDATE_OBSERVED" -> "观察到出口：${candidateName(parts.getOrNull(1))}"
            "LAST_ROOT_SCAN" -> "附上该 App 最近一次页面采样信息"
            "LAST_VISUAL_SCAN" -> "附上该 App 最近一次视觉补查信息"
            "LAST_MATCHER" -> "附上该 App 最近一次匹配诊断"
            "ROOT_SCAN" -> "从当前完整页面检查广告证据"
            "CONFIG" -> "记录当时的匹配配置"
            "GATE" -> "检查执行条件"
            "EXECUTION_ALLOWED" -> "该广告出口已通过执行条件检查"
            "ACTION_DELAY_SCHEDULED" -> "已安排规则的点击等待，尚未发出动作"
            "WINDOW_EVENT" -> "同一页面重复通知，保留原点击等待"
            "QUERY_INVALIDATED" -> "查询期间页面事件更新，等待新鲜页面再匹配"
            "RUNTIME_NOT_READY" -> if (code.contains("outdated=true"))
                "页面事件已更新，本次查询暂不点击" else "规则尚未就绪，详情见状态代码"
            "RULE_STATUS" -> if (code.contains("status=ACTION_DELAY"))
                "该规则仍在等待预定点击时机" else "该规则当前暂不可执行"
            "AD_ABSENT_PENDING" -> "广告证据暂时消失，等待稳定复查"
            "AD_DISAPPEARED_WITHOUT_ACTION" -> "未发出动作前广告已消失，无法确认自动跳过"
            "USER_REPORT" -> "用户报告漏跳，等待诊断"
            "VERIFY_START" -> "开始重新读取页面并验证结果"
            "VISUAL_SCAN" -> "在小程序左上角用本地文字识别核对广告出口"
            "VISUAL_RECHECK" -> "连续两帧确认出口仍在，再发出点击"
            "VISUAL_VERIFY" -> "复查原出口消失，并确认画面区域已变化"
            "VISUAL_STALE" -> "出口变化或倒计时将结束，放弃旧位置"
            "VISUAL_WAIT" -> "已有动作正在执行或复查，避免重复点击"
            "RETRY" -> "重新识别广告出口"
            "WINDOW_ENDED" -> "页面已切换，结束观察"
            "SESSION_TIMEOUT" -> "达到观察时间上限"
            else -> "诊断事件"
        }
        return BypassTimelinePresentation(timed?.groupValues?.get(1)?.let { "+$it ms" }, title, code)
    }

    fun action(code: String): String = when (code) {
        "click" -> "控件点击"
        "clickCenter" -> "点击控件中心"
        "visualTap" -> "点击新鲜画面中的出口文字中心"
        "clickNode" -> "无障碍控件点击"
        "longClick" -> "长按控件"
        "swipe" -> "滑动"
        "none" -> "无需动作"
        "" -> "未记录动作方式"
        else -> code
    }

    private fun candidateName(code: String?): String = candidate(
        code?.let { runCatching { BypassExitCandidateType.valueOf(it) }.getOrNull() },
    )

    private fun strategyName(code: String?): String = when (code) {
        "CONSERVATIVE" -> "保守"
        "AGGRESSIVE" -> "激进"
        "CRAZY" -> "彻底疯狂"
        else -> code.orEmpty()
    }
}
