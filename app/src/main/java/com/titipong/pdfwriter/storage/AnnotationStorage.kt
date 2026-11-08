package com.titipong.pdfwriter.storage

import android.content.Context
import com.titipong.pdfwriter.model.Bookmark
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextHighlight
import com.titipong.pdfwriter.model.TextNote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves annotations as JSON in app-private storage, one file per PDF (name + size). */
object AnnotationStorage {

    private val lastWritten = HashMap<String, Long>()

    /** Stable per-document key (the URI), hashed so it is a safe file name. */
    fun keyFor(uri: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** synced = true when the same ink is already written into the PDF file itself. */
    class Loaded(
        val pages: MutableMap<Int, PageAnnotations>,
        val bookmarks: MutableList<Bookmark>,
        val synced: Boolean
    )

    private fun fileFor(context: Context, key: String): File {
        val dir = File(context.filesDir, "annotations")
        dir.mkdirs()
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.json")
    }

    private fun r(v: Float, scale: Double): Double = Math.round(v * scale) / scale

    fun toJson(pages: Map<Int, PageAnnotations>, bookmarks: List<Bookmark>, synced: Boolean): String {
        val pagesJson = JSONObject()
        for ((index, page) in pages) {
            if (page.isEmpty()) continue
            val strokes = JSONArray()
            for (s in page.strokes) {
                val pts = JSONArray()
                for (p in s.points) {
                    pts.put(r(p.x, 100000.0))
                    pts.put(r(p.y, 100000.0))
                    pts.put(r(p.pressure, 1000.0))
                }
                val so = JSONObject()
                so.put("c", s.color)
                so.put("w", s.width.toDouble())
                so.put("h", s.highlighter)
                so.put("p", pts)
                strokes.put(so)
            }
            val texts = JSONArray()
            for (t in page.texts) {
                val to = JSONObject()
                to.put("t", t.text)
                to.put("x", t.x.toDouble())
                to.put("y", t.y.toDouble())
                to.put("c", t.color)
                to.put("z", t.size.toDouble())
                texts.put(to)
            }
            val hls = JSONArray()
            for (h in page.highlights) {
                val r = JSONArray()
                for (rect in h.rects) {
                    r.put(rect.left.toDouble()); r.put(rect.top.toDouble())
                    r.put(rect.right.toDouble()); r.put(rect.bottom.toDouble())
                }
                hls.put(JSONObject().apply {
                    put("c", h.color)
                    put("t", h.text)
                    put("r", r)
                    put("u", h.underline)
                })
            }
            val po = JSONObject()
            po.put("s", strokes)
            po.put("t", texts)
            po.put("h", hls)
            pagesJson.put(index.toString(), po)
        }
        val root = JSONObject()
        val bm = JSONArray()
        for (b in bookmarks) {
            bm.put(JSONObject().apply {
                put("t", b.title)
                put("p", b.page)
            })
        }
        root.put("v", 3)
        root.put("synced", synced)
        root.put("pages", pagesJson)
        root.put("bookmarks", bm)
        return root.toString()
    }

    /** Atomic write. Snapshots older than the last one written for this key are skipped. */
    @Synchronized
    fun save(context: Context, key: String, json: String, seq: Long) {
        val last = lastWritten[key]
        if (last != null && seq <= last) return
        val target = fileFor(context, key)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        lastWritten[key] = seq
    }

    /** The file's own table of contents, cached so a quick reopen doesn't need to parse the PDF. */
    fun saveToc(context: Context, key: String, toc: List<com.titipong.pdfwriter.model.OutlineEntry>) {
        val arr = JSONArray()
        for (e in toc) arr.put(JSONObject().apply { put("t", e.title); put("p", e.page); put("d", e.depth) })
        try {
            File(fileFor(context, key).path.removeSuffix(".json") + ".toc.json").writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    fun loadToc(context: Context, key: String): List<com.titipong.pdfwriter.model.OutlineEntry> = try {
        val f = File(fileFor(context, key).path.removeSuffix(".json") + ".toc.json")
        if (!f.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                com.titipong.pdfwriter.model.OutlineEntry(o.getString("t"), o.getInt("p"), o.getInt("d"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun load(context: Context, key: String): Loaded? {
        val result = mutableMapOf<Int, PageAnnotations>()
        val file = fileFor(context, key)
        if (!file.exists()) return null
        var synced = false
        val bookmarks = mutableListOf<Bookmark>()
        try {
            val root = JSONObject(file.readText())
            synced = root.optBoolean("synced", false)
            val pagesJson = root.getJSONObject("pages")
            val keys = pagesJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val po = pagesJson.getJSONObject(k)
                val page = PageAnnotations()
                val strokes = po.optJSONArray("s") ?: JSONArray()
                for (i in 0 until strokes.length()) {
                    val so = strokes.getJSONObject(i)
                    val arr = so.getJSONArray("p")
                    val pts = ArrayList<StrokePoint>(arr.length() / 3)
                    var j = 0
                    while (j + 2 < arr.length()) {
                        pts.add(
                            StrokePoint(
                                arr.getDouble(j).toFloat(),
                                arr.getDouble(j + 1).toFloat(),
                                arr.getDouble(j + 2).toFloat()
                            )
                        )
                        j += 3
                    }
                    page.strokes.add(
                        InkStroke(pts, so.getInt("c"), so.getDouble("w").toFloat(), so.optBoolean("h"))
                    )
                }
                val texts = po.optJSONArray("t") ?: JSONArray()
                for (i in 0 until texts.length()) {
                    val to = texts.getJSONObject(i)
                    page.texts.add(
                        TextNote(
                            to.getString("t"),
                            to.getDouble("x").toFloat(),
                            to.getDouble("y").toFloat(),
                            to.getInt("c"),
                            to.getDouble("z").toFloat()
                        )
                    )
                }
                val hls = po.optJSONArray("h") ?: JSONArray()
                for (i in 0 until hls.length()) {
                    val ho = hls.getJSONObject(i)
                    val r = ho.getJSONArray("r")
                    val rects = ArrayList<android.graphics.RectF>()
                    var j = 0
                    while (j + 3 < r.length()) {
                        rects.add(
                            android.graphics.RectF(
                                r.getDouble(j).toFloat(), r.getDouble(j + 1).toFloat(),
                                r.getDouble(j + 2).toFloat(), r.getDouble(j + 3).toFloat()
                            )
                        )
                        j += 4
                    }
                    if (rects.isNotEmpty()) page.highlights.add(TextHighlight(rects, ho.getInt("c"), ho.optString("t"), ho.optBoolean("u")))
                }
                result[k.toInt()] = page
            }
            val bm = root.optJSONArray("bookmarks") ?: JSONArray()
            for (i in 0 until bm.length()) {
                val bo = bm.getJSONObject(i)
                bookmarks.add(Bookmark(bo.getString("t"), bo.getInt("p")))
            }
        } catch (e: Exception) {
            return null // corrupt file: fall back to the PDF's own annotations
        }
        return Loaded(result, bookmarks, synced)
    }
}
