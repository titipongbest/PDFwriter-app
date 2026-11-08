package com.titipong.pdfwriter.search

import android.graphics.RectF
import com.titipong.pdfwriter.pdf.PageGeometry
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Text of every page with the position of each character, for search and highlighting.
 * Includes OCR text layers (they're ordinary, invisible PDF text).
 *
 * Matching ignores whitespace and case, because Thai has no spaces between words and
 * OCR / PDF extraction inserts them inconsistently.
 */
class SearchIndex private constructor(private val pages: List<PageText>) {

    /** [compact]: lowercase text without whitespace; [rects][i] is where compact[i] is on the page. */
    class PageText(
        val compact: String,
        val rects: List<RectF>,
        val display: String,
        val compactToDisplay: IntArray
    )

    class Hit(val page: Int, val rects: List<RectF>, val snippet: String)

    val isEmpty: Boolean get() = pages.all { it.compact.isEmpty() }

    fun find(query: String): List<Hit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        pages.forEachIndexed { pageIndex, pt ->
            var from = 0
            while (true) {
                val at = pt.compact.indexOf(q, from)
                if (at < 0) break
                val end = at + q.length
                hits.add(Hit(pageIndex, lineRects(pt.rects.subList(at, end)), snippet(pt, at, end)))
                from = at + max(1, q.length)
                if (hits.size >= MAX_HITS) return hits
            }
        }
        return hits
    }

    /** Merges character boxes into one box per text line. */
    private fun lineRects(chars: List<RectF>): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (r in chars) {
            val c = cur
            if (c != null && abs(r.centerY() - c.centerY()) < max(c.height(), r.height()) * 0.6f) {
                c.union(r)
            } else {
                cur = RectF(r).also { out.add(it) }
            }
        }
        return out
    }

    private fun snippet(pt: PageText, start: Int, end: Int): String {
        val ds = pt.compactToDisplay[start]
        val de = pt.compactToDisplay[end - 1] + 1
        val from = max(0, ds - 30)
        val to = min(pt.display.length, de + 30)
        val pre = if (from > 0) "…" else ""
        val post = if (to < pt.display.length) "…" else ""
        return pre + pt.display.substring(from, to).replace(Regex("\\s+"), " ").trim() + post
    }

    companion object {
        private const val MAX_HITS = 2000

        fun normalize(s: String): String =
            s.lowercase(Locale.ROOT).filterNot { it.isWhitespace() }

        fun build(file: File, onProgress: (Int, Int) -> Unit): SearchIndex {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val total = doc.numberOfPages
                val widths = FloatArray(total)
                var i = 0
                for (page in doc.pages) {
                    widths[i++] = PageGeometry.of(page).displayWidth
                }
                val stripper = PositionStripper()
                val out = ArrayList<PageText>(total)
                for (p in 0 until total) {
                    stripper.reset(widths[p])
                    stripper.startPage = p + 1
                    stripper.endPage = p + 1
                    try {
                        stripper.getText(doc)
                    } catch (e: Exception) {
                        stripper.reset(widths[p])
                    }
                    out.add(stripper.result())
                    onProgress(p + 1, total)
                }
                return SearchIndex(out)
            }
        }
    }

    private class PositionStripper : PDFTextStripper() {
        private var pageWidth = 1f
        private val compact = StringBuilder()
        private val display = StringBuilder()
        private val rects = ArrayList<RectF>()
        private val map = ArrayList<Int>()

        init {
            sortByPosition = true
        }

        fun reset(width: Float) {
            pageWidth = if (width > 0f) width else 1f
            compact.setLength(0)
            display.setLength(0)
            rects.clear()
            map.clear()
        }

        fun result() = PageText(compact.toString(), ArrayList(rects), display.toString(), map.toIntArray())

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            for (tp in textPositions) {
                val u = tp.unicode ?: continue
                val w = pageWidth
                val h = tp.heightDir.coerceAtLeast(1f)
                val r = RectF(tp.x / w, (tp.y - h) / w, (tp.x + tp.widthDirAdj) / w, (tp.y + h * 0.2f) / w)
                for (ch in u) {
                    display.append(ch)
                    if (ch.isWhitespace()) continue
                    compact.append(ch.lowercaseChar())
                    rects.add(r)
                    map.add(display.length - 1)
                }
            }
        }

        override fun writeWordSeparator() {
            display.append(' ')
        }

        override fun writeLineSeparator() {
            display.append(' ')
        }

        override fun writeParagraphEnd() {
            display.append(' ')
        }
    }
}
