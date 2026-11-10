package com.titipong.pdfwriter.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.io.File

/** Paper styles for new PDFs. The same drawing is used for the PDF and for the preview. */
enum class PageTemplate { BLANK, LINED, GRID, DOTTED, CORNELL }

object PdfTemplates {

    /** A4 in points. */
    const val A4_W = 595.28f
    const val A4_H = 841.89f
    private const val MM = 72f / 25.4f

    /** Line color (light blue-gray) and the red margin line of lined paper, as 0..1 RGB. */
    val LINE = floatArrayOf(0.70f, 0.78f, 0.86f)
    val MARGIN = floatArrayOf(0.93f, 0.55f, 0.55f)

    interface Painter {
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: FloatArray, width: Float)
        fun dot(x: Float, y: Float, r: Float, color: FloatArray)
    }

    /** Draws [t] on a page [w] x [h] points, y measured from the TOP. */
    fun draw(t: PageTemplate, w: Float, h: Float, p: Painter) {
        when (t) {
            PageTemplate.BLANK -> {}
            PageTemplate.LINED -> {
                val gap = 8 * MM
                var y = 30 * MM
                while (y < h - 12 * MM) {
                    p.line(12 * MM, y, w - 12 * MM, y, LINE, 0.6f)
                    y += gap
                }
                p.line(28 * MM, 12 * MM, 28 * MM, h - 12 * MM, MARGIN, 0.8f)
            }
            PageTemplate.GRID -> {
                val gap = 5 * MM
                var x = gap
                while (x < w) { p.line(x, 0f, x, h, LINE, 0.4f); x += gap }
                var y = gap
                while (y < h) { p.line(0f, y, w, y, LINE, 0.4f); y += gap }
            }
            PageTemplate.DOTTED -> {
                val gap = 5 * MM
                var y = gap
                while (y < h) {
                    var x = gap
                    while (x < w) { p.dot(x, y, 0.7f, LINE); x += gap }
                    y += gap
                }
            }
            PageTemplate.CORNELL -> {
                // Title band at the top, cue column on the left, summary box at the bottom.
                val top = 30 * MM
                val bottom = h - 45 * MM
                val cue = 60 * MM
                val gap = 8 * MM
                var y = top + gap
                while (y < bottom) {
                    p.line(cue, y, w - 10 * MM, y, LINE, 0.6f)
                    y += gap
                }
                p.line(10 * MM, top, w - 10 * MM, top, MARGIN, 1f)
                p.line(cue, top, cue, bottom, MARGIN, 1f)
                p.line(10 * MM, bottom, w - 10 * MM, bottom, MARGIN, 1f)
            }
        }
    }

    /** Writes a new PDF with [pages] pages of A4 paper in the chosen style. */
    fun create(out: File, template: PageTemplate, landscape: Boolean, pages: Int = 1) {
        val w = if (landscape) A4_H else A4_W
        val h = if (landscape) A4_W else A4_H
        PDDocument().use { doc ->
            repeat(pages.coerceAtLeast(1)) {
                val page = PDPage(PDRectangle(w, h))
                doc.addPage(page)
                if (template != PageTemplate.BLANK) {
                    PDPageContentStream(doc, page).use { cs ->
                        draw(template, w, h, object : Painter {
                            override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: FloatArray, width: Float) {
                                cs.setStrokingColor(color[0], color[1], color[2])
                                cs.setLineWidth(width)
                                cs.moveTo(x1, h - y1)
                                cs.lineTo(x2, h - y2)
                                cs.stroke()
                            }

                            override fun dot(x: Float, y: Float, r: Float, color: FloatArray) {
                                cs.setNonStrokingColor(color[0], color[1], color[2])
                                cs.addRect(x - r, h - y - r, 2 * r, 2 * r)
                                cs.fill()
                            }
                        })
                    }
                }
            }
            doc.documentInformation.creator = "PDFWriter"
            doc.save(out)
        }
    }
}
