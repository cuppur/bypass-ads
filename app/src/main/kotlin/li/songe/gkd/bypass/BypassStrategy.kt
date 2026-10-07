package li.songe.gkd.bypass

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Three-tier splash exit strategy.
 *
 * The mode decides which candidates, which fallbacks, which actions, and how
 * many exit attempts are allowed. AGGRESSIVE and CRAZY must differ in
 * candidate discovery: only CRAZY may run structural no-semantic close and
 * coordinate fallback, and only inside STRONG ad context.
 */
enum class BypassAdStrategyMode {
    CONSERVATIVE,
    AGGRESSIVE,
    CRAZY;

    val label: String
        get() = when (this) {
            CONSERVATIVE -> "保守"
            AGGRESSIVE -> "激进"
            CRAZY -> "彻底疯狂"
        }

    val description: String
        get() = when (this) {
            CONSERVATIVE -> "只运行成熟专用规则与明确的“跳过”语义控件。误触风险最低，可能漏过关闭/X 类广告。"
            AGGRESSIVE -> "确认广告上下文后，识别“跳过、关闭、Close、ad_close、×”等出口并允许有限重试。小程序可使用本地视觉补查，要求出口、广告标识与相邻倒计时同时成立。"
            CRAZY -> "确认广告上下文后，进一步尝试结构型关闭控件与受限坐标规则。成功率优先，误触风险最高。"
        }

    val policy: BypassStrategyPolicy
        get() = when (this) {
            CONSERVATIVE -> BypassStrategyPolicy(
                allowGenericCloseText = false,
                allowCloseViewId = false,
                allowGlyphClose = false,
                allowStructuralNoSemanticClose = false,
                allowCoordinateFallback = false,
                maxExitAttempts = 1,
            )
            AGGRESSIVE -> BypassStrategyPolicy(
                allowGenericCloseText = true,
                allowCloseViewId = true,
                allowGlyphClose = true,
                allowStructuralNoSemanticClose = false,
                allowCoordinateFallback = false,
                maxExitAttempts = 2,
            )
            CRAZY -> BypassStrategyPolicy(
                allowGenericCloseText = true,
                allowCloseViewId = true,
                allowGlyphClose = true,
                allowStructuralNoSemanticClose = true,
                allowCoordinateFallback = true,
                maxExitAttempts = 3,
            )
        }

    companion object {
        fun from(value: Int): BypassAdStrategyMode = entries.getOrElse(value) { CONSERVATIVE }
    }
}

/** Runtime gating parameters for one [BypassAdStrategyMode]. */
data class BypassStrategyPolicy(
    val allowGenericCloseText: Boolean,
    val allowCloseViewId: Boolean,
    /** Explicit X / × / ✕ glyph with close semantics. */
    val allowGlyphClose: Boolean,
    /** Small text-less structural ImageView/View close candidates. */
    val allowStructuralNoSemanticClose: Boolean,
    val allowCoordinateFallback: Boolean,
    val maxExitAttempts: Int,
)

/** What kind of ad-exit control a matched node looks like. */
enum class BypassExitCandidateType {
    SKIP_TEXT,
    CLOSE_TEXT,
    CLOSE_DESC,
    CLOSE_VIEW_ID,
    /** Explicit X / × / ✕ glyph. */
    CLOSE_ICON,
    /** Small text-less structural ImageView/View (no semantics). */
    STRUCTURAL_CLOSE,
    COORDINATE_FALLBACK,
    /** Exact id in a curated app rule, with an empty/countdown-only leaf. */
    DEDICATED_EXIT,
    /** A reviewed bundled mini-program SDK ancestor layout, with bounded exit. */
    CURATED_MINI_EXIT,
    /** Locally recognized exit text, with adjacent ad label and countdown in two fresh frames. */
    LOCAL_VISUAL_EXIT,
}

/** Why a strategy candidate was rejected (debug-only timeline label). */
object BypassRejectReason {
    const val NO_AD_CONTEXT = "NO_AD_CONTEXT"
    const val TOO_LARGE = "TOO_LARGE"
    const val SENSITIVE_ACTIVITY = "SENSITIVE_ACTIVITY"
    const val OUTSIDE_WINDOW = "OUTSIDE_WINDOW"
    const val NEGATIVE_SEMANTIC = "NEGATIVE_SEMANTIC"
    const val SENSITIVE_SEMANTIC = "SENSITIVE_SEMANTIC"
    const val STRATEGY_GATE = "STRATEGY_GATE"
    const val RULE_LEVEL = "RULE_LEVEL"
    const val BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
}

/** Pure text/attribute classifier used by tests and by the engine. */
object BypassExitClassifier {

    /** Skip semantics: 跳过/跳過/Skip with short text. */
    private val skipTextTokens = listOf("跳过", "跳過", "跳 过", "跳 過")
    private val skipDescTokens = skipTextTokens
    private val closeTextTokens = listOf("关闭", "关闭广告", "关闭此广告", "关闭该广告", "關閉", "關閉廣告", "close ad", "close")
    private val closeDescTokens = listOf("关闭", "关闭广告", "關閉", "close")
    private val negativeTokens = listOf("next", "下一步", "完成", "设置", "搜索", "历史记录", "阅读并同意", "跳过片头", "跳过片尾", "跳过视频", "取消", "退出", "帮助")
    private val sensitiveTokens = listOf("支付", "付款", "确认支付", "提交订单", "转账", "验证码", "授权登录", "允许", "安装", "卸载", "同意", "银行卡", "身份认证", "password", "otp", "card", "bank")
    private val blockedTokens = negativeTokens + sensitiveTokens
    private val camelCaseBoundary = Regex("([a-z0-9])([A-Z])")
    private val exactIdSelector = Regex("(?<![\\w!])(id|vid)\\s*=\\s*\"([^\"]+)\"")
    private val countdownOnly = Regex("[0-9\\s秒sS().（）]*")
    private val shortCountdownLabel = Regex("[（(]?\\s*([0-9]{1,2})\\s*(?:秒|s)?\\s*[）)]?", RegexOption.IGNORE_CASE)
    private val exactCloseLabels = setOf("关闭", "關閉", "close")
    private val videoSkipIdTokens = listOf("video", "head", "tail")
    // Chinese ad labels match by containment; English tokens must be whole
    // words so "address"/"badge"/"read"/"adapter" never count as "ad".
    private val adLabelCjkTokens = listOf("广告", "推广")
    private val adLabelEnRegex = Regex("(?i)(?<![a-z0-9])(ad|ads|advertisement|sponsored)(?![a-z0-9])")

    /**
     * Normalize any text for semantic matching: trim + lowercase. All
     * negative/sensitive/close/skip checks run against the normalized value.
     */
    fun normalize(value: String?): String = value?.trim()?.lowercase().orEmpty()

    private fun hasBlockedSemantics(text: String?, description: String?): Boolean {
        val t = normalize(text)
        val d = normalize(description)
        return blockedTokens.any { t.contains(it) || d.contains(it) }
    }

    /** Whether the node carries an explicit ad label (ad context evidence). */
    fun hasAdLabel(text: String?, description: String?, viewId: String?): Boolean {
        val haystack = listOfNotNull(text, description, viewId).joinToString(" ")
        if (adLabelCjkTokens.any { haystack.contains(it) }) return true
        return adLabelEnRegex.containsMatchIn(haystack)
    }

    fun classify(
        text: String?,
        description: String?,
        viewId: String?,
        className: String?,
        clickable: Boolean,
        width: Int,
        height: Int,
    ): BypassExitCandidateType? {
        val t = normalize(text)
        val d = normalize(description)
        val cn = className?.trim()?.lowercase().orEmpty()

        // Negative semantics are hard gates regardless of strategy.
        if (hasBlockedSemantics(t, d)) return null

        // Skip wins: mature conservative path.
        if (skipTextTokens.any { t.contains(it) } || t.contains("skip")) {
            return BypassExitCandidateType.SKIP_TEXT
        }
        if (skipDescTokens.any { d.contains(it) } || d.contains("skip")) {
            return BypassExitCandidateType.SKIP_TEXT
        }

        // The selectors also discover SDK controls by id, even when their
        // text and description are empty. Use the resource name, excluding
        // video navigation, so the package name cannot supply skip semantics.
        val resourceId = resourceName(viewId)
        if ((resourceId.split('_').contains("skip") || resourceId == "m_jump_btn") &&
            videoSkipIdTokens.none { resourceId.contains(it) }
        ) {
            return BypassExitCandidateType.SKIP_TEXT
        }

        // Explicit close semantics by view id: ad_close / splash_close / close_btn...
        if (resourceId.isNotBlank() && (resourceId.contains("ad_close") || resourceId.contains("splash_close") ||
                resourceId.contains("close_ad") || resourceId.contains("close_btn") || resourceId.contains("close_button") ||
                resourceId.contains("close_icon") || resourceId.contains("iv_close") ||
                resourceId.contains("img_close") || resourceId.contains("closeview") || resourceId == "ad_read_close")
        ) {
            return BypassExitCandidateType.CLOSE_VIEW_ID
        }

        // Explicit close text (normalized, so "Close"/"CLOSE"/"close" all hit).
        if (closeTextTokens.any { t.contains(it) } || closeTextTokens.any { d.contains(it) }) {
            return if (t.isNotBlank()) BypassExitCandidateType.CLOSE_TEXT else BypassExitCandidateType.CLOSE_DESC
        }

        // X / × glyphs.
        if (t == "x" || t == "×" || t == "✕" || t == "✖") {
            return BypassExitCandidateType.CLOSE_ICON
        }
        if (d == "关闭" || d == "close" || d == "x" || d == "×") {
            return BypassExitCandidateType.CLOSE_DESC
        }

        // Small image/view without text inside an ad context: structural.
        if (cn.contains("imageview") || cn.contains("image") || cn == "android.view.view") {
            if (width in 1..160 && height in 1..160) {
                return BypassExitCandidateType.STRUCTURAL_CLOSE
            }
        }
        return null
    }

    data class NodeSemantics(
        val text: String? = null,
        val description: String? = null,
        val viewId: String? = null,
        val className: String? = null,
        val clickable: Boolean = false,
        val bounds: Bounds,
        val visible: Boolean = true,
        val childCount: Int = 0,
    )

    private fun NodeSemantics.classifySelf(): BypassExitCandidateType? = classify(
        text, description, viewId, className, clickable,
        bounds.r - bounds.l, bounds.b - bounds.t,
    )

    private fun NodeSemantics.canInheritSkip(): Boolean =
        clickable && visible && text.isNullOrBlank() && description.isNullOrBlank() &&
            bounds.r - bounds.l in 1..420 && bounds.b - bounds.t in 1..260 &&
            childCount in 1..8

    /** A small clickable wrapper may carry its skip label on a direct leaf. */
    fun classifyTarget(target: NodeSemantics, directChildren: List<NodeSemantics>): BypassExitCandidateType? {
        val own = target.classifySelf()
        if (own != null && own != BypassExitCandidateType.STRUCTURAL_CLOSE) return own
        if (!target.canInheritSkip()) return own
        if (directChildren.any { hasBlockedSemantics(it.text, it.description) }) return null
        // Every supplied child must belong to this bounded control. Never
        // borrow semantics from an invisible or unrelated descendant.
        val skipChild = directChildren.any { child ->
            child.visible && child.childCount == 0 &&
                child.bounds.r > child.bounds.l && child.bounds.b > child.bounds.t &&
                child.bounds.l >= target.bounds.l && child.bounds.t >= target.bounds.t &&
                child.bounds.r <= target.bounds.r && child.bounds.b <= target.bounds.b &&
                child.classifySelf() == BypassExitCandidateType.SKIP_TEXT
        }
        return if (skipChild) BypassExitCandidateType.SKIP_TEXT else own
    }

    internal fun snapshot(node: AccessibilityNodeInfo): NodeSemantics {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return NodeSemantics(
            text = node.text?.toString(),
            description = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            clickable = node.isClickable,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            visible = node.isVisibleToUser,
            childCount = node.childCount,
        )
    }

    /** A separate timer is evidence only inside the same compact exit control. */
    fun hasAdjacentCloseCountdown(
        target: NodeSemantics,
        parent: NodeSemantics,
        siblings: List<NodeSemantics>,
    ): Boolean {
        if (!target.visible || target.childCount != 0 ||
            listOf(target.text, target.description).none { normalize(it) in exactCloseLabels } ||
            hasBlockedSemantics(target.text, target.description) ||
            !parent.visible || parent.childCount !in 2..8 || siblings.size != parent.childCount ||
            parent.bounds.r - parent.bounds.l !in 1..420 ||
            parent.bounds.b - parent.bounds.t !in 1..260
        ) return false
        fun contained(node: NodeSemantics): Boolean = node.visible && node.childCount == 0 &&
            node.bounds.r > node.bounds.l && node.bounds.b > node.bounds.t &&
            node.bounds.l >= parent.bounds.l && node.bounds.t >= parent.bounds.t &&
            node.bounds.r <= parent.bounds.r && node.bounds.b <= parent.bounds.b
        fun isTimer(value: String?): Boolean = shortCountdownLabel.matchEntire(normalize(value))
            ?.groupValues?.get(1)?.toIntOrNull() in 0..30
        if (!contained(target) || siblings.none { it == target }) return false
        // A toolbar, payment prompt or larger page must not lend its timer.
        if (siblings.any { !contained(it) || hasBlockedSemantics(it.text, it.description) ||
                listOf(it.text, it.description).any { value ->
                    val label = normalize(value)
                    label.isNotEmpty() && label !in exactCloseLabels &&
                        label !in setOf("|", "｜", "·") && !isTimer(label)
                }
            }) return false
        return siblings.any { sibling ->
            if (sibling == target || listOf(sibling.text, sibling.description).none(::isTimer)) return@any false
            val a = target.bounds
            val b = sibling.bounds
            val overlap = minOf(a.b, b.b) - maxOf(a.t, b.t)
            val horizontalGap = maxOf(a.l, b.l) - minOf(a.r, b.r)
            overlap * 2 >= minOf(a.b - a.t, b.b - b.t) && horizontalGap in 0..96
        }
    }

    data class ControlTree(val node: NodeSemantics, val children: List<ControlTree> = emptyList())

    /** SDKs can mount the adjacent timer and exit in different wrapper views. */
    fun hasNearbyCloseCountdown(target: NodeSemantics, timer: NodeSemantics): Boolean {
        val targetLabels = listOf(target.text, target.description).map(::normalize).filter(String::isNotEmpty)
        val timerLabels = listOf(timer.text, timer.description).map(::normalize).filter(String::isNotEmpty)
        if (!target.visible || !timer.visible || target.childCount > 1 || timer.childCount != 0 ||
            targetLabels.isEmpty() || targetLabels.any { it !in exactCloseLabels } ||
            timerLabels.isEmpty() || timerLabels.any {
                shortCountdownLabel.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() !in 0..30
            }) return false
        val a = target.bounds
        val b = timer.bounds
        if (a.r - a.l !in 1..420 || a.b - a.t !in 1..260 ||
            b.r - b.l !in 1..220 || b.b - b.t !in 1..160 ||
            maxOf(a.r, b.r) - minOf(a.l, b.l) > 420 ||
            maxOf(a.b, b.b) - minOf(a.t, b.t) > 260) return false
        val overlap = minOf(a.b, b.b) - maxOf(a.t, b.t)
        val gap = maxOf(a.l, b.l) - minOf(a.r, b.r)
        return overlap * 2 >= minOf(a.b - a.t, b.b - b.t) && gap in 0..96
    }

    /** Wrappers are allowed, but the complete compact control must be inspectable. */
    fun hasCloseCountdownInControl(target: NodeSemantics, control: ControlTree): Boolean {
        val bounds = control.node.bounds
        if (!control.node.visible || bounds.r - bounds.l !in 1..420 || bounds.b - bounds.t !in 1..260 ||
            !target.visible || listOf(target.text, target.description).none { normalize(it) in exactCloseLabels }
        ) return false
        var visited = 0
        var containsTarget = false
        val timers = mutableListOf<NodeSemantics>()
        fun isTimer(value: String?): Boolean = shortCountdownLabel.matchEntire(normalize(value))
            ?.groupValues?.get(1)?.toIntOrNull() in 0..30
        fun visit(tree: ControlTree, depth: Int): Boolean {
            val n = tree.node
            if (++visited > 24 || depth > 2 || n.childCount != tree.children.size ||
                !n.visible || n.bounds.r <= n.bounds.l || n.bounds.b <= n.bounds.t ||
                n.bounds.l < bounds.l || n.bounds.t < bounds.t || n.bounds.r > bounds.r || n.bounds.b > bounds.b ||
                hasBlockedSemantics(n.text, n.description)
            ) return false
            if (n == target) containsTarget = true
            for (value in listOf(n.text, n.description)) {
                val label = normalize(value)
                if (label.isNotEmpty() && label !in exactCloseLabels && label !in setOf("|", "｜", "·") &&
                    !isTimer(label)) return false
            }
            if (n != target && listOf(n.text, n.description).any(::isTimer)) timers += n
            return tree.children.all { visit(it, depth + 1) }
        }
        if (!visit(control, 0) || !containsTarget) return false
        return timers.any { timer ->
            val a = target.bounds
            val b = timer.bounds
            val overlap = minOf(a.b, b.b) - maxOf(a.t, b.t)
            val gap = maxOf(a.l, b.l) - minOf(a.r, b.r)
            overlap * 2 >= minOf(a.b - a.t, b.b - b.t) && gap in 0..96
        }
    }

    fun hasAdjacentCloseCountdown(node: AccessibilityNodeInfo): Boolean = runCatching {
        val target = snapshot(node)
        if (listOf(target.text, target.description).none { normalize(it) in exactCloseLabels }) return@runCatching false
        var parent = node.parent
        repeat(2) {
            val group = parent ?: return@runCatching false
            val groupNode = snapshot(group)
            if (groupNode.bounds.r - groupNode.bounds.l > 420 || groupNode.bounds.b - groupNode.bounds.t > 260) {
                return@runCatching false
            }
            var count = 0
            fun tree(current: AccessibilityNodeInfo, depth: Int): ControlTree? {
                if (++count > 24 || depth > 2 || current.childCount > 8) return null
                val children = (0 until current.childCount).map { index ->
                    tree(current.getChild(index) ?: return null, depth + 1) ?: return null
                }
                return ControlTree(snapshot(current), children)
            }
            val control = tree(group, 0)
            if (control != null && hasCloseCountdownInControl(target, control)) return@runCatching true
            parent = group.parent
        }
        false
    }.getOrDefault(false)

    /** The host's More/Close capsule exits the mini-program, not its ad. */
    fun hasMiniNavigationSibling(target: NodeSemantics, parent: NodeSemantics, siblings: List<NodeSemantics>): Boolean {
        if (!target.visible || !parent.visible || parent.bounds.r - parent.bounds.l !in 1..420 ||
            parent.bounds.b - parent.bounds.t !in 1..260 ||
            listOf(target.text, target.description).none { normalize(it) in exactCloseLabels } ||
            siblings.none { it == target }) return false
        return siblings.any { sibling ->
            sibling != target && sibling.visible &&
                listOf(sibling.text, sibling.description).any {
                    normalize(it) in setOf("更多", "更多选项", "更多功能", "more", "more options")
                }
        }
    }

    fun isMiniProgramNavigationClose(node: AccessibilityNodeInfo): Boolean = runCatching {
        val target = snapshot(node)
        var branch = node
        repeat(3) {
            val parent = branch.parent ?: return@runCatching false
            val parentNode = snapshot(parent)
            if (parentNode.bounds.r - parentNode.bounds.l > 420 || parentNode.bounds.b - parentNode.bounds.t > 260)
                return@runCatching false
            if (parent.childCount in 2..8) {
                val siblings = (0 until parent.childCount).map { snapshot(parent.getChild(it) ?: return@runCatching false) }
                if (isMiniNavigationDescendant(target, snapshot(branch), parentNode, siblings)) return@runCatching true
            }
            branch = parent
        }
        false
    }.getOrDefault(false)

    /** A selector may target the X image inside the host's close wrapper. */
    fun isMiniNavigationDescendant(target: NodeSemantics, closeBranch: NodeSemantics,
                                   container: NodeSemantics, siblings: List<NodeSemantics>): Boolean =
        target.visible && target.bounds.r > target.bounds.l && target.bounds.b > target.bounds.t &&
            target.bounds.l >= closeBranch.bounds.l && target.bounds.t >= closeBranch.bounds.t &&
            target.bounds.r <= closeBranch.bounds.r && target.bounds.b <= closeBranch.bounds.b &&
            hasMiniNavigationSibling(closeBranch, container, siblings)

    fun classifyNode(node: AccessibilityNodeInfo): BypassExitCandidateType? {
        val target = snapshot(node)
        if (!target.canInheritSkip()) return target.classifySelf()
        val children = (0 until target.childCount).mapNotNull { index ->
            runCatching { node.getChild(index)?.let(::snapshot) }.getOrNull()
        }
        return classifyTarget(target, children)
    }

    fun resourceName(viewId: String?): String = viewId?.substringAfterLast('/')
        ?.replace(camelCaseBoundary, "$1_$2")?.lowercase().orEmpty()

    /** The selector, rather than a guessed word, identifies an SDK exit. */
    fun classifyDedicatedTarget(target: NodeSemantics, selectors: List<String>): BypassExitCandidateType? {
        if (!target.visible || target.childCount != 0 ||
            target.bounds.r - target.bounds.l !in 1..420 || target.bounds.b - target.bounds.t !in 1..260 ||
            hasBlockedSemantics(target.text, target.description) ||
            !countdownOnly.matches(target.text.orEmpty()) || !countdownOnly.matches(target.description.orEmpty())
        ) return null
        val id = target.viewId ?: return null
        val exact = selectors.any { selector ->
            exactIdSelector.findAll(selector).any { match ->
                if (match.groupValues[1] == "id") id == match.groupValues[2]
                else id.substringAfterLast('/') == match.groupValues[2]
            }
        }
        return if (exact) BypassExitCandidateType.DEDICATED_EXIT else null
    }

    fun classifyDedicatedNode(node: AccessibilityNodeInfo, selectors: List<String>): BypassExitCandidateType? =
        classifyDedicatedTarget(snapshot(node), selectors)
}

/**
 * Host apps that keep generic close/X fallback excluded.
 *
 * Single source: rules/safety_exclusions.json (asset copy
 * bypass_safety_exclusions.json) — see [BypassSafetyConfig]. The built-in
 * fallback list lives there; nothing else defines it.
 */
fun isBypassHighRiskApp(packageName: String?): Boolean =
    BypassSafetyConfig.isHighRisk(packageName)

/** Negative fallback tokens shared by the engine and tests. */
val bypassNegativeFallbackTokens = listOf(
    "next", "下一步", "完成", "设置", "搜索", "历史记录", "阅读并同意",
    "跳过片头", "跳过片尾", "跳过视频", "取消", "退出", "帮助",
)
