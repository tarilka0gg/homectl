@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tarilka0gg.homectl

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** A file on a machine, or an entry inside an archive on a machine. */
data class RemoteFile(val name: String, val path: String, val size: Long, val mtime: Long = 0, val archive: String? = null, val dir: Boolean = false) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val kind get() = kindOf(name)
    val pathQuery get() = if (archive != null) "/api/fs/archive/file?path=${android.net.Uri.encode(archive)}&entry=${android.net.Uri.encode(path)}" else "/api/fs/download?path=${android.net.Uri.encode(path)}"
}

enum class Kind { Image, Text, Pdf, Archive, Video, Audio, Other }

private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val thumbExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val videoExt = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "ts")
private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "opus", "oga", "flac", "wav")
private val archiveExt = setOf("zip", "jar", "apk", "tar", "tgz", "gz", "xz", "bz2", "zst", "7z", "rar", "iso", "cbz", "txz", "tbz2")
private val textExt = setOf(
    "txt", "md", "log", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "csv", "tsv", "sh", "bash", "fish", "py", "rs", "c", "h", "cpp", "hpp",
    "js", "ts", "kt", "java", "html", "css", "sql", "service", "desktop", "rules", "env", "gitignore", "lock", "mk", "cmake", "nix", "lua", "go", "rb", "php",
    "patch", "diff", "tex", "srt", "properties", "gradle", "ebuild", "eclass", "zig",
)

fun kindOf(name: String): Kind {
    val n = name.lowercase()
    val ext = n.substringAfterLast('.', "")
    return when {
        n.endsWith(".tar.gz") || n.endsWith(".tar.xz") || n.endsWith(".tar.bz2") || n.endsWith(".tar.zst") -> Kind.Archive
        ext in imageExt -> Kind.Image
        ext == "pdf" -> Kind.Pdf
        ext in videoExt -> Kind.Video
        ext in audioExt -> Kind.Audio
        ext in archiveExt -> Kind.Archive
        ext in textExt || !n.contains('.') && n.first().isLetter() && n.length < 24 && n.all { it.isLetterOrDigit() || it == '-' || it == '_' } && n[0].isUpperCase() -> Kind.Text
        else -> Kind.Other
    }
}

fun canThumb(name: String) = name.substringAfterLast('.', "").lowercase() in thumbExt

fun mimeFor(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** Downloads [f] into the app cache (cached per machine/path), returns the File. */
suspend fun fetchToCache(ctx: Context, m: Machine, f: RemoteFile, onProgress: (Float) -> Unit = {}): File? {
    val dir = File(ctx.cacheDir, "remote").apply { mkdirs() }
    val file = File(dir, "${m.id.take(6)}-${(f.archive.orEmpty() + f.path).hashCode().toUInt()}-${f.name.replace('/', '_')}")
    if (file.exists() && file.length() > 0 && (f.size <= 0 || file.length() == f.size)) return file
    val ok = m.stream(f.pathQuery) { input, len ->
        file.outputStream().use { out ->
            val buf = ByteArray(128 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (len > 0) onProgress(done.toFloat() / len)
            }
        }
        true
    }
    if (ok != true) { file.delete(); return null }
    return file
}

fun shareFile(ctx: Context, file: File, name: String) {
    val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mimeFor(name)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
}

fun openFile(ctx: Context, file: File, name: String) {
    val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(u, mimeFor(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
}

// ---------- thumbnails ----------

private object Thumbs {
    val cache = LruCache<String, ImageBitmap>(400)
    val failed = HashSet<String>()
    val gate = Semaphore(4)
}

@Composable
fun Thumb(m: Machine, f: RemoteFile, size: Int = 48) {
    val key = "${m.id}|${f.path}|${f.mtime}"
    var bmp by remember(key) { mutableStateOf(Thumbs.cache.get(key)) }
    LaunchedEffect(key) {
        if (bmp != null || key in Thumbs.failed) return@LaunchedEffect
        val bytes = Thumbs.gate.withPermit { m.bytes("/api/fs/thumb?path=${android.net.Uri.encode(f.path)}&size=${size * 3}") }
        val decoded = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        if (decoded != null) { Thumbs.cache.put(key, decoded); bmp = decoded } else Thumbs.failed.add(key)
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("🖼", style = MaterialTheme.typography.titleMedium)
    }
}

// ---------- viewer ----------

/** Full-screen preview of [files] starting at [start] (images can be paged through). */
@Composable
fun PreviewDialog(m: Machine, files: List<RemoteFile>, start: Int, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(start.coerceIn(0, files.lastIndex)) }
    val f = files[index]
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Color.White, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                        Text(fmtBytes(f.size.toDouble()) + if (files.size > 1) "  ·  ${index + 1} / ${files.size}" else "", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton({
                        scope.launch { fetchToCache(ctx, m, f)?.let { shareFile(ctx, it, f.name) } ?: Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show() }
                    }) { Icon(Icons.Default.Share, "Share", tint = Color.White) }
                    IconButton({
                        scope.launch {
                            val file = fetchToCache(ctx, m, f)
                            if (file == null) { Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show(); return@launch }
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
                                put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeFor(f.name))
                                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/homectl")
                            }
                            val u = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            if (u != null) ctx.contentResolver.openOutputStream(u)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                            Toast.makeText(ctx, "Saved to Downloads/homectl/${f.name}", Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Default.Add, "Save to phone", tint = Color.White) }
                }
                var swipe by remember { mutableFloatStateOf(0f) }
                Box(
                    Modifier.weight(1f).fillMaxWidth().pointerInput(files.size, index) {
                        // swipe sideways to page; children that zoom/scroll consume their own drags first
                        detectHorizontalDragGestures(
                            onDragStart = { swipe = 0f },
                            onDragEnd = { if (swipe < -120f && index < files.lastIndex) index++ else if (swipe > 120f && index > 0) index-- },
                        ) { _, dx -> swipe += dx }
                    },
                ) {
                    key(f.path, f.archive) {
                        when (f.kind) {
                            Kind.Image -> ImageViewer(m, f)
                            Kind.Pdf -> PdfViewer(m, f)
                            Kind.Video, Kind.Audio -> MediaViewer(m, f)
                            else -> TextViewer(m, f)
                        }
                    }
                    if (files.size > 1) {
                        if (index > 0) IconButton({ index-- }, Modifier.align(Alignment.CenterStart)) { Icon(Icons.Default.KeyboardArrowLeft, "Previous", tint = Color.White, modifier = Modifier.size(36.dp)) }
                        if (index < files.lastIndex) IconButton({ index++ }, Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Default.KeyboardArrowRight, "Next", tint = Color.White, modifier = Modifier.size(36.dp)) }
                    }
                }
            }
        }
    }
}


@Composable
private fun Loading(progress: Float?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (progress != null && progress > 0f) CircularProgressIndicator(progress = { progress }) else CircularProgressIndicator()
    }
}

@Composable
private fun Failed(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Color.White, modifier = Modifier.padding(24.dp)) }
}

private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    var sample = 1
    while (o.outWidth / sample > maxSide || o.outHeight / sample > maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val rot = runCatching {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    return if (rot == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
}

@Composable
private fun ImageViewer(m: Machine, f: RemoteFile) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf<Float?>(0f) }
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(f.path, f.archive) {
        val file = fetchToCache(ctx, m, f) { progress = it }
        val b = file?.let { withContext(Dispatchers.IO) { decodeSampled(it, 2400) } }
        if (b == null) failed = true else bmp = b.asImageBitmap()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    when {
        failed -> Failed("Can't show this image (unsupported format?). Use Share or Save to open it elsewhere.")
        bmp == null -> Loading(progress)
        else -> Image(
            bmp!!, f.name,
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size > 1 || scale > 1f) {
                                scale = (scale * ev.calculateZoom()).coerceIn(1f, 8f)
                                val pan = ev.calculatePan()
                                if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f }
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; ox = 0f; oy = 0f } else scale = 3f }) }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = ox; translationY = oy },
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun TextViewer(m: Machine, f: RemoteFile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var editable by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var mtime by remember(f.path) { mutableLongStateOf(f.mtime) }
    var conflict by remember { mutableStateOf(false) }
    LaunchedEffect(f.path, f.archive) {
        val limit = 256 * 1024
        val bytes = m.stream(f.pathQuery, if (f.archive == null) "bytes=0-${limit - 1}" else null) { i, _ -> i.readNBytes(limit) }
        if (bytes == null) { failed = true; return@LaunchedEffect }
        val binary = bytes.take(4096).any { it.toInt() == 0 }
        if (binary) {
            note = "Binary file — first bytes as hex"
            text = bytes.take(512).chunked(16).joinToString("\n") { row -> row.joinToString(" ") { "%02x".format(it) } + "   " + row.map { b -> if (b in 32..126) b.toInt().toChar() else '.' }.joinToString("") }
        } else {
            if (bytes.size >= limit) note = "Showing the first ${limit / 1024} KB of ${fmtBytes(f.size.toDouble())} — too large to edit here"
            text = String(bytes, Charsets.UTF_8)
            editable = f.archive == null && bytes.size < limit
        }
    }
    fun save(force: Boolean) {
        saving = true
        scope.launch {
            val b = body("path" to f.path, "content" to draft).put("force", force)
            if (mtime > 0) b.put("mtime", mtime)
            val (code, j) = m.postSlow("/api/fs/write", b, 60_000)
            saving = false
            when {
                code == 200 -> { text = draft; mtime = j?.optLong("mtime") ?: mtime; editing = false; Toast.makeText(ctx, "Saved ${f.name}", Toast.LENGTH_SHORT).show() }
                code == 409 -> conflict = true
                else -> Toast.makeText(ctx, j?.str("error")?.ifBlank { null } ?: "Couldn't save", Toast.LENGTH_LONG).show()
            }
        }
    }
    when {
        failed -> Failed("Couldn't read this file.")
        text == null -> Loading(null)
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (editing) "Editing" else note, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
                if (editable && !editing) TextButton({ draft = text!!; editing = true }) { Text("Edit") }
                if (editing) {
                    TextButton({ editing = false }, enabled = !saving) { Text("Cancel") }
                    Button({ save(false) }, enabled = !saving && draft != text) { Text(if (saving) "Saving…" else "Save") }
                }
            }
            if (editing) {
                val c = Color(0xFFE6EDF3)
                OutlinedTextField(
                    draft, { draft = it },
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).imePadding(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = c, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c, unfocusedTextColor = c, cursorColor = Color.White, focusedBorderColor = Color.White.copy(alpha = 0.5f), unfocusedBorderColor = Color.White.copy(alpha = 0.25f)),
                )
            } else {
                SelectionContainer(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        Text(text!!, color = Color(0xFFE6EDF3), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
    if (conflict) AlertDialog(
        onDismissRequest = { conflict = false },
        title = { Text("File changed") },
        text = { Text("${f.name} was changed on ${m.name} after you opened it. Overwrite it with your version?") },
        confirmButton = { TextButton({ conflict = false; save(true) }) { Text("Overwrite", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ conflict = false }) { Text("Keep editing") } },
    )
}

@Composable
private fun PdfViewer(m: Machine, f: RemoteFile) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf<Float?>(0f) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(f.path, f.archive) {
        val file = fetchToCache(ctx, m, f) { progress = it }
        renderer = file?.let { runCatching { PdfRenderer(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
        if (renderer == null) failed = true
    }
    DisposableEffect(Unit) { onDispose { runCatching { renderer?.close() } } }
    val r = renderer
    when {
        failed -> Failed("Can't render this PDF. Use Share or Save to open it elsewhere.")
        r == null -> Loading(progress)
        else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
            items(r.pageCount) { i ->
                var page by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(i) {
                    page = withContext(Dispatchers.IO) {
                        synchronized(r) {
                            runCatching {
                                r.openPage(i).use { p ->
                                    val w = 1200
                                    val bmp = Bitmap.createBitmap(w, (w * p.height.toFloat() / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bmp.asImageBitmap()
                                }
                            }.getOrNull()
                        }
                    }
                }
                page?.let { Image(it, "page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                    ?: Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun MediaViewer(m: Machine, f: RemoteFile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableStateOf<Float?>(0f) }
    val base = m.active?.url
    // files on a machine stream straight from it (Range requests); entries inside an archive are fetched first
    LaunchedEffect(f.path, f.archive) { if (f.archive != null) local = fetchToCache(ctx, m, f) { progress = it } ?: run { failed = true; null } }
    if (failed) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This phone can't play this file here.", color = Color.White)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton({ scope.launch { fetchToCache(ctx, m, f)?.let { openFile(ctx, it, f.name) } ?: Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show() } }) { Text("Open with…") }
        }
        return
    }
    if (f.archive != null && local == null || f.archive == null && base == null) return Loading(progress)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (f.kind == Kind.Audio) Text("🎵", style = MaterialTheme.typography.displayLarge)
        AndroidView(
            factory = { c ->
                android.widget.VideoView(c).apply {
                    val mc = android.widget.MediaController(c)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                    setOnErrorListener { _, _, _ -> failed = true; true }
                    setOnPreparedListener { it.isLooping = false; start() }
                    if (local != null) setVideoPath(local!!.path)
                    else setVideoURI(android.net.Uri.parse(base + f.pathQuery), mapOf("Authorization" to "Bearer ${m.active?.token ?: m.token}"))
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.stopPlayback() },
        )
    }
}
