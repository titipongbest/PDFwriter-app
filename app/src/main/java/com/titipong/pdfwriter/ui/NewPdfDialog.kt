package com.titipong.pdfwriter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.titipong.pdfwriter.R
import com.titipong.pdfwriter.pdf.PageTemplate
import com.titipong.pdfwriter.pdf.PdfTemplates

/** "New": file name, portrait / landscape, and paper style (with previews). */
object NewPdfDialog {

    fun show(context: Context, defaultName: String, onCreate: (name: String, landscape: Boolean, t: PageTemplate) -> Unit) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun label(res: Int) = TextView(context).apply {
            setText(res)
            textSize = 14f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(14), 0, dp(6))
        }

        root.addView(label(R.string.new_name))
        val name = EditText(context).apply {
            setText(defaultName)
            setSelection(defaultName.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        root.addView(name)

        root.addView(label(R.string.new_orientation))
        val orient = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val portrait = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            setText(R.string.new_portrait)
        }
        val landscapeBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            setText(R.string.new_landscape)
        }
        orient.addView(portrait)
        orient.addView(landscapeBtn)
        orient.check(portrait.id)
        root.addView(orient)

        root.addView(label(R.string.new_template))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        root.addView(scroller)

        var chosen = PageTemplate.BLANK
        val names = mapOf(
            PageTemplate.BLANK to R.string.tpl_blank,
            PageTemplate.LINED to R.string.tpl_lined,
            PageTemplate.GRID to R.string.tpl_grid,
            PageTemplate.DOTTED to R.string.tpl_dotted,
            PageTemplate.CORNELL to R.string.tpl_cornell
        )
        val cards = mutableMapOf<PageTemplate, Pair<LinearLayout, ImageView>>()
        fun frame(selected: Boolean) = GradientDrawable().apply {
            cornerRadius = 8 * d
            setColor(if (selected) Color.parseColor("#EFF6FF") else Color.TRANSPARENT)
            setStroke(dp(if (selected) 2 else 1), Color.parseColor(if (selected) "#2563EB" else "#D1D5DB"))
        }
        fun refresh() {
            val land = orient.checkedButtonId == landscapeBtn.id
            for ((t, pair) in cards) {
                pair.first.background = frame(t == chosen)
                pair.second.setImageBitmap(preview(t, land, dp(if (land) 92 else 66)))
            }
        }
        for (t in PageTemplate.values()) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(6))
                isClickable = true
            }
            val img = ImageView(context)
            card.addView(img, LinearLayout.LayoutParams(dp(92), dp(92)).apply { gravity = Gravity.CENTER })
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            card.addView(TextView(context).apply {
                setText(names.getValue(t))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
            card.setOnClickListener {
                chosen = t
                refresh()
            }
            row.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
            cards[t] = card to img
        }
        orient.addOnButtonCheckedListener { _, _, checked -> if (checked) refresh() }
        refresh()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.new_pdf)
            .setView(root)
            .setPositiveButton(R.string.create) { _, _ ->
                val n = name.text.toString().trim().ifEmpty { defaultName }
                onCreate(n, orient.checkedButtonId == landscapeBtn.id, chosen)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A small picture of the paper style, [longSide] px on its longer side. */
    private fun preview(t: PageTemplate, landscape: Boolean, longSide: Int): Bitmap {
        val w = if (landscape) PdfTemplates.A4_H else PdfTemplates.A4_W
        val h = if (landscape) PdfTemplates.A4_W else PdfTemplates.A4_H
        val s = longSide / maxOf(w, h)
        val bmp = Bitmap.createBitmap((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun col(f: FloatArray) = Color.rgb((f[0] * 255).toInt(), (f[1] * 255).toInt(), (f[2] * 255).toInt())
        PdfTemplates.draw(t, w, h, object : PdfTemplates.Painter {
            override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: FloatArray, width: Float) {
                paint.color = col(color)
                paint.strokeWidth = maxOf(width * s * 1.6f, 0.8f)
                c.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, paint)
            }

            override fun dot(x: Float, y: Float, r: Float, color: FloatArray) {
                paint.color = col(color)
                c.drawCircle(x * s, y * s, maxOf(r * s * 1.6f, 0.7f), paint)
            }
        })
        // Page edge.
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#9CA3AF")
        paint.strokeWidth = 1f
        c.drawRect(0.5f, 0.5f, bmp.width - 0.5f, bmp.height - 0.5f, paint)
        return bmp
    }
}
