package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniProgramCountdownRegressionTest {
    private val close = BypassExitClassifier.NodeSemantics(
        text = "关闭", className = "android.widget.TextView",
        bounds = Bounds(930, 70, 1020, 130),
    )
    private val timer = close.copy(text = "5s", bounds = Bounds(850, 70, 920, 130))
    private val parent = close.copy(text = null, childCount = 2, bounds = Bounds(840, 60, 1030, 140))

    private fun evidence(target: BypassExitClassifier.NodeSemantics = close,
                         countdown: BypassExitClassifier.NodeSemantics = timer,
                         container: BypassExitClassifier.NodeSemantics = parent) =
        BypassExitClassifier.hasAdjacentCloseCountdown(target, container, listOf(countdown, target))

    @Test
    fun separate_visible_timer_in_same_small_control_is_evidence() {
        for (label in listOf("5s", "5秒", "5", "(5s)", "（ 5 秒 ）", "0s")) {
            assertTrue(label, evidence(countdown = timer.copy(text = label)))
        }
        assertTrue(evidence(target = close.copy(text = null, description = "关闭")))
        assertTrue(evidence(countdown = timer.copy(text = null, description = "5秒")))
    }

    @Test
    fun toolbar_and_distant_or_invisible_timer_are_rejected() {
        assertFalse(evidence(container = parent.copy(bounds = Bounds(0, 0, 1080, 150))))
        assertFalse(evidence(countdown = timer.copy(visible = false)))
        assertFalse(evidence(countdown = timer.copy(bounds = Bounds(850, 800, 920, 860))))
        assertFalse(evidence(countdown = timer.copy(bounds = Bounds(680, 70, 740, 130)),
            container = parent.copy(bounds = Bounds(670, 60, 1030, 140))))
        assertFalse(evidence(countdown = timer.copy(childCount = 1)))
        assertFalse(evidence(container = parent.copy(childCount = 3)))
        assertFalse(evidence(target = close.copy(visible = false)))
    }

    @Test
    fun ordinary_numbers_payment_and_long_countdown_do_not_supply_evidence() {
        for (label in listOf("支付 5秒", "验证码5秒", "￥5", "5分钟", "60秒", "500", "已充电5秒")) {
            assertFalse(label, evidence(countdown = timer.copy(text = label)))
        }
        assertFalse(evidence(target = close.copy(description = "确认支付")))
        assertFalse(evidence(target = close.copy(text = "关闭订单")))
    }

    @Test
    fun wrapped_buttons_and_timer_share_one_complete_compact_control() {
        val closeWrapper = close.copy(text = null, childCount = 1)
        val timerWrapper = timer.copy(text = null, childCount = 1)
        val control = BypassExitClassifier.ControlTree(parent, listOf(
            BypassExitClassifier.ControlTree(timerWrapper, listOf(BypassExitClassifier.ControlTree(timer))),
            BypassExitClassifier.ControlTree(closeWrapper, listOf(BypassExitClassifier.ControlTree(close))),
        ))
        assertTrue(BypassExitClassifier.hasCloseCountdownInControl(close, control))
        assertFalse(BypassExitClassifier.hasCloseCountdownInControl(close, control.copy(
            node = parent.copy(bounds = Bounds(0, 0, 1080, 150)),
        )))
        assertFalse(BypassExitClassifier.hasCloseCountdownInControl(close, control.copy(
            children = control.children.map { it.copy(children = emptyList()) },
        )))
        val toolbar = control.copy(children = control.children.map {
            if (it.node == timerWrapper) it.copy(node = timerWrapper.copy(description = "更多")) else it
        })
        assertFalse(BypassExitClassifier.hasCloseCountdownInControl(close, toolbar))
    }

    @Test
    fun countdown_evidence_is_limited_to_semantic_close_in_mini_program_hosts() {
        BypassAdContextTracker.clearForTest()
        try {
            for ((pkg, activity) in listOf(
                "com.tencent.mm" to "com.tencent.mm.plugin.appbrand.ui.AppBrandUI01",
                "com.tencent.mm" to "com.tencent.mm.plugin.appbrand.ui.AppBrandPluginUI",
                "com.eg.android.AlipayGphone" to "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity",
            )) {
                assertEquals(BypassAdContextLevel.STRONG, BypassAdContextTracker.evaluateCandidateContext(
                    pkg, activity, null, "930,70,1020,130", BypassExitCandidateType.CLOSE_TEXT,
                    now = 1000L, candidateText = "关闭", candidateHasAdjacentCountdown = evidence(),
                ))
                assertEquals(BypassAdContextLevel.WEAK, BypassAdContextTracker.evaluateCandidateContext(
                    pkg, activity, null, "930,70,1020,130", BypassExitCandidateType.CLOSE_TEXT,
                    now = 1000L, candidateText = "关闭",
                ))
            }
            for ((pkg, activity, type) in listOf(
                Triple("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", BypassExitCandidateType.CLOSE_TEXT),
                Triple("com.example.app", "Splash", BypassExitCandidateType.CLOSE_TEXT),
                Triple("com.tencent.mm", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI", BypassExitCandidateType.STRUCTURAL_CLOSE),
            )) {
                assertFalse(BypassAdContextLevel.STRONG == BypassAdContextTracker.evaluateCandidateContext(
                    pkg, activity, null, "930,70,1020,130", type, now = 1000L,
                    candidateText = "关闭", candidateHasAdjacentCountdown = true,
                ))
            }
        } finally {
            BypassAdContextTracker.clearForTest()
        }
    }
    @Test fun native_more_close_capsule_is_navigation_even_beside_page_ads() {
        val close = BypassExitClassifier.NodeSemantics(description = "关闭", className = "android.widget.FrameLayout",
            clickable = true, childCount = 1, bounds = Bounds(1053, 171, 1175, 261))
        val more = BypassExitClassifier.NodeSemantics(description = "更多", className = "android.widget.FrameLayout",
            clickable = true, childCount = 1, bounds = Bounds(930, 171, 1052, 261))
        val parent = BypassExitClassifier.NodeSemantics(childCount = 2, bounds = Bounds(930, 171, 1176, 261))
        assertTrue(BypassExitClassifier.hasMiniNavigationSibling(close, parent, listOf(more, close)))
        assertFalse(BypassExitClassifier.hasMiniNavigationSibling(close, parent,
            listOf(more.copy(description = "5秒"), close)))
    }

    @Test fun blank_or_glyph_icon_inside_native_close_is_also_navigation() {
        val close = BypassExitClassifier.NodeSemantics(description = "关闭", childCount = 1,
            bounds = Bounds(1053, 171, 1175, 261))
        val more = close.copy(description = "更多", bounds = Bounds(930, 171, 1052, 261))
        val capsule = close.copy(description = null, childCount = 2, bounds = Bounds(930, 171, 1176, 261))
        val icon = close.copy(description = null, childCount = 0, className = "android.widget.ImageView",
            bounds = Bounds(1080, 190, 1140, 250))
        for (label in listOf(null, "×", "x")) {
            assertTrue(BypassExitClassifier.isMiniNavigationDescendant(icon.copy(text = label), close,
                capsule, listOf(more, close)))
        }
        assertFalse(BypassExitClassifier.isMiniNavigationDescendant(icon.copy(bounds = Bounds(840, 190, 900, 250)),
            close, capsule, listOf(more, close)))
        assertFalse(BypassExitClassifier.isMiniNavigationDescendant(icon, close,
            capsule, listOf(more.copy(description = "5秒"), close)))
    }

    @Test fun nearby_timer_can_have_a_different_parent_but_must_be_exact_and_aligned() {
        val leftClose = close.copy(bounds = Bounds(60, 180, 190, 255))
        val beside = timer.copy(bounds = Bounds(210, 180, 300, 255))
        assertTrue(BypassExitClassifier.hasNearbyCloseCountdown(leftClose, beside))
        for (label in listOf("支付5秒", "验证码5秒", "已充电5秒", "60秒", "￥5")) {
            assertFalse(label, BypassExitClassifier.hasNearbyCloseCountdown(leftClose, beside.copy(text = label)))
        }
        assertFalse(BypassExitClassifier.hasNearbyCloseCountdown(leftClose, beside.copy(visible = false)))
        assertFalse(BypassExitClassifier.hasNearbyCloseCountdown(leftClose, beside.copy(childCount = 1)))
        assertFalse(BypassExitClassifier.hasNearbyCloseCountdown(leftClose,
            beside.copy(bounds = Bounds(210, 500, 300, 575))))
        assertFalse(BypassExitClassifier.hasNearbyCloseCountdown(leftClose,
            beside.copy(bounds = Bounds(330,180,420,255))))
        assertFalse(BypassExitClassifier.hasNearbyCloseCountdown(leftClose.copy(description = "关闭订单"), beside))
    }

}
