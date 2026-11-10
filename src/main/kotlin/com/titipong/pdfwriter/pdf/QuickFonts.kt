package com.titipong.pdfwriter.pdf

import org.apache.fontbox.FontBoxFont
import org.apache.fontbox.ttf.TTFParser
import org.apache.fontbox.ttf.TrueTypeFont
import org.apache.pdfbox.pdmodel.font.CIDFontMapping
import org.apache.pdfbox.pdmodel.font.FontMapper
import org.apache.pdfbox.pdmodel.font.FontMapping
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * PDFBox looks for fonts that aren't in the PDF by scanning every installed font once (about a
 * minute on Windows). Until that scan is done (in the background), this maps fonts straight to
 * a few known Windows font files, so opening and saving never waits.
 */
object QuickFonts : FontMapper {
    private val dir = File(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts")
    private val loaded = ConcurrentHashMap<String, TrueTypeFont>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private fun load(name: String): TrueTypeFont? {
        if (name in missing) return null
        loaded[name]?.let { return it }
        val f = File(dir, name)
        val font = try { if (f.exists()) TTFParser().parse(f) else null } catch (e: Throwable) { null }
        if (font == null) { missing.add(name); return null }
        return loaded.putIfAbsent(name, font) ?: font
    }

    private fun pick(baseFont: String?, fd: PDFontDescriptor?): TrueTypeFont? {
        val n = (baseFont ?: "").lowercase().replace(" ", "").replace("-", "").replace(",", "")
        // A font of the same name (e.g. "Tahoma", "Arial").
        val plain = n.substringAfter('+')
        if (plain.isNotEmpty() && plain.all { it.isLetterOrDigit() }) {
            load("$plain.ttf")?.let { return it }
        }
        val bold = "bold" in n || "black" in n || "heavy" in n || (fd?.isForceBold == true) || (fd?.fontWeight ?: 0f) >= 600f
        val italic = "italic" in n || "oblique" in n || (fd?.isItalic == true)
        val family = when {
            "courier" in n || "mono" in n || (fd?.isFixedPitch == true) -> "cour"
            "times" in n || "serif" in n && "sans" !in n || (fd?.isSerif == true && "sans" !in n) -> "times"
            "symbol" in n -> "symbol"
            "zapf" in n || "dingbat" in n -> "wingding"
            "thai" in n || "angsana" in n || "cordia" in n || "browallia" in n || "sarabun" in n -> "tahoma"
            else -> "arial"
        }
        val suffix = when {
            family == "symbol" || family == "wingding" -> ""
            family == "tahoma" -> if (bold) "bd" else ""
            bold && italic -> "bi"
            bold -> "bd"
            italic -> "i"
            family == "times" -> ""
            else -> ""
        }
        val file = when {
            family == "times" && suffix.isEmpty() -> "times.ttf"
            family == "times" -> "times$suffix.ttf"
            else -> "$family$suffix.ttf"
        }
        return load(file) ?: load("$family.ttf") ?: load("arial.ttf") ?: load("tahoma.ttf")
    }

    override fun getTrueTypeFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<TrueTypeFont>? =
        pick(baseFont, fontDescriptor)?.let { FontMapping(it, true) }

    override fun getFontBoxFont(baseFont: String?, fontDescriptor: PDFontDescriptor?): FontMapping<FontBoxFont>? =
        pick(baseFont, fontDescriptor)?.let { FontMapping<FontBoxFont>(it, true) }

    override fun getCIDFont(baseFont: String?, fontDescriptor: PDFontDescriptor?, cidSystemInfo: PDCIDSystemInfo?): CIDFontMapping? {
        val f = load("tahoma.ttf") ?: pick(baseFont, fontDescriptor) ?: return null
        return CIDFontMapping(null, f, true)
    }

    /** Use the quick mapping now; switch to PDFBox's full one once its font scan is done. */
    fun install() {
        val full = FontMappers.instance()
        FontMappers.set(this)
        Thread({
            try {
                full.getTrueTypeFont("Arial", null) // builds (or reads) PDFBox's font cache
                FontMappers.set(full)
            } catch (e: Throwable) { }
        }, "font-scan").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
}
