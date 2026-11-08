package com.titipong.pdfwriter.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Thai + English OCR with Tesseract, entirely on the device.
 * Not thread-safe: use one instance from one coroutine at a time.
 */
class OcrEngine(context: Context) : Closeable {

    /** A recognized word; [box] is normalized to the image width (x 0..1, y in width units). */
    class Word(val text: String, val box: RectF)

    private val api = TessBaseAPI()

    init {
        val dataDir = ensureData(context)
        if (!api.init(dataDir.absolutePath + "/", LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            throw IOException("OCR init failed")
        }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
    }

    fun recognize(bitmap: Bitmap): List<Word> {
        val words = ArrayList<Word>()
        val w = bitmap.width.toFloat()
        api.setImage(bitmap)
        api.getUTF8Text() // runs recognition
        val it = api.getResultIterator() ?: return words
        try {
            it.begin()
            do {
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (!text.isNullOrEmpty() && r.width() > 0 && r.height() > 0) {
                    words.add(Word(text, RectF(r.left / w, r.top / w, r.right / w, r.bottom / w)))
                }
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
            api.clear()
        }
        return mergeThai(words)
    }

    /**
     * Tesseract often returns Thai as tiny pieces: single letters, and tone marks / vowels above or
     * below as separate "words". Written into the PDF like that, the marks (zero width on their
     * own) were lost and copied text had spaces between letters. Joins them back: a piece made only
     * of combining marks goes into the previous word; touching Thai pieces on the same line are joined.
     */
    private fun mergeThai(input: List<Word>): List<Word> {
        val out = ArrayList<Word>(input.size)
        for (w in input) {
            val prev = out.lastOrNull()
            if (prev != null) {
                val marksOnly = w.text.all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
                val sameLine = overlapY(prev.box, w.box) > 0.4f * minOf(prev.box.height(), w.box.height())
                val h = maxOf(prev.box.height(), w.box.height())
                val touching = w.box.left - prev.box.right < 0.18f * h
                if (marksOnly || (sameLine && touching && isThai(prev.text.last()) && isThai(w.text.first()))) {
                    val box = RectF(prev.box).apply { union(w.box) }
                    out[out.size - 1] = Word(prev.text + w.text, box)
                    continue
                }
            }
            out.add(w)
        }
        return out
    }

    private fun overlapY(a: RectF, b: RectF): Float = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)

    private fun isThai(c: Char): Boolean = c in '\u0e00'..'\u0e7f'


    /** Asks a running [recognize] to stop early (safe from another thread). */
    fun stop() = api.stop()

    override fun close() = api.recycle()

    companion object {
        private const val LANGUAGES = "tha+eng"
        private val FILES = listOf("tha.traineddata", "eng.traineddata")
        /** Change when the bundled models change, so devices replace their copied files. */
        private const val MODEL_SET = "tessdata_best-1"

        /** Tesseract needs real files: copy the bundled language data out of the APK once. */
        fun ensureData(context: Context): File {
            val root = File(context.filesDir, "tess")
            val dir = File(root, "tessdata").apply { mkdirs() }
            val stamp = File(dir, "models.txt")
            if (!stamp.exists() || stamp.readText() != MODEL_SET) {
                FILES.forEach { File(dir, it).delete() }
            }
            for (name in FILES) {
                val target = File(dir, name)
                if (target.exists() && target.length() > 0) continue
                val tmp = File(dir, "$name.tmp")
                context.assets.open("tessdata/$name").use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.renameTo(target)
            }
            stamp.writeText(MODEL_SET)
            return root
        }
    }
}
