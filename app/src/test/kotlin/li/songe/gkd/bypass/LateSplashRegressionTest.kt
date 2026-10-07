package li.songe.gkd.bypass

import org.junit.Assert.*
import org.junit.Test

class LateSplashRegressionTest {
    @Test fun repeated_window_notifications_preserve_an_already_active_ad_delay() {
        for (activity in listOf("AppBrandUI00", "XRiverActivity\$App01", "QQMusicActivity")) {
            repeat(30) {
                assertFalse(BypassRuleTiming.shouldResetOnActivityEvent(true, activity, activity, true))
            }
        }
    }

    @Test fun activity_refinement_keeps_ad_delay_but_real_transitions_and_new_rules_reset() {
        assertFalse(BypassRuleTiming.shouldResetOnActivityEvent(true, null, "AppBrandUI", true))
        assertFalse(BypassRuleTiming.shouldResetOnActivityEvent(true, "AppBrandUI", null, true))
        assertTrue(BypassRuleTiming.shouldResetOnActivityEvent(true, "LauncherUI", "AppBrandUI", true))
        assertTrue(BypassRuleTiming.shouldResetOnActivityEvent(true, "AppBrandUI", "AppBrandUI", false))
    }

    @Test fun ordinary_automation_keeps_its_activity_event_reset_semantics() {
        assertTrue(BypassRuleTiming.shouldResetOnActivityEvent(false, "Main", "Main", true))
        assertTrue(BypassRuleTiming.shouldResetOnActivityEvent(false, null, "Main", true))
    }

    private fun gate(candidate: BypassExitCandidateType, mode: BypassAdStrategyMode,
                     context: BypassAdContextLevel = BypassAdContextLevel.STRONG,
                     host: String = "com.tencent.qqmusic",
                     trust: BypassRuleTrust = BypassRuleTrust.BYPASS_OVERRIDE,
                     coordinate: Boolean = false): String? = BypassStrategyGate.evaluateExecution(
        candidate, host, "MainActivity", 120, 60, mode.policy,
        BypassRulePolicy(trust, BypassAdStrategyMode.CONSERVATIVE, true, coordinate, 3),
        context, inWindow = false,
    )

    @Test fun matching_starts_immediately_and_bypass_does_not_expire() {
        for (elapsed in listOf(0L, 9000L, 11000L, 60000L, 3600000L)) {
            assertFalse(BypassRuleTiming.matchExpired(true, elapsed, 10000, 0))
        }
        assertFalse(BypassRuleTiming.matchExpired(false, 0, 10000, 0))
        assertTrue(BypassRuleTiming.matchExpired(false, 11000, 10000, 0))
        assertFalse(BypassRuleTiming.matchExpired(false, 11000, 10000, 2000))
    }

    @Test fun late_and_warm_return_skip_works_in_every_mode_and_host() {
        for (host in listOf("com.tencent.qqmusic", "com.tencent.mm", "com.eg.android.AlipayGphone")) {
            for (mode in BypassAdStrategyMode.entries) {
                assertNull(gate(BypassExitCandidateType.SKIP_TEXT, mode, BypassAdContextLevel.NONE, host))
            }
        }
    }

    @Test fun late_close_still_requires_mode_and_current_ad_evidence() {
        for (candidate in listOf(BypassExitCandidateType.CLOSE_TEXT, BypassExitCandidateType.CLOSE_VIEW_ID,
            BypassExitCandidateType.CLOSE_DESC, BypassExitCandidateType.CLOSE_ICON)) {
            assertEquals(BypassRejectReason.STRATEGY_GATE, gate(candidate, BypassAdStrategyMode.CONSERVATIVE))
            for (mode in listOf(BypassAdStrategyMode.AGGRESSIVE, BypassAdStrategyMode.CRAZY)) {
                assertNull(gate(candidate, mode))
                assertEquals(BypassRejectReason.OUTSIDE_WINDOW, gate(candidate, mode, BypassAdContextLevel.WEAK))
            }
        }
    }

    @Test fun late_structure_and_coordinates_remain_restricted() {
        assertEquals(BypassRejectReason.OUTSIDE_WINDOW,
            gate(BypassExitCandidateType.STRUCTURAL_CLOSE, BypassAdStrategyMode.CRAZY))
        assertEquals(BypassRejectReason.OUTSIDE_WINDOW,
            gate(BypassExitCandidateType.COORDINATE_FALLBACK, BypassAdStrategyMode.CRAZY))
        assertEquals(BypassRejectReason.STRATEGY_GATE, gate(BypassExitCandidateType.SKIP_TEXT,
            BypassAdStrategyMode.CONSERVATIVE, trust = BypassRuleTrust.BUNDLED_DEDICATED, coordinate = true))
    }

    @Test fun unkeyed_rules_and_global_app_key_collisions_verify_separately() {
        val evidence = BypassSessionAdEvidence(BypassExitCandidateType.SKIP_TEXT, null, 1,
            "800,60,920,120", 2, "com.tencent.qqmusic", true)
        assertTrue(evidence.belongsToGroup("com.tencent.qqmusic", 1))
        assertFalse(evidence.belongsToGroup(null, 1))
        assertFalse(evidence.belongsToGroup("com.other.app", 1))
        assertFalse(evidence.belongsToGroup("com.tencent.qqmusic", 2))
        assertTrue(evidence.isActedRule(2, null))
        assertFalse(evidence.isActedRule(1, null))
    }

    private val opaque = BypassExitClassifier.NodeSemantics(viewId = "com.example:id/sdk_timer",
        text = "5s", bounds = Bounds(800, 60, 920, 120))

    @Test fun precise_mature_sdk_rules_can_identify_countdown_only_exits() {
        assertEquals(BypassExitCandidateType.DEDICATED_EXIT,
            BypassExitClassifier.classifyDedicatedTarget(opaque, listOf("[vid=\"sdk_timer\"]")))
        assertEquals(BypassExitCandidateType.DEDICATED_EXIT,
            BypassExitClassifier.classifyDedicatedTarget(opaque, listOf("[id=\"com.example:id/sdk_timer\"]")))
        assertNull(gate(BypassExitCandidateType.DEDICATED_EXIT, BypassAdStrategyMode.CONSERVATIVE,
            trust = BypassRuleTrust.BUNDLED_DEDICATED))
        for (trust in listOf(BypassRuleTrust.BUNDLED_GLOBAL, BypassRuleTrust.BYPASS_OVERRIDE,
            BypassRuleTrust.LOCAL_IMPORT_DEDICATED, BypassRuleTrust.TEACH_NODE)) {
            assertNotNull(gate(BypassExitCandidateType.DEDICATED_EXIT, BypassAdStrategyMode.CRAZY, trust = trust))
        }
        assertNotNull(gate(BypassExitCandidateType.DEDICATED_EXIT, BypassAdStrategyMode.CRAZY,
            host = "com.tencent.mm", trust = BypassRuleTrust.BUNDLED_DEDICATED))
    }

    @Test fun dedicated_matching_never_promotes_negative_sensitive_or_broad_targets() {
        val selectors = listOf("[vid=\"sdk_timer\"]")
        for (text in listOf("下一步", "跳过并允许安装", "确认支付", "PAYMENT", "正常内容")) {
            assertNull(BypassExitClassifier.classifyDedicatedTarget(opaque.copy(text = text), selectors))
        }
        for (target in listOf(opaque.copy(visible = false), opaque.copy(childCount = 1),
            opaque.copy(bounds = Bounds(0,0,1080,2400)))) {
            assertNull(BypassExitClassifier.classifyDedicatedTarget(target, selectors))
        }
        for (selector in listOf("[vid!=\"sdk_timer\"]", "[vid~=\"sdk_.*\"]", "[vid=\"other\"]")) {
            assertNull(BypassExitClassifier.classifyDedicatedTarget(opaque, listOf(selector)))
        }
    }
}
