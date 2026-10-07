package li.songe.gkd.bypass

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.MainActivity
import li.songe.gkd.META
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.service.HttpService
import li.songe.gkd.ui.A11YScopeAppListRoute
import li.songe.gkd.ui.A11yEventLogRoute
import li.songe.gkd.ui.ActionLogRoute
import li.songe.gkd.ui.ActivityLogRoute
import li.songe.gkd.ui.AdvancedPageRoute
import li.songe.gkd.ui.AppOpsAllowRoute
import li.songe.gkd.ui.AuthA11yRoute
import li.songe.gkd.ui.BlockA11yAppListRoute
import li.songe.gkd.ui.CrashReportRoute
import li.songe.gkd.ui.SlowGroupRoute
import li.songe.gkd.ui.SnapshotPageRoute
import li.songe.gkd.ui.SubsAppListRoute
import li.songe.gkd.ui.SubsCategoryRoute
import li.songe.gkd.ui.SubsGlobalGroupListRoute
import li.songe.gkd.util.BackupUtils
import li.songe.gkd.util.appIconMapFlow
import li.songe.gkd.util.appInfoMapFlow
import li.songe.gkd.util.requestAppIcon
import li.songe.gkd.util.saveFileToDownloads
import li.songe.gkd.util.shareFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatBypassTime(epochMs: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
private fun formatBypassClock(epochMs: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
private fun dayStart(epochMs: Long): Long = Calendar.getInstance().run {
    timeInMillis = epochMs
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

private data class BypassTimelineItem(
    val id: String,
    val time: Long,
    val action: BypassActionRecord? = null,
    val failure: BypassFailureRecord? = null,
)

private val BypassAdCategory.title: String
    get() = when (this) {
        BypassAdCategory.SPLASH -> "开屏广告"
        BypassAdCategory.IN_APP_FULLSCREEN -> "全屏 / 插屏广告"
        BypassAdCategory.MARKETING_POPUP -> "营销弹窗"
        BypassAdCategory.OTHER_CLOSABLE -> "其它可关闭广告"
    }

@Composable
private fun Drawable.toBypassImage() = remember(this) { toBitmap().asImageBitmap() }

@Composable
fun BypassHomePage(
    engine: BypassEngine,
    onOpenRecords: () -> Unit,
    onOpenRuntimeProtection: () -> Unit = {},
    onOpenNotificationManagement: () -> Unit = onOpenRuntimeProtection,
    onRequestShizukuAuthorization: () -> Unit = {},
    onOpenAdbAuthorization: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by engine.serviceState.collectAsState()
    val master by engine.masterEnabled.collectAsState()
    val stats by engine.stats.collectAsState()
    val skipped by engine.skipCount.collectAsState()
    val latest by engine.latestAction.collectAsState()
    val protection by engine.runtimeProtection.collectAsState()
    val accessibility by engine.accessibilityControl.collectAsState()
    var showAccessibilityAuth by rememberSaveable { mutableStateOf(false) }

    fun setAccessibilityEnabled(enabled: Boolean) {
        if (!enabled || accessibility.hasWriteSecureSettings || accessibility.hasShizuku) {
            engine.setAccessibilityEnabled(enabled)
        } else {
            showAccessibilityAuth = true
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassStatusCard(
            state.label,
            state.description,
            state.status == BypassServiceStatus.NORMAL,
            if (state.status == BypassServiceStatus.NORMAL) null else "处理无障碍",
        ) {
            setAccessibilityEnabled(true)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("自动跳过广告") {
            BypassSwitchRow("自动跳过广告", if (master) "所有已启用的广告规则正在运行" else "已暂停所有广告规则", master, engine::setMasterEnabled)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("运行保障") {
            BypassSwitchRow(
                "无障碍服务",
                when (accessibility.status) {
                    BypassAccessibilityStatus.ENABLED -> "正常运行"
                    BypassAccessibilityStatus.RECOVERING -> "服务异常，可重新连接"
                    BypassAccessibilityStatus.DISABLED -> "已关闭"
                    BypassAccessibilityStatus.NEED_AUTHORIZATION -> "需要首次授权"
                },
                accessibility.status == BypassAccessibilityStatus.ENABLED,
                onCheckedChange = { enabled ->
                    if (enabled && accessibility.status == BypassAccessibilityStatus.RECOVERING &&
                        (accessibility.hasWriteSecureSettings || accessibility.hasShizuku)
                    ) {
                        engine.requestServiceRecovery()
                    } else {
                        setAccessibilityEnabled(enabled)
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            BypassNavRow(
                "通知管理 · ${if (protection.notificationGranted && protection.statusServiceRunning) "正常" else "需要检查"}",
                onOpenNotificationManagement,
            )
            Spacer(Modifier.height(8.dp))
            BypassNavRow(
                "电池优化 · ${if (protection.ignoringBatteryOptimizations) "不受限制" else "建议设为无限制"}",
                onOpenRuntimeProtection,
            )
            Spacer(Modifier.height(8.dp))
            BypassNavRow(
                "后台保护 · ${if (protection.accessibilityConnected) "良好" else "需要重新检查"}",
                onOpenRuntimeProtection,
            )
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("数据概览") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BypassMetric("已启用规则", stats.ruleCount.toString(), Modifier.weight(1f))
                BypassMetric("已保护应用", "${stats.appCount} 个", Modifier.weight(1f))
                BypassMetric("累计跳过", skipped.toString(), Modifier.weight(1f))
            }
            BypassMutedText("${stats.groupCount} 个规则组正在提供保护", 12)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("最近触发") {
            if (latest == null) {
                BypassMutedText("还没有触发记录。打开带广告的应用后，这里会显示最近一次结果。", 13)
            } else {
                Text(latest!!.appName ?: latest!!.appId, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                BypassMutedText("${latest!!.groupName ?: "广告规则"} · ${formatBypassTime(latest!!.time)}", 12)
            }
            Spacer(Modifier.height(10.dp))
            BypassModeButton("查看全部", false, onOpenRecords)
        }
    }
    if (showAccessibilityAuth) {
        AlertDialog(
            onDismissRequest = { showAccessibilityAuth = false },
            title = { Text("增强控制无障碍") },
            text = {
                Text(
                    "授权后 Bypass Ads 可直接控制无障碍服务，以后无需每次进入系统设置。",
                    fontSize = 14.sp,
                    color = BypassPalette.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityAuth = false
                    onRequestShizukuAuthorization()
                }) { Text("使用 Shizuku 授权") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        showAccessibilityAuth = false
                        onOpenAdbAuthorization()
                    }) { Text("ADB 命令授权") }
                    TextButton(onClick = {
                        showAccessibilityAuth = false
                        context.openBypassSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("系统无障碍设置") }
                }
            },
        )
    }
}

@Composable
fun BypassAdBlockingPage(
    engine: BypassEngine,
    onOpenApps: () -> Unit,
    onOpenRuleDetail: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenAdvancedRules: () -> Unit,
    onOpenSplashStrategy: () -> Unit = {},
) {
    val categories by engine.adCategories.collectAsState()
    val stats by engine.stats.collectAsState()
    val strategy by engine.strategyMode.collectAsState()
    val splash = categories.firstOrNull { it.category == BypassAdCategory.SPLASH }
    val inApp = categories.filter { it.category != BypassAdCategory.SPLASH }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        splash?.let {
            BypassSectionCard("开屏广告") {
                BypassMutedText("由首页主开关控制", 13)
                Spacer(Modifier.height(6.dp))
                BypassMutedText("专用规则 ${it.groupCount} 组 · 覆盖应用 ${it.appCount} 个 · 当前策略：${strategy.label}", 12)
                Spacer(Modifier.height(10.dp))
                BypassNavRow("开屏识别策略", onOpenSplashStrategy)
            }
            Spacer(Modifier.height(14.dp))
        }
        BypassSectionCard("应用内广告") {
            inApp.forEachIndexed { index, category ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                BypassCategoryRow(category, engine::setAdCategoryEnabled)
            }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("应用控制") {
            BypassNavRow("${stats.appCount} 个应用", onOpenApps)
        }
        Spacer(Modifier.height(14.dp))
        BypassRulesAndSubscriptionsSection(
            engine = engine,
            onOpenDetail = onOpenRuleDetail,
            onOpenSubscriptions = onOpenSubscriptions,
            onOpenAdvanced = onOpenAdvancedRules,
        )
    }
}

@Composable
private fun BypassCategoryRow(state: BypassAdCategoryState, onChange: (BypassAdCategory, Boolean) -> Unit) {
    BypassSwitchRow(
        title = state.title,
        subtitle = "${state.description}\n${state.appCount} 个应用 · ${state.groupCount} 组 · ${state.ruleCount} 条规则",
        checked = state.enabled,
        onCheckedChange = { onChange(state.category, it) },
    )
}

@Composable
fun BypassAppControlPage(
    engine: BypassEngine,
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val cachedApps by engine.protectedApps.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var enabledOnly by rememberSaveable { mutableStateOf(false) }
    var sortByRules by rememberSaveable { mutableStateOf(false) }
    var refreshKey by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val icons by appIconMapFlow.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(refreshKey) {
        engine.getProtectedApps()
    }
    val shown = cachedApps.asSequence()
        .filter { !enabledOnly || it.enabled }
        .filter { it.appName.contains(query, true) || it.packageName.contains(query, true) }
        .let { if (sortByRules) it.sortedByDescending { app -> app.ruleCount } else it.sortedBy { app -> app.appName } }
        .toList()
    Column(Modifier.fillMaxSize()) {
        BypassBackButton("应用控制", onBack)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索应用或包名") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BypassModeButton(if (enabledOnly) "筛选：已启用" else "筛选", enabledOnly, { enabledOnly = !enabledOnly })
            BypassModeButton(if (sortByRules) "排序：规则数" else "排序", sortByRules, { sortByRules = !sortByRules })
            BypassModeButton("刷新", false, { refreshKey++ })
        }
        BypassMutedText("关闭某个应用会将它加入本机白名单，不影响其它应用。", 12)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(shown, key = { it.packageName }) { entry ->
                LaunchedEffect(entry.packageName) { requestAppIcon(entry.packageName) }
                val icon = icons[entry.packageName]?.toBypassImage()
                BypassAppRow(entry.appName, entry.packageName, icon, entry.enabled, "${entry.groupCount} 组 · ${entry.ruleCount} 条规则", { onOpenApp(entry.packageName) }) { enabled ->
                    scope.launch { engine.setAppEnabled(entry.packageName, enabled) }
                }
            }
        }
    }
}

@Composable
fun BypassAppDetailPage(engine: BypassEngine, packageName: String, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<BypassAppDetail?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(packageName) { detail = engine.getAppDetail(packageName) }
    val item = detail ?: return BypassMutedText("正在加载应用规则…")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("应用详情", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard(item.appName) {
            BypassMutedText(item.packageName, 12)
            item.versionName?.let { BypassMutedText("版本 $it", 12) }
            Spacer(Modifier.height(8.dp))
            BypassSwitchRow(
                "为此应用启用保护",
                "关闭后此应用进入白名单。",
                item.enabled,
                onCheckedChange = { enabled ->
                scope.launch { engine.setAppEnabled(item.packageName, enabled); detail = engine.getAppDetail(packageName) }
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("规则组") {
            item.groups.forEachIndexed { index, group ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                BypassSwitchRow(
                    group.name,
                    "${group.category.title} · ${group.ruleCount} 条规则",
                    group.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch { engine.setRuleGroupEnabled(item.packageName, group.key, enabled); detail = engine.getAppDetail(packageName) }
                    },
                )
            }
        }
    }
}

@Composable
fun BypassRecordsPage(engine: BypassEngine, onOpenFailure: (String) -> Unit) {
    val sessions by engine.sessionRecords.collectAsState()
    val stats by engine.productStats.collectAsState()
    val unsuccessful by engine.unsuccessfulApps.collectAsState()
    val apps by appInfoMapFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("全部") }
    var packageFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllApps by rememberSaveable { mutableStateOf(false) }
    var reportDialog by rememberSaveable { mutableStateOf(false) }
    var reportSearch by rememberSaveable { mutableStateOf("") }
    var reportPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }
    val records = BypassRecordPresentation.filter(sessions, filter, packageFilter)
    val listState = rememberLazyListState()

    if (reportDialog) AlertDialog(
        onDismissRequest = { if (!reporting) reportDialog = false },
        title = { Text("记一次漏跳") },
        text = {
            Column {
                BypassMutedText("刚看到广告却没有记录时，选择广告所在的 App。小程序请选择微信或支付宝。这次补记会列入未成功统计，结果标为待确认。", 12)
                OutlinedTextField(reportSearch, { reportSearch = it }, label = { Text("搜索 App 名称或包名") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                val knownPackages = (listOf("com.eg.android.AlipayGphone", "com.tencent.mm", "com.tencent.qqmusic") +
                    sessions.map { it.packageName } + apps.keys).distinct()
                val choices = knownPackages.filter {
                    reportSearch.isBlank() || it.contains(reportSearch, true) || apps[it]?.name.orEmpty().contains(reportSearch, true)
                }
                LazyColumn(Modifier.height(230.dp)) {
                    items(choices, key = { it }) { pkg ->
                        Row(Modifier.fillMaxWidth().clickable { reportPackage = pkg }.padding(vertical = 10.dp)) {
                            Text(if (reportPackage == pkg) "●  " else "○  ", color = BypassPalette.Accent)
                            Column {
                                Text(apps[pkg]?.name ?: when (pkg) {
                                    "com.eg.android.AlipayGphone" -> "支付宝"
                                    "com.tencent.mm" -> "微信"
                                    "com.tencent.qqmusic" -> "QQ音乐"
                                    else -> pkg
                                }, fontSize = 14.sp)
                                BypassMutedText(pkg, 11)
                            }
                        }
                    }
                }
                reportError?.let { BypassMutedText(it, 12) }
            }
        },
        confirmButton = { TextButton(enabled = reportPackage != null && !reporting, onClick = {
            val pkg = reportPackage ?: return@TextButton
            reporting = true
            scope.launch {
                runCatching { engine.reportMissedAd(pkg) }.onSuccess {
                    reportDialog = false
                    onOpenFailure(it)
                }.onFailure { reportError = "记录失败：${it.message ?: "未知错误"}" }
                reporting = false
            }
        }) { Text(if (reporting) "正在记录" else "记录一次") } },
        dismissButton = { TextButton(onClick = { reportDialog = false }, enabled = !reporting) { Text("取消") } },
    )
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BypassMetric("今天跳过", stats.todaySkips.toString(), Modifier.weight(1f))
            BypassMetric("本周跳过", stats.weekSkips.toString(), Modifier.weight(1f))
            BypassMetric("未成功", unsuccessful.sumOf { it.total }.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "已跳过", "未成功", "待诊断").forEach { label ->
                FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { reportDialog = true; reportError = null }) { Text("记一次漏跳") }
            TextButton(onClick = { scope.launch { engine.clearRecentActions() } }) { Text("清除记录") }
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            item {
                BypassSectionCard("广告黑匣子") {
                    BypassMutedText("一次广告一条记录，包含识别、规则、执行和复查结果。保留最近 7 天，最多 2000 条；下方显示最新 500 条。总计已跳过 ${stats.totalSkips} 次。", 12)
                }
                Spacer(Modifier.height(10.dp))
            }
            if (unsuccessful.isNotEmpty()) item {
                BypassSectionCard("未成功跳过的 App") {
                    BypassMutedText("按保留范围内全部记录统计。已确认失败与结果待确认分别列出；点 App 可筛选。", 12)
                    (if (showAllApps) unsuccessful else unsuccessful.take(5)).forEach { entry ->
                        Row(Modifier.fillMaxWidth().clickable {
                            packageFilter = entry.packageName; filter = "未成功"
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(apps[entry.packageName]?.name ?: entry.packageName, fontSize = 14.sp, color = BypassPalette.Ink)
                                BypassMutedText("确认失败 ${entry.confirmedFailures} · 待确认 ${entry.unconfirmed}", 11)
                            }
                            Text("${entry.total} 次", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (unsuccessful.size > 5) TextButton(onClick = { showAllApps = !showAllApps }) {
                        Text(if (showAllApps) "收起" else "显示全部 ${unsuccessful.size} 个 App")
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            packageFilter?.let { pkg -> item {
                TextButton(onClick = { packageFilter = null }) { Text("${apps[pkg]?.name ?: pkg} · 清除 App 筛选") }
            } }
            if (records.isEmpty()) item {
                BypassSectionCard("记录") { BypassMutedText("暂无符合筛选条件的记录。完全未捕获的广告可用“记一次漏跳”补记。", 13) }
            }
            items(records, key = { it.id }) { record ->
                Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                    .clickable { onOpenFailure(record.id) }.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(apps[record.packageName]?.name ?: record.packageName, modifier = Modifier.weight(1f),
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                        Text(record.label, fontSize = 12.sp, color = if (record.isSuccess) BypassPalette.Accent else BypassPalette.Muted)
                    }
                    BypassMutedText("${formatBypassTime(record.time)} · ${record.strategyMode.label} · ${record.actionAttempts} 次动作", 12)
                    Spacer(Modifier.height(4.dp))
                    BypassMutedText(BypassRecordPresentation.summary(record), 12, 18)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun BypassFailureDetailPage(
    engine: BypassEngine, eventId: String, onBack: () -> Unit, onTeach: (BypassFailureRecord) -> Unit,
) {
    val sessions by engine.sessionRecords.collectAsState()
    val metadata by engine.ruleMetadata.collectAsState()
    val protection by engine.runtimeProtection.collectAsState()
    val masterEnabled by engine.masterEnabled.collectAsState()
    val apps by appInfoMapFlow.collectAsState()
    val record = sessions.firstOrNull { it.id == eventId }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? MainActivity
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("广告记录详情", onBack)
        Spacer(Modifier.height(12.dp))
        if (record == null) {
            BypassSectionCard("记录暂不可用") { BypassMutedText("正在读取记录；超出最新 500 条范围或已被清除的记录无法在此打开。", 13) }
            return@Column
        }
        BypassSectionCard(record.label) {
            Text(BypassRecordPresentation.summary(record), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
            Spacer(Modifier.height(10.dp))
            BypassMutedText("${apps[record.packageName]?.name ?: record.packageName}\n${record.packageName}\n\n时间  ${formatBypassTime(record.time)}\n页面  ${record.activityName ?: "未捕获"}\n策略  ${record.strategyMode.label}\n出口  ${BypassRecordPresentation.candidate(record.candidateType)}\n来源  ${BypassRecordPresentation.origin(record.ruleOrigin)}\n动作  ${record.actionAttempts} 次\n验证耗时  ${record.confirmedLatencyMs.takeIf { it > 0 }?.let { "$it ms" } ?: "未记录"}", 12, 19)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("识别与执行依据") {
            BypassMutedText("${if (record.candidateSeen) "已捕获出口候选" else "未捕获出口候选"}\n匹配规则  ${record.matchedRules.size} 条\n动作  ${record.actions.joinToString(" → ") { BypassRecordPresentation.action(it) }.ifBlank { "未发出" }}", 12)
            record.matchedRules.forEach { BypassMutedText(it, 11, 17) }
            if (record.timeline.isEmpty()) BypassMutedText("这是旧版记录，当时没有保存完整的识别日志。", 12)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("过程日志") {
            record.timeline.forEach { raw ->
                val entry = BypassRecordPresentation.timeline(raw)
                Text("${entry.elapsed?.let { "$it  " }.orEmpty()}${entry.title}", fontSize = 13.sp, color = BypassPalette.Ink)
                BypassMutedText(entry.code, 11, 17)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (record.candidates.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            BypassSectionCard("关闭控件快照") {
                BypassMutedText("只记录出口附近的控件信息，不保存页面正文或截图。", 12)
                record.candidates.forEachIndexed { index, node ->
                    Spacer(Modifier.height(8.dp))
                    BypassMutedText("${index + 1}. ${node.className}\n文字  ${node.text ?: "空"}\n描述  ${node.description ?: "空"}\nID  ${node.viewId ?: "空"}\n区域  ${node.bounds}\n可点击  ${node.clickable} · 父级可点击  ${node.parentClickable}", 11, 17)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("诊断工具") {
            BypassModeButton("导出这条记录到下载目录", false, onClick = {
                scope.launch {
                    result = runCatching {
                        val file = withContext(Dispatchers.IO) {
                            BypassDiagnostics.writeSessionBundle(record, metadata, protection, masterEnabled)
                        }
                        if (activity != null) activity.saveFileToDownloads(file)
                        "已保存 ${file.name} 到下载目录"
                    }.getOrElse { "导出失败：${it.message ?: "未知错误"}" }
                }
            })
            if (!record.isSuccess) {
                Spacer(Modifier.height(8.dp))
                BypassModeButton("重新检查当前页面", false, onClick = {
                    engine.retryCurrentMatch(); result = "已请求重新检查当前界面"
                })
                if (record.candidates.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    BypassModeButton("教 Bypass Ads 跳过", false, onClick = {
                        onTeach(BypassFailureRecord(record.id, record.time, record.packageName, record.activityName,
                            record.reason, BypassRecordPresentation.summary(record), record.candidates, record.strategyMode))
                    })
                }
            }
            result?.let { BypassMutedText(it, 12) }
        }
    }
}

@Composable
fun BypassSettingsPage(
    engine: BypassEngine,
    onOpenPreferences: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenFullTools: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassSectionCard("提示") {
            BypassNavRow("提示设置", onOpenPreferences)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("数据与工具") {
            BypassNavRow("备份与恢复", onOpenBackup)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("高级工具", onOpenAdvanced)
            if (META.channel == "fulltools") {
                Spacer(Modifier.height(8.dp))
                BypassNavRow("完整工具与网络", onOpenFullTools)
            }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("关于") {
            BypassNavRow("关于 Bypass Ads", onOpenAbout)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("开源许可", onOpenLicenses)
        }
    }
}

@Composable
fun BypassServicePermissionsPage(engine: BypassEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val protection by engine.runtimeProtection.collectAsState()
    val permissions by engine.permissionState.collectAsState()
    LaunchedEffect(Unit) { engine.refreshPermissionState() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("后台保护", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("后台保护") {
            BypassStateLine("无障碍服务 · ${if (protection.accessibilityConnected) "正常" else "未连接"}", protection.accessibilityConnected)
            BypassStateLine("通知服务 · ${if (protection.statusServiceRunning) "正常" else "未运行"}", protection.statusServiceRunning)
            BypassStateLine("电池优化 · ${if (protection.ignoringBatteryOptimizations) "不受限制" else "建议设为无限制"}", protection.ignoringBatteryOptimizations)
            BypassStateLine(
                "系统自启动 · ${if (protection.autostartNeedsUserConfirmation) "需用户确认" else "系统未提供状态"}",
                false,
            )
            BypassMutedText(
                "最近连接：${protection.accessibilityConnectedAt.takeIf { it > 0 }?.let(::formatBypassTime) ?: "尚未连接"}",
                12,
            )
            BypassMutedText(
                "最近恢复检查：${protection.lastRecoveryAttemptAt.takeIf { it > 0 }?.let(::formatBypassTime) ?: "尚未执行"}",
                12,
            )
            Spacer(Modifier.height(10.dp))
            BypassModeButton("立即重新检查", false, onClick = { engine.refreshPermissionState(); engine.requestServiceRecovery() })
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("通知权限") {
            BypassStateLine(if (permissions.notificationGranted) "通知权限已允许" else "通知权限未允许", permissions.notificationGranted)
            Spacer(Modifier.height(10.dp))
            BypassSwitchRow(
                "常驻通知",
                "显示服务运行状态，帮助系统在后台保持通知服务。",
                protection.statusServiceRunning || engine.persistentNotificationEnabled.collectAsState().value,
                engine::setPersistentNotificationEnabled,
            )
            Spacer(Modifier.height(10.dp))
            BypassModeButton("管理", false, onClick = { context.openNotificationSettings() })
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("后台运行") {
            BypassStateLine(if (permissions.ignoringBatteryOptimizations) "未受电池优化限制" else "可能限制后台运行", permissions.ignoringBatteryOptimizations)
            Spacer(Modifier.height(10.dp))
            BypassModeButton("电池优化设置", false, onClick = { context.openBatterySettings() })
            Spacer(Modifier.height(8.dp))
            if (protection.autostartNeedsUserConfirmation) {
                BypassMutedText("HyperOS 自启动状态需要在系统设置中由用户确认。", 12)
                Spacer(Modifier.height(8.dp))
            }
            BypassModeButton("HyperOS 自启动设置", false, onClick = { context.openAppDetails() })
        }
    }
}

@Composable
fun BypassAdvancedSettingsPage(engine: BypassEngine, onBack: () -> Unit) {
    val toastEnabled by engine.actionToastEnabled.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("提示设置", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("提示设置") {
            BypassSwitchRow("跳过成功提示", if (toastEnabled) "默认文案：Bypass Ads" else "静默跳过", toastEnabled, engine::setActionToastEnabled)
            Spacer(Modifier.height(8.dp))
            BypassMutedText("提示文本和样式沿用系统 Toast，避免覆盖正在使用的应用界面。", 12)
        }
    }
}

@Composable
fun BypassSplashStrategyPage(engine: BypassEngine, onBack: () -> Unit) {
    val fallback by engine.genericFallbackEnabled.collectAsState()
    val visualSettings by storeFlow.collectAsState()
    val strategy by engine.strategyMode.collectAsState()
    val crazyAcknowledged by engine.crazyModeAcknowledged.collectAsState()
    val categories by engine.adCategories.collectAsState()
    val splash = categories.firstOrNull { it.category == BypassAdCategory.SPLASH }
    var showCrazyConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingMode by rememberSaveable { mutableStateOf<BypassAdStrategyMode?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("开屏识别策略", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("开屏识别策略") {
            BypassMutedText("首页主开关关闭时，本页全部策略暂停。", 12)
            Spacer(Modifier.height(8.dp))
            BypassMutedText("跳过策略", 13)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BypassAdStrategyMode.entries.forEach { mode ->
                    BypassModeButton(
                        label = mode.label,
                        selected = strategy == mode,
                        onClick = {
                            if (mode == BypassAdStrategyMode.CRAZY && !crazyAcknowledged) {
                                pendingMode = mode
                                showCrazyConfirm = true
                            } else {
                                engine.setStrategyMode(mode)
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("bypass-strategy-${mode.name}"),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(strategy.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
            Spacer(Modifier.height(4.dp))
            BypassMutedText(strategy.description, 13, 20)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("专用规则") {
            BypassMutedText("成熟专用规则在任意策略下都优先执行，不受本页开关影响。", 12)
            Spacer(Modifier.height(8.dp))
            BypassMutedText("${splash?.groupCount ?: 0} 组 · 覆盖应用 ${splash?.appCount ?: 0} 个", 13)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("通用开屏识别") {
            BypassMutedText("专用规则未覆盖时，使用受限全局 fallback。模式决定允许哪些候选与动作。", 12)
            Spacer(Modifier.height(8.dp))
            BypassSwitchRow("通用开屏识别", "专用规则未覆盖时使用受限全局 fallback。", fallback, engine::setGenericFallbackEnabled)
            Spacer(Modifier.height(10.dp))
            BypassSwitchRow("小程序视觉补查", "激进或彻底疯狂模式下，补查无控件的左上角广告出口。", visualSettings.enableMiniProgramVisualSkip, {
                storeFlow.value = storeFlow.value.copy(enableMiniProgramVisualSkip = it)
            })
            Spacer(Modifier.height(6.dp))
            BypassMutedText("Android 11 及以上可用。文字识别在本机完成，画面不保存；仅检查微信、支付宝小程序左上角，右上角退出区域始终排除。需同时识别出口、广告标识和旁边的倒计时，并在点击前后复查。", 12)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("安全保护") {
            BypassMutedText("支付、银行、系统页面保持排除；NEXT、跳过片头、普通关闭按钮不会在无广告上下文时被点击。", 12)
        }
    }
    if (showCrazyConfirm) {
        AlertDialog(
            onDismissRequest = { showCrazyConfirm = false; pendingMode = null },
            title = { Text("彻底疯狂模式") },
            text = {
                Text(
                    "该模式会放宽广告退出按钮的识别和动作条件，可能误点页面中的其它关闭类控件。",
                    fontSize = 14.sp,
                    color = BypassPalette.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCrazyConfirm = false
                    engine.acknowledgeCrazyMode()
                    pendingMode?.let { engine.setStrategyMode(it) }
                    pendingMode = null
                }) { Text("继续开启") }
            },
            dismissButton = {
                TextButton(onClick = { showCrazyConfirm = false; pendingMode = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BypassRulesAndSubscriptionsSection(
    engine: BypassEngine,
    onOpenDetail: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metadata by engine.ruleMetadata.collectAsState()
    val categories by engine.adCategories.collectAsState()
    var result by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            result = if (text == null) "无法读取所选文件" else engine.importLocalRules(text, uri.lastPathSegment?.substringAfterLast('/')).message
        }
    }
    BypassSectionCard("规则与订阅") {
        Text("当前规则", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Spacer(Modifier.height(6.dp))
        Text("Bypass Ads 广告规则", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Spacer(Modifier.height(6.dp))
        BypassStateLine("正在使用", true)
        Spacer(Modifier.height(10.dp))
        BypassMutedText("来源：${if (metadata.sourceType == BypassRuleSourceType.BUNDLED) "内置规则" else "本地导入"}\n规则版本：${metadata.bundleVersion?.toString() ?: "未标记版本"}\n覆盖应用：${metadata.appCount}\n专用规则组：${metadata.groupCount}\n规则：${metadata.ruleCount}\nSHA256：${metadata.sha256.ifBlank { "尚未加载" }}\n更新时间：${metadata.installedAt.takeIf { it > 0 }?.let(::formatBypassTime) ?: "尚未加载"}", 13, 20)
        metadata.sourceFileName?.let { BypassMutedText("文件：$it", 12) }
        Spacer(Modifier.height(10.dp))
        BypassNavRow("当前规则", onOpenDetail)
        Spacer(Modifier.height(12.dp))
        Text("分类覆盖", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Spacer(Modifier.height(6.dp))
        categories.forEach { state ->
            BypassMutedText("${state.title} · ${state.appCount} 个应用 · ${state.ruleCount} 条规则", 13)
            Spacer(Modifier.height(5.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("规则管理", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Spacer(Modifier.height(6.dp))
        BypassNavRow("订阅管理", onOpenSubscriptions)
        Spacer(Modifier.height(8.dp))
        BypassModeButton("导入本地规则", false, onClick = { choose.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        Spacer(Modifier.height(8.dp))
        BypassModeButton("恢复内置规则", false, onClick = { scope.launch { result = engine.restoreBundledRules().message } })
        Spacer(Modifier.height(8.dp))
        BypassNavRow("高级规则管理", onOpenAdvanced)
        result?.let { BypassMutedText(it, 12) }
    }
}

@Composable
fun BypassRuleDetailPage(engine: BypassEngine, onBack: () -> Unit, onOpenApp: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<BypassAppInfo>()) }
    val metadata by engine.ruleMetadata.collectAsState()
    LaunchedEffect(metadata.sha256) { apps = engine.getProtectedApps().sortedBy { it.appName } }
    val filtered = apps.filter { it.appName.contains(query, true) || it.packageName.contains(query, true) }
    Column(Modifier.fillMaxSize()) {
        BypassBackButton("覆盖应用", onBack)
        Spacer(Modifier.height(12.dp))
        BypassMutedText("版本 ${metadata.bundleVersion ?: "未标记"} · ${metadata.appCount} 个应用 · ${metadata.ruleCount} 条规则", 12)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索已保护应用") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.packageName }) { entry ->
                Row(Modifier.fillMaxWidth().clickable { onOpenApp(entry.packageName) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.appName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                        Text(entry.packageName, fontSize = 11.sp, color = BypassPalette.Faint)
                    }
                    BypassMutedText("${entry.groupCount} 组", 12)
                }
            }
        }
    }
}

@Composable
fun BypassBackupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { BackupUtils.importBackUpData(uri) }.onSuccess { result = "备份已导入" }.onFailure { result = "导入失败：${it.message ?: "无法读取备份"}" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("备份与恢复", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("备份与恢复") {
            BypassMutedText("备份包含应用开关、分类开关、规则包配置、本地规则和高级设置。", 12)
            Spacer(Modifier.height(10.dp))
            BypassModeButton("导入备份", false, onClick = { importLauncher.launch(arrayOf("application/zip")) })
            Spacer(Modifier.height(8.dp))
            BypassModeButton("分享备份", false, onClick = { scope.launch(Dispatchers.IO) { runCatching { activity.shareFile(BackupUtils.exportBackUpData(), "分享 Bypass Ads 备份") }.onSuccess { result = "已打开分享面板" }.onFailure { result = "导出失败：${it.message ?: "未知错误"}" } } })
            Spacer(Modifier.height(8.dp))
            BypassModeButton("保存到下载", false, onClick = { scope.launch(Dispatchers.IO) { runCatching { activity.saveFileToDownloads(BackupUtils.exportBackUpData()) }.onSuccess { result = "已保存到下载目录" }.onFailure { result = "保存失败：${it.message ?: "未知错误"}" } } })
            result?.let { BypassMutedText(it, 12) }
        }
    }
}

@Composable
fun BypassAdvancedToolsPage(
    onBack: () -> Unit,
    onOpenRoute: (NavKey) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("高级工具", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("系统作用范围") {
            BypassNavRow("无障碍作用范围", { onOpenRoute(A11YScopeAppListRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("无障碍排除范围", { onOpenRoute(BlockA11yAppListRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("AppOps 与受限设置", { onOpenRoute(AppOpsAllowRoute) })
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("诊断工具") {
            BypassNavRow("诊断", onOpenDiagnostics); Spacer(Modifier.height(8.dp))
            BypassNavRow("快照与截图", { onOpenRoute(SnapshotPageRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("Activity 日志", { onOpenRoute(ActivityLogRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("无障碍事件日志", { onOpenRoute(A11yEventLogRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("原始动作日志（调试）", { onOpenRoute(ActionLogRoute()) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("崩溃报告", { onOpenRoute(CrashReportRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("高级调试设置", { onOpenRoute(AdvancedPageRoute) })
        }
    }
}

@Composable
fun BypassAdvancedRulesPage(
    onBack: () -> Unit,
    onOpenRoute: (NavKey) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("高级规则管理", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("规则管理") {
            BypassNavRow("应用规则", { onOpenRoute(SubsAppListRoute(BYPASS_SPLASH_SUBS_ID)) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("全局规则", { onOpenRoute(SubsGlobalGroupListRoute(BYPASS_SPLASH_SUBS_ID)) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("规则类别", { onOpenRoute(SubsCategoryRoute(BYPASS_SPLASH_SUBS_ID)) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("慢规则", { onOpenRoute(SlowGroupRoute) })
        }
    }
}

@Composable
fun BypassDiagnosticsPage(
    engine: BypassEngine,
    onBack: () -> Unit,
) {
    val events by BypassDiagnostics.events.collectAsState()
    val metadata by engine.ruleMetadata.collectAsState()
    val protection by engine.runtimeProtection.collectAsState()
    val masterEnabled by engine.masterEnabled.collectAsState()
    var bundleResult by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("诊断", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("未跳过诊断") {
            BypassMutedText("诊断在本机记录最近 40 条 GKD matcher 的排除原因（正式版同样生效）。导出内容始终保留在本机，并过滤输入和完整页面文字。", 12)
            Spacer(Modifier.height(10.dp))
            BypassModeButton("生成本地诊断包", false, onClick = {
                bundleResult = runCatching {
                    val file = BypassDiagnostics.writeLocalBundle(metadata, protection, masterEnabled)
                    "已保存 ${file.name}"
                }.getOrElse { "生成失败：${it.message ?: "未知错误"}" }
            })
            Spacer(Modifier.height(8.dp))
            BypassModeButton("清除诊断", false, BypassDiagnostics::clear)
            bundleResult?.let { BypassMutedText(it, 12) }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("最近匹配结果") {
            if (events.isEmpty()) {
                BypassMutedText("尚无可用诊断。打开未跳过的广告后返回此处查看。", 12)
            } else {
                events.forEachIndexed { index, event ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Text(event.reason.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                    BypassMutedText("${event.packageName} · ${event.activityName ?: "Activity 未知"}\n${event.detail}", 11)
                }
            }
        }
    }
}

@Composable
fun BypassTeachModePage(
    engine: BypassEngine,
    eventId: String,
    packageName: String,
    activityName: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val failures by engine.failureRecords.collectAsState()
    val event = failures.firstOrNull { it.id == eventId }
    val candidates = event?.candidates.orEmpty().mapNotNull { snapshot ->
        val selector = when {
            !snapshot.viewId.isNullOrBlank() -> "[vid=\"${snapshot.viewId.escapeTeachSelector()}\"]"
            !snapshot.description.isNullOrBlank() -> "[desc=\"${snapshot.description.escapeTeachSelector()}\"]"
            !snapshot.text.isNullOrBlank() -> "[text=\"${snapshot.text.escapeTeachSelector()}\"]"
            else -> null
        } ?: return@mapNotNull null
        BypassTeachCandidate(
            selector = selector,
            label = snapshot.description ?: snapshot.text ?: snapshot.viewId ?: "跳过候选",
            detail = buildString {
                append(snapshot.className.substringAfterLast('.'))
                append(if (snapshot.clickable) " · 可点击" else if (snapshot.parentClickable) " · 父级可点击" else " · 当前不可点击")
            },
        )
    }.distinctBy { it.selector }
    var selector by rememberSaveable { mutableStateOf("") }
    var coordinateMode by rememberSaveable { mutableStateOf(false) }
    var coordinateX by rememberSaveable { mutableStateOf("0.5") }
    var coordinateY by rememberSaveable { mutableStateOf("0.5") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var savedRules by remember { mutableStateOf(emptyList<BypassTeachRuleSummary>()) }

    fun currentDraft(): BypassTeachDraft? {
        if (packageName.isBlank() || activityName.isNullOrBlank()) return null
        if (coordinateMode) {
            val x = coordinateX.toFloatOrNull() ?: return null
            val y = coordinateY.toFloatOrNull() ?: return null
            return BypassTeachRules.coordinateDraft(packageName, activityName, x, y)
        }
        return selector.takeIf { it.isNotBlank() }?.let {
            BypassTeachDraft(packageName, activityName, it)
        }
    }

    LaunchedEffect(eventId) {
        if (selector.isBlank()) selector = candidates.firstOrNull()?.selector.orEmpty()
    }
    LaunchedEffect(Unit) { savedRules = engine.getTeachRules() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("教 Bypass Ads 跳过", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("失败会话") {
            BypassMutedText("应用：$packageName", 12)
            BypassMutedText("Activity：${activityName ?: "未识别"}", 12)
            BypassMutedText("候选来自这次失败时保存的安全快照，不读取当前 Bypass Ads 页面。", 12)
            if (event == null) {
                Spacer(Modifier.height(8.dp))
                BypassMutedText("这条失败记录已不在本机保留范围内，无法再提取候选。", 12)
            }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("优先控件规则") {
            BypassMutedText("仅显示失败时捕获的跳过/关闭候选；规则只绑定该包名和 Activity。", 12)
            Spacer(Modifier.height(8.dp))
            if (candidates.isEmpty()) {
                BypassMutedText("没有保存到可安全使用的跳过候选，可手动填写已知选择器。", 12)
            } else {
                candidates.forEachIndexed { index, candidate ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    BypassNavRow("${candidate.label} · ${candidate.detail}") {
                        selector = candidate.selector
                        coordinateMode = false
                        feedback = "已选中 ${candidate.selector}"
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = selector,
                onValueChange = { selector = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("选择器") },
                singleLine = true,
            )
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("无节点时的坐标回退") {
            BypassSwitchRow(
                "启用坐标教学",
                "仅该应用、Activity，启动后 15 秒内，最多执行 1 次。默认高风险。",
                coordinateMode,
                onCheckedChange = { coordinateMode = it },
            )
            if (coordinateMode) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(coordinateX, { coordinateX = it }, Modifier.weight(1f), label = { Text("相对 X 0~1") }, singleLine = true)
                    OutlinedTextField(coordinateY, { coordinateY = it }, Modifier.weight(1f), label = { Text("相对 Y 0~1") }, singleLine = true)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("保存与验证") {
            BypassMutedText("将匹配：$packageName / ${activityName ?: "当前 Activity"}", 12)
            BypassMutedText("目标：跳过 · 预计规则：${currentDraft()?.selector ?: "尚未选择"}", 12)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BypassModeButton("开始一次验证", false, onClick = {
                    val draft = currentDraft()
                    if (draft == null) {
                        feedback = "请先选择控件，或填写有效的相对坐标"
                    } else {
                        scope.launch {
                            val result = engine.testTeachRule(draft)
                            feedback = result.message
                        }
                    }
                })
                BypassModeButton("保存为待验证", false, onClick = {
                    val draft = currentDraft()
                    when {
                        draft == null -> feedback = "请先完成规则选择"
                        else -> scope.launch {
                            runCatching { engine.saveTeachRule(draft) }
                                .onSuccess {
                                    savedRules = engine.getTeachRules()
                                    feedback = "已保存为待验证；点“开始一次验证”后返回目标应用"
                                }
                                .onFailure { feedback = "保存失败：${it.message ?: "未知错误"}" }
                        }
                    }
                })
            }
            Spacer(Modifier.height(8.dp))
            BypassMutedText("验证方式：记录目标应用与页面后，返回目标应用；引擎只执行一次验证动作，并用结果写回（成功需结果确认，首次未匹配不算失败）。微信/支付宝小程序请手动返回小程序，不自动跳转。", 12)
            feedback?.let { BypassMutedText(it, 12) }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("本地教学规则") {
            if (savedRules.isEmpty()) {
                BypassMutedText("尚未保存教学规则。", 12)
            } else {
                savedRules.forEachIndexed { index, rule ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    BypassMutedText("${rule.packageName}\n${rule.activityName}\n${rule.selector}${if (rule.coordinateFallback) " · 坐标回退" else ""}\n${rule.verification.label}", 12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BypassModeButton("重新验证", false, onClick = {
                            scope.launch {
                                val result = engine.testTeachRule(
                                    BypassTeachDraft(
                                        packageName = rule.packageName,
                                        activityName = rule.activityName,
                                        selector = rule.selector,
                                    ),
                                )
                                feedback = result.message
                            }
                        })
                        BypassModeButton("删除", false, onClick = {
                            scope.launch {
                                engine.deleteTeachRule(rule.key)
                                savedRules = engine.getTeachRules()
                                feedback = "已删除本地教学规则"
                            }
                        })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val context = LocalContext.current
            val mainActivity = context as? MainActivity
            BypassModeButton("导出本地规则", false, onClick = {
                scope.launch(Dispatchers.IO) {
                    runCatching { engine.exportTeachRules() }
                        .onSuccess { file ->
                            if (mainActivity != null) mainActivity.saveFileToDownloads(file)
                            feedback = "已导出本地教学规则"
                        }
                        .onFailure { feedback = "导出失败：${it.message ?: "未知错误"}" }
                }
            })
        }
    }
}

private val BypassTeachVerification.label: String
    get() = when (this) {
        BypassTeachVerification.PENDING_VERIFICATION -> "待验证"
        BypassTeachVerification.VERIFIED -> "已验证"
        BypassTeachVerification.TEST_FAILED -> "验证失败"
    }

private fun String.escapeTeachSelector() = replace("\\", "\\\\").replace("\"", "\\\"")

@Composable
fun BypassFullToolsPage(
    onBack: () -> Unit,
    onOpenHelp: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val running by HttpService.isRunning.collectAsState()
    val ips by HttpService.localNetworkIpsFlow.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("完整工具与网络", onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("局域网 HTTP 工具") {
            BypassSwitchRow(
                "HTTP 服务",
                "只在完整工具构建中可用，供局域网调试和规则检查使用。",
                running,
                onCheckedChange = { enabled -> if (enabled) HttpService.start() else HttpService.stop() },
            )
            if (ips.isNotEmpty()) BypassMutedText("地址：${ips.joinToString()}", 12)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("网络能力") { BypassMutedText("默认离线构建不会注册 HTTP 服务，也不包含网络权限。远程订阅在广告页的高级规则管理中统一配置。", 12) }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("在线工具") {
            BypassNavRow("在线帮助", onOpenHelp)
            Spacer(Modifier.height(8.dp))
            BypassModeButton("检查应用更新", false, onCheckUpdate)
        }
    }
}

@Composable
fun BypassAboutPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("关于 Bypass Ads", onBack); Spacer(Modifier.height(12.dp))
        BypassSectionCard("Bypass Ads") { Text(li.songe.gkd.META.versionName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink); Spacer(Modifier.height(8.dp)); BypassMutedText("完全离线的广告自动跳过工具。", 13) }
    }
}

@Composable
fun BypassLicensesPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton("开源许可", onBack); Spacer(Modifier.height(12.dp))
        BypassSectionCard("GPL-3.0 开源许可") { BypassMutedText("本应用基于 GKD（gkd-kit）构建，遵循 GPL-3.0 许可。\n\n源码与许可信息见项目仓库 LICENSE 文件。\n\n第三方广告规则仅用于本地个人自用构建，未获授权公开再分发。", 12, 20) }
    }
}

@Composable
private fun BypassBackButton(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", fontSize = 26.sp, color = BypassPalette.Ink)
        Spacer(Modifier.size(8.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
    }
}
@Composable private fun BypassStateLine(label: String, healthy: Boolean) = Text("${if (healthy) "●" else "○"} $label", fontSize = 13.sp, color = if (healthy) BypassPalette.Accent else BypassPalette.Muted)
private fun android.content.Context.openAppDetails() = openBypassSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
private fun android.content.Context.openNotificationSettings() = openBypassSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
private fun android.content.Context.openBatterySettings() = openBypassSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
private fun android.content.Context.openBypassSettings(intent: Intent) { runCatching { startActivity(intent) }.getOrElse { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } } }
