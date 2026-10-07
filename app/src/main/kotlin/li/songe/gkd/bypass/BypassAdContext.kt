package li.songe.gkd.bypass

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Three-level ad context. Deliberately coarse: no fake AI scores. The
 * levels only gate whether generic close/glyph/structural/coordinate
 * candidates may act.
 */
enum class BypassAdContextLevel {
    NONE,
    WEAK,
    STRONG,
}

/**
 * Tracks ad-context evidence for the current top window and remembers
 * package / activity entry times separately. A service reconnect is NOT a
 * launch: it never opens a new splash window by itself (the entry-time maps
 * only move when the top app or activity actually changes).
 *
 * P0-1 (final misclick path): a window-wide ad label must NEVER upgrade the
 * whole window to STRONG — a far-away page banner (star-charge 瓜子/GNC
 * banners) must not lend STRONG to an unrelated small ImageView/X/Close.
 * STRONG is only reachable with evidence tied to the CURRENT window /
 * candidate:
 *
 *   - an explicit SKIP_TEXT candidate seen in this window (skip itself is
 *     strong ad evidence and keeps the session in STRONG),
 *   - a curated ad-specific close viewId (ad_close / splash_close /
 *     close_ad) candidate in this window,
 *   - an ad label located NEAR the current candidate's bounds on the
 *     already-fetched root (reuses scanFreshAdLabelNear / sameAdRegion),
 *   - the current session's confirmed skip/ad evidence (windowSkipSeen /
 *     windowAdViewIdSeen are exactly that, per window).
 *
 * Transient evidence has a lifecycle: it is cleared on a real package
 * change, on a real activity change, and when a session reaches a terminal
 * outcome (SUCCESS_CONFIRMED / MISCLICK_SUSPECTED / FAILURE_CONFIRMED).
 * Routine re-matches of the same ad never clear it.
 */
object BypassAdContextTracker {

    private const val CONTEXT_WINDOW_MS = 15_000L

    private val packageEntryTimes = ConcurrentHashMap<String, Long>()
    private val activityEntryTimes = ConcurrentHashMap<String, Long>() // key: "$pkg|$activity"

    /** Per-window evidence: "$pkg|$activity" -> flags. */
    private val windowSkipSeen = ConcurrentHashMap<String, Boolean>()
    private val windowCloseSeen = ConcurrentHashMap<String, Boolean>()

    /**
     * Curated ad-specific close viewIds that are themselves ad evidence
     * (P0-1): ad_close / splash_close / close_ad. Generic close viewIds
     * (close_btn / close_icon / iv_close ...) are NOT ad proof — they only
     * mark a close candidate, which stays WEAK without other evidence.
     */
    private val windowAdViewIdSeen = ConcurrentHashMap<String, Boolean>()

    /**
     * Bounds of skip / ad-specific-viewId evidence in this window. STRONG for
     * a later Close is only granted when the CURRENT candidate sits in the
     * same ad region as one of these (P0-1). A skip on a splash must never
     * STRONG an unrelated small ImageView elsewhere in the same Activity.
     */
    private val windowSkipBounds = ConcurrentHashMap<String, String>()
    private val windowAdViewIdBounds = ConcurrentHashMap<String, String>()

    /** Last package seen through the event stream; used to distinguish a real
     * package transition (refresh package entry time) from an activity change
     * or a service reconnect on the same package (keep it). */
    @Volatile
    private var lastEventPackage: String? = null

    /** Last activity seen through the event stream; used to clear the OLD
     * window's transient evidence on a real activity change. */
    @Volatile
    private var lastEventActivity: String? = null

    /** Called from A11yState.updateTopActivity on every top change. */
    fun onTopActivityChanged(packageName: String, activityName: String?, time: Long) {
        if (lastEventPackage != packageName) {
            // A real package transition (P0-5: REAL_PACKAGE_CHANGE) must
            // refresh the package entry time, even when the package returns
            // after another app was foreground (A -> B -> A). It also ends
            // every old window's transient ad evidence (P0-1): a previous
            // splash's SKIP must never pollute the next window.
            packageEntryTimes[packageName] = time
            clearAllWindowEvidence()
            lastEventPackage = packageName
            lastEventActivity = activityName
        } else {
            // Same package, new activity (REAL_ACTIVITY_CHANGE): refresh only
            // the activity entry time (WeChat LauncherUI -> AppBrandUI is a
            // fresh activity window while the package entry time stays put)
            // and clear the OLD activity's transient evidence.
            if (lastEventActivity != activityName) {
                lastEventActivity?.let { clearWindowEvidence(packageName, it) }
                lastEventActivity = activityName
            }
        }
        if (!activityName.isNullOrBlank()) {
            activityEntryTimes[windowKey(packageName, activityName)] = time
        }
    }

    /** Called when the top app changes (package-level transition). */
    fun onPackageChanged(packageName: String, time: Long) {
        packageEntryTimes[packageName] = time
        clearAllWindowEvidence()
        lastEventPackage = packageName
        lastEventActivity = null
    }

    /**
     * Engine-side observation: the matcher saw [packageName] as the top app
     * (from a fresh active-window read). This anchors the ad window even when
     * the OEM swallows the launch accessibility event. Only a REAL package
     * transition re-opens the window and clears the previous window's
     * transient evidence; a service reconnect (empty baseline) or a re-read
     * of the same app never does (P0-5).
     */
    fun onAppObserved(
        packageName: String,
        time: Long,
        type: BypassWindowAnchorObservationType = BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE,
    ) {
        if (type == BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE) {
            packageEntryTimes[packageName] = time
            clearAllWindowEvidence()
        }
    }

    fun packageEntryTime(packageName: String): Long = packageEntryTimes[packageName] ?: 0L

    fun activityEntryTime(packageName: String, activityName: String?): Long =
        activityName?.let { activityEntryTimes[windowKey(packageName, it)] } ?: 0L

    /** Record that the current window produced an ad candidate. */
    fun noteCandidate(
        packageName: String,
        activityName: String?,
        candidate: BypassExitCandidateType?,
        viewId: String? = null,
        bounds: String? = null,
    ) {
        if (candidate == null) return
        val key = windowKey(packageName, activityName)
        when (candidate) {
            BypassExitCandidateType.SKIP_TEXT -> {
                windowSkipSeen[key] = true
                if (!bounds.isNullOrBlank()) windowSkipBounds[key] = bounds
            }
            BypassExitCandidateType.CLOSE_VIEW_ID ->
                if (isAdSpecificViewId(viewId)) {
                    windowAdViewIdSeen[key] = true
                    if (!bounds.isNullOrBlank()) windowAdViewIdBounds[key] = bounds
                } else {
                    windowCloseSeen[key] = true
                }
            else -> windowCloseSeen[key] = true
        }
    }

    /** Curated ad-specific close viewIds (P0-1): the only viewIds that are
     * themselves STRONG ad evidence. */
    internal fun isAdSpecificViewId(viewId: String?): Boolean {
        val v = BypassExitClassifier.resourceName(viewId)
        return v.contains("ad_close") || v.contains("splash_close") || v.contains("close_ad")
    }

    internal fun isExplicitAdCloseLabel(text: String?, description: String?): Boolean {
        val label = listOfNotNull(text, description).joinToString(" ").lowercase()
        return listOf("关闭广告", "關閉廣告", "close ad", "关闭弹屏").any { label.contains(it) }
    }

    /**
     * Candidate-scoped ad context (P0-1): STRONG only with evidence tied to
     * the CURRENT candidate / current ad — never a far-away page banner, and
     * never a previous Skip anywhere in the same Activity lending STRONG to
     * an unrelated small ImageView.
     *
     * STRONG evidence:
     *  - this candidate is an ad-specific close viewId
     *  - an ad label sits NEAR this candidate (sameAdRegion)
     *  - this window already saw Skip / ad-specific viewId in the SAME ad
     *    region, and the current candidate is a SEMANTIC exit (skip / close
     *    text / desc / viewId / X). STRUCTURAL_CLOSE / COORDINATE_FALLBACK
     *    cannot inherit leftover skip evidence.
     */
    fun evaluateCandidateContext(
        packageName: String,
        activityName: String?,
        root: AccessibilityNodeInfo?,
        candidateBounds: String?,
        candidateType: BypassExitCandidateType? = null,
        now: Long = System.currentTimeMillis(),
        candidateViewId: String? = null,
        candidateText: String? = null,
        candidateDescription: String? = null,
        candidateHasAdjacentCountdown: Boolean = false,
    ): BypassAdContextLevel {
        // The current candidate being an explicit Skip is itself STRONG ad
        // evidence (task card: Skip does not require STRONG to act, but it
        // is STRONG evidence for a later same-region Close).
        if (candidateType == BypassExitCandidateType.SKIP_TEXT) {
            return BypassAdContextLevel.STRONG
        }
        if (candidateType == BypassExitCandidateType.CLOSE_TEXT || candidateType == BypassExitCandidateType.CLOSE_DESC) {
            if (isExplicitAdCloseLabel(candidateText, candidateDescription)) {
                return BypassAdContextLevel.STRONG
            }
            if (candidateHasAdjacentCountdown && isMiniProgramAdActivity(packageName, activityName)) {
                return BypassAdContextLevel.STRONG
            }
        }
        // Evaluate the current control before noteCandidate persists it. An
        // ad-specific id is already evidence; requiring earlier observations
        // here would reject the first (and every subsequent) match.
        if (candidateType == BypassExitCandidateType.CLOSE_VIEW_ID &&
            isAdSpecificViewId(candidateViewId)
        ) {
            return BypassAdContextLevel.STRONG
        }
        val key = windowKey(packageName, activityName)
        val semantic = BypassOutcomeVerifier.isSemanticSibling(candidateType)
        if (semantic && relatedSkipOrAdViewIdEvidence(key, candidateBounds)) {
            return BypassAdContextLevel.STRONG
        }
        // An ad label near the CURRENT candidate is evidence about THIS
        // candidate (same ad region), not about the whole window. Structural
        // / coordinate candidates may only get STRONG this way (or via an
        // ad-specific viewId on the candidate itself).
        if (root != null && candidateBounds != null &&
            scanFreshAdLabelNear(root, candidateBounds)
        ) {
            return BypassAdContextLevel.STRONG
        }
        // Window-level evaluate() still reports STRONG when skip was seen
        // (session-level tests / skip itself). Candidate-scoped callers must
        // not inherit that as STRONG for an unrelated structural control.
        return evaluateWithoutSkipStrong(packageName, activityName, now)
    }

    /**
     * Skip / ad-specific-viewId evidence is related to [candidateBounds] when
     * they share an ad region. Evidence recorded without bounds only lends
     * STRONG when the caller also has no bounds (unknown region); a concrete
     * far-away candidate must not inherit it.
     */
    private fun relatedSkipOrAdViewIdEvidence(key: String, candidateBounds: String?): Boolean {
        val skip = windowSkipSeen[key] == true
        val adId = windowAdViewIdSeen[key] == true
        if (!skip && !adId) return false
        val cand = BypassOutcomeVerifier.parseBounds(candidateBounds)
        if (cand == null) return true
        val skipB = BypassOutcomeVerifier.parseBounds(windowSkipBounds[key])
        if (skip && skipB != null && BypassOutcomeVerifier.sameAdRegion(cand, skipB)) return true
        val adB = BypassOutcomeVerifier.parseBounds(windowAdViewIdBounds[key])
        if (adId && adB != null && BypassOutcomeVerifier.sameAdRegion(cand, adB)) return true
        // Skip seen but its bounds are unknown: do NOT upgrade a concrete
        // far candidate. Same-session Close without stored skip bounds still
        // gets STRONG only via a near ad label / this candidate's own type.
        return false
    }

    private fun evaluateWithoutSkipStrong(
        packageName: String,
        activityName: String?,
        now: Long,
    ): BypassAdContextLevel {
        if (isMiniProgramAdActivity(packageName, activityName)) {
            return BypassAdContextLevel.WEAK
        }
        val key = windowKey(packageName, activityName)
        val freshEntry = freshEntry(packageName, activityName, now)
        if (windowCloseSeen[key] == true && freshEntry) {
            return BypassAdContextLevel.WEAK
        }
        return BypassAdContextLevel.NONE
    }

    /**
     * Bounded window scan for ad-label evidence (广告 / ad / sponsored). This
     * is context evidence only: it walks at most [MAX_SCAN_NODES] nodes of
     * the already-fetched root, never a second scanner. P0-1: it no longer
     * persists window evidence — a window-wide label must not upgrade STRONG.
     */
    @Deprecated("P0-1: page-wide ad labels must never upgrade the window; use evaluateCandidateContext")
    fun noteAdLabelFromWindow(root: AccessibilityNodeInfo, packageName: String, activityName: String?) {
        // Intentionally a no-op: window-wide ad labels are no longer ad
        // evidence for the whole window (star-charge page banners).
    }

    /**
     * One-shot ad-label scan on a fresh root. Never persists window evidence:
     * the OutcomeVerifier uses it AFTER an action, where the question is "is
     * there ad evidence RIGHT NOW", not "was there ever any".
     */
    fun scanFreshAdLabel(root: AccessibilityNodeInfo): Boolean =
        scanForAdLabel(root) != null

    /**
     * Session-scoped fresh ad-label scan (P0-2): true only when an ad label
     * (广告 / ad / sponsored) is visible NEAR the acted candidate's bounds on
     * the fresh root. Unrelated banners / labels elsewhere on the page (e.g.
     * a star-charge page banner) never count as "the ad is still up".
     */
    fun scanFreshAdLabelNear(root: AccessibilityNodeInfo, evidenceBounds: String): Boolean {
        val evidence = BypassOutcomeVerifier.parseBounds(evidenceBounds) ?: return false
        var visited = 0
        val MAX_SCAN_NODES = 80
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (visited++ >= MAX_SCAN_NODES) return false
            if (BypassExitClassifier.hasAdLabel(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    node.viewIdResourceName,
                )
            ) {
                val rect = android.graphics.Rect().also {
                    runCatching { node.getBoundsInScreen(it) }.getOrNull()
                }
                val labelBounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
                if (BypassOutcomeVerifier.sameAdRegion(evidence, labelBounds)) return true
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            repeat(childCount.coerceAtMost(24)) { index ->
                runCatching { node.getChild(index) }.getOrNull()?.let {
                    if (visit(it)) return true
                }
            }
            return false
        }
        return runCatching { visit(root) }.getOrDefault(false)
    }

    private fun scanForAdLabel(root: AccessibilityNodeInfo): String? {
        var visited = 0
        val MAX_SCAN_NODES = 80
        fun visit(node: AccessibilityNodeInfo): String? {
            if (visited++ >= MAX_SCAN_NODES) return null
            if (BypassExitClassifier.hasAdLabel(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    node.viewIdResourceName,
                )
            ) {
                return "ad_label"
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            repeat(childCount.coerceAtMost(24)) { index ->
                runCatching { node.getChild(index) }.getOrNull()?.let {
                    visit(it)?.let { label -> return label }
                }
            }
            return null
        }
        return runCatching { visit(root) }.getOrDefault(null)
    }

    /**
     * P0-1: transient ad evidence dies with its window. Called on a real
     * activity change (old activity) and on terminal session outcomes.
     */
    fun clearWindowEvidence(packageName: String, activityName: String?) {
        val key = windowKey(packageName, activityName)
        windowSkipSeen.remove(key)
        windowCloseSeen.remove(key)
        windowAdViewIdSeen.remove(key)
        windowSkipBounds.remove(key)
        windowAdViewIdBounds.remove(key)
    }

    /** P0-1: wipe all transient ad evidence (real package change). */
    fun clearAllWindowEvidence() {
        windowSkipSeen.clear()
        windowCloseSeen.clear()
        windowAdViewIdSeen.clear()
        windowSkipBounds.clear()
        windowAdViewIdBounds.clear()
    }

    fun resetWindow(packageName: String, activityName: String?) {
        clearWindowEvidence(packageName, activityName)
    }

    /**
     * Known mini-program / webview ad hosts. Presence of one of these
     * activities is WEAK ad context only: WeChat/Alipay mini-program ads live
     * inside these webview shells, but so do every normal page of the
     * mini-programs themselves (star-charge pages/member pages).
     */
    fun isMiniProgramAdActivity(packageName: String, activityName: String?): Boolean {
        if (activityName.isNullOrBlank()) return false
        return when (packageName) {
            "com.tencent.mm" ->
                  activityName.contains(".plugin.appbrand.ui.AppBrandUI") ||
                      activityName.contains(".plugin.appbrand.ui.AppBrandPluginUI") ||
                    activityName.contains(".plugin.appbrand.launching.AppBrandLaunchProxyUI") ||
                    activityName.contains("AppBrandUI")
            "com.eg.android.AlipayGphone" ->
                activityName.contains("XRiverActivity") ||
                    activityName.contains("NebulaActivity") ||
                    activityName.contains("nebula")
            else -> false
        }
    }

    /**
     * Evaluate the ad context of the current window (P0-1/P0-3):
     *
     * STRONG only with REAL ad evidence tied to this window: an explicit skip
     * candidate already seen here, or a curated ad-specific close viewId.
     * A page-wide ad label is never STRONG.
     * WEAK when the window is a known mini-program shell (AppBrandUI /
     * XRiverActivity) or a close candidate appeared during a fresh entry but
     * nothing else confirms an ad. NONE otherwise.
     */
    fun evaluate(
        packageName: String,
        activityName: String?,
        now: Long = System.currentTimeMillis(),
    ): BypassAdContextLevel {
        val key = windowKey(packageName, activityName)
        // Real window-scoped ad evidence first (P0-1): skip candidate or
        // curated ad-specific close viewId in THIS window.
        if (windowSkipSeen[key] == true || windowAdViewIdSeen[key] == true) {
            return BypassAdContextLevel.STRONG
        }
        // A mini-program shell is only weak evidence: the ad itself may or
        // may not be present.
        if (isMiniProgramAdActivity(packageName, activityName)) {
            return BypassAdContextLevel.WEAK
        }
        val freshEntry = freshEntry(packageName, activityName, now)
        if (windowCloseSeen[key] == true && freshEntry) {
            return BypassAdContextLevel.WEAK
        }
        return BypassAdContextLevel.NONE
    }

    /** Whether the window was entered recently (post-launch window). */
    fun freshEntry(packageName: String, activityName: String?, now: Long): Boolean {
        val activityTime = activityEntryTime(packageName, activityName)
        if (activityTime > 0 && now - activityTime <= CONTEXT_WINDOW_MS) return true
        val packageTime = packageEntryTime(packageName)
        return packageTime > 0 && now - packageTime <= CONTEXT_WINDOW_MS
    }

    private fun windowKey(packageName: String, activityName: String?) = "$packageName|${activityName ?: ""}"

    /** Test hook: wipe all tracker state (JVM unit tests). */
    fun clearForTest() {
        packageEntryTimes.clear()
        activityEntryTimes.clear()
        windowSkipSeen.clear()
        windowCloseSeen.clear()
        windowAdViewIdSeen.clear()
        windowSkipBounds.clear()
        windowAdViewIdBounds.clear()
        lastEventPackage = null
        lastEventActivity = null
    }
}

/**
 * How a fresh top-app observation relates to the startup window (P0-5).
 *
 * A service reconnect starts with an empty baseline: the first fresh root
 * read after reconnect only ESTABLISHES the baseline and is not evidence of
 * a launch — it must never refresh the package entry time
 * (SERVICE_RECONNECT). A genuine A -> B package transition (or a
 * stale-baseline reveal of a different app) does re-anchor
 * (REAL_PACKAGE_CHANGE). Real package transitions are also delivered through
 * the event stream (REAL_ACTIVITY_CHANGE when only the activity changed).
 */
enum class BypassWindowAnchorObservationType {
    /** First fresh observation after a reconnect / engine start: baseline only. */
    SERVICE_RECONNECT,

    /** A real package transition (observed or event-driven). */
    REAL_PACKAGE_CHANGE,

    /** Same package, new activity (event-driven only). */
    REAL_ACTIVITY_CHANGE,

    /** Re-observation of the same package that is still foreground. */
    ENGINE_FALLBACK_OBSERVATION,
}

object BypassWindowAnchor {
    /**
     * Classify a fresh top-app observation. The empty baseline is no longer
     * treated as "must re-anchor": it is a reconnect baseline.
     */
    fun classifyObservation(previousObservedApp: String, freshApp: String): BypassWindowAnchorObservationType =
        when {
            previousObservedApp.isEmpty() -> BypassWindowAnchorObservationType.SERVICE_RECONNECT
            previousObservedApp != freshApp -> BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE
            else -> BypassWindowAnchorObservationType.ENGINE_FALLBACK_OBSERVATION
        }

    /**
     * Backward-compatible predicate: only a REAL package transition may
     * (re)anchor the startup window.
     */
    fun shouldReanchorOnObservation(previousObservedApp: String, freshApp: String): Boolean =
        classifyObservation(previousObservedApp, freshApp) ==
            BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE
}
