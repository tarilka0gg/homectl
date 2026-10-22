package dev.tarilka0gg.homectl

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Encrypted links to the machines use their own self-signed certificates. Nothing is trusted by a certificate authority:
 * a connection is accepted only if the server's certificate has exactly the SHA-256 fingerprint the app pinned when it
 * paired. A different certificate (an eavesdropper in the middle) is refused.
 */
object Pin {
    private val factories = HashMap<String, SSLSocketFactory>()

    fun fingerprint(cert: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun factory(fp: String): SSLSocketFactory = factories.getOrPut(fp.lowercase()) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no client certificates")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val got = chain.firstOrNull()?.let { fingerprint(it.encoded) } ?: throw CertificateException("no certificate")
                // compare without stopping at the first difference
                val want = fp.lowercase()
                if (got.length != want.length || got.zip(want).fold(0) { acc, (a, b) -> acc or (a.code xor b.code) } != 0) {
                    throw CertificateException("the machine's certificate is not the one that was pinned")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }.socketFactory
    }

    /** The name in the certificate is irrelevant (the fingerprint is what is checked). */
    val anyHost = HostnameVerifier { _, _ -> true }
}

/**
 * WebViews and the video player can't use a pinned certificate, so they talk to a port on this phone's own loopback
 * (reachable only from the phone) and this forwards the bytes over the pinned, encrypted link.
 */
object LocalTls {
    private val ports = HashMap<String, Int>()

    @Synchronized
    fun forward(host: String, port: Int, fp: String): Int {
        val key = "$host:$port|$fp"
        ports[key]?.let { return it }
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "tls-forward") {
            while (true) {
                val client = try { server.accept() } catch (_: Exception) { return@thread }
                thread(isDaemon = true) {
                    try {
                        val upstream = Pin.factory(fp).createSocket(host, port) as SSLSocket
                        upstream.startHandshake()
                        pump(client, upstream)
                    } catch (_: Exception) {
                        runCatching { client.close() }
                    }
                }
            }
        }
        return server.localPort.also { ports[key] = it }
    }

    private fun pump(a: Socket, b: Socket) {
        fun copy(from: InputStream, to: OutputStream) {
            try {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { a.close() }
                runCatching { b.close() }
            }
        }
        thread(isDaemon = true) { copy(a.getInputStream(), b.getOutputStream()) }
        copy(b.getInputStream(), a.getOutputStream())
    }
}
