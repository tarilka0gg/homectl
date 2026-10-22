@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

fun fmtDate(sec: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(sec * 1000))

private fun ago(sec: Long): String {
    val s = System.currentTimeMillis() / 1000 - sec
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        s < 86400 * 60 -> "${s / 86400} days ago"
        else -> "${s / (86400 * 30)} months ago"
    }
}

/** Everything the machine knows about one file or folder: type, size, who owns it, all its dates, and a few things from inside. */
@Composable
fun DetailsDialog(m: Machine, f: RemoteFile, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember(f.path) { mutableStateOf<JSONObject?>(null) }
    var failed by remember(f.path) { mutableStateOf(false) }
    var total by remember(f.path) { mutableStateOf<JSONObject?>(null) }
    var measuring by remember(f.path) { mutableStateOf(false) }
    var hash by remember(f.path) { mutableStateOf<Pair<String, String>?>(null) }
    var hashing by remember(f.path) { mutableStateOf(false) }
    LaunchedEffect(f.path) {
        val (code, j) = m.getSlow("/api/fs/stat?path=" + android.net.Uri.encode(f.path))
        if (code == 200 && j != null) d = j else failed = true
    }
    fun copy(label: String, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Default.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                val j = d
                when {
                    failed -> Text("Couldn't read the details (is the machine running the latest homectl?).", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                    j == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> SelectionContainer {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            val isDir = j.str("kind") == "folder"
                            Group("General") {
                                Row2("Path", j.str("path"))
                                Row2("Kind", when {
                                    !j.isNull("link") -> "${if (isDir) "Link to a folder" else "Link to a file"} → ${j.str("link")}"
                                    isDir -> "Folder"
                                    j.str("kind") == "special" -> "Special file (device, socket, pipe…)"
                                    else -> "File"
                                })
                                if (j.has("mime")) Row2("Type", j.str("mime"))
                                if (j.has("description")) Row2("Format", j.str("description"))
                                if (!isDir && !j.isNull("size")) Row2("Size", "${fmtBytes(j.optDouble("size"))}  (${"%,d".format(j.optLong("size"))} bytes)")
                                Row2("Space on disk", fmtBytes(j.optDouble("on_disk")))
                                if (j.optBoolean("hidden")) Row2("Hidden", "yes (name starts with a dot)")
                            }
                            if (isDir) j.optJSONObject("items")?.let { it ->
                                Group("Inside") {
                                    Row2("Contains", "${it.optInt("files")} files · ${it.optInt("folders")} folders" + if (it.optInt("links") > 0) " · ${it.optInt("links")} links" else "")
                                    if (it.optLong("newest") > 0) Row2("Newest item", "${fmtDate(it.optLong("newest"))}  (${ago(it.optLong("newest"))})")
                                    val t = total
                                    if (t != null) Row2("Total size", "${fmtBytes(t.optDouble("total"))} in everything below" + if (t.optBoolean("partial")) " (partial)" else "")
                                    else Row2("Total size", if (measuring) "measuring…" else "not measured yet") 
                                    if (t == null) FilledTonalButton({
                                        measuring = true
                                        scope.launch {
                                            val (code, r) = m.getSlow("/api/fs/usage?path=" + android.net.Uri.encode(f.path))
                                            measuring = false
                                            if (code == 200) total = r else Toast.makeText(ctx, "Couldn't measure", Toast.LENGTH_SHORT).show()
                                        }
                                    }, enabled = !measuring) { Text(if (measuring) "Measuring…" else "Measure total size") }
                                }
                            }
                            if (j.has("image") || j.has("text")) Group("Content") {
                                j.optJSONObject("image")?.let { Row2("Image", "${it.optInt("width")} × ${it.optInt("height")} px  (${"%.1f".format(it.optInt("width") * it.optInt("height") / 1e6)} MP)") }
                                j.optJSONObject("text")?.let {
                                    Row2("Text", "${"%,d".format(it.optLong("lines"))} lines · ${"%,d".format(it.optLong("words"))} words")
                                    Row2("Line endings", if (it.optBoolean("crlf")) "Windows (CRLF)" else "Unix (LF)")
                                }
                            }
                            Group("Permissions") {
                                Row2("Mode", "${j.str("perms")}  (${j.str("mode")})", mono = true)
                                Row2("Owner", "${j.str("owner")}  (uid ${j.optLong("uid")})")
                                Row2("Group", "${j.str("group")}  (gid ${j.optLong("gid")})")
                            }
                            Group("Dates") {
                                Row2("Modified", "${fmtDate(j.optLong("mtime"))}  (${ago(j.optLong("mtime"))})")
                                Row2("Accessed", fmtDate(j.optLong("atime")))
                                Row2("Metadata changed", fmtDate(j.optLong("ctime")))
                                if (!j.isNull("birth")) Row2("Created", fmtDate(j.optLong("birth")))
                            }
                            Group("Filesystem") {
                                Row2("Inode", j.optLong("inode").toString())
                                Row2("Hard links", j.optLong("links").toString())
                            }
                            if (j.str("kind") == "file") Group("Checksum") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("sha256" to "SHA-256", "sha1" to "SHA-1", "md5" to "MD5").forEach { (algo, label) ->
                                        OutlinedButton({
                                            hashing = true
                                            scope.launch {
                                                val (code, r) = m.getSlow("/api/fs/hash?path=${android.net.Uri.encode(f.path)}&algo=$algo", 330_000)
                                                hashing = false
                                                if (code == 200 && r != null) hash = label to r.str("hash") else Toast.makeText(ctx, "Couldn't compute it", Toast.LENGTH_SHORT).show()
                                            }
                                        }, enabled = !hashing) { Text(label) }
                                    }
                                }
                                if (hashing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Reading the file…") }
                                hash?.let { (label, h) ->
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                    Text(h, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                    TextButton({ copy(label, h) }) { Text("Copy") }
                                }
                            }
                            OutlinedButton({ copy("path", j.str("path")) }) { Text("Copy path") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Row2(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}
