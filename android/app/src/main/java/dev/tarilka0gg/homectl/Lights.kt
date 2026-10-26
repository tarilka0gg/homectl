package dev.tarilka0gg.homectl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

private val presets = listOf(
    "Warm" to Triple(255, 244, 229),
    "Cool" to Triple(201, 226, 255),
    "Red" to Triple(255, 0, 0),
    "Green" to Triple(0, 255, 0),
    "Blue" to Triple(0, 0, 255),
    "Purple" to Triple(160, 0, 255),
)

@Composable
fun LightsScreen() {
    var nonce by remember { mutableIntStateOf(0) }
    var rescan by remember { mutableIntStateOf(0) }
    val lights = loaded(nonce to rescan, 5000) { Hc.array(if (rescan > 0) "/api/lights?rescan=1" else "/api/lights") }
    val scope = rememberCoroutineScope()
    fun act(block: suspend () -> Unit) = scope.launch { block(); delay(700); nonce++ }
    // null for a daemon older than the cloud feature: the section is simply not shown
    val cloud = loaded(nonce, 15000) { Hc.json("/api/govee") }

    val list = lights.value?.objs().orEmpty()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Lights", style = MaterialTheme.typography.titleLarge)
        IconButton({ rescan++ }) { Icon(Icons.Default.Refresh, "Rescan") }
    }
    if (lights.value == null) Text("Scanning…") else if (list.isEmpty()) {
        Text("None found. Enable \"LAN Control\" for each light in the Govee app. Lights that have no LAN Control (some bulbs, e.g. H6009) can be added with a Govee API key at the bottom of this page.", style = MaterialTheme.typography.bodyMedium)
    }
    if (list.size > 1) Section("All lights") {
        val anyOn = list.any { it.optBoolean("on") }
        SwitchRow("Everything", anyOn, "${list.count { it.optBoolean("on") }} of ${list.size} on") { on ->
            act { list.forEach { Hc.post("/api/lights/${it.str("ip")}", body("on" to on)) } }
        }
        SliderRow("Brightness — all lights", list.map { it.optInt("brightness", 100) }.average().toFloat(), 100f, 1f, enabled = anyOn) { b ->
            act { list.forEach { Hc.post("/api/lights/${it.str("ip")}", body("brightness" to b)) } }
        }
        val first = list.first().optJSONArray("rgb")?.let { a -> Triple(a.optInt(0), a.optInt(1), a.optInt(2)) }
        val same = list.all { it.optJSONArray("rgb")?.let { a -> Triple(a.optInt(0), a.optInt(1), a.optInt(2)) } == first }
        Text("One colour for every light", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            ColorWheel(if (same) first else null, enabled = true) { c ->
                act { list.forEach { Hc.post("/api/lights/${it.str("ip")}", body("on" to true, "rgb" to JSONArray(listOf(c.first, c.second, c.third)))) } }
            }
        }
        SliderRow("White temperature (K) — all lights", 4000f, 9000f, 2000f) { k -> act { list.forEach { Hc.post("/api/lights/${it.str("ip")}", body("on" to true, "kelvin" to k)) } } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            presets.forEach { (_, c) ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c.first, c.second, c.third)).clickable {
                    act { list.forEach { Hc.post("/api/lights/${it.str("ip")}", body("on" to true, "rgb" to JSONArray(listOf(c.first, c.second, c.third)))) } }
                })
            }
        }
    }
    list.forEachIndexed { i, l ->
        val ip = l.str("ip")
        val on = l.optBoolean("on")
        val rgb = l.optJSONArray("rgb")?.let { a -> Triple(a.optInt(0), a.optInt(1), a.optInt(2)) }
        val viaCloud = l.optString("via") == "cloud"
        val title = if (viaCloud) (l.optString("name").ifBlank { "Light ${i + 1}" } + "  ·  ${l.str("sku")}  ·  cloud") else "Light ${i + 1}  ·  ${l.str("sku")}"
        Section(title, trailing = {
            rgb?.let { Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) Color(it.first, it.second, it.third) else Color.Gray)) }
        }) {
            SwitchRow(if (on) "On" else "Off", on, if (!viaCloud) ip else if (l.has("online") && !l.optBoolean("online")) "via the Govee cloud — the light is offline" else "via the Govee cloud") { v -> act { Hc.post("/api/lights/$ip", body("on" to v)) } }
            SliderRow("Brightness", l.optInt("brightness", 100).toFloat(), 100f, 1f, enabled = on) { b -> act { Hc.post("/api/lights/$ip", body("brightness" to b)) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ColorWheel(rgb, enabled = on) { c -> act { Hc.post("/api/lights/$ip", body("on" to true, "rgb" to JSONArray(listOf(c.first, c.second, c.third)))) } }
            }
            Text("Pick a colour on the wheel — the centre is white, the edge the most vivid.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SliderRow("White temperature (K)", (l.optInt("kelvin", 4000).takeIf { it in 2000..9000 } ?: 4000).toFloat(), 9000f, 2000f, enabled = on) { k -> act { Hc.post("/api/lights/$ip", body("on" to true, "kelvin" to k)) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                presets.forEach { (_, c) ->
                    val selected = rgb == c
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(c.first, c.second, c.third))
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                            .clickable { act { Hc.post("/api/lights/$ip", body("on" to true, "rgb" to JSONArray(listOf(c.first, c.second, c.third)))) } },
                    )
                }
            }
        }
    }
    cloud.value?.let { c -> GoveeCloudSection(c.optBoolean("cloud")) { nonce++ } }
}

/** Govee cloud control for lights that never answer on the LAN: the API key is kept on the machine, never shown again. */
@Composable
private fun GoveeCloudSection(on: Boolean, changed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var bad by remember { mutableStateOf(false) }
    Section("Govee cloud") {
        if (on) {
            Text("On. Lights the LAN scan cannot find are controlled through Govee's servers (needs internet; Govee allows about 10 commands a minute per light).", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton({ scope.launch { Hc.cur?.delete("/api/govee"); changed() } }) { Text("Remove the API key") }
        } else {
            Text("For lights without LAN Control, such as some bulbs (H6009). In the Govee Home app open Profile → Settings → Apply for API Key; the key arrives by email. It is stored on this machine only, and only ever sent to Govee.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(key, { key = it.trim(); bad = false }, Modifier.fillMaxWidth(), label = { Text("Govee API key") }, singleLine = true, isError = bad,
                visualTransformation = PasswordVisualTransformation(), supportingText = if (bad) ({ Text("That key was refused") }) else null)
            Button({
                busy = true
                scope.launch { bad = !Hc.post("/api/govee", body("key" to key)); busy = false; if (!bad) { key = ""; changed() } }
            }, enabled = key.length >= 8 && !busy) { Text(if (busy) "Saving…" else "Save key") }
        }
    }
}
