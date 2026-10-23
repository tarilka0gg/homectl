package dev.tarilka0gg.homectl

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hc.init(this)
        Hc.activity = this
        applyScreenshotBlock()
        handle(intent)
        // The soft keyboard resizes the window; compare the visible area with the screen to know if it is up.
        val root = window.decorView
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(r)
            val up = r.bottom < resources.displayMetrics.heightPixels * 0.8
            TerminalHub.imeVisible = up
            if (Hc.imeUp != up) Hc.imeUp = up
        }
        setContent { HomectlTheme { LockGate(this) { App(); Hc.pwRequest?.let { PasswordDialog(it, this) } } } }
    }

    /** Optional: no screenshots, and a blank card in the app switcher (Security section on the System tab). */
    fun applyScreenshotBlock() {
        if (Hc.memBool("block.screenshots")) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        super.onStop()
        LockState.onStop()
        Diag.log("stopped (finishing=$isFinishing, config change=$isChangingConfigurations)")
    }

    override fun onStart() {
        super.onStart()
        val was = LockState.locked
        LockState.onStart()
        Diag.log("started (locked $was -> ${LockState.locked})")
    }

    override fun onDestroy() {
        Diag.log("destroyed (finishing=$isFinishing, config change=$isChangingConfigurations)")
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        TerminalHub.resumeAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i?.data?.takeIf { it.scheme == "homectl" }?.let { Hc.pairFrom(it.toString()) }
    }
}

@Composable
fun HomectlTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
