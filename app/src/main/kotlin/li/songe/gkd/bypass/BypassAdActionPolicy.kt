package li.songe.gkd.bypass

/** Called only after the candidate has passed the shared execution gate. */
object BypassAdActionPolicy {
    fun useCenter(
        candidate: BypassExitCandidateType?, miniProgram: Boolean, attempt: Int,
        action: String?, hasCustomPosition: Boolean, width: Int, height: Int,
    ): Boolean {
        if (hasCustomPosition || action !in setOf(null, "click", "clickNode", "clickCenter") ||
            width !in 1..420 || height !in 1..260) return false
        if (candidate == BypassExitCandidateType.CURATED_MINI_EXIT) return true
        val semanticExit = candidate in setOf(BypassExitCandidateType.SKIP_TEXT,
            BypassExitCandidateType.CLOSE_TEXT, BypassExitCandidateType.CLOSE_DESC,
            BypassExitCandidateType.CLOSE_VIEW_ID, BypassExitCandidateType.CLOSE_ICON,
            BypassExitCandidateType.DEDICATED_EXIT)
        // Web/SDK accessibility nodes may acknowledge ACTION_CLICK without
        // forwarding it. Mini-programs use the actual bounded control center;
        // other apps switch to the center on a fresh, budgeted retry.
        return semanticExit && (miniProgram || attempt > 1)
    }
}
