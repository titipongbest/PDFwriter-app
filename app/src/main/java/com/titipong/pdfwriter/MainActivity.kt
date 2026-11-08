package com.titipong.pdfwriter

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import com.titipong.pdfwriter.pdf.PdfDocumentRenderer
import com.titipong.pdfwriter.storage.AnnotationStorage
import com.titipong.pdfwriter.ui.PageAdapter
import com.titipong.pdfwriter.ui.PdfPageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity(), PdfPageView.Host {

    private lateinit var binding: ActivityMainBinding

    private var currentTool = DrawTool.PEN
    private var currentColor = COLORS[0]
    private var sizeLevel = 3

    override val tool: DrawTool get() = currentTool
    override val color: Int get() = currentColor
    override val strokeWidth: Float get() = 0.0008f + 0.0012f * sizeLevel

    private var document: PdfDocumentRenderer? = null
    private var adapter: PageAdapter? = null
    private var docKey: String? = null
    private var pages: MutableMap<Int, PageAnnotations> = mutableMapOf()
    private val history = ArrayDeque<Pair<Int, Any>>()

    private var dirty = false
    private var saveSeq = 0L
    private var saveJob: Job? = null

    private val toolButtons = mutableMapOf<DrawTool, View>()
    private val colorViews = mutableListOf<View>()
    private val density by lazy { resources.displayMetrics.density }
    private val prefs by lazy { getSharedPreferences("pdfwriter", MODE_PRIVATE) }

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val persisted = persistPermission(uri)
            openPdf(uri, remember = persisted, silent = false)
        }
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
        saveNow() // so closing / swiping away the app never loses ink
    }

    override fun onDestroy() {
        super.onDestroy()
        document?.close()
        document = null
    }

    /**
     * Some Samsung devices report "S Pen side button held" as legacy action codes
     * 211/212/213 instead of ACTION_DOWN/UP/MOVE + BUTTON_STYLUS_PRIMARY.
     * Translate them so the normal pipeline handles them, flagged as eraser.
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

    private fun markDirty() {
        dirty = true
        binding.tvSaveState.setText(R.string.saving)
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(400)
            val key = docKey ?: return@launch
            val json = AnnotationStorage.toJson(pages)
            val seq = ++saveSeq
            dirty = false
            try {
                withContext(Dispatchers.IO) { AnnotationStorage.save(applicationContext, key, json, seq) }
                if (!dirty) binding.tvSaveState.setText(R.string.saved)
            } catch (e: Exception) {
                dirty = true
                binding.tvSaveState.setText(R.string.save_failed)
            }
        }
    }

    /** Synchronous save, used when leaving the screen or switching files. */
    private fun saveNow() {
        saveJob?.cancel()
        if (!dirty) return
        val key = docKey ?: return
        try {
            AnnotationStorage.save(applicationContext, key, AnnotationStorage.toJson(pages), ++saveSeq)
            dirty = false
            binding.tvSaveState.setText(R.string.saved)
        } catch (e: Exception) {
            binding.tvSaveState.setText(R.string.save_failed)
        }
    }

    // ---------------------------------------------------------------- opening

    private fun launchPicker() {
        pickPdf.launch(arrayOf("application/pdf"))
    }

    private fun persistPermission(uri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: Exception) {
        false
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = 0L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        } catch (e: Exception) {
        }
        return Pair(name ?: uri.lastPathSegment ?: "document.pdf", size)
    }

    private fun openPdf(uri: Uri, remember: Boolean, silent: Boolean) {
        lifecycleScope.launch {
            saveNow()
            val meta = queryMeta(uri)
            val newDoc = try {
                withContext(Dispatchers.IO) {
                    val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open")
                    PdfDocumentRenderer.open(pfd)
                }
            } catch (e: Exception) {
                if (silent) {
                    prefs.edit().remove(PREF_LAST_URI).apply()
                } else {
                    Toast.makeText(this@MainActivity, R.string.open_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val key = AnnotationStorage.keyFor(meta.first, meta.second)
            val loaded = withContext(Dispatchers.IO) { AnnotationStorage.load(applicationContext, key) }

            document?.close()
            document = newDoc
            docKey = key
            pages = loaded
            history.clear()
            dirty = false

            val a = PageAdapter(newDoc, this@MainActivity, lifecycleScope)
            adapter = a
            binding.recycler.adapter = a
            binding.emptyView.visibility = View.GONE
            supportActionBar?.title = meta.first
            binding.tvSaveState.text = ""
            binding.recycler.post { updatePageIndicator() }

            if (remember) prefs.edit().putString(PREF_LAST_URI, uri.toString()).apply()
        }
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
