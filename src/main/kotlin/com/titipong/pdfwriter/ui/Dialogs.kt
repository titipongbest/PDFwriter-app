@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.titipong.pdfwriter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.titipong.pdfwriter.AppDirs
import com.titipong.pdfwriter.AppState
import com.titipong.pdfwriter.ColorMode
import com.titipong.pdfwriter.DocState
import com.titipong.pdfwriter.PageLayout
import com.titipong.pdfwriter.Prefs
import com.titipong.pdfwriter.compat.Color as AColor
import com.titipong.pdfwriter.model.Opacity
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.pdf.PageTemplate
import com.titipong.pdfwriter.pdf.PdfTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Every dialog the app can show (the color picker last, so it opens on top). */
@Composable
fun Dialogs(app: AppState) {
    val doc = app.current
    if (doc != null) {
        app.addTextAt?.let { (page, x, y) -> AddTextDialog(app, doc, page, x, y) }
        app.editNote?.let { (page, note) -> EditNoteDialog(app, doc, page, note) }
        if (app.showPages) PagesDialog(app, doc)
        if (app.showBookmarks) BookmarksDialog(app, doc)
        if (app.showViewMode) ViewModeDialog(app)
        if (app.showExport) ExportDialog(app, doc)
        if (app.showOcr) OcrDialog(app)
    }
    if (app.showSettings) SettingsDialog(app)
    if (app.showAbout) AboutDialog(app)
    if (app.showNew) NewPdfDialog(app)
    app.colorRequest?.let { ColorPickerDialog(app, it) }
}

@Composable
fun Sheet(
    title: String, onDismiss: () -> Unit, width: Dp = 420.dp,
    buttons: @Composable RowScope.() -> Unit = { TextButton(onDismiss) { Text("Close", fontFamily = Ui.font) } },
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(width).shadow(16.dp, RoundedCornerShape(14.dp))
                .background(Color.White, RoundedCornerShape(14.dp)).padding(20.dp)
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = Ui.font, color = Ui.Text)
            Spacer(Modifier.height(14.dp))
            content()
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { buttons() }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, fontSize = 12.sp, color = Ui.SubText, fontFamily = Ui.font, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
}

@Composable
private fun SwitchRow(text: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 14.sp, fontFamily = Ui.font, color = Ui.Text)
            if (sub != null) Text(sub, fontSize = 12.sp, fontFamily = Ui.font, color = Ui.SubText)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ActionRow(text: String, sub: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(text, fontSize = 14.sp, fontFamily = Ui.font, color = Ui.Text)
        if (sub != null) Text(sub, fontSize = 12.sp, fontFamily = Ui.font, color = Ui.SubText)
    }
}

// ===================================================================== color picker

/** A gradient bar with a thumb; [value] 0..1. */
@Composable
private fun GradientBar(brush: Brush, value: Float, onChange: (Float) -> Unit) {
    var w by remember { mutableStateOf(1f) }
    Box(
        Modifier.fillMaxWidth().height(26.dp).padding(vertical = 3.dp)
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures { ch, _ -> onChange((ch.position.x / size.width).coerceIn(0f, 1f)) } }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            w = size.width
            drawRoundRect(brush, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            val x = value * size.width
            drawCircle(Color.White, size.height / 2 + 2f, Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2))
            drawCircle(Color(0xFF374151), size.height / 2 + 2f, Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2), style = Stroke(2f))
        }
    }
}

private val PRESETS = listOf(
    0xFF111827, 0xFF4B5563, 0xFF9CA3AF, 0xFFFFFFFF, 0xFFDC2626, 0xFFF97316, 0xFFF59E0B, 0xFFFACC15,
    0xFF16A34A, 0xFF22C55E, 0xFF0D9488, 0xFF06B6D4, 0xFF2563EB, 0xFF4F46E5, 0xFF7C3AED, 0xFFDB2777
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerDialog(app: AppState, req: AppState.ColorRequest) {
    val init = remember(req) { FloatArray(3).also { AColor.colorToHSV(req.initial, it) } }
    var hue by remember(req) { mutableStateOf(init[0]) }
    var sat by remember(req) { mutableStateOf(init[1]) }
    var bri by remember(req) { mutableStateOf(init[2]) }
    var picked by remember(req) { mutableStateOf(req.initial or 0xFF000000.toInt()) }
    var opacity by remember(req) { mutableStateOf(req.opacityDefault?.let { Opacity.of(req.initial, it) } ?: 1f) }
    var pressure by remember(req) { mutableStateOf(req.pressure ?: true) }
    fun fromHsv() {
        picked = if (req.hueOnly) AColor.HSVToColor(floatArrayOf(hue, if (req.light) 0.8f else 0.9f, if (req.light) 0.98f else 0.85f))
        else AColor.HSVToColor(floatArrayOf(hue, sat, bri))
    }
    fun done() {
        val base = picked or 0xFF000000.toInt()
        val result = req.opacityDefault?.let { d -> if (abs(opacity - d) < 0.005f) base else Opacity.with(base, opacity) } ?: base
        if (req.pressure != null && pressure != req.pressure) req.onPressure(pressure)
        app.colorRequest = null
        req.onPicked(result)
    }
    Sheet("Color", { app.colorRequest = null }, width = 380.dp, buttons = {
        TextButton({ app.colorRequest = null }) { Text("Cancel", fontFamily = Ui.font) }
        Button(::done) { Text("OK", fontFamily = Ui.font) }
    }) {
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                .border(1.dp, Ui.BarLine, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxSize().background(picked.toColor().copy(alpha = if (req.opacityDefault != null) opacity else 1f)))
        }
        Section("Hue")
        GradientBar(Brush.horizontalGradient(List(7) { AColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)).toColor() }), hue / 360f) {
            hue = it * 359.9f
            if (!req.hueOnly && sat < 0.05f) { sat = 0.8f; if (bri < 0.2f) bri = 0.85f }
            fromHsv()
        }
        if (!req.hueOnly) {
            Section("Saturation")
            GradientBar(Brush.horizontalGradient(listOf(AColor.HSVToColor(floatArrayOf(hue, 0f, bri)).toColor(), AColor.HSVToColor(floatArrayOf(hue, 1f, bri)).toColor())), sat) {
                sat = it; fromHsv()
            }
            Section("Brightness")
            GradientBar(Brush.horizontalGradient(listOf(Color.Black, AColor.HSVToColor(floatArrayOf(hue, sat, 1f)).toColor())), bri) {
                bri = it; fromHsv()
            }
        }
        val presets = when {
            !req.hueOnly -> PRESETS
            !req.light -> PRESETS.take(3)
            else -> emptyList()
        }
        if (presets.isNotEmpty()) {
            Section("Presets")
            FlowRow {
                for (c in presets) Swatch(c.toColor(), (picked and 0xFFFFFF) == (c and 0xFFFFFF), 22.dp) {
                    picked = c
                    val h = FloatArray(3); AColor.colorToHSV(c, h); hue = h[0]; sat = h[1]; bri = h[2]
                }
            }
        }
        if (req.opacityDefault != null) {
            Section("Opacity ${Math.round(opacity * 100)}%")
            Slider(opacity, { opacity = it }, valueRange = 0.1f..1f)
        }
        if (req.pressure != null) {
            Section("Pen line")
            Row {
                Chip("Pressure", pressure) { pressure = true }
                Chip("Even", !pressure) { pressure = false }
            }
            Text("Without a pressure pen, Pressure follows your speed (slow = thicker).", fontSize = 11.sp, color = Ui.SubText, fontFamily = Ui.font)
        }
    }
}

// ===================================================================== text

@Composable
private fun AddTextDialog(app: AppState, doc: DocState, page: Int, x: Float, y: Float) {
    var text by remember { mutableStateOf("") }
    val close = { app.addTextAt = null }
    Sheet("Add text", close, width = 460.dp, buttons = {
        TextButton(close) { Text("Cancel", fontFamily = Ui.font) }
        Button({ app.addText(doc, page, x, y, text); close() }) { Text("Add", fontFamily = Ui.font) }
    }) {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Ui.noteFont, fontSize = 16.sp),
            placeholder = { Text("Type here (Enter = new line)", fontFamily = Ui.font) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditNoteDialog(app: AppState, doc: DocState, page: Int, note: TextNote) {
    var text by remember(note) { mutableStateOf(note.text) }
    var color by remember(note) { mutableStateOf(note.color) }
    var tenths by remember(note) { mutableStateOf(app.tenthsOfTextSize(note.size)) }
    var align by remember(note) { mutableStateOf(note.align) }
    val close = { app.editNote = null }
    Sheet("Edit text", close, width = 480.dp, buttons = {
        TextButton(close) { Text("Cancel", fontFamily = Ui.font) }
        Button({ app.applyNoteEdit(doc, page, note, text, color, tenths, align); close() }) { Text("OK", fontFamily = Ui.font) }
    }) {
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Ui.noteFont, fontSize = 16.sp, color = color.toColor()))
        Section("Color")
        FlowRow(verticalArrangement = Arrangement.Center) {
            val pal = (AppState.DEFAULT_COLORS + app.toolSettings.getValue(com.titipong.pdfwriter.model.DrawTool.TEXT).palette).distinct()
            for (c in pal) Swatch(c.toColor(), c == color, 22.dp) { color = c }
            Box(Modifier.padding(start = 4.dp)) {
                ToolButton("ic_palette", "Other color") {
                    app.colorRequest = AppState.ColorRequest(color) { color = it }
                }
            }
        }
        Section("Size and alignment")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ tenths = (tenths - 5).coerceAtLeast(1) }) { Text("−", fontSize = 16.sp) }
            Text(String.format("%.1f", tenths / 10f), fontFamily = Ui.font, modifier = Modifier.width(40.dp))
            TextButton({ tenths = (tenths + 5).coerceAtMost(400) }) { Text("+", fontSize = 16.sp) }
            Spacer(Modifier.width(16.dp))
            ToolButton("ic_align_left", "Left", align == 0) { align = 0 }
            ToolButton("ic_align_center", "Center", align == 1) { align = 1 }
            ToolButton("ic_align_right", "Right", align == 2) { align = 2 }
        }
    }
}

// ===================================================================== pages

@Composable
private fun Thumb(doc: DocState, src: Int?, selected: Boolean, label: String, onClick: () -> Unit, onDouble: () -> Unit) {
    val renderer = doc.renderer
    val aspect = src?.let { renderer?.aspects?.getOrNull(it) } ?: 1.414f
    val bmp by produceState<ImageBitmap?>(null, renderer, src) {
        if (renderer != null && src != null) {
            val img = renderer.peek(src) ?: renderer.render(src, 260, urgent = false)
            value = img?.let { withContext(Dispatchers.IO) { Bitmaps.of(it) } }
        }
    }
    Column(
        Modifier.padding(6.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Ui.Selected else Color.Transparent)
            .border(2.dp, if (selected) Ui.Primary else Color.Transparent, RoundedCornerShape(8.dp))
            .pointerInput(src) { detectTapGestures(onDoubleTap = { onDouble() }, onTap = { onClick() }) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f / aspect.coerceIn(0.3f, 3f)).background(Color.White).border(1.dp, Ui.BarLine)) {
            bmp?.let { Image(it, null, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(4.dp))
        Label(label, size = 12, color = if (selected) Ui.Primary else Ui.SubText)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PagesDialog(app: AppState, doc: DocState) {
    val n = doc.pageCount
    val order = remember(doc.renderer) { mutableStateListOf<Int?>().apply { addAll(0 until n) } }
    val sel = remember(doc.renderer) { mutableStateListOf<Int>() } // positions in [order]
    val changed = order.toList() != (0 until n).toList()
    val close = { app.showPages = false; app.extractPick = false }
    fun selectedSources() = sel.sorted().mapNotNull { order.getOrNull(it) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(900.dp).height(640.dp).shadow(16.dp, RoundedCornerShape(14.dp))
                .background(Color.White, RoundedCornerShape(14.dp)).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (app.extractPick) "Extract pages" else "Pages", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = Ui.font)
                Spacer(Modifier.width(12.dp))
                Label(if (sel.isEmpty()) "Click to select, Ctrl/Shift for more, double-click to open" else "${sel.size} selected", color = Ui.SubText, size = 12)
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Select all", false) { sel.clear(); sel.addAll(order.indices) }
                Chip("Clear", false) { sel.clear() }
                if (!app.extractPick) {
                    Chip("Delete", false) {
                        if (sel.size >= order.size) { app.toast("A file needs at least one page"); return@Chip }
                        val s = sel.toSet(); val keep = order.filterIndexed { i, _ -> i !in s }
                        order.clear(); order.addAll(keep); sel.clear()
                    }
                    Chip("Duplicate", false) {
                        val ps = sel.sorted()
                        for ((k, i) in ps.withIndex()) order.add(i + 1 + k, order[i + k])
                        sel.clear()
                    }
                    Chip("Add blank page", false) {
                        val at = (sel.maxOrNull() ?: (order.size - 1)) + 1
                        order.add(at, null); sel.clear(); sel.add(at)
                    }
                    Chip("◀ Move", false) {
                        val ps = sel.sorted()
                        if (ps.isNotEmpty() && ps.first() > 0) {
                            for (i in ps) { val t = order[i - 1]; order[i - 1] = order[i]; order[i] = t }
                            sel.clear(); sel.addAll(ps.map { it - 1 })
                        }
                    }
                    Chip("Move ▶", false) {
                        val ps = sel.sortedDescending()
                        if (ps.isNotEmpty() && ps.first() < order.size - 1) {
                            for (i in ps) { val t = order[i + 1]; order[i + 1] = order[i]; order[i] = t }
                            sel.clear(); sel.addAll(ps.map { it + 1 })
                        }
                    }
                }
                Chip("Extract as PDF", app.extractPick) {
                    val pages = selectedSources()
                    if (pages.isEmpty()) { app.toast("Select pages first"); return@Chip }
                    Files.save("Save pages as PDF", "${doc.name}-pages.pdf", "pdf")?.let { app.extractPages(pages, it, null); close() }
                }
                Chip("Extract as PNG", false) {
                    val pages = selectedSources()
                    if (pages.isEmpty()) { app.toast("Select pages first"); return@Chip }
                    Files.chooseFolder("Folder for the pictures")?.let { app.extractPages(pages, it, "png"); close() }
                }
                Chip("Extract as JPG", false) {
                    val pages = selectedSources()
                    if (pages.isEmpty()) { app.toast("Select pages first"); return@Chip }
                    Files.chooseFolder("Folder for the pictures")?.let { app.extractPages(pages, it, "jpg"); close() }
                }
            }
            HorizontalDivider(color = Ui.BarLine)
            var anchor by remember { mutableStateOf(-1) }
            LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(order.toList()) { i, src ->
                    Thumb(doc, src, i in sel, if (src == null) "New blank" else "${src + 1}", onClick = {
                        val shift = Keys.shift
                        val ctrl = Keys.ctrl
                        when {
                            shift && anchor >= 0 -> { sel.clear(); sel.addAll(minOf(anchor, i)..maxOf(anchor, i)) }
                            ctrl -> if (i in sel) sel.remove(i) else sel.add(i)
                            else -> { sel.clear(); sel.add(i) }
                        }
                        anchor = i
                    }, onDouble = {
                        if (src != null && !changed) { app.goToPage(doc, src); close() }
                    })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (changed) Label("Changes are saved into the PDF when you press Apply", color = Ui.SubText, size = 12)
                Spacer(Modifier.weight(1f))
                TextButton(close) { Text(if (changed) "Cancel" else "Close", fontFamily = Ui.font) }
                if (changed) Button({ app.applyPageOrder(doc, order.toList()); close() }) { Text("Apply", fontFamily = Ui.font) }
            }
        }
    }
}

/** Ctrl / Shift state for clicks (from the window's key events). */
object Keys {
    var ctrl = false
    var shift = false
    /** A text field on the main window has the keyboard. */
    var typing = false
}

// ===================================================================== bookmarks / contents

@Composable
private fun BookmarksDialog(app: AppState, doc: DocState) {
    var tab by remember { mutableStateOf(if (doc.bookmarks.isEmpty() && doc.outline.isNotEmpty()) 1 else 0) }
    var renaming by remember { mutableStateOf(-1) }
    var newTitle by remember { mutableStateOf("") }
    val close = { app.showBookmarks = false }
    Sheet("Bookmarks & contents", close, width = 520.dp) {
        Row {
            Chip("Bookmarks (${doc.bookmarks.size})", tab == 0) { tab = 0 }
            Chip("Contents (${doc.outline.size})", tab == 1) { tab = 1 }
        }
        Box(Modifier.fillMaxWidth().height(380.dp)) {
            if (tab == 0) {
                if (doc.bookmarks.isEmpty()) Text("No bookmarks yet. Use the bookmark button on the top bar to mark the current page.",
                    color = Ui.SubText, fontFamily = Ui.font, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                LazyColumn {
                    itemsIndexed(doc.bookmarks.toList()) { i, b ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { app.goToPage(doc, b.page); close() }
                            .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (renaming == i) {
                                OutlinedTextField(newTitle, { newTitle = it }, Modifier.weight(1f), singleLine = true)
                                TextButton({
                                    if (newTitle.isNotBlank()) { b.title = newTitle.trim(); doc.bookmarks[i] = b; app.markDirty(doc) }
                                    renaming = -1
                                }) { Text("OK", fontFamily = Ui.font) }
                            } else {
                                Column(Modifier.weight(1f)) {
                                    Text(b.title, fontFamily = Ui.font, fontSize = 14.sp)
                                    Text("Page ${b.page + 1}", fontFamily = Ui.font, fontSize = 12.sp, color = Ui.SubText)
                                }
                                TextButton({ renaming = i; newTitle = b.title }) { Text("Rename", fontFamily = Ui.font, fontSize = 12.sp) }
                                ToolButton("ic_delete", "Delete") { doc.bookmarks.removeAt(i); app.markDirty(doc) }
                            }
                        }
                    }
                }
            } else {
                if (doc.outline.isEmpty()) Text("This file has no table of contents.", color = Ui.SubText, fontFamily = Ui.font, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                LazyColumn {
                    items(doc.outline) { e ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { app.goToPage(doc, e.page); close() }
                            .padding(start = (8 + e.depth * 16).dp, end = 8.dp, top = 7.dp, bottom = 7.dp)) {
                            Text(e.title, Modifier.weight(1f), fontFamily = Ui.font, fontSize = 14.sp)
                            Text("${e.page + 1}", fontFamily = Ui.font, fontSize = 12.sp, color = Ui.SubText)
                        }
                    }
                }
            }
        }
    }
}

// ===================================================================== view mode

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewModeDialog(app: AppState) {
    val v = app.view
    fun set(n: com.titipong.pdfwriter.ViewSettings) { app.view = n; n.save() }
    Sheet("View mode", { app.showViewMode = false }, width = 460.dp) {
        Section("Pages")
        FlowRow {
            Chip("Single", v.layout == PageLayout.SINGLE) { set(v.copy(layout = PageLayout.SINGLE)) }
            Chip("Double", v.layout == PageLayout.DOUBLE) { set(v.copy(layout = PageLayout.DOUBLE)) }
            Chip("Cover + double", v.layout == PageLayout.COVER) { set(v.copy(layout = PageLayout.COVER)) }
        }
        Section("Scrolling")
        FlowRow {
            Chip("Vertical", v.vertical) { set(v.copy(vertical = true)) }
            Chip("Horizontal (page by page)", !v.vertical) { set(v.copy(vertical = false)) }
        }
        Section("Colors")
        FlowRow {
            Chip("Day", v.colorMode == ColorMode.DAY) { set(v.copy(colorMode = ColorMode.DAY)) }
            Chip("Night", v.colorMode == ColorMode.NIGHT) { set(v.copy(colorMode = ColorMode.NIGHT)) }
            Chip("Sepia", v.colorMode == ColorMode.SEPIA) { set(v.copy(colorMode = ColorMode.SEPIA)) }
            Chip("Custom", v.colorMode == ColorMode.CUSTOM) { set(v.copy(colorMode = ColorMode.CUSTOM)) }
        }
        if (v.colorMode == ColorMode.CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Background", size = 13); Spacer(Modifier.width(6.dp))
                Swatch(v.customBg.toColor(), false, 20.dp) {
                    app.colorRequest = AppState.ColorRequest(v.customBg, hueOnly = true, light = true) { c -> set(app.view.copy(customBg = c)) }
                }
                Spacer(Modifier.width(16.dp))
                Label("Text", size = 13); Spacer(Modifier.width(6.dp))
                Swatch(v.customFg.toColor(), false, 20.dp) {
                    app.colorRequest = AppState.ColorRequest(v.customFg, hueOnly = true) { c -> set(app.view.copy(customFg = c)) }
                }
            }
        }
        Section("Rotate")
        FlowRow {
            for (r in listOf(0, 90, 180, 270)) Chip("$r°", v.rotation == r) { set(v.copy(rotation = r)) }
        }
        SwitchRow("Crop margins", "Hide the empty borders of the pages", v.crop) { set(v.copy(crop = it)) }
        SwitchRow("Reading mode", "Only the text, in a comfortable size", v.reading) { set(v.copy(reading = it)) }
        if (v.reading) {
            Section("Text size ${v.readingTextSize.toInt()}")
            Slider(v.readingTextSize, { set(app.view.copy(readingTextSize = it)) }, valueRange = 12f..36f)
        }
    }
}

// ===================================================================== settings / about

@Composable
private fun SettingsDialog(app: AppState) {
    Sheet("Settings", { app.showSettings = false }) {
        SwitchRow("Ink smoothing", "Smoother pen lines", app.inkSmoothing) { app.inkSmoothing = it; Prefs.put("set_ink_smoothing", it) }
        SwitchRow("Free highlighter smoothing", "Straighten highlighter lines drawn roughly straight", app.hlStraighten) {
            app.hlStraighten = it; Prefs.put("set_hl_smoothing", it)
        }
        SwitchRow("Pen pressure", "Line width follows pen pressure (or speed with a mouse)", app.penPressure) {
            app.penPressure = it; Prefs.put("pen_pressure", it)
        }
        SwitchRow("Pixel eraser", "Erase only the part under the eraser", app.eraserPixel) { app.eraserPixel = it; Prefs.put("eraser_pixel", it) }
        Spacer(Modifier.height(6.dp))
        Text("App data: ${AppDirs.data.absolutePath}", fontSize = 11.sp, color = Ui.SubText, fontFamily = Ui.font)
    }
}

@Composable
private fun AboutDialog(app: AppState) {
    Sheet("About", { app.showAbout = false }, width = 380.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource("images/app_icon.png"), null, Modifier.size(84.dp))
            Spacer(Modifier.height(8.dp))
            Text("PDFWriter", fontSize = 20.sp, fontFamily = Ui.font, fontWeight = FontWeight.SemiBold)
            Text("Version $APP_VERSION for Windows", fontSize = 13.sp, fontFamily = Ui.font, color = Ui.SubText)
            Spacer(Modifier.height(10.dp))
            Text("Read, write, highlight, OCR (Thai + English) and handwriting to text — all on your computer.",
                fontSize = 13.sp, fontFamily = Ui.font, color = Ui.Text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("by NorthXbar", fontSize = 13.sp, fontFamily = Ui.font, color = Ui.Primary)
        }
    }
}

// ===================================================================== new / export / OCR

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewPdfDialog(app: AppState) {
    var name by remember { mutableStateOf("Untitled") }
    var landscape by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(PageTemplate.BLANK) }
    val close = { app.showNew = false }
    Sheet("New PDF", close, width = 560.dp, buttons = {
        TextButton(close) { Text("Cancel", fontFamily = Ui.font) }
        Button({
            val n = name.trim().ifEmpty { "Untitled" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            Files.save("Save the new PDF", "$n.pdf", "pdf")?.let { app.createNew(it, landscape, template); close() }
        }) { Text("Create", fontFamily = Ui.font) }
    }) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name", fontFamily = Ui.font) })
        Section("Orientation")
        Row {
            Chip("Portrait", !landscape) { landscape = false }
            Chip("Landscape", landscape) { landscape = true }
        }
        Section("Paper")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (t in PageTemplate.values()) {
                val selected = t == template
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { template = t }.padding(bottom = 8.dp)) {
                    val wPt = if (landscape) 842f else 595f
                    val hPt = if (landscape) 595f else 842f
                    Canvas(
                        Modifier.width(if (landscape) 110.dp else 78.dp).height(if (landscape) 78.dp else 110.dp)
                            .border(if (selected) 2.dp else 1.dp, if (selected) Ui.Primary else Ui.BarLine)
                            .background(Color.White)
                    ) {
                        val k = size.width / wPt
                        val ds = this
                        PdfTemplates.draw(t, wPt, hPt, object : PdfTemplates.Painter {
                            override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: FloatArray, width: Float) {
                                ds.drawLine(Color(color[0], color[1], color[2]), Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = (width * k * 2f).coerceAtLeast(0.6f))
                            }
                            override fun dot(x: Float, y: Float, r: Float, color: FloatArray) {
                                ds.drawCircle(Color(color[0], color[1], color[2]), (r * k * 1.5f).coerceAtLeast(0.6f), Offset(x * k, y * k))
                            }
                        })
                    }
                    Spacer(Modifier.height(4.dp))
                    Label(t.name.lowercase().replaceFirstChar { it.uppercase() }, size = 12, color = if (selected) Ui.Primary else Ui.SubText)
                }
            }
        }
    }
}

@Composable
private fun ExportDialog(app: AppState, doc: DocState) {
    val close = { app.showExport = false }
    Sheet("Export", close) {
        ActionRow("Save a copy", "Annotations stay editable") {
            Files.save("Save a copy", "${doc.name}-copy.pdf", "pdf")?.let { app.exportCopy(it, flatten = false) }; close()
        }
        ActionRow("Save a flattened copy", "Annotations become part of the pages (can't be edited)") {
            Files.save("Save a flattened copy", "${doc.name}-flat.pdf", "pdf")?.let { app.exportCopy(it, flatten = true) }; close()
        }
        ActionRow("Extract pages…", "Some pages as a PDF or as pictures") { close(); app.extractPick = true; app.showPages = true }
        ActionRow("Print…") { close(); app.print() }
        Spacer(Modifier.height(4.dp))
        Text("Your changes are always saved into the original file automatically.", fontSize = 11.sp, color = Ui.SubText, fontFamily = Ui.font)
    }
}

@Composable
private fun OcrDialog(app: AppState) {
    val close = { app.showOcr = false }
    Sheet("OCR — make text searchable", close) {
        Text("Reads Thai and English text in scanned pages (on this computer, no internet) and adds an invisible text layer, so you can search, select and highlight it.",
            fontSize = 13.sp, fontFamily = Ui.font, color = Ui.SubText)
        Spacer(Modifier.height(8.dp))
        ActionRow("This page") { close(); app.runOcr(false) }
        ActionRow("All pages without text") { close(); app.runOcr(true) }
    }
}
