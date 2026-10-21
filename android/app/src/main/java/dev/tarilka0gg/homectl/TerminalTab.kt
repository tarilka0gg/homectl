package dev.tarilka0gg.homectl

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/** A real terminal (xterm.js) on the selected machine's shell; "SSH" types an ssh command into it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen() {
    val m = Hc.cur ?: return
    var url by remember(m.id) { mutableStateOf<String?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var ssh by remember { mutableStateOf("") }
    LaunchedEffect(m.id) {
        if (m.active == null) m.resolve()
        url = m.termUrl()
        failed = url == null
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ssh, { ssh = it }, Modifier.weight(1f), label = { Text("ssh user@host") }, singleLine = true)
            FilledTonalButton(
                onClick = { if (ssh.isNotBlank()) web?.evaluateJavascript("hcSend(${JSONObject.quote("ssh ${ssh.trim()}\n")})", null) },
                enabled = web != null,
            ) { Text("SSH") }
        }
        if (failed) Text("Can't reach ${m.name}.", Modifier.padding(16.dp))
        url?.let { u ->
            key(u) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            setBackgroundColor(0xFF0B0F14.toInt())
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                    Log.d("homectl-term", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                                    Log.e("homectl-term", "load error ${req.url}: ${err.description}")
                                }

                                override fun onPageFinished(view: WebView, pageUrl: String?) {
                                    Log.d("homectl-term", "page finished $pageUrl")
                                    view.evaluateJavascript("hcStart(${JSONObject.quote(u)})", null)
                                }
                            }
                            loadUrl("file:///android_asset/term.html")
                            web = this
                        }
                    },
                    onRelease = { if (web === it) web = null; it.destroy() },
                )
            }
        }
    }
}
