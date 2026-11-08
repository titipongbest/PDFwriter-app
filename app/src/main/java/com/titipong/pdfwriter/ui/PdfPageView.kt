package com.titipong.pdfwriter.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.model.ImageNote
import kotlin.math.hypot
import kotlin.math.max

/**
 * One PDF page plus its ink layer.
 *  - Finger: not consumed, so the list scrolls (except in TEXT mode, where a tap places text).
 *  - S Pen / stylus: draws.
 *  - S Pen side button held (or the stylus eraser end): erases.
 *
 * Ink is stored in page-normalized coordinates (x across the page width = 0..1), independent of
 * how the page is shown. Display options (rotation, cropped margins) only change [toView], the
 * matrix from "page pixels" to view pixels, and touches are mapped back through its inverse.
 */
class PdfPageView(context: Context) : View(context) {

    interface Host {
        val tool: DrawTool
        val color: Int
        val strokeWidth: Float
        /** false = no tool selected: the S Pen scrolls like a finger (side button still erases). */
        val toolActive: Boolean get() = true
        /** A tap on an existing text note with the text tool. */
        fun onTextTapped(view: PdfPageView, page: Int, note: TextNote) {}
        /** Image tool: a tap on an existing picture, or on an empty spot (add a picture there). */
        fun onImageTapped(view: PdfPageView, page: Int, image: ImageNote) {}
        fun requestImage(page: Int, x: Float, y: Float) {}
        /** The selected text note is being dragged (hide popups), and was moved / resized. */
        fun onNoteDragStart() {}
        /** A tap away from the selected note: unselect it (instead of adding a new note). */
        fun onNoteDeselect() {}
        fun onNoteChanged(view: PdfPageView, page: Int, before: Any, after: Any) {}
        /** Settings: smooth pen strokes; straighten free-highlighter strokes. */
        val inkSmoothing: Boolean get() = false
        val highlighterStraighten: Boolean get() = false
        /** Setting: one finger draws like the pen (two fingers scroll / zoom). */
        val fingerDraw: Boolean get() = false
        /** true = erase only the touched part of strokes; false = erase whole strokes. */
        val eraserPixelMode: Boolean get() = false
        /** Eraser size (its own setting; also used for the S Pen side button). */
        val eraserWidth: Float get() = strokeWidth
        fun annotationsFor(page: Int): PageAnnotations
        fun onItemAdded(page: Int, item: Any)
        fun onItemsErased(page: Int)
        fun requestText(page: Int, x: Float, y: Float)
        fun isBookmarked(page: Int): Boolean
        /** Search matches on this page (normalized rects), and those of the current match. */
        fun searchHits(page: Int): List<RectF>? = null
        fun activeSearchHit(page: Int): List<RectF>? = null

        /** View mode: the pen / long-press selects text instead of drawing. */
        val viewMode: Boolean get() = false
        /** Starts a text selection at a normalized point; false if there's no text there. */
        fun selectBegin(view: PdfPageView, page: Int, nx: Float, ny: Float, byWord: Boolean): Boolean = false
        fun selectMove(view: PdfPageView, page: Int, nx: Float, ny: Float) {}
        fun selectEnd(view: PdfPageView, page: Int) {}
        /** A tap in view mode (e.g. on an existing highlight). */
        fun viewTap(view: PdfPageView, page: Int, nx: Float, ny: Float) {}
    }

    /** How the page looks: paper color, and filters for the page image and the ink. */
    class Look(val paper: Int, val pageFilter: ColorFilter?, val inkFilter: ColorFilter?) {
        companion object {
            val DEFAULT = Look(Color.WHITE, null, null)
        }
    }

    private enum class Mode { NONE, INK, ERASE, TEXT }

    var host: Host? = null
    var pageIndex = -1

    /** height / width of the page as the PDF shows it (before view rotation / crop). */
    var pageAspect = 1.4142f
        set(value) {
            if (field != value) {
                field = value
                geometryChanged()
            }
        }

    /** Extra view rotation, clockwise: 0, 90, 180, 270. Display only. */
    var viewRotation = 0
        set(value) {
            val v = ((value % 360) + 360) % 360
            if (field != v) {
                field = v
                geometryChanged()
            }
        }

    /** Visible part of the page in normalized coordinates (null = whole page). Display only. */
    var crop: RectF? = null
        set(value) {
            if (field != value) {
                field = value
                geometryChanged()
            }
        }

    var look: Look = Look.DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Called (at most every 1.5 s) when this page is drawn without its image, so it can be re-rendered. */
    var onNeedBitmap: (() -> Unit)? = null
    private var lastNeedRequest = 0L

    var annotations: PageAnnotations? = null
        set(value) {
            field = value
            invalidateInk()
        }

    var hits: List<RectF>? = null
        set(value) {
            field = value
            invalidate()
        }
    var activeHits: List<RectF>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The text note shown with a dotted frame and corner handles (drag to move / resize). */
    /** A TextNote or ImageNote. */
    var editingNote: Any? = null
        set(value) {
            field = value
            invalidate()
        }
    private val frameDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(37, 99, 235)
        strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(5f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f
        )
    }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val imageDst = RectF()
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(37, 99, 235)
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    /** Current text selection (view mode), normalized line boxes. */
    var selectionRects: List<RectF>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Draws a bookmark ribbon at the top-right corner. */
    var bookmarked = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minEraserPx = 10f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cachePaint = Paint(Paint.FILTER_BITMAP_FLAG)
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
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF4444") }
    private val ribbonPath = Path()
    private val highlightPaint = Paint()
    private val selectionPaint = Paint().apply { color = Color.argb(80, 37, 99, 235) }
    private val hitPaint = Paint().apply { color = Color.argb(100, 250, 204, 21) }
    private val activeHitPaint = Paint().apply { color = Color.argb(140, 249, 115, 22) }

    // Page pixels -> view pixels, and back.
    private val toView = Matrix()
    private val toPage = Matrix()
    private var matrixDirty = true
    private val pt = FloatArray(2)

    /** View pixels per normalized unit (= width of the whole page in view pixels). */
    var pageScale = 0f
        private set

    private var inkCache: Bitmap? = null
    private var inkDirty = true

    private var mode = Mode.NONE
    private var active: InkStroke? = null
    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserRadius = 0f
    private var fingerDownX = 0f
    private var fingerDownY = 0f

    private val cropLeft get() = crop?.left ?: 0f
    private val cropTop get() = crop?.top ?: 0f
    private val cropWidth get() = crop?.width()?.takeIf { it > 0f } ?: 1f
    private val cropHeight get() = crop?.height()?.takeIf { it > 0f } ?: pageAspect

    /** height / width of what this view shows (after crop and rotation). */
    val displayAspect: Float
        get() = if (viewRotation % 180 == 0) cropHeight / cropWidth else cropWidth / cropHeight

    private fun geometryChanged() {
        matrixDirty = true
        inkDirty = true
        requestLayout()
        invalidate()
    }

    fun invalidateInk() {
        inkDirty = true
        invalidate()
    }

    fun release() {
        editingNote = null
        inkCache?.recycle()
        inkCache = null
        inkDirty = true
        bitmap = null
        active = null
        mode = Mode.NONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
        } else {
            setMeasuredDimension(w, (w * displayAspect).toInt().coerceAtLeast(1))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        matrixDirty = true
        inkDirty = true
    }

    private fun ensureMatrix() {
        if (!matrixDirty) return
        val w = width.toFloat()
        val h = height.toFloat()
        val along = if (viewRotation % 180 == 0) w else h
        pageScale = if (along > 0f) along / cropWidth else 0f
        toView.reset()
        toView.setTranslate(-cropLeft * pageScale, -cropTop * pageScale)
        when (viewRotation) {
            90 -> { toView.postRotate(90f); toView.postTranslate(w, 0f) }
            180 -> { toView.postRotate(180f); toView.postTranslate(w, h) }
            270 -> { toView.postRotate(270f); toView.postTranslate(0f, h) }
        }
        toView.invert(toPage)
        matrixDirty = false
    }

    /** Where a normalized page point ends up in this view. */
    fun viewPointOf(nx: Float, ny: Float): android.graphics.PointF {
        ensureMatrix()
        pt[0] = nx * pageScale
        pt[1] = ny * pageScale
        toView.mapPoints(pt)
        return android.graphics.PointF(pt[0], pt[1])
    }

    /** Text notes as normalized boxes (for placing a popup over one). */
    fun textBox(note: TextNote): RectF {
        val s = pageScale.coerceAtLeast(1f)
        textPaint.textSize = note.size * s
        val w = textPaint.measureText(note.text) / s
        return RectF(note.x, note.y - note.size, note.x + w, note.y + note.size * 0.3f)
    }

    /** Where a normalized page point ends up in this view (y), e.g. to scroll a search match into view. */
    fun viewYOf(nx: Float, ny: Float): Float {
        ensureMatrix()
        pt[0] = nx * pageScale
        pt[1] = ny * pageScale
        toView.mapPoints(pt)
        return pt[1]
    }

    /** Full page width in view pixels: the size the page bitmap should be rendered at. */
    fun renderWidth(): Int {
        ensureMatrix()
        return pageScale.toInt()
    }

    private fun zoomScale(): Float {
        var p = parent
        repeat(3) {
            if (p is ZoomRecyclerView) return (p as ZoomRecyclerView).scale
            p = p?.parent
        }
        return 1f
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        ensureMatrix()
        val s = pageScale
        val lk = look
        canvas.drawColor(lk.paper)
        canvas.save()
        canvas.concat(toView)
        val bmp = bitmap
        // Self-healing: drawn without its image, or only with a small blurry preview.
        val needsSharp = bmp == null || bmp.isRecycled || bmp.width < minOf(s, 2800f) * 0.85f
        if (needsSharp && pageIndex >= 0 && onNeedBitmap != null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastNeedRequest > 700) {
                lastNeedRequest = now
                post { onNeedBitmap?.invoke() }
                // Look again later in case this request also fails.
                postInvalidateDelayed(1000)
            }
        }
        if (bmp != null && !bmp.isRecycled) {
            dst.set(0f, 0f, s, pageAspect * s)
            bitmapPaint.colorFilter = lk.pageFilter
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }
        annotations?.highlights?.forEach { hl ->
            highlightPaint.color = hl.color
            highlightPaint.colorFilter = lk.inkFilter
            if (hl.underline) {
                for (r in hl.rects) {
                    val thick = max(r.height() * com.titipong.pdfwriter.pdf.PdfAnnotationIO.UNDERLINE_RATIO * s, 1.5f)
                    canvas.drawRect(r.left * s, r.bottom * s - thick, r.right * s, r.bottom * s, highlightPaint)
                }
            } else {
                highlightPaint.alpha = 115
                for (r in hl.rects) canvas.drawRect(r.left * s, r.top * s, r.right * s, r.bottom * s, highlightPaint)
            }
        }
        selectionRects?.forEach { r -> canvas.drawRect(r.left * s, r.top * s, r.right * s, r.bottom * s, selectionPaint) }
        editingNote?.let { n ->
            val f = noteFrame(n) ?: return@let
            canvas.drawRect(f.left * s, f.top * s, f.right * s, f.bottom * s, frameDash)
            val hs = 5f * density
            for ((cx, cy) in corners(f)) {
                canvas.drawRect(cx * s - hs, cy * s - hs, cx * s + hs, cy * s + hs, handleFill)
                canvas.drawRect(cx * s - hs, cy * s - hs, cx * s + hs, cy * s + hs, handleStroke)
            }
        }
        hits?.forEach { r -> canvas.drawRect(r.left * s, r.top * s, r.right * s, r.bottom * s, hitPaint) }
        activeHits?.forEach { r ->
            val pad = 2f * density
            canvas.drawRect(r.left * s - pad, r.top * s - pad, r.right * s + pad, r.bottom * s + pad, activeHitPaint)
        }
        canvas.restore()

        val a = annotations
        if (a != null && !a.isEmpty()) {
            // Zoomed in: draw ink as vectors so it stays sharp. At 1x a bitmap cache keeps it fast.
            val cache = if (zoomScale() > 1.01f) null else ensureInkCache(a)
            if (cache != null) {
                cachePaint.colorFilter = lk.inkFilter
                canvas.drawBitmap(cache, 0f, 0f, cachePaint)
            } else {
                canvas.save()
                canvas.concat(toView)
                renderAnnotations(canvas, a, s, lk.inkFilter)
                canvas.restore()
            }
        }
        active?.let {
            canvas.save()
            canvas.concat(toView)
            drawStroke(canvas, it, s, lk.inkFilter)
            canvas.restore()
        }
        if (bookmarked) drawRibbon(canvas, width.toFloat())
        if (mode == Mode.ERASE) canvas.drawCircle(eraserX, eraserY, eraserRadius, eraserPaint)
    }

    private fun drawRibbon(canvas: Canvas, w: Float) {
        val rw = (w * 0.045f).coerceIn(10f * density, 28f * density)
        val rh = rw * 1.5f
        val right = w - rw * 0.8f
        val left = right - rw
        ribbonPath.reset()
        ribbonPath.moveTo(left, 0f)
        ribbonPath.lineTo(right, 0f)
        ribbonPath.lineTo(right, rh)
        ribbonPath.lineTo((left + right) / 2f, rh - rw * 0.4f)
        ribbonPath.lineTo(left, rh)
        ribbonPath.close()
        canvas.drawPath(ribbonPath, ribbonPaint)
    }

    /** Ink pre-rendered in view pixels (true colors; the ink filter is applied when it's drawn). */
    private fun ensureInkCache(a: PageAnnotations): Bitmap? {
        if (width <= 0 || height <= 0) return null
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
        val target = cache ?: return null
        if (inkDirty) {
            target.eraseColor(Color.TRANSPARENT)
            val c = Canvas(target)
            c.concat(toView)
            renderAnnotations(c, a, pageScale, null)
            inkDirty = false
        }
        return target
    }

    private fun renderAnnotations(canvas: Canvas, a: PageAnnotations, s: Float, filter: ColorFilter?) {
        // Pictures first, so ink and notes go on top of them.
        for (im in a.images) {
            val bmp = ImageCache.get(im.file) ?: continue
            imageDst.set(im.x * s, im.y * s, (im.x + im.w) * s, (im.y + im.h) * s)
            imagePaint.colorFilter = filter
            canvas.drawBitmap(bmp, null, imageDst, imagePaint)
        }
        for (st in a.strokes) drawStroke(canvas, st, s, filter)
        textPaint.colorFilter = filter
        for (t in a.texts) {
            textPaint.color = t.color
            textPaint.textSize = t.size * s
            canvas.drawText(t.text, t.x * s, t.y * s, textPaint)
        }
    }

    private fun drawStroke(canvas: Canvas, st: InkStroke, s: Float, filter: ColorFilter?) {
        val pts = st.points
        if (pts.isEmpty()) return
        inkPaint.colorFilter = filter
        inkPaint.color = st.color
        if (st.highlighter) {
            inkPaint.alpha = 90
            inkPaint.strokeCap = Paint.Cap.ROUND // rounded ends
            inkPaint.strokeWidth = st.width * s
            path.reset()
            path.moveTo(pts[0].x * s, pts[0].y * s)
            for (i in 1 until pts.size) path.lineTo(pts[i].x * s, pts[i].y * s)
            if (pts.size == 1) path.lineTo(pts[0].x * s + 0.1f, pts[0].y * s)
            canvas.drawPath(path, inkPaint)
        } else {
            inkPaint.strokeCap = Paint.Cap.ROUND
            if (pts.size == 1) {
                inkPaint.strokeWidth = st.width * s * pressureScale(pts[0].pressure)
                canvas.drawPoint(pts[0].x * s, pts[0].y * s, inkPaint)
                return
            }
            // Per-segment width follows pen pressure.
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                inkPaint.strokeWidth = st.width * s * pressureScale((a.pressure + b.pressure) / 2f)
                canvas.drawLine(a.x * s, a.y * s, b.x * s, b.y * s, inkPaint)
            }
        }
    }

    // ---------------------------------------------------------------- input

    /** View pixel -> normalized page coordinate (written into [pt]). */
    private fun toNormalized(x: Float, y: Float): Boolean {
        ensureMatrix()
        if (pageScale <= 0f) return false
        pt[0] = x
        pt[1] = y
        toPage.mapPoints(pt)
        pt[0] /= pageScale
        pt[1] /= pageScale
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = host ?: return false
        if (h.viewMode) return onViewTouch(e, h)
        val toolType = e.getToolType(0)
        val isPen = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        // "Draw with Finger": a finger writes like the pen while a tool is selected.
        val fingerPen = !isPen && h.fingerDraw && h.toolActive && toolType == MotionEvent.TOOL_TYPE_FINGER
        if (!isPen && !fingerPen) return onFinger(e, h)

        val eraserButton = !fingerPen && penButtonOverride ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0

        // No tool selected: the pen scrolls the page like a finger (unless the side button is held).
        if (!h.toolActive && !eraserButton && mode == Mode.NONE) return false
        if (!eraserButton && (h.tool == DrawTool.TEXT || h.tool == DrawTool.IMAGE) && noteDrag(e, h)) return true

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
                    Mode.TEXT -> if (toNormalized(e.x, e.y)) {
                        if (h.tool == DrawTool.IMAGE) imageTap(h, pt[0], pt[1]) else textTap(h, pt[0], pt[1])
                    }
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
        val placing = h.tool == DrawTool.TEXT || h.tool == DrawTool.IMAGE
        if (!placing || !h.toolActive) return false // let the list scroll
        if (noteDrag(e, h)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDownX = e.x
                fingerDownY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (hypot(e.x - fingerDownX, e.y - fingerDownY) < touchSlop && toNormalized(e.x, e.y)) {
                    performClick()
                    if (h.tool == DrawTool.IMAGE) imageTap(h, pt[0], pt[1]) else textTap(h, pt[0], pt[1])
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ---------------------------------------------------------------- moving / resizing a text note

    /** 0 = none, 1 = move, 2..5 = resize from corner (top-left, top-right, bottom-right, bottom-left). */
    private var dragKind = 0
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginal: Any? = null
    private var dragMoved = false

    private fun noteFrame(item: Any): RectF? {
        if (item is ImageNote) return RectF(item.x, item.y, item.x + item.w, item.y + item.h)
        val n = item as? TextNote ?: return null
        val b = textBox(n)
        val pad = 4f * density / pageScale.coerceAtLeast(1f)
        return RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
    }

    private fun corners(f: RectF) = listOf(f.left to f.top, f.right to f.top, f.right to f.bottom, f.left to f.bottom)

    /**
     * Drag on the selected note's frame: inside moves it, a corner handle resizes it (the opposite
     * corner stays put). Returns false when the touch isn't on the frame (normal text-tool handling).
     */
    private fun noteDrag(e: MotionEvent, h: Host): Boolean {
        val note = editingNote
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragKind = 0
                if (note == null || !toNormalized(e.x, e.y)) return false
                val x = pt[0]
                val y = pt[1]
                val f = noteFrame(note) ?: return false
                val grab = 18f * density / pageScale.coerceAtLeast(1f)
                val corner = corners(f).indexOfFirst { (cx, cy) -> hypot(cx - x, cy - y) < grab }
                dragKind = when {
                    corner >= 0 -> 2 + corner
                    RectF(f).apply { inset(-grab / 3, -grab / 3) }.contains(x, y) -> 1
                    else -> 0
                }
                if (dragKind == 0) return false
                dragStartX = x
                dragStartY = y
                dragOriginal = note
                dragMoved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKind == 0) return false
                val orig = dragOriginal ?: return true
                if (!toNormalized(e.x, e.y)) return true
                val dx = pt[0] - dragStartX
                val dy = pt[1] - dragStartY
                if (!dragMoved && hypot(dx, dy) * pageScale < touchSlop) return true
                if (!dragMoved) {
                    dragMoved = true
                    h.onNoteDragStart()
                }
                val updated: Any = when {
                    dragKind == 1 && orig is TextNote -> TextNote(orig.text, orig.x + dx, orig.y + dy, orig.color, orig.size)
                    dragKind == 1 && orig is ImageNote -> ImageNote(orig.file, orig.x + dx, orig.y + dy, orig.w, orig.h)
                    else -> resized(orig, dragKind - 2, pt[0], pt[1]) ?: return true
                }
                replaceNote(editingNote ?: return true, updated)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragKind == 0) return false
                val orig = dragOriginal
                val now = editingNote
                dragKind = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragMoved && orig != null && now != null && now !== orig) {
                    h.onNoteChanged(this, pageIndex, orig, now)
                } else if (!dragMoved && now != null) {
                    performClick()
                    // A tap: show its menu again.
                    if (now is ImageNote) h.onImageTapped(this, pageIndex, now)
                    else if (now is TextNote) h.onTextTapped(this, pageIndex, now)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragKind == 0) return false
                dragKind = 0
                return true
            }
        }
        return dragKind != 0
    }

    /** [orig] scaled so its frame's corner [corner] follows (x, y) while the opposite corner stays. */
    private fun resized(orig: Any, corner: Int, x: Float, y: Float): Any? {
        val f = noteFrame(orig) ?: return null
        val c = corners(f)
        val (ax, ay) = c[(corner + 2) % 4] // the fixed, opposite corner
        val scale = maxOf(
            kotlin.math.abs(x - ax) / f.width().coerceAtLeast(1e-4f),
            kotlin.math.abs(y - ay) / f.height().coerceAtLeast(1e-4f)
        ).coerceIn(0.2f, 8f)
        if (orig is ImageNote) {
            // Pictures keep their shape; the opposite corner stays put.
            val w = (orig.w * scale).coerceIn(0.02f, 3f)
            val h = orig.h * (w / orig.w)
            val left = if (corner == 0 || corner == 3) ax - w else ax
            val top = if (corner == 0 || corner == 1) ay - h else ay
            return ImageNote(orig.file, left, top, w, h)
        }
        if (orig !is TextNote) return null
        val size = (orig.size * scale).coerceIn(0.006f, 0.4f)
        val trial = TextNote(orig.text, orig.x, orig.y, orig.color, size)
        val nf = noteFrame(trial) ?: return null
        val (tx, ty) = corners(nf)[(corner + 2) % 4]
        return TextNote(orig.text, orig.x + (ax - tx), orig.y + (ay - ty), orig.color, size)
    }

    private fun replaceNote(old: Any, new: Any) {
        val a = annotations ?: return
        if (old is TextNote && new is TextNote) {
            val i = a.texts.indexOf(old)
            if (i < 0) return
            a.texts[i] = new
        } else if (old is ImageNote && new is ImageNote) {
            val i = a.images.indexOf(old)
            if (i < 0) return
            a.images[i] = new
        } else {
            return
        }
        editingNote = new
        inkDirty = true
        invalidate()
    }

    /** Text tool tap: on an existing note -> offer to delete it; elsewhere -> add a note. */
    private fun textTap(h: Host, nx: Float, ny: Float) {
        val hit = textAt(nx, ny)
        if (hit == null && editingNote != null) {
            h.onNoteDeselect()
            return
        }
        if (hit != null) h.onTextTapped(this, pageIndex, hit) else h.requestText(pageIndex, nx, ny)
    }

    /** Image tool tap: on a picture -> select it; elsewhere -> pick a picture to place there. */
    private fun imageTap(h: Host, nx: Float, ny: Float) {
        val hit = annotations?.images?.lastOrNull { nx >= it.x && nx <= it.x + it.w && ny >= it.y && ny <= it.y + it.h }
        if (hit == null && editingNote != null) {
            h.onNoteDeselect()
            return
        }
        if (hit != null) h.onImageTapped(this, pageIndex, hit) else h.requestImage(pageIndex, nx, ny)
    }

    private fun textAt(nx: Float, ny: Float): TextNote? {
        val s = pageScale
        if (s <= 0f) return null
        val pad = 6f * density / s
        return annotations?.texts?.lastOrNull { t ->
            textPaint.textSize = t.size * s
            val tw = textPaint.measureText(t.text) / s
            nx >= t.x - pad && nx <= t.x + tw + pad && ny >= t.y - t.size - pad && ny <= t.y + t.size * 0.3f + pad
        }
    }

    // ---------------------------------------------------------------- view mode: select text

    private var vmDownX = 0f
    private var vmDownY = 0f
    private var vmSelecting = false
    private var vmPen = false

    private val longPress = Runnable {
        val h = host ?: return@Runnable
        if (!vmSelecting && toNormalized(vmDownX, vmDownY) &&
            h.selectBegin(this, pageIndex, pt[0], pt[1], byWord = true)
        ) {
            vmSelecting = true
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * S Pen: drag to select (never scrolls). Finger: scrolls as usual; long-press selects the
     * word under it and dragging extends the selection. A tap goes to [Host.viewTap].
     */
    private fun onViewTouch(e: MotionEvent, h: Host): Boolean {
        val toolType = e.getToolType(0)
        val isPen = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vmDownX = e.x
                vmDownY = e.y
                vmSelecting = false
                vmPen = isPen
                if (isPen) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (vmSelecting) {
                    if (toNormalized(e.x, e.y)) h.selectMove(this, pageIndex, pt[0], pt[1])
                } else if (hypot(e.x - vmDownX, e.y - vmDownY) > touchSlop) {
                    if (vmPen) {
                        if (toNormalized(vmDownX, vmDownY) && h.selectBegin(this, pageIndex, pt[0], pt[1], byWord = false)) {
                            vmSelecting = true
                            if (toNormalized(e.x, e.y)) h.selectMove(this, pageIndex, pt[0], pt[1])
                        }
                    } else {
                        removeCallbacks(longPress) // the finger is scrolling
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (vmSelecting) {
                    h.selectEnd(this, pageIndex)
                } else if (hypot(e.x - vmDownX, e.y - vmDownY) < touchSlop && toNormalized(e.x, e.y)) {
                    performClick()
                    h.viewTap(this, pageIndex, pt[0], pt[1])
                }
                vmSelecting = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (vmSelecting) h.selectEnd(this, pageIndex)
                vmSelecting = false
            }
        }
        return true
    }

    private fun begin(e: MotionEvent, h: Host, eraserButton: Boolean) {
        val tool = if (eraserButton) DrawTool.ERASER else h.tool
        when (tool) {
            DrawTool.ERASER -> {
                mode = Mode.ERASE
                eraseAt(e.x, e.y, h)
            }
            DrawTool.TEXT, DrawTool.IMAGE -> mode = Mode.TEXT // a tap (handled on lift)
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
        if (!toNormalized(x, y)) return
        active?.points?.add(StrokePoint(pt[0], pt[1], pressure))
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
        refineStroke(s, h)
        val a = annotations ?: h.annotationsFor(pageIndex).also { annotations = it }
        a.strokes.add(s)
        // Draw just the new stroke onto the cache instead of rebuilding everything.
        val cache = inkCache
        if (!inkDirty && cache != null && cache.width == width && cache.height == height) {
            val c = Canvas(cache)
            c.concat(toView)
            drawStroke(c, s, pageScale, null)
        } else {
            inkDirty = true
        }
        invalidate()
        h.onItemAdded(pageIndex, s)
    }

    private fun eraseAt(px: Float, py: Float, h: Host) {
        if (!toNormalized(px, py)) return
        val s = pageScale
        eraserRadius = max(h.eraserWidth * s * 3f, minEraserPx)
        eraserX = px
        eraserY = py
        val a = annotations ?: return
        val x = pt[0]
        val y = pt[1]
        val rn = eraserRadius / s
        // Stroke mode: a stroke is hit anywhere along its line, not only at its points (a straightened
        // highlighter line has just its two ends, so touching its middle used to miss).
        val strokesRemoved = if (h.eraserPixelMode) erasePixels(a, x, y, rn) else a.strokes.removeAll { st ->
            val reach = rn + st.width / 2f
            val pts = st.points
            pts.any { p -> hypot(p.x - x, p.y - y) < reach } ||
                (1 until pts.size).any { segDist(pts[it - 1], pts[it], x, y) < reach }
        }
        // The eraser only removes pen / highlighter ink. Text notes and text highlights / underlines
        // are deleted by tapping them (text tool / view mode).
        if (strokesRemoved) {
            inkDirty = true
            h.onItemsErased(pageIndex)
        }
    }

    /**
     * Pixel eraser: cuts the part of each stroke under the eraser, keeping the rest as
     * separate strokes (so it stays vector ink, editable in other PDF apps).
     */
    private fun erasePixels(a: PageAnnotations, x: Float, y: Float, rn: Float): Boolean {
        var changed = false
        val out = ArrayList<InkStroke>(a.strokes.size)
        for (st in a.strokes) {
            val reach = rn + st.width / 2f
            val pts = st.points
            if (pts.none { hypot(it.x - x, it.y - y) < reach } &&
                (1 until pts.size).none { segDist(pts[it - 1], pts[it], x, y) < reach }
            ) {
                out.add(st)
                continue
            }
            changed = true
            var run = ArrayList<StrokePoint>()
            for (i in pts.indices) {
                val p = pts[i]
                val inside = hypot(p.x - x, p.y - y) < reach
                val cut = i > 0 && segDist(pts[i - 1], p, x, y) < reach
                if (inside || cut) {
                    if (run.size >= 2) out.add(InkStroke(run, st.color, st.width, st.highlighter))
                    run = ArrayList()
                }
                if (!inside) run.add(p)
            }
            if (run.size >= 2) out.add(InkStroke(run, st.color, st.width, st.highlighter))
        }
        if (changed) {
            a.strokes.clear()
            a.strokes.addAll(out)
        }
        return changed
    }

    /** Applies the smoothing settings to a finished stroke (before it's stored / saved to the PDF). */
    private fun refineStroke(s: InkStroke, h: Host) {
        val pts = s.points
        if (s.highlighter) {
            if (!h.highlighterStraighten || pts.size < 2) return
            val a = pts.first()
            var b = pts.last()
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 0.02f) return // a dab, not a line
            val dev = pts.maxOf { segDist(a, b, it.x, it.y) }
            if (dev > max(0.006f, len * 0.06f)) return // clearly not meant to be straight
            // Nearly horizontal (within ~6 degrees): make it exactly horizontal, like a text line.
            if (kotlin.math.abs(b.y - a.y) < kotlin.math.abs(b.x - a.x) * 0.1f) {
                b = StrokePoint(b.x, a.y, b.pressure)
            }
            pts.clear()
            pts.add(a)
            pts.add(b)
        } else {
            if (!h.inkSmoothing || pts.size < 3) return
            var cur: List<StrokePoint> = ArrayList(pts)
            repeat(2) {
                val out = ArrayList<StrokePoint>(cur.size)
                out.add(cur.first())
                for (i in 1 until cur.size - 1) {
                    val p0 = cur[i - 1]
                    val p1 = cur[i]
                    val p2 = cur[i + 1]
                    out.add(
                        StrokePoint(
                            (p0.x + 2 * p1.x + p2.x) / 4f,
                            (p0.y + 2 * p1.y + p2.y) / 4f,
                            (p0.pressure + 2 * p1.pressure + p2.pressure) / 4f
                        )
                    )
                }
                out.add(cur.last())
                cur = out
            }
            pts.clear()
            pts.addAll(cur)
        }
    }

    /** Distance from (x, y) to the segment a-b. */
    private fun segDist(a: StrokePoint, b: StrokePoint, x: Float, y: Float): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0f) (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f) else 0f
        return hypot(a.x + t * dx - x, a.y + t * dy - y)
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
