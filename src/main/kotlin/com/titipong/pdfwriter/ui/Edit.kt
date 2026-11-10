package com.titipong.pdfwriter.ui

import com.titipong.pdfwriter.compat.RectF
import com.titipong.pdfwriter.model.ImageNote
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.ItemGroup
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextLayout
import com.titipong.pdfwriter.model.TextNote
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Geometry of annotations on a page (normalized coordinates: x 0..1 across the page width, y in
 * width units), shared by drawing and editing. [measure] gives a string's width at text size 1.
 */
class PageGeometryKit(val measure: (String) -> Float) {

    fun layout(t: TextNote): List<TextLayout.Line> = TextLayout.lines(t.text, t.width) { measure(it) * t.size }

    fun boxWidth(t: TextNote, lines: List<TextLayout.Line> = layout(t)): Float =
        if (t.width > 0f) t.width else lines.maxOfOrNull { it.width } ?: 0f

    fun textBox(t: TextNote): RectF {
        val lines = layout(t)
        val bottom = t.y + t.size * TextLayout.LINE_HEIGHT * (lines.size - 1) + t.size * 0.3f
        return RectF(t.x, t.y - t.size, t.x + boxWidth(t, lines), bottom)
    }

    fun strokeBox(st: InkStroke): RectF? {
        if (st.points.isEmpty()) return null
        val r = st.width / 2f
        return RectF(st.points.minOf { it.x } - r, st.points.minOf { it.y } - r, st.points.maxOf { it.x } + r, st.points.maxOf { it.y } + r)
    }

    /** The frame drawn around a selected item ([pad] around text boxes and groups). */
    fun frame(item: Any, pad: Float): RectF? = when (item) {
        is ItemGroup -> {
            var r: RectF? = null
            for (it in item.items) {
                val f = (if (it is TextNote) textBox(it) else frame(it, 0f)) ?: continue
                r = r?.apply { union(f) } ?: RectF(f)
            }
            r?.apply { inset(-pad, -pad) }
        }
        is ImageNote -> RectF(item.x, item.y, item.x + item.w, item.y + item.h)
        is InkStroke -> strokeBox(item)
        is TextNote -> textBox(item).apply { inset(-pad, -pad) }
        else -> null
    }

    fun corners(f: RectF) = listOf(f.left to f.top, f.right to f.top, f.right to f.bottom, f.left to f.bottom)

    fun textAt(a: PageAnnotations, x: Float, y: Float, pad: Float): TextNote? = a.texts.lastOrNull { t ->
        val b = textBox(t)
        x >= b.left - pad && x <= b.right + pad && y >= b.top - pad && y <= b.bottom + pad
    }

    fun imageAt(a: PageAnnotations, x: Float, y: Float): ImageNote? =
        a.images.lastOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }

    fun strokeAt(a: PageAnnotations, x: Float, y: Float, pad: Float, highlighterOnly: Boolean = false): InkStroke? =
        a.strokes.lastOrNull { st -> (!highlighterOnly || st.highlighter) && hits(st, x, y, st.width / 2f + pad) }

    fun hits(st: InkStroke, x: Float, y: Float, reach: Float): Boolean {
        val pts = st.points
        return pts.any { hypot(it.x - x, it.y - y) < reach } || (1 until pts.size).any { segDist(pts[it - 1], pts[it], x, y) < reach }
    }

    // ------------------------------------------------------------ moving / resizing

    fun shifted(item: Any, dx: Float, dy: Float): Any = when (item) {
        is TextNote -> item.copy(x = item.x + dx, y = item.y + dy)
        is ImageNote -> ImageNote(item.file, item.x + dx, item.y + dy, item.w, item.h)
        is InkStroke -> InkStroke(item.points.map { StrokePoint(it.x + dx, it.y + dy, it.pressure) }.toMutableList(), item.color, item.width, item.highlighter)
        is ItemGroup -> ItemGroup(item.items.map { shifted(it, dx, dy) })
        else -> item
    }

    /** Text box / picture scaled from corner [corner] (0 tl, 1 tr, 2 br, 3 bl) to (x, y); the opposite corner stays. */
    fun resized(orig: Any, corner: Int, x: Float, y: Float, pad: Float): Any? {
        val f = frame(orig, pad) ?: return null
        val (ax, ay) = corners(f)[(corner + 2) % 4]
        val scale = max(abs(x - ax) / f.width().coerceAtLeast(1e-4f), abs(y - ay) / f.height().coerceAtLeast(1e-4f)).coerceIn(0.2f, 8f)
        if (orig is ImageNote) {
            val w = (orig.w * scale).coerceIn(0.02f, 3f)
            val h = orig.h * (w / orig.w)
            val left = if (corner == 0 || corner == 3) ax - w else ax
            val top = if (corner == 0 || corner == 1) ay - h else ay
            return ImageNote(orig.file, left, top, w, h)
        }
        if (orig is ItemGroup) return scaledGroup(orig, corner, x, y, pad)
        if (orig !is TextNote) return null
        val size = (orig.size * scale).coerceIn(0.006f, 0.4f)
        val width = if (orig.width > 0f) orig.width * (size / orig.size) else 0f
        val trial = orig.copy(size = size, width = width)
        val nf = frame(trial, pad) ?: return null
        val (tx, ty) = corners(nf)[(corner + 2) % 4]
        return trial.copy(x = orig.x + (ax - tx), y = orig.y + (ay - ty))
    }

    fun scaledGroup(g: ItemGroup, corner: Int, x: Float, y: Float, pad: Float): ItemGroup? {
        val f = frame(g, pad) ?: return null
        val (ax, ay) = corners(f)[(corner + 2) % 4]
        val k = max(abs(x - ax) / f.width().coerceAtLeast(1e-4f), abs(y - ay) / f.height().coerceAtLeast(1e-4f)).coerceIn(0.1f, 10f)
        fun sx(v: Float) = ax + (v - ax) * k
        fun sy(v: Float) = ay + (v - ay) * k
        return ItemGroup(g.items.map {
            when (it) {
                is InkStroke -> InkStroke(it.points.map { p -> StrokePoint(sx(p.x), sy(p.y), p.pressure) }.toMutableList(),
                    it.color, (it.width * k).coerceIn(0.0005f, 0.2f), it.highlighter)
                is TextNote -> it.copy(x = sx(it.x), y = sy(it.y), size = (it.size * k).coerceIn(0.004f, 0.4f),
                    width = if (it.width > 0f) it.width * k else 0f)
                is ImageNote -> ImageNote(it.file, sx(it.x), sy(it.y), it.w * k, it.h * k)
                else -> it
            }
        })
    }

    /** Text box with its left or right side moved to [x] (an explicit width: the text wraps). */
    fun widened(orig: TextNote, leftSide: Boolean, x: Float): TextNote {
        val b = textBox(orig)
        val minW = orig.size * 2f
        return if (leftSide) {
            val newLeft = x.coerceAtMost(b.right - minW)
            orig.copy(x = newLeft, width = b.right - newLeft)
        } else orig.copy(width = (x - b.left).coerceAtLeast(minW))
    }

    /** A straight highlighter line with end [end] moved along its direction to (x, y). */
    fun stretched(orig: InkStroke, end: Int, x: Float, y: Float): InkStroke {
        val fixed = orig.points[1 - end]
        val moving = orig.points[end]
        val len = hypot(moving.x - fixed.x, moving.y - fixed.y).coerceAtLeast(1e-5f)
        val ux = (moving.x - fixed.x) / len
        val uy = (moving.y - fixed.y) / len
        val newLen = ((x - fixed.x) * ux + (y - fixed.y) * uy).coerceAtLeast(orig.width)
        val p = StrokePoint(fixed.x + ux * newLen, fixed.y + uy * newLen, moving.pressure)
        val pts = if (end == 0) mutableListOf(p, fixed) else mutableListOf(fixed, p)
        return InkStroke(pts, orig.color, orig.width, orig.highlighter)
    }

    // ------------------------------------------------------------ alignment guides

    /** Lines to align with: page edges and center, other boxes / pictures, the document's text lines. */
    fun guideTargets(a: PageAnnotations?, aspect: Float, exclude: List<Any?>, docLines: List<RectF>): Pair<List<Float>, List<Float>> {
        val xs = arrayListOf(0f, 0.5f, 1f)
        val ys = arrayListOf(0f, aspect / 2f, aspect)
        if (a != null) {
            val skip = HashSet<Any>()
            for (g in exclude) if (g is ItemGroup) skip.addAll(g.items) else if (g != null) skip.add(g)
            val frames = a.texts.filter { it !in skip }.map { textBox(it) } +
                a.images.filter { it !in skip }.map { RectF(it.x, it.y, it.x + it.w, it.y + it.h) }
            for (f in frames) {
                xs += f.left; xs += f.centerX(); xs += f.right
                ys += f.top; ys += f.centerY(); ys += f.bottom
            }
        }
        for (r in docLines) { xs += r.left; xs += r.right; ys += r.bottom }
        return xs to ys
    }

    fun nearest(values: FloatArray, targets: List<Float>, thr: Float): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        var bestD = thr
        for (v in values) for (t in targets) {
            val d = abs(t - v)
            if (d < bestD) { bestD = d; best = (t - v) to t }
        }
        return best
    }

    // ------------------------------------------------------------ ink

    /** Smoothing settings applied to a finished stroke. */
    fun refine(s: InkStroke, inkSmoothing: Boolean, straighten: Boolean) {
        val pts = s.points
        if (s.highlighter) {
            if (!straighten || pts.size < 2) return
            val a = pts.first()
            var b = pts.last()
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 0.02f) return
            val dev = pts.maxOf { segDist(a, b, it.x, it.y) }
            if (dev > max(0.006f, len * 0.06f)) return
            if (abs(b.y - a.y) < abs(b.x - a.x) * 0.1f) b = StrokePoint(b.x, a.y, b.pressure)
            pts.clear(); pts.add(a); pts.add(b)
        } else {
            if (!inkSmoothing || pts.size < 3) return
            var cur: List<StrokePoint> = ArrayList(pts)
            repeat(2) {
                val out = ArrayList<StrokePoint>(cur.size)
                out.add(cur.first())
                for (i in 1 until cur.size - 1) {
                    val p0 = cur[i - 1]; val p1 = cur[i]; val p2 = cur[i + 1]
                    out.add(StrokePoint((p0.x + 2 * p1.x + p2.x) / 4f, (p0.y + 2 * p1.y + p2.y) / 4f, (p0.pressure + 2 * p1.pressure + p2.pressure) / 4f))
                }
                out.add(cur.last())
                cur = out
            }
            pts.clear(); pts.addAll(cur)
        }
    }

    /** Pixel eraser: cuts the ink under the eraser (rest kept as separate strokes). Returns removed / added. */
    fun erasePixels(a: PageAnnotations, x: Float, y: Float, rn: Float): Pair<List<InkStroke>, List<InkStroke>> {
        val removed = ArrayList<InkStroke>()
        val added = ArrayList<InkStroke>()
        val out = ArrayList<InkStroke>(a.strokes.size)
        for (st in a.strokes) {
            val reach = rn + st.width / 2f
            if (!hits(st, x, y, reach)) { out.add(st); continue }
            removed.add(st)
            val pts = st.points
            var run = ArrayList<StrokePoint>()
            for (i in pts.indices) {
                val p = pts[i]
                val inside = hypot(p.x - x, p.y - y) < reach
                val cut = i > 0 && segDist(pts[i - 1], p, x, y) < reach
                if (inside || cut) {
                    if (run.size >= 2) InkStroke(run, st.color, st.width, st.highlighter).also { out.add(it); added.add(it) }
                    run = ArrayList()
                }
                if (!inside) run.add(p)
            }
            if (run.size >= 2) InkStroke(run, st.color, st.width, st.highlighter).also { out.add(it); added.add(it) }
        }
        if (removed.isNotEmpty()) { a.strokes.clear(); a.strokes.addAll(out) }
        return removed to added
    }

    companion object {
        fun segDist(a: StrokePoint, b: StrokePoint, x: Float, y: Float): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0f) (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f) else 0f
            return hypot(a.x + t * dx - x, a.y + t * dy - y)
        }

        fun pointInPolygon(poly: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val (ax, ay) = poly[i]
                val (bx, by) = poly[j]
                if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
                j = i
            }
            return inside
        }
    }
}
