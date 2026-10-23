package dev.tarilka0gg.homectl

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's identity. An EC P-256 key pair created inside the phone's secure hardware (StrongBox where there is one); the
 * private key can never be read out, copied or backed up. Its SHA-256 fingerprint identifies the phone. The public half is
 * registered with each machine once, at home; from then on requests through the public tunnel must be signed with it, so a
 * leaked access token alone is useless to anyone else.
 */
object DeviceKey {
    private const val ALIAS = "homectl.device.v1"
    private val random = SecureRandom()

    @Synchronized
    private fun pair(): KeyPair {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // A key that exists but cannot be read right now (the keystore can be busy just after an update or a boot) must never be
        // replaced: a new key would be a new phone as far as the machines are concerned. Wait, and give up without replacing it.
        for (attempt in 0..4) {
            val e = runCatching { ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry }.getOrNull()
            if (e != null) return KeyPair(e.certificate.publicKey, e.privateKey)
            if (!runCatching { ks.containsAlias(ALIAS) }.getOrDefault(true)) break
            Diag.log("device key present but unreadable (try ${attempt + 1})")
            Thread.sleep(200)
        }
        if (runCatching { ks.containsAlias(ALIAS) }.getOrDefault(true)) throw java.io.IOException("the device key exists but cannot be read")
        Diag.log("device key created (none existed)")
        fun gen(strongBox: Boolean): KeyPair {
            val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(b.build()) }.generateKeyPair()
        }
        return try { gen(true) } catch (_: Exception) { gen(false) }
    }

    /** The public key in the X.509 form the machines store (base64, no line breaks). */
    fun publicKeyB64(): String = Base64.encodeToString(pair().public.encoded, Base64.NO_WRAP)

    /** SHA-256 of the public key, as lowercase hex. */
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(pair().public.encoded).joinToString("") { "%02x".format(it) }

    /** The fingerprint grouped for reading aloud: AB12 CD34 … */
    fun fingerprintPretty(): String = fingerprint().uppercase().chunked(4).joinToString(" ")

    /** The id the machines know this phone by (first 16 hex digits of the fingerprint). */
    fun idOrNull(): String? = runCatching { id() }.getOrNull()

    fun id(): String = fingerprint().take(16)

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    class Signed(val dev: String, val ts: Long, val nonce: String, val sig: String)

    /** A signature valid for exactly this request (method + path and query), for two minutes, once. */
    fun sign(method: String, pathQuery: String): Signed {
        val ts = System.currentTimeMillis()
        val nonce = b64url(ByteArray(12).also { random.nextBytes(it) })
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(pair().private as PrivateKey)
        s.update("$method\n$pathQuery\n$ts\n$nonce".toByteArray())
        return Signed(id(), ts, nonce, b64url(s.sign()))
    }

    fun headers(method: String, pathQuery: String): Map<String, String> = sign(method, pathQuery).let {
        mapOf("X-Homectl-Dev" to it.dev, "X-Homectl-Ts" to it.ts.toString(), "X-Homectl-Nonce" to it.nonce, "X-Homectl-Sig" to it.sig)
    }

    /** Query-string form for WebSockets, which can't carry custom headers. */
    fun query(method: String, pathQuery: String): String = sign(method, pathQuery).let { "dev=${it.dev}&ts=${it.ts}&n=${it.nonce}&sig=${it.sig}" }
}
