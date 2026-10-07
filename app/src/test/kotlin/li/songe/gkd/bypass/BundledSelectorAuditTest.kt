package li.songe.gkd.bypass

import java.io.File
import li.songe.gkd.data.RawSubscription
import li.songe.selector.Selector
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parse the actual packaged selectors with the production GKD parser. */
class BundledSelectorAuditTest {
    @Test fun every_packaged_selector_is_valid() {
        var root = File(System.getProperty("user.dir")!!)
        while (root.parentFile != null && !File(root, "settings.gradle.kts").exists()) root = root.parentFile!!
        val assets = File(root, "app/src/main/assets")
        val bundle = File(assets, "bypass_splash_rules.local.json").takeIf { it.exists() }
            ?: File(assets, "bypass_splash_rules.json")
        val subscription = RawSubscription.parse(bundle.readText(), json5 = false)
        var count = 0
        for (group in subscription.globalGroups + subscription.apps.flatMap { it.groups }) {
            for ((index, rule) in group.rules.withIndex()) {
                for (selector in rule.matches.orEmpty() + rule.anyMatches.orEmpty() +
                    rule.excludeMatches.orEmpty() + rule.excludeAllMatches.orEmpty()) {
                    try {
                        Selector.parse(selector)
                    } catch (e: Exception) {
                        throw AssertionError("Invalid packaged selector: group ${group.key}, rule $index", e)
                    }
                    count++
                }
            }
        }
        assertTrue("The packaged subscription must contain selectors", count > 0)
    }
}
