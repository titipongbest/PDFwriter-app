package com.titipong.pdfwriter.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Fast page scrubber along the right edge (view mode): drag the handle up/down to jump
 * through the document; a bubble shows the page number while dragging.
 * Only the strip at the very right edge takes touches, the rest passes through to the page.
 */
class PageScrubber(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {

    var pageCount = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Current page (0-based); ignored while the user is dragging. */
    var page = 0
        set(value) {
            if (!dragging && field != value) {
                field = value
                invalidate()
            }
        }

    /** (page, finished): called whenever the dragged-to page changes, and once on release. */
    var onScrub: ((Int, Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val touchWidth = 40 * d
    private val pad = 24 * d
    private var dragging = false

    /** 0 = resting (small, faint), 1 = being touched (big, solid); animated in between. */
    private var emphasis = 0f
    private var animator: android.animation.ValueAnimator? = null

    private fun animateEmphasis(target: Float) {
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(emphasis, target).apply {
            duration = 160
            addUpdateListener {
                emphasis = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun lerp(a: Float, b: Float) = a + (b - a) * emphasis

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB") }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15 * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    private val trackX get() = width - touchWidth / 2f

    private fun yForPage(p: Int): Float {
        val span = height - 2 * pad
        return if (pageCount <= 1) pad else pad + span * p / (pageCount - 1).toFloat()
    }

    private fun pageForY(y: Float): Int {
        if (pageCount <= 1) return 0
        val f = ((y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
        return (f * (pageCount - 1)).roundToInt()
    }

    override fun onDraw(canvas: Canvas) {
        if (pageCount <= 1) return
        val x = trackX
        // Resting: hairline track and a slim, faint handle. Touched: wider, solid blue handle.
        val trackW = lerp(1f, 3f) * d
        trackPaint.color = Color.argb(lerp(18f, 50f).toInt(), 0, 0, 0)
        rect.set(x - trackW / 2, pad, x + trackW / 2, height - pad)
        canvas.drawRoundRect(rect, trackW, trackW, trackPaint)
        val handleW = lerp(3f, 8f) * d
        val handleH = lerp(28f, 52f) * d
        handlePaint.color = blend(Color.argb(80, 55, 65, 81), Color.parseColor("#2563EB"), emphasis)
        val y = yForPage(page)
        rect.set(x - handleW / 2, y - handleH / 2, x + handleW / 2, y + handleH / 2)
        canvas.drawRoundRect(rect, handleW / 2, handleW / 2, handlePaint)
        if (dragging) {
            val label = "${page + 1} / $pageCount"
            val tw = textPaint.measureText(label)
            val bw = tw + 24 * d
            val bh = 34 * d
            val right = x - 16 * d
            val top = (y - bh / 2).coerceIn(0f, height - bh)
            rect.set(right - bw, top, right, top + bh)
            canvas.drawRoundRect(rect, bh / 2, bh / 2, bubblePaint)
            canvas.drawText(label, rect.centerX(), rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pageCount <= 1) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x < width - touchWidth) return false // not on the strip: let the page get it
                dragging = true
                animateEmphasis(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubTo(e.y, finished = false)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) scrubTo(e.y, finished = false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onScrub?.invoke(page, true)
                    animateEmphasis(0f)
                }
            }
        }
        return true
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun scrubTo(y: Float, finished: Boolean) {
        val p = pageForY(y)
        val changed = p != page
        // Bypass the "ignored while dragging" rule of the setter.
        val wasDragging = dragging
        dragging = false
        page = p
        dragging = wasDragging
        if (changed || finished) onScrub?.invoke(p, finished)
        invalidate()
    }
}
