package com.titipong.pdfwriter.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
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
 * Render worker threads (one per CPU core used). Each worker has its own copy of the open document,
 * so pages render in parallel. Work is picked by priority, not arrival:
 * 1. pages on screen, nearest to the page being read ([focus]) first;
 * 2. background work for pages (pre-rendering), again nearest first;
 * 3. other background work (cropping, OCR).
 * The order is decided when a worker takes its next job, so scrolling re-prioritises at once.
 */
private class RenderLanes(threads: Int, private val onWorkerExit: () -> Unit) {
    private class Task(val r: Runnable, val urgent: Boolean, val page: Int)

    private val lock = Object()
    private val tasks = ArrayList<Task>()
    private var stopped = false

    /** The page being read. */
    @Volatile
    var focus = 0

    init {
        repeat(threads) { i ->
            Thread({ loop() }, "pdf-render-$i").apply { priority = Thread.NORM_PRIORITY + 1 }.start()
        }
    }

    private fun rank(t: Task, focus: Int): Long {
        val tier = when {
            t.urgent && t.page < 0 -> 0L
            t.urgent -> 1L
            t.page >= 0 -> 2L
            else -> 3L
        }
        val dist = if (t.page < 0) 0L else abs(t.page - focus).toLong()
        return tier * 1_000_000L + dist
    }

    private fun loop() {
        try {
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
        } finally {
            onWorkerExit()
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

    /** Stops the workers once the queue is empty. */
    fun stop() {
        synchronized(lock) {
            stopped = true
            lock.notifyAll()
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
 * Renders PDF pages with MuPDF, on several CPU cores at once.
 *
 * - Page sizes are read once and remembered for the next time the file is opened.
 * - Rendered pages are kept in memory, as small previews, and as images on disk.
 * - A render that is no longer needed (page scrolled away) is stopped part-way.
 * - Only the page content is drawn; annotations are drawn by the app on top (as before).
 */
class PdfDocumentRenderer private constructor(
    private val file: File,
    /** height / width for every page */
    val aspects: FloatArray,
    /** Rendered pages are kept here as images, so a page seen once loads instantly afterwards. */
    private val diskDir: File?
) {
    val pageCount: Int get() = aspects.size

    @Volatile
    private var closed = false

    /** Each worker thread's own copy of the document (MuPDF documents are single-threaded). */
    private val threadDoc = ThreadLocal<Document?>()

    private val lanes = RenderLanes(WORKERS) {
        try { threadDoc.get()?.destroy() } catch (e: Throwable) { }
        threadDoc.set(null)
    }

    /** The page being read: its rendering goes first, then the pages nearest to it. */
    var focusPage: Int
        get() = lanes.focus
        set(value) { lanes.focus = value }

    /** Called on the main thread when a page turns out to have a different size than assumed. */
    var onAspectChanged: ((Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    // Page images live in native memory.
    private val cache = object : LruCache<Int, Bitmap>((160L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /** Small copies of pages already seen, shown immediately while the sharp one loads. */
    private val previews = object : LruCache<Int, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    private fun doc(): Document =
        threadDoc.get() ?: Document.openDocument(file.path).also { threadDoc.set(it) }

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
                val tmp = File(f.path + "." + Thread.currentThread().id + ".tmp")
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
     * Draws page [index] into a new [width]-pixel-wide bitmap with this thread's document.
     * Stops early (returns null) if the calling coroutine is cancelled meanwhile.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun draw(index: Int, width: Int): Bitmap? {
        val cookie = Cookie()
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val handle = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            aborted.set(true)
            try { cookie.abort() } catch (e: Throwable) { }
        }
        try {
            val page = doc().loadPage(index)
            try {
                val b = page.bounds
                val pw = (b.x1 - b.x0).coerceAtLeast(1f)
                val ph = (b.y1 - b.y0).coerceAtLeast(1f)
                val real = ph / pw
                if (abs(real - aspects[index]) > 0.002f) {
                    aspects[index] = real
                    main.post { if (!closed) onAspectChanged?.invoke(index) }
                }
                val h = (width * real).toInt().coerceAtLeast(1)
                val bmp = try {
                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                } catch (oom: OutOfMemoryError) {
                    cache.evictAll()
                    return null
                }
                bmp.eraseColor(Color.WHITE)
                val s = width / pw
                val ctm = Matrix(s, 0f, 0f, s, -b.x0 * s, -b.y0 * s)
                val dev = AndroidDrawDevice(bmp, 0, 0)
                try {
                    page.runPageContents(dev, ctm, cookie)
                    dev.close()
                } finally {
                    dev.destroy()
                }
                if (aborted.get()) {
                    bmp.recycle()
                    return null
                }
                return bmp
            } finally {
                page.destroy()
            }
        } finally {
            handle?.dispose()
            cookie.destroy()
        }
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
            val bmp = draw(index, w) ?: return@withContext null
            cache.put(index, bmp)
            keepPreview(index, bmp)
            // Small thumbnails (page overview) aren't worth storing; keep the sharpest one on disk.
            if (w >= 600 && w > diskWidth(index)) saveToDisk(index, bmp)
            bmp
        }

    /** Page sizes are all read when the document opens; kept for callers. */
    suspend fun measureAll() {}

    /** Renders a page at [width] pixels without caching (for OCR). Caller recycles the bitmap. */
    suspend fun renderUncached(index: Int, width: Int): Bitmap? = withContext(lanes.background) {
        if (closed || index !in 0 until pageCount) return@withContext null
        draw(index, width)
    }

    /**
     * Finds the area of the page that isn't blank paper, in normalized coordinates
     * (x across the width 0..1, y in width units). Renders a small copy just for this.
     */
    suspend fun contentBounds(index: Int): RectF? = withContext(lanes.background) {
        if (closed || index !in 0 until pageCount) return@withContext null
        val w = 220
        val bmp = draw(index, w) ?: return@withContext null
        val h = bmp.height
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
        lanes.stop()
        cache.evictAll()
        previews.evictAll()
    }

    companion object {
        const val MAX_WIDTH = 2800
        private const val PREVIEW_WIDTH = 480
        /** An image this close to the wanted width is used as is (no re-render for a few pixels). */
        private const val FIT = 0.9f
        private const val DISK_LIMIT = 400L * 1024 * 1024
        private const val ASPECTS_FILE = "aspects.bin"
        /** Render threads: half the CPU cores, 2 to 4. */
        private val WORKERS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

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

        private fun saveAspects(dir: File?, aspects: FloatArray) {
            dir ?: return
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

        suspend fun open(file: File, diskDir: File? = null): PdfDocumentRenderer = withContext(Dispatchers.IO) {
            val d = try {
                Document.openDocument(file.path)
            } catch (e: Throwable) {
                throw IOException("Cannot open PDF", e)
            }
            try {
                if (d.needsPassword()) throw IOException("Password protected")
                val count = d.countPages()
                val aspects = loadAspects(diskDir, count) ?: FloatArray(count) { i ->
                    val p = d.loadPage(i)
                    try {
                        val b = p.bounds
                        (b.y1 - b.y0) / (b.x1 - b.x0).coerceAtLeast(1f)
                    } finally {
                        p.destroy()
                    }
                }.also { saveAspects(diskDir, it) }
                PdfDocumentRenderer(file, aspects, diskDir)
            } finally {
                d.destroy()
            }
        }
    }
}
