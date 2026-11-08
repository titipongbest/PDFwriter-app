package com.titipong.pdfwriter.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** Decoded pictures placed on pages (shared by all page views), reduced to at most ~1600 px. */
object ImageCache {
    private const val MAX_SIDE = 1600
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(path: String): Bitmap? {
        cache.get(path)?.let { if (!it.isRecycled) return it }
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.also { cache.put(path, it) }
        } catch (e: Throwable) {
            null
        }
    }
}
