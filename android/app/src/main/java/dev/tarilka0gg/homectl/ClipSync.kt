@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tarilka0gg.homectl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val MAX_TEXT = 200_000
private const val MAX_IMAGE = 16_000_000

/** One clip on the phone: text, or an image kept as a file in the app's cache. */
class PhoneClip(val text: String?, val image: File?, val mime: String, val at: Long, val source: String) {
    val key: String get() = if (image != null) "img:${image.name}" else "txt:${text.hashCode()}:${text?.length}"
}

/** What has been on the phone's clipboard (while the app was open) or went through it, kept in memory and the app cache only. */
object PhoneClips {
    val items = mutableStateListOf<PhoneClip>()
    private fun dir(ctx: Context) = File(ctx.cacheDir, "remote").apply { mkdirs() }

    fun add(c: PhoneClip) {
        if (c.text != null && (c.text.isBlank() || c.text.length > MAX_TEXT)) return
        items.removeAll { it.key == c.key }
        items.add(0, c)
        while (items.size > 30) items.removeAt(items.lastIndex)
    }
    fun addText(t: String, source: String) = add(PhoneClip(t, null, "text/plain", System.currentTimeMillis(), source))

    fun saveImage(ctx: Context, bytes: ByteArray, mime: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
        val f = File(dir(ctx), "clip-$hash.${if (mime.endsWith("jpeg")) "jpg" else "png"}")
        if (!f.exists()) f.writeBytes(bytes)
        return f
    }

    /** Whatever is on the phone's clipboard now: an image, text, or null. */
    fun read(ctx: Context): PhoneClip? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        val item = clip.getItemAt(0)
        val mime = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        val uri = item.uri
        if (mime != null && uri != null) {
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_IMAGE + 1) } }.getOrNull()
            if (bytes != null && bytes.isNotEmpty() && bytes.size <= MAX_IMAGE) return PhoneClip(null, saveImage(ctx, bytes, mime), mime, System.currentTimeMillis(), "copied on phone")
        }
        val t = item.coerceToText(ctx)?.toString().orEmpty()
        return if (t.isBlank()) null else PhoneClip(t, null, "text/plain", System.currentTimeMillis(), "copied on phone")
    }

    fun writeText(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("homectl", text))
    }

    fun writeImage(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newUri(ctx.contentResolver, "image", uri))
    }

    private var listening = false
    fun listen(ctx: Context) {
        if (listening) return
        listening = true
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener { runCatching { read(ctx)?.let { add(it) } } }
    }
}

fun agoShort(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when { s < 10 -> "just now"; s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
}

/** Sends [c] to the machine's clipboard; returns a message and whether it worked. */
suspend fun sendToMachine(m: Machine, c: PhoneClip): Pair<Boolean, String> =
    if (c.image != null) {
        val ok = m.postBytes("/api/clipboard/image?mime=${c.mime}", c.image.readBytes())
        ok to if (ok) "Sent the image (${fmtBytes(c.image.length().toDouble())}) to ${m.name}'s clipboard" else "Couldn't send the image — is ${m.name} reachable?"
    } else {
        val ok = m.post("/api/clipboard", JSONObject().put("text", c.text.orEmpty()))
        val n = c.text.orEmpty().length
        ok to if (ok) "Sent $n character${if (n == 1) "" else "s"} to ${m.name}'s clipboard" else "Couldn't send — is ${m.name} reachable?"
    }

private fun thumbOf(f: File, max: Int = 160): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / s > max * 2 || o.outHeight / s > max * 2) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

/**
 * "Common clipboard": while on for a machine, whatever you copy on the phone appears on the machine and whatever is
 * copied on the machine appears on the phone — text and pictures. It only reacts to *changes* after it was switched on.
 * Android lets an app read the phone's clipboard only while it is in front, so phone → machine works while homectl is open;
 * machine → phone keeps working as long as the app is alive in the background.
 */
object ClipSync {
    private val flags = mutableStateMapOf<String, Boolean>()
    var note by mutableStateOf<String?>(null)

    fun isOn(id: String): Boolean = flags.getOrPut(id) { Hc.memBool("clip.common.$id") }
    fun set(id: String, v: Boolean) { flags[id] = v; Hc.memPutBool("clip.common.$id", v) }

    suspend fun run(ctx: Context, m: Machine) {
        note = "starting…"
        var lastPhone = PhoneClips.read(ctx)?.key
        var rev = -1L
        var lastText = ""
        var lastMachineImage = ""
        val first = m.json("/api/clipboard/state")
        if (first != null) {
            rev = first.optLong("rev", -1)
            if (first.optString("type") == "text") lastText = m.json("/api/clipboard")?.str("text").orEmpty()
        }
        note = "on — copy on either side"
        while (true) {
            delay(1500)
            val focused = Hc.activity?.hasWindowFocus() == true
            if (focused) {
                val cur = PhoneClips.read(ctx)
                if (cur != null && cur.key != lastPhone) {
                    lastPhone = cur.key
                    PhoneClips.add(cur)
                    if (cur.text == null || cur.text != lastText) {
                        val (ok, msg) = sendToMachine(m, cur)
                        if (ok) { if (cur.text != null) lastText = cur.text; note = "$msg · ${agoShort(System.currentTimeMillis())}" } else note = msg
                    }
                }
            }
            val st = m.json("/api/clipboard/state") ?: continue
            val r = st.optLong("rev", -1)
            if (r != rev) {
                rev = r
                val type = st.optString("type")
                if (type == "text") {
                    val t = m.json("/api/clipboard")?.str("text").orEmpty()
                    if (t.isNotBlank() && t != lastText && t.length <= MAX_TEXT) {
                        lastText = t
                        PhoneClips.writeText(ctx, t)
                        lastPhone = PhoneClips.read(ctx)?.key
                        PhoneClips.addText(t, "from ${m.name}")
                        note = "got ${t.length} characters from ${m.name}"
                    }
                } else if (type.startsWith("image/")) {
                    val bytes = m.bytes("/api/clipboard/image", MAX_IMAGE)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val f = PhoneClips.saveImage(ctx, bytes, type)
                        if (f.name != lastMachineImage) {
                            lastMachineImage = f.name
                            PhoneClips.writeImage(ctx, f)
                            lastPhone = PhoneClips.read(ctx)?.key
                            PhoneClips.add(PhoneClip(null, f, type, System.currentTimeMillis(), "from ${m.name}"))
                            note = "got an image (${fmtBytes(bytes.size.toDouble())}) from ${m.name}"
                        }
                    }
                }
            }
        }
    }
}


/** Choose which clip on the phone to send to the machine: what is on the phone's clipboard now, what was copied or received earlier, or text you write. */
@Composable
fun SendPicker(m: Machine, onResult: (Boolean, String) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var writing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val now = remember { PhoneClips.read(ctx) }
    val list = remember(now) {
        buildList {
            if (now != null) add(PhoneClip(now.text, now.image, now.mime, System.currentTimeMillis(), "on the phone's clipboard now"))
            PhoneClips.items.filter { it.key != now?.key }.forEach { add(it) }
        }
    }
    fun send(c: PhoneClip) {
        if (busy) return
        busy = true
        scope.launch {
            val (ok, msg) = sendToMachine(m, c)
            if (ok) PhoneClips.add(c)
            onResult(ok, msg)
            busy = false
            onClose()
        }
    }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Send to ${m.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton({ writing = true }) { Text("Write text…") }
        }
        Text("Tap the one to send — text or picture. The phone remembers what you copied while homectl was open, and what you sent or got.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list.isEmpty()) Text("Nothing yet — copy some text or a picture on the phone, then open this again.", Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(list.size) { i ->
                val c = list[i]
                val isNow = i == 0 && now != null
                ClipRow(
                    text = c.text.orEmpty().take(300),
                    sub = "${c.source} · ${agoShort(c.at)}" + (c.text?.let { " · ${it.length} characters" } ?: c.image?.let { " · picture ${fmtBytes(it.length().toDouble())}" }.orEmpty()),
                    thumb = c.image?.let { f -> { val bmp = remember(f) { thumbOf(f) }; bmp?.let { Image(it, null, Modifier.heightIn(max = 110.dp), contentScale = ContentScale.Fit) } } },
                    onClick = { send(c) },
                    onDelete = if (isNow) null else ({ PhoneClips.items.removeAll { it.key == c.key }; onClose() }),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (writing) AlertDialog(
        onDismissRequest = { writing = false },
        title = { Text("Text to send to ${m.name}") },
        text = { OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8) },
        confirmButton = { TextButton({ writing = false; send(PhoneClip(draft, null, "text/plain", System.currentTimeMillis(), "written")) }, enabled = draft.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton({ writing = false }) { Text("Cancel") } },
    )
}

/**
 * Choose which clip to bring from the machine to the phone (text or picture), or put back on the machine's own clipboard.
 * "Now & recent" is what is on the machine's clipboard right now plus its recent copies; "Noctalia history" is everything in
 * the Noctalia clipboard panel, with a search box.
 */
@Composable
fun GetPicker(m: Machine, onGotText: (String) -> Unit, onGotImage: (File, String) -> Unit, onClose: () -> Unit, onNote: (Boolean, String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var nonce by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<RemoteFile?>(null) }
    val now = loaded(nonce, 0) { m.json("/api/clipboard") }
    val hist = loaded(nonce, 0) { m.array("/api/clipboard/history") }
    val noc = loaded(nonce, 0) { m.array("/api/clipboard/noctalia") }
    val scope = rememberCoroutineScope()
    val recent = hist.value?.objs().orEmpty()
    val nocAll = noc.value?.objs().orEmpty()
    val nowType = now.value?.optString("type").orEmpty()
    val nocShown = remember(nocAll, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) nocAll else nocAll.filter { it.str("kind") == "text" && it.str("preview").lowercase().contains(q) }
    }
    fun getImage(pathQuery: String, mime: String) {
        scope.launch {
            val bytes = m.bytes(pathQuery, MAX_IMAGE)
            if (bytes == null || bytes.isEmpty()) onNote(false, "Couldn't get the picture from ${m.name}")
            else { onGotImage(PhoneClips.saveImage(ctx, bytes, mime), mime); onClose() }
        }
    }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Get from ${m.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (tab == 0 && recent.isNotEmpty()) TextButton({ scope.launch { m.call("DELETE", "/api/clipboard/history"); nonce++ } }) { Text("Clear list") }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text("Now & recent") })
            if (noc.value == null || nocAll.isNotEmpty()) FilterChip(tab == 1, { tab = 1 }, label = { Text("Noctalia history" + if (nocAll.isNotEmpty()) " (${nocAll.size})" else "") })
        }
        if (tab == 0) {
            Text("Tap one to copy it to the phone. Recent copies are kept in the machine's memory only, never on disk.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (now.value == null && hist.value == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                if (nowType.startsWith("image/")) item {
                    val f = remember(now.value) { RemoteFile("now.png", "", now.value?.optLong("size") ?: 0, 0, null) }
                    ClipRow("", "on ${m.name}'s clipboard now · picture ${fmtBytes(now.value?.optDouble("size") ?: 0.0)}", { getImage("/api/clipboard/image", nowType) }, null,
                        thumb = { MachineImage(m, "/api/clipboard/image") })
                }
                items(recent.size) { i ->
                    val e = recent[i]
                    val src = if (e.str("source") == "phone") "sent from the phone" else "copied on ${m.name}"
                    ClipRow(
                        e.str("preview") + if (e.optInt("len") > 300) "…" else "",
                        "$src · ${agoShort(e.optLong("at") * 1000)} · ${e.optInt("len")} characters" + if (e.optInt("lines") > 1) " · ${e.optInt("lines")} lines" else "",
                        {
                            scope.launch {
                                val full = m.json("/api/clipboard/history/${e.optLong("id")}")?.str("text")
                                if (full == null) onNote(false, "Couldn't read that entry") else { onGotText(full); onClose() }
                            }
                        },
                        onDelete = { scope.launch { m.call("DELETE", "/api/clipboard/history/${e.optLong("id")}"); nonce++ } },
                        extra = "Use on ${m.name}" to { scope.launch { val ok = m.post("/api/clipboard/history/${e.optLong("id")}/use"); onNote(ok, if (ok) "Put it back on ${m.name}'s clipboard" else "Couldn't reach ${m.name}"); onClose() } },
                    )
                }
                if (recent.isEmpty() && !nowType.startsWith("image/")) item { Text("Nothing copied on ${m.name} since it started watching. Copy something there and reopen this.", Modifier.padding(20.dp)) }
            }
        } else {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text("Search the text entries") })
            Text("From Noctalia's clipboard panel on ${m.name}: ${nocAll.count { it.str("kind") == "text" }} texts, ${nocAll.count { it.str("kind") == "image" }} pictures. Tap one to copy it to the phone.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (noc.value == null) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (nocShown.isEmpty()) Text("Nothing matches.", Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(nocShown.size, key = { nocShown[it].str("id") }) { i ->
                    val e = nocShown[i]
                    val isImage = e.str("kind") == "image"
                    val path = "/api/fs/download?path=" + android.net.Uri.encode(e.str("path"))
                    val file = RemoteFile(e.str("id") + ".png", e.str("path"), e.optLong("size"), e.optLong("at"))
                    val sub = (if (e.optBoolean("pinned")) "📌 pinned · " else "") + agoShort(e.optLong("at") * 1000) + " · " + (if (isImage) "picture " else "") + fmtBytes(e.optDouble("size"))
                    ClipRow(
                        if (isImage) "" else e.str("preview") + if (e.optLong("size") > 300) "…" else "",
                        sub,
                        {
                            if (isImage) getImage(path, e.str("mime").ifBlank { "image/png" })
                            else scope.launch {
                                val bytes = m.bytes(path, 400_000)
                                if (bytes == null) onNote(false, "Couldn't read that entry") else { onGotText(String(bytes, Charsets.UTF_8)); onClose() }
                            }
                        },
                        onDelete = null,
                        extra = "Use on ${m.name}" to { scope.launch { val ok = m.post("/api/clipboard/noctalia/${android.net.Uri.encode(e.str("id"))}/use"); onNote(ok, if (ok) "Put it on ${m.name}'s clipboard" else "Couldn't reach ${m.name}"); onClose() } },
                        thumb = if (isImage) ({ Thumb(m, file, 96) }) else null,
                        second = if (isImage) ("View" to { viewing = file }) else null,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    viewing?.let { f -> PreviewDialog(m, listOf(f), 0) { viewing = null } }
}

@Composable
private fun MachineImage(m: Machine, pathQuery: String) {
    var bmp by remember(pathQuery) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pathQuery) {
        bmp = m.bytes(pathQuery, 8_000_000)?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
    }
    bmp?.let { Image(it, null, Modifier.heightIn(max = 110.dp), contentScale = ContentScale.Fit) } ?: CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
}

@Composable
private fun ClipRow(
    text: String, sub: String, onClick: () -> Unit, onDelete: (() -> Unit)?,
    extra: Pair<String, () -> Unit>? = null, thumb: (@Composable () -> Unit)? = null, second: Pair<String, () -> Unit>? = null,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        thumb?.invoke()
        if (text.isNotEmpty()) Text(text, maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sub, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            second?.let { (label, act) -> TextButton(act, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) } }
            extra?.let { (label, act) -> TextButton(act, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) } }
            if (onDelete != null) IconButton(onDelete, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, "Remove", Modifier.size(18.dp)) }
        }
    }
    HorizontalDivider()
}
