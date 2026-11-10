@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.titipong.pdfwriter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titipong.pdfwriter.AppState
import com.titipong.pdfwriter.ColorMode
import com.titipong.pdfwriter.DocState
import com.titipong.pdfwriter.EraseEdit
import com.titipong.pdfwriter.NoteEdit
import com.titipong.pdfwriter.TextSelection
import com.titipong.pdfwriter.compat.RectF
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.model.ImageNote
import com.titipong.pdfwriter.model.Ink
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.ItemGroup
import com.titipong.pdfwriter.model.Opacity
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.StrokePoint
import com.titipong.pdfwriter.model.TextHighlight
import com.titipong.pdfwriter.model.TextLayout
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.render.PageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Text measuring shared by every page (text boxes). */
class NoteText(val measurer: TextMeasurer, val density: androidx.compose.ui.unit.Density) {
    private val widths = HashMap<String, Float>()
    private val ref = 200f

    fun style(px: Float, color: Color) = TextStyle(
        fontFamily = Ui.noteFont, color = color,
        fontSize = (px / density.density / density.fontScale).sp
    )

    /** Width of [s] at text size 1. */
    fun width(s: String): Float {
        if (s.isEmpty()) return 0f
        widths[s]?.let { return it }
        val r = measurer.measure(s, style(ref, Color.Black), softWrap = false, maxLines = 1)
        val w = r.multiParagraph.getLineRight(0) / ref
        if (widths.size > 4000) widths.clear()
        widths[s] = w
        return w
    }

    val kit = PageGeometryKit { width(it) }
}

val LocalNoteText = staticCompositionLocalOf<NoteText> { error("no NoteText") }

/** How close (page widths) the mouse must be to a letter to count as on the text. */
private const val TEXT_REACH = 0.008f

private val SEL_BLUE = Color(0xFF2563EB)
private val GUIDE = Color(0xFFEC4899)

/** Colour filter for the page picture in a reading color mode (annotations keep their colours). */
fun pageFilter(app: AppState): ColorFilter? {
    val v = app.view
    return when (v.colorMode) {
        ColorMode.DAY -> null
        ColorMode.NIGHT -> ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(nightMatrix()))
        ColorMode.SEPIA -> ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
            0.96f, 0f, 0f, 0f, 0f,
            0f, 0.91f, 0f, 0f, 0f,
            0f, 0f, 0.79f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        ColorMode.CUSTOM -> {
            val bg = v.customBg; val fg = v.customFg
            fun ch(c: Int, s: Int) = (c shr s) and 255
            fun k(s: Int) = (ch(bg, s) - ch(fg, s)) / 255f
            ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                k(16), 0f, 0f, 0f, ch(fg, 16).toFloat(),
                0f, k(8), 0f, 0f, ch(fg, 8).toFloat(),
                0f, 0f, k(0), 0f, ch(fg, 0).toFloat(),
                0f, 0f, 0f, 1f, 0f
            )))
        }
    }
}

/** Invert, then turn the hues back (colours keep their hue on a dark page). */
private fun nightMatrix(): FloatArray {
    val invert = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
    val hue = floatArrayOf(
        -0.574f, 1.430f, 0.144f, 0f, 0f,
        0.426f, 0.430f, 0.144f, 0f, 0f,
        0.426f, 1.430f, -0.856f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
    // hue x invert
    val out = FloatArray(20)
    for (r in 0 until 4) {
        for (c in 0 until 5) {
            var s = 0f
            for (k in 0 until 4) s += hue[r * 5 + k] * invert[k * 5 + c]
            if (c == 4) s += hue[r * 5 + 4]
            out[r * 5 + c] = s
        }
    }
    return out
}

fun paperColor(app: AppState): Color = when (app.view.colorMode) {
    ColorMode.DAY -> Color.White
    ColorMode.NIGHT -> Color(0xFF1F1F1F)
    ColorMode.SEPIA -> Color(245, 232, 201)
    ColorMode.CUSTOM -> app.view.customBg.toColor()
}

/** Width in pixels to render a page shown [shownPx] wide (rounded up so small zoom steps reuse it). */
fun renderWidthFor(shownPx: Float): Int {
    val w = (ceil(shownPx / 256f) * 256f).toInt()
    return w.coerceIn(400, PageRenderer.MAX_WIDTH)
}

/**
 * One page at [widthPx] wide (height = width x aspect): the rendered page, the annotations, and
 * everything you do on it with the mouse / pen. Coordinates are normalized to the page width.
 */
@Composable
fun PageView(app: AppState, doc: DocState, page: Int, widthPx: Float, onPan: (Offset) -> Unit) {
    val renderer = doc.renderer ?: return
    val aspect = renderer.aspects.getOrElse(page) { 1.414f }
    val density = LocalDensity.current
    val nt = LocalNoteText.current
    val kit = nt.kit
    val heightPx = widthPx * aspect
    val width by rememberUpdatedState(widthPx)
    val bitmap = rememberPageBitmap(app, doc, renderer, page, widthPx)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val clicks = remember { ClickTracker() }
    // View mode: the mouse is over text (shows the text cursor; dragging there selects).
    var overText by remember { mutableStateOf(false) }

    // View mode: read this page's text (also after the file is reloaded or OCR'd).
    LaunchedEffect(page, app.annotate) {
        if (app.annotate) return@LaunchedEffect
        while (true) {
            val idx = doc.searchIndex
            if (idx != null && !idx.isReady(page)) idx.ensurePage(page)
            delay(1000)
        }
    }

    val drawingTool = app.annotate && app.toolActive && app.tool in setOf(DrawTool.PEN, DrawTool.HIGHLIGHTER, DrawTool.ERASER)
    val toolIcon = toolPointer(app.tool)
    Box(
        Modifier.size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
            .background(paperColor(app))
            .onGloballyPositioned { coords = it }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { ev ->
                if (app.annotate) { if (overText) overText = false; return@onPointerEvent }
                val p = ev.changes.firstOrNull()?.position ?: return@onPointerEvent
                val idx = doc.searchIndex
                val on = idx != null && idx.isReady(page) && idx.charAt(page, p.x / width, p.y / width, TEXT_REACH) >= 0
                if (on != overText) overText = on
            }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { if (overText) overText = false }
            .pointerHoverIcon(
                if (app.annotate && app.toolActive && app.tool == DrawTool.TEXT) PointerIcon.Text
                else if (app.annotate && app.toolActive && toolIcon != null) toolIcon
                else if (drawingTool) PointerIcon.Crosshair
                else if (!app.annotate && overText) PointerIcon.Text
                else PointerIcon.Default
            )
            .pointerInput(doc, page) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val W = width
                    if (currentEvent.buttons.isTertiaryPressed) return@awaitEachGesture // the viewer pans
                    val secondary = currentEvent.buttons.isSecondaryPressed
                    val eraserPen = down.type == PointerType.Eraser
                    fun nx(o: Offset) = o.x / W
                    fun ny(o: Offset) = o.y / W
                    val x0 = nx(down.position)
                    val y0 = ny(down.position)
                    val pa = doc.pages[page]
                    val slop = 4f
                    val isDouble = clicks.isDouble(page, down.position, down.uptimeMillis)

                    // ---- something selected on this page: its frame and handles first
                    val sel = doc.editing
                    if (sel != null && doc.editingPage == page && !secondary && !eraserPen) {
                        val handle = handleAt(kit, sel, down.position, W)
                        if (handle != null && pa != null) {
                            dragItem(app, doc, page, pa, sel, handle, W, aspect, kit, down, isDouble)
                            return@awaitEachGesture
                        }
                    }
                    if (doc.editing != null || doc.textSel != null || doc.selectedHighlight != null) app.clearSelection(doc)

                    val annotateTool = app.annotate && app.toolActive
                    val erase = app.annotate && (eraserPen || secondary || (annotateTool && app.tool == DrawTool.ERASER))
                    if (erase) {
                        eraseGesture(app, doc, page, W, down)
                        return@awaitEachGesture
                    }

                    // An item under the pointer (tap or drag selects / moves it).
                    val pad = 6f / W
                    val itemHit: Any? = if (pa == null || !app.annotate) null else when {
                        !annotateTool -> kit.textAt(pa, x0, y0, pad) ?: kit.imageAt(pa, x0, y0)
                        app.tool == DrawTool.TEXT -> kit.textAt(pa, x0, y0, pad)
                        app.tool == DrawTool.IMAGE -> kit.imageAt(pa, x0, y0) ?: kit.textAt(pa, x0, y0, pad)
                        app.tool == DrawTool.SELECT -> kit.textAt(pa, x0, y0, pad) ?: kit.imageAt(pa, x0, y0)
                            ?: kit.strokeAt(pa, x0, y0, pad)
                        else -> null
                    }
                    if (itemHit != null) {
                        app.select(doc, page, itemHit)
                        if (isDouble && itemHit is TextNote) {
                            app.editNote = page to itemHit
                            awaitRelease()
                            return@awaitEachGesture
                        }
                        dragItem(app, doc, page, pa!!, itemHit, Handle.Move, W, aspect, kit, down, false)
                        return@awaitEachGesture
                    }

                    if (annotateTool && (app.tool == DrawTool.PEN || app.tool == DrawTool.HIGHLIGHTER)) {
                        val hl = app.tool == DrawTool.HIGHLIGHTER
                        // A tap on text / a picture (pen) or a highlighter line (highlighter) selects it.
                        val tapTarget: Any? = pa?.let {
                            if (hl) kit.strokeAt(it, x0, y0, pad, highlighterOnly = true)
                            else kit.textAt(it, x0, y0, pad) ?: kit.imageAt(it, x0, y0)
                        }
                        drawGesture(app, doc, page, W, down, hl, tapTarget)
                        return@awaitEachGesture
                    }

                    if (annotateTool && app.tool == DrawTool.SELECT) {
                        lassoGesture(app, doc, page, W, down, kit)
                        return@awaitEachGesture
                    }

                    if (annotateTool && (app.tool == DrawTool.TEXT || app.tool == DrawTool.IMAGE)) {
                        val moved = waitTap(down.id, slop)
                        if (!moved) {
                            if (app.tool == DrawTool.TEXT) {
                                app.addTextAt = Triple(page, x0, y0 + app.textSizeOf(app.setting.sizeTenths) * 0.8f)
                            } else {
                                app.scope.launch {
                                    delay(50)
                                    Files.openImage()?.let { app.placeImage(doc, page, x0, y0, it) }
                                }
                            }
                        }
                        return@awaitEachGesture
                    }

                    // ---- View mode / no tool: text selection, highlights, panning
                    val idx = doc.searchIndex
                    if (!app.annotate) {
                        val hl = pa?.highlights?.lastOrNull { h -> h.rects.any { it.contains(x0, y0) } }
                        val ci = if (idx != null && idx.isReady(page)) idx.charAt(page, x0, y0, TEXT_REACH) else -1
                        if (isDouble && ci >= 0 && idx != null) {
                            val r = idx.wordAt(page, ci)
                            doc.textSel = TextSelection(page, r.first, r.last)
                            awaitRelease()
                            return@awaitEachGesture
                        }
                        if (ci >= 0 && idx != null) {
                            // Drag over text: select it (a plain click on a highlight selects the highlight).
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                if (!moved && (ch.position - down.position).getDistance() < slop) continue
                                moved = true
                                val to = idx.charAt(page, nx(ch.position), ny(ch.position), 1f)
                                if (to >= 0) doc.textSel = TextSelection(page, ci, to)
                                ch.consume()
                            }
                            if (!moved && hl != null) doc.selectedHighlight = page to hl
                            return@awaitEachGesture
                        }
                        if (hl != null) {
                            if (!waitTap(down.id, slop)) doc.selectedHighlight = page to hl
                            return@awaitEachGesture
                        }
                    }
                    panGesture(down, coords, onPan)
                }
            }
    ) {
        val filter = remember(app.view) { pageFilter(app) }
        val night = app.view.colorMode == ColorMode.NIGHT
        // Page + saved annotations (a cached layer, redrawn only when the page changes).
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            readState(doc.versions[page], PictureCache.version)
            bitmap?.let {
                drawImage(
                    it, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Medium, colorFilter = filter
                )
            }
            doc.pages[page]?.let { drawAnnotations(it, size.width, nt, night, app.penPressure) }
        }
        // Live feedback: the stroke being drawn, selection, lasso, guides, eraser.
        Canvas(Modifier.fillMaxSize()) {
            drawOverlay(app, doc, page, size.width, nt)
        }
        SelectionPopup(app, doc, page, widthPx)
    }
}

// =================================================================== drawing

fun DrawScope.drawStroke(st: InkStroke, W: Float, night: Boolean) {
    val pts = st.points
    if (pts.isEmpty()) return
    if (st.highlighter) {
        val c = st.color.toColor().copy(alpha = Opacity.of(st.color, Opacity.INK_DEFAULT))
        val w = st.width * W
        if (pts.size == 1) {
            drawCircle(c, w / 2f, Offset(pts[0].x * W, pts[0].y * W), blendMode = if (night) BlendMode.SrcOver else BlendMode.Multiply)
            return
        }
        val path = Path().apply {
            moveTo(pts[0].x * W, pts[0].y * W)
            for (i in 1 until pts.size) lineTo(pts[i].x * W, pts[i].y * W)
        }
        drawPath(path, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = if (night) BlendMode.SrcOver else BlendMode.Multiply)
        return
    }
    val c = st.color.toColor()
    if (pts.size == 1) {
        drawCircle(c, st.width * W * Ink.pressureScale(pts[0].pressure) / 2f, Offset(pts[0].x * W, pts[0].y * W))
        return
    }
    val p0 = pts[0].pressure
    if (pts.all { it.pressure == p0 }) {
        val path = Path().apply {
            moveTo(pts[0].x * W, pts[0].y * W)
            for (i in 1 until pts.size) lineTo(pts[i].x * W, pts[i].y * W)
        }
        drawPath(path, c, style = Stroke(st.width * W * Ink.pressureScale(p0), cap = StrokeCap.Round, join = StrokeJoin.Round))
    } else {
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            drawLine(c, Offset(a.x * W, a.y * W), Offset(b.x * W, b.y * W),
                strokeWidth = st.width * W * Ink.pressureScale((a.pressure + b.pressure) / 2f), cap = StrokeCap.Round)
        }
    }
}

fun DrawScope.drawNote(t: TextNote, W: Float, nt: NoteText) {
    val lines = nt.kit.layout(t)
    val boxW = nt.kit.boxWidth(t, lines)
    val px = t.size * W
    val style = nt.style(px, t.color.toColor())
    lines.forEachIndexed { i, line ->
        if (line.text.isEmpty()) return@forEachIndexed
        val r = nt.measurer.measure(line.text, style, softWrap = false, maxLines = 1)
        val baseline = (t.y + t.size * TextLayout.LINE_HEIGHT * i) * W
        val left = (t.x + TextLayout.offset(t.align, boxW, line.width * 1f)) * W
        drawText(r, topLeft = Offset(left, baseline - r.firstBaseline))
    }
}

fun DrawScope.drawAnnotations(pa: PageAnnotations, W: Float, nt: NoteText, night: Boolean, pressure: Boolean) {
    for (h in pa.highlights) {
        val c = h.color.toColor().copy(alpha = Opacity.of(h.color, Opacity.TEXT_DEFAULT))
        for (r in h.rects) {
            if (h.underline) {
                val y = r.bottom * W
                drawLine(c.copy(alpha = max(c.alpha, 0.8f)), Offset(r.left * W, y), Offset(r.right * W, y), strokeWidth = max(1.5f, r.height() * W * 0.08f))
            } else {
                drawRect(c, Offset(r.left * W, r.top * W), Size(r.width() * W, r.height() * W), blendMode = if (night) BlendMode.SrcOver else BlendMode.Multiply)
            }
        }
    }
    for (im in pa.images) {
        val b = PictureCache.get(im.file)
        val tl = Offset(im.x * W, im.y * W)
        val sz = Size(im.w * W, im.h * W)
        if (b != null) {
            drawImage(b, dstOffset = IntOffset(tl.x.toInt(), tl.y.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(sz.width.toInt().coerceAtLeast(1), sz.height.toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium)
        } else drawRect(Color(0x22000000), tl, sz)
    }
    for (st in pa.strokes) drawStroke(st, W, night)
    for (t in pa.texts) drawNote(t, W, nt)
}

private fun DrawScope.dashedRect(r: Rect, color: Color = SEL_BLUE) {
    drawRect(color, r.topLeft, r.size, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
}

private fun DrawScope.handleBox(p: Offset) {
    val s = 9f
    drawRect(Color.White, Offset(p.x - s / 2, p.y - s / 2), Size(s, s))
    drawRect(SEL_BLUE, Offset(p.x - s / 2, p.y - s / 2), Size(s, s), style = Stroke(1.5f))
}

private fun RectF.toPx(W: Float) = Rect(left * W, top * W, right * W, bottom * W)

private fun DrawScope.drawOverlay(app: AppState, doc: DocState, page: Int, W: Float, nt: NoteText) {
    readState(doc.activeVersion)
    // Search hits
    doc.hits.forEachIndexed { i, h ->
        if (h.page != page) return@forEachIndexed
        val active = i == doc.activeHit
        for (r in h.rects) {
            val rr = r.toPx(W)
            drawRect(if (active) Color(0x88F97316) else Color(0x55FBBF24), rr.topLeft, rr.size)
            if (active) drawRect(Color(0xFFEA580C), rr.topLeft, rr.size, style = Stroke(1.5f))
        }
    }
    // Text selection
    doc.textSel?.takeIf { it.page == page }?.let { s ->
        doc.searchIndex?.rangeRects(page, min(s.from, s.to), max(s.from, s.to))?.forEach { r ->
            val rr = r.toPx(W)
            drawRect(Color(0x552563EB), rr.topLeft, rr.size)
        }
    }
    doc.selectedHighlight?.takeIf { it.first == page }?.let { (_, h) ->
        unionOf(h.rects)?.let { dashedRect(it.toPx(W).inflate(3f)) }
    }
    // The stroke being drawn
    doc.activeStroke?.takeIf { it.first == page }?.let { drawStroke(it.second, W, app.view.colorMode == ColorMode.NIGHT) }
    // Lasso
    doc.lasso?.takeIf { it.first == page && it.second.size > 1 }?.let { (_, pts) ->
        val path = Path().apply {
            moveTo(pts[0].first * W, pts[0].second * W)
            for (i in 1 until pts.size) lineTo(pts[i].first * W, pts[i].second * W)
        }
        drawPath(path, SEL_BLUE, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
    }
    // Guides
    doc.guides?.takeIf { it.first == page }?.let { (_, xs, ys) ->
        for (x in xs) drawLine(GUIDE, Offset(x * W, 0f), Offset(x * W, size.height), strokeWidth = 1f)
        for (y in ys) drawLine(GUIDE, Offset(0f, y * W), Offset(size.width, y * W), strokeWidth = 1f)
    }
    // Eraser
    doc.eraserAt?.takeIf { it.first == page }?.let { (_, x, y) ->
        val r = eraserRadius(app, W) * W
        drawCircle(Color(0x33000000), r, Offset(x * W, y * W))
        drawCircle(Color(0xAA374151), r, Offset(x * W, y * W), style = Stroke(1f))
    }
    // Selected item frame
    val sel = doc.editing
    if (sel != null && doc.editingPage == page) {
        val kit = nt.kit
        val f = kit.frame(sel, 6f / W) ?: return
        val r = f.toPx(W)
        if (sel is InkStroke && sel.highlighter && sel.points.size == 2) {
            dashedRect(r.inflate(4f))
            for (p in sel.points) handleBox(Offset(p.x * W, p.y * W))
            return
        }
        dashedRect(if (sel is InkStroke) r.inflate(4f) else r)
        for ((cx, cy) in kit.corners(f)) handleBox(Offset(cx * W, cy * W))
        if (sel is TextNote) {
            handleBox(Offset(r.left, r.center.y)); handleBox(Offset(r.right, r.center.y))
        }
    }
}

private fun unionOf(rs: List<RectF>): RectF? {
    var u: RectF? = null
    for (r in rs) u = u?.apply { union(r) } ?: RectF(r)
    return u
}

fun eraserRadius(app: AppState, W: Float): Float = max(app.eraserWidth * 3f, 6f / W)

// =================================================================== gestures

private class ClickTracker {
    var page = -1
    var time = 0L
    var pos = Offset.Zero
    fun isDouble(p: Int, at: Offset, t: Long): Boolean {
        val d = p == page && t - time < 400 && (at - pos).getDistance() < 8f
        page = p; time = if (d) 0L else t; pos = at
        return d
    }
}

private sealed class Handle {
    object Move : Handle()
    class Corner(val i: Int) : Handle()
    class Side(val left: Boolean) : Handle()
    class End(val i: Int) : Handle()
}

private fun handleAt(kit: PageGeometryKit, sel: Any, at: Offset, W: Float): Handle? {
    val f = kit.frame(sel, 6f / W) ?: return null
    val reach = 10f
    fun near(x: Float, y: Float) = hypot(x * W - at.x, y * W - at.y) < reach
    if (sel is InkStroke && sel.highlighter && sel.points.size == 2) {
        sel.points.forEachIndexed { i, p -> if (near(p.x, p.y)) return Handle.End(i) }
    } else {
        kit.corners(f).forEachIndexed { i, (x, y) -> if (near(x, y)) return Handle.Corner(i) }
        if (sel is TextNote) {
            if (near(f.left, f.centerY())) return Handle.Side(true)
            if (near(f.right, f.centerY())) return Handle.Side(false)
        }
    }
    val grow = if (sel is InkStroke) 6f / W else 0f
    return if (at.x / W >= f.left - grow && at.x / W <= f.right + grow && at.y / W >= f.top - grow && at.y / W <= f.bottom + grow) Handle.Move else null
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitRelease() {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.changes.none { it.pressed }) return
    }
}

/** Waits for the button to go up; true when it moved more than [slop] first (not a tap). */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitTap(
    id: androidx.compose.ui.input.pointer.PointerId, slop: Float
): Boolean {
    var start: Offset? = null
    var moved = false
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == id } ?: return moved
        if (start == null) start = ch.previousPosition
        if ((ch.position - start).getDistance() > slop) moved = true
        if (!ch.pressed) return moved
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.panGesture(
    down: androidx.compose.ui.input.pointer.PointerInputChange, coords: LayoutCoordinates?, onPan: (Offset) -> Unit
) {
    val c = coords
    var last = c?.localToRoot(down.position) ?: down.position
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return
        if (!ch.pressed) return
        val now = (if (c != null && c.isAttached) c.localToRoot(ch.position) else ch.position)
        val d = now - last
        last = now
        if (d != Offset.Zero) onPan(d)
        ch.consume()
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.drawGesture(
    app: AppState, doc: DocState, page: Int, W: Float,
    down: androidx.compose.ui.input.pointer.PointerInputChange, highlighter: Boolean, tapTarget: Any?
) {
    val width = app.strokeWidth * (if (highlighter) 5f else 1f)
    val usePressure = app.penPressure && !highlighter
    var simulated = 0.55f
    var lastT = down.uptimeMillis
    fun pressureOf(ch: androidx.compose.ui.input.pointer.PointerInputChange, dist: Float): Float {
        if (!usePressure) return Ink.NEUTRAL_PRESSURE
        val p = ch.pressure
        if (p > 0f && p < 0.999f) return p // a real pen pressure
        // Mouse / pen without pressure: slower = heavier.
        val dt = (ch.uptimeMillis - lastT).coerceAtLeast(1L)
        lastT = ch.uptimeMillis
        val speed = dist / dt
        val target = (0.75f - speed * 180f).coerceIn(0.3f, 0.75f)
        simulated += (target - simulated) * 0.3f
        return simulated
    }
    val st = InkStroke(mutableListOf(StrokePoint(down.position.x / W, down.position.y / W, pressureOf(down, 0f))), app.color, width, highlighter)
    doc.activeStroke = page to st
    doc.activeVersion++
    var moved = false
    down.consume()
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        val positions = ch.historical.map { it.position } + ch.position
        for (p in positions) {
            val last = st.points.last()
            val x = p.x / W
            val y = p.y / W
            val d = hypot(x - last.x, y - last.y)
            if (d * W < 0.8f) continue
            if ((p - down.position).getDistance() > 3f) moved = true
            st.points.add(StrokePoint(x, y, pressureOf(ch, d)))
        }
        doc.activeVersion++
        ch.consume()
        if (!ch.pressed) break
    }
    doc.activeStroke = null
    if (!moved && tapTarget != null) {
        app.select(doc, page, tapTarget)
        return
    }
    kitRefine(st, app)
    if (st.points.size == 1) st.points.add(StrokePoint(st.points[0].x + 0.0002f, st.points[0].y, st.points[0].pressure))
    doc.annotationsFor(page).strokes.add(st)
    app.onItemAdded(doc, page, st)
}

private val refineKit = PageGeometryKit { 0f }
private fun kitRefine(st: InkStroke, app: AppState) = refineKit.refine(st, app.inkSmoothing, app.hlStraighten)

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.eraseGesture(
    app: AppState, doc: DocState, page: Int, W: Float, down: androidx.compose.ui.input.pointer.PointerInputChange
) {
    val removed = ArrayList<InkStroke>()
    val added = ArrayList<InkStroke>()
    val rn = eraserRadius(app, W)
    val pixel = app.eraserPixel
    var lastX = down.position.x / W
    var lastY = down.position.y / W
    fun eraseAt(x: Float, y: Float) {
        val pa = doc.pages[page] ?: return
        if (pixel) {
            val (rem, add) = refineKit.erasePixels(pa, x, y, rn)
            if (rem.isEmpty()) return
            for (s in rem) if (!added.remove(s)) removed.add(s)
            added.addAll(add)
        } else {
            val hit = pa.strokes.filter { refineKit.hits(it, x, y, rn + it.width / 2f) }
            if (hit.isEmpty()) return
            pa.strokes.removeAll(hit.toSet())
            for (s in hit) if (!added.remove(s)) removed.add(s)
        }
        doc.touch(page)
    }
    fun sweep(x: Float, y: Float) {
        val d = hypot(x - lastX, y - lastY)
        val steps = (d / (rn * 0.5f)).toInt().coerceIn(1, 50)
        for (i in 1..steps) eraseAt(lastX + (x - lastX) * i / steps, lastY + (y - lastY) * i / steps)
        lastX = x; lastY = y
    }
    eraseAt(lastX, lastY)
    doc.eraserAt = Triple(page, lastX, lastY)
    down.consume()
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        if (!ch.pressed) break
        sweep(ch.position.x / W, ch.position.y / W)
        doc.eraserAt = Triple(page, ch.position.x / W, ch.position.y / W)
        ch.consume()
    }
    doc.eraserAt = null
    if (removed.isNotEmpty() || added.isNotEmpty()) {
        app.record(doc, page, EraseEdit(removed, added))
        app.markDirty(doc)
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.lassoGesture(
    app: AppState, doc: DocState, page: Int, W: Float,
    down: androidx.compose.ui.input.pointer.PointerInputChange, kit: PageGeometryKit
) {
    val pts = ArrayList<Pair<Float, Float>>()
    pts.add(down.position.x / W to down.position.y / W)
    doc.lasso = page to pts.toList()
    down.consume()
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        if (!ch.pressed) break
        val p = ch.position.x / W to ch.position.y / W
        val l = pts.last()
        if (hypot(p.first - l.first, p.second - l.second) * W > 2f) {
            pts.add(p)
            doc.lasso = page to pts.toList()
        }
        ch.consume()
    }
    doc.lasso = null
    if (pts.size < 3) return
    val pa = doc.pages[page] ?: return
    fun inside(x: Float, y: Float) = PageGeometryKit.pointInPolygon(pts, x, y)
    val items = ArrayList<Any>()
    for (st in pa.strokes) {
        val n = st.points.count { inside(it.x, it.y) }
        if (n > 0 && n >= st.points.size * 0.5f) items.add(st)
    }
    for (t in pa.texts) { val b = kit.textBox(t); if (inside(b.centerX(), b.centerY())) items.add(t) }
    for (im in pa.images) if (inside(im.x + im.w / 2, im.y + im.h / 2)) items.add(im)
    when (items.size) {
        0 -> {}
        1 -> app.select(doc, page, items[0])
        else -> app.select(doc, page, ItemGroup(items))
    }
}

/** Moves / resizes the selected item(s) while the button is down (with alignment guides). */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.dragItem(
    app: AppState, doc: DocState, page: Int, pa: PageAnnotations, orig: Any, handle: Handle,
    W: Float, aspect: Float, kit: PageGeometryKit,
    down: androidx.compose.ui.input.pointer.PointerInputChange, isDouble: Boolean
) {
    val pad = 6f / W
    val x0 = down.position.x / W
    val y0 = down.position.y / W
    val targets by lazy {
        kit.guideTargets(pa, aspect, listOf(orig), doc.searchIndex?.takeIf { it.isReady(page) }?.lineBoxes(page) ?: emptyList())
    }
    val thr = 6f / W
    var cur = orig
    var moved = false
    down.consume()
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
        if (!ch.pressed) break
        val x = ch.position.x / W
        val y = ch.position.y / W
        if (!moved && hypot(x - x0, y - y0) * W < 3f) continue
        moved = true
        ch.consume()
        val next: Any? = when (handle) {
            is Handle.Move -> {
                var dx = x - x0
                var dy = y - y0
                val f0 = kit.frame(orig, 0f)
                val gx = ArrayList<Float>()
                val gy = ArrayList<Float>()
                if (f0 != null) {
                    val f = RectF(f0).apply { offset(dx, dy) }
                    kit.nearest(floatArrayOf(f.left, f.centerX(), f.right), targets.first, thr)?.let { (adj, t) -> dx += adj; gx.add(t) }
                    kit.nearest(floatArrayOf(f.top, f.centerY(), f.bottom), targets.second, thr)?.let { (adj, t) -> dy += adj; gy.add(t) }
                }
                doc.guides = if (gx.isEmpty() && gy.isEmpty()) null else Triple(page, gx, gy)
                kit.shifted(orig, dx, dy)
            }
            is Handle.Corner -> if (orig is InkStroke || orig is TextNote) kit.scaledGroup(ItemGroup(listOf(orig)), handle.i, x, y, 0f)?.items?.firstOrNull()
                else kit.resized(orig, handle.i, x, y, pad)
            is Handle.Side -> (orig as? TextNote)?.let { kit.widened(it, handle.left, x) }
            is Handle.End -> (orig as? InkStroke)?.let { kit.stretched(it, handle.i, x, y) }
        }
        if (next != null && app.replace(pa, cur, next)) {
            cur = next
            doc.editing = next
            doc.touch(page)
        }
    }
    doc.guides = null
    if (cur !== orig) {
        app.record(doc, page, NoteEdit(orig, cur))
        app.markDirty(doc)
    } else if (!moved && isDouble && orig is TextNote) {
        app.editNote = page to orig
    }
}

/** Reads state values so a draw block is redone when they change. */
@Suppress("UNUSED_PARAMETER")
fun readState(vararg values: Any?) {}

@Composable
private fun rememberPageBitmap(app: AppState, doc: DocState, renderer: PageRenderer, page: Int, widthPx: Float): ImageBitmap? {
    val target = renderWidthFor(widthPx)
    var bmp by remember(renderer, page) { mutableStateOf(Bitmaps.cached(renderer.peek(page))) }
    var bmpW by remember(renderer, page) { mutableStateOf(if (bmp != null) renderer.peek(page)?.width ?: 0 else 0) }
    LaunchedEffect(renderer, page, target) {
        if (bmpW >= target * 0.9f) return@LaunchedEffect
        if (bmp == null) {
            renderer.peek(page)?.let { img ->
                bmp = withContext(Dispatchers.IO) { Bitmaps.of(img) }; bmpW = img.width
                if (bmpW >= target * 0.9f) return@LaunchedEffect
            }
        }
        if (bmp != null) delay(120) // zooming: wait until it settles
        val quick = withContext(Dispatchers.IO) { renderer.quick(page, target) }
        if (quick != null) {
            bmp = withContext(Dispatchers.IO) { Bitmaps.of(quick) }; bmpW = quick.width
            return@LaunchedEffect
        }
        if (page == doc.currentPage) app.loadingPage = page
        try {
            if (bmp == null && target > 1100) {
                // Something to look at quickly, then the sharp page.
                renderer.render(page, 600, urgent = true)?.let { img ->
                    bmp = withContext(Dispatchers.IO) { Bitmaps.of(img) }; bmpW = img.width
                }
            }
            renderer.render(page, target, urgent = true)?.let { img ->
                bmp = withContext(Dispatchers.IO) { Bitmaps.of(img) }; bmpW = img.width
            }
        } finally {
            if (app.loadingPage == page) app.loadingPage = -1
        }
    }
    return bmp
}

private class AboveProvider(val gap: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        var y = anchorBounds.top - gap - popupContentSize.height
        if (y < 0) y = anchorBounds.bottom + gap
        y = y.coerceIn(0, max(0, windowSize.height - popupContentSize.height))
        return IntOffset(x, y)
    }
}

@Composable
private fun PopupButton(text: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Label(text, color = Color.White) }
}

/** The bar of actions next to a selection (kept clear of its frame). */
@Composable
private fun SelectionPopup(app: AppState, doc: DocState, page: Int, W: Float) {
    val nt = LocalNoteText.current
    val textSel = doc.textSel?.takeIf { it.page == page }
    val hl = doc.selectedHighlight?.takeIf { it.first == page }?.second
    val sel = doc.editing?.takeIf { doc.editingPage == page }
    val frame: RectF = when {
        textSel != null -> unionOf(doc.searchIndex?.rangeRects(page, min(textSel.from, textSel.to), max(textSel.from, textSel.to)) ?: emptyList())
        hl != null -> unionOf(hl.rects)
        sel != null -> nt.kit.frame(sel, 6f / W)
        else -> null
    } ?: return
    if (doc.lasso != null || doc.guides != null) return
    val density = LocalDensity.current
    val r = frame.toPx(W)
    Box(
        Modifier.offset { IntOffset(r.left.toInt(), r.top.toInt()) }
            .size(with(density) { r.width.coerceAtLeast(1f).toDp() }, with(density) { r.height.coerceAtLeast(1f).toDp() })
    ) {
        androidx.compose.ui.window.Popup(
            popupPositionProvider = remember { AboveProvider(with(density) { 14.dp.roundToPx() }) },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false)
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.shadow(6.dp, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(Color(0xFF1F2937), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(3.dp)
            ) {
                when {
                    textSel != null -> {
                        PopupButton("Copy") { app.copySelection() }
                        PopupButton("Highlight") { app.markSelection(false) }
                        PopupButton("Underline") { app.markSelection(true) }
                    }
                    hl != null -> {
                        PopupButton("Color") { app.recolorSelected() }
                        PopupButton("Delete") { app.deleteSelected() }
                    }
                    sel is TextNote -> {
                        PopupButton("Edit") { app.editNote = page to sel }
                        PopupButton("Color") { app.recolorSelected() }
                        PopupButton("Delete") { app.deleteSelected() }
                    }
                    sel is ImageNote -> PopupButton("Delete") { app.deleteSelected() }
                    sel is InkStroke -> {
                        PopupButton("Color") { app.recolorSelected() }
                        if (!sel.highlighter) PopupButton("To text") { app.convertSelectionToText() }
                        PopupButton("Delete") { app.deleteSelected() }
                    }
                    sel is ItemGroup -> {
                        if (sel.items.any { it is InkStroke || it is TextNote }) PopupButton("Color") { app.recolorSelected() }
                        if (sel.items.any { it is InkStroke && !it.highlighter }) PopupButton("To text") { app.convertSelectionToText() }
                        PopupButton("Delete") { app.deleteSelected() }
                    }
                }
            }
        }
    }
}
