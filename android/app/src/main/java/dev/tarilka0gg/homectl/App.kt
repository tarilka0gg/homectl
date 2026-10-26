@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class Tab(val label: String, val icon: ImageVector) {
    Monitor("Monitor", Icons.Default.Info),
    Controls("Controls", Icons.Default.Settings),
    Terminal("Terminal", Icons.Default.PlayArrow),
    Network("Network", Icons.Default.Share),
    Devices("Devices", Icons.Default.Home),
    Windows("Windows", WindowsIcon),
    Files("Files", Icons.Default.Menu),
    System("System", Icons.Default.Build),
}

@Composable
fun App() {
    var adding by remember { mutableStateOf(!Hc.paired) }
    if (Hc.loginsUnreadable && !Hc.paired) return LoginsUnreadableScreen { adding = true; Hc.loginsUnreadable = false }
    if (adding || !Hc.paired) return AddMachineScreen(canCancel = Hc.paired) { adding = false }
    // the tab is remembered by name, so adding a tab never moves people to a different one
    var tab by remember { mutableStateOf(Tab.entries.firstOrNull { it.name == Hc.memStr("tab.name") } ?: Tab.Monitor) }
    // each tab keeps its scroll position while you move between tabs, and across leaving the app
    val scrolls = remember { Tab.entries.associateWith { ScrollState(Hc.memInt("scroll.${it.name}")) } }
    val restored = remember { mutableStateMapOf<Tab, Boolean>() }
    LaunchedEffect(tab) {
        val st = scrolls[tab] ?: return@LaunchedEffect
        if (tab == Tab.Terminal || tab == Tab.Files || tab == Tab.Windows) return@LaunchedEffect
        if (restored[tab] != true) {
            val want = Hc.memInt("scroll.${tab.name}")
            var tries = 0
            while (st.maxValue < want && tries++ < 40) delay(100) // the content arrives a moment after launch
            st.scrollTo(want.coerceAtMost(st.maxValue))
            restored[tab] = true
        }
        snapshotFlow { st.value }.collectLatest { v -> delay(300); Hc.memPutInt("scroll.${tab.name}", v) }
    }
    var menu by remember { mutableStateOf(false) }
    val m = Hc.cur!!
    val ctxApp = LocalContext.current
    LaunchedEffect(Unit) { PhoneClips.listen(ctxApp) }
    LaunchedEffect(Unit) { ClipService.ensure(ctxApp) }
    LaunchedEffect(Hc.machines.map { it.offline }) { learnMacs() }
    LaunchedEffect(Hc.openPath) { if (Hc.openPath != null) { tab = Tab.Files; Hc.memPutStr("tab.name", Tab.Files.name) } }

    // the tab names show for five seconds after the menu button is pressed, then slide away again
    var labelsShown by remember { mutableStateOf(false) }
    var labelRound by remember { mutableIntStateOf(0) }
    // every change of labelRound restarts (or cancels) the countdown; the menu button changes it
    LaunchedEffect(labelRound) { if (labelsShown) { delay(5000); labelsShown = false } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        // a plain row, not a text button: the button's 40 dp minimum height would cut the name in the 32 dp bar
                        Row(Modifier.height(32.dp).clickable { menu = true }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name.ifBlank { "homectl" }, style = MaterialTheme.typography.titleSmall)
                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(20.dp))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            Hc.machines.forEach {
                                DropdownMenuItem(text = { Text(it.name) }, onClick = { Hc.select(it); menu = false })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Add machine…") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { menu = false; adding = true })
                        }
                    }
                Spacer(Modifier.weight(1f))
                AssistChip(
                    modifier = Modifier.height(24.dp),
                        onClick = {},
                        label = { Text(style = MaterialTheme.typography.labelSmall, text = if (m.certProblem) "certificate!" else if (m.offline) "offline" else if (m.authProblem != null) "not registered" else (m.active?.kind ?: "connecting…") + if (m.active?.let { m.pinFor(it) } != null) " 🔒" else "") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (m.offline) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                Spacer(Modifier.width(8.dp))
            }
        },
        bottomBar = {
            androidx.compose.animation.AnimatedVisibility(
                !Hc.dockHidden,
                enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(240)) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
                exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(240)) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(240)),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tab.entries.forEach {
                                val tint = if (tab == it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                Box(Modifier.weight(1f).fillMaxHeight().clickable { tab = it; Hc.memPutStr("tab.name", it.name) }, contentAlignment = Alignment.Center) {
                                    Icon(it.icon, it.label, Modifier.size(24.dp), tint = tint)
                                }
                            }
                            IconButton(onClick = { labelsShown = !labelsShown; labelRound++ }) { Icon(Icons.Default.Menu, "Show or hide tab names") }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            labelsShown,
                            enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top) + androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) + androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut(),
                        ) {
                            // the names sit under their icons, centred on them; the spacer lines up with the menu button
                            Row(Modifier.fillMaxWidth().clipToBounds().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Tab.entries.forEach {
                                    Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        color = if (tab == it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("Menu", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        if (tab == Tab.Terminal || tab == Tab.Files || tab == Tab.Windows) {
            Box(Modifier.padding(pad).fillMaxSize()) { when (tab) { Tab.Terminal -> TerminalScreen(); Tab.Windows -> WindowsScreen(); else -> FilesScreen() } }
        } else {
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scrolls.getValue(tab)).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tab) {
                    Tab.Monitor -> MonitorScreen()
                    Tab.Controls -> ControlsScreen()
                    Tab.Files -> {}
                    Tab.System -> SystemScreen { adding = true }
                    Tab.Network -> NetworkScreen()
                    Tab.Devices -> DevicesScreen { adding = true }
                    Tab.Terminal, Tab.Windows -> {}
                }
            }
        }
    }
}

/** The keystore could not read the saved logins just now; they are still there, so offer a retry instead of forcing a new pairing. */
@Composable
private fun LoginsUnreadableScreen(pairAgain: () -> Unit) {
    var tried by remember { mutableStateOf(false) }
    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Saved logins can't be read right now", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Android's keystore is busy (it can be, just after an update or a restart). Nothing was deleted.", style = MaterialTheme.typography.bodyMedium)
            if (tried) Text("Still not readable.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button({ tried = !Hc.retryLogins() }) { Text("Try again") }
            TextButton(pairAgain) { Text("Pair a machine anyway") }
        }
    }
}

/** Finds machines on the LAN / tailnet and pairs with a 6-digit code, or accepts a pairing link or manual entry. */
@Composable
private fun AddMachineScreen(canCancel: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<Found>?>(null) }
    var scanNonce by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<Found, String>?>(null) }
    var code by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf(false) }

    LaunchedEffect(scanNonce) {
        found = null
        found = Discovery.scan()
    }
    val known = Hc.machines.flatMap { m -> m.endpoints.map { it.url } }.toSet()

    fun finish(f: Found, token: String, fp: String? = null) {
        val m = Machine(token.take(10), f.name, token, listOf(Endpoint(f.kind, f.url)), fp)
        Hc.add(m)
        scope.launch { m.resolve() }
        onDone()
    }

    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add a machine", style = MaterialTheme.typography.headlineMedium)
            if (Hc.keysLost && !Hc.paired) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Your saved logins couldn't be opened. Android deletes an app's keys when it is uninstalled (installing an older version or a differently signed build does that), so this phone has to be paired again. Open the app once on your home Wi-Fi or Tailscale so the phone registers itself.", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Section("Nearby", trailing = {
                if (found == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else TextButton({ scanNonce++ }) { Text("Rescan") }
            }) {
                val list = found?.filter { it.url !in known }.orEmpty()
                if (found != null && list.isEmpty()) {
                    Text("Nothing new found on this Wi-Fi or tailnet. Start homectl on the machine, or use a link below.", style = MaterialTheme.typography.bodyMedium)
                }
                list.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${f.kind} · ${f.url}", style = MaterialTheme.typography.bodySmall)
                        }
                        FilledTonalButton({
                            scope.launch {
                                val r = Net.postJson(f.url, "/api/pair/request", body("device" to Build.MODEL))
                                if (r == null) Toast.makeText(ctx, "Couldn't start pairing — try again in a few seconds", Toast.LENGTH_LONG).show()
                                else { code = ""; pending = f to r.getString("id") }
                            }
                        }) { Text("Pair") }
                    }
                }
                Text("A 6-digit code appears on the machine (a notification on a desktop; on a server run `homectl pair`).", style = MaterialTheme.typography.bodySmall)
            }

            Section("Pairing link or address") {
                OutlinedTextField(link, { link = it; linkError = false }, Modifier.fillMaxWidth(), label = { Text("homectl:// link, or http://host:7878") }, isError = linkError)
                if (!link.startsWith("homectl://")) OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        if (Hc.pairFrom(link, token)) { Hc.cur?.let { m -> scope.launch { m.resolve() } }; onDone() } else linkError = true
                    }) { Text("Connect") }
                    OutlinedButton({
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        link = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    }) { Text("Paste") }
                }
            }
            if (canCancel) TextButton(onDone) { Text("Cancel") }
        }
    }

    pending?.let { (f, id) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Pair with ${f.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the 6-digit code shown on ${f.name}.")
                    OutlinedTextField(code, { if (it.length <= 6) code = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Code") })
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val r: JSONObject? = Net.postJson(f.url, "/api/pair/confirm", body("id" to id, "code" to code))
                        if (r == null) { Toast.makeText(ctx, "Wrong or expired code", Toast.LENGTH_LONG).show(); return@launch }
                        pending = null
                        finish(Found(r.optString("name", f.name), f.url, f.kind), r.getString("token"), r.optString("fp").ifBlank { null })
                    }
                }, enabled = code.length == 6) { Text("Pair") }
            },
            dismissButton = { TextButton({ pending = null }) { Text("Cancel") } },
        )
    }
}
