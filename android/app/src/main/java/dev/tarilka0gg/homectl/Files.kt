@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package dev.tarilka0gg.homectl

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Saves a remote file into the phone's Downloads/homectl folder. */
private suspend fun saveToDownloads(ctx: Context, m: Machine, f: RemoteFile, onProgress: (Float) -> Unit): Boolean {
    val file = fetchToCache(ctx, m, f, onProgress) ?: return false
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
        put(MediaStore.Downloads.MIME_TYPE, mimeFor(f.name))
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/homectl")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
    return true
}

@Composable
fun FilesScreen() {
    val m = Hc.cur ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember(m.id) { mutableStateOf<String?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var sheet by remember { mutableStateOf<RemoteFile?>(null) }
    var mkdir by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<List<RemoteFile>, Int>?>(null) }
    var archive by remember { mutableStateOf<RemoteFile?>(null) }
    val listing = loaded(path to reload) { Hc.json("/api/fs/list" + (path?.let { "?path=" + android.net.Uri.encode(it) } ?: "")) }
    val cur = listing.value?.str("path") ?: path ?: ""
    fun full(name: String) = if (cur == "/") "/$name" else "$cur/$name"

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch {
            var ok = 0
            for (u in uris) {
                val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                } ?: "file-${System.currentTimeMillis()}"
                if (m.uploadTo(ctx, u, cur, name)) ok++
            }
            Toast.makeText(ctx, "Uploaded $ok/${uris.size} to $cur", Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    val all = listing.value?.optJSONArray("entries")?.objs().orEmpty().filter { showHidden || !it.optBoolean("hidden") }
    val files = all.filter { !it.optBoolean("dir") }.map { RemoteFile(it.str("name"), full(it.str("name")), it.optLong("size"), it.optLong("mtime")) }

    fun open(f: RemoteFile) {
        when (f.kind) {
            Kind.Image -> { val imgs = files.filter { it.kind == Kind.Image }; preview = imgs to imgs.indexOfFirst { it.path == f.path }.coerceAtLeast(0) }
            Kind.Text, Kind.Pdf -> preview = listOf(f) to 0
            Kind.Archive -> archive = f
            Kind.Other -> sheet = f
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ listing.value?.takeIf { !it.isNull("parent") }?.let { path = it.str("parent") } }, enabled = listing.value?.isNull("parent") == false) { Icon(Icons.Default.ArrowBack, "Up") }
                IconButton({ path = null }) { Icon(Icons.Default.Home, "Home") }
                Text(cur, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ picker.launch("*/*") }) { Text("Upload here") }
                OutlinedButton({ mkdir = true }) { Text("New folder") }
                FilterChip(showHidden, { showHidden = !showHidden }, label = { Text("Hidden files") })
            }
        }
        progress?.let { (n, f) ->
            item {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("$n  ${(f * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                }
            }
        }
        if (listing.value == null) item { Text("Loading…") } else if (all.isEmpty()) item { Text("Empty folder.", style = MaterialTheme.typography.bodyMedium) }
        items(all, key = { it.str("name") }) { e ->
            val dir = e.optBoolean("dir")
            val name = e.str("name")
            val rf = RemoteFile(name, full(name), e.optLong("size"), e.optLong("mtime"))
            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (dir) path = full(name) else open(rf) },
                    onLongClick = { if (!dir) sheet = rf },
                ).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    when {
                        dir -> Text("📁", style = MaterialTheme.typography.titleLarge)
                        canThumb(name) -> Thumb(m, rf)
                        rf.kind == Kind.Archive -> Text("📦", style = MaterialTheme.typography.titleLarge)
                        rf.kind == Kind.Pdf -> Text("📕", style = MaterialTheme.typography.titleLarge)
                        rf.kind == Kind.Image -> Text("🖼", style = MaterialTheme.typography.titleLarge)
                        else -> Text("📄", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(name + if (e.optBoolean("link")) "  ↪" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (dir) "" else fmtBytes(e.getDouble("size")) + " · ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.getLong("mtime") * 1000)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    preview?.let { (list, i) -> PreviewDialog(m, list, i) { preview = null } }
    archive?.let { a -> ArchiveDialog(m, a, onClose = { archive = null }, onOpenFolder = { dest -> archive = null; path = dest }) }

    sheet?.let { f ->
        ModalBottomSheet({ sheet = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium)
                Text("${fmtBytes(f.size.toDouble())} · ${f.path}", style = MaterialTheme.typography.bodySmall)
                if (f.kind != Kind.Other) ListItem({ Text(if (f.kind == Kind.Archive) "Look inside / unzip" else "Preview") }, Modifier.combinedClickable(onClick = { sheet = null; open(f) }))
                if (f.kind == Kind.Archive) ListItem({ Text("Unzip here") }, Modifier.combinedClickable(onClick = {
                    sheet = null
                    scope.launch {
                        Toast.makeText(ctx, "Unpacking…", Toast.LENGTH_SHORT).show()
                        val (code, j) = m.postSlow("/api/fs/archive/extract", body("path" to f.path))
                        if (code == 200) { Toast.makeText(ctx, "Unpacked ${j?.optInt("files")} files into ${j?.str("dest")}", Toast.LENGTH_LONG).show(); reload++ }
                        else Toast.makeText(ctx, j?.str("error")?.ifBlank { null } ?: "Unpack failed", Toast.LENGTH_LONG).show()
                    }
                }))
                ListItem({ Text("Download to phone") }, Modifier.combinedClickable(onClick = {
                    sheet = null
                    scope.launch {
                        progress = f.name to 0f
                        val ok = saveToDownloads(ctx, m, f) { progress = f.name to it }
                        progress = null
                        Toast.makeText(ctx, if (ok) "Saved to Downloads/homectl/${f.name}" else "Download failed", Toast.LENGTH_LONG).show()
                    }
                }), supportingContent = { Text("Downloads/homectl") })
                ListItem({ Text("Open with…") }, Modifier.combinedClickable(onClick = {
                    sheet = null
                    scope.launch {
                        progress = f.name to 0f
                        val file = fetchToCache(ctx, m, f) { progress = f.name to it }
                        progress = null
                        if (file == null) Toast.makeText(ctx, "Download failed", Toast.LENGTH_LONG).show() else openFile(ctx, file, f.name)
                    }
                }))
                ListItem({ Text("Share…") }, Modifier.combinedClickable(onClick = {
                    sheet = null
                    scope.launch {
                        progress = f.name to 0f
                        val file = fetchToCache(ctx, m, f) { progress = f.name to it }
                        progress = null
                        if (file == null) Toast.makeText(ctx, "Download failed", Toast.LENGTH_LONG).show() else shareFile(ctx, file, f.name)
                    }
                }))
                ListItem({ Text("Copy path") }, Modifier.combinedClickable(onClick = {
                    sheet = null
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("path", f.path))
                }))
            }
        }
    }
    if (mkdir) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { mkdir = false },
            title = { Text("New folder in $cur") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton({ scope.launch { m.post("/api/fs/mkdir", body("path" to full(name.trim()))); reload++ }; mkdir = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton({ mkdir = false }) { Text("Cancel") } },
        )
    }
}

private data class AEntry(val path: String, val size: Long, val dir: Boolean)

/** Browse the inside of an archive like a folder, preview entries, and unpack all or just what you tick. */
@Composable
private fun ArchiveDialog(m: Machine, a: RemoteFile, onClose: () -> Unit, onOpenFolder: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prefix by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var preview by remember { mutableStateOf<RemoteFile?>(null) }

    LaunchedEffect(a.path) {
        val (code, j) = m.getSlow("/api/fs/archive/list?path=${android.net.Uri.encode(a.path)}")
        if (code == 200 && j != null) entries = j.getJSONArray("entries").objs().map { AEntry(it.str("path"), it.optLong("size"), it.optBoolean("dir")) }
        else error = j?.str("error")?.ifBlank { null } ?: "Can't read this archive."
    }

    // children of the current folder: first path segment below the prefix
    val rows = remember(entries, prefix) {
        val seen = LinkedHashMap<String, AEntry>()
        entries.orEmpty().forEach { e ->
            if (!e.path.startsWith(prefix) || e.path == prefix.trimEnd('/')) return@forEach
            val rest = e.path.removePrefix(prefix)
            val first = rest.substringBefore('/')
            val isDir = rest.contains('/') || e.dir
            val full = prefix + first
            val cur = seen[first]
            seen[first] = AEntry(full, if (isDir) (cur?.size ?: 0) + (if (rest.contains('/')) e.size else 0) else e.size, isDir || cur?.dir == true)
        }
        seen.values.sortedWith(compareByDescending<AEntry> { it.dir }.thenBy { it.path.lowercase() })
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (prefix.isEmpty()) onClose() else prefix = prefix.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }) { Icon(Icons.Default.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("/" + prefix, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClose) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                        entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            if (rows.isEmpty()) item { Text("Empty.", Modifier.padding(16.dp)) }
                            items(rows, key = { it.path }) { r ->
                                val name = r.path.removePrefix(prefix)
                                Row(
                                    Modifier.fillMaxWidth().combinedClickable(onClick = {
                                        if (r.dir) prefix = r.path.trimEnd('/') + "/"
                                        else preview = RemoteFile(name, r.path, r.size, archive = a.path).takeIf { it.kind != Kind.Other && it.kind != Kind.Archive }
                                            ?: run { scope.launch { Toast.makeText(ctx, "Tick it and unpack, or unpack everything", Toast.LENGTH_SHORT).show() }; null }
                                    }).padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(r.path in selected, { on -> selected = if (on) selected + r.path else selected - r.path })
                                    Text(if (r.dir) "📁" else "📄", Modifier.padding(end = 10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (r.dir) "folder" else fmtBytes(r.size.toDouble()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    if (busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun unpack(only: Set<String>?) {
                        busy = true
                        scope.launch {
                            val b = JSONObject().put("path", a.path)
                            only?.let { s -> b.put("entries", org.json.JSONArray(s.toList())) }
                            val (code, j) = m.postSlow("/api/fs/archive/extract", b)
                            busy = false
                            if (code == 200) result = j else Toast.makeText(ctx, j?.str("error")?.ifBlank { null } ?: "Unpack failed", Toast.LENGTH_LONG).show()
                        }
                    }
                    Button({ unpack(null) }, enabled = entries != null && !busy) { Text("Unzip all") }
                    FilledTonalButton({ unpack(selected) }, enabled = selected.isNotEmpty() && !busy) { Text("Unzip selected (${selected.size})") }
                }
            }
        }
    }
    preview?.let { f -> PreviewDialog(m, listOf(f), 0) { preview = null } }
    result?.let { r ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("Unpacked") },
            text = { Text("${r.optInt("files")} files (${fmtBytes(r.optDouble("bytes"))}) into\n${r.str("dest")}") },
            confirmButton = { TextButton({ val d = r.str("dest"); result = null; onOpenFolder(d) }) { Text("Show folder") } },
            dismissButton = { TextButton({ result = null }) { Text("Stay") } },
        )
    }
}
