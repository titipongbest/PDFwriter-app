package com.titipong.pdfwriter.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.titipong.pdfwriter.R
import com.titipong.pdfwriter.model.PageAnnotations
import com.titipong.pdfwriter.pdf.PdfDocumentRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Thumbnail grid of all pages. In edit mode pages can be selected (tap), reordered
 * (long-press and drag, via ItemTouchHelper), deleted, and blank pages inserted.
 * Nothing touches the file until the caller applies [order].
 */
class PageGridAdapter(
    private val doc: PdfDocumentRenderer,
    private val scope: CoroutineScope,
    private val annotationsOf: (Int) -> PageAnnotations?,
    private val isBookmarked: (Int) -> Boolean,
    private val onOpenPage: (Int) -> Unit,
    private val onLongPressInViewMode: (Int) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<PageGridAdapter.Holder>() {

    /** source = index in the current document, or null for a new blank page. */
    class Slot(val source: Int?, val isCopy: Boolean = false)

    class Holder(
        val root: FrameLayout,
        val card: FrameLayout,
        val page: PdfPageView,
        val label: TextView,
        val check: TextView
    ) : RecyclerView.ViewHolder(root) {
        var job: Job? = null
    }

    val slots = mutableListOf<Slot>()
    val selected = LinkedHashSet<Slot>()

    /** Selection only (for "extract pages"): no dragging, no move labels. */
    var pickMode = false

    fun selectAll() {
        selected.clear()
        selected.addAll(slots)
        notifyItemRangeChanged(0, itemCount, STATE_PAYLOAD)
        onSelectionChanged()
    }

    var editMode = false
        set(value) {
            field = value
            if (!value) selected.clear()
            notifyItemRangeChanged(0, itemCount, STATE_PAYLOAD)
        }

    init {
        reset()
    }

    fun reset() {
        slots.clear()
        for (i in 0 until doc.pageCount) slots.add(Slot(i))
        selected.clear()
        notifyDataSetChanged()
    }

    fun isModified(): Boolean =
        slots.size != doc.pageCount || slots.withIndex().any { (i, s) -> s.source != i }

    fun order(): List<Int?> = slots.map { it.source }

    fun move(from: Int, to: Int) {
        val s = slots.removeAt(from)
        slots.add(to, s)
        notifyItemMoved(from, to)
    }

    fun refreshLabels() = notifyItemRangeChanged(0, itemCount, STATE_PAYLOAD)

    /** Returns false if that would delete every page. */
    fun deleteSelected(): Boolean {
        if (selected.isEmpty()) return true
        if (selected.size >= slots.size) return false
        slots.removeAll(selected)
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
        return true
    }

    /** Inserts a copy right after each selected page (in page order). Returns how many were added. */
    fun duplicateSelected(): Int {
        if (selected.isEmpty()) return 0
        val targets = slots.filter { it in selected }
        for (s in targets.asReversed()) {
            val pos = slots.indexOf(s)
            slots.add(pos + 1, Slot(s.source, isCopy = true))
        }
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
        return targets.size
    }

    /** Inserts a blank page after the last selected page, or at the end. Returns its position. */
    fun insertBlank(): Int {
        val pos = selected.lastOrNull()?.let { slots.indexOf(it) + 1 } ?: slots.size
        slots.add(pos, Slot(null))
        notifyItemInserted(pos)
        notifyItemRangeChanged(pos, slots.size - pos, STATE_PAYLOAD)
        return pos
    }

    override fun getItemCount(): Int = slots.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val p = (8 * d).toInt()
            setPadding(p, p, p, p)
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val card = FrameLayout(ctx).apply {
            val p = (3 * d).toInt()
            setPadding(p, p, p, p)
        }
        val page = PdfPageView(ctx)
        card.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val check = TextView(ctx).apply {
            text = "✓"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2563EB"))
            }
            visibility = View.GONE
        }
        val cs = (28 * d).toInt()
        card.addView(check, FrameLayout.LayoutParams(cs, cs).apply {
            gravity = Gravity.TOP or Gravity.START
            val m = (6 * d).toInt()
            setMargins(m, m, m, m)
        })
        val label = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, (6 * d).toInt(), 0, 0)
        }
        column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column)
        return Holder(root, card, page, label, check)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(STATE_PAYLOAD)) {
            bindState(holder, position)
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val slot = slots[position]
        val src = slot.source
        val v = holder.page
        v.pageIndex = src ?: -1
        v.pageAspect = aspectFor(position)
        v.annotations = src?.let(annotationsOf)
        v.bookmarked = src?.let(isBookmarked) ?: false
        v.bitmap = null
        holder.job?.cancel()
        if (src != null) {
            holder.job = scope.launch {
                val w = if (v.width > 0) v.width else THUMB_WIDTH
                val bmp = try { doc.render(src, w) } catch (e: Exception) { null }
                if (bmp != null && v.pageIndex == src) v.bitmap = bmp
            }
        }
        bindState(holder, position)
    }

    private fun aspectFor(position: Int): Float {
        slots[position].source?.let { return doc.aspects[it] }
        // Blank page: same shape as the nearest real page before it (that's how it will be created).
        for (i in position - 1 downTo 0) slots[i].source?.let { return doc.aspects[it] }
        slots.firstOrNull { it.source != null }?.source?.let { return doc.aspects[it] }
        return 1.4142f
    }

    private fun bindState(holder: Holder, position: Int) {
        val ctx = holder.root.context
        val d = ctx.resources.displayMetrics.density
        val slot = slots[position]
        val isSelected = editMode && slot in selected
        holder.card.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 4 * d
            if (isSelected) setStroke((3 * d).toInt(), Color.parseColor("#2563EB"))
            else setStroke((1 * d).toInt(), Color.parseColor("#D1D5DB"))
        }
        holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.label.text = when {
            slot.source == null -> ctx.getString(R.string.page_blank_label, position + 1)
            slot.isCopy -> ctx.getString(R.string.page_copy_label, position + 1, slot.source + 1)
            editMode && slot.source != position -> ctx.getString(R.string.page_moved_label, position + 1, slot.source + 1)
            else -> (position + 1).toString()
        }
        holder.root.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val s = slots[pos]
            if (editMode) {
                if (!selected.remove(s)) selected.add(s)
                notifyItemChanged(pos, STATE_PAYLOAD)
                onSelectionChanged()
            } else {
                s.source?.let(onOpenPage)
            }
        }
        if (editMode) {
            holder.root.setOnLongClickListener(null)
            holder.root.isLongClickable = false
        } else {
            holder.root.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongPressInViewMode(pos)
                true
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.page.release()
    }

    companion object {
        private const val STATE_PAYLOAD = "state"
        private const val THUMB_WIDTH = 320
    }
}
