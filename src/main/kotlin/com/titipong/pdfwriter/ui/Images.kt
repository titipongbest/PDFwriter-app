package com.titipong.pdfwriter.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/** Rendered pages converted for drawing, keyed by the rendered image itself (a few kept). */
object Bitmaps {
    private val map = object : LinkedHashMap<BufferedImage, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BufferedImage, ImageBitmap>?) = size > 28
    }

    fun cached(img: BufferedImage?): ImageBitmap? = img?.let { synchronized(map) { map[it] } }

    fun of(img: BufferedImage): ImageBitmap {
        synchronized(map) { map[img] }?.let { return it }
        val b = img.toComposeImageBitmap()
        synchronized(map) { map[img] = b }
        return b
    }
}

/** Pictures placed on pages (ImageNote files), loaded in the background. */
object PictureCache {
    var version by mutableStateOf(0)
        private set
    private val map = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 40
    }
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "pictures").apply { isDaemon = true } }

    fun get(path: String): ImageBitmap? {
        synchronized(map) { map[path] }?.let { return it }
        synchronized(loading) {
            if (path in loading || path in failed) return null
            loading.add(path)
        }
        pool.execute {
            val b = try {
                javax.imageio.ImageIO.read(File(path))?.toComposeImageBitmap()
            } catch (e: Throwable) {
                null
            }
            synchronized(loading) { loading.remove(path); if (b == null) failed.add(path) }
            if (b != null) synchronized(map) { map[path] = b }
            javax.swing.SwingUtilities.invokeLater { version++ }
        }
        return null
    }
}
