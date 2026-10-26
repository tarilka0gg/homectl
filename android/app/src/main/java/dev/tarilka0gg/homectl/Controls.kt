@file:OptIn(ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ControlsScreen() {
    val scope = rememberCoroutineScope()
    var nonce by remember { mutableIntStateOf(0) }
    /** Send a change, give the machine a moment, then re-read so every switch shows the real state. */
    fun act(delayMs: Long = 500, block: suspend () -> Unit) = scope.launch { block(); delay(delayMs); nonce++ }

    val radios = loaded(nonce, 5000, "ctl-radios") { Hc.json("/api/radios") }
    val wifi = loaded(nonce, 8000, "ctl-wifi") { Hc.json("/api/wifi") }
    val bt = loaded(nonce, 6000, "ctl-bt") { Hc.json("/api/bluetooth") }
    val mixer = loaded(nonce, 4000, "ctl-mixer") { Hc.json("/api/mixer") }
    val bri = loaded(Unit, 0, "ctl-bri") { Hc.json("/api/brightness") }
    val mons = loaded(Unit, 0, "ctl-mons") { Hc.array("/api/monitors") }
    val cam = loaded(nonce, 10000) { Hc.array("/api/webcam/0") }
    val tunnel = loaded(nonce, 6000, "ctl-tunnel") { Hc.json("/api/tunnel") }

    LightsScreen()

    val disp = loaded(nonce, 15000, "ctl-disp") { Hc.json("/api/displays") }
    var allOffConfirm by remember { mutableStateOf(false) }
    disp.value?.let { d ->
        val internal = d.optJSONObject("internal")
        val externals = d.optJSONArray("external")?.objs().orEmpty()
        if (internal != null || externals.isNotEmpty()) Section("Screens") {
            val comp = if (Hc.compositorOff) 1 else 0
            internal?.let {
                SwitchRow(it.str("title"), it.optBoolean("on"), if (it.optBoolean("on")) "${it.optInt("percent")}% brightness" else "off (backlight at 0)") { on ->
                    act(1200) { Hc.post("/api/displays/internal/${if (on) "on" else "off"}") }
                }
                if (it.optBoolean("on") && !it.isNull("percent")) SliderRow("Brightness", it.optInt("percent").toFloat(), 100f, 1f) { v -> scope.launch { Hc.post("/api/brightness", body("percent" to v)) } }
            }
            externals.forEach { e ->
                val known = !e.isNull("on")
                SwitchRow(e.str("title"), e.optBoolean("on"), if (known) e.str("detail") else "${e.str("detail")} · not answering", enabled = true) { on ->
                    act(3000) { Hc.post("/api/displays/${e.str("id")}/${if (on) "on" else "off"}?compositor=$comp") }
                }
                if (e.optBoolean("on") && !e.isNull("percent")) SliderRow("Brightness", e.optInt("percent").toFloat(), 100f, 0f) { v -> scope.launch { Hc.post("/api/monitors/${e.str("id")}/brightness", body("percent" to v)) } }
            }
            if (externals.isNotEmpty() && d.optBoolean("compositor")) {
                SwitchRow("Also disable it in the compositor", Hc.compositorOff, "frees the GPU from driving a dark screen so it can sleep") { Hc.updateCompositorOff(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ allOffConfirm = true }) { Text("All screens off") }
                FilledTonalButton({ act(3000) { Hc.post("/api/displays/all/on?compositor=$comp") } }) { Text("All screens on") }
            }
            Text("External monitors use DDC/CI standby (not DPMS), the built-in panel its backlight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (allOffConfirm) AlertDialog(
        onDismissRequest = { allOffConfirm = false },
        title = { Text("Turn every screen off?") },
        text = { Text("You can turn them back on from here, or by pressing a key on the machine.") },
        confirmButton = { TextButton({ allOffConfirm = false; act(3000) { Hc.post("/api/displays/all/off?compositor=${if (Hc.compositorOff) 1 else 0}") } }) { Text("Turn off") } },
        dismissButton = { TextButton({ allOffConfirm = false }) { Text("Cancel") } },
    )

    Section("Connectivity") {
        val r = radios.value
        if (r == null) Text("Loading…") else {
            val w = r.optBoolean("wifi")
            val b = r.optBoolean("bluetooth")
            SwitchRow("Airplane mode", !w && !b, "turns Wi-Fi and Bluetooth off together") { on ->
                act { Hc.post("/api/wifi/power", body("on" to !on)); Hc.post("/api/bluetooth/power", body("on" to !on)) }
            }
            val net = wifi.value?.getJSONArray("networks")?.objs()?.firstOrNull { it.optBoolean("active") }
            SwitchRow("Wi-Fi", w, net?.let { "${it.str("ssid")} · ${it.optInt("signal")}%" }) { on -> act { Hc.post("/api/wifi/power", body("on" to on)) } }
            SwitchRow("Bluetooth", b) { on -> act(1500) { Hc.post("/api/bluetooth/power", body("on" to on)) } }
        }
        bt.value?.getJSONArray("devices")?.objs()?.forEach { dev ->
            val mac = dev.str("mac")
            SwitchRow(dev.str("name"), dev.optBoolean("connected"), if (dev.optBoolean("connected")) "connected" else "not connected") { on ->
                act(2500) { Hc.post("/api/bluetooth/$mac/${if (on) "connect" else "disconnect"}") }
            }
        }
    }

    var showHidden by remember { mutableStateOf(false) }
    for ((kind, devKey, title) in listOf(Triple("sink", "sinks", "Outputs"), Triple("source", "sources", "Inputs"))) {
        val all = mixer.value?.getJSONArray(devKey)?.objs().orEmpty().filter { it.str("name").isNotBlank() && it.str("desc") != "(null)" }
        val usable = all.filter { it.optBoolean("available", true) }
        val hidden = all.size - usable.size
        Section(title, trailing = {
            if (hidden > 0) TextButton({ showHidden = !showHidden }) { Text(if (showHidden) "Hide $hidden unplugged" else "$hidden unplugged hidden") }
        }) {
            if (mixer.value == null) Text("Loading…")
            else if (usable.isEmpty()) Text("Nothing usable right now.", style = MaterialTheme.typography.bodyMedium)
            (if (showHidden) all else usable).forEach { dev ->
                val name = dev.str("name")
                val isDef = dev.optBoolean("default")
                val ok = dev.optBoolean("available", true)
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(isDef, { act { Hc.post("/api/mixer/$kind/$name", body("default" to true)) } }, enabled = ok)
                        Column(Modifier.weight(1f)) {
                            Text(dev.str("desc"), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                            Text(
                                when { !ok -> "unplugged"; isDef -> "in use" else -> "tap the circle to switch to it" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(!dev.optBoolean("muted"), { on -> act { Hc.post("/api/mixer/$kind/$name", body("muted" to !on)) } }, enabled = ok)
                    }
                    SliderRow(if (dev.optBoolean("muted")) "muted" else "volume", dev.optInt("percent").toFloat(), 150f, enabled = ok) { v ->
                        act { Hc.post("/api/mixer/$kind/$name", body("percent" to v)) }
                    }
                }
            }
        }
    }

    val streams = mixer.value?.getJSONArray("streams")?.objs().orEmpty()
    val recording = mixer.value?.getJSONArray("recording")?.objs().orEmpty().filter { it.str("app") != "Unknown" }
    Section("Mixer — apps playing") {
        if (streams.isEmpty()) Text("Nothing is playing.", style = MaterialTheme.typography.bodyMedium)
        streams.forEach { s -> AppStream(s, "stream", ::act) }
    }
    if (recording.isNotEmpty()) Section("Mixer — apps recording") {
        recording.forEach { s -> AppStream(s, "recording", ::act) }
    }

    val extNoBrightness = disp.value?.optJSONArray("external")?.objs().orEmpty().any { !it.has("percent") }
    if (disp.value == null || extNoBrightness) Section("Display") {
        bri.value?.takeIf { !it.isNull("percent") }?.let { b ->
            SliderRow("Laptop screen", b.optInt("percent", 50).toFloat(), 100f, 1f) { v -> scope.launch { Hc.post("/api/brightness", body("percent" to v)) } }
        }
        val ml = mons.value?.objs().orEmpty()
        ml.forEach { d ->
            val n = d.str("display")
            var cur by remember(n) { mutableStateOf<Float?>(null) }
            LaunchedEffect(n) { Hc.json("/api/monitors/$n/brightness")?.let { cur = it.optInt("percent", 50).toFloat() } }
            cur?.let { SliderRow(d.str("model").ifBlank { "Monitor $n" }, it) { v -> scope.launch { Hc.post("/api/monitors/$n/brightness", body("percent" to v)) } } }
        }
        if (bri.value?.isNull("percent") != false && ml.isEmpty()) Text("No controllable displays.", style = MaterialTheme.typography.bodyMedium)
    }

    cam.value?.objs()?.takeIf { it.isNotEmpty() }?.let { controls ->
        Section("Webcam") {
            controls.forEach { c ->
                val id = c.getLong("id")
                if (c.optInt("min") == 0 && c.optInt("max") == 1) {
                    SwitchRow(c.str("name"), c.optInt("value") == 1) { on -> act { Hc.post("/api/webcam/0", body("id" to id, "value" to if (on) 1 else 0)) } }
                } else {
                    SliderRow(c.str("name"), c.optInt("value").toFloat(), c.optInt("max").toFloat(), c.optInt("min").toFloat()) { v ->
                        scope.launch { Hc.post("/api/webcam/0", body("id" to id, "value" to v)) }
                    }
                }
            }
        }
    }

    tunnel.value?.let { t ->
        Section("Remote access") {
            SwitchRow("Public tunnel", t.optBoolean("on"), if (!t.optBoolean("available")) "cloudflared is not installed on this machine" else t.str("url").ifBlank { if (t.optBoolean("auto") && t.opt("hub_up") == true) "off — your server reaches this machine; it starts by itself if the server stops answering" else "off" }, enabled = t.optBoolean("available")) { on ->
                act(3000) { Hc.post("/api/tunnel", body("on" to on)) }
            }
        }
    }
}

@Composable
private fun AppStream(s: JSONObject, kind: String, act: (Long, suspend () -> Unit) -> Unit) {
    val id = s.getInt("id")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SliderRow(s.str("app") + s.str("title").takeIf { it.isNotBlank() && it != s.str("app") && it != "(null)" }?.let { " — ${it.take(36)}" }.orEmpty(), s.optInt("percent").toFloat(), 150f) { v -> act(300) { Hc.post("/api/mixer/$kind/$id", body("percent" to v)) } }
        }
        Switch(!s.optBoolean("muted"), { on -> act(300) { Hc.post("/api/mixer/$kind/$id", body("muted" to !on)) } }, Modifier.padding(start = 12.dp))
    }
}
