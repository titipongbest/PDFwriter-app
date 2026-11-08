package com.titipong.pdfwriter

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        // Thai font bundled with the app (Noto Sans Thai, OFL) for text written into PDFs: system
        // Thai fonts differ by device and some lack characters (e.g. tone marks were dropped).
        try {
            val f = java.io.File(filesDir, "fonts/NotoSansThai-Regular.ttf")
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                val tmp = java.io.File(f.path + ".tmp")
                assets.open("fonts/NotoSansThai-Regular.ttf").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(f)
            }
            com.titipong.pdfwriter.pdf.PdfAnnotationIO.bundledThaiFont = f
        } catch (e: Exception) {
        }
        com.titipong.pdfwriter.pdf.PdfAnnotationIO.imagesDir = java.io.File(filesDir, "images").apply { mkdirs() }
    }

    companion object {
        /** Outlives the activity, so a PDF write started when leaving the app still finishes. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
