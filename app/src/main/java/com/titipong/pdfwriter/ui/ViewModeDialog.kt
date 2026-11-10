package com.titipong.pdfwriter.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.titipong.pdfwriter.R

/**
 * "View mode" dialog: page layout, scrolling direction, color mode, rotate, crop.
 * Every change applies immediately through [onChange]; OK just closes.
 */
class ViewModeDialog(
    private val context: Context,
    private val settings: ViewSettings,
    private val onChange: () -> Unit
) {
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val accent = Color.parseColor("#2563EB")
    private val ink = Color.parseColor("#1F2937")
    private val muted = Color.parseColor("#6B7280")
    private val divider = Color.parseColor("#E5E7EB")

    private val layoutTiles = mutableListOf<Pair<Choice, LinearLayout>>()
    private val colorRings = mutableListOf<Pair<ColorMode, FrameLayout>>()
    private lateinit var rotationValue: TextView
    private lateinit var customSwatch: TextView

    private enum class Choice { SINGLE, DOUBLE, READING, COVER }

    fun show() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        content.addView(sectionTitle(R.string.vm_viewing_mode))
        content.addView(tileRow(
            tile(Choice.SINGLE, R.drawable.ic_page_single, R.string.vm_single),
            tile(Choice.DOUBLE, R.drawable.ic_page_double, R.string.vm_double)
        ))
        content.addView(tileRow(
            tile(Choice.READING, R.drawable.ic_reading, R.string.vm_reading),
            tile(Choice.COVER, R.drawable.ic_cover, R.string.vm_cover)
        ))

        content.addView(switchRow(R.drawable.ic_vertical_scroll, R.string.vm_vertical, settings.vertical) {
            settings.vertical = it
            onChange()
        })
        content.addView(line())
        content.addView(colorRow())
        content.addView(line())

        content.addView(sectionTitle(R.string.vm_actions))
        content.addView(actionRow(R.drawable.ic_rotate, R.string.vm_rotate) {
            settings.rotation = (settings.rotation + 90) % 360
            updateRotation()
            onChange()
        }.also { row ->
            rotationValue = TextView(context).apply {
                textSize = 16f
                setTextColor(muted)
            }
            row.addView(rotationValue)
        })
        content.addView(line())
        content.addView(switchRow(R.drawable.ic_crop, R.string.vm_crop, settings.crop) {
            settings.crop = it
            onChange()
        })
        content.addView(TextView(context).apply {
            setText(R.string.vm_display_only)
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(24), dp(8), dp(24), 0)
        })

        updateTiles()
        updateColors()
        updateRotation()

        MaterialAlertDialogBuilder(context)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- pieces

    private fun sectionTitle(res: Int) = TextView(context).apply {
        setText(res)
        textSize = 22f
        setTextColor(accent)
        setPadding(dp(24), dp(16), dp(24), dp(8))
    }

    private fun line() = View(context).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun icon(res: Int, sizeDp: Int = 28) = ImageView(context).apply {
        setImageResource(res)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun tileRow(a: View, b: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), 0, dp(16), 0)
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun tile(choice: Choice, iconRes: Int, labelRes: Int): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(8), dp(14))
            isClickable = true
            isFocusable = true
            background = selectableBackground()
        }
        row.addView(icon(iconRes, 32))
        row.addView(TextView(context).apply {
            setText(labelRes)
            textSize = 18f
            setPadding(dp(14), 0, 0, 0)
        })
        row.setOnClickListener {
            when (choice) {
                Choice.READING -> settings.reading = true
                Choice.SINGLE -> { settings.reading = false; settings.layout = PageLayout.SINGLE }
                Choice.DOUBLE -> { settings.reading = false; settings.layout = PageLayout.DOUBLE }
                Choice.COVER -> { settings.reading = false; settings.layout = PageLayout.COVER }
            }
            updateTiles()
            onChange()
        }
        layoutTiles.add(choice to row)
        return row
    }

    private fun currentChoice(): Choice = when {
        settings.reading -> Choice.READING
        settings.layout == PageLayout.DOUBLE -> Choice.DOUBLE
        settings.layout == PageLayout.COVER -> Choice.COVER
        else -> Choice.SINGLE
    }

    private fun updateTiles() {
        val current = currentChoice()
        for ((choice, row) in layoutTiles) {
            val selected = choice == current
            val c = if (selected) accent else ink
            (row.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(c)
            (row.getChildAt(1) as TextView).apply {
                setTextColor(c)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    private fun switchRow(iconRes: Int, labelRes: Int, initial: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        val row = baseRow(iconRes, labelRes)
        val sw = MaterialSwitch(context).apply { isChecked = initial }
        sw.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        row.setOnClickListener { sw.toggle() }
        row.addView(sw)
        return row
    }

    private fun actionRow(iconRes: Int, labelRes: Int, onClick: () -> Unit): LinearLayout {
        val row = baseRow(iconRes, labelRes)
        row.setOnClickListener { onClick() }
        return row
    }

    private fun baseRow(iconRes: Int, labelRes: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
        isClickable = true
        isFocusable = true
        background = selectableBackground()
        addView(icon(iconRes).apply { imageTintList = ColorStateList.valueOf(ink) })
        addView(TextView(context).apply {
            setText(labelRes)
            textSize = 18f
            setTextColor(ink)
            setPadding(dp(20), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun updateRotation() {
        rotationValue.text = context.getString(R.string.vm_rotation_value, settings.rotation)
    }

    // ---------------------------------------------------------------- color mode

    private fun colorRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(12), dp(16), dp(12))
        }
        row.addView(icon(R.drawable.ic_palette).apply { imageTintList = ColorStateList.valueOf(ink) })
        row.addView(TextView(context).apply {
            setText(R.string.vm_color_mode)
            textSize = 18f
            setTextColor(ink)
            setPadding(dp(20), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(colorCircle(ColorMode.DAY, Color.WHITE) {
            ImageView(context).apply {
                setImageResource(R.drawable.ic_sun)
                imageTintList = ColorStateList.valueOf(ink)
            }
        })
        row.addView(colorCircle(ColorMode.NIGHT, Color.BLACK) {
            ImageView(context).apply {
                setImageResource(R.drawable.ic_moon)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
        })
        row.addView(colorCircle(ColorMode.SEPIA, Color.parseColor("#EAD9C0")) {
            letterA(Color.parseColor("#3F2E1E"))
        })
        row.addView(colorCircle(ColorMode.CUSTOM, settings.customBg) {
            letterA(settings.customFg).also { customSwatch = it }
        })
        // ⋮ : edit the custom colors
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_more_vert)
            imageTintList = ColorStateList.valueOf(muted)
            val p = dp(8)
            setPadding(p, p, p, p)
            isClickable = true
            background = selectableBackground()
            contentDescription = context.getString(R.string.vm_custom_colors)
            setOnClickListener { editCustomColors() }
        }, LinearLayout.LayoutParams(dp(40), dp(48)))
        return row
    }

    private fun letterA(color: Int) = TextView(context).apply {
        text = "A"
        textSize = 20f
        typeface = Typeface.SERIF
        gravity = Gravity.CENTER
        setTextColor(color)
    }

    private fun colorCircle(mode: ColorMode, fill: Int, inner: () -> View): FrameLayout {
        val ring = FrameLayout(context).apply {
            val p = dp(3)
            setPadding(p, p, p, p)
            isClickable = true
            setOnClickListener {
                settings.colorMode = mode
                updateColors()
                onChange()
            }
        }
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(1), Color.parseColor("#33000000"))
            }
        }
        circle.addView(inner(), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        ring.addView(circle, FrameLayout.LayoutParams(dp(44), dp(44)))
        ring.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(6) }
        colorRings.add(mode to ring)
        return ring
    }

    private fun updateColors() {
        for ((mode, ring) in colorRings) {
            ring.background = if (mode == settings.colorMode) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(3), accent)
                }
            } else {
                null
            }
        }
        // Keep the custom swatch in sync with the chosen colors.
        colorRings.firstOrNull { it.first == ColorMode.CUSTOM }?.second?.getChildAt(0)?.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(settings.customBg)
                setStroke(dp(1), Color.parseColor("#33000000"))
            }
        if (::customSwatch.isInitialized) customSwatch.setTextColor(settings.customFg)
    }

    private fun editCustomColors() {
        OptionSheet.show(context, context.getString(R.string.vm_custom_colors), listOf(
            OptionSheet.Option(R.drawable.ic_palette, context.getString(R.string.vm_custom_bg), context.getString(R.string.opt_bg_s)),
            OptionSheet.Option(R.drawable.ic_palette, context.getString(R.string.vm_custom_fg), context.getString(R.string.opt_fg_s))
        )) { which ->
                val initial = if (which == 0) settings.customBg else settings.customFg
                ColorPicker.show(context, initial) { picked ->
                    if (which == 0) settings.customBg = picked else settings.customFg = picked
                    settings.colorMode = ColorMode.CUSTOM
                    updateColors()
                    onChange()
                }
        }
    }

    private fun selectableBackground() = context.obtainStyledAttributes(
        intArrayOf(android.R.attr.selectableItemBackground)
    ).let { a ->
        val drawable = a.getDrawable(0)
        a.recycle()
        drawable
    }
}
