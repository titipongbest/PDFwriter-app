package com.titipong.pdfwriter

import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.titipong.pdfwriter.databinding.ActivityMainBinding
import com.titipong.pdfwriter.model.Bookmark
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.OutlineEntry
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import com.titipong.pdfwriter.pdf.PdfDocumentRenderer
import com.titipong.pdfwriter.storage.AnnotationStorage
import com.titipong.pdfwriter.ui.BookmarkAdapter
import com.titipong.pdfwriter.ui.ColorPicker
import com.titipong.pdfwriter.ui.PageAdapter
import com.titipong.pdfwriter.ui.PageGridAdapter
import com.titipong.pdfwriter.ui.PdfPageView
import com.titipong.pdfwriter.ui.ReadingAdapter
import com.titipong.pdfwriter.ui.ViewConfig
import com.titipong.pdfwriter.ui.ViewModeDialog
import com.titipong.pdfwriter.ui.ViewSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), PdfPageView.Host {

    private lateinit var binding: ActivityMainBinding

    // Tools
    private var currentTool = DrawTool.PEN
    private val palette = DEFAULT_COLORS.toMutableList()
    private var colorIndex = 1
    /** Pen size in tenths: 1..100 means 0.1..10.0 */
    private var sizeTenths = 30
    private val sizeValue: Float get() = sizeTenths / 10f

    override val tool: DrawTool get() = currentTool
    override val color: Int get() = palette[colorIndex]
    override val strokeWidth: Float get() = 0.0003f + 0.0012f * sizeValue

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
    private var pages: MutableMap<Int, PageAnnotations> = mutableMapOf()
    private var bookmarks: MutableList<Bookmark> = mutableListOf()
    private var outline: List<OutlineEntry> = emptyList()
    private val history = ArrayDeque<Pair<Int, Any>>()

    // Saving: an in-app copy right away, the PDF file itself a moment later.
    private var dirty = false
    private var saveSeq = 0L
    private var saveJob: Job? = null
    private var changeVersion = 0L
    private var pdfSavedVersion = 0L
    private var pdfJob: Job? = null

    // Page overview
    private var gridAdapter: PageGridAdapter? = null
    private var bookmarkAdapter: BookmarkAdapter? = null
    private var pendingOrder: List<Int?>? = null
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

    private val toolButtons = mutableMapOf<DrawTool, View>()
    private val colorViews = mutableListOf<View>()
    private val density by lazy { resources.displayMetrics.density }
    private val prefs by lazy { getSharedPreferences("pdfwriter", MODE_PRIVATE) }

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openPdf(uri, remember = persistPermission(uri), silent = false)
    }

    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) exportCopy(uri)
    }

    /** "Save as new file" for page edits on a file that can't be overwritten. */
    private val exportEdited = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val order = pendingOrder
        pendingOrder = null
        if (uri != null && order != null) applyPageEdits(order, uri, openAfter = true)
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (binding.pagesPanel.visibility == View.VISIBLE) {
                closePanelOrEdit()
            } else if (viewSettings.reading) {
                exitReading()
            }
        }
    }

    private fun updateBackEnabled() {
        backCallback.isEnabled = binding.pagesPanel.visibility == View.VISIBLE || viewSettings.reading
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        onBackPressedDispatcher.addCallback(this, backCallback)
        viewSettings = ViewSettings.load(prefs)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.setItemViewCacheSize(2)
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
            }
        })
        binding.recycler.onZoomSettled = { scale -> onZoomSettled(scale) }
        binding.btnOpenEmpty.setOnClickListener { launchPicker() }

        loadToolPrefs()
        setupTools()
        setupPanel()

        val incoming = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (incoming != null) {
            openPdf(incoming, remember = persistPermission(incoming), silent = false)
        } else {
            prefs.getString(PREF_LAST_URI, null)?.let {
                openPdf(Uri.parse(it), remember = true, silent = true)
            }
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
        saveToolPrefs()
        saveNow()      // instant in-app copy
        writePdfNow()  // and into the PDF file, even if the app is being closed
    }

    override fun onDestroy() {
        super.onDestroy()
        document?.close()
        document = null
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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> {
            launchPicker()
            true
        }
        R.id.action_export -> {
            if (document != null) exportPdf.launch(docName.removeSuffix(".pdf") + "-annotated.pdf")
            true
        }
        R.id.action_view_mode -> {
            showViewModeDialog()
            true
        }
        R.id.action_reset_zoom -> {
            binding.recycler.resetZoom()
            true
        }
        R.id.action_clear -> {
            confirmClearPage()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ================================================================ tools

    private fun loadToolPrefs() {
        prefs.getString(PREF_PALETTE, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.let { saved ->
            if (saved.size == DEFAULT_COLORS.size) {
                palette.clear()
                palette.addAll(saved)
            }
        }
        colorIndex = prefs.getInt(PREF_COLOR_INDEX, 1).coerceIn(0, palette.size - 1)
        sizeTenths = prefs.getInt(PREF_SIZE_TENTHS, 30).coerceIn(1, 100)
    }

    private fun saveToolPrefs() {
        prefs.edit()
            .putString(PREF_PALETTE, palette.joinToString(","))
            .putInt(PREF_COLOR_INDEX, colorIndex)
            .putInt(PREF_SIZE_TENTHS, sizeTenths)
            .apply()
    }

    private fun setupTools() {
        toolButtons[DrawTool.PEN] = binding.btnPen
        toolButtons[DrawTool.HIGHLIGHTER] = binding.btnHighlight
        toolButtons[DrawTool.ERASER] = binding.btnEraser
        toolButtons[DrawTool.TEXT] = binding.btnText
        toolButtons.forEach { (t, v) -> v.setOnClickListener { selectTool(t) } }
        binding.btnUndo.setOnClickListener { undoLast() }
        binding.btnPages.setOnClickListener { openPanel(bookmarksTab = false) }
        binding.btnViewMode.setOnClickListener { showViewModeDialog() }
        binding.btnTextSmaller.setOnClickListener { changeReadingSize(-2f) }
        binding.btnTextBigger.setOnClickListener { changeReadingSize(+2f) }
        binding.btnReadingExit.setOnClickListener { exitReading() }
        binding.readingList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
            }
        })
        binding.btnBookmark.setOnClickListener { toggleBookmark() }
        binding.btnBookmark.setOnLongClickListener {
            openPanel(bookmarksTab = true)
            true
        }

        // Colors: tap = use, tap the selected one again or long-press = edit.
        val dot = (28 * density).toInt()
        val gap = (6 * density).toInt()
        palette.indices.forEach { i ->
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
        binding.seekSize.max = 99
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
        showSize()

        selectTool(DrawTool.PEN)
        selectColor(colorIndex)
    }

    private fun stepSize(delta: Int) {
        sizeTenths = (sizeTenths + delta).coerceIn(1, 100)
        binding.seekSize.progress = sizeTenths - 1
        showSize()
    }

    private fun showSize() {
        binding.tvSize.text = String.format(Locale.US, "%.1f", sizeValue)
    }

    private fun selectTool(t: DrawTool) {
        currentTool = t
        toolButtons.forEach { (key, v) -> v.isSelected = key == t }
    }

    private fun selectColor(index: Int) {
        colorIndex = index
        colorViews.forEachIndexed { i, v ->
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette[i])
                if (i == colorIndex) setStroke((3 * density).toInt(), Color.parseColor("#F59E0B"))
                else setStroke((1 * density).toInt(), Color.parseColor("#33000000"))
            }
        }
    }

    private fun editColor(index: Int) {
        ColorPicker.show(this, palette[index]) { picked ->
            palette[index] = picked
            selectColor(index)
            saveToolPrefs()
        }
    }

    private fun onZoomSettled(scale: Float) {
        val a = adapter ?: return
        a.renderScale = scale
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first >= 0 && last >= first) a.notifyItemRangeChanged(first, last - first + 1, PageAdapter.RERENDER_PAYLOAD)
    }

    // ================================================================ Host

    override fun annotationsFor(page: Int): PageAnnotations = pages.getOrPut(page) { PageAnnotations() }

    override fun isBookmarked(page: Int): Boolean = bookmarks.any { it.page == page }

    override fun onItemAdded(page: Int, item: Any) {
        history.addLast(page to item)
        if (history.size > MAX_HISTORY) history.removeFirst()
        markDirty()
    }

    override fun onItemsErased(page: Int) {
        markDirty()
    }

    override fun requestText(page: Int, x: Float, y: Float) {
        promptText(getString(R.string.add_text), "") { text ->
            val note = TextNote(text, x, y, color, 0.018f + 0.004f * sizeValue)
            annotationsFor(page).texts.add(note)
            adapter?.notifyPage(page, PageAdapter.INK_PAYLOAD)
            onItemAdded(page, note)
        }
    }

    private fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.text_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
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
        while (history.isNotEmpty()) {
            val (page, item) = history.removeLast()
            val pa = pages[page] ?: continue
            val removed = when (item) {
                is InkStroke -> pa.strokes.remove(item)
                is TextNote -> pa.texts.remove(item)
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
        binding.btnBookmark.setImageResource(
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
        binding.btnDeletePages.setOnClickListener {
            val g = gridAdapter ?: return@setOnClickListener
            if (!g.deleteSelected()) Toast.makeText(this, R.string.cannot_delete_all, Toast.LENGTH_SHORT).show()
        }
        binding.btnApplyEdit.setOnClickListener { onApplyEdit() }

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
            override fun isLongPressDragEnabled(): Boolean = gridAdapter?.editMode == true

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
        gridAdapter?.editMode = on
        binding.panelHint.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnAddBlank.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnCancelEdit.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnApplyEdit.visibility = if (on) View.VISIBLE else View.GONE
        binding.btnEditPages.visibility = if (on) View.GONE else View.VISIBLE
        updateEditButtons()
    }

    private fun updateEditButtons() {
        val g = gridAdapter
        val n = g?.selected?.size ?: 0
        binding.btnDeletePages.visibility = if (g?.editMode == true && n > 0) View.VISIBLE else View.GONE
        binding.btnDeletePages.text = getString(R.string.delete_pages_n, n)
    }

    private fun cancelEdit() {
        val g = gridAdapter ?: return
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
        if (!g.isModified()) {
            setEditMode(false)
            return
        }
        val order = g.order()
        if (pdfWritable) {
            applyPageEdits(order, docUri ?: return, openAfter = false)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.read_only_pages_title)
                .setMessage(R.string.read_only_pages_msg)
                .setPositiveButton(R.string.save_as_new) { _, _ ->
                    pendingOrder = order
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
    private fun applyPageEdits(order: List<Int?>, dest: Uri, openAfter: Boolean) {
        val src = localCopy ?: return
        val key = docKey ?: return
        val newPages = mutableMapOf<Int, PageAnnotations>()
        order.forEachIndexed { i, s ->
            val pa = s?.let { pages[it] } ?: return@forEachIndexed
            newPages[i] = PageAnnotations().also {
                it.strokes.addAll(pa.strokes)
                it.texts.addAll(pa.texts)
            }
        }
        val newBookmarks = bookmarks.mapNotNull { b ->
            val i = order.indexOf(b.page)
            if (i >= 0) Bookmark(b.title, i) else null
        }.toMutableList()

        val progress = showProgress(getString(R.string.applying_pages))
        applyingPages = true
        lifecycleScope.launch {
            try {
                runPageEdits(src, key, order, newPages, newBookmarks, dest, openAfter, progress)
            } finally {
                applyingPages = false
            }
        }
    }

    private suspend fun runPageEdits(
        src: File,
        key: String,
        order: List<Int?>,
        newPages: MutableMap<Int, PageAnnotations>,
        newBookmarks: MutableList<Bookmark>,
        dest: Uri,
        openAfter: Boolean,
        progress: AlertDialog
    ) {
        run {
            saveNow()
            pdfJob?.cancel()
            val dir = File(filesDir, "docs").apply { mkdirs() }
            val rebuilt = File(dir, "$key.edit.pdf")
            val result = try {
                PdfAnnotationIO.rebuildPages(src, order, rebuilt)
                PdfAnnotationIO.write(applicationContext, rebuilt, newPages, newBookmarks, dest)
                true
            } catch (e: Throwable) {
                false
            }
            if (!result) {
                progress.dismiss()
                rebuilt.delete()
                Toast.makeText(this@MainActivity, R.string.pages_failed, Toast.LENGTH_LONG).show()
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

            // Swap the app over to the new layout.
            val finalCopy = File(dir, "$key.pdf")
            val opened = try {
                withContext(Dispatchers.IO) {
                    if (!rebuilt.renameTo(finalCopy)) {
                        finalCopy.delete()
                        rebuilt.renameTo(finalCopy)
                    }
                    val doc = PdfDocumentRenderer.open(ParcelFileDescriptor.open(finalCopy, ParcelFileDescriptor.MODE_READ_ONLY))
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
                openPdf(dest, remember = true, silent = false)
                return
            }
            document?.close()
            document = opened.first
            outline = opened.second
            localCopy = finalCopy
            pages = newPages
            bookmarks = newBookmarks
            history.clear()
            cropCache.remove(key)
            readingTexts = null

            val keepPage = currentPage().coerceIn(0, (opened.first.pageCount - 1).coerceAtLeast(0))
            closePanelForce()
            applyView(keepPage)
            setStatus(R.string.saved_in_pdf)
            binding.recycler.post { updatePageIndicator() }
            Toast.makeText(this@MainActivity, R.string.pages_saved, Toast.LENGTH_SHORT).show()
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
        if (!isDestroyed) binding.tvSaveState.setText(resId)
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

    private fun snapshotPages(): Map<Int, PageAnnotations> = pages.mapValues { (_, pa) ->
        PageAnnotations().also {
            it.strokes.addAll(pa.strokes)
            it.texts.addAll(pa.texts)
        }
    }

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
        pdfSavedVersion = version // claim it; reset below if the write fails
        setStatus(R.string.saving_pdf)
        App.scope.launch {
            val ok = try {
                PdfAnnotationIO.write(applicationContext, src, snap, snapBookmarks, dest)
                true
            } catch (e: Throwable) {
                false
            }
            if (!ok) {
                if (pdfSavedVersion == version) pdfSavedVersion = -1
                setStatus(R.string.save_pdf_failed)
                return@launch
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

    private fun exportCopy(dest: Uri) {
        val src = localCopy ?: return
        val snap = snapshotPages()
        val snapBookmarks = snapshotBookmarks()
        App.scope.launch {
            val ok = try {
                PdfAnnotationIO.write(applicationContext, src, snap, snapBookmarks, dest)
                true
            } catch (e: Throwable) {
                false
            }
            Toast.makeText(
                applicationContext,
                if (ok) R.string.export_done else R.string.save_pdf_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ================================================================ opening

    private fun launchPicker() {
        pickPdf.launch(arrayOf("application/pdf"))
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
        val needsPdfWrite: Boolean
    )

    private fun openPdf(uri: Uri, remember: Boolean, silent: Boolean) {
        lifecycleScope.launch {
            saveNow()
            writePdfNow()
            setStatus(R.string.loading)
            val name = queryName(uri)
            val key = AnnotationStorage.keyFor(uri.toString())
            val opened = try {
                withContext(Dispatchers.IO) { loadDocument(uri, key) }
            } catch (e: Exception) {
                binding.tvSaveState.text = ""
                if (silent) {
                    prefs.edit().remove(PREF_LAST_URI).apply()
                } else {
                    Toast.makeText(this@MainActivity, R.string.open_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            document?.close()
            document = opened.doc
            docUri = uri
            docName = name
            docKey = key
            localCopy = opened.copy
            pdfWritable = opened.writable
            pages = opened.pages
            bookmarks = opened.bookmarks
            outline = opened.outline
            history.clear()
            dirty = false
            changeVersion = 0
            pdfSavedVersion = 0

            readingTexts = null
            binding.emptyView.visibility = View.GONE
            applyView(0)
            supportActionBar?.title = name
            setStatus(if (opened.writable) R.string.saved_in_pdf else R.string.read_only_file)
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

    /** Runs on IO: copies the file privately, opens the renderer, and loads ink + bookmarks. */
    private suspend fun loadDocument(uri: Uri, key: String): Opened {
        val dir = File(filesDir, "docs").apply { mkdirs() }
        val copy = File(dir, "$key.pdf")
        val tmp = File(dir, "$key.tmp")
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (!tmp.renameTo(copy)) {
            copy.delete()
            tmp.renameTo(copy)
        }
        // Keep only this copy and the previous one (a save for it may still be running).
        val previous = localCopy?.name
        dir.listFiles()?.forEach { if (it.name != copy.name && it.name != previous) it.delete() }

        val doc = PdfDocumentRenderer.open(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY))
        val writable = canWrite(uri)
        val side = AnnotationStorage.load(applicationContext, key)
        val fromPdf = try {
            PdfAnnotationIO.read(copy)
        } catch (e: Throwable) {
            null
        }
        val toc = fromPdf?.outline ?: emptyList()

        // The PDF library can't read this file: keep everything in the app only, never touch the file.
        if (fromPdf == null) {
            return Opened(
                doc, copy, side?.pages ?: mutableMapOf(), side?.bookmarks ?: mutableListOf(), toc,
                writable = false, needsPdfWrite = false
            )
        }
        // The PDF file is the source of truth (it may have been edited in another app),
        // unless the app holds newer changes that never made it into the file.
        if (side != null && (!side.synced || !writable)) {
            return Opened(doc, copy, side.pages, side.bookmarks, toc, writable, needsPdfWrite = !side.synced)
        }
        return Opened(doc, copy, fromPdf.pages, fromPdf.bookmarks, toc, writable, needsPdfWrite = false)
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
        val lm = LinearLayoutManager(
            this,
            if (viewSettings.vertical) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL,
            false
        )
        binding.recycler.adapter = null
        binding.recycler.layoutManager = lm
        binding.recycler.setPagingMode(!viewSettings.vertical)
        val a = PageAdapter(doc, this, lifecycleScope, config)
        adapter = a
        binding.recycler.adapter = a
        lm.scrollToPositionWithOffset(a.positionOf(page.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))), 0)
        binding.recycler.post { updatePageIndicator() }
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

    private fun updatePageIndicator() {
        val total = document?.pageCount ?: 0
        val first = currentPage()
        supportActionBar?.subtitle =
            if (total > 0 && first >= 0) getString(R.string.page_of, first + 1, total) else null
        updateBookmarkButton()
    }

    companion object {
        private const val PREF_LAST_URI = "last_uri"
        private const val PREF_PALETTE = "palette"
        private const val PREF_COLOR_INDEX = "color_index"
        private const val PREF_SIZE_TENTHS = "size_tenths"
        private const val PREF_COLOR_HINT_SHOWN = "color_hint_shown"
        private const val MAX_HISTORY = 500
        private const val PDF_SAVE_DELAY_MS = 2500L
        private const val SAMSUNG_PEN_DOWN = 211
        private const val SAMSUNG_PEN_UP = 212
        private const val SAMSUNG_PEN_MOVE = 213

        private val DEFAULT_COLORS = listOf(
            Color.parseColor("#111827"),
            Color.parseColor("#2563EB"),
            Color.parseColor("#DC2626"),
            Color.parseColor("#16A34A"),
            Color.parseColor("#F59E0B"),
            Color.parseColor("#7C3AED")
        )
    }
}
