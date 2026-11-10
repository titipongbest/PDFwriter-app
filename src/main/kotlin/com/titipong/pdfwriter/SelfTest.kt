package com.titipong.pdfwriter

import com.titipong.pdfwriter.compat.Color
import com.titipong.pdfwriter.compat.RectF
import com.titipong.pdfwriter.model.Bookmark
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextHighlight
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.ocr.OcrEngine
import com.titipong.pdfwriter.pdf.PageTemplate
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import com.titipong.pdfwriter.pdf.PdfTemplates
import com.titipong.pdfwriter.render.PageRenderer
import kotlinx.coroutines.runBlocking
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/** `PDFWriter.exe --selftest <report.txt>`: checks the parts that need Windows (used by the build). */
object SelfTest {
    fun run(report: File) {
        val out = StringBuilder()
        fun log(s: String) { out.appendLine(s); report.writeText(out.toString()) }
        fun check(name: String, block: () -> String) {
            val t = System.currentTimeMillis()
            try { log("OK   $name: ${block()} (${System.currentTimeMillis() - t} ms)") }
            catch (e: Throwable) { log("FAIL $name: ${e.javaClass.simpleName}: ${e.message}\n" + e.stackTrace.take(6).joinToString("\n") { "      at $it" }) }
        }
        log("resources = ${AppDirs.resources.absolutePath} exists=${AppDirs.resources.exists()}")
        log("thai font = ${PdfAnnotationIO.bundledThaiFont}")
        val dir = File(AppDirs.cache, "selftest").apply { deleteRecursively(); mkdirs() }
        val pdf = File(dir, "new.pdf")
        check("create PDF") { PdfTemplates.create(pdf, PageTemplate.LINED, false, 3); "${pdf.length()} bytes" }
        check("render") {
            val r = PageRenderer.open(pdf, null)
            val img = runBlocking { r.render(0, 1200) }
            r.close()
            "${r.pageCount} pages, ${img?.width}x${img?.height}"
        }
        check("save annotations") {
            val pa = PageAnnotations()
            pa.strokes.add(InkStroke(mutableListOf(StrokePoint(0.1f, 0.1f, 0.5f), StrokePoint(0.3f, 0.2f, 0.7f)), Color.BLACK, 0.003f, false))
            pa.strokes.add(InkStroke(mutableListOf(StrokePoint(0.1f, 0.3f, 0.5f), StrokePoint(0.6f, 0.3f, 0.5f)), Color.parseColor("#FACC15"), 0.02f, true))
            pa.texts.add(TextNote("สวัสดีครับ ทดสอบภาษาไทย\nHello", 0.1f, 0.5f, Color.parseColor("#DC2626"), 0.03f, 0.5f, 1))
            pa.highlights.add(TextHighlight(listOf(RectF(0.1f, 0.6f, 0.5f, 0.63f)), Color.parseColor("#FDE047"), "x"))
            val dest = File(dir, "annotated.pdf")
            runBlocking { PdfAnnotationIO.write(pdf, mapOf(0 to pa), listOf(Bookmark("B1", 1)), dest) }
            val back = PdfAnnotationIO.read(dest)
            val p = back.pages[0]
            "strokes=${p?.strokes?.size} texts=${p?.texts?.size} '${p?.texts?.firstOrNull()?.text?.replace("\n", "|")}' highlights=${p?.highlights?.size} bookmarks=${back.bookmarks.size}"
        }
        check("OCR Thai+English") {
            val img = BufferedImage(1600, 500, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = java.awt.Color.WHITE; g.fillRect(0, 0, img.width, img.height)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = java.awt.Color.BLACK
            g.font = Font("Tahoma", Font.PLAIN, 64)
            g.drawString("ภาษาไทยที่มีวรรณยุกต์ น้ำใจ", 60, 160)
            g.drawString("Hello PDFWriter 2026", 60, 320)
            g.dispose()
            val e = OcrEngine()
            val words = try { e.recognize(img) } finally { e.close() }
            words.joinToString(" ") { it.text }
        }
        check("handwriting helper") {
            val strokes = listOf(
                InkStroke(mutableListOf(StrokePoint(0.10f, 0.10f, 0.5f), StrokePoint(0.10f, 0.20f, 0.5f)), Color.BLACK, 0.003f, false),
                InkStroke(mutableListOf(StrokePoint(0.10f, 0.15f, 0.5f), StrokePoint(0.15f, 0.15f, 0.5f)), Color.BLACK, 0.003f, false),
                InkStroke(mutableListOf(StrokePoint(0.15f, 0.10f, 0.5f), StrokePoint(0.15f, 0.20f, 0.5f)), Color.BLACK, 0.003f, false),
                InkStroke(mutableListOf(StrokePoint(0.19f, 0.14f, 0.5f), StrokePoint(0.19f, 0.20f, 0.5f)), Color.BLACK, 0.003f, false)
            )
            try { Handwriting.recognize(strokes, "en").joinToString(" | ") { it.text } }
            catch (e: IllegalStateException) { "helper ran, said: ${e.message}" }
        }
        log("DONE")
    }
}
