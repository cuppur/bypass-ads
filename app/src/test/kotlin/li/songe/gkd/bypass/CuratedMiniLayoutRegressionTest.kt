package li.songe.gkd.bypass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.ResolvedAppGroup
import li.songe.gkd.data.AppRule
import li.songe.gkd.data.SubsItem

class CuratedMiniLayoutRegressionTest {
    private val host = "com.eg.android.AlipayGphone"
    private val activity = "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity"
    private val node = BypassExitClassifier.NodeSemantics(
        className = "android.view.View", bounds = Bounds(1000, 150, 1140, 225),
    )
    private fun admits(trust: BypassRuleTrust = BypassRuleTrust.BUNDLED_DEDICATED,
                       fingerprints: List<String> = listOf(BypassMiniProgramLayouts.ALIPAY_NATIVE_EXIT),
                       target: BypassExitClassifier.NodeSemantics = node,
                       coordinate: Boolean = false, pkg: String = host, act: String = activity) =
        BypassMiniProgramLayouts.admits(pkg, act, trust, coordinate, fingerprints, target)

    @Test
    fun reviewed_bundled_native_exit_is_admitted_but_not_untrusted_or_guessed_layouts() {
        assertTrue(admits())
        for (trust in BypassRuleTrust.entries.filter { it != BypassRuleTrust.BUNDLED_DEDICATED }) {
            assertFalse(trust.name, admits(trust))
        }
        assertFalse(admits(fingerprints = listOf("changed-selector")))
        assertFalse(admits(fingerprints = listOf(BypassMiniProgramLayouts.ALIPAY_NATIVE_EXIT, "extra-condition")))
        assertFalse(admits(coordinate = true))
        assertFalse(admits(pkg = "com.tencent.mm"))
        assertFalse(admits(act = "MainActivity"))
    }

    @Test
    fun nav_bar_sensitive_text_and_large_or_invisible_node_are_not_curated_exits() {
        for (target in listOf(node.copy(text = "支付"), node.copy(description = "关闭"),
            node.copy(viewId = "toolbar_close"), node.copy(visible = false), node.copy(childCount = 1),
            node.copy(className = "android.widget.FrameLayout"), node.copy(bounds = Bounds(0, 0, 1200, 2600)))) {
            assertFalse(admits(target = target))
        }
    }

    @Test
    fun actual_packaged_mini_rules_use_window_root_and_cover_base_and_variant_activities() {
        var root = File(System.getProperty("user.dir")!!)
        while (root.parentFile != null && !File(root, "settings.gradle.kts").exists()) root = root.parentFile!!
        val file = File(root, "app/src/main/assets/bypass_splash_rules.local.json")
        if (!file.exists()) return // public fixture does not embed the private self-use source
        val subscription = RawSubscription.parse(file.readText(), json5 = false)
        val app = subscription.apps.first { it.id == host }
        val group = app.groups.first { it.name == "开屏广告-小程序开屏广告" }
        val resolved = ResolvedAppGroup(group, subscription,
            SubsItem(id = BYPASS_SPLASH_SUBS_ID, enable = true, order = 0), null, app, true)
        for (raw in group.rules) {
            val rule = AppRule(raw as RawSubscription.RawAppRule, resolved, null)
            assertTrue(rule.matchRoot)
            assertTrue(rule.matchActivity(host, activity))
            assertTrue(rule.matchActivity(host, activity + "$" + "Main"))
        }
        val reviewed = group.rules.first { raw -> raw.matches.orEmpty().map(BypassMiniProgramLayouts::selectorFingerprint) ==
            listOf(BypassMiniProgramLayouts.ALIPAY_NATIVE_EXIT) }
        assertTrue(admits(fingerprints = reviewed.matches.orEmpty().map(BypassMiniProgramLayouts::selectorFingerprint)))
    }

    @Test
    fun reviewed_small_exit_runs_in_aggressive_but_requires_real_proof_and_context() {
        val policy = BypassRulePolicy(BypassRuleTrust.BUNDLED_DEDICATED,
            BypassAdStrategyMode.CONSERVATIVE, false, false, 2)
        fun gate(mode: BypassAdStrategyMode, proof: Boolean = true, context: BypassAdContextLevel = BypassAdContextLevel.STRONG) =
            BypassStrategyGate.evaluateExecution(BypassExitCandidateType.CURATED_MINI_EXIT, host, activity,
                140, 75, mode.policy, policy, context, inWindow = false, verifiedMiniLayout = proof)
        assertNull(gate(BypassAdStrategyMode.AGGRESSIVE))
        assertNull(gate(BypassAdStrategyMode.CRAZY))
        assertNotNull(gate(BypassAdStrategyMode.CONSERVATIVE))
        assertNotNull(gate(BypassAdStrategyMode.AGGRESSIVE, proof = false))
        assertNotNull(gate(BypassAdStrategyMode.AGGRESSIVE, context = BypassAdContextLevel.WEAK))
    }
}
