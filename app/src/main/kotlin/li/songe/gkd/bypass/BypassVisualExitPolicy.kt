package li.songe.gkd.bypass

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** OCR text stays in memory. Only a whitelisted exit label and its geometry may be logged. */
data class BypassVisualText(val text: String, val bounds: Bounds)

data class BypassVisualExit(
    val label: String,
    val bounds: Bounds,
    val countdown: Int,
    val screenWidth: Int,
    val screenHeight: Int,
)

/** A visual exit needs three adjacent signals; a bare close, X, or coordinate is never enough. */
object BypassVisualExitPolicy {
    private val exitLabels = setOf("跳过", "跳過", "关闭", "關閉", "关闭广告", "關閉廣告", "skip", "close", "closead")
    private val adLabels = setOf("广告", "廣告", "ad", "advertisement", "sponsored")
    private val timer = Regex("^([0-9]{1,2})(?:秒|s|sec|秒后)?$")

    fun isSupportedHost(packageName: String, activityName: String?, debug: Boolean = false): Boolean =
        BypassAdContextTracker.isMiniProgramAdActivity(packageName, activityName) ||
            (debug && packageName == "app.bypassads.testad" && activityName == "app.bypassads.testad.MainActivity")

    fun normalize(text: String): String = text.lowercase().replace(Regex("[\\s|｜·•:：()（）▼▽▾]"), "")

    fun signalSummary(texts: List<BypassVisualText>, width: Int, height: Int): String {
        val labels = texts.filter { inCorner(it.bounds, width, height) }.map { normalize(it.text) }.distinct()
        return "exits=${labels.count { it in exitLabels }} ad_labels=${labels.count { it in adLabels }} timers=${labels.count { timer.matches(it) }}"
    }

    fun inspect(texts: List<BypassVisualText>, screenWidth: Int, screenHeight: Int): BypassVisualExit? {
        if (screenWidth <= 0 || screenHeight <= screenWidth) return null
        val small = texts.filter { inCorner(it.bounds, screenWidth, screenHeight) }
        val ads = small.filter { normalize(it.text) in adLabels }
        val timers = small.mapNotNull { span ->
            timer.matchEntire(normalize(span.text))?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 0..30 }?.let { span to it }
        }
        // The smallest exact text box keeps the tap on the exit, not on the
        // "广告 | 跳过" pill's advertisement menu or divider.
        for (exit in small.filter { normalize(it.text) in exitLabels }.sortedBy { area(it.bounds) }) {
            val ad = ads.firstOrNull { adjacent(exit.bounds, it.bounds, screenWidth) && !overlaps(exit.bounds, it.bounds) } ?: continue
            val count = timers.firstOrNull { adjacent(exit.bounds, it.first.bounds, screenWidth) &&
                !overlaps(exit.bounds, it.first.bounds) && !overlaps(ad.bounds, it.first.bounds) } ?: continue
            return BypassVisualExit(
                label = if (normalize(exit.text) in setOf("跳过", "跳過", "skip")) "跳过" else "关闭",
                bounds = exit.bounds, countdown = count.second, screenWidth = screenWidth, screenHeight = screenHeight,
            )
        }
        return null
    }

    fun inCorner(bounds: Bounds, width: Int, height: Int): Boolean =
        bounds.l >= 0 && bounds.t >= height * 0.025 &&
            bounds.r <= width * 0.45 && bounds.b <= height * 0.24 &&
            bounds.r - bounds.l in 10..min(420, (width * 0.34).toInt()) &&
            bounds.b - bounds.t in 10..min(160, (height * 0.065).toInt())

    private fun adjacent(a: Bounds, b: Bounds, screenWidth: Int): Boolean {
        val h = max(a.b - a.t, b.b - b.t)
        val vertical = abs((a.t + a.b) / 2 - (b.t + b.b) / 2)
        val gap = max(0, max(a.l, b.l) - min(a.r, b.r))
        return vertical <= h * 0.85 && gap <= min(screenWidth * 0.22, h * 5.0)
    }

    private fun area(b: Bounds): Int = (b.r - b.l) * (b.b - b.t)
    private fun overlaps(a: Bounds, b: Bounds): Boolean =
        min(a.r, b.r) > max(a.l, b.l) && min(a.b, b.b) > max(a.t, b.t)

    /** Map the physical screenshot to the current default display's logical gesture coordinates. */
    fun mapBounds(bounds: Bounds, bitmapWidth: Int, bitmapHeight: Int, screenWidth: Int, screenHeight: Int): Bounds? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return null
        val aspectError = abs(bitmapWidth.toDouble() / bitmapHeight - screenWidth.toDouble() / screenHeight)
        if (aspectError > 0.025) return null // wrong display / rotation / unexpected letterboxing
        fun x(v: Int) = (v.toLong() * screenWidth / bitmapWidth).toInt()
        fun y(v: Int) = (v.toLong() * screenHeight / bitmapHeight).toInt()
        return Bounds(x(bounds.l), y(bounds.t), x(bounds.r), y(bounds.b))
    }

    fun sameExit(a: BypassVisualExit, b: BypassVisualExit): Boolean {
        if (a.screenWidth != b.screenWidth || a.screenHeight != b.screenHeight || a.label != b.label) return false
        val tolerance = max(12, (a.screenWidth * 0.025).toInt())
        return abs(a.bounds.l - b.bounds.l) <= tolerance && abs(a.bounds.t - b.bounds.t) <= tolerance &&
            abs(a.bounds.r - b.bounds.r) <= tolerance && abs(a.bounds.b - b.bounds.b) <= tolerance
    }

    /** Never tap the last countdown second or a cached frame that could now be the homepage. */
    fun canAct(first: BypassVisualExit, fresh: BypassVisualExit, capturedAt: Long, now: Long): Boolean =
        now >= capturedAt && now - capturedAt <= 750L && sameExit(first, fresh) &&
            fresh.countdown in 2..first.countdown

    fun sameRegionHasEvidence(texts: List<BypassVisualText>, exit: BypassVisualExit): Boolean = texts.any {
        val label = normalize(it.text)
        (label in exitLabels || label in adLabels) && inCorner(it.bounds, exit.screenWidth, exit.screenHeight) &&
            adjacent(exit.bounds, it.bounds, exit.screenWidth)
    }

    /** A disappearance at the natural countdown deadline is inconclusive. */
    fun verifyAbsenceBeforeExpiry(exit: BypassVisualExit, actionAt: Long, firstAbsentAt: Long, secondAbsentAt: Long): Boolean =
        firstAbsentAt > actionAt && secondAbsentAt - firstAbsentAt >= 250L &&
            secondAbsentAt - actionAt < (exit.countdown - 1) * 1_000L
}
