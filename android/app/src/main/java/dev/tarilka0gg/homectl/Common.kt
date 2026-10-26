package dev.tarilka0gg.homectl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

fun body(vararg p: Pair<String, Any>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }

fun JSONArray.floats(): List<Float> = (0 until length()).map { getDouble(it).toFloat() }
fun JSONArray.objs(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

fun fmtBytes(b: Double): String {
    val u = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var v = b
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (i == 0) "${v.toInt()} B" else "%.1f %s".format(v, u[i])
}

fun fmtRate(b: Double) = fmtBytes(b) + "/s"

fun fmtUptime(s: Long): String {
    val d = s / 86400
    val h = s % 86400 / 3600
    val m = s % 3600 / 60
    return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** green → yellow → red like btop's meters. */
fun heat(percent: Float): Color = Color.hsv(120f * (1f - (percent / 100f).coerceIn(0f, 1f)), 0.65f, 0.85f)

@Composable
fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, max: Float = 100f, min: Float = 0f, enabled: Boolean = true, onDone: (Int) -> Unit) {
    var v by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) v = value }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(v.toInt().toString(), style = MaterialTheme.typography.labelLarge)
        }
        Slider(v, { dragging = true; v = it }, enabled = enabled, valueRange = min..max, onValueChangeFinished = { dragging = false; onDone(v.toInt()) })
    }
}

/**
 * A block whose height only ever grows while it is on screen. Things above the part you are looking at that come and go between
 * refreshes (a list of 0-8 rows, a "nothing to show" line) would otherwise push everything below them up and down every second.
 */
@Composable
fun Modifier.keepMaxHeight(): Modifier {
    var max by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    return this.heightIn(min = with(density) { max.toDp() }).onSizeChanged { if (it.height > max) max = it.height }
}

/**
 * A switch that follows the real state: it flips immediately and is corrected by the refresh. A flip the machine did not confirm
 * (refused, a password that was cancelled, a service that failed to start) goes back after a few seconds; it used to stay wrong
 * until the real state happened to change.
 */
@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    var local by remember { mutableStateOf(checked) }
    var touched by remember { mutableIntStateOf(0) }
    val real by rememberUpdatedState(checked)
    LaunchedEffect(checked) { local = checked }
    LaunchedEffect(touched) { if (touched > 0) { delay(4000); local = real } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(local, { local = it; touched++; onChange(it) }, enabled = enabled)
    }
}

/** The last answer of each screen's data per machine, so coming back to a tab shows it at once while fresh data loads. */
private object LastSeen {
    val map = HashMap<String, Any>()
}

/**
 * Loads on entry and whenever [key] or the selected machine changes; polls every [everyMs] if > 0.
 * With a [cacheId] the previous result for this machine is shown immediately instead of an empty screen.
 */
@Composable
fun <T> loaded(key: Any?, everyMs: Long = 0, cacheId: String? = null, fetch: suspend () -> T?): State<T?> {
    val mid = Hc.cur?.id
    val cacheKey = if (cacheId != null && mid != null) "$mid|$cacheId" else null
    @Suppress("UNCHECKED_CAST")
    val s = remember(mid) { mutableStateOf<T?>(cacheKey?.let { LastSeen.map[it] as? T }) }
    LaunchedEffect(key, mid) {
        do {
            fetch()?.let { s.value = it; if (cacheKey != null) LastSeen.map[cacheKey] = it }
            if (everyMs > 0) delay(everyMs)
        } while (everyMs > 0)
    }
    return s
}

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(height))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color, RoundedCornerShape(height)))
    }
}

/** Line chart of the last samples; several series share one scale. */
@Composable
fun Spark(series: List<List<Float>>, colors: List<Color>, modifier: Modifier = Modifier, max: Float? = null, min: Float = 0f, height: Dp = 56.dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(height).background(track.copy(alpha = 0.35f), RoundedCornerShape(8.dp))) {
        val top = max ?: (series.flatten().maxOrNull() ?: 1f).coerceAtLeast(1f)
        series.forEachIndexed { si, vals ->
            if (vals.size < 2) return@forEachIndexed
            val path = Path()
            vals.forEachIndexed { i, v ->
                val x = size.width * i / (vals.size - 1)
                val y = size.height - size.height * ((v - min) / (top - min).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (si == 0) {
                val fill = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(fill, colors[si].copy(alpha = 0.18f))
            }
            drawPath(path, colors[si], style = Stroke(width = 2.5f))
        }
    }
}

@Composable
fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** A [Spark] with its scale printed in the corner, so peaks are readable. */
@Composable
fun Chart(series: List<List<Float>>, colors: List<Color>, fmt: (Float) -> String, max: Float? = null, height: Dp = 64.dp, autoMin: Boolean = false) {
    val top = max ?: (series.flatten().maxOrNull() ?: 0f)
    val lo = if (autoMin) ((series.flatten().minOrNull() ?: 0f) - (top - (series.flatten().minOrNull() ?: 0f)) * 0.15f - 1f).coerceAtLeast(0f) else 0f
    Box {
        Spark(series, colors, max = max ?: top.coerceAtLeast(1f), min = lo, height = height)
        Text(
            "max ${fmt(top)}",
            Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun JSONObject.series(k: String): List<Float> = optJSONArray(k)?.floats().orEmpty()


/** True while any VPN (Tailscale, WireGuard, …) is active on the phone. */
fun vpnActive(ctx: android.content.Context): Boolean {
    val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    return runCatching { cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true } }.getOrDefault(false)
}

/** Tests every address of [m] and says in plain words why the machine can't be reached. */
@Composable
fun ConnectionTest(m: Machine, autoRun: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember(m.id) { mutableStateOf<List<Machine.Probe>?>(null) }
    var busy by remember(m.id) { mutableStateOf(false) }
    var vpn by remember(m.id) { mutableStateOf(vpnActive(ctx)) }
    fun run() { busy = true; vpn = vpnActive(ctx); scope.launch { results = m.diagnose(); busy = false } }
    LaunchedEffect(m.id) { if (autoRun) run() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedButton({ run() }, enabled = !busy) { Text(if (busy) "Testing…" else "Test the connection to ${m.name}") }
        results?.let { rs ->
            Text(if (vpn) "VPN on this phone: active (Tailscale or similar)" else "VPN on this phone: none active", style = MaterialTheme.typography.bodySmall)
            rs.forEach { p ->
                Text(
                    (if (p.ok) "✓ " else "✗ ") + "${p.endpoint.kind}  ${p.endpoint.url.removePrefix("http://").removePrefix("https://")}  —  ${p.detail}" + if (p.ok) " (${p.ms} ms)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            val tail = rs.filter { it.endpoint.kind == "tailscale" }
            val hint = when {
                rs.any { it.ok } -> null
                tail.isNotEmpty() && !vpn -> "Tailscale isn't connected on this phone, so the machine's tailnet address can't work. Open the Tailscale app and connect it. Away from home it also needs to reach its login server — if it says it can't, that is why."
                tail.isNotEmpty() && tail.all { !it.ok } -> "Tailscale is on, but the machine doesn't answer through it: the machine may be off, homectl stopped, or the tailnet can't find a path (a self-hosted Headscale that is only reachable on the home network can't hand out new routes while you're away)."
                else -> "None of the machine's addresses answer from here."
            }
            var announced by remember(m.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(rs) {
                if (m.rv != null && rs.none { it.ok }) announced = m.fetchAnnouncement()?.let { "last tunnel address announced by ${m.name}: ${it.url.removePrefix("https://")} (${agoShort(it.ts * 1000)})" } ?: "no signed tunnel announcement found for ${m.name} (is its tunnel switched on?)"
            }
            announced?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
