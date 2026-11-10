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
        check("text PDF") {
            val f = File(dir, "text.pdf")
            org.apache.pdfbox.pdmodel.PDDocument().use { d ->
                val pg = org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4)
                d.addPage(pg)
                org.apache.pdfbox.pdmodel.PDPageContentStream(d, pg).use { cs ->
                    cs.beginText()
                    cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD, 40f)
                    cs.newLineAtOffset(60f, 842f - 140f)
                    cs.showText("Highlight this line")
                    cs.newLineAtOffset(0f, -80f)
                    cs.showText("Underline this one")
                    cs.endText()
                }
                d.save(f)
            }
            val idx = com.titipong.pdfwriter.search.SearchIndex(f, 1)
            runBlocking { idx.ensurePage(0) }
            "${f.length()} bytes, hasText=${idx.hasText(0)}, char at line 1 = ${idx.charAt(0, 0.2f, 0.19f, 0.01f)}"
        }
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

    /** Like a person: open, add text, close the app (phase A); start again and look (phase B). */
    fun flow(phase: String, pdf: File, report: File) {
        val out = StringBuilder()
        fun log(x: String) { out.appendLine(x); report.appendText(x + "\n") }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        val app = AppState(scope)
        try {
            if (phase == "A") {
                runBlocking(kotlinx.coroutines.Dispatchers.Main) { app.open(pdf) }
                val doc = app.current ?: run { log("A: open failed"); return }
                app.annotate = true
                app.selectTool(com.titipong.pdfwriter.model.DrawTool.TEXT)
                runBlocking(kotlinx.coroutines.Dispatchers.Main) {
                    app.addText(doc, 0, 0.2f, 0.3f, "ข้อความทดสอบ Test")
                    doc.annotationsFor(0).strokes.add(InkStroke(mutableListOf(StrokePoint(0.1f, 0.1f, 0.5f), StrokePoint(0.4f, 0.2f, 0.5f)), Color.BLACK, 0.003f, false))
                    app.onItemAdded(doc, 0, doc.annotationsFor(0).strokes.last())
                }
                log("A: texts=${doc.pages[0]?.texts?.size} dirty=${doc.dirty}")
                kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(300) }
                javax.swing.SwingUtilities.invokeAndWait { app.saveAllBlocking() }
                log("A: after save saving=${doc.saving} failed=${doc.saveFailed} file=${pdf.length()}")
                val back = PdfAnnotationIO.read(pdf)
                log("A: in the PDF file: texts=${back.pages[0]?.texts?.size} strokes=${back.pages[0]?.strokes?.size}")
            } else {
                app.restoreTabs()
                var waited = 0
                while (app.current == null && waited < 200) { Thread.sleep(100); waited++ }
                val doc = app.current
                log("B: tabs=${app.docs.size} current=${doc?.file}")
                log("B: texts=${doc?.pages?.get(0)?.texts?.map { it.text }} strokes=${doc?.pages?.get(0)?.strokes?.size}")
            }
        } catch (e: Throwable) {
            log("$phase: ERROR ${e}\n" + e.stackTrace.take(8).joinToString("\n"))
        }
    }
}
