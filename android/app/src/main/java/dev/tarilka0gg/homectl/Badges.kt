package dev.tarilka0gg.homectl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val scriptExt = setOf("py", "rs", "js", "ts", "c", "h", "cpp", "hpp", "kt", "java", "go", "rb", "php", "lua", "zig", "sh", "bash", "fish", "sql")

/** A folder, or a rounded file badge coloured by type and labelled with its extension, drawn instead of emoji. */
@Composable
fun FileBadge(name: String, kind: Kind, dir: Boolean, size: Dp = 40.dp) {
    if (dir) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            drawRoundRect(Color(0xFFD99A00), Offset(w * 0.06f, h * 0.16f), Size(w * 0.42f, h * 0.24f), CornerRadius(w * 0.07f))
            drawRoundRect(Color(0xFFFFB300), Offset(w * 0.06f, h * 0.28f), Size(w * 0.88f, h * 0.58f), CornerRadius(w * 0.08f))
            drawRoundRect(Color(0xFFFFCA28), Offset(w * 0.06f, h * 0.38f), Size(w * 0.88f, h * 0.48f), CornerRadius(w * 0.08f))
        }
        return
    }
    val ext = name.substringAfterLast('.', "").lowercase()
    val color = when {
        kind == Kind.Image -> Color(0xFF43A047)
        kind == Kind.Video -> Color(0xFFE53935)
        kind == Kind.Audio -> Color(0xFF8E24AA)
        kind == Kind.Pdf -> Color(0xFFD84315)
        kind == Kind.Archive -> Color(0xFF8D6E63)
        ext in scriptExt -> Color(0xFF00897B)
        kind == Kind.Text -> Color(0xFF1E88E5)
        else -> Color(0xFF78909C)
    }
    val label = when {
        ext.isNotEmpty() -> ext.take(4).uppercase()
        kind == Kind.Text -> "TXT"
        else -> "FILE"
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(size / 6)
        Box(Modifier.size(size * 0.82f, size).clip(shape).background(color.copy(alpha = 0.16f)).border(1.5.dp, color, shape), contentAlignment = Alignment.Center) {
            Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = if (label.length > 3) 9.sp else 11.sp, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    }
}
