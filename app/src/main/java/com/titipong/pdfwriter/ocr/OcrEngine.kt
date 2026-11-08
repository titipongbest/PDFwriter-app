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
        val w = bitmap.width.toFloat()
        api.setImage(bitmap)
        try {
            val full = api.getUTF8Text() ?: return emptyList() // runs recognition
            fun norm(r: android.graphics.Rect) = RectF(r.left / w, r.top / w, r.right / w, r.bottom / w)
            // Each recognized character with its box, in reading order.
            val symbols = ArrayList<Pair<String, RectF>>()
            val words = ArrayList<Word>()
            api.getResultIterator()?.let { it ->
                try {
                    it.begin()
                    do {
                        val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL)
                        val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL)
                        if (!t.isNullOrEmpty()) symbols.add(t to norm(r))
                    } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL))
                } finally {
                    it.delete()
                }
            }
            byTesseractSpacing(full, symbols)?.let { return it }
            // Fallback: Tesseract's own words, with Thai pieces joined.
            api.getResultIterator()?.let { it ->
                try {
                    it.begin()
                    do {
                        val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
                        val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        if (!text.isNullOrEmpty() && r.width() > 0 && r.height() > 0) words.add(Word(text, norm(r)))
                    } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                } finally {
                    it.delete()
                }
            }
            return mergeThai(words)
        } finally {
            api.clear()
        }
    }

    /**
     * Words split where Tesseract's page text has spaces (its Thai spacing is good), each with the
     * union box of its characters. Thai has no spaces inside words, but Tesseract's word boxes
     * often cut Thai into single letters; its plain text doesn't. Null if the two don't line up.
     */
    private fun byTesseractSpacing(full: String, symbols: List<Pair<String, RectF>>): List<Word>? {
        val owner = ArrayList<Int>()
        val chars = StringBuilder()
        symbols.forEachIndexed { i, (t, _) -> for (c in t) if (!c.isWhitespace()) { chars.append(c); owner.add(i) } }
        val compact = full.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || compact != chars.toString()) return null
        val out = ArrayList<Word>()
        val text = StringBuilder()
        var box: RectF? = null
        var k = 0
        fun flush() {
            val b = box
            if (text.isNotEmpty() && b != null && b.width() > 0f && b.height() > 0f) out.add(Word(text.toString(), b))
            text.setLength(0)
            box = null
        }
        for (c in full) {
            if (c.isWhitespace()) {
                flush()
                continue
            }
            val r = symbols[owner[k++]].second
            text.append(c)
            box = box?.apply { union(r) } ?: RectF(r)
        }
        flush()
        return out
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
