package com.titipong.pdfwriter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small persistent settings store (JSON file in the app's data folder). */
object Prefs {
    private val file = File(AppDirs.data, "settings.json")
    private val root: JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }
    @Volatile private var pending = false

    fun getString(k: String, d: String? = null): String? = if (root.has(k)) root.optString(k, d) else d
    fun getInt(k: String, d: Int): Int = root.optInt(k, d)
    fun getFloat(k: String, d: Float): Float = root.optDouble(k, d.toDouble()).toFloat()
    fun getBoolean(k: String, d: Boolean): Boolean = root.optBoolean(k, d)
    fun getIntList(k: String): List<Int>? = root.optJSONArray(k)?.let { a -> List(a.length()) { a.getInt(it) } }
    fun getStringList(k: String): List<String> = root.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

    fun put(k: String, v: Any?) {
        synchronized(root) {
            when (v) {
                null -> root.remove(k)
                is List<*> -> root.put(k, JSONArray(v))
                else -> root.put(k, v)
            }
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        if (pending) return
        pending = true
        Thread {
            Thread.sleep(400)
            pending = false
            flush()
        }.apply { isDaemon = true }.start()
    }

    fun flush() {
        try {
            val text = synchronized(root) { root.toString(1) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) { }
    }
}
