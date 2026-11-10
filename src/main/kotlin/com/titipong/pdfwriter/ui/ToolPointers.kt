package com.titipong.pdfwriter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import com.titipong.pdfwriter.model.DrawTool

/** A mouse pointer showing the annotate tool in use: the tool's icon with a white outline. */
@Composable
fun toolPointer(tool: DrawTool): PointerIcon? {
    val name = when (tool) {
        DrawTool.PEN -> "ic_pen"
        DrawTool.HIGHLIGHTER -> "ic_highlight"
        DrawTool.ERASER -> "ic_eraser"
        DrawTool.IMAGE -> "ic_image"
        DrawTool.SELECT -> "ic_lasso"
        else -> return null
    }
    val painter = painterResource("drawable/$name.xml")
    val density = LocalDensity.current
    return remember(name) {
        try {
            val full = 32
            val s = 24f
            val off = 4f
            val img = ImageBitmap(full, full)
            val canvas = androidx.compose.ui.graphics.Canvas(img)
            CanvasDrawScope().draw(androidx.compose.ui.unit.Density(1f), LayoutDirection.Ltr, canvas, Size(full.toFloat(), full.toFloat())) {
                for ((dx, dy) in listOf(-1.5f to 0f, 1.5f to 0f, 0f to -1.5f, 0f to 1.5f, -1f to -1f, 1f to 1f, -1f to 1f, 1f to -1f)) {
                    translate(off + dx, off + dy) { with(painter) { draw(Size(s, s), colorFilter = ColorFilter.tint(Color.White)) } }
                }
                translate(off, off) { with(painter) { draw(Size(s, s), colorFilter = ColorFilter.tint(Color(0xFF111827))) } }
            }
            // Where it touches the page: the tip (bottom left) for pen / highlighter / eraser, else the middle.
            val hot = when (tool) {
                DrawTool.PEN, DrawTool.HIGHLIGHTER, DrawTool.ERASER -> java.awt.Point((off + s * 0.12f).toInt(), (off + s * 0.88f).toInt())
                else -> java.awt.Point(full / 2, full / 2)
            }
            PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(img.toAwtImage(), hot, name))
        } catch (e: Throwable) {
            null
        }
    }.also { if (density.density < 0f) Unit }
}
