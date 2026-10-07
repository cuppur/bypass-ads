package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Strategy-gate matrix V2 on pure logic. Mirrors the testad scene matrix so
 * the same expectations run in both JVM tests and on-device ad scenes.
 *
 * V2 differences from R6.2:
 *  - AGGRESSIVE allows glyph X but NOT structural no-semantic close.
 *  - Only CRAZY allows structural close and coordinate fallback.
 *  - Generic close/glyph/structural candidates need STRONG ad context.
 */
class BypassStrategyMatrixTest {

    private fun policyFor(ordinal: Int) = BypassAdStrategyMode.from(ordinal).policy

    /** Generic fallback gate with STRONG context (post-entry window). The
     * candidate is passed through unchanged — a null candidate is a HARD
     * denial (P0-1: nothing is ever faked as SKIP_TEXT). */
    private fun gate(
        candidate: BypassExitCandidateType?,
        p: BypassStrategyPolicy,
        w: Int,
        h: Int,
        context: BypassAdContextLevel = BypassAdContextLevel.STRONG,
        trust: BypassRuleTrust = BypassRuleTrust.BUNDLED_GLOBAL,
        inWindow: Boolean = true,
    ): String? = BypassStrategyGate.evaluateExecution(
        candidate = candidate,
        packageName = "com.example.app",
        activityName = "com.example.MainActivity",
        nodeWidth = w,
        nodeHeight = h,
        policy = p,
        rulePolicy = BypassRulePolicy(
            trust = trust,
            minimumMode = BypassAdStrategyMode.CONSERVATIVE,
            requiresStrongAdContext = true,
            coordinate = false,
            maxAttempts = 3,
        ),
        contextLevel = context,
        inWindow = inWindow,
    )

    @Test
    fun conservative_only_allows_skip() {
        val p = policyFor(0)
        assertNull("skip should be allowed", gate(BypassExitCandidateType.SKIP_TEXT, p, 80, 40))
        assertNotNull("close text rejected in conservative", gateCloseText(p))
        assertNotNull("close view id rejected in conservative", gateCloseViewId(p))
        assertNotNull("glyph X rejected in conservative", gateCloseIcon(p))
        assertNotNull("structural rejected in conservative", gateStructural(p))
        assertNotNull("coordinate rejected in conservative", gateCoordinate(p))
    }

    @Test
    fun aggressive_allows_close_viewid_and_glyph_but_not_structural_or_coordinate() {
        val p = policyFor(1)
        assertNull(gateCloseText(p))
        assertNull(gateCloseViewId(p))
        assertNull("glyph X allowed in aggressive", gateCloseIcon(p))
        assertNotNull("structural no-semantic still rejected in aggressive", gateStructural(p))
        assertNotNull("coordinate still rejected in aggressive", gateCoordinate(p))
    }

    @Test
    fun crazy_allows_everything_within_strong_context() {
        val p = policyFor(2)
        assertNull(gateCloseText(p))
        assertNull(gateCloseViewId(p))
        assertNull(gateCloseIcon(p))
        assertNull("structural allowed in crazy", gateStructural(p))
        assertNull("coordinate allowed in crazy", gateCoordinate(p))
    }

    @Test
    fun generic_close_requires_strong_ad_context_in_aggressive_and_crazy() {
        for (ordinal in 1..2) {
            val p = policyFor(ordinal)
            assertEquals(
                BypassRejectReason.NO_AD_CONTEXT,
                gate(BypassExitCandidateType.CLOSE_TEXT, p, 80, 40, context = BypassAdContextLevel.WEAK),
            )
            assertEquals(
                BypassRejectReason.NO_AD_CONTEXT,
                gate(BypassExitCandidateType.CLOSE_ICON, p, 40, 40, context = BypassAdContextLevel.NONE),
            )
        }
    }

    @Test
    fun out_of_window_allows_confirmed_ad_close() {
        val p = policyFor(1)
        assertNull(
            gate(BypassExitCandidateType.CLOSE_TEXT, p, 80, 40, inWindow = false),
        )
    }

    @Test
    fun oversized_candidate_rejected_in_every_mode() {
        for (ordinal in 0..2) {
            val p = policyFor(ordinal)
            assertEquals(BypassRejectReason.TOO_LARGE, gate(BypassExitCandidateType.SKIP_TEXT, p, 520, 320))
        }
    }

    @Test
    fun mature_dedicated_rules_run_ungated_in_every_mode() {
        // A mature dedicated close rule is curated: conservative runs it too.
        for (ordinal in 0..2) {
            val p = policyFor(ordinal)
            assertNull(
                BypassStrategyGate.rejectReason(
                    candidate = BypassExitCandidateType.CLOSE_TEXT,
                    packageName = "com.example.app",
                    activityName = "com.example.MainActivity",
                    nodeWidth = 80,
                    nodeHeight = 40,
                    policy = p,
                    rulePolicy = BypassRulePolicy(
                        trust = BypassRuleTrust.BUNDLED_DEDICATED,
                        minimumMode = BypassAdStrategyMode.CONSERVATIVE,
                        requiresStrongAdContext = false,
                        coordinate = false,
                        maxAttempts = 3,
                    ),
                    contextLevel = BypassAdContextLevel.NONE,
                    inWindow = false,
                ),
            )
        }
    }

    @Test
    fun high_risk_app_blocks_untrusted_sources_but_exempt_sources_keep_gating() {
        for (ordinal in 0..2) {
            val p = policyFor(ordinal)
            // Untrusted source on a high-risk host is always denied, even
            // with STRONG context and inside the window.
            assertEquals(
                BypassRejectReason.SENSITIVE_ACTIVITY,
                BypassStrategyGate.rejectReason(
                    candidate = BypassExitCandidateType.CLOSE_TEXT,
                    packageName = "com.eg.android.AlipayGphone",
                    activityName = "com.alipay.XRiverActivity",
                    nodeWidth = 80,
                    nodeHeight = 40,
                    policy = p,
                    rulePolicy = BypassRulePolicy(
                        trust = BypassRuleTrust.LOCAL_IMPORT_GLOBAL,
                        minimumMode = BypassAdStrategyMode.AGGRESSIVE,
                        requiresStrongAdContext = true,
                        coordinate = false,
                        maxAttempts = 3,
                    ),
                    contextLevel = BypassAdContextLevel.STRONG,
                    inWindow = true,
                ),
            )
            // Exempt source (curated dedicated) is NOT allowed to bypass the
            // window check on a high-risk host: OUTSIDE_WINDOW still denies.
            assertEquals(
                BypassRejectReason.OUTSIDE_WINDOW,
                BypassStrategyGate.rejectReason(
                    candidate = BypassExitCandidateType.CLOSE_TEXT,
                    packageName = "com.eg.android.AlipayGphone",
                    activityName = "com.alipay.XRiverActivity",
                    nodeWidth = 80,
                    nodeHeight = 40,
                    policy = p,
                    rulePolicy = BypassRulePolicy(
                        trust = BypassRuleTrust.BUNDLED_DEDICATED,
                        minimumMode = BypassAdStrategyMode.CONSERVATIVE,
                        requiresStrongAdContext = false,
                        coordinate = false,
                        maxAttempts = 3,
                    ),
                    contextLevel = BypassAdContextLevel.NONE,
                    inWindow = false,
                ),
            )
        }
    }

    @Test
    fun wechat_override_outside_window_is_allowed() {
        // Late mini-program close is allowed only with current STRONG evidence.
        assertNull(
            wechatOverrideGate(inWindow = false, context = BypassAdContextLevel.STRONG),
        )
    }

    @Test
    fun wechat_override_without_strong_context_is_no() {
        // P0-3 acceptance: 微信 override + context NONE => NO.
        assertEquals(
            BypassRejectReason.NO_AD_CONTEXT,
            wechatOverrideGate(inWindow = true, context = BypassAdContextLevel.NONE),
        )
        assertEquals(
            BypassRejectReason.NO_AD_CONTEXT,
            wechatOverrideGate(inWindow = true, context = BypassAdContextLevel.WEAK),
        )
    }

    @Test
    fun wechat_override_strong_in_window_is_yes() {
        // P0-3 acceptance: 微信 override + STRONG + inWindow => YES.
        assertNull(
            wechatOverrideGate(inWindow = true, context = BypassAdContextLevel.STRONG),
        )
    }

    @Test
    fun wechat_override_close_in_conservative_is_strategy_gated() {
        // The override may only waive "untrusted source": the strategy gate
        // still applies, so a generic CLOSE_TEXT override cannot act in the
        // CONSERVATIVE mode even on WeChat with STRONG + inWindow.
        assertEquals(
            BypassRejectReason.STRATEGY_GATE,
            BypassStrategyGate.rejectReason(
                candidate = BypassExitCandidateType.CLOSE_TEXT,
                packageName = "com.tencent.mm",
                activityName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                nodeWidth = 80,
                nodeHeight = 40,
                policy = BypassAdStrategyMode.CONSERVATIVE.policy,
                rulePolicy = BypassRulePolicy(
                    trust = BypassRuleTrust.BYPASS_OVERRIDE,
                    minimumMode = BypassAdStrategyMode.AGGRESSIVE,
                    requiresStrongAdContext = true,
                    coordinate = false,
                    maxAttempts = 3,
                ),
                contextLevel = BypassAdContextLevel.STRONG,
                inWindow = true,
            ),
        )
    }

    private fun wechatOverrideGate(inWindow: Boolean, context: BypassAdContextLevel): String? =
        BypassStrategyGate.evaluateExecution(
            candidate = BypassExitCandidateType.CLOSE_TEXT,
            packageName = "com.tencent.mm",
            activityName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            nodeWidth = 80,
            nodeHeight = 40,
            policy = BypassAdStrategyMode.AGGRESSIVE.policy,
            rulePolicy = BypassRulePolicy(
                trust = BypassRuleTrust.BYPASS_OVERRIDE,
                minimumMode = BypassAdStrategyMode.AGGRESSIVE,
                requiresStrongAdContext = true,
                coordinate = false,
                maxAttempts = 3,
            ),
            contextLevel = context,
            inWindow = inWindow,
        )

    // ---- P0-1 production-path gate: the engine and these tests call the
    // ---- exact same BypassStrategyGate.evaluateExecution ----

    /** The engine's gate with a fully specified request (production path). */
    private fun prodGate(
        candidate: BypassExitCandidateType?,
        packageName: String,
        trust: BypassRuleTrust,
        mode: BypassAdStrategyMode = BypassAdStrategyMode.AGGRESSIVE,
        requiresStrongAdContext: Boolean = true,
        context: BypassAdContextLevel = BypassAdContextLevel.STRONG,
        inWindow: Boolean = true,
        w: Int = 80,
        h: Int = 40,
    ): String? = BypassStrategyGate.evaluateExecution(
        candidate = candidate,
        packageName = packageName,
        activityName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
        nodeWidth = w,
        nodeHeight = h,
        policy = mode.policy,
        rulePolicy = BypassRulePolicy(
            trust = trust,
            minimumMode = BypassAdStrategyMode.CONSERVATIVE,
            requiresStrongAdContext = requiresStrongAdContext,
            coordinate = false,
            maxAttempts = 3,
        ),
        contextLevel = context,
        inWindow = inWindow,
    )

    @Test
    fun high_risk_bundled_dedicated_outside_window_is_allowed() {
        // Curated late close still passes strategy and current ad evidence checks.
        assertNull(
            prodGate(
                candidate = BypassExitCandidateType.CLOSE_TEXT,
                packageName = "com.tencent.mm",
                trust = BypassRuleTrust.BUNDLED_DEDICATED,
                requiresStrongAdContext = false,
                inWindow = false,
            ),
        )
    }

    @Test
    fun high_risk_bundled_dedicated_null_candidate_is_no() {
        // Acceptance: high-risk host + BUNDLED_DEDICATED + candidate=null => NO.
        // No origin may act without a real semantic candidate (P0-1: no
        // SKIP_TEXT masquerade, no dedicated bypass for a fake candidate).
        assertEquals(
            BypassRejectReason.NEGATIVE_SEMANTIC,
            prodGate(
                candidate = null,
                packageName = "com.tencent.mm",
                trust = BypassRuleTrust.BUNDLED_DEDICATED,
                requiresStrongAdContext = false,
                inWindow = true,
            ),
        )
    }

    @Test
    fun high_risk_bundled_dedicated_in_window_aggressive_with_semantics_is_allowed() {
        // A real semantic candidate inside the window on a high-risk host is
        // allowed to continue (dedicated only waives the source, the rest of
        // the gate passed).
        assertNull(
            prodGate(
                candidate = BypassExitCandidateType.SKIP_TEXT,
                packageName = "com.tencent.mm",
                trust = BypassRuleTrust.BUNDLED_DEDICATED,
                requiresStrongAdContext = false,
                inWindow = true,
            ),
        )
    }

    @Test
    fun normal_app_mature_dedicated_requires_real_candidate() {
        // A null candidate is a hard denial even on a normal host with a
        // mature dedicated rule: no fake-skip execution ever.
        assertEquals(
            BypassRejectReason.NEGATIVE_SEMANTIC,
            prodGate(
                candidate = null,
                packageName = "com.example.app",
                trust = BypassRuleTrust.BUNDLED_DEDICATED,
                requiresStrongAdContext = false,
                inWindow = false, // must not even reach the window check
            ),
        )
    }

    @Test
    fun normal_app_mature_dedicated_with_real_candidate_runs_low_cost_path() {
        // Acceptance: normal app mature dedicated => conservative normal run,
        // even outside the startup window (curated trusted path).
        assertNull(
            prodGate(
                candidate = BypassExitCandidateType.CLOSE_TEXT,
                packageName = "com.example.app",
                trust = BypassRuleTrust.BUNDLED_DEDICATED,
                requiresStrongAdContext = false,
                context = BypassAdContextLevel.NONE,
                inWindow = false,
            ),
        )
    }

    @Test
    fun wechat_official_skip_outside_window_is_allowed() {
        // Explicit Skip remains available when a mini-program ad appears late.
        assertNull(
            prodGate(
                candidate = BypassExitCandidateType.SKIP_TEXT,
                packageName = "com.tencent.mm",
                trust = BypassRuleTrust.BYPASS_OVERRIDE,
                inWindow = false,
            ),
        )
    }

    @Test
    fun wechat_official_skip_in_window_is_yes() {
        // Acceptance: WeChat official Skip + inWindow => YES (skip semantics
        // are explicit; no ad-context requirement).
        assertNull(
            prodGate(
                candidate = BypassExitCandidateType.SKIP_TEXT,
                packageName = "com.tencent.mm",
                trust = BypassRuleTrust.BYPASS_OVERRIDE,
                context = BypassAdContextLevel.NONE,
                inWindow = true,
            ),
        )
    }

    @Test
    fun wechat_official_close_without_strong_context_is_no() {
        // Acceptance: WeChat official Close + context NONE => NO.
        assertEquals(
            BypassRejectReason.NO_AD_CONTEXT,
            prodGate(
                candidate = BypassExitCandidateType.CLOSE_TEXT,
                packageName = "com.tencent.mm",
                trust = BypassRuleTrust.BYPASS_OVERRIDE,
                context = BypassAdContextLevel.NONE,
                inWindow = true,
            ),
        )
    }

    @Test
    fun wechat_official_close_strong_in_window_aggressive_is_yes() {
        // Acceptance: WeChat official Close + STRONG + inWindow + Aggressive => YES.
        assertNull(
            prodGate(
                candidate = BypassExitCandidateType.CLOSE_TEXT,
                packageName = "com.tencent.mm",
                trust = BypassRuleTrust.BYPASS_OVERRIDE,
                mode = BypassAdStrategyMode.AGGRESSIVE,
                context = BypassAdContextLevel.STRONG,
                inWindow = true,
            ),
        )
    }

    @Test
    fun high_risk_imported_rule_still_denied_even_with_strong_context() {
        // Untrusted source on a high-risk host is ALWAYS denied, even with
        // STRONG context, in window, and CRAZY mode.
        assertEquals(
            BypassRejectReason.SENSITIVE_ACTIVITY,
            prodGate(
                candidate = BypassExitCandidateType.SKIP_TEXT,
                packageName = "com.tencent.mm",
                trust = BypassRuleTrust.LOCAL_IMPORT_DEDICATED,
                mode = BypassAdStrategyMode.CRAZY,
                context = BypassAdContextLevel.STRONG,
                inWindow = true,
            ),
        )
    }

    // ---- P0-3: AppBrandUI alone is WEAK context (star-charge acceptance) ----

    @Test
    fun normal_appbrandui_without_ad_evidence_small_imageview_crazy_is_no() {
        // Acceptance: normal AppBrandUI + no splash evidence + small
        // ImageView matching a Crazy structural selector => NO ACTION. The
        // mini-program shell alone is WEAK context, and a structural
        // no-semantic close requires STRONG context in every mode. (The rule
        // is an exempt override so the gate reaches the context check instead
        // of denying on source trust.)
        assertEquals(
            BypassRejectReason.NO_AD_CONTEXT,
            BypassStrategyGate.evaluateExecution(
                candidate = BypassExitCandidateType.STRUCTURAL_CLOSE,
                packageName = "com.tencent.mm",
                activityName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                nodeWidth = 60,
                nodeHeight = 60,
                policy = BypassAdStrategyMode.CRAZY.policy,
                rulePolicy = BypassRulePolicy(
                    trust = BypassRuleTrust.BYPASS_OVERRIDE,
                    minimumMode = BypassAdStrategyMode.CONSERVATIVE,
                    requiresStrongAdContext = true,
                    coordinate = false,
                    maxAttempts = 3,
                ),
                contextLevel = BypassAdContextLevel.WEAK, // AppBrandUI alone
                inWindow = true,
            ),
        )
    }

    @Test
    fun appbrandui_with_real_ad_evidence_structural_close_crazy_is_yes() {
        // Acceptance: AppBrandUI + real ad evidence (STRONG context) +
        // structural close + Crazy => YES.
        assertNull(
            BypassStrategyGate.evaluateExecution(
                candidate = BypassExitCandidateType.STRUCTURAL_CLOSE,
                packageName = "com.tencent.mm",
                activityName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                nodeWidth = 60,
                nodeHeight = 60,
                policy = BypassAdStrategyMode.CRAZY.policy,
                rulePolicy = BypassRulePolicy(
                    trust = BypassRuleTrust.BYPASS_OVERRIDE,
                    minimumMode = BypassAdStrategyMode.CRAZY,
                    requiresStrongAdContext = true,
                    coordinate = false,
                    maxAttempts = 3,
                ),
                contextLevel = BypassAdContextLevel.STRONG, // real ad evidence
                inWindow = true,
            ),
        )
    }

    private fun gateCloseText(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.CLOSE_TEXT, p, 80, 40)

    private fun gateCloseViewId(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.CLOSE_VIEW_ID, p, 60, 40)

    private fun gateCloseIcon(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.CLOSE_ICON, p, 40, 40)

    private fun gateStructural(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.STRUCTURAL_CLOSE, p, 60, 60)

    private fun gateCoordinate(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.COORDINATE_FALLBACK, p, 40, 40)
}
