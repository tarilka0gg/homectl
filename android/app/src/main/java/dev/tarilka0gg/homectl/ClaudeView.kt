@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Claude's orange, for its mark and the "needs you" state. */
val ClaudeOrange = Color(0xFFD97757)

/** The Claude Code session of a window card: name, what it is doing, the last thing said. */
class ClaudeInfo(j: JSONObject) {
    val session = j.str("session")
    val name = j.str("name")
    val status = j.str("status")
    val waiting = j.str("waiting")
    val last = j.optJSONObject("last")?.str("t").orEmpty()
    val lastFromUser = j.optJSONObject("last")?.str("k") == "user"
    val needsYou get() = status == "waiting"
    val working get() = status == "busy" || status == "shell"
}

fun claudeStatus(c: ClaudeInfo): String = when (c.status) {
    "waiting" -> if (c.waiting.isNotBlank()) "Needs your answer" else "Waiting for you"
    "busy" -> "Working…"
    "shell" -> "Running a command…"
    "idle" -> "Ready"
    else -> c.status.ifBlank { "Claude Code" }
}

@Composable
fun ClaudeMark(size: Int = 36) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(ClaudeOrange), contentAlignment = Alignment.Center) {
        Text("✳", color = Color.White, fontSize = (size * 0.55f).sp)
    }
}

@Composable
fun StatusDot(c: ClaudeInfo) {
    val col = when {
        c.needsYou -> ClaudeOrange
        c.working -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(col))
}

// ---- the conversation ----

/** One thing in the conversation, in the order it happened. */
private sealed class Row(val key: String) {
    class User(key: String, val text: String) : Row(key)
    class Text(key: String, val text: String) : Row(key)
    class Think(key: String, val text: String) : Row(key)
    class Cmd(key: String, val text: String) : Row(key)
    class Note(key: String, val text: String) : Row(key)
    /** A tool call with its result, once there is one. */
    class Tool(key: String, val call: JSONObject, val result: JSONObject?) : Row(key)
}

private fun rowsOf(items: List<JSONObject>): List<Row> {
    val results = HashMap<String, JSONObject>()
    for (i in items) if (i.str("k") == "result") results[i.str("id")] = i
    val out = ArrayList<Row>()
    for (i in items) {
        val key = "i" + i.optLong("i")
        when (i.str("k")) {
            "tool" -> out.add(Row.Tool(key, i, results[i.str("id")]))
            "user" -> out.add(Row.User(key, i.str("t")))
            "text" -> out.add(Row.Text(key, i.str("t")))
            "think" -> out.add(Row.Think(key, i.str("t")))
            "cmd" -> out.add(Row.Cmd(key, i.str("t")))
            "note" -> out.add(Row.Note(key, i.str("t")))
        }
    }
    return out
}

private fun toolIcon(name: String): ImageVector = when (name) {
    "Bash" -> Icons.Default.PlayArrow
    "Read" -> Icons.Default.Info
    "Edit", "Write", "NotebookEdit" -> Icons.Default.Edit
    "Grep", "Glob" -> Icons.Default.Search
    "WebFetch", "WebSearch" -> Icons.Default.Share
    "Agent", "Task" -> Icons.Default.Person
    "TodoWrite" -> Icons.Default.CheckCircle
    else -> Icons.Default.Build
}

private fun shortFile(p: String) = p.substringAfterLast('/').ifBlank { p }

/**
 * The conversation of a Claude Code session, as in the Claude app: what you wrote on the right, Claude's answers as text, and
 * every tool call in between with its own output.
 */
@Composable
fun ClaudeView(term: String, initial: ClaudeInfo, onTerminal: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val items = remember(term) { mutableStateListOf<JSONObject>() }
    var next by remember(term) { mutableLongStateOf(-1L) }
    var info by remember(term) { mutableStateOf(initial) }
    var lost by remember(term) { mutableStateOf(false) }
    var gap by remember(term) { mutableStateOf(false) }
    // the bottom of the terminal, shown while Claude is asking something (a permission or a question with choices)
    var prompt by remember(term) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(term) {
        while (true) {
            val q = if (next < 0) "limit=400" else "after=${next - 1}&limit=400"
            val (code, j) = Hc.cur?.getSlow("/api/windows/$term/chat?$q", 10_000) ?: (0 to null)
            if (j != null) {
                lost = false
                val got = j.optJSONArray("items")?.objs().orEmpty()
                if (next < 0 || j.optBoolean("gap")) { items.clear(); gap = j.optBoolean("gap") && next < 0 }
                val have = items.mapTo(HashSet()) { it.optLong("i") }
                got.filter { it.optLong("i") !in have }.let { items.addAll(it) }
                next = j.optLong("next")
                info = ClaudeInfo(JSONObject().put("session", j.str("session")).put("name", j.str("title").ifBlank { info.name }).put("status", j.str("status")).put("waiting", j.str("waiting")))
            } else if (code == 404 || code == 0) lost = true
            if (info.needsYou) {
                Hc.cur?.getSlow("/api/windows/$term/screen?back=0", 5000)?.second?.optJSONArray("lines")?.let { ls ->
                    val lines = (0 until ls.length()).map { i -> ls.getJSONArray(i).let { rs -> (0 until rs.length()).joinToString("") { rs.getJSONArray(it).getString(0) } }.trimEnd() }
                    prompt = lines.dropLastWhile { it.isBlank() }.takeLast(18)
                }
            } else prompt = emptyList()
            delay(if (info.working || info.needsYou) 1200 else 2500)
        }
    }

    fun send(text: String) = scope.launch {
        // several lines go in as one paste, so the first line break does not send the message early; then Enter sends it
        Hc.post("/api/windows/$term/input", body("text" to if ('\n' in text) "\u001b[200~$text\u001b[201~" else text))
        delay(120)
        Hc.post("/api/windows/$term/input", body("text" to "\r"))
    }
    fun key(seq: String) = scope.launch { Hc.post("/api/windows/$term/input", body("text" to seq)) }

    val rows = remember(items.size, items.lastOrNull()?.optLong("i")) { rowsOf(items.toList()) }
    val list = rememberLazyListState()
    // follow the end while you are at the end; scrolled up, it stays where you are
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) follow = !list.canScrollForward }
    LaunchedEffect(rows.size, prompt.size, info.needsYou) { if (follow && rows.isNotEmpty()) list.scrollToItem(rows.size + 1) }

    val imeUp = Hc.imeUp
    LaunchedEffect(imeUp) { Hc.dockHidden = imeUp }
    DisposableEffect(Unit) { Hc.fullView = true; onDispose { Hc.dockHidden = false; Hc.fullView = false } }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back to the windows") }
            ClaudeMark(28)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name.ifBlank { "Claude Code" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(info)
                    Text(if (lost) "disconnected" else claudeStatus(info), style = MaterialTheme.typography.bodySmall,
                        color = if (lost) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onTerminal) { Text("Terminal") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (gap) item("gap") { Text("Earlier messages are not shown.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (next >= 0 && rows.isEmpty()) item("empty") { Text("Nothing said yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (next < 0) item("loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is Row.User -> UserBubble(r.text)
                    is Row.Text -> MarkdownText(r.text)
                    is Row.Think -> Thought(r.text)
                    is Row.Cmd -> Text(r.text, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                    is Row.Note -> Text(r.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    is Row.Tool -> ToolCall(r.call, r.result)
                }
            }
            if (info.working) item("working") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ClaudeOrange)
                    Text(claudeStatus(info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // the question itself is on the terminal; the keys are offered even when its text could not be read
            if (info.needsYou) item("prompt") { AskCard(prompt, ::key) }
        }
        Composer(working = info.working, onSend = { send(it) }, onStop = { key("\u001b") })
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SelectionContainer {
            Text(text, Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, maxLines = 30, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Thought(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Row(Modifier.clickable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Thought", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open && text.isNotBlank()) Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolCall(t: JSONObject, result: JSONObject?) {
    var full by remember { mutableStateOf(false) }
    val name = t.str("name")
    val sum = t.str("sum").let { if (name in setOf("Read", "Edit", "Write")) shortFile(it) else it }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(toolIcon(name), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(if (name.startsWith("mcp__")) name.substringAfterLast("__") else name); pop()
                if (sum.isNotBlank()) append("  $sum")
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (result == null) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            else if (result.optBoolean("err")) Text("failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        val detail = t.str("detail").takeIf { it.isNotBlank() && it != t.str("sum") }
        val out = result?.str("t").orEmpty()
        if (detail != null || out.isNotBlank()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF111418)).clickable { full = !full }.padding(8.dp)) {
                if (detail != null) Text("$ $detail", color = Color(0xFF8BE9A0), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = if (full) 40 else 3, overflow = TextOverflow.Ellipsis)
                if (out.isNotBlank()) Text(out, color = if (result?.optBoolean("err") == true) Color(0xFFFF8A80) else Color(0xFFD7DAE0), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    maxLines = if (full) 200 else 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** What Claude is asking right now, as the terminal shows it, with the keys that answer it. */
@Composable
private fun AskCard(lines: List<String>, key: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ClaudeOrange.copy(alpha = 0.12f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Claude is asking", style = MaterialTheme.typography.labelLarge, color = ClaudeOrange)
        if (lines.isNotEmpty()) Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1" to "1", "2" to "2", "3" to "3", "↑" to "\u001b[A", "↓" to "\u001b[B", "Enter" to "\r", "Esc" to "\u001b").forEach { (label, seq) ->
                FilledTonalButton({ key(seq) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) }
            }
        }
    }
}

@Composable
private fun Composer(working: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            text, { text = it }, Modifier.weight(1f), placeholder = { Text("Reply to Claude") }, maxLines = 6,
            shape = RoundedCornerShape(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        if (working && text.isBlank()) FilledIconButton(onStop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Icon(Icons.Default.Close, "Interrupt (Esc)") }
        else FilledIconButton({ if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, enabled = text.isNotBlank(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = ClaudeOrange)) { Icon(Icons.Default.Send, "Send") }
    }
}

// ---- a little Markdown: headings, lists, quotes, code blocks, inline code, bold and italics ----

@Composable
fun MarkdownText(src: String) {
    val blocks = remember(src) { mdBlocks(src) }
    val code = MaterialTheme.colorScheme.surfaceContainerHighest
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is Md.Code -> Text(b.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(code).horizontalScroll(rememberScrollState()).padding(10.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, softWrap = false)
                    is Md.Head -> Text(inline(b.text, code), style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                    is Md.Item -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
                        Text(b.mark, Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(inline(b.text, code), style = MaterialTheme.typography.bodyMedium)
                    }
                    is Md.Quote -> Text(inline(b.text, code), Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is Md.Table -> Text(b.text, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, softWrap = false)
                    is Md.Para -> Text(inline(b.text, code), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private sealed class Md {
    class Code(val text: String) : Md()
    class Head(val level: Int, val text: String) : Md()
    class Item(val depth: Int, val mark: String, val text: String) : Md()
    class Quote(val text: String) : Md()
    class Table(val text: String) : Md()
    class Para(val text: String) : Md()
}

private fun mdBlocks(src: String): List<Md> {
    val out = ArrayList<Md>()
    val lines = src.lines()
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out.add(Md.Para(para.toString().trim())); para.setLength(0) }
    while (i < lines.size) {
        val l = lines[i]
        val t = l.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { body.appendLine(lines[i]); i++ }
                out.add(Md.Code(body.toString().trimEnd()))
            }
            t.startsWith("#") && t.trimStart('#').startsWith(" ") -> { flush(); out.add(Md.Head(t.takeWhile { it == '#' }.length, t.trimStart('#').trim())) }
            t.startsWith("|") -> {
                flush()
                val body = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val row = lines[i].trim()
                    if (!row.replace("|", "").replace("-", "").replace(":", "").isBlank()) body.appendLine(row.trim('|').split('|').joinToString("  ") { it.trim() })
                    i++
                }
                out.add(Md.Table(body.toString().trimEnd())); continue
            }
            Regex("^([-*+]|\\d+[.)]) ").containsMatchIn(t) -> {
                flush()
                val mark = t.substringBefore(' ')
                out.add(Md.Item((l.length - t.length) / 2, if (mark[0].isDigit()) mark else "•", t.substringAfter(' ')))
            }
            t.startsWith(">") -> { flush(); out.add(Md.Quote(t.trimStart('>').trim())) }
            t.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l) }
        }
        i++
    }
    flush()
    return out
}

/** `code`, **bold**, *italic* and [links](url) (shown as their text). */
private fun inline(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s[i] == '`' && s.indexOf('`', i + 1) > i -> {
                val e = s.indexOf('`', i + 1)
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)); append(s.substring(i + 1, e)); pop(); i = e + 1
            }
            s.startsWith("**", i) && s.indexOf("**", i + 2) > i -> {
                val e = s.indexOf("**", i + 2)
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(s.substring(i + 2, e)); pop(); i = e + 2
            }
            s[i] == '*' && i + 1 < s.length && s[i + 1] != ' ' && s.indexOf('*', i + 1) > i + 1 -> {
                val e = s.indexOf('*', i + 1)
                pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)); append(s.substring(i + 1, e)); pop(); i = e + 1
            }
            s[i] == '[' && s.indexOf("](", i) > i && s.indexOf(')', s.indexOf("](", i)) > 0 -> {
                val mid = s.indexOf("](", i); val e = s.indexOf(')', mid)
                pushStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)); append(s.substring(i + 1, mid)); pop(); i = e + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}
