package com.titipong.pdfwriter.model

enum class DrawTool { PEN, HIGHLIGHTER, ERASER, TEXT, IMAGE }

/** x and y are normalized to the page WIDTH (0..1 across, 0..aspect down). */
class StrokePoint(val x: Float, val y: Float, val pressure: Float)

/** width is normalized to page width. Identity equality on purpose (used by undo). */
class InkStroke(
    val points: MutableList<StrokePoint>,
    val color: Int,
    val width: Float,
    val highlighter: Boolean
)

/** x,y = baseline-left, size = text size; all normalized to page width. */
class TextNote(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val size: Float
)

/**
 * A picture placed on the page. [file] is the image in the app's storage; x,y = top-left and
 * w,h = size, all normalized to the page width. Identity equality (undo).
 */
class ImageNote(
    val file: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)

/**
 * A text highlight (PDF /Highlight markup). [rects] are line boxes, normalized like everything
 * else (x 0..1 across the page width, y in width units, as displayed). Identity equality (undo).
 */
class TextHighlight(
    val rects: List<android.graphics.RectF>,
    val color: Int,
    val text: String,
    /** true = underline (PDF /Underline), false = highlight (PDF /Highlight). */
    val underline: Boolean = false
) {
    fun withColor(c: Int) = TextHighlight(rects, c, text, underline)
}

class PageAnnotations {
    val strokes: MutableList<InkStroke> = mutableListOf()
    val texts: MutableList<TextNote> = mutableListOf()
    val highlights: MutableList<TextHighlight> = mutableListOf()
    val images: MutableList<ImageNote> = mutableListOf()
    fun isEmpty() = strokes.isEmpty() && texts.isEmpty() && highlights.isEmpty() && images.isEmpty()

    /** Shallow copy (the items themselves are never mutated once added). */
    fun copy(): PageAnnotations = PageAnnotations().also {
        it.strokes.addAll(strokes)
        it.texts.addAll(texts)
        it.highlights.addAll(highlights)
        it.images.addAll(images)
    }
}

/** A bookmark this app manages (stored in the PDF outline, marked /PWBookmark). */
class Bookmark(var title: String, var page: Int)

/** An entry of the file's own table of contents (read-only here). */
class OutlineEntry(val title: String, val page: Int, val depth: Int)
