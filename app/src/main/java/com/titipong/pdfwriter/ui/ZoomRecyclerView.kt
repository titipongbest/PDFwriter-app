package com.titipong.pdfwriter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Vertical page list with two-finger pinch zoom, one-finger pan when zoomed, and double-tap zoom.
 * Children receive touch events already mapped back to unzoomed coordinates, so drawing keeps working.
 * Gestures only react to fingers: the S Pen never zooms or pans, and a palm resting while writing
 * (finger + stylus) doesn't count as a pinch.
 */
class ZoomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    var scale = 1f
        private set
    private var tx = 0f
    private val inverse = Matrix()

    /** Called when a zoom gesture finishes, with the final scale. */
    var onZoomSettled: ((Float) -> Unit)? = null

    private var basePaddingBottom = -1
    private var blocking = false
    private var cancelSent = false
    private var lastPanX = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Pan with the moving focus, then zoom around it.
            tx += detector.focusX - lastFocusX
            val dy = (lastFocusY - detector.focusY) / scale
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            scrollBy(0, dy.roundToInt())
            zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onZoomSettled?.invoke(scale)
        }
    }).apply { isQuickScaleEnabled = false }

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (scale > 1.05f) 1f else 2f, e.x, e.y)
            onZoomSettled?.invoke(scale)
            return true
        }
    })

    fun resetZoom() {
        zoomTo(1f, 0f, 0f)
        onZoomSettled?.invoke(scale)
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val s = target.coerceIn(MIN_SCALE, MAX_SCALE)
        if (s != scale) {
            val contentX = (fx - tx) / scale
            tx = fx - contentX * s
            val dy = fy / scale - fy / s
            scale = s
            updateBottomPadding()
            scrollBy(0, dy.roundToInt())
        }
        clampTx()
        updateMatrix()
        invalidate()
    }

    /** Extra bottom padding so the end of the last page is reachable while zoomed in. */
    private fun updateBottomPadding() {
        if (basePaddingBottom < 0) basePaddingBottom = paddingBottom
        val extra = if (height > 0) (height - height / scale).roundToInt() else 0
        setPadding(paddingLeft, paddingTop, paddingRight, basePaddingBottom + extra)
    }

    private fun clampTx() {
        val minTx = width - width * scale
        tx = tx.coerceIn(minTx, 0f)
    }

    private fun updateMatrix() {
        inverse.reset()
        inverse.setTranslate(-tx, 0f)
        inverse.postScale(1f / scale, 1f / scale)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(tx, 0f)
        canvas.scale(scale, scale)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    private fun allFingers(ev: MotionEvent): Boolean {
        for (i in 0 until ev.pointerCount) {
            if (ev.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) return false
        }
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val fingers = allFingers(ev)
        if (fingers) {
            scaleDetector.onTouchEvent(ev)
            tapDetector.onTouchEvent(ev)
        }
        val action = ev.actionMasked

        // A second finger starts a pinch: cancel whatever the children/list were doing.
        if (fingers && action == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount >= 2) blocking = true

        if (blocking) {
            if (!cancelSent) {
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                cancel.transform(inverse)
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                cancelSent = true
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                blocking = false
                cancelSent = false
            }
            return true
        }

        // One-finger horizontal pan while zoomed (vertical is the list's own scrolling).
        if (fingers && ev.pointerCount == 1 && scale > 1f) {
            when (action) {
                MotionEvent.ACTION_DOWN -> lastPanX = ev.x
                MotionEvent.ACTION_MOVE -> {
                    tx += ev.x - lastPanX
                    lastPanX = ev.x
                    clampTx()
                    updateMatrix()
                    invalidate()
                }
            }
        }

        val mapped = MotionEvent.obtain(ev)
        mapped.transform(inverse)
        val handled = super.dispatchTouchEvent(mapped)
        mapped.recycle()
        return handled
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (scale != 1f) {
            updateBottomPadding()
            clampTx()
            updateMatrix()
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}
