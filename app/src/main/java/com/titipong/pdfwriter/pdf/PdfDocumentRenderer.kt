package com.titipong.pdfwriter.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Which page a piece of render work is for, so the queue can put the page being read first. */
private class PageKey(val page: Int) : AbstractCoroutineContextElement(PageKey) {
    companion object : CoroutineContext.Key<PageKey>
}

/**
 * The one thread that touches the PdfRenderer. Work is picked by priority, not arrival:
 * 1. pages on screen, nearest to the page being read ([focus]) first;
 * 2. background work for pages (pre-rendering), again nearest first;
 * 3. other background work (measuring, cropping, OCR).
 * The order is decided when the thread takes the next job, so scrolling re-prioritises at once.
 */
private class RenderLanes {
    private class Task(val r: Runnable, val urgent: Boolean, val page: Int)

    private val lock = Object()
    private val tasks = ArrayList<Task>()
    private var stopped = false

    /** The page being read. */
    @Volatile
    var focus = 0

    init {
        Thread({ loop() }, "pdf-render").apply { priority = Thread.NORM_PRIORITY + 1 }.start()
    }

    private fun rank(t: Task, focus: Int): Long {
        val tier = when {
            t.urgent && t.page < 0 -> 0L      // opening the document
            t.urgent -> 1L
            t.page >= 0 -> 2L
            else -> 3L
        }
        val dist = if (t.page < 0) 0L else abs(t.page - focus).toLong()
        return tier * 1_000_000L + dist
    }

    private fun loop() {
        while (true) {
            val r: Runnable = synchronized(lock) {
                while (tasks.isEmpty() && !stopped) lock.wait()
                if (tasks.isEmpty()) return
                val f = focus
                var best = 0
                var bestRank = Long.MAX_VALUE
                for (i in tasks.indices) {
                    val k = rank(tasks[i], f)
                    if (k < bestRank) {   // ties: the oldest wins
                        bestRank = k
                        best = i
                    }
                }
                tasks.removeAt(best).r
            }
            try { r.run() } catch (t: Throwable) { }
        }
    }

    fun post(r: Runnable, urgent: Boolean, page: Int = -1) {
        synchronized(lock) {
            if (!stopped) {
                tasks.add(Task(r, urgent, page))
                lock.notify()
                return
            }
        }
        // Closed: let it run elsewhere (it only sees that the document is closed).
        Dispatchers.Default.dispatch(EmptyCoroutineContext, r)
    }

    /** Stops the thread once the queue is empty. */
    fun stop() {
        synchronized(lock) {
            stopped = true
            lock.notify()
        }
    }

    val urgent: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) =
            post(block, true, context[PageKey]?.page ?: -1)
    }
    val background: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) =
            post(block, false, context[PageKey]?.page ?: -1)
    }
}

/**
 * Wraps the platform PdfRenderer. All renderer access happens on one thread.
 *
 * Speed:
 * - Page sizes aren't all measured up front (opening a page parses its whole content, slow for big
 *   files). Unknown pages assume the first page's size until they're opened; sizes are remembered.
 * - Rendered pages are kept in memory, as small previews, and as images on disk.
 */
class PdfDocumentRenderer private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val lanes: RenderLanes,
    /** height / width for every page (estimated for pages not opened yet) */
    val aspects: FloatArray,
    private val known: BooleanArray,
    /** Rendered pages are kept here as images, so a page seen once loads instantly afterwards. */
    private val diskDir: File?
) {
    val pageCount: Int get() = aspects.size

    @Volatile
    private var closed = false

    /** The page being read: its rendering goes first, then the pages nearest to it. */
    var focusPage: Int
        get() = lanes.focus
        set(value) { lanes.focus = value }

    /** Called on the main thread when a page turns out to have a different size than assumed. */
    var onAspectChanged: ((Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    // Page images live in native memory; keep the cache modest so rendering more pages doesn't fail.
    private val cache = object : LruCache<Int, Bitmap>(
        (160L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /** Small copies of pages already seen, shown immediately while the sharp one loads. */
    private val previews = object : LruCache<Int, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /** The best image already in memory for [index] (no rendering). */
    fun peek(index: Int): Bitmap? = cache.get(index)?.takeIf { !it.isRecycled }
        ?: previews.get(index)?.takeIf { !it.isRecycled }

    private fun keepPreview(index: Int, bmp: Bitmap) {
        if (previews.get(index) != null) return
        val w = PREVIEW_WIDTH.coerceAtMost(bmp.width)
        val h = (w * bmp.height.toFloat() / bmp.width).toInt().coerceAtLeast(1)
        try {
            val small = Bitmap.createScaledBitmap(bmp, w, h, true).copy(Bitmap.Config.RGB_565, false)
            if (small != null) previews.put(index, small)
        } catch (e: Throwable) { }
    }

    /** Records the real size of a page that is open. Renderer thread only. */
    private fun learnAspect(index: Int, page: PdfRenderer.Page) {
        if (known[index]) return
        known[index] = true
        val real = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
        if (abs(real - aspects[index]) > 0.002f) {
            aspects[index] = real
            main.post { if (!closed) onAspectChanged?.invoke(index) }
        }
    }

    private fun diskFile(index: Int): File? = diskDir?.let { File(it, "$index.jpg") }

    private fun loadFromDisk(index: Int, minWidth: Int): Bitmap? {
        val f = diskFile(index) ?: return null
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth < minWidth * FIT) return null
            BitmapFactory.decodeFile(f.path)
        } catch (e: Throwable) {
            null
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun saveToDisk(index: Int, bmp: Bitmap) {
        val f = diskFile(index) ?: return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (!tmp.renameTo(f)) tmp.delete()
            } catch (e: Throwable) { }
        }
    }

    private fun diskWidth(index: Int): Int {
        val f = diskFile(index) ?: return 0
        if (!f.exists()) return 0
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            o.outWidth.coerceAtLeast(0)
        } catch (e: Throwable) {
            0
        }
    }

    /** True when [index] is ready at [width] without rendering (in memory or on disk). */
    fun isReady(index: Int, width: Int): Boolean {
        val w = width.coerceIn(300, MAX_WIDTH)
        val c = cache.get(index)
        if (c != null && c.width >= w * FIT) return true
        return diskWidth(index) >= w * FIT
    }

    /**
     * A sharp-enough image already made (memory or disk), without rendering: shown first while a
     * sharper one (e.g. for zoom) is rendered. Null if there is none at [minWidth].
     */
    suspend fun quick(index: Int, minWidth: Int): Bitmap? = withContext(lanes.urgent + PageKey(index)) {
        if (closed || index !in 0 until pageCount) return@withContext null
        val w = minWidth.coerceIn(300, MAX_WIDTH)
        val cached = cache.get(index)
        if (cached != null && cached.width >= w * FIT) return@withContext cached
        loadFromDisk(index, w)?.also {
            if (cached == null || cached.width < it.width) cache.put(index, it)
            keepPreview(index, it)
        }
    }

    suspend fun render(index: Int, targetWidth: Int, urgent: Boolean = true): Bitmap? =
        withContext((if (urgent) lanes.urgent else lanes.background) + PageKey(index)) {
        if (closed || index !in 0 until pageCount) return@withContext null
        val w = targetWidth.coerceIn(300, MAX_WIDTH)
        val cached = cache.get(index)
        if (cached != null && cached.width >= w * FIT) return@withContext cached
        loadFromDisk(index, w)?.let {
            cache.put(index, it)
            keepPreview(index, it)
            return@withContext it
        }
        val page = renderer.openPage(index)
        try {
            learnAspect(index, page)
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
            keepPreview(index, bmp)
            // Small thumbnails (page overview) aren't worth storing; keep the sharpest one on disk.
            if (w >= 600 && w > diskWidth(index)) saveToDisk(index, bmp)
            bmp
        } finally {
            page.close()
        }
    }

    /**
     * Measures the pages not opened yet, a few at a time so on-screen pages still render first,
     * then remembers all sizes for the next time this file is opened.
     */
    suspend fun measureAll() {
        var i = 0
        while (i < pageCount && !closed) {
            coroutineContext.ensureActive()
            val from = i
            withContext(lanes.background) {
                var n = 0
                var j = from
                while (j < pageCount && n < 2 && !closed) {
                    if (!known[j]) {
                        val p = renderer.openPage(j)
                        try { learnAspect(j, p) } finally { p.close() }
                        n++
                    }
                    j++
                }
                i = j
            }
        }
        if (!closed) withContext(Dispatchers.IO) { saveAspects() }
    }

    private fun saveAspects() {
        val dir = diskDir ?: return
        if (known.any { !it }) return
        try {
            dir.mkdirs()
            val tmp = File(dir, "$ASPECTS_FILE.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(aspects.size)
                aspects.forEach { out.writeFloat(it) }
            }
            tmp.renameTo(File(dir, ASPECTS_FILE))
        } catch (e: Throwable) { }
    }

    /** Renders a page at [width] pixels without caching (for OCR). Caller recycles the bitmap. */
    suspend fun renderUncached(index: Int, width: Int): Bitmap? = withContext(lanes.background) {
        if (closed || index !in 0 until pageCount) return@withContext null
        val page = renderer.openPage(index)
        try {
            learnAspect(index, page)
            val h = (width * aspects[index]).toInt().coerceAtLeast(1)
            val bmp = try {
                Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                cache.evictAll()
                return@withContext null
            }
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            bmp
        } finally {
            page.close()
        }
    }

    /**
     * Finds the area of the page that isn't blank paper, in normalized coordinates
     * (x across the width 0..1, y in width units). Renders a small copy just for this.
     */
    suspend fun contentBounds(index: Int): RectF? = withContext(lanes.background) {
        if (closed || index !in 0 until pageCount) return@withContext null
        val w = 220
        val page = renderer.openPage(index)
        val bmp: Bitmap
        val h: Int
        try {
            learnAspect(index, page)
            h = (w * aspects[index]).toInt().coerceAtLeast(1)
            bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                return@withContext null
            }
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
        onAspectChanged = null
        lanes.post(Runnable {
            try { renderer.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
            cache.evictAll()
            previews.evictAll()
            lanes.stop()
        }, urgent = false)
    }

    companion object {
        const val MAX_WIDTH = 2800
        private const val PREVIEW_WIDTH = 480
        /** An image this close to the wanted width is used as is (no re-render for a few pixels). */
        private const val FIT = 0.9f
        private const val DISK_LIMIT = 400L * 1024 * 1024
        private const val ASPECTS_FILE = "aspects.bin"

        /**
         * The page-image folder for this exact version of [file] (any change to the file gets a new
         * folder). Old versions of the same file are removed, and the total stays under 400 MB.
         */
        fun diskCacheFor(cacheRoot: File, file: File): File {
            val root = File(cacheRoot, "pages").apply { mkdirs() }
            val name = "${file.nameWithoutExtension}_${file.length()}_${file.lastModified()}"
            val dir = File(root, name)
            try {
                root.listFiles()?.forEach {
                    if (it.name != name && it.name.startsWith(file.nameWithoutExtension + "_")) it.deleteRecursively()
                }
                dir.mkdirs()
                dir.setLastModified(System.currentTimeMillis())
                val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: emptyList()
                var total = dirs.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }
                for (d in dirs) {
                    if (total <= DISK_LIMIT || d == dir) continue
                    total -= d.listFiles()?.sumOf { it.length() } ?: 0L
                    d.deleteRecursively()
                }
            } catch (e: Throwable) { }
            return dir
        }

        private fun loadAspects(dir: File?, count: Int): FloatArray? {
            val f = dir?.let { File(it, ASPECTS_FILE) } ?: return null
            if (!f.exists()) return null
            return try {
                DataInputStream(f.inputStream().buffered()).use { inp ->
                    if (inp.readInt() != count) return null
                    FloatArray(count) { inp.readFloat() }
                }
            } catch (e: Throwable) {
                null
            }
        }

        suspend fun open(pfd: ParcelFileDescriptor, diskDir: File? = null): PdfDocumentRenderer {
            val lanes = RenderLanes()
            try {
                return withContext(lanes.urgent) {
                    val r = PdfRenderer(pfd)
                    val count = r.pageCount
                    val saved = loadAspects(diskDir, count)
                    val aspects: FloatArray
                    val known: BooleanArray
                    if (saved != null) {
                        aspects = saved
                        known = BooleanArray(count) { true }
                    } else {
                        // Only the first page is opened now; the rest are measured later.
                        known = BooleanArray(count)
                        var first = 1.4142f
                        if (count > 0) {
                            val p = r.openPage(0)
                            try {
                                first = p.height.toFloat() / p.width.toFloat().coerceAtLeast(1f)
                            } finally {
                                p.close()
                            }
                            known[0] = true
                        }
                        aspects = FloatArray(count) { first }
                    }
                    PdfDocumentRenderer(pfd, r, lanes, aspects, known, diskDir)
                }
            } catch (e: Exception) {
                lanes.stop()
                try { pfd.close() } catch (ignored: Exception) { }
                throw e
            }
        }
    }
}
