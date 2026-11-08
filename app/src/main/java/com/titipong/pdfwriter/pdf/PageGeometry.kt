package com.titipong.pdfwriter.pdf

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.util.Matrix

/**
 * Maps between the app's coordinates and PDF user space.
 *
 * App coordinates: origin at the top-left of the page *as displayed* (after /Rotate),
 * both axes in units of the displayed page width (so x is 0..1, y is 0..aspect).
 * PDF user space: points, origin bottom-left of the unrotated CropBox.
 */
class PageGeometry(
    private val llx: Float,
    private val lly: Float,
    private val urx: Float,
    private val ury: Float,
    rotation: Int
) {
    private val rot = ((rotation % 360) + 360) % 360
    private val w = urx - llx
    private val h = ury - lly
    val displayWidth: Float = if (rot == 90 || rot == 270) h else w
    val displayHeight: Float = if (rot == 90 || rot == 270) w else h

    fun toUser(nx: Float, ny: Float): FloatArray {
        val dx = nx * displayWidth
        val dy = ny * displayWidth
        return when (rot) {
            90 -> floatArrayOf(llx + dy, lly + dx)
            180 -> floatArrayOf(urx - dx, lly + dy)
            270 -> floatArrayOf(urx - dy, ury - dx)
            else -> floatArrayOf(llx + dx, ury - dy)
        }
    }

    fun toNormalized(x: Float, y: Float): FloatArray {
        val dx: Float
        val dy: Float
        when (rot) {
            90 -> { dx = y - lly; dy = x - llx }
            180 -> { dx = urx - x; dy = y - lly }
            270 -> { dx = ury - y; dy = urx - x }
            else -> { dx = x - llx; dy = ury - y }
        }
        return floatArrayOf(dx / displayWidth, dy / displayWidth)
    }

    /** "Display-up" space: displayed page, points, origin bottom-left, y up. */
    fun upX(nx: Float): Float = nx * displayWidth
    fun upY(ny: Float): Float = displayHeight - ny * displayWidth

    /** Content-stream matrix from display-up space to user space (so appearances look upright). */
    fun displayUpMatrix(): Matrix = when (rot) {
        90 -> Matrix(0f, 1f, -1f, 0f, urx, lly)
        180 -> Matrix(-1f, 0f, 0f, -1f, urx, ury)
        270 -> Matrix(0f, -1f, 1f, 0f, llx, ury)
        else -> Matrix(1f, 0f, 0f, 1f, llx, lly)
    }

    companion object {
        fun of(page: PDPage): PageGeometry {
            val box = page.cropBox
            return PageGeometry(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, page.rotation)
        }
    }
}
