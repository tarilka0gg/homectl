package dev.tarilka0gg.homectl

/**
 * Reads a multipart motion-JPEG stream: every part has a Content-Length header, a blank line, then that many bytes of JPEG.
 * Allocates almost nothing per frame: one buffered reader, one growing byte buffer, and two bitmaps that take turns (a frame is
 * decoded into the one that is not on screen), instead of a new byte array and a new 1.2 MB bitmap fifteen times a second.
 */
fun readMjpeg(raw: java.io.InputStream, keepGoing: () -> Boolean, onFrame: (android.graphics.Bitmap) -> Unit) {
    val input = java.io.BufferedInputStream(raw, 64 * 1024)
    val head = StringBuilder()
    fun line(): String? {
        head.setLength(0)
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return head.toString().trimEnd('\r')
            if (head.length < 200) head.append(b.toChar())
        }
    }
    var buf = ByteArray(96 * 1024)
    val spare = arrayOfNulls<android.graphics.Bitmap>(2)
    var turn = 0
    while (keepGoing()) {
        var len = -1
        while (true) {
            val l = line() ?: return
            if (l.startsWith("content-length:", ignoreCase = true)) len = l.substringAfter(':').trim().toIntOrNull() ?: -1
            if (l.isEmpty() && len >= 0) break
        }
        if (len <= 0 || len > 8_000_000) return
        if (buf.size < len) buf = ByteArray(len + len / 4)
        var got = 0
        while (got < len) { val n = input.read(buf, got, len - got); if (n < 0) return; got += n }
        val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true; inBitmap = spare[turn] }
        val bmp = runCatching { android.graphics.BitmapFactory.decodeByteArray(buf, 0, len, opts) }.getOrNull()
            ?: android.graphics.BitmapFactory.decodeByteArray(buf, 0, len).also { spare[turn] = null } // the old bitmap did not fit: start over with a new one
            ?: continue
        spare[turn] = bmp
        turn = 1 - turn
        onFrame(bmp)
    }
}
