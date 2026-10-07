package li.songe.gkd.bypass

import li.songe.gkd.data.BypassDetectionSession
import org.junit.Assert.*
import org.junit.Test

class BlackboxRegressionTest {
    private fun observed() = BypassDetectionSession("ad-1", "com.tencent.mm", "AppBrandUI", 1000L,
        diagnosticTimeline = "+0ms|AD_EVIDENCE:CLOSE_WITH_COUNTDOWN")

    @Test fun initial_unknown_activity_is_refinement_not_another_ad() {
        assertTrue(BypassBlackbox.sameWindow("host", null, "host", "Splash"))
        assertTrue(BypassBlackbox.sameWindow("host", "Splash", "host", null))
        assertFalse(BypassBlackbox.sameWindow("host", "Splash", "host", "Home"))
        assertFalse(BypassBlackbox.sameWindow("host", null, "other", null))
    }

    @Test fun proof_survives_busy_countdown_and_rule_retries() {
        var record = observed()
        repeat(180) { n ->
            record = record.copy(diagnosticTimeline = BypassBlackbox.appendEvent(record, "MATCHER:SELECTOR_NO_MATCH_$n", 1100L + n * 100L))
        }
        assertTrue(BypassBlackbox.hasAdEvidence(record))
        assertTrue(record.diagnosticTimeline.lines().size <= 24)
        assertTrue(record.diagnosticTimeline.lines().first().contains("AD_EVIDENCE:CLOSE_WITH_COUNTDOWN"))
    }

    @Test fun repeated_proof_and_rapid_same_stage_do_not_flood_history() {
        val original = observed()
        assertEquals(original.diagnosticTimeline, BypassBlackbox.appendEvent(original, "AD_EVIDENCE:CLOSE_WITH_COUNTDOWN", 1200L))
        val first = original.copy(diagnosticTimeline = BypassBlackbox.appendEvent(original, "MATCHER:SELECTOR_NO_MATCH", 1200L))
        val busy = first.copy(diagnosticTimeline = BypassBlackbox.appendEvent(first, "ROOT_SCAN:complete=true", 1300L))
        assertEquals(busy.diagnosticTimeline, BypassBlackbox.appendEvent(busy, "MATCHER:SELECTOR_NO_MATCH", 1400L))
    }

    @Test fun rejected_fallback_retries_do_not_evict_allowed_exit_and_its_wait() {
        var record = observed()
        for (event in listOf("EXECUTION_ALLOWED:rule=200 type=SKIP_TEXT", "MATCHER:WAITING_FOR_DELAY",
            "ACTION_DELAY_SCHEDULED:rule=200 delay_ms=800", "QUERY_INVALIDATED:new_window_event=true")) {
            record = record.copy(diagnosticTimeline = BypassBlackbox.appendEvent(record, event, 1100L))
        }
        repeat(90) { n ->
            for (event in listOf("GATE:type=STRUCTURAL_CLOSE", "CLOSE_CANDIDATE_REJECTED:STRUCTURAL_CLOSE:RULE_LEVEL", "MATCHER:SELECTOR_NO_MATCH")) {
                record = record.copy(diagnosticTimeline = BypassBlackbox.appendEvent(record, event, 2100L + n * 1000L))
            }
        }
        assertTrue(BypassBlackbox.hasAllowedCandidate(record))
        assertTrue(record.diagnosticTimeline.contains("MATCHER:WAITING_FOR_DELAY"))
        assertTrue(record.diagnosticTimeline.contains("ACTION_DELAY_SCHEDULED:rule=200 delay_ms=800"))
        assertTrue(record.diagnosticTimeline.contains("QUERY_INVALIDATED:"))
        assertTrue(record.diagnosticTimeline.lines().size <= BypassBlackbox.MAX_TIMELINE_ITEMS)
    }

    @Test fun allowed_exit_wait_is_not_reclassified_as_rejected_fallback() {
        val blocked = BypassBlackbox.recordReason(observed(), FailureReason.GLOBAL_EXCLUDED)
        val allowed = BypassBlackbox.admitted(blocked).copy(diagnosticTimeline =
            BypassBlackbox.appendEvent(blocked, "EXECUTION_ALLOWED:rule=200 type=SKIP_TEXT", 1200L))
        assertNull(allowed.finalFailureReason)
        val waiting = BypassBlackbox.recordReason(allowed, FailureReason.WAITING_FOR_DELAY)
        assertEquals(FailureReason.WAITING_FOR_DELAY.name,
            BypassBlackbox.recordReason(waiting, FailureReason.GLOBAL_EXCLUDED).finalFailureReason)
        assertEquals(FailureReason.ACTION_NO_EFFECT.name,
            BypassBlackbox.recordReason(waiting, FailureReason.ACTION_NO_EFFECT).finalFailureReason)
    }

    @Test fun disappearance_without_action_never_becomes_auto_success() {
        val finished = BypassBlackbox.finishUnresolved(observed(), 2000L, "AD_DISAPPEARED_WITHOUT_ACTION")
        assertEquals(BypassSessionResult.UNRESOLVED, finished.sessionResult)
        assertFalse(finished.success)
        assertEquals(0, finished.actionAttempts)
    }

    @Test fun page_open_without_proof_does_not_count_as_failed_ad() {
        val open = BypassDetectionSession("normal", "com.tencent.mm", "AppBrandUI", 1000L)
        assertFalse(BypassBlackbox.hasAdEvidence(open))
        assertEquals(BypassSessionResult.OPEN, BypassBlackbox.finishUnresolved(open, 2000L, "WINDOW_ENDED").sessionResult)
    }

    @Test fun later_selector_miss_does_not_hide_gate_or_action_failure() {
        val blocked = BypassBlackbox.recordReason(observed(), FailureReason.GLOBAL_EXCLUDED)
        assertEquals(FailureReason.GLOBAL_EXCLUDED.name, BypassBlackbox.recordReason(blocked, FailureReason.SELECTOR_NO_MATCH).finalFailureReason)
        val noEffect = BypassBlackbox.recordReason(blocked, FailureReason.ACTION_NO_EFFECT)
        assertEquals(FailureReason.ACTION_NO_EFFECT.name, BypassBlackbox.recordReason(noEffect, FailureReason.ACTIVITY_MISMATCH).finalFailureReason)
    }

    @Test fun legacy_action_history_keeps_its_ad_classification() {
        val legacy = observed().copy(diagnosticTimeline = "", candidateSeen = true, actionAttempts = 1)
        assertTrue(BypassBlackbox.hasAdEvidence(legacy))
    }

    private fun row(result: BypassSessionResult, proof: Boolean = true) = BypassSessionRecord(
        "ad-1", 2000L, "com.tencent.mm", "AppBrandUI", BypassAdStrategyMode.AGGRESSIVE, result,
        0, 0L, BypassExitCandidateType.CLOSE_TEXT, FailureReason.GLOBAL_EXCLUDED,
        timeline = listOf("+0ms|CLOSE_CANDIDATE_REJECTED:CLOSE_TEXT:STRATEGY_GATE"), hasAdEvidence = proof,
    )

    @Test fun unresolved_and_reported_ads_are_counted_separately_from_success() {
        assertTrue(BypassRecordPresentation.countsAsUnsuccessful(row(BypassSessionResult.UNRESOLVED)))
        assertFalse(BypassRecordPresentation.countsAsUnsuccessful(row(BypassSessionResult.OPEN)))
        assertFalse(BypassRecordPresentation.countsAsUnsuccessful(row(BypassSessionResult.UNRESOLVED, false)))
        assertFalse(BypassRecordPresentation.countsAsUnsuccessful(row(BypassSessionResult.SUCCESS_CONFIRMED)))
    }

    @Test fun records_filter_combines_app_and_outcome() {
        val success = row(BypassSessionResult.SUCCESS_CONFIRMED)
        val missing = row(BypassSessionResult.UNRESOLVED).copy(id = "ad-2")
        val other = missing.copy(id = "ad-3", packageName = "com.eg.android.AlipayGphone")
        assertEquals(listOf(missing), BypassRecordPresentation.filter(listOf(success, missing, other), "未成功", "com.tencent.mm"))
    }

    @Test fun reason_detail_explains_rejected_stage_and_still_exposes_its_code() {
        val record = row(BypassSessionResult.UNRESOLVED)
        assertTrue(BypassRecordPresentation.summary(record).contains("策略"))
        val event = BypassRecordPresentation.timeline(record.timeline.first())
        assertEquals("+0 ms", event.elapsed)
        assertTrue(event.code.contains("STRATEGY_GATE"))
        assertTrue(event.title.contains("策略"))
    }

    @Test fun allowed_wait_summary_does_not_blame_an_unrelated_rejected_alternative() {
        val waiting = row(BypassSessionResult.UNRESOLVED).copy(reason = FailureReason.WAITING_FOR_DELAY,
            timeline = listOf("+0ms|EXECUTION_ALLOWED:rule=200 type=SKIP_TEXT",
                "+50ms|MATCHER:WAITING_FOR_DELAY", "+1200ms|CLOSE_CANDIDATE_REJECTED:STRUCTURAL_CLOSE:RULE_LEVEL"))
        assertTrue(BypassRecordPresentation.summary(waiting).contains("等待"))
        assertTrue(BypassRecordPresentation.timeline("+90ms|RUNTIME_NOT_READY:rule=200 status=READY outdated=true").title.contains("页面事件"))
    }
}
