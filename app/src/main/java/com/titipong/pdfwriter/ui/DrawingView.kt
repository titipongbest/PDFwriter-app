package com.titipong.pdfwriter.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.titipong.pdfwriter.model.*

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var currentTool: DrawTool = DrawTool.PEN
    var strokeColor: Int = Color.BLUE
    var strokeWidth: Float = 8f
    var pageIndex: Int = 0

    // callbacks
    var onStrokeFinished: ((SerializableStroke) -> Unit)? = null
    var onAnnotationsChanged: (() -> Unit)? = null

    private val committedStrokes = mutableListOf<Stroke>()
    private var currentPath: Path? = null
    private var currentPoints = mutableListOf<StrokePoint>()
    private var lastX = 0f
    private var lastY = 0f

    private val penPaint get() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = this@DrawingView.strokeWidth
    }

    private val highlightPaint get() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        alpha = 90
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = this@DrawingView.strokeWidth * 3f
    }

    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // off-screen bitmap for eraser support
    private var offBitmap: Bitmap? = null
    private var offCanvas: Canvas? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        offBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        offCanvas = Canvas(offBitmap!!)
        redrawAll()
    }

    private fun redrawAll() {
        offCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        committedStrokes.forEach { stroke ->
            if (stroke.pageIndex == pageIndex) {
                offCanvas?.drawPath(stroke.path, stroke.paint)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        offBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        currentPath?.let { path ->
            val paint = when (currentTool) {
                DrawTool.HIGHLIGHTER -> highlightPaint
                DrawTool.ERASER -> eraserPaint.also { it.strokeWidth = strokeWidth * 4f }
                else -> penPaint
            }
            canvas.drawPath(path, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // S Pen: pointerType stylus only draws; touch is ignored (parent scrolls)
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                       event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        val isMouse = event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if (!isStylus && !isMouse) return false // let touch scroll

        // S Pen barrel button (button_secondary) → eraser
        val effectiveTool = if (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) {
            DrawTool.ERASER
        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
            DrawTool.ERASER
        } else {
            currentTool
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().also { it.moveTo(event.x, event.y) }
                currentPoints.clear()
                currentPoints.add(StrokePoint(event.x, event.y, event.pressure))
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val path = currentPath ?: return true
                // use historical points for smoother input
                val histCount = event.historySize
                for (h in 0 until histCount) {
                    val hx = event.getHistoricalX(h)
                    val hy = event.getHistoricalY(h)
                    val pr = event.getHistoricalPressure(h)
                    path.quadTo(lastX, lastY, (lastX + hx) / 2, (lastY + hy) / 2)
                    currentPoints.add(StrokePoint(hx, hy, pr))
                    lastX = hx; lastY = hy
                }
                path.quadTo(lastX, lastY, (lastX + event.x) / 2, (lastY + event.y) / 2)
                currentPoints.add(StrokePoint(event.x, event.y, event.pressure))
                lastX = event.x; lastY = event.y

                if (effectiveTool == DrawTool.ERASER) {
                    // apply eraser live to off-canvas
                    offCanvas?.drawCircle(event.x, event.y, strokeWidth * 4f,
                        eraserPaint.also { it.strokeWidth = strokeWidth * 4f })
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val path = currentPath ?: return true
                val paint = when (effectiveTool) {
                    DrawTool.HIGHLIGHTER -> highlightPaint
                    DrawTool.ERASER -> eraserPaint.also { it.strokeWidth = strokeWidth * 4f }
                    else -> penPaint
                }
                if (effectiveTool != DrawTool.ERASER) {
                    offCanvas?.drawPath(path, paint)
                    committedStrokes.add(Stroke(path, paint, effectiveTool, pageIndex))
                    // notify with serializable form
                    val ss = SerializableStroke(
                        points = currentPoints.toList(),
                        color = strokeColor,
                        strokeWidth = strokeWidth,
                        tool = effectiveTool.name,
                        pageIndex = pageIndex
                    )
                    onStrokeFinished?.invoke(ss)
                } else {
                    // remove strokes that overlap eraser area
                    val eraserRgn = Region()
                    val eraserBounds = RectF(event.x - strokeWidth*4, event.y - strokeWidth*4,
                        event.x + strokeWidth*4, event.y + strokeWidth*4)
                    eraserRgn.set(eraserBounds.left.toInt(), eraserBounds.top.toInt(),
                        eraserBounds.right.toInt(), eraserBounds.bottom.toInt())
                    committedStrokes.removeAll { stroke ->
                        val strokeBounds = RectF()
                        stroke.path.computeBounds(strokeBounds, true)
                        RectF.intersects(strokeBounds, eraserBounds)
                    }
                    redrawAll()
                }
                onAnnotationsChanged?.invoke()
                currentPath = null
                currentPoints.clear()
                invalidate()
            }
        }
        return true
    }

    fun undo() {
        if (committedStrokes.isNotEmpty()) {
            committedStrokes.removeLast()
            redrawAll()
            onAnnotationsChanged?.invoke()
        }
    }

    fun clearPage() {
        committedStrokes.removeAll { it.pageIndex == pageIndex }
        redrawAll()
        onAnnotationsChanged?.invoke()
    }

    fun loadStrokes(strokes: List<SerializableStroke>) {
        committedStrokes.clear()
        strokes.filter { it.pageIndex == pageIndex }.forEach { ss ->
            val path = Path()
            ss.points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x, p.y)
                else path.lineTo(p.x, p.y)
            }
            val tool = DrawTool.valueOf(ss.tool)
            val paint = when (tool) {
                DrawTool.HIGHLIGHTER -> Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ss.color; alpha = 90
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.ROUND
                    strokeWidth = ss.strokeWidth * 3f
                }
                DrawTool.ERASER -> eraserPaint.also { it.strokeWidth = ss.strokeWidth * 4f }
                else -> Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ss.color
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                    strokeWidth = ss.strokeWidth
                }
            }
            committedStrokes.add(Stroke(path, paint, tool, pageIndex))
        }
        redrawAll()
    }

    fun getStrokes(): List<SerializableStroke> = committedStrokes.map { stroke ->
        val path = stroke.path
        val bounds = RectF()
        path.computeBounds(bounds, true)
        // We store the original SerializableStroke via onStrokeFinished already
        // For reload, build from path approximation
        SerializableStroke(
            points = listOf(StrokePoint(bounds.left, bounds.top)),
            color = stroke.paint.color,
            strokeWidth = stroke.paint.strokeWidth,
            tool = stroke.tool.name,
            pageIndex = stroke.pageIndex
        )
    }
}
