@file:OptIn(ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private val powerColor = Color(0xFFFFA726)

/** A label on the left, a value on the right. */
@Composable
private fun Line(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f, fill = true).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.widthIn(max = 230.dp), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = color, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private fun hours(h: Double): String {
    val m = (h * 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

@Composable
fun MemoryDetails(d: JSONObject) {
    val i = d.optJSONObject("mem_info") ?: return
    Line("Applications", fmtBytes(i.optDouble("anon")))
    Line("Shared memory", fmtBytes(i.optDouble("shmem")))
    Line("Mapped files", fmtBytes(i.optDouble("mapped")))
    Line("Kernel (slab)", fmtBytes(i.optDouble("slab")))
    Line("Waiting to be written", "${fmtBytes(i.optDouble("dirty"))} dirty · ${fmtBytes(i.optDouble("writeback"))} writing")
    Line("Promised to programs", fmtBytes(i.optDouble("committed")))
    i.optJSONArray("zram")?.objs().orEmpty().forEach { z ->
        val orig = z.optDouble("orig")
        val used = z.optDouble("used")
        Line("${z.str("name")} (compressed swap)", "${fmtBytes(orig)} → ${fmtBytes(used)}  (${"%.1f".format(orig / used.coerceAtLeast(1.0))}×)")
    }
}

@Composable
fun CpuDetailsSection(d: JSONObject) {
    val c = d.optJSONObject("cpu_info") ?: return
    Section("CPU details") {
        if (c.str("governor").isNotBlank()) Line("Governor", c.str("governor"))
        if (c.str("epp").isNotBlank()) Line("Energy preference", c.str("epp"))
        if (c.str("driver").isNotBlank()) Line("Frequency driver", c.str("driver"))
        if (!c.isNull("boost")) Line("Turbo boost", if (c.optBoolean("boost")) "enabled" else "disabled")
        Line("Thermal throttle events", "%,d since boot".format(c.optLong("throttle_events")), if (c.optLong("throttle_events") > 0) Color(0xFFFFA726) else Color.Unspecified)
        c.optJSONObject("rates")?.let { r ->
            Line("Context switches", "%,.0f /s".format(r.optDouble("ctx")))
            Line("Interrupts", "%,.0f /s".format(r.optDouble("irq")))
            Line("New processes", "%.1f /s".format(r.optDouble("forks")))
        }
        Line("Tasks", "${c.optInt("running")} running · ${c.optInt("blocked")} blocked on I/O")
    }
}

@Composable
fun PowerSection(d: JSONObject, hist: HistView, gpus: List<JSONObject>) {
    val p = d.optJSONObject("power")
    val rapl = p?.optJSONArray("rapl")?.objs().orEmpty()
    val bat = d.optJSONObject("battery_info")
    val batW = bat?.takeIf { !it.isNull("watts") }?.optDouble("watts")
    val gpuW = gpus.filter { !it.isNull("power_w") }
    val denied = p?.optBoolean("rapl_denied") == true
    if (rapl.isEmpty() && batW == null && gpuW.isEmpty() && !denied) return
    Section("Power", trailing = { rapl.firstOrNull()?.let { Text("${"%.1f".format(it.optDouble("w"))} W", style = MaterialTheme.typography.labelLarge) } }) {
        val series = hist.series("power.pkg")
        if (series.size > 1) Chart(listOf(series), listOf(powerColor), { "%.0f W".format(it) }, height = 56.dp)
        rapl.forEach { Line(it.str("name"), "${"%.1f".format(it.optDouble("w"))} W") }
        if (batW != null) Line("Battery ${bat.str("status").lowercase()}", "${"%.1f".format(batW)} W")
        gpuW.forEach { Line(it.str("name").replace("NVIDIA GeForce ", "").replace("Laptop GPU", "").trim().ifBlank { "GPU" }, "${"%.1f".format(it.optDouble("power_w"))} W") }
        if (denied) {
            val scope = rememberCoroutineScope()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Text("CPU power needs permission to read the energy counters (root-only since the PLATYPUS side-channel fix). The app can allow it until the next reboot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton({
                scope.launch {
                    val (_, j) = Hc.cur?.privPost("/api/priv/rapl", org.json.JSONObject(), 30_000) ?: (0 to null)
                    android.widget.Toast.makeText(ctx, if (j?.optBoolean("ok") == true) "Allowed — power appears in a moment" else "Couldn't allow it", android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("Allow reading CPU power") }
            SelectionContainer { Text("or on the machine: doas chmod a+r /sys/class/powercap/intel-rapl:*/energy_uj", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
fun BatterySection(d: JSONObject) {
    val b = d.optJSONObject("battery_info") ?: return
    val pct = b.optDouble("percent")
    Section("Battery  ${"%.0f".format(pct)}%", trailing = { Text(b.str("status"), style = MaterialTheme.typography.labelLarge) }) {
        Bar((pct / 100).toFloat(), heat((100 - pct).toFloat()))
        if (!b.isNull("health")) Line("Health", "${"%.0f".format(b.optDouble("health"))}% of original capacity", if (b.optDouble("health") < 70) Color(0xFFFFA726) else Color.Unspecified)
        if (!b.isNull("wh_full") && !b.isNull("wh_design")) Line("Capacity", "${b.optDouble("wh_full")} Wh now · ${b.optDouble("wh_design")} Wh new")
        if (!b.isNull("wh_now")) Line("Energy left", "${b.optDouble("wh_now")} Wh")
        if (!b.isNull("cycles") && b.optDouble("cycles") > 0) Line("Charge cycles", "%.0f".format(b.optDouble("cycles")))
        if (!b.isNull("volts")) Line("Voltage", "${b.optDouble("volts")} V")
        if (!b.isNull("watts")) Line(if (b.str("status") == "Charging") "Charging at" else "Drawing", "${b.optDouble("watts")} W")
        if (!b.isNull("hours")) Line(if (b.str("status") == "Charging") "Time to full" else "Time left", hours(b.optDouble("hours")))
        val who = listOf(b.str("maker"), b.str("model"), b.str("tech")).filter { it.isNotBlank() }.joinToString(" · ")
        if (who.isNotBlank()) Text(who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PressureSection(d: JSONObject) {
    val p = d.optJSONObject("pressure") ?: return
    if (p.length() == 0) return
    Section("Pressure") {
        Text("How much of the last 10 seconds tasks sat waiting for the resource (0% = never).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @Composable fun row(label: String, key: String, kind: String) {
            val o = p.optJSONObject(key) ?: return
            if (!o.has("${kind}_avg10")) return
            val v10 = o.optDouble("${kind}_avg10")
            val v60 = o.optDouble("${kind}_avg60")
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Line(label, "${"%.0f".format(v10)}%  ·  60 s ${"%.0f".format(v60)}%")
                Bar((v10 / 100).toFloat(), heat(v10.toFloat()), height = 5.dp)
            }
        }
        row("CPU — waiting to run", "cpu", "some")
        row("Memory — waiting for memory", "memory", "some")
        row("Memory — fully stalled", "memory", "full")
        row("Disk — waiting for I/O", "io", "some")
        row("Disk — fully stalled", "io", "full")
    }
}

@Composable
fun SensorsSection(d: JSONObject) {
    val chips = d.optJSONArray("sensors")?.objs().orEmpty()
    if (chips.isEmpty()) return
    val count = chips.sumOf { it.optJSONArray("items")?.length() ?: 0 }
    var open by remember { mutableStateOf(Hc.memBool("monitor.sensors")) }
    Section("All sensors  ($count)", trailing = {
        TextButton({ open = !open; Hc.memPutBool("monitor.sensors", open) }) { Text(if (open) "Hide" else "Show") }
    }) {
        if (!open) {
            val hottest = chips.flatMap { it.optJSONArray("items")?.objs().orEmpty() }.filter { it.str("kind") == "temp" }.maxByOrNull { it.optDouble("value") }
            Text("Every temperature, fan, voltage, current and power reading the machine exposes." + (hottest?.let { " Hottest now: ${it.str("label")} ${"%.0f".format(it.optDouble("value"))}°C." } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        chips.forEach { c ->
            Text(c.str("chip").replace("_", " "), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            c.optJSONArray("items")?.objs().orEmpty().forEach { s ->
                val v = s.optDouble("value")
                val (text, color) = when (s.str("kind")) {
                    "temp" -> "${"%.1f".format(v)} °C" to heat(((v - 30) * 100 / 70).toFloat())
                    "fan" -> "${"%.0f".format(v)} rpm" to Color.Unspecified
                    "volt" -> "${"%.3f".format(v)} V" to Color.Unspecified
                    "curr" -> "${"%.3f".format(v)} A" to Color.Unspecified
                    else -> "${"%.2f".format(v)} W" to Color.Unspecified
                }
                val limit = listOfNotNull(s.takeIf { !it.isNull("max") }?.let { "max ${"%.0f".format(it.optDouble("max"))}" }, s.takeIf { !it.isNull("crit") }?.let { "crit ${"%.0f".format(it.optDouble("crit"))}" }).joinToString(" · ")
                Line(s.str("label") + if (limit.isNotBlank()) "  ($limit)" else "", text, color)
            }
        }
    }
}

@Composable
fun WifiLine(d: JSONObject) {
    val w = d.optJSONObject("wifi_info") ?: return
    val dbm = w.optDouble("dbm")
    val quality = when { dbm >= -50 -> "excellent"; dbm >= -60 -> "good"; dbm >= -70 -> "fair"; else -> "weak" }
    Line("Wi-Fi ${w.str("iface")} signal", "${"%.0f".format(dbm)} dBm · $quality · link ${"%.0f".format(w.optDouble("link"))}/70")
}

@Composable
fun SystemDetailsSection(d: JSONObject) {
    val m = d.optJSONObject("misc") ?: return
    Section("System details") {
        if (!m.isNull("files_open")) Line("Open files", "%,d of %,d".format(m.optLong("files_open"), m.optLong("files_max")))
        if (!m.isNull("entropy")) Line("Random-number entropy", "%.0f bits".format(m.optDouble("entropy")))
        val taint = m.optLong("tainted")
        Line("Kernel taint flags", if (taint == 0L) "none (clean)" else "$taint (out-of-tree modules)")
    }
}
