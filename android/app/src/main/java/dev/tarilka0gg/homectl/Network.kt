package dev.tarilka0gg.homectl

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/** Network of the machine: airplane mode, Wi-Fi with its networks, Ethernet, Bluetooth with its devices. */
@Composable
fun NetworkScreen() {
    val scope = rememberCoroutineScope()
    var nonce by remember { mutableIntStateOf(0) }
    /** Send a change, give the machine a moment, then re-read so every switch shows the real state. */
    fun act(delayMs: Long = 500, block: suspend () -> Unit) = scope.launch { block(); delay(delayMs); nonce++ }

    val radios = loaded(nonce, 5000, "ctl-radios") { Hc.json("/api/radios") }
    val wifi = loaded(nonce, 8000, "ctl-wifi") { Hc.json("/api/wifi") }
    val bt = loaded(nonce, 6000, "ctl-bt") { Hc.json("/api/bluetooth") }

    val r = radios.value
    if (r != null) Section("Airplane mode", trailing = {
        Toggle(!r.optBoolean("wifi") && !r.optBoolean("bluetooth")) { on -> act { Hc.post("/api/wifi/power", body("on" to !on)); Hc.post("/api/bluetooth/power", body("on" to !on)) } }
    }) {}

    val w = radios.value?.optBoolean("wifi") == true
    Section("Wi-Fi", trailing = { Toggle(w, radios.value != null) { on -> act { Hc.post("/api/wifi/power", body("on" to on)) } } }) {
        val nets = wifi.value?.getJSONArray("networks")?.objs().orEmpty()
        if (w) {
            if (wifi.value == null) Text("Loading…", style = MaterialTheme.typography.bodySmall)
            else if (nets.isEmpty()) Text("No networks in range.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            nets.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.str("ssid"), style = MaterialTheme.typography.bodyLarge)
                        Text(if (n.optBoolean("active")) "connected · ${n.optInt("signal")}%" else "${n.optInt("signal")}% signal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    val eth = loaded(nonce, 8000, "net-eth") { Hc.array("/api/ethernet") }
    Section("Ethernet") {
        val wired = eth.value?.objs().orEmpty()
        if (eth.value == null) Text("Loading…", style = MaterialTheme.typography.bodySmall)
        else if (wired.isEmpty()) Text("No wired connection on this machine.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        wired.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.str("connection").ifBlank { e.str("device") }, style = MaterialTheme.typography.bodyLarge)
                    Text(e.str("device") + " · " + e.str("state"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }
    val btOn = bt.value?.optBoolean("powered") == true
    Section("Bluetooth", trailing = { Toggle(btOn, bt.value != null) { on -> act(1500) { Hc.post("/api/bluetooth/power", body("on" to on)) } } }) {
        bt.value?.getJSONArray("devices")?.objs().orEmpty().forEach { dev ->
            val mac = dev.str("mac")
            SwitchRow(dev.str("name"), dev.optBoolean("connected"), if (dev.optBoolean("connected")) "connected" else "paired · not connected", enabled = btOn) { on ->
                act(2500) { Hc.post("/api/bluetooth/$mac/${if (on) "connect" else "disconnect"}") }
            }
        }
        Button(onClick = {
            scope.launch {
                scanning = true
                val r = Hc.getSlow("/api/bluetooth/scan", 30_000)
                scanning = false
                scanned = true
                found = r?.second?.optJSONArray("devices")?.objs().orEmpty()
            }
        }, enabled = !scanning) { Text(if (scanning) "Scanning… 8 s" else "Scan for devices") }
        if (scanned && found.isEmpty() && !scanning) Text("No devices found. Put the device in pairing mode and scan again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        found.forEach { d ->
            val mac = d.str("mac")
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.str("name").ifBlank { mac }, style = MaterialTheme.typography.bodyLarge)
                    Text(mac + if (d.optBoolean("connected")) " · connected" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!d.optBoolean("connected")) OutlinedButton({ act(3000) { Hc.post("/api/bluetooth/$mac/pair") } }) { Text("Pair") }
            }
        }
    }
}

/** A switch for a card's title row: shows the change at once and falls back to the machine's real state a few seconds later. */
@Composable
private fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    var local by remember { mutableStateOf(checked) }
    var touched by remember { mutableIntStateOf(0) }
    val real by rememberUpdatedState(checked)
    LaunchedEffect(checked) { local = checked }
    LaunchedEffect(touched) { if (touched > 0) { delay(4000); local = real } }
    Switch(local, { local = it; touched++; onChange(it) }, enabled = enabled)
}
