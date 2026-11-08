package com.titipong.pdfwriter

import android.content.Intent
import android.graphics.Color
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
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.titipong.pdfwriter.databinding.ActivityMainBinding
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.model.InkStroke
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.TextNote
import com.titipong.pdfwriter.pdf.PdfAnnotationIO
import com.titipong.pdfwriter.pdf.PdfDocumentRenderer
import com.titipong.pdfwriter.storage.AnnotationStorage
import com.titipong.pdfwriter.ui.PageAdapter
import com.titipong.pdfwriter.ui.PdfPageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity(), PdfPageView.Host {

    private lateinit var binding: ActivityMainBinding

    private var currentTool = DrawTool.PEN
    private var currentColor = COLORS[0]
    private var sizeLevel = 3

    override val tool: DrawTool get() = currentTool
    override val color: Int get() = currentColor
    override val strokeWidth: Float get() = 0.0008f + 0.0012f * sizeLevel

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
    private val history = ArrayDeque<Pair<Int, Any>>()

    // Saving: an in-app copy right away, the PDF file itself a moment later.
    private var dirty = false
    private var saveSeq = 0L
    private var saveJob: Job? = null
    private var changeVersion = 0L
    private var pdfSavedVersion = 0L
    private var pdfJob: Job? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.setItemViewCacheSize(2)
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator()
            }
        })
        binding.recycler.onZoomSettled = { scale -> onZoomSettled(scale) }
        binding.btnOpenEmpty.setOnClickListener { launchPicker() }

        setupTools()

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

    // ---------------------------------------------------------------- tools

    private fun setupTools() {
        toolButtons[DrawTool.PEN] = binding.btnPen
        toolButtons[DrawTool.HIGHLIGHTER] = binding.btnHighlight
        toolButtons[DrawTool.ERASER] = binding.btnEraser
        toolButtons[DrawTool.TEXT] = binding.btnText
        toolButtons.forEach { (t, v) -> v.setOnClickListener { selectTool(t) } }
        binding.btnUndo.setOnClickListener { undoLast() }

        val dot = (28 * density).toInt()
        val gap = (6 * density).toInt()
        for (c in COLORS) {
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(dot, dot).apply { marginEnd = gap }
            v.contentDescription = getString(R.string.color)
            v.setOnClickListener { selectColor(c) }
            binding.colorRow.addView(v)
            colorViews.add(v)
        }

        binding.seekSize.max = 9
        binding.seekSize.progress = sizeLevel - 1
        binding.tvSize.text = sizeLevel.toString()
        binding.seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sizeLevel = progress + 1
                binding.tvSize.text = sizeLevel.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        selectTool(DrawTool.PEN)
        selectColor(COLORS[0])
    }

    private fun selectTool(t: DrawTool) {
        currentTool = t
        toolButtons.forEach { (key, v) -> v.isSelected = key == t }
    }

    private fun selectColor(c: Int) {
        currentColor = c
        colorViews.forEachIndexed { i, v ->
            val selected = COLORS[i] == c
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLORS[i])
                if (selected) setStroke((3 * density).toInt(), Color.parseColor("#F59E0B"))
                else setStroke((1 * density).toInt(), Color.parseColor("#33000000"))
            }
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

    // ---------------------------------------------------------------- Host

    override fun annotationsFor(page: Int): PageAnnotations = pages.getOrPut(page) { PageAnnotations() }

    override fun onItemAdded(page: Int, item: Any) {
        history.addLast(page to item)
        if (history.size > MAX_HISTORY) history.removeFirst()
        markDirty()
    }

    override fun onItemsErased(page: Int) {
        markDirty()
    }

    override fun requestText(page: Int, x: Float, y: Float) {
        val input = EditText(this).apply {
            hint = getString(R.string.text_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val pad = (20 * density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_text)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val note = TextNote(text, x, y, currentColor, 0.018f + 0.004f * sizeLevel)
                    annotationsFor(page).texts.add(note)
                    adapter?.notifyItemChanged(page, PageAdapter.INK_PAYLOAD)
                    onItemAdded(page, note)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    // ---------------------------------------------------------------- editing

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
                adapter?.notifyItemChanged(page, PageAdapter.INK_PAYLOAD)
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
                adapter?.notifyItemChanged(page, PageAdapter.INK_PAYLOAD)
                markDirty()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- saving

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
            val json = AnnotationStorage.toJson(pages, synced = false)
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
            AnnotationStorage.save(applicationContext, key, AnnotationStorage.toJson(pages, synced = false), ++saveSeq)
            dirty = false
        } catch (e: Exception) {
            setStatus(R.string.save_failed)
        }
    }

    private fun snapshot(): Map<Int, PageAnnotations> = pages.mapValues { (_, pa) ->
        PageAnnotations().also {
            it.strokes.addAll(pa.strokes)
            it.texts.addAll(pa.texts)
        }
    }

    /** Writes the ink into the original PDF in the background; survives leaving the app. */
    private fun writePdfNow() {
        pdfJob?.cancel()
        if (!pdfWritable) return
        val version = changeVersion
        if (version == pdfSavedVersion) return
        val src = localCopy ?: return
        val dest = docUri ?: return
        val key = docKey ?: return
        val snap = snapshot()
        pdfSavedVersion = version // claim it; reset below if the write fails
        setStatus(R.string.saving_pdf)
        App.scope.launch {
            val ok = try {
                PdfAnnotationIO.write(applicationContext, src, snap, dest)
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
                val json = AnnotationStorage.toJson(snap, synced = true)
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
        val snap = snapshot()
        App.scope.launch {
            val ok = try {
                PdfAnnotationIO.write(applicationContext, src, snap, dest)
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

    // ---------------------------------------------------------------- opening

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
            history.clear()
            dirty = false
            changeVersion = 0
            pdfSavedVersion = 0

            binding.recycler.resetZoom()
            val a = PageAdapter(opened.doc, this@MainActivity, lifecycleScope)
            adapter = a
            binding.recycler.adapter = a
            binding.emptyView.visibility = View.GONE
            supportActionBar?.title = name
            setStatus(if (opened.writable) R.string.saved_in_pdf else R.string.read_only_file)
            binding.recycler.post { updatePageIndicator() }
            if (remember) prefs.edit().putString(PREF_LAST_URI, uri.toString()).apply()

            // Ink saved in the app but not yet in the file (e.g. the app was killed mid-save): write it now.
            if (opened.needsPdfWrite && opened.writable) {
                changeVersion = 1
                writePdfNow()
            }
        }
    }

    /** Runs on IO: copies the file privately, opens the renderer, and loads the ink layer. */
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

        // The PDF file is the source of truth (it may have been edited in another app),
        // unless the app holds newer ink that never made it into the file.
        if (side != null && (!side.synced || !writable)) {
            return Opened(doc, copy, side.pages, writable, needsPdfWrite = !side.synced)
        }
        val fromPdf = try {
            PdfAnnotationIO.read(copy)
        } catch (e: Throwable) {
            // The PDF library can't read this file: keep ink in the app only, never touch the file.
            return Opened(doc, copy, side?.pages ?: mutableMapOf(), writable = false, needsPdfWrite = false)
        }
        return Opened(doc, copy, fromPdf, writable, needsPdfWrite = false)
    }

    private fun currentPage(): Int {
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return -1
        return lm.findFirstVisibleItemPosition()
    }

    private fun updatePageIndicator() {
        val total = document?.pageCount ?: 0
        val first = currentPage()
        supportActionBar?.subtitle =
            if (total > 0 && first >= 0) getString(R.string.page_of, first + 1, total) else null
    }

    companion object {
        private const val PREF_LAST_URI = "last_uri"
        private const val MAX_HISTORY = 500
        private const val PDF_SAVE_DELAY_MS = 2500L
        private const val SAMSUNG_PEN_DOWN = 211
        private const val SAMSUNG_PEN_UP = 212
        private const val SAMSUNG_PEN_MOVE = 213

        private val COLORS = intArrayOf(
            Color.parseColor("#111827"),
            Color.parseColor("#2563EB"),
            Color.parseColor("#DC2626"),
            Color.parseColor("#16A34A"),
            Color.parseColor("#F59E0B"),
            Color.parseColor("#7C3AED")
        )
    }
}
