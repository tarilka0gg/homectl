@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package dev.tarilka0gg.homectl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun MonitorScreen() {
    val data = loaded(Unit, 1500) { Hc.json("/api/monitor") }
    val scope = rememberCoroutineScope()
    if (Hc.machines.size > 1) Fleet()
    val d = data.value
    if (d == null) {
        Text(if (Hc.cur?.offline == true) "Machine is offline." else "Loading…")
        return
    }
    val info = d.getJSONObject("info")
    val cpu = d.getJSONObject("cpu")
    val mem = d.getJSONObject("mem")
    val hist = d.getJSONObject("history")
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Section(info.str("hostname"), trailing = { Text("up ${fmtUptime(d.optLong("uptime_s"))}", style = MaterialTheme.typography.labelLarge) }) {
        Text("${info.str("os")} · ${info.str("kernel")}", style = MaterialTheme.typography.bodySmall)
        Text(info.str("cpu_model"), style = MaterialTheme.typography.bodySmall)
        val load = d.getJSONArray("load").floats()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (load.size == 3) AssistChip({}, label = { Text("load ${load.joinToString(" ") { "%.2f".format(it) }}") })
            if (!cpu.isNull("temp")) AssistChip({}, label = { Text("CPU ${"%.0f".format(cpu.getDouble("temp"))}°C") })
            d.optJSONObject("temps")?.takeIf { !it.isNull("nvme") }?.let { AssistChip({}, label = { Text("NVMe ${"%.0f".format(it.getDouble("nvme"))}°C") }) }
            d.optJSONObject("battery")?.let { b ->
                AssistChip({}, label = { Text("🔋 ${b.optInt("percent")}% ${b.str("status")}${if (!b.isNull("watts")) " ${"%.1f".format(b.getDouble("watts"))} W" else ""}") })
            }
            d.optJSONObject("gpu")?.let { g ->
                val label = if (g.has("util")) "GPU ${g.optInt("util")}% ${g.optInt("temp")}°C" else "GPU ${g.str("state")}"
                AssistChip({}, label = { Text(label) })
            }
        }
    }

    val total = cpu.getDouble("total").toFloat()
    Section("CPU  ${"%.0f".format(total)}%", trailing = {
        Text("${"%.1f".format(cpu.optDouble("freq_mhz") / 1000)} GHz", style = MaterialTheme.typography.labelLarge)
    }) {
        Spark(listOf(hist.getJSONArray("cpu").floats()), listOf(primary), max = 100f)
        val cores = cpu.getJSONArray("cores").floats()
        cores.withIndex().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (i, p) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$i".padStart(2), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Bar(p / 100f, heat(p), Modifier.weight(1f), 6.dp)
                        Text("${p.toInt()}".padStart(3), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    val mt = mem.getDouble("total")
    val used = mt - mem.getDouble("available")
    val swapT = mem.getDouble("swap_total")
    Section("Memory  ${"%.0f".format(100 * used / mt)}%", trailing = { Text("${fmtBytes(used)} / ${fmtBytes(mt)}", style = MaterialTheme.typography.labelLarge) }) {
        Spark(listOf(hist.getJSONArray("mem").floats()), listOf(tertiary), max = 100f)
        Bar((used / mt).toFloat(), heat((100 * used / mt).toFloat()))
        Text("cached ${fmtBytes(mem.getDouble("cached"))}", style = MaterialTheme.typography.bodySmall)
        if (swapT > 0) {
            val su = swapT - mem.getDouble("swap_free")
            Text("swap ${fmtBytes(su)} / ${fmtBytes(swapT)}", style = MaterialTheme.typography.bodySmall)
            Bar((su / swapT).toFloat(), heat((100 * su / swapT).toFloat()), height = 5.dp)
        }
    }

    val net = d.getJSONObject("net")
    val io = d.getJSONObject("disk_io")
    val rxC = Color(0xFF4CAF50)
    val txC = Color(0xFFFF9800)
    Section("Network & disk I/O") {
        Spark(listOf(hist.getJSONArray("rx").floats(), hist.getJSONArray("tx").floats()), listOf(rxC, txC))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("▼ ${fmtRate(net.getDouble("rx_bps"))}", rxC)
            Legend("▲ ${fmtRate(net.getDouble("tx_bps"))}", txC)
        }
        Text("disk read ${fmtRate(io.getDouble("read_bps"))} · write ${fmtRate(io.getDouble("write_bps"))}", style = MaterialTheme.typography.bodySmall)
    }

    val disks = d.getJSONArray("disks").objs()
    if (disks.isNotEmpty()) Section("Disks") {
        disks.forEach { k ->
            val t = k.getDouble("total")
            val u = k.getDouble("used")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k.str("mount"), style = MaterialTheme.typography.bodyMedium)
                Text("${fmtBytes(u)} / ${fmtBytes(t)}", style = MaterialTheme.typography.labelMedium)
            }
            Bar((u / t).toFloat(), heat((100 * u / t).toFloat()))
        }
    }

    Processes(d.getJSONObject("procs"), scope)
}

@Composable
private fun Processes(procs: JSONObject, scope: kotlinx.coroutines.CoroutineScope) {
    var sortCpu by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    val rows = procs.getJSONArray("top").objs().sortedByDescending { if (sortCpu) it.getDouble("cpu") else it.getDouble("mem") }.take(15)
    Section("Processes  (${procs.optInt("count")})", trailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(sortCpu, { sortCpu = true }, label = { Text("CPU") })
            FilterChip(!sortCpu, { sortCpu = false }, label = { Text("MEM") })
        }
    }) {
        Row(Modifier.fillMaxWidth()) {
            Text("PID", Modifier.width(76.dp), style = MaterialTheme.typography.labelSmall)
            Text("NAME", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text("CPU%", Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall)
            Text("MEM", Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall)
        }
        rows.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { selected = p }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.getInt("pid")}", Modifier.width(76.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(p.str("name"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%.1f".format(p.getDouble("cpu")), Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = heat(p.getDouble("cpu").toFloat().coerceAtMost(100f)))
                Text(fmtBytes(p.getDouble("mem")), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
    selected?.let { p ->
        val pid = p.getInt("pid")
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${p.str("name")}  ($pid)") },
            text = { Text("CPU ${"%.1f".format(p.getDouble("cpu"))}% · ${fmtBytes(p.getDouble("mem"))}") },
            confirmButton = {
                Row {
                    TextButton({ scope.launch { Hc.post("/api/procs/$pid/term") }; selected = null }) { Text("Terminate") }
                    TextButton({ scope.launch { Hc.post("/api/procs/$pid/kill") }; selected = null }) { Text("Kill") }
                }
            },
            dismissButton = {
                Row {
                    TextButton({ scope.launch { Hc.post("/api/procs/$pid/stop") }; selected = null }) { Text("Pause") }
                    TextButton({ scope.launch { Hc.post("/api/procs/$pid/cont") }; selected = null }) { Text("Resume") }
                }
            },
        )
    }
}

/** One compact card per machine so a server and a laptop can be watched side by side. */
@Composable
private fun Fleet() {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Hc.machines.forEach { m ->
            var cpu by remember(m.id) { mutableStateOf<Float?>(null) }
            var mem by remember(m.id) { mutableStateOf<Float?>(null) }
            var temp by remember(m.id) { mutableStateOf<Double?>(null) }
            LaunchedEffect(m.id) {
                while (true) {
                    m.json("/api/monitor")?.let { d ->
                        cpu = d.getJSONObject("cpu").getDouble("total").toFloat()
                        val mm = d.getJSONObject("mem")
                        mem = (100 * (1 - mm.getDouble("available") / mm.getDouble("total"))).toFloat()
                        temp = d.getJSONObject("cpu").let { if (it.isNull("temp")) null else it.getDouble("temp") }
                    } ?: run { cpu = null }
                    delay(4000)
                }
            }
            val sel = Hc.cur?.id == m.id
            Card(
                onClick = { Hc.select(m) },
                colors = CardDefaults.cardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(12.dp).width(130.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cpu == null) Text(if (m.offline) "offline" else "…", style = MaterialTheme.typography.bodySmall) else {
                        Text("CPU ${cpu!!.toInt()}%${temp?.let { " · ${it.toInt()}°" } ?: ""}", style = MaterialTheme.typography.labelMedium)
                        Bar(cpu!! / 100f, heat(cpu!!), height = 5.dp)
                        Text("MEM ${mem!!.toInt()}%", style = MaterialTheme.typography.labelMedium)
                        Bar(mem!! / 100f, heat(mem!!), height = 5.dp)
                    }
                }
            }
        }
    }
}
