package com.titipong.pdfwriter

import com.titipong.pdfwriter.compat.RectF
import com.titipong.pdfwriter.model.InkStroke
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Handwriting to text with the Windows handwriting recognizer (Windows Ink, offline), through a
 * small helper program shipped with the app (hwr\hwr.exe).
 */
object Handwriting {

    class Line(val text: String, val box: RectF)

    private fun box(st: InkStroke) = RectF(
        st.points.minOf { it.x }, st.points.minOf { it.y }, st.points.maxOf { it.x }, st.points.maxOf { it.y }
    )

    /** Strokes grouped into text lines (marks above / below a line stay with it), in writing order. */
    private fun lines(strokes: List<InkStroke>): List<List<InkStroke>> {
        class L(var top: Float, var bottom: Float, val items: MutableList<Pair<Int, InkStroke>>)
        val out = ArrayList<L>()
        val sorted = strokes.withIndex().filter { it.value.points.isNotEmpty() }.sortedByDescending { box(it.value).height() }
        for ((i, st) in sorted) {
            val b = box(st)
            val line = out.firstOrNull { l ->
                val h = (l.bottom - l.top).coerceAtLeast(1e-4f)
                b.centerY() > l.top - h * 0.6f && b.centerY() < l.bottom + h * 0.6f
            }
            if (line == null) out.add(L(b.top, b.bottom, mutableListOf(i to st)))
            else {
                line.items.add(i to st)
                line.top = minOf(line.top, b.top)
                line.bottom = maxOf(line.bottom, b.bottom)
            }
        }
        return out.sortedBy { it.top }.map { l -> l.items.sortedBy { it.first }.map { it.second } }
    }

    fun recognize(strokes: List<InkStroke>, lang: String): List<Line> {
        val exe = File(AppDirs.resources, "hwr/hwr.exe")
        if (!exe.exists()) throw IllegalStateException("Handwriting recognition is only available on Windows")
        val groups = lines(strokes)
        val input = StringBuilder(lang).append('\n')
        for (g in groups) {
            input.append("L\n")
            for (st in g) {
                input.append("S")
                for (p in st.points) input.append(' ').append(String.format(Locale.US, "%.1f,%.1f", p.x * 1000f, p.y * 1000f))
                input.append('\n')
            }
        }
        val proc = ProcessBuilder(exe.absolutePath).redirectErrorStream(true).start()
        proc.outputStream.use { it.write(input.toString().toByteArray(Charsets.UTF_8)) }
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readLines()
        proc.waitFor(30, TimeUnit.SECONDS)
        out.firstOrNull { it.startsWith("ERR") }?.let { err ->
            if (err.contains("NO_RECOGNIZER")) {
                throw IllegalStateException(
                    if (lang == "th") "Thai handwriting isn't installed in Windows. Add it in Settings > Time & language > Language > Thai > Options > Handwriting."
                    else "English handwriting isn't installed in Windows (Settings > Time & language > Language)."
                )
            }
            throw IllegalStateException("Couldn't read the handwriting")
        }
        return groups.mapIndexedNotNull { i, g ->
            val text = out.getOrNull(i)?.trim().orEmpty()
            if (text.isEmpty()) null else {
                var b: RectF? = null
                for (st in g) { val r = box(st); b = b?.apply { union(r) } ?: RectF(r) }
                Line(text, b!!)
            }
        }
    }
}
