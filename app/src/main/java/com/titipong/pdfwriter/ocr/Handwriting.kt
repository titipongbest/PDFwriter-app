package com.titipong.pdfwriter.ocr

import android.graphics.RectF
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import com.titipong.pdfwriter.model.InkStroke
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handwriting to text with Google ML Kit Digital Ink Recognition. Runs on the device: the language
 * model (about 20 MB) is downloaded once, then it works offline.
 */
object Handwriting {

    /** "th" or "en". Null if ML Kit has no model for it. */
    fun model(lang: String): DigitalInkRecognitionModel? = try {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang)?.let { DigitalInkRecognitionModel.builder(it).build() }
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }

    suspend fun isDownloaded(m: DigitalInkRecognitionModel): Boolean =
        RemoteModelManager.getInstance().isModelDownloaded(m).await()

    suspend fun download(m: DigitalInkRecognitionModel) {
        RemoteModelManager.getInstance().download(m, DownloadConditions.Builder().build()).await()
    }

    /** One recognized line: its text and the box of its strokes (normalized page coordinates). */
    class Line(val text: String, val box: RectF)

    private fun box(st: InkStroke) = RectF(
        st.points.minOf { it.x }, st.points.minOf { it.y }, st.points.maxOf { it.x }, st.points.maxOf { it.y }
    )

    /**
     * Groups strokes into lines (by height on the page: marks above / below a line stay with it),
     * each kept in writing order, so several lines can be written at once.
     */
    private fun lines(strokes: List<InkStroke>): List<List<InkStroke>> {
        class L(var top: Float, var bottom: Float, val items: MutableList<Pair<Int, InkStroke>>)
        val out = ArrayList<L>()
        val sorted = strokes.withIndex().filter { it.value.points.isNotEmpty() }
            .sortedByDescending { box(it.value).height() } // big strokes first define the lines
        for ((i, st) in sorted) {
            val b = box(st)
            val line = out.firstOrNull { l ->
                val h = (l.bottom - l.top).coerceAtLeast(1e-4f)
                b.centerY() > l.top - h * 0.6f && b.centerY() < l.bottom + h * 0.6f
            }
            if (line == null) {
                out.add(L(b.top, b.bottom, mutableListOf(i to st)))
            } else {
                line.items.add(i to st)
                line.top = minOf(line.top, b.top)
                line.bottom = maxOf(line.bottom, b.bottom)
            }
        }
        return out.sortedBy { it.top }.map { l -> l.items.sortedBy { it.first }.map { it.second } }
    }

    /** Recognizes [strokes] (any number of lines). Empty if nothing was understood. */
    suspend fun recognize(model: DigitalInkRecognitionModel, strokes: List<InkStroke>): List<Line> {
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        try {
            val result = ArrayList<Line>()
            for (line in lines(strokes)) {
                val ink = Ink.builder()
                var b: RectF? = null
                for (st in line) {
                    val sb = Ink.Stroke.builder()
                    for (p in st.points) sb.addPoint(Ink.Point.create(p.x * 1000f, p.y * 1000f))
                    ink.addStroke(sb.build())
                    val r = box(st)
                    b = b?.apply { union(r) } ?: RectF(r)
                }
                val area = b ?: continue
                val ctx = RecognitionContext.builder()
                    .setPreContext("")
                    .setWritingArea(WritingArea(area.width() * 1000f, area.height() * 1000f))
                    .build()
                val text = recognizer.recognize(ink.build(), ctx).await().candidates.firstOrNull()?.text?.trim()
                if (!text.isNullOrEmpty()) result.add(Line(text, area))
            }
            return result
        } finally {
            recognizer.close()
        }
    }
}
