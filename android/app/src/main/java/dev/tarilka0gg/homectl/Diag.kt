package dev.tarilka0gg.homectl

import android.content.Context

/**
 * A short breadcrumb trail kept on the phone (the last 60 events, no secrets), so that when the app loses its login or its
 * identity after an update the reason can be read off System → Copy diagnostics instead of guessed.
 */
object Diag {
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("homectl-diag", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun log(msg: String) {
        val p = prefs ?: return
        val line = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg
        val all = (p.getString("log", "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(60)
        p.edit().putString("log", all.joinToString("\n")).apply()
    }

    fun text(): String = prefs?.getString("log", "").orEmpty().ifBlank { "(nothing recorded yet)" }

    /** Notes a version change, once per install. */
    fun noteVersion(ctx: Context) {
        val p = prefs ?: return
        val now = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("?")
        val before = p.getString("version", null)
        if (before != now) { log(if (before == null) "first run of $now" else "app updated $before -> $now"); p.edit().putString("version", now).apply() }
    }
}
