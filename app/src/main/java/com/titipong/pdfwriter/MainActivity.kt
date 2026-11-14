package com.titipong.pdfwriter

import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.titipong.pdfwriter.databinding.ActivityMainBinding
import com.titipong.pdfwriter.export.PageExporter
import com.titipong.pdfwriter.model.Bookmark
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.OutlineEntry
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.TextHighlight
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.model.ImageNote
import com.titipong.pdfwriter.ocr.OcrEngine
import com.titipong.pdfwriter.search.SearchIndex
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import com.titipong.pdfwriter.pdf.PdfDocumentRenderer
import com.titipong.pdfwriter.storage.AnnotationStorage
import com.titipong.pdfwriter.ui.BookmarkAdapter
import com.titipong.pdfwriter.ui.ColorPicker
import com.titipong.pdfwriter.ui.PageAdapter
import com.titipong.pdfwriter.ui.PageGridAdapter
import com.titipong.pdfwriter.ui.PdfPageView
import com.titipong.pdfwriter.ui.ReadingAdapter
import com.titipong.pdfwriter.ui.SettingsDialog
import com.titipong.pdfwriter.ui.ViewConfig
import com.titipong.pdfwriter.ui.ViewModeDialog
import com.titipong.pdfwriter.ui.ViewSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

class MainActivity : AppCompatActivity(), PdfPageView.Host {

    private lateinit var binding: ActivityMainBinding

    // Tools
    private var currentTool = DrawTool.PEN

    /** Each tool remembers its own colors, chosen color and size. Size in tenths: 1..100 = 0.1..10.0 */
    private class ToolSetting(val palette: MutableList<Int>, var colorIndex: Int, var sizeTenths: Int)

    private val toolSettings = mapOf(
        DrawTool.PEN to ToolSetting(DEFAULT_COLORS.toMutableList(), 1, 30),
        DrawTool.HIGHLIGHTER to ToolSetting(HIGHLIGHTER_INK_COLORS.toMutableList(), 0, 30),
        DrawTool.TEXT to ToolSetting(DEFAULT_COLORS.toMutableList(), 0, 30),
        DrawTool.ERASER to ToolSetting(mutableListOf(), 0, 40),
        DrawTool.IMAGE to ToolSetting(mutableListOf(), 0, 30),
        DrawTool.SELECT to ToolSetting(mutableListOf(), 0, 30),
        DrawTool.WRITE_TEXT to ToolSetting(DEFAULT_COLORS.toMutableList(), 0, 25)
    )
    private val cur: ToolSetting get() = toolSettings.getValue(currentTool)
    private val palette: MutableList<Int> get() = cur.palette
    private var colorIndex: Int
        get() = cur.colorIndex
        set(value) { cur.colorIndex = value }
    private var sizeTenths: Int
        get() = cur.sizeTenths
        set(value) { cur.sizeTenths = value }
    private val sizeValue: Float get() = sizeTenths / 10f

    private fun widthFor(tenths: Int) = 0.0003f + 0.0012f * (tenths / 10f)

    override val tool: DrawTool get() = currentTool
    /** Tapping the selected tool again puts it away (no tool: the S Pen scrolls). */
    private var toolOn = true
    override val toolActive: Boolean get() = toolOn
    private var eraserPixel = false
    override val inkSmoothing: Boolean get() = SettingsDialog.isOn(prefs, SettingsDialog.INK_SMOOTHING)
    override val highlighterStraighten: Boolean
        get() = SettingsDialog.isOn(prefs, SettingsDialog.HIGHLIGHTER_STRAIGHTEN)
    override val fingerDraw: Boolean get() = SettingsDialog.isOn(prefs, SettingsDialog.FINGER_DRAW)
    override val eraserPixelMode: Boolean get() = eraserPixel
    override val penPressure: Boolean get() = prefs.getBoolean(PREF_PEN_PRESSURE, true)
    private var tappedNote: Pair<Int, TextNote>? = null
    override val color: Int get() = palette.getOrNull(colorIndex) ?: Color.BLACK
    override val strokeWidth: Float get() = widthFor(sizeTenths)
    override val eraserWidth: Float get() = widthFor(toolSettings.getValue(DrawTool.ERASER).sizeTenths)

    // Current document
    private var document: PdfDocumentRenderer? = null
    private var adapter: PageAdapter? = null
    private var docUri: Uri? = null
    private var docName: String = ""
    private var docKey: String? = null
    /** Private copy we render from and build saves from, so overwriting the original is safe. */
    private var localCopy: File? = null
    /** True when the ink can be written into the original PDF file. */
    private var pdfWritable = false
    /** Password of the currently open encrypted PDF (so auto-saves preserve the lock). */
    private var docPassword: String? = null
    /** Session cache of passwords for open encrypted tabs, keyed by [docKey]. */
    private val tabPasswords = HashMap<String, String>()
    private var pages: MutableMap<Int, PageAnnotations> = mutableMapOf()
    private var bookmarks: MutableList<Bookmark> = mutableListOf()
    private var outline: List<OutlineEntry> = emptyList()
    private val history = ArrayDeque<Pair<Int, Any>>()

    // Saving: an in-app copy right away, the PDF file itself a moment later.
    private var dirty = false
    /**
     * Order of in-app saves. Kept for the whole app process (not per screen): after leaving with
     * Back and coming back, a new screen must not restart at 0, or every save would be skipped as
     * "older than the last one written".
     */
    private var saveSeq: Long
        get() = SaveSeq.value
        set(v) { SaveSeq.value = v }
    private var saveJob: Job? = null
    private var changeVersion = 0L
    private var pdfSavedVersion = 0L
    private var pdfJob: Job? = null

    // Page overview
    private var gridAdapter: PageGridAdapter? = null
    private var bookmarkAdapter: BookmarkAdapter? = null
    /** A change to the document itself (page layout, OCR text layer) waiting for a "save as" target. */
    private class BaseChange(
        val transform: suspend (File, File) -> Unit,
        val newPages: MutableMap<Int, PageAnnotations>,
        val newBookmarks: MutableList<Bookmark>,
        val okMessage: Int,
        val failMessage: Int,
        val progressMessage: String
    )
    private var pendingChange: BaseChange? = null
    /** While pages are being rearranged, no other PDF write may run (it would restore the old layout). */
    private var applyingPages = false

    // View mode (display only)
    private lateinit var viewSettings: ViewSettings
    /** Auto-crop bounds per document key (null value = page not cropped). */
    private val cropCache = mutableMapOf<String, Map<Int, RectF>>()
    private var cropJob: Job? = null
    private var readingTexts: List<String>? = null
    private var readingAdapter: ReadingAdapter? = null
    private var readingJob: Job? = null

    // View / annotate mode
    private var interactionView = false
    private var hlColorIndex = 0
    /** Colors for text highlight / underline (view mode); editable. */
    private val markupPalette = HIGHLIGHT_COLORS.toMutableList()
    private val hlColorViews = mutableListOf<View>()
    private var menuBookmark: MenuItem? = null

    // Text selection (view mode)
    private var selPage = -1
    private var selAnchorStart = 0
    private var selAnchorEnd = 0
    private var selFocus = 0
    private var selView: PdfPageView? = null
    private var tappedHighlight: Pair<Int, TextHighlight>? = null

    // Search
    private var searchIndexStore: SearchIndex? = null
    /** Text of the open document, read lazily page by page (created on first use). */
    private val searchIndex: SearchIndex?
        get() {
            searchIndexStore?.let { return it }
            val file = localCopy ?: return null
            val doc = document ?: return null
            return SearchIndex(file, doc.pageCount).also { searchIndexStore = it }
        }
    private var textPrepJob: Job? = null
    private var textPreparedAround = -1
    private var searchIndexJob: Job? = null
    private var searchDebounce: Job? = null
    private var hits: List<SearchIndex.Hit> = emptyList()
    private var hitIndex = -1
    private var hitsByPage: Map<Int, List<RectF>> = emptyMap()

    private val toolButtons = mutableMapOf<DrawTool, View>()
    private val colorViews = mutableListOf<View>()
    private val density by lazy { resources.displayMetrics.density }
    private val prefs by lazy { getSharedPreferences("pdfwriter", MODE_PRIVATE) }

    /** Image tool: where the picture being picked goes (page, normalized x, y). */
    private var pendingImageAt: Triple<Int, Float, Float>? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val at = pendingImageAt
        pendingImageAt = null
        if (uri != null && at != null) placeImage(uri, at.first, at.second, at.third)
    }

    /** "New": the paper chosen in the dialog, waiting for where to save the file. */
    private var pendingNew: Pair<Boolean, com.titipong.pdfwriter.pdf.PageTemplate>? = null
    private val createNewPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val spec = pendingNew
        pendingNew = null
        if (uri != null && spec != null) writeNewPdf(uri, spec.first, spec.second)
    }

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openPdf(uri, remember = persistPermission(uri), silent = false)
    }

    /** A file opened read-only (e.g. from My Files): picked again here to get permission to save into it. */
    private var readOnlyUri: Uri? = null
    private val grantWrite = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val old = readOnlyUri
        readOnlyUri = null
        if (uri == null) return@registerForActivityResult
        persistPermission(uri)
        if (old != null && docUri?.toString() == old.toString()) {
            val newKey = AnnotationStorage.keyFor(uri.toString())
            docPassword?.let { pw ->
                tabPasswords[newKey] = pw
                prefs.edit().putBoolean("enc_$newKey", true).apply()
            }
            if (uri.toString() != old.toString()) {
                // Carry the ink over to the new link, marked "not yet in the file", then reopen: it gets written.
                saveNow()
                try {
                    AnnotationStorage.save(
                        applicationContext, newKey,
                        AnnotationStorage.toJson(pages, bookmarks, synced = false), ++saveSeq
                    )
                } catch (e: Exception) { }
                tabs.removeAll { it.uri == old.toString() }
                saveTabs()
            }
        }
        openPdf(uri, remember = true, silent = false, force = true)
    }

    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) exportCopy(uri)
    }

    private val exportFlat = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) exportCopy(uri, flatten = true)
    }

    /** Export with password: (password, flatten) waiting for destination URI. */
    private var pendingProtectedExport: Pair<String, Boolean>? = null
    private val exportProtected = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val spec = pendingProtectedExport
        pendingProtectedExport = null
        if (uri != null && spec != null) exportCopy(uri, flatten = spec.second, password = spec.first)
    }

    // Extract pages: the pages picked in the overview, waiting for a destination.
    private var pendingExtract: List<Int>? = null
    private var pendingImageFormat: PageExporter.ImageFormat? = null

    private val extractPdfTarget = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val sel = pendingExtract
        pendingExtract = null
        if (uri != null && sel != null) runExtractPdf(sel, uri)
    }

    /** Folder for images on Android 9 and older (newer versions save straight to the gallery). */
    private val extractFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val sel = pendingExtract
        val fmt = pendingImageFormat
        pendingExtract = null
        pendingImageFormat = null
        if (tree != null && sel != null && fmt != null) runExtractImages(sel, fmt, tree)
    }

    /** "Save as new file" for page edits on a file that can't be overwritten. */
    private val exportEdited = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val change = pendingChange
        pendingChange = null
        if (uri != null && change != null) commitBaseChange(change, uri, openAfter = true)
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.pagesPanel.visibility == View.VISIBLE) {
                closePanelOrEdit()
            } else if (binding.searchBar.visibility == View.VISIBLE) {
                closeSearch()
            } else if (viewSettings.reading) {
                exitReading()
            }
        }
    }

    private fun updateBackEnabled() {
        backCallback.isEnabled = binding.pagesPanel.visibility == View.VISIBLE ||
            binding.searchBar.visibility == View.VISIBLE || viewSettings.reading
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (isPhone) applyPhoneLayout()
        setSupportActionBar(binding.toolbar)
        // The app logo and name (the file name is already on its tab).
        supportActionBar?.title = getString(R.string.app_name)
        binding.toolbar.setLogo(R.drawable.app_logo)
        binding.toolbar.titleMarginStart = (10 * density).toInt()
        binding.toolbar.logoDescription = getString(R.string.app_name)
        onBackPressedDispatcher.addCallback(this, backCallback)
        viewSettings = ViewSettings.load(prefs)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.setItemViewCacheSize(4)
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
                if (barAnchor != null) positionBar()
            }
        })
        binding.recycler.onZoomSettled = { scale -> onZoomSettled(scale) }
        binding.recycler.onSingleTap = { onPageSingleTap() }
        binding.recycler.fingerDrawing = { fingerDraw && !interactionView && toolActive }
        binding.pageScrubber.onScrub = { page, _ -> scrubToPage(page) }
        binding.btnOpenEmpty.setOnClickListener { launchPicker() }
        binding.tvCredit.text = getString(R.string.credit_line, appVersion())

        loadToolPrefs()
        setupTools()
        setupPanel()

        loadTabs()
        val incoming = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (incoming != null) {
            openPdf(incoming, remember = persistPermission(incoming), silent = false)
        } else {
            val last = prefs.getString(PREF_LAST_URI, null) ?: tabs.lastOrNull()?.uri
            last?.let { openPdf(Uri.parse(it), remember = true, silent = true) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { openPdf(it, remember = persistPermission(it), silent = false) }
        }
    }

    override fun onPause() {
        super.onPause()
        rememberPage()
        saveToolPrefs()
        saveNow()      // instant in-app copy
        writePdfNow()  // and into the PDF file, even if the app is being closed
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val hard = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) document?.trimMemory(hard)
    }

    override fun onDestroy() {
        super.onDestroy()
        loadHandler.removeCallbacksAndMessages(null)
        document?.close()
        document = null
        searchIndexStore?.close()
        searchIndexStore = null
        measureJob?.cancel()
    }

    /**
     * Some Samsung devices report "S Pen side button held" as legacy action codes
     * 211/212/213 instead of ACTION_DOWN/UP/MOVE + BUTTON_STYLUS_PRIMARY.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val mapped = when (ev.actionMasked) {
            SAMSUNG_PEN_DOWN -> MotionEvent.ACTION_DOWN
            SAMSUNG_PEN_UP -> MotionEvent.ACTION_UP
            SAMSUNG_PEN_MOVE -> MotionEvent.ACTION_MOVE
            else -> -1
        }
        if (mapped < 0) return super.dispatchTouchEvent(ev)
        val copy = MotionEvent.obtain(ev)
        copy.action = mapped
        PdfPageView.penButtonOverride = true
        try {
            return super.dispatchTouchEvent(copy)
        } finally {
            PdfPageView.penButtonOverride = false
            copy.recycle()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        if (isPhone) {
            // Less used buttons go into the ⋮ menu on phones.
            menu.findItem(R.id.action_ocr)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.findItem(R.id.action_bookmark)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menuBookmark = menu.findItem(R.id.action_bookmark)
        updateBookmarkButton()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> {
            launchPicker()
            true
        }
        R.id.action_export -> {
            showExportDialog()
            true
        }
        R.id.action_new -> {
            com.titipong.pdfwriter.ui.NewPdfDialog.show(this, getString(R.string.new_default_name)) { name, landscape, template ->
                pendingNew = landscape to template
                createNewPdf.launch(if (name.endsWith(".pdf", true)) name else "$name.pdf")
            }
            true
        }
        R.id.action_print -> {
            printDocument()
            true
        }
        R.id.action_settings -> {
            SettingsDialog.show(this, prefs)
            true
        }
        R.id.action_about -> {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_message, appVersion()))
                .setPositiveButton(R.string.ok, null)
                .show()
            true
        }
        R.id.action_pages -> {
            openPanel(bookmarksTab = false)
            true
        }
        R.id.action_bookmark -> {
            toggleBookmark()
            true
        }
        R.id.action_bookmark_list -> {
            openPanel(bookmarksTab = true)
            true
        }
        R.id.action_search -> {
            openSearch()
            true
        }
        R.id.action_ocr -> {
            showOcrDialog()
            true
        }
        R.id.action_view_mode -> {
            showViewModeDialog()
            true
        }
        R.id.action_clear -> {
            confirmClearPage()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** versionName from the installed package (always matches the build). */
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    // ================================================================ tools

    private fun loadToolPrefs() {
        for ((t, ts) in toolSettings) {
            val k = "tool_${t.name}_"
            // Pen falls back to the settings saved by older versions (one shared pen setting).
            val paletteStr = prefs.getString(k + "palette", null)
                ?: if (t == DrawTool.PEN) prefs.getString(PREF_PALETTE, null) else null
            paletteStr?.split(',')?.mapNotNull { it.toIntOrNull() }?.let { saved ->
                // Older versions had 6 colors: keep the first 5.
                if (saved.size >= ts.palette.size) {
                    val n = ts.palette.size
                    ts.palette.clear()
                    ts.palette.addAll(saved.take(n))
                }
            }
            val idx = if (prefs.contains(k + "color")) prefs.getInt(k + "color", 0)
            else if (t == DrawTool.PEN) prefs.getInt(PREF_COLOR_INDEX, ts.colorIndex) else ts.colorIndex
            ts.colorIndex = if (ts.palette.isEmpty()) 0 else idx.coerceIn(0, ts.palette.size - 1)
            val size = if (prefs.contains(k + "size")) prefs.getInt(k + "size", ts.sizeTenths)
            else if (t == DrawTool.PEN) prefs.getInt(PREF_SIZE_TENTHS, ts.sizeTenths) else ts.sizeTenths
            ts.sizeTenths = size.coerceIn(1, MAX_SIZE_TENTHS)
        }
        currentTool = try {
            DrawTool.valueOf(prefs.getString(PREF_TOOL, DrawTool.PEN.name) ?: DrawTool.PEN.name)
                .let { if (it == DrawTool.WRITE_TEXT) DrawTool.PEN else it } // tool removed: lasso + "To text"
        } catch (e: IllegalArgumentException) {
            DrawTool.PEN
        }
    }

    private fun saveToolPrefs() {
        val e = prefs.edit()
        for ((t, ts) in toolSettings) {
            val k = "tool_${t.name}_"
            e.putString(k + "palette", ts.palette.joinToString(","))
            e.putInt(k + "color", ts.colorIndex)
            e.putInt(k + "size", ts.sizeTenths)
        }
        e.putString(PREF_TOOL, currentTool.name)
        e.putBoolean(PREF_TOOL_ON, toolOn)
        e.apply()
    }

    private fun setupTools() {
        toolButtons[DrawTool.PEN] = binding.btnPen
        toolButtons[DrawTool.HIGHLIGHTER] = binding.btnHighlight
        toolButtons[DrawTool.ERASER] = binding.btnEraser
        toolButtons[DrawTool.TEXT] = binding.btnText
        toolButtons[DrawTool.IMAGE] = binding.btnImage
        toolButtons[DrawTool.SELECT] = binding.btnSelect
        toolButtons.forEach { (t, v) ->
            v.setOnClickListener {
                if (t == currentTool && toolOn) {
                    toolOn = false
                    toolButtons.values.forEach { it.isSelected = false }
                    saveToolPrefs()
                } else {
                    selectTool(t)
                }
            }
        }
        binding.btnUndo.setOnClickListener { undoLast() }
        binding.btnSearchClose.setOnClickListener { closeSearch() }
        binding.btnSearchNext.setOnClickListener { stepHit(+1) }
        binding.btnSearchPrev.setOnClickListener { stepHit(-1) }
        binding.btnSearchList.setOnClickListener { showHitList() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDebounce?.cancel()
                runSearch(binding.searchInput.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.searchInput.doAfterTextChanged { text ->
            searchDebounce?.cancel()
            searchDebounce = lifecycleScope.launch {
                delay(500)
                runSearch(text?.toString() ?: "")
            }
        }
        binding.btnTextSmaller.setOnClickListener { changeReadingSize(-2f) }
        binding.btnTextBigger.setOnClickListener { changeReadingSize(+2f) }
        binding.btnReadingExit.setOnClickListener { exitReading() }
        binding.readingList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
            }
        })

        // Colors: tap = use, tap the selected one again or long-press = edit.
        val dot = (34 * density).toInt()
        val gap = (4 * density).toInt()
        DEFAULT_COLORS.indices.forEach { i ->
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(dot, dot).apply { marginEnd = gap }
            v.contentDescription = getString(R.string.color)
            v.setOnClickListener {
                if (i == colorIndex) editColor(i) else selectColor(i)
            }
            v.setOnLongClickListener {
                editColor(i)
                true
            }
            binding.colorRow.addView(v)
            colorViews.add(v)
        }

        // Size: 0.1 .. 10.0 in steps of 0.1
        binding.seekSize.max = MAX_SIZE_TENTHS - 1
        binding.seekSize.progress = sizeTenths - 1
        binding.seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sizeTenths = progress + 1
                showSize()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.btnSizeMinus.setOnClickListener { stepSize(-1) }
        binding.btnSizePlus.setOnClickListener { stepSize(+1) }
        binding.tvSize.setOnClickListener { promptSize() }
        binding.btnViewUndo.setOnClickListener { undoLast() }
        eraserPixel = prefs.getBoolean(PREF_ERASER_PIXEL, false)
        binding.inkLangGroup.check(if (handwritingLang == "en") R.id.btnLangEn else R.id.btnLangTh)
        binding.inkLangGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                handwritingLang = if (id == R.id.btnLangEn) "en" else "th"
                prefs.edit().putString(PREF_HW_LANG, handwritingLang).apply()
                ensureHandwritingModel()
            }
        }
        binding.btnSelConvert.setOnClickListener { convertSelectedHandwriting() }
        binding.eraserModeGroup.check(if (eraserPixel) R.id.btnErasePixel else R.id.btnEraseStroke)
        binding.eraserModeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                eraserPixel = id == R.id.btnErasePixel
                prefs.edit().putBoolean(PREF_ERASER_PIXEL, eraserPixel).apply()
            }
        }

        val wasOn = prefs.getBoolean(PREF_TOOL_ON, true)
        selectTool(currentTool)
        if (!wasOn) {
            toolOn = false
            toolButtons.values.forEach { it.isSelected = false }
        }
        setupModeToggle()
    }

    private fun stepSize(delta: Int) {
        setSize(sizeTenths + delta)
    }

    private fun setSize(tenths: Int) {
        sizeTenths = tenths.coerceIn(1, MAX_SIZE_TENTHS)
        binding.seekSize.progress = sizeTenths - 1
        showSize()
        saveToolPrefs()
    }

    /** Tap the size number to type an exact value. */
    private fun promptSize() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.1f", sizeValue))
            setSelectAllOnFocus(true)
        }
        val pad = (20 * density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.size_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val v = input.text.toString().replace(',', '.').toFloatOrNull()
                if (v == null || v < 0.1f || v > 10f) {
                    Toast.makeText(this, R.string.size_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    setSize(Math.round(v * 10f))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun showSize() {
        binding.tvSize.text = String.format(Locale.US, "%.1f", sizeValue)
    }

    /** Switches tool and shows that tool's own colors and size. */
    private fun selectTool(t: DrawTool) {
        clearSelection() // a framed note / picture / stroke belongs to the previous tool
        flushHandwriting() // leaving "Write to text": convert what was written
        currentTool = t
        toolOn = true
        toolButtons.forEach { (key, v) -> v.isSelected = key == t }
        val hasColors = palette.isNotEmpty()
        binding.colorRow.visibility = if (hasColors) View.VISIBLE else View.GONE
        binding.eraserModeGroup.visibility = if (t == DrawTool.ERASER) View.VISIBLE else View.GONE
        // Pictures have no color or size: resize them with their frame.
        // Handwriting language for "To text" (lasso handwriting with the Select tool).
        binding.inkLangGroup.visibility = if (t == DrawTool.SELECT) View.VISIBLE else View.GONE
        val sized = if (t == DrawTool.IMAGE || t == DrawTool.SELECT || t == DrawTool.WRITE_TEXT) View.GONE else View.VISIBLE
        listOf(binding.tvSizeLabel, binding.btnSizeMinus, binding.tvSize, binding.btnSizePlus, binding.sizeDivider)
            .forEach { it.visibility = sized }
        if (isPhone) binding.tvSizeLabel.visibility = View.GONE // no room on phones
        if (hasColors) selectColor(colorIndex)
        binding.seekSize.progress = sizeTenths - 1
        showSize()
        saveToolPrefs()
    }

    private fun selectColor(index: Int) {
        colorIndex = index
        colorViews.forEachIndexed { i, v ->
            v.visibility = if (i < palette.size) View.VISIBLE else View.GONE
            if (i >= palette.size) return@forEachIndexed
            v.background = swatch(palette[i], i == colorIndex)
        }
    }

    /**
     * A color swatch. Selected: a smaller dot inside a gray rounded square, so the chosen color is
     * obvious. Not selected: a plain full-size dot.
     */
    private fun swatch(color: Int, selected: Boolean): android.graphics.drawable.Drawable {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1 * density).toInt(), Color.parseColor("#33000000"))
        }
        if (!selected) {
            return android.graphics.drawable.LayerDrawable(arrayOf(dot)).apply {
                val inset = (2 * density).toInt()
                setLayerInset(0, inset, inset, inset, inset)
            }
        }
        val frame = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * density
            setColor(Color.parseColor("#F1F5F9"))
            setStroke((2 * density).toInt(), Color.parseColor("#6B7280"))
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(frame, dot)).apply {
            val inset = (8 * density).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun editColor(index: Int) {
        // Pen colors: just a hue bar (and presets); other tools keep the full editor.
        val pen = currentTool == DrawTool.PEN
        val hl = currentTool == DrawTool.HIGHLIGHTER
        ColorPicker.show(
            this, palette[index], hueOnly = pen || hl, light = hl,
            opacityDefault = if (hl) com.titipong.pdfwriter.model.Opacity.INK_DEFAULT else null,
            pressure = if (pen) penPressure else null,
            onPressure = { prefs.edit().putBoolean(PREF_PEN_PRESSURE, it).apply() }
        ) { picked ->
            palette[index] = picked
            selectColor(index)
            saveToolPrefs()
        }
    }

    private fun onZoomSettled(scale: Float) {
        if (barAnchor != null) binding.recycler.post { positionBar() }
        val a = adapter ?: return
        a.renderScale = scale
        watchPageLoad()
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first >= 0 && last >= first) a.notifyItemRangeChanged(first, last - first + 1, PageAdapter.RERENDER_PAYLOAD)
    }

    // ================================================================ Host

    override fun annotationsFor(page: Int): PageAnnotations = pages.getOrPut(page) { PageAnnotations() }

    override fun isBookmarked(page: Int): Boolean = bookmarks.any { it.page == page }

    override fun searchHits(page: Int): List<RectF>? = hitsByPage[page]

    override fun activeSearchHit(page: Int): List<RectF>? =
        hits.getOrNull(hitIndex)?.takeIf { it.page == page }?.rects

    override fun onItemAdded(page: Int, item: Any) {
        history.addLast(page to item)
        if (history.size > MAX_HISTORY) history.removeFirst()
        markDirty()
    }

    override fun onItemsErased(page: Int) {
        markDirty()
    }

    override fun requestText(page: Int, x: Float, y: Float) {
        clearSelection()
        promptText(getString(R.string.add_text), "", multiLine = true) { text ->
            val note = TextNote(text, x, y, color, 0.018f + 0.004f * sizeValue)
            annotationsFor(page).texts.add(note)
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            onItemAdded(page, note)
        }
    }

    private fun promptText(title: String, initial: String, multiLine: Boolean = false, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.text_hint)
            if (multiLine) {
                // Text boxes: Enter starts a new line.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                maxLines = 8
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
            }
            setText(initial)
            setSelection(initial.length)
        }
        val pad = (20 * density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) onOk(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    // ================================================================ editing

    private fun undoLast() {
        clearSelection()
        while (history.isNotEmpty()) {
            val (page, item) = history.removeLast()
            val pa = pages[page] ?: continue
            val removed = when (item) {
                is InkStroke -> pa.strokes.remove(item)
                is TextNote -> pa.texts.remove(item)
                is ImageNote -> pa.images.remove(item)
                is TextHighlight -> pa.highlights.remove(item)
                is ConvertEdit -> {
                    pa.texts.remove(item.note)
                    pa.strokes.addAll(item.strokes)
                    true
                }
                is GroupDelete -> {
                    for (it in item.items) when (it) {
                        is TextNote -> pa.texts.add(it)
                        is ImageNote -> pa.images.add(it)
                        is InkStroke -> pa.strokes.add(it)
                    }
                    true
                }
                is NoteEdit -> {
                    val before = item.before
                    val after = item.after
                    if (before is TextNote && after is TextNote) {
                        val i = pa.texts.indexOf(after)
                        if (i >= 0) pa.texts[i] = before
                        i >= 0
                    } else if (before is ImageNote && after is ImageNote) {
                        val i = pa.images.indexOf(after)
                        if (i >= 0) pa.images[i] = before
                        i >= 0
                    } else if (before is InkStroke && after is InkStroke) {
                        val i = pa.strokes.indexOf(after)
                        if (i >= 0) pa.strokes[i] = before
                        i >= 0
                    } else if (before is com.titipong.pdfwriter.model.ItemGroup && after is com.titipong.pdfwriter.model.ItemGroup) {
                        var any = false
                        before.items.zip(after.items).forEach { (b, a) ->
                            when {
                                b is TextNote && a is TextNote -> pa.texts.indexOf(a).takeIf { it >= 0 }?.let { pa.texts[it] = b; any = true }
                                b is ImageNote && a is ImageNote -> pa.images.indexOf(a).takeIf { it >= 0 }?.let { pa.images[it] = b; any = true }
                                b is InkStroke && a is InkStroke -> pa.strokes.indexOf(a).takeIf { it >= 0 }?.let { pa.strokes[it] = b; any = true }
                            }
                        }
                        any
                    } else {
                        false
                    }
                }
                else -> false
            }
            if (removed) {
                adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
                markDirty()
                return
            }
        }
        Toast.makeText(this, R.string.nothing_to_undo, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearPage() {
        if (document == null) return
        val page = currentPage()
        if (page < 0) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_page)
            .setMessage(getString(R.string.clear_page_confirm, page + 1))
            .setPositiveButton(R.string.delete) { _, _ ->
                pages[page]?.let {
                    it.strokes.clear()
                    it.texts.clear()
                    it.images.clear()
                }
                adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
                markDirty()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ================================================================ bookmarks

    private fun toggleBookmark() {
        if (document == null) return
        val page = currentPage()
        if (page < 0) return
        val existing = bookmarks.firstOrNull { it.page == page }
        if (existing != null) {
            removeBookmark(existing, undoable = true)
        } else {
            val b = Bookmark(getString(R.string.bookmark_default, page + 1), page)
            bookmarks.add(b)
            bookmarksChanged(page)
            Snackbar.make(binding.root, getString(R.string.bookmark_added, page + 1), Snackbar.LENGTH_LONG)
                .setAction(R.string.rename) { renameBookmark(b) }
                .show()
        }
    }

    private fun removeBookmark(b: Bookmark, undoable: Boolean) {
        val index = bookmarks.indexOf(b)
        if (index < 0) return
        bookmarks.removeAt(index)
        bookmarksChanged(b.page)
        if (undoable) {
            Snackbar.make(binding.root, R.string.bookmark_removed, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    bookmarks.add(index.coerceAtMost(bookmarks.size), b)
                    bookmarksChanged(b.page)
                }
                .show()
        }
    }

    private fun renameBookmark(b: Bookmark) {
        promptText(getString(R.string.rename_bookmark), b.title) { text ->
            b.title = text
            bookmarksChanged(b.page)
        }
    }

    private fun bookmarksChanged(page: Int) {
        adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
        updateBookmarkButton()
        refreshBookmarkList()
        markDirty()
    }

    private fun updateBookmarkButton() {
        val page = currentPage()
        menuBookmark?.setIcon(
            if (page >= 0 && isBookmarked(page)) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
        )
    }

    // ================================================================ page overview panel

    private fun setupPanel() {
        binding.panelTabs.addTab(binding.panelTabs.newTab().setText(R.string.tab_pages))
        binding.panelTabs.addTab(binding.panelTabs.newTab().setText(R.string.tab_bookmarks))
        binding.panelTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanelTab(tab.position == 1)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        binding.btnClosePanel.setOnClickListener { closePanelOrEdit() }
        binding.btnEditPages.setOnClickListener { setEditMode(true) }
        binding.btnCancelEdit.setOnClickListener { cancelEdit() }
        binding.btnAddBlank.setOnClickListener {
            val pos = gridAdapter?.insertBlank() ?: return@setOnClickListener
            binding.pagesGrid.scrollToPosition(pos)
        }
        // One button for the selected pages; its actions open as a vertical list.
        binding.btnDeletePages.setOnClickListener { showPageActions() }
        binding.btnApplyEdit.setOnClickListener { onApplyEdit() }
        binding.btnSelectAll.setOnClickListener { gridAdapter?.selectAll() }

        binding.bookmarksList.layoutManager = LinearLayoutManager(this)
        bookmarkAdapter = BookmarkAdapter(
            onJump = { page -> jumpTo(page) },
            onRename = { b -> renameBookmark(b) },
            onDelete = { b -> removeBookmark(b, undoable = true) }
        )
        binding.bookmarksList.adapter = bookmarkAdapter

        // Drag to reorder (edit mode only).
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean =
                gridAdapter?.editMode == true && gridAdapter?.pickMode != true

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                gridAdapter?.move(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                gridAdapter?.refreshLabels()
            }
        })
        touchHelper.attachToRecyclerView(binding.pagesGrid)
    }

    private fun openPanel(bookmarksTab: Boolean) {
        val doc = document ?: return
        val span = (resources.displayMetrics.widthPixels / (180 * density)).toInt().coerceAtLeast(2)
        binding.pagesGrid.layoutManager = GridLayoutManager(this, span)
        val g = PageGridAdapter(
            doc = doc,
            scope = lifecycleScope,
            annotationsOf = { pages[it] },
            isBookmarked = { isBookmarked(it) },
            onOpenPage = { jumpTo(it) },
            onLongPressInViewMode = { pos ->
                setEditMode(true)
                gridAdapter?.let { ga ->
                    ga.selected.add(ga.slots[pos])
                    ga.refreshLabels()
                    updateEditButtons()
                }
            },
            onSelectionChanged = { updateEditButtons() }
        )
        gridAdapter = g
        binding.pagesGrid.adapter = g
        binding.pagesGrid.scrollToPosition(currentPage().coerceAtLeast(0))
        refreshBookmarkList()
        setEditMode(false)
        binding.pagesPanel.visibility = View.VISIBLE
        binding.toolStrip.visibility = View.GONE
        updateBackEnabled()
        binding.panelTabs.getTabAt(if (bookmarksTab) 1 else 0)?.select()
        showPanelTab(bookmarksTab)
    }

    private fun showPanelTab(bookmarksTab: Boolean) {
        if (bookmarksTab && gridAdapter?.editMode == true) {
            // Don't silently lose a page edit by switching tabs.
            binding.panelTabs.getTabAt(0)?.select()
            return
        }
        binding.pagesGrid.visibility = if (bookmarksTab) View.GONE else View.VISIBLE
        val empty = bookmarks.isEmpty() && outline.isEmpty()
        binding.bookmarksList.visibility = if (bookmarksTab && !empty) View.VISIBLE else View.GONE
        binding.bookmarksEmpty.visibility = if (bookmarksTab && empty) View.VISIBLE else View.GONE
        binding.btnEditPages.visibility = if (bookmarksTab || gridAdapter?.editMode == true) View.GONE else View.VISIBLE
    }

    private fun refreshBookmarkList() {
        bookmarkAdapter?.submit(getString(R.string.my_bookmarks), bookmarks, getString(R.string.file_toc), outline)
        if (binding.pagesPanel.visibility == View.VISIBLE && binding.panelTabs.selectedTabPosition == 1) {
            showPanelTab(true)
        }
    }

    private fun setEditMode(on: Boolean) {
        val pick = on && gridAdapter?.pickMode == true
        if (!on) gridAdapter?.pickMode = false
        gridAdapter?.editMode = on
        binding.panelHint.visibility = if (on) View.VISIBLE else View.GONE
        binding.panelHint.setText(if (pick) R.string.extract_hint else R.string.edit_pages_hint)
        binding.btnAddBlank.visibility = if (on && !pick) View.VISIBLE else View.GONE
        binding.btnSelectAll.visibility = if (pick) View.VISIBLE else View.GONE
        binding.btnCancelEdit.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnApplyEdit.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnEditPages.visibility = if (on) View.GONE else View.VISIBLE
        updateEditButtons()
    }

    private fun updateEditButtons() {
        val g = gridAdapter
        val n = g?.selected?.size ?: 0
        binding.btnDeletePages.visibility = if (g?.editMode == true && n > 0) View.VISIBLE else View.GONE
        binding.btnDeletePages.text = getString(R.string.selected_pages_n, n)
        binding.btnDuplicatePages.visibility = View.GONE
        if (g?.pickMode == true) {
            binding.btnDeletePages.visibility = View.GONE
            binding.btnDuplicatePages.visibility = View.GONE
            binding.btnApplyEdit.text = getString(R.string.extract_n, n)
        } else {
            binding.btnApplyEdit.setText(R.string.save)
        }
    }

    private fun showPageActions() {
        val g = gridAdapter ?: return
        val n = g.selected.size
        if (n == 0) return
        val popup = android.widget.PopupMenu(this, binding.btnDeletePages)
        popup.menu.add(0, 1, 0, getString(R.string.duplicate_pages_n, n)).setIcon(R.drawable.ic_copy)
        popup.menu.add(0, 2, 1, getString(R.string.delete_pages_n, n)).setIcon(R.drawable.ic_delete)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val added = g.duplicateSelected()
                    if (added > 0) Toast.makeText(this, getString(R.string.pages_duplicated, added), Toast.LENGTH_SHORT).show()
                }
                2 -> if (!g.deleteSelected()) Toast.makeText(this, R.string.cannot_delete_all, Toast.LENGTH_SHORT).show()
            }
            true
        }
        popup.show()
    }

    private fun cancelEdit() {
        val g = gridAdapter ?: return
        if (g.pickMode) {
            // Extract was opened from the export menu: cancelling closes the overview.
            setEditMode(false)
            closePanelOrEdit()
            return
        }
        if (!g.isModified()) {
            setEditMode(false)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_edits)
            .setMessage(R.string.discard_edits_msg)
            .setPositiveButton(R.string.discard) { _, _ ->
                g.reset()
                setEditMode(false)
            }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }

    private fun closePanelOrEdit() {
        if (gridAdapter?.editMode == true) {
            cancelEdit()
            return
        }
        binding.pagesPanel.visibility = View.GONE
        binding.toolStrip.visibility = View.VISIBLE
        binding.pagesGrid.adapter = null
        gridAdapter = null
        updateBackEnabled()
    }

    private fun jumpTo(page: Int) {
        closePanelOrEdit()
        if (gridAdapter != null) return // still in edit mode (user kept editing)
        if (viewSettings.reading) {
            (binding.readingList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(page, 0)
            binding.recycler.post { updatePageIndicator() }
            return
        }
        binding.recycler.resetZoom()
        val pos = adapter?.positionOf(page) ?: page
        (binding.recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
        binding.recycler.post { updatePageIndicator() }
    }

    private fun onApplyEdit() {
        val g = gridAdapter ?: return
        if (g.pickMode) {
            onExtract()
            return
        }
        if (!g.isModified()) {
            setEditMode(false)
            return
        }
        val order = g.order()
        if (pdfWritable) {
            commitBaseChange(pageEditChange(order), docUri ?: return, openAfter = false)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.read_only_pages_title)
                .setMessage(R.string.read_only_pages_msg)
                .setPositiveButton(R.string.save_as_new) { _, _ ->
                    pendingChange = pageEditChange(order)
                    exportEdited.launch(docName.removeSuffix(".pdf") + "-edited.pdf")
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * Applies a new page order (deletes / blank inserts / moves) to the file:
     * rebuilds the pages, writes ink and bookmarks for the new layout into [dest],
     * and only then switches the app over, so a failure leaves everything as it was.
     */
    private fun pageEditChange(order: List<Int?>): BaseChange {
        val newPages = mutableMapOf<Int, PageAnnotations>()
        order.forEachIndexed { i, src ->
            val pa = src?.let { pages[it] } ?: return@forEachIndexed
            newPages[i] = pa.copy()
        }
        val newBookmarks = bookmarks.mapNotNull { b ->
            val i = order.indexOf(b.page)
            if (i >= 0) Bookmark(b.title, i) else null
        }.toMutableList()
        return BaseChange(
            transform = { src, out -> PdfAnnotationIO.rebuildPages(src, order, out) },
            newPages = newPages,
            newBookmarks = newBookmarks,
            okMessage = R.string.pages_saved,
            failMessage = R.string.pages_failed,
            progressMessage = getString(R.string.applying_pages)
        )
    }

    /** Same pages and ink, only the base document changes (e.g. an OCR text layer). */
    private fun sameLayoutChange(transform: suspend (File, File) -> Unit, ok: Int, fail: Int, progress: String) =
        BaseChange(
            transform = transform,
            newPages = snapshotPages().toMutableMap(),
            newBookmarks = snapshotBookmarks().toMutableList(),
            okMessage = ok,
            failMessage = fail,
            progressMessage = progress
        )

    /**
     * Applies a change to the document itself (page order, OCR text layer...): builds the new
     * file, writes ink and bookmarks into [dest], and only then switches the app over,
     * so a failure leaves everything as it was.
     */
    private fun commitBaseChange(change: BaseChange, dest: Uri, openAfter: Boolean) {
        val src = localCopy ?: return
        val key = docKey ?: return
        val progress = showProgress(change.progressMessage)
        applyingPages = true
        lifecycleScope.launch {
            try {
                runBaseChange(src, key, change, dest, openAfter, progress)
            } finally {
                applyingPages = false
            }
        }
    }

    private suspend fun runBaseChange(
        src: File,
        key: String,
        change: BaseChange,
        dest: Uri,
        openAfter: Boolean,
        progress: AlertDialog
    ) {
        val newPages = change.newPages
        val newBookmarks = change.newBookmarks
        run {
            saveNow()
            pdfJob?.cancel()
            val dir = File(filesDir, "docs").apply { mkdirs() }
            val rebuilt = File(dir, "$key.edit.pdf")
            val pw = if (!openAfter) docPassword else null
            val result = try {
                change.transform(src, rebuilt)
                PdfAnnotationIO.write(applicationContext, rebuilt, newPages, newBookmarks, dest, password = pw)
                true
            } catch (e: Throwable) {
                false
            }
            if (!result) {
                progress.dismiss()
                rebuilt.delete()
                Toast.makeText(this@MainActivity, change.failMessage, Toast.LENGTH_LONG).show()
                return
            }
            if (openAfter) {
                progress.dismiss()
                rebuilt.delete()
                closePanelForce()
                applyingPages = false
                openPdf(dest, remember = persistPermission(dest), silent = false)
                return
            }

            // The file now has the new layout. Record it in the app copy (marked as matching the
            // file) and make sure no pending save writes the old layout back.
            val json = AnnotationStorage.toJson(newPages, newBookmarks, synced = true)
            val seq = ++saveSeq
            withContext(Dispatchers.IO) {
                try { AnnotationStorage.save(applicationContext, key, json, seq) } catch (e: Exception) { }
            }
            dirty = false
            changeVersion++
            pdfSavedVersion = changeVersion
            withContext(Dispatchers.IO) { sourceMeta(dest) }?.let {
                prefs.edit().putString("meta_$key", it)
                    .putString("alias_${docName}|${it.substringBefore(':')}", key).apply()
            }

            // Swap the app over to the new layout.
            val finalCopy = File(dir, "$key.pdf")
            val opened = try {
                withContext(Dispatchers.IO) {
                    if (!rebuilt.renameTo(finalCopy)) {
                        finalCopy.delete()
                        rebuilt.renameTo(finalCopy)
                    }
                    val doc = PdfDocumentRenderer.open(
                        ParcelFileDescriptor.open(finalCopy, ParcelFileDescriptor.MODE_READ_ONLY),
                        PdfDocumentRenderer.diskCacheFor(cacheDir, finalCopy)
                    )
                    val toc = try { PdfAnnotationIO.read(finalCopy).outline } catch (e: Throwable) { emptyList() }
                    Pair(doc, toc)
                }
            } catch (e: Throwable) {
                null
            }
            progress.dismiss()
            if (opened == null) {
                // The file itself was saved fine; reopen it from scratch.
                closePanelForce()
                applyingPages = false
                openPdf(dest, remember = true, silent = false, force = true)
                return
            }
            document?.close()
            document = opened.first
            startMeasuring(opened.first)
            outline = opened.second
            localCopy = finalCopy
            pages = newPages
            bookmarks = newBookmarks
            history.clear()
            cropCache.remove(key)
            readingTexts = null
            clearSearch(resetIndex = true)

            val keepPage = currentPage().coerceIn(0, (opened.first.pageCount - 1).coerceAtLeast(0))
            closePanelForce()
            applyView(keepPage)
            setStatus(R.string.saved_in_pdf)
            binding.recycler.post { updatePageIndicator() }
            Toast.makeText(this@MainActivity, change.okMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePanelForce() {
        gridAdapter?.editMode = false
        setEditMode(false)
        closePanelOrEdit()
    }

    private fun showProgress(message: String): AlertDialog {
        val pad = (24 * density).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(this@MainActivity))
            addView(android.widget.TextView(this@MainActivity).apply {
                text = message
                textSize = 16f
                setPadding(pad, 0, 0, 0)
            })
        }
        return MaterialAlertDialogBuilder(this).setView(row).setCancelable(false).show()
    }

    // ================================================================ saving

    private fun setStatus(resId: Int) {
        if (isDestroyed) return
        // Phones: a short status so the file name keeps its room in the top bar.
        val short = if (!isPhone) null else when (resId) {
            R.string.saved_in_pdf -> "✓"
            R.string.saved_in_app -> "✓ App"
            R.string.saving -> "…"
            R.string.save_failed -> "⚠"
            else -> null
        }
        if (short != null) binding.tvSaveState.text = short else binding.tvSaveState.setText(resId)
    }

    /** Phone-sized screen (narrowest side under 600 dp). Tablets keep the full single-row layout. */
    private val isPhone: Boolean get() = resources.configuration.smallestScreenWidthDp < 600

    /** Second row of the Annotate tools on phones: colors, eraser mode, size, undo. */
    private var toolRow2: View? = null
    /** Phones: the scroller holding the first-row Annotate tools. */
    private var toolsRow1: View? = null

    /**
     * Phones: the Annotate tools don't fit one row, so colors / size / undo move to a second
     * (scrollable) row; the mode button and size label get smaller.
     */
    private fun applyPhoneLayout() {
        val d = density
        // Page editing bar: Cancel / Save don't fit next to "+ Blank page" and "N selected" on a
        // phone: put them on their own line under it, at the right.
        (binding.btnApplyEdit.parent as? LinearLayout)?.let { header ->
            val panel = header.parent as? LinearLayout
            if (panel != null) {
                val row2 = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(android.graphics.Color.WHITE)
                    setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
                }
                for (v in listOf(binding.btnCancelEdit, binding.btnApplyEdit)) {
                    header.removeView(v)
                    row2.addView(v)
                }
                panel.addView(row2, panel.indexOfChild(header) + 1, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }
        val tools = binding.annotateTools
        val start = tools.indexOfChild(binding.colorRow) - 1 // the divider before the colors
        if (start < 0) return
        val moved = (start + 1 until tools.childCount).map { tools.getChildAt(it) }
        tools.removeViews(start, tools.childCount - start) // also drops that divider
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (2 * d).toInt())
        }
        moved.forEach { row.addView(it) }
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        binding.toolStrip.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        toolRow2 = scroller

        // First row: 7 tools + the mode button don't fit a phone at full size: make the tool
        // buttons a little smaller, and let the row scroll sideways if it still doesn't fit.
        val btn = (36 * d).toInt()
        for (i in 0 until tools.childCount) {
            val c = tools.getChildAt(i)
            if (c is android.widget.ImageButton) {
                val lp = c.layoutParams as LinearLayout.LayoutParams
                lp.width = btn
                lp.height = btn
                lp.marginEnd = (2 * d).toInt()
                c.layoutParams = lp
                c.setPadding((7 * d).toInt(), (7 * d).toInt(), (7 * d).toInt(), (7 * d).toInt())
            }
        }
        val parent = tools.parent as LinearLayout
        val index = parent.indexOfChild(tools)
        val toolsLp = tools.layoutParams as LinearLayout.LayoutParams
        parent.removeView(tools)
        tools.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val toolScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }
        parent.addView(toolScroll, index, LinearLayout.LayoutParams(0, toolsLp.height, 1f))
        toolsRow1 = toolScroll
        binding.tvSizeLabel.visibility = View.GONE
        binding.btnMode.apply {
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding((10 * d).toInt(), paddingTop, (6 * d).toInt(), paddingBottom)
        }
    }

    private fun markDirty() {
        dirty = true
        changeVersion++
        setStatus(R.string.saving)
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(400)
            val key = docKey ?: return@launch
            val json = AnnotationStorage.toJson(pages, bookmarks, synced = false)
            val seq = ++saveSeq
            dirty = false
            try {
                withContext(Dispatchers.IO) { AnnotationStorage.save(applicationContext, key, json, seq) }
                if (!pdfWritable) setStatus(R.string.saved_in_app)
            } catch (e: Exception) {
                dirty = true
                setStatus(R.string.save_failed)
            }
        }
        if (pdfWritable) {
            pdfJob?.cancel()
            pdfJob = lifecycleScope.launch {
                delay(PDF_SAVE_DELAY_MS)
                writePdfNow()
            }
        }
    }

    /** Synchronous in-app save, used when leaving the screen or switching files. */
    private fun saveNow() {
        saveJob?.cancel()
        if (!dirty) return
        val key = docKey ?: return
        try {
            AnnotationStorage.save(
                applicationContext, key, AnnotationStorage.toJson(pages, bookmarks, synced = false), ++saveSeq
            )
            dirty = false
        } catch (e: Exception) {
            setStatus(R.string.save_failed)
        }
    }

    private fun snapshotPages(): Map<Int, PageAnnotations> = pages.mapValues { (_, pa) -> pa.copy() }

    private fun snapshotBookmarks(): List<Bookmark> = bookmarks.map { Bookmark(it.title, it.page) }

    /** Writes ink + bookmarks into the original PDF in the background; survives leaving the app. */
    private fun writePdfNow() {
        pdfJob?.cancel()
        if (!pdfWritable || applyingPages) return
        val version = changeVersion
        if (version == pdfSavedVersion) return
        val src = localCopy ?: return
        val dest = docUri ?: return
        val key = docKey ?: return
        val snap = snapshotPages()
        val snapBookmarks = snapshotBookmarks()
        val pw = docPassword
        pdfSavedVersion = version // claim it; reset below if the write fails
        setStatus(R.string.saving_pdf)
        App.scope.launch {
            val ok = try {
                PdfAnnotationIO.write(applicationContext, src, snap, snapBookmarks, dest, password = pw)
                true
            } catch (e: Throwable) {
                false
            }
            if (!ok) {
                if (pdfSavedVersion == version) pdfSavedVersion = -1
                setStatus(R.string.save_pdf_failed)
                return@launch
            }
            // The file now matches our private copy + ink: a later reopen may reuse them.
            withContext(Dispatchers.IO) { sourceMeta(dest) }?.let {
                prefs.edit().putString("meta_$key", it)
                    .putString("alias_${docName}|${it.substringBefore(':')}", key).apply()
            }
            // Nothing changed meanwhile: mark the in-app copy as matching the file.
            if (changeVersion == version && docKey == key) {
                val json = AnnotationStorage.toJson(snap, snapBookmarks, synced = true)
                val seq = ++saveSeq
                withContext(Dispatchers.IO) {
                    try { AnnotationStorage.save(applicationContext, key, json, seq) } catch (e: Exception) { }
                }
                setStatus(R.string.saved_in_pdf)
            }
        }
    }

    // ================================================================ print

    /** Prints the document with its ink, highlights and notes (flattened, as on screen). */
    private fun printDocument() {
        val src = localCopy ?: return
        val pageCount = document?.pageCount ?: return
        val snap = snapshotPages()
        val snapBookmarks = snapshotBookmarks()
        val jobName = docName.removeSuffix(".pdf")
        val tmp = File(cacheDir, "print.pdf")
        val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
        pm.print(jobName, object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: android.print.PrintAttributes?,
                newAttributes: android.print.PrintAttributes?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = android.print.PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback?.onLayoutFinished(info, oldAttributes != newAttributes)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                val dest = destination ?: return
                lifecycleScope.launch {
                    try {
                        PdfAnnotationIO.writeFile(src, snap, snapBookmarks, tmp, flatten = true)
                        withContext(Dispatchers.IO) {
                            val out = java.io.FileOutputStream(dest.fileDescriptor)
                            tmp.inputStream().use { it.copyTo(out) }
                            out.flush()
                        }
                        callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    } catch (e: Throwable) {
                        Toast.makeText(this@MainActivity, R.string.print_failed, Toast.LENGTH_LONG).show()
                        callback?.onWriteFailed(e.message)
                    } finally {
                        tmp.delete()
                    }
                }
            }
        }, null)
    }

    // ================================================================ export

    private fun showExportDialog() {
        if (document == null) return
        val base = docName.removeSuffix(".pdf")
        val copySub = getString(if (docPassword != null) R.string.opt_copy_unlock_s else R.string.opt_copy_s)
        val flatSub = getString(if (docPassword != null) R.string.opt_flat_unlock_s else R.string.opt_flat_s)
        com.titipong.pdfwriter.ui.OptionSheet.show(this, getString(R.string.export_title), listOf(
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_copy, getString(R.string.opt_copy_t), copySub),
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_cover, getString(R.string.opt_flat_t), flatSub),
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_lock, getString(R.string.opt_protect_t), getString(R.string.opt_protect_s)),
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_pages, getString(R.string.opt_extract_t), getString(R.string.opt_extract_s))
        )) { which ->
            when (which) {
                0 -> exportPdf.launch("$base-copy.pdf")
                1 -> exportFlat.launch("$base-flattened.pdf")
                2 -> promptExportPassword { pw, flatten ->
                    pendingProtectedExport = pw to flatten
                    exportProtected.launch("$base-protected.pdf")
                }
                else -> openExtractPicker()
            }
        }
    }

    private fun promptExportPassword(onConfirm: (password: String, flatten: Boolean) -> Unit) {
        val pad = (20 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun makePasswordField(hintRes: Int): Pair<TextInputLayout, TextInputEditText> {
            val til = TextInputLayout(this).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                hint = getString(hintRes)
            }
            val et = TextInputEditText(til.context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                maxLines = 1
            }
            til.addView(et)
            et.doAfterTextChanged { til.error = null }
            return til to et
        }
        val (til1, et1) = makePasswordField(R.string.password_hint)
        val (til2, et2) = makePasswordField(R.string.password_confirm_hint)
        root.addView(til1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(til2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() })

        val flattenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val flattenLabel = android.widget.TextView(this).apply {
            setText(R.string.export_protect_flatten)
            textSize = 14f
            setTextColor(Color.parseColor("#374151"))
        }
        val flattenSwitch = MaterialSwitch(this)
        flattenRow.addView(flattenLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        flattenRow.addView(flattenSwitch)
        flattenRow.setOnClickListener { flattenSwitch.toggle() }
        root.addView(flattenRow)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_protect_title)
            .setView(android.widget.ScrollView(this).apply { addView(root) })
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = et1.text?.toString() ?: ""
                val p2 = et2.text?.toString() ?: ""
                when {
                    p1.isEmpty() -> til1.error = getString(R.string.password_empty)
                    p1 != p2 -> til2.error = getString(R.string.password_mismatch)
                    else -> {
                        dialog.dismiss()
                        onConfirm(p1, flattenSwitch.isChecked)
                    }
                }
            }
            et1.requestFocus()
        }
        dialog.show()
    }

    /** The page overview in "tick pages to extract" mode. */
    private fun openExtractPicker() {
        openPanel(bookmarksTab = false)
        val g = gridAdapter ?: return
        g.pickMode = true
        setEditMode(true)
    }

    private fun onExtract() {
        val g = gridAdapter ?: return
        val selected = g.slots.filter { it in g.selected }.mapNotNull { it.source }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.extract_pick_one, Toast.LENGTH_SHORT).show()
            return
        }
        val base = docName.removeSuffix(".pdf")
        com.titipong.pdfwriter.ui.OptionSheet.show(this, getString(R.string.extract_as, selected.size), listOf(
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_page_single, getString(R.string.opt_pdf_t), getString(R.string.opt_pdf_s)),
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_image, getString(R.string.opt_png_t), getString(R.string.opt_png_s)),
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_image, getString(R.string.opt_jpg_t), getString(R.string.opt_jpg_s))
        )) { which ->
                when (which) {
                    0 -> {
                        pendingExtract = selected
                        val range = if (selected.size == 1) "p${selected[0] + 1}" else "${selected.size}pages"
                        extractPdfTarget.launch("$base-$range.pdf")
                    }
                    else -> {
                        val fmt = if (which == 1) PageExporter.ImageFormat.PNG else PageExporter.ImageFormat.JPG
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            runExtractImages(selected, fmt, null)
                        } else {
                            pendingExtract = selected
                            pendingImageFormat = fmt
                            extractFolder.launch(null)
                        }
                    }
                }
        }
    }

    private fun runExtractPdf(selected: List<Int>, dest: Uri) {
        val src = localCopy ?: return
        val snap = snapshotPages()
        val snapBookmarks = snapshotBookmarks()
        val progress = showProgress(getString(R.string.extracting))
        lifecycleScope.launch {
            val ok = try {
                PageExporter.extractPdf(applicationContext, src, snap, snapBookmarks, selected, dest)
                true
            } catch (e: Throwable) {
                false
            }
            progress.dismiss()
            Toast.makeText(this@MainActivity, if (ok) R.string.extract_pdf_done else R.string.extract_failed, Toast.LENGTH_LONG).show()
            if (ok) closePanelForce()
        }
    }

    /** [folder] null = save to the gallery (Android 10+). */
    private fun runExtractImages(selected: List<Int>, format: PageExporter.ImageFormat, folder: Uri?) {
        val src = localCopy ?: return
        val snap = snapshotPages()
        val base = docName.removeSuffix(".pdf").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var cancelled = false
        val (dialog, label) = showCancellableProgress(getString(R.string.extracting)) { cancelled = true }
        lifecycleScope.launch {
            val saved = try {
                PageExporter.extractImages(
                    applicationContext, src, snap, selected, base, format,
                    openOutput = { name, mime ->
                        if (folder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) galleryOutput(name, mime)
                        else if (folder != null) folderOutput(folder, name, mime)
                        else null
                    },
                    onProgress = { done, total ->
                        if (cancelled) throw java.util.concurrent.CancellationException()
                        runOnUiThread { label.text = getString(R.string.extracting_image, done, total) }
                    }
                )
            } catch (e: Throwable) {
                -1
            }
            dialog.dismiss()
            if (cancelled) return@launch
            val msg = when {
                saved <= 0 -> getString(R.string.extract_failed)
                folder == null -> getString(R.string.extract_images_done, saved)
                else -> getString(R.string.extract_images_done_folder, saved)
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            if (saved > 0) closePanelForce()
        }
    }

    /** Pictures/PDFWriter in the gallery; the file becomes visible once the stream is closed. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun galleryOutput(name: String, mime: String): OutputStream? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDFWriter")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        val os = contentResolver.openOutputStream(uri) ?: return null
        return object : FilterOutputStream(os) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                super.close()
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
    }

    private fun folderOutput(tree: Uri, name: String, mime: String): OutputStream? = try {
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        DocumentsContract.createDocument(contentResolver, dir, mime, name)?.let { contentResolver.openOutputStream(it) }
    } catch (e: Exception) {
        null
    }

    private fun exportCopy(dest: Uri, flatten: Boolean = false, password: String? = null) {
        val src = localCopy ?: return
        val snap = snapshotPages()
        val snapBookmarks = snapshotBookmarks()
        App.scope.launch {
            val ok = try {
                PdfAnnotationIO.write(
                    applicationContext, src, snap, snapBookmarks, dest,
                    flatten = flatten, password = password
                )
                true
            } catch (e: Throwable) {
                false
            }
            val msg = when {
                !ok -> R.string.save_pdf_failed
                !password.isNullOrEmpty() -> R.string.export_protected_done
                flatten -> R.string.export_flat_done
                else -> R.string.export_done
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ================================================================ opening

    private fun launchPicker() {
        pickPdf.launch(arrayOf("application/pdf"))
    }

    /** Writes the new blank PDF where the user chose, then opens it in a new tab. */
    private fun writeNewPdf(uri: Uri, landscape: Boolean, template: com.titipong.pdfwriter.pdf.PageTemplate) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(cacheDir, "new.pdf")
                    com.titipong.pdfwriter.pdf.PdfTemplates.create(tmp, template, landscape)
                    contentResolver.openOutputStream(uri, "w")?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                        ?: throw IOException("Cannot write")
                    tmp.delete()
                    true
                } catch (e: Throwable) {
                    false
                }
            }
            if (!ok) {
                Toast.makeText(this@MainActivity, R.string.new_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            openPdf(uri, remember = persistPermission(uri), silent = false)
        }
    }

    private fun persistPermission(uri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            contentResolver.takePersistableUriPermission(uri, rw)
            true
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    private fun canWrite(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "rw")?.close()
        true
    } catch (e: Exception) {
        false
    }

    private fun queryName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        } catch (e: Exception) {
        }
        return name ?: uri.lastPathSegment ?: "document.pdf"
    }

    private class Opened(
        val doc: PdfDocumentRenderer,
        val copy: File,
        val pages: MutableMap<Int, PageAnnotations>,
        val bookmarks: MutableList<Bookmark>,
        val outline: List<OutlineEntry>,
        val writable: Boolean,
        val needsPdfWrite: Boolean,
        /** Size/date of the source file when copied (null = the private copy was reused). */
        val copiedMeta: String? = null,
        val hadSidecar: Boolean = true,
        val usedPassword: String? = null
    )

    private fun openPdf(
        uri: Uri,
        remember: Boolean,
        silent: Boolean,
        force: Boolean = false,
        password: String? = null,
        reuseCopy: Boolean = false
    ) {
        // Already the open document: nothing to load.
        if (!force && document != null && uri.toString() == docUri?.toString()) {
            refreshTabs()
            return
        }
        lifecycleScope.launch {
            // The same file reached through a different link (e.g. Recent vs. My Files): go to its tab.
            val newName = queryName(uri)
            val twin = if (force) null else tabs.firstOrNull { it.uri != uri.toString() && it.name == newName }
                ?.takeIf { withContext(Dispatchers.IO) { sameFile(uri, Uri.parse(it.uri)) } }
            if (twin != null) {
                if (twin.uri == docUri?.toString()) {
                    refreshTabs()
                    Toast.makeText(this@MainActivity, R.string.already_open, Toast.LENGTH_SHORT).show()
                } else {
                    openPdf(Uri.parse(twin.uri), remember = true, silent = false)
                }
                return@launch
            }
            saveNow()
            writePdfNow()
            setStatus(R.string.loading)
            val name = queryName(uri)
            val key = AnnotationStorage.keyFor(uri.toString())
            val effectivePassword = password ?: tabPasswords[key]
            rememberPage()
            val keep = tabs.map { AnnotationStorage.keyFor(it.uri) + ".pdf" }.toSet() +
                listOfNotNull(localCopy?.name)
            val opened = try {
                withContext(Dispatchers.IO) { loadDocument(uri, key, keep, effectivePassword, reuseCopy) }
            } catch (e: PdfAnnotationIO.PasswordRequiredException) {
                binding.tvSaveState.text = ""
                promptOpenPassword(
                    name = name,
                    wrongPassword = e.wrongPassword,
                    onSubmit = { entered ->
                        openPdf(uri, remember = remember, silent = false, force = true, password = entered, reuseCopy = true)
                    },
                    onCancel = {
                        if (tabs.none { it.uri == uri.toString() } && localCopy?.name != "$key.pdf") {
                            File(File(filesDir, "docs"), "$key.pdf").delete()
                        }
                        if (document != null) {
                            setStatus(if (pdfWritable) R.string.saved_in_pdf else R.string.read_only_file)
                        } else {
                            binding.tvSaveState.text = ""
                        }
                        refreshTabs()
                    }
                )
                return@launch
            } catch (e: Exception) {
                binding.tvSaveState.text = ""
                // A tab whose file is gone or no longer accessible is removed.
                tabs.removeAll { it.uri == uri.toString() }
                saveTabs()
                refreshTabs()
                if (silent) {
                    prefs.edit().remove(PREF_LAST_URI).apply()
                } else {
                    Toast.makeText(this@MainActivity, R.string.open_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            document?.close()
            document = opened.doc
            startMeasuring(opened.doc)
            docUri = uri
            docName = name
            docKey = key
            localCopy = opened.copy
            pdfWritable = opened.writable
            docPassword = opened.usedPassword
            if (opened.usedPassword != null) {
                tabPasswords[key] = opened.usedPassword
                prefs.edit().putBoolean("enc_$key", true).apply()
            } else {
                tabPasswords.remove(key)
                prefs.edit().remove("enc_$key").apply()
            }
            pages = opened.pages
            bookmarks = opened.bookmarks
            outline = opened.outline
            history.clear()
            dirty = false
            changeVersion = 0
            pdfSavedVersion = 0

            readingTexts = null
            clearSearch(resetIndex = true)
            binding.emptyView.visibility = View.GONE
            opened.copiedMeta?.let { prefs.edit().putString("meta_$key", it).apply() }
            if (!opened.hadSidecar) {
                // So the next open of this file can skip parsing the PDF.
                val json = AnnotationStorage.toJson(pages, bookmarks, synced = true)
                val seq = ++saveSeq
                withContext(Dispatchers.IO) {
                    try { AnnotationStorage.save(applicationContext, key, json, seq) } catch (e: Exception) { }
                }
            }
            addTab(uri, name)
            applyView(prefs.getInt("page_$key", 0).coerceIn(0, (opened.doc.pageCount - 1).coerceAtLeast(0)))
            if (interactionView) prepareTextIndex()
            supportActionBar?.title = getString(R.string.app_name) // the file name is on the tab
            setStatus(if (opened.writable) R.string.saved_in_pdf else R.string.read_only_file)
            if (!opened.writable) {
                Snackbar.make(binding.root, R.string.read_only_hint, 10000)
                    .setAction(R.string.allow_saving) {
                        readOnlyUri = uri
                        Toast.makeText(this@MainActivity, R.string.pick_same_file, Toast.LENGTH_LONG).show()
                        grantWrite.launch(arrayOf("application/pdf"))
                    }.show()
            }
            binding.recycler.post { updatePageIndicator() }
            if (remember) prefs.edit().putString(PREF_LAST_URI, uri.toString()).apply()

            if (!prefs.getBoolean(PREF_COLOR_HINT_SHOWN, false)) {
                prefs.edit().putBoolean(PREF_COLOR_HINT_SHOWN, true).apply()
                Snackbar.make(binding.root, R.string.color_hint, Snackbar.LENGTH_LONG).show()
            }

            // Ink saved in the app but not yet in the file (e.g. the app was killed mid-save): write it now.
            if (opened.needsPdfWrite && opened.writable) {
                changeVersion = 1
                writePdfNow()
            }
        }
    }

    private fun promptOpenPassword(
        name: String,
        wrongPassword: Boolean,
        onSubmit: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        val pad = (20 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val til = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            hint = getString(R.string.password_hint)
            if (wrongPassword) error = getString(R.string.password_incorrect)
        }
        val input = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            maxLines = 1
        }
        til.addView(input)
        input.doAfterTextChanged { til.error = null }
        root.addView(til)

        var submitted = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.password_required_title)
            .setMessage(getString(R.string.password_required_msg, name))
            .setView(root)
            .setPositiveButton(R.string.open, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (!submitted) {
                    submitted = true
                    onCancel()
                }
            }
            .setOnCancelListener {
                if (!submitted) {
                    submitted = true
                    onCancel()
                }
            }
            .create()

        fun trySubmit() {
            val pw = input.text?.toString() ?: ""
            if (pw.isEmpty()) {
                til.error = getString(R.string.password_empty)
                return
            }
            submitted = true
            dialog.dismiss()
            onSubmit(pw)
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                trySubmit()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { trySubmit() }
            input.requestFocus()
        }
        dialog.show()
    }

    /**
     * Whether two links point at the same file: same size, and the same first and last 64 KB.
     * (Different apps hand out different links for one file.)
     */
    private fun sameFile(a: Uri, b: Uri): Boolean = try {
        fun fingerprint(u: Uri): Pair<Long, Int>? {
            contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
                val size = pfd.statSize
                java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                    val ch = input.channel
                    val md = java.security.MessageDigest.getInstance("SHA-1")
                    val chunk = 64 * 1024
                    val buf = java.nio.ByteBuffer.allocate(chunk)
                    for (pos in listOf(0L, maxOf(0L, size - chunk))) {
                        buf.clear()
                        ch.position(pos)
                        while (buf.hasRemaining() && ch.read(buf) > 0) { }
                        md.update(buf.array(), 0, buf.position())
                    }
                    return size to md.digest().contentHashCode()
                }
            }
            return null
        }
        val fa = fingerprint(a)
        fa != null && fa == fingerprint(b)
    } catch (e: Exception) {
        false
    }

    /** Size and modification time of the source file, to tell whether it changed since we last saw it. */
    private fun countItems(pages: Map<Int, PageAnnotations>): Int =
        pages.values.sumOf { it.strokes.size + it.texts.size + it.highlights.size + it.images.size }

    private fun sourceMeta(uri: Uri): String? {
        var size: Long? = null
        var modified = 0L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        } catch (e: Exception) {
        }
        try {
            contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) modified = c.getLong(0)
            }
        } catch (e: Exception) {
        }
        return size?.let { "$it:$modified" }
    }

    /**
     * Runs on IO: opens the renderer and loads ink + bookmarks.
     * Fast path (switching tabs): if the file hasn't changed since this app last copied or saved it,
     * the private copy and the app's saved ink are reused, so nothing is copied or parsed.
     */
    private suspend fun loadDocument(
        uri: Uri,
        key: String,
        keepFiles: Set<String>,
        password: String?,
        reuseCopy: Boolean
    ): Opened {
        val dir = File(filesDir, "docs").apply { mkdirs() }
        val copy = File(dir, "$key.pdf")
        val meta = sourceMeta(uri)
        // The same file can arrive through different links (My Files, Recent, the picker...):
        // ink saved under another link for this file (same name and size) is found too.
        val aliasPref = meta?.let { "alias_${queryName(uri)}|${it.substringBefore(':')}" }
        var side = AnnotationStorage.load(applicationContext, key)
        if (side == null && aliasPref != null) {
            prefs.getString(aliasPref, null)?.takeIf { it != key }?.let { other ->
                side = AnnotationStorage.load(applicationContext, other)
                side?.let { sd ->
                    try {
                        AnnotationStorage.save(applicationContext, key, AnnotationStorage.toJson(sd.pages, sd.bookmarks, sd.synced), ++saveSeq)
                    } catch (e: Exception) { }
                }
            }
        }
        if (aliasPref != null) prefs.edit().putString(aliasPref, key).apply()
        val needsPromptBeforeFast = prefs.getBoolean("enc_$key", false) && password == null
        val fast = !reuseCopy && !needsPromptBeforeFast && meta != null && side != null && copy.exists() &&
            meta == prefs.getString("meta_$key", null)

        if (!fast && !(reuseCopy && copy.exists())) {
            val tmp = File(dir, "$key.tmp")
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(copy)) {
                copy.delete()
                tmp.renameTo(copy)
            }
        }
        // Keep the copies of open tabs (and one whose save may still be running).
        dir.listFiles()?.forEach { if (it.name != copy.name && it.name !in keepFiles) it.delete() }

        var wasEncrypted = false
        val doc = if (password != null && !fast) {
            wasEncrypted = PdfAnnotationIO.decryptInPlace(copy, password)
            PdfDocumentRenderer.open(
                ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY),
                PdfDocumentRenderer.diskCacheFor(cacheDir, copy)
            )
        } else {
            try {
                PdfDocumentRenderer.open(
                    ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY),
                    PdfDocumentRenderer.diskCacheFor(cacheDir, copy)
                )
            } catch (e: Throwable) {
                if (e !is SecurityException && e !is IOException) throw e
                val unlocked = try {
                    PdfAnnotationIO.decryptInPlace(copy, null)
                } catch (pe: PdfAnnotationIO.PasswordRequiredException) {
                    throw pe
                } catch (ignored: Throwable) {
                    false
                }
                if (!unlocked) throw e
                PdfDocumentRenderer.open(
                    ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY),
                    PdfDocumentRenderer.diskCacheFor(cacheDir, copy)
                )
            }
        }
        val usedPassword = if (fast || wasEncrypted) password else null
        val writable = canWrite(uri)

        val fs = side
        if (fast && fs != null) {
            return Opened(
                doc, copy, fs.pages, fs.bookmarks, AnnotationStorage.loadToc(applicationContext, key),
                writable, needsPdfWrite = !fs.synced, usedPassword = usedPassword
            )
        }

        val fromPdf = try {
            PdfAnnotationIO.read(copy)
        } catch (e: Throwable) {
            null
        }
        val toc = fromPdf?.outline ?: emptyList()
        AnnotationStorage.saveToc(applicationContext, key, toc)

        // The PDF library can't read this file: keep everything in the app only, never touch the file.
        if (fromPdf == null) {
            return Opened(
                doc, copy, side?.pages ?: mutableMapOf(), side?.bookmarks ?: mutableListOf(), toc,
                writable = false, needsPdfWrite = false, copiedMeta = meta, hadSidecar = true,
                usedPassword = usedPassword
            )
        }
        // The PDF file is the source of truth (it may have been edited in another app),
        // unless the app holds newer changes that never made it into the file.
        val sd = side
        if (sd != null && (!sd.synced || !writable)) {
            return Opened(
                doc, copy, sd.pages, sd.bookmarks, toc, writable,
                needsPdfWrite = !sd.synced, copiedMeta = meta, usedPassword = usedPassword
            )
        }
        // The file has less of this app's ink than was saved into it: the save didn't stick
        // (the file app dropped it). Never lose the work: keep the app's copy and write it again.
        if (sd != null && countItems(sd.pages) > countItems(fromPdf.pages)) {
            return Opened(
                doc, copy, sd.pages, sd.bookmarks, toc, writable,
                needsPdfWrite = writable, copiedMeta = meta, usedPassword = usedPassword
            )
        }
        return Opened(
            doc, copy, fromPdf.pages, fromPdf.bookmarks, toc, writable,
            needsPdfWrite = false, copiedMeta = meta, hadSidecar = false, usedPassword = usedPassword
        )
    }

    // ================================================================ document tabs

    private class DocTab(val uri: String, var name: String)

    private val tabs = mutableListOf<DocTab>()
    private var suppressTabEvents = false

    private fun loadTabs() {
        tabs.clear()
        try {
            val arr = org.json.JSONArray(prefs.getString(PREF_TABS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tabs.add(DocTab(o.getString("u"), o.optString("n")))
            }
        } catch (e: Exception) {
        }
        binding.docTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (suppressTabEvents) return
                val t = tabs.getOrNull(tab.position) ?: return
                if (t.uri != docUri?.toString()) openPdf(Uri.parse(t.uri), remember = true, silent = false)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        refreshTabs()
    }

    private fun saveTabs() {
        val arr = org.json.JSONArray()
        for (t in tabs) arr.put(org.json.JSONObject().apply { put("u", t.uri); put("n", t.name) })
        prefs.edit().putString(PREF_TABS, arr.toString()).apply()
    }

    private fun addTab(uri: Uri, name: String) {
        val u = uri.toString()
        val existing = tabs.firstOrNull { it.uri == u }
        if (existing != null) {
            existing.name = name
        } else {
            tabs.add(DocTab(u, name))
            while (tabs.size > MAX_TABS) tabs.removeAt(0)
        }
        saveTabs()
        refreshTabs()
    }

    private fun refreshTabs() {
        suppressTabEvents = true
        val bar = binding.docTabs
        bar.removeAllTabs()
        val active = docUri?.toString()
        for (t in tabs) {
            val tab = bar.newTab()
            tab.customView = tabView(t, t.uri == active)
            bar.addTab(tab, t.uri == active)
        }
        bar.visibility = if (tabs.isEmpty()) View.GONE else View.VISIBLE
        suppressTabEvents = false
    }

    private fun tabView(t: DocTab, active: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(android.widget.TextView(this).apply {
            text = t.name.removeSuffix(".pdf")
            maxWidth = (200 * density).toInt()
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 13f
            setTextColor(Color.parseColor(if (active) "#1D4ED8" else "#475569"))
        })
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = getString(R.string.close_tab)
            val p = (6 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
            setOnClickListener { closeTab(t) }
        })
        return row
    }

    private fun closeTab(t: DocTab) {
        val index = tabs.indexOf(t)
        if (index < 0) return
        val wasActive = t.uri == docUri?.toString()
        if (wasActive) rememberPage()
        tabPasswords.remove(AnnotationStorage.keyFor(t.uri))
        tabs.removeAt(index)
        saveTabs()
        if (wasActive) {
            val next = tabs.getOrNull(index) ?: tabs.getOrNull(index - 1)
            if (next != null) {
                openPdf(Uri.parse(next.uri), remember = true, silent = false)
            } else {
                closeDocument()
            }
        }
        refreshTabs()
    }

    /** Back to the empty screen (last tab closed). Ink is saved first. */
    private fun closeDocument() {
        saveNow()
        writePdfNow()
        clearSelection()
        clearSearch(resetIndex = true)
        document?.close()
        document = null
        searchIndexStore?.close()
        searchIndexStore = null
        measureJob?.cancel()
        adapter = null
        binding.recycler.adapter = null
        docUri = null
        docKey = null
        localCopy = null
        docPassword = null
        pages = mutableMapOf()
        bookmarks = mutableListOf()
        outline = emptyList()
        history.clear()
        binding.emptyView.visibility = View.VISIBLE
        supportActionBar?.title = getString(R.string.app_name)
        supportActionBar?.subtitle = null
        binding.tvSaveState.text = ""
        prefs.edit().remove(PREF_LAST_URI).apply()
    }

    /** Remembers where the reader was in the current file. */
    private fun rememberPage() {
        val key = docKey ?: return
        val page = currentPage()
        if (page >= 0) prefs.edit().putInt("page_$key", page).apply()
    }

    private fun currentPage(): Int {
        if (viewSettings.reading && binding.readingPanel.visibility == View.VISIBLE) {
            val rl = binding.readingList.layoutManager as? LinearLayoutManager
            val p = rl?.findFirstVisibleItemPosition() ?: -1
            if (p >= 0) return p
        }
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return -1
        var pos = if (binding.recycler.horizontalPaging) lm.findFirstCompletelyVisibleItemPosition() else -1
        if (pos < 0) pos = lm.findFirstVisibleItemPosition()
        if (pos < 0) return -1
        return adapter?.firstPageAt(pos)?.takeIf { it >= 0 } ?: pos
    }

    // ================================================================ view / annotate mode

    /** Interaction modes, listed in the mode picker. Add new modes here. */
    private enum class Mode(val label: Int, val description: Int) {
        VIEW(R.string.mode_view, R.string.mode_view_desc),
        ANNOTATE(R.string.mode_annotate, R.string.mode_annotate_desc)
    }

    private val currentMode: Mode get() = if (interactionView) Mode.VIEW else Mode.ANNOTATE

    private fun setupModeToggle() {
        interactionView = prefs.getBoolean(PREF_INTERACTION_VIEW, false)
        prefs.getString(PREF_HL_PALETTE, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.let { saved ->
            if (saved.size == markupPalette.size) {
                markupPalette.clear()
                markupPalette.addAll(saved)
            }
        }
        hlColorIndex = prefs.getInt(PREF_HL_COLOR, 0).coerceIn(0, markupPalette.size - 1)

        val dot = (34 * density).toInt()
        val gap = (4 * density).toInt()
        markupPalette.indices.forEach { i ->
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(dot, dot).apply { marginEnd = gap }
            v.contentDescription = getString(R.string.color)
            // Tap = use; tap the selected one again or long-press = edit it.
            v.setOnClickListener { if (i == hlColorIndex) editMarkupColor(i) else selectHighlightColor(i) }
            v.setOnLongClickListener {
                editMarkupColor(i)
                true
            }
            binding.hlColorRow.addView(v)
            hlColorViews.add(v)
        }
        selectHighlightColor(hlColorIndex)

        binding.btnMode.setOnClickListener { showModeMenu() }
        binding.btnSelCopy.setOnClickListener { copySelection() }
        binding.btnSelHighlight.setOnClickListener { markupSelection(underline = false) }
        binding.btnSelUnderline.setOnClickListener { markupSelection(underline = true) }
        binding.btnHlRecolor.setOnClickListener { recolorTappedMarkup() }
        binding.btnHlDelete.setOnClickListener { deleteTappedHighlight() }
        binding.btnNoteEdit.setOnClickListener { editTappedNote() }
        binding.btnSelClose.setOnClickListener { clearSelection() }
        showModeTools()
    }

    private fun showModeMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnMode)
        Mode.values().forEachIndexed { i, m ->
            popup.menu.add(0, i, i, getString(m.label)).apply {
                isCheckable = true
                isChecked = m == currentMode
            }
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            setInteraction(view = Mode.values()[item.itemId] == Mode.VIEW)
            true
        }
        popup.show()
    }

    private fun setInteraction(view: Boolean) {
        if (!view && chromeHidden) setChromeHidden(false)
        interactionView = view
        prefs.edit().putBoolean(PREF_INTERACTION_VIEW, view).apply()
        clearSelection()
        showModeTools()
        if (view) prepareTextIndex()
    }

    /** View mode's fast scrubber: shown when a document is open. */
    private fun updateScrubber() {
        val doc = document
        binding.pageScrubber.visibility =
            if (interactionView && doc != null && doc.pageCount > 1) View.VISIBLE else View.GONE
        binding.pageScrubber.pageCount = doc?.pageCount ?: 0
        val p = currentPage()
        if (p >= 0) binding.pageScrubber.page = p
    }

    /** Jumps straight to a page while scrubbing (keeps the zoom level). */
    private fun scrubToPage(page: Int) {
        if (viewSettings.reading) {
            (binding.readingList.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(page, 0)
        } else {
            val pos = adapter?.positionOf(page) ?: return
            (binding.recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
        }
        clearSelection()
        binding.recycler.post { updatePageIndicator() }
    }

    private fun showModeTools() {
        updateScrubber()
        binding.btnMode.setText(currentMode.label)
        binding.viewTools.visibility = if (interactionView) View.VISIBLE else View.GONE
        binding.annotateTools.visibility = if (interactionView) View.GONE else View.VISIBLE
        toolRow2?.visibility = binding.annotateTools.visibility
        toolsRow1?.visibility = binding.annotateTools.visibility
    }

    private fun selectHighlightColor(index: Int) {
        hlColorIndex = index
        prefs.edit().putInt(PREF_HL_COLOR, index).apply()
        hlColorViews.forEachIndexed { i, v ->
            v.background = swatch(markupPalette[i], i == hlColorIndex)
        }
    }

    private fun editMarkupColor(index: Int) {
        ColorPicker.show(this, markupPalette[index], hueOnly = true, light = true,
            opacityDefault = com.titipong.pdfwriter.model.Opacity.TEXT_DEFAULT) { picked ->
            markupPalette[index] = picked
            prefs.edit().putString(PREF_HL_PALETTE, markupPalette.joinToString(",")).apply()
            selectHighlightColor(index)
        }
    }

    /** Text positions are needed to select text; build them in the background. */
    /** Reads the text of the page being viewed (and its neighbours) so it can be selected at once. */
    private fun prepareTextIndex(force: Boolean = false) {
        val idx = searchIndex ?: return
        val here = currentPage().coerceAtLeast(0)
        if (!force && here == textPreparedAround) return
        textPreparedAround = here
        textPrepJob?.cancel()
        textPrepJob = lifecycleScope.launch {
            try {
                for (p in intArrayOf(here, here + 1, here - 1, here + 2)) idx.ensurePage(p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
            }
        }
    }

    override val viewMode: Boolean get() = interactionView

    override fun selectBegin(view: PdfPageView, page: Int, nx: Float, ny: Float, byWord: Boolean): Boolean {
        clearSelection()
        val idx = searchIndex ?: return false
        // Text boxes (Text tool) can be selected and highlighted like the PDF's text.
        idx.setNotes(page, view.noteChars())
        if (!idx.isReady(page)) {
            lifecycleScope.launch {
                try { idx.ensurePage(page) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { }
            }
            Toast.makeText(this, R.string.text_preparing, Toast.LENGTH_SHORT).show()
            return false
        }
        if (!idx.hasText(page)) {
            Snackbar.make(binding.root, R.string.no_text_here, Snackbar.LENGTH_LONG)
                .setAction(R.string.ocr) { showOcrDialog() }
                .show()
            return false
        }
        val i = idx.charAt(page, nx, ny, maxDistance = 0.03f)
        if (i < 0) return false
        val range = if (byWord) idx.wordAt(page, i) else i..i
        selPage = page
        selAnchorStart = range.first
        selAnchorEnd = range.last
        selFocus = range.last
        selView = view
        showSelection()
        return true
    }

    override fun selectMove(view: PdfPageView, page: Int, nx: Float, ny: Float) {
        val idx = searchIndex ?: return
        if (page != selPage) return
        val i = idx.charAt(page, nx, ny)
        if (i >= 0) selFocus = i
        showSelection()
    }

    override fun selectEnd(view: PdfPageView, page: Int) {
        if (selPage < 0) return
        val (a, b) = selectionRange()
        binding.btnSelCopy.visibility = View.VISIBLE
        binding.btnSelHighlight.visibility = View.VISIBLE
        binding.btnSelUnderline.visibility = View.VISIBLE
        binding.btnHlRecolor.visibility = View.GONE
        binding.btnHlDelete.visibility = View.GONE
        val idx = searchIndex ?: return
        showBarAt(view, idx.rangeRects(selPage, a, b))
    }

    override fun viewTap(view: PdfPageView, page: Int, nx: Float, ny: Float) {
        val hadPopup = binding.selectionBar.visibility == View.VISIBLE
        clearSelection()
        val padHit = 0.004f
        val onMarkup = pages[page]?.highlights?.any { h ->
            h.rects.any { r -> nx >= r.left - padHit && nx <= r.right + padHit && ny >= r.top - padHit && ny <= r.bottom + padHit }
        } == true
        if (!onMarkup) {
            // A tap that only dismisses the popup shouldn't also hide the menus.
            if (hadPopup) tapHandledAt = android.os.SystemClock.uptimeMillis()
            return // otherwise onPageSingleTap() toggles the menus (after ruling out a double tap)
        }
        tapHandledAt = android.os.SystemClock.uptimeMillis()
        val pad = 0.004f
        val hl = pages[page]?.highlights?.lastOrNull { h ->
            h.rects.any { r -> nx >= r.left - pad && nx <= r.right + pad && ny >= r.top - pad && ny <= r.bottom + pad }
        } ?: return
        tappedHighlight = page to hl
        binding.btnSelCopy.visibility = View.GONE
        binding.btnSelHighlight.visibility = View.GONE
        binding.btnSelUnderline.visibility = View.GONE
        binding.btnHlRecolor.visibility = View.VISIBLE
        binding.btnHlDelete.visibility = View.VISIBLE
        showBarAt(view, hl.rects)
    }

    override fun onTextTapped(view: PdfPageView, page: Int, note: TextNote) {
        clearSelection()
        tappedNote = page to note
        noteView = view
        view.editingNote = note // dotted frame: drag to move, corners to resize
        binding.btnSelCopy.visibility = View.GONE
        binding.btnSelHighlight.visibility = View.GONE
        binding.btnSelUnderline.visibility = View.GONE
        binding.btnHlRecolor.visibility = View.VISIBLE // quick color change
        binding.btnNoteEdit.visibility = View.VISIBLE
        binding.btnHlDelete.visibility = View.VISIBLE
        showBarAt(view, listOf(view.textBox(note)))
    }

    // ---------------------------------------------------------------- popup bar placement

    /** What the floating bar is attached to: a page view and normalized boxes on it. */
    private var barAnchor: Pair<PdfPageView, List<RectF>>? = null

    private fun showBarAt(view: PdfPageView, rects: List<RectF>) {
        barAnchor = view to rects
        val bar = binding.selectionBar
        bar.visibility = View.VISIBLE
        bar.alpha = 0f // invisible until placed (its size is known after layout)
        bar.post { positionBar() }
    }

    /** Puts the bar just above the anchored text (or below it, if there's no room above). */
    private fun positionBar() {
        val bar = binding.selectionBar
        val (view, rects) = barAnchor ?: return
        if (bar.visibility != View.VISIBLE || rects.isEmpty() || !view.isAttachedToWindow) return
        val rv = binding.recycler
        val offset = android.graphics.Rect()
        try {
            rv.offsetDescendantRectToMyCoords(view, offset)
        } catch (e: IllegalArgumentException) {
            return // the page view was recycled
        }
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (r in rects) {
            for ((nx, ny) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
                val p = view.viewPointOf(nx, ny)
                val sp = rv.toScreen(offset.left + p.x, offset.top + p.y)
                top = minOf(top, sp.y); bottom = maxOf(bottom, sp.y)
                left = minOf(left, sp.x); right = maxOf(right, sp.x)
            }
        }
        val margin = 8 * density
        // A framed item (text box, picture, lasso…) has its frame and handles around the box:
        // keep the menu clear of them.
        val framed = noteView != null && noteView === view && view.editingNote != null
        val gap = if (framed) 26 * density * binding.recycler.scale.coerceAtLeast(1f) else margin
        val parentW = (bar.parent as View).width.toFloat()
        val parentH = (bar.parent as View).height.toFloat()
        val x = ((left + right) / 2f - bar.width / 2f).coerceIn(margin, (parentW - bar.width - margin).coerceAtLeast(margin))
        val above = top - bar.height - gap
        val below = bottom + gap
        var y = when {
            above >= margin -> above
            below + bar.height <= parentH - margin -> below
            // No room above or below (a tall item): keep it at the top or bottom edge, whichever
            // covers less of the item.
            top - margin > parentH - bottom - margin -> margin
            else -> parentH - bar.height - margin
        }
        y = y.coerceIn(margin, (parentH - bar.height - margin).coerceAtLeast(margin))
        bar.translationX = x
        bar.translationY = y
        bar.alpha = 1f
    }

    // ---------------------------------------------------------------- view mode: tap to hide menus

    private var chromeHidden = false
    /** When a tap was used for something else (opening / dismissing a popup), it doesn't toggle the menus. */
    private var tapHandledAt = 0L

    /** View mode: a single tap anywhere hides or shows the menus. */
    private fun onPageSingleTap() {
        if (!interactionView) return
        if (android.os.SystemClock.uptimeMillis() - tapHandledAt < 700) return
        setChromeHidden(!chromeHidden)
    }

    /** Hides / shows the top bar, tabs, tool strip and the system bars (view mode, single tap). */
    private fun setChromeHidden(hidden: Boolean) {
        chromeHidden = hidden
        val v = if (hidden) View.GONE else View.VISIBLE
        binding.toolbar.visibility = v
        binding.toolStrip.visibility = v
        binding.chromeDivider.visibility = v
        binding.docTabs.visibility = if (hidden || tabs.isEmpty()) View.GONE else View.VISIBLE
        if (hidden && binding.searchBar.visibility == View.VISIBLE) closeSearch()
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            ctl.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun selectionRange(): Pair<Int, Int> =
        if (selFocus >= selAnchorStart) selAnchorStart to maxOf(selAnchorEnd, selFocus)
        else selFocus to selAnchorEnd

    private fun showSelection() {
        val idx = searchIndex ?: return
        if (selPage < 0) return
        val (a, b) = selectionRange()
        selView?.selectionRects = idx.rangeRects(selPage, a, b)
    }

    override fun onNoteDeselect() = clearSelection()

    override fun onNoteDragStart() {
        barAnchor = null
        binding.selectionBar.visibility = View.GONE
        // Read this page's text in the background, so later drags can also align with its lines.
        val page = noteView?.pageIndex ?: return
        val idx = searchIndex ?: return
        if (!idx.isReady(page)) lifecycleScope.launch {
            try { idx.ensurePage(page) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { }
        }
    }

    override fun guideBoxes(page: Int): List<RectF> =
        searchIndexStore?.takeIf { it.isReady(page) }?.lineBoxes(page) ?: emptyList()

    override fun onNoteChanged(view: PdfPageView, page: Int, before: Any, after: Any) {
        history.addLast(page to NoteEdit(before, after))
        if (history.size > MAX_HISTORY) history.removeFirst()
        markDirty()
        // Keep it selected, menu above its new place.
        if (after is TextNote) onTextTapped(view, page, after)
        if (after is ImageNote) onImageTapped(view, page, after)
        if (after is InkStroke) onStrokeTapped(view, page, after)
        if (after is com.titipong.pdfwriter.model.ItemGroup) onGroupSelected(view, page, after)
    }

    /** Undo record for a text note / picture that was moved, resized or edited. */
    private class NoteEdit(val before: Any, val after: Any)

    private var tappedImage: Pair<Int, ImageNote>? = null
    private var tappedStroke: Pair<Int, InkStroke>? = null

    // ---------------------------------------------------------------- handwriting to text

    private val PREF_HW_LANG = "hw_lang"
    private var handwritingLang: String = "th"
        get() = prefs.getString(PREF_HW_LANG, field) ?: field
    private var hwView: PdfPageView? = null
    private var hwPage = -1
    private var hwJob: Job? = null

    /** A stroke written with "Write to text": convert everything written once the pen rests. */
    override fun onHandwritingStroke(view: PdfPageView, page: Int, stroke: InkStroke) {
        if (hwView != null && (hwView !== view || hwPage != page)) flushHandwriting() // other page: convert that first
        hwView = view
        hwPage = page
        hwJob?.cancel()
        hwJob = lifecycleScope.launch {
            delay(1300)
            flushHandwriting()
        }
    }

    private fun flushHandwriting() {
        hwJob?.cancel()
        val view = hwView ?: return
        val page = hwPage
        hwView = null
        val strokes = ArrayList(view.pendingInk)
        view.pendingInk.clear()
        if (strokes.isEmpty()) return
        lifecycleScope.launch {
            val note = recognizeToNote(strokes)
            if (note == null) {
                // Not understood: keep the writing as ink so nothing is lost.
                val pa = annotationsFor(page)
                strokes.forEach { pa.strokes.add(it); onItemAdded(page, it) }
            } else {
                annotationsFor(page).texts.add(note)
                onItemAdded(page, note)
            }
            view.invalidate()
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            markDirty()
        }
    }

    /** Recognizes handwriting into a text box placed where it was written (same color, similar size). */
    private suspend fun recognizeToNote(strokes: List<InkStroke>): TextNote? {
        val model = com.titipong.pdfwriter.ocr.Handwriting.model(handwritingLang)
        if (model == null) {
            Toast.makeText(this, R.string.hw_unsupported, Toast.LENGTH_LONG).show()
            return null
        }
        val lines = try {
            if (!com.titipong.pdfwriter.ocr.Handwriting.isDownloaded(model)) {
                ensureHandwritingModel()
                return null
            }
            com.titipong.pdfwriter.ocr.Handwriting.recognize(model, strokes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.makeText(this, R.string.hw_failed, Toast.LENGTH_SHORT).show()
            return null
        }
        if (lines.isEmpty()) {
            Toast.makeText(this, R.string.hw_failed, Toast.LENGTH_SHORT).show()
            return null
        }
        val first = lines.first().box
        // Handwriting boxes include ascenders / marks: the text size is a bit smaller than a line.
        val size = (lines.map { it.box.height() }.average().toFloat() * 0.7f).coerceIn(0.008f, 0.2f)
        val color = strokes.firstOrNull()?.color ?: Color.BLACK
        val left = lines.minOf { it.box.left }
        return TextNote(lines.joinToString("\n") { it.text }, left, first.top + size, color, size)
    }

    /** The handwriting model for the chosen language must be on the device (asks to download it once). */
    private fun ensureHandwritingModel() {
        val lang = handwritingLang
        val model = com.titipong.pdfwriter.ocr.Handwriting.model(lang)
        if (model == null) {
            Toast.makeText(this, R.string.hw_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val have = try { com.titipong.pdfwriter.ocr.Handwriting.isDownloaded(model) } catch (e: Throwable) { false }
            if (have) return@launch
            val name = getString(if (lang == "en") R.string.lang_english else R.string.lang_thai)
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.hw_download_title)
                .setMessage(getString(R.string.hw_download_message, name))
                .setPositiveButton(R.string.download) { _, _ -> downloadHandwritingModel(model) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun downloadHandwritingModel(model: com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hw_downloading)
            .setView(com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
                isIndeterminate = true
                val p = (24 * density).toInt()
                setPadding(p, p / 2, p, p / 2)
            })
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val ok = try {
                com.titipong.pdfwriter.ocr.Handwriting.download(model)
                true
            } catch (e: Throwable) {
                false
            }
            progress.dismiss()
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.hw_ready else R.string.hw_download_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Select menu: convert the handwriting in the lasso selection to a text box. */
    private fun convertSelectedHandwriting() {
        val (page, group) = tappedGroup ?: return
        val ink = group.items.filterIsInstance<InkStroke>().filter { !it.highlighter }
        if (ink.isEmpty()) return
        clearSelection()
        lifecycleScope.launch {
            val note = recognizeToNote(ink) ?: return@launch
            val pa = annotationsFor(page)
            pa.strokes.removeAll(ink.toSet())
            pa.texts.add(note)
            history.addLast(page to ConvertEdit(ink, note))
            if (history.size > MAX_HISTORY) history.removeFirst()
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            markDirty()
        }
    }

    /** Undo record for handwriting converted to text: the ink comes back, the text goes. */
    private class ConvertEdit(val strokes: List<InkStroke>, val note: TextNote)
    private var tappedGroup: Pair<Int, com.titipong.pdfwriter.model.ItemGroup>? = null

    override fun onGroupSelected(view: PdfPageView, page: Int, group: com.titipong.pdfwriter.model.ItemGroup) {
        clearSelection()
        tappedGroup = page to group
        noteView = view
        view.editingNote = group // dotted frame around everything picked: drag to move together
        binding.btnSelCopy.visibility = View.GONE
        binding.btnSelHighlight.visibility = View.GONE
        binding.btnSelUnderline.visibility = View.GONE
        binding.btnNoteEdit.visibility = View.GONE
        // Color: recolors the ink and text boxes in the selection.
        binding.btnHlRecolor.visibility =
            if (group.items.any { it is InkStroke || it is TextNote }) View.VISIBLE else View.GONE
        binding.btnHlDelete.visibility = View.VISIBLE
        // Handwriting (pen ink) in the selection can be turned into text.
        binding.btnSelConvert.visibility =
            if (group.items.any { it is InkStroke && !it.highlighter }) View.VISIBLE else View.GONE
        var box: RectF? = null
        for (it in group.items) {
            val r = when (it) {
                is TextNote -> view.textBox(it)
                is ImageNote -> RectF(it.x, it.y, it.x + it.w, it.y + it.h)
                is InkStroke -> if (it.points.isEmpty()) null else RectF(
                    it.points.minOf { p -> p.x }, it.points.minOf { p -> p.y },
                    it.points.maxOf { p -> p.x }, it.points.maxOf { p -> p.y }
                )
                else -> null
            } ?: continue
            box = box?.apply { union(r) } ?: RectF(r)
        }
        showBarAt(view, listOfNotNull(box))
    }

    /** Undo record for deleting a lasso selection: the items come back. */
    private class GroupDelete(val items: List<Any>)

    override fun onStrokeTapped(view: PdfPageView, page: Int, stroke: InkStroke) {
        clearSelection()
        tappedStroke = page to stroke
        noteView = view
        view.editingNote = stroke // dotted frame; a straight line gets end handles
        binding.btnSelCopy.visibility = View.GONE
        binding.btnSelHighlight.visibility = View.GONE
        binding.btnSelUnderline.visibility = View.GONE
        binding.btnNoteEdit.visibility = View.GONE
        binding.btnHlRecolor.visibility = View.VISIBLE
        binding.btnHlDelete.visibility = View.VISIBLE
        val r = stroke.width / 2f
        showBarAt(view, listOf(RectF(
            stroke.points.minOf { it.x } - r, stroke.points.minOf { it.y } - r,
            stroke.points.maxOf { it.x } + r, stroke.points.maxOf { it.y } + r
        )))
    }

    override fun onImageTapped(view: PdfPageView, page: Int, image: ImageNote) {
        clearSelection()
        tappedImage = page to image
        noteView = view
        view.editingNote = image // dotted frame: drag to move, corners to resize
        binding.btnSelCopy.visibility = View.GONE
        binding.btnSelHighlight.visibility = View.GONE
        binding.btnSelUnderline.visibility = View.GONE
        binding.btnHlRecolor.visibility = View.GONE
        binding.btnNoteEdit.visibility = View.GONE
        binding.btnHlDelete.visibility = View.VISIBLE
        showBarAt(view, listOf(RectF(image.x, image.y, image.x + image.w, image.y + image.h)))
    }

    override fun requestImage(page: Int, x: Float, y: Float) {
        clearSelection()
        pendingImageAt = Triple(page, x, y)
        try {
            pickImage.launch(
                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            pendingImageAt = null
            Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copies the picked picture into the app (rotated upright, at most 2000 px, JPEG unless it has
     * transparency) and places it centered on the tapped spot, 40% of the page width wide.
     */
    private fun placeImage(uri: Uri, page: Int, x: Float, y: Float) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                        val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                        android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                            val big = maxOf(info.size.width, info.size.height)
                            if (big > 2000) {
                                val f = 2000f / big
                                dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                            }
                            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                        var sample = 1
                        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 2000) sample *= 2
                        contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                        } ?: throw IOException("Cannot read")
                    }
                    val dir = File(filesDir, "images").apply { mkdirs() }
                    val alpha = bmp.hasAlpha()
                    val f = File(dir, java.util.UUID.randomUUID().toString() + if (alpha) ".png" else ".jpg")
                    f.outputStream().use {
                        bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    Triple(f, bmp.width, bmp.height)
                } catch (e: Throwable) {
                    null
                }
            }
            if (saved == null) {
                Toast.makeText(this@MainActivity, R.string.image_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (file, iw, ih) = saved
            val aspect = document?.aspects?.getOrNull(page) ?: 1.414f
            var w = 0.4f
            var h = w * ih / iw.coerceAtLeast(1)
            if (h > aspect * 0.8f) { // very tall picture: fit the page height instead
                h = aspect * 0.8f
                w = h * iw / ih.coerceAtLeast(1)
            }
            val left = (x - w / 2f).coerceIn(0f, (1f - w).coerceAtLeast(0f))
            val top = (y - h / 2f).coerceIn(0f, (aspect - h).coerceAtLeast(0f))
            val note = ImageNote(file.path, left, top, w, h)
            annotationsFor(page).images.add(note)
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            onItemAdded(page, note)
            // Select it right away so it can be moved / resized.
            binding.recycler.post {
                adapter?.pageViewFor(binding.recycler, page)?.let { onImageTapped(it, page, note) }
            }
        }
    }

    private var noteView: PdfPageView? = null

    private fun clearSelectionBarExtras() {
        binding.btnSelConvert.visibility = View.GONE
    }

    private fun clearSelection() {
        clearSelectionBarExtras()
        noteView?.editingNote = null
        noteView = null
        selView?.selectionRects = null
        selView = null
        selPage = -1
        tappedHighlight = null
        tappedNote = null
        tappedImage = null
        tappedStroke = null
        tappedGroup = null
        barAnchor = null
        binding.btnNoteEdit.visibility = View.GONE
        binding.selectionBar.visibility = View.GONE
    }

    private fun copySelection() {
        val idx = searchIndex ?: return
        if (selPage < 0) return
        val (a, b) = selectionRange()
        val text = idx.rangeText(selPage, a, b)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PDF", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun markupSelection(underline: Boolean) {
        val idx = searchIndex ?: return
        val page = selPage
        if (page < 0) return
        val (a, b) = selectionRange()
        val rects = idx.rangeRects(page, a, b)
        if (rects.isEmpty()) return
        val hl = TextHighlight(rects, markupPalette[hlColorIndex], idx.rangeText(page, a, b), underline)
        annotationsFor(page).highlights.add(hl)
        clearSelection()
        adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
        onItemAdded(page, hl)
    }

    /** Pick any color for a highlight / underline that's already on the page. */
    private fun recolorTappedMarkup() {
        tappedGroup?.let { (page, group) ->
            val view = noteView
            val first = group.items.firstNotNullOfOrNull { (it as? InkStroke)?.color ?: (it as? TextNote)?.color } ?: Color.BLACK
            ColorPicker.show(this, first) { picked ->
                val pa = pages[page] ?: return@show
                val recolored = com.titipong.pdfwriter.model.ItemGroup(group.items.map {
                    when (it) {
                        is InkStroke -> InkStroke(it.points.toMutableList(), picked, it.width, it.highlighter)
                        is TextNote -> it.copy(color = picked)
                        else -> it
                    }
                })
                group.items.zip(recolored.items).forEach { (o, n) ->
                    when {
                        o is InkStroke && n is InkStroke -> pa.strokes.indexOf(o).takeIf { it >= 0 }?.let { pa.strokes[it] = n }
                        o is TextNote && n is TextNote -> pa.texts.indexOf(o).takeIf { it >= 0 }?.let { pa.texts[it] = n }
                    }
                }
                history.addLast(page to NoteEdit(group, recolored))
                if (history.size > MAX_HISTORY) history.removeFirst()
                adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
                markDirty()
                if (view != null) onGroupSelected(view, page, recolored)
            }
            return
        }
        tappedNote?.let { (page, note) ->
            val view = noteView
            ColorPicker.show(this, note.color) { picked ->
                val list = pages[page]?.texts ?: return@show
                val i = list.indexOf(note)
                if (i < 0) return@show
                val recolored = note.copy(color = picked)
                list[i] = recolored
                history.addLast(page to NoteEdit(note, recolored))
                adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
                markDirty()
                if (view != null) onTextTapped(view, page, recolored)
            }
            return
        }
        tappedStroke?.let { (page, stroke) ->
            val view = noteView
            ColorPicker.show(
                this, stroke.color, hueOnly = stroke.highlighter, light = stroke.highlighter,
                opacityDefault = if (stroke.highlighter) com.titipong.pdfwriter.model.Opacity.INK_DEFAULT else null
            ) { picked ->
                val list = pages[page]?.strokes ?: return@show
                val i = list.indexOf(stroke)
                if (i < 0) return@show
                val recolored = InkStroke(stroke.points.toMutableList(), picked, stroke.width, stroke.highlighter)
                list[i] = recolored
                history.addLast(page to NoteEdit(stroke, recolored))
                adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
                markDirty()
                if (view != null) onStrokeTapped(view, page, recolored)
            }
            return
        }
        val (page, hl) = tappedHighlight ?: return
        ColorPicker.show(this, hl.color, hueOnly = true, light = true,
            opacityDefault = com.titipong.pdfwriter.model.Opacity.TEXT_DEFAULT) { picked ->
            val list = pages[page]?.highlights ?: return@show
            val i = list.indexOf(hl)
            if (i < 0) return@show
            list[i] = hl.withColor(picked)
            clearSelection()
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            markDirty()
        }
    }

    /** Edit a text note: its words, color and size. */
    private fun editTappedNote() {
        val (page, note) = tappedNote ?: return
        clearSelection()
        var color = note.color
        // Same 0.1–10.0 scale as the text tool's size.
        var tenths = Math.round((note.size - 0.018f) / 0.004f * 10f).coerceIn(1, MAX_SIZE_TENTHS)
        fun sizeOf(t: Int) = 0.018f + 0.004f * (t / 10f)

        val pad = (20 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            setText(note.text)
            setSelection(note.text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 8
        }
        root.addView(input)

        // Paragraph alignment.
        var align = note.align
        val alignGroup = com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val alignIds = IntArray(3)
        listOf(R.drawable.ic_align_left, R.drawable.ic_align_center, R.drawable.ic_align_right).forEachIndexed { i, icon ->
            val b = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                id = View.generateViewId()
                setIconResource(icon)
                iconPadding = 0
                iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                contentDescription = getString(listOf(R.string.align_left, R.string.align_center, R.string.align_right)[i])
            }
            alignIds[i] = b.id
            alignGroup.addView(b)
        }
        alignGroup.check(alignIds[align.coerceIn(0, 2)])
        alignGroup.addOnButtonCheckedListener { _, id, checked -> if (checked) align = alignIds.indexOf(id).coerceAtLeast(0) }
        root.addView(alignGroup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad / 2 })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, pad / 2, 0, 0)
        }
        val swatch = View(this)
        fun paintSwatch() {
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((1 * density).toInt(), Color.parseColor("#33000000"))
            }
        }
        paintSwatch()
        swatch.contentDescription = getString(R.string.change_color)
        swatch.setOnClickListener {
            ColorPicker.show(this, color) { picked ->
                color = picked
                paintSwatch()
            }
        }
        val d = (36 * density).toInt()
        row.addView(swatch, LinearLayout.LayoutParams(d, d).apply { marginEnd = pad / 2 })
        val label = android.widget.TextView(this).apply {
            text = getString(R.string.text_size_value, String.format(Locale.US, "%.1f", tenths / 10f))
            minWidth = (80 * density).toInt()
        }
        row.addView(label)
        val seek = SeekBar(this).apply {
            max = 99
            progress = tenths - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tenths = progress + 1
                    label.text = getString(R.string.text_size_value, String.format(Locale.US, "%.1f", tenths / 10f))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_text_title)
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                val list = pages[page]?.texts ?: return@setPositiveButton
                val i = list.indexOf(note)
                if (i < 0 || text.isEmpty()) return@setPositiveButton
                val newSize = sizeOf(tenths)
                // Keep the box width in proportion to the text size.
                val width = if (note.width > 0f) note.width * newSize / note.size else 0f
                val edited = note.copy(text = text, color = color, size = newSize, width = width, align = align)
                list[i] = edited
                history.addLast(page to NoteEdit(note, edited))
                adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
                markDirty()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteTappedHighlight() {
        tappedGroup?.let { (page, group) ->
            val pa = pages[page]
            if (pa != null) {
                for (it in group.items) when (it) {
                    is TextNote -> pa.texts.remove(it)
                    is ImageNote -> pa.images.remove(it)
                    is InkStroke -> pa.strokes.remove(it)
                }
                history.addLast(page to GroupDelete(group.items))
            }
            clearSelection()
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            markDirty()
            return
        }
        tappedStroke?.let { (page, stroke) ->
            pages[page]?.strokes?.remove(stroke)
            clearSelection()
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            markDirty()
            return
        }
        tappedImage?.let { (page, image) ->
            pages[page]?.images?.remove(image)
            clearSelection()
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            markDirty()
            return
        }
        tappedNote?.let { (page, note) ->
            pages[page]?.texts?.remove(note)
            clearSelection()
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            markDirty()
            return
        }
        val (page, hl) = tappedHighlight ?: return
        pages[page]?.highlights?.remove(hl)
        clearSelection()
        adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
        markDirty()
    }

    // ================================================================ search

    private fun openSearch() {
        if (document == null) return
        binding.searchBar.visibility = View.VISIBLE
        updateBackEnabled()
        binding.searchInput.requestFocus()
        binding.searchInput.selectAll()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun closeSearch() {
        hideKeyboard()
        searchDebounce?.cancel()
        binding.searchBar.visibility = View.GONE
        binding.searchInput.setText("")
        clearSearch(resetIndex = false)
        updateBackEnabled()
    }

    /** Clears highlights; with [resetIndex] also drops the text index (the document changed). */
    private fun clearSearch(resetIndex: Boolean) {
        hits = emptyList()
        hitsByPage = emptyMap()
        hitIndex = -1
        if (resetIndex) {
            searchIndexJob?.cancel()
            textPrepJob?.cancel()
            textPreparedAround = -1
            searchIndexStore?.close()
            searchIndexStore = null
        }
        binding.searchCount.text = ""
        refreshHighlights()
    }

    private fun refreshHighlights() {
        val a = adapter ?: return
        a.notifyItemRangeChanged(0, a.itemCount, PageAdapter.SEARCH_PAYLOAD)
    }

    private fun runSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            clearSearch(resetIndex = false)
            return
        }
        val idx = searchIndex ?: return
        if (!idx.allReady) {
            buildIndexThen { runSearch(binding.searchInput.text.toString()) }
            return
        }
        if (idx.isEmpty) {
            hits = emptyList()
            hitsByPage = emptyMap()
            binding.searchCount.setText(R.string.search_none)
            Snackbar.make(binding.root, R.string.search_no_text, Snackbar.LENGTH_LONG)
                .setAction(R.string.ocr) { showOcrDialog() }
                .show()
            refreshHighlights()
            return
        }
        hits = idx.find(q)
        hitsByPage = hits.groupBy { it.page }.mapValues { (_, list) -> list.flatMap { it.rects } }
        refreshHighlights()
        if (hits.isEmpty()) {
            hitIndex = -1
            binding.searchCount.setText(R.string.search_none)
            return
        }
        // Start from the page being read.
        val here = currentPage().coerceAtLeast(0)
        val first = hits.indexOfFirst { it.page >= here }
        goToHit(if (first >= 0) first else 0)
    }

    private fun buildIndexThen(after: () -> Unit) {
        if (searchIndexJob?.isActive == true) return
        val idx = searchIndex ?: return
        searchIndexJob = lifecycleScope.launch {
            val ok = try {
                idx.ensureAll { done, total ->
                    runOnUiThread {
                        if (searchIndexStore === idx && !idx.allReady) {
                            binding.searchCount.text = getString(R.string.search_indexing, done, total)
                        }
                    }
                }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            if (searchIndexStore !== idx) return@launch
            if (!ok) {
                binding.searchCount.setText(R.string.search_failed)
                return@launch
            }
            binding.searchCount.text = ""
            after()
        }
    }

    private fun stepHit(delta: Int) {
        if (hits.isEmpty()) return
        goToHit(((hitIndex + delta) % hits.size + hits.size) % hits.size)
    }

    private fun goToHit(i: Int) {
        val h = hits.getOrNull(i) ?: return
        hitIndex = i
        binding.searchCount.text = getString(R.string.search_count, i + 1, hits.size)
        refreshHighlights()
        if (viewSettings.reading) {
            jumpTo(h.page)
            return
        }
        val a = adapter ?: return
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(a.positionOf(h.page), 0)
        // In vertical scrolling, bring the match itself (not just its page) into view.
        if (!binding.recycler.horizontalPaging) {
            binding.recycler.post {
                val v = a.pageViewFor(binding.recycler, h.page) ?: return@post
                val spread = v.parent as? View ?: return@post
                val r = h.rects.firstOrNull() ?: return@post
                val y = spread.top + v.top + v.viewYOf(r.left, r.top)
                val visible = binding.recycler.height / binding.recycler.scale
                binding.recycler.scrollBy(0, (y - visible / 3f).toInt())
                updatePageIndicator()
            }
        }
    }

    private fun showHitList() {
        if (hits.isEmpty()) return
        val shown = hits.take(500)
        val items = shown.map { getString(R.string.search_result_item, it.page + 1, it.snippet) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.search_results_title, hits.size))
            .setItems(items) { _, which -> goToHit(which) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ================================================================ OCR (only when the user asks)

    private fun showOcrDialog() {
        if (document == null || applyingPages) return
        val page = currentPage().coerceAtLeast(0)
        com.titipong.pdfwriter.ui.OptionSheet.show(this, getString(R.string.ocr_title), listOf(
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_ocr, getString(R.string.opt_ocr_page_t, page + 1), getString(R.string.opt_ocr_page_s)),
            com.titipong.pdfwriter.ui.OptionSheet.Option(R.drawable.ic_pages, getString(R.string.opt_ocr_all_t), getString(R.string.opt_ocr_all_s))
        )) { which -> runOcr(if (which == 0) listOf(page) else null) }
    }

    /** OCRs [pagesToRead], or (null) every page without extractable text, then saves a text layer. */
    private fun runOcr(pagesToRead: List<Int>?) {
        val doc = document ?: return
        val src = localCopy ?: return
        lifecycleScope.launch {
            val targets = pagesToRead ?: run {
                setStatus(R.string.ocr_checking)
                val texts = readingTexts ?: try {
                    withContext(Dispatchers.IO) { PdfAnnotationIO.extractText(src) }.also { readingTexts = it }
                } catch (e: Throwable) {
                    null
                }
                setStatus(if (pdfWritable) R.string.saved_in_pdf else R.string.saved_in_app)
                texts?.let { t -> t.indices.filter { t[it].isBlank() } } ?: (0 until doc.pageCount).toList()
            }
            if (targets.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.ocr_all_have_text, Toast.LENGTH_LONG).show()
                return@launch
            }

            var cancelled = false
            var engine: OcrEngine? = null
            val (dialog, label) = showCancellableProgress(getString(R.string.ocr_starting)) {
                cancelled = true
                engine?.stop()
            }
            val results = HashMap<Int, List<OcrEngine.Word>>()
            try {
                engine = try {
                    withContext(Dispatchers.Default) { OcrEngine(applicationContext) }
                } catch (e: Throwable) {
                    null
                }
                val eng = engine
                if (eng == null) {
                    Toast.makeText(this@MainActivity, R.string.ocr_init_failed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                for ((n, p) in targets.withIndex()) {
                    if (cancelled) break
                    label.text = getString(R.string.ocr_progress, p + 1, n + 1, targets.size)
                    val bmp = doc.renderUncached(p, OCR_WIDTH) ?: continue
                    val words = withContext(Dispatchers.Default) {
                        try {
                            eng.recognize(bmp)
                        } catch (e: Throwable) {
                            emptyList()
                        } finally {
                            bmp.recycle()
                        }
                    }
                    if (words.isNotEmpty()) results[p] = words
                }
            } finally {
                val e = engine
                if (e != null) withContext(Dispatchers.Default) { e.close() }
                dialog.dismiss()
            }
            if (results.isEmpty()) {
                if (!cancelled) Toast.makeText(this@MainActivity, R.string.ocr_nothing_found, Toast.LENGTH_LONG).show()
                return@launch
            }
            // Cancel stops reading; the pages read so far are still saved into the file.
            val change = sameLayoutChange(
                transform = { s, o -> PdfAnnotationIO.addTextLayer(s, results, o) },
                ok = R.string.ocr_done,
                fail = R.string.ocr_failed,
                progress = getString(R.string.ocr_saving)
            )
            if (pdfWritable) {
                commitBaseChange(change, docUri ?: return@launch, openAfter = false)
            } else {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.read_only_pages_title)
                    .setMessage(R.string.ocr_read_only_msg)
                    .setPositiveButton(R.string.save_as_new) { _, _ ->
                        pendingChange = change
                        exportEdited.launch(docName.removeSuffix(".pdf") + "-ocr.pdf")
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showCancellableProgress(message: String, onCancel: () -> Unit): Pair<AlertDialog, android.widget.TextView> {
        val pad = (24 * density).toInt()
        val label = android.widget.TextView(this).apply {
            text = message
            textSize = 16f
            setPadding(pad, 0, 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(ProgressBar(this@MainActivity))
            addView(label)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(row)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .show()
        return Pair(dialog, label)
    }

    // ================================================================ view mode

    private fun showViewModeDialog() {
        ViewModeDialog(this, viewSettings) {
            viewSettings.save(prefs)
            applyView(currentPage().coerceAtLeast(0))
        }.show()
    }

    /** Rebuilds the reader for the current view settings, keeping [page] in view. */
    private fun applyView(page: Int) {
        binding.recycler.setBackgroundColor(viewSettings.listBackground())
        val doc = document ?: return
        if (viewSettings.reading) {
            showReading(page)
            return
        }
        hideReading()
        val key = docKey
        val crops = if (viewSettings.crop && key != null) {
            cropCache[key] ?: run {
                startCropDetection(doc, key)
                null
            }
        } else {
            null
        }
        val config = ViewConfig(
            layout = viewSettings.layout,
            vertical = viewSettings.vertical,
            rotation = viewSettings.rotation,
            crops = crops,
            look = viewSettings.look()
        )
        val lm = com.titipong.pdfwriter.ui.PageLayoutManager(
            this,
            if (viewSettings.vertical) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL
        )
        clearSelection()
        binding.recycler.adapter = null
        binding.recycler.layoutManager = lm
        binding.recycler.setPagingMode(!viewSettings.vertical)
        val a = PageAdapter(doc, this, lifecycleScope, config)
        adapter = a
        binding.recycler.adapter = a
        lm.scrollToPositionWithOffset(a.positionOf(page.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))), 0)
        binding.recycler.post { updatePageIndicator() }
    }

    private var measureJob: Job? = null

    /** Measures the real size of every page in the background (after the first pages are shown). */
    private fun startMeasuring(doc: PdfDocumentRenderer) {
        measureJob?.cancel()
        measureJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            try { doc.measureAll() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { }
        }
    }

    /** Finds each page's content area once per document, then re-applies the view. */
    private fun startCropDetection(doc: PdfDocumentRenderer, key: String) {
        if (cropJob?.isActive == true) return
        cropJob = lifecycleScope.launch {
            val result = HashMap<Int, RectF>()
            for (i in 0 until doc.pageCount) {
                val r = try { doc.contentBounds(i) } catch (e: Exception) { null }
                if (r != null) result[i] = r
                if (i % 5 == 0 && docKey == key) {
                    binding.tvSaveState.text = getString(R.string.crop_progress, i + 1, doc.pageCount)
                }
                if (document !== doc) return@launch
            }
            cropCache[key] = result
            setStatus(if (pdfWritable) R.string.saved_in_pdf else R.string.saved_in_app)
            if (docKey == key && viewSettings.crop && !viewSettings.reading) applyView(currentPage().coerceAtLeast(0))
        }
    }

    // ================================================================ reading mode

    private fun showReading(page: Int) {
        binding.readingPanel.visibility = View.VISIBLE
        binding.readingPanel.setBackgroundColor(viewSettings.readingBackground())
        binding.readingTitle.setTextColor(viewSettings.readingText())
        if (binding.readingList.layoutManager == null) binding.readingList.layoutManager = LinearLayoutManager(this)
        updateBackEnabled()
        val texts = readingTexts
        if (texts != null) {
            bindReading(texts, page)
            return
        }
        val src = localCopy ?: return
        binding.readingTitle.setText(R.string.reading_loading)
        readingJob?.cancel()
        readingJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { PdfAnnotationIO.extractText(src) }
            } catch (e: Throwable) {
                null
            }
            if (result == null) {
                Toast.makeText(this@MainActivity, R.string.reading_failed, Toast.LENGTH_LONG).show()
                viewSettings.reading = false
                viewSettings.save(prefs)
                applyView(page)
                return@launch
            }
            readingTexts = result
            if (viewSettings.reading) bindReading(result, page)
        }
    }

    private fun bindReading(texts: List<String>, page: Int) {
        binding.readingTitle.setText(R.string.reading_title)
        val ra = ReadingAdapter(texts).apply {
            textColor = viewSettings.readingText()
            mutedColor = Color.argb(150, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
            textSize = viewSettings.readingTextSize
        }
        readingAdapter = ra
        binding.readingList.adapter = ra
        (binding.readingList.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(page.coerceIn(0, (texts.size - 1).coerceAtLeast(0)), 0)
        binding.recycler.post { updatePageIndicator() }
    }

    private fun hideReading() {
        readingJob?.cancel()
        binding.readingPanel.visibility = View.GONE
        binding.readingList.adapter = null
        readingAdapter = null
        updateBackEnabled()
    }

    private fun exitReading() {
        val page = currentPage().coerceAtLeast(0)
        viewSettings.reading = false
        viewSettings.save(prefs)
        applyView(page)
    }

    private fun changeReadingSize(delta: Float) {
        viewSettings.readingTextSize = (viewSettings.readingTextSize + delta).coerceIn(12f, 40f)
        viewSettings.save(prefs)
        readingAdapter?.textSize = viewSettings.readingTextSize
    }

    // ---------------------------------------------------------------- loading progress (top bar)

    private val loadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadPage = -1
    private var loadStart = 0L
    private var loadWasSharp = true
    private val loadTick = Runnable { updateLoadProgress() }
    private val loadHide = Runnable { binding.loadBox.visibility = View.GONE }

    private fun watchPageLoad() {
        loadHandler.removeCallbacks(loadTick)
        loadHandler.post(loadTick)
    }

    /** Shows "Loading…" in the top bar while the page being read isn't sharp yet. */
    private fun updateLoadProgress() {
        loadHandler.removeCallbacks(loadTick)
        val doc = document
        val a = adapter
        if (doc == null || a == null || viewSettings.reading || binding.recycler.adapter !== a) {
            loadHandler.removeCallbacks(loadHide)
            binding.loadBox.visibility = View.GONE
            return
        }
        val page = doc.focusPage
        val now = android.os.SystemClock.uptimeMillis()
        if (page != loadPage) {
            loadPage = page
            loadStart = now
            loadWasSharp = false
        }
        val pv = a.pageViewFor(binding.recycler, page)
        if (pv == null || pv.width == 0) {
            loadHandler.postDelayed(loadTick, 100)
            return
        }
        val target = (pv.renderWidth().coerceAtLeast(pv.width) * a.renderScale).toInt()
            .coerceIn(300, PdfDocumentRenderer.MAX_WIDTH)
        val bmp = pv.bitmap
        val sharp = bmp != null && !bmp.isRecycled && bmp.width >= target * 0.88f
        if (sharp) {
            loadWasSharp = true
            if (binding.loadBox.visibility == View.VISIBLE) {
                loadHandler.removeCallbacks(loadHide)
                loadHandler.postDelayed(loadHide, 150)
            }
            return
        }
        if (loadWasSharp) {
            // Was sharp, now needs a sharper image (zoomed in): a new load starts.
            loadWasSharp = false
            loadStart = now
        }
        // Fast loads (memory / saved image) don't flash the indicator.
        if (now - loadStart >= 150) {
            loadHandler.removeCallbacks(loadHide)
            binding.loadPct.text = getString(R.string.page_loading, page + 1)
            binding.loadBox.visibility = View.VISIBLE
        }
        loadHandler.postDelayed(loadTick, 80)
    }

    /** The page in the middle of the screen (the one being read). */
    private fun focusPage(): Int {
        val rv = binding.recycler
        val child = rv.findChildViewUnder(rv.width / 2f, rv.height / 2f)
        val pos = child?.let { rv.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        if (pos == RecyclerView.NO_POSITION) return currentPage()
        return adapter?.firstPageAt(pos)?.takeIf { it >= 0 } ?: currentPage()
    }

    private fun updatePageIndicator() {
        val total = document?.pageCount ?: 0
        val first = currentPage()
        // Render the page being read first, then the pages nearest to it.
        document?.let { d -> focusPage().takeIf { it >= 0 }?.let { d.focusPage = it } }
        watchPageLoad()
        supportActionBar?.subtitle = null // pages: shown by the page scrubber
        updateBookmarkButton()
        updateScrubber()
        if (interactionView) prepareTextIndex()
    }

    companion object {
        /** Largest pen / highlighter / eraser / text size (40.0). */
        const val MAX_SIZE_TENTHS = 400
        private const val PREF_LAST_URI = "last_uri"
        private const val PREF_PALETTE = "palette"
        private const val PREF_COLOR_INDEX = "color_index"
        private const val PREF_SIZE_TENTHS = "size_tenths"
        private const val PREF_COLOR_HINT_SHOWN = "color_hint_shown"
        private const val MAX_HISTORY = 500
        private const val PDF_SAVE_DELAY_MS = 2500L
        private const val OCR_WIDTH = 2000
        private const val PREF_INTERACTION_VIEW = "interaction_view"
        private const val PREF_HL_COLOR = "highlight_color"
        private const val PREF_HL_PALETTE = "highlight_palette"

        /** Text-highlighter colors (view mode). */
        private val HIGHLIGHT_COLORS = listOf(
            Color.parseColor("#FDE047"),
            Color.parseColor("#86EFAC"),
            Color.parseColor("#93C5FD"),
            Color.parseColor("#F9A8D4"),
            Color.parseColor("#FDBA74")
        )
        private const val SAMSUNG_PEN_DOWN = 211
        private const val SAMSUNG_PEN_UP = 212
        private const val SAMSUNG_PEN_MOVE = 213

        private const val PREF_TOOL = "current_tool"
        private const val PREF_PEN_PRESSURE = "pen_pressure"
        private const val PREF_TOOL_ON = "tool_on"
        private const val PREF_ERASER_PIXEL = "eraser_pixel"
        private const val PREF_TABS = "open_tabs"
        private const val MAX_TABS = 12

        /** Default highlighter-pen colors (light, see-through). */
        private val HIGHLIGHTER_INK_COLORS = listOf(
            Color.parseColor("#FACC15"),
            Color.parseColor("#4ADE80"),
            Color.parseColor("#60A5FA"),
            Color.parseColor("#F472B6"),
            Color.parseColor("#FB923C")
        )

        private val DEFAULT_COLORS = listOf(
            Color.parseColor("#111827"),
            Color.parseColor("#2563EB"),
            Color.parseColor("#DC2626"),
            Color.parseColor("#16A34A"),
            Color.parseColor("#F59E0B")
        )
    }
}

/** See [MainActivity.saveSeq]. */
private object SaveSeq {
    @Volatile var value = System.currentTimeMillis()
}
