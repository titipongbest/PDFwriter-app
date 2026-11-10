package com.titipong.pdfwriter.compat

/** Minimal stand-ins for Android's RectF / PointF / Color, so the shared code ports unchanged. */
class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(r: RectF) : this(r.left, r.top, r.right, r.bottom)

    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    val isEmpty: Boolean get() = left >= right || top >= bottom
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun set(r: RectF) = set(r.left, r.top, r.right, r.bottom)
    fun contains(x: Float, y: Float) = left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
    fun inset(dx: Float, dy: Float) { left += dx; top += dy; right -= dx; bottom -= dy }
    fun offset(dx: Float, dy: Float) { left += dx; right += dx; top += dy; bottom += dy }
    fun union(r: RectF) {
        if (r.left >= r.right || r.top >= r.bottom) return
        if (left < right && top < bottom) {
            if (left > r.left) left = r.left
            if (top > r.top) top = r.top
            if (right < r.right) right = r.right
            if (bottom < r.bottom) bottom = r.bottom
        } else set(r)
    }
    fun intersects(r: RectF) = left < r.right && r.left < right && top < r.bottom && r.top < bottom
    override fun toString() = "RectF($left, $top, $right, $bottom)"
}

class PointF(var x: Float = 0f, var y: Float = 0f)

object Color {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val TRANSPARENT = 0
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun parseColor(s: String): Int {
        val h = s.removePrefix("#")
        return when (h.length) {
            6 -> (0xFF000000 or h.toLong(16)).toInt()
            8 -> h.toLong(16).toInt()
            else -> throw IllegalArgumentException("Unknown color")
        }
    }
    fun HSVToColor(hsv: FloatArray): Int = java.awt.Color.HSBtoRGB(hsv[0] / 360f, hsv[1], hsv[2])
    fun colorToHSV(c: Int, out: FloatArray) {
        val hsb = java.awt.Color.RGBtoHSB(red(c), green(c), blue(c), null)
        out[0] = hsb[0] * 360f; out[1] = hsb[1]; out[2] = hsb[2]
    }
}
