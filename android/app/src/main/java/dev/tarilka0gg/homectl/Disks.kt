@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tarilka0gg.homectl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.json.JSONObject

private val readColor = Color(0xFF4CAF50)
private val writeColor = Color(0xFFFF9800)
private val busyColor = Color(0xFF29B6F6)

/** One card per physical disk: live read/write, how busy it is, who is using it, and a way to see what fills each partition. */
@Composable
fun DisksSection(d: JSONObject, hist: JSONObject) {
    val devs = d.optJSONArray("disk_devs")?.objs().orEmpty()
    var usage by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (devs.isEmpty()) {
        // older daemon, or no whole-disk devices: fall back to the mount list
        val disks = d.optJSONArray("disks")?.objs().orEmpty()
        if (disks.isNotEmpty()) Section("Disks") { disks.forEach { PartRow(it) { m -> usage = m to m } } }
    }

    devs.forEach { k ->
        val name = k.str("name")
        val busy = k.optDouble("busy")
        val title = k.str("model").ifBlank { name }
        Section(title) {
            Text("$name · ${k.str("kind")} · ${fmtBytes(k.optDouble("size"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Busy ${"%.0f".format(busy)}%", style = MaterialTheme.typography.titleSmall)
                if (!k.isNull("temp")) Text("${"%.0f".format(k.getDouble("temp"))}°C", style = MaterialTheme.typography.labelLarge, color = heat(((k.getDouble("temp") - 30) * 2).toFloat()))
            }
            Chart(listOf(hist.series("disk.$name.busy")), listOf(busyColor), { "%.0f%%".format(it) }, max = 100f, height = 44.dp)
            Chart(listOf(hist.series("disk.$name.r"), hist.series("disk.$name.w")), listOf(readColor, writeColor), { fmtRate(it.toDouble()) })
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend("read ${fmtRate(k.optDouble("read_bps"))} · ${"%.0f".format(k.optDouble("read_iops"))} IOPS", readColor)
            }
            Legend("write ${fmtRate(k.optDouble("write_bps"))} · ${"%.0f".format(k.optDouble("write_iops"))} IOPS", writeColor)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            k.optJSONArray("parts")?.objs().orEmpty().forEach { p -> PartRow(p) { usage = it to it } }
            Text("Tap a partition to see what takes its space.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val procs = d.optJSONArray("disk_procs")?.objs().orEmpty()
    if (devs.isNotEmpty()) Section("Disk activity by process", trailing = {
        Text(if (d.str("disk_procs_scope") == "all") "all users" else "this user only", style = MaterialTheme.typography.labelSmall)
    }) {
        if (procs.isEmpty()) Text("Nothing is reading or writing right now.", style = MaterialTheme.typography.bodyMedium)
        procs.forEach { p ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${p.str("name")}  ${p.optInt("pid")}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("R ${fmtRate(p.optDouble("read_bps"))}  W ${fmtRate(p.optDouble("write_bps"))}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }

    usage?.let { (_, mount) -> UsageDialog(mount) { usage = null } }
}

@Composable
private fun PartRow(p: JSONObject, onOpen: (String) -> Unit) {
    val t = p.getDouble("total")
    val u = p.getDouble("used")
    Column(Modifier.fillMaxWidth().clickable { onOpen(p.str("mount")) }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(p.str("mount") + p.str("fs").let { if (it.isBlank()) "" else "  ($it)" }, style = MaterialTheme.typography.bodyMedium)
            Text("${fmtBytes(u)} / ${fmtBytes(t)}  ·  ${"%.0f".format(100 * u / t)}%", style = MaterialTheme.typography.labelMedium)
        }
        Bar((u / t).toFloat(), heat((100 * u / t).toFloat()))
    }
}

/** Biggest folders and files under [start]: tap a folder to look inside, open it in Files, or delete it. */
@Composable
private fun UsageDialog(start: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(start) }
    var r by remember { mutableStateOf<JSONObject?>(null) }
    var removed by remember(path) { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        r = null
        val (code, j) = Hc.cur?.getSlow("/api/fs/usage?path=" + android.net.Uri.encode(path)) ?: (0 to null)
        r = j?.takeIf { code == 200 } ?: JSONObject().put("failed", true)
    }
    val up = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    val canUp = path != start && path != "/"

    fun openInFiles(p: String, isDir: Boolean) {
        Hc.openPath = if (isDir) p else p.substringBeforeLast('/').ifEmpty { "/" }
        onClose()
    }

    // Back gesture / outside tap = one level up first, close at the top (a Dialog has its own back handling)
    Dialog(onDismissRequest = { if (canUp) path = up else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (canUp) path = up else onClose() }) { Icon(Icons.Default.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text("What takes space", style = MaterialTheme.typography.titleSmall)
                        Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ openInFiles(path, true) }) { Text("Open") }
                    IconButton(onClose) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                val cur = r
                when {
                    cur == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Measuring…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    cur.optBoolean("failed") -> Text("Couldn't measure this folder (no access?).", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                    else -> {
                        val entries = cur.optJSONArray("entries")?.objs().orEmpty().filter { it.str("path") !in removed }
                        val top = entries.maxOfOrNull { it.optDouble("size") }?.coerceAtLeast(1.0) ?: 1.0
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item {
                                Text(
                                    "${fmtBytes(cur.optDouble("total"))} in ${cur.optLong("ms") / 1000.0} s" + if (cur.optBoolean("partial")) " (partial — took too long)" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (entries.isEmpty()) item { Text("Nothing here.", style = MaterialTheme.typography.bodyMedium) }
                            items(entries, key = { it.str("path") }) { e ->
                                val dir = e.optBoolean("dir")
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        Modifier.weight(1f).clickable { if (dir) path = e.str("path") else openInFiles(e.str("path"), false) }.padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text((if (dir) "📁 " else "📄 ") + e.str("name"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(fmtBytes(e.optDouble("size")), style = MaterialTheme.typography.labelLarge)
                                        }
                                        Bar((e.optDouble("size") / top).toFloat(), MaterialTheme.colorScheme.primary)
                                    }
                                    TextButton({ openInFiles(e.str("path"), dir) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Open") }
                                    IconButton({ confirm = e }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { e ->
        AlertDialog(
            onDismissRequest = { if (!deleting) confirm = null },
            title = { Text("Delete ${e.str("name")}?") },
            text = { Text("${e.str("path")}\n\n${fmtBytes(e.optDouble("size"))} will be removed from ${Hc.cur?.name}. This can't be undone.") },
            confirmButton = {
                TextButton({
                    deleting = true
                    scope.launch {
                        val (code, j) = Hc.cur?.postSlow("/api/fs/delete", body("path" to e.str("path")), 600_000) ?: (0 to null)
                        deleting = false
                        confirm = null
                        if (code == 204) {
                            removed = removed + e.str("path")
                            Toast.makeText(ctx, "Deleted ${e.str("name")} (${fmtBytes(e.optDouble("size"))})", Toast.LENGTH_SHORT).show()
                        } else Toast.makeText(ctx, j?.str("error")?.ifBlank { null } ?: "Couldn't delete (no permission?)", Toast.LENGTH_LONG).show()
                    }
                }, enabled = !deleting) { Text(if (deleting) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirm = null }, enabled = !deleting) { Text("Cancel") } },
        )
    }
}
