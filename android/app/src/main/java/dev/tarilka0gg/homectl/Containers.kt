@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val actionNames = mapOf("start" to "Start", "reboot" to "Restart", "shutdown" to "Shut down")

/** Starting, restarting or shutting down can take a while (a container shutting down waits for its services). */
private suspend fun containerAction(m: Machine, path: String): Boolean = m.postSlow(path, JSONObject(), 180_000).first in 200..299

/** Top of a container's Monitor: is it running, and its power buttons. */
@Composable
fun ContainerCard() {
    val m = Hc.cur ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember(m.id) { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(m.id, round) { while (true) { m.json("/api/container")?.let { info = it }; delay(10_000) } }
    val running = info?.optBoolean("running")
    fun act(a: String) = scope.launch {
        busy = a
        val ok = containerAction(m, "/api/container/$a")
        busy = null
        round++
        Toast.makeText(ctx, if (ok) "${actionNames[a]}: done" else "${actionNames[a]} failed", Toast.LENGTH_SHORT).show()
    }
    Section("Container", trailing = { Text(busy?.let { "${actionNames[it]}…" } ?: when (running) { true -> "running"; false -> "stopped"; null -> "…" }, style = MaterialTheme.typography.labelLarge) }) {
        // opened by the host's owner through the host, or a phone invited to this one container
        val viaHost = m.endpoints.any { it.hub != null && it.url.contains("/peer/ct-") }
        Text(if (viaHost) "Opened through its host, as the host's owner." else "This phone has this container only. The host it runs on, and the other containers, are out of its reach.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running == false) FilledTonalButton({ act("start") }, enabled = busy == null) { Text("Start") }
            if (running == true) {
                FilledTonalButton({ confirm = "reboot" }, enabled = busy == null) { Text("Restart") }
                OutlinedButton({ confirm = "shutdown" }, enabled = busy == null) { Text("Shut down") }
            }
        }
    }
    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${actionNames[a]} ${m.name}?") },
            text = { Text(if (a == "shutdown") "Everything running in the container stops. It can be started again from here." else "Everything running in the container restarts.") },
            confirmButton = { TextButton({ confirm = null; act(a) }) { Text(actionNames[a]!!) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

/** On a host in host mode: its containers, inviting a phone to one, and (if the owner wants it) opening them from this phone. */
@Composable
fun ContainersSection() {
    val m = Hc.cur ?: return
    if (m.container) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember(m.id) { mutableStateOf<JSONObject?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var invite by remember { mutableStateOf<Pair<String, String>?>(null) }
    var ask by remember { mutableStateOf<Pair<JSONObject, String>?>(null) }
    LaunchedEffect(m.id, round, m.active) { data = m.probeJson("/api/containers") }
    val d = data ?: return // not a container host
    val list = d.optJSONArray("containers")?.objs().orEmpty()
    val owner = d.optBoolean("owner_access")
    fun act(c: JSONObject, a: String) = scope.launch {
        busy = c.getString("id")
        val ok = containerAction(m, "/api/containers/${c.getString("id")}/$a")
        busy = null
        round++
        Toast.makeText(ctx, if (ok) "${c.optString("name")}: ${(actionNames[a] ?: "done").lowercase()}" else "Failed", Toast.LENGTH_SHORT).show()
    }
    Section("Containers") {
        SwitchRow("Open containers from this phone", owner,
            sub = if (owner) "You can open every container here, as its root." else "Off: only the phones invited to a container can see inside it. Changed only on the home network.") { v ->
            scope.launch { if (m.post("/api/containers/owner", body("on" to v))) round++ else Toast.makeText(ctx, "Only from the home network", Toast.LENGTH_SHORT).show() }
        }
        if (list.isEmpty()) Text("No containers found.", style = MaterialTheme.typography.bodySmall)
        list.forEach { c ->
            val id = c.getString("id")
            val running = c.optBoolean("running")
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.optString("name"), style = MaterialTheme.typography.bodyLarge)
                        Text("$id · ${if (busy == id) "working…" else if (running) "running" else "stopped"}${if (c.optBoolean("shared")) " · shared with phones" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (owner && running) TextButton({ openContainer(m, c) }) { Text("Open") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!running) FilledTonalButton({ act(c, "start") }, enabled = busy == null) { Text("Start") }
                    else {
                        OutlinedButton({ ask = c to "reboot" }, enabled = busy == null) { Text("Restart") }
                        OutlinedButton({ ask = c to "shutdown" }, enabled = busy == null) { Text("Shut down") }
                    }
                    FilledTonalButton({
                        scope.launch {
                            val r = m.call("POST", "/api/containers/$id/invite")?.let { runCatching { JSONObject(it) }.getOrNull() }
                            if (r == null) Toast.makeText(ctx, "Invites are made only on the home network", Toast.LENGTH_SHORT).show()
                            else invite = c.optString("name") to r.getString("code")
                        }
                    }) { Text("Invite a phone") }
                    if (c.optBoolean("shared")) TextButton({ ask = c to "reset" }) { Text("Remove phones", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    invite?.let { (name, code) ->
        AlertDialog(
            onDismissRequest = { invite = null },
            title = { Text("Invite a phone to $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
                    Text("On their phone: Add machine, pick ${m.name} (or type its address), and enter this code. It works once, for 15 minutes, and gives that phone this container only.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton({ invite = null }) { Text("Done") } },
        )
    }
    ask?.let { (c, a) ->
        val reset = a == "reset"
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(if (reset) "Remove the phones of ${c.optString("name")}?" else "${actionNames[a]} ${c.optString("name")}?") },
            text = { Text(if (reset) "Every phone invited to this container loses it. They need a new invite to get it back." else "Everything running in the container ${if (a == "shutdown") "stops" else "restarts"}.") },
            confirmButton = { TextButton({ ask = null; act(c, a) }) { Text(if (reset) "Remove" else actionNames[a]!!) } },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel") } },
        )
    }
}

/** Adds the container as a machine reached through its host (like a machine reached through a server), and selects it. */
private fun openContainer(host: Machine, c: JSONObject) {
    val hubUrl = host.active?.takeIf { it.kind != "server" }?.url ?: return
    val cid = c.getString("id")
    val id = "${host.id}-$cid"
    val existing = Hc.machines.find { it.id == id }
    if (existing != null) return Hc.select(existing)
    Hc.add(Machine(id, c.optString("name"), host.token, listOf(Endpoint("server", "$hubUrl/peer/ct-$cid", host.token, host.id)), container = true))
}
