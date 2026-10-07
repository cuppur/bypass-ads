package li.songe.gkd.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.META
import li.songe.gkd.bypass.BypassActionBudget
import li.songe.gkd.bypass.BypassExecutionArbiter
import li.songe.gkd.bypass.BypassVisualSkipper
import li.songe.gkd.bypass.BypassAdCategory
import li.songe.gkd.bypass.GkdBypassEngine
import li.songe.gkd.bypass.BypassAdActionPolicy
import li.songe.gkd.bypass.BypassAdContextLevel
import li.songe.gkd.bypass.BypassAdContextTracker
import li.songe.gkd.bypass.BypassOutcome
import li.songe.gkd.bypass.BypassOutcomeVerifier
import li.songe.gkd.bypass.BypassPerfTrace
import li.songe.gkd.bypass.BypassDiagnostics
import li.songe.gkd.bypass.BypassDetectionSessions
import li.songe.gkd.bypass.BypassSessionAdEvidence
import li.songe.gkd.bypass.Bounds
import li.songe.gkd.bypass.BypassRejectReason
import li.songe.gkd.bypass.BypassExitCandidateType
import li.songe.gkd.bypass.BypassExitClassifier
import li.songe.gkd.bypass.BypassRulePolicyResolver
import li.songe.gkd.bypass.BypassRuleTrust
import li.songe.gkd.bypass.BypassRuntimeFlow
import li.songe.gkd.bypass.BypassMiniProgramLayouts
import li.songe.gkd.bypass.BypassMiniProgramTargets
import li.songe.gkd.bypass.BypassRootAdObserver
import li.songe.gkd.bypass.BypassObservedAdEvidence
import li.songe.gkd.bypass.BypassBlackbox
import li.songe.gkd.bypass.BypassStrategyGate
import li.songe.gkd.bypass.BypassTeachRules
import li.songe.gkd.bypass.BypassWindowAnchor
import li.songe.gkd.bypass.BypassWindowAnchorObservationType
import li.songe.gkd.bypass.FailureReason
import li.songe.gkd.data.ActionPerformer
import li.songe.gkd.data.ActionResult
import li.songe.gkd.data.AppRule
import li.songe.gkd.data.GkdAction
import li.songe.gkd.data.ResolvedRule
import li.songe.gkd.data.RpcError
import li.songe.gkd.data.RuleStatus
import li.songe.gkd.isActivityVisible
import li.songe.gkd.service.A11yService
import li.songe.gkd.service.EventService
import li.songe.gkd.service.topAppIdFlow
import li.songe.gkd.shizuku.casted
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.shizuku.uiAutomationFlow
import li.songe.gkd.store.actualBlockA11yAppList
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.AutomatorModeOption
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.runMainPost
import li.songe.gkd.util.showActionToast
import li.songe.gkd.util.systemUiAppId
import li.songe.selector.MatchOption
import li.songe.selector.Selector
import java.util.concurrent.Executors
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume


private val eventDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
private val queryDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
private val actionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

private val latestServiceMode = atomic(0)
private val latestServiceTime = atomic(0L)
// A service reconnect / automation-mode handover shares the same action owner.
private val bypassExecution = BypassExecutionArbiter()

class A11yRuleEngine(val service: A11yCommonImpl) {
    private val a11yContext = A11yContext(this)
    private val visualSkipper by lazy {
        BypassVisualSkipper(service, bypassExecution,
            canRun = { effective && scope.isActive && isInteractive && storeFlow.value.enableMatch &&
                !activityRuleFlow.value.blockMatch && isBypassAppEnabled(topActivityFlow.value.appId) &&
                GkdBypassEngine.adCategories.value.any { it.category == BypassAdCategory.SPLASH && it.enabled } },
            freshPackage = { getTimeoutActiveWindow()?.packageName?.toString() },
            onVerifiedExit = {
                activityRuleFlow.value.currentRules.filter { it.subsItem.id == BYPASS_SPLASH_SUBS_ID }.forEach { it.rearmAfterAdExit() }
                val top = topActivityFlow.value
                BypassAdContextTracker.clearWindowEvidence(top.appId, top.activityId)
            })
    }
    private val effective get() = latestServiceMode.value == service.mode.value
    private val hasOthersService = when (service.mode) {
        AutomatorModeOption.A11yMode -> uiAutomationFlow.value != null
        AutomatorModeOption.AutomationMode -> A11yService.instance != null
    }

    private fun canObserveAds(packageName: String = topActivityFlow.value.appId): Boolean =
        packageName !in setOf(META.appId, systemUiAppId, imeAppId, launcherAppId) &&
        BypassRootAdObserver.canObserve(packageName) &&
            (packageName in setOf("com.tencent.mm", "com.eg.android.AlipayGphone") ||
                storeFlow.value.enableGenericFallback || activityRuleFlow.value.appRules.any { it.subsItem.id == BYPASS_SPLASH_SUBS_ID })

    /** Per-top-app exit-attempt counters for strategy-aware bounded retries. */
    @Volatile
    private var observedTopApp = ""

    fun onAppChanged() {
        BypassActionBudget.clear()
        observedTopApp = ""
    }

    fun onA11yConnected() {
        val serviceTime = System.currentTimeMillis()
        latestServiceMode.value = service.mode.value
        latestServiceTime.value = serviceTime
        visualSkipper.start(scope)
        if (storeFlow.value.enableBlockA11yAppList && !actualBlockA11yAppList.contains(topAppIdFlow.value)) {
            startQueryJob(byForced = true)
        }
        runMainPost(1000L) {// 共存 1000ms, 等待另一个服务稳定
            if (latestServiceTime.value == serviceTime) {
                when (service.mode) {
                    AutomatorModeOption.A11yMode -> uiAutomationFlow.value?.shutdown(true)
                    AutomatorModeOption.AutomationMode -> A11yService.instance?.shutdown(true)
                }
            }
        }
    }

    fun onScreenForcedActive() {
        // 关闭屏幕 -> Activity::onStop -> 点亮屏幕 -> Activity::onStart -> Activity::onResume
        val a = topActivityFlow.value
        synchronized(topActivityFlow) {
            updateTopActivity(
                a.appId,
                a.activityId,
                scene = ActivityScene.ScreenOn
            )
        }
        startQueryJob()
    }

    val safeActiveWindow: AccessibilityNodeInfo?
        get() = try {
            // 某些应用耗时 554ms
            // java.lang.SecurityException: Call from user 0 as user -2 without permission INTERACT_ACROSS_USERS or INTERACT_ACROSS_USERS_FULL not allowed.
            service.windowNodeInfo?.setGeneratedTime()
        } catch (_: Throwable) {
            null
        }.apply {
            a11yContext.rootCache.value = this
        }

    val safeActiveWindowAppId: String?
        get() = safeActiveWindow?.packageName?.toString()

    private val scope get() = service.scope

    @Volatile
    private var latestStateEvent: A11yEvent? = null
    private var lastContentEventTime = 0L
    private var lastEventTime = 0L
    private val eventDeque = ArrayDeque<A11yEvent>()
    fun onA11yEvent(event: AccessibilityEvent?) {
        BypassPerfTrace.appSwitch()
        if (!effective) return
        if (!event.isUseful()) return
        // 拒绝副屏无障碍事件
        if (AndroidTarget.TIRAMISU && event.displayId != Display.DEFAULT_DISPLAY) return
        onA11yFeatEvent(event)
        if (event.eventType == CONTENT_CHANGED) {
            if (!isInteractive) return // 屏幕关闭后仍然有无障碍事件 type:2048, time:8094, app:com.miui.aod, cls:android.widget.TextView
            if (event.packageName == systemUiAppId && event.packageName != topActivityFlow.value.appId) return
        }
        // 过滤部分输入法事件
        if (event.packageName == imeAppId && topActivityFlow.value.appId != imeAppId) {
            if (event.recordCount == 0 && event.action == 0 && !event.isFullScreen) return
        }
        // 直接丢弃自身事件，自行更新 topActivity
        if ((event.eventType == CONTENT_CHANGED || !isActivityVisible) && event.packageName == META.appId) return

        val a11yEvent = event.toA11yEvent() ?: return
        if (a11yEvent.type == CONTENT_CHANGED) {
            // 防止 content 类型事件过快
            if (a11yEvent.time - lastContentEventTime < 100 && a11yEvent.time - appChangeTime > 5000 && a11yEvent.time - lastTriggerTime > 3000) {
                return
            }
            lastContentEventTime = a11yEvent.time
        }
        EventService.logEvent(event)
        if (META.debuggable) {
            Log.d(
                "onNewA11yEvent",
                "type:${event.eventType}, time:${event.eventTime - lastEventTime}, app:${event.packageName}, cls:${event.className}"
            )
        }
        if (event.eventTime < lastEventTime) {
            // 某些应用会发送负时间事件, 直接丢弃
            // type:32, time:-104, app:com.miui.home, cls:com.miui.home.launcher.Launcher
            return
        }
        lastEventTime = event.eventTime
        if (event.eventType == STATE_CHANGED) {
            latestStateEvent = a11yEvent
        }
        synchronized(eventDeque) { eventDeque.addLast(a11yEvent) }
        scope.launch(eventDispatcher) { consumeEvent(a11yEvent) }
    }

    private val queryEvents = mutableListOf<A11yEvent>()
    @Volatile private var eventNeedsSettling = false
    private suspend fun consumeEvent(headEvent: A11yEvent) {
        val consumedEvents = synchronized(eventDeque) {
            if (eventDeque.firstOrNull() !== headEvent) return
            eventDeque.filter { it.sameAs(headEvent) }.apply {
                repeat(size) { eventDeque.removeFirst() }
            }
        }
        val latestEvent = consumedEvents.last()
        val evAppId = latestEvent.appId
        val evActivityId = latestEvent.name
        val oldAppId = topActivityFlow.value.appId
        val rightAppId = if (oldAppId == evAppId) {
            evAppId
        } else {
            getTimeoutAppId() ?: return
        }
        if (rightAppId == evAppId) {
            if (latestEvent.type == STATE_CHANGED) {
                synchronized(topActivityFlow) {
                    // tv.danmaku.bili, com.miui.home, com.miui.home.launcher.Launcher
                    if (isActivity(evAppId, evActivityId)) {
                        updateTopActivity(evAppId, evActivityId)
                    }
                }
            }
        }
        if (rightAppId != topActivityFlow.value.appId) {
            synchronized(topActivityFlow) {
                // 从 锁屏，下拉通知栏 返回等情况, 应用不会发送事件, 但是系统组件会发送事件
                val topCpn = shizukuContextFlow.value.topCpn()
                if (topCpn?.packageName == rightAppId) {
                    updateTopActivity(topCpn.packageName, topCpn.className)
                } else {
                    updateTopActivity(rightAppId, null)
                }
            }
        }
        val activityRule = activityRuleFlow.value
        if (!storeFlow.value.enableMatch) {
            BypassDiagnostics.record(FailureReason.MASTER_DISABLED, evAppId, evActivityId, "matching_disabled")
            return
        }
        if (evAppId != rightAppId || (activityRule.skipConsumeEvent && !canObserveAds(rightAppId))) {
            return
        }
        synchronized(queryEvents) { queryEvents.addAll(consumedEvents) }
        a11yContext.interruptKey++
        eventNeedsSettling = canObserveAds(rightAppId)
        startQueryJob(byEvent = latestEvent)
    }

    private var lastGetAppIdTime = 0L
    private var lastAppId: String? = null
    private suspend fun getTimeoutAppId(): String? {
        if (lastAppId != null && System.currentTimeMillis() - lastGetAppIdTime <= 100) return lastAppId
        // 某些应用通过无障碍获取 safeActiveWindow 耗时长，导致多个事件连续堆积堵塞，无法检测到 appId 切换导致状态异常
        // https://github.com/gkd-kit/gkd/issues/622
        lastAppId = withTimeoutOrNull(100) {
            runInterruptible(Dispatchers.IO) { safeActiveWindowAppId }
        } ?: shizukuContextFlow.value.topCpn()?.packageName
        lastGetAppIdTime = System.currentTimeMillis()
        return lastAppId
    }

    // 某些场景耗时 5000 ms
    private suspend fun getTimeoutActiveWindow(): AccessibilityNodeInfo? {
        return suspendCancellableCoroutine { s ->
            val temp = atomic<Continuation<AccessibilityNodeInfo?>?>(s)
            scope.launch(Dispatchers.IO) {
                delay(500L)
                if (s.isActive) {
                    temp.getAndUpdate { null }?.resume(null)
                }
            }
            scope.launch(Dispatchers.IO) {
                val a = safeActiveWindow
                if (s.isActive) {
                    temp.getAndUpdate { null }?.resume(a)
                }
            }
        }
    }

    @Volatile
    private var querying = false

    @Volatile
    private var bypassVerificationJob: Job? = null

    @Synchronized
    private fun startQueryJob(
        byEvent: A11yEvent? = null,
        byForced: Boolean = false,
        byDelayRule: ResolvedRule? = null,
    ) {
        if (!effective) return
        // Expire armed teach verifications whose window ended without success.
        BypassTeachRules.checkVerificationWindows()
        if (!storeFlow.value.enableMatch) {
            BypassDiagnostics.record(FailureReason.MASTER_DISABLED, detail = "matching_disabled")
            return
        }
        if (activityRuleFlow.value.currentRules.isEmpty()) {
            BypassDiagnostics.record(FailureReason.NO_RULE_FOR_APP, detail = "no_resolved_rules")
            if (!canObserveAds()) return
        }
        if (querying) return
        // 无障碍从零启动时获取 safeActiveWindow 非常耗时
        if (byEvent == null && service.justStarted && !hasOthersService) return checkFutureStartJob()
        querying = true
        scope.launchTry(queryDispatcher) {
            val st = if (META.debuggable) System.currentTimeMillis() else 0L
            try {
                if (META.debuggable) {
                    Log.d(
                        "A11yRuleEngine",
                        "startQueryJob start byEvent=${byEvent != null}, byForced=$byForced, byDelayRule=${byDelayRule != null}"
                    )
                }
                queryAction(byEvent, byForced, byDelayRule)
            } finally {
                querying = false
                checkFutureStartJob()
                if (META.debuggable) {
                    val et = System.currentTimeMillis() - st
                    Log.d("A11yRuleEngine", "startQueryJob end $et ms")
                }
            }
        }
    }

    @Volatile private var futureQueryJob: Job? = null

    @Synchronized
    private fun checkFutureStartJob() {
        if (futureQueryJob?.isActive == true || !isInteractive) return
        val t = System.currentTimeMillis()
        val nearEntryOrAction = t - lastTriggerTime < 3000L || t - appChangeTime < 3000L
        val activityRule = activityRuleFlow.value
        val forced = !nearEntryOrAction && activityRule.hasFeatureAction
        val miniPolling = storeFlow.value.enableMatch && !activityRule.blockMatch && isBypassAppEnabled(activityRule.topActivity.appId) &&
            BypassAdContextTracker.isMiniProgramAdActivity(activityRule.topActivity.appId, activityRule.topActivity.activityId)
        val settling = eventNeedsSettling
        if (!nearEntryOrAction && !forced && !miniPolling && !settling) return
        futureQueryJob = scope.launch(actionDispatcher) {
            // A mini-program SDK may expose a close control without emitting
            // a new accessibility event. Keep a single low-frequency watcher.
            delay(if (nearEntryOrAction && t - appChangeTime < 1500L) 150 else if (settling) 250 else if (!nearEntryOrAction && !forced && miniPolling) 1000 else 300)
            futureQueryJob = null
            eventNeedsSettling = false
            startQueryJob(byForced = forced)
        }
    }

    private fun fixAppId(rightAppId: String) {
        if (topActivityFlow.value.appId == rightAppId) return
        synchronized(topActivityFlow) {
            val topCpn = shizukuContextFlow.value.topCpn()
            if (topCpn?.packageName == rightAppId) {
                updateTopActivity(topCpn.packageName, topCpn.className)
            } else {
                updateTopActivity(rightAppId, null)
            }
        }
        scope.launch(actionDispatcher) {
            delay(300)
            startQueryJob()
        }
    }

    private fun scheduleBypassRetry(sessionId: String, context: TopActivity) {
        scope.launch(actionDispatcher) {
            // A failed action is still inside queryAction: an immediate
            // startQueryJob is discarded by `querying`. Re-enter after this
            // pass, using a normal query so rules without forcedTime can run.
            delay(300)
            if (topActivityFlow.value != context || !BypassDetectionSessions.isActive(sessionId)) return@launch
            startQueryJob()
        }
    }

    private suspend fun queryAction(
        byEvent: A11yEvent? = null,
        byForced: Boolean = false,
        delayRule: ResolvedRule? = null,
    ) {
        val tempStateEvent = latestStateEvent
        val newEvents = if (delayRule != null) {// 延迟规则不消耗事件
            null
        } else {
            synchronized(queryEvents) {
                if (byEvent != null && queryEvents.isEmpty()) {
                    return
                }
                (if (queryEvents.size > 1) {
                    val hasDiffItem = queryEvents.any { e ->
                        queryEvents.any { e2 -> !e.sameAs(e2) }
                    }
                    if (hasDiffItem) {
                        // 存在不同的事件节点, 全部丢弃使用 root 查询
                        null
                    } else {
                        // type,appId,className 一致, 需要在 synchronized 外验证是否是同一节点
                        arrayOf(
                            queryEvents[queryEvents.size - 2],
                            queryEvents.last(),
                        )
                    }
                } else if (queryEvents.size == 1) {
                    arrayOf(queryEvents.last())
                } else {
                    null
                }).apply {
                    queryEvents.clear()
                }
            }
        }
        val activityRule = synchronized(topActivityFlow) { activityRuleFlow.value }
        BypassPerfTrace.matcherStarted(topActivityFlow.value.appId, activityRule.priorityRules.size)
        activityRule.currentRules.forEach { rule ->
            if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID && !isBypassAppEnabled(topActivityFlow.value.appId)) {
                BypassDiagnostics.record(FailureReason.APP_DISABLED, detail = "bypass_app_opt_out")
                return@forEach
            }
            if (rule.status == RuleStatus.Status3 && rule.matchDelayJob.value == null) {
                rule.matchDelayJob.value = scope.launch(actionDispatcher) {
                    delay(rule.matchDelay)
                    rule.matchDelayJob.value = null
                    startQueryJob(byDelayRule = rule)
                }
            }
        }
        var bypassRoot: AccessibilityNodeInfo? = null
        var bypassRootRead = false
        var rootAdObservation: ObservedRootAd? = null
        if (canObserveAds()) {
            bypassRootRead = true
            bypassRoot = getTimeoutActiveWindow()
            if (bypassRoot == null) BypassDetectionSessions.noteRootProbe(activityRule.topActivity.appId,
                activityRule.topActivity.activityId, "ROOT_UNAVAILABLE:timeout_or_missing")
            bypassRoot?.let { root ->
                val pkg = root.packageName?.toString()
                if (pkg != activityRule.topActivity.appId) {
                    pkg?.let { scope.launch(eventDispatcher) { fixAppId(it) } }
                    return
                }
                a11yContext.clearNodeCache()
                rootAdObservation = recordAdObservation(root, activityRule)
            }
        }
        if (activityRule.skipMatch) {
            // 如果当前应用没有规则/暂停匹配, 则不去调用获取事件节点避免阻塞
            BypassDiagnostics.record(FailureReason.NO_RULE_FOR_APP, detail = "resolved_rules_not_runnable")
            val reason = when {
                !isBypassAppEnabled(activityRule.topActivity.appId) -> FailureReason.APP_DISABLED
                activityRule.appRules.any { it.subsItem.id == BYPASS_SPLASH_SUBS_ID } &&
                    activityRule.activityRules.none { it.subsItem.id == BYPASS_SPLASH_SUBS_ID } -> FailureReason.ACTIVITY_MISMATCH
                else -> FailureReason.NO_RULE_FOR_APP
            }
            BypassDetectionSessions.noteMatcherReason(activityRule.topActivity.appId, activityRule.topActivity.activityId, reason)
            return
        }
        var lastNode = if (newEvents == null || newEvents.size <= 1) {
            newEvents?.firstOrNull()?.safeSource
        } else {
            // 获取最后两个事件, 如果最后两个事件的节点不一致, 则丢弃
            // 相等则是同一个节点发出的连续事件, 常见于倒计时界面
            val lastNode = newEvents.last().safeSource
            if (lastNode == null || lastNode == newEvents[0].safeSource) {
                lastNode
            } else {
                null
            }
        }
        var lastNodeUsed = false
        if (!a11yContext.clearOldAppNodeCache()) {
            if (byEvent != null) { // 此为多数情况
                // 新事件到来时, 若缓存清理不及时会导致无法查询到节点
                a11yContext.clearNodeCache(lastNode)
            }
        }
        if (bypassRoot != null) a11yContext.clearNodeCache()
        var queryExecutionToken: Long? = null
        try {
        for (rule in activityRule.priorityRules) { // 规则数量有可能过多导致耗时过长
            if (!effective) return
            if (checkOutDate(activityRule, tempStateEvent)) {
                BypassDetectionSessions.activeId(activityRule.topActivity.appId, activityRule.topActivity.activityId)?.let {
                    BypassDetectionSessions.noteStage(it, "QUERY_INVALIDATED:new_window_event=true")
                }
                break
            }
            if (delayRule != null && delayRule !== rule) continue
            if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID && !isBypassAppEnabled(topActivityFlow.value.appId)) {
                BypassDiagnostics.record(FailureReason.APP_DISABLED, detail = "bypass_app_opt_out")
                BypassDetectionSessions.noteMatcherReason(activityRule.topActivity.appId, activityRule.topActivity.activityId, FailureReason.APP_DISABLED)
                continue
            }
            val initialStatus = rule.status
            if (initialStatus != RuleStatus.StatusOk) {
                if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID &&
                    (initialStatus == RuleStatus.Status6 || delayRule === rule)) {
                    BypassDetectionSessions.activeId(activityRule.topActivity.appId, activityRule.topActivity.activityId)?.let {
                        BypassDetectionSessions.noteStage(it, "RULE_STATUS:rule=${rule.key ?: -1} status=${initialStatus.diagnosticCode}")
                    }
                }
                continue
            }
            if (byForced && !rule.checkForced()) continue
            lastNode?.let { n ->
                val refreshOk = (!lastNodeUsed) || (try {
                    val e = n.refresh()
                    if (e) {
                        n.setGeneratedTime()
                    }
                    e
                } catch (_: Throwable) {
                    false
                })
                lastNodeUsed = true
                if (!refreshOk) {
                    lastNode = null
                }
            }
            // A countdown content event commonly originates from its TextView.
            // Its sibling exit and the ad label are outside that subtree. All
            // ad rules share ONE fresh window read per pass, including after
            // startup polling has ended; ordinary automation keeps event scope.
            val nodeVal = (if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID) {
                if (!bypassRootRead) {
                    bypassRootRead = true
                    bypassRoot = getTimeoutActiveWindow()
                    a11yContext.clearNodeCache()
                }
                bypassRoot
            } else lastNode ?: getTimeoutActiveWindow()) ?: run {
                BypassDiagnostics.record(FailureReason.ACCESSIBILITY_NODE_MISSING, detail = "active_window_unavailable")
                BypassDetectionSessions.noteMatcherReason(activityRule.topActivity.appId, activityRule.topActivity.activityId, FailureReason.ACCESSIBILITY_NODE_MISSING)
                continue
            }
            val rightAppId = nodeVal.packageName?.toString() ?: break
            // Anchor the ad window to an observed top-app change. OEMs
            // sometimes swallow the launch event; a fresh active-window read
            // that reveals a real top-app transition is a launch. A service
            // reconnect (empty baseline) or a re-read of the same app is NOT:
            // it must never re-open the startup window (P0-5).
            if (rightAppId != observedTopApp) {
                val observationType = BypassWindowAnchor.classifyObservation(observedTopApp, rightAppId)
                observedTopApp = rightAppId
                if (observationType == BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE) {
                    BypassAdContextTracker.onAppObserved(rightAppId, System.currentTimeMillis(), observationType)
                }
            }
            val matchApp = rule.matchActivity(rightAppId)
            if (topActivityFlow.value.appId != rightAppId || (!matchApp && rule is AppRule)) {
                scope.launch(eventDispatcher) { fixAppId(rightAppId) }
                return
            }
            if (!matchApp) {
                if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID) {
                    BypassDiagnostics.record(FailureReason.ACTIVITY_MISMATCH, detail = "rule_activity_mismatch")
                    BypassDetectionSessions.noteMatcherReason(rightAppId, activityRule.topActivity.activityId, FailureReason.ACTIVITY_MISMATCH)
                }
                continue
            }
            BypassPerfTrace.selectorQueried()
            val target = queryAdRule(a11yContext, rule, nodeVal) ?: run {
                if (rule.subsItem.id == BYPASS_SPLASH_SUBS_ID) {
                    BypassDiagnostics.record(FailureReason.SELECTOR_NO_MATCH, detail = "gkd_selector_no_match")
                    BypassDetectionSessions.noteMatcherReason(rightAppId, activityRule.topActivity.activityId, FailureReason.SELECTOR_NO_MATCH)
                    if (BypassTeachRules.isPendingVerification(rule.g.appId, rule.g.group.key)) {
                        // A first no-match is never a verification failure:
                        // only the armed verification window decides.
                        BypassTeachRules.noticeNoMatch(rule.g.appId, rule.g.group.key)
                    }
                }
                continue
            }
            BypassPerfTrace.matched(rule.statusText())
            val bypassContext = topActivityFlow.value
            val isBypassRule = rule.subsItem.id == BYPASS_SPLASH_SUBS_ID
            // Wait for fresh-state verification before spending another
            // attempt, including queries queued by countdown/layout events.
            if (isBypassRule && (bypassVerificationJob?.isActive == true || bypassExecution.busy)) break
            if (META.debuggable && isBypassRule) {
                Log.d("A11yRuleEngine", "bypass rule matched: ${rule.g.group.name}/${rule.rule.name} status=${rule.status}")
            }
            var bypassSessionId: String? = null
            var bypassMaxAttempts = 0
            var bypassCandidateType: BypassExitCandidateType? = null
            var bypassExecutionToken: Long? = null
            if (isBypassRule) {
                val mode = BypassStrategyGate.currentStrategyMode()
                val rulePolicy = BypassRulePolicyResolver.resolve(rule)
                // B. Classify the matched control.
                if (!BypassStrategyGate.isSaneCandidate(target)) continue
                val verifiedMiniLayout = BypassMiniProgramLayouts.admitsNode(
                    bypassContext.appId, bypassContext.activityId, rulePolicy,
                    rule.rule.matches.orEmpty() + rule.rule.anyMatches.orEmpty(), target,
                )
                val nativeHeaderClose = BypassAdContextTracker.isMiniProgramAdActivity(bypassContext.appId, bypassContext.activityId) &&
                    BypassExitClassifier.isMiniProgramNavigationClose(target)
                val candidate = if (nativeHeaderClose) null else if (verifiedMiniLayout) BypassExitCandidateType.CURATED_MINI_EXIT else
                    BypassExitClassifier.classifyNode(target) ?: if (
                    rulePolicy.trust == li.songe.gkd.bypass.BypassRuleTrust.BUNDLED_DEDICATED &&
                    !rulePolicy.coordinate && !li.songe.gkd.bypass.isBypassHighRiskApp(bypassContext.appId)
                ) BypassExitClassifier.classifyDedicatedNode(
                    target, rule.rule.matches.orEmpty() + rule.rule.anyMatches.orEmpty(),
                ) else null
                bypassCandidateType = candidate
                // C. THE shared execution gate (P0-1). Every origin — bundled
                //    dedicated, official override, imported, teach — runs this
                //    exact function; high-risk exempt sources only waive
                //    "untrusted source", never window/size/strategy/context.
                //    A null candidate (no exit semantics) is a hard denial for
                //    every origin: nothing is ever faked as SKIP_TEXT.
                //
                //    Ad context is CANDIDATE-SCOPED (P0-1): STRONG only from
                //    window evidence tied to this ad (skip candidate /
                //    curated ad-specific viewId) or an ad label NEAR the
                //    current candidate. A far-away page banner never upgrades
                //    an unrelated small ImageView/X/Close.
                val candidateBounds = runCatching {
                    val r = target.casted.boundsInScreen
                    "${r.left},${r.top},${r.right},${r.bottom}"
                }.getOrNull()
                val adjacentCountdown =
                    candidate in setOf(BypassExitCandidateType.CLOSE_TEXT, BypassExitCandidateType.CLOSE_DESC) &&
                    BypassAdContextTracker.isMiniProgramAdActivity(bypassContext.appId, bypassContext.activityId) &&
                    (BypassExitClassifier.hasAdjacentCloseCountdown(target) ||
                        rootAdObservation?.closeCountdownNodes?.contains(target) == true)
                val sameRootProof = rootAdObservation?.let { observed ->
                    observed.node == target && observed.evidence != null
                } == true
                val contextLevel = if (verifiedMiniLayout || sameRootProof) BypassAdContextLevel.STRONG else BypassAdContextTracker.evaluateCandidateContext(
                    bypassContext.appId, bypassContext.activityId, root = nodeVal,
                    candidateBounds = candidateBounds, candidateType = candidate,
                    candidateViewId = target.viewIdResourceName, candidateText = target.text?.toString(),
                    candidateDescription = target.contentDescription?.toString(),
                    candidateHasAdjacentCountdown = adjacentCountdown,
                )
                val evidence = when {
                    verifiedMiniLayout -> BypassObservedAdEvidence.VERIFIED_MINI_AD_LAYOUT
                    candidate == BypassExitCandidateType.SKIP_TEXT -> BypassObservedAdEvidence.SKIP_CONTROL
                    adjacentCountdown -> BypassObservedAdEvidence.CLOSE_WITH_COUNTDOWN
                    candidate != null && contextLevel == BypassAdContextLevel.STRONG -> BypassObservedAdEvidence.EXPLICIT_AD_WITH_EXIT
                    else -> null
                }
                val observedSessionId = evidence?.let {
                    BypassDetectionSessions.observeAd(bypassContext.appId, bypassContext.activityId, mode, it, rulePolicy.trust)
                } ?: BypassDetectionSessions.activeId(bypassContext.appId, bypassContext.activityId)
                if (observedSessionId != null && candidate != null) {
                    BypassDetectionSessions.noteCandidate(observedSessionId, bypassContext.appId, bypassContext.activityId,
                        candidate, rule.statusText(), target, ruleKey = rule.rule.key, groupKey = rule.g.group.key)
                    BypassDetectionSessions.noteStage(observedSessionId,
                        "GATE:type=${candidate.name} trust=${rulePolicy.trust} mode=$mode context=$contextLevel adjacent=$adjacentCountdown")
                }
                val reject = if (nativeHeaderClose) "NAVIGATION_CONTROL" else if (mode.ordinal < rulePolicy.minimumMode.ordinal) "RULE_LEVEL" else BypassStrategyGate.evaluateExecution(
                    candidate = candidate, packageName = bypassContext.appId, activityName = bypassContext.activityId,
                    nodeWidth = target.casted.boundsInScreen.width(), nodeHeight = target.casted.boundsInScreen.height(),
                    policy = mode.policy, rulePolicy = rulePolicy, contextLevel = contextLevel,
                    inWindow = BypassStrategyGate.inStartupWindow(bypassContext.appId), verifiedMiniLayout = verifiedMiniLayout,
                )
                if (reject != null) {
                    if (META.debuggable) {
                        Log.d("A11yRuleEngine", "bypass candidate rejected: ${candidate?.name} reason=$reject")
                    }
                    BypassDiagnostics.record(
                        FailureReason.GLOBAL_EXCLUDED,
                        packageName = bypassContext.appId,
                        activityName = bypassContext.activityId,
                        detail = "CLOSE_CANDIDATE_REJECTED reason=$reject type=${candidate?.name ?: "?"} trust=${rulePolicy.trust}",
                    )
                    BypassDetectionSessions.candidateRejected(
                        bypassContext.appId,
                        bypassContext.activityId,
                        candidate?.name ?: "?",
                        reject,
                    )
                    continue
                }
                // D. One ad = one session: attach evidence to the window
                // session and keep the strategy recorded at session start.
                val sessionId = BypassDetectionSessions.ensureSession(
                    bypassContext.appId,
                    bypassContext.activityId,
                    mode,
                    rulePolicy.trust,
                ) ?: continue
                BypassDetectionSessions.executionAllowed(sessionId, rule.key, candidate)
                candidate?.let {
                    BypassAdContextTracker.noteCandidate(
                        bypassContext.appId,
                        bypassContext.activityId,
                        it,
                        target.viewIdResourceName,
                        bounds = candidateBounds,
                    )
                    BypassDetectionSessions.noteCandidate(
                        sessionId,
                        bypassContext.appId,
                        bypassContext.activityId,
                        it,
                        rule.statusText(),
                        target,
                        ruleKey = rule.rule.key,
                        groupKey = rule.g.group.key,
                        admitted = true,
                    )
                }
                BypassDetectionSessions.strategyApplied(bypassContext.appId, bypassContext.activityId, mode)
                // E. Per-session attempt cap:
                //    minOf(mode.policy.maxExitAttempts, rulePolicy.maxAttempts, 3).
                //    The reservation itself happens immediately before
                //    performAction below (Runtime V2): waiting for an action
                //    delay, selector misses and scheduled re-queries never
                //    consume an attempt.
                bypassMaxAttempts = BypassRuntimeFlow.effectiveMaxAttempts(mode, rulePolicy)
                bypassSessionId = sessionId
            }
            // ---- Runtime V2 sequencing (P0-1). Order is fixed:
            // match -> gates (above) -> action delay (WAIT only, no budget)
            // -> status/outdate -> reserveAttempt -> performAction -> verifier.
            if (isBypassRule) {
                val pendingActionDelay = rule.checkDelay() && rule.actionDelayJob.value == null
                val runtimeStatus = rule.status
                val runtimeOutOfDate = checkOutDate(activityRule, tempStateEvent)
                val decision = BypassRuntimeFlow.decide(
                    ruleHasPendingActionDelay = pendingActionDelay,
                    ruleStatusOk = runtimeStatus == RuleStatus.StatusOk,
                    outOfDate = runtimeOutOfDate,
                    budgetUsed = BypassActionBudget.attemptsUsed(bypassSessionId!!),
                    maxAttempts = bypassMaxAttempts,
                )
                when (decision) {
                    BypassRuntimeFlow.Decision.WAIT_FOR_DELAY -> {
                        BypassDiagnostics.record(FailureReason.WAITING_FOR_DELAY, detail = "rule_action_delay")
                        BypassDetectionSessions.waitingForDelay(bypassContext.appId, bypassContext.activityId)
                        BypassDetectionSessions.noteStage(bypassSessionId,
                            "ACTION_DELAY_SCHEDULED:rule=${rule.key ?: -1} delay_ms=${rule.actionDelay}")
                        rule.actionDelayJob.value = scope.launch(actionDispatcher) {
                            delay(rule.actionDelay)
                            rule.actionDelayJob.value = null
                            startQueryJob(byDelayRule = rule)
                        }
                        continue
                    }
                    BypassRuntimeFlow.Decision.NOT_READY -> {
                        BypassDetectionSessions.noteStage(bypassSessionId,
                            "RUNTIME_NOT_READY:rule=${rule.key ?: -1} status=${runtimeStatus.diagnosticCode} outdated=$runtimeOutOfDate")
                        break
                    }
                    BypassRuntimeFlow.Decision.BUDGET_EXHAUSTED -> {
                        if (META.debuggable) {
                            Log.d("A11yRuleEngine", "bypass budget exhausted for session $bypassSessionId")
                        }
                        BypassDiagnostics.record(
                            FailureReason.ACTION_NO_EFFECT,
                            packageName = bypassContext.appId,
                            activityName = bypassContext.activityId,
                            detail = "BUDGET_EXHAUSTED attempts=$bypassMaxAttempts",
                        )
                        BypassDetectionSessions.confirmedFailure(bypassSessionId, FailureReason.ACTION_NO_EFFECT)
                        continue
                    }
                    BypassRuntimeFlow.Decision.PROCEED -> {
                        // Reserve exactly once, immediately before the action.
                        bypassExecutionToken = bypassExecution.tryAcquire() ?: break
                        queryExecutionToken = bypassExecutionToken
                        if (!BypassActionBudget.reserveAttempt(bypassSessionId, bypassMaxAttempts)) {
                            bypassExecution.release(bypassExecutionToken)
                            BypassDetectionSessions.confirmedFailure(bypassSessionId, FailureReason.ACTION_NO_EFFECT)
                            continue
                        }
                    }
                }
            } else {
                if (rule.checkDelay() && rule.actionDelayJob.value == null) {
                    rule.actionDelayJob.value = scope.launch(actionDispatcher) {
                        delay(rule.actionDelay)
                        rule.actionDelayJob.value = null
                        startQueryJob(byDelayRule = rule)
                    }
                    continue
                }
                if (rule.status != RuleStatus.StatusOk) break
                if (checkOutDate(activityRule, tempStateEvent)) break
            }
            BypassPerfTrace.actionStarted(rule.statusText())
            if (isBypassRule && !target.isClickable) {
                BypassDiagnostics.record(FailureReason.TARGET_FOUND_NOT_CLICKABLE, detail = "matched_target_not_clickable")
                BypassDetectionSessions.targetNotClickable(bypassContext.appId, bypassContext.activityId)
            }
            // P0-2 (multi-stage): capture the IMMUTABLE evidence of THIS
            // action immediately before performAction. The async verifier
            // receives this snapshot and never re-guesses from the mutable
            // active session — a candidate B appearing later cannot pollute
            // the verification of action A.
            val actionEvidence = bypassSessionId?.let { BypassDetectionSessions.captureActionEvidence(it) }
                ?.copy(ruleIndex = rule.index, groupAppId = rule.g.appId, scopedIdentity = true)
            if (bypassSessionId != null && actionEvidence != null) BypassDetectionSessions.bindActionEvidence(bypassSessionId, actionEvidence)
            val centerAction = isBypassRule && BypassAdActionPolicy.useCenter(
                candidate = bypassCandidateType,
                miniProgram = BypassAdContextTracker.isMiniProgramAdActivity(bypassContext.appId, bypassContext.activityId),
                attempt = bypassSessionId?.let { BypassActionBudget.attemptsUsed(it) } ?: 1,
                action = rule.rule.action ?: if (rule.rule.swipeArg != null) "swipe" else null,
                hasCustomPosition = rule.rule.position != null,
                width = target.casted.boundsInScreen.width(), height = target.casted.boundsInScreen.height(),
            )
            val actionResult = try { if (centerAction) {
                // Native web/SDK nodes can acknowledge ACTION_CLICK without
                // forwarding it to the visible close control. This reviewed
                // small target is clicked at its actual bounded center.
                ActionPerformer.ClickCenter.perform(target, rule.rule)
            } else rule.performAction(target) } catch (error: Throwable) {
                bypassExecutionToken?.let(bypassExecution::release)
                throw error
            }
            BypassPerfTrace.actionFinished(rule.statusText())
            BypassPerfTrace.actionFinishedT3(rule.statusText())
            bypassSessionId?.let {
                BypassDetectionSessions.actionAttempted(it, actionResult.action)
                BypassDetectionSessions.noteActionResult(it, actionResult.result, actionResult.action)
            }
            if (actionResult.result) {
                BypassPerfTrace.actionSucceeded()
                val topActivity = topActivityFlow.value
                rule.trigger()
                if (isBypassRule && bypassSessionId != null) {
                    val sid = bypassSessionId
                    // OutcomeVerifier is the only retry scheduler for bypass:
                    // Action -> fresh-state verify -> ad still there + budget ->
                    // fresh query for the next exit candidate.
                    bypassVerificationJob = scope.launch(actionDispatcher) {
                        val actionStart = System.currentTimeMillis()
                        BypassDetectionSessions.noteStage(sid, "VERIFY_START")
                        val outcome = BypassOutcomeVerifier.verify(
                            packageName = bypassContext.appId,
                            // P0-2: the verifier re-checks THE SAME ad region /
                            // exit THIS action acted on, from the immutable
                            // per-action evidence — never unrelated banners,
                            // never a later candidate B.
                            sessionEvidence = actionEvidence,
                            freshWindowProvider = { getTimeoutActiveWindow() },
                            // P0-5: the verifier re-reads the top app AFTER
                            // its own delay; the event-driven flow is only the
                            // fallback when the window read fails.
                            topFallbackProvider = { topActivityFlow.value.appId },
                            freshSameAdProvider = { root, evidence -> hasSameAdCandidate(root, evidence) },
                            freshProximityAdLabelProvider = { root, evidence ->
                                hasProximityAdLabel(root, evidence)
                            },
                        )
                        val latency = System.currentTimeMillis() - actionStart
                        BypassPerfTrace.outcomeConfirmed(rule.statusText(), outcome.name)
                        BypassDetectionSessions.outcomeConfirmed(sid, outcome, latency)
                        when (outcome) {
                            BypassOutcome.SUCCESS_CONFIRMED -> {
                                // A second mini-program ad can appear without an
                                // Activity change. Re-arm only after verified exit.
                                activityRule.priorityRules.filter {
                                    it.subsItem.id == BYPASS_SPLASH_SUBS_ID &&
                                        it.g.appId == rule.g.appId && it.g.group.key == rule.g.group.key
                                }.forEach { it.rearmAfterAdExit() }
                                BypassTeachRules.markVerification(rule.g.appId, rule.g.group.key, success = true)
                                if (actionResult.action != ActionPerformer.None.action) {
                                    showActionToast(rule)
                                }
                            }
                            BypassOutcome.MISCLICK_SUSPECTED -> {
                                BypassDiagnostics.record(
                                    FailureReason.MISCLICK_SUSPECTED,
                                    packageName = bypassContext.appId,
                                    activityName = bypassContext.activityId,
                                    detail = "external_landing",
                                )
                            }
                            BypassOutcome.ACTION_NO_EFFECT,
                            -> {
                                if (BypassActionBudget.attemptsUsed(sid) < bypassMaxAttempts) {
                                    rule.rearmAfterAdExit()
                                    BypassDiagnostics.record(FailureReason.ACTION_NO_EFFECT, detail = "outcome=$outcome requery")
                                    BypassDetectionSessions.noteStage(sid, "RETRY:FRESH_QUERY")
                                    scheduleBypassRetry(sid, bypassContext)
                                } else {
                                    BypassDetectionSessions.confirmedFailure(sid, FailureReason.ACTION_NO_EFFECT)
                                }
                            }
                            BypassOutcome.UNRESOLVED -> {
                                // Missing fresh evidence is not proof that
                                // the ad remained. Preserve an unconfirmed
                                // outcome instead of manufacturing a failure.
                                if (BypassActionBudget.attemptsUsed(sid) < bypassMaxAttempts) {
                                    rule.rearmAfterAdExit()
                                    BypassDetectionSessions.noteStage(sid, "RETRY:FRESH_QUERY")
                                    scheduleBypassRetry(sid, bypassContext)
                                } else {
                                    BypassDetectionSessions.unresolved(sid, "verification_unavailable")
                                }
                            }
                        }
                    }
                    bypassExecutionToken?.let { token ->
                        bypassVerificationJob?.invokeOnCompletion { bypassExecution.release(token) }
                        queryExecutionToken = null // the verifier now owns release, including cancellation
                    }
                } else {
                    scope.launch(actionDispatcher) {
                        delay(300)
                        startQueryJob()
                    }
                    if (actionResult.action != ActionPerformer.None.action) {
                        showActionToast(rule)
                    }
                }
                addActionLog(rule, topActivity, target, actionResult)
            } else if (isBypassRule) {
                bypassExecutionToken?.let(bypassExecution::release)
                BypassDiagnostics.record(FailureReason.ACTION_FAILED, detail = "gkd_action_returned_false")
                bypassSessionId?.let { sid ->
                    if (BypassActionBudget.attemptsUsed(sid) < bypassMaxAttempts) {
                        scheduleBypassRetry(sid, bypassContext)
                    } else {
                        BypassDetectionSessions.confirmedFailure(sid, FailureReason.ACTION_FAILED)
                    }
                }
            }
            // The UI may have changed after the action. Do not spend another
            // attempt on a cached target from this same pass.
            if (isBypassRule) break
        }
        } finally {
            // Also covers errors while reading candidate geometry or recording
            // the action, before ownership could be passed to the verifier.
            queryExecutionToken?.let(bypassExecution::release)
        }
    }

    private data class ObservedRootAd(val evidence: BypassObservedAdEvidence?, val node: AccessibilityNodeInfo?,
                                     val closeCountdownNodes: List<AccessibilityNodeInfo> = emptyList())

    private fun queryAdRule(context: A11yContext, rule: ResolvedRule, root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val top = topActivityFlow.value
        if (rule.subsItem.id != BYPASS_SPLASH_SUBS_ID ||
            !BypassAdContextTracker.isMiniProgramAdActivity(top.appId, top.activityId))
            return context.queryRule(rule, root)
        val rect = root.casted.boundsInScreen
        val window = Bounds(rect.left, rect.top, rect.right, rect.bottom)
        return context.queryRule(rule, root) { target ->
            val bounds = target.casted.boundsInScreen
            BypassMiniProgramTargets.allowsTarget(BypassExitClassifier.classifyNode(target),
                Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom), window,
                BypassExitClassifier.isMiniProgramNavigationClose(target))
        }
    }

    private fun recordAdObservation(root: AccessibilityNodeInfo, activityRule: ActivityRule): ObservedRootAd {
        val top = activityRule.topActivity
        val observation = BypassRootAdObserver.inspect(root, top.appId, top.activityId)
        BypassDetectionSessions.noteRootProbe(top.appId, top.activityId,
            "visited=${observation.visited} complete=${observation.complete} children=${root.childCount} adLabel=${observation.anyAdLabel} evidence=${observation.evidence?.name ?: "NONE"} rules=${activityRule.currentRules.count { it.subsItem.id == BYPASS_SPLASH_SUBS_ID }}")
        var evidence = observation.evidence?.let { BypassObservedAdEvidence.valueOf(it.name) }
        var node = observation.node
        var candidate = node?.let { BypassExitClassifier.classifyNode(it) }
        var trust: BypassRuleTrust? = null
        // The native SDK exit has no text/id. Probe the reviewed full template
        // even when its action status is exhausted; otherwise a still-visible
        // ad would be treated as absent and receive a new budget/session.
        if (evidence == null && top.appId == "com.eg.android.AlipayGphone") {
            for (rule in activityRule.currentRules) {
                if (rule.subsItem.id != BYPASS_SPLASH_SUBS_ID) continue
                val selectors = rule.rule.matches.orEmpty() + rule.rule.anyMatches.orEmpty()
                if (selectors.size != 1 || BypassMiniProgramLayouts.selectorFingerprint(selectors[0]) != BypassMiniProgramLayouts.ALIPAY_NATIVE_EXIT) continue
                val policy = BypassRulePolicyResolver.resolve(rule)
                val found = queryAdRule(a11yContext, rule, root) ?: continue
                if (!BypassMiniProgramLayouts.admitsNode(top.appId, top.activityId, policy, selectors, found)) continue
                evidence = BypassObservedAdEvidence.VERIFIED_MINI_AD_LAYOUT
                node = found
                candidate = BypassExitCandidateType.CURATED_MINI_EXIT
                trust = policy.trust
                break
            }
        }
        if (evidence != null) {
            val id = BypassDetectionSessions.observeAd(top.appId, top.activityId,
                BypassStrategyGate.currentStrategyMode(), evidence, trust) ?: return ObservedRootAd(evidence, node, observation.closeCountdownNodes)
            if (node != null && candidate != null) BypassDetectionSessions.noteObservedCandidate(id, top.appId, top.activityId, candidate, node)
            BypassDetectionSessions.noteStage(id, "ROOT_SCAN:visited=${observation.visited} complete=${observation.complete} source=FRESH_WINDOW")
            BypassDetectionSessions.noteStage(id, "CONFIG:master=${storeFlow.value.enableMatch} generic=${storeFlow.value.enableGenericFallback} app=${isBypassAppEnabled(top.appId)} rules=${activityRule.currentRules.count { it.subsItem.id == BYPASS_SPLASH_SUBS_ID }}")
        } else if (observation.complete) {
            val previous = BypassDetectionSessions.windowAdEvidence(top.appId, top.activityId)
            if (previous?.candidateType == BypassExitCandidateType.LOCAL_VISUAL_EXIT) return ObservedRootAd(evidence, node)
            val absent = if (previous != null && (previous.ruleKey != null || previous.ruleIndex != null)) {
                !hasSameAdCandidate(root, previous) && !hasProximityAdLabel(root, previous)
            } else !observation.anyAdLabel
            if (META.debuggable) Log.d("BypassBlackbox", "clear probe visited=${observation.visited} complete=${observation.complete} label=${observation.anyAdLabel} scoped=${previous != null} absent=$absent")
            if (!absent) return ObservedRootAd(evidence, node)
            BypassDetectionSessions.observeClear(top.appId, top.activityId, confirmAbsence = {
                if (!isInteractive || !BypassBlackbox.sameWindow(top.appId, top.activityId,
                        topActivityFlow.value.appId, topActivityFlow.value.activityId)) false
                else getTimeoutActiveWindow()?.let { fresh ->
                    if (fresh.packageName?.toString() != top.appId || fresh.childCount == 0) false
                    else {
                        val stable = BypassRootAdObserver.inspect(fresh, top.appId, top.activityId)
                        stable.complete && stable.evidence == null &&
                            if (previous != null && (previous.ruleKey != null || previous.ruleIndex != null))
                                !hasSameAdCandidate(fresh, previous) && !hasProximityAdLabel(fresh, previous)
                            else !stable.anyAdLabel
                    }
                } ?: false
            }) {
                if (BypassBlackbox.sameWindow(top.appId, top.activityId, topActivityFlow.value.appId, topActivityFlow.value.activityId)) {
                    activityRuleFlow.value.currentRules.filter { it.subsItem.id == BYPASS_SPLASH_SUBS_ID }
                        .forEach { it.rearmAfterAdExit() }
                    BypassAdContextTracker.clearWindowEvidence(top.appId, top.activityId)
                }
            }
        }
        return ObservedRootAd(evidence, node, observation.closeCountdownNodes)
    }

    private fun checkOutDate(
        activityRule: ActivityRule,
        stateEvent: A11yEvent?
    ): Boolean {
        if (stateEvent !== latestStateEvent) return true
        synchronized(topActivityFlow) {
            if (activityRule !== activityRuleFlow.value) return true
        }
        return false
    }

    /**
     * Whether THE SAME ad (same rule family, same region) still matches on a
     * fresh root (P0-2). Only the session's acted rule is queried first;
     * unrelated banners never count as "the ad is still up". Multi-stage
     * (P0-2): if the exact rule no longer matches, a SEMANTIC sibling exit of
     * the SAME group (skip / close text / desc / curated viewId / X glyph) in
     * the SAME ad region still means "the ad is up" (Skip gone -> Close
     * appeared), so the verifier reports ACTION_NO_EFFECT instead of a false
     * early SUCCESS. STRUCTURAL_CLOSE / COORDINATE_FALLBACK are never
     * siblings: a normal page ImageView must not fabricate a failure.
     * Reuses the GKD selector machinery (no second scanner).
     */
    private fun hasSameAdCandidate(root: AccessibilityNodeInfo, evidence: BypassSessionAdEvidence): Boolean {
        // The window changed after the action; drop the stale node cache so
        // the fresh queries see the new window, not the old nodes.
        val freshContext = A11yContext(this, interruptable = false).apply { rootCache.value = root }
        if (evidence.groupKey == null || (evidence.ruleKey == null && evidence.ruleIndex == null)) return false
        val activityRule = synchronized(topActivityFlow) { activityRuleFlow.value }
        val acted = BypassOutcomeVerifier.parseBounds(evidence.bounds)
        // 1) The exact acted rule, in the same region.
        for (rule in activityRule.priorityRules) {
            if (rule.subsItem.id != BYPASS_SPLASH_SUBS_ID) continue
            if (!evidence.belongsToGroup(rule.g.appId, rule.g.group.key) ||
                !evidence.isActedRule(rule.index, rule.rule.key)) continue
            val matched = queryAdRule(freshContext, rule, root) ?: break
            if (BypassAdContextTracker.isMiniProgramAdActivity(activityRule.topActivity.appId, activityRule.topActivity.activityId) &&
                BypassExitClassifier.isMiniProgramNavigationClose(matched)) continue
            val nodeBounds = matched.casted.boundsInScreen
            val fresh = Bounds(nodeBounds.left, nodeBounds.top, nodeBounds.right, nodeBounds.bottom)
            if (acted == null) return true
            return BypassOutcomeVerifier.sameAdRegion(acted, fresh)
        }
        // 2) The exact rule is gone: a semantic sibling exit of the SAME
        //    group, still in the same ad region, means the ad is still up.
        for (rule in activityRule.priorityRules) {
            if (rule.subsItem.id != BYPASS_SPLASH_SUBS_ID) continue
            if (!evidence.belongsToGroup(rule.g.appId, rule.g.group.key) ||
                evidence.isActedRule(rule.index, rule.rule.key)) continue
            val matched = queryAdRule(freshContext, rule, root) ?: continue
            val candidateType = runCatching { BypassExitClassifier.classifyNode(matched) }.getOrNull()
            if (!BypassOutcomeVerifier.isSemanticSibling(candidateType)) continue
            if (BypassAdContextTracker.isMiniProgramAdActivity(activityRule.topActivity.appId, activityRule.topActivity.activityId) &&
                BypassExitClassifier.isMiniProgramNavigationClose(matched)) continue
            val nodeBounds = matched.casted.boundsInScreen
            val fresh = Bounds(nodeBounds.left, nodeBounds.top, nodeBounds.right, nodeBounds.bottom)
            if (acted == null || BypassOutcomeVerifier.sameAdRegion(acted, fresh)) return true
        }
        // The acted rule (and any semantic sibling of the same group) no
        // longer matches: cannot claim the same ad is still up.
        return false
    }

    /**
     * Whether an ad label is still visible near the acted candidate (P0-2).
     */
    private fun hasProximityAdLabel(root: AccessibilityNodeInfo, evidence: BypassSessionAdEvidence): Boolean {
        val bounds = evidence.bounds ?: return false
        return BypassAdContextTracker.scanFreshAdLabelNear(root, bounds)
    }

    companion object {
        val service: A11yCommonImpl?
            get() = uiAutomationFlow.value?.takeIf {
                it.mode.value == latestServiceMode.value
            } ?: A11yService.instance
        val instance: A11yRuleEngine? get() = service?.ruleEngine

        fun compatWindows(): List<AccessibilityWindowInfo> {
            return try {
                service?.windowInfos
            } catch (_: Throwable) {
                null
            } ?: emptyList()
        }

        fun onScreenForcedActive() {
            instance?.onScreenForcedActive()
        }

        fun performActionBack(): Boolean {
            val r1 = shizukuContextFlow.value.inputManager?.key(KeyEvent.KEYCODE_BACK)
            if (r1 != null) return true
            return A11yService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
        }

        suspend fun screenshot(): Bitmap? = service?.screenshot()

        suspend fun execAction(gkdAction: GkdAction): ActionResult {
            val selector = Selector.parseOrNull(gkdAction.selector) ?: throw RpcError("非法选择器")
            runCatching { selector.checkType(typeInfo) }.exceptionOrNull()?.let {
                throw RpcError("选择器类型错误:${it.message}")
            }
            val s = instance ?: throw RpcError("服务未连接")
            val a = s.safeActiveWindow ?: throw RpcError("界面没有节点信息")
            val targetNode = A11yContext(s, interruptable = false).querySelfOrSelector(
                a, selector, MatchOption(fastQuery = gkdAction.fastQuery)
            ) ?: throw RpcError("没有查询到节点")
            return withContext(Dispatchers.IO) {
                ActionPerformer
                    .getAction(gkdAction.action ?: ActionPerformer.None.action)
                    .perform(targetNode, gkdAction)
            }
        }

    }
}
