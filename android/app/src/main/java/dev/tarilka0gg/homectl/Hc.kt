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
class Machine(val id: String, name: String, val token: String, endpoints: List<Endpoint>, fp: String? = null, rv: String? = null) {
    /** Private channel where this machine announces its current tunnel address (signed with the access token). */
    var rv by mutableStateOf(rv)
    /** SHA-256 of this machine's certificate, pinned at pairing: connections to it are encrypted and checked against it. */
    var fp by mutableStateOf(fp)
    /** The daemon's version as last reported (empty for older daemons). */
    var daemonVersion by mutableStateOf("")
    /** True when the machine presented a different certificate than the pinned one. */
    var certProblem by mutableStateOf(false)
    var name by mutableStateOf(name)
    var endpoints by mutableStateOf(endpoints)
    var active by mutableStateOf<Endpoint?>(null)
    var offline by mutableStateOf(false)
    /** Set when an address answers but the machine rejects this phone's signature; the app stays usable and says why. */
    var authProblem by mutableStateOf<String?>(null)

    // same network first; away from home the tunnel, then the route through another machine; the self-hosted tailnet last
    private val order = listOf("lan", "tunnel", "server", "tailscale")

    /** The certificate to pin for [e]: the relay's own for a route through another machine, none for a public https tunnel. */
    fun pinFor(e: Endpoint): String? = when {
        e.url.startsWith("https://") -> null
        e.hub != null -> Hc.machines.find { it.id == e.hub }?.fp
        else -> fp
    }

    /** Does a request to [e] need this phone's signature? True for the public tunnel and for machines set to "always". */
    fun signFor(e: Endpoint): Boolean = e.url.contains(".trycloudflare.com") || deviceAlways

    /** The machine asked for this phone's signature on every request, not only through the tunnel. */
    var deviceAlways by mutableStateOf(false)

    fun needsSigned(): Boolean = active?.let { signFor(it) } == true

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

    /** Same-network addresses answer in milliseconds, so a dead one is given up on quickly; anything reached through Tailscale's relays,
     *  the server or a tunnel gets time for the first packets to find a path (several seconds on mobile data). */
    private fun connectBudget(e: Endpoint) = if (e.kind == "lan") 1500 else 6000

    private suspend fun ping(e: Endpoint): Boolean = probe(e).ok

    class Probe(val endpoint: Endpoint, val ok: Boolean, val ms: Long, val detail: String)

    /** One reachability test of [e] with the reason it failed, for the "why can't I connect?" screen. */
    suspend fun probe(e: Endpoint): Probe = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            val c = Net.open(urlOf(e), "GET", "/api/ping", null, 4000, connectMs = connectBudget(e), pin = pinFor(e))
            c.readTimeout = 4000
            val code = c.responseCode
            val body = if (code == 200) c.inputStream.bufferedReader().readText() else ""
            val ok = code == 200 && body.contains("homectl")
            if (ok && signFor(e)) {
                // the address answers; does the machine accept *this phone*?
                val c2 = Net.open(urlOf(e), "GET", "/api/security", null, 4000, connectMs = 4000, pin = pinFor(e), sign = true)
                c2.setRequestProperty("Authorization", "Bearer ${e.token ?: token}")
                c2.readTimeout = 5000
                if (c2.responseCode == 401) {
                    val why = runCatching { JSONObject(c2.errorStream?.bufferedReader()?.readText().orEmpty()).optString("why") }.getOrDefault("")
                    Hc.memPutBool("enrolled.$id", false)
                    val msg = "answers, but this phone isn't registered with the machine" + (if (why.isNotBlank()) " ($why)" else "") + " — open the app once on the home network (Wi-Fi or Tailscale)"
                    authProblem = msg
                    // the address is alive, so it stays selectable: the app opens instead of showing "offline" for good
                    return@withContext Probe(e, true, System.currentTimeMillis() - t0, msg)
                }
                authProblem = null
            }
            Probe(e, ok, System.currentTimeMillis() - t0, if (ok) "answered" else "answered with HTTP $code, but not homectl")
        } catch (ex: Exception) {
            if (pinFor(e) != null && (ex is javax.net.ssl.SSLException || ex.cause is java.security.cert.CertificateException)) certProblem = true
            val why = when {
                ex is java.net.SocketTimeoutException || ex is java.net.NoRouteToHostException -> "no answer — nothing is reachable at that address from here"
                ex is java.net.ConnectException && ex.message?.contains("refused", true) == true -> "refused — the machine is reachable but homectl isn't running on it"
                ex is java.net.ConnectException || ex is java.net.UnknownHostException -> "can't connect — no route to that address"
                ex is javax.net.ssl.SSLException || ex.cause is java.security.cert.CertificateException -> "the certificate is not the one pinned for this machine"
                else -> ex.javaClass.simpleName + (ex.message?.let { ": $it" } ?: "")
            }
            Probe(e, false, System.currentTimeMillis() - t0, why)
        }
    }

    suspend fun diagnose(): List<Probe> = coroutineScope { endpoints.map { e -> async { probe(e) } }.awaitAll() }

    private val probes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Picks the best reachable address (Tailscale, then LAN, then relay, then tunnel) and refreshes the address list
     * from it. All addresses are probed at once, but it does not wait for dead ones: it returns as soon as the best
     * address answers, or — when only a lower-priority one has answered — after a short grace period for better ones.
     */
    suspend fun resolve(chain: Set<String> = emptySet()): Endpoint? {
        // a route through another machine needs that machine's current address first. [chain] holds the machines already being
        // resolved above this call: two machines that relay through each other (or one through itself) used to wait on each
        // other forever, which overflowed the stack and closed the app the moment it opened with nothing reachable.
        val here = chain + id
        endpoints.mapNotNull { it.hub }.distinct().forEach { hid -> if (hid !in here) Hc.machines.find { it.id == hid }?.let { hub -> if (hub.active == null) hub.resolve(here) } }
        endpoints = endpoints.map { e ->
            val h = e.hub?.takeIf { it != id }?.let { hid -> Hc.machines.find { it.id == hid } }
            // a relay is reached through the hub's own address, never through another relay
            val hub = h?.active?.takeIf { it.kind != "server" }?.url ?: return@map e
            e.copy(url = hub + "/peer/" + e.url.substringAfterLast("/peer/"))
        }
        pick()
        if (active == null && lookupAnnouncedTunnel()) pick()
        offline = active == null
        if (active != null) certProblem = false
        if (active == null) authProblem = null
        active?.let { refreshEndpoints(it) }
        if (active != null) { syncSecurity(); Hc.autoLinkAll() }
        return active
    }

    /** Probes every address at once and keeps the best one that answers (see [order]). */
    private suspend fun pick() {
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
    }

    /**
     * Registers this phone's public key with the machine the first time it is reached over a direct route (home network,
     * Tailscale) — never through the tunnel or another machine, which the machine refuses — and reads its security settings.
     */
    private suspend fun syncSecurity() {
        val e = active ?: return
        val direct = e.kind == "lan" || e.kind == "tailscale"
        if (direct && !Hc.memBool("enrolled.$id")) {
            val key = runCatching { DeviceKey.publicKeyB64() }.getOrNull() ?: return
            val body = JSONObject().put("name", android.os.Build.MODEL).put("key", key)
            val (code, _) = postSlowWith("/api/devices", body, 15_000, null)
            if (code == 200) Hc.memPutBool("enrolled.$id", true)
        }
        json("/api/security")?.let { s -> deviceAlways = s.optBoolean("device_always", false) }
    }

    private var lastLookup = 0L

    class Announcement(val url: String, val ts: Long)

    /** The newest correctly signed tunnel address on the machine's announcement channel (or null). Nothing unsigned is ever trusted. */
    suspend fun fetchAnnouncement(): Announcement? = withContext(Dispatchers.IO) {
        val topic = rv ?: return@withContext null
        try {
            val c = java.net.URL("https://ntfy.sh/$topic/json?poll=1&since=12h").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            val lines = c.inputStream.bufferedReader().readText().lines()
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(token.toByteArray(), "HmacSHA256")) }
            var best: Announcement? = null
            for (l in lines) {
                val m = runCatching { JSONObject(JSONObject(l).optString("message")) }.getOrNull() ?: continue
                val url = m.optString("url"); val ts = m.optLong("ts"); val sig = m.optString("sig")
                if (!Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").matches(url)) continue
                val want = mac.doFinal("$url|$ts".toByteArray()).joinToString("") { "%02x".format(it) }
                if (want.length != sig.length || want.zip(sig).fold(0) { a, (x, y) -> a or (x.code xor y.code) } != 0) continue
                if (best == null || ts > best.ts) best = Announcement(url, ts)
            }
            best
        } catch (_: Exception) {
            null
        }
    }

    /** Nothing answers: ask the announcement channel for the tunnel's current address and, if it is new, use it. */
    private suspend fun lookupAnnouncedTunnel(): Boolean {
        if (rv == null || System.currentTimeMillis() - lastLookup < 20_000) return false
        lastLookup = System.currentTimeMillis()
        val a = fetchAnnouncement() ?: return false
        val seen = Hc.memStr("rvts.$id", "0").toLongOrNull() ?: 0L
        if (a.ts < seen) return false // an older announcement replayed
        val have = endpoints.firstOrNull { it.kind == "tunnel" }?.url
        Hc.memPutStr("rvts.$id", a.ts.toString())
        if (have == a.url) return false
        endpoints = endpoints.filter { it.kind != "tunnel" } + Endpoint("tunnel", a.url)
        Hc.save()
        return true
    }

    private suspend fun refreshEndpoints(via: Endpoint) {
        val j = raw(via, "GET", "/api/endpoints")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        j.optString("fp").ifBlank { null }?.let { found -> if (fp == null) { fp = found; Hc.save() } } // first contact: pin it
        j.optString("rv").ifBlank { null }?.let { found -> if (rv != found) { rv = found; Hc.save() } }
        daemonVersion = j.optString("version")
        val a = j.optJSONArray("urls") ?: return
        val fresh = (0 until a.length()).map { a.getJSONObject(it).let { o -> Endpoint(o.getString("kind"), o.getString("url"), o.optString("token").ifBlank { null }, o.optString("hub").ifBlank { null }) } }
        if (fresh.isNotEmpty()) {
            endpoints = fresh + endpoints.filter { it.kind == "server" } // keep relay routes the daemon doesn't know about
            name = j.optString("name", name).ifBlank { name }
            Hc.save()
        }
    }

    private suspend fun raw(e: Endpoint, method: String, path: String, body: String? = null, forceSign: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val c = Net.open(urlOf(e), method, path, e.token ?: token, 4000, connectMs = 2000, pin = pinFor(e), sign = forceSign || signFor(e))
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            // the machine forgot this phone (or never knew it): register again the next time it is reached directly
            if (code == 401 && text.contains("device_required")) {
                Hc.memPutBool("enrolled.$id", false)
                authProblem = runCatching { JSONObject(text).optString("why") }.getOrDefault("").ifBlank { "this machine wants requests signed by a registered phone" }
                // the machine wants signatures on every request (the app forgets that when it restarts): sign from now on and try again
                if (!forceSign && !signFor(e)) { deviceAlways = true; return@withContext raw(e, method, path, body, forceSign = true) }
            } else if (code in 200..299) authProblem = null
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
                val c = Net.open(urlOf(e), "POST", path, e.token ?: token, 6000, pin = pinFor(e), sign = signFor(e))
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
                val c = Net.open(urlOf(e), "POST", pathQuery, e.token ?: token, 6000, pin = pinFor(e), sign = signFor(e))
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
                val c = Net.open(urlOf(e), "POST", "/api/files/$name", e.token ?: token, 5000, pin = pinFor(e), sign = signFor(e))
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

    /** WebSocket URL for terminal session [sid] on the active address, carrying this phone's signature when the machine needs it. */
    fun termUrl(sid: String, shell: String? = null, cols: Int? = null, rows: Int? = null): String? = active?.let { e ->
        val base = localBase(e)
        val q = "t=${e.token ?: token}&s=$sid" + (shell?.let { sh -> "&sh=" + Uri.encode(sh) } ?: "") + (if (cols != null && rows != null) "&cols=$cols&rows=$rows" else "")
        val url = "$base/api/term?$q"
        val signed = if (signFor(e)) { val u = java.net.URL(url); url + "&" + DeviceKey.query("GET", u.file) } else url
        signed.replaceFirst("http", "ws")
    }

    suspend fun delete(path: String): Boolean = call("DELETE", path) != null

    /** Streams a GET: [block] gets the stream and its length (-1 if unknown). [range] e.g. "bytes=0-65535". */
    suspend fun <T> stream(pathQuery: String, range: String? = null, block: (java.io.InputStream, Long) -> T): T? {
        val e = active ?: resolve() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val c = Net.open(urlOf(e), "GET", pathQuery, e.token ?: token, 6000, pin = pinFor(e), sign = signFor(e))
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
                val c = Net.open(urlOf(e), "POST", path, e.token ?: token, 6000, pin = pinFor(e), sign = signFor(e))
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
                val c = Net.open(urlOf(e), "GET", pathQuery, e.token ?: token, 6000, pin = pinFor(e), sign = signFor(e))
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
                val c = Net.open(urlOf(e), "POST", "/api/fs/upload?dir=${Uri.encode(dir)}&name=${Uri.encode(fileName)}", e.token ?: token, 5000, pin = pinFor(e), sign = signFor(e))
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
    fun open(base: String, method: String, path: String, token: String?, ms: Int, connectMs: Int = ms, pin: String? = null, sign: Boolean = false): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            if (sign) DeviceKey.headers(method, URL(base + path).file).forEach { (k, v) -> setRequestProperty(k, v) }
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
    /** True while the terminal view's keyboard is up: the bottom dock slides away and comes back when it closes. */
    var dockHidden by mutableStateOf(false)

    /** True while the soft keyboard is up (measured from the visible window area, which works however the keyboard was closed). */
    var imeUp by mutableStateOf(false)
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

    /** True when saved logins existed but could not be opened, so the user is told to pair again instead of just seeing an empty app. */
    var keysLost by mutableStateOf(false)
    /** The saved logins exist but the keystore could not read them just now. Nothing is overwritten; "Try again" reads them anew. */
    var loginsUnreadable by mutableStateOf(false)

    /** Reads the saved logins again after a transient keystore failure. */
    fun retryLogins(): Boolean {
        val g = Keyring.get("machines", strong = false)
        if (g is Keyring.Got.Value) {
            machines.clear()
            val a = JSONArray(g.text)
            for (i in 0 until a.length()) a.getJSONObject(i).let { o -> machines.add(Machine(o.getString("id"), o.getString("name"), o.getString("token"), parseEndpoints(o.getJSONArray("endpoints")), o.optString("fp").ifBlank { null }, o.optString("rv").ifBlank { null })) }
            loginsUnreadable = false
        } else if (g is Keyring.Got.Lost) { loginsUnreadable = false; keysLost = true }
        return !loginsUnreadable
    }
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
        Diag.init(ctx)
        Diag.watchCrashes()
        Diag.noteExits(ctx)
        Diag.noteVersion(ctx)
        var sealed = Keyring.get("machines", strong = false)
        // the keystore can be busy for a moment after an update or a boot: try again before giving up
        for (i in 0..3) { if (sealed !is Keyring.Got.Unreadable) break; Thread.sleep(400); sealed = Keyring.get("machines", strong = false) }
        if (sealed is Keyring.Got.Unreadable) { loginsUnreadable = true; Diag.log("saved logins cannot be read right now (keystore busy?)") }
        // the phone's key is gone (a reinstall removes it) or could not be read; the blob is kept, pairing again replaces it
        if (sealed is Keyring.Got.Lost) { keysLost = true; Diag.log("saved logins could not be opened (key lost or unreadable)") }
        else Diag.log("start: saved logins ${if (sealed is Keyring.Got.Value) "opened" else "none"}, device key ${DeviceKey.idOrNull() ?: "unreadable"}")
        val stored = (sealed as? Keyring.Got.Value)?.text ?: prefs.getString("machines", null)
        stored?.let { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                machines.add(Machine(o.getString("id"), o.getString("name"), o.getString("token"), parseEndpoints(o.getJSONArray("endpoints")), o.optString("fp").ifBlank { null }, o.optString("rv").ifBlank { null }))
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
        if (loginsUnreadable) return // never overwrite logins that could not be read
        val a = JSONArray()
        machines.forEach { m ->
            a.put(JSONObject().put("id", m.id).put("name", m.name).put("token", m.token).put("fp", m.fp ?: "").put("rv", m.rv ?: "").put("endpoints", JSONArray().also { ea ->
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
        add(Machine(token.take(10), name, token, urls.map { Endpoint(kindOf(it), it.trimEnd('/')) }, uri.getQueryParameter("fp")?.ifBlank { null }, uri.getQueryParameter("rv")?.ifBlank { null }))
        return true
    }

    private val autoLinked = HashSet<String>()
    private var autoLinking = false

    /**
     * A machine that has a tunnel (reachable from anywhere) becomes the way to reach the other machines when you are away from
     * home: every other paired machine gets registered with it once, so away from home "gentoo" simply keeps working through
     * the server. Switched off per machine by pressing Unlink in System → Reach … through a server.
     */
    suspend fun autoLinkAll() {
        for (h in machines.toList()) if (h.active != null && h.active?.kind != "server") autoLink(h)
    }

    suspend fun autoLink(hub: Machine) {
        if (autoLinking || hub.endpoints.none { it.kind == "tunnel" }) return
        autoLinking = true
        try {
            for (t in machines.toList()) {
                if (t.id == hub.id || memBool("autolink.skip.${t.id}.${hub.id}")) continue
                // already linked: only make sure the machine knows its server (so it can keep its own tunnel off while the server is up)
                if (relayedVia(t, hub)) { if ("${t.id}>${hub.id}#hub" !in autoLinked) { autoLinked.add("${t.id}>${hub.id}#hub"); registerHubs(t, hub) }; continue }
                if ("${t.id}>${hub.id}" in autoLinked) continue
                autoLinked.add("${t.id}>${hub.id}")
                if (!linkViaServer(t, hub)) autoLinked.remove("${t.id}>${hub.id}")
            }
        } finally { autoLinking = false }
    }

    fun relayedVia(m: Machine, hub: Machine) = m.endpoints.any { it.hub == hub.id }

    /** Makes [hub] (e.g. the home server) relay to [target], so the app can reach [target] through the hub alone. */
    suspend fun linkViaServer(target: Machine, hub: Machine): Boolean {
        memPutBool("autolink.skip.${target.id}.${hub.id}", false)
        val hubUrl = hub.active?.url ?: hub.resolve()?.url ?: return false
        // the hub reaches the target by its own network (tailnet / LAN), not through us
        val direct = target.endpoints.firstOrNull { it.kind == "tailscale" } ?: target.endpoints.firstOrNull { it.kind == "lan" } ?: return false
        val peerId = target.id.filter { it.isLetterOrDigit() }.take(24)
        val ok = hub.call("POST", "/api/peers", body("id" to peerId, "name" to target.name, "url" to direct.url, "token" to target.token)) != null
        if (!ok) return false
        target.endpoints = target.endpoints.filter { it.hub != hub.id } + Endpoint("server", "$hubUrl/peer/$peerId", hub.token, hub.id)
        save()
        target.resolve()
        registerHubs(target, hub)
        return true
    }

    /**
     * Tells [target] the server's home-network addresses (LAN, Tailscale) so that, with HOMECTL_TUNNEL=auto, it keeps its own
     * public tunnel off while the server answers and starts it when the server doesn't. Older daemons just ignore this.
     */
    suspend fun registerHubs(target: Machine, hub: Machine) {
        val urls = hub.endpoints.filter { it.kind == "lan" || it.kind == "tailscale" }.map { it.url }
        if (urls.isEmpty()) return
        target.call("POST", "/api/hubs", body("urls" to JSONArray(urls)))
    }

    suspend fun unlinkViaServer(target: Machine, hub: Machine) {
        memPutBool("autolink.skip.${target.id}.${hub.id}", true)
        val peerId = target.id.filter { it.isLetterOrDigit() }.take(24)
        hub.call("DELETE", "/api/peers/$peerId")
        target.call("DELETE", "/api/hubs")
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
