package dev.tarilka0gg.homectl

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Share-sheet target: sends the shared file(s) to the daemon's inbox. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hc.init(this)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (!Hc.paired || uris.isEmpty()) {
            Toast.makeText(this, "homectl: not paired or nothing to send", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(this, "Sending ${uris.size} file(s)…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            var ok = 0
            for (u in uris) if (Hc.upload(app, u, displayName(u))) ok++
            Toast.makeText(app, "homectl: sent $ok/${uris.size}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun displayName(u: Uri): String =
        runCatching {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: "shared-${System.currentTimeMillis()}"
}
