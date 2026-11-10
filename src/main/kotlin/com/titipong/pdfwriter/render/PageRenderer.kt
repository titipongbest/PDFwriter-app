package com.titipong.pdfwriter.render

import com.titipong.pdfwriter.compat.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import java.util.LinkedHashMap
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Renders the pages of one PDF (page content only: annotations are drawn by the app on top).
 *
 * Several worker threads, each with its own copy of the document (PDFBox documents are single
 * threaded), render in parallel. Work is picked by priority: the page being read ([focus]) first,
 * then the pages nearest to it; requests no longer wanted (scrolled away) are dropped.
 * Rendered pages are kept in memory and as images on disk, so a page seen once comes back at once.
 */
class PageRenderer private constructor(
    private val file: File,
    /** height / width of every page as displayed (after /Rotate). */
    val aspects: FloatArray,
    private val diskDir: File?
) {
    val pageCount: Int get() = aspects.size

    @Volatile
    var focus = 0

    @Volatile
    private var closed = false

    private class Task(val page: Int, val width: Int, val urgent: Boolean, val run: (PDFRenderer, PDDocument) -> Unit) {
        @Volatile var cancelled = false
    }

    private val lock = Object()
    private val tasks = ArrayList<Task>()

    // Memory cache: (page) -> sharpest image so far, limited by total pixels.
    private val cache = object : LinkedHashMap<Int, BufferedImage>(64, 0.75f, true) {}
    private var cachedBytes = 0L
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 3).coerceAtMost(600L * 1024 * 1024)

    init {
        repeat(WORKERS) { i -> Thread({ worker() }, "pdf-render-$i").apply { isDaemon = true; start() } }
    }

    private fun worker() {
        var doc: PDDocument? = null
        var renderer: PDFRenderer? = null
        try {
            while (true) {
                val task: Task = synchronized(lock) {
                    while (tasks.isEmpty() && !closed) lock.wait()
                    if (closed && tasks.isEmpty()) return
                    val f = focus
                    var best = 0
                    var bestRank = Long.MAX_VALUE
                    for (i in tasks.indices) {
                        val t = tasks[i]
                        val rank = (if (t.urgent) 0L else 1_000_000L) + abs(t.page - f)
                        if (rank < bestRank) { bestRank = rank; best = i }
                    }
                    tasks.removeAt(best)
                }
                if (task.cancelled || closed) {
                    task.run(PDFRendererStub, PDDocumentStub)
                    continue
                }
                if (doc == null) {
                    doc = PDDocument.load(file, MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
                    // The app draws annotations itself.
                    for (p in doc.pages) try { p.annotations = emptyList() } catch (e: Exception) { }
                    renderer = PDFRenderer(doc).apply { isSubsamplingAllowed = true }
                }
                task.run(renderer!!, doc)
            }
        } catch (e: InterruptedException) {
        } finally {
            try { doc?.close() } catch (e: Exception) { }
        }
    }

    private suspend fun <T> enqueue(page: Int, width: Int, urgent: Boolean, block: (PDFRenderer, PDDocument) -> T?): T? =
        suspendCancellableCoroutine { cont ->
            lateinit var task: Task
            task = Task(page, width, urgent) { r, d ->
                if (task.cancelled || closed || r === PDFRendererStub) {
                    if (cont.isActive) cont.resume(null)
                } else {
                    val v = try { block(r, d) } catch (e: Throwable) { null }
                    if (cont.isActive) cont.resume(v)
                }
            }
            cont.invokeOnCancellation { task.cancelled = true }
            synchronized(lock) {
                if (closed) { cont.resume(null); return@suspendCancellableCoroutine }
                tasks.add(task)
                lock.notify()
            }
        }

    // ---------------------------------------------------------------- caches

    private fun cached(page: Int): BufferedImage? = synchronized(cache) { cache[page] }

    private fun keep(page: Int, img: BufferedImage) {
        synchronized(cache) {
            val old = cache[page]
            if (old != null && old.width >= img.width) return
            if (old != null) cachedBytes -= bytes(old)
            cache[page] = img
            cachedBytes += bytes(img)
            val it = cache.entries.iterator()
            while (cachedBytes > maxBytes && it.hasNext()) {
                val e = it.next()
                if (e.key == page) continue
                cachedBytes -= bytes(e.value)
                it.remove()
            }
        }
    }

    private fun bytes(i: BufferedImage) = i.width.toLong() * i.height * 4

    private fun diskFile(page: Int) = diskDir?.let { File(it, "$page.jpg") }

    private fun fromDisk(page: Int, minWidth: Int): BufferedImage? {
        val f = diskFile(page) ?: return null
        if (!f.exists()) return null
        return try {
            javax.imageio.ImageIO.createImageInputStream(f).use { iis ->
                val reader = javax.imageio.ImageIO.getImageReaders(iis).next()
                reader.input = iis
                if (reader.getWidth(0) < minWidth * FIT) { reader.dispose(); return null }
                reader.read(0).also { reader.dispose() }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun toDisk(page: Int, img: BufferedImage) {
        val f = diskFile(page) ?: return
        Thread {
            try {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + "." + Thread.currentThread().id + ".tmp")
                javax.imageio.ImageIO.write(img, "jpg", tmp)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (e: Throwable) { }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** An image already made (memory or disk) at least about [minWidth] wide, without rendering. */
    fun quick(page: Int, minWidth: Int): BufferedImage? {
        cached(page)?.let { if (it.width >= minWidth * FIT) return it }
        return fromDisk(page, minWidth)?.also { keep(page, it) }
    }

    /** Best image in memory, any size (shown while a sharper one is made). */
    fun peek(page: Int): BufferedImage? = cached(page)

    /** Page [page] rendered [width] pixels wide (from the caches when possible). */
    suspend fun render(page: Int, width: Int, urgent: Boolean = true): BufferedImage? {
        if (closed || page !in 0 until pageCount) return null
        val w = width.coerceIn(200, MAX_WIDTH)
        quick(page, w)?.let { return it }
        return enqueue(page, w, urgent) { r, d ->
            cached(page)?.takeIf { it.width >= w * FIT }?.let { return@enqueue it }
            val pw = d.getPage(page).let { p ->
                val b = p.cropBox
                if (p.rotation % 180 != 0) b.height else b.width
            }
            val scale = w / pw.coerceAtLeast(1f)
            val img = r.renderImage(page, scale, ImageType.RGB)
            keep(page, img)
            if (w >= 600) toDisk(page, img)
            img
        }
    }

    /** Uncached render for OCR / export at [width] pixels. */
    suspend fun renderUncached(page: Int, width: Int): BufferedImage? =
        enqueue(page, width, urgent = false) { r, d ->
            val p = d.getPage(page)
            val pw = if (p.rotation % 180 != 0) p.cropBox.height else p.cropBox.width
            r.renderImage(page, width / pw.coerceAtLeast(1f), ImageType.RGB)
        }

    /** The non-blank area of a page (normalized: x 0..1, y in width units), for auto crop. */
    suspend fun contentBounds(page: Int): RectF? = enqueue(page, 220, urgent = false) { r, d ->
        val p = d.getPage(page)
        val pw = if (p.rotation % 180 != 0) p.cropBox.height else p.cropBox.width
        val img = r.renderImage(page, 220 / pw.coerceAtLeast(1f), ImageType.RGB)
        val w = img.width
        val h = img.height
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            val c = img.getRGB(x, y)
            val luma = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
            if (luma < 235) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return@enqueue null
        val m = 0.02f
        val fw = w.toFloat()
        val a = aspects[page]
        val rect = RectF(
            (minX / fw - m).coerceAtLeast(0f), (minY / fw - m).coerceAtLeast(0f),
            ((maxX + 1) / fw + m).coerceAtMost(1f), ((maxY + 1) / fw + m).coerceAtMost(a)
        )
        if (rect.width() > 0.96f && rect.height() > a * 0.96f) null else rect
    }

    fun close() {
        closed = true
        synchronized(lock) {
            tasks.forEach { it.cancelled = true }
            lock.notifyAll()
        }
        synchronized(cache) { cache.clear(); cachedBytes = 0 }
    }

    companion object {
        const val MAX_WIDTH = 3600
        private const val FIT = 0.9f
        private val WORKERS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

        // Markers for "skip this task" (cancelled / closed).
        private val PDDocumentStub = PDDocument()
        private val PDFRendererStub = PDFRenderer(PDDocumentStub)

        /** Opens [file]; [diskDir] keeps rendered pages between sessions. */
        fun open(file: File, diskDir: File?): PageRenderer {
            val aspects = PDDocument.load(file, MemoryUsageSetting.setupMixed(256L * 1024 * 1024)).use { d ->
                FloatArray(d.numberOfPages) { i ->
                    val p = d.getPage(i)
                    val b = p.cropBox
                    val rot = p.rotation % 180 != 0
                    val w = if (rot) b.height else b.width
                    val h = if (rot) b.width else b.height
                    h / w.coerceAtLeast(1f)
                }
            }
            return PageRenderer(file, aspects, diskDir)
        }

        /** Disk cache folder for this exact version of [file] (old versions removed, 500 MB total). */
        fun diskCacheFor(root: File, key: String, file: File): File {
            val pages = File(root, "pages").apply { mkdirs() }
            val name = "${key}_${file.length()}_${file.lastModified()}"
            val dir = File(pages, name)
            try {
                pages.listFiles()?.forEach { if (it.name != name && it.name.startsWith(key + "_")) it.deleteRecursively() }
                dir.mkdirs()
                dir.setLastModified(System.currentTimeMillis())
                val dirs = pages.listFiles()?.filter { it.isDirectory }?.sortedBy { it.lastModified() } ?: emptyList()
                var total = dirs.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }
                for (d in dirs) {
                    if (total <= 500L * 1024 * 1024 || d == dir) continue
                    total -= d.listFiles()?.sumOf { it.length() } ?: 0L
                    d.deleteRecursively()
                }
            } catch (e: Throwable) { }
            return dir
        }
    }
}
