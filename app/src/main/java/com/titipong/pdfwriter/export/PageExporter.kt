package com.titipong.pdfwriter.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.titipong.pdfwriter.model.Bookmark
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.math.max

/** "Extract pages": selected pages as a new PDF, or as one image per page. */
object PageExporter {

    enum class ImageFormat(val ext: String, val mime: String, val compress: Bitmap.CompressFormat, val quality: Int) {
        PNG("png", "image/png", Bitmap.CompressFormat.PNG, 100),
        JPG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 92)
    }

    private const val IMAGE_DPI = 200f
    private const val MAX_IMAGE_SIDE = 4000

    /** Ink of the selected pages, renumbered for the new document. */
    private fun remap(pages: Map<Int, PageAnnotations>, selected: List<Int>): Map<Int, PageAnnotations> =
        selected.mapIndexedNotNull { i, src -> pages[src]?.let { i to it.copy() } }.toMap()

    private fun remapBookmarks(bookmarks: List<Bookmark>, selected: List<Int>): List<Bookmark> =
        bookmarks.mapNotNull { b ->
            val i = selected.indexOf(b.page)
            if (i >= 0) Bookmark(b.title, i) else null
        }

    /** The selected pages as one PDF, ink still editable. */
    suspend fun extractPdf(
        context: Context,
        source: File,
        pages: Map<Int, PageAnnotations>,
        bookmarks: List<Bookmark>,
        selected: List<Int>,
        dest: Uri
    ) {
        val tmp = File(context.cacheDir, "extract-pages.pdf")
        try {
            PdfAnnotationIO.rebuildPages(source, selected, tmp)
            PdfAnnotationIO.write(
                context, tmp, remap(pages, selected), remapBookmarks(bookmarks, selected), dest,
                dropFileOutline = true
            )
        } finally {
            tmp.delete()
        }
    }

    /**
     * Renders the selected pages, ink included, to images. [openOutput] returns a stream for
     * (fileName, mimeType). Returns how many images were saved.
     */
    suspend fun extractImages(
        context: Context,
        source: File,
        pages: Map<Int, PageAnnotations>,
        selected: List<Int>,
        baseName: String,
        format: ImageFormat,
        openOutput: (String, String) -> OutputStream?,
        onProgress: (Int, Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val reordered = File(context.cacheDir, "extract-a.pdf")
        val flattened = File(context.cacheDir, "extract-b.pdf")
        try {
            PdfAnnotationIO.rebuildPages(source, selected, reordered)
            // Ink burned into the pages, so the renderer draws it like page content.
            PdfAnnotationIO.writeFile(
                reordered, remap(pages, selected), emptyList(), flattened,
                flatten = true, dropFileOutline = true
            )
            var saved = 0
            val pfd = ParcelFileDescriptor.open(flattened, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                for (i in 0 until renderer.pageCount) {
                    onProgress(i + 1, renderer.pageCount)
                    val page = renderer.openPage(i)
                    val bmp: Bitmap
                    try {
                        var w = page.width * IMAGE_DPI / 72f
                        var h = page.height * IMAGE_DPI / 72f
                        val longest = max(w, h)
                        if (longest > MAX_IMAGE_SIDE) {
                            w *= MAX_IMAGE_SIDE / longest
                            h *= MAX_IMAGE_SIDE / longest
                        }
                        bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    } finally {
                        page.close()
                    }
                    try {
                        val name = "$baseName-p${selected[i] + 1}.${format.ext}"
                        openOutput(name, format.mime)?.use { os ->
                            bmp.compress(format.compress, format.quality, os)
                            saved++
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
            } finally {
                renderer.close()
                pfd.close()
            }
            saved
        } finally {
            reordered.delete()
            flattened.delete()
        }
    }
}
