package com.titipong.pdfwriter.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.titipong.pdfwriter.R
import com.titipong.pdfwriter.model.Bookmark
import com.titipong.pdfwriter.model.OutlineEntry

/** Your bookmarks (editable), followed by the file's own table of contents (read-only). */
class BookmarkAdapter(
    private val onJump: (Int) -> Unit,
    private val onRename: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.Holder>() {

    private sealed class Row {
        class Header(val text: String) : Row()
        class Mine(val bookmark: Bookmark) : Row()
        class Toc(val entry: OutlineEntry) : Row()
    }

    class Holder(
        val root: LinearLayout,
        val title: TextView,
        val page: TextView,
        val rename: TextView,
        val delete: TextView
    ) : RecyclerView.ViewHolder(root)

    private val rows = mutableListOf<Row>()

    fun submit(headerMine: String, mine: List<Bookmark>, headerToc: String, toc: List<OutlineEntry>) {
        rows.clear()
        if (mine.isNotEmpty()) {
            rows.add(Row.Header(headerMine))
            mine.sortedBy { it.page }.forEach { rows.add(Row.Mine(it)) }
        }
        if (toc.isNotEmpty()) {
            rows.add(Row.Header(headerToc))
            toc.forEach { rows.add(Row.Toc(it)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            minimumHeight = (52 * d).toInt()
            setPadding((16 * d).toInt(), 0, (8 * d).toInt(), 0)
        }
        fun action(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#2563EB"))
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
        }
        val title = TextView(ctx).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#111827"))
        }
        val page = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding((12 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        val rename = action(ctx.getString(R.string.rename))
        val delete = action(ctx.getString(R.string.delete)).apply { setTextColor(Color.parseColor("#DC2626")) }
        root.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(page)
        root.addView(rename)
        root.addView(delete)
        return Holder(root, title, page, rename, delete)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ctx = holder.root.context
        val d = ctx.resources.displayMetrics.density
        when (val row = rows[position]) {
            is Row.Header -> {
                holder.title.text = row.text
                holder.title.setTypeface(null, Typeface.BOLD)
                holder.title.setTextColor(Color.parseColor("#6B7280"))
                holder.title.textSize = 13f
                holder.page.visibility = View.GONE
                holder.rename.visibility = View.GONE
                holder.delete.visibility = View.GONE
                holder.root.setBackgroundColor(Color.parseColor("#F3F4F6"))
                holder.root.setOnClickListener(null)
                holder.root.isClickable = false
            }
            is Row.Mine -> {
                bindItem(holder, row.bookmark.title, row.bookmark.page, 0, d)
                holder.title.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_bookmark_filled, 0, 0, 0)
                holder.title.compoundDrawablePadding = (10 * d).toInt()
                holder.rename.visibility = View.VISIBLE
                holder.delete.visibility = View.VISIBLE
                holder.rename.setOnClickListener { onRename(row.bookmark) }
                holder.delete.setOnClickListener { onDelete(row.bookmark) }
            }
            is Row.Toc -> {
                bindItem(holder, row.entry.title, row.entry.page, row.entry.depth, d)
                holder.title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                holder.rename.visibility = View.GONE
                holder.delete.visibility = View.GONE
            }
        }
    }

    private fun bindItem(holder: Holder, title: String, page: Int, depth: Int, d: Float) {
        val ctx = holder.root.context
        holder.title.text = title.ifBlank { ctx.getString(R.string.bookmark_default, page + 1) }
        holder.title.setTypeface(null, Typeface.NORMAL)
        holder.title.setTextColor(Color.parseColor("#111827"))
        holder.title.textSize = 16f
        holder.title.setPadding((depth * 20 * d).toInt(), 0, 0, 0)
        holder.page.visibility = View.VISIBLE
        holder.page.text = if (page >= 0) ctx.getString(R.string.page_short, page + 1) else ""
        holder.root.setBackgroundColor(Color.WHITE)
        if (page >= 0) {
            holder.root.setOnClickListener { onJump(page) }
        } else {
            holder.root.setOnClickListener(null)
            holder.root.isClickable = false
        }
    }
}
