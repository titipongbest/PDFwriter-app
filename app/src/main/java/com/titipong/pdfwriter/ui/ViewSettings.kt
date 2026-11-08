package com.titipong.pdfwriter.ui

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

enum class ColorMode { DAY, NIGHT, SEPIA, CUSTOM }

/** Everything in the View Mode dialog. Display only: nothing here changes the PDF file. */
class ViewSettings(
    var reading: Boolean,
    var layout: PageLayout,
    var vertical: Boolean,
    var colorMode: ColorMode,
    var customBg: Int,
    var customFg: Int,
    var rotation: Int,
    var crop: Boolean,
    var readingTextSize: Float
) {

    fun look(): PdfPageView.Look = when (colorMode) {
        ColorMode.DAY -> PdfPageView.Look.DEFAULT
        // Inverts brightness but keeps hues, so red ink stays red-ish and black ink turns white.
        ColorMode.NIGHT -> NIGHT_FILTER.let { PdfPageView.Look(Color.BLACK, it, it) }
        ColorMode.SEPIA -> PdfPageView.Look(Color.rgb(245, 232, 201), SEPIA_FILTER, null)
        ColorMode.CUSTOM -> PdfPageView.Look(customBg, mapFilter(customBg, customFg), null)
    }

    /** Background behind the pages. */
    fun listBackground(): Int = when (colorMode) {
        ColorMode.DAY -> Color.parseColor("#E5E7EB")
        ColorMode.NIGHT -> Color.parseColor("#262626")
        ColorMode.SEPIA -> Color.parseColor("#D9CFB8")
        ColorMode.CUSTOM -> blend(customBg, customFg, 0.12f)
    }

    fun readingBackground(): Int = when (colorMode) {
        ColorMode.DAY -> Color.WHITE
        ColorMode.NIGHT -> Color.parseColor("#121212")
        ColorMode.SEPIA -> Color.parseColor("#F4ECD8")
        ColorMode.CUSTOM -> customBg
    }

    fun readingText(): Int = when (colorMode) {
        ColorMode.DAY -> Color.parseColor("#111827")
        ColorMode.NIGHT -> Color.parseColor("#E5E7EB")
        ColorMode.SEPIA -> Color.parseColor("#5B4636")
        ColorMode.CUSTOM -> customFg
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(K_READING, reading)
            .putString(K_LAYOUT, layout.name)
            .putBoolean(K_VERTICAL, vertical)
            .putString(K_COLOR, colorMode.name)
            .putInt(K_BG, customBg)
            .putInt(K_FG, customFg)
            .putInt(K_ROTATION, rotation)
            .putBoolean(K_CROP, crop)
            .putFloat(K_TEXT_SIZE, readingTextSize)
            .apply()
    }

    companion object {
        private const val K_READING = "view_reading"
        private const val K_LAYOUT = "view_layout"
        private const val K_VERTICAL = "view_vertical"
        private const val K_COLOR = "view_color"
        private const val K_BG = "view_custom_bg"
        private const val K_FG = "view_custom_fg"
        private const val K_ROTATION = "view_rotation"
        private const val K_CROP = "view_crop"
        private const val K_TEXT_SIZE = "view_reading_text_size"

        fun load(prefs: SharedPreferences) = ViewSettings(
            reading = prefs.getBoolean(K_READING, false),
            layout = enumOr(prefs.getString(K_LAYOUT, null), PageLayout.SINGLE),
            vertical = prefs.getBoolean(K_VERTICAL, true),
            colorMode = enumOr(prefs.getString(K_COLOR, null), ColorMode.DAY),
            customBg = prefs.getInt(K_BG, Color.parseColor("#1E293B")),
            customFg = prefs.getInt(K_FG, Color.parseColor("#E2E8F0")),
            rotation = prefs.getInt(K_ROTATION, 0),
            crop = prefs.getBoolean(K_CROP, false),
            readingTextSize = prefs.getFloat(K_TEXT_SIZE, 18f)
        )

        private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            try {
                if (name == null) default else enumValueOf<T>(name)
            } catch (e: IllegalArgumentException) {
                default
            }

        private val NIGHT_FILTER: ColorMatrixColorFilter by lazy {
            val invert = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            // Luminance-preserving hue rotation by 180 degrees, so inverting doesn't swap hues.
            val hue180 = ColorMatrix(
                floatArrayOf(
                    -0.574f, 1.430f, 0.144f, 0f, 0f,
                    0.426f, 0.430f, 0.144f, 0f, 0f,
                    0.426f, 1.430f, -0.856f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            invert.postConcat(hue180)
            ColorMatrixColorFilter(invert)
        }

        private val SEPIA_FILTER: ColorMatrixColorFilter by lazy {
            ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.96f, 0.91f, 0.79f, 1f) })
        }

        /** Maps white paper to [bg] and black text to [fg], shades in between. */
        private fun mapFilter(bg: Int, fg: Int): ColorMatrixColorFilter {
            fun row(f: Int, b: Int): FloatArray {
                val k = (f - b) / 255f
                return floatArrayOf(-k * 0.299f, -k * 0.587f, -k * 0.114f, 0f, f.toFloat())
            }
            val r = row(Color.red(fg), Color.red(bg))
            val g = row(Color.green(fg), Color.green(bg))
            val b = row(Color.blue(fg), Color.blue(bg))
            return ColorMatrixColorFilter(
                ColorMatrix(r + g + b + floatArrayOf(0f, 0f, 0f, 1f, 0f))
            )
        }

        private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        )
    }
}
