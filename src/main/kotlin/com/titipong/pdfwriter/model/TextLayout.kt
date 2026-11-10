package com.titipong.pdfwriter.model

import java.text.BreakIterator
import java.util.Locale

/**
 * Line layout for text boxes, shared by the screen and the PDF writer (each with its own text
 * measuring). Explicit new lines are kept; with a box width, lines wrap at word boundaries
 * (dictionary-based for Thai), and a word too long for the box is split between characters.
 */
object TextLayout {
    const val LINE_HEIGHT = 1.3f

    class Line(val text: String, val width: Float)

    /** [maxWidth] <= 0: no wrapping. Widths are in the same unit as [measure]. */
    fun lines(text: String, maxWidth: Float, measure: (String) -> Float): List<Line> {
        val out = ArrayList<Line>()
        for (para in text.split('\n')) {
            if (maxWidth <= 0f || measure(para) <= maxWidth) {
                out.add(Line(para, measure(para)))
                continue
            }
            val bi = BreakIterator.getLineInstance(Locale("th"))
            bi.setText(para)
            var cur = StringBuilder()
            var start = bi.first()
            var end = bi.next()
            while (end != BreakIterator.DONE) {
                val piece = para.substring(start, end)
                val trial = cur.toString() + piece
                if (cur.isNotEmpty() && measure(trial.trimEnd()) > maxWidth) {
                    out.add(Line(cur.toString().trimEnd(), measure(cur.toString().trimEnd())))
                    cur = StringBuilder()
                }
                if (cur.isEmpty() && measure(piece.trimEnd()) > maxWidth) {
                    // A single word wider than the box: break it between characters.
                    val chars = BreakIterator.getCharacterInstance()
                    chars.setText(piece)
                    var cs = chars.first()
                    var ce = chars.next()
                    while (ce != BreakIterator.DONE) {
                        val c = piece.substring(cs, ce)
                        if (cur.isNotEmpty() && measure(cur.toString() + c) > maxWidth) {
                            out.add(Line(cur.toString(), measure(cur.toString())))
                            cur = StringBuilder()
                        }
                        cur.append(c)
                        cs = ce
                        ce = chars.next()
                    }
                } else {
                    cur.append(piece)
                }
                start = end
                end = bi.next()
            }
            val last = cur.toString().trimEnd()
            out.add(Line(last, measure(last)))
        }
        return out
    }

    /** Left offset of a line of [lineWidth] in a box of [boxWidth] for [align]. */
    fun offset(align: Int, boxWidth: Float, lineWidth: Float): Float = when (align) {
        1 -> (boxWidth - lineWidth) / 2f
        2 -> boxWidth - lineWidth
        else -> 0f
    }
}
