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

    /** Writes the line at once (apply() could be lost when the process is dying). */
    @Synchronized
    fun logNow(msg: String) {
        val p = prefs ?: return
        val line = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg
        val all = (p.getString("log", "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(60)
        p.edit().putString("log", all.joinToString("\n")).commit()
    }

    /** Records uncaught exceptions before the process dies. */
    fun watchCrashes() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                logNow("CRASH in ${t.name}: ${e::class.java.simpleName}: ${e.message?.take(160)} @ " + e.stackTrace.take(5).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
            }
            before?.uncaughtException(t, e)
        }
    }

    /** Why the previous runs of this app ended (Android 11+): a crash, the system killing it, the user swiping it away, … */
    fun noteExits(ctx: Context) {
        val p = prefs ?: return
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            val seen = p.getLong("exit.seen", 0L)
            val fresh = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > seen }.sortedBy { it.timestamp }
            for (i in fresh) {
                val why = when (i.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"; android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
                    android.app.ApplicationExitInfo.REASON_ANR -> "ANR"; android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user"
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped"; android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"; android.app.ApplicationExitInfo.REASON_SIGNALED -> "signal ${i.status}"
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exit(${i.status})"; android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
                    android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"; android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
                    else -> "reason ${i.reason}"
                }
                val at = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(i.timestamp))
                log("previous run ended at $at: $why ${i.description?.take(120).orEmpty()} (${i.processName})")
            }
            fresh.lastOrNull()?.let { p.edit().putLong("exit.seen", it.timestamp).apply() }
        }
    }
}
