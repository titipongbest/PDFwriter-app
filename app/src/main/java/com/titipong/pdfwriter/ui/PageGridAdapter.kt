package com.titipong.pdfwriter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Thumbnail grid of all pages. In edit mode pages can be selected (tap), reordered
 * (long-press and drag, via ItemTouchHelper), deleted, and blank pages inserted.
 * Nothing touches the file until the caller applies [order].
 */
class PageGridAdapter(
    private val doc: PdfDocumentRenderer,
    private val scope: CoroutineScope,
    private val span: Int,
    private val startPage: Int,
    private val annotationsOf: (Int) -> PageAnnotations?,
    private val isBookmarked: (Int) -> Boolean,
    private val onOpenPage: (Int) -> Unit,
    private val onLongPressInViewMode: (Int) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<PageGridAdapter.Holder>() {

    /** Lightweight flat bitmap view for thumbnails (no hardware FBO layer, no UI-thread stroke rasterization). */
    class ThumbView(context: Context) : View(context) {
        var pageIndex = -1
        var pageAspect = 1.4142f
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                    invalidate()
                }
            }
        var bitmap: Bitmap? = null
            set(value) {
                if (field !== value) {
                    field = value
                    invalidate()
                }
            }
        var bookmarked = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val density = resources.displayMetrics.density
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()
        private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF4444") }
        private val ribbonPath = Path()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
            } else {
                setMeasuredDimension(w, (w * pageAspect).toInt().coerceAtLeast(1))
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val bmp = bitmap
            if (bmp != null && !bmp.isRecycled && (bmp.config != Bitmap.Config.HARDWARE || canvas.isHardwareAccelerated)) {
                dst.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            }
            if (bookmarked) {
                val w = width.toFloat()
                val rw = (w * 0.045f).coerceIn(10f * density, 28f * density)
                val rh = rw * 1.5f
                val right = w - rw * 0.8f
                val left = right - rw
                ribbonPath.reset()
                ribbonPath.moveTo(left, 0f)
                ribbonPath.lineTo(right, 0f)
                ribbonPath.lineTo(right, rh)
                ribbonPath.lineTo((left + right) / 2f, rh - rw * 0.4f)
                ribbonPath.lineTo(left, rh)
                ribbonPath.close()
                canvas.drawPath(ribbonPath, ribbonPaint)
            }
        }
    }

    /** source = index in the current document, or null for a new blank page. */
    class Slot(val source: Int?, val isCopy: Boolean = false)

    class Holder(
        val root: FrameLayout,
        val cardBg: GradientDrawable,
        val page: ThumbView,
        val label: TextView,
        val check: TextView
    ) : RecyclerView.ViewHolder(root) {
        var job: Job? = null
        var needsLoad = false
        var boundSelected: Boolean? = null
        var longClickActive: Boolean? = null
        lateinit var longClickListener: View.OnLongClickListener
    }

    val slots = mutableListOf<Slot>()
    val selected = LinkedHashSet<Slot>()

    private var thumbWidth = THUMB_WIDTH
    private var prewarmJob: Job? = null

    @Volatile
    private var fastScrolling = false

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            val threshold = (14f * rv.resources.displayMetrics.density).toInt().coerceAtLeast(18)
            val wasFast = fastScrolling
            fastScrolling = rv.scrollState == RecyclerView.SCROLL_STATE_SETTLING && abs(dy) > threshold
            if (wasFast && !fastScrolling) {
                bindVisiblePending(rv)
            }
        }

        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            if (newState != RecyclerView.SCROLL_STATE_SETTLING) {
                fastScrolling = false
                bindVisiblePending(rv)
            }
        }
    }

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

    private fun computeThumbWidth(ctx: Context, gridWidthPx: Int): Int {
        val d = ctx.resources.displayMetrics.density
        val totalW = if (gridWidthPx > 0) gridWidthPx else ctx.resources.displayMetrics.widthPixels
        val gridPad = (16 * d).toInt()
        val itemPad = (22 * d).toInt()
        val colW = (totalW - gridPad) / span.coerceAtLeast(1) - itemPad
        return colW.coerceIn(240, 640)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        fastScrolling = false
        thumbWidth = computeThumbWidth(recyclerView.context, recyclerView.width)
        recyclerView.addOnScrollListener(scrollListener)
        startPrewarm()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerView.removeOnScrollListener(scrollListener)
        fastScrolling = false
        prewarmJob?.cancel()
        prewarmJob = null
    }

    private fun bindVisiblePending(rv: RecyclerView) {
        for (i in 0 until rv.childCount) {
            val h = rv.getChildViewHolder(rv.getChildAt(i)) as? Holder ?: continue
            if (h.needsLoad && h.job?.isActive != true) {
                val pos = h.bindingAdapterPosition
                if (pos in slots.indices) {
                    val src = slots[pos].source
                    if (src != null) startLoad(h, src, aspectFor(pos))
                }
            }
        }
    }

    /**
     * Pre-composites annotated page thumbnails sequentially on a background worker (nearest to
     * [startPage] first) into GPU HARDWARE bitmaps so scrolling either down or up is instant.
     */
    private fun startPrewarm() {
        prewarmJob?.cancel()
        prewarmJob = scope.launch {
            val tw = thumbWidth
            val targets = ArrayList<Int>()
            for (p in 0 until doc.pageCount) {
                val a = annotationsOf(p)
                if (a != null && !a.isEmpty()) targets.add(p)
            }
            if (targets.isEmpty()) return@launch
            targets.sortBy { abs(it - startPage) }
            for (p in targets) {
                while (fastScrolling) delay(40)
                ensureActive()
                val a = annotationsOf(p) ?: continue
                if (a.isEmpty()) continue
                val stamp = PdfDocumentRenderer.annotStamp(a)
                val existing = doc.getCachedThumb(p, stamp, tw)
                if (existing != null && existing.hasPage) continue
                val base = try {
                    doc.peekThumb(p)?.takeIf { it.width >= tw * 0.85f } ?: doc.quickThumb(p, tw)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
                if (base == null && existing != null) continue
                val snap = a.copy()
                val aspect = doc.aspects[p]
                val bmp = withContext(thumbDispatcher) {
                    PdfPageView.renderThumbnail(base, snap, tw, aspect)
                }
                if (bmp != null) {
                    doc.putCachedThumb(p, stamp, hasPage = base != null, bmp)
                }
                yield()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        if (parent.width > 0) thumbWidth = computeThumbWidth(ctx, parent.width)
        val d = ctx.resources.displayMetrics.density
        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val p = (8 * d).toInt()
            setPadding(p, p, p, p)
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cardBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 4 * d
            setStroke((1 * d).toInt(), Color.parseColor("#D1D5DB"))
        }
        val card = FrameLayout(ctx).apply {
            val p = (3 * d).toInt()
            setPadding(p, p, p, p)
            background = cardBg
        }
        val page = ThumbView(ctx)
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
        val holder = Holder(root, cardBg, page, label, check)
        root.setOnClickListener {
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
        holder.longClickListener = View.OnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onLongPressInViewMode(pos)
            true
        }
        return holder
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
        holder.job?.cancel()
        holder.job = null
        holder.needsLoad = false
        val aspect = aspectFor(position)
        v.pageIndex = src ?: -1
        v.pageAspect = aspect
        v.bookmarked = src?.let(isBookmarked) ?: false
        if (src == null) {
            v.bitmap = null
            bindState(holder, position)
            return
        }
        // Keep RenderLanes prioritized toward whichever direction the user is scrolling (up or down).
        doc.focusPage = src
        val tw = if (v.width > 0) v.width.coerceIn(240, 640) else thumbWidth
        val a = annotationsOf(src)
        val stamp = if (a == null || a.isEmpty()) PLAIN_STAMP else PdfDocumentRenderer.annotStamp(a)
        val cached = doc.getCachedThumb(src, stamp, tw)
        if (cached != null && cached.hasPage) {
            v.bitmap = cached.bitmap
        } else {
            v.bitmap = cached?.bitmap ?: doc.peekThumb(src)
            holder.needsLoad = true
            if (!fastScrolling) {
                startLoad(holder, src, aspect)
            }
        }
        bindState(holder, position)
    }

    private fun startLoad(holder: Holder, src: Int, aspect: Float) {
        val v = holder.page
        if (v.pageIndex != src) return
        val tw = if (v.width > 0) v.width.coerceIn(240, 640) else thumbWidth
        val a = annotationsOf(src)
        val hasAnnot = a != null && !a.isEmpty()
        val stamp = if (hasAnnot && a != null) PdfDocumentRenderer.annotStamp(a) else PLAIN_STAMP
        val cached = doc.getCachedThumb(src, stamp, tw)
        if (cached != null && cached.hasPage) {
            v.bitmap = cached.bitmap
            holder.needsLoad = false
            return
        }
        val snap = if (hasAnnot) a?.copy() else null
        val peeked = doc.peekThumb(src)?.takeIf { it.width >= tw * 0.85f }
        holder.job?.cancel()
        holder.job = scope.launch {
            val quickBase = peeked
                ?: try { doc.quickThumb(src, tw) } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { null }
            if (quickBase != null) {
                val full = withContext(thumbDispatcher) {
                    PdfPageView.renderThumbnail(quickBase, snap, tw, aspect)
                }
                if (full != null) {
                    doc.putCachedThumb(src, stamp, hasPage = true, full)
                    if (v.pageIndex == src) {
                        v.bitmap = full
                        holder.needsLoad = false
                    }
                }
                return@launch
            }
            if (snap != null && cached == null) {
                val prelim = withContext(thumbDispatcher) {
                    PdfPageView.renderThumbnail(null, snap, tw, aspect)
                }
                if (prelim != null) {
                    doc.putCachedThumb(src, stamp, hasPage = false, prelim)
                    if (v.pageIndex == src) v.bitmap = prelim
                }
            }
            delay(SETTLE_DEBOUNCE_MS)
            while (fastScrolling) delay(40)
            ensureActive()
            if (v.pageIndex != src) return@launch
            doc.focusPage = src
            val rendered = try { doc.renderThumb(src, tw) } catch (ce: CancellationException) { throw ce } catch (_: Throwable) { null }
                ?: return@launch
            val full = withContext(thumbDispatcher) {
                PdfPageView.renderThumbnail(rendered, snap, tw, doc.aspects[src])
            }
            if (full != null) {
                doc.putCachedThumb(src, stamp, hasPage = true, full)
                if (v.pageIndex == src) {
                    v.bitmap = full
                    holder.needsLoad = false
                }
            }
        }
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
        holder.page.bookmarked = slot.source?.let(isBookmarked) ?: false
        val isSelected = editMode && slot in selected
        if (holder.boundSelected != isSelected) {
            holder.boundSelected = isSelected
            if (isSelected) holder.cardBg.setStroke((3 * d).toInt(), Color.parseColor("#2563EB"))
            else holder.cardBg.setStroke((1 * d).toInt(), Color.parseColor("#D1D5DB"))
            holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
        holder.label.text = when {
            slot.source == null -> ctx.getString(R.string.page_blank_label, position + 1)
            slot.isCopy -> ctx.getString(R.string.page_copy_label, position + 1, slot.source + 1)
            editMode && slot.source != position -> ctx.getString(R.string.page_moved_label, position + 1, slot.source + 1)
            else -> (position + 1).toString()
        }
        val wantLongClick = !editMode
        if (holder.longClickActive != wantLongClick) {
            holder.longClickActive = wantLongClick
            if (wantLongClick) {
                holder.root.setOnLongClickListener(holder.longClickListener)
            } else {
                holder.root.setOnLongClickListener(null)
                holder.root.isLongClickable = false
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.needsLoad = false
        holder.page.pageIndex = -1
        holder.page.bitmap = null
    }

    companion object {
        private const val STATE_PAYLOAD = "state"
        private const val THUMB_WIDTH = 320
        private const val PLAIN_STAMP = 0L
        private const val SETTLE_DEBOUNCE_MS = 55L

        private val thumbDispatcher = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "pdf-thumb").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }.asCoroutineDispatcher()
    }
}
