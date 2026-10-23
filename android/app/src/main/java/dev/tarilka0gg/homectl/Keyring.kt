package dev.tarilka0gg.homectl

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * The app's keyring. Secrets (the machines' access tokens and any saved `doas` passwords) are encrypted with an AES key
 * that lives in the phone's hardware-backed Android Keystore and can only be used shortly after you unlock with your
 * fingerprint, face or screen-lock PIN/pattern/password. They are never stored readable, and never leave the phone
 * except over the encrypted link to the one machine they belong to.
 */
object Keyring {
    private const val ALIAS_STRONG = "homectl.keyring.v1"
    private const val ALIAS_BOUND = "homectl.bound.v1"
    private const val AUTH_SECONDS = 300
    private lateinit var appCtx: Context
    private lateinit var prefs: android.content.SharedPreferences

    /** Set once the user has unlocked in this app run (and not locked again since). */
    @Volatile var unlocked = false
    @Volatile private var lastAuthAt = 0L

    fun init(ctx: Context) {
        if (::appCtx.isInitialized) return
        appCtx = ctx.applicationContext
        prefs = appCtx.getSharedPreferences("homectl-keyring", Context.MODE_PRIVATE)
    }

    /** True when the phone has a screen lock, which the keyring needs to protect anything. */
    fun deviceSecure(): Boolean = (appCtx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** Passwords are "strong": the key needs a fingerprint/face/PIN unlock within the last five minutes. Everything else is only bound to this phone's hardware. */
    private fun key(strong: Boolean, create: Boolean = true): SecretKey {
        val alias = if (strong) ALIAS_STRONG else ALIAS_BOUND
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // an existing key that cannot be read right now is waited for and never replaced: a new key could not open what the old one sealed
        for (attempt in 0..4) {
            val k = runCatching { ks.getKey(alias, null) as? SecretKey }.getOrNull()
            if (k != null) return k
            if (!runCatching { ks.containsAlias(alias) }.getOrDefault(true)) break
            Diag.log("keyring key $alias present but unreadable (try ${attempt + 1})")
            Thread.sleep(200)
        }
        if (runCatching { ks.containsAlias(alias) }.getOrDefault(true)) throw java.io.IOException("keyring key $alias exists but cannot be read")
        if (!create) throw java.security.GeneralSecurityException("no keyring key $alias")
        Diag.log("keyring key $alias created (none existed)")
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strong && deviceSecure()) {
            b.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
            if (Build.VERSION.SDK_INT >= 24) b.setInvalidatedByBiometricEnrollment(false)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }

    private fun encrypt(plain: ByteArray, strong: Boolean): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(strong)) }
        return Base64.encodeToString(c.iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    private fun decrypt(blob: String, strong: Boolean): ByteArray {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(strong, create = false), GCMParameterSpec(128, raw, 0, 12)) }
        return c.doFinal(raw, 12, raw.size - 12)
    }

    fun has(name: String) = prefs.contains(name)
    fun remove(name: String) { prefs.edit().remove(name).apply() }

    /** Encrypts and stores [value]; needs a recent unlock (the caller makes sure of it). */
    fun put(name: String, value: String, strong: Boolean = true) { prefs.edit().putString(name, encrypt(value.toByteArray(), strong)).apply() }

    sealed class Got {
        data class Value(val text: String) : Got()
        object Missing : Got()
        object NeedsUnlock : Got()
        object Lost : Got() // the key was removed (the screen lock was turned off or changed to none)
    }

    fun get(name: String, strong: Boolean = true): Got {
        val blob = prefs.getString(name, null) ?: return Got.Missing
        return try {
            Got.Value(String(decrypt(blob, strong)))
        } catch (_: UserNotAuthenticatedException) {
            Got.NeedsUnlock
        } catch (_: KeyPermanentlyInvalidatedException) {
            Got.Lost
        } catch (_: Exception) {
            Got.Lost
        }
    }

    /**
     * Shows the system prompt (fingerprint, face, or the screen-lock PIN/pattern/password) and returns true on success.
     * Without a screen lock there is nothing to ask for, so it succeeds.
     */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null): Boolean {
        if (!deviceSecure()) { unlocked = true; return true }
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    lastAuthAt = System.currentTimeMillis()
                    if (cont.isActive) cont.resume(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
            })
            val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).apply { subtitle?.let { setSubtitle(it) } }.setAllowedAuthenticators(allowed).build()
            prompt.authenticate(info)
        }
    }

    /** Reads [name], asking for the fingerprint/face/PIN again if the last unlock is older than five minutes. */
    suspend fun read(activity: FragmentActivity, name: String, why: String): String? {
        for (attempt in 0..1) {
            when (val g = get(name)) {
                is Got.Value -> return g.text
                Got.Missing, Got.Lost -> return null
                Got.NeedsUnlock -> if (!authenticate(activity, "Unlock homectl", why)) return null
            }
        }
        return null
    }

    /** Stores [value], asking to unlock first if needed. */
    suspend fun write(activity: FragmentActivity, name: String, value: String, why: String): Boolean {
        for (attempt in 0..1) {
            try { put(name, value); return true } catch (_: UserNotAuthenticatedException) { if (!authenticate(activity, "Unlock homectl", why)) return false }
            catch (_: Exception) { return false }
        }
        return false
    }
}
