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

    Section("App") {
        val appVersion = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("?") }
        Text("homectl app $appVersion" + (m?.daemonVersion?.takeIf { it.isNotBlank() }?.let { " · daemon on ${m.name} $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        TextButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(Diag.text())); android.widget.Toast.makeText(ctx, "Diagnostics copied", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Copy diagnostics") }
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

    TunnelSection()
    if (m != null) Section("Connection") { ConnectionTest(m) }
    if (m != null) SecuritySection(m)

    var nonce by remember { mutableIntStateOf(0) }
    // the list is only replaced when it changed, so a refresh that found nothing new does not redraw every row
    val lastSvc = remember { arrayOfNulls<Pair<String, org.json.JSONArray>>(1) }
    val svc = loaded(nonce, 6000, "sys-svc") {
        Hc.array("/api/services")?.let { a ->
            val t = a.toString()
            lastSvc[0]?.takeIf { it.first == t }?.second ?: a.also { lastSvc[0] = t to a }
        }
    }
    var filter by remember { mutableStateOf("") }
    Section("Services") {
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter") }, singleLine = true)
        svc.value?.objs()?.filter { filter.isBlank() || it.str("name").contains(filter, true) }?.forEach { s ->
            val n = s.str("name")
            val running = s.str("state") == "started"
            key(n) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SwitchRow(n, running, s.str("state")) { on ->
                        scope.launch { Hc.cur?.privPost("/api/services/$n/${if (on) "start" else "stop"}"); delay(800); nonce++ }
                    }
                }
                if (running) TextButton({ scope.launch { Hc.cur?.privPost("/api/services/$n/restart"); delay(800); nonce++ } }) { Text("Restart") }
            } }
        } ?: Text("Loading…")
    }

    // ---- clipboard: one card, two pickers ----
    var clipNote by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var gotImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var gotFile by remember { mutableStateOf<java.io.File?>(null) }
    var sendPicker by remember { mutableStateOf(false) }
    var getPicker by remember { mutableStateOf(false) }
    fun say(ok: Boolean, text: String) { clipNote = ok to text; Toast.makeText(ctx, (if (ok) "✓ " else "✗ ") + text, Toast.LENGTH_SHORT).show() }
    if (sendPicker && m != null) SendPicker(m, onResult = { ok, text -> say(ok, text) }, onClose = { sendPicker = false })
    if (getPicker && m != null) GetPicker(
        m,
        onGotText = { t ->
            gotImage = null; gotFile = null
            PhoneClips.writeText(ctx, t); PhoneClips.addText(t, "from ${m.name}")
            say(true, "Copied ${t.length} characters from ${m.name} to the phone's clipboard")
        },
        onGotImage = { f, mime ->
            PhoneClips.writeImage(ctx, f)
            PhoneClips.add(PhoneClip(null, f, mime, System.currentTimeMillis(), "from ${m.name}"))
            gotFile = f
            gotImage = android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap()
            say(true, "Copied the picture from ${m.name} to the phone's clipboard (${fmtBytes(f.length().toDouble())})")
        },
        onClose = { getPicker = false }, onNote = { ok, text -> say(ok, text) },
    )
    Section("Clipboard") {
        if (m != null) {
            val common = ClipSync.isOn(m.id)
            val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            SwitchRow("Common clipboard with ${m.name}", common, ClipSync.note?.takeIf { common } ?: "runs in the background with a notification, even when the app is closed") { on ->
                ClipSync.set(m.id, on)
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    ClipService.ensure(ctx)
                }
                say(true, if (on) "Common clipboard on — what you copy on ${m.name} lands on the phone; use the notification's Send clipboard (or the quick-settings tile) to push the phone's" else "Common clipboard off")
            }
            if (common) Text("Android doesn't let a background app read the phone's clipboard, so to send what you copied on the phone while homectl is closed, tap Send clipboard in the notification. Add the \"Send clipboard\" tile to quick settings for a one-swipe version.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ sendPicker = true }, enabled = m != null) { Text("Send to ${m?.name ?: "machine"}…") }
            FilledTonalButton({ getPicker = true }, enabled = m != null) { Text("Get from ${m?.name ?: "machine"}…") }
        }
        clipNote?.let { (ok, t) -> Text((if (ok) "✓ " else "✗ ") + t, style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        gotImage?.let { img ->
            androidx.compose.foundation.Image(img, "Picture from the machine", Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            OutlinedButton({
                val f = gotFile ?: return@OutlinedButton
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "clipboard-${System.currentTimeMillis()}.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, if (f.name.endsWith("jpg")) "image/jpeg" else "image/png")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/homectl")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }; say(true, "Saved the picture to Pictures/homectl") } else say(false, "Couldn't save the picture")
            }) { Text("Save picture to the gallery") }
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
        // ---- this phone's identity ----
        val devices = loaded(nonce to m.id, 0, "dev-${m.id}") { Hc.array("/api/devices") }
        val registered = devices.value?.objs().orEmpty().any { it.str("id") == DeviceKey.idOrNull() }
        Text("This phone", style = MaterialTheme.typography.titleSmall)
        Text("Fingerprint (SHA-256): ${(runCatching { DeviceKey.fingerprintPretty() }.getOrDefault("unavailable right now")).chunked(20).joinToString("\n")}", style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Text(
            when {
                devices.value == null -> "Checking whether ${m.name} knows this phone…"
                registered -> "✓ Registered with ${m.name}. Through the public tunnel it only accepts requests signed by this phone's hardware key, so a leaked access token alone is useless."
                else -> "✗ Not registered with ${m.name} yet. It is registered automatically the next time you are on the home network; until then the tunnel refuses this phone."
            },
            style = MaterialTheme.typography.bodySmall, color = if (registered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val always = sec.value?.optBoolean("device_always") == true
        if (registered) SwitchRow("Require this phone on the home network too", always, "turns the signature check on for LAN and Tailscale as well; the machine itself is exempt") { on ->
            scope.launch {
                val ok = Hc.post("/api/security", body("device_always" to on))
                Toast.makeText(ctx, if (ok) (if (on) "Now required everywhere" else "Only required through the tunnel") else "Couldn't change it — do it on the home network", Toast.LENGTH_LONG).show()
                nonce++
            }
        }
        val others = devices.value?.objs().orEmpty().filter { it.str("id") != DeviceKey.idOrNull() }
        if (others.isNotEmpty()) {
            Text("Other registered phones", style = MaterialTheme.typography.labelMedium)
            others.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.str("name"), style = MaterialTheme.typography.bodyMedium)
                        Text(d.str("fingerprint").take(16).uppercase().chunked(4).joinToString(" ") + "…  ·  added " + fmtDate(d.optLong("added")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ scope.launch { val ok = m.call("DELETE", "/api/devices/${d.str("id")}") != null; Toast.makeText(ctx, if (ok) "Removed ${d.str("name")}" else "Couldn't remove it — do it on the home network", Toast.LENGTH_LONG).show(); nonce++ } }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        HorizontalDivider()
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


/** The public tunnel of the selected machine: a way in from outside the home network. */
@Composable
private fun TunnelSection() {
    val scope = rememberCoroutineScope()
    var nonce by remember { mutableIntStateOf(0) }
    val tunnel = loaded(nonce, 6000, "ctl-tunnel") { Hc.json("/api/tunnel") }
    tunnel.value?.let { t ->
        Section("Remote access") {
            SwitchRow("Public tunnel", t.optBoolean("on"), if (!t.optBoolean("available")) "cloudflared is not installed on this machine" else t.str("url").ifBlank { if (t.optBoolean("auto") && t.opt("hub_up") == true) "off — your server reaches this machine; it starts by itself if the server stops answering" else "off" }, enabled = t.optBoolean("available")) { on ->
                scope.launch { Hc.post("/api/tunnel", body("on" to on)); delay(3000); nonce++ }
            }
        }
    }
}
