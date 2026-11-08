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
        return words
    }

    /** Asks a running [recognize] to stop early (safe from another thread). */
    fun stop() = api.stop()

    override fun close() = api.recycle()

    companion object {
        private const val LANGUAGES = "tha+eng"
        private val FILES = listOf("tha.traineddata", "eng.traineddata")

        /** Tesseract needs real files: copy the bundled language data out of the APK once. */
        fun ensureData(context: Context): File {
            val root = File(context.filesDir, "tess")
            val dir = File(root, "tessdata").apply { mkdirs() }
            for (name in FILES) {
                val target = File(dir, name)
                if (target.exists() && target.length() > 0) continue
                val tmp = File(dir, "$name.tmp")
                context.assets.open("tessdata/$name").use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.renameTo(target)
            }
            return root
        }
    }
}
