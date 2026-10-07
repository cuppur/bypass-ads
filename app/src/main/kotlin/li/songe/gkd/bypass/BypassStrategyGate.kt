package li.songe.gkd.bypass

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import li.songe.gkd.META
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.store.storeFlow

/**
 * Runtime gate that turns a matched node into an allowed ad-exit action.
 *
 * The GKD matcher still does the selector work; this layer decides, per
 * strategy mode, rule trust, and ad context, whether a *generic* candidate
 * is allowed to run. Curated app rules have a narrower trusted path.
 */
object BypassStrategyGate {

    fun currentStrategyMode(): BypassAdStrategyMode =
        BypassAdStrategyMode.from(storeFlow.value.bypassAdStrategyMode)

    fun currentPolicy(): BypassStrategyPolicy = currentStrategyMode().policy

    /**
     * Decide whether a candidate may act.
     *
     * @param candidate classified exit type
     * @param policy active mode policy
     * @param rulePolicy identity-derived policy of the matched rule
     * @param contextLevel current ad context (NONE/WEAK/STRONG)
     * @param inWindow whether the window was freshly entered
     */
    fun rejectReason(
        candidate: BypassExitCandidateType,
        packageName: String,
        activityName: String?,
        nodeWidth: Int,
        nodeHeight: Int,
        policy: BypassStrategyPolicy,
        rulePolicy: BypassRulePolicy,
        contextLevel: BypassAdContextLevel,
        inWindow: Boolean,
    ): String? = evaluateExecution(
        candidate = candidate,
        packageName = packageName,
        activityName = activityName,
        nodeWidth = nodeWidth,
        nodeHeight = nodeHeight,
        policy = policy,
        rulePolicy = rulePolicy,
        contextLevel = contextLevel,
        inWindow = inWindow,
    )

    /**
     * THE shared production execution gate (P0-1). The engine and the
     * production-path tests call exactly this function, so a test verdict is
     * the engine verdict. Returns null (ALLOW) or a [BypassRejectReason].
     *
     * Trust semantics:
     *  - High-risk hosts (WeChat / Alipay / ...) deny every non-exempt
     *    source outright. Exempt sources (BUNDLED_DEDICATED / BYPASS_OVERRIDE)
     *    only waive "untrusted source"; they still run the FULL gate below:
     *    real candidate -> size -> strategy -> ad context, with a startup
     *    deadline for ambiguous structural/coordinate candidates.
     *  - A null candidate (no exit semantics, or negative/sensitive text) is
     *    a HARD denial for EVERY origin — nothing ever acts as a fake
     *    SKIP_TEXT ("candidate ?: SKIP_TEXT" is forbidden).
     *  - On normal (non-high-risk) hosts, mature BUNDLED_DEDICATED rules keep
     *    the low-cost trusted path (real candidate required, no window/size/
     *    strategy/context gating; coordinates still require CRAZY).
     */
    fun evaluateExecution(
        candidate: BypassExitCandidateType?,
        packageName: String,
        activityName: String?,
        nodeWidth: Int,
        nodeHeight: Int,
        policy: BypassStrategyPolicy,
        rulePolicy: BypassRulePolicy,
        contextLevel: BypassAdContextLevel,
        inWindow: Boolean,
        verifiedMiniLayout: Boolean = false,
        verifiedVisualExit: Boolean = false,
        visualTestHost: Boolean = false,
    ): String? {
        // High-risk hosts: untrusted sources are always denied.
        val isHighRisk = isBypassHighRiskApp(packageName)
        val exemptSource = rulePolicy.trust == BypassRuleTrust.BUNDLED_DEDICATED ||
            rulePolicy.trust == BypassRuleTrust.BYPASS_OVERRIDE
        if (isHighRisk && !exemptSource) {
            return BypassRejectReason.SENSITIVE_ACTIVITY
        }

        // A matched control with no exit semantics (or with negative /
        // sensitive semantics) never acts — for ANY origin, including
        // high-risk exempt sources and normal-host dedicated rules.
        if (candidate == null) {
            return BypassRejectReason.NEGATIVE_SEMANTIC
        }
        // OCR is an internal, proof-backed path. A node/imported rule cannot
        // turn a coordinate into a visual exit by changing its candidate name.
        if (candidate == BypassExitCandidateType.LOCAL_VISUAL_EXIT &&
            (!verifiedVisualExit || rulePolicy.trust != BypassRuleTrust.BYPASS_OVERRIDE ||
                rulePolicy.coordinate || contextLevel != BypassAdContextLevel.STRONG ||
                !BypassVisualExitPolicy.isSupportedHost(packageName, activityName, visualTestHost))
        ) return BypassRejectReason.NEGATIVE_SEMANTIC
        if (candidate == BypassExitCandidateType.CURATED_MINI_EXIT &&
            (!verifiedMiniLayout || rulePolicy.trust != BypassRuleTrust.BUNDLED_DEDICATED ||
                rulePolicy.coordinate || contextLevel != BypassAdContextLevel.STRONG ||
                !isMiniProgramActivity(packageName, activityName))
        ) return BypassRejectReason.NEGATIVE_SEMANTIC

        if (rulePolicy.coordinate && !policy.allowCoordinateFallback) return BypassRejectReason.STRATEGY_GATE
        if (candidate == BypassExitCandidateType.DEDICATED_EXIT &&
            (isHighRisk || rulePolicy.trust != BypassRuleTrust.BUNDLED_DEDICATED ||
                nodeWidth !in 1..420 || nodeHeight !in 1..260)
        ) return BypassRejectReason.NEGATIVE_SEMANTIC

        // On normal hosts, curated dedicated rules run in every mode without
        // candidate gating (but still require a real semantic candidate).
        // On high-risk hosts they keep going through the window/size/strategy/
        // context checks below.
        if (!isHighRisk && rulePolicy.trust == BypassRuleTrust.BUNDLED_DEDICATED) {
            return null
        }

        // Explicit exits remain available for ads appearing later in a host
        // activity. Only ambiguous structural/coordinate guesses expire.
        if (!inWindow && !BypassRuleTiming.allowsLateCandidate(candidate, contextLevel)) {
            return BypassRejectReason.OUTSIDE_WINDOW
        }

        // Size constraint: oversized controls are not close buttons.
        if (nodeWidth !in 1..420 || nodeHeight !in 1..260) {
            return BypassRejectReason.TOO_LARGE
        }

        // Candidate type -> active policy.
        val allowed = when (candidate) {
            BypassExitCandidateType.SKIP_TEXT -> true
            BypassExitCandidateType.CLOSE_TEXT,
            BypassExitCandidateType.CLOSE_DESC,
            -> policy.allowGenericCloseText
            BypassExitCandidateType.CLOSE_VIEW_ID -> policy.allowCloseViewId
            BypassExitCandidateType.CLOSE_ICON -> policy.allowGlyphClose
            BypassExitCandidateType.STRUCTURAL_CLOSE -> policy.allowStructuralNoSemanticClose
            BypassExitCandidateType.COORDINATE_FALLBACK -> policy.allowCoordinateFallback
            BypassExitCandidateType.DEDICATED_EXIT -> false // handled only by the curated path above
            BypassExitCandidateType.CURATED_MINI_EXIT -> policy.allowGenericCloseText
            BypassExitCandidateType.LOCAL_VISUAL_EXIT -> policy.allowGenericCloseText
        }
        if (!allowed) {
            return BypassRejectReason.STRATEGY_GATE
        }

        // Generic close/glyph/structural/coordinate candidates need a strong
        // ad context (skip semantics are always explicit).
        if (candidate != BypassExitCandidateType.SKIP_TEXT &&
            rulePolicy.requiresStrongAdContext &&
            contextLevel != BypassAdContextLevel.STRONG
        ) {
            return BypassRejectReason.NO_AD_CONTEXT
        }
        return null
    }

    /**
     * Whether the current window is inside the post-launch ad window.
     *
     * The window is anchored to actual package / activity entry time (kept by
     * [BypassAdContextTracker]); a service reconnect never opens a new window.
     */
    fun inStartupWindow(packageName: String, startupWindowMs: Long = 15_000L): Boolean {
        val topApp = topActivityFlow.value.appId
        if (topApp != packageName) return false
        val now = System.currentTimeMillis()
        val activityName = topActivityFlow.value.activityId
        val activityTime = BypassAdContextTracker.activityEntryTime(packageName, activityName)
        if (activityTime > 0 && now - activityTime <= startupWindowMs) return true
        val packageTime = BypassAdContextTracker.packageEntryTime(packageName)
        val inWindow = packageTime > 0 && now - packageTime <= startupWindowMs
        if (META.debuggable && !inWindow) {
            Log.d("BypassStrategyGate", "window miss pkg=$packageName act=$activityName activityTime=$activityTime packageTime=$packageTime now=$now")
        }
        return inWindow
    }

    /** Whether an activity is a known mini-program / webview splash host. */
    fun isMiniProgramActivity(packageName: String, activityName: String?): Boolean =
        BypassAdContextTracker.isMiniProgramAdActivity(packageName, activityName)

    /** Whether a candidate node is visible to user and inside the screen. */
    fun isSaneCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty) return false
        val display = li.songe.gkd.app.resources.displayMetrics
        if (rect.right <= 0 || rect.bottom <= 0) return false
        if (rect.left >= display.widthPixels || rect.top >= display.heightPixels) return false
        return true
    }

    /** How many exit attempts remain for the current mode. */
    fun maxExitAttempts(): Int = currentPolicy().maxExitAttempts
}
