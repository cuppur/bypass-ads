package li.songe.gkd.bypass

import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import li.songe.gkd.appScope
import li.songe.gkd.data.BypassDetectionSession
import li.songe.gkd.db.DbSet
import li.songe.gkd.util.json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A deliberately small, non-content snapshot of a skip/close control. */
@Serializable
data class BypassCandidateSnapshot(
    val text: String? = null,
    val description: String? = null,
    val viewId: String? = null,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val parentClickable: Boolean,
    val packageName: String,
    val activityName: String?,
)

/**
 * Session-scoped ad evidence (P0-2): exactly the candidate the session acted
 * on — its classified type, the structured rule identity, and its screen
 * bounds. The OutcomeVerifier re-checks THIS ad region/exit afterwards, never
 * an unrelated window-wide banner.
 */
data class BypassSessionAdEvidence(
    val candidateType: BypassExitCandidateType?,
    val ruleKey: Int?,
    val groupKey: Int?,
    /** "left,top,right,bottom" of the acted candidate. */
    val bounds: String?,
    /** Runtime identity also covers source rules without an explicit key. */
    val ruleIndex: Int? = null,
    val groupAppId: String? = null,
    val scopedIdentity: Boolean = false,
) {
    fun belongsToGroup(appId: String?, key: Int): Boolean =
        groupKey == key && (!scopedIdentity || groupAppId == appId)

    fun isActedRule(index: Int, key: Int?): Boolean =
        if (ruleIndex != null) ruleIndex == index else ruleKey != null && ruleKey == key
}

/**
 * One ad session is one ad. Matcher evidence is accumulated into the active
 * window session; multiple actions (skip -> X -> close) stay in ONE session
 * and produce exactly one [BypassSessionResult].
 */
object BypassDetectionSessions {
    private const val SESSION_HARD_TIMEOUT_MS = 15_000L
    private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_TIMELINE_ITEMS = 24
    private const val MAX_RULES = 8
    private const val MAX_ACTIONS = 8
    private const val MAX_SNAPSHOTS = 8
    private const val AD_ABSENT_STABLE_MS = 600L

    private data class ActiveSession(
        val record: BypassDetectionSession,
        val lastTouched: Long,
    )

    private data class WindowProbe(val activityName: String?, val time: Long, val stage: String)
    private val recentWindowProbes = ConcurrentHashMap<String, WindowProbe>()
    private val recentVisualProbes = ConcurrentHashMap<String, WindowProbe>()

    fun noteVisualProbe(packageName: String, activityName: String?, stage: String) {
        recentVisualProbes[packageName] = WindowProbe(activityName, System.currentTimeMillis(),
            stage.replace(Regex("[^A-Za-z0-9_ .:/=+-]"), "_").take(160))
        if (recentVisualProbes.size > 8) recentVisualProbes.minByOrNull { it.value.time }?.key?.let(recentVisualProbes::remove)
    }

    /** Metadata for a user report even when no automatic ad proof was visible. */
    fun noteRootProbe(packageName: String, activityName: String?, stage: String) {
        recentWindowProbes[packageName] = WindowProbe(activityName, System.currentTimeMillis(),
            stage.replace(Regex("[^A-Za-z0-9_ .:/=+-]"), "_").take(160))
        if (recentWindowProbes.size > 8) {
            recentWindowProbes.minByOrNull { it.value.time }?.key?.let(recentWindowProbes::remove)
        }
    }

    private val lock = Any()
    private data class PersistRequest(val session: BypassDetectionSession, val cleanup: Boolean)
    private val persistence = Channel<PersistRequest>(Channel.UNLIMITED)
    private val scheduledFinalizers = ConcurrentHashMap<String, Job>()
    private var clearJob: Job? = null
    private var suppressedWindow: Pair<String, String?>? = null
    private var suppressedEvidence: BypassSessionAdEvidence? = null
    private val actionEvidence = ConcurrentHashMap<String, BypassSessionAdEvidence>()

    fun bindActionEvidence(sessionId: String, evidence: BypassSessionAdEvidence) {
        actionEvidence[sessionId] = evidence
    }

    fun windowAdEvidence(packageName: String, activityName: String?): BypassSessionAdEvidence? = synchronized(lock) {
        active?.record?.takeIf { BypassBlackbox.sameWindow(it.packageName, it.activityName, packageName, activityName) }?.let {
            return actionEvidence[it.sessionId] ?: evidenceOf(it)?.let { evidence ->
                evidence.copy(bounds = evidence.bounds ?: decodeSnapshots(it.candidateSnapshots).lastOrNull()?.bounds)
            }
        }
        if (suppressedWindow?.let { BypassBlackbox.sameWindow(it.first, it.second, packageName, activityName) } == true)
            suppressedEvidence else null
    }
    private var active: ActiveSession? = null

    init {
        // One consumer preserves submission order. Launching an IO coroutine
        // per update can otherwise let an older snapshot replace a final one.
        appScope.launch(Dispatchers.IO) {
            for (request in persistence) {
                DbSet.bypassDetectionSessionDao.upsert(request.session)
                if (request.cleanup) {
                    DbSet.bypassDetectionSessionDao.deleteBefore(System.currentTimeMillis() - RETENTION_MS)
                    DbSet.bypassDetectionSessionDao.trimToLatest(2_000)
                    runCatching { BypassDiagnostics.writeFinalSessionTrace(request.session) }.onFailure {
                        BypassDiagnostics.record(FailureReason.UNKNOWN, request.session.packageName,
                            request.session.activityName, "BLACKBOX_TRACE_WRITE_FAILED")
                    }
                }
            }
        }
    }

    /** Open history only after a bounded root scan found actual ad evidence. */
    fun observeAd(
        packageName: String,
        activityName: String?,
        mode: BypassAdStrategyMode,
        evidence: BypassObservedAdEvidence,
        ruleOrigin: BypassRuleTrust? = null,
    ): String? {
        synchronized(lock) {
            clearJob?.cancel()
            clearJob = null
            if (suppressedWindow?.let { BypassBlackbox.sameWindow(it.first, it.second, packageName, activityName) } == true) return null
        }
        val record = update(packageName, activityName, "AD_EVIDENCE:${evidence.name}", create = true) {
            it.copy(strategyMode = mode.name, ruleOrigin = it.ruleOrigin ?: ruleOrigin?.name)
        } ?: return null
        scheduleObservationTimeout(record)
        return record.sessionId
    }

    /** A bounded candidate snapshot; this is an observation, not a rule hit. */
    fun noteObservedCandidate(
        sessionId: String,
        packageName: String,
        activityName: String?,
        candidateType: BypassExitCandidateType,
        target: AccessibilityNodeInfo,
    ) {
        val snapshot = BypassCandidateSanitizer.snapshot(target, packageName, activityName, structural = true)
        updateById(sessionId, "CANDIDATE_OBSERVED:${candidateType.name}") { record ->
            record.copy(
                candidateSeen = true,
                candidateType = record.candidateType ?: candidateType.name,
                candidateSnapshots = snapshot?.let {
                    encodeSnapshots(decodeSnapshots(record.candidateSnapshots) + it)
                } ?: record.candidateSnapshots,
            )
        }
    }

    /** Call only with matcher enum/flags, never screen text. */
    fun noteStage(sessionId: String, stage: String) {
        updateById(sessionId, stage)
    }

    fun noteMatcherReason(
        packageName: String,
        activityName: String?,
        reason: FailureReason,
        ruleLabel: String? = null,
    ) {
        update(packageName, activityName, "MATCHER:${reason.name}", create = false) {
            BypassBlackbox.recordReason(it, reason).copy(
                // A selector miss is never entered in matched_rules.
                matchedRules = it.matchedRules,
            )
        }
    }

    /** Absence must remain stable; a transient empty WebView tree proves nothing. */
    fun observeClear(packageName: String, activityName: String?, confirmAbsence: suspend () -> Boolean,
                     onStableAbsence: (() -> Unit)? = null) {
        synchronized(lock) {
            if (active?.record?.let { BypassBlackbox.sameWindow(it.packageName, it.activityName, packageName, activityName) } != true &&
                suppressedWindow?.let { BypassBlackbox.sameWindow(it.first, it.second, packageName, activityName) } != true) return
            if (clearJob?.isActive == true) return
            active?.record?.let { updateById(it.sessionId, "AD_ABSENT_PENDING") }
            clearJob = appScope.launch {
                delay(AD_ABSENT_STABLE_MS)
                // An empty animation frame must not release the old budget.
                // Events may be slower than this delay; read the window again.
                val confirmed = runCatching { confirmAbsence() }.getOrDefault(false)
                synchronized(lock) {
                    if (!confirmed) {
                        clearJob = null
                        return@synchronized
                    }
                    active?.takeIf { BypassBlackbox.sameWindow(it.record.packageName, it.record.activityName, packageName, activityName) }
                        ?.let { current ->
                            // An action in flight still belongs to the verifier.
                            if (current.record.actionAttempts == 0) {
                                closeUnresolved(current, "AD_DISAPPEARED_WITHOUT_ACTION")
                            }
                        }
                    if (suppressedWindow?.let { BypassBlackbox.sameWindow(it.first, it.second, packageName, activityName) } == true) {
                        suppressedWindow = null
                        suppressedEvidence = null
                    }
                    clearJob = null
                    if (active == null) onStableAbsence?.invoke()
                }
            }
        }
    }

    /** A real foreground transition re-arms the next possible advertisement. */
    fun onWindowChanged(packageName: String, activityName: String?) {
        synchronized(lock) {
            suppressedWindow?.let { window ->
                suppressedWindow = if (BypassBlackbox.sameWindow(window.first, window.second, packageName, activityName))
                    packageName to (activityName ?: window.second) else null
                if (suppressedWindow == null) suppressedEvidence = null
            }
            val current = active
            if (current != null && BypassBlackbox.sameWindow(current.record.packageName, current.record.activityName, packageName, activityName)) {
                if (current.record.activityName == null && activityName != null) {
                    val refined = current.record.copy(activityName = activityName,
                        diagnosticTimeline = BypassBlackbox.appendEvent(current.record, "ACTIVITY_IDENTIFIED", System.currentTimeMillis()))
                    active = ActiveSession(refined, current.lastTouched)
                    persist(refined)
                }
            } else if (current != null) {
                if (current.record.actionAttempts == 0) closeUnresolved(current, "WINDOW_ENDED")
                else appScope.launch {
                    delay(2_000L)
                    synchronized(lock) {
                        active?.takeIf { it.record.sessionId == current.record.sessionId }
                            ?.let { closeUnresolved(it, "WINDOW_ENDED") }
                    }
                }
            }
            clearJob?.cancel()
            clearJob = null
        }
    }

    fun noteActionResult(sessionId: String, accepted: Boolean, action: String) {
        updateById(sessionId, "ACTION_RESULT:${if (accepted) "ACCEPTED" else "REJECTED"}:${action.take(48)}") {
            if (accepted) it else BypassBlackbox.recordReason(it, FailureReason.ACTION_FAILED)
        }
    }

    fun activeId(packageName: String, activityName: String?): String? = synchronized(lock) {
        active?.record?.takeIf { BypassBlackbox.sameWindow(it.packageName, it.activityName, packageName, activityName) }?.sessionId
    }

    fun isActive(sessionId: String): Boolean = synchronized(lock) {
        active?.let {
            it.record.sessionId == sessionId &&
                System.currentTimeMillis() - it.record.startTime <= SESSION_HARD_TIMEOUT_MS
        } ?: false
    }

    /**
     * Get (or create) the active session for the current window. The session
     * carries the strategy mode at session start and the rule trust origin.
     * Returns null only when a session cannot be established (no window).
     */
    fun ensureSession(
        packageName: String,
        activityName: String?,
        mode: BypassAdStrategyMode,
        ruleOrigin: BypassRuleTrust,
    ): String? {
        val session = update(packageName, activityName, "SESSION_OPEN", create = true) { record ->
            if (record.result == BypassSessionResult.OPEN.name) {
                record.copy(
                    strategyMode = record.strategyMode.takeIf { it != BypassDetectionSession.BypassStrategyModeDefault } ?: mode.name,
                    ruleOrigin = record.ruleOrigin ?: ruleOrigin.name,
                )
            } else {
                record
            }
        }
        return session?.sessionId
    }

    fun noteCandidate(
        sessionId: String,
        packageName: String,
        activityName: String?,
        candidateType: BypassExitCandidateType,
        ruleLabel: String,
        target: AccessibilityNodeInfo,
        ruleKey: Int? = null,
        groupKey: Int? = null,
        admitted: Boolean = false,
    ) {
        val snapshot = BypassCandidateSanitizer.snapshot(target, packageName, activityName, structural = true)
        updateById(sessionId, "TARGET_FOUND:${candidateType.name}") { record ->
            val keepAdmitted = !admitted && BypassBlackbox.hasAllowedCandidate(record)
            record.copy(
                candidateSeen = true,
                candidateType = if (keepAdmitted) record.candidateType else candidateType.name,
                matchedRules = append(record.matchedRules, ruleLabel, MAX_RULES),
                candidateSnapshots = snapshot?.let {
                    encodeSnapshots(decodeSnapshots(record.candidateSnapshots) + it)
                } ?: record.candidateSnapshots,
                // Session-scoped verifier evidence (P0-2): acted* holds the
                // MOST RECENT action's rule identity and candidate bounds, so
                // a multi-stage ad (Skip -> Close) verifies against the last
                // exit it acted on, never sticky on the first candidate.
                actedRuleKey = if (keepAdmitted) record.actedRuleKey else ruleKey,
                actedGroupKey = if (keepAdmitted) record.actedGroupKey else groupKey,
                actedCandidateBounds = if (keepAdmitted) record.actedCandidateBounds else snapshot?.bounds ?: record.actedCandidateBounds,
            )
        }
    }

    fun executionAllowed(sessionId: String, ruleKey: Int?, candidate: BypassExitCandidateType?) {
        updateById(sessionId, "EXECUTION_ALLOWED:rule=${ruleKey ?: -1} type=${candidate?.name ?: "UNKNOWN"}") {
            BypassBlackbox.admitted(it)
        }
    }

    /** The only accepted OCR text is a fixed "跳过" / "关闭" label, never the recognized page. */
    fun noteVisualCandidate(sessionId: String, packageName: String, activityName: String?,
                            exit: BypassVisualExit, admitted: Boolean = false) {
        val snapshot = BypassCandidateSnapshot(text = if (exit.label == "跳过") "跳过" else "关闭",
            className = "LocalVisualText", bounds = exit.bounds.toDiagnosticBounds(),
            clickable = false, parentClickable = false, packageName = packageName, activityName = activityName)
        updateById(sessionId, "TARGET_FOUND:LOCAL_VISUAL_EXIT") { record ->
            val keepAdmitted = !admitted && BypassBlackbox.hasAllowedCandidate(record)
            record.copy(candidateSeen = true,
                candidateType = if (keepAdmitted) record.candidateType else BypassExitCandidateType.LOCAL_VISUAL_EXIT.name,
                matchedRules = append(record.matchedRules, "LOCAL_CHINESE_OCR_V1", MAX_RULES),
                candidateSnapshots = encodeSnapshots(decodeSnapshots(record.candidateSnapshots) + snapshot),
                actedRuleKey = if (keepAdmitted) record.actedRuleKey else null,
                actedGroupKey = if (keepAdmitted) record.actedGroupKey else null,
                actedCandidateBounds = if (keepAdmitted) record.actedCandidateBounds else snapshot.bounds)
        }
    }

    /**
     * P0-2 (multi-stage): immutable evidence of the CURRENT action, captured
     * immediately before performAction. The OutcomeVerifier receives THIS
     * snapshot and never re-guesses from the mutable active session after
     * verification has started — a later candidate B cannot pollute the
     * verification of action A.
     */
    fun captureActionEvidence(sessionId: String): BypassSessionAdEvidence? = synchronized(lock) {
        val current = active?.takeIf { it.record.sessionId == sessionId } ?: return null
        evidenceOf(current.record)
    }

    /** Pure: the latest acted evidence of a session record (JVM-testable). */
    internal fun evidenceOf(record: BypassDetectionSession): BypassSessionAdEvidence? {
        if (record.actedRuleKey == null && record.actedGroupKey == null &&
            record.actedCandidateBounds == null && record.candidateType == null
        ) {
            return null
        }
        return BypassSessionAdEvidence(
            candidateType = record.candidateType?.let {
                runCatching { BypassExitCandidateType.valueOf(it) }.getOrNull()
            },
            ruleKey = record.actedRuleKey,
            groupKey = record.actedGroupKey,
            bounds = record.actedCandidateBounds,
        )
    }

    /**
     * The ad evidence of the session currently attached to this window, or
     * null when no session / no acted candidate exists. Kept for legacy
     * callers; the verifier itself uses [captureActionEvidence] (P0-2).
     */
    fun activeSessionEvidence(packageName: String, activityName: String?): BypassSessionAdEvidence? =
        synchronized(lock) {
            val current = active?.record ?: return null
            if (!BypassBlackbox.sameWindow(current.packageName, current.activityName, packageName, activityName)) return null
            evidenceOf(current)
        }

    /** Record that one action attempt was reserved/performed. */
    fun actionAttempted(sessionId: String, action: String) {
        updateById(sessionId, "ACTION_ATTEMPT:${action.take(48)}") { record ->
            record.copy(
                actions = append(record.actions, action, MAX_ACTIONS),
                actionAttempts = record.actionAttempts + 1,
            )
        }
    }

    /** Record the verified outcome of the session's actions. */
    fun outcomeConfirmed(sessionId: String, outcome: BypassOutcome, latencyMs: Long) {
        val result = when (outcome) {
            BypassOutcome.SUCCESS_CONFIRMED -> BypassSessionResult.SUCCESS_CONFIRMED
            BypassOutcome.ACTION_NO_EFFECT -> BypassSessionResult.UNRESOLVED
            BypassOutcome.UNRESOLVED -> BypassSessionResult.UNRESOLVED
            BypassOutcome.MISCLICK_SUSPECTED -> BypassSessionResult.MISCLICK_SUSPECTED
        }
        val now = System.currentTimeMillis()
        updateById(sessionId, "OUTCOME:${outcome.name}:${latencyMs}ms") { record ->
            record.copy(
                result = if (result == BypassSessionResult.UNRESOLVED) BypassSessionResult.OPEN.name else result.name,
                success = result == BypassSessionResult.SUCCESS_CONFIRMED,
                endTime = now,
                confirmedLatencyMs = latencyMs,
                finalFailureReason = when (result) {
                    BypassSessionResult.SUCCESS_CONFIRMED -> null
                    BypassSessionResult.MISCLICK_SUSPECTED -> FailureReason.MISCLICK_SUSPECTED.name
                    else -> if (outcome == BypassOutcome.ACTION_NO_EFFECT) FailureReason.ACTION_NO_EFFECT.name else record.finalFailureReason
                },
            )
        }?.let {
            if (result == BypassSessionResult.SUCCESS_CONFIRMED || result == BypassSessionResult.MISCLICK_SUSPECTED) {
                // P0-1: a terminal outcome ends this window's transient ad
                // evidence — the previous splash's STRONG must not pollute the
                // normal page that follows (star-charge regression).
                synchronized(lock) {
                    active?.record?.let { r ->
                        BypassAdContextTracker.clearWindowEvidence(r.packageName, r.activityName)
                    }
                }
                finalizeNow(sessionId)
            }
        }
    }

    /** The active session could not be resolved within the window. */
    fun unresolved(sessionId: String, detail: String) {
        val now = System.currentTimeMillis()
        updateById(sessionId, "UNRESOLVED:$detail") { record ->
            record.copy(result = BypassSessionResult.UNRESOLVED.name, success = false, endTime = now)
        }?.let { finalizeNow(sessionId, suppress = true) }
    }

    /** Finalize a session as a confirmed failure (budget exhausted). */
    fun confirmedFailure(sessionId: String, reason: FailureReason) {
        val now = System.currentTimeMillis()
        updateById(sessionId, "FINAL:$reason") { record ->
            record.copy(
                result = BypassSessionResult.FAILURE_CONFIRMED.name,
                success = false,
                endTime = now,
                finalFailureReason = reason.name,
            )
        }?.let {
            scheduledFinalizers.remove(sessionId)?.cancel()
            // P0-1: FAILURE_CONFIRMED is terminal too — clear this window's
            // transient ad evidence.
            synchronized(lock) {
                active?.record?.let { r ->
                    BypassAdContextTracker.clearWindowEvidence(r.packageName, r.activityName)
                }
            }
            finalizeNow(sessionId, suppress = true)
        }
    }

    fun waitingForDelay(packageName: String, activityName: String?) {
        noteMatcherReason(packageName, activityName, FailureReason.WAITING_FOR_DELAY)
    }

    fun targetNotClickable(packageName: String, activityName: String?) {
        // A center gesture may still succeed; this observation is not terminal.
        update(packageName, activityName, "TARGET_FOUND_NOT_CLICKABLE", create = false)
    }

    fun candidateRejected(packageName: String, activityName: String?, candidateType: String, reason: String) {
        update(packageName, activityName, "CLOSE_CANDIDATE_REJECTED:$candidateType:$reason", create = false) {
            BypassBlackbox.recordReason(it, FailureReason.GLOBAL_EXCLUDED)
        }
    }

    /** Record the active strategy mode for the current window. */
    fun strategyApplied(packageName: String, activityName: String?, mode: BypassAdStrategyMode) {
        update(packageName, activityName, "STRATEGY:${mode.name}", create = false)
    }

    private fun finalizeNow(sessionId: String, suppress: Boolean = false) {
        synchronized(lock) {
            val current = active?.takeIf { it.record.sessionId == sessionId } ?: return
            if (suppress) {
                suppressedWindow = current.record.packageName to current.record.activityName
                suppressedEvidence = actionEvidence[sessionId] ?: evidenceOf(current.record)
            }
            actionEvidence.remove(sessionId)
            scheduledFinalizers.remove(sessionId)?.cancel()
            persist(current.record, cleanup = true)
            active = null
        }
        BypassActionBudget.reset(sessionId)
    }

    private fun closeUnresolved(current: ActiveSession, event: String) {
        val record = BypassBlackbox.finishUnresolved(current.record, System.currentTimeMillis(), event)
        persist(record, cleanup = true)
        scheduledFinalizers.remove(record.sessionId)?.cancel()
        suppressedWindow = record.packageName to record.activityName
        suppressedEvidence = actionEvidence.remove(record.sessionId) ?: evidenceOf(record)?.let { evidence ->
            evidence.copy(bounds = evidence.bounds ?: decodeSnapshots(record.candidateSnapshots).lastOrNull()?.bounds)
        }
        active = null
        BypassActionBudget.reset(record.sessionId)
    }

    private fun scheduleObservationTimeout(record: BypassDetectionSession) {
        synchronized(lock) {
            if (scheduledFinalizers.containsKey(record.sessionId)) return
            scheduledFinalizers[record.sessionId] = appScope.launch {
                delay((record.startTime + SESSION_HARD_TIMEOUT_MS - System.currentTimeMillis()).coerceAtLeast(1L))
                synchronized(lock) {
                    active?.takeIf { it.record.sessionId == record.sessionId }
                        ?.let { closeUnresolved(it, "SESSION_TIMEOUT") }
                }
            }
        }
    }

    private fun updateById(
        sessionId: String,
        event: String,
        transform: (BypassDetectionSession) -> BypassDetectionSession = { it },
    ): BypassDetectionSession? = synchronized(lock) {
        val current = active?.takeIf { it.record.sessionId == sessionId } ?: return null
        val now = System.currentTimeMillis()
        val next = transform(current.record).copy(
            diagnosticTimeline = BypassBlackbox.appendEvent(current.record, event, now),
        )
        active = ActiveSession(next, now)
        if (next != current.record) persist(next)
        next
    }

    private fun update(
        packageName: String,
        activityName: String?,
        event: String,
        create: Boolean,
        transform: (BypassDetectionSession) -> BypassDetectionSession = { it },
    ): BypassDetectionSession? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val current = active
        val sameWindow = current?.record?.let { BypassBlackbox.sameWindow(it.packageName, it.activityName, packageName, activityName) } == true
        if (sameWindow && now - current.record.startTime > SESSION_HARD_TIMEOUT_MS) {
            closeUnresolved(current, "SESSION_TIMEOUT")
            return null
        }
        if (!sameWindow && current != null) {
            // Do not evict the evidence while a click is being verified in another app.
            if (current.record.actionAttempts > 0) return null
            closeUnresolved(current, "WINDOW_ENDED")
        }
        if (create && suppressedWindow?.let { BypassBlackbox.sameWindow(it.first, it.second, packageName, activityName) } == true) return null
        val base = active?.takeIf { sameWindow } ?: if (create) {
            ActiveSession(BypassDetectionSession(
                sessionId = UUID.randomUUID().toString(), packageName = packageName,
                activityName = activityName, startTime = now,
            ), now)
        } else return null
        val next = transform(base.record).copy(
            activityName = base.record.activityName ?: activityName,
            diagnosticTimeline = BypassBlackbox.appendEvent(base.record, event, now),
        )
        active = ActiveSession(next, now)
        if (next != base.record) persist(next)
        next
    }

    private fun persist(session: BypassDetectionSession, cleanup: Boolean = false) {
        persistence.trySend(PersistRequest(session, cleanup))
    }

    private fun append(value: String, item: String, limit: Int): String {
        val clean = item.replace(Regex("[^A-Za-z0-9_ .:/=-]"), "_").take(160)
        return (value.lines().filter(String::isNotBlank) + clean).distinct().takeLast(limit).joinToString("\n")
    }

    /** Explicit user observation covers ads whose controls are absent from the accessibility tree. */
    fun reportMissedAd(packageName: String, mode: BypassAdStrategyMode, reason: FailureReason): String {
        val now = System.currentTimeMillis()
        val probe = recentWindowProbes[packageName]?.takeIf { now - it.time in 0..60_000L }
        val visual = recentVisualProbes[packageName]?.takeIf { now - it.time in 0..60_000L }
        val recentMatcher = BypassDiagnostics.events.value.firstOrNull { it.packageName == packageName && now - it.time in 0..60_000L }
        val evidence = "+0ms|AD_EVIDENCE:USER_REPORTED_AD\n+0ms|USER_REPORT:CONTROLS_NOT_CAPTURED"
        val probeStage = probe?.let { "\n+0ms|LAST_ROOT_SCAN:age=${now - it.time}ms ${it.stage}" }.orEmpty()
        val matcherStage = recentMatcher?.let { "\n+0ms|LAST_MATCHER:${it.reason.name} age=${now - it.time}ms ${it.detail}" }.orEmpty()
        val visualStage = visual?.let { "\n+0ms|LAST_VISUAL_SCAN:age=${now - it.time}ms ${it.stage}" }.orEmpty()
        val record = BypassDetectionSession(
            sessionId = UUID.randomUUID().toString(), packageName = packageName, activityName = probe?.activityName,
            startTime = now, endTime = now, strategyMode = mode.name,
            result = BypassSessionResult.UNRESOLVED.name, finalFailureReason = reason.name,
            diagnosticTimeline = evidence + probeStage + matcherStage + visualStage,
        )
        persist(record, cleanup = true)
        return record.sessionId
    }

    private fun decodeSnapshots(value: String): List<BypassCandidateSnapshot> = runCatching {
        json.decodeFromString<List<BypassCandidateSnapshot>>(value)
    }.getOrDefault(emptyList())

    private fun encodeSnapshots(snapshots: List<BypassCandidateSnapshot>): String =
        json.encodeToString(snapshots.distinct().takeLast(MAX_SNAPSHOTS))
}

/** Converts a session into a product record (success or failure row). */
internal fun BypassDetectionSession.toSessionRecord(): BypassSessionRecord {
    val reason = runCatching { FailureReason.valueOf(finalFailureReason.orEmpty()) }
        .getOrDefault(FailureReason.UNKNOWN)
    val snapshots = runCatching {
        json.decodeFromString<List<BypassCandidateSnapshot>>(candidateSnapshots)
    }.getOrDefault(emptyList())
    return BypassSessionRecord(
        id = sessionId,
        time = endTime.takeIf { it > 0 } ?: startTime,
        packageName = packageName,
        activityName = activityName,
        strategyMode = runCatching { BypassAdStrategyMode.valueOf(strategyMode) }.getOrDefault(BypassAdStrategyMode.CONSERVATIVE),
        result = sessionResult,
        actionAttempts = actionAttempts,
        confirmedLatencyMs = confirmedLatencyMs,
        candidateType = candidateType?.let { runCatching { BypassExitCandidateType.valueOf(it) }.getOrNull() },
        reason = reason,
        actions = actions.lines().filter { it.isNotBlank() },
        candidates = snapshots,
        ruleOrigin = ruleOrigin,
        candidateSeen = candidateSeen,
        matchedRules = matchedRules.lines().filter(String::isNotBlank),
        timeline = diagnosticTimeline.lines().filter(String::isNotBlank),
        actedRuleKey = actedRuleKey,
        actedGroupKey = actedGroupKey,
        actedCandidateBounds = actedCandidateBounds,
        hasAdEvidence = BypassBlackbox.hasAdEvidence(this),
    )
}

private object BypassCandidateSanitizer {
    private val closeTokens = listOf(
        "skip", "close", "dismiss", "cancel", "ignore", "ad_close",
        "跳过", "关闭", "略过", "广告", "倒计时", "×",
    )
    private val sensitiveTokens = listOf(
        "password", "passwd", "otp", "verification", "card", "bank",
        "密码", "验证码", "银行卡", "手机号", "电话", "聊天", "消息",
    )
    private val phoneOrCard = Regex("(?<!\\d)(?:\\d[ -]?){11,19}(?!\\d)")

    /**
     * Snapshot a candidate. With [structural] a text-less small node inside
     * an ad window is also snapshotted (class/bounds/clickable only) so
     * CRAZY structural failures can be taught without page content.
     */
    fun snapshot(
        node: AccessibilityNodeInfo,
        packageName: String,
        activityName: String?,
        structural: Boolean,
    ): BypassCandidateSnapshot? {
        val className = node.className?.toString().orEmpty()
        if (className.contains("edittext", ignoreCase = true) || node.isEditable || node.inputType != InputType.TYPE_NULL) {
            return null
        }
        val text = clean(node.text?.toString())
        val description = clean(node.contentDescription?.toString())
        val viewId = clean(node.viewIdResourceName)
        val values = listOfNotNull(text, description, viewId)
        val sensitive = values.any(::isSensitive)
        if (sensitive) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val small = bounds.width() in 1..220 && bounds.height() in 1..160
        val looksLikeClose = values.any(::isCloseLike) || (structural && small && !node.isEditable)
        if (!looksLikeClose) return null
        return BypassCandidateSnapshot(
            text = text?.takeIf(::isCloseLike),
            description = description?.takeIf(::isCloseLike),
            viewId = viewId?.takeIf(::isCloseLike),
            className = className.take(120),
            bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            clickable = node.isClickable,
            parentClickable = hasClickableParent(node),
            packageName = packageName,
            activityName = activityName,
        )
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }?.take(80)

    private fun isCloseLike(value: String): Boolean {
        val normalized = value.lowercase()
        return closeTokens.any { normalized.contains(it) }
    }

    private fun isSensitive(value: String): Boolean {
        val normalized = value.lowercase()
        return sensitiveTokens.any { normalized.contains(it) } || phoneOrCard.containsMatchIn(value)
    }

    private fun hasClickableParent(node: AccessibilityNodeInfo): Boolean {
        var parent = runCatching { node.parent }.getOrNull()
        repeat(5) {
            if (parent?.isClickable == true) return true
            parent = runCatching { parent?.parent }.getOrNull()
        }
        return false
    }
}
