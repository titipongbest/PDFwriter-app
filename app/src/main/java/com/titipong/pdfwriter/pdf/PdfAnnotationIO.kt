package com.titipong.pdfwriter.pdf

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.ui.PdfPageView
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
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
    private val INK_LIST = COSName.getPDFName("InkList")
    private val BORDER = COSName.getPDFName("Border")
    private val PW_PRESSURE = COSName.getPDFName("PWPressure")
    private val PW_HIGHLIGHTER = COSName.getPDFName("PWHighlighter")
    private val PW_TEXT = COSName.getPDFName("PWText")
    private val PW_X = COSName.getPDFName("PWX")
    private val PW_Y = COSName.getPDFName("PWY")
    private val PW_COLOR = COSName.getPDFName("PWColor")
    private val PW_SIZE = COSName.getPDFName("PWSize")

    private const val HIGHLIGHT_ALPHA = 0.35f
    private val writeLock = Mutex()

    // ------------------------------------------------------------------ read

    /** Reads every Ink annotation (from any app) and this app's FreeText notes. */
    fun read(file: File): MutableMap<Int, PageAnnotations> {
        val result = mutableMapOf<Int, PageAnnotations>()
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
                    }
                }
                if (!pa.isEmpty()) result[index] = pa
                index++
            }
        }
        return result
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
            else -> false
        }
    }

    // ------------------------------------------------------------------ write

    /**
     * Writes [pages] into a copy of [source] and stores the result at [dest].
     * Ink/FreeText annotations previously imported are replaced; everything else in the file is kept.
     */
    suspend fun write(context: Context, source: File, pages: Map<Int, PageAnnotations>, dest: Uri) =
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val out = File(context.cacheDir, "pdfwriter-out.pdf")
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
                            for (s in snap.strokes) if (s.points.isNotEmpty()) list.add(buildInk(doc, s, geo))
                            for (t in snap.texts) list.add(buildText(doc, t, geo, fonts))
                        }
                        page.annotations = list
                    }
                    doc.save(out)
                }
                val os = try {
                    context.contentResolver.openOutputStream(dest, "wt")
                } catch (e: Exception) {
                    context.contentResolver.openOutputStream(dest, "w")
                } ?: throw java.io.IOException("Cannot write")
                os.use { o -> out.inputStream().use { it.copyTo(o) } }
                out.delete()
            }
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
            d.setFloat(COSName.CA, HIGHLIGHT_ALPHA)
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
                cs.setLineCapStyle(if (s.highlighter) 2 else 1)
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
