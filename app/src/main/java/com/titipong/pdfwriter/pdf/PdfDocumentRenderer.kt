package com.titipong.pdfwriter.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Wraps the platform PdfRenderer. All renderer access happens on one thread. */
class PdfDocumentRenderer private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val dispatcher: ExecutorCoroutineDispatcher,
    /** height / width for every page */
    val aspects: FloatArray
) {
    val pageCount: Int get() = aspects.size

    @Volatile
    private var closed = false

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    suspend fun render(index: Int, targetWidth: Int): Bitmap? = withContext(dispatcher) {
        if (closed || index !in 0 until pageCount) return@withContext null
        val w = targetWidth.coerceIn(300, MAX_WIDTH)
        val cached = cache.get(index)
        if (cached != null && cached.width >= w) return@withContext cached
        val page = renderer.openPage(index)
        try {
            val h = (w * aspects[index]).toInt().coerceAtLeast(1)
            val bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                cache.evictAll()
                return@withContext null
            }
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(index, bmp)
            bmp
        } finally {
            page.close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        dispatcher.executor.execute {
            try { renderer.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
            cache.evictAll()
            dispatcher.close()
        }
    }

    companion object {
        private const val MAX_WIDTH = 2000

        suspend fun open(pfd: ParcelFileDescriptor): PdfDocumentRenderer {
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                return withContext(dispatcher) {
                    val r = PdfRenderer(pfd)
                    val aspects = FloatArray(r.pageCount) { i ->
                        val p = r.openPage(i)
                        try {
                            p.height.toFloat() / p.width.toFloat().coerceAtLeast(1f)
                        } finally {
                            p.close()
                        }
                    }
                    PdfDocumentRenderer(pfd, r, dispatcher, aspects)
                }
            } catch (e: Exception) {
                dispatcher.close()
                try { pfd.close() } catch (ignored: Exception) { }
                throw e
            }
        }
    }
}
