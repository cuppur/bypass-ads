package li.songe.gkd.bypass

/** Event matching stays live; forced polling still has its original deadline. */
object BypassRuleTiming {
    /** Repeated STATE_CHANGED events are not another ad or Activity entry. */
    fun shouldResetOnActivityEvent(
        isBypass: Boolean,
        previousActivity: String?,
        currentActivity: String?,
        alreadyActive: Boolean,
    ): Boolean = !isBypass || !alreadyActive ||
        (previousActivity != null && currentActivity != null && previousActivity != currentActivity)

    fun matchExpired(isBypass: Boolean, elapsed: Long, matchTime: Long?, matchDelay: Long): Boolean =
        !isBypass && matchTime != null && elapsed > matchTime + matchDelay

    fun allowsLateCandidate(candidate: BypassExitCandidateType, context: BypassAdContextLevel): Boolean =
        candidate == BypassExitCandidateType.SKIP_TEXT ||
            (context == BypassAdContextLevel.STRONG && candidate in setOf(
                BypassExitCandidateType.CLOSE_TEXT, BypassExitCandidateType.CLOSE_DESC,
                BypassExitCandidateType.CLOSE_VIEW_ID, BypassExitCandidateType.CLOSE_ICON,
                BypassExitCandidateType.CURATED_MINI_EXIT,
                BypassExitCandidateType.LOCAL_VISUAL_EXIT,
            ))
}
