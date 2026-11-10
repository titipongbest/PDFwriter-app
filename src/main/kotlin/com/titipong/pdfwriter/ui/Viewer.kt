@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.titipong.pdfwriter.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titipong.pdfwriter.AppState
import com.titipong.pdfwriter.ColorMode
import com.titipong.pdfwriter.DocState
import com.titipong.pdfwriter.PageLayout
import com.titipong.pdfwriter.compat.RectF
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Hooks the viewer gives to the keyboard shortcuts. */
object ViewerControl {
    var scrollBy: (Float) -> Unit = {}
    var zoomBy: (Float) -> Unit = {}
}

/** Pages shown side by side for a layout (cover: the first page alone). */
fun spreads(count: Int, layout: PageLayout): List<List<Int>> = when (layout) {
    PageLayout.SINGLE -> List(count) { listOf(it) }
    PageLayout.DOUBLE -> (0 until count step 2).map { if (it + 1 < count) listOf(it, it + 1) else listOf(it) }
    PageLayout.COVER -> buildList {
        if (count > 0) add(listOf(0))
        var i = 1
        while (i < count) { add(if (i + 1 < count) listOf(i, i + 1) else listOf(i)); i += 2 }
    }
}

@Composable
fun DocumentViewer(app: AppState, doc: DocState) {
    val renderer = doc.renderer
    if (renderer == null) {
        Box(Modifier.fillMaxSize().background(Ui.Workspace), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (app.view.reading) {
        ReadingView(app, doc)
        return
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val v = app.view
    val vertical = v.vertical
    val rows = remember(renderer, v.layout) { spreads(renderer.pageCount, v.layout) }
    val gap = with(density) { 12.dp.toPx() }
    val margin = with(density) { 16.dp.toPx() }
    val odd = (v.rotation / 90) % 2 == 1

    // Auto crop: find each page's content area once.
    if (v.crop) {
        LaunchedEffect(renderer, doc.currentPage) {
            val around = (doc.currentPage - 4..doc.currentPage + 6).filter { it in 0 until renderer.pageCount && !doc.crops.containsKey(it) }
            for (p in around) {
                val b = renderer.contentBounds(p)
                doc.crops[p] = b ?: RectF(0f, 0f, 1f, renderer.aspects[p])
            }
        }
    }
    fun cropOf(p: Int): RectF = (if (v.crop) doc.crops[p] else null) ?: RectF(0f, 0f, 1f, renderer.aspects[p])
    fun dispAspect(p: Int): Float { val c = cropOf(p); val a = c.height() / c.width().coerceAtLeast(1e-4f); return if (odd) 1f / a else a }

    BoxWithConstraints(Modifier.fillMaxSize().background(if (v.colorMode == ColorMode.NIGHT) Color(0xFF2B2B2B) else Ui.Workspace)) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val perRow = rows.maxOfOrNull { it.size } ?: 1
        val median = remember(renderer, v.crop, odd, doc.crops.size) {
            (0 until renderer.pageCount).map { dispAspect(it) }.sorted().let { it.getOrElse(it.size / 2) { 1.414f } }
        }
        val availW = (vw - margin * 2 - gap * (perRow - 1)) / perRow
        val availH = vh - margin * 2
        val baseW = if (app.fitWidth) availW else min(availW, availH / median)
        val pageW = (baseW * doc.zoom).coerceAtLeast(60f)
        val rowW = perRow * pageW + gap * (perRow - 1)
        val maxRowH = rows.maxOfOrNull { r -> r.maxOf { dispAspect(it) } * pageW } ?: 0f

        val onPan: (Offset) -> Unit = { d ->
            if (vertical) {
                doc.listState.dispatchRawDelta(-d.y)
                doc.hScroll.dispatchRawDelta(-d.x)
            } else {
                doc.listState.dispatchRawDelta(-d.x)
                doc.vScroll.dispatchRawDelta(-d.y)
            }
        }

        fun zoomBy(f: Float, anchor: Offset) {
            val old = doc.zoom
            val new = (old * f).coerceIn(0.3f, 6f)
            if (new == old) return
            val r = new / old
            val idx = doc.listState.firstVisibleItemIndex
            val off = doc.listState.firstVisibleItemScrollOffset
            val cross = if (vertical) doc.hScroll else doc.vScroll
            val crossVal = cross.value
            doc.zoom = new
            scope.launch {
                delay(16)
                val main = if (vertical) anchor.y else anchor.x
                val crossA = if (vertical) anchor.x else anchor.y
                doc.listState.scrollToItem(idx, 0)
                doc.listState.scrollBy((off + main) * r - main)
                delay(16)
                cross.scrollTo(((crossVal + crossA) * r - crossA).roundToInt().coerceAtLeast(0))
            }
        }
        ViewerControl.zoomBy = { f -> zoomBy(f, Offset(vw / 2, vh / 2)) }
        ViewerControl.scrollBy = { d -> scope.launch { doc.listState.scrollBy(d) } }

        val lastFlip = remember { longArrayOf(0L) }
        val wheel = Modifier.onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { ev ->
            val ch = ev.changes.firstOrNull() ?: return@onPointerEvent
            val dy = ch.scrollDelta.y
            if (ev.keyboardModifiers.isCtrlPressed) {
                zoomBy(if (dy < 0) 1.1f else 1f / 1.1f, ch.position)
                ch.consume()
            } else if (!vertical && !ev.keyboardModifiers.isShiftPressed) {
                val canCross = if (dy > 0) doc.vScroll.value < doc.vScroll.maxValue else doc.vScroll.value > 0
                if (!canCross) {
                    val now = System.currentTimeMillis()
                    if (now - lastFlip[0] > 220 && dy != 0f) {
                        lastFlip[0] = now
                        val row = (doc.listState.firstVisibleItemIndex + if (dy > 0) 1 else 0) - if (dy < 0 && doc.listState.firstVisibleItemScrollOffset == 0) 1 else 0
                        val target = row.coerceIn(0, rows.size - 1)
                        scope.launch { doc.listState.animateScrollToItem(target) }
                    }
                    ch.consume()
                }
            }
        }

        // Middle button (wheel press) held: drag the pages, anywhere in the viewer, any mode / tool.
        var panning by remember { androidx.compose.runtime.mutableStateOf(false) }
        val middlePan = Modifier.pointerInput(onPan) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.type != PointerEventType.Press || !ev.buttons.isTertiaryPressed) continue
                    var last = ev.changes.firstOrNull()?.position ?: continue
                    ev.changes.forEach { it.consume() }
                    panning = true
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull() ?: break
                        val d = ch.position - last
                        last = ch.position
                        if (d != Offset.Zero) onPan(d)
                        e.changes.forEach { it.consume() }
                        if (!e.buttons.isTertiaryPressed) break
                    }
                    panning = false
                }
            }
        }.then(if (panning) Modifier.pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true) else Modifier)

        // Which page is being read.
        LaunchedEffect(doc, rows, vertical) {
            snapshotFlow {
                val info = doc.listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val item = info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size > center }
                    ?: info.visibleItemsInfo.firstOrNull()
                item?.index ?: -1
            }.distinctUntilChanged().collect { r ->
                if (r in rows.indices && doc.restored) app.rememberPage(doc, rows[r].first())
            }
        }
        // Scroll to the page last read, and when the layout changes.
        LaunchedEffect(doc, rows) {
            val r = rows.indexOfFirst { doc.currentPage in it }.coerceAtLeast(0)
            if (!doc.restored || doc.listState.firstVisibleItemIndex != r) doc.listState.scrollToItem(r)
            doc.restored = true
            renderer.focus = doc.currentPage
        }
        LaunchedEffect(app.scrollRequest) {
            val req = app.scrollRequest ?: return@LaunchedEffect
            if (req.first !== doc) return@LaunchedEffect
            val r = rows.indexOfFirst { req.second in it }
            if (r >= 0) doc.listState.scrollToItem(r)
            app.scrollRequest = null
        }
        // Get the pages around the current one ready.
        val target = renderWidthFor(pageW / cropOf(doc.currentPage).width().coerceAtLeast(0.1f))
        LaunchedEffect(renderer, doc.currentPage, target) {
            delay(250)
            for (d in 1..3) for (p in listOf(doc.currentPage + d, doc.currentPage - d)) {
                if (p in 0 until renderer.pageCount) launch { renderer.render(p, target, urgent = false) }
            }
        }

        val pageContent: @Composable (Int) -> Unit = { p ->
            val dw = pageW
            val dh = pageW * dispAspect(p)
            PageFrame(app, doc, p, dw, dh, cropOf(p), odd, v.rotation, onPan)
        }

        if (vertical) {
            val contentW = max(vw, rowW + margin * 2)
            Box(Modifier.fillMaxSize().then(wheel).then(middlePan)) {
                Box(Modifier.fillMaxSize().horizontalScroll(doc.hScroll)) {
                    LazyColumn(
                        state = doc.listState,
                        modifier = Modifier.width(with(density) { contentW.toDp() }).fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(rows.size, key = { rows[it].first() }) { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                for (p in rows[r]) pageContent(p)
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(doc.listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
                if (contentW > vw + 1) HorizontalScrollbar(rememberScrollbarAdapter(doc.hScroll), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                PageIndicator(doc, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 14.dp))
            }
        } else {
            val contentH = max(vh, maxRowH + margin * 2)
            val snap = rememberSnapFlingBehavior(doc.listState)
            Box(Modifier.fillMaxSize().then(wheel).then(middlePan)) {
                Box(Modifier.fillMaxSize().verticalScroll(doc.vScroll)) {
                    LazyRow(
                        state = doc.listState,
                        flingBehavior = snap,
                        modifier = Modifier.height(with(density) { contentH.toDp() }).fillMaxWidth()
                    ) {
                        items(rows.size, key = { rows[it].first() }) { r ->
                            Box(
                                Modifier.width(with(density) { max(vw, rowW + margin * 2).toDp() }).height(with(density) { contentH.toDp() }),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    for (p in rows[r]) pageContent(p)
                                }
                            }
                        }
                    }
                }
                HorizontalScrollbar(rememberScrollbarAdapter(doc.listState), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                if (contentH > vh + 1) VerticalScrollbar(rememberScrollbarAdapter(doc.vScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                PageIndicator(doc, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 14.dp))
            }
        }
    }
}

@Composable
private fun PageIndicator(doc: DocState, modifier: Modifier) {
    Box(modifier.background(Color(0xB31F2937), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text("${doc.currentPage + 1} / ${doc.pageCount}", color = Color.White, fontSize = 12.sp, fontFamily = Ui.font)
    }
}

/** A page as displayed: cropped to [crop] and turned by [rotation], [dw] x [dh] pixels. */
@Composable
private fun PageFrame(
    app: AppState, doc: DocState, page: Int, dw: Float, dh: Float, crop: RectF, odd: Boolean, rotation: Int,
    onPan: (Offset) -> Unit
) {
    val density = LocalDensity.current
    val uw = if (odd) dh else dw
    val uh = if (odd) dw else dh
    val fullW = uw / crop.width().coerceAtLeast(1e-4f)
    val aspect = doc.renderer?.aspects?.getOrNull(page) ?: 1.414f
    Box(Modifier.size(with(density) { dw.toDp() }, with(density) { dh.toDp() }).shadow(3.dp)) {
        Box(
            Modifier.layout { m, _ ->
                val pl = m.measure(Constraints.fixed(uw.roundToInt().coerceAtLeast(1), uh.roundToInt().coerceAtLeast(1)))
                layout(dw.roundToInt(), dh.roundToInt()) {
                    pl.placeWithLayer(((dw - uw) / 2f).roundToInt(), ((dh - uh) / 2f).roundToInt()) { rotationZ = rotation.toFloat() }
                }
            }.clipToBounds()
        ) {
            Box(Modifier.layout { m, c ->
                val pl = m.measure(Constraints.fixed(fullW.roundToInt().coerceAtLeast(1), (fullW * aspect).roundToInt().coerceAtLeast(1)))
                layout(c.maxWidth, c.maxHeight) { pl.place((-crop.left * fullW).roundToInt(), (-crop.top * fullW).roundToInt()) }
            }) {
                PageView(app, doc, page, fullW, onPan)
            }
        }
    }
}

/** Reading mode: the text of the pages, in a column at the chosen size. */
@Composable
private fun ReadingView(app: AppState, doc: DocState) {
    val texts by produceState<List<String>?>(null, doc.base) {
        value = withContext(Dispatchers.IO) { try { PdfAnnotationIO.extractText(doc.base) } catch (e: Throwable) { emptyList() } }
    }
    val v = app.view
    val bg = paperColor(app)
    val fg = when (v.colorMode) {
        ColorMode.NIGHT -> Color(0xFFE5E5E5)
        ColorMode.SEPIA -> Color(0xFF433422)
        ColorMode.CUSTOM -> v.customFg.toColor()
        else -> Ui.Text
    }
    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.TopCenter) {
        val t = texts
        if (t == null) { CircularProgressIndicator(Modifier.padding(40.dp)); return@Box }
        if (t.all { it.isBlank() }) {
            Text("This file has no text. Use OCR first.", Modifier.padding(40.dp), color = fg, fontFamily = Ui.font)
            return@Box
        }
        SelectionContainer {
            LazyColumn(Modifier.widthIn(max = 820.dp).fillMaxHeight().padding(horizontal = 24.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
                items(t.indices.toList()) { i ->
                    Column(Modifier.padding(bottom = 28.dp)) {
                        Text("— ${i + 1} —", color = fg.copy(alpha = 0.5f), fontSize = 12.sp, fontFamily = Ui.font, modifier = Modifier.padding(bottom = 8.dp))
                        Text(t[i].trim(), color = fg, fontSize = v.readingTextSize.sp, lineHeight = (v.readingTextSize * 1.6f).sp, fontFamily = Ui.noteFont)
                    }
                }
            }
        }
    }
}
