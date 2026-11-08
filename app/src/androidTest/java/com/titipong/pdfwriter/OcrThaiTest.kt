package com.titipong.pdfwriter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.titipong.pdfwriter.ocr.OcrEngine
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Thai OCR end to end: image -> OCR words -> PDF text layer -> extracted text; counts tone marks. */
@RunWith(AndroidJUnit4::class)
class OcrThaiTest {
    private val lines = listOf(
        "ผู้ประกอบวิชาชีพกฎหมายแบ่งได้เป็น 2 ประเภท ได้แก่ ผู้ประกอบวิชาชีพกฎหมายที่เกี่ยวข้อง",
        "กับศาลโดยตรง คือ ผู้พิพากษา พนักงานอัยการและทนายความ กับผู้ประกอบวิชาชีพกฎหมาย",
        "ที่ไม่เกี่ยวข้องกับศาลโดยตรง คือ นิติกร นายทหารพระธรรมนูญ เป็นต้น",
        "วิชากฎหมาย เป็นวิชาชีพซึ่งต้องศึกษาอบรมในวิทยาการทางความคิดยิ่งกว่าการใช้มือหรือ"
    )
    private fun tones(s: String) = s.count { it in '่'..'๋' }

    @Test
    fun thaiToneMarksSurvive() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val w = 2000
        val bmp = Bitmap.createBitmap(w, 2828, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f }
        lines.forEachIndexed { i, l -> c.drawText(l, 80f, 200f + i * 80f, p) }
        val truth = lines.joinToString(" ")

        val words = OcrEngine(ctx).use { it.recognize(bmp) }
        val ocrText = words.joinToString(" ") { it.text }
        Perf.log("ocr-test ocr tones=${tones(ocrText)}/${tones(truth)} text=$ocrText")

        // A one-page PDF with the image, like a scanned page.
        val dir = File(ctx.cacheDir, "ocrtest").apply { mkdirs() }
        val src = File(dir, "src.pdf")
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, 595, 842), null)
        pdf.finishPage(page)
        src.outputStream().use { pdf.writeTo(it) }
        pdf.close()

        val out1 = File(dir, "ocr1.pdf")
        PdfAnnotationIO.addTextLayer(src, mapOf(0 to words), out1)
        val text1 = PdfAnnotationIO.extractText(out1)[0]
        Perf.log("ocr-test layer tones=${tones(text1)}/${tones(truth)} words=${words.size} font=${PdfAnnotationIO.bundledThaiFont} text=$text1")

        // OCR the same page again: the old layer must be replaced, not doubled.
        val out2 = File(dir, "ocr2.pdf")
        PdfAnnotationIO.addTextLayer(out1, mapOf(0 to words), out2)
        val text2 = PdfAnnotationIO.extractText(out2)[0]
        Perf.log("ocr-test again tones=${tones(text2)} length1=${text1.length} length2=${text2.length}")
    }
}
