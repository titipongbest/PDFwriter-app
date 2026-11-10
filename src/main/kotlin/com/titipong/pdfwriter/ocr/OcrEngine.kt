package com.titipong.pdfwriter.ocr

import com.sun.jna.Pointer
import com.titipong.pdfwriter.AppDirs
import com.titipong.pdfwriter.compat.RectF
import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.TessAPI1
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer

/**
 * Thai + English OCR with Tesseract (Tess4J), entirely on the computer.
 * Not thread-safe: use one instance from one coroutine at a time.
 */
class OcrEngine : Closeable {

    /** A recognized word; [box] is normalized to the image width (x 0..1, y in width units). */
    class Word(val text: String, val box: RectF)

    private val handle: ITessAPI.TessBaseAPI = TessAPI1.TessBaseAPICreate()

    init {
        val dir = dataDir()
        if (TessAPI1.TessBaseAPIInit2(handle, dir.absolutePath, LANGUAGES, ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY) != 0) {
            TessAPI1.TessBaseAPIDelete(handle)
            throw IOException("OCR init failed")
        }
        TessAPI1.TessBaseAPISetPageSegMode(handle, ITessAPI.TessPageSegMode.PSM_AUTO)
    }

    private fun text(p: Pointer?): String? {
        if (p == null) return null
        val s = p.getString(0, "UTF-8")
        TessAPI1.TessDeleteText(p)
        return s
    }

    fun recognize(image: BufferedImage): List<Word> {
        // 8-bit gray copy for Tesseract.
        val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        gray.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        val bytes = (gray.raster.dataBuffer as java.awt.image.DataBufferByte).data
        val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buf.flip()
        val w = image.width.toFloat()
        TessAPI1.TessBaseAPISetImage(handle, buf, gray.width, gray.height, 1, gray.width)
        try {
            if (TessAPI1.TessBaseAPIRecognize(handle, null) != 0) return emptyList()
            val full = text(TessAPI1.TessBaseAPIGetUTF8Text(handle)) ?: return emptyList()
            val symbols = iterate(ITessAPI.TessPageIteratorLevel.RIL_SYMBOL, w)
            byTesseractSpacing(full, symbols)?.let { return it }
            val words = iterate(ITessAPI.TessPageIteratorLevel.RIL_WORD, w).map { Word(it.first.trim(), it.second) }
                .filter { it.text.isNotEmpty() && it.box.width() > 0 && it.box.height() > 0 }
            return mergeThai(words)
        } finally {
            TessAPI1.TessBaseAPIClear(handle)
        }
    }

    private fun iterate(level: Int, w: Float): List<Pair<String, RectF>> {
        val out = ArrayList<Pair<String, RectF>>()
        val ri = TessAPI1.TessBaseAPIGetIterator(handle) ?: return out
        try {
            val pi = TessAPI1.TessResultIteratorGetPageIterator(ri)
            TessAPI1.TessPageIteratorBegin(pi)
            val l = IntBuffer.allocate(1)
            val t = IntBuffer.allocate(1)
            val r = IntBuffer.allocate(1)
            val b = IntBuffer.allocate(1)
            do {
                val s = text(TessAPI1.TessResultIteratorGetUTF8Text(ri, level)) ?: continue
                if (s.isEmpty()) continue
                TessAPI1.TessPageIteratorBoundingBox(pi, level, l, t, r, b)
                out.add(s to RectF(l.get(0) / w, t.get(0) / w, r.get(0) / w, b.get(0) / w))
            } while (TessAPI1.TessPageIteratorNext(pi, level) == ITessAPI.TRUE)
        } finally {
            TessAPI1.TessResultIteratorDelete(ri)
        }
        return out
    }

    /**
     * Words split where Tesseract's page text has spaces (its Thai spacing is good), each with the
     * union box of its characters. Null if the two don't line up.
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
            val bx = box
            if (text.isNotEmpty() && bx != null && bx.width() > 0f && bx.height() > 0f) out.add(Word(text.toString(), bx))
            text.setLength(0)
            box = null
        }
        for (c in full) {
            if (c.isWhitespace()) { flush(); continue }
            val r = symbols[owner[k++]].second
            text.append(c)
            box = box?.apply { union(r) } ?: RectF(r)
        }
        flush()
        return out
    }

    /** Joins Thai pieces Tesseract split apart (marks into the previous word, touching letters). */
    private fun mergeThai(input: List<Word>): List<Word> {
        val out = ArrayList<Word>(input.size)
        for (w in input) {
            val prev = out.lastOrNull()
            if (prev != null) {
                val marksOnly = w.text.all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
                val sameLine = minOf(prev.box.bottom, w.box.bottom) - maxOf(prev.box.top, w.box.top) >
                    0.4f * minOf(prev.box.height(), w.box.height())
                val h = maxOf(prev.box.height(), w.box.height())
                val touching = w.box.left - prev.box.right < 0.18f * h
                if (marksOnly || (sameLine && touching && isThai(prev.text.last()) && isThai(w.text.first()))) {
                    out[out.size - 1] = Word(prev.text + w.text, RectF(prev.box).apply { union(w.box) })
                    continue
                }
            }
            out.add(w)
        }
        return out
    }

    private fun isThai(c: Char) = c in '฀'..'๿'

    override fun close() {
        TessAPI1.TessBaseAPIEnd(handle)
        TessAPI1.TessBaseAPIDelete(handle)
    }

    companion object {
        const val LANGUAGES = "tha+eng"
        /** Pages are rendered at this width for OCR. */
        const val OCR_WIDTH = 2000

        fun dataDir(): File {
            val d = File(AppDirs.resources, "tessdata")
            if (!File(d, "tha.traineddata").exists()) throw IOException("OCR data missing: $d")
            return d
        }
    }
}
