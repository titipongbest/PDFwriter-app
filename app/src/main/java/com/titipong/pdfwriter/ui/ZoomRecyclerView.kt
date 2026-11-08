package com.titipong.pdfwriter.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * Page list with two-finger pinch zoom, one-finger pan when zoomed, and double-tap zoom.
 *
 * Works in two modes:
 *  - vertical: continuous vertical scrolling (the list scrolls Y, zoom pans X)
 *  - horizontal paging: one spread per screen, swipe left/right (the list scrolls X, zoom pans Y);
 *    pages snap into place when not zoomed.
 *
 * Children receive touch events mapped back to unzoomed coordinates, so drawing keeps working.
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
    private var ty = 0f
    private val inverse = Matrix()

    /** A confirmed single finger tap (not part of a double tap), anywhere on the list. */
    var onSingleTap: (() -> Unit)? = null

    /** Called when a zoom gesture finishes, with the final scale. */
    var onZoomSettled: ((Float) -> Unit)? = null

    var horizontalPaging = false
        private set
    private val snapHelper = PagerSnapHelper()
    private var snapAttached = false

    // Padding from XML (used in vertical mode); horizontal paging uses none.
    private val xmlPadding = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    private val base = xmlPadding.copyOf()

    private var blocking = false
    private var cancelSent = false
    private var lastPanX = 0f
    private var lastPanY = 0f
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
            val dfx = detector.focusX - lastFocusX
            val dfy = detector.focusY - lastFocusY
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            if (horizontalPaging) {
                ty += dfy
                scrollBy((-dfx / scale).roundToInt(), 0)
            } else {
                tx += dfx
                scrollBy(0, (-dfy / scale).roundToInt())
            }
            zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onZoomSettled?.invoke(scale)
        }
    }).apply { isQuickScaleEnabled = false }

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (scale > 1.05f) 1f else 2f, e.x, e.y)
            onZoomSettled?.invoke(scale)
            return true
        }
    })

    /** Switches between vertical scrolling and horizontal paging (resets zoom). */
    fun setPagingMode(horizontal: Boolean) {
        horizontalPaging = horizontal
        scale = 1f
        tx = 0f
        ty = 0f
        if (horizontal) base.fill(0) else xmlPadding.copyInto(base)
        setPadding(base[0], base[1], base[2], base[3])
        updateSnap()
        updateMatrix()
        invalidate()
    }

    /** Unzoomed list coordinates -> where they appear on screen (relative to this view). */
    fun toScreen(x: Float, y: Float): android.graphics.PointF =
        android.graphics.PointF(tx + x * scale, ty + y * scale)

    fun resetZoom() {
        zoomTo(1f, 0f, 0f)
        onZoomSettled?.invoke(scale)
    }

    private fun updateSnap() {
        val want = horizontalPaging && scale <= 1.01f
        if (want && !snapAttached) {
            try {
                snapHelper.attachToRecyclerView(this)
                snapAttached = true
            } catch (e: IllegalStateException) {
                // Another fling listener is set; paging just won't snap.
            }
        } else if (!want && snapAttached) {
            snapHelper.attachToRecyclerView(null)
            snapAttached = false
        }
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val s = target.coerceIn(MIN_SCALE, MAX_SCALE)
        if (s != scale) {
            if (horizontalPaging) {
                val contentY = (fy - ty) / scale
                ty = fy - contentY * s
                val dx = fx / scale - fx / s
                scale = s
                updateExtraPadding()
                scrollBy(dx.roundToInt(), 0)
            } else {
                val contentX = (fx - tx) / scale
                tx = fx - contentX * s
                val dy = fy / scale - fy / s
                scale = s
                updateExtraPadding()
                scrollBy(0, dy.roundToInt())
            }
            updateSnap()
        }
        clampPan()
        updateMatrix()
        invalidate()
    }

    /** Extra padding at the end of the scroll axis so the last page is fully reachable when zoomed. */
    private fun updateExtraPadding() {
        if (horizontalPaging) {
            val extra = if (width > 0) (width - width / scale).roundToInt() else 0
            setPadding(base[0], base[1], base[2] + extra, base[3])
        } else {
            val extra = if (height > 0) (height - height / scale).roundToInt() else 0
            setPadding(base[0], base[1], base[2], base[3] + extra)
        }
    }

    private fun clampPan() {
        if (horizontalPaging) {
            tx = 0f
            ty = ty.coerceIn(height - height * scale, 0f)
        } else {
            ty = 0f
            tx = tx.coerceIn(width - width * scale, 0f)
        }
    }

    private fun updateMatrix() {
        inverse.reset()
        inverse.setTranslate(-tx, -ty)
        inverse.postScale(1f / scale, 1f / scale)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(tx, ty)
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

        // One-finger pan across the scroll direction while zoomed (along it is the list's own scrolling).
        if (fingers && ev.pointerCount == 1 && scale > 1f) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastPanX = ev.x
                    lastPanY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (horizontalPaging) ty += ev.y - lastPanY else tx += ev.x - lastPanX
                    lastPanX = ev.x
                    lastPanY = ev.y
                    clampPan()
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
            updateExtraPadding()
            clampPan()
            updateMatrix()
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}
