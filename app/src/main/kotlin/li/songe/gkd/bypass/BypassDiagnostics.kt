package li.songe.gkd.bypass

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import li.songe.gkd.META
import li.songe.gkd.data.BypassDetectionSession
import li.songe.gkd.util.dbFolder
import li.songe.gkd.app
import li.songe.gkd.a11y.topActivityFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reasons are stable product diagnostics, rather than opaque matcher logs. */
enum class FailureReason {
    NO_RULE_FOR_APP,
    CATEGORY_DISABLED,
    MASTER_DISABLED,
    APP_DISABLED,
    GLOBAL_EXCLUDED,
    ACTIVITY_MISMATCH,
    SELECTOR_NO_MATCH,
    TARGET_FOUND_NOT_CLICKABLE,
    ACTION_FAILED,
    ACTION_NO_EFFECT,
    WAITING_FOR_DELAY,
    ACTION_TOO_EARLY,
    ACCESSIBILITY_NODE_MISSING,
    EVENT_MISSED,
    MISCLICK_SUSPECTED,
    VISUAL_CAPTURE_UNAVAILABLE,
    VISUAL_RECOGNITION_UNAVAILABLE,
    VISUAL_TARGET_STALE,
    UNKNOWN,
}

data class BypassDiagnosticEvent(
    val id: Long,
    val time: Long,
    val packageName: String,
    val activityName: String?,
    val reason: FailureReason,
    val detail: String,
)

/**
 * Self-use shadow trace layered around the existing GKD matcher (v1.0: also
 * active in the formal self-use release, not only debug). It does not scan
 * nodes independently and intentionally stores no UI text, input, or full
 * accessibility trees — only reason codes plus package/activity identity.
 * The exported package is local-only by default.
 */
object BypassDiagnostics {
    private const val MAX_EVENTS = 40
    private val _events = MutableStateFlow<List<BypassDiagnosticEvent>>(emptyList())
    val events = _events

    fun record(
        reason: FailureReason,
        packageName: String = topActivityFlow.value.appId,
        activityName: String? = topActivityFlow.value.activityId,
        detail: String = "",
    ) {
        // v1.0: the self-use release records the same privacy-safe diagnostics
        // as debug (the Diagnostics page must work on the installed release).
        val event = BypassDiagnosticEvent(
            id = System.nanoTime(),
            time = System.currentTimeMillis(),
            packageName = packageName,
            activityName = activityName,
            reason = reason,
            detail = detail.take(160).replace(Regex("[^A-Za-z0-9_ .:/=-]"), "_"),
        )
        _events.value = (listOf(event) + _events.value).take(MAX_EVENTS)
    }

    fun clear() {
        _events.value = emptyList()
        File(dbFolder.parentFile, "blackbox").listFiles().orEmpty().filter {
            it.isFile && (Regex("session-[0-9]+-[A-Fa-f0-9-]+\\.json").matches(it.name) ||
                Regex("\\.session-[0-9]+-[A-Fa-f0-9-]+\\.json\\.tmp").matches(it.name))
        }.forEach { it.delete() }
    }

    /** Local metadata mirror for USB diagnosis of non-debuggable release builds. */
    fun writeFinalSessionTrace(session: BypassDetectionSession) {
        if (session.sessionResult == BypassSessionResult.OPEN) return
        val record = session.toSessionRecord()
        val row = JSONObject().put("session_id", session.sessionId).put("package_name", session.packageName)
            .put("activity_name", session.activityName).put("start_time", session.startTime).put("end_time", session.endTime)
            .put("result", session.result).put("final_failure_reason", session.finalFailureReason)
            .put("strategy_mode", session.strategyMode).put("candidate_type", session.candidateType)
            .put("action_attempts", session.actionAttempts).put("confirmed_latency_ms", session.confirmedLatencyMs)
            .put("rule_origin", session.ruleOrigin).put("timeline", JSONArray(record.timeline))
            .put("matched_rules", JSONArray(record.matchedRules))
        val shapes = JSONArray()
        record.candidates.forEach { node ->
            shapes.put(JSONObject().put("className", node.className).put("bounds", node.bounds)
                .put("clickable", node.clickable).put("parentClickable", node.parentClickable))
        }
        row.put("control_shapes", shapes)
        val directory = File(dbFolder.parentFile, "blackbox").apply { mkdirs() }
        val name = "session-${session.startTime}-${session.sessionId}.json"
        val pending = File(directory, ".$name.tmp")
        pending.writeText(row.toString())
        val destination = File(directory, name)
        check(pending.renameTo(destination)) { "Unable to store blackbox trace" }
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val files = directory.listFiles().orEmpty().filter { it.name.startsWith("session-") && it.extension == "json" }
        files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        files.filter { it.exists() }.sortedByDescending { it.lastModified() }.drop(2_000).forEach { it.delete() }
    }

    fun writeSessionBundle(
        record: BypassSessionRecord, metadata: BypassRuleMetadata,
        protection: BypassRuntimeProtection, masterEnabled: Boolean,
    ): File {
        val current = writeLocalBundle(metadata, protection, masterEnabled)
        val root = JSONObject(current.readText())
        val row = JSONObject().put("id", record.id).put("time", record.time)
            .put("packageName", record.packageName).put("activity", record.activityName)
            .put("result", record.result.name).put("reason", record.reason.name)
            .put("strategyAtObservation", record.strategyMode.name).put("ruleOrigin", record.ruleOrigin)
            .put("candidateType", record.candidateType?.name).put("actionAttempts", record.actionAttempts)
            .put("confirmedLatencyMs", record.confirmedLatencyMs).put("hasAdEvidence", record.hasAdEvidence)
            .put("matchedRules", JSONArray(record.matchedRules)).put("actions", JSONArray(record.actions))
            .put("timeline", JSONArray(record.timeline))
        val candidates = JSONArray()
        record.candidates.forEach { c ->
            candidates.put(JSONObject().put("text", c.text).put("description", c.description).put("viewId", c.viewId)
                .put("className", c.className).put("bounds", c.bounds).put("clickable", c.clickable)
                .put("parentClickable", c.parentClickable))
        }
        root.put("adSession", row.put("candidates", candidates))
        current.writeText(root.toString(2))
        return current
    }

    fun writeLocalBundle(
        metadata: BypassRuleMetadata,
        protection: BypassRuntimeProtection,
        masterEnabled: Boolean,
    ): File {
        val current = topActivityFlow.value
        val root = JSONObject()
            .put("packageName", current.appId)
            .put("activity", current.activityId)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("ruleBundleSha256", metadata.sha256)
            .put("ruleSource", metadata.sourceType.name)
            .put("masterEnabled", masterEnabled)
            .put("accessibilityConnected", protection.accessibilityConnected)
            .put("generatedAt", System.currentTimeMillis())
        val entries = JSONArray()
        events.value.forEach { event ->
            entries.put(
                JSONObject()
                    .put("time", event.time)
                    .put("id", event.id)
                    .put("packageName", event.packageName)
                    .put("activity", event.activityName)
                    .put("failureReason", event.reason.name)
                    .put("matcherDetail", event.detail),
            )
        }
        root.put("recentMatcherEvents", entries)
        root.put("privacy", "No input text, passwords, phone/card numbers, chat content, or full accessibility tree is exported.")
        val directory = File(app.filesDir, "bypass-diagnostics").apply { mkdirs() }
        return File(directory, "diagnostic-${System.currentTimeMillis()}.json").apply {
            writeText(root.toString(2))
        }
    }
}
