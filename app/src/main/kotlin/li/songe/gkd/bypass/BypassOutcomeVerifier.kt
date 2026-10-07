package li.songe.gkd.bypass

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Outcome of one performed action, decided from a fresh state read (never
 * from the stale matched node alone).
 */
enum class BypassOutcome {
    SUCCESS_CONFIRMED,
    ACTION_NO_EFFECT,
    UNRESOLVED,
    MISCLICK_SUSPECTED,
}

/** Pure screen-rect math (JVM-testable; no android.graphics dependency). */
data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int)

/**
 * Verifies what an action actually did by re-reading the active window and
 * re-querying ad evidence with the same GKD selector machinery (no second
 * scanner). The result is the session-level truth.
 *
 * The check is SESSION-SCOPED (P0-2): after an action the verifier re-checks
 * THE SAME ad region/exit the session acted on — the same rule family and the
 * same candidate bounds — never the whole window:
 *
 *   Action -> delay -> re-read top package/activity (never the pre-delay
 *   cached state) -> fresh root -> re-match the acted rule -> candidate
 *   bounds vs acted bounds -> ad labels near the acted candidate.
 *
 *  - top moved to an external landing package (browser / store / launcher)
 *    after the delay               -> MISCLICK_SUSPECTED
 *  - top moved elsewhere           -> UNRESOLVED
 *  - no session evidence / no fresh root -> UNRESOLVED
 *  - the SAME ad candidate (same rule + same region) still matches
 *                                   -> ACTION_NO_EFFECT
 *  - an ad label is still visible NEAR the acted candidate
 *                                   -> ACTION_NO_EFFECT
 *  - unrelated banners / unrelated ad labels elsewhere on the page never
 *    block SUCCESS_CONFIRMED (star-charge page banners are not "the ad")
 *  - otherwise (same app, no same-ad evidence) -> SUCCESS_CONFIRMED
 */
object BypassOutcomeVerifier {

    private const val VERIFY_DELAY_MS = 300L

    /** Same-ad region tolerance (px, center distance of bounds). */
    const val SAME_AD_RADIUS_PX = 500

    /** Browsers / app stores / launchers / ad landing hosts. */
    val externalLandingPackages = setOf(
        "com.android.chrome",
        "com.android.browser",
        "com.miui.browser",
        "com.tencent.mtt",
        "com.UCMobile",
        "com.quark.browser",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.oplus.browser",
        "com.android.vending",
        "com.xiaomi.market",
        "com.tencent.android.qqdownloader",
        "com.huawei.appmarket",
        "com.oppo.market",
        "com.vivo.market",
        "com.bbk.appstore",
        // Launchers: an ad-exit action that throws the user to the home
        // screen did not close an ad -> misclick-suspected, stop.
        "com.miui.home",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.android.launcher",
    )

    /**
     * @param packageName the package the action ran in (the original top app)
     * @param sessionEvidence the ad evidence the session acted on (may be null
     *        when no candidate was recorded -> UNRESOLVED, never a guessed
     *        success or failure)
     * @param freshWindowProvider fresh active-window read after the delay
     * @param topFallbackProvider fallback fresh top-package read when the
     *        window read fails (e.g. the event-driven topActivityFlow)
     * @param freshSameAdProvider whether the SAME rule still matches near the
     *        acted candidate bounds on the fresh root
     * @param freshProximityAdLabelProvider whether an ad label is still
     *        visible near the acted candidate bounds on the fresh root
     */
    suspend fun verify(
        packageName: String,
        sessionEvidence: BypassSessionAdEvidence?,
        freshWindowProvider: suspend () -> AccessibilityNodeInfo?,
        topFallbackProvider: suspend () -> String?,
        freshSameAdProvider: suspend (AccessibilityNodeInfo, BypassSessionAdEvidence) -> Boolean,
        freshProximityAdLabelProvider: suspend (AccessibilityNodeInfo, BypassSessionAdEvidence) -> Boolean,
    ): BypassOutcome {
        // 1. Give the target app a moment to react — and a possible external
        //    jump (browser/store/launcher) a moment to show up.
        delay(VERIFY_DELAY_MS)

        // 2. Re-read the top package/activity AFTER the delay. The cached
        //    pre-delay top state must never decide the outcome (P0-5): a
        //    performAction returning true followed 200ms later by a jump to
        //    Chrome must be MISCLICK_SUSPECTED, never SUCCESS_CONFIRMED.
        val freshRoot = freshWindowProvider()
        val freshTopPkg = freshRoot?.packageName?.toString() ?: topFallbackProvider()

        // 3. Fresh evidence on the same window, scoped to the acted candidate.
        //    Without session evidence there is nothing to verify: the verdict
        //    is UNRESOLVED, never a guessed success/failure.
        if (freshRoot != null && sessionEvidence != null) {
            val sameAdExists = runCatching {
                freshSameAdProvider(freshRoot, sessionEvidence)
            }.getOrElse { return BypassOutcome.UNRESOLVED }
            val proximityAdLabel = runCatching {
                freshProximityAdLabelProvider(freshRoot, sessionEvidence)
            }.getOrElse { return BypassOutcome.UNRESOLVED }
            val first = decideFromVerify(
                freshTopPkg = freshTopPkg, packageName = packageName, freshRootAvailable = true,
                sessionEvidenceAvailable = true, sameAdExists = sameAdExists, proximityAdLabel = proximityAdLabel,
            )
            if (first != BypassOutcome.SUCCESS_CONFIRMED) return first
            // A brief empty/loading tree must not be reported as an exit.
            delay(150L)
            val stableRoot = freshWindowProvider() ?: return BypassOutcome.UNRESOLVED
            val stableTop = stableRoot.packageName?.toString() ?: topFallbackProvider()
            val stableAd = runCatching { freshSameAdProvider(stableRoot, sessionEvidence) }
                .getOrElse { return BypassOutcome.UNRESOLVED }
            val stableLabel = runCatching { freshProximityAdLabelProvider(stableRoot, sessionEvidence) }
                .getOrElse { return BypassOutcome.UNRESOLVED }
            return decideFromVerify(
                freshTopPkg = stableTop, packageName = packageName,
                freshRootAvailable = true, sessionEvidenceAvailable = true,
                sameAdExists = stableAd, proximityAdLabel = stableLabel,
            )
        }
        return decideFromVerify(
            freshTopPkg = freshTopPkg,
            packageName = packageName,
            freshRootAvailable = freshRoot != null,
            sessionEvidenceAvailable = sessionEvidence != null,
            sameAdExists = false,
            proximityAdLabel = false,
        )
    }

    /**
     * Pure full-chain decision used by [verify] (unit-testable without an
     * AccessibilityNodeInfo): top-change checks, fresh-root availability,
     * session evidence, then the same-ad / proximity evidence.
     */
    internal fun decideFromVerify(
        freshTopPkg: String?,
        packageName: String,
        freshRootAvailable: Boolean,
        sessionEvidenceAvailable: Boolean,
        sameAdExists: Boolean,
        proximityAdLabel: Boolean,
    ): BypassOutcome = when {
        freshTopPkg != null && freshTopPkg != packageName ->
            decide(
                sameAdExists = false,
                proximityAdLabel = false,
                topChanged = true,
                topChangedToExternal = isExternalLanding(freshTopPkg),
                hasSessionEvidence = sessionEvidenceAvailable,
            )
        !freshRootAvailable -> BypassOutcome.UNRESOLVED
        !sessionEvidenceAvailable -> BypassOutcome.UNRESOLVED
        else -> decide(
            sameAdExists = sameAdExists,
            proximityAdLabel = proximityAdLabel,
            topChanged = false,
            topChangedToExternal = false,
            hasSessionEvidence = true,
        )
    }

    /**
     * Pure decision logic (unit-testable):
     *
     *  - top moved to an external landing package  -> MISCLICK_SUSPECTED
     *  - top moved elsewhere (not external)        -> UNRESOLVED
     *  - no session evidence                       -> UNRESOLVED
     *  - the SAME ad candidate still matches       -> ACTION_NO_EFFECT
     *  - an ad label is still near the acted candidate -> ACTION_NO_EFFECT
     *  - otherwise (same app, no same-ad evidence) -> SUCCESS_CONFIRMED
     */
    fun decide(
        sameAdExists: Boolean,
        proximityAdLabel: Boolean,
        topChanged: Boolean,
        topChangedToExternal: Boolean,
        hasSessionEvidence: Boolean,
    ): BypassOutcome = when {
        topChanged && topChangedToExternal -> BypassOutcome.MISCLICK_SUSPECTED
        topChanged -> BypassOutcome.UNRESOLVED
        !hasSessionEvidence -> BypassOutcome.UNRESOLVED
        sameAdExists -> BypassOutcome.ACTION_NO_EFFECT
        proximityAdLabel -> BypassOutcome.ACTION_NO_EFFECT
        else -> BypassOutcome.SUCCESS_CONFIRMED
    }

    fun isExternalLanding(packageName: String): Boolean = externalLandingPackages.contains(packageName)

    /**
     * Semantic exit types that may continue a multi-stage ad after the exact
     * acted rule vanished (P0-2). STRUCTURAL_CLOSE / COORDINATE_FALLBACK are
     * deliberately excluded: a normal-page ImageView must never fabricate
     * "the ad is still up".
     */
    val SEMANTIC_SIBLING_CANDIDATE_TYPES: Set<BypassExitCandidateType> = setOf(
        BypassExitCandidateType.SKIP_TEXT,
        BypassExitCandidateType.CLOSE_TEXT,
        BypassExitCandidateType.CLOSE_DESC,
        BypassExitCandidateType.CLOSE_VIEW_ID,
        BypassExitCandidateType.CLOSE_ICON,
    )

    fun isSemanticSibling(candidateType: BypassExitCandidateType?): Boolean =
        candidateType != null && candidateType in SEMANTIC_SIBLING_CANDIDATE_TYPES

    /**
     * Pure multi-stage "is the SAME ad still present?" check (P0-2, JVM-testable).
     *
     *  - the exact acted rule still matches in the same region -> yes
     *  - the exact rule is gone, but a SEMANTIC sibling of the same group is
     *    still in the same ad region (Skip -> Close) -> yes
     *  - a STRUCTURAL_CLOSE / COORDINATE_FALLBACK sibling is never "the ad"
     *  - a far-away banner / unrelated ImageView is never "the ad"
     */
    fun sameAdStillPresent(
        exactRuleMatchedInSameRegion: Boolean,
        siblingType: BypassExitCandidateType? = null,
        siblingInSameRegion: Boolean = false,
    ): Boolean = exactRuleMatchedInSameRegion ||
        (isSemanticSibling(siblingType) && siblingInSameRegion)

    /** "left,top,right,bottom" -> bounds (null when malformed). */
    fun parseBounds(value: String?): Bounds? = value
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.size == 4 }
        ?.let { Bounds(it[0], it[1], it[2], it[3]) }

    /** Center distance in px between two bounds. */
    fun centerDistance(a: Bounds, b: Bounds): Int {
        val ax = (a.l + a.r) / 2
        val ay = (a.t + a.b) / 2
        val bx = (b.l + b.r) / 2
        val by = (b.t + b.b) / 2
        val dx = ax - bx
        val dy = ay - by
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
    }

    /** Whether two bounds belong to the same ad region. */
    fun sameAdRegion(a: Bounds, b: Bounds): Boolean =
        centerDistance(a, b) <= SAME_AD_RADIUS_PX

    /** Accessor kept for tests that want to pin the verifier delay. */
    internal fun verifyDelayMs(): Long = VERIFY_DELAY_MS
}
