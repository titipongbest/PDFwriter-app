package com.titipong.pdfwriter.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
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

    // Page images live in native memory; keep the cache modest so rendering more pages doesn't fail.
    private val cache = object : LruCache<Int, Bitmap>(
        minOf(Runtime.getRuntime().maxMemory() / 5, 96L * 1024 * 1024).toInt()
    ) {
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

    /** Renders a page at [width] pixels without caching (for OCR). Caller recycles the bitmap. */
    suspend fun renderUncached(index: Int, width: Int): Bitmap? = withContext(dispatcher) {
        if (closed || index !in 0 until pageCount) return@withContext null
        val h = (width * aspects[index]).toInt().coerceAtLeast(1)
        val bmp = try {
            Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            cache.evictAll()
            return@withContext null
        }
        val page = renderer.openPage(index)
        try {
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        } finally {
            page.close()
        }
        bmp
    }

    /**
     * Finds the area of the page that isn't blank paper, in normalized coordinates
     * (x across the width 0..1, y in width units). Renders a small copy just for this.
     */
    suspend fun contentBounds(index: Int): RectF? = withContext(dispatcher) {
        if (closed || index !in 0 until pageCount) return@withContext null
        val w = 220
        val h = (w * aspects[index]).toInt().coerceAtLeast(1)
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            return@withContext null
        }
        val page = renderer.openPage(index)
        try {
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        } finally {
            page.close()
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (luma < 235) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return@withContext null // blank page: show it whole
        val margin = 0.02f
        val fw = w.toFloat()
        val left = (minX / fw - margin).coerceAtLeast(0f)
        val top = (minY / fw - margin).coerceAtLeast(0f)
        val right = ((maxX + 1) / fw + margin).coerceAtMost(1f)
        val bottom = ((maxY + 1) / fw + margin).coerceAtMost(aspects[index])
        // Not worth cropping if it would barely change anything.
        if (right - left > 0.96f && bottom - top > aspects[index] * 0.96f) return@withContext null
        RectF(left, top, right, bottom)
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
        private const val MAX_WIDTH = 2800

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
