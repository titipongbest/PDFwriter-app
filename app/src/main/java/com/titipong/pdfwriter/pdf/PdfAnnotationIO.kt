package com.titipong.pdfwriter.pdf

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.titipong.pdfwriter.model.Bookmark
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.OutlineEntry
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextHighlight
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.ocr.OcrEngine
import com.titipong.pdfwriter.ui.PdfPageView
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads and writes the ink layer as standard PDF annotations, so other PDF apps
 * (Xodo, Adobe Acrobat, Drawboard, ...) can show, move and erase it:
 *   - pen / highlighter strokes -> /Ink annotations (with an appearance stream that keeps pen pressure)
 *   - text notes                -> /FreeText annotations
 * Extra /PW* keys let this app read its own data back exactly; other apps ignore them.
 */
object PdfAnnotationIO {

    private val INK = COSName.getPDFName("Ink")
    private val FREE_TEXT = COSName.getPDFName("FreeText")
    private val HIGHLIGHT = COSName.getPDFName("Highlight")
    private val UNDERLINE = COSName.getPDFName("Underline")
    private val QUAD_POINTS = COSName.getPDFName("QuadPoints")
    private val INK_LIST = COSName.getPDFName("InkList")
    private val BORDER = COSName.getPDFName("Border")
    private val PW_PRESSURE = COSName.getPDFName("PWPressure")
    private val PW_HIGHLIGHTER = COSName.getPDFName("PWHighlighter")
    private val PW_TEXT = COSName.getPDFName("PWText")
    private val PW_X = COSName.getPDFName("PWX")
    private val PW_Y = COSName.getPDFName("PWY")
    private val PW_COLOR = COSName.getPDFName("PWColor")
    private val PW_SIZE = COSName.getPDFName("PWSize")
    private val PW_BOOKMARK = COSName.getPDFName("PWBookmark")
    private val PW_OCR = COSName.getPDFName("PWOcr")

    /** Thai font shipped with the app (set by App); used before any system font. */
    @Volatile
    var bundledThaiFont: File? = null
    private val PREV = COSName.getPDFName("Prev")
    private val NEXT = COSName.getPDFName("Next")
    private val PARENT = COSName.getPDFName("Parent")

    private const val HIGHLIGHT_ALPHA = 0.35f
    const val UNDERLINE_RATIO = 0.09f
    private val writeLock = Mutex()

    // ------------------------------------------------------------------ read

    class ReadResult(
        val pages: MutableMap<Int, PageAnnotations>,
        val bookmarks: MutableList<Bookmark>,
        val outline: List<OutlineEntry>
    )

    /**
     * Reads every Ink annotation (from any app), this app's FreeText notes,
     * this app's bookmarks, and the file's own table of contents.
     */
    fun read(file: File): ReadResult {
        val result = mutableMapOf<Int, PageAnnotations>()
        val bookmarks = mutableListOf<Bookmark>()
        val outline = mutableListOf<OutlineEntry>()
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            var index = 0
            for (page in doc.pages) {
                val geo = PageGeometry.of(page)
                val pa = PageAnnotations()
                for (annot in page.annotations) {
                    val d = annot.cosObject
                    when (d.getDictionaryObject(COSName.SUBTYPE)) {
                        INK -> parseInk(d, geo)?.let { pa.strokes.addAll(it) }
                        FREE_TEXT -> parseText(d)?.let { pa.texts.add(it) }
                        HIGHLIGHT -> parseHighlight(d, geo, underline = false)?.let { pa.highlights.add(it) }
                        UNDERLINE -> parseHighlight(d, geo, underline = true)?.let { pa.highlights.add(it) }
                    }
                }
                if (!pa.isEmpty()) result[index] = pa
                index++
            }
            try {
                doc.documentCatalog.documentOutline?.let { readOutline(doc, it, 0, bookmarks, outline) }
            } catch (e: Exception) {
                // A broken outline shouldn't stop the ink from loading.
            }
        }
        return ReadResult(result, bookmarks, outline)
    }

    private fun readOutline(
        doc: PDDocument,
        node: PDOutlineNode,
        depth: Int,
        bookmarks: MutableList<Bookmark>,
        outline: MutableList<OutlineEntry>
    ) {
        for (item in node.children()) {
            val page = try {
                item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } ?: -1
            } catch (e: Exception) {
                -1
            }
            val title = item.title ?: ""
            if (item.cosObject.getBoolean(PW_BOOKMARK, false)) {
                if (page >= 0) bookmarks.add(Bookmark(title, page))
            } else {
                outline.add(OutlineEntry(title, page, depth))
                if (depth < 8) readOutline(doc, item, depth + 1, bookmarks, outline)
            }
        }
    }

    /** Plain text of each page, for reading mode. Blank string for pages without extractable text. */
    fun extractText(file: File): List<String> {
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val out = ArrayList<String>(doc.numberOfPages)
            for (i in 1..doc.numberOfPages) {
                stripper.startPage = i
                stripper.endPage = i
                out.add(
                    try {
                        stripper.getText(doc).trim()
                    } catch (e: Exception) {
                        ""
                    }
                )
            }
            return out
        }
    }

    private fun num(b: COSBase?): Float? = (b as? COSNumber)?.floatValue()

    private fun parseInk(d: COSDictionary, geo: PageGeometry): List<InkStroke>? = try {
        val inkList = d.getDictionaryObject(INK_LIST) as? COSArray
        if (inkList == null || geo.displayWidth <= 0f) {
            null
        } else {
            val color = readColor(d.getDictionaryObject(COSName.C) as? COSArray)
            val bs = d.getDictionaryObject(COSName.BS) as? COSDictionary
            val border = d.getDictionaryObject(BORDER) as? COSArray
            val widthPt = num(bs?.getDictionaryObject(COSName.W))
                ?: (if (border != null && border.size() >= 3) num(border.getObject(2)) else null)
                ?: 1f
            val opacity = num(d.getDictionaryObject(COSName.CA)) ?: 1f
            val highlighter = d.getBoolean(PW_HIGHLIGHTER, false) || opacity < 0.9f
            val pressures = d.getDictionaryObject(PW_PRESSURE) as? COSArray
            val strokes = ArrayList<InkStroke>()
            for (i in 0 until inkList.size()) {
                val path = inkList.getObject(i) as? COSArray ?: continue
                val pr = pressures?.let { if (i < it.size()) it.getObject(i) as? COSArray else null }
                val pts = ArrayList<StrokePoint>(path.size() / 2)
                var j = 0
                while (j + 1 < path.size()) {
                    val x = num(path.getObject(j)) ?: 0f
                    val y = num(path.getObject(j + 1)) ?: 0f
                    val n = geo.toNormalized(x, y)
                    val p = pr?.let { num(it.getObject(j / 2)) } ?: 0.5f
                    pts.add(StrokePoint(n[0], n[1], p))
                    j += 2
                }
                if (pts.isNotEmpty()) {
                    strokes.add(InkStroke(pts, color, max(widthPt, 0.3f) / geo.displayWidth, highlighter))
                }
            }
            strokes
        }
    } catch (e: Exception) {
        null
    }

    /** Any app's /Highlight: one box per quad (8 numbers), in display coordinates. */
    private fun parseHighlight(d: COSDictionary, geo: PageGeometry, underline: Boolean): TextHighlight? = try {
        val q = d.getDictionaryObject(QUAD_POINTS) as? COSArray
        if (q == null || q.size() < 8 || geo.displayWidth <= 0f) {
            null
        } else {
            val rects = ArrayList<android.graphics.RectF>()
            var i = 0
            while (i + 7 < q.size()) {
                var l = Float.MAX_VALUE
                var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE
                var b = -Float.MAX_VALUE
                for (k in 0 until 4) {
                    val n = geo.toNormalized(num(q.getObject(i + k * 2)) ?: 0f, num(q.getObject(i + k * 2 + 1)) ?: 0f)
                    l = min(l, n[0]); r = max(r, n[0]); t = min(t, n[1]); b = max(b, n[1])
                }
                if (r > l && b > t) rects.add(android.graphics.RectF(l, t, r, b))
                i += 8
            }
            if (rects.isEmpty()) null
            else TextHighlight(
                rects, readColor(d.getDictionaryObject(COSName.C) as? COSArray), d.getString(COSName.CONTENTS) ?: "", underline
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun parseText(d: COSDictionary): TextNote? = try {
        if (!d.getBoolean(PW_TEXT, false)) {
            null
        } else {
            TextNote(
                d.getString(COSName.CONTENTS) ?: "",
                num(d.getDictionaryObject(PW_X)) ?: 0f,
                num(d.getDictionaryObject(PW_Y)) ?: 0f,
                d.getInt(PW_COLOR, Color.BLACK),
                num(d.getDictionaryObject(PW_SIZE)) ?: 0.03f
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun readColor(arr: COSArray?): Int {
        if (arr == null) return Color.BLACK
        val c = FloatArray(arr.size()) { num(arr.getObject(it)) ?: 0f }
        fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
        return when (c.size) {
            1 -> Color.rgb(ch(c[0]), ch(c[0]), ch(c[0]))
            3 -> Color.rgb(ch(c[0]), ch(c[1]), ch(c[2]))
            4 -> Color.rgb(
                ch((1 - c[0]) * (1 - c[3])), ch((1 - c[1]) * (1 - c[3])), ch((1 - c[2]) * (1 - c[3]))
            )
            else -> Color.BLACK
        }
    }

    /** True for annotations this app owns in the file (and therefore rewrites). */
    private fun isManaged(annot: PDAnnotation, geo: PageGeometry): Boolean {
        val d = annot.cosObject
        return when (d.getDictionaryObject(COSName.SUBTYPE)) {
            INK -> parseInk(d, geo) != null // unreadable ink is left untouched
            FREE_TEXT -> d.getBoolean(PW_TEXT, false)
            HIGHLIGHT -> parseHighlight(d, geo, false) != null // unreadable markup is left untouched
            UNDERLINE -> parseHighlight(d, geo, true) != null
            else -> false
        }
    }

    // ------------------------------------------------------------------ write

    /**
     * Writes [pages] into a copy of [source] and stores the result at [dest].
     * Ink/FreeText annotations previously imported are replaced; everything else in the file is kept.
     */
    suspend fun write(
        context: Context,
        source: File,
        pages: Map<Int, PageAnnotations>,
        bookmarks: List<Bookmark>,
        dest: Uri,
        flatten: Boolean = false,
        dropFileOutline: Boolean = false
    ) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val out = File(context.cacheDir, "pdfwriter-out.pdf")
            buildInto(source, pages, bookmarks, out, flatten, dropFileOutline)
            val os = try {
                context.contentResolver.openOutputStream(dest, "wt")
            } catch (e: Exception) {
                context.contentResolver.openOutputStream(dest, "w")
            } ?: throw java.io.IOException("Cannot write")
            os.use { o -> out.inputStream().use { it.copyTo(o) } }
            out.delete()
        }
    }

    /** Same as [write], into a local file. */
    suspend fun writeFile(
        source: File,
        pages: Map<Int, PageAnnotations>,
        bookmarks: List<Bookmark>,
        out: File,
        flatten: Boolean = false,
        dropFileOutline: Boolean = false
    ) = writeLock.withLock {
        withContext(Dispatchers.IO) { buildInto(source, pages, bookmarks, out, flatten, dropFileOutline) }
    }

    private fun buildInto(
        source: File,
        pages: Map<Int, PageAnnotations>,
        bookmarks: List<Bookmark>,
        out: File,
        flatten: Boolean,
        dropFileOutline: Boolean
    ) {
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val fonts = FontSet(doc)
            var index = 0
            for (page in doc.pages) {
                val geo = PageGeometry.of(page)
                val existing = page.annotations
                val kept = existing.filterNot { isManaged(it, geo) }
                val snap = pages[index]
                index++
                if (kept.size == existing.size && (snap == null || snap.isEmpty())) continue
                val list = ArrayList<PDAnnotation>(kept)
                if (snap != null && geo.displayWidth > 0f) {
                    for (st in snap.strokes) if (st.points.isNotEmpty()) list.add(buildInk(doc, st, geo))
                    for (t in snap.texts) list.add(buildText(doc, t, geo, fonts))
                    for (h in snap.highlights) if (h.rects.isNotEmpty()) list.add(buildHighlight(doc, h, geo))
                }
                page.annotations = list
            }
            writeBookmarks(doc, bookmarks, dropFileOutline)
            if (flatten) flattenAnnotations(doc)
            doc.save(out)
        }
    }

    /**
     * Burns every visible annotation (ink, highlights, notes, other apps' markup) into the page
     * content so it can't be edited any more. Links stay clickable; form fields are left alone.
     */
    private fun flattenAnnotations(doc: PDDocument) {
        for (page in doc.pages) {
            val annots = page.annotations
            if (annots.isEmpty()) continue
            val keep = ArrayList<PDAnnotation>()
            var cs: PDPageContentStream? = null
            try {
                for (a in annots) {
                    if (a is PDAnnotationLink || a is PDAnnotationWidget) {
                        keep.add(a)
                        continue
                    }
                    if (a is PDAnnotationPopup || a.isHidden || a.isNoView) continue
                    val ap = a.normalAppearanceStream
                    val rect = a.rectangle
                    val bbox = ap?.bBox
                    if (ap == null || rect == null || bbox == null) {
                        keep.add(a) // nothing to draw it with; leave it as it is
                        continue
                    }
                    // Map the appearance (after its own /Matrix) onto the annotation's rectangle.
                    val m = ap.matrix
                    val pts = listOf(
                        m.transformPoint(bbox.lowerLeftX, bbox.lowerLeftY),
                        m.transformPoint(bbox.upperRightX, bbox.lowerLeftY),
                        m.transformPoint(bbox.lowerLeftX, bbox.upperRightY),
                        m.transformPoint(bbox.upperRightX, bbox.upperRightY)
                    )
                    val x0 = pts.minOf { it.x }
                    val x1 = pts.maxOf { it.x }
                    val y0 = pts.minOf { it.y }
                    val y1 = pts.maxOf { it.y }
                    if (x1 - x0 <= 0f || y1 - y0 <= 0f) continue
                    val sx = rect.width / (x1 - x0)
                    val sy = rect.height / (y1 - y0)
                    val stream = cs ?: PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                        .also { cs = it }
                    stream.saveGraphicsState()
                    val ca = num(a.cosObject.getDictionaryObject(COSName.CA)) ?: 1f
                    if (ca < 1f) {
                        val gs = PDExtendedGraphicsState()
                        gs.strokingAlphaConstant = ca
                        gs.nonStrokingAlphaConstant = ca
                        stream.setGraphicsStateParameters(gs)
                    }
                    stream.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - x0 * sx, rect.lowerLeftY - y0 * sy))
                    stream.drawForm(ap)
                    stream.restoreGraphicsState()
                }
            } finally {
                cs?.close()
            }
            page.annotations = keep
        }
    }

    /**
     * Rebuilds the outline's top level: the file's own entries stay (with their sub-trees),
     * this app's bookmarks are replaced by [bookmarks]. PdfBox can't delete outline items,
     * so the top-level list is re-linked into a fresh outline.
     */
    private fun writeBookmarks(doc: PDDocument, bookmarks: List<Bookmark>, dropFileOutline: Boolean = false) {
        val catalog = doc.documentCatalog
        val old = catalog.documentOutline
        val existing = old?.children()?.toList() ?: emptyList()
        // Extracted pages: the file's own table of contents would point at pages that aren't there.
        val kept = if (dropFileOutline) emptyList()
        else existing.filterNot { it.cosObject.getBoolean(PW_BOOKMARK, false) }
        if (!dropFileOutline && kept.size == existing.size && bookmarks.isEmpty()) return // nothing of ours, nothing to add

        val outline = PDDocumentOutline()
        for (item in kept) {
            item.cosObject.removeItem(PREV)
            item.cosObject.removeItem(NEXT)
            item.cosObject.removeItem(PARENT)
            outline.addLast(item)
        }
        val pageCount = doc.numberOfPages
        for (b in bookmarks.sortedBy { it.page }) {
            if (b.page !in 0 until pageCount) continue
            val item = PDOutlineItem()
            item.title = b.title
            item.setDestination(doc.getPage(b.page))
            item.cosObject.setBoolean(PW_BOOKMARK, true)
            outline.addLast(item)
        }
        outline.openNode()
        catalog.documentOutline = if (outline.hasChildren()) outline else null
    }

    /**
     * Writes a copy of [source] whose pages follow [order]: each entry is an index into the
     * source, or null for a new blank page (same size as its neighbour). Pages keep their
     * own annotations, links, and outline references because the same page objects are reused.
     */
    suspend fun rebuildPages(source: File, order: List<Int?>, out: File) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                val tree = doc.pages
                val old: List<PDPage> = tree.toList()
                for (p in old) tree.remove(p)
                val used = HashSet<Int>()
                order.forEachIndexed { i, src ->
                    if (src != null && used.add(src)) {
                        tree.add(old[src])
                    } else if (src != null) {
                        tree.add(duplicatePage(old[src]))
                    } else {
                        val refIndex = order.subList(0, i).lastOrNull { it != null }
                            ?: order.firstOrNull { it != null }
                        val ref = refIndex?.let { old[it] }
                        val box = ref?.cropBox ?: PDRectangle.A4
                        val blank = PDPage(PDRectangle(box.width, box.height))
                        if (ref != null) blank.rotation = ref.rotation
                        tree.add(blank)
                    }
                }
                doc.save(out)
            }
        }
    }

    /**
     * Writes a copy of [source] with an invisible text layer on the OCR'd pages, so the text can be
     * searched, selected and copied in any PDF app. The page's look doesn't change. [words] boxes are
     * normalized to the page as displayed.
     */
    suspend fun addTextLayer(source: File, words: Map<Int, List<OcrEngine.Word>>, out: File) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                val fonts = FontSet(doc)
                var index = 0
                for (page in doc.pages) {
                    val list = words[index]
                    index++
                    if (list.isNullOrEmpty()) continue
                    val geo = PageGeometry.of(page)
                    if (geo.displayWidth <= 0f) continue
                    // OCR again: replace this app's earlier text layer instead of stacking a second one.
                    if (page.cosObject.getBoolean(PW_OCR, false)) removeOcrLayer(page)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginMarkedContent(PW_OCR)
                        cs.transform(geo.displayUpMatrix())
                        cs.beginText()
                        cs.setRenderingMode(RenderingMode.NEITHER) // invisible
                        for (w in list) {
                            val runs = fonts.split(w.text)
                            if (runs.isEmpty()) continue
                            val boxH = w.box.height() * geo.displayWidth
                            val boxW = w.box.width() * geo.displayWidth
                            val size = (boxH * 0.85f).coerceAtLeast(1f)
                            var natural = 0f
                            for ((font, str) in runs) {
                                natural += try { font.getStringWidth(str) / 1000f * size } catch (e: Exception) { 0f }
                            }
                            if (natural <= 0f) continue
                            // Stretch the text so it covers the word's box (for selection / highlights).
                            cs.setHorizontalScaling((boxW / natural * 100f).coerceIn(10f, 1000f))
                            val x = geo.upX(w.box.left)
                            val baseline = geo.upY(w.box.bottom) + boxH * 0.15f
                            cs.setTextMatrix(Matrix.getTranslateInstance(x, baseline))
                            for ((font, str) in runs) {
                                cs.setFont(font, size)
                                cs.showText(str)
                            }
                        }
                        cs.endText()
                        cs.endMarkedContent()
                    }
                    page.cosObject.setBoolean(PW_OCR, true)
                }
                doc.save(out)
            }
        }
    }

    /**
     * Empties the content stream(s) holding this app's OCR text layer on [page]: tagged
     * "/PWOcr BMC", or (older versions) an appended stream of invisible text only. The stream keeps
     * its leading "Q" so the page's graphics state stays balanced.
     */
    private fun removeOcrLayer(page: PDPage) {
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS) as? COSArray ?: return
        for (i in 1 until contents.size()) {
            val stream = contents.getObject(i) as? com.tom_roush.pdfbox.cos.COSStream ?: continue
            val text = try {
                stream.createInputStream().use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            } catch (e: Exception) {
                continue
            }
            val trimmed = text.trimStart()
            val tagged = text.contains("/PWOcr BMC")
            val legacy = trimmed.startsWith("Q") && text.contains("3 Tr") && text.contains("BT") &&
                !Regex("""\s(Do|re|f|S|sh)\s""").containsMatchIn(text)
            if (!tagged && !legacy) continue
            stream.createOutputStream(COSName.FLATE_DECODE).use { it.write("Q\n".toByteArray(Charsets.ISO_8859_1)) }
        }
    }

    /**
     * A copy of [page] that shares its content and resources (allowed in PDF, keeps the file small).
     * Annotations aren't shared between pages: this app's ink is re-added from its own copy,
     * other annotations (e.g. links) stay only on the original.
     */
    private fun duplicatePage(page: PDPage): PDPage {
        val dict = COSDictionary(page.cosObject)
        dict.removeItem(COSName.PARENT)
        dict.removeItem(COSName.ANNOTS)
        dict.removeItem(COSName.STRUCT_PARENTS)
        dict.removeItem(PW_OCR)
        return PDPage(dict)
    }

    private fun rgb(color: Int) = floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)

    private fun colorArray(color: Int): COSArray {
        val arr = COSArray()
        for (v in rgb(color)) arr.add(COSFloat(v))
        return arr
    }

    private fun baseDict(subtype: COSName, rect: PDRectangle): COSDictionary {
        val d = COSDictionary()
        d.setItem(COSName.TYPE, COSName.ANNOT)
        d.setItem(COSName.SUBTYPE, subtype)
        d.setItem(COSName.RECT, rect.cosArray)
        d.setInt(COSName.F, 4) // Print
        d.setString(COSName.NM, "pdfwriter-" + UUID.randomUUID())
        d.setString(COSName.T, "PDFWriter")
        return d
    }

    private fun quantize(v: Float) = (v * 4f).roundToInt() / 4f

    private fun buildInk(doc: PDDocument, s: InkStroke, geo: PageGeometry): PDAnnotation {
        val basePt = max(s.width * geo.displayWidth, 0.3f)
        val path = COSArray()
        val press = COSArray()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in s.points) {
            val u = geo.toUser(p.x, p.y)
            path.add(COSFloat(u[0]))
            path.add(COSFloat(u[1]))
            press.add(COSFloat(p.pressure))
            minX = min(minX, u[0]); maxX = max(maxX, u[0])
            minY = min(minY, u[1]); maxY = max(maxY, u[1])
        }
        val pad = basePt * 0.8f + 2f
        val rect = PDRectangle(minX - pad, minY - pad, (maxX - minX) + 2 * pad, (maxY - minY) + 2 * pad)

        val d = baseDict(INK, rect)
        d.setItem(INK_LIST, COSArray().apply { add(path) })
        d.setItem(PW_PRESSURE, COSArray().apply { add(press) })
        d.setItem(COSName.C, colorArray(s.color))
        d.setItem(COSName.BS, COSDictionary().apply {
            setFloat(COSName.W, basePt)
            setItem(COSName.S, COSName.getPDFName("S"))
        })
        if (s.highlighter) {
            // Transparency lives in the appearance only; also setting /CA would apply it twice.
            d.setBoolean(PW_HIGHLIGHTER, true)
        }

        val annot = PDAnnotation.createAnnotation(d)
        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            cs.transform(geo.displayUpMatrix())
            if (s.highlighter) {
                val gs = PDExtendedGraphicsState()
                gs.strokingAlphaConstant = HIGHLIGHT_ALPHA
                gs.blendMode = BlendMode.MULTIPLY
                cs.setGraphicsStateParameters(gs)
            }
            val c = rgb(s.color)
            cs.setStrokingColor(c[0], c[1], c[2])
            cs.setLineJoinStyle(1)
            val pts = s.points
            fun ux(i: Int) = geo.upX(pts[i].x)
            fun uy(i: Int) = geo.upY(pts[i].y)
            if (s.highlighter || pts.size == 1) {
                cs.setLineCapStyle(1) // round caps (pen and highlighter)
                cs.setLineWidth(if (s.highlighter) basePt else basePt * PdfPageView.pressureScale(pts[0].pressure))
                cs.moveTo(ux(0), uy(0))
                if (pts.size == 1) cs.lineTo(ux(0) + 0.01f, uy(0))
                for (i in 1 until pts.size) cs.lineTo(ux(i), uy(i))
                cs.stroke()
            } else {
                // Pressure: consecutive segments of (nearly) equal width share one sub-path.
                cs.setLineCapStyle(1)
                fun segWidth(i: Int) =
                    quantize(basePt * PdfPageView.pressureScale((pts[i - 1].pressure + pts[i].pressure) / 2f))
                var i = 1
                while (i < pts.size) {
                    val w = segWidth(i)
                    cs.setLineWidth(w)
                    cs.moveTo(ux(i - 1), uy(i - 1))
                    cs.lineTo(ux(i), uy(i))
                    i++
                    while (i < pts.size && segWidth(i) == w) {
                        cs.lineTo(ux(i), uy(i))
                        i++
                    }
                    cs.stroke()
                }
            }
        }
        annot.appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
        return annot
    }

    private fun buildHighlight(doc: PDDocument, h: TextHighlight, geo: PageGeometry): PDAnnotation {
        val quads = COSArray()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (r in h.rects) {
            // Acrobat's order: upper-left, upper-right, lower-left, lower-right (relative to the text).
            for ((nx, ny) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
                val u = geo.toUser(nx, ny)
                quads.add(COSFloat(u[0]))
                quads.add(COSFloat(u[1]))
                minX = min(minX, u[0]); maxX = max(maxX, u[0])
                minY = min(minY, u[1]); maxY = max(maxY, u[1])
            }
        }
        val rect = PDRectangle(minX - 1f, minY - 1f, (maxX - minX) + 2f, (maxY - minY) + 2f)
        val d = baseDict(if (h.underline) UNDERLINE else HIGHLIGHT, rect)
        d.setItem(QUAD_POINTS, quads)
        d.setItem(COSName.C, colorArray(h.color))
        d.setFloat(COSName.CA, 1f)
        if (h.text.isNotEmpty()) d.setString(COSName.CONTENTS, h.text)

        val annot = PDAnnotation.createAnnotation(d)
        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            cs.transform(geo.displayUpMatrix())
            val c = rgb(h.color)
            cs.setNonStrokingColor(c[0], c[1], c[2])
            if (h.underline) {
                // A bar along the bottom of each line box.
                for (r in h.rects) {
                    val x = geo.upX(r.left)
                    val yBottom = geo.upY(r.bottom)
                    val thick = max((geo.upY(r.top) - yBottom) * UNDERLINE_RATIO, 0.6f)
                    cs.addRect(x, yBottom, geo.upX(r.right) - x, thick)
                }
            } else {
                val gs = PDExtendedGraphicsState()
                gs.blendMode = BlendMode.MULTIPLY // text under the highlight stays readable
                cs.setGraphicsStateParameters(gs)
                for (r in h.rects) {
                    val x = geo.upX(r.left)
                    val yTop = geo.upY(r.top)
                    val yBottom = geo.upY(r.bottom)
                    cs.addRect(x, yBottom, geo.upX(r.right) - x, yTop - yBottom)
                }
            }
            cs.fill()
        }
        annot.appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
        return annot
    }

    private fun buildText(doc: PDDocument, t: TextNote, geo: PageGeometry, fonts: FontSet): PDAnnotation {
        val size = max(t.size * geo.displayWidth, 2f)
        val runs = fonts.split(t.text)
        var textWidth = 0f
        for ((font, str) in runs) {
            textWidth += try { font.getStringWidth(str) / 1000f * size } catch (e: Exception) { size * str.length * 0.5f }
        }
        // Box in display-up space, then mapped to user space.
        val x0 = geo.upX(t.x) - 2f
        val x1 = geo.upX(t.x) + textWidth + 2f
        val baseY = geo.upY(t.y)
        val y0 = baseY - size * 0.35f
        val y1 = baseY + size * 1.05f
        val corners = listOf(
            upToUser(geo, x0, y0), upToUser(geo, x1, y0), upToUser(geo, x0, y1), upToUser(geo, x1, y1)
        )
        val rect = PDRectangle(
            corners.minOf { it[0] }, corners.minOf { it[1] },
            corners.maxOf { it[0] } - corners.minOf { it[0] },
            corners.maxOf { it[1] } - corners.minOf { it[1] }
        )
        val c = rgb(t.color)
        val d = baseDict(FREE_TEXT, rect)
        d.setString(COSName.CONTENTS, t.text)
        d.setString(COSName.DA, String.format(Locale.US, "/Helv %.1f Tf %.3f %.3f %.3f rg", size, c[0], c[1], c[2]))
        d.setItem(COSName.BS, COSDictionary().apply { setFloat(COSName.W, 0f) })
        d.setBoolean(PW_TEXT, true)
        d.setFloat(PW_X, t.x)
        d.setFloat(PW_Y, t.y)
        d.setInt(PW_COLOR, t.color)
        d.setFloat(PW_SIZE, t.size)

        val annot = PDAnnotation.createAnnotation(d)
        if (runs.isNotEmpty()) {
            val ap = PDAppearanceStream(doc)
            ap.bBox = rect
            ap.resources = PDResources()
            PDPageContentStream(doc, ap).use { cs ->
                cs.transform(geo.displayUpMatrix())
                cs.beginText()
                cs.setNonStrokingColor(c[0], c[1], c[2])
                cs.newLineAtOffset(geo.upX(t.x), baseY)
                for ((font, str) in runs) {
                    cs.setFont(font, size)
                    cs.showText(str)
                }
                cs.endText()
            }
            annot.appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
        }
        return annot
    }

    private fun upToUser(geo: PageGeometry, ux: Float, uy: Float): FloatArray =
        geo.toUser(ux / geo.displayWidth, (geo.displayHeight - uy) / geo.displayWidth)

    /** Picks, per character, the first font that has it: a system Thai font, Roboto, then Helvetica. */
    private class FontSet(private val doc: PDDocument) {
        private val fonts: List<PDFont> by lazy {
            val list = ArrayList<PDFont>()
            bundledThaiFont?.let { load(it) }?.let { list.add(it) }
            thaiFontFile()?.let { load(it) }?.let { list.add(it) }
            load(File("/system/fonts/Roboto-Regular.ttf"))?.let { list.add(it) }
            list.add(PDType1Font.HELVETICA)
            list
        }

        private fun load(f: File): PDFont? = try {
            if (f.exists()) PDType0Font.load(doc, f) else null
        } catch (e: Throwable) {
            null
        }

        private fun thaiFontFile(): File? {
            val files = File("/system/fonts").listFiles() ?: return null
            return files
                .filter { it.name.contains("thai", ignoreCase = true) && it.name.endsWith(".ttf", ignoreCase = true) }
                .sortedBy { f ->
                    val n = f.name.lowercase(Locale.ROOT)
                    var score = 0
                    if (!n.contains("sans")) score += 4
                    if (n.contains("ui")) score += 1
                    if (n.contains("bold")) score += 8
                    score
                }
                .firstOrNull()
        }

        private fun canEncode(font: PDFont, s: String): Boolean = try {
            font.encode(s)
            true
        } catch (e: Exception) {
            false
        }

        fun split(text: String): List<Pair<PDFont, String>> {
            val runs = ArrayList<Pair<PDFont, String>>()
            var current: PDFont? = null
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val ch = String(Character.toChars(cp))
                i += ch.length
                val font = fonts.firstOrNull { canEncode(it, ch) } ?: continue
                if (font !== current && sb.isNotEmpty()) {
                    runs.add(Pair(current!!, sb.toString()))
                    sb.setLength(0)
                }
                current = font
                sb.append(ch)
            }
            if (sb.isNotEmpty() && current != null) runs.add(Pair(current, sb.toString()))
            return runs
        }
    }
}
