package li.songe.gkd.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import li.songe.gkd.bypass.BypassSessionResult
import li.songe.gkd.bypass.BypassUnsuccessfulAppStats

/**
 * One ad session: a bounded matcher window for one possible ad. Multiple
 * actions inside the window belong to the same session and produce exactly
 * one product result (see [result]). The raw GKD ActionLog keeps meaning
 * "an action was performed"; [result] is the only success truth.
 */
@Entity(
    tableName = "bypass_detection_session",
    indices = [
        Index(value = ["end_time"]),
        Index(value = ["success", "final_failure_reason"]),
        Index(value = ["result", "end_time"]),
    ],
)
data class BypassDetectionSession(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_name") val activityName: String?,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "end_time") val endTime: Long = 0L,
    @ColumnInfo(name = "candidate_seen") val candidateSeen: Boolean = false,
    @ColumnInfo(name = "matched_rules") val matchedRules: String = "",
    @ColumnInfo(name = "actions") val actions: String = "",
    /** Legacy flag, derived from [result] == SUCCESS_CONFIRMED. */
    @ColumnInfo(name = "success") val success: Boolean = false,
    @ColumnInfo(name = "final_failure_reason") val finalFailureReason: String? = null,
    @ColumnInfo(name = "diagnostic_timeline") val diagnosticTimeline: String = "",
    @ColumnInfo(name = "candidate_snapshots") val candidateSnapshots: String = "",
    // ---- schema 16: product session fields ----
    /** Strategy mode at session start (historical, never the current value). */
    @ColumnInfo(name = "strategy_mode", defaultValue = "CONSERVATIVE")
    val strategyMode: String = BypassStrategyModeDefault,
    /** Product outcome (BypassSessionResult name). */
    @ColumnInfo(name = "result", defaultValue = "OPEN")
    val result: String = BypassSessionResult.OPEN.name,
    /** Total action attempts reserved for this session. */
    @ColumnInfo(name = "action_attempts", defaultValue = "0")
    val actionAttempts: Int = 0,
    /** Exit candidate type of the last attempted action. */
    @ColumnInfo(name = "candidate_type") val candidateType: String? = null,
    /** Confirmed latency: action start -> outcome confirmed (ms). */
    @ColumnInfo(name = "confirmed_latency_ms", defaultValue = "0")
    val confirmedLatencyMs: Long = 0L,
    /** Rule trust origin (BypassRuleTrust name) that created the session. */
    @ColumnInfo(name = "rule_origin") val ruleOrigin: String? = null,
    // ---- schema 17: session-scoped verifier evidence ----
    /** Structured identity of the rule whose candidate was acted on. */
    @ColumnInfo(name = "acted_rule_key") val actedRuleKey: Int? = null,
    @ColumnInfo(name = "acted_group_key") val actedGroupKey: Int? = null,
    /** "left,top,right,bottom" of the acted candidate (same-ad region check). */
    @ColumnInfo(name = "acted_candidate_bounds") val actedCandidateBounds: String? = null,
) {
    val sessionResult: BypassSessionResult
        get() = runCatching { BypassSessionResult.valueOf(result) }.getOrDefault(BypassSessionResult.OPEN)

    @Dao
    interface BypassDetectionSessionDao {
        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun upsert(session: BypassDetectionSession)

        @Query(
            """
            SELECT * FROM bypass_detection_session
            WHERE result IN ('FAILURE_CONFIRMED', 'UNRESOLVED', 'MISCLICK_SUSPECTED')
            ORDER BY end_time DESC
            LIMIT 300
            """
        )
        fun queryFailures(): Flow<List<BypassDetectionSession>>

        @Query(
            """
            SELECT * FROM bypass_detection_session
            WHERE result = 'SUCCESS_CONFIRMED'
            ORDER BY end_time DESC
            LIMIT 300
            """
        )
        fun querySuccesses(): Flow<List<BypassDetectionSession>>

        @Query(
            """
            SELECT * FROM bypass_detection_session
            ORDER BY CASE WHEN end_time = 0 THEN start_time ELSE end_time END DESC
            LIMIT 500
            """
        )
        fun queryAll(): Flow<List<BypassDetectionSession>>

        @Query("""
            SELECT package_name AS packageName,
                SUM(CASE WHEN result IN ('FAILURE_CONFIRMED','MISCLICK_SUSPECTED') THEN 1 ELSE 0 END) AS confirmedFailures,
                SUM(CASE WHEN result = 'UNRESOLVED' THEN 1 ELSE 0 END) AS unconfirmed,
                MAX(end_time) AS lastTime
            FROM bypass_detection_session
            WHERE result IN ('FAILURE_CONFIRMED','MISCLICK_SUSPECTED','UNRESOLVED')
                AND end_time >= :cutoff
                AND (diagnostic_timeline LIKE '%AD_EVIDENCE:%' OR (candidate_seen = 1 AND action_attempts > 0))
            GROUP BY package_name
            ORDER BY confirmedFailures + unconfirmed DESC, lastTime DESC
        """)
        fun queryUnsuccessfulByApp(cutoff: Long): Flow<List<BypassUnsuccessfulAppStats>>

        @Query("""
            DELETE FROM bypass_detection_session
            WHERE session_id NOT IN (
                SELECT session_id FROM bypass_detection_session
                ORDER BY CASE WHEN end_time = 0 THEN start_time ELSE end_time END DESC LIMIT :limit
            )
        """)
        suspend fun trimToLatest(limit: Int): Int

        @Query("SELECT COUNT(*) FROM bypass_detection_session WHERE result = 'SUCCESS_CONFIRMED' AND end_time >= :from")
        fun countSuccessSince(from: Long): Flow<Int>

        @Query("SELECT COUNT(*) FROM bypass_detection_session WHERE result = 'SUCCESS_CONFIRMED'")
        fun countSuccessAll(): Flow<Int>

        @Query("SELECT AVG(confirmed_latency_ms) FROM bypass_detection_session WHERE result = 'SUCCESS_CONFIRMED' AND confirmed_latency_ms > 0")
        fun avgConfirmedLatency(): Flow<Long?>

        @Query(
            """
            DELETE FROM bypass_detection_session
            WHERE end_time > 0 AND end_time < :cutoff
            """
        )
        suspend fun deleteBefore(cutoff: Long): Int

        @Query("DELETE FROM bypass_detection_session")
        suspend fun deleteAll(): Int
    }

    companion object {
        const val BypassStrategyModeDefault = "CONSERVATIVE"
    }
}
