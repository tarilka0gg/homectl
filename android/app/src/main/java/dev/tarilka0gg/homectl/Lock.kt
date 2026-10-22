@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tarilka0gg.homectl

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

/** Everything stays hidden until you unlock with your fingerprint, face or screen lock; it locks again a minute after you leave. */
object LockState {
    var locked by mutableStateOf(true)
    private var leftAt = 0L
    const val GRACE_MS = 60_000L

    fun onStop() { leftAt = System.currentTimeMillis() }
    fun onStart() {
        if (leftAt != 0L && System.currentTimeMillis() - leftAt > GRACE_MS) { locked = true; Keyring.unlocked = false }
    }
}

@Composable
fun LockGate(activity: FragmentActivity, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val secure = remember { Keyring.deviceSecure() }

    fun unlock() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = Keyring.authenticate(activity, "Unlock homectl", "Your machines, files and terminals")
            busy = false
            failed = !ok
            if (ok) LockState.locked = false
        }
    }
    LaunchedEffect(LockState.locked) { if (LockState.locked) unlock() }

    if (!LockState.locked) { content(); return }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(12.dp))
            Text("homectl is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (secure) "Unlock with your fingerprint, face or screen lock." else "Set a screen lock on this phone to protect the app.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failed) Text("Not unlocked.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            Button({ unlock() }, enabled = !busy) { Text("Unlock") }
        }
    }
}

/** Asks for a machine's doas password when an action needs root, optionally saving it in the keyring. */
@Composable
fun PasswordDialog(req: Hc.PwRequest, activity: FragmentActivity?) {
    val scope = rememberCoroutineScope()
    var pw by remember(req) { mutableStateOf("") }
    var save by remember(req) { mutableStateOf(true) }
    var busy by remember(req) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { req.answer(null) },
        title = { Text("Root password for ${req.machine.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (req.wrong) "That password was refused. Try again." else "This action needs root. Enter your doas (sudo) password for ${req.machine.name}.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(save, { save = it })
                    Text("Save it in this phone's keyring (asks for your fingerprint, face or screen lock to use it)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                scope.launch {
                    if (save && activity != null) Keyring.write(activity, "pw.${req.machine.id}", pw, "Save the root password for ${req.machine.name}")
                    req.answer(pw)
                }
            }, enabled = pw.isNotEmpty() && !busy) { Text("Continue") }
        },
        dismissButton = { TextButton({ req.answer(null) }) { Text("Cancel") } },
    )
}
