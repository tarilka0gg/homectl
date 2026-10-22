@file:OptIn(ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.json.JSONObject

@Composable
fun SystemScreen(onAddMachine: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = Hc.cur
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }

    Section("Settings") {
        Text("Update speed", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(500 to "Fast 0.5 s", 1000 to "Normal 1 s", 2000 to "Relaxed 2 s", 5000 to "Slow 5 s").forEach { (ms, label) ->
                FilterChip(Hc.speedMs == ms, { Hc.setSpeed(ms) }, label = { Text(label) })
            }
        }
        Text(
            "How often the Monitor refreshes and the machines take new samples. Faster looks smoother but uses more battery, data and CPU on the machine; when the app isn't open, machines sample every 5 s.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (m != null && Hc.machines.size > 1) {
        val hubs = Hc.machines.filter { it.id != m.id }
        Section("Reach ${m.name} through a server") {
            Text(
                "The app talks only to the server, and the server asks ${m.name} for everything (monitor, files, terminal). Works from anywhere the server is reachable; ${m.name} just has to be on while you use it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hubs.forEach { h ->
                val linked = Hc.relayedVia(m, h)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.bodyLarge)
                        Text(if (linked) "linked" else "not linked", style = MaterialTheme.typography.bodySmall)
                    }
                    if (linked) OutlinedButton({ scope.launch { Hc.unlinkViaServer(m, h) } }) { Text("Unlink") }
                    else FilledTonalButton({
                        scope.launch {
                            val ok = Hc.linkViaServer(m, h)
                            Toast.makeText(ctx, if (ok) "${h.name} now relays to ${m.name}" else "Couldn't link — is ${h.name} updated and reachable, and does ${m.name} have a Tailscale or LAN address?", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Link") }
                }
            }
        }
    }

    var modesNonce by remember { mutableIntStateOf(0) }
    val modes = loaded(modesNonce, 6000, "sys-modes") { Hc.array("/api/actions") }
    var modeConfirm by remember { mutableStateOf<Triple<JSONObject, Boolean, String>?>(null) }
    var modeResult by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var modeBusy by remember { mutableStateOf(setOf<String>()) }
    fun runMode(a: JSONObject, on: Boolean) {
        val id = a.str("id")
        modeBusy = modeBusy + id
        scope.launch {
            val (code, j) = Hc.cur?.postSlow("/api/actions/$id/${if (on) "on" else "off"}", JSONObject(), 200_000) ?: (0 to null)
            modeBusy = modeBusy - id
            modesNonce++
            when {
                code == 200 && j?.optBoolean("ok") == true -> Toast.makeText(ctx, "${a.str("title")}: ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
                j != null -> modeResult = a.str("title") to j
                else -> Toast.makeText(ctx, "Couldn't reach the machine", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun requestMode(a: JSONObject, on: Boolean) {
        val warn = (if (on) a.str("confirm_on") else a.str("confirm_off")).trim()
        if (warn.isNotEmpty()) modeConfirm = Triple(a, on, warn) else runMode(a, on)
    }
    val modeList = modes.value?.objs().orEmpty()
    if (modeList.isNotEmpty()) Section("Power modes") {
        modeList.forEach { a ->
            val id = a.str("id")
            val busy = id in modeBusy || a.optBoolean("running")
            val sub = listOf(a.str("desc"), if (a.optBoolean("assumed")) "state assumed from last use" else "").filter { it.isNotBlank() }.joinToString("\n")
            if (!a.isNull("state")) {
                SwitchRow(a.str("title"), a.optBoolean("state"), sub.ifBlank { null }, enabled = !busy) { on -> requestMode(a, on) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.str("title"), style = MaterialTheme.typography.bodyLarge)
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton({ requestMode(a, true) }, enabled = !busy) { Text("Turn on") }
                        OutlinedButton({ requestMode(a, false) }, enabled = !busy) { Text("Turn off") }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text("Scripts named <name>-on.sh / <name>-off.sh in your home, ~/.local/bin or /usr/local/bin show up here automatically; they run through doas. More can be defined in ~/.config/homectl/actions.json.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    modeConfirm?.let { (a, on, warn) ->
        AlertDialog(
            onDismissRequest = { modeConfirm = null },
            title = { Text("${if (on) "Turn on" else "Turn off"} ${a.str("title")}?") },
            text = { Text(warn) },
            confirmButton = { TextButton({ modeConfirm = null; runMode(a, on) }) { Text(if (on) "Turn on" else "Turn off") } },
            dismissButton = { TextButton({ modeConfirm = null }) { Text("Cancel") } },
        )
    }
    modeResult?.let { (title, j) ->
        AlertDialog(
            onDismissRequest = { modeResult = null },
            title = { Text("$title failed") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (j.str("hint").isNotBlank()) Text(j.str("hint"), style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    val o = j.str("output").trim().ifBlank { j.str("error") }
                    if (o.isNotBlank()) Text(o, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text("exit code ${j.optInt("code")}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton({ modeResult = null }) { Text("Close") } },
            dismissButton = {
                if (j.str("hint").isNotBlank()) TextButton({
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("doas rule", j.str("hint").substringAfter('\n')))
                    Toast.makeText(ctx, "Rule copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy rule") }
            },
        )
    }

    Section("Power — ${m?.name ?: ""}") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ scope.launch { Hc.post("/api/power/lock") } }) { Text("Lock") }
            FilledTonalButton({ confirm = "suspend" to "Suspend" }) { Text("Suspend") }
            FilledTonalButton({ confirm = "reboot" to "Restart" }) { Text("Restart") }
            Button({ confirm = "poweroff" to "Power off" }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Power off") }
        }
    }
    confirm?.let { (action, label) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("$label ${m?.name}?") },
            text = { Text(if (action == "poweroff") "You can't turn it back on from here." else "The machine will be unreachable for a moment.") },
            confirmButton = { TextButton({ scope.launch { Hc.post("/api/power/$action") }; confirm = null }) { Text(label) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }

    var nonce by remember { mutableIntStateOf(0) }
    val svc = loaded(nonce, 6000, "sys-svc") { Hc.array("/api/services") }
    var filter by remember { mutableStateOf("") }
    Section("Services") {
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter") }, singleLine = true)
        svc.value?.objs()?.filter { filter.isBlank() || it.str("name").contains(filter, true) }?.forEach { s ->
            val n = s.str("name")
            val running = s.str("state") == "started"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SwitchRow(n, running, s.str("state")) { on ->
                        scope.launch { Hc.post("/api/services/$n/${if (on) "start" else "stop"}"); delay(800); nonce++ }
                    }
                }
                if (running) TextButton({ scope.launch { Hc.post("/api/services/$n/restart"); delay(800); nonce++ } }) { Text("Restart") }
            }
        } ?: Text("Loading…")
    }

    var clip by remember { mutableStateOf("") }
    Section("Clipboard") {
        OutlinedTextField(clip, { clip = it }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ scope.launch { Hc.post("/api/clipboard", body("text" to clip)) } }) { Text("Send to machine") }
            OutlinedButton({ scope.launch { clip = Hc.json("/api/clipboard")?.str("text").orEmpty() } }) { Text("Get from machine") }
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
        Text("Files land in ~/homectl-inbox. You can also use Share → homectl from any app.", style = MaterialTheme.typography.bodyMedium)
        Button({ picker.launch("*/*") }) { Text("Choose files") }
    }

    Section("Machines") {
        Hc.machines.forEach { mm ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(mm.name, style = MaterialTheme.typography.bodyLarge)
                    Text(if (mm.offline) "offline" else mm.active?.let { "${it.kind} · ${it.url}" } ?: "not checked", style = MaterialTheme.typography.bodySmall,
                        color = if (mm.offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ Hc.remove(mm) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
        Button(onAddMachine) { Text("Add machine") }
    }
}
