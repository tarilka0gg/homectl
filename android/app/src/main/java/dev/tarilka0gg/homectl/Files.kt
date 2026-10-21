@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

private fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** Saves a remote file into the phone's Downloads/homectl folder; returns its Uri. */
private suspend fun saveToDownloads(ctx: Context, m: Machine, remote: String, name: String, onProgress: (Float) -> Unit): android.net.Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/homectl")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
    val ok = m.download(remote) { input, len ->
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            val buf = ByteArray(128 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (len > 0) onProgress(done.toFloat() / len)
            }
        }
        true
    }
    if (ok != true) { ctx.contentResolver.delete(uri, null, null); return null }
    return uri
}

/** Downloads to the app cache (for sharing / opening) and returns a content Uri via FileProvider. */
private suspend fun cacheFile(ctx: Context, m: Machine, remote: String, name: String, onProgress: (Float) -> Unit): android.net.Uri? {
    val dir = File(ctx.cacheDir, "remote").apply { mkdirs() }
    val f = File(dir, name)
    val ok = m.download(remote) { input, len ->
        f.outputStream().use { out ->
            val buf = ByteArray(128 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (len > 0) onProgress(done.toFloat() / len)
            }
        }
        true
    }
    return if (ok == true) FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f) else null
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
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var mkdir by remember { mutableStateOf(false) }
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ listing.value?.takeIf { !it.isNull("parent") }?.let { path = it.str("parent") } }, enabled = listing.value?.isNull("parent") == false) { Icon(Icons.Default.ArrowBack, "Up") }
            IconButton({ path = null }) { Icon(Icons.Default.Home, "Home") }
            Text(cur, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton({ reload++ }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ picker.launch("*/*") }) { Text("Upload here") }
            OutlinedButton({ mkdir = true }) { Text("New folder") }
            FilterChip(showHidden, { showHidden = !showHidden }, label = { Text("Hidden files") })
        }
        progress?.let { (n, f) ->
            Column {
                Text("$n  ${(f * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
            }
        }
        val entries = listing.value?.optJSONArray("entries")?.objs().orEmpty().filter { showHidden || !it.optBoolean("hidden") }
        if (listing.value == null) Text("Loading…") else if (entries.isEmpty()) Text("Empty folder.", style = MaterialTheme.typography.bodyMedium)
        entries.forEach { e ->
            val dir = e.optBoolean("dir")
            Row(
                Modifier.fillMaxWidth().clickable { if (dir) path = full(e.str("name")) else selected = e }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (dir) "📁" else "📄", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f)) {
                    Text(e.str("name") + if (e.optBoolean("link")) "  ↪" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (dir) "" else fmtBytes(e.getDouble("size")) + " · ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.getLong("mtime") * 1000)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    selected?.let { f ->
        val name = f.str("name")
        val remote = full(name)
        ModalBottomSheet({ selected = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text("${fmtBytes(f.getDouble("size"))} · $remote", style = MaterialTheme.typography.bodySmall)
                ListItem({ Text("Download to phone") }, Modifier.clickable {
                    selected = null
                    scope.launch {
                        progress = name to 0f
                        val u = saveToDownloads(ctx, m, remote, name) { progress = name to it }
                        progress = null
                        Toast.makeText(ctx, if (u != null) "Saved to Downloads/homectl/$name" else "Download failed", Toast.LENGTH_LONG).show()
                    }
                }, supportingContent = { Text("Downloads/homectl") })
                ListItem({ Text("Open") }, Modifier.clickable {
                    selected = null
                    scope.launch {
                        progress = name to 0f
                        val u = cacheFile(ctx, m, remote, name) { progress = name to it }
                        progress = null
                        if (u == null) Toast.makeText(ctx, "Download failed", Toast.LENGTH_LONG).show()
                        else ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(u, mimeOf(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
                    }
                })
                ListItem({ Text("Share…") }, Modifier.clickable {
                    selected = null
                    scope.launch {
                        progress = name to 0f
                        val u = cacheFile(ctx, m, remote, name) { progress = name to it }
                        progress = null
                        if (u == null) Toast.makeText(ctx, "Download failed", Toast.LENGTH_LONG).show()
                        else ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mimeOf(name)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
                    }
                })
                ListItem({ Text("Copy path") }, Modifier.clickable {
                    selected = null
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("path", remote))
                })
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
