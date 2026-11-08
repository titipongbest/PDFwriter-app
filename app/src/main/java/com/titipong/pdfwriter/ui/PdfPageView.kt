package com.titipong.pdfwriter.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextNote
import kotlin.math.hypot
import kotlin.math.max

/**
 * One PDF page plus its ink layer.
 *  - Finger: not consumed, so the RecyclerView scrolls (except in TEXT mode, where a tap places text).
 *  - S Pen / stylus: draws.
 *  - S Pen side button held (or the stylus eraser end): erases.
 */
class PdfPageView(context: Context) : View(context) {

    interface Host {
        val tool: DrawTool
        val color: Int
        val strokeWidth: Float
        fun annotationsFor(page: Int): PageAnnotations
        fun onItemAdded(page: Int, item: Any)
        fun onItemsErased(page: Int)
        fun requestText(page: Int, x: Float, y: Float)
    }

    private enum class Mode { NONE, INK, ERASE, TEXT }

    var host: Host? = null
    var pageIndex = -1

    var pageAspect = 1.4142f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var annotations: PageAnnotations? = null
        set(value) {
            field = value
            invalidateInk()
        }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minEraserPx = 10f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#9CA3AF")
    }
    private val path = Path()
    private val dst = RectF()

    private var inkCache: Bitmap? = null
    private var inkDirty = true

    private var mode = Mode.NONE
    private var active: InkStroke? = null
    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserRadius = 0f
    private var fingerDownX = 0f
    private var fingerDownY = 0f

    fun invalidateInk() {
        inkDirty = true
        invalidate()
    }

    fun release() {
        inkCache?.recycle()
        inkCache = null
        inkDirty = true
        bitmap = null
        active = null
        mode = Mode.NONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * pageAspect).toInt().coerceAtLeast(1))
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        canvas.drawColor(Color.WHITE)
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            dst.set(0f, 0f, w, height.toFloat())
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }
        val a = annotations
        if (a != null && !a.isEmpty()) {
            // Zoomed in: draw ink as vectors so it stays sharp. At 1x a bitmap cache keeps it fast.
            val zoomed = ((parent as? ZoomRecyclerView)?.scale ?: 1f) > 1.01f
            val cache = if (zoomed) {
                null
            } else {
                ensureInkCache(a)
                inkCache
            }
            if (cache != null) canvas.drawBitmap(cache, 0f, 0f, null) else renderAnnotations(canvas, a, w)
        }
        active?.let { drawStroke(canvas, it, w) }
        if (mode == Mode.ERASE) canvas.drawCircle(eraserX, eraserY, eraserRadius, eraserPaint)
    }

    private fun ensureInkCache(a: PageAnnotations) {
        if (width <= 0 || height <= 0) return
        var cache = inkCache
        if (cache == null || cache.width != width || cache.height != height) {
            cache?.recycle()
            cache = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                null
            }
            inkCache = cache
            inkDirty = true
        }
        val target = cache ?: return
        if (!inkDirty) return
        target.eraseColor(Color.TRANSPARENT)
        renderAnnotations(Canvas(target), a, width.toFloat())
        inkDirty = false
    }

    private fun renderAnnotations(canvas: Canvas, a: PageAnnotations, w: Float) {
        for (s in a.strokes) drawStroke(canvas, s, w)
        for (t in a.texts) {
            textPaint.color = t.color
            textPaint.textSize = t.size * w
            canvas.drawText(t.text, t.x * w, t.y * w, textPaint)
        }
    }

    private fun drawStroke(canvas: Canvas, s: InkStroke, w: Float) {
        val pts = s.points
        if (pts.isEmpty()) return
        inkPaint.color = s.color
        if (s.highlighter) {
            inkPaint.alpha = 90
            inkPaint.strokeCap = Paint.Cap.SQUARE
            inkPaint.strokeWidth = s.width * w
            path.reset()
            path.moveTo(pts[0].x * w, pts[0].y * w)
            for (i in 1 until pts.size) path.lineTo(pts[i].x * w, pts[i].y * w)
            if (pts.size == 1) path.lineTo(pts[0].x * w + 0.1f, pts[0].y * w)
            canvas.drawPath(path, inkPaint)
        } else {
            inkPaint.strokeCap = Paint.Cap.ROUND
            if (pts.size == 1) {
                inkPaint.strokeWidth = s.width * w * pressureScale(pts[0].pressure)
                canvas.drawPoint(pts[0].x * w, pts[0].y * w, inkPaint)
                return
            }
            // Per-segment width follows pen pressure.
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                inkPaint.strokeWidth = s.width * w * pressureScale((a.pressure + b.pressure) / 2f)
                canvas.drawLine(a.x * w, a.y * w, b.x * w, b.y * w, inkPaint)
            }
        }
    }

    // ---------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = host ?: return false
        val toolType = e.getToolType(0)
        val isPen = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (!isPen) return onFinger(e, h)

        val eraserButton = penButtonOverride ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                begin(e, h, eraserButton)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    begin(e, h, eraserButton)
                } else if (eraserButton && mode == Mode.INK) {
                    // Side button pressed mid-stroke: finish the stroke, continue as eraser.
                    commitStroke(h)
                    mode = Mode.ERASE
                }
                when (mode) {
                    Mode.INK -> appendPoints(e)
                    Mode.ERASE -> {
                        for (i in 0 until e.historySize) eraseAt(e.getHistoricalX(i), e.getHistoricalY(i), h)
                        eraseAt(e.x, e.y, h)
                    }
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.INK -> {
                        appendPoints(e)
                        commitStroke(h)
                    }
                    Mode.TEXT -> if (width > 0) h.requestText(pageIndex, e.x / width, e.y / width)
                    else -> {}
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                active = null
                endGesture()
            }
        }
        return true
    }

    private fun onFinger(e: MotionEvent, h: Host): Boolean {
        if (h.tool != DrawTool.TEXT) return false // let the list scroll
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDownX = e.x
                fingerDownY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (hypot(e.x - fingerDownX, e.y - fingerDownY) < touchSlop && width > 0) {
                    performClick()
                    h.requestText(pageIndex, e.x / width, e.y / width)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun begin(e: MotionEvent, h: Host, eraserButton: Boolean) {
        val tool = if (eraserButton) DrawTool.ERASER else h.tool
        when (tool) {
            DrawTool.ERASER -> {
                mode = Mode.ERASE
                eraseAt(e.x, e.y, h)
            }
            DrawTool.TEXT -> mode = Mode.TEXT
            DrawTool.PEN, DrawTool.HIGHLIGHTER -> {
                mode = Mode.INK
                val hl = tool == DrawTool.HIGHLIGHTER
                active = InkStroke(mutableListOf(), h.color, if (hl) h.strokeWidth * 5f else h.strokeWidth, hl)
                addPoint(e.x, e.y, e.pressure)
            }
        }
        invalidate()
    }

    private fun addPoint(x: Float, y: Float, pressure: Float) {
        val w = width.toFloat()
        if (w <= 0f) return
        active?.points?.add(StrokePoint(x / w, y / w, pressure))
    }

    private fun appendPoints(e: MotionEvent) {
        for (i in 0 until e.historySize) {
            addPoint(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i))
        }
        addPoint(e.x, e.y, e.pressure)
    }

    private fun commitStroke(h: Host) {
        val s = active ?: return
        active = null
        if (s.points.isEmpty()) return
        val a = annotations ?: h.annotationsFor(pageIndex).also { annotations = it }
        a.strokes.add(s)
        // Draw just the new stroke onto the cache instead of rebuilding everything.
        val cache = inkCache
        if (!inkDirty && cache != null && cache.width == width && cache.height == height) {
            drawStroke(Canvas(cache), s, width.toFloat())
        } else {
            inkDirty = true
        }
        invalidate()
        h.onItemAdded(pageIndex, s)
    }

    private fun eraseAt(px: Float, py: Float, h: Host) {
        val w = width.toFloat()
        if (w <= 0f) return
        eraserRadius = max(h.strokeWidth * w * 3f, minEraserPx)
        eraserX = px
        eraserY = py
        val a = annotations ?: return
        val x = px / w
        val y = py / w
        val rn = eraserRadius / w
        val strokesRemoved = a.strokes.removeAll { s ->
            val reach = rn + s.width / 2f
            s.points.any { p -> hypot(p.x - x, p.y - y) < reach }
        }
        val textsRemoved = a.texts.removeAll { t ->
            textPaint.textSize = t.size * w
            val tw = textPaint.measureText(t.text) / w
            x >= t.x - rn && x <= t.x + tw + rn && y >= t.y - t.size - rn && y <= t.y + rn
        }
        if (strokesRemoved || textsRemoved) {
            inkDirty = true
            h.onItemsErased(pageIndex)
        }
    }

    private fun endGesture() {
        mode = Mode.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    companion object {
        /** Set by MainActivity while it re-dispatches Samsung's legacy "side button held" events. */
        @JvmStatic
        var penButtonOverride = false

        fun pressureScale(p: Float): Float = 0.4f + p.coerceIn(0f, 1f) * 1.2f
    }
}
