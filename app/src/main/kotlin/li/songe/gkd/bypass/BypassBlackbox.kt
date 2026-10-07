package li.songe.gkd.bypass

import li.songe.gkd.data.BypassDetectionSession

/** Stable signals; callers pass classifications, never page text or entire trees. */
enum class BypassObservedAdEvidence {
    SKIP_CONTROL,
    AD_SPECIFIC_EXIT,
    CLOSE_WITH_COUNTDOWN,
    VERIFIED_MINI_AD_LAYOUT,
    EXPLICIT_AD_WITH_EXIT,
    USER_REPORTED_AD,
    VISUAL_AD_EXIT,
}

/** Pure, shared rules for persistence, product rows and failure aggregates. */
object BypassBlackbox {
    const val EVIDENCE_MARKER = "AD_EVIDENCE:"
    const val EXECUTION_ALLOWED_MARKER = "EXECUTION_ALLOWED:"
    const val MAX_TIMELINE_ITEMS = 24

    fun hasAllowedCandidate(record: BypassDetectionSession): Boolean =
        record.diagnosticTimeline.lines().any { it.substringAfter('|').startsWith(EXECUTION_ALLOWED_MARKER) }

    fun sameWindow(aPackage: String, aActivity: String?, bPackage: String, bActivity: String?): Boolean =
        aPackage == bPackage && (aActivity == bActivity || aActivity == null || bActivity == null)

    fun hasAdEvidence(record: BypassDetectionSession): Boolean =
        record.diagnosticTimeline.contains(EVIDENCE_MARKER) ||
            // Legacy sessions are created after the execution gate, rather
            // than on every app opening. Preserve their known action history.
            (record.candidateSeen && record.actionAttempts > 0) ||
            record.sessionResult == BypassSessionResult.SUCCESS_CONFIRMED

    fun appendEvent(record: BypassDetectionSession, event: String, now: Long): String {
        val clean = event.replace(Regex("[^A-Za-z0-9_ .:/=+-]"), "_").take(160)
        val previous = record.diagnosticTimeline.lines().filter(String::isNotBlank)
        if (previous.lastOrNull()?.substringAfter('|') == clean) return record.diagnosticTimeline
        if (clean.startsWith(EVIDENCE_MARKER) && previous.any { it.substringAfter('|') == clean }) return record.diagnosticTimeline
        val duplicate = previous.lastOrNull { it.substringAfter('|') == clean }
        val lastMs = duplicate?.substringBefore("ms|")?.removePrefix("+")?.toLongOrNull()
        if (lastMs != null && now - record.startTime - lastMs < 800L) return record.diagnosticTimeline
        val next = previous + "+${(now - record.startTime).coerceAtLeast(0)}ms|$clean"
        // Keep first decision milestones as well as recent retries. A rejected
        // fallback must not evict the earlier allowed semantic exit and wait.
        val milestones = listOf(EVIDENCE_MARKER, "ROOT_SCAN:", "CONFIG:",
            "TARGET_FOUND:", "GATE:", EXECUTION_ALLOWED_MARKER,
            "MATCHER:WAITING_FOR_DELAY", "ACTION_DELAY_SCHEDULED:",
            "WINDOW_EVENT:", "QUERY_INVALIDATED:", "RUNTIME_NOT_READY:",
            "ACTION_ATTEMPT:", "ACTION_RESULT:", "VERIFY_START", "OUTCOME:")
        val pinned = milestones.mapNotNull { prefix ->
            next.indexOfFirst { it.substringAfter('|').startsWith(prefix) }.takeIf { it >= 0 }
        }.distinct().take(MAX_TIMELINE_ITEMS - 8)
        val recent = next.indices.reversed().filter { it !in pinned }
            .take(MAX_TIMELINE_ITEMS - pinned.size)
        return (pinned + recent).sorted().joinToString("\n") { next[it] }
    }

    /** Page departure/deadline is inconclusive, not proof of an automatic exit. */
    fun finishUnresolved(record: BypassDetectionSession, now: Long, event: String): BypassDetectionSession =
        record.copy(
            endTime = now,
            result = if (hasAdEvidence(record)) BypassSessionResult.UNRESOLVED.name else record.result,
            finalFailureReason = record.finalFailureReason ?: FailureReason.SELECTOR_NO_MATCH.name,
            diagnosticTimeline = appendEvent(record, event, now),
        )

    /** Keep the actionable cause when later selector retries inevitably miss. */
    fun recordReason(record: BypassDetectionSession, reason: FailureReason): BypassDetectionSession {
        if (reason == FailureReason.GLOBAL_EXCLUDED && hasAllowedCandidate(record)) return record
        val old = runCatching { FailureReason.valueOf(record.finalFailureReason.orEmpty()) }.getOrNull()
        return if (old == null || reasonPriority(reason) >= reasonPriority(old)) {
            record.copy(finalFailureReason = reason.name)
        } else record
    }

    /** Later rejected alternatives do not explain why an allowed exit was idle. */
    fun admitted(record: BypassDetectionSession): BypassDetectionSession =
        if (record.finalFailureReason == FailureReason.GLOBAL_EXCLUDED.name)
            record.copy(finalFailureReason = null) else record

    private fun reasonPriority(reason: FailureReason): Int = when (reason) {
        FailureReason.MISCLICK_SUSPECTED -> 6
        FailureReason.ACTION_NO_EFFECT, FailureReason.ACTION_FAILED -> 5
        FailureReason.GLOBAL_EXCLUDED, FailureReason.APP_DISABLED,
        FailureReason.CATEGORY_DISABLED, FailureReason.MASTER_DISABLED -> 4
        FailureReason.TARGET_FOUND_NOT_CLICKABLE, FailureReason.ACCESSIBILITY_NODE_MISSING,
        FailureReason.VISUAL_CAPTURE_UNAVAILABLE, FailureReason.VISUAL_RECOGNITION_UNAVAILABLE,
        FailureReason.VISUAL_TARGET_STALE -> 3
        FailureReason.WAITING_FOR_DELAY, FailureReason.ACTION_TOO_EARLY -> 2
        FailureReason.ACTIVITY_MISMATCH, FailureReason.NO_RULE_FOR_APP,
        FailureReason.SELECTOR_NO_MATCH, FailureReason.EVENT_MISSED -> 1
        FailureReason.UNKNOWN -> 0
    }
}
