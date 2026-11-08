package com.titipong.pdfwriter.ui

import android.graphics.RectF
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.RecyclerView
import com.titipong.pdfwriter.pdf.PdfDocumentRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class PageLayout { SINGLE, DOUBLE, COVER }

/** Display settings for the reader list. */
class ViewConfig(
    val layout: PageLayout,
    val vertical: Boolean,
    val rotation: Int,
    val crops: Map<Int, RectF>?,
    val look: PdfPageView.Look
)

/** Reader list: each item is a spread of one or two pages. */
class PageAdapter(
    private val doc: PdfDocumentRenderer,
    private val host: PdfPageView.Host,
    private val scope: CoroutineScope,
    private val config: ViewConfig
) : RecyclerView.Adapter<PageAdapter.Holder>() {

    class Holder(val spread: SpreadView) : RecyclerView.ViewHolder(spread) {
        val jobs = arrayOfNulls<Job>(2)
    }

    /** Pages shown by each item. */
    private val spreads: List<IntArray> = buildSpreads(doc.pageCount, config.layout)
    private val positionOfPage = IntArray(doc.pageCount).also { arr ->
        spreads.forEachIndexed { pos, s -> s.forEach { arr[it] = pos } }
    }

    /** Pages render at (page width on screen x renderScale) so zoomed pages stay sharp. */
    var renderScale = 1f

    fun positionOf(page: Int): Int = if (page in positionOfPage.indices) positionOfPage[page] else 0
    /** The page view showing [page], if it's on screen. */
    fun pageViewFor(rv: RecyclerView, page: Int): PdfPageView? {
        if (page !in positionOfPage.indices) return null
        val pos = positionOfPage[page]
        val holder = rv.findViewHolderForAdapterPosition(pos) as? Holder ?: return null
        val i = spreads[pos].indexOf(page)
        return if (i >= 0) holder.spread.pages[i] else null
    }

    fun firstPageAt(position: Int): Int = spreads.getOrNull(position)?.firstOrNull() ?: -1
    fun notifyPage(page: Int, payload: Any) {
        if (page in positionOfPage.indices) notifyItemChanged(positionOfPage[page], payload)
    }

    override fun getItemCount(): Int = spreads.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = SpreadView(parent.context)
        val gap = (12 * parent.resources.displayMetrics.density).toInt()
        view.layoutParams = if (config.vertical) {
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = gap }
        } else {
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        view.fitViewport = !config.vertical
        view.pages.forEach { it.host = host }
        val holder = Holder(view)
        // Self-healing: a page still blank when drawn (render failed / was lost) asks to be rendered again.
        view.pages.forEachIndexed { slot, pv ->
            pv.onNeedBitmap = {
                val page = pv.pageIndex
                if (page >= 0 && pv.bitmap == null && holder.jobs[slot]?.isActive != true) render(holder, slot, page)
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        val s = spreads[position]
        if (payloads.contains(RERENDER_PAYLOAD)) {
            s.forEachIndexed { i, page ->
                holder.spread.pages[i].invalidateInk()
                render(holder, i, page)
            }
            return
        }
        if (payloads.contains(SEARCH_PAYLOAD)) {
            s.forEachIndexed { i, page ->
                holder.spread.pages[i].hits = host.searchHits(page)
                holder.spread.pages[i].activeHits = host.activeSearchHit(page)
            }
            if (payloads.all { it == SEARCH_PAYLOAD }) return
        }
        if (payloads.contains(INK_PAYLOAD)) {
            s.forEachIndexed { i, page ->
                holder.spread.pages[i].bookmarked = host.isBookmarked(page)
                holder.spread.pages[i].invalidateInk()
            }
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = spreads[position]
        holder.spread.count = s.size
        s.forEachIndexed { i, page ->
            val v = holder.spread.pages[i]
            v.pageIndex = page
            v.pageAspect = doc.aspects[page]
            v.viewRotation = config.rotation
            v.crop = config.crops?.get(page)
            v.look = config.look
            v.annotations = host.annotationsFor(page)
            v.bookmarked = host.isBookmarked(page)
            v.hits = host.searchHits(page)
            v.activeHits = host.activeSearchHit(page)
            // Show what's already loaded right away (no blank page), then sharpen.
            v.bitmap = doc.peek(page)
            render(holder, i, page)
        }
    }

    /** Waits for layout (so the on-screen size is known), keeps the old bitmap until the new one is ready. */
    private fun render(holder: Holder, slot: Int, page: Int) {
        val v = holder.spread.pages[slot]
        holder.jobs[slot]?.cancel()
        v.doOnLayout {
            if (v.pageIndex != page) return@doOnLayout
            holder.jobs[slot]?.cancel()
            holder.jobs[slot] = scope.launch {
                val target = (v.renderWidth().coerceAtLeast(v.width) * renderScale).toInt()
                // Retry failed renders (e.g. memory pressure), at a lower resolution after the first tries.
                var bmp: android.graphics.Bitmap? = null
                var attempt = 0
                while (bmp == null && attempt < RENDER_ATTEMPTS && v.pageIndex == page) {
                    val factor = if (attempt < 2) 1f else Math.pow(0.7, (attempt - 1).toDouble()).toFloat()
                    bmp = try {
                        doc.render(page, (target * factor).toInt())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                    if (bmp == null) {
                        attempt++
                        kotlinx.coroutines.delay(300L * attempt)
                    }
                }
                if (bmp != null && v.pageIndex == page) v.bitmap = bmp
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.jobs.forEachIndexed { i, job ->
            job?.cancel()
            holder.jobs[i] = null
        }
        holder.spread.pages.forEach { it.release() }
    }

    companion object {
        const val INK_PAYLOAD = "ink"
        const val RERENDER_PAYLOAD = "rerender"
        const val SEARCH_PAYLOAD = "search"
        private const val RENDER_ATTEMPTS = 5

        fun buildSpreads(count: Int, layout: PageLayout): List<IntArray> {
            val out = ArrayList<IntArray>()
            var i = 0
            if (layout == PageLayout.COVER && count > 0) {
                out.add(intArrayOf(0))
                i = 1
            }
            while (i < count) {
                if (layout != PageLayout.SINGLE && i + 1 < count) {
                    out.add(intArrayOf(i, i + 1))
                    i += 2
                } else {
                    out.add(intArrayOf(i))
                    i += 1
                }
            }
            return out
        }
    }
}
