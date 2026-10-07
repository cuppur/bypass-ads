package li.songe.gkd.store

import kotlinx.serialization.Serializable
import li.songe.gkd.META
import li.songe.gkd.util.AppGroupOption
import li.songe.gkd.util.AppSortOption
import li.songe.gkd.util.AutomatorModeOption
import li.songe.gkd.util.RuleSortOption
import li.songe.gkd.util.UpdateChannelOption
import li.songe.gkd.util.UpdateTimeOption

@Serializable
data class SettingsStore(
    val enableAutomator: Boolean = false,
    val automatorMode: Int = AutomatorModeOption.A11yMode.value,
    val enableShizuku: Boolean = false,
    val enableMatch: Boolean = true,
    val enableGenericFallback: Boolean = true,
    /** Android 11+: local OCR of the mini-program's top-left ad exit only. */
    val enableMiniProgramVisualSkip: Boolean = true,
    /** Bypass Ads splash exit strategy. 0=CONSERVATIVE 1=AGGRESSIVE 2=CRAZY. */
    val bypassAdStrategyMode: Int = 0,
    /** True once the user has acknowledged the CRAZY-mode confirmation. */
    val bypassCrazyModeAcknowledged: Boolean = false,
    val enableStatusService: Boolean = false,
    val excludeFromRecents: Boolean = false,
    val captureScreenshot: Boolean = false,
    val screenshotTargetAppId: String = "",
    val screenshotEventSelector: String = "",
    val httpServerPort: Int = 8888,
    val updateSubsInterval: Long = UpdateTimeOption.Everyday.value,
    val captureVolumeChange: Boolean = false,
    val toastWhenClick: Boolean = true,
    val actionToast: String = META.appName,
    val autoClearMemorySubs: Boolean = false,
    val hideSnapshotStatusBar: Boolean = false,
    val enableDarkTheme: Boolean? = null,
    val enableDynamicColor: Boolean = true,
    val showSaveSnapshotToast: Boolean = true,
    val useSystemToast: Boolean = false,
    val useCustomNotifText: Boolean = false,
    val customNotifTitle: String = META.appName,
    val customNotifText: String = $$"${i}全局/${k}应用/${u}规则/${n}触发",
    val updateChannel: Int = if (META.isBeta) UpdateChannelOption.Beta.value else UpdateChannelOption.Stable.value,
    val appSort: Int = AppSortOption.ByUsedTime.value,
    val showBlockApp: Boolean = true,
    val appRuleSort: Int = RuleSortOption.ByDefault.value,
    val subsAppSort: Int = AppSortOption.ByUsedTime.value,
    val subsCategorySort: Int = AppSortOption.ByUsedTime.value,
    val subsAppShowUninstall: Boolean = false,
    val subsAppGroupType: Int = AppGroupOption.UserGroup.value or AppGroupOption.SystemGroup.value,
    val subsCategoryGroupType: Int = AppGroupOption.UserGroup.value or AppGroupOption.SystemGroup.value,
    val subsAppShowBlock: Boolean = false,
    val subsCategoryShowBlock: Boolean = false,
    val subsExcludeSort: Int = AppSortOption.ByUsedTime.value,
    val subsExcludeShowBlockApp: Boolean = true,
    val subsExcludeShowInnerDisabledApp: Boolean = true,
    val subsPowerWarn: Boolean = true,
    val enableBlockA11yAppList: Boolean = false,
    val blockA11yAppListFollowMatch: Boolean = true,
    val a11yAppSort: Int = AppSortOption.ByUsedTime.value,
    val a11yScopeAppSort: Int = AppSortOption.ByUsedTime.value,
    val appGroupType: Int = (1 shl AppGroupOption.normalObjects.size) - 1,
    val a11yAppGroupType: Int = appGroupType,
    val a11yScopeAppGroupType: Int = appGroupType,
    val subsExcludeAppGroupType: Int = appGroupType,
    val showDisabledRule: Boolean = true,
) {
    val useA11y get() = automatorMode == AutomatorModeOption.A11yMode.value
    val useAutomation get() = automatorMode == AutomatorModeOption.AutomationMode.value
}
