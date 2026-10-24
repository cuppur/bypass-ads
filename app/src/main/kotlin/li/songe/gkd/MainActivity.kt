package li.songe.gkd

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import com.dylanc.activityresult.launcher.PickContentLauncher
import com.dylanc.activityresult.launcher.StartActivityLauncher
import com.dylanc.activityresult.launcher.launchForResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.reflect.jvm.jvmName
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.a11y.updateSystemDefaultAppId
import li.songe.gkd.a11y.updateTopActivity
import li.songe.gkd.bypass.BypassAdBlockingPage
import li.songe.gkd.bypass.BypassDiagnosticsPage
import li.songe.gkd.bypass.BypassAboutPage
import li.songe.gkd.bypass.BypassAdvancedSettingsPage
import li.songe.gkd.bypass.BypassAdvancedRulesPage
import li.songe.gkd.bypass.BypassAdvancedToolsPage
import li.songe.gkd.bypass.BypassAppDetailPage
import li.songe.gkd.bypass.BypassAppControlPage
import li.songe.gkd.bypass.BypassBackupPage
import li.songe.gkd.bypass.BypassBackupRoute
import li.songe.gkd.bypass.BypassAdsRoute
import li.songe.gkd.bypass.BypassAdvancedRulesRoute
import li.songe.gkd.bypass.BypassAdvancedToolsRoute
import li.songe.gkd.bypass.BypassFullToolsPage
import li.songe.gkd.bypass.BypassFullToolsRoute
import li.songe.gkd.bypass.BypassHomePage
import li.songe.gkd.bypass.BypassHomeRoute
import li.songe.gkd.bypass.BypassLicensesPage
import li.songe.gkd.bypass.BypassLicensesRoute
import li.songe.gkd.bypass.BypassPalette
import li.songe.gkd.bypass.BypassPerfTrace
import li.songe.gkd.bypass.BypassAppDetailRoute
import li.songe.gkd.bypass.BypassAppControlRoute
import li.songe.gkd.bypass.BypassAboutRoute
import li.songe.gkd.bypass.BypassDiagnosticsRoute
import li.songe.gkd.bypass.BypassNotificationManagementRoute
import li.songe.gkd.bypass.BypassPromptSettingsRoute
import li.songe.gkd.bypass.BypassRuleDetailRoute
import li.songe.gkd.bypass.BypassRuntimeProtectionRoute
import li.songe.gkd.bypass.BypassSettingsRoute
import li.songe.gkd.bypass.BypassSplashStrategyRoute
import li.songe.gkd.bypass.BypassTeachRoute
import li.songe.gkd.bypass.BypassTeachModePage
import li.songe.gkd.bypass.BypassFailureDetailPage
import li.songe.gkd.bypass.BypassFailureDetailRoute
import li.songe.gkd.bypass.BypassRecordsPage
import li.songe.gkd.bypass.BypassRecordsRoute
import li.songe.gkd.bypass.BypassRootTab
import li.songe.gkd.bypass.BypassRuleDetailPage
import li.songe.gkd.bypass.BypassServicePermissionsPage
import li.songe.gkd.bypass.BypassSettingsPage
import li.songe.gkd.bypass.BypassSplashStrategyPage
import li.songe.gkd.bypass.BypassTabBar
import li.songe.gkd.bypass.BypassTitleBar
import li.songe.gkd.bypass.GkdBypassEngine
import li.songe.gkd.permission.updatePermissionState
import li.songe.gkd.service.StatusService
import li.songe.gkd.service.fixRestartAutomatorService
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.ui.share.FixedWindowInsets
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.style.AppTheme
import li.songe.gkd.ui.A11YScopeAppListRoute
import li.songe.gkd.ui.A11yEventLogPage
import li.songe.gkd.ui.A11yEventLogRoute
import li.songe.gkd.ui.A11yScopeAppListPage
import li.songe.gkd.ui.AboutPage
import li.songe.gkd.ui.AboutRoute
import li.songe.gkd.ui.ActionLogPage
import li.songe.gkd.ui.ActionLogRoute
import li.songe.gkd.ui.ActivityLogPage
import li.songe.gkd.ui.ActivityLogRoute
import li.songe.gkd.ui.AdvancedPage
import li.songe.gkd.ui.AdvancedPageRoute
import li.songe.gkd.ui.AppConfigPage
import li.songe.gkd.ui.AppConfigRoute
import li.songe.gkd.ui.AppOpsAllowPage
import li.songe.gkd.ui.AppOpsAllowRoute
import li.songe.gkd.ui.AuthA11yPage
import li.songe.gkd.ui.AuthA11yRoute
import li.songe.gkd.ui.BlockA11yAppListPage
import li.songe.gkd.ui.BlockA11yAppListRoute
import li.songe.gkd.ui.CrashReportPage
import li.songe.gkd.ui.CrashReportRoute
import li.songe.gkd.ui.EditBlockAppListPage
import li.songe.gkd.ui.EditBlockAppListRoute
import li.songe.gkd.ui.ImagePreviewPage
import li.songe.gkd.ui.ImagePreviewRoute
import li.songe.gkd.ui.SlowGroupPage
import li.songe.gkd.ui.SlowGroupRoute
import li.songe.gkd.ui.SnapshotPage
import li.songe.gkd.ui.SnapshotPageRoute
import li.songe.gkd.ui.SubsAppGroupListPage
import li.songe.gkd.ui.SubsAppGroupListRoute
import li.songe.gkd.ui.SubsAppListPage
import li.songe.gkd.ui.SubsAppListRoute
import li.songe.gkd.ui.SubsCategoryGroupPage
import li.songe.gkd.ui.SubsCategoryGroupRoute
import li.songe.gkd.ui.SubsCategoryPage
import li.songe.gkd.ui.SubsCategoryRoute
import li.songe.gkd.ui.SubsGlobalGroupExcludePage
import li.songe.gkd.ui.SubsGlobalGroupExcludeRoute
import li.songe.gkd.ui.SubsGlobalGroupListPage
import li.songe.gkd.ui.SubsGlobalGroupListRoute
import li.songe.gkd.ui.UpsertRuleGroupPage
import li.songe.gkd.ui.UpsertRuleGroupRoute
import li.songe.gkd.ui.WebViewPage
import li.songe.gkd.ui.WebViewRoute
import li.songe.gkd.ui.home.HomeRoute
import li.songe.gkd.ui.home.ScaffoldExt
import li.songe.gkd.ui.home.useSubsManagePage
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.BarUtils
import li.songe.gkd.util.KeyboardUtils
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.componentName
import li.songe.gkd.util.fixSomeProblems
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.toast

/**
 * Bypass Ads product entry.
 *
 * The GKD engine (subscriptions, rule resolution, accessibility service) keeps
 * running underneath; this activity only renders the Bypass Ads product UI and
 * talks to the engine through [GkdBypassEngine]. No GKD product screens are
 * reachable from the normal user flow.
 */
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(li.songe.gkd.i18n.UiLanguage.context(newBase))
    }

    val mainVm by viewModels<MainViewModel>()
    val launcher by lazy { StartActivityLauncher(this) }
    val pickContentLauncher by lazy { PickContentLauncher(this) }

    // GKD 原有 UI 组件依赖这些成员（键盘/insets/文件选择）；Bypass Ads 产品壳保留定义。
    val imeFullHiddenFlow = MutableStateFlow(true)
    val imePlayingFlow = MutableStateFlow(false)

    private val imeVisible: Boolean
        get() = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    var topBarWindowInsets by mutableStateOf(WindowInsets(top = BarUtils.getStatusBarHeight()))

    private fun watchKeyboardVisible() {
        if (AndroidTarget.R) {
            ViewCompat.setWindowInsetsAnimationCallback(
                window.decorView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onStart(
                        animation: WindowInsetsAnimationCompat,
                        bounds: WindowInsetsAnimationCompat.BoundsCompat
                    ): WindowInsetsAnimationCompat.BoundsCompat {
                        imePlayingFlow.update { imeVisible }
                        return super.onStart(animation, bounds)
                    }

                    override fun onProgress(
                        insets: WindowInsetsCompat,
                        runningAnimations: List<WindowInsetsAnimationCompat>
                    ): WindowInsetsCompat {
                        return insets
                    }

                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        imeFullHiddenFlow.update { !imeVisible }
                        imePlayingFlow.update { false }
                        super.onEnd(animation)
                    }
                })
        } else {
            KeyboardUtils.registerSoftInputChangedListener(window) { height ->
                imeFullHiddenFlow.update { height == 0 }
            }
        }
    }

    suspend fun hideSoftInput(): Boolean {
        if (!imeFullHiddenFlow.updateAndGet { !imeVisible }) {
            KeyboardUtils.hideSoftInput(this@MainActivity)
            imeFullHiddenFlow.drop(1).first()
            return true
        }
        return false
    }

    fun justHideSoftInput(): Boolean {
        if (!imeFullHiddenFlow.updateAndGet { !imeVisible }) {
            KeyboardUtils.hideSoftInput(this@MainActivity)
            return true
        }
        return false
    }

    suspend fun pickFile(contentType: String): Uri? {
        val u = launcher.launchForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = contentType
        }).data?.data
        if (u == null) {
            toast("未选择文件")
        }
        return u
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        fixSomeProblems()
        super.onCreate(savedInstanceState)
        mainVm // keep GKD engine initialization alive
        launcher
        pickContentLauncher
        watchKeyboardVisible()
        StatusService.autoStart()
        setContent {
            CompositionLocalProvider(LocalMainViewModel provides mainVm) {
                AppTheme {
                    BypassApp(mainVm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisibleState++
        if (topActivityFlow.value.appId != META.appId) {
            synchronized(topActivityFlow) {
                updateTopActivity(
                    META.appId,
                    MainActivity::class.jvmName
                )
            }
        }
    }

    var isFirstResume = true
    override fun onResume() {
        super.onResume()
        GkdBypassEngine.refreshPermissionState()
        if (isFirstResume && startTime - app.startTime < 2000) {
            isFirstResume = false
        } else {
            syncFixState()
        }
    }

    override fun onStop() {
        super.onStop()
        activityVisibleState--
    }

    private val startTime = System.currentTimeMillis()
}

@Volatile
private var activityVisibleState = 0
val isActivityVisible get() = activityVisibleState > 0

val activityNavSourceName by lazy { META.appId + ".activity.nav.source" }

fun Activity.navToMainActivity() {
    if (intent != null) {
        val navIntent = Intent(intent)
        navIntent.component = MainActivity::class.componentName
        navIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        navIntent.putExtra(activityNavSourceName, this::class.jvmName)
        startActivity(navIntent)
    }
    finish()
}

private val syncStateMutex = Mutex()
fun syncFixState() {
    appScope.launchTry(Dispatchers.IO) {
        if (syncStateMutex.isLocked) {
            LogUtils.d("syncFixState isLocked")
        }
        syncStateMutex.withLock {
            updateSystemDefaultAppId()
            shizukuContextFlow.value.grantSelf()
            updatePermissionState()
            fixRestartAutomatorService()
        }
    }
}

@Serializable
private data object LegacySubscriptionToolsRoute : NavKey

@Composable
private fun BypassApp(mainVm: MainViewModel) {
    MaterialTheme {
        Surface(color = BypassPalette.PageBackground, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                val selectedTab by mainVm.bypassRootTab.collectAsState()
                val showingDetail = mainVm.backStack.size > 1
                if (!showingDetail) {
                    BypassTitleBar(subtitle = "离线自动跳过广告")
                    Spacer(Modifier.height(12.dp))
                    BypassTabBar(
                        tabs = listOf(
                            "首页" to BypassRootTab.HOME,
                            "广告" to BypassRootTab.ADS,
                            "记录" to BypassRootTab.RECORDS,
                            "设置" to BypassRootTab.SETTINGS,
                        ),
                        selected = selectedTab,
                        onSelect = mainVm::selectBypassRootTab,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                UnifiedRouteHost(
                    mainVm = mainVm,
                    rootTab = selectedTab,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun UnifiedRouteHost(
    mainVm: MainViewModel,
    rootTab: BypassRootTab,
    modifier: Modifier = Modifier,
) {
    val rootStateHolder = rememberSaveableStateHolder()
    val topRoute = mainVm.topRoute
    LaunchedEffect(topRoute) {
        withFrameNanos {
            BypassPerfTrace.detailNavigationFirstFrame(topRoute::class.simpleName.orEmpty())
        }
    }
    NavDisplay(
        modifier = modifier,
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        predictivePopTransitionSpec = { _ -> EnterTransition.None togetherWith ExitTransition.None },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        backStack = mainVm.backStack,
        onBack = mainVm::popPage,
        entryProvider = entryProvider {
            entry<BypassHomeRoute> {
                rootStateHolder.SaveableStateProvider(rootTab) {
                    LaunchedEffect(rootTab) {
                        withFrameNanos { BypassPerfTrace.tabSwitchFirstFrame(rootTab.name) }
                    }
                    when (rootTab) {
                        BypassRootTab.HOME -> BypassHomePage(
                            engine = GkdBypassEngine,
                            onOpenRecords = { mainVm.selectBypassRootTab(BypassRootTab.RECORDS) },
                            onOpenRuntimeProtection = { mainVm.navigatePage(BypassRuntimeProtectionRoute) },
                            onOpenNotificationManagement = { mainVm.navigatePage(BypassNotificationManagementRoute) },
                            onRequestShizukuAuthorization = mainVm::requestBypassAccessibilityViaShizuku,
                            onOpenAdbAuthorization = { mainVm.navigatePage(AuthA11yRoute) },
                        )
                        BypassRootTab.ADS -> BypassAdBlockingPage(
                            engine = GkdBypassEngine,
                            onOpenApps = { mainVm.navigatePage(BypassAppControlRoute) },
                            onOpenRuleDetail = { mainVm.navigatePage(BypassRuleDetailRoute) },
                            onOpenSubscriptions = { mainVm.navigatePage(LegacySubscriptionToolsRoute) },
                            onOpenAdvancedRules = { mainVm.navigatePage(BypassAdvancedRulesRoute) },
                            onOpenSplashStrategy = { mainVm.navigatePage(BypassSplashStrategyRoute) },
                        )
                        BypassRootTab.RECORDS -> BypassRecordsPage(
                            engine = GkdBypassEngine,
                            onOpenFailure = { mainVm.navigatePage(BypassFailureDetailRoute(it)) },
                        )
                        BypassRootTab.SETTINGS -> BypassSettingsPage(
                            engine = GkdBypassEngine,
                            onOpenPreferences = { mainVm.navigatePage(BypassPromptSettingsRoute) },
                            onOpenBackup = { mainVm.navigatePage(BypassBackupRoute) },
                            onOpenAdvanced = { mainVm.navigatePage(BypassAdvancedToolsRoute) },
                            onOpenFullTools = { mainVm.navigatePage(BypassFullToolsRoute) },
                            onOpenAbout = { mainVm.navigatePage(BypassAboutRoute) },
                            onOpenLicenses = { mainVm.navigatePage(BypassLicensesRoute) },
                        )
                    }
                }
            }
            // Retained for legacy code that still selects HomeRoute directly.
            entry<HomeRoute> {
                BypassHomePage(
                    engine = GkdBypassEngine,
                    onOpenRecords = { mainVm.selectBypassRootTab(BypassRootTab.RECORDS) },
                    onOpenRuntimeProtection = { mainVm.navigatePage(BypassRuntimeProtectionRoute) },
                    onOpenNotificationManagement = { mainVm.navigatePage(BypassNotificationManagementRoute) },
                    onRequestShizukuAuthorization = mainVm::requestBypassAccessibilityViaShizuku,
                )
            }
            entry<BypassRuntimeProtectionRoute> { BypassServicePermissionsPage(GkdBypassEngine, mainVm::popPage) }
            entry<BypassNotificationManagementRoute> { BypassServicePermissionsPage(GkdBypassEngine, mainVm::popPage) }
            entry<BypassPromptSettingsRoute> { BypassAdvancedSettingsPage(GkdBypassEngine, mainVm::popPage) }
            entry<BypassSplashStrategyRoute> { BypassSplashStrategyPage(GkdBypassEngine, mainVm::popPage) }
            entry<BypassAppControlRoute> {
                BypassAppControlPage(
                    engine = GkdBypassEngine,
                    onBack = mainVm::popPage,
                    onOpenApp = { mainVm.navigatePage(BypassAppDetailRoute(it)) },
                )
            }
            entry<BypassRuleDetailRoute> {
                BypassRuleDetailPage(GkdBypassEngine, mainVm::popPage) { mainVm.navigatePage(BypassAppDetailRoute(it)) }
            }
            entry<BypassAppDetailRoute> { route -> BypassAppDetailPage(GkdBypassEngine, route.packageName, mainVm::popPage) }
            entry<BypassFailureDetailRoute> { route ->
                BypassFailureDetailPage(
                    engine = GkdBypassEngine,
                    eventId = route.eventId,
                    onBack = mainVm::popPage,
                    onTeach = { event ->
                        mainVm.navigatePage(
                            BypassTeachRoute(event.id, event.packageName, event.activityName),
                        )
                    },
                )
            }
            entry<BypassBackupRoute> { BypassBackupPage(mainVm::popPage) }
            entry<BypassAdvancedToolsRoute> {
                BypassAdvancedToolsPage(mainVm::popPage, mainVm::navigatePage) {
                    mainVm.navigatePage(BypassDiagnosticsRoute)
                }
            }
            entry<BypassAdvancedRulesRoute> {
                BypassAdvancedRulesPage(
                    onBack = mainVm::popPage,
                    onOpenRoute = mainVm::navigatePage,
                )
            }
            entry<BypassDiagnosticsRoute> {
                BypassDiagnosticsPage(GkdBypassEngine, mainVm::popPage)
            }
            entry<BypassTeachRoute> { route ->
                BypassTeachModePage(
                    engine = GkdBypassEngine,
                    eventId = route.eventId,
                    packageName = route.packageName,
                    activityName = route.activityName,
                    onBack = mainVm::popPage,
                )
            }
            entry<BypassFullToolsRoute> {
                BypassFullToolsPage(
                    onBack = mainVm::popPage,
                    onOpenHelp = { mainVm.navigatePage(WebViewRoute("https://gkd.li/guide/intro")) },
                    onCheckUpdate = { mainVm.updateStatus?.checkUpdate(true) },
                )
            }
            entry<BypassAboutRoute> { BypassAboutPage(mainVm::popPage) }
            entry<BypassLicensesRoute> { BypassLicensesPage(mainVm::popPage) }
            entry<LegacySubscriptionToolsRoute> { LegacySubscriptionToolsPage() }
            entry<AuthA11yRoute> { AuthA11yPage() }
            entry<AboutRoute> { AboutPage() }
            entry<BlockA11yAppListRoute> { BlockA11yAppListPage() }
            entry<AdvancedPageRoute> { AdvancedPage() }
            entry<SnapshotPageRoute> { SnapshotPage() }
            entry<AppOpsAllowRoute> { AppOpsAllowPage() }
            entry<A11YScopeAppListRoute> { A11yScopeAppListPage() }
            entry<ActivityLogRoute> { ActivityLogPage() }
            entry<A11yEventLogRoute> { A11yEventLogPage() }
            entry<EditBlockAppListRoute> { EditBlockAppListPage() }
            entry<SlowGroupRoute> { SlowGroupPage() }
            entry<SubsAppListRoute> { SubsAppListPage(it) }
            entry<WebViewRoute> { WebViewPage(it) }
            entry<SubsCategoryRoute> { SubsCategoryPage(it) }
            entry<SubsGlobalGroupListRoute> { SubsGlobalGroupListPage(it) }
            entry<SubsGlobalGroupExcludeRoute> { SubsGlobalGroupExcludePage(it) }
            entry<ActionLogRoute> { ActionLogPage(it) }
            entry<ImagePreviewRoute> { ImagePreviewPage(it) }
            entry<UpsertRuleGroupRoute> { UpsertRuleGroupPage(it) }
            entry<SubsAppGroupListRoute> { SubsAppGroupListPage(it) }
            entry<AppConfigRoute> { AppConfigPage(it) }
            entry<CrashReportRoute> { CrashReportPage() }
            entry<SubsCategoryGroupRoute> { SubsCategoryGroupPage(it) }
        },
    )
    mainVm.inputSubsLinkOption.ContentDialog()
}

@Composable
private fun LegacySubscriptionToolsPage() {
    val page: ScaffoldExt = useSubsManagePage()
    Scaffold(
        modifier = page.modifier,
        topBar = page.topBar,
        floatingActionButton = page.floatingActionButton,
        content = page.content,
    )
}
