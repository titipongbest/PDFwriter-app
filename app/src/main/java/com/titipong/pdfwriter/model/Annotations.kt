package com.titipong.pdfwriter.model

enum class DrawTool { PEN, HIGHLIGHTER, ERASER, TEXT }

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

class PageAnnotations {
    val strokes: MutableList<InkStroke> = mutableListOf()
    val texts: MutableList<TextNote> = mutableListOf()
    fun isEmpty() = strokes.isEmpty() && texts.isEmpty()
}

/** A bookmark this app manages (stored in the PDF outline, marked /PWBookmark). */
class Bookmark(var title: String, var page: Int)

/** An entry of the file's own table of contents (read-only here). */
class OutlineEntry(val title: String, val page: Int, val depth: Int)
