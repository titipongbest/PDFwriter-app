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
import android.graphics.PointF
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.model.ImageNote
import com.titipong.pdfwriter.model.ItemGroup
import com.titipong.pdfwriter.model.TextLayout
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
        /** Highlighter tool: a tap on an existing highlighter stroke. */
        fun onStrokeTapped(view: PdfPageView, page: Int, stroke: InkStroke) {}
        /** Select tool: items circled with the lasso (or tapped again). */
        fun onGroupSelected(view: PdfPageView, page: Int, group: ItemGroup) {}
        /** Handwriting-to-text tool: a stroke was written (kept aside until it is converted). */
        fun onHandwritingStroke(view: PdfPageView, page: Int, stroke: InkStroke) {}
        /** Lines of the document's own text on [page] (normalized boxes), to align notes / pictures with. */
        fun guideBoxes(page: Int): List<RectF> = emptyList()
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
        /** Pen: line width follows pen pressure (false: even width). */
        val penPressure: Boolean get() = true
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

    private enum class Mode { NONE, INK, ERASE, TEXT, LASSO }

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
    private val sideRect = RectF()
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(236, 72, 153)
        strokeWidth = 1.2f * resources.displayMetrics.density
    }
    private val guidesX = ArrayList<Float>()
    private val guidesY = ArrayList<Float>()
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
    /** Part of the page whose ink changed (normalized), redrawn alone instead of the whole page. */
    private var inkDirtyRect: RectF? = null
    /** Ink was erased in the gesture going on (saved once, when it ends). */
    private var erasedInGesture = false
    /** Bounds of each stroke (cached), to skip strokes far from the eraser quickly. */
    private val strokeBoxes = java.util.IdentityHashMap<InkStroke, RectF>()

    private fun boxOf(st: InkStroke): RectF = strokeBoxes.getOrPut(st) {
        val pts = st.points
        if (pts.isEmpty()) RectF() else {
            val r = RectF(pts[0].x, pts[0].y, pts[0].x, pts[0].y)
            for (p in pts) r.union(p.x, p.y)
            val w = st.width
            r.inset(-w, -w)
            r
        }
    }

    private fun near(st: InkStroke, x: Float, y: Float, reach: Float): Boolean {
        val b = boxOf(st)
        return x >= b.left - reach && x <= b.right + reach && y >= b.top - reach && y <= b.bottom + reach
    }

    private fun markInkChanged(r: RectF) {
        if (inkDirty) return
        inkDirtyRect = inkDirtyRect?.apply { union(r) } ?: RectF(r)
    }

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
        pendingInk.clear()
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
        val lines = layoutText(note)
        val w = boxWidth(note, lines)
        val bottom = note.y + note.size * TextLayout.LINE_HEIGHT * (lines.size - 1) + note.size * 0.3f
        return RectF(note.x, note.y - note.size, note.x + w, bottom)
    }

    /** The note's lines, widths normalized to the page width (same breaking as the PDF writer). */
    private fun layoutText(t: TextNote): List<TextLayout.Line> {
        val s = pageScale.coerceAtLeast(1f)
        textPaint.textSize = t.size * s
        return TextLayout.lines(t.text, t.width) { textPaint.measureText(it) / s }
    }

    private fun boxWidth(t: TextNote, lines: List<TextLayout.Line>): Float =
        if (t.width > 0f) t.width else lines.maxOfOrNull { it.width } ?: 0f

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
                highlightPaint.alpha = Math.round(com.titipong.pdfwriter.model.Opacity.of(hl.color, com.titipong.pdfwriter.model.Opacity.TEXT_DEFAULT) * 255)
                for (r in hl.rects) canvas.drawRect(r.left * s, r.top * s, r.right * s, r.bottom * s, highlightPaint)
            }
        }
        selectionRects?.forEach { r -> canvas.drawRect(r.left * s, r.top * s, r.right * s, r.bottom * s, selectionPaint) }
        editingNote?.let { n ->
            val f = noteFrame(n) ?: return@let
            canvas.drawRect(f.left * s, f.top * s, f.right * s, f.bottom * s, frameDash)
            val hs = 5f * density
            if (n is InkStroke) {
                // Round handles at the ends of a straight line: drag to make it longer / shorter.
                if (isLine(n)) for (p in n.points) {
                    canvas.drawCircle(p.x * s, p.y * s, hs * 1.4f, handleFill)
                    canvas.drawCircle(p.x * s, p.y * s, hs * 1.4f, handleStroke)
                }
                return@let
            }
            for ((cx, cy) in corners(f)) {
                canvas.drawRect(cx * s - hs, cy * s - hs, cx * s + hs, cy * s + hs, handleFill)
                canvas.drawRect(cx * s - hs, cy * s - hs, cx * s + hs, cy * s + hs, handleStroke)
            }
            if (n is TextNote) {
                // Side handles: drag to set the box width (text wraps).
                val cy = f.centerY() * s
                for (cx in floatArrayOf(f.left * s, f.right * s)) {
                    sideRect.set(cx - hs * 0.7f, cy - hs * 1.8f, cx + hs * 0.7f, cy + hs * 1.8f)
                    canvas.drawRoundRect(sideRect, hs, hs, handleFill)
                    canvas.drawRoundRect(sideRect, hs, hs, handleStroke)
                }
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
        if (pendingInk.isNotEmpty()) {
            canvas.save()
            canvas.concat(toView)
            for (st in pendingInk) drawStroke(canvas, st, s, lk.inkFilter)
            canvas.restore()
        }
        active?.let {
            canvas.save()
            canvas.concat(toView)
            drawStroke(canvas, it, s, lk.inkFilter)
            canvas.restore()
        }
        // On top of everything (never baked into the ink cache): the lasso and alignment guides.
        if (lasso.size > 1 || guidesX.isNotEmpty() || guidesY.isNotEmpty()) {
            canvas.save()
            canvas.concat(toView)
            // Lasso being drawn.
            if (lasso.size > 1) {
                lassoPath.reset()
                lassoPath.moveTo(lasso[0].x * s, lasso[0].y * s)
                for (i in 1 until lasso.size) lassoPath.lineTo(lasso[i].x * s, lasso[i].y * s)
                lassoPath.close()
                canvas.drawPath(lassoPath, lassoFill)
                canvas.drawPath(lassoPath, lassoPaint)
            }
            // While dragging: alignment guides.
            if (guidesX.isNotEmpty() || guidesY.isNotEmpty()) {
                val ph = pageAspect * s
                for (gx in guidesX) canvas.drawLine(gx * s, 0f, gx * s, ph, guidePaint)
                for (gy in guidesY) canvas.drawLine(0f, gy * s, s, gy * s, guidePaint)
            }
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
            inkDirtyRect = null
        } else {
            val r = inkDirtyRect
            if (r != null) {
                // Only where ink was erased: clear that area and draw what's left there.
                inkDirtyRect = null
                val s = pageScale
                val c = Canvas(target)
                c.concat(toView)
                c.clipRect(r.left * s - 2f, r.top * s - 2f, r.right * s + 2f, r.bottom * s + 2f)
                c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                renderAnnotations(c, a, s, null, r)
            }
        }
        return target
    }

    private fun renderAnnotations(canvas: Canvas, a: PageAnnotations, s: Float, filter: ColorFilter?, region: RectF? = null) {
        // Pictures first, so ink and notes go on top of them.
        for (im in a.images) {
            val bmp = ImageCache.get(im.file) ?: continue
            imageDst.set(im.x * s, im.y * s, (im.x + im.w) * s, (im.y + im.h) * s)
            imagePaint.colorFilter = filter
            canvas.drawBitmap(bmp, null, imageDst, imagePaint)
        }
        for (st in a.strokes) {
            if (region != null && !RectF.intersects(region, boxOf(st))) continue
            drawStroke(canvas, st, s, filter)
        }
        textPaint.colorFilter = filter
        for (t in a.texts) {
            val lines = layoutText(t)
            val bw = boxWidth(t, lines)
            textPaint.color = t.color
            textPaint.textSize = t.size * s
            lines.forEachIndexed { i, line ->
                val lx = t.x + TextLayout.offset(t.align, bw, line.width)
                val ly = t.y + t.size * TextLayout.LINE_HEIGHT * i
                canvas.drawText(line.text, lx * s, ly * s, textPaint)
            }
        }
    }

    private fun drawStroke(canvas: Canvas, st: InkStroke, s: Float, filter: ColorFilter?) {
        val pts = st.points
        if (pts.isEmpty()) return
        inkPaint.colorFilter = filter
        inkPaint.color = st.color
        if (st.highlighter) {
            inkPaint.alpha = Math.round(com.titipong.pdfwriter.model.Opacity.of(st.color, com.titipong.pdfwriter.model.Opacity.INK_DEFAULT) * 255)
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
            val p0 = pts[0].pressure
            if (pts.all { it.pressure == p0 }) {
                // Even line: one path (much faster than a line per segment).
                inkPaint.strokeWidth = st.width * s * pressureScale(p0)
                inkPaint.strokeJoin = Paint.Join.ROUND
                path.reset()
                path.moveTo(pts[0].x * s, pts[0].y * s)
                for (i in 1 until pts.size) path.lineTo(pts[i].x * s, pts[i].y * s)
                canvas.drawPath(path, inkPaint)
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
        if (!eraserButton && h.tool in DRAGGABLE_TOOLS && noteDrag(e, h)) return true

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
                    Mode.LASSO -> {
                        for (i in 0 until e.historySize) {
                            if (toNormalized(e.getHistoricalX(i), e.getHistoricalY(i))) lasso.add(PointF(pt[0], pt[1]))
                        }
                        if (toNormalized(e.x, e.y)) lasso.add(PointF(pt[0], pt[1]))
                    }
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
                        if (!highlighterTap(h) && !writeTap(h)) {
                            if (editingNote != null) h.onNoteDeselect()
                            commitStroke(h)
                        }
                    }
                    Mode.LASSO -> finishLasso(h)
                    Mode.TEXT -> if (toNormalized(e.x, e.y)) {
                        if (h.tool == DrawTool.IMAGE) imageTap(h, pt[0], pt[1]) else textTap(h, pt[0], pt[1])
                    }
                    else -> {}
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                active = null
                lasso.clear()
                endGesture()
            }
        }
        return true
    }

    private fun onFinger(e: MotionEvent, h: Host): Boolean {
        if (h.tool == DrawTool.HIGHLIGHTER && h.toolActive) return onFingerHighlighter(e, h)
        if (h.tool == DrawTool.SELECT && h.toolActive) return onFingerSelect(e, h)
        if (h.tool == DrawTool.WRITE_TEXT && h.toolActive) return onFingerWrite(e, h)
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

    // ---------------------------------------------------------------- selecting highlighter strokes

    /** Highlighter stroke under a normalized point (the topmost). */
    private fun highlighterAt(x: Float, y: Float): InkStroke? = annotations?.strokes?.lastOrNull { st ->
        st.highlighter && run {
            val reach = st.width / 2f + 4f * density / pageScale.coerceAtLeast(1f)
            val pts = st.points
            pts.any { hypot(it.x - x, it.y - y) < reach } ||
                (1 until pts.size).any { segDist(pts[it - 1], pts[it], x, y) < reach }
        }
    }

    /**
     * The highlighter "stroke" just drawn was only a tap: on an existing highlighter stroke it
     * selects that stroke (no dot is added); with something selected it unselects it.
     */
    private fun highlighterTap(h: Host): Boolean {
        val st = active ?: return false
        if (!st.highlighter || st.points.isEmpty()) return false
        val xs = st.points.map { it.x }
        val ys = st.points.map { it.y }
        if ((xs.max() - xs.min()) * pageScale > touchSlop || (ys.max() - ys.min()) * pageScale > touchSlop) return false
        val p = st.points.first()
        val hit = highlighterAt(p.x, p.y)
        if (hit == null && editingNote == null) return false
        active = null
        if (hit != null) h.onStrokeTapped(this, pageIndex, hit) else h.onNoteDeselect()
        return true
    }

    /**
     * Write to text: a tap (not writing) on a text box selects it (frame: move, resize, edit, color);
     * a tap elsewhere with something selected unselects it. The tap leaves no ink.
     */
    private fun writeTap(h: Host): Boolean {
        if (!handwriting) return false
        val st = active ?: return false
        if (st.points.isEmpty()) return false
        val xs = st.points.map { it.x }
        val ys = st.points.map { it.y }
        if ((xs.max() - xs.min()) * pageScale > touchSlop || (ys.max() - ys.min()) * pageScale > touchSlop) return false
        val p = st.points.first()
        val hit = textAt(p.x, p.y)
        if (hit == null && editingNote == null) return false
        active = null
        if (hit != null) h.onTextTapped(this, pageIndex, hit) else h.onNoteDeselect()
        return true
    }

    private var hlDownX = 0f
    private var hlDownY = 0f

    /** Finger with the highlighter tool (not drawing with fingers): taps select strokes, drags scroll. */
    private fun onFingerHighlighter(e: MotionEvent, h: Host): Boolean {
        if (noteDrag(e, h)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hlDownX = e.x
                hlDownY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (hypot(e.x - hlDownX, e.y - hlDownY) < touchSlop && toNormalized(e.x, e.y)) {
                    val hit = highlighterAt(pt[0], pt[1])
                    if (hit != null) {
                        performClick()
                        h.onStrokeTapped(this, pageIndex, hit)
                    } else if (editingNote != null) {
                        h.onNoteDeselect()
                    }
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- lasso selection

    private val lasso = ArrayList<PointF>()
    private val lassoPath = android.graphics.Path()
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(37, 99, 235)
        strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f
        )
    }
    private val lassoFill = Paint().apply { color = Color.argb(28, 37, 99, 235) }

    private fun inLasso(x: Float, y: Float): Boolean {
        var inside = false
        var j = lasso.size - 1
        for (i in lasso.indices) {
            val a = lasso[i]
            val b = lasso[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Lasso done: pick ink with most of its points inside, and boxes / pictures whose center is inside. */
    private fun finishLasso(h: Host) {
        val pts = ArrayList(lasso)
        lasso.clear()
        val small = pts.size < 3 || run {
            val w = pts.maxOf { it.x } - pts.minOf { it.x }
            val hh = pts.maxOf { it.y } - pts.minOf { it.y }
            maxOf(w, hh) * pageScale < touchSlop * 2
        }
        if (small) {
            // A tap, not a lasso: select the item under it on its own.
            val p = pts.firstOrNull()
            if (p == null || !selectItemAt(h, p.x, p.y)) {
                if (editingNote != null) h.onNoteDeselect()
            }
            return
        }
        lasso.addAll(pts)
        val a = annotations
        val picked = ArrayList<Any>()
        if (a != null) {
            a.strokes.filterTo(picked) { st -> st.points.isNotEmpty() && st.points.count { inLasso(it.x, it.y) } * 2 >= st.points.size }
            a.texts.filterTo(picked) { t -> textBox(t).let { inLasso(it.centerX(), it.centerY()) } }
            a.images.filterTo(picked) { im -> inLasso(im.x + im.w / 2f, im.y + im.h / 2f) }
        }
        lasso.clear()
        if (picked.isEmpty()) {
            if (editingNote != null) h.onNoteDeselect()
        } else {
            h.onGroupSelected(this, pageIndex, ItemGroup(picked))
        }
    }

    private var wrDownX = 0f
    private var wrDownY = 0f

    /** Finger with Write to text (not drawing with fingers): tap a text box to select it, drag it, else scroll. */
    private fun onFingerWrite(e: MotionEvent, h: Host): Boolean {
        if (noteDrag(e, h)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wrDownX = e.x
                wrDownY = e.y
            }
            MotionEvent.ACTION_UP -> if (hypot(e.x - wrDownX, e.y - wrDownY) < touchSlop && toNormalized(e.x, e.y)) {
                val hit = textAt(pt[0], pt[1])
                if (hit != null) {
                    performClick()
                    h.onTextTapped(this, pageIndex, hit)
                } else if (editingNote != null) {
                    h.onNoteDeselect()
                }
            }
        }
        return true
    }

    /**
     * Select tool tap: the topmost item under (x, y) gets its own frame — a text box (move, resize,
     * color, edit), a picture, a highlighter line, or pen ink (move, color, To text, delete).
     */
    private fun selectItemAt(h: Host, x: Float, y: Float): Boolean {
        val a = annotations ?: return false
        textAt(x, y)?.let { h.onTextTapped(this, pageIndex, it); return true }
        a.images.lastOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }?.let {
            h.onImageTapped(this, pageIndex, it)
            return true
        }
        val reachPad = 6f * density / pageScale.coerceAtLeast(1f)
        val stroke = a.strokes.lastOrNull { st ->
            val reach = st.width / 2f + reachPad
            val pts = st.points
            pts.any { hypot(it.x - x, it.y - y) < reach } ||
                (1 until pts.size).any { segDist(pts[it - 1], pts[it], x, y) < reach }
        } ?: return false
        if (stroke.highlighter) h.onStrokeTapped(this, pageIndex, stroke)
        else h.onGroupSelected(this, pageIndex, ItemGroup(listOf(stroke)))
        return true
    }

    private var selDownX = 0f
    private var selDownY = 0f

    /** Finger with the select tool (not drawing with fingers): drag the selection, tap away to unselect, else scroll. */
    private fun onFingerSelect(e: MotionEvent, h: Host): Boolean {
        if (noteDrag(e, h)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selDownX = e.x
                selDownY = e.y
            }
            MotionEvent.ACTION_UP -> if (hypot(e.x - selDownX, e.y - selDownY) < touchSlop) {
                val hit = toNormalized(e.x, e.y) && selectItemAt(h, pt[0], pt[1])
                if (hit) performClick() else if (editingNote != null) h.onNoteDeselect()
            }
        }
        return true
    }

    private fun shifted(item: Any, dx: Float, dy: Float): Any = when (item) {
        is TextNote -> item.copy(x = item.x + dx, y = item.y + dy)
        is ImageNote -> ImageNote(item.file, item.x + dx, item.y + dy, item.w, item.h)
        is InkStroke -> InkStroke(item.points.map { StrokePoint(it.x + dx, it.y + dy, it.pressure) }.toMutableList(), item.color, item.width, item.highlighter)
        is ItemGroup -> ItemGroup(item.items.map { shifted(it, dx, dy) })
        else -> item
    }

    /** A straight highlighter line (two points) can be lengthened / shortened from its ends. */
    private fun isLine(st: InkStroke) = st.points.size == 2

    // ---------------------------------------------------------------- moving / resizing a text note

    /** 0 = none, 1 = move, 2..5 = resize from corner (top-left, top-right, bottom-right, bottom-left). */
    private var dragKind = 0
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginal: Any? = null
    private var dragMoved = false

    private fun noteFrame(item: Any): RectF? {
        if (item is ItemGroup) {
            var r: RectF? = null
            for (it in item.items) {
                val f = (it as? TextNote)?.let { t -> textBox(t) } ?: noteFrame(it) ?: continue
                r = r?.apply { union(f) } ?: RectF(f)
            }
            return r?.apply { inset(-4f * density / pageScale.coerceAtLeast(1f), -4f * density / pageScale.coerceAtLeast(1f)) }
        }
        if (item is ImageNote) return RectF(item.x, item.y, item.x + item.w, item.y + item.h)
        if (item is InkStroke) {
            if (item.points.isEmpty()) return null
            val r = item.width / 2f
            return RectF(
                item.points.minOf { it.x } - r, item.points.minOf { it.y } - r,
                item.points.maxOf { it.x } + r, item.points.maxOf { it.y } + r
            )
        }
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
                dragKind = if (note is ItemGroup) {
                    when {
                        corner >= 0 -> 2 + corner // resize everything together
                        RectF(f).apply { inset(-grab / 3, -grab / 3) }.contains(x, y) -> 1
                        else -> 0
                    }
                } else if (note is InkStroke) {
                    val end = if (isLine(note)) note.points.indexOfFirst { hypot(it.x - x, it.y - y) < grab } else -1
                    when {
                        end >= 0 -> 6 + end // 6 = first end, 7 = last end
                        highlighterAt(x, y) === note -> 1
                        else -> 0
                    }
                } else when {
                    note is TextNote && hypot(f.left - x, f.centerY() - y) < grab -> 8
                    note is TextNote && hypot(f.right - x, f.centerY() - y) < grab -> 9
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
                // Snap the finger / pen to alignment lines (resizing); moving snaps the whole frame below.
                guidesX.clear()
                guidesY.clear()
                if (dragKind != 1) {
                    val (sx, sy) = snapPoint(pt[0], pt[1], orig)
                    pt[0] = sx
                    pt[1] = sy
                }
                var updated: Any = when {
                    orig is ItemGroup && dragKind == 1 -> shifted(orig, dx, dy)
                    orig is ItemGroup -> scaledGroup(orig, dragKind - 2, pt[0], pt[1]) ?: return true
                    orig is TextNote && (dragKind == 8 || dragKind == 9) -> widened(orig, dragKind == 8, pt[0])
                    orig is InkStroke && dragKind == 1 ->
                        InkStroke(orig.points.map { StrokePoint(it.x + dx, it.y + dy, it.pressure) }.toMutableList(), orig.color, orig.width, orig.highlighter)
                    orig is InkStroke && dragKind >= 6 -> stretched(orig, dragKind - 6, pt[0], pt[1])
                    dragKind == 1 && orig is TextNote -> orig.copy(x = orig.x + dx, y = orig.y + dy)
                    dragKind == 1 && orig is ImageNote -> ImageNote(orig.file, orig.x + dx, orig.y + dy, orig.w, orig.h)
                    else -> resized(orig, dragKind - 2, pt[0], pt[1]) ?: return true
                }
                if (dragKind == 1) updated = snapMove(updated, orig)
                val before = guidesX.size + guidesY.size
                replaceNote(editingNote ?: return true, updated)
                if (before > 0 && lastGuideCount == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastGuideCount = before
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragKind == 0) return false
                val orig = dragOriginal
                val now = editingNote
                dragKind = 0
                guidesX.clear()
                guidesY.clear()
                lastGuideCount = 0
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragMoved && orig != null && now != null && now !== orig) {
                    h.onNoteChanged(this, pageIndex, orig, now)
                } else if (!dragMoved && now != null) {
                    performClick()
                    // A tap: show its menu again.
                    if (now is ItemGroup) h.onGroupSelected(this, pageIndex, now)
                    else if (now is ImageNote) h.onImageTapped(this, pageIndex, now)
                    else if (now is InkStroke) h.onStrokeTapped(this, pageIndex, now)
                    else if (now is TextNote) h.onTextTapped(this, pageIndex, now)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragKind == 0) return false
                dragKind = 0
                guidesX.clear()
                guidesY.clear()
                lastGuideCount = 0
                invalidate()
                return true
            }
        }
        return dragKind != 0
    }

    private var lastGuideCount = 0

    /** Text box with its left or right side moved to [x] (sets an explicit width; text wraps). */
    private fun widened(orig: TextNote, leftSide: Boolean, x: Float): TextNote {
        val b = textBox(orig)
        val minW = orig.size * 2f
        return if (leftSide) {
            val newLeft = x.coerceAtMost(b.right - minW)
            orig.copy(x = newLeft, width = b.right - newLeft)
        } else {
            orig.copy(width = (x - b.left).coerceAtLeast(minW))
        }
    }

    // ---------------------------------------------------------------- alignment guides (snapping)

    /** Lines to align with: page edges and center, other notes / pictures, the document's text lines. */
    private fun guideTargets(exclude: Any): Pair<List<Float>, List<Float>> {
        val xs = arrayListOf(0f, 0.5f, 1f)
        val ys = arrayListOf(0f, pageAspect / 2f, pageAspect)
        val a = annotations
        if (a != null) {
            val skip = HashSet<Any>()
            for (g in listOf(exclude, editingNote)) {
                if (g is ItemGroup) skip.addAll(g.items) else if (g != null) skip.add(g)
            }
            val frames = a.texts.filter { it !in skip }.map { textBox(it) } +
                a.images.filter { it !in skip }.map { RectF(it.x, it.y, it.x + it.w, it.y + it.h) }
            for (f in frames) {
                xs += f.left; xs += f.centerX(); xs += f.right
                ys += f.top; ys += f.centerY(); ys += f.bottom
            }
        }
        host?.guideBoxes(pageIndex)?.forEach { r ->
            xs += r.left; xs += r.right
            ys += r.bottom
        }
        return xs to ys
    }

    private fun snapThreshold() = 8f * density / pageScale.coerceAtLeast(1f)

    /** Nearest target within the threshold to any of [values]: (shift, target) or null. */
    private fun nearest(values: FloatArray, targets: List<Float>): Pair<Float, Float>? {
        val thr = snapThreshold()
        var best: Pair<Float, Float>? = null
        var bestD = thr
        for (v in values) for (t in targets) {
            val d = kotlin.math.abs(t - v)
            if (d < bestD) {
                bestD = d
                best = (t - v) to t
            }
        }
        return best
    }

    /** A moved item shifted so one of its edges / center lines up with a guide; records the guides. */
    private fun snapMove(item: Any, orig: Any): Any {
        val f = (item as? TextNote)?.let { textBox(it) } ?: noteFrame(item) ?: return item
        val (xs, ys) = guideTargets(orig)
        val sx = nearest(floatArrayOf(f.left, f.centerX(), f.right), xs)
        val sy = nearest(floatArrayOf(f.top, f.centerY(), f.bottom), ys)
        val dx = sx?.first ?: 0f
        val dy = sy?.first ?: 0f
        sx?.let { guidesX += it.second }
        sy?.let { guidesY += it.second }
        if (dx == 0f && dy == 0f) return item
        if (item is ItemGroup) return shifted(item, dx, dy)
        return when (item) {
            is TextNote -> item.copy(x = item.x + dx, y = item.y + dy)
            is ImageNote -> ImageNote(item.file, item.x + dx, item.y + dy, item.w, item.h)
            is InkStroke -> InkStroke(item.points.map { StrokePoint(it.x + dx, it.y + dy, it.pressure) }.toMutableList(), item.color, item.width, item.highlighter)
            else -> item
        }
    }

    /** A dragged handle position snapped to guides (resizing / stretching); records the guides. */
    private fun snapPoint(x: Float, y: Float, orig: Any): Pair<Float, Float> {
        val (xs, ys) = guideTargets(orig)
        val sx = nearest(floatArrayOf(x), xs)
        val sy = nearest(floatArrayOf(y), ys)
        sx?.let { guidesX += it.second }
        sy?.let { guidesY += it.second }
        return (sx?.second ?: x) to (sy?.second ?: y)
    }

    /**
     * A lasso selection scaled from corner [corner] of its frame to (x, y), the opposite corner
     * staying put: ink (and its thickness), text boxes (text size, width) and pictures all scale.
     */
    /** Lasso selection scaled from a corner: width and height follow the finger freely. */
    private fun scaledGroup(g: ItemGroup, corner: Int, x: Float, y: Float): ItemGroup? {
        val f = noteFrame(g) ?: return null
        val (ax, ay) = corners(f)[(corner + 2) % 4]
        val kx = (kotlin.math.abs(x - ax) / f.width().coerceAtLeast(1e-4f)).coerceIn(0.1f, 10f)
        val ky = (kotlin.math.abs(y - ay) / f.height().coerceAtLeast(1e-4f)).coerceIn(0.1f, 10f)
        val k = kotlin.math.sqrt(kx * ky)
        fun sx(v: Float) = ax + (v - ax) * kx
        fun sy(v: Float) = ay + (v - ay) * ky
        return ItemGroup(g.items.map {
            when (it) {
                is InkStroke -> InkStroke(
                    it.points.map { p -> StrokePoint(sx(p.x), sy(p.y), p.pressure) }.toMutableList(),
                    it.color, (it.width * k).coerceIn(0.0005f, 0.2f), it.highlighter
                )
                is TextNote -> it.copy(
                    x = sx(it.x), y = sy(it.y), size = (it.size * ky).coerceIn(0.004f, 0.4f),
                    width = if (it.width > 0f) it.width * kx else 0f
                )
                is ImageNote -> ImageNote(it.file, sx(it.x), sy(it.y), it.w * kx, it.h * ky)
                else -> it
            }
        })
    }

    /** A straight line with end [end] moved along the line's direction to (x, y)'s position on it. */
    private fun stretched(orig: InkStroke, end: Int, x: Float, y: Float): InkStroke {
        val fixed = orig.points[1 - end]
        val moving = orig.points[end]
        val len = hypot(moving.x - fixed.x, moving.y - fixed.y).coerceAtLeast(1e-5f)
        val ux = (moving.x - fixed.x) / len
        val uy = (moving.y - fixed.y) / len
        val newLen = ((x - fixed.x) * ux + (y - fixed.y) * uy).coerceAtLeast(orig.width) // never flips
        val p = StrokePoint(fixed.x + ux * newLen, fixed.y + uy * newLen, moving.pressure)
        val pts = if (end == 0) mutableListOf(p, fixed) else mutableListOf(fixed, p)
        return InkStroke(pts, orig.color, orig.width, orig.highlighter)
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
        // Text boxes resize freely: across = the box width (the text wraps), up/down = the text size.
        val kx = (kotlin.math.abs(x - ax) / f.width().coerceAtLeast(1e-4f)).coerceIn(0.1f, 10f)
        val ky = (kotlin.math.abs(y - ay) / f.height().coerceAtLeast(1e-4f)).coerceIn(0.2f, 8f)
        val size = (orig.size * ky).coerceIn(0.006f, 0.4f)
        val width = (boxWidth(orig, layoutText(orig)) * kx).coerceAtLeast(size * 1.5f)
        val trial = orig.copy(size = size, width = width)
        val nf = noteFrame(trial) ?: return null
        val (tx, ty) = corners(nf)[(corner + 2) % 4]
        return trial.copy(x = orig.x + (ax - tx), y = orig.y + (ay - ty))
    }

    private fun replaceNote(old: Any, new: Any) {
        val a = annotations ?: return
        if (old is ItemGroup && new is ItemGroup) {
            old.items.zip(new.items).forEach { (o, n) -> replaceIn(a, o, n) }
            editingNote = new
            inkDirty = true
            invalidate()
            return
        }
        if (old is TextNote && new is TextNote) {
            val i = a.texts.indexOf(old)
            if (i < 0) return
            a.texts[i] = new
        } else if (old is ImageNote && new is ImageNote) {
            val i = a.images.indexOf(old)
            if (i < 0) return
            a.images[i] = new
        } else if (old is InkStroke && new is InkStroke) {
            val i = a.strokes.indexOf(old)
            if (i < 0) return
            a.strokes[i] = new
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

    private fun replaceIn(a: PageAnnotations, old: Any, new: Any) {
        when {
            old is TextNote && new is TextNote -> a.texts.indexOf(old).takeIf { it >= 0 }?.let { a.texts[it] = new }
            old is ImageNote && new is ImageNote -> a.images.indexOf(old).takeIf { it >= 0 }?.let { a.images[it] = new }
            old is InkStroke && new is InkStroke -> a.strokes.indexOf(old).takeIf { it >= 0 }?.let { a.strokes[it] = new }
        }
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
            val b = textBox(t)
            nx >= b.left - pad && nx <= b.right + pad && ny >= b.top - pad && ny <= b.bottom + pad
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
            DrawTool.SELECT -> {
                mode = Mode.LASSO
                lasso.clear()
                if (toNormalized(e.x, e.y)) lasso.add(PointF(pt[0], pt[1]))
            }
            DrawTool.PEN, DrawTool.HIGHLIGHTER, DrawTool.WRITE_TEXT -> {
                mode = Mode.INK
                handwriting = tool == DrawTool.WRITE_TEXT
                usePressure = tool != DrawTool.PEN || h.penPressure
                val hl = tool == DrawTool.HIGHLIGHTER
                active = InkStroke(mutableListOf(), h.color, if (hl) h.strokeWidth * 5f else h.strokeWidth, hl)
                addPoint(e.x, e.y, e.pressure)
            }
        }
        invalidate()
    }

    /** Pressure on for this stroke (the pen's "even width" option stores a neutral pressure). */
    private var usePressure = true

    private fun addPoint(x: Float, y: Float, pressure: Float) {
        if (!toNormalized(x, y)) return
        active?.points?.add(StrokePoint(pt[0], pt[1], if (usePressure) pressure else NEUTRAL_PRESSURE))
    }

    private fun appendPoints(e: MotionEvent) {
        for (i in 0 until e.historySize) {
            addPoint(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i))
        }
        addPoint(e.x, e.y, e.pressure)
    }

    /** The stroke being drawn is handwriting to convert to text (not saved as ink). */
    private var handwriting = false

    /** Handwriting waiting to be converted to text (drawn on top, not part of the page's ink). */
    val pendingInk = ArrayList<InkStroke>()

    private fun commitStroke(h: Host) {
        val s = active ?: return
        active = null
        if (s.points.isEmpty()) return
        if (handwriting) {
            pendingInk.add(s)
            invalidate()
            h.onHandwritingStroke(this, pageIndex, s)
            return
        }
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
            if (!near(st, x, y, reach)) return@removeAll false
            val pts = st.points
            val hit = pts.any { p -> hypot(p.x - x, p.y - y) < reach } ||
                (1 until pts.size).any { segDist(pts[it - 1], pts[it], x, y) < reach }
            if (hit) markInkChanged(boxOf(st))
            hit
        }
        // The eraser only removes pen / highlighter ink. Text notes and text highlights / underlines
        // are deleted by tapping them (text tool / view mode).
        if (strokesRemoved) {
            (editingNote as? InkStroke)?.let { if (it !in a.strokes) h.onNoteDeselect() }
            (editingNote as? ItemGroup)?.let { g -> if (g.items.any { it is InkStroke && it !in a.strokes }) h.onNoteDeselect() }
            erasedInGesture = true // saved once, when the eraser lifts
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
            if (!near(st, x, y, reach) || pts.none { hypot(it.x - x, it.y - y) < reach } &&
                (1 until pts.size).none { segDist(pts[it - 1], pts[it], x, y) < reach }
            ) {
                out.add(st)
                continue
            }
            changed = true
            markInkChanged(boxOf(st))
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
        if (erasedInGesture) {
            erasedInGesture = false
            host?.onItemsErased(pageIndex)
        }
        if (strokeBoxes.size > 4000) strokeBoxes.clear()
        mode = Mode.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    companion object {
        /** Set by MainActivity while it re-dispatches Samsung's legacy "side button held" events. */
        @JvmStatic
        var penButtonOverride = false

        fun pressureScale(p: Float): Float = 0.4f + p.coerceIn(0f, 1f) * 1.2f

        /** pressureScale(0.5) = 1: the stroke has exactly the chosen width. */
        const val NEUTRAL_PRESSURE = 0.5f

        /** Tools whose items can be selected and dragged with a frame. */
        private val DRAGGABLE_TOOLS = setOf(
            DrawTool.TEXT, DrawTool.IMAGE, DrawTool.HIGHLIGHTER, DrawTool.SELECT, DrawTool.WRITE_TEXT
        )
    }
}
