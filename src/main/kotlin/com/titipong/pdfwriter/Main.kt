package com.titipong.pdfwriter

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import com.titipong.pdfwriter.ui.App
import com.titipong.pdfwriter.ui.Keys
import com.titipong.pdfwriter.ui.Ui
import com.titipong.pdfwriter.ui.ViewerControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

private fun prepareFiles() {
    try { com.titipong.pdfwriter.pdf.QuickFonts.install() } catch (e: Throwable) { }
    PdfAnnotationIO.imagesDir = AppDirs.images
    try {
        val font = File(AppDirs.data, "fonts/NotoSansThai-Regular.ttf")
        if (!font.exists()) {
            font.parentFile.mkdirs()
            Thread.currentThread().contextClassLoader.getResourceAsStream("fonts/NotoSansThai-Regular.ttf")?.use { input ->
                font.outputStream().use { input.copyTo(it) }
            }
        }
        if (font.exists()) PdfAnnotationIO.bundledThaiFont = font
    } catch (e: Throwable) { }
}

fun main(args: Array<String>) {
    System.setProperty("sun.java2d.uiScale.enabled", "true")
    prepareFiles()
    if (args.size >= 4 && args[0] == "--flowtest") {
        SelfTest.flow(args[1], File(args[2]), File(args[3]))
        Prefs.flush()
        kotlin.system.exitProcess(0)
    }
    if (args.size >= 2 && args[0] == "--selftest") {
        SelfTest.run(File(args[1]))
        kotlin.system.exitProcess(0)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val app = AppState(scope)
    if ("--view" in args) app.setAnnotateMode(false)
    val startFiles = args.map { File(it) }.filter { it.isFile && it.name.lowercase().endsWith(".pdf") }
    app.restoreTabs(startFiles)

    application {
        val state = rememberWindowState(placement = WindowPlacement.Maximized, width = 1280.dp, height = 860.dp)
        Window(
            onCloseRequest = { app.saveAllBlocking(); exitApplication() },
            title = app.current?.let { "${it.file.name} — PDFWriter" } ?: "PDFWriter",
            icon = painterResource("images/app_icon.png"),
            state = state,
            onPreviewKeyEvent = { e ->
                Keys.ctrl = e.isCtrlPressed
                Keys.shift = e.isShiftPressed
                false
            },
            onKeyEvent = { e -> handleKey(app, e) }
        ) {
            LaunchedEffect(Unit) {
                Ui.window = window
                window.minimumSize = java.awt.Dimension(720, 480)
                val target = DropTarget()
                target.addDropTargetListener(object : DropTargetAdapter() {
                    override fun drop(e: DropTargetDropEvent) {
                        try {
                            e.acceptDrop(DnDConstants.ACTION_COPY)
                            val list = e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: emptyList<Any>()
                            val pdfs = list.filterIsInstance<File>().filter { it.name.lowercase().endsWith(".pdf") }
                            scope.launch { for (f in pdfs) app.open(f) }
                            e.dropComplete(true)
                        } catch (t: Throwable) {
                            e.dropComplete(false)
                        }
                    }
                })
                try { window.contentPane.dropTarget = target } catch (t: Throwable) { }
            }
            MaterialTheme(colorScheme = Ui.colors) { App(app) }
        }
    }
}

private fun handleKey(app: AppState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val doc = app.current
    val ctrl = e.isCtrlPressed
    if (Keys.typing && !(ctrl && e.key in setOf(Key.O, Key.N, Key.P, Key.W, Key.Tab, Key.S))) return false
    if (ctrl) {
        when (e.key) {
            Key.O -> { com.titipong.pdfwriter.ui.openFiles(app); return true }
            Key.N -> { app.showNew = true; return true }
            Key.S -> { doc?.let { d -> app.scope.launch { app.markDirty(d); app.saveNow(d) } }; return true }
            Key.P -> { app.print(); return true }
            Key.F -> { if (doc != null) app.showSearch = true; return true }
            Key.W -> { doc?.let { app.close(it) }; return true }
            Key.Z -> { app.undo(); return true }
            Key.C -> { app.copySelection(); return true }
            Key.Tab -> {
                if (app.docs.size > 1 && doc != null) {
                    val i = app.docs.indexOf(doc)
                    app.switchTo(app.docs[(i + if (e.isShiftPressed) app.docs.size - 1 else 1) % app.docs.size])
                }
                return true
            }
            Key.Equals, Key.Plus, Key.NumPadAdd -> { ViewerControl.zoomBy(1.15f); return true }
            Key.Minus, Key.NumPadSubtract -> { ViewerControl.zoomBy(1f / 1.15f); return true }
            Key.Zero, Key.NumPad0 -> { doc?.zoom = 1f; return true }
            else -> return false
        }
    }
    when (e.key) {
        Key.F11 -> { app.chromeHidden = !app.chromeHidden; return true }
        Key.Escape -> {
            if (doc != null) app.clearSelection(doc)
            if (app.chromeHidden) app.chromeHidden = false
            return true
        }
        Key.Delete, Key.Backspace -> { app.deleteSelected(); return true }
        Key.PageDown, Key.DirectionRight -> { doc?.let { app.goToPage(it, it.currentPage + 1) }; return true }
        Key.PageUp, Key.DirectionLeft -> { doc?.let { app.goToPage(it, it.currentPage - 1) }; return true }
        Key.MoveHome -> { doc?.let { app.goToPage(it, 0) }; return true }
        Key.MoveEnd -> { doc?.let { app.goToPage(it, it.pageCount - 1) }; return true }
        Key.DirectionDown -> { ViewerControl.scrollBy(80f); return true }
        Key.DirectionUp -> { ViewerControl.scrollBy(-80f); return true }
    }
    if (doc != null && app.annotate) {
        val t = when (e.key) {
            Key.P -> DrawTool.PEN
            Key.H -> DrawTool.HIGHLIGHTER
            Key.E -> DrawTool.ERASER
            Key.T -> DrawTool.TEXT
            Key.I -> DrawTool.IMAGE
            Key.S -> DrawTool.SELECT
            else -> null
        }
        if (t != null) {
            if (!(app.tool == t && app.toolActive)) app.selectTool(t)
            return true
        }
    }
    return false
}
