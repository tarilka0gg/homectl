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
        modifier.fillMaxWidth().border(2.dp, ring, RoundedCornerShape(14.dp)).then(if (c.isTerminal) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(c.app, c.title)
            Column(Modifier.weight(1f)) {
                Text(c.title.ifBlank { c.app.ifBlank { "window" } }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(c.app.takeIf { it.isNotBlank() && it != "terminal" }, c.cwd.takeIf { it.isNotBlank() }?.let { shortPath(it) }, c.workspace.takeIf { it.isNotBlank() }?.let { "workspace $it" }, if (c.isTerminal) "mirrored" else null).joinToString(" · "),
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
                if (c.pv.isNotEmpty()) c.pv.forEach { Text(styled(it), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Clip) }
                else c.preview.forEach { Text(it, color = Color(0xFFD7DAE0), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Clip) }
            }
        }
    }
}

private fun shortPath(p: String) = p.replace(Regex("^/home/[^/]+"), "~")

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
    val title: String, val sb: Int, val lines: List<List<Run>>, val mouse: Int = 0,
)

private fun parseScreen(j: JSONObject): Screen {
    val ls = j.getJSONArray("lines")
    val lines = (0 until ls.length()).map { i ->
        val rs: JSONArray = ls.getJSONArray(i)
        (0 until rs.length()).map { k -> rs.getJSONArray(k).let { Run(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3)) } }
    }
    return Screen(j.getLong("seq"), j.getInt("cols"), j.getInt("rows"), j.getInt("cx"), j.getInt("cy"), j.optBoolean("cv", true), j.optBoolean("alt"), j.str("title"), j.optInt("sb"), lines, j.optInt("mouse"))
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
            val (code, j) = Hc.cur?.getSlow("/api/windows/${c.term}/screen?since=$seq&back=1000&wait=12", 30_000) ?: (0 to null)
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
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val clip = LocalClipboardManager.current
    // the bottom dock slides away while the keyboard is up and comes back when it closes
    val imeUp = WindowInsets.isImeVisible
    LaunchedEffect(imeUp) { Hc.dockHidden = imeUp }
    DisposableEffect(Unit) { onDispose { Hc.dockHidden = false } }

    /** Everything typed or pressed goes through here, so Ctrl and Alt apply to the next key whichever way it came. */
    fun emit(raw: String) {
        var t = raw
        if (mods.ctrl && t.length == 1 && (t[0] in '@'..'_' || t[0] in 'a'..'z')) t = (t[0].code and 0x1f).toChar().toString()
        if (mods.ctrl) mods.ctrl = false
        if (mods.alt) { t = "\u001b$t"; mods.alt = false }
        keys.trySend(t)
    }
    fun showKeyboard() { focus.requestFocus(); kb?.show() }

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
            else ScreenCanvas(s, zoom, jump) { col, row ->
                // a tap is a click for programs that asked for the mouse, and otherwise brings up the keyboard
                if (s.mouse > 0) emit("\u001b[<0;${col + 1};${row + 1}M\u001b[<0;${col + 1};${row + 1}m") else showKeyboard()
            }
        }
        TerminalKeys(
            mods, ::emit,
            toggleKeyboard = { if (imeUp) kb?.hide() else showKeyboard() },
            paste = { clip.getText()?.text?.takeIf { it.isNotEmpty() }?.let { emit(it) } },
            copy = { screen?.let { sc -> clip.setText(AnnotatedString(sc.lines.joinToString("\n") { l -> l.joinToString("") { it.text }.trimEnd() }.trimEnd())) } },
            smaller = { zoom = (zoom * 0.9f).coerceAtLeast(0.6f) }, bigger = { zoom = (zoom * 1.1f).coerceAtMost(2.2f) }, bottom = { jump++ },
        )
        CommandBar(focus) { keys.trySend(it) }
    }
}

/** Ctrl and Alt stay pressed until the next key. */
private class KeyMods { var ctrl by mutableStateOf(false); var alt by mutableStateOf(false); var more by mutableStateOf(false) }

/** The ordinary text box and Send button: type a line (or nothing, for just Enter) and send it with Enter. */
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

@Composable
private fun ScreenCanvas(s: Screen, zoom: Float, jump: Int, onCell: (Int, Int) -> Unit) {
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
        val px = maxOf(fit, minPx).coerceAtMost(with(density) { 16.sp.toPx() }) * zoom
        val cw = px * ratio
        val ch = px * 1.25f
        val totalW = cw * s.cols + 8f
        val totalH = ch * s.lines.size + 8f
        // follow the bottom unless the person scrolled up; scrolling back down to the end follows again
        var follow by remember { mutableStateOf(true) }
        // only a finger (or a fling) decides whether to keep following; the content growing must not, or the view would stay at the top
        LaunchedEffect(Unit) { snapshotFlow { vScroll.isScrollInProgress to vScroll.value }.collect { (moving, _) -> if (moving) follow = vScroll.maxValue - vScroll.value < ch * 2 } }
        LaunchedEffect(jump) { if (jump > 0) { follow = true; vScroll.scrollTo(vScroll.maxValue) } }
        LaunchedEffect(s.seq, totalH) { if (follow && !vScroll.isScrollInProgress) vScroll.scrollTo(vScroll.maxValue) }
        Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll).pointerInput(cw, ch, s.sb, s.cols, s.rows) {
            detectTapGestures { o ->
                val col = ((o.x - 4f) / cw).toInt().coerceIn(0, s.cols - 1)
                val row = ((o.y - 4f) / ch).toInt() - s.sb
                onCell(col, row.coerceIn(0, s.rows - 1))
            }
        }) {
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
    K("⌨", act = "kb"), K("Paste", act = "paste"), K("Copy", act = "copy"), K("A−", act = "fm"), K("A+", act = "fp"), K("⇣", act = "bottom"),
    K("^C", "\u0003"), K("^D", "\u0004"), K("^Z", "\u001a"), K("^L", "\u000c"), K("^R", "\u0012"), K("^A", "\u0001"), K("^E", "\u0005"), K("^W", "\u0017"), K("^U", "\u0015"),
)

@Composable
private fun TerminalKeys(
    mods: KeyMods, emit: (String) -> Unit, toggleKeyboard: () -> Unit, paste: () -> Unit, copy: () -> Unit,
    smaller: () -> Unit, bigger: () -> Unit, bottom: () -> Unit,
) {
    fun run(k: K) {
        when (k.act) {
            null -> emit(k.seq ?: return)
            "more" -> mods.more = !mods.more
            "ctrl" -> mods.ctrl = !mods.ctrl
            "alt" -> mods.alt = !mods.alt
            "kb" -> toggleKeyboard()
            "paste" -> paste(); "copy" -> copy(); "fm" -> smaller(); "fp" -> bigger(); "bottom" -> bottom()
        }
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 6.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        androidx.compose.animation.AnimatedVisibility(mods.more) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { keysTools.forEach { KeyButton(it, false, ::run) } }
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
    Box(
        Modifier.height(36.dp).widthIn(min = 40.dp).clip(shape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
            .pointerInput(k) {
                detectTapGestures(onPress = {
                    var repeating = false
                    val job = if (k.repeat) scope.launch { delay(380); repeating = true; while (true) { run(k); delay(55) } } else null
                    val released = tryAwaitRelease()
                    job?.cancel()
                    if (released && !repeating) run(k)
                })
            }.padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(k.label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}
