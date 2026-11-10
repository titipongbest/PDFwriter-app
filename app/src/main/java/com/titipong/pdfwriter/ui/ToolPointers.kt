package com.titipong.pdfwriter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.PointerIcon
import androidx.core.content.ContextCompat
import com.titipong.pdfwriter.R
import com.titipong.pdfwriter.model.DrawTool

/** Mouse / pen hover pointers that show the annotate tool in use (the tool's icon, outlined). */
object ToolPointers {
    private val cache = HashMap<DrawTool, PointerIcon?>()
    private val images = HashMap<DrawTool, Triple<Bitmap, Float, Float>?>()

    fun forTool(context: Context, tool: DrawTool): PointerIcon? {
        return cache.getOrPut(tool) {
            image(context, tool)?.let { (b, x, y) -> PointerIcon.create(b, x, y) }
        }
    }

    /** The tool's pointer picture and its hot spot (the point that touches the page). */
    fun image(context: Context, tool: DrawTool): Triple<Bitmap, Float, Float>? =
        images.getOrPut(tool) { try { make(context, tool) } catch (e: Throwable) { null } }

    private fun make(context: Context, tool: DrawTool): Triple<Bitmap, Float, Float>? {
        val res = when (tool) {
            DrawTool.PEN -> R.drawable.ic_pen
            DrawTool.HIGHLIGHTER -> R.drawable.ic_highlight
            DrawTool.ERASER -> R.drawable.ic_eraser
            DrawTool.IMAGE -> R.drawable.ic_image
            DrawTool.SELECT -> R.drawable.ic_lasso
            DrawTool.TEXT -> R.drawable.ic_text
            else -> return null
        }
        val d = context.resources.displayMetrics.density
        val size = (24 * d).toInt().coerceAtLeast(24)
        val pad = (2 * d).toInt().coerceAtLeast(2)
        val full = size + pad * 2
        val bmp = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun draw(color: Int, dx: Int, dy: Int) {
            val dr = ContextCompat.getDrawable(context, res)?.mutate() ?: return
            dr.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            dr.setBounds(pad + dx, pad + dy, pad + dx + size, pad + dy + size)
            dr.draw(c)
        }
        // A white outline so it shows on dark pages too.
        val o = (1.5f * d).toInt().coerceAtLeast(1)
        for ((dx, dy) in listOf(-o to 0, o to 0, 0 to -o, 0 to o, -o to -o, o to o, -o to o, o to -o)) draw(Color.WHITE, dx, dy)
        draw(Color.parseColor("#111827"), 0, 0)
        // Where the tool touches the page: the pen / highlighter / eraser tip (bottom left), else the middle.
        val (hx, hy) = when (tool) {
            DrawTool.PEN, DrawTool.HIGHLIGHTER, DrawTool.ERASER -> (pad + size * 0.12f) to (pad + size * 0.88f)
            else -> (full / 2f) to (full / 2f)
        }
        return Triple(bmp, hx, hy)
    }
}
