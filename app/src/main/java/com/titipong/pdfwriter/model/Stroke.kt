package com.titipong.pdfwriter.model

import android.graphics.Paint
import android.graphics.Path
import kotlinx.serialization.Serializable

enum class DrawTool { PEN, HIGHLIGHTER, ERASER }

data class Stroke(
    val path: Path,
    val paint: Paint,
    val tool: DrawTool,
    val pageIndex: Int
)

@Serializable
data class StrokePoint(val x: Float, val y: Float, val pressure: Float = 0.5f)

@Serializable
data class SerializableStroke(
    val points: List<StrokePoint>,
    val color: Int,
    val strokeWidth: Float,
    val tool: String,
    val pageIndex: Int
)

@Serializable
data class TextAnnotation(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val textSize: Float,
    val pageIndex: Int
)

@Serializable
data class PageAnnotations(
    val strokes: MutableList<SerializableStroke> = mutableListOf(),
    val texts: MutableList<TextAnnotation> = mutableListOf()
)
