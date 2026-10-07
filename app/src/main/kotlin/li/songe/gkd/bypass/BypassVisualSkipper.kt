package li.songe.gkd.bypass

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.ViewConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import li.songe.gkd.META
import li.songe.gkd.a11y.A11yCommonImpl
import li.songe.gkd.a11y.TopActivity
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.ScreenUtils
import kotlin.coroutines.resume
import kotlin.math.abs

/** A fallback for mini-program SDK/Canvas exits which have no accessible node. */
class BypassVisualSkipper(
    private val service: A11yCommonImpl,
    private val arbiter: BypassExecutionArbiter,
    private val canRun: () -> Boolean,
    private val freshPackage: suspend () -> String?,
    private val onVerifiedExit: () -> Unit,
) {
    private var job: Job? = null
    private var lastEligibility = ""
    private val vision = BypassLocalVision(service)
    private val rulePolicy = BypassRulePolicy(BypassRuleTrust.BYPASS_OVERRIDE,
        BypassAdStrategyMode.AGGRESSIVE, requiresStrongAdContext = true, coordinate = false, maxAttempts = 3)

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true || service !is AccessibilityService || Build.VERSION.SDK_INT < 30) return
        job = scope.launch(Dispatchers.Default) {
            try {
                if (storeFlow.value.enableMiniProgramVisualSkip) {
                    if (META.debuggable) Log.d("BypassVisual", "LOCAL_MODEL_WARM_START")
                    runCatching { vision.warmUp() }.onFailure {
                        if (META.debuggable) Log.d("BypassVisual", "LOCAL_MODEL_WARM_FAILED:${it.javaClass.simpleName}")
                    }
                    if (META.debuggable) Log.d("BypassVisual", "LOCAL_MODEL_WARM_END")
                }
                while (isActive) {
                    val top = topActivityFlow.value
                    if (META.debuggable) {
                        val marker = "pkg=${top.appId} act=${top.activityId} supported=${BypassVisualExitPolicy.isSupportedHost(top.appId, top.activityId, true)} allowed=${eligible(top)} busy=${arbiter.busy}"
                        if (marker != lastEligibility) { Log.d("BypassVisual", marker); lastEligibility = marker }
                    }
                    if (eligible(top) && !arbiter.busy) {
                        try {
                            inspect(top)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            probe(top, "RECOGNITION_UNAVAILABLE type=${error.javaClass.simpleName}")
                            BypassDetectionSessions.noteMatcherReason(top.appId, top.activityId, FailureReason.VISUAL_RECOGNITION_UNAVAILABLE)
                        }
                    }
                    delay(750L)
                }
            } finally {
                withContext(NonCancellable) { vision.close() }
            }
        }
    }

    private fun eligible(top: TopActivity): Boolean = canRun() &&
        storeFlow.value.enableMiniProgramVisualSkip && storeFlow.value.enableGenericFallback &&
        BypassVisualExitPolicy.isSupportedHost(top.appId, top.activityId, META.debuggable)

    private fun sameWindow(top: TopActivity): Boolean = eligible(top) &&
        BypassBlackbox.sameWindow(top.appId, top.activityId, topActivityFlow.value.appId, topActivityFlow.value.activityId)

    private fun probe(top: TopActivity, stage: String) {
        if (META.debuggable) Log.d("BypassVisual", "pkg=${top.appId} $stage")
        BypassDetectionSessions.noteVisualProbe(top.appId, top.activityId, stage)
    }

    private suspend fun read(top: TopActivity, reference: BypassVisualExit? = null): BypassVisualFrame? {
        val frame = vision.read(reference)
        if (frame == null) {
            probe(top, vision.failure ?: "CAPTURE_UNAVAILABLE")
            BypassDetectionSessions.noteMatcherReason(top.appId, top.activityId, FailureReason.VISUAL_CAPTURE_UNAVAILABLE)
        }
        return frame
    }

    private suspend fun inspect(top: TopActivity) {
        val firstFrame = read(top) ?: return
        if (!sameWindow(top)) return
        val first = firstFrame.exit
        probe(top, "result=${if (first == null) "NO_PROVEN_EXIT" else "AD_EXIT"} " +
            BypassVisualExitPolicy.signalSummary(firstFrame.texts, firstFrame.screenWidth, firstFrame.screenHeight) +
            " mode=${BypassStrategyGate.currentStrategyMode().name}")
        if (first == null) {
            // A node scan cannot clear a visual session: the original SDK had
            // no nodes in the first place. Re-arm only on stable visual absence.
            val previous = BypassDetectionSessions.windowAdEvidence(top.appId, top.activityId)
            if (previous?.candidateType == BypassExitCandidateType.LOCAL_VISUAL_EXIT && firstFrame.texts.isNotEmpty()) {
                BypassDetectionSessions.observeClear(top.appId, top.activityId, confirmAbsence = {
                    sameWindow(top) && freshPackage() == top.appId &&
                        read(top)?.let { it.exit == null && it.texts.isNotEmpty() } == true
                }, onStableAbsence = onVerifiedExit)
            }
            return
        }
        val mode = BypassStrategyGate.currentStrategyMode()
        val sid = BypassDetectionSessions.observeAd(top.appId, top.activityId, mode,
            BypassObservedAdEvidence.VISUAL_AD_EXIT, BypassRuleTrust.BYPASS_OVERRIDE) ?: return
        BypassDetectionSessions.noteVisualCandidate(sid, top.appId, top.activityId, first)
        BypassDetectionSessions.noteStage(sid, "VISUAL_SCAN:source=LOCAL_CHINESE_OCR ad_label=true adjacent_countdown=${first.countdown}")
        val reject = BypassStrategyGate.evaluateExecution(BypassExitCandidateType.LOCAL_VISUAL_EXIT,
            top.appId, top.activityId, first.bounds.r - first.bounds.l, first.bounds.b - first.bounds.t,
            mode.policy, rulePolicy, BypassAdContextLevel.STRONG, inWindow = false, verifiedVisualExit = true,
            visualTestHost = META.debuggable)
        if (reject != null) {
            BypassDetectionSessions.candidateRejected(top.appId, top.activityId, "LOCAL_VISUAL_EXIT", reject)
            return
        }
        if (arbiter.busy) {
            BypassDetectionSessions.noteStage(sid, "VISUAL_WAIT:ANOTHER_ACTION_IN_FLIGHT")
            return
        }
        if (!foregroundReady(top, sid)) return
        val freshFrame = read(top, first) ?: return
        val fresh = freshFrame.exit
        if (fresh == null || !BypassVisualExitPolicy.canAct(first, fresh, freshFrame.capturedAt, SystemClock.elapsedRealtime())) {
            BypassDetectionSessions.noteStage(sid, "VISUAL_STALE:EXIT_CHANGED_OR_COUNTDOWN_ENDING")
            BypassDetectionSessions.noteMatcherReason(top.appId, top.activityId, FailureReason.VISUAL_TARGET_STALE)
            return
        }
        val token = arbiter.tryAcquire() ?: return
        try {
            // The foreground root must still belong to this mini-program.
            // No timer/position is cached across an app switch or an ad exit.
            if (!foregroundReady(top, sid) ||
                !BypassDetectionSessions.isActive(sid) ||
                !BypassVisualExitPolicy.canAct(first, fresh, freshFrame.capturedAt, SystemClock.elapsedRealtime())) return
            BypassDetectionSessions.noteVisualCandidate(sid, top.appId, top.activityId, fresh, admitted = true)
            BypassDetectionSessions.executionAllowed(sid, null, BypassExitCandidateType.LOCAL_VISUAL_EXIT)
            BypassDetectionSessions.noteStage(sid, "VISUAL_RECHECK:TWO_FRESH_FRAMES countdown=${fresh.countdown}")
            val maxAttempts = BypassRuntimeFlow.effectiveMaxAttempts(mode, rulePolicy)
            if (!BypassActionBudget.reserveAttempt(sid, maxAttempts)) {
                BypassDetectionSessions.confirmedFailure(sid, FailureReason.ACTION_NO_EFFECT)
                return
            }
            BypassDetectionSessions.bindActionEvidence(sid, BypassSessionAdEvidence(
                BypassExitCandidateType.LOCAL_VISUAL_EXIT, null, null, fresh.bounds.toDiagnosticBounds()))
            val actionAt = SystemClock.elapsedRealtime()
            BypassDetectionSessions.actionAttempted(sid, "visualTap")
            val accepted = tap(fresh)
            BypassDetectionSessions.noteActionResult(sid, accepted, "visualTap")
            if (!accepted) {
                if (BypassActionBudget.attemptsUsed(sid) >= maxAttempts)
                    BypassDetectionSessions.confirmedFailure(sid, FailureReason.ACTION_FAILED)
                return
            }
            BypassDetectionSessions.noteStage(sid, "VERIFY_START:source=LOCAL_VISUAL_EXIT")
            val outcome = verify(top, fresh, freshFrame.regionColors, actionAt)
            BypassDetectionSessions.outcomeConfirmed(sid, outcome, SystemClock.elapsedRealtime() - actionAt)
            when (outcome) {
                BypassOutcome.SUCCESS_CONFIRMED -> onVerifiedExit()
                BypassOutcome.MISCLICK_SUSPECTED -> Unit
                BypassOutcome.ACTION_NO_EFFECT -> if (BypassActionBudget.attemptsUsed(sid) >= maxAttempts)
                    BypassDetectionSessions.confirmedFailure(sid, FailureReason.ACTION_NO_EFFECT)
                BypassOutcome.UNRESOLVED -> BypassDetectionSessions.unresolved(sid, "visual_verification_inconclusive")
            }
        } finally {
            arbiter.release(token)
        }
    }

    private suspend fun foregroundReady(top: TopActivity, sid: String): Boolean {
        if (!sameWindow(top)) {
            BypassDetectionSessions.noteStage(sid, "VISUAL_STALE:WINDOW_OR_CONFIG_CHANGED")
            return false
        }
        val current = freshPackage()
        if (current != top.appId) {
            BypassDetectionSessions.noteStage(sid, "VISUAL_STALE:${if (current == null) "FOREGROUND_UNAVAILABLE" else "FOREGROUND_CHANGED"}")
            BypassDetectionSessions.noteMatcherReason(top.appId, top.activityId,
                if (current == null) FailureReason.ACCESSIBILITY_NODE_MISSING else FailureReason.ACTIVITY_MISMATCH)
            return false
        }
        return true
    }

    private suspend fun verify(top: TopActivity, exit: BypassVisualExit, before: IntArray?, actionAt: Long): BypassOutcome {
        delay(350L)
        var absentAt = 0L
        repeat(2) { index ->
            val current = freshPackage() ?: return BypassOutcome.UNRESOLVED
            if (current in BypassOutcomeVerifier.externalLandingPackages) return BypassOutcome.MISCLICK_SUSPECTED
            if (current != top.appId || !sameWindow(top)) return BypassOutcome.UNRESOLVED
            val frame = read(top, exit) ?: return BypassOutcome.UNRESOLVED
            if (BypassVisualExitPolicy.sameRegionHasEvidence(frame.texts, exit)) return BypassOutcome.ACTION_NO_EFFECT
            if (!pixelsChanged(before, frame.regionColors)) return BypassOutcome.UNRESOLVED
            BypassDetectionSessions.activeId(top.appId, top.activityId)?.let {
                BypassDetectionSessions.noteStage(it, "VISUAL_VERIFY:pass=${index + 1} exit_absent=true pixels_changed=true")
            }
            if (index == 0) {
                absentAt = frame.capturedAt
                delay(350L)
            } else if (!BypassVisualExitPolicy.verifyAbsenceBeforeExpiry(exit, actionAt, absentAt, frame.capturedAt)) {
                return BypassOutcome.UNRESOLVED
            }
        }
        return BypassOutcome.SUCCESS_CONFIRMED
    }

    private suspend fun tap(exit: BypassVisualExit): Boolean {
        val accessibility = service as? AccessibilityService ?: return false
        val x = (exit.bounds.l + exit.bounds.r) / 2f
        val y = (exit.bounds.t + exit.bounds.b) / 2f
        if (!ScreenUtils.inScreen(x, y)) return false
        return suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
                Path().apply { moveTo(x, y) }, 0, ViewConfiguration.getTapTimeout().toLong())).build()
            val accepted = accessibility.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    private fun pixelsChanged(before: IntArray?, after: IntArray?): Boolean {
        if (before == null || after == null || before.size != 96 || after.size != 96) return false
        val difference = before.indices.sumOf { i ->
            (0..2).sumOf { c -> abs((before[i] shr (c * 8) and 255) - (after[i] shr (c * 8) and 255)) }
        }
        return difference / (96 * 3) >= 15
    }
}

internal fun Bounds.toDiagnosticBounds(): String = "$l,$t,$r,$b"
