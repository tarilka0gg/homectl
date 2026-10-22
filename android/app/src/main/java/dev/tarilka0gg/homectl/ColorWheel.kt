package dev.tarilka0gg.homectl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A round hue/saturation picker: the angle is the colour, the distance from the centre is how saturated it is (white in the
 * middle). Brightness is a separate slider. [onPick] is called with an RGB colour when the finger is lifted (and on a tap).
 */
@Composable
fun ColorWheel(initial: Triple<Int, Int, Int>?, size: Dp = 230.dp, enabled: Boolean = true, onPick: (Triple<Int, Int, Int>) -> Unit) {
    val hsv = remember(initial) {
        val a = FloatArray(3)
        if (initial != null) android.graphics.Color.RGBToHSV(initial.first, initial.second, initial.third, a) else { a[0] = 0f; a[1] = 0f; a[2] = 1f }
        a
    }
    var hue by remember(initial) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(initial) { mutableFloatStateOf(hsv[1]) }
    var dragging by remember { mutableStateOf(false) }
    val sweep = remember { Brush.sweepGradient(listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 1f, 1f) }) }

    fun update(p: Offset, w: Float) {
        val c = Offset(w / 2, w / 2)
        val d = p - c
        hue = ((Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())).toFloat() + 360f) % 360f)
        sat = (hypot(d.x, d.y) / (w / 2f)).coerceIn(0f, 1f)
    }
    fun send() {
        val c = Color.hsv(hue, sat, 1f)
        onPick(Triple((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()))
    }

    Canvas(
        Modifier.size(size).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectDragGestures(
                onDragStart = { dragging = true; update(it, this.size.width.toFloat()) },
                onDragEnd = { dragging = false; send() },
                onDragCancel = { dragging = false },
            ) { change, _ -> update(change.position, this.size.width.toFloat()); change.consume() }
        }.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures { update(it, this.size.width.toFloat()); send() }
        },
    ) {
        val r = min(this.size.width, this.size.height) / 2f
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val alpha = if (enabled) 1f else 0.35f
        drawCircle(sweep, r, c, alpha)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), c, r), r, c, alpha)
        // the chosen colour: a ring at its position, filled with it
        val a = Math.toRadians(hue.toDouble())
        val pos = Offset(c.x + (cos(a) * sat * r).toFloat(), c.y + (sin(a) * sat * r).toFloat())
        drawCircle(Color.hsv(hue, sat, 1f), 15.dp.toPx(), pos)
        drawCircle(Color.White, 15.dp.toPx(), pos, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.35f), 16.5.dp.toPx(), pos, style = Stroke(1.2.dp.toPx()))
    }
}
