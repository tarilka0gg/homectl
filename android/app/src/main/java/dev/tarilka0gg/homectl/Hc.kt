package dev.tarilka0gg.homectl

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Endpoint(val kind: String, val url: String)

/**
 * Talks to the homectl daemon. Keeps every known address (Tailscale, LAN, tunnel) and fails over between
 * them: probes all in parallel and uses the best one that answers. The daemon reports its current
 * addresses on every connection, so a changed tunnel URL is learned automatically.
 */
object Hc {
    private lateinit var prefs: android.content.SharedPreferences
    private val order = listOf("tailscale", "lan", "tunnel")

    var token by mutableStateOf("")
    var name by mutableStateOf("")
    var endpoints by mutableStateOf<List<Endpoint>>(emptyList())
    var active by mutableStateOf<Endpoint?>(null)
    var offline by mutableStateOf(false)

    val paired get() = token.isNotEmpty() && endpoints.isNotEmpty()

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("homectl", Context.MODE_PRIVATE)
        token = prefs.getString("token", "") ?: ""
        name = prefs.getString("name", "") ?: ""
        endpoints = parseEndpoints(prefs.getString("endpoints", "[]") ?: "[]")
    }

    private fun parseEndpoints(json: String): List<Endpoint> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Endpoint(o.getString("kind"), o.getString("url")) } }
    }

    private fun save() {
        val a = JSONArray()
        endpoints.forEach { a.put(JSONObject().put("kind", it.kind).put("url", it.url)) }
        prefs.edit().putString("token", token).putString("name", name).putString("endpoints", a.toString()).apply()
    }

    fun unpair() {
        token = ""; name = ""; endpoints = emptyList(); active = null
        save()
    }

    /** Accepts `homectl://pair?t=TOKEN&n=NAME&u=URL&u=URL...` or a bare URL + token. */
    fun pairFrom(link: String, fallbackToken: String = ""): Boolean {
        val uri = Uri.parse(link.trim())
        val list = if (uri.scheme == "homectl") {
            token = uri.getQueryParameter("t") ?: return false
            name = uri.getQueryParameter("n") ?: "homectl"
            uri.getQueryParameters("u")
        } else {
            token = fallbackToken.trim()
            name = "homectl"
            listOf(link.trim().trimEnd('/'))
        }
        if (token.isEmpty() || list.isEmpty()) return false
        endpoints = list.map { Endpoint(kindOf(it), it.trimEnd('/')) }
        active = null
        save()
        return true
    }

    private fun kindOf(url: String): String {
        val host = Uri.parse(url).host ?: return "lan"
        return when {
            host.endsWith(".trycloudflare.com") || url.startsWith("https://") -> "tunnel"
            host.startsWith("100.") -> "tailscale"
            else -> "lan"
        }
    }

    private fun open(base: String, method: String, path: String, auth: Boolean, ms: Int): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = ms
            readTimeout = ms * 2
            if (auth) setRequestProperty("Authorization", "Bearer $token")
        }

    private suspend fun ping(e: Endpoint): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = open(e.url, "GET", "/api/ping", false, 2500)
            val ok = c.responseCode == 200 && c.inputStream.bufferedReader().readText().contains("homectl")
            c.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Picks the best reachable address (Tailscale, then LAN, then tunnel) and refreshes the address list from it. */
    suspend fun resolve(): Endpoint? = coroutineScope {
        val cands = endpoints.sortedBy { order.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }
        val up = cands.map { e -> async { e to ping(e) } }.awaitAll().filter { it.second }.map { it.first }
        active = up.firstOrNull()
        offline = active == null
        active?.let { refreshEndpoints(it) }
        active
    }

    private suspend fun refreshEndpoints(via: Endpoint) {
        val j = raw(via, "GET", "/api/endpoints")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val a = j.optJSONArray("urls") ?: return
        val fresh = (0 until a.length()).map { a.getJSONObject(it).let { o -> Endpoint(o.getString("kind"), o.getString("url")) } }
        if (fresh.isNotEmpty()) {
            endpoints = fresh
            name = j.optString("name", name)
            save()
        }
    }

    private suspend fun raw(e: Endpoint, method: String, path: String, body: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val c = open(e.url, method, path, true, 4000)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
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

    suspend fun upload(ctx: Context, uri: Uri, fileName: String): Boolean {
        val e = active ?: resolve() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val name = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                val c = open(e.url, "POST", "/api/files/$name", true, 5000)
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
