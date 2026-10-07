package li.songe.gkd.bypass

import org.junit.Assert.*
import org.junit.Test

class AdActionPolicyRegressionTest {
    private fun center(type: BypassExitCandidateType?, mini: Boolean = false, attempt: Int = 1,
                       action: String? = "click", custom: Boolean = false, width: Int = 120) =
        BypassAdActionPolicy.useCenter(type, mini, attempt, action, custom, width, 70)

    @Test fun mini_program_semantic_exits_use_physical_center_on_first_attempt() {
        assertTrue(center(BypassExitCandidateType.SKIP_TEXT, mini = true))
        assertTrue(center(BypassExitCandidateType.CLOSE_DESC, mini = true))
        assertTrue(center(BypassExitCandidateType.CURATED_MINI_EXIT, mini = true))
    }

    @Test fun ordinary_apps_switch_action_only_on_budgeted_fresh_retry() {
        assertFalse(center(BypassExitCandidateType.SKIP_TEXT))
        assertTrue(center(BypassExitCandidateType.SKIP_TEXT, attempt = 2))
        assertTrue(center(BypassExitCandidateType.DEDICATED_EXIT, attempt = 2))
    }

    @Test fun blank_structural_and_coordinate_guesses_gain_no_new_action_path() {
        assertFalse(center(null, mini = true, attempt = 2))
        assertFalse(center(BypassExitCandidateType.STRUCTURAL_CLOSE, mini = true, attempt = 2))
        assertFalse(center(BypassExitCandidateType.COORDINATE_FALLBACK, mini = true, attempt = 2))
        assertFalse(center(BypassExitCandidateType.SKIP_TEXT, mini = true, custom = true))
    }

    @Test fun special_actions_and_large_nodes_keep_their_existing_performer() {
        for (action in listOf("swipe", "back", "none", "longClick")) {
            assertFalse(center(BypassExitCandidateType.SKIP_TEXT, mini = true, action = action))
        }
        assertFalse(center(BypassExitCandidateType.SKIP_TEXT, mini = true, width = 421))
    }
}
