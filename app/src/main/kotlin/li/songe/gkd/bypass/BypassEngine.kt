package li.songe.gkd.bypass

import kotlinx.coroutines.flow.StateFlow

/**
 * Bypass Ads product facade.
 *
 * The product UI talks only to this interface. Everything GKD-specific
 * (subscriptions, rule resolution, accessibility service, stores, DAOs) stays
 * behind the adapter implementation, so the normal user flow never exposes
 * GKD concepts (subs, selector, route, matcher, ...).
 */
interface BypassEngine {

    /** Translated accessibility/service status for the home screen. */
    val serviceState: StateFlow<BypassServiceState>

    /** Master matching switch. */
    val masterEnabled: StateFlow<Boolean>

    /** Product-level advertising categories backed by real rule groups. */
    val adCategories: StateFlow<List<BypassAdCategoryState>>

    /** Coverage stats of the bundled splash rule set. */
    val stats: StateFlow<BypassRuleStats>

    /** Persisted provenance for the rule package currently selected by the user. */
    val ruleMetadata: StateFlow<BypassRuleMetadata>

    /** Android permission and background-execution state shown in product UI. */
    val permissionState: StateFlow<BypassPermissionState>

    /** Actual accessibility control state and available enhanced-control paths. */
    val accessibilityControl: StateFlow<BypassAccessibilityControl>

    /** Runtime health shown from Home. This does not claim to bypass Android's
     * lifecycle restrictions; it exposes the states users can actually fix. */
    val runtimeProtection: StateFlow<BypassRuntimeProtection>

    /** Latest finalized ad session (null when nothing finalized yet). The
     *  raw GKD ActionLog is only exposed from the Advanced/debug screens. */
    val latestAction: StateFlow<BypassActionRecord?>

    /** Number of successful actions kept in the Bypass Ads local history. */
    val skipCount: StateFlow<Int>

    /** Product records: every finalized ad session (success + failure). */
    val sessionRecords: StateFlow<List<BypassSessionRecord>>

    /** Aggregated over all retained ad records, not just the newest display rows. */
    val unsuccessfulApps: StateFlow<List<BypassUnsuccessfulAppStats>>

    suspend fun reportMissedAd(packageName: String): String

    /** Recent product-facing failure records (subset of [sessionRecords]). */
    val failureRecords: StateFlow<List<BypassFailureRecord>>

    /** Product stats computed from SUCCESS_CONFIRMED sessions only. */
    val productStats: StateFlow<BypassStats>

    /** Current-process average matcher-to-action response time. */
    val averageResponseMs: StateFlow<Int?>

    /** Conservative generic splash fallback switch (default on). */
    val genericFallbackEnabled: StateFlow<Boolean>

    /** Three-tier splash exit strategy (default CONSERVATIVE). */
    val strategyMode: StateFlow<BypassAdStrategyMode>

    /** Whether the one-time CRAZY confirmation has been shown and accepted. */
    val crazyModeAcknowledged: StateFlow<Boolean>

    /** Whether a successful skip should show the branded toast. */
    val actionToastEnabled: StateFlow<Boolean>

    /** Whether the ongoing service notification is enabled. */
    val persistentNotificationEnabled: StateFlow<Boolean>

    fun setMasterEnabled(enabled: Boolean)

    fun setAdCategoryEnabled(category: BypassAdCategory, enabled: Boolean)

    fun setGenericFallbackEnabled(enabled: Boolean)

    /** Switch the splash exit strategy. */
    fun setStrategyMode(mode: BypassAdStrategyMode)

    /** Persist the one-time CRAZY confirmation. */
    fun acknowledgeCrazyMode()

    fun setActionToastEnabled(enabled: Boolean)

    fun setPersistentNotificationEnabled(enabled: Boolean)

    /** Refresh system-controlled permission state after returning from Settings. */
    fun refreshPermissionState()

    /** Try the existing internal recovery path; callers still offer the
     * system accessibility screen when Android requires user consent. */
    fun requestServiceRecovery()

    fun setAccessibilityEnabled(enabled: Boolean)

    suspend fun tryGrantAccessibilityControlWithShizuku(): Boolean

    fun retryCurrentMatch()

    suspend fun importLocalRules(source: String, sourceFileName: String? = null): BypassImportResult

    suspend fun restoreBundledRules(): BypassImportResult

    /**
     * Rebuild the effective rule stack from the clean bundled base + current
     * local import + teach, persist it, rebuild provenance and refresh
     * metadata/category state. Used after a backup restore (authoritative
     * restore contract) and by restore-bundled.
     */
    suspend fun rebuildEffectiveStack(): BypassImportResult

    suspend fun clearRecentActions()

    /** Apps that currently have at least one splash rule in the bundled set. */
    val protectedApps: StateFlow<List<BypassAppInfo>>

    suspend fun getProtectedApps(): List<BypassAppInfo>

    suspend fun getAppEnabled(packageName: String): Boolean

    suspend fun setAppEnabled(packageName: String, enabled: Boolean)

    suspend fun getAppDetail(packageName: String): BypassAppDetail?

    suspend fun setRuleGroupEnabled(packageName: String, groupKey: Int, enabled: Boolean)

    suspend fun getSubscriptions(): List<BypassSubscriptionInfo>

    suspend fun getTeachRules(): List<BypassTeachRuleSummary>

    suspend fun testTeachRule(draft: BypassTeachDraft): BypassTeachTestResult

    suspend fun saveTeachRule(draft: BypassTeachDraft): BypassTeachRuleSummary

    suspend fun deleteTeachRule(key: Int)

    suspend fun exportTeachRules(): java.io.File
}

enum class BypassAdCategory {
    SPLASH,
    IN_APP_FULLSCREEN,
    MARKETING_POPUP,
    OTHER_CLOSABLE,
}

data class BypassAdCategoryState(
    val category: BypassAdCategory,
    val enabled: Boolean,
    val appCount: Int,
    val groupCount: Int,
    val ruleCount: Int,
) {
    val title: String
        get() = when (category) {
            BypassAdCategory.SPLASH -> "自动跳过开屏广告"
            BypassAdCategory.IN_APP_FULLSCREEN -> "全屏 / 插屏广告"
            BypassAdCategory.MARKETING_POPUP -> "营销弹窗"
            BypassAdCategory.OTHER_CLOSABLE -> "其它可关闭广告"
        }

    val description: String
        get() = when (category) {
            BypassAdCategory.SPLASH -> "启动应用时自动关闭开屏广告"
            BypassAdCategory.IN_APP_FULLSCREEN -> "关闭遮挡整个界面的广告"
            BypassAdCategory.MARKETING_POPUP -> "关闭活动、推广、会员等营销内容"
            BypassAdCategory.OTHER_CLOSABLE -> "局部与分段广告，可能影响原有操作体验"
        }
}

enum class BypassServiceStatus {
    NORMAL,
    RECOVERING,
    OFF,
    NEED_AUTHORIZATION,
}

/**
 * Product a11y status (P1-5). HyperOS can keep the service Bound after an
 * upgrade/rebind without delivering onServiceConnected, so system-bound and
 * accessibility-enabled signals may lag the in-process instance. They are
 * recovery hints only; NORMAL requires the live registry instance.
 */
fun resolveBypassServiceStatus(
    instanceRunning: Boolean,
    authorized: Boolean,
    systemBound: Boolean,
    accessibilityEnabled: Boolean = false,
): BypassServiceStatus = when {
    // Only the live in-process registry proves that the Bypass service runs.
    // Authorization and system accessibility APIs are diagnostic signals.
    instanceRunning -> BypassServiceStatus.NORMAL
    !authorized -> BypassServiceStatus.NEED_AUTHORIZATION
    else -> BypassServiceStatus.RECOVERING
}

data class BypassServiceState(val status: BypassServiceStatus) {
    val label: String
        get() = when (status) {
            BypassServiceStatus.NORMAL -> "正常运行"
            BypassServiceStatus.RECOVERING -> "正在恢复"
            BypassServiceStatus.OFF -> "未开启"
            BypassServiceStatus.NEED_AUTHORIZATION -> "需要授权"
        }

    val description: String
        get() = when (status) {
            BypassServiceStatus.NORMAL -> "无障碍服务已连接，开屏广告保护正常运行。"
            BypassServiceStatus.RECOVERING -> "系统正在恢复无障碍服务，保护会很快恢复。"
            BypassServiceStatus.OFF -> "尚未开启保护。"
            BypassServiceStatus.NEED_AUTHORIZATION -> "需要授予无障碍权限，Bypass Ads 才能自动跳过开屏广告。"
        }
}

data class BypassRuleStats(
    val appCount: Int = 0,
    val groupCount: Int = 0,
    val ruleCount: Int = 0,
)

enum class BypassRuleSourceType { BUNDLED, LOCAL_IMPORT }

data class BypassRuleMetadata(
    val sourceType: BypassRuleSourceType = BypassRuleSourceType.BUNDLED,
    val bundleVersion: Int? = null,
    val installedAt: Long = 0L,
    val appCount: Int = 0,
    val groupCount: Int = 0,
    val ruleCount: Int = 0,
    val sha256: String = "",
    val sourceFileName: String? = null,
)

data class BypassPermissionState(
    val notificationGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val statusServiceRunning: Boolean = false,
    val accessibilityConnectedAt: Long = 0L,
    val hyperOsAutostartNeedsConfirmation: Boolean = false,
)

data class BypassRuntimeProtection(
    val accessibilityConnected: Boolean = false,
    val statusServiceRunning: Boolean = false,
    val notificationGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val accessibilityConnectedAt: Long = 0L,
    val lastRecoveryAttemptAt: Long = 0L,
    /** OEM autostart settings are not queryable through a reliable public API. */
    val autostartNeedsUserConfirmation: Boolean = false,
)

enum class BypassAccessibilityStatus {
    ENABLED,
    DISABLED,
    NEED_AUTHORIZATION,
    RECOVERING,
}

data class BypassAccessibilityControl(
    val status: BypassAccessibilityStatus = BypassAccessibilityStatus.NEED_AUTHORIZATION,
    val hasWriteSecureSettings: Boolean = false,
    val hasShizuku: Boolean = false,
) {
    val label: String
        get() = when (status) {
            BypassAccessibilityStatus.ENABLED -> "正常"
            BypassAccessibilityStatus.DISABLED -> "已关闭"
            BypassAccessibilityStatus.NEED_AUTHORIZATION -> "需要首次授权"
            BypassAccessibilityStatus.RECOVERING -> "服务异常"
        }
}

data class BypassActionRecord(
    val id: Int,
    val appId: String,
    val appName: String?,
    val groupName: String?,
    val time: Long,
)

data class BypassFailureRecord(
    val id: String,
    val time: Long,
    val packageName: String,
    val activityName: String?,
    val reason: FailureReason,
    val detail: String,
    val candidates: List<BypassCandidateSnapshot> = emptyList(),
    /** Strategy in effect when the session started (historical). */
    val strategyMode: BypassAdStrategyMode? = null,
) {
    val explanation: String
        get() = when (reason) {
            FailureReason.NO_RULE_FOR_APP -> "当前应用没有可用的广告规则。"
            FailureReason.CATEGORY_DISABLED -> "对应广告类别已关闭。"
            FailureReason.MASTER_DISABLED -> "首页的自动跳过广告已暂停。"
            FailureReason.APP_DISABLED -> "此应用的广告保护已关闭。"
            FailureReason.GLOBAL_EXCLUDED -> "安全排除规则阻止了这次操作。"
            FailureReason.ACTIVITY_MISMATCH -> "规则与当前页面不匹配。"
            FailureReason.SELECTOR_NO_MATCH -> "已有规则，但没有找到对应控件。"
            FailureReason.TARGET_FOUND_NOT_CLICKABLE -> "目标找到了，但当前不可点击。"
            FailureReason.ACTION_FAILED -> "点击动作没有成功执行。"
            FailureReason.ACTION_NO_EFFECT -> "动作执行后，目标仍然存在。"
            FailureReason.WAITING_FOR_DELAY -> "规则正在等待动作延迟。"
            FailureReason.ACTION_TOO_EARLY -> "页面仍在加载，规则正在等待。"
            FailureReason.ACCESSIBILITY_NODE_MISSING -> "当前无法读取无障碍节点。"
            FailureReason.EVENT_MISSED -> "可能错过了页面变化事件。"
            FailureReason.MISCLICK_SUSPECTED -> "动作把界面带到了外部页面，已立即停止。"
            FailureReason.VISUAL_CAPTURE_UNAVAILABLE -> "系统未提供小程序画面，无法完成视觉补查。"
            FailureReason.VISUAL_RECOGNITION_UNAVAILABLE -> "本地文字识别暂时不可用。"
            FailureReason.VISUAL_TARGET_STALE -> "复查时出口已变化或倒计时将结束，未点击旧位置。"
            FailureReason.UNKNOWN -> "暂时无法确定原因。"
        }
}

/**
 * One product record per ad session. All Records rows come from here; the
 * raw ActionLog is never mixed into the product timeline.
 */
data class BypassSessionRecord(
    val id: String,
    val time: Long,
    val packageName: String,
    val activityName: String?,
    /** Strategy in effect when the session started (historical). */
    val strategyMode: BypassAdStrategyMode,
    val result: BypassSessionResult,
    val actionAttempts: Int,
    val confirmedLatencyMs: Long,
    val candidateType: BypassExitCandidateType?,
    val reason: FailureReason,
    val actions: List<String> = emptyList(),
    val candidates: List<BypassCandidateSnapshot> = emptyList(),
    val ruleOrigin: String? = null,
    val candidateSeen: Boolean = false,
    val matchedRules: List<String> = emptyList(),
    val timeline: List<String> = emptyList(),
    val actedRuleKey: Int? = null,
    val actedGroupKey: Int? = null,
    val actedCandidateBounds: String? = null,
    val hasAdEvidence: Boolean = false,
) {
    val isSuccess: Boolean get() = result == BypassSessionResult.SUCCESS_CONFIRMED
    val label: String
        get() = when (result) {
            BypassSessionResult.OPEN -> "处理中"
            BypassSessionResult.SUCCESS_CONFIRMED -> "已跳过"
            BypassSessionResult.FAILURE_CONFIRMED -> "未跳过"
            BypassSessionResult.UNRESOLVED -> "无法确认"
            BypassSessionResult.MISCLICK_SUSPECTED -> "疑似误触"
        }
}

data class BypassUnsuccessfulAppStats(
    val packageName: String,
    val confirmedFailures: Int,
    val unconfirmed: Int,
    val lastTime: Long,
) {
    val total: Int get() = confirmedFailures + unconfirmed
}

/** Product stats, all derived from SUCCESS_CONFIRMED sessions. */
data class BypassStats(
    val todaySkips: Int = 0,
    val weekSkips: Int = 0,
    val totalSkips: Int = 0,
    val averageResponseMs: Long? = null,
)

data class BypassAppInfo(
    val packageName: String,
    val appName: String,
    val enabled: Boolean,
    val groupCount: Int,
    val ruleCount: Int = 0,
)

data class BypassRuleGroupInfo(
    val key: Int,
    val name: String,
    val category: BypassAdCategory,
    val enabled: Boolean,
    val ruleCount: Int,
)

data class BypassAppDetail(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val enabled: Boolean,
    val groups: List<BypassRuleGroupInfo>,
)

data class BypassSubscriptionInfo(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val appCount: Int,
    val groupCount: Int,
    val ruleCount: Int,
)

data class BypassImportResult(val accepted: Boolean, val message: String)
