@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.tarilka0gg.homectl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home("Home", Icons.Default.Home),
    Lights("Lights", Icons.Default.Star),
    Controls("Controls", Icons.Default.Settings),
    Tools("Tools", Icons.Default.Build),
}

@Composable
fun App() {
    if (!Hc.paired) return PairScreen()
    var tab by remember { mutableStateOf(Tab.Home) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Hc.name.ifBlank { "homectl" }) },
                actions = {
                    val a = Hc.active
                    AssistChip(
                        onClick = {},
                        label = { Text(if (Hc.offline) "offline" else a?.kind ?: "connecting…") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (Hc.offline) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(selected = tab == it, onClick = { tab = it }, icon = { Icon(it.icon, it.label) }, label = { Text(it.label) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                Tab.Home -> HomeScreen()
                Tab.Lights -> LightsScreen()
                Tab.Controls -> ControlsScreen()
                Tab.Tools -> ToolsScreen()
            }
        }
    }
}

@Composable
private fun PairScreen() {
    var link by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Connect to your machine", style = MaterialTheme.typography.headlineMedium)
            Text(
                "On the laptop run `homectl pair`, then paste the homectl:// link below (or open it on this phone). " +
                    "Or enter a server address and token by hand.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(link, { link = it; error = false }, Modifier.fillMaxWidth(), label = { Text("Pairing link or server URL") }, isError = error)
            if (!link.startsWith("homectl://")) {
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { error = !Hc.pairFrom(link, token) }) { Text("Connect") }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    link = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                }) { Text("Paste") }
            }
        }
    }
}

// ---------- shared pieces ----------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SliderRow(label: String, initial: Float, max: Float = 100f, min: Float = 0f, onDone: (Int) -> Unit) {
    var v by remember(initial) { mutableFloatStateOf(initial) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(v.toInt().toString(), style = MaterialTheme.typography.labelLarge)
        }
        Slider(v, { v = it }, valueRange = min..max, onValueChangeFinished = { onDone(v.toInt()) })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

private fun body(vararg p: Pair<String, Any>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }

/** Reloads when [key] changes; polls every [everyMs] if > 0. */
@Composable
private fun <T> loaded(key: Any?, everyMs: Long = 0, fetch: suspend () -> T?): State<T?> {
    val s = remember { mutableStateOf<T?>(null) }
    LaunchedEffect(key) {
        do {
            fetch()?.let { s.value = it }
            if (everyMs > 0) delay(everyMs)
        } while (everyMs > 0)
    }
    return s
}

// ---------- Home ----------

@Composable
private fun HomeScreen() {
    val scope = rememberCoroutineScope()
    val stats = loaded(Unit, 5000) { Hc.json("/api/stats") }
    Section("System") {
        val s = stats.value
        if (s == null) Text("loading…") else {
            val total = s.optLong("mem_total_kb").toFloat()
            val used = total - s.optLong("mem_available_kb")
            val load = s.getJSONArray("load")
            Text("up ${"%.1f".format(s.optLong("uptime_s") / 3600f)} h · load ${load.getDouble(0)} ${load.getDouble(1)} ${load.getDouble(2)}", fontFamily = FontFamily.Monospace)
            Text("Memory ${"%.1f".format(used / 1048576)} / ${"%.1f".format(total / 1048576)} GiB", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { if (total > 0) used / total else 0f }, Modifier.fillMaxWidth())
        }
    }
    Section("Connection") {
        Hc.endpoints.forEach {
            val on = it == Hc.active
            Text(
                "${if (on) "●" else "○"} ${it.kind}  ${it.url}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { Hc.resolve() } }) { Text("Re-check") }
            TextButton(onClick = { Hc.unpair() }) { Text("Unpair") }
        }
    }
}

// ---------- Lights ----------

private val presets = listOf(
    "Warm" to Triple(255, 244, 229),
    "Cool" to Triple(201, 226, 255),
    "Red" to Triple(255, 0, 0),
    "Green" to Triple(0, 255, 0),
    "Blue" to Triple(0, 0, 255),
    "Purple" to Triple(160, 0, 255),
)

@Composable
private fun LightsScreen() {
    var nonce by remember { mutableIntStateOf(0) }
    val lights = loaded(nonce) { Hc.array("/api/lights") }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Govee lights", style = MaterialTheme.typography.titleLarge)
        IconButton({ nonce++ }) { Icon(Icons.Default.Refresh, "Rescan") }
    }
    val l: JSONArray? = lights.value
    if (l == null) Text("scanning…") else if (l.length() == 0) {
        Text("None found. Enable \"LAN Control\" for each light in the Govee app.", style = MaterialTheme.typography.bodyMedium)
    }
    for (i in 0 until (l?.length() ?: 0)) {
        val o = l!!.getJSONObject(i)
        val ip = o.getString("ip")
        Section("${o.getString("sku")}  ·  $ip") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ scope.launch { Hc.post("/api/lights/$ip", body("on" to true)) } }) { Text("On") }
                OutlinedButton({ scope.launch { Hc.post("/api/lights/$ip", body("on" to false)) } }) { Text("Off") }
            }
            SliderRow("Brightness", 100f, 100f, 1f) { b -> scope.launch { Hc.post("/api/lights/$ip", body("brightness" to b)) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                presets.forEach { (name, c) ->
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(c.first, c.second, c.third))
                            .clickable { scope.launch { Hc.post("/api/lights/$ip", body("rgb" to JSONArray(listOf(c.first, c.second, c.third)))) } },
                    )
                }
            }
        }
    }
}

// ---------- Controls ----------

@Composable
private fun ControlsScreen() {
    val scope = rememberCoroutineScope()
    val bri = loaded(Unit) { Hc.json("/api/brightness") }
    val mons = loaded(Unit) { Hc.array("/api/monitors") }
    val audio = loaded(Unit) { Hc.json("/api/audio") }
    var btNonce by remember { mutableIntStateOf(0) }
    val bt = loaded(btNonce) { Hc.json("/api/bluetooth") }
    var wifiNonce by remember { mutableIntStateOf(0) }
    val wifi = loaded(wifiNonce) { Hc.json("/api/wifi") }

    Section("Display") {
        bri.value?.let { b -> SliderRow("Laptop screen", b.optInt("percent", 50).toFloat(), 100f, 1f) { v -> scope.launch { Hc.post("/api/brightness", body("percent" to v)) } } }
        val m = mons.value
        for (i in 0 until (m?.length() ?: 0)) {
            val d = m!!.getJSONObject(i)
            val n = d.getString("display")
            var cur by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(n) { Hc.json("/api/monitors/$n/brightness")?.let { cur = it.optInt("percent", 50).toFloat() } }
            cur?.let { SliderRow(d.optString("model", "Monitor $n"), it) { v -> scope.launch { Hc.post("/api/monitors/$n/brightness", body("percent" to v)) } } }
        }
    }
    Section("Audio") {
        audio.value?.let { a ->
            for ((key, label) in listOf("output" to "Speakers", "mic" to "Microphone")) {
                val o = a.getJSONObject(key)
                SliderRow(label, o.optInt("percent", 0).toFloat(), 150f) { v -> scope.launch { Hc.post("/api/audio/$key", body("percent" to v)) } }
                SwitchRow("$label muted", o.optBoolean("muted")) { m -> scope.launch { Hc.post("/api/audio/$key", body("muted" to m)) } }
            }
        } ?: Text("loading…")
    }
    Section("Bluetooth") {
        bt.value?.let { b ->
            SwitchRow("Bluetooth", b.optBoolean("powered")) { on -> scope.launch { Hc.post("/api/bluetooth/power", body("on" to on)); btNonce++ } }
            val d = b.getJSONArray("devices")
            for (i in 0 until d.length()) {
                val dev = d.getJSONObject(i)
                val conn = dev.optBoolean("connected")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(dev.getString("name"), Modifier.weight(1f))
                    if (conn) OutlinedButton({ scope.launch { Hc.post("/api/bluetooth/${dev.getString("mac")}/disconnect"); btNonce++ } }) { Text("Disconnect") }
                    else FilledTonalButton({ scope.launch { Hc.post("/api/bluetooth/${dev.getString("mac")}/connect"); btNonce++ } }) { Text("Connect") }
                }
            }
        } ?: Text("loading…")
    }
    Section("Wi-Fi") {
        wifi.value?.let { w ->
            SwitchRow("Wi-Fi", w.optBoolean("enabled")) { on -> scope.launch { Hc.post("/api/wifi/power", body("on" to on)); wifiNonce++ } }
            val n = w.getJSONArray("networks")
            for (i in 0 until n.length()) n.getJSONObject(i).takeIf { it.optBoolean("active") }?.let {
                Text("Connected: ${it.getString("ssid")} (${it.optInt("signal")}%)", style = MaterialTheme.typography.bodyMedium)
            }
        } ?: Text("loading…")
    }
}

// ---------- Tools ----------

@Composable
private fun ToolsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var clip by remember { mutableStateOf("") }
    Section("Clipboard") {
        OutlinedTextField(clip, { clip = it }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ scope.launch { Hc.post("/api/clipboard", body("text" to clip)) } }) { Text("Send to laptop") }
            OutlinedButton({ scope.launch { clip = Hc.json("/api/clipboard")?.optString("text").orEmpty().let { if (it == "null") "" else it } } }) { Text("Get from laptop") }
            OutlinedButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("homectl", clip))
            }) { Text("Copy") }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch {
            var ok = 0
            for (u in uris) {
                val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                } ?: "file-${System.currentTimeMillis()}"
                if (Hc.upload(ctx, u, name)) ok++
            }
            Toast.makeText(ctx, "Sent $ok/${uris.size}", Toast.LENGTH_SHORT).show()
        }
    }
    Section("Send files") {
        Text("Files land in ~/homectl-inbox on the laptop. You can also use Share → homectl from any app.", style = MaterialTheme.typography.bodyMedium)
        Button({ picker.launch("*/*") }) { Text("Choose files") }
    }

    val cam = loaded(Unit) { Hc.array("/api/webcam/0") }
    cam.value?.takeIf { it.length() > 0 }?.let { c ->
        Section("Webcam") {
            for (i in 0 until c.length()) {
                val o = c.getJSONObject(i)
                SliderRow(o.getString("name"), o.optInt("value").toFloat(), o.optInt("max").toFloat(), o.optInt("min").toFloat()) { v ->
                    scope.launch { Hc.post("/api/webcam/0", body("id" to o.getLong("id"), "value" to v)) }
                }
            }
        }
    }

    var nonce by remember { mutableIntStateOf(0) }
    val svc = loaded(nonce) { Hc.array("/api/services") }
    var filter by remember { mutableStateOf("") }
    Section("Services") {
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter") }, singleLine = true)
        val s = svc.value
        for (i in 0 until (s?.length() ?: 0)) {
            val o = s!!.getJSONObject(i)
            val n = o.getString("name")
            if (filter.isNotBlank() && !n.contains(filter, true)) continue
            val running = o.optString("state") == "started"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(n)
                    Text(o.optString("state"), style = MaterialTheme.typography.labelSmall, color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ scope.launch { Hc.post("/api/services/$n/${if (running) "stop" else "start"}"); nonce++ } }) { Text(if (running) "Stop" else "Start") }
                TextButton({ scope.launch { Hc.post("/api/services/$n/restart"); nonce++ } }) { Text("Restart") }
            }
        }
    }
}
