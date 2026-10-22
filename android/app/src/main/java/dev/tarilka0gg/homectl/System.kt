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
import androidx.compose.ui.graphics.asImageBitmap
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

    if (m != null) SecuritySection(m)

    var modesNonce by remember { mutableIntStateOf(0) }
    val modes = loaded(modesNonce, 6000, "sys-modes") { Hc.array("/api/actions") }
    var modeConfirm by remember { mutableStateOf<Triple<JSONObject, Boolean, String>?>(null) }
    var modeResult by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var modeBusy by remember { mutableStateOf(setOf<String>()) }
    fun runMode(a: JSONObject, on: Boolean) {
        val id = a.str("id")
        modeBusy = modeBusy + id
        scope.launch {
            val (code, j) = Hc.cur?.privPost("/api/actions/$id/${if (on) "on" else "off"}", JSONObject(), 200_000) ?: (0 to null)
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
            confirmButton = { TextButton({ scope.launch { Hc.cur?.privPost("/api/power/$action") }; confirm = null }) { Text(label) } },
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
                        scope.launch { Hc.cur?.privPost("/api/services/$n/${if (on) "start" else "stop"}"); delay(800); nonce++ }
                    }
                }
                if (running) TextButton({ scope.launch { Hc.cur?.privPost("/api/services/$n/restart"); delay(800); nonce++ } }) { Text("Restart") }
            }
        } ?: Text("Loading…")
    }

    // ---- clipboard: phone <-> machine, with a visible result for every action ----
    var clip by remember { mutableStateOf("") }
    var machineClip by remember { mutableStateOf<String?>(null) }
    var machineImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var machineImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var machineClipType by remember { mutableStateOf("text") }
    var clipNote by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun phoneClip(): String = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    fun say(ok: Boolean, text: String) { clipNote = ok to text; Toast.makeText(ctx, (if (ok) "✓ " else "✗ ") + text, Toast.LENGTH_SHORT).show() }
    fun chars(t: String) = "${t.length} character${if (t.length == 1) "" else "s"}"
    suspend fun sendText(t: String) {
        if (t.isBlank()) return say(false, "Nothing to send — the text is empty")
        busy = true
        val ok = Hc.post("/api/clipboard", body("text" to t))
        busy = false
        say(ok, if (ok) "Sent ${chars(t)} to ${m?.name ?: "the machine"}'s clipboard" else "Couldn't send — is ${m?.name ?: "the machine"} reachable?")
    }
    var sendPicker by remember { mutableStateOf(false) }
    var getPicker by remember { mutableStateOf(false) }
    if (sendPicker) SendPicker(m?.name ?: "machine", onPick = { t -> clip = t; scope.launch { sendText(t); PhoneClips.add(t, "sent") } }, onClose = { sendPicker = false })
    if (getPicker && m != null) GetPicker(m, onPick = { t ->
        machineClip = t; machineClipType = "text"; machineImage = null
        PhoneClips.write(ctx, t); PhoneClips.add(t, "from ${m.name}")
        say(true, "Copied ${chars(t)} from ${m.name} to the phone's clipboard")
    }, onClose = { getPicker = false }, onNote = { ok, text -> say(ok, text) })
    Section("Clipboard") {
        if (m != null) {
            val common = ClipSync.isOn(m.id)
            SwitchRow("Common clipboard with ${m.name}", common, ClipSync.note?.takeIf { common } ?: "copy on one, paste on the other — works while homectl is open") { on ->
                ClipSync.set(m.id, on)
                say(true, if (on) "Common clipboard on — only new copies are shared" else "Common clipboard off")
            }
            HorizontalDivider()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ sendPicker = true }) { Text("Choose what to send…") }
            FilledTonalButton({ getPicker = true }, enabled = m != null) { Text("Choose what to get…") }
        }
        Text("Phone → ${m?.name ?: "machine"}", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(clip, { clip = it }, Modifier.fillMaxWidth(), label = { Text("Text to send") }, minLines = 2, maxLines = 6)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                val t = phoneClip()
                if (t.isEmpty()) say(false, "The phone's clipboard is empty") else { clip = t; PhoneClips.add(t, "copied on phone"); say(true, "Pasted ${chars(t)} from the phone's clipboard") }
            }) { Text("Paste from phone") }
            FilledTonalButton({ scope.launch { sendText(clip) } }, enabled = !busy) { Text("Send text") }
            Button({
                val t = phoneClip()
                if (t.isEmpty()) say(false, "The phone's clipboard is empty") else { clip = t; scope.launch { sendText(t) } }
            }, enabled = !busy) { Text("Send phone clipboard") }
        }
        HorizontalDivider()
        Text("${m?.name ?: "Machine"} → phone", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({
                scope.launch {
                    busy = true
                    val j = Hc.json("/api/clipboard")
                    busy = false
                    machineImage = null; machineImageBytes = null
                    if (j == null) say(false, "Couldn't read the machine's clipboard")
                    else {
                        val type = j.optString("type", "text")
                        machineClipType = type
                        val t = j.str("text")
                        machineClip = t
                        when {
                            type.startsWith("image/") -> {
                                val bytes = Hc.cur?.bytes("/api/clipboard/image", 32_000_000)
                                val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                                if (bmp != null) { machineImage = bmp.asImageBitmap(); machineImageBytes = bytes; say(true, "The machine's clipboard holds an image (${bmp.width}×${bmp.height}, ${fmtBytes(bytes.size.toDouble())})") }
                                else say(false, "The machine's clipboard holds an image the phone can't show")
                            }
                            type == "empty" -> say(true, "The machine's clipboard is empty")
                            type == "other" -> say(false, "The machine's clipboard holds something that isn't text or an image")
                            else -> say(true, if (t.isEmpty()) "The machine's clipboard is empty" else "Got ${chars(t)} from the machine")
                        }
                    }
                }
            }, enabled = !busy) { Text("Get from machine") }
            OutlinedButton({
                val t = machineClip.orEmpty()
                if (t.isEmpty()) say(false, "Get the machine's clipboard first")
                else { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("homectl", t)); say(true, "Copied ${chars(t)} to the phone's clipboard") }
            }, enabled = machineClipType == "text" && !machineClip.isNullOrEmpty()) { Text("Copy to phone") }
            OutlinedButton({ machineClip?.let { clip = it; say(true, "Moved it into the text box") } }, enabled = machineClipType == "text" && !machineClip.isNullOrEmpty()) { Text("Edit it") }
        }
        machineImage?.let { img ->
            androidx.compose.foundation.Image(img, "Machine clipboard image", Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            OutlinedButton({
                val bytes = machineImageBytes ?: return@OutlinedButton
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "clipboard-${System.currentTimeMillis()}.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, machineClipType)
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/homectl")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; say(true, "Saved the image to Pictures/homectl") } else say(false, "Couldn't save the image")
            }) { Text("Save image to phone") }
        }
        if (machineImage == null) machineClip?.let { t ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(if (t.isEmpty()) "(empty)" else t.take(2000) + if (t.length > 2000) "…" else "", Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 8)
                }
            }
        }
        clipNote?.let { (ok, t) -> Text((if (ok) "✓ " else "✗ ") + t, style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
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


/** Saved root password, encrypted-link status and the "encrypted only" switch for the selected machine. */
@Composable
private fun SecuritySection(m: Machine) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var nonce by remember { mutableIntStateOf(0) }
    val sec = loaded(nonce to m.id, 0, "sec-${m.id}") { Hc.json("/api/security") }
    val saved = remember(nonce, m.id) { Hc.hasSavedPassword(m) }
    var testing by remember { mutableStateOf(false) }
    Section("Security — ${m.name}") {
        val s = sec.value
        val pinned = m.fp != null
        Text(
            when {
                m.certProblem -> "⚠ This machine's certificate does not match the one pinned when you paired. The app refuses to connect — someone may be intercepting the link, or the machine's certificate was replaced (then pair again)."
                s == null -> "Checking…"
                pinned && s.optBoolean("tls") -> "🔒 Encrypted link, certificate pinned: ${m.fp!!.take(8).chunked(4).joinToString(" ")}… (SHA-256)"
                s.optBoolean("tls") -> "This machine can encrypt links, but this pairing has no pinned certificate. Pair again to pin it."
                else -> "This machine is running an older homectl without encrypted links."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (m.certProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (s != null && s.optBoolean("tls")) {
            SwitchRow("Refuse plain connections", s.optBoolean("tls_only"), "from other devices on the network; this machine itself and the Tailscale network (already encrypted) are exempt") { on ->
                scope.launch { val ok = Hc.post("/api/security", body("tls_only" to on)); if (!ok) Toast.makeText(ctx, "Couldn't change it", Toast.LENGTH_SHORT).show(); nonce++ }
            }
        }
        var block by remember { mutableStateOf(Hc.memBool("block.screenshots")) }
        SwitchRow("Hide the app from screenshots", block, "also blanks the app-switcher preview; applies to this phone") { on ->
            block = on; Hc.memPutBool("block.screenshots", on); (Hc.activity as? MainActivity)?.applyScreenshotBlock()
        }
        HorizontalDivider()
        Text("Root password (doas)", style = MaterialTheme.typography.titleSmall)
        Text(
            if (saved) "Saved in this phone's keyring. It is encrypted with a key that needs your fingerprint, face or screen lock, and is sent only with the one action that needs it."
            else "Not saved. Actions that need root (power modes, services, restart) will ask for it when needed — you can save it then.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                testing = true
                scope.launch {
                    val (code, j) = m.privPost("/api/priv/test", JSONObject(), 30_000)
                    testing = false
                    nonce++ // the card shows whether a password is now saved
                    Toast.makeText(ctx, when { j?.optBoolean("ok") == true -> "Root access works"; code == 0 -> "Cancelled or no answer"; j?.optBoolean("wrong_password") == true -> "Wrong password"; else -> "Not allowed" }, Toast.LENGTH_SHORT).show()
                }
            }, enabled = !testing) { Text("Test root access") }
            if (saved) OutlinedButton({ Hc.forgetPassword(m); nonce++ }) { Text("Forget password") }
        }
    }
}
