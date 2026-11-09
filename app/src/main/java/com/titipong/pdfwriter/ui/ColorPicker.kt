package com.titipong.pdfwriter.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.titipong.pdfwriter.R
import java.util.Locale

/** Color editor: presets, hue / saturation / brightness sliders, and a hex field. */
object ColorPicker {

    private val PRESETS = intArrayOf(
        0xFF111827.toInt(), 0xFF4B5563.toInt(), 0xFF9CA3AF.toInt(), 0xFFFFFFFF.toInt(),
        0xFFDC2626.toInt(), 0xFFF97316.toInt(), 0xFFF59E0B.toInt(), 0xFFFACC15.toInt(),
        0xFF16A34A.toInt(), 0xFF22C55E.toInt(), 0xFF0D9488.toInt(), 0xFF06B6D4.toInt(),
        0xFF2563EB.toInt(), 0xFF4F46E5.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt()
    )

    /** [hueOnly]: just the hue bar (plus presets for black / gray) — used for pen colors. */
    /**
     * [pressure]: when not null (pen), also shows the line option Pressure / Even; the choice is
     * given to [onPressure] on OK.
     */
    fun show(
        context: Context, initial: Int, hueOnly: Boolean = false,
        pressure: Boolean? = null, onPressure: (Boolean) -> Unit = {}, onPicked: (Int) -> Unit
    ) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        var color = initial
        var updating = false

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }

        val preview = View(context)
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        val hex = EditText(context).apply {
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(7))
            hint = "#2563EB"
        }
        root.addView(hex)

        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(8), 0, 0)
        }

        val hue = SeekBar(context).apply { max = 360 }
        val sat = SeekBar(context).apply { max = 100 }
        val bri = SeekBar(context).apply { max = 100 }
        // Rainbow track for hue.
        hue.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        ).apply { cornerRadius = dp(4).toFloat(); setSize(0, dp(8)) }

        root.addView(label(context.getString(R.string.color_hue)))
        root.addView(hue)
        if (!hueOnly) {
            root.addView(label(context.getString(R.string.color_saturation)))
            root.addView(sat)
            root.addView(label(context.getString(R.string.color_brightness)))
            root.addView(bri)
        }
        if (hueOnly) hex.visibility = View.GONE

        fun refresh(fromHex: Boolean) {
            updating = true
            preview.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#33000000"))
            }
            if (!fromHex) {
                hex.setText(String.format(Locale.US, "#%06X", color and 0xFFFFFF))
                hex.setSelection(hex.text.length)
            }
            hue.progress = hsv[0].toInt()
            sat.progress = (hsv[1] * 100).toInt()
            bri.progress = (hsv[2] * 100).toInt()
            updating = false
        }

        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating || !fromUser) return
                hsv[0] = hue.progress.toFloat()
                if (hueOnly) {
                    // One bar: a clear, rich color of that hue.
                    hsv[1] = 0.9f
                    hsv[2] = 0.85f
                } else {
                    hsv[1] = sat.progress / 100f
                    hsv[2] = bri.progress / 100f
                }
                color = Color.HSVToColor(hsv)
                refresh(fromHex = false)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        hue.setOnSeekBarChangeListener(sliderListener)
        sat.setOnSeekBarChangeListener(sliderListener)
        bri.setOnSeekBarChangeListener(sliderListener)

        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val t = s?.toString()?.trim() ?: return
                val normalized = if (t.startsWith("#")) t else "#$t"
                if (normalized.length != 7) return
                try {
                    color = Color.parseColor(normalized)
                    Color.colorToHSV(color, hsv)
                    refresh(fromHex = true)
                } catch (e: IllegalArgumentException) {
                    // Not a color yet; keep typing.
                }
            }
        })

        // Presets: 2 rows of 8.
        root.addView(label(context.getString(R.string.color_presets)))
        val size = dp(30)
        for (row in 0 until 2) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            for (i in 0 until 8) {
                val c = PRESETS[row * 8 + i]
                val sw = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke(dp(1), Color.parseColor("#33000000"))
                    }
                    setOnClickListener {
                        color = c
                        Color.colorToHSV(color, hsv)
                        refresh(fromHex = false)
                    }
                }
                line.addView(sw, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) })
            }
            root.addView(line)
        }

        // Pen line: width follows pressure, or even.
        var usePressure = pressure ?: true
        if (pressure != null) {
            root.addView(label(context.getString(R.string.pen_line)))
            val group = com.google.android.material.button.MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
            val on = com.google.android.material.button.MaterialButton(
                context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply { id = View.generateViewId(); setText(R.string.pressure_on) }
            val off = com.google.android.material.button.MaterialButton(
                context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply { id = View.generateViewId(); setText(R.string.pressure_off) }
            group.addView(on)
            group.addView(off)
            group.check(if (pressure) on.id else off.id)
            group.addOnButtonCheckedListener { _, id, checked -> if (checked) usePressure = id == on.id }
            root.addView(group)
        }

        refresh(fromHex = false)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_color)
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (pressure != null) onPressure(usePressure)
                onPicked(color or 0xFF000000.toInt())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
