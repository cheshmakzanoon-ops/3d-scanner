package com.realityforge.scanner.qa

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException

/** The one shared 026/080/088/092 fault source. Only SYNTHETIC test assets are exposed. */
class FaultDocumentProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.realityforge.scanner.test.faults"
        val MODES = listOf("normal", "truncated", "slow", "revoked", "short-write", "enospc")
    }
    private val thread = HandlerThread("bounded-document-faults").apply { start() }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { addRow(columns.map { when(it) {
            Root.COLUMN_ROOT_ID -> "synthetic"; Root.COLUMN_DOCUMENT_ID -> "root"
            Root.COLUMN_TITLE -> "Synthetic QA faults"; Root.COLUMN_FLAGS -> 0; else -> null
        } }.toTypedArray()) }
    }
    private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { ids.forEach { id -> addRow(columns.map { when(it) {
            Document.COLUMN_DOCUMENT_ID -> id; Document.COLUMN_DISPLAY_NAME -> "$id.jpg"
            Document.COLUMN_MIME_TYPE -> if(id == "root") Document.MIME_TYPE_DIR else "image/jpeg"
            Document.COLUMN_FLAGS -> if(id in listOf("short-write", "enospc")) Document.FLAG_SUPPORTS_WRITE else 0
            else -> null
        } }.toTypedArray()) } }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) = rows(listOf(documentId), projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = rows(if(parentDocumentId == "root") MODES else emptyList(), projection)
    override fun getDocumentType(documentId: String) = if(documentId == "root") Document.MIME_TYPE_DIR else "image/jpeg"
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        if(documentId !in MODES || documentId == "revoked") throw FileNotFoundException("Synthetic revoked/unknown destination")
        if(mode.contains('w')) {
            require(documentId in listOf("enospc", "short-write")) { "Read-only synthetic fixture" }
            var written = 0L
            val manager = requireNotNull(context).getSystemService(StorageManager::class.java)
            return manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = written
                    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                        signal?.throwIfCanceled()
                        if(documentId == "enospc" || written >= 32) throw ErrnoException("synthetic bounded write", if(documentId == "enospc") OsConstants.ENOSPC else OsConstants.EIO)
                        val count = minOf(size, 8, (32 - written).toInt())
                        written += count
                        return count
                    }
                    override fun onFsync() { }
                    override fun onRelease() { }
                }, Handler(thread.looper))
        }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        Thread {
            try {
                val bytes = requireNotNull(context).assets.open("relief/view-01.jpg").use { it.readBytes() }
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val limit = if(documentId == "truncated") 64 else bytes.size
                    var offset = 0
                    while(offset < limit) {
                        signal?.throwIfCanceled()
                        val count = minOf(1024, limit-offset)
                        output.write(bytes,offset,count)
                        offset += count
                        if(documentId == "slow") Thread.sleep(10)
                    }
                }
            } catch(error: Exception) { runCatching { pipe[1].closeWithError(error.javaClass.simpleName) } }
        }.start()
        return pipe[0]
    }
}
