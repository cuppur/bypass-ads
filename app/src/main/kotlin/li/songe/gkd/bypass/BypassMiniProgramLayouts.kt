package li.songe.gkd.bypass

import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

/** Narrow admission for a reviewed native SDK layout, never a generic X guess. */
object BypassMiniProgramLayouts {
    // Fingerprint of the bundled Alipay splash selector with a complete
    // android:id/content -> SDK container -> last-child exit ancestor chain.
    // A changed selector needs review. Imported rules cannot gain this trust.
    internal const val ALIPAY_NATIVE_EXIT = "cc65985ba619279357134caf6a702212ae6b103952b1df56be1bcfa12af91350"

    fun selectorFingerprint(selector: String): String = MessageDigest.getInstance("SHA-256")
        .digest(selector.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun admits(
        packageName: String,
        activityName: String?,
        trust: BypassRuleTrust,
        coordinate: Boolean,
        selectorFingerprints: List<String>,
        node: BypassExitClassifier.NodeSemantics,
    ): Boolean = packageName == "com.eg.android.AlipayGphone" &&
        BypassAdContextTracker.isMiniProgramAdActivity(packageName, activityName) &&
        trust == BypassRuleTrust.BUNDLED_DEDICATED && !coordinate &&
        selectorFingerprints == listOf(ALIPAY_NATIVE_EXIT) &&
        node.visible && node.childCount == 0 && node.className == "android.view.View" &&
        node.text.isNullOrBlank() && node.description.isNullOrBlank() && node.viewId.isNullOrBlank() &&
        node.bounds.r - node.bounds.l in 1..420 && node.bounds.b - node.bounds.t in 1..260

    fun admitsNode(
        packageName: String, activityName: String?, policy: BypassRulePolicy,
        selectors: List<String>, node: AccessibilityNodeInfo,
    ): Boolean {
        if (packageName != "com.eg.android.AlipayGphone" || policy.trust != BypassRuleTrust.BUNDLED_DEDICATED ||
            policy.coordinate || selectors.size != 1 || node.childCount != 0 ||
            node.className?.toString() != "android.view.View" ||
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        ) return false
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return admits(packageName, activityName, policy.trust, policy.coordinate,
            selectors.map(::selectorFingerprint), BypassExitClassifier.NodeSemantics(
                text = node.text?.toString(), description = node.contentDescription?.toString(),
                viewId = node.viewIdResourceName, className = node.className?.toString(),
                clickable = node.isClickable, visible = node.isVisibleToUser, childCount = node.childCount,
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            ))
    }
}
