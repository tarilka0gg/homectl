package dev.tarilka0gg.homectl

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The common clipboard as a foreground service: a persistent notification keeps it alive while the app is closed, and it
 * puts everything copied on a machine onto the phone's clipboard. Android doesn't let a background app *read* the phone's
 * clipboard, so for phone → machine there is a "Send clipboard" button on the notification and a quick-settings tile that
 * briefly bring a see-through screen to the front, read the clipboard and send it. (While homectl itself is open that
 * happens automatically.)
 */
class ClipService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = HashMap<String, Job>()
    private var lastShown = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hc.init(this)
        if (intent?.action == ACTION_STOP) {
            Hc.machines.forEach { ClipSync.set(it.id, false) }
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification("starting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        if (jobs["_watch"] == null) jobs["_watch"] = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            val on = Hc.machines.filter { ClipSync.isOn(it.id) }
            if (on.isEmpty()) { stopSelf(); return }
            on.forEach { m -> if (jobs[m.id]?.isActive != true) jobs[m.id] = scope.launch { ClipSync.run(applicationContext, m) } }
            jobs.keys.filter { it != "_watch" && on.none { m -> m.id == it } }.forEach { jobs.remove(it)?.cancel() }
            val text = "with ${on.joinToString { it.name }}" + (ClipSync.note?.let { " · $it" } ?: "")
            if (text != lastShown) { lastShown = text; nm.notify(ID, notification(text)) }
            delay(2500)
        }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Common clipboard", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val send = PendingIntent.getActivity(this, 1, Intent(this, SendClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, ClipService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Common clipboard on")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Send clipboard", send).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ID = 4711
        const val CHANNEL = "clipboard"
        const val ACTION_STOP = "dev.tarilka0gg.homectl.STOP_CLIP"

        /** Starts the service if any machine has the common clipboard on. */
        fun ensure(ctx: Context) {
            Hc.init(ctx)
            if (Hc.machines.any { ClipSync.isOn(it.id) }) runCatching { ctx.startForegroundService(Intent(ctx, ClipService::class.java)) }
        }
    }
}

/** See-through screen: wait until it has focus (Android only then allows reading the clipboard), send the phone's clipboard, close. */
class SendClipActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hc.init(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val app = applicationContext
        val clip = PhoneClips.read(app)
        val targets = Hc.machines.filter { ClipSync.isOn(it.id) }.ifEmpty { listOfNotNull(Hc.cur) }
        if (clip == null || targets.isEmpty()) {
            Toast.makeText(app, if (clip == null) "homectl: the phone's clipboard is empty" else "homectl: no machine paired", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        PhoneClips.add(clip)
        CoroutineScope(Dispatchers.Main).launch {
            val results = targets.map { sendToMachine(it, clip) }
            Toast.makeText(app, (if (results.all { it.first }) "✓ " else "✗ ") + results.joinToString("; ") { it.second }, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

/** Quick-settings tile: tap it to send the phone's clipboard to the machine(s). */
class ClipTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_ACTIVE; label = "Send clipboard"; updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, SendClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 5, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}

/** Brings the service back after a reboot if the common clipboard was on. */
class ClipBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) ClipService.ensure(ctx)
    }
}
