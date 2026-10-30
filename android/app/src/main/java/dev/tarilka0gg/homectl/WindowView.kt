@file:OptIn(ExperimentalLayoutApi::class)

package dev.tarilka0gg.homectl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/**
 * Any window, live: its screen (or, for a floating window, just the window) is streamed at the phone's resolution; it is only
 * brought to the front when you ask. A video player, or a browser on a video site, opens full screen, turned to fit the picture. Tap = click, hold = right click, drag = scroll, two fingers = zoom and move; the bar below
 * types into whatever has the keyboard on the machine.
 */
@Composable
fun WindowView(id: String, title: String, app: String, place: JSONObject?, icon: @Composable () -> Unit, onClose: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val output = place?.str("output").orEmpty()
    // a floating window's own rectangle on its screen (fractions); a tiled one shows the whole screen
    val rect = place?.optJSONArray("rect")?.let { a -> if (a.length() == 4) FloatArray(4) { a.optDouble(it).toFloat() } else null }
    var frame by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var status by remember(id) { mutableStateOf("") }
    var running by remember(id) { mutableStateOf(true) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    // on the active workspace of its screen; anything else is only brought forward when you ask, so the laptop's layout stays put
    var shown by remember(id) { mutableStateOf(place?.optBoolean("visible") == true) }
    var bring by remember(id) { mutableStateOf(false) }
    // a niri with the window-capture patch pictures a window wherever it is; off screen it can only be watched, since taps and
    // typing would go to whatever is on the screen instead
    val canCapture = place?.optBoolean("capture") == true
    val niriId = place?.optLong("niri", -1L) ?: -1L
    var watchOnly by remember(id) { mutableStateOf(false) }
    var video by remember(id) { mutableStateOf(isVideo(app, title)) }
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    // full screen the picture may end up sideways, so it is asked for at the phone's long side
    val widthPx = with(density) { (if (video) maxOf(conf.screenWidthDp, conf.screenHeightDp) else conf.screenWidthDp).dp.roundToPx() }.coerceIn(480, 2400)

    DisposableEffect(Unit) { Hc.fullView = true; onDispose { Hc.fullView = false; Hc.videoView = false } }
    LaunchedEffect(video) { Hc.videoView = video }
    // a wide picture turns the phone sideways while it is full screen
    val wide = frame?.let { it.width > it.height } == true
    LaunchedEffect(video, wide) {
        Hc.activity?.requestedOrientation = if (video && wide) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(Unit) { onDispose { Hc.activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    BackHandler(video) { video = false }

    LaunchedEffect(id, attempt, video) {
        if (output.isEmpty()) { status = "This machine's compositor does not say which screen the window is on, so it can't be shown."; return@LaunchedEffect }
        // only what is on a screen can be copied
        if (bring) { bring = false; Hc.post("/api/windows/$id/focus"); delay(350); shown = true }
        watchOnly = !shown && canCapture && niriId >= 0
        if (!shown && !watchOnly) { frame = null; status = "hidden"; return@LaunchedEffect }
        val crop = rect?.let { "&crop=" + it.joinToString(",") }.orEmpty()
        status = ""
        running = true
        val fps = if (video) 24 else 8
        val path = if (watchOnly) "/api/niri-windows/$niriId/stream?w=$widthPx&fps=$fps" else "/api/screens/${android.net.Uri.encode(output)}/stream?w=$widthPx&fps=$fps$crop"
        val ok = Hc.cur?.stream(path) { input, _ ->
            readMjpeg(input, { running }) { bmp -> frame = bmp.asImageBitmap() }
        }
        if (ok == null && running) status = "The picture stopped. The machine may need the newer homectl daemon, or its compositor does not allow screen copies."
    }
    DisposableEffect(id) { onDispose { running = false } }

    fun toScreen(fx: Float, fy: Float): Pair<Double, Double> = if (rect != null) (rect[0] + fx * rect[2]).toDouble() to (rect[1] + fy * rect[3]).toDouble() else fx.toDouble() to fy.toDouble()
    fun pointer(op: String, fx: Float, fy: Float, dx: Float = 0f, dy: Float = 0f) = scope.launch {
        if (watchOnly) return@launch
        val (x, y) = toScreen(fx, fy)
        Hc.post("/api/screens/${android.net.Uri.encode(output)}/pointer", body("op" to op, "x" to x, "y" to y, "dx" to dx.toDouble(), "dy" to dy.toDouble()))
    }
    fun keys(vararg k: String) = scope.launch { Hc.post("/api/keys", JSONObject().put("keys", org.json.JSONArray(k.toList()))) }
    fun type(t: String) = scope.launch { Hc.post("/api/keys", body("text" to t)) }

    val imeUp = Hc.imeUp
    LaunchedEffect(imeUp) { Hc.dockHidden = imeUp }
    DisposableEffect(Unit) { onDispose { Hc.dockHidden = false } }
    var closing by remember { mutableStateOf(false) }

    if (video) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val f = frame
            if (f == null) {
                if (status.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else Text(if (status == "hidden") "This window is on another workspace. Leave full screen to bring it forward." else status,
                    Modifier.align(Alignment.Center).padding(24.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            } else LiveScreen(
                f,
                onTap = { x, y -> pointer("tap", x, y) },
                onRight = { x, y -> pointer("right", x, y) },
                onScroll = { x, y, dx, dy -> pointer("scroll", x, y, dx, dy) },
            )
            IconButton({ video = false }, Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))) {
                Icon(ExitFullscreen, "Leave full screen", tint = Color.White.copy(alpha = 0.8f))
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back to the windows") }
            icon()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { app }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(output.ifBlank { null }, if (rect == null && output.isNotEmpty()) "whole screen" else null, frame?.let { "${it.width}×${it.height}" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ video = true }) { Icon(Fullscreen, "Full screen") }
            IconButton({ attempt++ }) { Icon(Icons.Default.Refresh, "Reconnect") }
            IconButton({ closing = true }) { Icon(Icons.Default.Close, "Close the window") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val f = frame
            if (f == null) {
                if (status == "hidden") Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This window is on another workspace. Watching it there needs niri with homectl's window-capture patch (packaging/niri in the homectl repo).", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton({ bring = true; attempt++ }) { Text("Bring it forward on the laptop") }
                }
                else if (status.isEmpty()) CircularProgressIndicator() else Text(status, Modifier.padding(24.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            } else {
                LiveScreen(
                    f,
                    onTap = { x, y -> pointer("tap", x, y) },
                    onRight = { x, y -> pointer("right", x, y) },
                    onScroll = { x, y, dx, dy -> pointer("scroll", x, y, dx, dy) },
                )
            }
        }
        if (watchOnly) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Watching: it is on another workspace", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ bring = true; attempt++ }) { Text("Bring forward to control") }
        }
        else KeyBar(onKeys = { keys(*it) }, onText = { type(it) })
    }
    if (closing) AlertDialog(
        onDismissRequest = { closing = false },
        title = { Text("Close this window?") },
        text = { Text("“${title.ifBlank { app }}” will be closed. Unsaved work in it may be lost.") },
        confirmButton = { TextButton({ closing = false; onClose() }) { Text("Close it") } },
        dismissButton = { TextButton({ closing = false }) { Text("Keep") } },
    )
}

/** The picture, fitted to the space; gestures are turned into positions on it (fractions of its width and height). */
@Composable
private fun LiveScreen(img: ImageBitmap, onTap: (Float, Float) -> Unit, onRight: (Float, Float) -> Unit, onScroll: (Float, Float, Float, Float) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val iw = img.width.toFloat()
    val ih = img.height.toFloat()
    // where on the picture a touch is: undo the zoom (around the centre), then the fit
    fun at(p: Offset): Pair<Float, Float>? {
        if (box.width == 0) return null
        val c = Offset(box.width / 2f, box.height / 2f)
        val q = (p - c - offset) / scale + c
        val fit = minOf(box.width / iw, box.height / ih)
        val left = (box.width - iw * fit) / 2f
        val top = (box.height - ih * fit) / 2f
        val fx = (q.x - left) / (iw * fit)
        val fy = (q.y - top) / (ih * fit)
        return if (fx in 0f..1f && fy in 0f..1f) fx to fy else null
    }
    val tapNow by rememberUpdatedState(onTap)
    val rightNow by rememberUpdatedState(onRight)
    val scrollNow by rememberUpdatedState(onScroll)
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }.pointerInput(img.width, img.height) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = down.position
                val t0 = down.uptimeMillis
                var last = start
                var moved = false
                var multi = false
                var pend = Offset.Zero
                do {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        multi = true
                        scale = (scale * ev.calculateZoom()).coerceIn(1f, 6f)
                        offset = if (scale <= 1.01f) Offset.Zero else offset + ev.calculatePan()
                        ev.changes.forEach { it.consume() }
                    } else if (pressed.size == 1 && !multi) {
                        val p = pressed[0].position
                        if ((p - start).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            if (scale > 1.01f) offset += p - last
                            else {
                                // dragging the content up scrolls down, as on a phone
                                pend += p - last
                                if (abs(pend.y) > 10f || abs(pend.x) > 10f) {
                                    at(start)?.let { (x, y) -> scrollNow(x, y, -pend.x * 1.5f, -pend.y * 1.5f) }
                                    pend = Offset.Zero
                                }
                            }
                            pressed[0].consume()
                        }
                        last = p
                    }
                } while (ev.changes.any { it.pressed })
                if (!moved && !multi) {
                    val held = (currentEvent.changes.firstOrNull()?.uptimeMillis ?: t0) - t0
                    at(start)?.let { (x, y) -> if (held > 450) rightNow(x, y) else tapNow(x, y) }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Image(img, "The window, live", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
    }
}

/** Typing into the machine: a text box for words, and a row of the keys a phone keyboard lacks. */
@Composable
private fun KeyBar(onKeys: (Array<String>) -> Unit, onText: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 6.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(
                "Enter" to "Return", "⌫" to "BackSpace", "Tab" to "Tab", "Esc" to "Escape", "←" to "Left", "→" to "Right", "↑" to "Up", "↓" to "Down",
                "PgUp" to "Page_Up", "PgDn" to "Page_Down", "^C" to "ctrl+c", "^V" to "ctrl+v", "^Z" to "ctrl+z", "^A" to "ctrl+a", "^F" to "ctrl+f", "^L" to "ctrl+l",
                "^W" to "ctrl+w", "^T" to "ctrl+t", "Alt+Tab" to "alt+Tab", "F5" to "F5",
            ).forEach { (label, k) ->
                Box(Modifier.height(34.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                    .pointerInput(k) { awaitEachGesture { awaitFirstDown(); onKeys(arrayOf(k)) } }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Type into the window") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotEmpty()) { onText(text); text = "" } }))
            IconButton({ if (text.isNotEmpty()) { onText(text); text = "" } }) { Icon(Icons.Default.Send, "Type it") }
        }
    }
}

/** Players, and browsers on a video site, by their app id and title. */
private fun isVideo(app: String, title: String): Boolean {
    val a = app.lowercase()
    val players = listOf("mpv", "vlc", "celluloid", "totem", "showtime", "haruna", "smplayer", "kodi", "clapper", "jellyfin", "stremio", "freetube")
    if (players.any { it in a }) return true
    val sites = listOf("YouTube", "Twitch", "Netflix", "Vimeo", "Prime Video", "Disney+", "Jellyfin", "Plex")
    return sites.any { title.contains(it) }
}

private fun pathIcon(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(addPathNodes(d), fill = SolidColor(Color.Black)).build()
// Material's fullscreen and fullscreen_exit shapes (the core icon set has neither)
private val Fullscreen = pathIcon("Fullscreen", "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z")
private val ExitFullscreen = pathIcon("ExitFullscreen", "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z")
