package dev.tarilka0gg.homectl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

data class Found(val name: String, val url: String, val kind: String)

/** Finds homectl daemons by probing /api/ping on this phone's LAN /24 and Tailscale /24 (no broadcast needed). */
object Discovery {
    private fun localSubnets(): List<Triple<String, Int, String>> {
        val out = mutableListOf<Triple<String, Int, String>>() // prefix "a.b.c", own last octet, kind
        for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses.toList().filterIsInstance<Inet4Address>()) {
                val b = a.address.map { it.toInt() and 0xff }
                val kind = if (b[0] == 100 && b[1] in 64..127) "tailscale" else "lan"
                out.add(Triple("${b[0]}.${b[1]}.${b[2]}", b[3], kind))
            }
        }
        return out.distinctBy { it.first }
    }

    private suspend fun probe(ip: String, kind: String): Found? = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ip:7878"
            val c = Net.open(url, "GET", "/api/ping", null, 700)
            val body = if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else ""
            c.disconnect()
            val j = JSONObject(body)
            if (j.optInt("homectl") == 1) Found(j.optString("name", ip), url, kind) else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun scan(): List<Found> = coroutineScope {
        val gate = Semaphore(80)
        val jobs = localSubnets().flatMap { (prefix, own, kind) ->
            (1..254).filter { it != own }.map { n -> async { gate.withPermit { probe("$prefix.$n", kind) } } }
        }
        jobs.awaitAll().filterNotNull().distinctBy { it.url }
    }
}
