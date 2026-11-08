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

    // ------------------------------------------------------------ selection (view mode)

    fun hasText(page: Int): Boolean = pages.getOrNull(page)?.compact?.isNotEmpty() == true

    /**
     * Index of the character at (or nearest to) a normalized point. With [maxDistance] > 0,
     * returns -1 when nothing is that close (e.g. a tap on blank margin).
     */
    fun charAt(page: Int, nx: Float, ny: Float, maxDistance: Float = 0f): Int {
        val pt = pages.getOrNull(page) ?: return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        pt.rects.forEachIndexed { i, r ->
            val dx = if (nx < r.left) r.left - nx else if (nx > r.right) nx - r.right else 0f
            // Weigh vertical distance more, so a point between lines picks its own line.
            val dy = if (ny < r.top) r.top - ny else if (ny > r.bottom) ny - r.bottom else 0f
            val d = dx + dy * 3f
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        if (maxDistance > 0f && bestD > maxDistance) return -1
        return best
    }

    /** The word around character [i]. Thai words are found with ICU's dictionary word breaker. */
    fun wordAt(page: Int, i: Int): IntRange {
        val pt = pages.getOrNull(page) ?: return i..i
        if (i !in pt.compact.indices) return i..i
        val disp = pt.compactToDisplay[i]
        val bi = java.text.BreakIterator.getWordInstance(Locale("th"))
        bi.setText(pt.display)
        var end = bi.following(disp)
        if (end == java.text.BreakIterator.DONE) end = pt.display.length
        var start = bi.previous()
        if (start == java.text.BreakIterator.DONE || start > disp) start = disp
        // Map the display range back to compact indices.
        var s = i
        while (s > 0 && pt.compactToDisplay[s - 1] >= start) s--
        var e = i
        while (e + 1 < pt.compact.length && pt.compactToDisplay[e + 1] < end) e++
        return s..e
    }

    fun rangeRects(page: Int, from: Int, to: Int): List<RectF> {
        val pt = pages.getOrNull(page) ?: return emptyList()
        val a = min(from, to).coerceIn(0, pt.rects.size - 1)
        val b = max(from, to).coerceIn(0, pt.rects.size - 1)
        if (pt.rects.isEmpty()) return emptyList()
        return lineRects(pt.rects.subList(a, b + 1))
    }

    /** The selected text with its original spacing. */
    fun rangeText(page: Int, from: Int, to: Int): String {
        val pt = pages.getOrNull(page) ?: return ""
        if (pt.compact.isEmpty()) return ""
        val a = min(from, to).coerceIn(0, pt.compact.length - 1)
        val b = max(from, to).coerceIn(0, pt.compact.length - 1)
        val ds = pt.compactToDisplay[a]
        val de = pt.compactToDisplay[b] + 1
        return pt.display.substring(ds, de).replace(Regex("[ \\t]+"), " ").trim()
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
