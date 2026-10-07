package li.songe.gkd.bypass

import org.junit.Assert.*
import org.junit.Test

class MiniProgramTargetRegressionTest {
    private val window = Bounds(0, 0, 1240, 2772)
    private val close = Bounds(80, 190, 250, 275)

    @Test fun left_top_exit_is_eligible_but_fixed_right_exit_is_excluded() {
        for (candidate in listOf(BypassExitCandidateType.CLOSE_TEXT, BypassExitCandidateType.CLOSE_DESC,
            BypassExitCandidateType.CLOSE_ICON, BypassExitCandidateType.STRUCTURAL_CLOSE, null)) {
            assertTrue(BypassMiniProgramTargets.allowsTarget(candidate, close, window, false))
            assertFalse(BypassMiniProgramTargets.allowsTarget(candidate, Bounds(1053,171,1175,261), window, false))
            assertFalse(BypassMiniProgramTargets.allowsTarget(candidate, close, window, true))
        }
    }

    @Test fun control_region_uses_actual_window_and_rejects_bottom_and_large_targets() {
        val shifted = Bounds(400, 100, 1640, 2872)
        assertTrue(BypassMiniProgramTargets.allowsTarget(BypassExitCandidateType.CLOSE_TEXT,
            Bounds(480, 290, 650, 375), shifted, false))
        for (bounds in listOf(Bounds(80, 1900, 250, 2000), Bounds(-10,190,100,270), window)) {
            assertFalse(BypassMiniProgramTargets.allowsTarget(BypassExitCandidateType.CLOSE_TEXT, bounds, window, false))
        }
        assertTrue(BypassMiniProgramTargets.allowsTarget(BypassExitCandidateType.SKIP_TEXT,
            Bounds(1000,190,1160,270), window, false))
    }

    @Test fun earlier_navigation_match_does_not_shadow_later_left_ad_exit() {
        val right = Bounds(1053,171,1175,261)
        val matches = listOf(right, close)
        val chosen = firstAcceptedQueryTarget(right, { matches.asSequence() }) {
            BypassMiniProgramTargets.allowsTarget(BypassExitCandidateType.CLOSE_DESC, it, window, it == right)
        }
        assertEquals(close, chosen)
        assertEquals(right, firstAcceptedQueryTarget(right, { matches.asSequence() }, null))
        assertNull(firstAcceptedQueryTarget(right, { sequenceOf(right) }) { false })
    }

    @Test fun left_location_does_not_waive_mode_or_ad_evidence() {
        val policy = BypassRulePolicy(BypassRuleTrust.BYPASS_OVERRIDE, BypassAdStrategyMode.AGGRESSIVE, true, false, 2)
        assertTrue(BypassMiniProgramTargets.allowsTarget(BypassExitCandidateType.CLOSE_DESC, close, window, false))
        assertEquals(BypassRejectReason.NO_AD_CONTEXT, BypassStrategyGate.evaluateExecution(BypassExitCandidateType.CLOSE_DESC,
            "com.tencent.mm", ".plugin.appbrand.ui.AppBrandUI", 170, 85, BypassAdStrategyMode.AGGRESSIVE.policy,
            policy, BypassAdContextLevel.NONE, true))
        assertEquals(BypassRejectReason.STRATEGY_GATE, BypassStrategyGate.evaluateExecution(BypassExitCandidateType.CLOSE_DESC,
            "com.tencent.mm", ".plugin.appbrand.ui.AppBrandUI", 170, 85, BypassAdStrategyMode.CONSERVATIVE.policy,
            policy, BypassAdContextLevel.STRONG, true))
    }
}
