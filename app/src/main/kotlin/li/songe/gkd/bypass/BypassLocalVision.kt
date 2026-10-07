package li.songe.gkd.bypass

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.songe.gkd.a11y.A11yCommonImpl
import li.songe.gkd.util.ScreenUtils
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal data class BypassVisualFrame(
    val capturedAt: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val texts: List<BypassVisualText>,
    val exit: BypassVisualExit?,
    val regionColors: IntArray?,
)

/** Bundled Chinese model. Cropped pixels and OCR text never leave memory or enter history. */
internal class BypassLocalVision(private val service: A11yCommonImpl) {
    private val clientHolder = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val client by clientHolder
    private var lastCaptureAt = 0L
    private val readLock = Mutex()
    private var closed = false
    var failure: String? = null
        private set

    suspend fun warmUp() {
        val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            client.process(InputImage.fromBitmap(blank, 0)).awaitCompletion()
        } finally {
            blank.recycle()
        }
    }

    suspend fun read(reference: BypassVisualExit? = null): BypassVisualFrame? = readLock.withLock {
        if (closed) return@withLock null
        readInternal(reference)
    }

    private suspend fun readInternal(reference: BypassVisualExit?): BypassVisualFrame? {
        delay((350L - (SystemClock.elapsedRealtime() - lastCaptureAt)).coerceAtLeast(0L))
        val capturedAt = SystemClock.elapsedRealtime()
        lastCaptureAt = capturedAt
        failure = null
        val full = service.screenshot() ?: run {
            failure = "CAPTURE_UNAVAILABLE code=${service.screenshotFailureCode ?: -1}"
            return null
        }
        var software: Bitmap? = null
        var crop: Bitmap? = null
        try {
            // A hardware bitmap cannot be read by ML Kit / getPixel on the CPU.
            software = full.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val imageWidth = full.width
            val imageHeight = full.height
            val size = ScreenUtils.getScreenSize()
            val top = (imageHeight * 0.03).toInt()
            val right = (imageWidth * 0.45).toInt()
            val bottom = (imageHeight * 0.24).toInt()
            if (right <= 0 || bottom <= top || size.width >= size.height) {
                failure = "DISPLAY_UNSUPPORTED"
                return null
            }
            crop = Bitmap.createBitmap(software, 0, top, right, bottom - top)
            // Release the rest of the screen before recognition. Only the left
            // corner is supplied to the recognizer, including on model errors.
            software.recycle()
            software = null
            full.recycle()
            val recognized = client.process(InputImage.fromBitmap(crop, 0)).awaitCompletion()
            val spans = extract(recognized).mapNotNull { span ->
                BypassVisualExitPolicy.mapBounds(
                    span.bounds.copy(t = span.bounds.t + top, b = span.bounds.b + top),
                    imageWidth, imageHeight, size.width, size.height,
                )?.let { BypassVisualText(span.text, it) }
            }
            if (spans.isEmpty() && BypassVisualExitPolicy.mapBounds(Bounds(0, top, right, bottom),
                    imageWidth, imageHeight, size.width, size.height) == null) {
                failure = "DISPLAY_MISMATCH"
                return null
            }
            val exit = BypassVisualExitPolicy.inspect(spans, size.width, size.height)
            val sample = (reference ?: exit)?.takeIf { it.screenWidth == size.width && it.screenHeight == size.height }?.let {
                sampleRegion(crop, it.bounds, imageWidth, imageHeight, size.width, size.height, top)
            }
            return BypassVisualFrame(capturedAt, size.width, size.height, spans, exit, sample)
        } finally {
            crop?.takeUnless { it.isRecycled }?.recycle()
            software?.takeUnless { it.isRecycled }?.recycle()
            full.takeUnless { it.isRecycled }?.recycle()
        }
    }

    private fun sampleRegion(crop: Bitmap, logical: Bounds, imageWidth: Int, imageHeight: Int,
                             screenWidth: Int, screenHeight: Int, top: Int): IntArray? {
        val l = logical.l * imageWidth / screenWidth
        val r = logical.r * imageWidth / screenWidth
        val t = logical.t * imageHeight / screenHeight - top
        val b = logical.b * imageHeight / screenHeight - top
        if (l < 0 || t < 0 || r > crop.width || b > crop.height || r <= l || b <= t) return null
        return IntArray(96) { i ->
            crop.getPixel((l + (r - l) * (i % 12 + 0.5) / 12).toInt().coerceAtMost(r - 1),
                (t + (b - t) * (i / 12 + 0.5) / 8).toInt().coerceAtMost(b - 1))
        }
    }

    private fun extract(text: Text): List<BypassVisualText> = buildList {
        fun addSpan(value: String, rect: android.graphics.Rect?) {
            if (rect != null && value.length in 1..24) add(BypassVisualText(value, Bounds(rect.left, rect.top, rect.right, rect.bottom)))
        }
        text.textBlocks.take(32).forEach { block -> block.lines.take(24).forEach { line ->
            if (size >= 512) return@buildList
            addSpan(line.text, line.boundingBox)
            val symbols = line.elements.flatMap { element ->
                addSpan(element.text, element.boundingBox)
                element.symbols
            }.take(80)
            // Chinese OCR may emit "广告|跳过" as one element. Symbol boxes
            // isolate the actual exit without estimating a character position.
            for (start in symbols.indices) {
                for (count in 1..6) {
                    if (size >= 512) return@buildList
                    if (start + count > symbols.size) break
                    val part = symbols.subList(start, start + count)
                    val boxes = part.mapNotNull { it.boundingBox }
                    if (boxes.size != count) continue
                    val bounds = Bounds(boxes.minOf { it.left }, boxes.minOf { it.top },
                        boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
                    add(BypassVisualText(part.joinToString("") { it.text }, bounds))
                }
            }
        } }
    }.distinct()

    suspend fun close() = readLock.withLock {
        closed = true
        // Failed SDK initialization must not be retried from a cancellation
        // finally block, where it would become an uncaught app-level crash.
        if (clientHolder.isInitialized()) clientHolder.value.close()
    }
}

/** Finish the local task before recycling its image, even if the service is being cancelled. */
private suspend fun <T> Task<T>.awaitCompletion(): T = suspendCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("LOCAL_OCR_FAILED"))
    }
}
