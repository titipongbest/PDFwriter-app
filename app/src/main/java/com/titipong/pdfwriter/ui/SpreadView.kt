package com.titipong.pdfwriter.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * One row of the reader: one page, or two pages side by side (double / cover layouts).
 * Pages in a spread share the same height. With [fitViewport] (horizontal paging) the spread
 * fills the viewport and the pages are scaled to fit inside it and centered.
 */
class SpreadView(context: Context) : ViewGroup(context) {

    val pages: List<PdfPageView> = listOf(PdfPageView(context), PdfPageView(context))
    private val gap = (6 * resources.displayMetrics.density).toInt()
    private val widths = IntArray(2)
    private var pageHeight = 0

    /** How many of [pages] are used (1 or 2). */
    var count = 1
        set(value) {
            field = value.coerceIn(1, 2)
            pages[1].visibility = if (field == 2) View.VISIBLE else View.GONE
            requestLayout()
        }

    var fitViewport = false
        set(value) {
            field = value
            requestLayout()
        }

    init {
        pages.forEach { addView(it) }
        pages[1].visibility = View.GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var sumInverse = 0f
        for (i in 0 until count) sumInverse += 1f / pages[i].displayAspect.coerceAtLeast(0.01f)
        val gaps = gap * (count - 1)
        var h = ((w - gaps) / sumInverse)
        val viewportH = MeasureSpec.getSize(heightMeasureSpec)
        if (fitViewport && viewportH > 0 && h > viewportH) h = viewportH.toFloat()
        pageHeight = h.toInt().coerceAtLeast(1)
        for (i in 0 until count) {
            widths[i] = (h / pages[i].displayAspect.coerceAtLeast(0.01f)).toInt().coerceAtLeast(1)
            pages[i].measure(
                MeasureSpec.makeMeasureSpec(widths[i], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(pageHeight, MeasureSpec.EXACTLY)
            )
        }
        val height = if (fitViewport && viewportH > 0) viewportH else pageHeight
        setMeasuredDimension(w, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var total = gap * (count - 1)
        for (i in 0 until count) total += widths[i]
        var x = ((r - l) - total) / 2
        val y = if (fitViewport) ((b - t) - pageHeight) / 2 else 0
        for (i in 0 until count) {
            pages[i].layout(x, y, x + widths[i], y + pageHeight)
            x += widths[i] + gap
        }
    }
}
