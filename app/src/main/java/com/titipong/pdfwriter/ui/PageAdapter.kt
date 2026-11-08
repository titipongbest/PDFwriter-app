package com.titipong.pdfwriter.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.titipong.pdfwriter.pdf.PdfDocumentRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PageAdapter(
    private val doc: PdfDocumentRenderer,
    private val host: PdfPageView.Host,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PageAdapter.Holder>() {

    class Holder(val page: PdfPageView) : RecyclerView.ViewHolder(page) {
        var job: Job? = null
    }

    override fun getItemCount(): Int = doc.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = PdfPageView(parent.context)
        val gap = (12 * parent.resources.displayMetrics.density).toInt()
        view.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = gap }
        view.host = host
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(INK_PAYLOAD)) {
            holder.page.invalidateInk()
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val v = holder.page
        v.pageIndex = position
        v.pageAspect = doc.aspects[position]
        v.annotations = host.annotationsFor(position)
        v.bitmap = null
        holder.job?.cancel()
        holder.job = scope.launch {
            val parentWidth = (v.parent as? View)?.width ?: 0
            val target = when {
                v.width > 0 -> v.width
                parentWidth > 0 -> parentWidth
                else -> v.resources.displayMetrics.widthPixels
            }
            val bmp = try {
                doc.render(position, target)
            } catch (e: Exception) {
                null
            }
            if (bmp != null && v.pageIndex == position) v.bitmap = bmp
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.job = null
        holder.page.release()
    }

    companion object {
        const val INK_PAYLOAD = "ink"
    }
}
