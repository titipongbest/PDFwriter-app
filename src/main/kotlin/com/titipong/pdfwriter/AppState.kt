package com.titipong.pdfwriter

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.titipong.pdfwriter.compat.Color
import com.titipong.pdfwriter.compat.RectF
import com.titipong.pdfwriter.model.Bookmark
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.model.ImageNote
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.ItemGroup
import com.titipong.pdfwriter.model.OutlineEntry
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.TextHighlight
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.ocr.OcrEngine
import com.titipong.pdfwriter.pdf.PageTemplate
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import com.titipong.pdfwriter.pdf.PdfTemplates
import com.titipong.pdfwriter.render.PageRenderer
import com.titipong.pdfwriter.search.SearchIndex
import com.titipong.pdfwriter.storage.AnnotationStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PageLayout { SINGLE, DOUBLE, COVER }
enum class ColorMode { DAY, NIGHT, SEPIA, CUSTOM }

/** Display settings (View Mode dialog). Nothing here changes the PDF. */
data class ViewSettings(
    val layout: PageLayout = PageLayout.SINGLE,
    val vertical: Boolean = true,
    val colorMode: ColorMode = ColorMode.DAY,
    val customBg: Int = Color.rgb(250, 247, 240),
    val customFg: Int = Color.rgb(40, 40, 40),
    val rotation: Int = 0,
    val crop: Boolean = false,
    val reading: Boolean = false,
    val readingTextSize: Float = 18f
) {
    fun save() {
        Prefs.put("view_layout", layout.name); Prefs.put("view_vertical", vertical)
        Prefs.put("view_color", colorMode.name); Prefs.put("view_bg", customBg); Prefs.put("view_fg", customFg)
        Prefs.put("view_rotation", rotation); Prefs.put("view_crop", crop); Prefs.put("view_reading", reading)
        Prefs.put("view_text_size", readingTextSize)
    }

    companion object {
        fun load() = ViewSettings(
            layout = runCatching { PageLayout.valueOf(Prefs.getString("view_layout", "SINGLE")!!) }.getOrDefault(PageLayout.SINGLE),
            vertical = Prefs.getBoolean("view_vertical", true),
            colorMode = runCatching { ColorMode.valueOf(Prefs.getString("view_color", "DAY")!!) }.getOrDefault(ColorMode.DAY),
            customBg = Prefs.getInt("view_bg", Color.rgb(250, 247, 240)),
            customFg = Prefs.getInt("view_fg", Color.rgb(40, 40, 40)),
            rotation = Prefs.getInt("view_rotation", 0),
            crop = Prefs.getBoolean("view_crop", false),
            reading = Prefs.getBoolean("view_reading", false),
            readingTextSize = Prefs.getFloat("view_text_size", 18f)
        )
    }
}

class ToolSetting(val palette: MutableList<Int>, var colorIndex: Int, var sizeTenths: Int)

/** Text selected in View mode: characters [from]..[to] of [page] (SearchIndex positions). */
data class TextSelection(val page: Int, val from: Int, val to: Int)

/** Undo records (besides plain "item added"). */
class NoteEdit(val before: Any, val after: Any)
class GroupDelete(val items: List<Any>)
class ConvertEdit(val strokes: List<InkStroke>, val note: TextNote)
class EraseEdit(val removed: List<InkStroke>, val added: List<InkStroke>)

/** One open PDF (a tab). */
class DocState(val file: File, val key: String) {
    var base: File = File(AppDirs.docs, "$key.pdf")
    var renderer by mutableStateOf<PageRenderer?>(null)
    val pages = HashMap<Int, PageAnnotations>()
    val bookmarks = mutableStateListOf<Bookmark>()
    var outline by mutableStateOf<List<OutlineEntry>>(emptyList())
    /** Bumped when a page's annotations change (redraw). */
    val versions = mutableStateMapOf<Int, Int>()
    val history = ArrayDeque<Pair<Int, Any>>()

    var currentPage by mutableStateOf(0)
    var zoom by mutableStateOf(1f)
    val listState = LazyListState()
    val hScroll = ScrollState(0)
    val vScroll = ScrollState(0)

    var searchIndex: SearchIndex? = null
    var hits by mutableStateOf<List<SearchIndex.Hit>>(emptyList())
    var activeHit by mutableStateOf(-1)
    val crops = mutableStateMapOf<Int, RectF>()

    // Selections
    var textSel by mutableStateOf<TextSelection?>(null)
    var selectedHighlight by mutableStateOf<Pair<Int, TextHighlight>?>(null)
    /** The framed item (TextNote, ImageNote, InkStroke, ItemGroup) and its page. */
    var editing by mutableStateOf<Any?>(null)
    var editingPage by mutableStateOf(-1)

    // Gesture feedback (drawn on top of the page)
    var activeStroke by mutableStateOf<Pair<Int, InkStroke>?>(null)
    var activeVersion by mutableStateOf(0)
    var lasso by mutableStateOf<Pair<Int, List<Pair<Float, Float>>>?>(null)
    var guides by mutableStateOf<Triple<Int, List<Float>, List<Float>>?>(null)
    var eraserAt by mutableStateOf<Triple<Int, Float, Float>?>(null)

    /** The viewer scrolled to [currentPage] after opening (once). */
    var restored = false

    var dirty = false
    var saveJob: Job? = null
    var saving by mutableStateOf(false)
    var savedToPdf by mutableStateOf(true)
    var saveFailed by mutableStateOf(false)

    val name: String get() = file.nameWithoutExtension
    val pageCount: Int get() = renderer?.pageCount ?: 0

    fun annotationsFor(page: Int): PageAnnotations = pages.getOrPut(page) { PageAnnotations() }
    fun touch(page: Int) { versions[page] = (versions[page] ?: 0) + 1 }
    fun touchAll() { for (p in 0 until pageCount) touch(p) }
    fun isBookmarked(page: Int) = bookmarks.any { it.page == page }
}

class AppState(val scope: CoroutineScope) {

    val docs = mutableStateListOf<DocState>()
    var current by mutableStateOf<DocState?>(null)

    // ---------------------------------------------------------------- modes and tools

    var annotate by mutableStateOf(Prefs.getBoolean("annotate", true))
    var tool by mutableStateOf(runCatching { DrawTool.valueOf(Prefs.getString("tool", "PEN")!!) }.getOrDefault(DrawTool.PEN)
        .let { if (it == DrawTool.WRITE_TEXT) DrawTool.PEN else it })
    var toolActive by mutableStateOf(true)
    var paletteVersion by mutableStateOf(0)

    val toolSettings: Map<DrawTool, ToolSetting> = mapOf(
        DrawTool.PEN to ToolSetting(DEFAULT_COLORS.toMutableList(), 1, 30),
        DrawTool.HIGHLIGHTER to ToolSetting(HIGHLIGHTER_COLORS.toMutableList(), 0, 30),
        DrawTool.ERASER to ToolSetting(mutableListOf(), 0, 40),
        DrawTool.TEXT to ToolSetting(DEFAULT_COLORS.toMutableList(), 0, 30),
        DrawTool.IMAGE to ToolSetting(mutableListOf(), 0, 30),
        DrawTool.SELECT to ToolSetting(mutableListOf(), 0, 30)
    )
    val markupPalette = mutableStateListOf<Int>()
    var markupIndex by mutableStateOf(Prefs.getInt("markup_index", 0))

    var eraserPixel by mutableStateOf(Prefs.getBoolean("eraser_pixel", false))
    var penPressure by mutableStateOf(Prefs.getBoolean("pen_pressure", true))
    var inkSmoothing by mutableStateOf(Prefs.getBoolean("set_ink_smoothing", true))
    var hlStraighten by mutableStateOf(Prefs.getBoolean("set_hl_smoothing", true))
    var hwLang by mutableStateOf(Prefs.getString("hw_lang", "th") ?: "th")

    var view by mutableStateOf(ViewSettings.load())
    var fitWidth by mutableStateOf(Prefs.getBoolean("fit_width", false))

    // ---------------------------------------------------------------- UI state

    var chromeHidden by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var progress by mutableStateOf<String?>(null)
    var showPages by mutableStateOf(false)
    var showBookmarks by mutableStateOf(false)
    var showSearch by mutableStateOf(false)
    var showViewMode by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showAbout by mutableStateOf(false)
    var showNew by mutableStateOf(false)
    var showExport by mutableStateOf(false)
    var showOcr by mutableStateOf(false)
    var extractPick by mutableStateOf(false)
    var editNote by mutableStateOf<Pair<Int, TextNote>?>(null)
    var addTextAt by mutableStateOf<Triple<Int, Float, Float>?>(null)
    var colorRequest by mutableStateOf<ColorRequest?>(null)
    var searchQuery by mutableStateOf("")
    var searchInfo by mutableStateOf("")
    var loadingPage by mutableStateOf(-1)

    /** Something wants a color picked. */
    class ColorRequest(
        val initial: Int,
        val hueOnly: Boolean = false,
        val light: Boolean = false,
        val opacityDefault: Float? = null,
        val pressure: Boolean? = null,
        val onPressure: (Boolean) -> Unit = {},
        val onPicked: (Int) -> Unit
    )

    init {
        for ((t, ts) in toolSettings) {
            Prefs.getIntList("palette_${t.name}")?.let { saved ->
                if (saved.size >= ts.palette.size && ts.palette.isNotEmpty()) {
                    val n = ts.palette.size
                    ts.palette.clear(); ts.palette.addAll(saved.take(n))
                }
            }
            ts.colorIndex = Prefs.getInt("color_${t.name}", ts.colorIndex).coerceIn(0, (ts.palette.size - 1).coerceAtLeast(0))
            ts.sizeTenths = Prefs.getInt("size_${t.name}", ts.sizeTenths)
        }
        markupPalette.addAll(Prefs.getIntList("markup_palette")?.takeIf { it.size == MARKUP_COLORS.size } ?: MARKUP_COLORS)
    }

    val setting: ToolSetting get() = toolSettings.getValue(tool)
    val color: Int get() = setting.palette.getOrNull(setting.colorIndex) ?: Color.BLACK
    /** Stroke width, normalized to the page width (highlighter: 5x). */
    val strokeWidth: Float get() = widthFor(setting.sizeTenths)
    val eraserWidth: Float get() = widthFor(toolSettings.getValue(DrawTool.ERASER).sizeTenths)
    val markupColor: Int get() = markupPalette.getOrNull(markupIndex) ?: markupPalette.first()

    fun widthFor(tenths: Int) = 0.0003f + 0.0012f * (tenths / 10f)
    /** Text size (normalized) for a size value in tenths. */
    fun textSizeOf(tenths: Int) = 0.018f + 0.004f * (tenths / 10f)
    fun tenthsOfTextSize(size: Float) = Math.round((size - 0.018f) / 0.004f * 10f).coerceIn(1, 400)

    fun selectTool(t: DrawTool) {
        current?.let { clearSelection(it) }
        if (t == tool && toolActive) {
            toolActive = false // tapping the selected tool again leaves it
        } else {
            tool = t
            toolActive = true
        }
        Prefs.put("tool", tool.name)
        paletteVersion++
    }

    fun selectColor(index: Int) {
        setting.colorIndex = index
        Prefs.put("color_${tool.name}", index)
        paletteVersion++
    }

    fun setSize(tenths: Int) {
        setting.sizeTenths = tenths.coerceIn(1, 400)
        Prefs.put("size_${tool.name}", setting.sizeTenths)
        paletteVersion++
    }

    fun editPaletteColor(index: Int) {
        val ts = setting
        val pen = tool == DrawTool.PEN
        val hl = tool == DrawTool.HIGHLIGHTER
        colorRequest = ColorRequest(
            ts.palette[index], hueOnly = pen || hl, light = hl,
            opacityDefault = if (hl) com.titipong.pdfwriter.model.Opacity.INK_DEFAULT else null,
            pressure = if (pen) penPressure else null,
            onPressure = { penPressure = it; Prefs.put("pen_pressure", it) }
        ) { picked ->
            ts.palette[index] = picked
            Prefs.put("palette_${tool.name}", ts.palette.toList())
            selectColor(index)
        }
    }

    fun editMarkupColor(index: Int) {
        colorRequest = ColorRequest(
            markupPalette[index], hueOnly = true, light = true,
            opacityDefault = com.titipong.pdfwriter.model.Opacity.TEXT_DEFAULT
        ) { picked ->
            markupPalette[index] = picked
            Prefs.put("markup_palette", markupPalette.toList())
            markupIndex = index
            Prefs.put("markup_index", index)
        }
    }

    fun setAnnotateMode(on: Boolean) {
        annotate = on
        Prefs.put("annotate", on)
        current?.let { clearSelection(it) }
        if (on) chromeHidden = false
    }

    fun toast(msg: String) {
        message = msg
        scope.launch {
            delay(2600)
            if (message == msg) message = null
        }
    }

    // ---------------------------------------------------------------- opening / tabs

    fun restoreTabs() {
        val files = Prefs.getStringList("tabs").map { File(it) }.filter { it.exists() }
        val currentPath = Prefs.getString("current_tab")
        scope.launch {
            for (f in files) open(f, select = f.absolutePath == currentPath || files.size == 1)
            if (current == null) docs.firstOrNull()?.let { current = it }
        }
    }

    private fun saveTabs() {
        Prefs.put("tabs", docs.map { it.file.absolutePath })
        Prefs.put("current_tab", current?.file?.absolutePath)
    }

    fun switchTo(d: DocState) {
        current?.let { clearSelection(it) }
        current = d
        saveTabs()
    }

    suspend fun open(file: File, select: Boolean = true) {
        val path = file.absoluteFile
        docs.firstOrNull { it.file.absoluteFile == path }?.let { if (select) switchTo(it); return }
        val key = AnnotationStorage.keyFor(path.absolutePath)
        val doc = DocState(path, key)
        progress = "Opening ${path.name}…"
        val ok = try {
            withContext(Dispatchers.IO) { load(doc) }
            true
        } catch (e: Throwable) {
            false
        } finally {
            progress = null
        }
        if (!ok) {
            toast("Couldn't open ${path.name}")
            return
        }
        docs.add(doc)
        if (select || current == null) current = doc
        saveTabs()
    }

    private fun meta(f: File) = "${f.length()}:${f.lastModified()}"

    /** Reads a file: annotations (from the PDF, or the app's copy when it's up to date) and the renderer. */
    private fun load(doc: DocState) {
        val file = doc.file
        val side = AnnotationStorage.load(doc.key)
        val sameFile = Prefs.getString("meta_${doc.key}") == meta(file) && doc.base.exists()
        if (sameFile && side != null) {
            // Unchanged since we last saved it: the app's copy is complete (fast).
            doc.pages.putAll(side.pages)
            doc.bookmarks.addAll(side.bookmarks)
            doc.outline = AnnotationStorage.loadToc(doc.key)
        } else {
            file.copyTo(doc.base, overwrite = true)
            val r = PdfAnnotationIO.read(doc.base)
            if (side != null && !side.synced && sameFile) {
                doc.pages.putAll(side.pages)
                doc.bookmarks.addAll(side.bookmarks)
            } else {
                doc.pages.putAll(r.pages)
                doc.bookmarks.addAll(r.bookmarks)
            }
            doc.outline = r.outline
            AnnotationStorage.saveToc(doc.key, r.outline)
            Prefs.put("meta_${doc.key}", meta(file))
            AnnotationStorage.save(doc.key, AnnotationStorage.toJson(doc.pages, doc.bookmarks, synced = true), System.nanoTime())
        }
        doc.renderer = PageRenderer.open(doc.base, PageRenderer.diskCacheFor(AppDirs.cache, doc.key, doc.base))
        doc.currentPage = Prefs.getInt("page_${doc.key}", 0).coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))
        doc.searchIndex = SearchIndex(doc.base, doc.pageCount)
    }

    fun close(doc: DocState) {
        scope.launch {
            if (doc.dirty) saveNow(doc)
            doc.renderer?.close()
            doc.searchIndex?.close()
            val i = docs.indexOf(doc)
            docs.remove(doc)
            if (current === doc) current = docs.getOrNull(i.coerceAtMost(docs.size - 1))
            saveTabs()
        }
    }

    fun rememberPage(doc: DocState, page: Int) {
        if (page != doc.currentPage) {
            doc.currentPage = page
            Prefs.put("page_${doc.key}", page)
        }
        doc.renderer?.focus = page
    }

    // ---------------------------------------------------------------- saving

    fun markDirty(doc: DocState) {
        doc.dirty = true
        doc.saving = true
        doc.saveJob?.cancel()
        doc.saveJob = scope.launch {
            delay(1200)
            saveNow(doc)
        }
    }

    suspend fun saveNow(doc: DocState) {
        doc.saveJob?.cancel()
        if (!doc.dirty) return
        doc.dirty = false
        doc.saving = true
        val snapshot = HashMap<Int, PageAnnotations>()
        for ((k, v) in doc.pages) snapshot[k] = v.copy()
        val bms = doc.bookmarks.map { Bookmark(it.title, it.page) }
        val ok = withContext(Dispatchers.IO) {
            try {
                AnnotationStorage.save(doc.key, AnnotationStorage.toJson(snapshot, bms, synced = false), System.nanoTime())
            } catch (e: Exception) { }
            try {
                PdfAnnotationIO.write(doc.base, snapshot, bms, doc.file)
                AnnotationStorage.save(doc.key, AnnotationStorage.toJson(snapshot, bms, synced = true), System.nanoTime())
                Prefs.put("meta_${doc.key}", meta(doc.file))
                true
            } catch (e: Throwable) {
                false
            }
        }
        doc.saving = doc.dirty
        doc.savedToPdf = ok
        doc.saveFailed = !ok
    }

    fun saveAllBlocking() {
        kotlinx.coroutines.runBlocking { for (d in docs.toList()) if (d.dirty) saveNow(d) }
        Prefs.flush()
    }

    // ---------------------------------------------------------------- history / undo

    fun onItemAdded(doc: DocState, page: Int, item: Any) {
        doc.history.addLast(page to item)
        if (doc.history.size > MAX_HISTORY) doc.history.removeFirst()
        doc.touch(page)
        markDirty(doc)
    }

    fun record(doc: DocState, page: Int, entry: Any) {
        doc.history.addLast(page to entry)
        if (doc.history.size > MAX_HISTORY) doc.history.removeFirst()
    }

    fun undo() {
        val doc = current ?: return
        clearSelection(doc)
        while (doc.history.isNotEmpty()) {
            val (page, item) = doc.history.removeLast()
            val pa = doc.pages[page] ?: continue
            val done = when (item) {
                is InkStroke -> pa.strokes.remove(item)
                is TextNote -> pa.texts.remove(item)
                is ImageNote -> pa.images.remove(item)
                is TextHighlight -> pa.highlights.remove(item)
                is ConvertEdit -> { pa.texts.remove(item.note); pa.strokes.addAll(item.strokes); true }
                is EraseEdit -> { pa.strokes.removeAll(item.added.toSet()); pa.strokes.addAll(item.removed); true }
                is GroupDelete -> {
                    for (it in item.items) when (it) {
                        is TextNote -> pa.texts.add(it)
                        is ImageNote -> pa.images.add(it)
                        is InkStroke -> pa.strokes.add(it)
                    }
                    true
                }
                is NoteEdit -> replace(pa, item.after, item.before)
                else -> false
            }
            if (done) {
                doc.touch(page)
                markDirty(doc)
                return
            }
        }
    }

    /** Puts [new] where [old] is (also item by item for groups). */
    fun replace(pa: PageAnnotations, old: Any, new: Any): Boolean {
        if (old is ItemGroup && new is ItemGroup) {
            var any = false
            old.items.zip(new.items).forEach { (o, n) -> if (replace(pa, o, n)) any = true }
            return any
        }
        return when {
            old is TextNote && new is TextNote -> pa.texts.indexOf(old).takeIf { it >= 0 }?.let { pa.texts[it] = new; true } ?: false
            old is ImageNote && new is ImageNote -> pa.images.indexOf(old).takeIf { it >= 0 }?.let { pa.images[it] = new; true } ?: false
            old is InkStroke && new is InkStroke -> pa.strokes.indexOf(old).takeIf { it >= 0 }?.let { pa.strokes[it] = new; true } ?: false
            old is TextHighlight && new is TextHighlight -> pa.highlights.indexOf(old).takeIf { it >= 0 }?.let { pa.highlights[it] = new; true } ?: false
            else -> false
        }
    }

    // ---------------------------------------------------------------- selection actions

    fun clearSelection(doc: DocState) {
        doc.textSel = null
        doc.selectedHighlight = null
        doc.editing = null
        doc.editingPage = -1
    }

    fun select(doc: DocState, page: Int, item: Any?) {
        doc.textSel = null
        doc.selectedHighlight = null
        doc.editing = item
        doc.editingPage = if (item == null) -1 else page
    }

    /** The selected item(s) changed by a drag / resize / recolor: record and save. */
    fun itemChanged(doc: DocState, page: Int, before: Any, after: Any) {
        record(doc, page, NoteEdit(before, after))
        doc.editing = after
        doc.touch(page)
        markDirty(doc)
    }

    fun deleteSelected() {
        val doc = current ?: return
        doc.selectedHighlight?.let { (page, hl) ->
            doc.pages[page]?.highlights?.remove(hl)
            clearSelection(doc); doc.touch(page); markDirty(doc); return
        }
        val item = doc.editing ?: return
        val page = doc.editingPage
        val pa = doc.pages[page] ?: return
        val items = if (item is ItemGroup) item.items else listOf(item)
        for (it in items) when (it) {
            is TextNote -> pa.texts.remove(it)
            is ImageNote -> pa.images.remove(it)
            is InkStroke -> pa.strokes.remove(it)
        }
        record(doc, page, GroupDelete(items))
        clearSelection(doc)
        doc.touch(page)
        markDirty(doc)
    }

    /** Color for the selected item(s) / highlight. */
    fun recolorSelected() {
        val doc = current ?: return
        doc.selectedHighlight?.let { (page, hl) ->
            colorRequest = ColorRequest(hl.color, hueOnly = true, light = true,
                opacityDefault = com.titipong.pdfwriter.model.Opacity.TEXT_DEFAULT) { picked ->
                val pa = doc.pages[page] ?: return@ColorRequest
                val n = hl.withColor(picked)
                if (replace(pa, hl, n)) {
                    record(doc, page, NoteEdit(hl, n))
                    doc.selectedHighlight = page to n
                    doc.touch(page); markDirty(doc)
                }
            }
            return
        }
        val item = doc.editing ?: return
        val page = doc.editingPage
        val first = when (item) {
            is InkStroke -> item.color
            is TextNote -> item.color
            is ItemGroup -> item.items.firstNotNullOfOrNull { (it as? InkStroke)?.color ?: (it as? TextNote)?.color } ?: Color.BLACK
            else -> return
        }
        val hl = item is InkStroke && item.highlighter
        colorRequest = ColorRequest(first, hueOnly = hl, light = hl,
            opacityDefault = if (hl) com.titipong.pdfwriter.model.Opacity.INK_DEFAULT else null) { picked ->
            fun re(it: Any): Any = when (it) {
                is InkStroke -> InkStroke(it.points.toMutableList(), picked, it.width, it.highlighter)
                is TextNote -> it.copy(color = picked)
                is ItemGroup -> ItemGroup(it.items.map { x -> re(x) })
                else -> it
            }
            val pa = doc.pages[page] ?: return@ColorRequest
            val n = re(item)
            if (replace(pa, item, n)) itemChanged(doc, page, item, n)
        }
    }

    fun copySelection() {
        val doc = current ?: return
        val sel = doc.textSel ?: return
        val text = doc.searchIndex?.rangeText(sel.page, minOf(sel.from, sel.to), maxOf(sel.from, sel.to)) ?: return
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
        toast("Copied")
        clearSelection(doc)
    }

    /** Highlight / underline the selected text with the markup color. */
    fun markSelection(underline: Boolean) {
        val doc = current ?: return
        val sel = doc.textSel ?: return
        val idx = doc.searchIndex ?: return
        val a = minOf(sel.from, sel.to)
        val b = maxOf(sel.from, sel.to)
        val rects = idx.rangeRects(sel.page, a, b)
        if (rects.isEmpty()) return
        val hl = TextHighlight(rects, markupColor, idx.rangeText(sel.page, a, b), underline)
        doc.annotationsFor(sel.page).highlights.add(hl)
        clearSelection(doc)
        onItemAdded(doc, sel.page, hl)
    }

    // ---------------------------------------------------------------- notes, pictures

    fun addText(doc: DocState, page: Int, x: Float, y: Float, text: String) {
        if (text.isBlank()) return
        val note = TextNote(text, x, y, color, textSizeOf(setting.sizeTenths))
        doc.annotationsFor(page).texts.add(note)
        onItemAdded(doc, page, note)
    }

    fun applyNoteEdit(doc: DocState, page: Int, note: TextNote, text: String, color: Int, sizeTenths: Int, align: Int) {
        val pa = doc.pages[page] ?: return
        val newSize = textSizeOf(sizeTenths)
        val width = if (note.width > 0f) note.width * newSize / note.size else 0f
        val edited = note.copy(text = text, color = color, size = newSize, width = width, align = align)
        if (replace(pa, note, edited)) {
            record(doc, page, NoteEdit(note, edited))
            if (doc.editing === note) doc.editing = edited
            doc.touch(page)
            markDirty(doc)
        }
    }

    /** Copies a picture into the app (upright, at most 2000 px) and places it centered on (x, y). */
    fun placeImage(doc: DocState, page: Int, x: Float, y: Float, source: File) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    var img = javax.imageio.ImageIO.read(source) ?: return@withContext null
                    val big = maxOf(img.width, img.height)
                    if (big > 2000) {
                        val f = 2000.0 / big
                        val w = (img.width * f).toInt(); val h = (img.height * f).toInt()
                        val t = java.awt.image.BufferedImage(w, h, if (img.colorModel.hasAlpha()) java.awt.image.BufferedImage.TYPE_INT_ARGB else java.awt.image.BufferedImage.TYPE_INT_RGB)
                        t.createGraphics().apply {
                            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                            drawImage(img, 0, 0, w, h, null); dispose()
                        }
                        img = t
                    }
                    val alpha = img.colorModel.hasAlpha()
                    val f = File(AppDirs.images, java.util.UUID.randomUUID().toString() + if (alpha) ".png" else ".jpg")
                    val out = if (alpha) img else java.awt.image.BufferedImage(img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_RGB).also {
                        it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
                    }
                    javax.imageio.ImageIO.write(out, if (alpha) "png" else "jpg", f)
                    Triple(f, img.width, img.height)
                } catch (e: Throwable) {
                    null
                }
            }
            if (saved == null) { toast("Couldn't add the picture"); return@launch }
            val (file, iw, ih) = saved
            val aspect = doc.renderer?.aspects?.getOrNull(page) ?: 1.414f
            var w = 0.4f
            var h = w * ih / iw.coerceAtLeast(1)
            if (h > aspect * 0.8f) { h = aspect * 0.8f; w = h * iw / ih.coerceAtLeast(1) }
            val left = (x - w / 2f).coerceIn(0f, (1f - w).coerceAtLeast(0f))
            val top = (y - h / 2f).coerceIn(0f, (aspect - h).coerceAtLeast(0f))
            val note = ImageNote(file.path, left, top, w, h)
            doc.annotationsFor(page).images.add(note)
            onItemAdded(doc, page, note)
            select(doc, page, note)
        }
    }

    // ---------------------------------------------------------------- bookmarks

    fun toggleBookmark() {
        val doc = current ?: return
        val p = doc.currentPage
        val existing = doc.bookmarks.firstOrNull { it.page == p }
        if (existing != null) doc.bookmarks.remove(existing) else doc.bookmarks.add(Bookmark("Page ${p + 1}", p))
        doc.bookmarks.sortBy { it.page }
        markDirty(doc)
    }

    // ---------------------------------------------------------------- search

    fun runSearch(query: String) {
        val doc = current ?: return
        val idx = doc.searchIndex ?: return
        searchQuery = query
        if (query.isBlank()) { doc.hits = emptyList(); doc.activeHit = -1; searchInfo = ""; return }
        scope.launch {
            if (!idx.allReady) {
                idx.ensureAll { done, total -> searchInfo = "Reading text $done/$total…" }
            }
            val hits = withContext(Dispatchers.Default) { idx.find(query) }
            doc.hits = hits
            doc.activeHit = if (hits.isEmpty()) -1 else 0
            searchInfo = if (hits.isEmpty()) (if (idx.isEmpty) "No text – try OCR" else "No results") else "1/${hits.size}"
            if (hits.isNotEmpty()) goToPage(doc, hits[0].page)
        }
    }

    fun nextHit(step: Int) {
        val doc = current ?: return
        if (doc.hits.isEmpty()) return
        doc.activeHit = (doc.activeHit + step + doc.hits.size) % doc.hits.size
        searchInfo = "${doc.activeHit + 1}/${doc.hits.size}"
        goToPage(doc, doc.hits[doc.activeHit].page)
    }

    /** Set by the viewer: scrolls to a page. */
    var scrollRequest by mutableStateOf<Pair<DocState, Int>?>(null)

    fun goToPage(doc: DocState, page: Int) {
        if (page !in 0 until doc.pageCount) return
        rememberPage(doc, page)
        scrollRequest = doc to page
    }

    // ---------------------------------------------------------------- page edits, OCR (new base file)

    /**
     * Applies a new page order ([order]: old page index, or null for a blank page) to the file:
     * rebuilds it, writes the annotations in their new places, and reopens it.
     */
    fun applyPageOrder(doc: DocState, order: List<Int?>) {
        scope.launch {
            saveNow(doc)
            val newPages = HashMap<Int, PageAnnotations>()
            order.forEachIndexed { j, src -> if (src != null) doc.pages[src]?.let { newPages[j] = it.copy() } }
            val newBookmarks = doc.bookmarks.mapNotNull { b -> order.indexOf(b.page).takeIf { it >= 0 }?.let { Bookmark(b.title, it) } }
            swapBase(doc, "Updating pages…", newPages, newBookmarks) { src, out -> PdfAnnotationIO.rebuildPages(src, order, out) }
        }
    }

    private suspend fun swapBase(
        doc: DocState, message: String,
        newPages: Map<Int, PageAnnotations>, newBookmarks: List<Bookmark>,
        transform: suspend (File, File) -> Unit
    ) {
        progress = message
        try {
            val rebuilt = File(AppDirs.docs, "${doc.key}.edit.pdf")
            val ok = try {
                transform(doc.base, rebuilt)
                PdfAnnotationIO.write(rebuilt, newPages, newBookmarks, doc.file)
                true
            } catch (e: Throwable) {
                false
            }
            if (!ok) { rebuilt.delete(); toast("Couldn't update the file"); return }
            withContext(Dispatchers.IO) {
                doc.renderer?.close()
                doc.searchIndex?.close()
                val finalBase = File(AppDirs.docs, "${doc.key}.pdf")
                finalBase.delete()
                rebuilt.renameTo(finalBase)
                doc.base = finalBase
                doc.pages.clear(); doc.pages.putAll(newPages)
                val r = try { PdfAnnotationIO.read(finalBase).outline } catch (e: Throwable) { emptyList() }
                AnnotationStorage.saveToc(doc.key, r)
                AnnotationStorage.save(doc.key, AnnotationStorage.toJson(newPages, newBookmarks, synced = true), System.nanoTime())
                Prefs.put("meta_${doc.key}", meta(doc.file))
                val renderer = PageRenderer.open(finalBase, PageRenderer.diskCacheFor(AppDirs.cache, doc.key, finalBase))
                withContext(Dispatchers.Main) {
                    doc.outline = r
                    doc.bookmarks.clear(); doc.bookmarks.addAll(newBookmarks)
                    doc.history.clear()
                    doc.hits = emptyList()
                    doc.crops.clear()
                    clearSelection(doc)
                    doc.searchIndex = SearchIndex(finalBase, renderer.pageCount)
                    doc.renderer = renderer
                    doc.currentPage = doc.currentPage.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    doc.touchAll()
                }
            }
        } finally {
            progress = null
        }
    }

    /** OCR: [allMissing] = every page without text, else the current page only. */
    fun runOcr(allMissing: Boolean) {
        val doc = current ?: return
        val renderer = doc.renderer ?: return
        scope.launch {
            val targets: List<Int> = if (allMissing) {
                progress = "Checking pages…"
                val texts = withContext(Dispatchers.IO) { try { PdfAnnotationIO.extractText(doc.base) } catch (e: Throwable) { emptyList() } }
                (0 until doc.pageCount).filter { texts.getOrNull(it).isNullOrBlank() }
            } else listOf(doc.currentPage)
            if (targets.isEmpty()) { progress = null; toast("Every page already has text"); return@launch }
            val words = HashMap<Int, List<OcrEngine.Word>>()
            try {
                val engine = withContext(Dispatchers.IO) { OcrEngine() }
                try {
                    targets.forEachIndexed { i, p ->
                        progress = "OCR page ${p + 1} (${i + 1}/${targets.size})…"
                        val img = renderer.renderUncached(p, OcrEngine.OCR_WIDTH) ?: return@forEachIndexed
                        words[p] = withContext(Dispatchers.IO) { engine.recognize(img) }
                    }
                } finally {
                    withContext(Dispatchers.IO) { engine.close() }
                }
            } catch (e: Throwable) {
                progress = null
                toast("OCR failed: ${e.message}")
                return@launch
            }
            saveNow(doc)
            val pagesNow = HashMap<Int, PageAnnotations>().also { m -> doc.pages.forEach { (k, v) -> m[k] = v.copy() } }
            swapBase(doc, "Adding text…", pagesNow, doc.bookmarks.map { Bookmark(it.title, it.page) }) { src, out ->
                PdfAnnotationIO.addTextLayer(src, words, out)
            }
            toast("OCR done: ${words.values.sumOf { it.size }} words")
        }
    }

    // ---------------------------------------------------------------- new / export / print

    fun createNew(dest: File, landscape: Boolean, template: PageTemplate) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { PdfTemplates.create(dest, template, landscape); true } catch (e: Throwable) { false }
            }
            if (ok) open(dest) else toast("Couldn't create the PDF")
        }
    }

    fun exportCopy(dest: File, flatten: Boolean) {
        val doc = current ?: return
        scope.launch {
            saveNow(doc)
            progress = "Exporting…"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(AppDirs.cache, "export.pdf")
                    PdfAnnotationIO.writeFile(doc.base, doc.pages, doc.bookmarks.toList(), tmp, flatten = flatten)
                    tmp.copyTo(dest, overwrite = true); tmp.delete(); true
                } catch (e: Throwable) { false }
            }
            progress = null
            toast(if (ok) "Saved ${dest.name}" else "Export failed")
        }
    }

    /** Selected pages as one PDF, or as images (PNG / JPG) in [dest] folder. */
    fun extractPages(pagesSel: List<Int>, dest: File, asImages: String?) {
        val doc = current ?: return
        scope.launch {
            saveNow(doc)
            progress = "Extracting…"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val part = File(AppDirs.cache, "extract-part.pdf")
                    PdfAnnotationIO.rebuildPages(doc.base, pagesSel, part)
                    val newPages = HashMap<Int, PageAnnotations>()
                    pagesSel.forEachIndexed { j, src -> doc.pages[src]?.let { newPages[j] = it.copy() } }
                    if (asImages == null) {
                        PdfAnnotationIO.writeFile(part, newPages, emptyList(), dest, dropFileOutline = true)
                    } else {
                        val flat = File(AppDirs.cache, "extract-flat.pdf")
                        PdfAnnotationIO.writeFile(part, newPages, emptyList(), flat, flatten = true)
                        org.apache.pdfbox.pdmodel.PDDocument.load(flat).use { d ->
                            val r = org.apache.pdfbox.rendering.PDFRenderer(d)
                            dest.mkdirs()
                            for (i in 0 until d.numberOfPages) {
                                val img = r.renderImageWithDPI(i, 200f, org.apache.pdfbox.rendering.ImageType.RGB)
                                javax.imageio.ImageIO.write(img, asImages, File(dest, "${doc.name}-p${pagesSel[i] + 1}.$asImages"))
                            }
                        }
                        flat.delete()
                    }
                    part.delete()
                    true
                } catch (e: Throwable) { false }
            }
            progress = null
            toast(if (ok) "Extracted ${pagesSel.size} page(s)" else "Extract failed")
        }
    }

    fun print() {
        val doc = current ?: return
        scope.launch {
            saveNow(doc)
            progress = "Preparing to print…"
            val flat = File(AppDirs.cache, "print.pdf")
            val ok = withContext(Dispatchers.IO) {
                try { PdfAnnotationIO.writeFile(doc.base, doc.pages, emptyList(), flat, flatten = true); true } catch (e: Throwable) { false }
            }
            progress = null
            if (!ok) { toast("Couldn't print"); return@launch }
            withContext(Dispatchers.IO) {
                try {
                    org.apache.pdfbox.pdmodel.PDDocument.load(flat).use { d ->
                        val job = java.awt.print.PrinterJob.getPrinterJob()
                        job.setJobName(doc.name)
                        job.setPageable(org.apache.pdfbox.printing.PDFPageable(d))
                        if (job.printDialog()) job.print()
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) { toast("Couldn't print: ${e.message}") }
                }
            }
        }
    }

    // ---------------------------------------------------------------- handwriting -> text

    /** Lasso "To text": recognizes the pen ink in the selection and replaces it with a text box. */
    fun convertSelectionToText() {
        val doc = current ?: return
        val group = doc.editing as? ItemGroup ?: (doc.editing as? InkStroke)?.let { ItemGroup(listOf(it)) } ?: return
        val page = doc.editingPage
        val ink = group.items.filterIsInstance<InkStroke>().filter { !it.highlighter }
        if (ink.isEmpty()) return
        clearSelection(doc)
        scope.launch {
            progress = "Reading handwriting…"
            val lines = try {
                withContext(Dispatchers.IO) { Handwriting.recognize(ink, hwLang) }
            } catch (e: Throwable) {
                progress = null
                toast(e.message ?: "Couldn't read the handwriting")
                return@launch
            }
            progress = null
            if (lines.isEmpty()) { toast("Couldn't read the handwriting"); return@launch }
            val first = lines.first().box
            val size = (lines.map { it.box.height() }.average().toFloat() * 0.7f).coerceIn(0.008f, 0.2f)
            val note = TextNote(lines.joinToString("\n") { it.text }, lines.minOf { it.box.left }, first.top + size, ink.first().color, size)
            val pa = doc.annotationsFor(page)
            pa.strokes.removeAll(ink.toSet())
            pa.texts.add(note)
            record(doc, page, ConvertEdit(ink, note))
            doc.touch(page)
            markDirty(doc)
        }
    }

    companion object {
        const val MAX_HISTORY = 200
        val DEFAULT_COLORS = listOf(
            Color.parseColor("#111827"), Color.parseColor("#2563EB"), Color.parseColor("#DC2626"),
            Color.parseColor("#16A34A"), Color.parseColor("#F59E0B")
        )
        val HIGHLIGHTER_COLORS = listOf(
            Color.parseColor("#FACC15"), Color.parseColor("#4ADE80"), Color.parseColor("#60A5FA"),
            Color.parseColor("#F472B6"), Color.parseColor("#FB923C")
        )
        val MARKUP_COLORS = listOf(
            Color.parseColor("#FDE047"), Color.parseColor("#86EFAC"), Color.parseColor("#93C5FD"),
            Color.parseColor("#F9A8D4"), Color.parseColor("#FDBA74")
        )
    }
}
