package com.titipong.pdfwriter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

object Ui {
    val Primary = Color(0xFF2563EB)
    val Bar = Color(0xFFFFFFFF)
    val BarLine = Color(0xFFE5E7EB)
    val Workspace = Color(0xFFE9EBEF)
    val Text = Color(0xFF111827)
    val SubText = Color(0xFF6B7280)
    val Selected = Color(0xFFDBEAFE)
    val Danger = Color(0xFFDC2626)

    val colors = lightColorScheme(
        primary = Primary,
        onPrimary = Color.White,
        secondary = Primary,
        surface = Color.White,
        background = Workspace,
        surfaceVariant = Color(0xFFF3F4F6),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color(0xFFF3F4F6)
    )

    /** UI text: the system font (Segoe UI, with Windows' Thai fonts as fallback). */
    val font: FontFamily = FontFamily.Default

    /** Text boxes on pages: the same font the PDF gets (Noto Sans Thai). */
    val noteFont: FontFamily by lazy {
        try {
            FontFamily(Font(resource = "fonts/NotoSansThai-Regular.ttf"))
        } catch (e: Throwable) {
            FontFamily.Default
        }
    }

    /** The main window (for file dialogs). */
    var window: Frame? = null
}

fun Int.toColor() = Color(this)

fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt()
    val r = (red * 255f + 0.5f).toInt()
    val g = (green * 255f + 0.5f).toInt()
    val b = (blue * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

@Composable
fun icon(name: String): Painter = painterResource("drawable/$name.xml")

/** A square tool button with a vector icon and a tooltip. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToolButton(
    iconName: String,
    tip: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 34.dp,
    tint: Color = Color(0xFF374151),
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tip, fontFamily = Ui.font) } },
        state = rememberTooltipState()
    ) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(8.dp))
                .background(if (selected) Ui.Selected else Color.Transparent)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon(iconName), tip, Modifier.size(size * 0.62f),
                tint = if (!enabled) tint.copy(alpha = 0.35f) else if (selected) Ui.Primary else tint
            )
        }
    }
}

/** A round color swatch (selected: ring). */
@Composable
fun Swatch(color: Color, selected: Boolean, size: Dp = 24.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size + 6.dp).clip(CircleShape)
            .border(if (selected) 2.dp else 0.dp, if (selected) Ui.Primary else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 1f)).border(1.dp, Color(0x22000000), CircleShape))
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Ui.Text, size: Int = 13, bold: Boolean = false) {
    Text(
        text, modifier, color = color, fontSize = size.sp, fontFamily = Ui.font,
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.SemiBold else null, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Ui.Selected else Color(0xFFF3F4F6))
            .border(1.dp, if (selected) Ui.Primary else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Label(text, color = if (selected) Ui.Primary else Ui.Text) }
}

/** Windows file dialogs. */
object Files {
    fun openPdf(): List<File> {
        val d = FileDialog(Ui.window, "Open PDF", FileDialog.LOAD)
        d.isMultipleMode = true
        d.file = "*.pdf"
        d.filenameFilter = FilenameFilter { _, name -> name.lowercase().endsWith(".pdf") }
        d.isVisible = true
        return d.files?.toList() ?: emptyList()
    }

    fun openImage(): File? {
        val d = FileDialog(Ui.window, "Choose a picture", FileDialog.LOAD)
        d.file = "*.png;*.jpg;*.jpeg;*.bmp;*.gif"
        d.filenameFilter = FilenameFilter { _, name -> name.lowercase().let { n -> listOf(".png", ".jpg", ".jpeg", ".bmp", ".gif").any { n.endsWith(it) } } }
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    fun save(title: String, suggested: String, ext: String): File? {
        val d = FileDialog(Ui.window, title, FileDialog.SAVE)
        d.file = suggested
        d.isVisible = true
        val f = d.file ?: return null
        val name = if (f.lowercase().endsWith(".$ext")) f else "$f.$ext"
        return File(d.directory, name)
    }

    fun chooseFolder(title: String): File? {
        val ch = javax.swing.JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        }
        return if (ch.showOpenDialog(Ui.window) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile else null
    }
}
