package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplashCandidateRegressionTest {
    private fun classify(text: String? = null, description: String? = null, id: String? = null) =
        BypassExitClassifier.classify(text, description, id, "android.widget.TextView", true, 120, 60)

    @Test
    fun countdown_skip_description_is_recognized() {
        for (description in listOf("Skip 5", "SKIP", "跳 过 5", "跳 過 5")) {
            assertEquals(description, BypassExitCandidateType.SKIP_TEXT, classify(description = description))
        }
    }

    @Test
    fun textless_sdk_skip_ids_are_recognized() {
        for (id in listOf("tt_splash_skip_btn", "btn_skip", "splashSkipBtn", "skip", "mJumpBtn")) {
            assertEquals(id, BypassExitCandidateType.SKIP_TEXT, classify(id = "com.example:id/$id"))
        }
    }

    @Test
    fun jd_reader_close_ids_are_recognized() {
        for (id in listOf("mCloseBtn", "ad_read_close")) {
            assertEquals(BypassExitCandidateType.CLOSE_VIEW_ID, classify(id = "com.jd.app.reader:id/$id"))
        }
    }

    @Test
    fun video_navigation_and_package_names_do_not_supply_skip_semantics() {
        for (id in listOf("video_skip", "skip_head", "skip_tail", "skipper", "close")) {
            assertNull(id, classify(id = "com.skip.app:id/$id"))
        }
    }

    @Test
    fun skip_id_does_not_override_negative_or_sensitive_text() {
        for (text in listOf("下一步", "跳过视频", "确认支付", "允许安装")) {
            assertNull(text, classify(text = text, id = "com.example:id/skip_btn"))
        }
    }

    private val wrapper = BypassExitClassifier.NodeSemantics(
        className = "android.widget.FrameLayout", clickable = true,
        bounds = Bounds(800, 60, 960, 140), childCount = 1,
    )
    private val skipLeaf = BypassExitClassifier.NodeSemantics(
        text = "跳过 5", className = "android.widget.TextView",
        bounds = Bounds(810, 70, 950, 130),
    )

    @Test
    fun clickable_wrapper_inherits_its_direct_skip_label() {
        assertEquals(BypassExitCandidateType.SKIP_TEXT, BypassExitClassifier.classifyTarget(wrapper, listOf(skipLeaf)))
        assertEquals(BypassExitCandidateType.SKIP_TEXT, BypassExitClassifier.classifyTarget(
            wrapper.copy(className = "android.view.View"), listOf(skipLeaf),
        ))
    }

    @Test
    fun invisible_outside_or_nested_labels_do_not_classify_wrapper() {
        for (child in listOf(
            skipLeaf.copy(visible = false),
            skipLeaf.copy(bounds = Bounds(20, 800, 160, 860)),
            skipLeaf.copy(childCount = 1),
        )) {
            assertNull(BypassExitClassifier.classifyTarget(wrapper, listOf(child)))
        }
    }

    @Test
    fun large_invisible_nonclickable_or_sensitive_parents_do_not_inherit_skip() {
        for (parent in listOf(
            wrapper.copy(bounds = Bounds(0, 0, 1080, 2400)),
            wrapper.copy(visible = false),
            wrapper.copy(clickable = false),
            wrapper.copy(text = "确认支付"),
            wrapper.copy(description = "下一步"),
            wrapper.copy(childCount = 20),
        )) {
            assertNull(BypassExitClassifier.classifyTarget(parent, listOf(skipLeaf)))
        }
    }

    @Test
    fun negative_or_sensitive_leaf_does_not_classify_wrapper() {
        for (text in listOf("跳过视频", "跳过并允许安装", "跳过确认支付")) {
            assertNull(BypassExitClassifier.classifyTarget(wrapper, listOf(skipLeaf.copy(text = text))))
        }
        assertNull(BypassExitClassifier.classifyTarget(
            wrapper.copy(childCount = 2), listOf(skipLeaf, skipLeaf.copy(text = "确认支付")),
        ))
    }

    @Test
    fun inherited_skip_is_allowed_by_the_production_gate_in_all_modes() {
        val candidate = BypassExitClassifier.classifyTarget(wrapper, listOf(skipLeaf))
        val rulePolicy = BypassRulePolicy(
            trust = BypassRuleTrust.BUNDLED_GLOBAL,
            minimumMode = BypassAdStrategyMode.CONSERVATIVE,
            requiresStrongAdContext = true, coordinate = false, maxAttempts = 3,
        )
        for (mode in BypassAdStrategyMode.entries) {
            assertNull(mode.name, BypassStrategyGate.evaluateExecution(
                candidate, "com.example.app", "Splash", 160, 80,
                mode.policy, rulePolicy, BypassAdContextLevel.NONE, inWindow = true,
            ))
        }
    }

    @Test
    fun first_ad_close_match_is_strong_without_previously_recorded_evidence() {
        BypassAdContextTracker.clearForTest()
        try {
            for (id in listOf("ad_close", "splash_close_btn", "close_ad")) {
                assertEquals(BypassAdContextLevel.STRONG, BypassAdContextTracker.evaluateCandidateContext(
                    "com.example.app", "Splash", root = null, candidateBounds = "800,60,960,140",
                    candidateType = BypassExitCandidateType.CLOSE_VIEW_ID,
                    candidateViewId = "com.example:id/$id", now = 1000L,
                ))
            }
            // Observing the candidate alone does not persist evidence to
            // unrelated controls or later windows.
            assertEquals(BypassAdContextLevel.NONE, BypassAdContextTracker.evaluate("com.example.app", "Splash", 1000L))
        } finally {
            BypassAdContextTracker.clearForTest()
        }
    }

    @Test
    fun ordinary_close_id_and_structural_nodes_do_not_create_ad_evidence() {
        BypassAdContextTracker.clearForTest()
        try {
            for ((type, id) in listOf(
                BypassExitCandidateType.CLOSE_VIEW_ID to "iv_close",
                BypassExitCandidateType.CLOSE_VIEW_ID to "com.ad_close.app:id/iv_close",
                BypassExitCandidateType.STRUCTURAL_CLOSE to "ad_close",
                BypassExitCandidateType.CLOSE_TEXT to "close_btn",
            )) {
                assertEquals(BypassAdContextLevel.NONE, BypassAdContextTracker.evaluateCandidateContext(
                    "com.example.app", "Splash", root = null, candidateBounds = "800,60,960,140",
                    candidateType = type, candidateViewId = id, now = 1000L,
                ))
            }
        } finally {
            BypassAdContextTracker.clearForTest()
        }
    }
}
