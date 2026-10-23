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
    val cwd = j.str("cwd")
    val cmd = j.str("cmd")
    val isTerminal get() = term.isNotEmpty()
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
    if (b != null) {
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
    val m = Hc.cur ?: return
    val scope = rememberCoroutineScope()
    // asked without Machine.call(): an older daemon answers 404, which must not mark the whole machine offline
    var tooOld by remember(m.id) { mutableStateOf(false) }
    val data by loaded(m.id, everyMs = 2000, cacheId = "windows") {
        val (code, j) = m.getSlow("/api/windows", 8000)
        tooOld = code == 404
        j
    }
    val cards = remember(data) { data?.optJSONArray("windows")?.objs()?.map { WinCard(it) }.orEmpty() }
    var openId by remember { mutableStateOf(Hc.memStr("windows.open").ifBlank { null }) }
    fun open(id: String?) { openId = id; Hc.memPutStr("windows.open", id.orEmpty()) }
    var closing by remember { mutableStateOf<WinCard?>(null) }

    val opened = cards.firstOrNull { it.id == openId }
    if (opened != null && opened.isTerminal) {
        BackHandler { open(null) }
        TerminalView(opened) { open(null) }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Windows", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        tooOld -> "needs homectl 0.18.0 or newer on this machine"
                        data == null -> "loading…"
                        cards.isEmpty() -> "nothing open"
                        else -> "${cards.size} open · ${cards.count { it.isTerminal }} mirrored"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
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
            items(cards, key = { it.id }) { c ->
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
            confirmButton = { TextButton({ closing = null; scope.launch { Hc.post("/api/windows/${c.id}/close") } }) { Text("Close it") } },
            dismissButton = { TextButton({ closing = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun WindowCard(c: WinCard, modifier: Modifier, onOpen: () -> Unit, onFocus: () -> Unit, onClose: () -> Unit) {
    val ring by animateColorAsState(if (c.focused) MaterialTheme.colorScheme.primary else Color.Transparent, tween(400), label = "focus")
    var flash by remember { mutableFloatStateOf(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(c.preview) {
        if (first) { first = false; return@LaunchedEffect }
        flash = 1f
        animate(1f, 0f, animationSpec = tween(700)) { v, _ -> flash = v }
    }
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier.fillMaxWidth().border(2.dp, ring, RoundedCornerShape(14.dp)).then(if (c.isTerminal) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(c.app, c.title)
            Column(Modifier.weight(1f)) {
                Text(c.title.ifBlank { c.app.ifBlank { "window" } }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(c.app.takeIf { it.isNotBlank() }, c.workspace.takeIf { it.isNotBlank() }?.let { "workspace $it" }, if (c.isTerminal) "mirrored" else null).joinToString(" · "),
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
        if (c.isTerminal && c.preview.isNotEmpty()) {
            val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f * flash)
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF111418)).background(tint).padding(8.dp).animateContentSize(),
            ) {
                c.preview.forEach { Text(it, color = Color(0xFFD7DAE0), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Clip) }
            }
        }
    }
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
    val title: String, val sb: Int, val lines: List<List<Run>>,
)

private fun parseScreen(j: JSONObject): Screen {
    val ls = j.getJSONArray("lines")
    val lines = (0 until ls.length()).map { i ->
        val rs: JSONArray = ls.getJSONArray(i)
        (0 until rs.length()).map { k -> rs.getJSONArray(k).let { Run(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3)) } }
    }
    return Screen(j.getLong("seq"), j.getInt("cols"), j.getInt("rows"), j.getInt("cx"), j.getInt("cy"), j.optBoolean("cv", true), j.optBoolean("alt"), j.str("title"), j.optInt("sb"), lines)
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

@Composable
private fun TerminalView(c: WinCard, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var screen by remember(c.term) { mutableStateOf<Screen?>(null) }
    var lost by remember(c.term) { mutableStateOf(false) }
    val keys = remember(c.term) { Channel<String>(Channel.UNLIMITED) }

    // the machine pushes a change as soon as it happens; this just keeps asking until something differs
    LaunchedEffect(c.term) {
        var seq = 0L
        var fails = 0
        while (true) {
            val (code, j) = Hc.cur?.getSlow("/api/windows/${c.term}/screen?since=$seq&back=400&wait=12", 30_000) ?: (0 to null)
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
            else ScreenCanvas(s)
        }
        InputBar { keys.trySend(it) }
    }
}

@Composable
private fun ScreenCanvas(s: Screen) {
    val density = LocalDensity.current
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blink")
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = maxWidth
        val vh = maxHeight
        val minPx = with(density) { 9.sp.toPx() }
        val probe = remember { Paint().apply { typeface = Typeface.MONOSPACE } }
        probe.textSize = 100f
        val ratio = probe.measureText("M") / 100f
        val availW = with(density) { vw.toPx() } - 8f
        val fit = availW / (s.cols * ratio)
        val px = maxOf(fit, minPx).coerceAtMost(with(density) { 16.sp.toPx() })
        val cw = px * ratio
        val ch = px * 1.25f
        val totalW = cw * s.cols + 8f
        val totalH = ch * s.lines.size + 8f
        // follow the bottom unless the person scrolled up
        LaunchedEffect(s.seq, totalH) {
            if (vScroll.maxValue - vScroll.value < ch * 3 || vScroll.value == 0) vScroll.scrollTo(vScroll.maxValue)
        }
        Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
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
                if (s.cursor) {
                    val row = s.sb + s.cy
                    drawRect(Color(0xFF8AB4F8).copy(alpha = blink), Offset(4f + s.cx * cw, 4f + row * ch), Size(cw, ch))
                }
            }
        }
    }
}

private val keyRow = listOf(
    "Esc" to "\u001b", "Tab" to "\t", "^C" to "\u0003", "^D" to "\u0004", "^L" to "\u000c", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
    "Home" to "\u001b[H", "End" to "\u001b[F", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~",
)

@Composable
private fun InputBar(send: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            keyRow.forEach { (label, seq) -> FilledTonalButton({ send(seq) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) } }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Type a command") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send(text + "\r"); text = "" }),
            )
            IconButton({ send(text + "\r"); text = "" }) { Icon(Icons.Default.Send, "Send") }
        }
    }
}
