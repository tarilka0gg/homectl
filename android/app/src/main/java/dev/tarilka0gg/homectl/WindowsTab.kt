@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A 2×2 grid of windows, for the tab. */
val WindowsIcon: ImageVector by lazy {
    ImageVector.Builder("Windows", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
            moveTo(3f, 3f); horizontalLineTo(11f); verticalLineTo(11f); horizontalLineTo(3f); close()
            moveTo(13f, 3f); horizontalLineTo(21f); verticalLineTo(11f); horizontalLineTo(13f); close()
            moveTo(3f, 13f); horizontalLineTo(11f); verticalLineTo(21f); horizontalLineTo(3f); close()
            moveTo(13f, 13f); horizontalLineTo(21f); verticalLineTo(21f); horizontalLineTo(13f); close()
        }
    }.build()
}

private class WinCard(val j: JSONObject) {
    val id = j.str("id")
    val title = j.str("title")
    val app = j.str("app_id")
    val workspace = j.str("workspace")
    val focused = j.optBoolean("focused")
    val kind = j.str("kind")
    val term = j.str("term")
    val preview = j.optJSONArray("preview")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    val pv: List<List<Run>> = j.optJSONArray("pv")?.let { ls ->
        (0 until ls.length()).map { i -> ls.getJSONArray(i).let { rs -> (0 until rs.length()).map { k -> rs.getJSONArray(k).let { Run(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3)) } } } }
    }.orEmpty()
    val cwd = j.str("cwd")
    val cmd = j.str("cmd")
    val isTerminal get() = term.isNotEmpty()
    /** the Claude Code session running in this terminal, if any */
    val claude = j.optJSONObject("claude")?.let { ClaudeInfo(it) }
    /** where the compositor has put the window: screen, visible now, size (niri only) */
    val place: JSONObject? = j.optJSONObject("place")
    /** what the filter row groups it under */
    val group get() = if (claude != null) "claude" else app.ifBlank { "window" }
}

private object Icons2 {
    val cache = ConcurrentHashMap<String, Any>() // machine|app -> ImageBitmap, or Unit when the machine has none
}

@Composable
private fun AppIcon(app: String, title: String, size: Int = 36) {
    val mid = Hc.cur?.id ?: return
    val key = "$mid|$app"
    var bmp by remember(key) { mutableStateOf(Icons2.cache[key] as? ImageBitmap) }
    LaunchedEffect(key) {
        if (Icons2.cache.containsKey(key) || app.isBlank()) return@LaunchedEffect
        val bytes = Hc.cur?.bytes("/api/window-icon/${android.net.Uri.encode(app)}", 400_000)
        val img = bytes?.let {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, o)
            o.inSampleSize = (o.outWidth / 128).coerceAtLeast(1)
            o.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(it, 0, it.size, o)?.asImageBitmap()
        }
        Icons2.cache[key] = img ?: Unit
        bmp = img
    }
    val b = bmp
    if (app == "terminal") {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1B2128)), contentAlignment = Alignment.Center) {
            Text(">_", color = Color(0xFF8BE9A0), fontFamily = FontFamily.Monospace, fontSize = (size * 0.38f).sp)
        }
    } else if (b != null) {
        Image(b, null, Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        // no icon on the machine: a letter on a colour made from the app's name
        val hue = (app.hashCode().mod(360)).toFloat()
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(Color.hsv(hue, 0.45f, 0.55f)), contentAlignment = Alignment.Center) {
            Text((app.substringAfterLast('.').ifBlank { title }.firstOrNull() ?: '?').uppercaseChar().toString(), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun WindowsScreen() {
    val ctx = LocalContext.current
    val m = Hc.cur ?: return
    val scope = rememberCoroutineScope()
    // asked without Machine.call(): an older daemon answers 404, which must not mark the whole machine offline
    var tooOld by remember(m.id) { mutableStateOf(false) }
    val data by loaded(m.id, everyMs = 2000, cacheId = "windows") {
        val (code, j) = m.getSlow("/api/windows", 8000)
        tooOld = code == 404
        j
    }
    // sessions that need an answer first, then the ones at work, then everything in the compositor's order
    val cards = remember(data) {
        data?.optJSONArray("windows")?.objs()?.map { WinCard(it) }.orEmpty()
            .sortedBy { c -> when { c.claude?.needsYou == true -> 0; c.claude?.working == true -> 1; c.claude != null -> 2; else -> 3 } }
    }
    var openId by remember { mutableStateOf(Hc.memStr("windows.open").ifBlank { null }) }
    // a Claude Code card opens its conversation; this switches it to the raw terminal instead
    var raw by remember { mutableStateOf(false) }
    fun open(id: String?) { openId = id; raw = false; Hc.memPutStr("windows.open", id.orEmpty()) }
    var closing by remember { mutableStateOf<WinCard?>(null) }
    var filter by remember { mutableStateOf(Hc.memStr("windows.filter")) }

    val opened = cards.firstOrNull { it.id == openId }
    if (opened != null) {
        val info = opened.claude
        when {
            info != null && !raw -> {
                BackHandler { open(null) }
                ClaudeView(opened.term, info, onTerminal = { raw = true }, onBack = { open(null) })
            }
            opened.isTerminal -> {
                BackHandler { if (info != null) raw = false else open(null) }
                TerminalView(opened) { if (info != null) raw = false else open(null) }
            }
            else -> {
                BackHandler { open(null) }
                WindowView(
                    opened.id, opened.title, opened.app, opened.place, icon = { AppIcon(opened.app, opened.title, 28) },
                    onClose = { scope.launch { Hc.post("/api/windows/${opened.id}/close"); open(null) } }, onBack = { open(null) },
                )
            }
        }
        return
    }
    val shown = cards.filter { filter.isBlank() || it.group == filter }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Windows", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        tooOld -> "needs homectl 0.18.0 or newer on this machine"
                        data == null -> "loading…"
                        cards.isEmpty() -> "nothing open"
                        else -> listOfNotNull("${cards.size} open", cards.count { it.claude != null }.takeIf { it > 0 }?.let { "$it Claude Code" },
                            cards.count { it.claude?.needsYou == true }.takeIf { it > 0 }?.let { "$it waiting for you" }).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // one chip per kind of window, with its icon and how many are open; tap to show only those
        if (cards.size > 1) {
            val groups = cards.groupBy { it.group }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter.isBlank(), { filter = ""; Hc.memPutStr("windows.filter", "") }, label = { Text("All ${cards.size}") })
                groups.forEach { (g, list) ->
                    val c = list.first()
                    FilterChip(filter == g, { filter = if (filter == g) "" else g; Hc.memPutStr("windows.filter", filter) },
                        leadingIcon = { if (g == "claude") ClaudeMark(18) else AppIcon(c.app, c.title, 18) },
                        label = { Text("${appLabel(g)} ${list.size}") })
                }
            }
        }
        if (tooOld) {
            Text("Update the homectl daemon on ${m.name} (System shows its version), then this tab fills in.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        } else if (data != null && cards.none { it.isTerminal }) {
            Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Mirror a terminal", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Start your shell through homectl-pty in a terminal window (for example `homectl-pty` instead of `fish`), and its text, colours and scrollback appear here, and you can type into it. Other windows are listed with their title and icon.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.id }) { c ->
                WindowCard(
                    c, Modifier.animateItem(),
                    onOpen = { open(c.id) },
                    onFocus = { scope.launch { Hc.post("/api/windows/${c.id}/focus") } },
                    onClose = { closing = c },
                )
            }
        }
    }

    closing?.let { c ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("Close this window?") },
            text = { Text("“${c.title.ifBlank { c.app }}” will be closed on ${m.name}. Unsaved work in it may be lost.") },
            confirmButton = { TextButton({
                closing = null
                scope.launch {
                    val ok = Hc.post("/api/windows/${c.id}/close")
                    android.widget.Toast.makeText(ctx, if (ok) "Close requested. Programs that ask first (unsaved work, a running command) show their question on ${m.name}." else "Could not close it: the machine refused or the window is already gone.", android.widget.Toast.LENGTH_LONG).show()
                }
            }) { Text("Close it") } },
            dismissButton = { TextButton({ closing = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun WindowCard(c: WinCard, modifier: Modifier, onOpen: () -> Unit, onFocus: () -> Unit, onClose: () -> Unit) {
    // The desktop's focus is deliberately not shown: it moves for reasons that have nothing to do with this list (notifications,
    // the mouse), and a ring that jumps between cards made the list look like it was misbehaving.
    val ring = Color.Transparent
    var flash by remember { mutableFloatStateOf(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(c.preview) {
        if (first) { first = false; return@LaunchedEffect }
        flash = 1f
        animate(1f, 0f, animationSpec = tween(700)) { v, _ -> flash = v }
    }
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier.fillMaxWidth().border(2.dp, ring, RoundedCornerShape(14.dp)).clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cl = c.claude
            if (cl != null) ClaudeMark() else AppIcon(c.app, c.title)
            Column(Modifier.weight(1f)) {
                Text(cl?.name?.ifBlank { null } ?: c.title.ifBlank { c.app.ifBlank { "window" } }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (cl != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(cl)
                    Text(listOfNotNull(claudeStatus(cl), c.cwd.takeIf { it.isNotBlank() }?.let { shortPath(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (cl.needsYou) ClaudeOrange else Color.Unspecified)
                }
                else Text(
                    listOfNotNull(appLabel(c.app).takeIf { c.app.isNotBlank() && c.app != "terminal" }, c.cwd.takeIf { it.isNotBlank() }?.let { shortPath(it) },
                        c.place?.let { p -> if (p.optBoolean("visible")) "on ${p.str("output")}" else "hidden workspace" }, if (c.isTerminal) "mirrored" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Window actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Bring to front on the machine") }, onClick = { menu = false; onFocus() })
                    if (c.isTerminal) DropdownMenuItem(text = { Text("Open here") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Close window") }, onClick = { menu = false; onClose() })
                }
            }
        }
        val cl = c.claude
        if (cl != null) {
            if (cl.last.isNotBlank()) Text(
                (if (cl.lastFromUser) "You: " else "") + plainPreview(cl.last),
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        } else if (!c.isTerminal && c.place?.optBoolean("visible") == true && c.place.optBoolean("active")) {
            Thumbnail(c)
        } else if (c.isTerminal && c.preview.isNotEmpty()) {
            val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f * flash)
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF111418)).background(tint).padding(8.dp).animateContentSize(),
            ) {
                if (c.pv.isNotEmpty()) c.pv.forEach { Text(styled(it), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Clip) }
                else c.preview.forEach { Text(it, color = Color(0xFFD7DAE0), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Clip) }
            }
        }
    }
}

private fun shortPath(p: String) = p.replace(Regex("^/home/[^/]+"), "~")

/** A message as one line of plain text: no code fences, emphasis marks or list bullets. */
private fun plainPreview(t: String) = t.replace("```", " ").replace("**", "").replace("`", "").lines()
    .joinToString(" ") { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("# ") }.replace(Regex("\\s+"), " ").trim()

/** A short name for an app id: "com.mitchellh.ghostty" -> "ghostty". */
private fun appLabel(app: String) = when (app) { "claude" -> "Claude"; "" -> "window"; else -> app.substringAfterLast('.') }

/** A small picture of a window that is on a screen right now, refreshed every few seconds while the list is open. */
@Composable
private fun Thumbnail(c: WinCard) {
    val out = c.place?.str("output").orEmpty()
    val rect = c.place?.optJSONArray("rect")?.let { a -> if (a.length() == 4) (0 until 4).joinToString(",") { a.optDouble(it).toString() } else null }
    var img by remember(c.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(c.id, out, rect) {
        while (out.isNotEmpty()) {
            val b = Hc.cur?.bytes("/api/screens/${android.net.Uri.encode(out)}/frame?w=560&q=60" + (rect?.let { "&crop=$it" } ?: ""), 2_000_000)
            b?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { img = it.asImageBitmap() }
            delay(8000)
        }
    }
    img?.let { Image(it, "What the window shows", Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
}

// ---- the live terminal ----

private class Run(val text: String, val fg: Int, val bg: Int, val at: Int) {
    /** Columns this run covers: wide characters take two. */
    val cols = text.codePoints().toArray().sumOf { cpWidth(it) }
}

private fun cpWidth(cp: Int): Int = when {
    cp < 0x300 -> 1
    cp in 0x300..0x36F || cp in 0x200B..0x200F || cp in 0xFE00..0xFE0F || cp == 0x20E3 -> 0
    cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE6F ||
        cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1F64F || cp in 0x1F900..0x1F9FF || cp in 0x20000..0x3FFFD -> 2
    else -> 1
}

private class Screen(
    val seq: Long, val cols: Int, val rows: Int, val cx: Int, val cy: Int, val cursor: Boolean, val alt: Boolean,
    val title: String, val sb: Int, val lines: List<List<Run>>, val mouse: Int = 0, val sbt: Int = 0,
)

private fun parseScreen(j: JSONObject): Screen {
    val ls = j.getJSONArray("lines")
    val lines = (0 until ls.length()).map { i ->
        val rs: JSONArray = ls.getJSONArray(i)
        (0 until rs.length()).map { k -> rs.getJSONArray(k).let { Run(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3)) } }
    }
    return Screen(j.getLong("seq"), j.getInt("cols"), j.getInt("rows"), j.getInt("cx"), j.getInt("cy"), j.optBoolean("cv", true), j.optBoolean("alt"), j.str("title"), j.optInt("sb"), lines, j.optInt("mouse"), j.optInt("sbt"))
}

/** A line of runs as styled text, for the small previews on the cards. */
private fun styled(runs: List<Run>): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    for (r in runs) {
        val bold = r.at and 1 != 0
        var f = colorOf(r.fg, DEFAULT_FG, bold)
        var b = if (r.bg >= 0) colorOf(r.bg, 0) else 0
        if (r.at and 16 != 0) { val t = f; f = if (r.bg >= 0) b else DEFAULT_BG; b = t }
        pushStyle(androidx.compose.ui.text.SpanStyle(
            color = Color(f), background = if (b != 0) Color(b) else Color.Unspecified,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
            textDecoration = if (r.at and 8 != 0) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        ))
        append(r.text)
        pop()
    }
}

private val ansi16 = intArrayOf(
    0xFF1D1F21.toInt(), 0xFFCC6666.toInt(), 0xFFB5BD68.toInt(), 0xFFF0C674.toInt(), 0xFF81A2BE.toInt(), 0xFFB294BB.toInt(), 0xFF8ABEB7.toInt(), 0xFFC5C8C6.toInt(),
    0xFF666666.toInt(), 0xFFD54E53.toInt(), 0xFFB9CA4A.toInt(), 0xFFE7C547.toInt(), 0xFF7AA6DA.toInt(), 0xFFC397D8.toInt(), 0xFF70C0B1.toInt(), 0xFFEAEAEA.toInt(),
)
private const val DEFAULT_FG = 0xFFD7DAE0.toInt()
private const val DEFAULT_BG = 0xFF111418.toInt()

private fun cube(x: Int) = if (x == 0) 0 else 55 + x * 40

private fun colorOf(v: Int, default: Int, bold: Boolean = false): Int = when {
    v < 0 -> default
    v < 8 -> ansi16[if (bold) v + 8 else v]
    v < 16 -> ansi16[v]
    v < 232 -> (0xFF shl 24) or (cube((v - 16) / 36) shl 16) or (cube((v - 16) / 6 % 6) shl 8) or cube((v - 16) % 6)
    v < 256 -> { val g = 8 + (v - 232) * 10; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
    else -> (0xFF shl 24) or (v - 256)
}

/** A selection of terminal text: two (line, column) points in the order they were pressed. */
private class Sel(val a: Pair<Int, Int>, val b: Pair<Int, Int>) {
    val from get() = if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a else b
    val to get() = if (from === a) b else a
}

/** One entry per screen cell (a wide character is followed by an empty one), so columns can be turned into text exactly. */
private fun cellsOf(runs: List<Run>): List<String> {
    val out = ArrayList<String>()
    for (r in runs) {
        var i = 0
        while (i < r.text.length) {
            val cp = r.text.codePointAt(i)
            val n = Character.charCount(cp)
            val w = cpWidth(cp)
            if (w > 0) { out.add(String(Character.toChars(cp))); repeat(w - 1) { out.add("") } }
            i += n
        }
    }
    return out
}

private fun selectedText(s: Screen, sel: Sel): String {
    val f = sel.from; val t = sel.to
    return (f.first..t.first.coerceAtMost(s.lines.size - 1)).joinToString("\n") { l ->
        val cells = cellsOf(s.lines.getOrElse(l) { emptyList() })
        val lo = if (l == f.first) f.second else 0
        val hi = if (l == t.first) t.second else cells.size - 1
        if (lo >= cells.size) "" else cells.subList(lo.coerceAtLeast(0), (hi + 1).coerceAtMost(cells.size)).joinToString("").trimEnd()
    }.trimEnd()
}

@Composable
private fun TerminalView(c: WinCard, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var screen by remember(c.term) { mutableStateOf<Screen?>(null) }
    var lost by remember(c.term) { mutableStateOf(false) }
    val keys = remember(c.term) { Channel<String>(Channel.UNLIMITED) }
    // how many lines of history are asked for: a few at first, more as you scroll up towards the top (the machine keeps 5000)
    var back by remember(c.term) { mutableIntStateOf(300) }

    // the machine pushes a change as soon as it happens; this just keeps asking until something differs
    LaunchedEffect(c.term, back) {
        var seq = 0L
        var fails = 0
        while (true) {
            val (code, j) = Hc.cur?.getSlow("/api/windows/${c.term}/screen?since=$seq&back=$back&wait=12", 30_000) ?: (0 to null)
            if (j != null && !j.optBoolean("same")) {
                runCatching { parseScreen(j) }.getOrNull()?.let { screen = it; seq = it.seq }
                fails = 0; lost = false
            } else if (code == 404) {
                lost = true; delay(2000)
            } else if (j == null) {
                if (++fails > 1) lost = true
                delay(1000)
            }
        }
    }
    // keys go out one at a time, in order
    LaunchedEffect(c.term) { for (k in keys) Hc.post("/api/windows/${c.term}/input", body("text" to k)) }

    val mods = remember { KeyMods() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var jump by remember { mutableIntStateOf(0) }
    var fitReq by remember { mutableIntStateOf(0) }
    var live by remember { mutableStateOf(false) } // typing goes out letter by letter instead of as a line
    var sel by remember { mutableStateOf<Sel?>(null) }
    var input by remember { mutableStateOf<TermInput?>(null) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val clip = LocalClipboardManager.current
    // the bottom dock slides away while the keyboard is up and comes back when it closes
    val imeUp = Hc.imeUp
    LaunchedEffect(imeUp) { Hc.dockHidden = imeUp }
    DisposableEffect(Unit) { Hc.fullView = true; onDispose { Hc.dockHidden = false; Hc.fullView = false } }

    /** Everything typed or pressed goes through here, so Ctrl and Alt apply to the next key whichever way it came. */
    val emitRef = remember { mutableStateOf<(String) -> Unit>({}) }
    fun emit(raw: String) {
        var t = raw
        if (mods.ctrl && t.length == 1 && (t[0] in '@'..'_' || t[0] in 'a'..'z')) t = (t[0].code and 0x1f).toChar().toString()
        if (mods.ctrl) mods.ctrl = false
        if (mods.alt) { t = "\u001b$t"; mods.alt = false }
        keys.trySend(t)
    }
    emitRef.value = ::emit
    val host = androidx.compose.ui.platform.LocalView.current
    val imm = remember { ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager }
    // The text box keeps its focus when the keyboard is closed, and Compose's own show() is then ignored: restart the input session
    // and ask the system directly.
    fun showKeyboard() {
        if (live) input?.show()
        else { focus.requestFocus(); host.post { imm.restartInput(host); imm.showSoftInput(host, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); kb?.show() } }
    }
    fun hideKeyboard() { if (live) input?.hide() else { imm.hideSoftInputFromWindow(host.windowToken, 0); kb?.hide() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back to the windows") }
            AppIcon(c.app, c.title, 28)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(screen?.title?.ifBlank { null } ?: c.title.ifBlank { c.cmd }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(c.cwd.takeIf { it.isNotBlank() }, screen?.let { "${it.cols}×${it.rows}" }, if (lost) "disconnected" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (lost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton({ scope.launch { Hc.post("/api/windows/${c.id}/focus") } }) { Icon(Icons.Default.Place, "Bring to front on the machine") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(DEFAULT_BG))) {
            val s = screen
            if (s == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else ScreenCanvas(
                s, zoom, jump, fitReq, sel, onFit = { zoom = it },
                onCell = { col, row ->
                    // a tap is a click for programs that asked for the mouse, and otherwise brings up the keyboard
                    if (sel != null) sel = null
                    else if (s.mouse > 0) emit("\u001b[<0;${col + 1};${row + 1}M\u001b[<0;${col + 1};${row + 1}m") else showKeyboard()
                },
                onNearTop = { if (s.sbt > s.sb && back < 5000) back = minOf(5000, maxOf(back * 2, back + 500)) },
                onSelect = { sel = it },
            )
            if (live) androidx.compose.ui.viewinterop.AndroidView({ c0 -> TermInput(c0) { t -> emitRef.value(t) }.also { input = it } }, Modifier.size(1.dp))
            sel?.let { sl ->
                Row(Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 6.dp)) {
                    TextButton({
                        screen?.let { sc -> clip.setText(AnnotatedString(selectedText(sc, sl))) }
                        android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show(); sel = null
                    }) { Text("Copy", color = MaterialTheme.colorScheme.inversePrimary) }
                    TextButton({ sel = null }) { Text("Cancel", color = MaterialTheme.colorScheme.inverseOnSurface) }
                }
            }
        }
        TerminalKeys(
            mods, ::emit, live,
            toggleKeyboard = { if (Hc.imeUp) hideKeyboard() else showKeyboard() }, // read live: a captured value would be from an earlier moment
            toggleLive = { live = !live; sel = null },
            fit = { fitReq++ },
            paste = { clip.getText()?.text?.takeIf { it.isNotEmpty() }?.let { emit(it) } },
            copy = { screen?.let { sc -> clip.setText(AnnotatedString(sc.lines.joinToString("\n") { l -> l.joinToString("") { it.text }.trimEnd() }.trimEnd())) } },
            smaller = { zoom = (zoom * 0.9f).coerceAtLeast(0.5f) }, bigger = { zoom = (zoom * 1.1f).coerceAtMost(2.5f) }, bottom = { jump++ },
        )
        if (!live) CommandBar(focus) { keys.trySend(it) }
    }
}

/** Ctrl and Alt stay pressed until the next key. */
private class KeyMods { var ctrl by mutableStateOf(false); var alt by mutableStateOf(false); var more by mutableStateOf(false) }

/** The ordinary text box and Send button: a line is typed here, with the cursor where you tap, and goes out with Enter. */
@Composable
private fun CommandBar(focus: FocusRequester, send: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun go() { send(text + "\r"); text = "" }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text, { text = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true, placeholder = { Text("Type a command") },
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = { go() }),
        )
        IconButton({ go() }) { Icon(Icons.Default.Send, "Send") }
    }
}

/** Font size is a fixed, readable 13 sp (what the Terminal tab uses); wide windows scroll sideways instead of shrinking. */
private const val BASE_SP = 13f

@Composable
private fun ScreenCanvas(
    s: Screen, zoom: Float, jump: Int, fitReq: Int, sel: Sel?, onFit: (Float) -> Unit,
    onCell: (Int, Int) -> Unit, onNearTop: () -> Unit, onSelect: (Sel?) -> Unit,
) {
    val density = LocalDensity.current
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blink")
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val screenNow by rememberUpdatedState(s)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = maxWidth
        val vh = maxHeight
        val probe = remember { Paint().apply { typeface = Typeface.MONOSPACE } }
        probe.textSize = 100f
        val ratio = probe.measureText("M") / 100f
        val availW = with(density) { vw.toPx() } - 8f
        val basePx = with(density) { BASE_SP.sp.toPx() }
        val px = basePx * zoom
        val cw = px * ratio
        val ch = px * 1.25f
        val totalW = cw * s.cols + 8f
        val totalH = ch * s.lines.size + 8f
        LaunchedEffect(fitReq) { if (fitReq > 0) onFit((availW / (s.cols * ratio) / basePx).coerceIn(0.4f, 2.5f)) }
        // follow the bottom unless the person scrolled up; scrolling back down to the end follows again
        var follow by remember { mutableStateOf(true) }
        // only a finger (or a fling) decides whether to keep following; the content growing must not, or the view would stay at the top
        LaunchedEffect(Unit) { snapshotFlow { vScroll.isScrollInProgress to vScroll.value }.collect { (moving, _) -> if (moving) follow = vScroll.maxValue - vScroll.value < ch * 2 } }
        // near the top of what is loaded: ask the machine for more history
        LaunchedEffect(Unit) { snapshotFlow { vScroll.value }.collect { v -> if (v < ch * 80) onNearTop() } }
        LaunchedEffect(jump) { if (jump > 0) { follow = true; vScroll.scrollTo(vScroll.maxValue) } }
        LaunchedEffect(s.seq, totalH) { if (follow && !vScroll.isScrollInProgress) vScroll.scrollTo(vScroll.maxValue) }
        fun cellAt(o: Offset) = (((o.y - 4f) / ch).toInt().coerceIn(0, (screenNow.lines.size - 1).coerceAtLeast(0))) to (((o.x - 4f) / cw).toInt().coerceIn(0, screenNow.cols - 1))
        var anchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        Box(
            Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)
                .pointerInput(cw, ch, s.sb, s.cols, s.rows) {
                    detectTapGestures { o ->
                        val (line, col) = cellAt(o)
                        onCell(col, (line - screenNow.sb).coerceIn(0, screenNow.rows - 1))
                    }
                }
                .pointerInput(cw, ch) {
                    // press and hold, then drag: selects text; the Copy bar appears on the screen
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o -> anchor = cellAt(o); onSelect(Sel(anchor!!, anchor!!)); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                        onDrag = { change, _ -> change.consume(); anchor?.let { onSelect(Sel(it, cellAt(change.position))) } },
                        onDragEnd = { anchor = null }, onDragCancel = { anchor = null },
                    )
                },
        ) {
            val h = with(density) { totalH.toDp() }
            val w = with(density) { totalW.toDp() }
            androidx.compose.foundation.Canvas(Modifier.size(w.coerceAtLeast(vw), h.coerceAtLeast(vh))) {
                val fg = Paint().apply { typeface = Typeface.MONOSPACE; textSize = px; isAntiAlias = true }
                val bold = Paint(fg).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
                val italic = Paint(fg).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC) }
                val baseline = ch * 0.82f
                drawIntoCanvas { cv ->
                    val nc = cv.nativeCanvas
                    s.lines.forEachIndexed { y, runs ->
                        var x = 0
                        val top = 4f + y * ch
                        for (r in runs) {
                            val isBold = r.at and 1 != 0
                            var f = colorOf(r.fg, DEFAULT_FG, isBold)
                            var b = colorOf(r.bg, 0)
                            val hasBg = r.bg >= 0
                            val inv = r.at and 16 != 0
                            if (inv) { val t = if (r.fg < 0) DEFAULT_FG else f; f = if (hasBg) b else DEFAULT_BG; b = t }
                            if (hasBg || inv) {
                                drawRect(Color(b), Offset(4f + x * cw, top), Size(r.cols * cw, ch))
                            }
                            val p = when { isBold -> bold; r.at and 4 != 0 -> italic; else -> fg }
                            p.color = if (r.at and 2 != 0) (f and 0x00FFFFFF) or (0x99 shl 24) else f
                            // draw per cell so wide and narrow glyphs keep their columns
                            var cx = x
                            var i = 0
                            while (i < r.text.length) {
                                val cp = r.text.codePointAt(i)
                                val n = Character.charCount(cp)
                                val wcols = cpWidth(cp)
                                if (cp != 0x20 && wcols > 0) nc.drawText(r.text, i, i + n, 4f + cx * cw, top + baseline, p)
                                cx += wcols; i += n
                            }
                            if (r.at and 8 != 0) drawRect(Color(f), Offset(4f + x * cw, top + ch - 3f), Size(r.cols * cw, 1.5f))
                            if (r.at and 32 != 0) drawRect(Color(f), Offset(4f + x * cw, top + ch * 0.55f), Size(r.cols * cw, 1.5f))
                            x += r.cols
                        }
                    }
                }
                sel?.let { sl ->
                    val f = sl.from; val t = sl.to
                    for (line in f.first..t.first) {
                        val lo = if (line == f.first) f.second else 0
                        val hi = if (line == t.first) t.second else s.cols - 1
                        drawRect(Color(0x663D7EFF), Offset(4f + lo * cw, 4f + line * ch), Size((hi - lo + 1) * cw, ch))
                    }
                }
                if (s.cursor) {
                    val row = s.sb + s.cy
                    drawRect(Color(0xFF8AB4F8).copy(alpha = blink), Offset(4f + s.cx * cw, 4f + row * ch), Size(cw, ch))
                }
            }
        }
    }
}

/** One key of the bar: `seq` is sent as is, `act` names a local action; `repeat` keys auto-repeat while held. */
private class K(val label: String, val seq: String? = null, val act: String? = null, val repeat: Boolean = false)

// the same keys as the Terminal tab (assets/term.html)
private val keysMain = listOf(
    K("⋯", act = "more"), K("Esc", "\u001b"), K("Tab", "\t"), K("Ctrl", act = "ctrl"), K("Alt", act = "alt"),
    K("↑", "\u001b[A", repeat = true), K("↓", "\u001b[B", repeat = true), K("←", "\u001b[D", repeat = true), K("→", "\u001b[C", repeat = true),
    K("Home", "\u001b[H"), K("End", "\u001b[F"), K("PgUp", "\u001b[5~", repeat = true), K("PgDn", "\u001b[6~", repeat = true), K("Del", "\u001b[3~", repeat = true),
    K("|", "|"), K("~", "~"), K("/", "/"), K("-", "-"), K("_", "_"), K("\\", "\\"), K("$", "$"), K("&", "&"), K("*", "*"),
    K("F1", "\u001bOP"), K("F2", "\u001bOQ"), K("F3", "\u001bOR"), K("F4", "\u001bOS"), K("F5", "\u001b[15~"), K("F6", "\u001b[17~"), K("F7", "\u001b[18~"),
    K("F8", "\u001b[19~"), K("F9", "\u001b[20~"), K("F10", "\u001b[21~"), K("F11", "\u001b[23~"), K("F12", "\u001b[24~"),
)
private val keysTools = listOf(
    K("⌨", act = "kb"), K("Live", act = "live"), K("Paste", act = "paste"), K("Copy", act = "copy"), K("A−", act = "fm"), K("A+", act = "fp"), K("Fit", act = "fit"), K("⇣", act = "bottom"),
    K("^C", "\u0003"), K("^D", "\u0004"), K("^Z", "\u001a"), K("^L", "\u000c"), K("^R", "\u0012"), K("^A", "\u0001"), K("^E", "\u0005"), K("^W", "\u0017"), K("^U", "\u0015"),
)

@Composable
private fun TerminalKeys(
    mods: KeyMods, emit: (String) -> Unit, live: Boolean, toggleKeyboard: () -> Unit, toggleLive: () -> Unit, fit: () -> Unit,
    paste: () -> Unit, copy: () -> Unit, smaller: () -> Unit, bigger: () -> Unit, bottom: () -> Unit,
) {
    fun run(k: K) {
        when (k.act) {
            null -> emit(k.seq ?: return)
            "more" -> mods.more = !mods.more
            "ctrl" -> mods.ctrl = !mods.ctrl
            "alt" -> mods.alt = !mods.alt
            "kb" -> toggleKeyboard()
            "live" -> toggleLive()
            "fit" -> fit()
            "paste" -> paste(); "copy" -> copy(); "fm" -> smaller(); "fp" -> bigger(); "bottom" -> bottom()
        }
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 6.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // the tools row opens ABOVE the main row (the Terminal tab does the same)
        androidx.compose.animation.AnimatedVisibility(mods.more) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { keysTools.forEach { KeyButton(it, it.act == "live" && live, ::run) } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            keysMain.forEach { KeyButton(it, (it.act == "ctrl" && mods.ctrl) || (it.act == "alt" && mods.alt) || (it.act == "more" && mods.more), ::run) }
        }
    }
}

/** Fires on release, so swiping along the row doesn't press keys; holding a navigation key repeats it. */
@Composable
private fun KeyButton(k: K, on: Boolean, run: (K) -> Unit) {
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(10.dp)
    // the press handler below is started once; it must call the current action, not the one from the first composition
    val runNow by rememberUpdatedState(run)
    Box(
        Modifier.height(36.dp).widthIn(min = 40.dp).clip(shape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
            .pointerInput(k) {
                detectTapGestures(onPress = {
                    var repeating = false
                    val job = if (k.repeat) scope.launch { delay(380); repeating = true; while (true) { runNow(k); delay(55) } } else null
                    val released = tryAwaitRelease()
                    job?.cancel()
                    if (released && !repeating) runNow(k)
                })
            }.padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(k.label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}
