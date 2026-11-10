package com.titipong.pdfwriter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titipong.pdfwriter.AppState
import com.titipong.pdfwriter.DocState
import com.titipong.pdfwriter.Prefs
import com.titipong.pdfwriter.model.DrawTool
import kotlinx.coroutines.launch

const val APP_VERSION = "1.0"

@Composable
fun App(app: AppState) {
    val measurer = rememberTextMeasurer(512)
    val density = LocalDensity.current
    val nt = remember(measurer, density) { NoteText(measurer, density) }
    CompositionLocalProvider(LocalNoteText provides nt) {
        Column(Modifier.fillMaxSize().background(Ui.Workspace)) {
            val doc = app.current
            if (!app.chromeHidden) {
                TopBar(app, doc)
                if (doc != null) ToolRow(app, doc)
                if (doc != null && app.showSearch) SearchBar(app)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (doc != null) key(doc) { DocumentViewer(app, doc) } else EmptyView(app)
                if (app.chromeHidden) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC1F2937)).clickable { app.chromeHidden = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) { Label("Show toolbars (F11)", color = Color.White, size = 12) }
                }
                app.message?.let { msg ->
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).widthIn(max = 560.dp)
                            .shadow(6.dp, RoundedCornerShape(10.dp)).background(Color(0xFF1F2937), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) { Text(msg, color = Color.White, fontSize = 13.sp, fontFamily = Ui.font) }
                }
                app.progress?.let { p ->
                    Box(Modifier.fillMaxSize().background(Color(0x33000000)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
                        Row(
                            Modifier.shadow(8.dp, RoundedCornerShape(12.dp)).background(Color.White, RoundedCornerShape(12.dp)).padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(14.dp))
                            Label(p, size = 14)
                        }
                    }
                }
            }
        }
        Dialogs(app)
    }
}

// ===================================================================== top bar

@Composable
private fun TopBar(app: AppState, doc: DocState?) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(Ui.Bar).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource("images/logo.png"), "PDFWriter", Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        Label("PDFWriter", size = 15, bold = true)
        Spacer(Modifier.width(12.dp))
        // Tabs
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (d in app.docs) Tab(app, d, d === doc)
            ToolButton("ic_folder", "Open PDF (Ctrl+O)", size = 30.dp) { openFiles(app) }
        }
        if (doc != null) {
            val status = when {
                app.loadingPage >= 0 -> "Loading page ${app.loadingPage + 1}…"
                doc.saving -> "Saving…"
                doc.saveFailed -> "Not saved to the file"
                else -> "Saved"
            }
            Label(status, color = if (doc.saveFailed && app.loadingPage < 0) Ui.Danger else Ui.SubText, size = 12)
            Spacer(Modifier.width(10.dp))
            ModeMenu(app)
            Spacer(Modifier.width(4.dp))
            ToolButton("ic_search", "Search (Ctrl+F)", selected = app.showSearch) { app.showSearch = !app.showSearch }
            ToolButton(if (doc.isBookmarked(doc.currentPage)) "ic_bookmark_filled" else "ic_bookmark", "Bookmark this page") { app.toggleBookmark() }
            ToolButton("ic_list", "Bookmarks & contents") { app.showBookmarks = true }
            ToolButton("ic_pages", "Pages") { app.showPages = true }
            ToolButton("ic_view_mode", "View mode") { app.showViewMode = true }
            ToolButton("ic_ocr", "OCR (make text searchable)") { app.showOcr = true }
        }
        MoreMenu(app, doc)
    }
    HorizontalDivider(color = Ui.BarLine)
}

@Composable
private fun Tab(app: AppState, d: DocState, selected: Boolean) {
    Row(
        Modifier.padding(end = 4.dp).height(32.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Ui.Selected else Color(0xFFF3F4F6))
            .clickable { app.switchTo(d) }.padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Label(d.name, Modifier.widthIn(max = 180.dp), color = if (selected) Ui.Primary else Ui.Text, size = 13)
        Spacer(Modifier.width(2.dp))
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).clickable { app.close(d) }, contentAlignment = Alignment.Center) {
            Icon(icon("ic_close"), "Close", Modifier.size(14.dp), tint = Ui.SubText)
        }
    }
}

@Composable
private fun ModeMenu(app: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(30.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Ui.BarLine, RoundedCornerShape(8.dp))
                .clickable { open = true }.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label(if (app.annotate) "Annotate" else "View", size = 13)
            Icon(icon("ic_chevron_down"), null, Modifier.size(16.dp), tint = Ui.SubText)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("View", fontFamily = Ui.font) }, { open = false; app.setAnnotateMode(false) })
            DropdownMenuItem({ Text("Annotate", fontFamily = Ui.font) }, { open = false; app.setAnnotateMode(true) })
        }
    }
}

fun openFiles(app: AppState) {
    app.scope.launch {
        for (f in Files.openPdf()) app.open(f)
    }
}

@Composable
private fun MoreMenu(app: AppState, doc: DocState?) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton("ic_more_vert", "More") { open = true }
        DropdownMenu(open, { open = false }) {
            @Composable
            fun item(text: String, shortcut: String = "", enabled: Boolean = true, action: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(text, fontFamily = Ui.font, fontSize = 14.sp) },
                    trailingIcon = if (shortcut.isNotEmpty()) ({ Text(shortcut, fontFamily = Ui.font, fontSize = 12.sp, color = Ui.SubText) }) else null,
                    enabled = enabled,
                    onClick = { open = false; action() }
                )
            }
            item("New PDF…", "Ctrl+N") { app.showNew = true }
            item("Open…", "Ctrl+O") { openFiles(app) }
            HorizontalDivider()
            item("Save now", "Ctrl+S", doc != null) { doc?.let { d -> app.scope.launch { app.markDirty(d); app.saveNow(d) } } }
            item("Export / save a copy…", "", doc != null) { app.showExport = true }
            item("Extract pages…", "", doc != null) { app.extractPick = true; app.showPages = true }
            item("Print…", "Ctrl+P", doc != null) { app.print() }
            HorizontalDivider()
            item("OCR…", "", doc != null) { app.showOcr = true }
            item("View mode…", "", doc != null) { app.showViewMode = true }
            item(if (app.chromeHidden) "Show toolbars" else "Hide toolbars", "F11") { app.chromeHidden = !app.chromeHidden }
            HorizontalDivider()
            item("Settings…") { app.showSettings = true }
            item("About PDFWriter") { app.showAbout = true }
            if (doc != null) {
                HorizontalDivider()
                item("Close tab", "Ctrl+W") { app.close(doc) }
            }
        }
    }
}

// ===================================================================== tools

@Composable
private fun VDivider() = Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp).background(Ui.BarLine))

@Composable
private fun TextButtonSmall(text: String, tip: String = text, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).widthIn(min = 30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) { Label(text, size = 14) }
}

@Composable
private fun ToolRow(app: AppState, doc: DocState) {
    readState(app.paletteVersion)
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Ui.Bar).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (app.annotate) {
            val active = { t: DrawTool -> app.toolActive && app.tool == t }
            ToolButton("ic_pen", "Pen (P)", active(DrawTool.PEN)) { app.selectTool(DrawTool.PEN) }
            ToolButton("ic_highlight", "Highlighter (H)", active(DrawTool.HIGHLIGHTER)) { app.selectTool(DrawTool.HIGHLIGHTER) }
            ToolButton("ic_eraser", "Eraser (E) — the right mouse button also erases", active(DrawTool.ERASER)) { app.selectTool(DrawTool.ERASER) }
            ToolButton("ic_text", "Text (T)", active(DrawTool.TEXT)) { app.selectTool(DrawTool.TEXT) }
            ToolButton("ic_image", "Picture (I)", active(DrawTool.IMAGE)) { app.selectTool(DrawTool.IMAGE) }
            ToolButton("ic_lasso", "Lasso select (S)", active(DrawTool.SELECT)) { app.selectTool(DrawTool.SELECT) }
            VDivider()
            if (app.toolActive) {
                when (app.tool) {
                    DrawTool.PEN, DrawTool.HIGHLIGHTER, DrawTool.TEXT -> {
                        val ts = app.setting
                        ts.palette.forEachIndexed { i, c ->
                            Swatch(c.toColor(), i == ts.colorIndex) {
                                if (i == ts.colorIndex) app.editPaletteColor(i) else app.selectColor(i)
                            }
                        }
                        ToolButton("ic_palette", "Edit this color") { app.editPaletteColor(ts.colorIndex) }
                        VDivider()
                        SizeControl(app)
                    }
                    DrawTool.ERASER -> {
                        Chip("Stroke", !app.eraserPixel) { app.eraserPixel = false; Prefs.put("eraser_pixel", false) }
                        Chip("Pixel", app.eraserPixel) { app.eraserPixel = true; Prefs.put("eraser_pixel", true) }
                        VDivider()
                        SizeControl(app)
                    }
                    DrawTool.SELECT -> {
                        Label("Handwriting:", color = Ui.SubText, size = 12)
                        Spacer(Modifier.width(6.dp))
                        Chip("TH", app.hwLang == "th") { app.hwLang = "th"; Prefs.put("hw_lang", "th") }
                        Chip("EN", app.hwLang == "en") { app.hwLang = "en"; Prefs.put("hw_lang", "en") }
                    }
                    else -> {}
                }
            }
        } else {
            Label("Highlight color", color = Ui.SubText, size = 12)
            Spacer(Modifier.width(6.dp))
            app.markupPalette.forEachIndexed { i, c ->
                Swatch(c.toColor(), i == app.markupIndex) {
                    if (i == app.markupIndex) app.editMarkupColor(i) else { app.markupIndex = i; Prefs.put("markup_index", i) }
                }
            }
            ToolButton("ic_palette", "Edit this color") { app.editMarkupColor(app.markupIndex) }
            Spacer(Modifier.width(8.dp))
            Label("Select text with the mouse to copy, highlight or underline it", color = Ui.SubText, size = 12)
        }
        Spacer(Modifier.weight(1f))
        // Zoom
        TextButtonSmall("−", "Zoom out (Ctrl+−)") { ViewerControl.zoomBy(1f / 1.15f) }
        Box(Modifier.width(52.dp).clickable { doc.zoom = 1f }, contentAlignment = Alignment.Center) {
            Label("${Math.round(doc.zoom * 100)}%", size = 12)
        }
        TextButtonSmall("+", "Zoom in (Ctrl++)") { ViewerControl.zoomBy(1.15f) }
        Chip(if (app.fitWidth) "Fit width" else "Fit page", false) {
            app.fitWidth = !app.fitWidth; Prefs.put("fit_width", app.fitWidth); doc.zoom = 1f
        }
        VDivider()
        ToolButton("ic_undo", "Undo (Ctrl+Z)") { app.undo() }
    }
    HorizontalDivider(color = Ui.BarLine)
}

@Composable
private fun SizeControl(app: AppState) {
    val tenths = app.setting.sizeTenths
    TextButtonSmall("−") { app.setSize(if (tenths > 10) tenths - 5 else tenths - 1) }
    Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { Label(String.format("%.1f", tenths / 10f), size = 13) }
    TextButtonSmall("+") { app.setSize(if (tenths >= 10) tenths + 5 else tenths + 1) }
}

// ===================================================================== search

@Composable
private fun SearchBar(app: AppState) {
    var q by remember { mutableStateOf(app.searchQuery) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(Color(0xFFF9FAFB)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon("ic_search"), null, Modifier.size(18.dp), tint = Ui.SubText)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.width(360.dp).height(30.dp).background(Color.White, RoundedCornerShape(6.dp))
                .border(1.dp, Ui.BarLine, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                q, { q = it },
                Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { Keys.typing = it.isFocused }.onPreviewKeyEvent { e ->
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == Key.Enter) {
                        if (q == app.searchQuery && app.current?.hits?.isNotEmpty() == true) app.nextHit(if (e.isShiftPressed) -1 else 1)
                        else app.runSearch(q)
                        true
                    } else if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == Key.Escape) {
                        app.showSearch = false; true
                    } else false
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, fontFamily = Ui.font, color = Ui.Text),
                cursorBrush = SolidColor(Ui.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { app.runSearch(q) })
            )
            if (q.isEmpty()) Label("Search in this file", color = Ui.SubText, size = 13)
        }
        Spacer(Modifier.width(8.dp))
        ToolButton("ic_chevron_up", "Previous (Shift+Enter)") { app.nextHit(-1) }
        ToolButton("ic_chevron_down", "Next (Enter)") { app.nextHit(1) }
        Spacer(Modifier.width(8.dp))
        Label(app.searchInfo, color = Ui.SubText, size = 12)
        Spacer(Modifier.weight(1f))
        ToolButton("ic_close", "Close (Esc)") {
            app.showSearch = false
            app.current?.let { it.hits = emptyList(); it.activeHit = -1 }
            app.searchInfo = ""
        }
    }
    HorizontalDivider(color = Ui.BarLine)
}

// ===================================================================== no file open

@Composable
private fun EmptyView(app: AppState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource("images/app_icon.png"), null, Modifier.size(110.dp))
            Spacer(Modifier.height(12.dp))
            Text("PDFWriter", fontSize = 26.sp, fontFamily = Ui.font, color = Ui.Text)
            Spacer(Modifier.height(4.dp))
            Text("Read, write and highlight on PDF files", fontSize = 14.sp, fontFamily = Ui.font, color = Ui.SubText)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ openFiles(app) }) { Text("Open PDF", fontFamily = Ui.font) }
                OutlinedButton({ app.showNew = true }) { Text("New PDF", fontFamily = Ui.font) }
            }
            Spacer(Modifier.height(10.dp))
            Text("or drop PDF files here", fontSize = 12.sp, fontFamily = Ui.font, color = Ui.SubText)
            Spacer(Modifier.height(40.dp))
            Text("by NorthXbar · version $APP_VERSION", fontSize = 12.sp, fontFamily = Ui.font, color = Ui.SubText)
        }
    }
}
