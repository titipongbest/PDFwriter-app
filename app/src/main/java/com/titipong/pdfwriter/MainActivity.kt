package com.titipong.pdfwriter

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.titipong.pdfwriter.databinding.ActivityMainBinding
import com.titipong.pdfwriter.model.DrawTool
import com.titipong.pdfwriter.model.TextAnnotation
import com.titipong.pdfwriter.viewmodel.PdfViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: PdfViewModel by viewModels()

    private val COLORS = intArrayOf(
        Color.parseColor("#2563EB"),
        Color.parseColor("#DC2626"),
        Color.parseColor("#16A34A"),
        Color.parseColor("#D97706"),
        Color.parseColor("#7C3AED"),
        Color.parseColor("#111827")
    )
    private var selectedColor = Color.parseColor("#2563EB")

    private val openPdf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadPdf(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupToolButtons()
        setupColorPicker()
        setupSizeSlider()
        setupSaveBadge()
        setupDrawingView()

        // handle open-with intent
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { loadPdf(it) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> { openPdf.launch("application/pdf"); true }
            R.id.action_undo -> { binding.drawingView.undo(); vm.undoStroke(vm.currentPage.value); true }
            R.id.action_clear -> {
                AlertDialog.Builder(this)
                    .setTitle("ลบรอยเขียนหน้านี้")
                    .setMessage("ต้องการลบรอยเขียนทั้งหมดในหน้านี้ใช่ไหม?")
                    .setPositiveButton("ลบ") { _, _ ->
                        binding.drawingView.clearPage()
                        vm.clearPage(vm.currentPage.value)
                    }
                    .setNegativeButton("ยกเลิก", null)
                    .show()
                true
            }
            R.id.action_text -> {
                showTextDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupDrawingView() {
        binding.drawingView.onStrokeFinished = { stroke ->
            vm.addStroke(stroke)
        }
    }

    private fun loadPdf(uri: Uri) {
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment ?: "document.pdf"
        } ?: (uri.lastPathSegment ?: "document.pdf")

        binding.pdfView.fromUri(uri)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .defaultPage(0)
            .onLoad(OnLoadCompleteListener { nbPages ->
                vm.onPdfLoaded(name, nbPages)
                // load annotations for page 0
                val ann = vm.getPageAnnotations(0)
                if (ann != null) {
                    binding.drawingView.loadStrokes(ann.strokes)
                }
            })
            .onPageChange(OnPageChangeListener { page, _ ->
                vm.onPageChanged(page)
                binding.drawingView.pageIndex = page
                val ann = vm.getPageAnnotations(page)
                binding.drawingView.loadStrokes(ann?.strokes ?: emptyList())
                supportActionBar?.subtitle = "หน้า ${page + 1} / ${vm.totalPages.value}"
            })
            .scrollHandle(DefaultScrollHandle(this))
            .spacing(10)
            .load()

        supportActionBar?.title = name
    }

    private fun setupToolButtons() {
        binding.btnPen.setOnClickListener { setTool(DrawTool.PEN) }
        binding.btnHighlight.setOnClickListener { setTool(DrawTool.HIGHLIGHTER) }
        binding.btnEraser.setOnClickListener { setTool(DrawTool.ERASER) }
        setTool(DrawTool.PEN)
    }

    private fun setTool(tool: DrawTool) {
        binding.drawingView.currentTool = tool
        binding.btnPen.isSelected = tool == DrawTool.PEN
        binding.btnHighlight.isSelected = tool == DrawTool.HIGHLIGHTER
        binding.btnEraser.isSelected = tool == DrawTool.ERASER
    }

    private fun setupColorPicker() {
        val colorButtons = listOf(
            binding.color1, binding.color2, binding.color3,
            binding.color4, binding.color5, binding.color6
        )
        colorButtons.forEachIndexed { i, btn ->
            btn.setBackgroundColor(COLORS[i])
            btn.setOnClickListener {
                selectedColor = COLORS[i]
                binding.drawingView.strokeColor = selectedColor
                colorButtons.forEach { b -> b.isSelected = false }
                btn.isSelected = true
            }
        }
        colorButtons[0].isSelected = true
    }

    private fun setupSizeSlider() {
        binding.seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val size = (progress + 1).toFloat() * 2f
                binding.drawingView.strokeWidth = size
                binding.tvSize.text = "${progress + 1}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.seekSize.progress = 2
    }

    private fun setupSaveBadge() {
        lifecycleScope.launch {
            vm.saveState.collectLatest { state ->
                binding.tvSaveState.text = when (state) {
                    PdfViewModel.SaveState.IDLE -> ""
                    PdfViewModel.SaveState.SAVING -> "⏳ บันทึก..."
                    PdfViewModel.SaveState.SAVED -> "✓ บันทึกแล้ว"
                }
            }
        }
    }

    private fun showTextDialog() {
        val et = EditText(this)
        et.hint = "พิมพ์ข้อความ"
        AlertDialog.Builder(this)
            .setTitle("เพิ่มข้อความ")
            .setView(et)
            .setPositiveButton("ตกลง") { _, _ ->
                val txt = et.text.toString()
                if (txt.isNotBlank()) {
                    Toast.makeText(this, "แตะตำแหน่งที่ต้องการวางข้อความ", Toast.LENGTH_SHORT).show()
                    // set pending text mode
                    binding.drawingView.currentTool = DrawTool.PEN // reset; text via tap handled separately
                    // Simple: place at center of visible area
                    val cx = binding.drawingView.width / 2f
                    val cy = binding.drawingView.height / 2f
                    val ann = TextAnnotation(txt, cx, cy, selectedColor, 40f, vm.currentPage.value)
                    vm.addText(ann)
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }
}
