package li.songe.gkd.bypass

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Bounded observation on the matcher's fresh root; it never performs actions. */
object BypassRootAdObserver {
    enum class Evidence { SKIP_CONTROL, AD_SPECIFIC_EXIT, CLOSE_WITH_COUNTDOWN, EXPLICIT_AD_WITH_EXIT }
    data class Observation(val evidence: Evidence?, val node: AccessibilityNodeInfo?,
                           val visited: Int, val complete: Boolean, val anyAdLabel: Boolean,
                           val closeCountdownNodes: List<AccessibilityNodeInfo> = emptyList())
    private const val MAX_NODES = 512
    private val SHORT_TIMER = Regex("[（(]?\\s*[0-9]{1,2}\\s*(?:秒|[sS])?\\s*[）)]?")

    fun canObserve(packageName: String): Boolean = packageName.isNotBlank() &&
        (!isBypassHighRiskApp(packageName) || packageName in setOf("com.tencent.mm", "com.eg.android.AlipayGphone"))

    fun inspect(root: AccessibilityNodeInfo, packageName: String, activityName: String?): Observation {
        if (!canObserve(packageName) || root.packageName?.toString() != packageName) {
            return Observation(null, null, 0, false, false)
        }
        if (packageName in setOf("com.tencent.mm", "com.eg.android.AlipayGphone") &&
            !BypassAdContextTracker.isMiniProgramAdActivity(packageName, activityName)
        ) return Observation(null, null, 0, false, false)
        val deadline = System.nanoTime() + 80_000_000L
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val labels = mutableListOf<Bounds>()
        val closes = mutableListOf<Pair<AccessibilityNodeInfo, Bounds>>()
        val timers = mutableListOf<BypassExitClassifier.NodeSemantics>()
        val windowRect = Rect().also { root.getBoundsInScreen(it) }
        val windowBounds = Bounds(windowRect.left, windowRect.top, windowRect.right, windowRect.bottom)
        val miniProgram = BypassAdContextTracker.isMiniProgramAdActivity(packageName, activityName)
        var visited = 0
        var complete = true
        var bestEvidence: Evidence? = null
        var bestNode: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty() && visited < MAX_NODES && System.nanoTime() < deadline) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
                if (miniProgram && node.childCount == 0 && rect.width() in 1..220 && rect.height() in 1..160 &&
                    timers.size < 32 && listOf(node.text, node.contentDescription).any {
                        it?.toString()?.trim()?.matches(SHORT_TIMER) == true
                    }) timers += BypassExitClassifier.snapshot(node)
                if (BypassExitClassifier.hasAdLabel(node.text?.toString(), node.contentDescription?.toString(), node.viewIdResourceName)) {
                    if (!rect.isEmpty && labels.size < 16) labels += bounds
                }
                if (rect.width() in 1..420 && rect.height() in 1..260 &&
                    (!miniProgram || BypassMiniProgramTargets.allowsTarget(BypassExitClassifier.classifyNode(node),
                        bounds, windowBounds, BypassExitClassifier.isMiniProgramNavigationClose(node)))) {
                    val type = BypassExitClassifier.classifyNode(node)
                    val evidence = when {
                        type == BypassExitCandidateType.SKIP_TEXT &&
                            (node.text?.length ?: 0) < 24 && (node.contentDescription?.length ?: 0) < 24 -> Evidence.SKIP_CONTROL
                        type in setOf(BypassExitCandidateType.CLOSE_TEXT, BypassExitCandidateType.CLOSE_DESC, BypassExitCandidateType.CLOSE_VIEW_ID) &&
                            (BypassAdContextTracker.isAdSpecificViewId(node.viewIdResourceName) ||
                                BypassAdContextTracker.isExplicitAdCloseLabel(node.text?.toString(), node.contentDescription?.toString())) -> Evidence.AD_SPECIFIC_EXIT
                        type in setOf(BypassExitCandidateType.CLOSE_TEXT, BypassExitCandidateType.CLOSE_DESC) &&
                            BypassAdContextTracker.isMiniProgramAdActivity(packageName, activityName) &&
                            BypassExitClassifier.hasAdjacentCloseCountdown(node) -> Evidence.CLOSE_WITH_COUNTDOWN
                        else -> null
                    }
                    if (evidence != null && bestEvidence == null) {
                        bestEvidence = evidence
                        bestNode = node
                    }
                    if (type in setOf(BypassExitCandidateType.CLOSE_TEXT, BypassExitCandidateType.CLOSE_DESC,
                            BypassExitCandidateType.CLOSE_VIEW_ID, BypassExitCandidateType.CLOSE_ICON) && closes.size < 12) {
                        closes += node to bounds
                    }
                }
            }
            if (node.childCount > 48) complete = false
            repeat(node.childCount.coerceAtMost(48)) { index ->
                val child = runCatching { node.getChild(index) }.getOrNull()
                if (child != null) queue.addLast(child) else complete = false
            }
        }
        if (queue.isNotEmpty()) complete = false
        val countdownCloses = if (miniProgram) closes.mapNotNull { (node, _) ->
            node.takeIf { timers.any { BypassExitClassifier.hasNearbyCloseCountdown(BypassExitClassifier.snapshot(node), it) } }
        } else emptyList()
        if (bestEvidence == null && countdownCloses.isNotEmpty()) {
            bestEvidence = Evidence.CLOSE_WITH_COUNTDOWN
            bestNode = countdownCloses.first()
        }
        if (bestEvidence == null) {
            closes.firstOrNull { (_, bounds) -> labels.any { BypassOutcomeVerifier.sameAdRegion(bounds, it) } }?.let {
                bestEvidence = Evidence.EXPLICIT_AD_WITH_EXIT
                bestNode = it.first
            }
        }
        return Observation(bestEvidence, bestNode, visited, complete, labels.isNotEmpty(), countdownCloses)
    }
}
