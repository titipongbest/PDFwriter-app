package com.titipong.pdfwriter.storage

import android.content.Context
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextNote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves annotations as JSON in app-private storage, one file per PDF (name + size). */
object AnnotationStorage {

    private val lastWritten = HashMap<String, Long>()

    fun keyFor(name: String, size: Long): String = "${name}_$size"

    private fun fileFor(context: Context, key: String): File {
        val dir = File(context.filesDir, "annotations")
        dir.mkdirs()
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.json")
    }

    private fun r(v: Float, scale: Double): Double = Math.round(v * scale) / scale

    fun toJson(pages: Map<Int, PageAnnotations>): String {
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
            val po = JSONObject()
            po.put("s", strokes)
            po.put("t", texts)
            pagesJson.put(index.toString(), po)
        }
        val root = JSONObject()
        root.put("v", 1)
        root.put("pages", pagesJson)
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

    fun load(context: Context, key: String): MutableMap<Int, PageAnnotations> {
        val result = mutableMapOf<Int, PageAnnotations>()
        val file = fileFor(context, key)
        if (!file.exists()) return result
        try {
            val pagesJson = JSONObject(file.readText()).getJSONObject("pages")
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
                result[k.toInt()] = page
            }
        } catch (e: Exception) {
            // Corrupt file: start fresh rather than crash.
        }
        return result
    }
}
