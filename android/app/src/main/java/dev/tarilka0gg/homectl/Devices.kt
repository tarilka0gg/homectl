@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Power and screens of the selected machine, the machines themselves (power, wake up), the lamps, the rest of the tailnet. */
@Composable
fun DevicesScreen(onAddMachine: () -> Unit) {
    PowerSection()
    ScreensSection()
    MachinesSection(onAddMachine)
    ContainersSection()
    LightsScreen()
    TailscaleSection()
}

/** One frame of the webcam, taken only when the person asks for it (the camera is not on until then). */
@Composable
fun WebcamSection() {
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    suspend fun grab() {
        busy = true
        val jpg = Hc.bytes("/api/webcam/0/frame")
        busy = false
        val bmp = jpg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp == null) { status = "No webcam frame from this machine."; frame = null; live = false } else { status = ""; frame = bmp.asImageBitmap() }
    }
    fun take() = scope.launch { grab() }
    // live: the machine sends a motion-JPEG stream (about 15 frames a second) that is shown as it arrives; leaving it closes the stream
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        status = ""
        val ok = Hc.cur?.stream("/api/webcam/0/stream") { input, _ ->
            readMjpeg(input, { live }) { bmp -> frame = bmp.asImageBitmap() }
        }
        if (ok == null && live) status = "Couldn't start the live view — this machine may need the newer homectl daemon."
        live = false
    }
    var nonce by remember { mutableIntStateOf(0) }
    val cam = loaded(nonce, 10000) { Hc.array("/api/webcam/0") }
    Section("Webcam", trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ live = !live }) { Text(if (live) "Stop live" else "Live") }
            TextButton({ if (!live) take() }, enabled = !busy && !live) { Text(if (frame == null) "Show" else "Refresh") }
        }
    }) {
        frame?.let { Image(it, "Webcam preview", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { full = true }, contentScale = ContentScale.Fit) }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (frame == null && status.isEmpty()) Text("Show takes one picture; Live is a real video stream (about 15 frames a second).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cam.value?.objs()?.takeIf { it.isNotEmpty() }?.let { controls ->
            HorizontalDivider()
            controls.forEach { c ->
                val id = c.getLong("id")
                if (c.optInt("min") == 0 && c.optInt("max") == 1) {
                    SwitchRow(c.str("name"), c.optInt("value") == 1) { on -> scope.launch { Hc.post("/api/webcam/0", body("id" to id, "value" to if (on) 1 else 0)); delay(500); nonce++ } }
                } else {
                    SliderRow(c.str("name"), c.optInt("value").toFloat(), c.optInt("max").toFloat(), c.optInt("min").toFloat()) { v ->
                        scope.launch { Hc.post("/api/webcam/0", body("id" to id, "value" to v)) }
                    }
                }
            }
        }
    }
    // full screen: the picture fills the height of the phone when held upright, tap to close
    frame?.takeIf { full }?.let { f ->
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { full = false }, contentAlignment = Alignment.Center) {
                Image(f, "Webcam", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

/** The other devices of this person's tailnet, online first. */
@Composable
private fun TailscaleSection() {
    val ts = loaded(Unit, 15000, "dev-tailscale") { Hc.json("/api/tailscale") }
    ts.value?.let { t ->
        Section("Tailscale") {
            if (!t.optBoolean("available")) {
                Text("Tailscale is not installed on this machine.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val peers = t.optJSONArray("peers")?.objs().orEmpty()
                if (peers.isEmpty()) Text("No other devices on this tailnet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                peers.forEach { p ->
                    val on = p.optBoolean("online")
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.str("name").ifBlank { p.str("dns") }, style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(p.str("ip"), p.str("os")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (on) "online" else "offline", style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The screens of the machine: the built-in panel and external monitors, on and off, brightness. */
@Composable
fun ScreensSection() {
    val scope = rememberCoroutineScope()
    var nonce by remember { mutableIntStateOf(0) }
    /** Send a change, give the machine a moment, then re-read so every switch shows the real state. */
    fun act(delayMs: Long = 500, block: suspend () -> Unit) = scope.launch { block(); delay(delayMs); nonce++ }

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
}

/** The machines this phone knows: where each is reached, sleep / restart / power off or wake up, removing one, adding one. */
@Composable
private fun MachinesSection(onAddMachine: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Hc.machines.map { it.offline }) { learnMacs() }
    Section("Machines") {
        Hc.machines.forEachIndexed { i, mm ->
            if (i > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mm.name, style = MaterialTheme.typography.bodyLarge)
                        Text(if (mm.offline) "offline" else mm.active?.let { "${it.kind} · ${it.url}" } ?: "not checked", style = MaterialTheme.typography.bodySmall,
                            color = if (mm.offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ Hc.remove(mm) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                if (!mm.offline && mm.id == Hc.cur?.id) Text("Selected — its power controls are in Power above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (!mm.offline) PowerButtons(mm, withLock = false)
                else {
                    val known = macsOf(mm).isNotEmpty()
                    FilledTonalButton({
                        scope.launch { Toast.makeText(ctx, if (wakeUp(mm)) "Wake-up sent to ${mm.name}" else "Couldn't send it", Toast.LENGTH_SHORT).show() }
                    }, enabled = known) { Text("Wake up") }
                    Text(if (known) "Sends a Wake-on-LAN packet from this phone and from your other machines. Needs Wake-on-LAN enabled in the machine's BIOS and network card, and works over a cable more often than over Wi-Fi."
                        else "The phone learns the machine's network address while it is online; open the app once while it is on.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(onAddMachine) { Text("Add machine") }
    }
}

/** Power of the machine: performance and battery modes (scripts), restart, power off, suspend and lock. */
@Composable
fun PowerSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = Hc.cur
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
    var pNonce by remember { mutableIntStateOf(0) }
    val prof = loaded(pNonce, 6000, "ctl-profile") { Hc.json("/api/profile") }
    Section("Power") {
        prof.value?.takeIf { it.optBoolean("available") }?.let { p ->
            val cur = p.str("current")
            Text("Performance profile", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.getJSONArray("choices").let { a -> (0 until a.length()).map { a.getString(it) } }.forEach { c ->
                    FilterChip(cur == c, {
                        scope.launch {
                            val (code, _) = Hc.cur?.privPost("/api/profile", body("profile" to c), 30_000) ?: (0 to null)
                            if (code != 204) Toast.makeText(ctx, "Couldn't change the profile — it needs root on the machine", Toast.LENGTH_LONG).show()
                            pNonce++
                        }
                    }, label = { Text(when (c) { "low-power" -> "Power saver"; "balanced" -> "Balanced"; "performance" -> "Performance"; "quiet" -> "Quiet"; "balanced-performance" -> "Balanced performance"; else -> c }) })
                }
            }
            HorizontalDivider()
        }
        if (modeList.isEmpty()) Text("This machine has no power scripts yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        modeList.forEach { a ->
            val id = a.str("id")
            val busy = id in modeBusy || a.optBoolean("running")
            if (id == "battery-powersave") {
                // the on script makes the machine battery safe, the off script gives it back its performance
                val battery = a.optBoolean("state")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Power mode", style = MaterialTheme.typography.bodyLarge)
                    Text(if (a.isNull("state")) "not known yet" else if (battery) "Battery safe is on" else "Performance is on", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (if (battery) FilledTonalButton({ requestMode(a, true) }, enabled = !busy) { Text("Battery safe") }
                        else OutlinedButton({ requestMode(a, true) }, enabled = !busy) { Text("Battery safe") })
                        (if (!battery && !a.isNull("state")) FilledTonalButton({ requestMode(a, false) }, enabled = !busy) { Text("Performance") }
                        else OutlinedButton({ requestMode(a, false) }, enabled = !busy) { Text("Performance") })
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                return@forEach
            }
            val sub = listOf(if (a.optBoolean("assumed")) "state assumed from last use" else "").filter { it.isNotBlank() }.joinToString("\n")
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
        if (m != null) { HorizontalDivider(); PowerButtons(m, withLock = true) }
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

}

/** What this phone has learned about a machine's network cards (for waking it), saved per machine. */
private fun macsOf(m: Machine): List<String> = Hc.memStr("mac.${m.id}").split(',').filter { it.length == 17 }

/** Asks every machine that is online and whose cards are not yet known for them; a machine that is off can only be woken with this. */
suspend fun learnMacs() {
    for (m in Hc.machines.toList()) {
        if (m.offline || m.container || macsOf(m).isNotEmpty()) continue
        val a = m.array("/api/macs") ?: continue
        val list = a.objs().sortedBy { it.optBoolean("wireless") }.map { it.str("mac") }
        if (list.isNotEmpty()) Hc.memPutStr("mac.${m.id}", list.joinToString(","))
    }
}

/** Wake-on-LAN: the phone broadcasts the magic packet itself (works on the same Wi-Fi) and asks every other online machine to do it too. */
private suspend fun wakeUp(m: Machine): Boolean {
    val macs = macsOf(m)
    if (macs.isEmpty()) return false
    val viaPhone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            java.net.DatagramSocket().use { sock ->
                sock.broadcast = true
                for (mac in macs) {
                    val b = mac.split(':').map { it.toInt(16).toByte() }.toByteArray()
                    val pkt = ByteArray(6) { 0xff.toByte() } + (0 until 16).flatMap { b.toList() }.toByteArray()
                    for (port in intArrayOf(9, 7)) sock.send(java.net.DatagramPacket(pkt, pkt.size, java.net.InetAddress.getByName("255.255.255.255"), port))
                }
            }
        }.isSuccess
    }
    var viaPeer = false
    for (h in Hc.machines.toList().filter { it.id != m.id && !it.offline && !it.container }) for (mac in macs) if (h.call("POST", "/api/wake", body("mac" to mac)) != null) viaPeer = true
    return viaPeer || viaPhone
}

/** Lock, sleep, restart and power off for one machine, each asking first. */
@Composable
private fun PowerButtons(mm: Machine, withLock: Boolean) {
    val scope = rememberCoroutineScope()
    var confirmPower by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (withLock) FilledTonalButton({ scope.launch { mm.privPost("/api/power/lock") } }) { Text("Lock") }
        FilledTonalButton({ confirmPower = "suspend" }) { Text("Sleep") }
        FilledTonalButton({ confirmPower = "reboot" }) { Text("Restart") }
        Button({ confirmPower = "poweroff" }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Power off") }
    }
    confirmPower?.let { action ->
        val label = when (action) { "suspend" -> "Sleep"; "reboot" -> "Restart"; else -> "Power off" }
        AlertDialog(
            onDismissRequest = { confirmPower = null },
            title = { Text("$label ${mm.name}?") },
            text = { Text(when (action) { "poweroff" -> "To start it again you need its power button, or Wake up here if its network card allows it."; "suspend" -> "It will be unreachable until woken (use Wake up here, or press a key on it)."; else -> "It will be unreachable for a moment." }) },
            confirmButton = { TextButton({ confirmPower = null; scope.launch { mm.privPost("/api/power/$action") } }) { Text(label) } },
            dismissButton = { TextButton({ confirmPower = null }) { Text("Cancel") } },
        )
    }
}
