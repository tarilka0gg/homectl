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

    val mixer = loaded(nonce, 4000, "ctl-mixer") { Hc.json("/api/mixer") }

    var showHidden by remember { mutableStateOf(false) }
    for ((kind, devKey, title) in listOf(Triple("sink", "sinks", "Sound output"), Triple("source", "sources", "Sound input"))) {
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
    Section("Sound — apps playing") {
        if (streams.isEmpty()) Text("Nothing is playing.", style = MaterialTheme.typography.bodyMedium)
        streams.forEach { s -> AppStream(s, "stream", ::act) }
    }
    if (recording.isNotEmpty()) Section("Sound — apps recording") {
        recording.forEach { s -> AppStream(s, "recording", ::act) }
    }

    WebcamSection()
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
