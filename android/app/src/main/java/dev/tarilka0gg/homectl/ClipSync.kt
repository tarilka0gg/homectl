@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tarilka0gg.homectl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val MAX_CLIP = 200_000

class PhoneClip(val text: String, val at: Long, val source: String)

/** What has been on the phone's clipboard (while the app was open) or went through it, kept in memory only. */
object PhoneClips {
    val items = mutableStateListOf<PhoneClip>()

    fun add(text: String, source: String) {
        if (text.isBlank() || text.length > MAX_CLIP) return
        items.removeAll { it.text == text }
        items.add(0, PhoneClip(text, System.currentTimeMillis(), source))
        while (items.size > 30) items.removeAt(items.lastIndex)
    }

    fun read(ctx: Context): String =
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()

    fun write(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("homectl", text))
    }

    private var listening = false
    fun listen(ctx: Context) {
        if (listening) return
        listening = true
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener { runCatching { add(read(ctx), "copied on phone") } }
    }
}

fun agoShort(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when { s < 10 -> "just now"; s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
}

/**
 * "Common clipboard": while on for a machine, whatever you copy on the phone appears on the machine, and whatever is
 * copied on the machine appears on the phone. It only reacts to *changes* after it was switched on. Android lets an app
 * read the phone's clipboard only while it is in front, so phone → machine works while homectl is open; machine → phone
 * keeps working as long as the app is alive in the background.
 */
object ClipSync {
    private val flags = mutableStateMapOf<String, Boolean>()
    var note by mutableStateOf<String?>(null)

    fun isOn(id: String): Boolean = flags.getOrPut(id) { Hc.memBool("clip.common.$id") }
    fun set(id: String, v: Boolean) { flags[id] = v; Hc.memPutBool("clip.common.$id", v) }

    suspend fun run(ctx: Context, m: Machine) {
        note = "starting…"
        var lastPhone = PhoneClips.read(ctx)
        var rev = -1L
        var lastText = ""
        val first = m.json("/api/clipboard/state")
        if (first != null) {
            rev = first.optLong("rev", -1)
            if (first.optString("type") == "text") lastText = m.json("/api/clipboard")?.str("text").orEmpty()
        }
        note = "on — copy on either side"
        while (true) {
            delay(1500)
            val focused = Hc.activity?.hasWindowFocus() == true
            val phone = if (focused) PhoneClips.read(ctx) else lastPhone
            if (focused && phone != lastPhone) {
                lastPhone = phone
                if (phone.isNotBlank() && phone != lastText && phone.length <= MAX_CLIP) {
                    if (m.post("/api/clipboard", JSONObject().put("text", phone))) {
                        lastText = phone
                        note = "sent ${phone.length} characters to ${m.name} · ${agoShort(System.currentTimeMillis())}"
                    } else note = "couldn't reach ${m.name}"
                }
            }
            val st = m.json("/api/clipboard/state") ?: continue
            val r = st.optLong("rev", -1)
            if (r != rev) {
                rev = r
                if (st.optString("type") == "text") {
                    val t = m.json("/api/clipboard")?.str("text").orEmpty()
                    if (t.isNotBlank() && t != lastText && t.length <= MAX_CLIP) {
                        lastText = t
                        lastPhone = t
                        PhoneClips.write(ctx, t)
                        PhoneClips.add(t, "from ${m.name}")
                        note = "got ${t.length} characters from ${m.name}"
                    }
                }
            }
        }
    }
}

private fun lines(t: String) = t.lines().size

/** Choose which of the phone's recent clips to send to the machine. */
@Composable
fun SendPicker(machine: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val now = remember { PhoneClips.read(ctx) }
    val list = remember(now) {
        buildList {
            if (now.isNotBlank()) add(PhoneClip(now, System.currentTimeMillis(), "on the phone's clipboard now"))
            PhoneClips.items.filter { it.text != now }.forEach { add(it) }
        }
    }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Send to $machine", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
        Text("Tap the one to send. The phone remembers what you copied while homectl was open, and what you sent or got.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list.isEmpty()) Text("Nothing yet — copy some text on the phone, then come back.", Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(list.size) { i ->
                val c = list[i]
                ClipRow(c.text, "${c.source} · ${agoShort(c.at)}", { onPick(c.text); onClose() }, onDelete = if (i == 0 && c.text == now) null else ({ PhoneClips.items.removeAll { it.text == c.text }; onClose() }))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Choose which clip to bring from the machine to the phone (or put back on the machine's own clipboard). Two lists:
 * the machine's recent copies, and — if the desktop shell is Noctalia — everything in its clipboard history, text and images.
 */
@Composable
fun GetPicker(m: Machine, onPick: (String) -> Unit, onClose: () -> Unit, onNote: (Boolean, String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var nonce by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<RemoteFile?>(null) }
    val hist = loaded(nonce, 0) { m.array("/api/clipboard/history") }
    val noc = loaded(nonce, 0) { m.array("/api/clipboard/noctalia") }
    val scope = rememberCoroutineScope()
    val recent = hist.value?.objs().orEmpty()
    val nocAll = noc.value?.objs().orEmpty()
    val nocShown = remember(nocAll, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) nocAll else nocAll.filter { it.str("kind") == "text" && it.str("preview").lowercase().contains(q) }
    }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Get from ${m.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (tab == 0 && recent.isNotEmpty()) TextButton({ scope.launchIt { m.call("DELETE", "/api/clipboard/history"); nonce++ } }) { Text("Clear all") }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text("Recent copies") })
            if (noc.value == null || nocAll.isNotEmpty()) FilterChip(tab == 1, { tab = 1 }, label = { Text("Noctalia history" + if (nocAll.isNotEmpty()) " (${nocAll.size})" else "") })
        }
        if (tab == 0) {
            Text("Tap the one to copy to the phone. Kept in the machine's memory only, never written to disk.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hist.value == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (recent.isEmpty()) Text("Nothing copied on ${m.name} since it started watching. Copy something there and reopen this.", Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(recent.size) { i ->
                    val e = recent[i]
                    val src = if (e.str("source") == "phone") "sent from the phone" else "copied on ${m.name}"
                    ClipRow(
                        e.str("preview") + if (e.optInt("len") > 300) "…" else "",
                        "$src · ${agoShort(e.optLong("at") * 1000)} · ${e.optInt("len")} characters" + if (e.optInt("lines") > 1) " · ${e.optInt("lines")} lines" else "",
                        {
                            scope.launchIt {
                                val full = m.json("/api/clipboard/history/${e.optLong("id")}")?.str("text")
                                if (full == null) onNote(false, "Couldn't read that entry") else { onPick(full); onClose() }
                            }
                        },
                        onDelete = { scope.launchIt { m.call("DELETE", "/api/clipboard/history/${e.optLong("id")}"); nonce++ } },
                        extra = "Use on ${m.name}" to { scope.launchIt { val ok = m.post("/api/clipboard/history/${e.optLong("id")}/use"); onNote(ok, if (ok) "Put it back on ${m.name}'s clipboard" else "Couldn't reach ${m.name}"); onClose() } },
                    )
                }
            }
        } else {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text("Search the text entries") })
            Text("Scanned from Noctalia's clipboard panel on ${m.name}: ${nocAll.count { it.str("kind") == "text" }} texts, ${nocAll.count { it.str("kind") == "image" }} images. Tap to copy a text to the phone, or to open an image.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (noc.value == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (nocShown.isEmpty()) Text("Nothing matches.", Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(nocShown.size, key = { nocShown[it].str("id") }) { i ->
                    val e = nocShown[i]
                    val isImage = e.str("kind") == "image"
                    val file = RemoteFile(e.str("id") + ".png", e.str("path"), e.optLong("size"), e.optLong("at"))
                    val sub = (if (e.optBoolean("pinned")) "📌 pinned · " else "") + agoShort(e.optLong("at") * 1000) + " · " + (if (isImage) "image " else "") + fmtBytes(e.optDouble("size"))
                    ClipRow(
                        if (isImage) "" else e.str("preview") + if (e.optLong("size") > 300) "…" else "",
                        sub,
                        {
                            if (isImage) viewing = file
                            else scope.launchIt {
                                val bytes = m.bytes("/api/fs/download?path=" + android.net.Uri.encode(e.str("path")), 400_000)
                                if (bytes == null) onNote(false, "Couldn't read that entry") else { onPick(String(bytes, Charsets.UTF_8)); onClose() }
                            }
                        },
                        onDelete = null,
                        extra = "Use on ${m.name}" to { scope.launchIt { val ok = m.post("/api/clipboard/noctalia/${android.net.Uri.encode(e.str("id"))}/use"); onNote(ok, if (ok) "Put it on ${m.name}'s clipboard" else "Couldn't reach ${m.name}"); onClose() } },
                        thumb = if (isImage) ({ Thumb(m, file, 72) }) else null,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    viewing?.let { f -> PreviewDialog(m, listOf(f), 0) { viewing = null } }
}

private fun kotlinx.coroutines.CoroutineScope.launchIt(block: suspend () -> Unit) { this.launch { block() } }

@Composable
private fun ClipRow(text: String, sub: String, onClick: () -> Unit, onDelete: (() -> Unit)?, extra: Pair<String, () -> Unit>? = null, thumb: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        thumb?.invoke()
        if (text.isNotEmpty()) Text(text, maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sub, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra?.let { (label, act) -> TextButton(act, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) } }
            if (onDelete != null) IconButton(onDelete, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Remove", Modifier.size(18.dp)) }
        }
    }
    HorizontalDivider()
}
