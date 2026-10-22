@file:OptIn(ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The terminals' WebViews live here, not in the composition, so switching app tabs never restarts a terminal.
 * (The shell itself also lives on the machine, so even killing the app only costs a reattach.)
 */
object TerminalHub {
    private val views = HashMap<String, WebView>()
    private val scope = CoroutineScope(Dispatchers.Main)
    var lastFocused by mutableStateOf<String?>(null)
    @Volatile var imeVisible = false

    /** Bumped when a WebView had to be replaced (its renderer process died), so the screen rebuilds it. */
    var generation by mutableIntStateOf(0)

    /** Shells each machine offers, fetched ahead of time so the ＋ menu opens instantly. */
    val shells = mutableStateMapOf<String, List<Pair<String, String>>>()

    suspend fun loadShells(m: Machine) {
        m.array("/api/term/shells")?.objs()?.map { it.str("path") to it.str("name") }?.takeIf { it.isNotEmpty() }?.let { shells[m.id] = it }
    }

    /** A page that is already loaded and waiting, so a new session doesn't pay for creating a WebView. */
    private var spare: WebView? = null

    fun key(m: Machine, sid: String) = "${m.id}/$sid"

    fun toggleKeyboard(w: WebView) {
        val imm = w.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imeVisible) imm.hideSoftInputFromWindow(w.windowToken, 0) else { w.requestFocus(); imm.showSoftInput(w, 0) }
    }

    fun toggleKeyboardFor(key: String) { views[key]?.let { toggleKeyboard(it) } }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(ctx: Context): WebView {
        val bridge = Bridge()
        return WebView(MutableContextWrapper(ctx)).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = 100 // the page does its own sizing; the system font scale must not change cell metrics
            settings.setSupportZoom(false)
            setBackgroundColor(0xFF0B0F14.toInt())
            tag = bridge
            bridge.w = this
            addJavascriptInterface(bridge, "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    bridge.loaded = true
                    bridge.m?.let { start(view, it, bridge.sid) }
                }

                /** Android may kill the WebView's renderer while the app is in the background. Without handling this the
                 *  whole app dies; instead drop the view and rebuild it — the shell is still running on the machine. */
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    views.entries.firstOrNull { it.value === view }?.key?.let { views.remove(it) }
                    if (spare === view) spare = null
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    generation++
                    return true
                }
            }
            loadUrl("file:///android_asset/term.html")
        }
    }

    fun view(ctx: Context, m: Machine, sid: String): WebView {
        val k = key(m, sid)
        views[k]?.let { (it.context as? MutableContextWrapper)?.baseContext = ctx; return it }
        val w = spare?.also { spare = null } ?: create(ctx)
        (w.context as? MutableContextWrapper)?.baseContext = ctx
        val b = w.tag as Bridge
        b.m = m; b.sid = sid; b.key = k
        views[k] = w
        if (b.loaded) start(w, m, sid)
        // get the next one ready while the user isn't waiting
        w.postDelayed({ if (spare == null) spare = create(ctx) }, 400)
        return w
    }

    /** Called when the screen opens: build the spare ahead of time. */
    fun warm(ctx: Context) {
        if (spare == null) spare = create(ctx)
    }

    private fun start(w: WebView, m: Machine, sid: String) {
        scope.launch {
            if (m.active == null) m.resolve()
            val u = m.termUrl(sid, TermState.pendingShell.remove(key(m, sid))) ?: return@launch
            w.evaluateJavascript("hcStart(${JSONObject.quote(u)})", null)
        }
    }

    /** Back from another app: wake the pages and make sure their sockets are alive. */
    fun resumeAll() {
        views.values.forEach { it.onResume(); it.evaluateJavascript("hcResume()", null) }
    }

    fun send(m: Machine, sid: String, text: String) {
        views[key(m, sid)]?.evaluateJavascript("hcSend(${JSONObject.quote(text)})", null)
    }

    fun drop(m: Machine, sid: String) {
        views.remove(key(m, sid))?.destroy()
    }

    private class Bridge {
        lateinit var w: WebView
        var m: Machine? = null
        var sid = ""
        var key = ""
        var loaded = false
        private val ctx get() = w.context

        @JavascriptInterface fun copy(t: String) {
            w.post { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("terminal", t)) }
        }

        @JavascriptInterface fun paste(): String =
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()

        @JavascriptInterface fun showKeyboard() {
            w.post { w.requestFocus(); (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(w, 0) }
        }

        @JavascriptInterface fun toggleKeyboard() { w.post { TerminalHub.toggleKeyboard(w) } }

        /** A new address for every connection attempt: its signature is valid once, and the window size is part of what is signed. */
        @JavascriptInterface fun freshUrl(cols: Int, rows: Int): String = m?.termUrl(sid, null, cols, rows).orEmpty()

        @JavascriptInterface fun refreshUrl() {
            val mm = m ?: return
            scope.launch { mm.active = null; mm.resolve(); start(w, mm, sid) }
        }

        @JavascriptInterface fun focused() { scope.launch { lastFocused = key } }

        @JavascriptInterface fun tick() { w.post { w.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
    }
}

data class Pane(val machineId: String, val sessionId: String)

/** Which sessions are shown: one pane (following the top-bar machine) or two side by side. Survives restarts. */
object TermState {
    private lateinit var prefs: android.content.SharedPreferences
    private val last = HashMap<String, String>()
    /** Shell chosen for a session that doesn't exist yet (machine id/session id → path). */
    val pendingShell = HashMap<String, String>()
    var split by mutableStateOf(false)
    var second by mutableStateOf<Pane?>(null)
    var ssh by mutableStateOf<List<String>>(emptyList())

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("homectl-term", Context.MODE_PRIVATE)
        runCatching {
            val o = JSONObject(prefs.getString("last", "{}")!!)
            o.keys().forEach { last[it] = o.getString(it) }
        }
        split = prefs.getBoolean("split", false)
        second = prefs.getString("second", null)?.split("/")?.takeIf { it.size == 2 }?.let { Pane(it[0], it[1]) }
        ssh = runCatching { JSONArray(prefs.getString("ssh", "[]")!!).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun session(m: Machine) = last[m.id] ?: "main"

    fun select(m: Machine, sid: String) {
        last[m.id] = sid
        save()
    }

    fun updateSplit(v: Boolean) { split = v; save() }
    fun updateSecond(p: Pane?) { second = p; save() }

    fun remember(target: String) {
        ssh = (listOf(target) + ssh.filter { it != target }).take(12)
        save()
    }

    private fun save() {
        prefs.edit().putString("last", JSONObject(last as Map<*, *>).toString()).putBoolean("split", split)
            .putString("second", second?.let { "${it.machineId}/${it.sessionId}" })
            .putString("ssh", JSONArray(ssh).toString()).apply()
    }
}

private data class Sess(val id: String, val name: String)

@Composable
private fun sessionsOf(m: Machine, nonce: Int): List<Sess> {
    var list by remember(m.id) { mutableStateOf<List<Sess>>(emptyList()) }
    LaunchedEffect(m.id, nonce) {
        while (true) {
            m.array("/api/term/sessions")?.objs()?.map { Sess(it.str("id"), it.str("name")) }?.let { list = it }
            delay(4000)
        }
    }
    return list
}

private fun label(list: List<Sess>, s: Sess) = s.name.ifBlank { "shell ${list.indexOf(s) + 1}" }

@Composable
fun TerminalScreen() {
    val m = Hc.cur ?: return
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { TermState.init(ctx); TerminalHub.warm(ctx) }
    LaunchedEffect(m.id) { TerminalHub.loadShells(m) }
    var nonce by remember { mutableIntStateOf(0) }
    val sid = TermState.session(m)
    val sessions = sessionsOf(m, nonce)
    val scope = rememberCoroutineScope()
    var sshDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var closeDialog by remember { mutableStateOf(false) }
    var newSession by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (newSession) {
        // nothing is preselected and nothing is created until "Create" is pressed on a deliberate choice
        val shells = TerminalHub.shells[m.id].orEmpty()
        val options = listOf<Pair<String?, String>>(null to "Default shell") + shells.map { (path, name) -> path to name }
        var choice by remember { mutableStateOf<Int?>(null) }
        val shownAt = remember { System.currentTimeMillis() }
        AlertDialog(
            onDismissRequest = { newSession = false },
            title = { Text("New terminal session") },
            text = {
                Column {
                    Text("Pick a shell for ${m.name}:", style = MaterialTheme.typography.bodyMedium)
                    options.forEachIndexed { i, (_, name) ->
                        Row(Modifier.fillMaxWidth().clickable { if (System.currentTimeMillis() - shownAt > 400) choice = i }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(choice == i, { if (System.currentTimeMillis() - shownAt > 400) choice = i })
                            Text(name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    val c = choice ?: return@TextButton
                    newSession = false
                    val id = "t" + System.currentTimeMillis().toString(36)
                    options[c].first?.let { TermState.pendingShell[TerminalHub.key(m, id)] = it }
                    TermState.select(m, id)
                    scope.launch { delay(1500); nonce++ }
                }, enabled = choice != null) { Text("Create") }
            },
            dismissButton = { TextButton({ newSession = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = if (sessions.any { it.id == sid }) sessions else sessions + Sess(sid, "")
                items(shown, key = { it.id }) { s ->
                    FilterChip(sid == s.id, { TermState.select(m, s.id) }, label = { Text(label(shown, s)) })
                }
                item {
                    AssistChip({ newSession = true }, label = { Text("＋") })
                }
            }
            IconButton({ TermState.updateSplit(!TermState.split); if (TermState.split && TermState.second == null) TermState.updateSecond(Pane(m.id, "split")) }) {
                Icon(Icons.Default.List, "Split", tint = if (TermState.split) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({
                val k = TerminalHub.lastFocused ?: TerminalHub.key(m, sid)
                TerminalHub.toggleKeyboardFor(k)
            }) { Text("⌨", style = MaterialTheme.typography.titleLarge) }
            IconButton({ sshDialog = true }) { Icon(Icons.Default.Send, "SSH") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    TerminalHub.shells[m.id].orEmpty().forEach { (path, name) ->
                        DropdownMenuItem(text = { Text("Switch this session to $name") }, onClick = { menu = false; TerminalHub.send(m, sid, "exec $path\n") })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Rename session") }, onClick = { menu = false; renameDialog = true })
                    DropdownMenuItem(text = { Text("Close session") }, onClick = { menu = false; closeDialog = true })
                }
            }
        }

        val panes: @Composable () -> Unit = {
            if (!TermState.split) {
                Pane(m, sid, Modifier.fillMaxSize())
            } else {
                val sec = TermState.second ?: Pane(m.id, "split")
                val m2 = Hc.machines.find { it.id == sec.machineId } ?: m
                val a: @Composable (Modifier) -> Unit = { mod -> Box(mod) { Pane(m, sid, Modifier.fillMaxSize()) } }
                val b: @Composable (Modifier) -> Unit = { mod ->
                    Column(mod) {
                        SecondHeader(m2, sec.sessionId, onPick = { mm, s -> TermState.updateSecond(Pane(mm.id, s)) })
                        Pane(m2, sec.sessionId, Modifier.fillMaxSize())
                    }
                }
                if (landscape) Row(Modifier.fillMaxSize()) { a(Modifier.weight(1f).fillMaxHeight()); b(Modifier.weight(1f).fillMaxHeight()) }
                else Column(Modifier.fillMaxSize()) { a(Modifier.weight(1f).fillMaxWidth()); b(Modifier.weight(1f).fillMaxWidth()) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { panes() }
    }

    if (sshDialog) SshDialog(m, onDismiss = { sshDialog = false }) { target ->
        TermState.remember(target)
        val focused = TerminalHub.lastFocused
        val sec = TermState.second
        // type into the pane you last touched
        if (TermState.split && sec != null && focused == "${sec.machineId}/${sec.sessionId}") {
            Hc.machines.find { it.id == sec.machineId }?.let { TerminalHub.send(it, sec.sessionId, "ssh $target\n") }
        } else TerminalHub.send(m, sid, "ssh $target\n")
        sshDialog = false
    }
    if (renameDialog) {
        var name by remember { mutableStateOf(sessions.find { it.id == sid }?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Rename session") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton({ scope.launch { m.post("/api/term/sessions/$sid/name", body("name" to name)); nonce++ }; renameDialog = false }) { Text("Save") } },
            dismissButton = { TextButton({ renameDialog = false }) { Text("Cancel") } },
        )
    }
    if (closeDialog) AlertDialog(
        onDismissRequest = { closeDialog = false },
        title = { Text("Close this session?") },
        text = { Text("The shell and anything running in it will be stopped.") },
        confirmButton = {
            TextButton({
                scope.launch {
                    m.delete("/api/term/sessions/$sid")
                    TerminalHub.drop(m, sid)
                    TermState.select(m, sessions.firstOrNull { it.id != sid }?.id ?: "main")
                    nonce++
                }
                closeDialog = false
            }) { Text("Close") }
        },
        dismissButton = { TextButton({ closeDialog = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Pane(m: Machine, sid: String, modifier: Modifier) {
    val ctx = LocalContext.current
    key(m.id, sid, TerminalHub.generation) {
        AndroidView(factory = { TerminalHub.view(ctx, m, sid) }, modifier = modifier)
    }
}

@Composable
private fun SecondHeader(m: Machine, sid: String, onPick: (Machine, String) -> Unit) {
    val sessions = sessionsOf(m, 0)
    var machineMenu by remember { mutableStateOf(false) }
    var sessMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton({ machineMenu = true }) { Text(m.name); Icon(Icons.Default.ArrowDropDown, null) }
                DropdownMenu(machineMenu, { machineMenu = false }) {
                    Hc.machines.forEach { mm -> DropdownMenuItem(text = { Text(mm.name) }, onClick = { machineMenu = false; onPick(mm, "split") }) }
                }
            }
            Box {
                TextButton({ sessMenu = true }) { Text(sessions.find { it.id == sid }?.let { label(sessions, it) } ?: sid); Icon(Icons.Default.ArrowDropDown, null) }
                DropdownMenu(sessMenu, { sessMenu = false }) {
                    sessions.forEach { s -> DropdownMenuItem(text = { Text(label(sessions, s)) }, onClick = { sessMenu = false; onPick(m, s.id) }) }
                    DropdownMenuItem(text = { Text("＋ New session") }, onClick = { sessMenu = false; onPick(m, "t" + System.currentTimeMillis().toString(36)) })
                }
            }
        }
    }
}

@Composable
private fun SshDialog(m: Machine, onDismiss: () -> Unit, onGo: (String) -> Unit) {
    var target by remember { mutableStateOf("") }
    val hosts = Hc.machines.filter { it.id != m.id }.mapNotNull { mm -> mm.endpoints.firstOrNull()?.let { Uri.parse(it.url).host }?.let { "@$it" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SSH from ${m.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(target, { target = it }, singleLine = true, label = { Text("user@host") })
                if (TermState.ssh.isNotEmpty()) Text("Recent", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermState.ssh.forEach { AssistChip({ target = it }, label = { Text(it) }) }
                }
                if (hosts.isNotEmpty()) Text("Your other machines", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hosts.forEach { h -> AssistChip({ target = (target.substringBefore('@', "")).let { u -> if (u.isEmpty()) "user$h" else "$u$h" } }, label = { Text(h) }) }
                }
            }
        },
        confirmButton = { TextButton({ if (target.isNotBlank()) onGo(target.trim()) }, enabled = target.isNotBlank()) { Text("Connect") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
