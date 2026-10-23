package dev.tarilka0gg.homectl

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * The phone's own keyboard, typing straight into a terminal: every letter goes out as it is typed, in whatever layout and
 * language the keyboard is set to, and suggestions are switched off so nothing is held back in a composing word (the same way
 * terminal apps such as Termux ask for it). Backspace, Enter and the arrow keys arrive as the keys they are.
 */
class TermInput(ctx: Context, private val out: (String) -> Unit) : View(ctx) {
    init { isFocusable = true; isFocusableInTouchMode = true }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(attrs: EditorInfo): InputConnection {
        attrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        attrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            /** What the keyboard is still composing, already sent; a change to it is sent as backspaces plus the new tail. */
            private var comp = ""

            private fun replaceComposing(now: String) {
                var common = 0
                while (common < comp.length && common < now.length && comp[common] == now[common]) common++
                repeat(comp.length - common) { out("\u007f") }
                if (now.length > common) out(now.substring(common))
                comp = now
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean { replaceComposing(text.toString()); return true }
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { replaceComposing(text.toString().replace("\n", "\r")); comp = ""; return true }
            override fun finishComposingText(): Boolean { comp = ""; return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (comp.isNotEmpty()) comp = comp.dropLast(minOf(beforeLength, comp.length))
                repeat(beforeLength) { out("\u007f") }
                return true
            }
            override fun performEditorAction(editorAction: Int): Boolean { out("\r"); return true }
            override fun sendKeyEvent(event: KeyEvent): Boolean { if (event.action == KeyEvent.ACTION_DOWN) key(event); return true }
        }
    }

    /** Hardware keyboards and the keys a soft keyboard sends as key events. */
    private fun key(e: KeyEvent): Boolean {
        val s = when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            else -> {
                val c = e.getUnicodeChar(e.metaState and KeyEvent.META_CTRL_MASK.inv())
                if (c == 0) return false
                val ch = c.toChar().toString()
                if (e.isCtrlPressed && ch[0] in 'a'..'z') (ch[0].code and 0x1f).toChar().toString() else ch
            }
        }
        out(s)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(event) || super.onKeyDown(keyCode, event)

    fun show() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // after the keyboard was closed the view still has focus, and a plain show request is ignored; restart the input first
        imm.restartInput(this)
        post { imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT); androidx.core.view.ViewCompat.getWindowInsetsController(this)?.show(androidx.core.view.WindowInsetsCompat.Type.ime()) }
    }
    fun hide() { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0) }
}
