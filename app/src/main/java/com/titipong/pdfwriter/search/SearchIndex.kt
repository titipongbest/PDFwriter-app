package com.titipong.pdfwriter.search

import android.graphics.RectF
import com.titipong.pdfwriter.pdf.PageGeometry
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Text of every page with the position of each character, for search and highlighting.
 * Includes OCR text layers (they're ordinary, invisible PDF text).
 *
 * Matching ignores whitespace and case, because Thai has no spaces between words and
 * OCR / PDF extraction inserts them inconsistently.
 *
 * Pages are read lazily, one at a time, on a low-priority thread: the page being read is prepared
 * for text selection right away, and the whole document only when searching.
 */
class SearchIndex(private val file: File, private val pageCount: Int) {

    private val texts = AtomicReferenceArray<PageText>(pageCount)
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-text").apply { priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private var doc: PDDocument? = null
    private var stripper: PositionStripper? = null
    @Volatile
    private var closed = false

    private val pages: List<PageText?>
        get() = List(pageCount) { texts.get(it) }

    fun isReady(page: Int): Boolean = page in 0 until pageCount && texts.get(page) != null
    val allReady: Boolean get() = (0 until pageCount).all { texts.get(it) != null }

    /** Reads the text of [page] if it hasn't been read yet. */
    suspend fun ensurePage(page: Int) {
        if (page !in 0 until pageCount || texts.get(page) != null || closed) return
        withContext(dispatcher) { extract(page) }
    }

    /** Reads every page (for search), reporting progress. */
    suspend fun ensureAll(onProgress: (Int, Int) -> Unit) {
        for (p in 0 until pageCount) {
            coroutineContext.ensureActive()
            ensurePage(p)
            if (p % 5 == 4 || p == pageCount - 1) onProgress(p + 1, pageCount)
        }
    }

    private fun extract(page: Int) {
        if (closed || texts.get(page) != null) return
        val d = doc ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also { doc = it }
        val st = stripper ?: PositionStripper().also { stripper = it }
        val width = try { PageGeometry.of(d.getPage(page)).displayWidth } catch (e: Exception) { 1f }
        st.reset(width)
        st.startPage = page + 1
        st.endPage = page + 1
        try {
            st.getText(d)
        } catch (e: Exception) {
            st.reset(width)
        }
        texts.set(page, st.result())
    }

    fun close() {
        if (closed) return
        closed = true
        dispatcher.executor.execute {
            try { doc?.close() } catch (e: Exception) { }
            doc = null
            dispatcher.close()
        }
    }

    /** [compact]: lowercase text without whitespace; [rects][i] is where compact[i] is on the page. */
    class PageText(
        val compact: String,
        val rects: List<RectF>,
        val display: String,
        val compactToDisplay: IntArray
    )

    class Hit(val page: Int, val rects: List<RectF>, val snippet: String)

    val isEmpty: Boolean get() = pages.all { it == null || it.compact.isEmpty() }

    fun find(query: String): List<Hit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        pages.forEachIndexed { pageIndex, pt ->
            if (pt == null) return@forEachIndexed
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

    /** The page's text lines as boxes (for alignment guides); empty until the page is read. */
    fun lineBoxes(page: Int): List<RectF> {
        lineCache[page]?.let { return it }
        val pt = texts.getOrNull(page) ?: return emptyList()
        return lineRects(pt.rects).also { lineCache[page] = it }
    }
    private val lineCache = java.util.concurrent.ConcurrentHashMap<Int, List<RectF>>()

    fun hasText(page: Int): Boolean = texts.getOrNull(page)?.compact?.isNotEmpty() == true

    /**
     * Index of the character at (or nearest to) a normalized point. With [maxDistance] > 0,
     * returns -1 when nothing is that close (e.g. a tap on blank margin).
     */
    fun charAt(page: Int, nx: Float, ny: Float, maxDistance: Float = 0f): Int {
        val pt = texts.getOrNull(page) ?: return -1
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
        val pt = texts.getOrNull(page) ?: return i..i
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
        val pt = texts.getOrNull(page) ?: return emptyList()
        val a = min(from, to).coerceIn(0, pt.rects.size - 1)
        val b = max(from, to).coerceIn(0, pt.rects.size - 1)
        if (pt.rects.isEmpty()) return emptyList()
        return lineRects(pt.rects.subList(a, b + 1))
    }

    /** The selected text with its original spacing. */
    fun rangeText(page: Int, from: Int, to: Int): String {
        val pt = texts.getOrNull(page) ?: return ""
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

        private fun <T> AtomicReferenceArray<T>.getOrNull(i: Int): T? = if (i in 0 until length()) get(i) else null
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
                val h = maxOf(tp.heightDir, tp.fontSizeInPt * 0.75f, 1f) // some fonts report a tiny glyph height
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
