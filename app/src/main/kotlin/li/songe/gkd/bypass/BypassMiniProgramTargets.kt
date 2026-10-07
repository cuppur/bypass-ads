package li.songe.gkd.bypass

/** Reported mini-program splash exits are at the top left, outside the host capsule. */
object BypassMiniProgramTargets {
    fun allowsTarget(candidate: BypassExitCandidateType?, bounds: Bounds, window: Bounds,
                     nativeNavigation: Boolean): Boolean {
        if (nativeNavigation) return false
        if (candidate == BypassExitCandidateType.SKIP_TEXT) return true
        val width = window.r - window.l
        val height = window.b - window.t
        if (width <= 0 || height <= 0 || bounds.r - bounds.l !in 1..420 || bounds.b - bounds.t !in 1..260)
            return false
        if (bounds.l < window.l || bounds.t < window.t || bounds.r > window.r || bounds.b > window.b)
            return false
        return (bounds.l.toLong() + bounds.r - 2L * window.l) * 10 <= width.toLong() * 9 &&
            (bounds.t.toLong() + bounds.b - 2L * window.t) * 5 <= height.toLong() * 3
    }
}

/** Continue querying after a matching navigation control instead of losing the ad target. */
internal fun <T> firstAcceptedQueryTarget(first: T?, remaining: () -> Sequence<T>,
                                         accepts: ((T) -> Boolean)?): T? {
    if (first != null && (accepts == null || accepts(first))) return first
    return if (accepts == null) remaining().firstOrNull() else remaining().firstOrNull(accepts)
}
