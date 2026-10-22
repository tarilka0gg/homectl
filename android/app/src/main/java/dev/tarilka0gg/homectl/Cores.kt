package dev.tarilka0gg.homectl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * All per-thread CPU graphs in one canvas, two per row: a label line (thread, load, temperature) over a small graph.
 * One drawing surface instead of ~7 layout nodes per thread, so a 24-thread CPU re-renders each poll without re-laying out.
 */
@Composable
fun CoreGrid(history: List<List<Float>>, load: List<Float>, temps: List<Double?>) {
    val density = LocalDensity.current
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val path = remember { Path() }
    val fill = remember { Path() }
    val textSize = with(density) { 11.sp().toPx() }
    val left = remember(textColor, textSize) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = textColor; this.textSize = textSize; typeface = android.graphics.Typeface.DEFAULT } }
    val right = remember(textColor, textSize) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = textColor; this.textSize = textSize; typeface = android.graphics.Typeface.MONOSPACE; textAlign = android.graphics.Paint.Align.RIGHT } }
    val n = load.size
    val rows = (n + 1) / 2
    val cellH = 58.dp
    Canvas(Modifier.fillMaxWidth().height(cellH * rows)) {
        val gap = 8.dp.toPx()
        val cellW = (size.width - gap) / 2
        val rowH = cellH.toPx()
        val labelH = 16.dp.toPx()
        val graphH = 34.dp.toPx()
        val radius = CornerRadius(8.dp.toPx())
        for (i in 0 until n) {
            val x0 = (i % 2) * (cellW + gap)
            val y0 = (i / 2) * rowH
            val p = load[i]
            val c = heat(p)
            val t = temps.getOrNull(i)
            drawIntoCanvas { cv ->
                cv.nativeCanvas.drawText("core $i", x0, y0 + textSize, left)
                cv.nativeCanvas.drawText("${p.toInt()}%" + (t?.let { " · ${it.toInt()}°" } ?: ""), x0 + cellW, y0 + textSize, right)
            }
            val gy = y0 + labelH
            drawRoundRect(track, Offset(x0, gy), Size(cellW, graphH), radius)
            val v = history.getOrNull(i).orEmpty()
            if (v.size < 2) continue
            path.reset()
            v.forEachIndexed { k, s ->
                val x = x0 + cellW * k / (v.size - 1)
                val y = gy + graphH - graphH * (s / 100f).coerceIn(0f, 1f)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            fill.reset()
            fill.addPath(path)
            fill.lineTo(x0 + cellW, gy + graphH)
            fill.lineTo(x0, gy + graphH)
            fill.close()
            drawPath(fill, c.copy(alpha = 0.18f))
            drawPath(path, c, style = Stroke(width = 2.5f))
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
