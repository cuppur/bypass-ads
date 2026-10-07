package li.songe.gkd.bypass

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.app
import li.songe.gkd.loadCleanBundledBase
import li.songe.gkd.appScope
import li.songe.gkd.data.AppConfig
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsConfig
import li.songe.gkd.db.DbSet
import li.songe.gkd.service.fixRestartAutomatorService
import li.songe.gkd.service.A11yService
import li.songe.gkd.service.A11yInstanceRegistry
import li.songe.gkd.service.StatusService
import li.songe.gkd.service.setA11yServiceEnabled
import li.songe.gkd.permission.shizukuGrantedState
import li.songe.gkd.permission.updatePermissionState
import li.songe.gkd.permission.writeSecureSettingsState
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.appInfoMapFlow
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.mapState
import li.songe.gkd.util.subsMapFlow
import li.songe.gkd.util.updateSubscription
import li.songe.gkd.util.updateSubscriptionNow
import java.security.MessageDigest

/**
 * GKD-backed [BypassEngine] adapter.
 *
 * Product UI stays on [BypassEngine]; this object is the only place that
 * touches GKD internals for product-facing state.
 */
object GkdBypassEngine : BypassEngine {

    private const val METADATA_PREFS = "bypass_rule_metadata"
    private const val CATEGORY_PREFS = "bypass_ad_categories"
    private const val CATEGORY_READY = "category_defaults_ready"
    private val metadataPrefs by lazy { app.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE) }
    private val categoryPrefs by lazy { app.getSharedPreferences(CATEGORY_PREFS, Context.MODE_PRIVATE) }
    private val metadataFlow = MutableStateFlow(readMetadata())
    private val permissionStateFlow = MutableStateFlow(readPermissionState())
    private val categoryVersion = MutableStateFlow(0)
    private val protectedAppsCache = MutableStateFlow<List<BypassAppInfo>>(emptyList())
    private val lastRecoveryAttemptAt = MutableStateFlow(0L)
    private val categoryMap by lazy { readCategoryMap() }
    // Publish the current owner synchronously at lifecycle/permission edges.
    // An asynchronous collector's old running value must not leave a live
    // service labelled as recovering after an APK upgrade.
    private val currentServiceState = MutableStateFlow(BypassServiceState(BypassServiceStatus.OFF))

    init {
        runCatching {
            app.a11yManager.addAccessibilityStateChangeListener {
                noteA11ySystemChanged()
            }
        }
        runCatching {
            app.registerObserver(
                android.provider.Settings.Secure.getUriFor(
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ),
                li.songe.gkd.contentObserver { noteA11ySystemChanged() },
            )
        }
        noteA11ySystemChanged()
        // P0-4: restore the persisted local-import origin side-map before ANY
        // resolution (the resolver also restores lazily, but the engine must
        // not depend on the UI having been opened).
        BypassRuleProvenance.restore()
        // P0-3 (3.3/3.6): rebuild the effective stack from a CLEAN bundled
        // base (APK assets, never the possibly-stale persisted subscription)
        // + the current local import + teach, and record truthful metadata:
        // LOCAL_IMPORT when a local layer exists, BUNDLED otherwise — keeping
        // the previous sourceFileName/import time instead of stamping the APK
        // update time on every launch.
        appScope.launchTry(Dispatchers.IO) {
            val localImport = BypassRuleStackManager.readLocalImport()
            val teach = BypassTeachRules.read()
            val cleanBase = loadCleanBundledBase() ?: return@launchTry
            val effective = BypassTeachRules.apply(
                BypassRuleStackManager.mergeBundledAndLocal(cleanBase, localImport),
            )
            // Always rebuild the effective stack from the clean APK base so a
            // bundled APK upgrade cannot leave the previous (possibly stale)
            // persisted subscription in place — even when there is no local
            // import / teach layer.
            updateSubscription(effective)
            val previous = metadataFlow.value
            val sourceType = if (localImport != null) BypassRuleSourceType.LOCAL_IMPORT else BypassRuleSourceType.BUNDLED
            if (previous.bundleVersion == null || localImport != null || previous.sourceType != sourceType) {
                saveMetadata(
                    metadataFor(
                        effective,
                        sourceType,
                        // Preserve the user's original source file name /
                        // import time across restarts and bundled upgrades.
                        if (sourceType == BypassRuleSourceType.LOCAL_IMPORT) {
                            previous.sourceFileName ?: localImport?.name
                        } else {
                            null
                        },
                        if (previous.installedAt > 0L) previous.installedAt
                        else app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime,
                    ),
                )
            }
            ensureCategoryDefaults(effective)
        }
    }

    /**
     * P0-3 (3.3) + backup/restore: rebuild the effective stack from the clean
     * bundled base + current local import + teach, persist it, rebuild the
     * provenance side-map, refresh metadata and category defaults. Used after
     * a backup restore and by restore-bundled.
     */
    override suspend fun rebuildEffectiveStack(): BypassImportResult {
        val localImport = BypassRuleStackManager.readLocalImport()
        val cleanBase = loadCleanBundledBase() ?: return BypassImportResult(false, "内置规则包不可用")
        val effective = BypassTeachRules.apply(
            BypassRuleStackManager.mergeBundledAndLocal(cleanBase, localImport),
        )
        updateSubscriptionNow(effective)
        val previous = metadataFlow.value
        val sourceType = if (localImport != null) BypassRuleSourceType.LOCAL_IMPORT else BypassRuleSourceType.BUNDLED
        saveMetadata(
            metadataFor(
                effective,
                sourceType,
                if (sourceType == BypassRuleSourceType.LOCAL_IMPORT) {
                    previous.sourceFileName ?: localImport?.name
                } else {
                    null
                },
                if (previous.installedAt > 0L) previous.installedAt else System.currentTimeMillis(),
            ),
        )
        ensureCategoryDefaults(effective)
        return BypassImportResult(true, "规则栈已重建")
    }

    /** Restore metadata through the engine so disk and the live flow agree. */
    fun restoreMetadata(metadata: BypassRuleMetadata) {
        saveMetadata(metadata)
    }

    /** Whether the accessibility service component is currently enabled in
     * system settings (used to distinguish "正在恢复" from "需要授权"). */
    private fun checkA11yAuthorized(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            li.songe.gkd.app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it == A11yService.a11yCn.flattenToString() }
    }

    /**
     * Whether the system currently has our accessibility service Bound (P1-5).
     * HyperOS recovery/upgrade can keep the service Bound without delivering
     * onServiceConnected, so the in-process instance flag may lag. Bound is
     * the system's own truth that the engine is already working.
     */
    private fun checkA11ySystemBound(): Boolean = runCatching {
        val am = li.songe.gkd.app.a11yManager
        am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { info ->
            val si = info.resolveInfo?.serviceInfo
            val byComponent = si?.packageName == li.songe.gkd.app.packageName &&
                si.name == A11yService.a11yCn.className
            val byId = info.id == A11yService.a11yCn.flattenToString() ||
                info.id.endsWith("/${A11yService.a11yCn.className}")
            byComponent || byId
        }
    }.getOrDefault(false)

    private fun checkA11yEnabled(): Boolean =
        runCatching { li.songe.gkd.app.a11yManager.isEnabled }.getOrDefault(false)

    /** Called from the live a11y service and from settings observers. */
    fun noteA11ySystemChanged() {
        currentServiceState.value = BypassServiceState(
            resolveBypassServiceStatus(
                instanceRunning = A11yService.instance != null,
                authorized = checkA11yAuthorized(),
                systemBound = checkA11ySystemBound(),
                accessibilityEnabled = checkA11yEnabled(),
            ),
        )
    }

    override val serviceState: StateFlow<BypassServiceState> = currentServiceState

    override val masterEnabled: StateFlow<Boolean> =
        storeFlow.mapState(appScope) { it.enableMatch }

    override val adCategories: StateFlow<List<BypassAdCategoryState>> =
        combine(subsMapFlow, categoryVersion) { subscriptions, _ ->
            buildCategoryStates(subscriptions[BYPASS_SPLASH_SUBS_ID])
        }.stateIn(
            appScope,
            SharingStarted.Eagerly,
            BypassAdCategory.entries.map { BypassAdCategoryState(it, defaultCategoryEnabled(it), 0, 0, 0) },
        )

    override val stats: StateFlow<BypassRuleStats> =
        subsMapFlow.map { subscriptions ->
            // Coverage is a property of the bundled Bypass rule stack, not of
            // the apps currently installed on this particular phone.  The
            // latter made a 170-app bundle misleadingly show "30 个应用".
            val bundle = subscriptions[BYPASS_SPLASH_SUBS_ID]
            BypassRuleStats(
                appCount = bundle?.apps?.size ?: 0,
                // This is deliberately the dedicated-app group count. The
                // single conservative generic fallback is reported separately
                // as a capability, not disguised as a dedicated rule group.
                groupCount = bundle?.apps?.sumOf { it.groups.size } ?: 0,
                ruleCount = bundle?.apps?.sumOf { app ->
                    app.groups.sumOf { group -> group.rules.size }
                }?.plus(bundle.globalGroups.sumOf { group -> group.rules.size }) ?: 0,
            )
        }.stateIn(appScope, SharingStarted.Eagerly, BypassRuleStats())

    override val ruleMetadata: StateFlow<BypassRuleMetadata> = metadataFlow

    override val permissionState: StateFlow<BypassPermissionState> = permissionStateFlow

    override val accessibilityControl: StateFlow<BypassAccessibilityControl> = combine(
        serviceState,
        permissionStateFlow,
        writeSecureSettingsState.stateFlow,
        shizukuGrantedState.stateFlow,
    ) { service, _, hasWriteSecureSettings, hasShizuku ->
        val authorized = checkA11yAuthorized()
        BypassAccessibilityControl(
            status = when {
                service.status == BypassServiceStatus.NORMAL -> BypassAccessibilityStatus.ENABLED
                authorized -> BypassAccessibilityStatus.RECOVERING
                hasWriteSecureSettings -> BypassAccessibilityStatus.DISABLED
                else -> BypassAccessibilityStatus.NEED_AUTHORIZATION
            },
            hasWriteSecureSettings = hasWriteSecureSettings,
            hasShizuku = hasShizuku,
        )
    }.stateIn(
        appScope,
        SharingStarted.Eagerly,
        BypassAccessibilityControl(),
    )

    override val runtimeProtection: StateFlow<BypassRuntimeProtection> = combine(
        serviceState,
        StatusService.isRunning,
        permissionStateFlow,
        A11yService.lastConnectedAt,
    ) { service, notificationRunning, permission, connectedAt ->
        BypassRuntimeProtection(
            accessibilityConnected = service.status == BypassServiceStatus.NORMAL,
            statusServiceRunning = notificationRunning,
            notificationGranted = permission.notificationGranted,
            ignoringBatteryOptimizations = permission.ignoringBatteryOptimizations,
            accessibilityConnectedAt = connectedAt,
            autostartNeedsUserConfirmation = permission.hyperOsAutostartNeedsConfirmation,
        )
    }.combine(lastRecoveryAttemptAt) { protection, recoveryAttempt ->
        protection.copy(lastRecoveryAttemptAt = recoveryAttempt)
    }.stateIn(appScope, SharingStarted.Eagerly, BypassRuntimeProtection())

    override val skipCount: StateFlow<Int> =
        DbSet.bypassDetectionSessionDao.countSuccessAll()
            .stateIn(appScope, SharingStarted.Eagerly, 0)

    override val sessionRecords: StateFlow<List<BypassSessionRecord>> =
        DbSet.bypassDetectionSessionDao.queryAll().map { sessions ->
            sessions.map { it.toSessionRecord() }
        }.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    override val unsuccessfulApps: StateFlow<List<BypassUnsuccessfulAppStats>> =
        DbSet.bypassDetectionSessionDao.queryUnsuccessfulByApp(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    override suspend fun reportMissedAd(packageName: String): String {
        require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName))
        val reason = when {
            !storeFlow.value.enableMatch -> FailureReason.MASTER_DISABLED
            !li.songe.gkd.a11y.isBypassAppEnabled(packageName) -> FailureReason.APP_DISABLED
            A11yService.instance == null -> FailureReason.EVENT_MISSED
            else -> FailureReason.ACCESSIBILITY_NODE_MISSING
        }
        return BypassDetectionSessions.reportMissedAd(packageName, strategyMode.value, reason)
    }

    /**
     * Latest FINALIZED ad session (never an in-progress OPEN session, never
     * the raw GKD ActionLog). The raw ActionLog is only reachable from the
     * Advanced/debug screens; Home "最近触发" is product history.
     */
    override val latestAction: StateFlow<BypassActionRecord?> =
        sessionRecords.map { records ->
            records.firstOrNull { it.result != BypassSessionResult.OPEN }?.let { r ->
                BypassActionRecord(
                    id = r.id.hashCode(),
                    appId = r.packageName,
                    appName = appInfoMapFlow.value[r.packageName]?.name,
                    groupName = r.label,
                    time = r.time,
                )
            }
        }.stateIn(appScope, SharingStarted.Eagerly, null)

    override val failureRecords: StateFlow<List<BypassFailureRecord>> =
        DbSet.bypassDetectionSessionDao.queryFailures().map { sessions ->
            sessions.map { it.toSessionRecord() }.map { r ->
                BypassFailureRecord(
                    id = r.id,
                    time = r.time,
                    packageName = r.packageName,
                    activityName = r.activityName,
                    reason = r.reason,
                    strategyMode = r.strategyMode,
                    detail = buildString {
                        append(r.strategyMode.label).append(" · ")
                        append(r.label)
                        if (r.actionAttempts > 0) append(" · ").append(r.actionAttempts).append(" 次动作")
                        r.candidateType?.let { append(" · ").append(it.name) }
                    },
                    candidates = r.candidates,
                )
            }
        }.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    override val productStats: StateFlow<BypassStats> =
        combine(
            DbSet.bypassDetectionSessionDao.countSuccessSince(startOfDay()),
            DbSet.bypassDetectionSessionDao.countSuccessSince(startOfWeek()),
            DbSet.bypassDetectionSessionDao.countSuccessAll(),
            DbSet.bypassDetectionSessionDao.avgConfirmedLatency(),
        ) { today, week, total, avg ->
            BypassStats(
                todaySkips = today,
                weekSkips = week,
                totalSkips = total,
                averageResponseMs = avg,
            )
        }.stateIn(appScope, SharingStarted.Eagerly, BypassStats())

    override val averageResponseMs: StateFlow<Int?> = BypassPerfTrace.averageActionLatencyMs

    override val genericFallbackEnabled: StateFlow<Boolean> =
        storeFlow.mapState(appScope) { it.enableGenericFallback }

    override val strategyMode: StateFlow<BypassAdStrategyMode> =
        storeFlow.mapState(appScope) { BypassAdStrategyMode.from(it.bypassAdStrategyMode) }

    override val crazyModeAcknowledged: StateFlow<Boolean> =
        storeFlow.mapState(appScope) { it.bypassCrazyModeAcknowledged }

    override val actionToastEnabled: StateFlow<Boolean> =
        storeFlow.mapState(appScope) { it.toastWhenClick }

    override val persistentNotificationEnabled: StateFlow<Boolean> =
        storeFlow.mapState(appScope) { it.enableStatusService }

    override fun setMasterEnabled(enabled: Boolean) {
        storeFlow.value = storeFlow.value.copy(enableMatch = enabled)
    }

    override fun setAdCategoryEnabled(category: BypassAdCategory, enabled: Boolean) {
        categoryPrefs.edit().putBoolean(category.name, enabled).apply()
        categoryVersion.value++
        appScope.launchTry(Dispatchers.IO) {
            val bundle = subsMapFlow.value[BYPASS_SPLASH_SUBS_ID] ?: return@launchTry
            val configs = DbSet.subsConfigDao.queryAll().filter {
                it.subsId == BYPASS_SPLASH_SUBS_ID && it.type == SubsConfig.AppGroupType
            }
            val updates = bundle.apps.flatMap { appRule ->
                appRule.groups.filter { categoryFor(appRule.id, it.name) == category }.map { group ->
                    val current = configs.find { it.appId == appRule.id && it.groupKey == group.key }
                    current?.copy(enable = enabled) ?: SubsConfig(
                        type = SubsConfig.AppGroupType,
                        enable = enabled,
                        subsId = BYPASS_SPLASH_SUBS_ID,
                        appId = appRule.id,
                        groupKey = group.key,
                    )
                }
            }
            if (updates.isNotEmpty()) DbSet.subsConfigDao.insert(*updates.toTypedArray())
        }
    }

    override fun setGenericFallbackEnabled(enabled: Boolean) {
        storeFlow.value = storeFlow.value.copy(enableGenericFallback = enabled)
        // Source-global groups replace the Bypass fallback when a mature
        // subscription supplies them. The one product switch must therefore
        // gate every active global splash group, not just fallback key 9000.
        appScope.launchTry(Dispatchers.IO) {
            val bundle = subsMapFlow.value[BYPASS_SPLASH_SUBS_ID] ?: return@launchTry
            val existing = DbSet.subsConfigDao.queryAll()
                .filter {
                    it.subsId == BYPASS_SPLASH_SUBS_ID &&
                        it.type == SubsConfig.GlobalGroupType
                }
                .associateBy { it.groupKey }
            val updates = bundle.globalGroups.map { group ->
                existing[group.key]?.copy(enable = enabled)
                    ?: SubsConfig(
                        type = SubsConfig.GlobalGroupType,
                        enable = enabled,
                        subsId = BYPASS_SPLASH_SUBS_ID,
                        groupKey = group.key,
                    )
            }
            if (updates.isNotEmpty()) DbSet.subsConfigDao.insert(*updates.toTypedArray())
        }
    }

    override fun setStrategyMode(mode: BypassAdStrategyMode) {
        storeFlow.value = storeFlow.value.copy(bypassAdStrategyMode = mode.ordinal)
    }

    override fun acknowledgeCrazyMode() {
        storeFlow.value = storeFlow.value.copy(bypassCrazyModeAcknowledged = true)
    }

    override fun setActionToastEnabled(enabled: Boolean) {
        storeFlow.value = storeFlow.value.copy(toastWhenClick = enabled)
    }

    override fun setPersistentNotificationEnabled(enabled: Boolean) {
        storeFlow.value = storeFlow.value.copy(enableStatusService = enabled)
        if (enabled) StatusService.autoStart() else StatusService.stop()
    }

    override fun refreshPermissionState() {
        permissionStateFlow.value = readPermissionState()
        noteA11ySystemChanged()
        li.songe.gkd.util.LogUtils.d(
            "a11y-status live=${A11yService.instance != null}" +
                " registry=${A11yInstanceRegistry.isRunning.value}" +
                " alias=${A11yService.isRunning.value}" +
                " authorized=${checkA11yAuthorized()}" +
                " published=${currentServiceState.value.status.name}",
        )
    }

    override fun requestServiceRecovery() {
        lastRecoveryAttemptAt.value = System.currentTimeMillis()
        appScope.launchTry(Dispatchers.IO) { fixRestartAutomatorService() }
    }

    override fun setAccessibilityEnabled(enabled: Boolean) {
        appScope.launchTry(Dispatchers.IO) {
            if (enabled && !writeSecureSettingsState.updateAndGet()) {
                if (!tryGrantAccessibilityControlWithShizuku()) return@launchTry
            }
            setA11yServiceEnabled(enabled)
            refreshPermissionState()
        }
    }

    override suspend fun tryGrantAccessibilityControlWithShizuku(): Boolean {
        if (!shizukuGrantedState.updateAndGet()) return false
        shizukuContextFlow.value.grantSelf()
        updatePermissionState()
        refreshPermissionState()
        return writeSecureSettingsState.updateAndGet()
    }

    override fun retryCurrentMatch() {
        A11yRuleEngine.onScreenForcedActive()
    }

    override suspend fun importLocalRules(source: String, sourceFileName: String?): BypassImportResult {
        val parsed = runCatching { RawSubscription.parse(source) }.getOrElse {
            return BypassImportResult(false, "无法解析规则文件")
        }
        val advertisingApps = parsed.apps.mapNotNull { appRule ->
            val groups = appRule.groups.filter { isAdvertisingGroup(it.name) }
            appRule.takeIf { groups.isNotEmpty() }?.copy(groups = groups)
        }
        if (advertisingApps.isEmpty()) return BypassImportResult(false, "文件中没有可导入的广告规则")
        // P0-3 (3.3): always merge over the CLEAN bundled base from the APK
        // assets — never over the current effective subscription, which may
        // already contain an older local import + teach (import B would
        // otherwise freeze import A into the stack).
        val sourceGlobals = parsed.globalGroups.filter { it.name == "开屏广告" || it.name.startsWith("开屏广告-") }
        val imported = parsed.copy(
            id = BYPASS_SPLASH_SUBS_ID,
            name = "Bypass Ads 广告规则",
            // Preserve the package's actual declared version. A local import
            // must never manufacture a version from the previously active set.
            version = parsed.version,
            apps = advertisingApps,
            // P0-3 (3.4): the local layer carries ONLY the local file's own
            // global groups. A file without globals gets an empty global
            // layer; bundled global/fallback is preserved by the merge and is
            // never mislabeled as local.
            globalGroups = sourceGlobals,
            categories = parsed.categories.filter { it.name in advertisingCategoryNames },
        )
        // Layered stack: persist the local layer, merge over bundled (never
        // deleting unrelated bundled coverage), then teach on top.
        BypassRuleStackManager.saveLocalImport(imported)
        val cleanBase = loadCleanBundledBase() ?: return BypassImportResult(false, "内置规则包不可用")
        val effective = BypassTeachRules.apply(
            BypassRuleStackManager.mergeBundledAndLocal(cleanBase, imported),
        )
        // Atomic apply: success is only returned after the engine holds it.
        updateSubscriptionNow(effective)
        saveMetadata(metadataFor(effective, BypassRuleSourceType.LOCAL_IMPORT, sourceFileName))
        ensureCategoryDefaults(effective)
        return BypassImportResult(true, "已导入 ${advertisingApps.size} 个应用的广告规则（与内置规则合并）")
    }

    override suspend fun restoreBundledRules(): BypassImportResult {
        // 恢复内置: remove the local import layer; Teach rules are kept
        // (they can be deleted separately in the Teach screen). Rebuild from
        // the clean bundled base (P0-3 3.3).
        BypassRuleStackManager.clearLocalImport()
        val result = rebuildEffectiveStack()
        if (!result.accepted) return result
        return BypassImportResult(true, "已恢复内置开屏规则（教学规则保留）")
    }

    override suspend fun clearRecentActions() {
        // Clear everything the product shows: session records, stats, the
        // corresponding raw Bypass action log, and the debug trace. After
        // this the Records screen is truly empty.
        DbSet.bypassDetectionSessionDao.deleteAll()
        DbSet.actionLogDao.deleteBySubsId(BYPASS_SPLASH_SUBS_ID)
        BypassDiagnostics.clear()
    }

    private fun startOfDay(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfWeek(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    override val protectedApps: StateFlow<List<BypassAppInfo>> = protectedAppsCache

    override suspend fun getProtectedApps(): List<BypassAppInfo> {
        val subs = li.songe.gkd.util.subsMapFlow.value[BYPASS_SPLASH_SUBS_ID] ?: return emptyList()
        val appConfigs = DbSet.appConfigDao.queryAll()
        val appMap = appInfoMapFlow.value
        return subs.apps.map { app ->
            val config = appConfigs.find { c -> c.subsId == BYPASS_SPLASH_SUBS_ID && c.appId == app.id }
            BypassAppInfo(
                packageName = app.id,
                appName = appMap[app.id]?.name ?: app.id,
                enabled = config?.enable ?: true,
                groupCount = app.groups.size,
                ruleCount = app.groups.sumOf { it.rules.size },
            )
        }.also { protectedAppsCache.value = it }
    }

    override suspend fun getAppEnabled(packageName: String): Boolean {
        val config = DbSet.appConfigDao.queryAll()
            .find { c -> c.subsId == BYPASS_SPLASH_SUBS_ID && c.appId == packageName }
        return config?.enable ?: true
    }

    override suspend fun setAppEnabled(packageName: String, enabled: Boolean) {
        val existing = DbSet.appConfigDao.queryAll()
            .find { c -> c.subsId == BYPASS_SPLASH_SUBS_ID && c.appId == packageName }
        DbSet.appConfigDao.insert(
            existing?.copy(enable = enabled)
                ?: AppConfig(enable = enabled, subsId = BYPASS_SPLASH_SUBS_ID, appId = packageName)
        )
    }

    override suspend fun getAppDetail(packageName: String): BypassAppDetail? {
        val subs = subsMapFlow.value[BYPASS_SPLASH_SUBS_ID] ?: return null
        val appRule = subs.apps.find { it.id == packageName } ?: return null
        val appConfig = DbSet.appConfigDao.queryAll().find {
            it.subsId == BYPASS_SPLASH_SUBS_ID && it.appId == packageName
        }
        val groupConfigs = DbSet.subsConfigDao.queryAll().filter {
            it.subsId == BYPASS_SPLASH_SUBS_ID && it.type == SubsConfig.AppGroupType && it.appId == packageName
        }
        val categoryConfigs = DbSet.categoryConfigDao.queryAll().filter {
            it.subsId == BYPASS_SPLASH_SUBS_ID
        }
        val packageInfo = runCatching { app.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        return BypassAppDetail(
            packageName = packageName,
            appName = appInfoMapFlow.value[packageName]?.name ?: appRule.name ?: packageName,
            versionName = packageInfo?.versionName,
            enabled = appConfig?.enable ?: true,
            groups = appRule.groups.map { group ->
                BypassRuleGroupInfo(
                    key = group.key,
                    name = group.name,
                    category = categoryFor(packageName, group.name),
                    enabled = li.songe.gkd.util.getGroupEnable(
                        group,
                        groupConfigs.find { it.groupKey == group.key },
                        subs.getCategory(group.name),
                        categoryConfigs.find { it.categoryKey == subs.getCategory(group.name)?.key },
                    ),
                    ruleCount = group.rules.size,
                )
            },
        )
    }

    override suspend fun setRuleGroupEnabled(packageName: String, groupKey: Int, enabled: Boolean) {
        val existing = DbSet.subsConfigDao.queryAll().find {
            it.subsId == BYPASS_SPLASH_SUBS_ID &&
                it.type == SubsConfig.AppGroupType &&
                it.appId == packageName &&
                it.groupKey == groupKey
        }
        DbSet.subsConfigDao.insert(
            existing?.copy(enable = enabled) ?: SubsConfig(
                type = SubsConfig.AppGroupType,
                enable = enabled,
                subsId = BYPASS_SPLASH_SUBS_ID,
                appId = packageName,
                groupKey = groupKey,
            ),
        )
    }

    override suspend fun getSubscriptions(): List<BypassSubscriptionInfo> {
        val subscription = subsMapFlow.value[BYPASS_SPLASH_SUBS_ID] ?: return emptyList()
        return listOf(
            BypassSubscriptionInfo(
                id = subscription.id,
                name = subscription.name,
                enabled = true,
                appCount = subscription.apps.size,
                groupCount = subscription.groupsSize,
                ruleCount = subscription.apps.sumOf { appRule -> appRule.groups.sumOf { it.rules.size } } +
                    subscription.globalGroups.sumOf { it.rules.size },
            ),
        )
    }

    override suspend fun getTeachRules(): List<BypassTeachRuleSummary> = BypassTeachRules.list()

    override suspend fun testTeachRule(draft: BypassTeachDraft): BypassTeachTestResult =
        BypassTeachRules.test(draft)

    override suspend fun saveTeachRule(draft: BypassTeachDraft): BypassTeachRuleSummary {
        val summary = BypassTeachRules.save(draft)
        val current = subsMapFlow.value[BYPASS_SPLASH_SUBS_ID]
        if (current != null) {
            val effective = BypassTeachRules.apply(current)
            updateSubscription(effective)
            ensureCategoryDefaults(effective)
            saveMetadata(
                metadataFor(
                    effective,
                    metadataFlow.value.sourceType,
                    metadataFlow.value.sourceFileName,
                ),
            )
        }
        return summary
    }

    override suspend fun deleteTeachRule(key: Int) {
        val oldOverrides = BypassTeachRules.read()
        BypassTeachRules.delete(key)
        val current = subsMapFlow.value[BYPASS_SPLASH_SUBS_ID] ?: return
        val overrideKeys = oldOverrides.apps.flatMap { it.groups }.map { it.key }.toSet()
        val sourceOnly = current.copy(
            apps = current.apps.mapNotNull { appRule ->
                val groups = appRule.groups.filterNot { it.key in overrideKeys }
                appRule.takeIf { groups.isNotEmpty() }?.copy(groups = groups)
            },
        )
        val effective = BypassTeachRules.apply(sourceOnly)
        updateSubscription(effective)
        ensureCategoryDefaults(effective)
        saveMetadata(
            metadataFor(
                effective,
                metadataFlow.value.sourceType,
                metadataFlow.value.sourceFileName,
            ),
        )
    }

    override suspend fun exportTeachRules(): java.io.File = BypassTeachRules.export()

    private fun readPermissionState(): BypassPermissionState {
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val power = app.getSystemService(PowerManager::class.java)
        return BypassPermissionState(
            notificationGranted = notifications,
            ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(app.packageName) == true,
            statusServiceRunning = StatusService.isRunning.value,
            accessibilityConnectedAt = A11yService.lastConnectedAt.value,
            // HyperOS exposes no stable public API for normal apps to query
            // its autostart switch. Never manufacture an "enabled" result.
            hyperOsAutostartNeedsConfirmation = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true),
        )
    }

    private fun buildCategoryStates(subscription: RawSubscription?): List<BypassAdCategoryState> {
        return BypassAdCategory.entries.map { category ->
            val groups = subscription?.apps.orEmpty().flatMap { appRule ->
                appRule.groups.filter { categoryFor(appRule.id, it.name) == category }
                    .map { appRule.id to it }
            }
            BypassAdCategoryState(
                category = category,
                enabled = categoryPrefs.getBoolean(category.name, defaultCategoryEnabled(category)),
                appCount = groups.map { it.first }.distinct().size,
                groupCount = groups.size,
                ruleCount = groups.sumOf { it.second.rules.size },
            )
        }
    }

    private suspend fun ensureCategoryDefaults(subscription: RawSubscription) {
        categoryPrefs.edit().apply {
            BypassAdCategory.entries.forEach { category ->
                if (!categoryPrefs.contains(category.name)) {
                    putBoolean(category.name, defaultCategoryEnabled(category))
                }
            }
            putBoolean(CATEGORY_READY, true)
            apply()
        }
        // A newly imported bundle can contain groups that did not exist during
        // the first launch. Give only those groups the persisted category
        // policy; never overwrite an explicit per-group user choice.
        val configs = DbSet.subsConfigDao.queryAll()
        val missingGroupUpdates = subscription.apps.flatMap { appRule ->
            appRule.groups.filter {
                configs.none { config ->
                        config.subsId == BYPASS_SPLASH_SUBS_ID &&
                            config.type == SubsConfig.AppGroupType &&
                            config.appId == appRule.id &&
                            config.groupKey == it.key
                    }
            }.map { group ->
                SubsConfig(
                    type = SubsConfig.AppGroupType,
                    enable = categoryPrefs.getBoolean(
                        categoryFor(appRule.id, group.name).name,
                        defaultCategoryEnabled(categoryFor(appRule.id, group.name)),
                    ),
                    subsId = BYPASS_SPLASH_SUBS_ID,
                    appId = appRule.id,
                    groupKey = group.key,
                )
            }
        }
        val missingGlobalUpdates = subscription.globalGroups.mapNotNull { group ->
            if (configs.any {
                    it.subsId == BYPASS_SPLASH_SUBS_ID &&
                        it.type == SubsConfig.GlobalGroupType &&
                        it.groupKey == group.key
                }) {
                null
            } else {
                SubsConfig(
                    type = SubsConfig.GlobalGroupType,
                    enable = storeFlow.value.enableGenericFallback,
                    subsId = BYPASS_SPLASH_SUBS_ID,
                    groupKey = group.key,
                )
            }
        }
        val updates = missingGroupUpdates + missingGlobalUpdates
        if (updates.isNotEmpty()) DbSet.subsConfigDao.insert(*updates.toTypedArray())
        categoryVersion.value++
    }

    private fun categoryFor(appId: String, groupName: String): BypassAdCategory {
        categoryMap.overrides["$appId/$groupName"]?.let { return it }
        if (categoryMap.marketingKeywords.any { groupName.contains(it, ignoreCase = true) }) {
            return BypassAdCategory.MARKETING_POPUP
        }
        return when {
            groupName == "开屏广告" || groupName.startsWith("开屏广告-") -> BypassAdCategory.SPLASH
            groupName == "全屏广告" || groupName.startsWith("全屏广告-") -> BypassAdCategory.IN_APP_FULLSCREEN
            else -> BypassAdCategory.OTHER_CLOSABLE
        }
    }

    private fun readCategoryMap(): CategoryMap {
        return runCatching {
            val root = li.songe.gkd.util.json.parseToJsonElement(
                app.assets.open("bypass_category_map.json").bufferedReader().use { it.readText() },
            ).jsonObject
            CategoryMap(
                overrides = root["overrides"]?.jsonObject.orEmpty().mapNotNull { (key, value) ->
                    runCatching { key to BypassAdCategory.valueOf(value.jsonPrimitive.content) }.getOrNull()
                }.toMap(),
                marketingKeywords = root["marketingKeywords"]?.jsonArray.orEmpty()
                    .map { it.jsonPrimitive.content }.ifEmpty { defaultMarketingKeywords },
            )
        }.getOrElse { CategoryMap(emptyMap(), defaultMarketingKeywords) }
    }

    private data class CategoryMap(
        val overrides: Map<String, BypassAdCategory>,
        val marketingKeywords: List<String>,
    )

    private val advertisingCategoryNames = setOf("开屏广告", "全屏广告", "局部广告", "分段广告")
    private val defaultMarketingKeywords = listOf("营销", "活动", "推广", "促销", "会员", "福利", "推荐", "广告弹窗")

    private fun isAdvertisingGroup(name: String) = advertisingCategoryNames.any {
        name == it || name.startsWith("$it-")
    }

    private fun defaultCategoryEnabled(category: BypassAdCategory) = category != BypassAdCategory.OTHER_CLOSABLE

    private fun readMetadata() = BypassRuleMetadata(
        sourceType = runCatching {
            BypassRuleSourceType.valueOf(metadataPrefs.getString("sourceType", "BUNDLED")!!)
        }.getOrDefault(BypassRuleSourceType.BUNDLED),
        bundleVersion = metadataPrefs.getInt("bundleVersion", -1).takeIf { it >= 0 },
        installedAt = metadataPrefs.getLong("installedAt", 0L),
        appCount = metadataPrefs.getInt("appCount", 0),
        groupCount = metadataPrefs.getInt("groupCount", 0),
        ruleCount = metadataPrefs.getInt("ruleCount", 0),
        sha256 = metadataPrefs.getString("sha256", "") ?: "",
        sourceFileName = metadataPrefs.getString("sourceFileName", null),
    )

    private fun saveMetadata(metadata: BypassRuleMetadata) {
        metadataPrefs.edit()
            .putString("sourceType", metadata.sourceType.name)
            .putInt("bundleVersion", metadata.bundleVersion ?: -1)
            .putLong("installedAt", metadata.installedAt)
            .putInt("appCount", metadata.appCount)
            .putInt("groupCount", metadata.groupCount)
            .putInt("ruleCount", metadata.ruleCount)
            .putString("sha256", metadata.sha256)
            .putString("sourceFileName", metadata.sourceFileName)
            .apply()
        metadataFlow.value = metadata
    }

    private fun metadataFor(
        bundle: RawSubscription,
        sourceType: BypassRuleSourceType,
        sourceFileName: String?,
        installedAt: Long = System.currentTimeMillis(),
    ): BypassRuleMetadata {
        val appGroups = bundle.apps.sumOf { it.groups.size }
        val rules = bundle.apps.sumOf { app -> app.groups.sumOf { it.rules.size } } +
            bundle.globalGroups.sumOf { it.rules.size }
        // Hash the exact normalized subscription that is sent to the engine.
        val normalized = li.songe.gkd.util.json.encodeToString(RawSubscription.serializer(), bundle)
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return BypassRuleMetadata(
            sourceType = sourceType,
            bundleVersion = bundle.version,
            installedAt = installedAt,
            appCount = bundle.apps.size,
            groupCount = appGroups,
            ruleCount = rules,
            sha256 = hash,
            sourceFileName = sourceFileName,
        )
    }
}
