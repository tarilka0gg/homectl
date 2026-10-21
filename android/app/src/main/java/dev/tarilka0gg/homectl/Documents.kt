package dev.tarilka0gg.homectl

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Shows every paired machine as a storage location in Android's system file picker, so any app
 * (Telegram, Gmail, a file manager…) can pick or attach files straight from your computers. Read-only.
 * Document ids look like "<machine id>|<absolute path>".
 */
class HomectlDocuments : DocumentsProvider() {
    private val listings = HashMap<String, Pair<Long, JSONObject>>()

    override fun onCreate(): Boolean {
        Hc.init(context!!)
        return true
    }

    private fun machine(docId: String) = Hc.machines.find { it.id == docId.substringBefore('|') }
    private fun pathOf(docId: String) = docId.substringAfter('|')

    private fun list(m: Machine, path: String): JSONObject? {
        val key = "${m.id}|$path"
        synchronized(listings) { listings[key]?.takeIf { System.currentTimeMillis() - it.first < 5000 }?.let { return it.second } }
        val j = runBlocking {
            if (m.active == null) m.resolve()
            m.json("/api/fs/list" + if (path.isEmpty()) "" else "?path=" + Uri.encode(path))
        } ?: return null
        synchronized(listings) { listings[key] = System.currentTimeMillis() to j }
        return j
    }

    private fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun addEntry(c: MatrixCursor, m: Machine, dir: String, e: JSONObject) {
        val name = e.getString("name")
        val isDir = e.optBoolean("dir")
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, "${m.id}|${if (dir == "/") "" else dir}/$name")
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mime(name))
            .add(Document.COLUMN_SIZE, e.optLong("size"))
            .add(Document.COLUMN_LAST_MODIFIED, e.optLong("mtime") * 1000)
            .add(Document.COLUMN_FLAGS, 0)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID))
        for (m in Hc.machines) {
            val home = list(m, "")?.optString("path") ?: continue
            c.newRow()
                .add(Root.COLUMN_ROOT_ID, m.id)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_TITLE, m.name)
                .add(Root.COLUMN_SUMMARY, "homectl")
                .add(Root.COLUMN_DOCUMENT_ID, "${m.id}|$home")
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC)
        val m = machine(documentId) ?: return c
        val path = pathOf(documentId)
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        val entry = if (name.isEmpty()) null else list(m, parent)?.optJSONArray("entries")?.objs()?.firstOrNull { it.str("name") == name }
        if (entry != null) addEntry(c, m, parent, entry)
        else c.newRow().add(Document.COLUMN_DOCUMENT_ID, documentId).add(Document.COLUMN_DISPLAY_NAME, name.ifEmpty { m.name })
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR).add(Document.COLUMN_FLAGS, 0).add(Document.COLUMN_SIZE, 0)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC)
        val m = machine(parentDocumentId) ?: return c
        val dir = pathOf(parentDocumentId)
        list(m, dir)?.optJSONArray("entries")?.objs()?.filter { !it.optBoolean("hidden") }?.forEach { addEntry(c, m, dir, it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        machine(parentDocumentId)?.id == machine(documentId)?.id && pathOf(documentId).startsWith(pathOf(parentDocumentId).trimEnd('/') + "/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode.any { it == 'w' || it == 'a' }) throw java.io.FileNotFoundException("read-only")
        val m = machine(documentId) ?: throw java.io.FileNotFoundException()
        val path = pathOf(documentId)
        val dir = File(context!!.cacheDir, "documents").apply { mkdirs() }
        val f = File(dir, "${documentId.hashCode().toUInt()}-${path.substringAfterLast('/')}")
        val ok = runBlocking {
            if (m.active == null) m.resolve()
            m.download(path) { input, _ -> f.outputStream().use { input.copyTo(it) }; true }
        }
        if (ok != true) throw java.io.FileNotFoundException("download failed")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private companion object {
        val DEFAULT_DOC = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
