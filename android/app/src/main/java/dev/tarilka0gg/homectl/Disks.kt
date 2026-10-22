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

/** Biggest folders under [start], drill down by tapping. */
@Composable
private fun UsageDialog(start: String, onClose: () -> Unit) {
    var path by remember { mutableStateOf(start) }
    var r by remember { mutableStateOf<JSONObject?>(null) }
    // folder scans can take a while on big disks: use the long-timeout request
    LaunchedEffect(path) {
        r = null
        val (code, j) = Hc.cur?.getSlow("/api/fs/usage?path=" + android.net.Uri.encode(path)) ?: (0 to null)
        r = j?.takeIf { code == 200 } ?: JSONObject().put("failed", true)
    }
    val up = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
    val canUp = path != start && path != "/"

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
                    IconButton(onClose) { Icon(Icons.Default.Close, "Close") }
                }
                HorizontalDivider()
                if (r == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Measuring folders…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (r!!.optBoolean("failed")) {
                    Text("Couldn't measure this folder (no access?).", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                } else {
                    val entries = r!!.optJSONArray("entries")?.objs().orEmpty()
                    val top = entries.maxOfOrNull { it.optDouble("size") }?.coerceAtLeast(1.0) ?: 1.0
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r!!.optBoolean("partial")) item { Text("Took too long — sizes are partial.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        items(entries, key = { it.str("path") }) { e ->
                            Column(Modifier.fillMaxWidth().clickable { path = e.str("path") }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("📁 " + e.str("name"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(fmtBytes(e.optDouble("size")), style = MaterialTheme.typography.labelLarge)
                                }
                                Bar((e.optDouble("size") / top).toFloat(), MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (r!!.optLong("files_here") > 0) item {
                            Text("Files directly in this folder: ${fmtBytes(r!!.optDouble("files_here"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (entries.isEmpty()) item { Text("No subfolders.", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}
