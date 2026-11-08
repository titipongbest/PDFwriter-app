package com.titipong.pdfwriter.storage

import android.content.Context
import com.titipong.pdfwriter.model.PageAnnotations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AnnotationStorage {

    private fun storageDir(context: Context) =
        File(context.filesDir, "annotations").also { it.mkdirs() }

    private fun fileFor(context: Context, pdfName: String): File {
        val safe = pdfName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(storageDir(context), "$safe.json")
    }

    suspend fun save(
        context: Context,
        pdfName: String,
        annotations: Map<Int, PageAnnotations>
    ) = withContext(Dispatchers.IO) {
        val root = JSONObject()
        val pages = JSONObject()
        annotations.forEach { (page, pa) ->
            val pageObj = JSONObject()
            // strokes
            val strArr = JSONArray()
            pa.strokes.forEach { s ->
                val obj = JSONObject()
                obj.put("color", s.color)
                obj.put("width", s.strokeWidth)
                obj.put("tool", s.tool)
                obj.put("page", s.pageIndex)
                val pts = JSONArray()
                s.points.forEach { p ->
                    val pt = JSONObject()
                    pt.put("x", p.x); pt.put("y", p.y); pt.put("pr", p.pressure)
                    pts.put(pt)
                }
                obj.put("pts", pts)
                strArr.put(obj)
            }
            pageObj.put("strokes", strArr)
            // texts
            val txtArr = JSONArray()
            pa.texts.forEach { t ->
                val obj = JSONObject()
                obj.put("text", t.text)
                obj.put("x", t.x); obj.put("y", t.y)
                obj.put("color", t.color)
                obj.put("size", t.textSize)
                obj.put("page", t.pageIndex)
                txtArr.put(obj)
            }
            pageObj.put("texts", txtArr)
            pages.put(page.toString(), pageObj)
        }
        root.put("pages", pages)
        fileFor(context, pdfName).writeText(root.toString())
    }

    suspend fun load(
        context: Context,
        pdfName: String
    ): Map<Int, PageAnnotations> = withContext(Dispatchers.IO) {
        val file = fileFor(context, pdfName)
        if (!file.exists()) return@withContext emptyMap()
        try {
            val root = JSONObject(file.readText())
            val pages = root.getJSONObject("pages")
            val result = mutableMapOf<Int, PageAnnotations>()
            pages.keys().forEach { key ->
                val page = key.toInt()
                val pageObj = pages.getJSONObject(key)
                val pa = PageAnnotations()
                // strokes
                val strArr = pageObj.getJSONArray("strokes")
                for (i in 0 until strArr.length()) {
                    val obj = strArr.getJSONObject(i)
                    val pts = mutableListOf<com.titipong.pdfwriter.model.StrokePoint>()
                    val ptsArr = obj.getJSONArray("pts")
                    for (j in 0 until ptsArr.length()) {
                        val pt = ptsArr.getJSONObject(j)
                        pts.add(com.titipong.pdfwriter.model.StrokePoint(
                            pt.getDouble("x").toFloat(),
                            pt.getDouble("y").toFloat(),
                            pt.optDouble("pr", 0.5).toFloat()
                        ))
                    }
                    pa.strokes.add(com.titipong.pdfwriter.model.SerializableStroke(
                        points = pts,
                        color = obj.getInt("color"),
                        strokeWidth = obj.getDouble("width").toFloat(),
                        tool = obj.getString("tool"),
                        pageIndex = obj.getInt("page")
                    ))
                }
                // texts
                val txtArr = pageObj.getJSONArray("texts")
                for (i in 0 until txtArr.length()) {
                    val obj = txtArr.getJSONObject(i)
                    pa.texts.add(com.titipong.pdfwriter.model.TextAnnotation(
                        text = obj.getString("text"),
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        color = obj.getInt("color"),
                        textSize = obj.getDouble("size").toFloat(),
                        pageIndex = obj.getInt("page")
                    ))
                }
                result[page] = pa
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
