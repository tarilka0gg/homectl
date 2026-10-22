package dev.tarilka0gg.homectl

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One way to reach a machine. A "server" endpoint goes through another paired machine that relays to this one:
 * [url] is `<hub>/peer/<id>`, [token] is the hub's token and [hub] is the hub machine's id (its address is refreshed on resolve).
 */
data class Endpoint(val kind: String, val url: String, val token: String? = null, val hub: String? = null)

/**
 * One paired computer. Keeps every address it has reported (Tailscale, LAN, tunnel) and fails over between
 * them: probes all in parallel and uses the best one that answers. The daemon reports its current addresses on
 * every connection, so a changed tunnel URL is learned automatically.
 */
class Machine(val id: String, name: String, val token: String, endpoints: List<Endpoint>, fp: String? = null) {
    /** SHA-256 of this machine's certificate, pinned at pairing: connections to it are encrypted and checked against it. */
    var fp by mutableStateOf(fp)
    /** True when the machine presented a different certificate than the pinned one. */
    var certProblem by mutableStateOf(false)
    var name by mutableStateOf(name)
    var endpoints by mutableStateOf(endpoints)
    var active by mutableStateOf<Endpoint?>(null)
    var offline by mutableStateOf(false)

    private val order = listOf("tailscale", "lan", "server", "tunnel")

    /** The certificate to pin for [e]: the relay's own for a route through another machine, none for a public https tunnel. */
    fun pinFor(e: Endpoint): String? = when {
        e.url.startsWith("https://") -> null
        e.hub != null -> Hc.machines.find { it.id == e.hub }?.fp
        else -> fp
    }

    /** [e]'s address, as https when a certificate is pinned for it. */
    fun urlOf(e: Endpoint): String = if (pinFor(e) != null && e.url.startsWith("http://")) "https://" + e.url.removePrefix("http://") else e.url

    /** Address for WebViews and the video player: an encrypted link is reached through the phone's own loopback forwarder. */
    fun localBase(e: Endpoint): String {
        val pin = pinFor(e)
        if (pin == null || !e.url.startsWith("http://")) return e.url
        val rest = e.url.removePrefix("http://")
        val hostPort = rest.substringBefore('/')
        val path = rest.removePrefix(hostPort)
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: 7878
        return "http://127.0.0.1:${LocalTls.forward(host, port, pin)}$path"
    }

    private suspend fun ping(e: Endpoint): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = Net.open(urlOf(e), "GET", "/api/ping", null, 2500, connectMs = 1500, pin = pinFor(e))
            c.readTimeout = 1500
            val ok = c.responseCode == 200 && c.inputStream.bufferedReader().readText().contains("homectl")
            ok
        } catch (ex: Exception) {
            if (pinFor(e) != null && (ex is javax.net.ssl.SSLException || ex.cause is java.security.cert.CertificateException)) certProblem = true
            false
        }
    }

    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Picks the best reachable address (Tailscale, then LAN, then relay, then tunnel) and refreshes the address list
     * from it. All addresses are probed at once, but it does not wait for dead ones: it returns as soon as the best
     * address answers, or — when only a lower-priority one has answered — after a short grace period for better ones.
     */
    suspend fun resolve(): Endpoint? {
        endpoints = endpoints.map { e ->
            val hub = e.hub?.let { id -> Hc.machines.find { it.id == id } }?.active?.url ?: return@map e
            e.copy(url = hub + "/peer/" + e.url.substringAfterLast("/peer/"))
        }
        val cands = endpoints.sortedBy { order.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }
        val results = Channel<Pair<Int, Boolean>>(Channel.UNLIMITED)
        cands.forEachIndexed { i, e -> probes.launch { results.trySend(i to ping(e)) } }
        val status = arrayOfNulls<Boolean>(cands.size)
        var firstOkAt = 0L
        var pick: Int? = null
        while (true) {
            val best = status.indices.firstOrNull { status[it] != false } ?: break      // everything failed
            if (status[best] == true) { pick = best; break }
            val anyOk = status.indexOfFirst { it == true }
            val wait = if (anyOk >= 0) (firstOkAt + 300 - System.currentTimeMillis()).coerceAtLeast(0) else Long.MAX_VALUE
            val r = withTimeoutOrNull(wait) { results.receive() }
            if (r == null) { pick = anyOk; break }
            status[r.first] = r.second
            if (r.second && firstOkAt == 0L) firstOkAt = System.currentTimeMillis()
        }
        active = pick?.let { cands[it] }
        offline = active == null
        if (active != null) certProblem = false
        active?.let { refreshEndpoints(it) }
        return active
    }

    private suspend fun refreshEndpoints(via: Endpoint) {
        val j = raw(via, "GET", "/api/endpoints")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        j.optString("fp").ifBlank { null }?.let { found -> if (fp == null) { fp = found; Hc.save() } } // first contact: pin it
        val a = j.optJSONArray("urls") ?: return
        val fresh = (0 until a.length()).map { a.getJSONObject(it).let { o -> Endpoint(o.getString("kind"), o.getString("url"), o.optString("token").ifBlank { null }, o.optString("hub").ifBlank { null }) } }
        if (fresh.isNotEmpty()) {
            endpoints = fresh + endpoints.filter { it.kind == "server" } // keep relay routes the daemon doesn't know about
            name = j.optString("name", name).ifBlank { name }
            Hc.save()
        }
    }

    private suspend fun raw(e: Endpoint, method: String, path: String, body: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val c = Net.open(urlOf(e), method, path, e.token ?: token, 4000, connectMs = 2000, pin = pinFor(e))
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) text else null
        } catch (_: Exception) {
            null
        }
    }

    /** Runs a request on the active address, re-resolving once if it fails. Returns the body ("" for 204) or null. */
    suspend fun call(method: String, path: String, body: JSONObject? = null): String? {
        val b = body?.toString()
        for (attempt in 0..1) {
            val e = (if (attempt == 0) active else null) ?: resolve() ?: return null
            raw(e, method, path, b)?.let { offline = false; return it }
            active = null
        }
        offline = true
        return null
    }

    suspend fun json(path: String): JSONObject? = call("GET", path)?.let { runCatching { JSONObject(it) }.getOrNull() }
    suspend fun array(path: String): JSONArray? = call("GET", path)?.let { runCatching { JSONArray(it) }.getOrNull() }
    suspend fun post(path: String, body: JSONObject = JSONObject()): Boolean = call("POST", path, body) != null

    /**
     * A request that may need root on the machine (power modes, services, power actions). It first tries as before; if the
     * machine says it needs the user's doas password, the saved one is sent (after a fingerprint/face/PIN unlock when
     * needed), and if there is none the app asks for it ([Hc.pwRequest]). Returns the HTTP status and JSON body.
     */
    suspend fun privPost(path: String, body: JSONObject = JSONObject(), readTimeoutMs: Int = 200_000): Pair<Int, JSONObject?> {
        var pw: String? = null
        for (round in 0..2) {
            val (code, j) = postSlowWith(path, body, readTimeoutMs, pw)
            val needs = j?.optBoolean("needs_password") == true || j?.optString("error") == "password_required"
            val wrong = j?.optBoolean("wrong_password") == true || j?.optString("error") == "wrong_password"
            if (!needs && !wrong) return code to j
            if (wrong) Hc.forgetPassword(this)
            pw = Hc.passwordFor(this, wrong) ?: return code to j // the user cancelled
        }
        return 0 to null
    }

    private suspend fun postSlowWith(path: String, body: JSONObject, readTimeoutMs: Int, pw: String?): Pair<Int, JSONObject?> {
        val e = active ?: resolve() ?: return 0 to null
        return withContext(Dispatchers.IO) {
            try {
                val c = Net.open(urlOf(e), "POST", path, e.token ?: token, 6000, pin = pinFor(e))
                c.readTimeout = readTimeoutMs
                if (pw != null) c.setRequestProperty("X-Homectl-Pass", android.util.Base64.encodeToString(pw.toByteArray(), android.util.Base64.NO_WRAP))
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                code to runCatching { JSONObject(text) }.getOrNull()
            } catch (_: Exception) {
                0 to null
            }
        }
    }

    /** POSTs raw bytes (an image for the clipboard); true on a 2xx answer. */
    suspend fun postBytes(pathQuery: String, bytes: ByteArray, readTimeoutMs: Int = 60_000): Boolean {
        val e = active ?: resolve() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val c = Net.open(urlOf(e), "POST", pathQuery, e.token ?: token, 6000, pin = pinFor(e))
                c.readTimeout = readTimeoutMs
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes.size)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { it.write(bytes) }
                val ok = c.responseCode in 200..299
                (if (ok) c.inputStream else c.errorStream)?.close()
                ok
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun upload(ctx: Context, uri: Uri, fileName: String): Boolean {
        val e = active ?: resolve() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val name = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                val c = Net.open(urlOf(e), "POST", "/api/files/$name", e.token ?: token, 5000, pin = pinFor(e))
                c.doOutput = true
                c.setChunkedStreamingMode(65536)
                ctx.contentResolver.openInputStream(uri)!!.use { i -> c.outputStream.use { o -> i.copyTo(o) } }
                val ok = c.responseCode == 201
                c.disconnect()
                ok
            } catch (_: Exception) {
                false
            }
        }
    }

    /** WebSocket URL for terminal session [sid] on the active address. */
    fun termUrl(sid: String, shell: String? = null): String? =
        active?.let { localBase(it).replaceFirst("http", "ws") + "/api/term?t=${it.token ?: token}&s=$sid" + (shell?.let { sh -> "&sh=" + Uri.encode(sh) } ?: "") }

    suspend fun delete(path: String): Boolean = call("DELETE", path) != null

    /** Streams a GET: [block] gets the stream and its length (-1 if unknown). [range] e.g. "bytes=0-65535". */
    suspend fun <T> stream(pathQuery: String, range: String? = null, block: (java.io.InputStream, Long) -> T): T? {
        val e = active ?: resolve() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val c = Net.open(urlOf(e), "GET", pathQuery, e.token ?: token, 6000, pin = pinFor(e))
                c.readTimeout = 60000
                if (range != null) c.setRequestProperty("Range", range)
                if (c.responseCode !in 200..299) { c.disconnect(); return@withContext null }
                val r = c.inputStream.use { block(it, c.contentLengthLong) }
                c.disconnect()
                r
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Streams a remote file: [block] gets the stream and its length (-1 if unknown). */
    suspend fun <T> download(path: String, block: (java.io.InputStream, Long) -> T): T? =
        stream("/api/fs/download?path=" + Uri.encode(path), null, block)

    /** A whole (small) response as bytes, e.g. a thumbnail. */
    suspend fun bytes(pathQuery: String, max: Int = 4_000_000): ByteArray? =
        stream(pathQuery) { i, _ -> i.readNBytes(max) }

    /** POST that may take a while (unpacking an archive). Returns HTTP status and the JSON body. */
    suspend fun postSlow(path: String, body: JSONObject, readTimeoutMs: Int = 600_000): Pair<Int, JSONObject?> {
        val e = active ?: resolve() ?: return 0 to null
        return withContext(Dispatchers.IO) {
            try {
                val c = Net.open(urlOf(e), "POST", path, e.token ?: token, 6000, pin = pinFor(e))
                c.readTimeout = readTimeoutMs
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                code to runCatching { JSONObject(text) }.getOrNull()
            } catch (_: Exception) {
                0 to null
            }
        }
    }

    suspend fun getSlow(pathQuery: String, readTimeoutMs: Int = 120_000): Pair<Int, JSONObject?> {
        val e = active ?: resolve() ?: return 0 to null
        return withContext(Dispatchers.IO) {
            try {
                val c = Net.open(urlOf(e), "GET", pathQuery, e.token ?: token, 6000, pin = pinFor(e))
                c.readTimeout = readTimeoutMs
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                code to runCatching { JSONObject(text) }.getOrNull()
            } catch (_: Exception) {
                0 to null
            }
        }
    }

    /** Uploads [uri] into remote directory [dir]; false if the name is taken or the transfer fails. */
    suspend fun uploadTo(ctx: Context, uri: Uri, dir: String, fileName: String): Boolean {
        val e = active ?: resolve() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val c = Net.open(urlOf(e), "POST", "/api/fs/upload?dir=${Uri.encode(dir)}&name=${Uri.encode(fileName)}", e.token ?: token, 5000, pin = pinFor(e))
                c.doOutput = true
                c.setChunkedStreamingMode(65536)
                ctx.contentResolver.openInputStream(uri)!!.use { i -> c.outputStream.use { o -> i.copyTo(o) } }
                val ok = c.responseCode == 201
                c.disconnect()
                ok
            } catch (_: Exception) {
                false
            }
        }
    }
}

object Net {
    fun open(base: String, method: String, path: String, token: String?, ms: Int, connectMs: Int = ms, pin: String? = null): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            if (pin != null && this is javax.net.ssl.HttpsURLConnection) {
                sslSocketFactory = Pin.factory(pin)
                hostnameVerifier = Pin.anyHost
            }
            requestMethod = method
            connectTimeout = connectMs
            readTimeout = ms * 2
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    /** Unauthenticated JSON POST, used for code pairing. Returns the parsed reply or null. */
    suspend fun postJson(base: String, path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val c = open(base, "POST", path, null, 3000)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            val text = if (ok) c.inputStream.bufferedReader().readText() else ""
            if (ok) JSONObject(text) else null
        } catch (_: Exception) {
            null
        }
    }
}

object Hc {
    private lateinit var prefs: android.content.SharedPreferences
    val machines = mutableStateListOf<Machine>()
    var selectedId by mutableStateOf("")

    /** Read NVIDIA stats even on battery (asks the daemon to, which may keep the dGPU awake). */
    var gpuLive by mutableStateOf(false)

    /** How often the machines take fresh samples and the Monitor refreshes (ms). */
    var speedMs by mutableIntStateOf(1000)
    /** Monitor CPU section: one small graph per core instead of bars. */
    var perCoreGraphs by mutableStateOf(false)
    /** When turning a monitor off, also disable its output in the compositor so the GPU can sleep. */
    var compositorOff by mutableStateOf(true)
    fun updateCompositorOff(v: Boolean) { compositorOff = v; prefs.edit().putBoolean("compoff", v).apply() }

    // ---- saved root passwords ----
    class PwRequest(val machine: Machine, val wrong: Boolean, val answer: (String?) -> Unit)
    /** Set while the app needs to ask for a machine's doas password; App shows the dialog. */
    var pwRequest by mutableStateOf<PwRequest?>(null)
    /** The activity that can show the unlock prompt. */
    var activity: androidx.fragment.app.FragmentActivity? = null

    fun hasSavedPassword(m: Machine) = Keyring.has("pw.${m.id}")
    fun forgetPassword(m: Machine) = Keyring.remove("pw.${m.id}")

    /** The machine's saved doas password (unlocking first if needed) or, if none is saved (or [forceAsk]), asks for it. */
    suspend fun passwordFor(m: Machine, forceAsk: Boolean = false): String? {
        val act = activity ?: return null
        if (!forceAsk && Keyring.has("pw.${m.id}")) {
            Keyring.read(act, "pw.${m.id}", "Use the saved password for ${m.name}")?.let { return it }
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            pwRequest = PwRequest(m, forceAsk) { pw -> pwRequest = null; if (cont.isActive) cont.resume(pw) {} }
            cont.invokeOnCancellation { pwRequest = null }
        }
    }

    /** A folder another screen asked the Files tab to show (consumed by Files). */
    var openPath by mutableStateOf<String?>(null)

    /** Last open tab, so coming back to the app lands where you left. */
    var lastTab by mutableIntStateOf(0)

    fun setSpeed(ms: Int) { speedMs = ms; prefs.edit().putInt("speed", ms).apply() }
    fun setPerCore(v: Boolean) { perCoreGraphs = v; prefs.edit().putBoolean("percore", v).apply() }
    // ---- what the screens remember (where you were), kept across leaving the app ----
    fun memInt(k: String, def: Int = 0) = if (::prefs.isInitialized) prefs.getInt("m.$k", def) else def
    fun memPutInt(k: String, v: Int) { if (::prefs.isInitialized) prefs.edit().putInt("m.$k", v).apply() }
    fun memStr(k: String, def: String = "") = if (::prefs.isInitialized) prefs.getString("m.$k", def) ?: def else def
    fun memPutStr(k: String, v: String) { if (::prefs.isInitialized) prefs.edit().putString("m.$k", v).apply() }
    fun memBool(k: String, def: Boolean = false) = if (::prefs.isInitialized) prefs.getBoolean("m.$k", def) else def
    fun memPutBool(k: String, v: Boolean) { if (::prefs.isInitialized) prefs.edit().putBoolean("m.$k", v).apply() }

    fun setTab(i: Int) { lastTab = i; prefs.edit().putInt("tab", i).apply() }

    val paired get() = machines.isNotEmpty()
    val cur: Machine? get() = machines.find { it.id == selectedId } ?: machines.firstOrNull()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("homectl", Context.MODE_PRIVATE)
        selectedId = prefs.getString("selected", "") ?: ""
        speedMs = prefs.getInt("speed", 1000)
        perCoreGraphs = prefs.getBoolean("percore", false)
        compositorOff = prefs.getBoolean("compoff", true)
        lastTab = prefs.getInt("tab", 0)
        Keyring.init(ctx)
        // tokens are kept encrypted in the keyring; a plain list from an older version is moved there on first run
        val stored = (Keyring.get("machines", strong = false) as? Keyring.Got.Value)?.text ?: prefs.getString("machines", null)
        stored?.let { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                machines.add(Machine(o.getString("id"), o.getString("name"), o.getString("token"), parseEndpoints(o.getJSONArray("endpoints")), o.optString("fp").ifBlank { null }))
            }
        } ?: migrateV1()
        if (machines.isNotEmpty() && prefs.getString("machines", null) != null) save() // moves an old plain-text list into the keyring
    }

    /** Version 0.2 stored a single machine in flat keys. */
    private fun migrateV1() {
        val t = prefs.getString("token", "").orEmpty()
        val e = runCatching { parseEndpoints(JSONArray(prefs.getString("endpoints", "[]"))) }.getOrDefault(emptyList())
        if (t.isNotEmpty() && e.isNotEmpty()) {
            machines.add(Machine(t.take(10), prefs.getString("name", "homectl").orEmpty().ifBlank { "homectl" }, t, e))
            save()
        }
    }

    private fun parseEndpoints(a: JSONArray) =
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Endpoint(o.getString("kind"), o.getString("url"), o.optString("token").ifBlank { null }, o.optString("hub").ifBlank { null }) } }

    fun save() {
        val a = JSONArray()
        machines.forEach { m ->
            a.put(JSONObject().put("id", m.id).put("name", m.name).put("token", m.token).put("fp", m.fp ?: "").put("endpoints", JSONArray().also { ea ->
                m.endpoints.forEach { ea.put(JSONObject().put("kind", it.kind).put("url", it.url).put("token", it.token ?: "").put("hub", it.hub ?: "")) }
            }))
        }
        val sealed = runCatching { Keyring.put("machines", a.toString(), strong = false) }.isSuccess
        prefs.edit().apply { if (sealed) remove("machines") else putString("machines", a.toString()) }.putString("selected", selectedId).apply()
    }

    fun select(m: Machine) {
        selectedId = m.id
        save()
    }

    fun add(m: Machine) {
        machines.removeAll { it.id == m.id }
        machines.add(m)
        selectedId = m.id
        save()
    }

    fun remove(m: Machine) {
        machines.remove(m)
        if (selectedId == m.id) selectedId = machines.firstOrNull()?.id ?: ""
        save()
    }

    fun kindOf(url: String): String {
        val host = Uri.parse(url).host ?: return "lan"
        return when {
            host.endsWith(".trycloudflare.com") || url.startsWith("https://") -> "tunnel"
            host.startsWith("100.") -> "tailscale"
            else -> "lan"
        }
    }

    /** Accepts `homectl://pair?t=TOKEN&n=NAME&u=URL&u=URL...` or a bare URL + token. */
    fun pairFrom(link: String, fallbackToken: String = ""): Boolean {
        val uri = Uri.parse(link.trim())
        val token: String
        val name: String
        val urls: List<String>
        if (uri.scheme == "homectl") {
            token = uri.getQueryParameter("t") ?: return false
            name = uri.getQueryParameter("n") ?: "homectl"
            urls = uri.getQueryParameters("u")
        } else {
            token = fallbackToken.trim()
            name = Uri.parse(link.trim()).host ?: "homectl"
            urls = listOf(link.trim().trimEnd('/'))
        }
        if (token.isEmpty() || urls.isEmpty()) return false
        add(Machine(token.take(10), name, token, urls.map { Endpoint(kindOf(it), it.trimEnd('/')) }, uri.getQueryParameter("fp")?.ifBlank { null }))
        return true
    }

    fun relayedVia(m: Machine, hub: Machine) = m.endpoints.any { it.hub == hub.id }

    /** Makes [hub] (e.g. the home server) relay to [target], so the app can reach [target] through the hub alone. */
    suspend fun linkViaServer(target: Machine, hub: Machine): Boolean {
        val hubUrl = hub.active?.url ?: hub.resolve()?.url ?: return false
        // the hub reaches the target by its own network (tailnet / LAN), not through us
        val direct = target.endpoints.firstOrNull { it.kind == "tailscale" } ?: target.endpoints.firstOrNull { it.kind == "lan" } ?: return false
        val peerId = target.id.filter { it.isLetterOrDigit() }.take(24)
        val ok = hub.call("POST", "/api/peers", body("id" to peerId, "name" to target.name, "url" to direct.url, "token" to target.token)) != null
        if (!ok) return false
        target.endpoints = target.endpoints.filter { it.hub != hub.id } + Endpoint("server", "$hubUrl/peer/$peerId", hub.token, hub.id)
        save()
        target.resolve()
        return true
    }

    suspend fun unlinkViaServer(target: Machine, hub: Machine) {
        val peerId = target.id.filter { it.isLetterOrDigit() }.take(24)
        hub.call("DELETE", "/api/peers/$peerId")
        target.endpoints = target.endpoints.filter { it.hub != hub.id }
        save()
        target.resolve()
    }

    // The screens talk to the selected machine.
    suspend fun json(path: String) = cur?.json(path)
    suspend fun array(path: String) = cur?.array(path)
    suspend fun post(path: String, body: JSONObject = JSONObject()) = cur?.post(path, body) ?: false
    suspend fun upload(ctx: Context, uri: Uri, fileName: String) = cur?.upload(ctx, uri, fileName) ?: false
}
