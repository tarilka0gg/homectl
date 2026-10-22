@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package dev.tarilka0gg.homectl

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

/** One row of a folder listing or of search results ([where] = the folder, shown for search hits). */
@Composable
private fun FileRow(m: Machine, rf: RemoteFile, dir: Boolean, link: Boolean, where: String?, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                dir -> Text("📁", style = MaterialTheme.typography.titleLarge)
                canThumb(rf.name) -> Thumb(m, rf)
                rf.kind == Kind.Archive -> Text("📦", style = MaterialTheme.typography.titleLarge)
                rf.kind == Kind.Pdf -> Text("📕", style = MaterialTheme.typography.titleLarge)
                rf.kind == Kind.Image -> Text("🖼", style = MaterialTheme.typography.titleLarge)
                rf.kind == Kind.Video -> Text("🎬", style = MaterialTheme.typography.titleLarge)
                rf.kind == Kind.Audio -> Text("🎵", style = MaterialTheme.typography.titleLarge)
                else -> Text("📄", style = MaterialTheme.typography.titleLarge)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(rf.name + if (link) "  ↪" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (where != null) Text(where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (dir) "" else fmtBytes(rf.size.toDouble()) + " · ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(rf.mtime * 1000)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun FilesScreen() {
    val m = Hc.cur ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // browsing history: back/forward like a browser (path null = the machine's home)
    var hist by remember(m.id) { mutableStateOf(listOf<String?>(null)) }
    var pos by remember(m.id) { mutableIntStateOf(0) }
    val path = hist[pos]
    fun go(p: String?) { hist = hist.take(pos + 1) + p; pos = hist.lastIndex }
    var showHidden by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var sheet by remember { mutableStateOf<RemoteFile?>(null) }
    var mkdir by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<List<RemoteFile>, Int>?>(null) }
    var archive by remember { mutableStateOf<RemoteFile?>(null) }
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val listing = loaded(path to reload) { Hc.json("/api/fs/list" + (path?.let { "?path=" + android.net.Uri.encode(it) } ?: "")) }
    val cur = listing.value?.str("path") ?: path ?: ""
    val parent = listing.value?.takeIf { !it.isNull("parent") }?.str("parent")
    val home = listing.value?.str("home").orEmpty()
    fun full(name: String) = if (cur == "/") "/$name" else "$cur/$name"

    fun back() { if (pos > 0) pos-- else if (parent != null && cur != home) go(parent) }
    fun forward() { if (pos < hist.lastIndex) pos++ }
    BackHandler(enabled = searching || editing || pos > 0 || (parent != null && cur != home)) {
        when {
            editing -> editing = false
            searching -> { searching = false; query = "" }
            else -> back()
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
                if (m.uploadTo(ctx, u, cur, name)) ok++
            }
            Toast.makeText(ctx, "Uploaded $ok/${uris.size} to $cur", Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    LaunchedEffect(Hc.openPath, m.id) { Hc.openPath?.let { go(it); Hc.openPath = null } }

    val all = listing.value?.optJSONArray("entries")?.objs().orEmpty().filter { showHidden || !it.optBoolean("hidden") }
    val files = all.filter { !it.optBoolean("dir") }.map { RemoteFile(it.str("name"), full(it.str("name")), it.optLong("size"), it.optLong("mtime")) }

    // search: names below the current folder, a moment after typing stops
    var results by remember { mutableStateOf<List<JSONObject>?>(null) }
    var truncated by remember { mutableStateOf(false) }
    LaunchedEffect(query, cur, showHidden, searching) {
        results = null
        if (!searching || query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        val j = Hc.json("/api/fs/search?path=${android.net.Uri.encode(cur)}&q=${android.net.Uri.encode(query.trim())}&hidden=$showHidden")
        results = j?.optJSONArray("entries")?.objs().orEmpty()
        truncated = j?.optBoolean("truncated") == true
    }

    fun open(f: RemoteFile, siblings: List<RemoteFile> = files) {
        when (f.kind) {
            Kind.Image -> { val imgs = siblings.filter { it.kind == Kind.Image }.ifEmpty { listOf(f) }; preview = imgs to imgs.indexOfFirst { it.path == f.path }.coerceAtLeast(0) }
            Kind.Archive -> archive = f
            else -> preview = listOf(f) to 0 // text, pdf, video, audio and anything else (hex view for binaries)
        }
    }

    // typed path: suggestions are the folders next to what is typed so far
    val typedDir = if (typed.endsWith("/")) typed else typed.substringBeforeLast('/', "").ifEmpty { if (typed.startsWith("/")) "/" else "~" }
    val sugListing = loaded(typedDir to editing) { if (editing) Hc.json("/api/fs/list?path=" + android.net.Uri.encode(typedDir)) else null }
    fun goTyped() {
        scope.launch {
            val t = typed.trim().ifEmpty { "~" }
            val j = Hc.json("/api/fs/list?path=" + android.net.Uri.encode(t))
            if (j != null) { go(j.str("path")); editing = false; return@launch }
            // not a folder: maybe a file, then show its folder and open it
            val dirOf = t.substringBeforeLast('/', "").ifEmpty { "/" }
            val pj = Hc.json("/api/fs/list?path=" + android.net.Uri.encode(dirOf))
            val e = pj?.optJSONArray("entries")?.objs()?.firstOrNull { it.str("name") == t.substringAfterLast('/') && !it.optBoolean("dir") }
            if (pj != null && e != null) {
                val d = pj.str("path")
                go(d); editing = false
                open(RemoteFile(e.str("name"), (if (d == "/") "" else d) + "/" + e.str("name"), e.optLong("size"), e.optLong("mtime")), emptyList())
            } else Toast.makeText(ctx, "No such folder or file", Toast.LENGTH_SHORT).show()
        }
    }

    var swipe by remember { mutableFloatStateOf(0f) }
    LazyColumn(
        Modifier.fillMaxSize().pointerInput(pos, hist.size) {
            // swipe right = back, swipe left = forward (system back gesture works too)
            detectHorizontalDragGestures(onDragStart = { swipe = 0f }, onDragEnd = { if (swipe > 160f) back() else if (swipe < -160f) forward() }) { _, dx -> swipe += dx }
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ back() }, enabled = pos > 0 || (parent != null && cur != home)) { Icon(Icons.Default.ArrowBack, "Back") }
                IconButton({ forward() }, enabled = pos < hist.lastIndex) { Icon(Icons.Default.ArrowForward, "Forward") }
                IconButton({ parent?.let { go(it) } }, enabled = parent != null) { Icon(Icons.Default.KeyboardArrowUp, "Up") }
                IconButton({ go(null) }) { Icon(Icons.Default.Home, "Home") }
                Spacer(Modifier.weight(1f))
                IconButton({ searching = !searching; if (!searching) query = ""; editing = false }) { Icon(Icons.Default.Search, "Search") }
                IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
        }
        item {
            if (editing) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Path (/ or ~/…)") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrect = false, keyboardType = KeyboardType.Uri),
                        keyboardActions = KeyboardActions(onGo = { goTyped() }),
                        trailingIcon = { IconButton({ goTyped() }) { Icon(Icons.Default.ArrowForward, "Go") } },
                    )
                    val prefix = if (typed.endsWith("/")) "" else typed.substringAfterLast('/')
                    val base = if (typed.endsWith("/")) typed else typed.substringBeforeLast('/', "").let { if (it.isEmpty() && typed.startsWith("/")) "/" else it }
                    val sugs = sugListing.value?.takeIf { typed.isNotEmpty() }?.optJSONArray("entries")?.objs().orEmpty()
                        .filter { it.optBoolean("dir") && it.str("name").startsWith(prefix, ignoreCase = true) && (prefix.startsWith(".") || !it.optBoolean("hidden")) }.take(14)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sugs.forEach { e -> AssistChip({ typed = (if (base.endsWith("/") || base.isEmpty()) base else "$base/") + e.str("name") + "/" }, label = { Text(e.str("name")) }) }
                    }
                }
            } else {
                Text(
                    cur, Modifier.fillMaxWidth().combinedClickable(onClick = { typed = if (cur.endsWith("/")) cur else "$cur/"; editing = true }).padding(vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (searching) item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search in $cur") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear") } })
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
        if (searching && query.isNotBlank()) {
            val rs = results
            if (rs == null) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Searching…") } }
            else {
                if (rs.isEmpty()) item { Text("Nothing found.", style = MaterialTheme.typography.bodyMedium) }
                items(rs, key = { it.str("path") }) { e ->
                    val dir = e.optBoolean("dir")
                    val rf = RemoteFile(e.str("name"), e.str("path"), e.optLong("size"), e.optLong("mtime"))
                    FileRow(m, rf, dir, false, e.str("path").substringBeforeLast('/').ifEmpty { "/" },
                        onClick = { if (dir) { go(rf.path); searching = false; query = "" } else open(rf, rs.filter { !it.optBoolean("dir") }.map { RemoteFile(it.str("name"), it.str("path"), it.optLong("size"), it.optLong("mtime")) }) },
                        onLong = { if (!dir) sheet = rf })
                }
                if (truncated) item { Text("Showing the first matches — type more to narrow down.", style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            if (listing.value == null) item { Text("Loading…") } else if (all.isEmpty()) item { Text("Empty folder.", style = MaterialTheme.typography.bodyMedium) }
            items(all, key = { it.str("name") }) { e ->
                val dir = e.optBoolean("dir")
                val name = e.str("name")
                val rf = RemoteFile(name, full(name), e.optLong("size"), e.optLong("mtime"))
                FileRow(m, rf, dir, e.optBoolean("link"), null, onClick = { if (dir) go(full(name)) else open(rf) }, onLong = { if (!dir) sheet = rf })
            }
        }
    }

    preview?.let { (list, i) -> PreviewDialog(m, list, i) { preview = null } }
    archive?.let { a -> ArchiveDialog(m, a, onClose = { archive = null }, onOpenFolder = { dest -> archive = null; go(dest) }) }

    sheet?.let { f ->
        ModalBottomSheet({ sheet = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium)
                Text("${fmtBytes(f.size.toDouble())} · ${f.path}", style = MaterialTheme.typography.bodySmall)
                ListItem({ Text(if (f.kind == Kind.Archive) "Look inside / unzip" else "Preview") }, Modifier.combinedClickable(onClick = { sheet = null; open(f) }))
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

    val upOne = { if (prefix.isEmpty()) onClose() else prefix = prefix.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }
    // back gesture goes up one folder inside the archive, closes at the top
    Dialog(onDismissRequest = upOne, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                                        else preview = RemoteFile(name, r.path, r.size, archive = a.path).takeIf { it.kind != Kind.Archive }
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
