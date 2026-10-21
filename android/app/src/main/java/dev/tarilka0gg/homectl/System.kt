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
    val svc = loaded(nonce, 6000) { Hc.array("/api/services") }
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
