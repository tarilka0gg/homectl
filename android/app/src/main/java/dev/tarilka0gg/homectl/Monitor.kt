@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package dev.tarilka0gg.homectl

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val rxColor = Color(0xFF4CAF50)
private val txColor = Color(0xFFFF9800)
private val tempColor = Color(0xFFE53935)
private val fanColors = listOf(Color(0xFF29B6F6), Color(0xFFAB47BC), Color(0xFF26A69A), Color(0xFFFFCA28))

/** What the screen needs from the graph history, as plain lists (no JSON round-trips per poll). */
class HistView(private val m: Map<String, List<Float>>, val cores: List<List<Float>>) {
    fun series(k: String): List<Float> = m[k].orEmpty()
}

/**
 * Keeps each machine's graph history here and asks the machine only for samples newer than the last tick, so a poll is a
 * few KB instead of the whole history (85 KB with per-core graphs). Older daemons just send everything every time.
 */
object HistStore {
    private class Mem {
        var tick = 0L
        var cores = false
        val series = HashMap<String, ArrayList<Float>>()
        val coreSeries = ArrayList<ArrayList<Float>>()
    }
    private val mems = HashMap<String, Mem>()
    private const val KEEP = 120

    fun since(machine: String, wantCores: Boolean): Long = mems[machine]?.takeIf { it.cores == wantCores }?.tick ?: 0L

    /** Folds [d] (a monitor reply) into the stored history. */
    fun merge(machine: String, d: JSONObject, wantCores: Boolean) {
        val m = mems.getOrPut(machine) { Mem() }
        val delta = d.optBoolean("hist_delta") && m.cores == wantCores
        if (!delta) { m.series.clear(); m.coreSeries.clear() }
        d.optJSONObject("history")?.let { h ->
            h.keys().forEach { k ->
                val list = m.series.getOrPut(k) { ArrayList() }
                val a = h.getJSONArray(k)
                for (i in 0 until a.length()) list.add(a.getDouble(i).toFloat())
                if (list.size > KEEP) list.subList(0, list.size - KEEP).clear()
            }
        }
        d.optJSONArray("core_history")?.let { cores ->
            for (c in 0 until cores.length()) {
                if (m.coreSeries.size <= c) m.coreSeries.add(ArrayList())
                val list = m.coreSeries[c]
                val a = cores.getJSONArray(c)
                for (i in 0 until a.length()) list.add(a.getDouble(i).toFloat())
                if (list.size > KEEP) list.subList(0, list.size - KEEP).clear()
            }
        }
        m.cores = wantCores
        m.tick = d.optLong("tick", 0L)
    }

    /** A snapshot that stays valid while the stored lists keep growing. */
    fun view(machine: String): HistView {
        val m = mems[machine] ?: return HistView(emptyMap(), emptyList())
        return HistView(m.series.mapValues { it.value.toList() }, m.coreSeries.map { it.toList() })
    }
}

@Composable
fun MonitorScreen() {
    val data = loaded(listOf(Hc.gpuLive, Hc.speedMs, Hc.perCoreGraphs), Hc.speedMs.coerceAtLeast(500).toLong(), "monitor") {
        val machine = Hc.cur?.id
        val cores = Hc.perCoreGraphs
        Hc.json("/api/monitor?every=${Hc.speedMs}" + (if (Hc.gpuLive) "&gpu=1" else "") + (if (cores) "&cores=1" else "") +
            (machine?.let { "&since=${HistStore.since(it, cores)}" } ?: ""))?.also { d -> machine?.let { HistStore.merge(it, d, cores) } }
    }
    val scope = rememberCoroutineScope()
    if (Hc.machines.size > 1) Fleet()
    val d = data.value
    if (d == null) {
        Text(if (Hc.cur?.offline == true) "Machine is offline." else Hc.cur?.authProblem?.let { "This machine doesn't accept this phone yet: $it" } ?: "Loading…")
        if (Hc.cur?.offline == true) Hc.cur?.let { ConnectionTest(it, autoRun = true) }
        return
    }
    val info = d.getJSONObject("info")
    val cpu = d.getJSONObject("cpu")
    val mem = d.optJSONObject("mem") ?: JSONObject()
    val hist = HistStore.view(Hc.cur?.id.orEmpty())
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary

    Section(info.str("hostname"), trailing = { Text("up ${fmtUptime(d.optLong("uptime_s"))}", style = MaterialTheme.typography.labelLarge) }) {
        Text("${info.str("os")} · ${info.str("kernel")}", style = MaterialTheme.typography.bodySmall)
        Text(info.str("cpu_model"), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val load = d.getJSONArray("load").floats()
            if (load.size == 3) AssistChip({}, label = { Text("load ${load.joinToString(" ") { "%.2f".format(it) }}") })
            if (!cpu.isNull("temp")) AssistChip({}, label = { Text("CPU ${"%.0f".format(cpu.getDouble("temp"))}°C") })
            d.optJSONObject("battery")?.let { b ->
                AssistChip({}, label = { Text("🔋 ${b.optInt("percent")}% ${b.str("status")}${if (!b.isNull("watts")) " ${"%.1f".format(b.getDouble("watts"))} W" else ""}") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!Hc.circleLook, { Hc.updateCircleLook(false) }, label = { Text("Bars") })
            FilterChip(Hc.circleLook, { Hc.updateCircleLook(true) }, label = { Text("Circles") })
        }
        if (Hc.circleLook) Overview(d)
    }

    // ---- CPU ----
    val total = cpu.getDouble("total").toFloat()
    val cores = cpu.optJSONArray("cores")?.floats().orEmpty()
    val coreTemps = cpu.optJSONArray("core_temps")
    val coreFreqs = cpu.optJSONArray("core_freqs")
    Section("CPU  ${"%.0f".format(total)}%", trailing = {
        Text("${"%.2f".format(cpu.optDouble("freq_mhz") / 1000)} GHz avg · ${"%.2f".format(cpu.optDouble("freq_max_mhz") / 1000)} max", style = MaterialTheme.typography.labelMedium)
    }) {
        Chart(listOf(hist.series("cpu")), listOf(primary), { "%.0f%%".format(it) }, max = 100f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!Hc.perCoreGraphs, { Hc.setPerCore(false) }, label = { Text(if (Hc.circleLook) "Circles" else "Bars") })
            FilterChip(Hc.perCoreGraphs, { Hc.setPerCore(true) }, label = { Text("Graph per core") })
        }
        if (Hc.perCoreGraphs && hist.cores.isNotEmpty()) {
            CoreGrid(hist.cores, cores, (0 until cores.size).map { i -> coreTemps?.let { if (i < it.length() && !it.isNull(i)) it.getDouble(i) else null } })
        } else if (Hc.circleLook) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cores.forEachIndexed { i, p ->
                    val t = coreTemps?.let { if (i < it.length() && !it.isNull(i)) it.getDouble(i) else null }
                    Box(Modifier.padding(horizontal = 2.dp)) { Gauge("core $i", "${p.toInt()}%", p / 100f, heat(p), 52.dp, t?.let { "${it.toInt()}°" }) }
                }
            }
        } else cores.withIndex().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (i, p) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$i".padStart(2), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Bar(p / 100f, heat(p), Modifier.weight(1f), 6.dp)
                        Text("${p.toInt()}%".padStart(4), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        val t = coreTemps?.let { if (i < it.length() && !it.isNull(i)) it.getDouble(i) else null }
                        if (t != null) Text("${t.toInt()}°", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = heat(((t - 30) * 100 / 70).toFloat()))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        coreFreqs?.let { f ->
            val fl = f.floats()
            if (fl.isNotEmpty()) Text("clock: min ${"%.0f".format(fl.min())} · max ${"%.0f".format(fl.max())} MHz across ${fl.size} threads", style = MaterialTheme.typography.bodySmall)
        }
    }

    // ---- GPUs ----
    val gpus = d.optJSONArray("gpus")?.objs().orEmpty()
    CpuDetailsSection(d)
    gpus.forEach { g -> GpuCard(g, hist) }

    // ---- memory ----
    val mt = mem.getDouble("total")
    val used = mt - mem.getDouble("available")
    val swapT = mem.getDouble("swap_total")
    Section("Memory  ${"%.0f".format(100 * used / mt)}%", trailing = { Text("${fmtBytes(used)} / ${fmtBytes(mt)}", style = MaterialTheme.typography.labelLarge) }) {
        Chart(listOf(hist.series("mem")), listOf(tertiary), { "%.0f%%".format(it) }, max = 100f)
        if (Hc.circleLook) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Gauge("RAM", "${"%.0f".format(100 * used / mt)}%", (used / mt).toFloat(), heat((100 * used / mt).toFloat()), sub = fmtBytes(used))
            if (swapT > 0) {
                val su = swapT - mem.getDouble("swap_free")
                Gauge("Swap", "${"%.0f".format(100 * su / swapT)}%", (su / swapT).toFloat(), heat((100 * su / swapT).toFloat()), sub = fmtBytes(su))
            }
        } else Bar((used / mt).toFloat(), heat((100 * used / mt).toFloat()))
        Text("cached ${fmtBytes(mem.getDouble("cached"))} · buffers ${fmtBytes(mem.optDouble("buffers"))}", style = MaterialTheme.typography.bodySmall)
        if (swapT > 0 && !Hc.circleLook) {
            val su = swapT - mem.getDouble("swap_free")
            Text("swap ${fmtBytes(su)} / ${fmtBytes(swapT)}", style = MaterialTheme.typography.bodySmall)
            Bar((su / swapT).toFloat(), heat((100 * su / swapT).toFloat()), height = 5.dp)
        }
        MemoryDetails(d)
    }

    // ---- temperatures and fans ----
    val temps = d.optJSONArray("temps")?.objs().orEmpty()
    val tempSeries = buildList {
        hist.series("temp.cpu").takeIf { it.isNotEmpty() }?.let { add("CPU" to it) }
        gpus.forEach { g -> hist.series("${g.str("id")}.temp").takeIf { it.isNotEmpty() && g.str("kind") == "discrete" }?.let { add("GPU" to it) } }
    }
    if (tempSeries.isNotEmpty() || temps.isNotEmpty()) Section("Temperatures") {
        if (tempSeries.isNotEmpty()) {
            Chart(tempSeries.map { it.second }, listOf(tempColor, Color(0xFF7E57C2)), { "%.0f°C".format(it) }, autoMin = true)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                tempSeries.forEachIndexed { i, (n, v) -> Legend("$n ${"%.0f".format(v.lastOrNull() ?: 0f)}°C", listOf(tempColor, Color(0xFF7E57C2))[i]) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            temps.forEach { t -> AssistChip({}, label = { Text("${t.str("label").replace("nvme ", "NVMe ").replace("spd5118 temp1", "DIMM")} ${"%.0f".format(t.getDouble("value"))}°") }) }
        }
    }
    val fans = d.optJSONArray("fans")?.objs().orEmpty()
    if (fans.isNotEmpty()) Section("Fans") {
        Chart(fans.indices.map { hist.series("fan.$it") }, fanColors, { "%.0f rpm".format(it) }, autoMin = true)
        fans.forEachIndexed { i, f ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Legend(f.str("name"), fanColors[i % fanColors.size])
                Text("${"%.0f".format(f.getDouble("rpm"))} rpm" + if (!f.isNull("pwm")) " · ${"%.0f".format(f.getDouble("pwm"))}%" else "", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    PowerSection(d, hist, gpus)
    BatterySection(d)
    PressureSection(d)
    SensorsSection(d)

    // ---- network and disk I/O ----
    val net = d.getJSONObject("net")
    val io = d.getJSONObject("disk_io")
    Section("Network") {
        Chart(listOf(hist.series("rx"), hist.series("tx")), listOf(rxColor, txColor), { fmtRate(it.toDouble()) })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("▼ ${fmtRate(net.getDouble("rx_bps"))}", rxColor)
            Legend("▲ ${fmtRate(net.getDouble("tx_bps"))}", txColor)
        }
        if (net.has("rx_peak")) {
            Text("peak since start: ▼ ${fmtRate(net.optDouble("rx_peak"))} · ▲ ${fmtRate(net.optDouble("tx_peak"))}", style = MaterialTheme.typography.bodySmall)
            Text("total since boot: ▼ ${fmtBytes(net.optDouble("rx_total"))} · ▲ ${fmtBytes(net.optDouble("tx_total"))}", style = MaterialTheme.typography.bodySmall)
        }
        net.optJSONArray("ifaces")?.objs().orEmpty().forEach { i ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(i.str("name"), style = MaterialTheme.typography.bodyMedium)
                Text("▼ ${fmtRate(i.optDouble("rx_bps"))}  ▲ ${fmtRate(i.optDouble("tx_bps"))}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
        }
        WifiLine(d)
    }
    Section("Disk I/O (all disks)") {
        Chart(listOf(hist.series("dr"), hist.series("dw")), listOf(rxColor, txColor), { fmtRate(it.toDouble()) })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("read ${fmtRate(io.getDouble("read_bps"))}", rxColor)
            Legend("write ${fmtRate(io.getDouble("write_bps"))}", txColor)
        }
        if (io.has("read_peak")) Text("peak: read ${fmtRate(io.optDouble("read_peak"))} · write ${fmtRate(io.optDouble("write_peak"))}", style = MaterialTheme.typography.bodySmall)
    }

    DisksSection(d, hist)
    SystemDetailsSection(d)

    Processes(d.optJSONObject("procs")?.optInt("count") ?: 0, scope)
}

/** The circle look's summary: the few numbers you glance at, as big rings. */
@Composable
private fun Overview(d: JSONObject) {
    val cpu = d.getJSONObject("cpu")
    val mem = d.optJSONObject("mem") ?: JSONObject()
    val gpu = d.optJSONArray("gpus")?.objs().orEmpty().firstOrNull { !it.isNull("util") }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        cpu.getDouble("total").toFloat().let { p -> Gauge("CPU", "${p.toInt()}%", p / 100f, heat(p)) }
        if (mem.has("total")) {
            val p = (100 * (1 - mem.getDouble("available") / mem.getDouble("total"))).toFloat()
            Gauge("Memory", "${p.toInt()}%", p / 100f, heat(p))
        }
        gpu?.getDouble("util")?.toFloat()?.let { p -> Gauge("GPU", "${p.toInt()}%", p / 100f, heat(p)) }
        if (!cpu.isNull("temp")) cpu.getDouble("temp").let { t -> Gauge("CPU temp", "${t.toInt()}°", (t / 100).toFloat(), heat(((t - 30) * 100 / 70).toFloat())) }
    }
}

@Composable
private fun GpuCard(g: JSONObject, hist: HistView) {
    val id = g.str("id")
    val live = g.optBoolean("live")
    val integrated = g.str("kind") == "integrated"
    val c = MaterialTheme.colorScheme.primary
    Section(g.str("name").replace("NVIDIA GeForce ", "").replace("Laptop GPU", "").trim().ifBlank { "GPU" }, trailing = {
        AssistChip({}, label = { Text("${if (integrated) "iGPU" else "dGPU"} · ${g.str("state")}${g.str("pstate").let { if (it.isBlank()) "" else " · $it" }}") })
    }) {
        val note = g.str("note")
        if (note.isNotBlank()) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (g.str("vendor") == "nvidia" && g.str("state") == "active" && note.contains("battery")) {
                SwitchRow("Read it anyway (may stop it sleeping)", Hc.gpuLive) { Hc.gpuLive = it }
            }
        }
        if (Hc.circleLook && (!g.isNull("util") || (!g.isNull("mem_total") && !g.optBoolean("shared_memory")))) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (!g.isNull("util")) g.getDouble("util").toFloat().let { u -> Gauge("Load", "${u.toInt()}%", u / 100f, heat(u)) }
            if (!g.isNull("mem_total") && !g.optBoolean("shared_memory")) {
                val u = g.optDouble("mem_used")
                val t = g.getDouble("mem_total")
                Gauge("VRAM", "${"%.0f".format(100 * u / t)}%", (u / t).toFloat(), heat((100 * u / t).toFloat()), sub = fmtBytes(u))
            }
            if (!g.isNull("temp")) g.getDouble("temp").let { t -> Gauge("Temp", "${t.toInt()}°", (t / 100).toFloat(), heat(((t - 30) * 100 / 70).toFloat())) }
        }
        if (!g.isNull("util")) {
            Text("Load ${"%.0f".format(g.getDouble("util"))}%", style = MaterialTheme.typography.titleSmall)
            Chart(listOf(hist.series("$id.util")), listOf(c), { "%.0f%%".format(it) }, max = 100f, height = 48.dp)
        }
        if (g.optBoolean("shared_memory")) {
            Text("Memory: shared with system RAM", style = MaterialTheme.typography.bodySmall)
        } else if (!g.isNull("mem_total")) {
            val u = g.optDouble("mem_used")
            val t = g.getDouble("mem_total")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("VRAM", style = MaterialTheme.typography.bodyMedium)
                Text("${fmtBytes(u)} / ${fmtBytes(t)}", style = MaterialTheme.typography.labelMedium)
            }
            if (!Hc.circleLook) Bar((u / t).toFloat(), heat((100 * u / t).toFloat()))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!g.isNull("clock_mhz")) AssistChip({}, label = { Text("core ${"%.0f".format(g.getDouble("clock_mhz"))}${if (!g.isNull("clock_max_mhz")) " / ${"%.0f".format(g.getDouble("clock_max_mhz"))}" else ""} MHz") })
            if (!g.isNull("mem_clock_mhz")) AssistChip({}, label = { Text("mem ${"%.0f".format(g.getDouble("mem_clock_mhz"))} MHz") })
            if (!g.isNull("power_w")) AssistChip({}, label = { Text("${"%.1f".format(g.getDouble("power_w"))}${if (!g.isNull("power_limit_w")) " / ${"%.0f".format(g.getDouble("power_limit_w"))}" else ""} W") })
            if (!g.isNull("temp")) AssistChip({}, label = { Text("${"%.0f".format(g.getDouble("temp"))}°C${if (integrated) " (package)" else ""}") })
            if (!g.isNull("fan_pct")) AssistChip({}, label = { Text("fan ${"%.0f".format(g.getDouble("fan_pct"))}%") })
            if (!g.isNull("fan_rpm")) AssistChip({}, label = { Text("fan ${"%.0f".format(g.getDouble("fan_rpm"))} rpm") })
        }
        if (live) {
            val clock = hist.series("$id.clock")
            if (clock.size > 1) { Text("Clock", style = MaterialTheme.typography.labelMedium); Chart(listOf(clock), listOf(Color(0xFF26A69A)), { "%.0f MHz".format(it) }, height = 40.dp, autoMin = true) }
            val power = hist.series("$id.power")
            if (power.size > 1) { Text("Power", style = MaterialTheme.typography.labelMedium); Chart(listOf(power), listOf(Color(0xFFFFA726)), { "%.1f W".format(it) }, height = 40.dp, autoMin = true) }
            val temp = hist.series("$id.temp")
            if (temp.size > 1 && !integrated) { Text("Temperature", style = MaterialTheme.typography.labelMedium); Chart(listOf(temp), listOf(tempColor), { "%.0f°C".format(it) }, height = 40.dp, autoMin = true) }
            val vram = hist.series("$id.vram")
            if (vram.size > 1 && !g.isNull("mem_total")) { Text("VRAM", style = MaterialTheme.typography.labelMedium); Chart(listOf(vram), listOf(Color(0xFF7E57C2)), { fmtBytes(it.toDouble()) }, max = g.getDouble("mem_total").toFloat(), height = 40.dp) }
        }
    }
}

@Composable
private fun Processes(count: Int, scope: CoroutineScope) {
    var sort by remember { mutableStateOf(Hc.memStr("proc.sort", "cpu")) }
    var desc by remember { mutableStateOf(Hc.memBool("proc.desc", true)) }
    var q by remember { mutableStateOf(Hc.memStr("proc.q")) }
    LaunchedEffect(sort, desc, q) { Hc.memPutStr("proc.sort", sort); Hc.memPutBool("proc.desc", desc); Hc.memPutStr("proc.q", q) }
    var limit by remember { mutableIntStateOf(40) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    val res = loaded(listOf(sort, desc, q, limit), (Hc.speedMs * 2).coerceIn(1000, 10000).toLong()) {
        Hc.json("/api/procs?sort=$sort&order=${if (desc) "desc" else "asc"}&q=${Uri.encode(q)}&limit=$limit")
    }
    Section("Processes  ($count)") {
        OutlinedTextField(q, { q = it; limit = 40 }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search name, command, user or PID") })
        // tap a column header to sort by it; tap again to reverse
        @Composable
        fun H(label: String, key: String, mod: Modifier) {
            val on = sort == key
            Text(
                label + if (on) (if (desc) " ▼" else " ▲") else "",
                mod.clickable { if (on) desc = !desc else { sort = key; desc = key == "cpu" || key == "mem" } }.padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            H("PID", "pid", Modifier.width(80.dp))
            H("NAME", "name", Modifier.weight(1f))
            H("CPU%", "cpu", Modifier.width(64.dp))
            H("MEM", "mem", Modifier.width(78.dp))
        }
        val procs = res.value?.optJSONArray("procs")?.objs().orEmpty()
        procs.forEach { p -> key(p.getInt("pid")) {
            Row(Modifier.fillMaxWidth().clickable { selected = p }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.getInt("pid")}", Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Column(Modifier.weight(1f)) {
                    Text(p.str("name"), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.str("user"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("%.1f".format(p.getDouble("cpu")), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = heat(p.getDouble("cpu").toFloat().coerceAtMost(100f)))
                Text(fmtBytes(p.getDouble("mem")), Modifier.width(78.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        } }
        val matched = res.value?.optInt("matched") ?: 0
        if (procs.size < matched) OutlinedButton({ limit += 60 }) { Text("Show more (${procs.size} of $matched)") }
    }
    selected?.let { p ->
        val pid = p.getInt("pid")
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${p.str("name")}  ($pid)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.str("cmd"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("user ${p.str("user")} · ${p.optInt("threads")} threads · state ${p.str("state")}", style = MaterialTheme.typography.bodySmall)
                    Text("CPU ${"%.1f".format(p.getDouble("cpu"))}% · memory ${fmtBytes(p.getDouble("mem"))}", style = MaterialTheme.typography.bodyMedium)
                }
            },
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
                    // /api/glance is ~75 bytes and doesn't speed up the machine's sampler; older daemons only have /api/monitor
                    val g = m.json("/api/glance")
                    if (g != null) {
                        cpu = g.optDouble("cpu").toFloat()
                        mem = g.optDouble("mem").toFloat()
                        temp = if (g.isNull("temp")) null else g.getDouble("temp")
                    } else m.json("/api/monitor")?.let { d ->
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
