package com.titipong.pdfwriter.ui

import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.titipong.pdfwriter.R

/** Reading mode: the text of each page, reflowed, at a size the reader chooses. */
class ReadingAdapter(private val texts: List<String>) : RecyclerView.Adapter<ReadingAdapter.Holder>() {

    class Holder(val root: LinearLayout, val header: TextView, val body: TextView) : RecyclerView.ViewHolder(root)

    var textSize = 18f
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var textColor = Color.BLACK
    var mutedColor = Color.GRAY

    override fun getItemCount() = texts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val h = (28 * d).toInt()
            setPadding(h, (16 * d).toInt(), h, (16 * d).toInt())
        }
        val header = TextView(ctx).apply { textSize = 13f }
        val body = TextView(ctx).apply {
            setLineSpacing(0f, 1.45f)
            setTextIsSelectable(true)
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        root.addView(header)
        root.addView(body)
        return Holder(root, header, body)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ctx = holder.root.context
        holder.header.text = ctx.getString(R.string.page_short, position + 1)
        holder.header.setTextColor(mutedColor)
        val text = texts[position]
        holder.body.text = text.ifBlank { ctx.getString(R.string.reading_no_text) }
        holder.body.setTextColor(if (text.isBlank()) mutedColor else textColor)
        holder.body.textSize = textSize
    }
}
