package com.titipong.pdfwriter.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.titipong.pdfwriter.R

/** App settings: a list of title + description + switch rows. Add new settings to [rows]. */
object SettingsDialog {

    const val INK_SMOOTHING = "set_ink_smoothing"
    const val HIGHLIGHTER_STRAIGHTEN = "set_hl_smoothing"
    const val FINGER_DRAW = "set_finger_draw"

    private class Row(val key: String, val title: Int, val description: Int, val default: Boolean)

    private val rows = listOf(
        Row(INK_SMOOTHING, R.string.set_ink_smoothing, R.string.set_ink_smoothing_desc, true),
        Row(HIGHLIGHTER_STRAIGHTEN, R.string.set_hl_smoothing, R.string.set_hl_smoothing_desc, true),
        Row(FINGER_DRAW, R.string.set_finger_draw, R.string.set_finger_draw_desc, false)
    )

    fun isOn(prefs: SharedPreferences, key: String): Boolean =
        prefs.getBoolean(key, rows.firstOrNull { it.key == key }?.default ?: false)

    fun show(context: Context, prefs: SharedPreferences) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        for (r in rows) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(14), dp(20), dp(14))
                isClickable = true
            }
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                setText(r.title)
                textSize = 17f
                setTextColor(Color.parseColor("#111827"))
            })
            texts.addView(TextView(context).apply {
                setText(r.description)
                textSize = 14f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = MaterialSwitch(context).apply { isChecked = isOn(prefs, r.key) }
            sw.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(r.key, checked).apply() }
            row.setOnClickListener { sw.toggle() }
            row.addView(sw)
            list.addView(row)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings)
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
