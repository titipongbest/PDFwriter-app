package com.titipong.pdfwriter.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.barteksc.pdfviewer.PDFView
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.model.SerializableStroke
import com.titipong.pdfwriter.model.TextAnnotation
import com.titipong.pdfwriter.storage.AnnotationStorage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PdfViewModel(app: Application) : AndroidViewModel(app) {

    private val _pdfName = MutableStateFlow<String?>(null)
    val pdfName: StateFlow<String?> = _pdfName

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages

    private val _saveState = MutableStateFlow(SaveState.IDLE)
    val saveState: StateFlow<SaveState> = _saveState

    // pageIndex -> annotations
    private val annotations = mutableMapOf<Int, PageAnnotations>()
    private var saveJob: Job? = null

    enum class SaveState { IDLE, SAVING, SAVED }

    fun onPdfLoaded(name: String, total: Int) {
        _pdfName.value = name
        _totalPages.value = total
        _currentPage.value = 0
        viewModelScope.launch {
            val loaded = AnnotationStorage.load(getApplication(), name)
            annotations.clear()
            annotations.putAll(loaded)
        }
    }

    fun onPageChanged(page: Int) {
        _currentPage.value = page
    }

    fun addStroke(stroke: SerializableStroke) {
        val page = annotations.getOrPut(stroke.pageIndex) { PageAnnotations() }
        page.strokes.add(stroke)
        scheduleSave()
    }

    fun addText(text: TextAnnotation) {
        val page = annotations.getOrPut(text.pageIndex) { PageAnnotations() }
        page.texts.add(text)
        scheduleSave()
    }

    fun undoStroke(pageIndex: Int) {
        annotations[pageIndex]?.strokes?.removeLastOrNull()
        scheduleSave()
    }

    fun clearPage(pageIndex: Int) {
        annotations[pageIndex]?.let { it.strokes.clear(); it.texts.clear() }
        scheduleSave()
    }

    fun getPageAnnotations(page: Int): PageAnnotations? = annotations[page]

    private fun scheduleSave() {
        _saveState.value = SaveState.SAVING
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            val name = _pdfName.value ?: return@launch
            AnnotationStorage.save(getApplication(), name, annotations)
            _saveState.value = SaveState.SAVED
        }
    }
}
