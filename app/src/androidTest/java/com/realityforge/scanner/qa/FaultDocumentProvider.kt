package com.realityforge.scanner.qa

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Sole shared test-APK provider. All retained bytes belong to explicitly configured synthetic tests. */
class FaultDocumentProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.realityforge.scanner.test.faults"
        const val MAX_BYTES = 1024 * 1024
        const val MAX_REQUESTS = 16
        const val MAX_EVENTS = 256
        val MODES = listOf("normal", "truncated", "slow", "revoked", "short-write", "enospc")
        private val REQUEST_MODES = setOf("normal", "slow", "silent-truncate", "enospc", "edquot", "revoked", "revoke-readback", "missing")
        private val REQUEST_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
    private val thread = HandlerThread("bounded-document-faults").apply { start() }
    private val lock = Any()
    private val handles = mutableMapOf<String, Int>()
    private val root get() = File(requireNotNull(context).filesDir, "qa-fault-documents").apply { mkdirs() }
    private fun requestId(id: String): String = id.also { require(REQUEST_ID.matches(it)) { "Invalid synthetic request ID" } }
    private fun directory(id: String) = File(root, requestId(id))
    private fun payload(id: String) = File(directory(id), "payload.bin")
    private fun metadata(id: String) = AtomicFile(File(directory(id), "state.json"))
    private fun read(id: String): JSONObject {
        val file = metadata(id)
        if (!file.baseFile.isFile) throw FileNotFoundException("Unknown synthetic request")
        return file.openRead().bufferedReader().use { JSONObject(it.readText()) }
    }
    private fun save(id: String, state: JSONObject) {
        val file = metadata(id)
        val stream = file.startWrite()
        try { stream.write(state.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun event(id: String, state: JSONObject, name: String, details: JSONObject = JSONObject()) {
        val events = state.getJSONArray("events")
        val sequence = state.getLong("eventSequence") + 1
        state.put("eventSequence", sequence)
        events.put(details.put("sequence", sequence).put("event", name)
            .put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos()).put("wallTimeMillis", System.currentTimeMillis()))
        if (events.length() > MAX_EVENTS) { events.remove(0); state.put("droppedEvents", state.getLong("droppedEvents") + 1) }
        save(id, state)
    }
    private fun parsedDocument(documentId: String): Pair<String, Boolean>? = when {
        documentId.startsWith("request/") -> requestId(documentId.removePrefix("request/")) to false
        documentId.startsWith("request-root/") -> requestId(documentId.removePrefix("request-root/")) to true
        else -> null
    }
    private fun available(state: JSONObject) {
        if (!state.getBoolean("created") || state.getBoolean("deleted") || state.getJSONObject("config").getString("mode") == "missing")
            throw FileNotFoundException("Synthetic document unavailable")
    }
    private fun snapshot(id: String, state: JSONObject): JSONObject {
        val data = payload(id)
        val sha = MessageDigest.getInstance("SHA-256")
        if (data.isFile) data.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
        }
        return JSONObject(state.toString()).put("providerPid", android.os.Process.myPid()).put("storedBytes", if (data.isFile) data.length() else 0)
            .put("storedSha256", sha.digest().joinToString("") { "%02x".format(it) })
    }
    private fun integer(config: JSONObject, key: String, default: Long): Long {
        val value = config.opt(key) ?: return default
        require(value is Int || value is Long) { "$key must be an integer" }
        return (value as Number).toLong()
    }
    private fun boolean(config: JSONObject, key: String, default: Boolean): Boolean {
        val value = config.opt(key) ?: return default
        require(value is Boolean) { "$key must be a boolean" }
        return value
    }
    override fun onCreate() = true
    override fun shutdown() { thread.quitSafely(); super.shutdown() }

    /** Administrative API is additionally permission checked; no arbitrary file paths/reset-all exist. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!method.startsWith("qa.")) return super.call(method, arg, extras)
        requireNotNull(context).enforceCallingOrSelfPermission("android.permission.MANAGE_DOCUMENTS", "Synthetic fault configuration requires MANAGE_DOCUMENTS")
        return synchronized(lock) {
            when (method) {
                "qa.configure" -> {
                    val configText = requireNotNull(extras?.getString("config"))
                    require(configText.length <= 4096) { "Oversized configuration" }
                    val config = JSONObject(configText)
                    val allowed = setOf("schema", "requestId", "mode", "verification", "limitBytes", "delayMillis", "deleteFails", "preExisting", "created", "synthetic")
                    require(config.keys().asSequence().all { it in allowed }) { "Unknown configuration field" }
                    require(integer(config, "schema", -1) == 1L && boolean(config, "synthetic", false)) { "Synthetic schema1 only" }
                    val id = requestId(config.getString("requestId"))
                    require(!directory(id).exists()) { "Request exists; explicitly reset it before reuse" }
                    require((root.listFiles()?.count { it.isDirectory } ?: 0) < MAX_REQUESTS) { "Synthetic request capacity reached" }
                    require(!config.has("mode") || config.get("mode") is String)
                    require(!config.has("verification") || config.get("verification") is String)
                    val mode = config.optString("mode", "normal")
                    val verification = config.optString("verification", "readback")
                    val limit = integer(config, "limitBytes", MAX_BYTES.toLong())
                    val delay = integer(config, "delayMillis", if (mode == "slow") 10L else 0L)
                    require(mode in REQUEST_MODES && verification in setOf("readback", "size-only", "none"))
                    require(limit in 0..MAX_BYTES.toLong() && delay in 0..25)
                    val preExisting = boolean(config, "preExisting", false)
                    val created = boolean(config, "created", true)
                    require(!preExisting || created)
                    config.put("mode", mode).put("verification", verification).put("limitBytes", limit)
                        .put("delayMillis", delay).put("preExisting", preExisting).put("created", created)
                        .put("deleteFails", boolean(config, "deleteFails", false))
                    check(directory(id).mkdir())
                    payload(id).outputStream().use { it.flush() }
                    val state = JSONObject().put("schema", 1).put("requestId", id).put("config", config)
                        .put("created", created).put("deleted", false).put("revoked", mode == "revoked")
                        .put("writeOpens", 0).put("readOpens", 0).put("writeCalls", 0).put("deleteCalls", 0)
                        .put("acceptedBytes", 0L).put("storedWriteBytes", 0L).put("eventSequence", 0L)
                        .put("droppedEvents", 0L).put("configuredProviderPid", android.os.Process.myPid()).put("events", JSONArray())
                    event(id, state, "configured")
                    Bundle().apply {
                        putString("documentId", "request/$id"); putString("parentDocumentId", "request-root/$id")
                        putString("json", snapshot(id, state).toString())
                    }
                }
                "qa.snapshot" -> {
                    val id = requestId(requireNotNull(arg))
                    Bundle().apply { putString("json", snapshot(id, read(id)).toString()) }
                }
                "qa.revoke" -> {
                    val id = requestId(requireNotNull(arg)); val state = read(id)
                    state.put("revoked", true); event(id, state, "permissionRevoked")
                    Bundle().apply { putString("json", snapshot(id, state).toString()) }
                }
                "qa.reset" -> {
                    val id = requestId(requireNotNull(arg))
                    require((handles[id] ?: 0) == 0) { "Close request descriptors before reset" }
                    val removed = directory(id).deleteRecursively()
                    Bundle().apply { putBoolean("removed", removed) }
                }
                else -> throw IllegalArgumentException("Unknown synthetic fault operation")
            }
        }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> { when (it) {
            Root.COLUMN_ROOT_ID -> "synthetic"; Root.COLUMN_DOCUMENT_ID -> "root"
            Root.COLUMN_TITLE -> "Synthetic QA faults"; Root.COLUMN_FLAGS -> 0; else -> null
        } }.toTypedArray()) }
    }
    private fun rows(ids: List<String>, projection: Array<out String>?): Cursor = synchronized(lock) {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
        MatrixCursor(columns).apply { ids.forEach { id ->
            val request = parsedDocument(id)
            val state = request?.let { read(it.first) }
            val isDirectory = id == "root" || request?.second == true
            if (state != null && !isDirectory) available(state)
            if (request == null && id != "root" && id !in MODES) throw FileNotFoundException("Unknown synthetic document")
            addRow(columns.map<String, Any?> { when (it) {
                Document.COLUMN_DOCUMENT_ID -> id
                Document.COLUMN_DISPLAY_NAME -> if (isDirectory) "Synthetic request" else if (request == null) "$id.jpg" else "synthetic-output.bin"
                Document.COLUMN_MIME_TYPE -> if (isDirectory) Document.MIME_TYPE_DIR else if (request == null) "image/jpeg" else "application/octet-stream"
                Document.COLUMN_FLAGS -> when {
                    request?.second == true && state?.getJSONObject("config")?.getBoolean("preExisting") == false -> Document.FLAG_DIR_SUPPORTS_CREATE
                    request != null && !isDirectory -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                    id in listOf("short-write", "enospc") -> Document.FLAG_SUPPORTS_WRITE
                    else -> 0
                }
                Document.COLUMN_SIZE -> if (request != null && !isDirectory && state?.getJSONObject("config")?.getString("verification") != "none") payload(request.first).length() else null
                else -> null
            } }.toTypedArray())
        } }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) = rows(listOf(documentId), projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = synchronized(lock) {
        val ids = when {
            parentDocumentId == "root" -> MODES + (root.listFiles()?.filter { it.isDirectory }?.map { "request-root/${it.name}" } ?: emptyList())
            parentDocumentId.startsWith("request-root/") -> {
                val id = requestId(parentDocumentId.removePrefix("request-root/")); val state = read(id)
                if (state.getBoolean("created") && !state.getBoolean("deleted") && state.getJSONObject("config").getString("mode") != "missing") listOf("request/$id") else emptyList()
            }
            else -> emptyList()
        }
        rows(ids, projection)
    }
    override fun getDocumentType(documentId: String): String = synchronized(lock) {
        val request = parsedDocument(documentId)
        if (request != null) { val state = read(request.first); if (!request.second) available(state) }
        else if (documentId != "root" && documentId !in MODES) throw FileNotFoundException("Unknown synthetic document")
        if (documentId == "root" || request?.second == true) Document.MIME_TYPE_DIR else if (request == null) "image/jpeg" else "application/octet-stream"
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String = synchronized(lock) {
        val request = parsedDocument(parentDocumentId) ?: throw FileNotFoundException("Only configured synthetic request parents support creation")
        require(request.second)
        val state = read(request.first)
        require(!state.getJSONObject("config").getBoolean("preExisting") && !state.getBoolean("created")) { "Single new synthetic output only" }
        state.put("created", true); event(request.first, state, "create")
        "request/${request.first}"
    }
    override fun deleteDocument(documentId: String) = synchronized(lock) {
        val request = parsedDocument(documentId) ?: throw FileNotFoundException("Read-only synthetic fixture")
        require(!request.second)
        val state = read(request.first)
        state.put("deleteCalls", state.getInt("deleteCalls") + 1)
        event(request.first, state, "deleteAttempt", JSONObject().put("preExisting", state.getJSONObject("config").getBoolean("preExisting")))
        available(state)
        if (state.getJSONObject("config").getBoolean("deleteFails")) throw FileNotFoundException("Configured synthetic delete failure")
        check(payload(request.first).delete() || !payload(request.first).exists())
        state.put("deleted", true); event(request.first, state, "deleted")
        Unit
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val request = parsedDocument(documentId)
        if (request != null) {
            require(!request.second)
            return openRequest(request.first, mode, signal)
        }
        if (documentId == "revoked") throw SecurityException("Synthetic permission revoked")
        if (documentId !in MODES) throw FileNotFoundException("Unknown synthetic destination")
        if (mode.contains('w')) {
            require(documentId in listOf("enospc", "short-write")) { "Read-only synthetic fixture" }
            var written = 0L
            val manager = requireNotNull(context).getSystemService(StorageManager::class.java)
            return manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY, object : ProxyFileDescriptorCallback() {
                override fun onGetSize() = written
                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                    signal?.throwIfCanceled()
                    if (documentId == "enospc" || written >= 32) throw ErrnoException("synthetic bounded write", if (documentId == "enospc") OsConstants.ENOSPC else OsConstants.EIO)
                    val count = minOf(size, 8, (32 - written).toInt()); written += count; return count
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
                    val limit = if (documentId == "truncated") 64 else bytes.size
                    var offset = 0
                    while (offset < limit) {
                        signal?.throwIfCanceled()
                        val count = minOf(1024, limit - offset); output.write(bytes, offset, count); offset += count
                        if (documentId == "slow") Thread.sleep(10)
                    }
                }
            } catch (error: Exception) { runCatching { pipe[1].closeWithError(error.javaClass.simpleName) } }
        }.start()
        return pipe[0]
    }
    private fun openRequest(id: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = synchronized(lock) {
        require(mode in setOf("r", "w", "wt")) { "Bounded fixtures support r/w/wt only" }
        val state = read(id); available(state)
        val config = state.getJSONObject("config")
        val writing = mode != "r"
        if (state.getBoolean("revoked") || (!writing && config.getString("mode") == "revoke-readback" && state.getInt("writeOpens") > 0)) {
            event(id, state, "openPermissionDenied"); throw SecurityException("Configured synthetic permission revoked")
        }
        if (!writing && config.getString("verification") != "readback") {
            event(id, state, "readbackUnsupported"); throw FileNotFoundException("Synthetic destination has no readback capability")
        }
        require((handles[id] ?: 0) < 4) { "Synthetic descriptor bound reached" }
        if (writing) {
            RandomAccessFile(payload(id), "rw").use { it.setLength(0); it.fd.sync() }
            state.put("writeOpens", state.getInt("writeOpens") + 1)
        } else state.put("readOpens", state.getInt("readOpens") + 1)
        event(id, state, if (writing) "openWrite" else "openRead")
        handles[id] = (handles[id] ?: 0) + 1
        val manager = requireNotNull(context).getSystemService(StorageManager::class.java)
        try {
            manager.openProxyFileDescriptor(if (writing) ParcelFileDescriptor.MODE_WRITE_ONLY else ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = synchronized(lock) { payload(id).length() }
                    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = synchronized(lock) {
                        signal?.throwIfCanceled()
                        val current = read(id)
                        if (current.getBoolean("revoked")) {
                            event(id, current, "descriptorPermissionDenied"); throw ErrnoException("synthetic revoked descriptor", OsConstants.EACCES)
                        }
                        require(offset >= 0 && size in 0..data.size)
                        val options = current.getJSONObject("config")
                        val fault = options.getString("mode")
                        val threshold = if (fault in setOf("silent-truncate", "enospc", "edquot")) options.getLong("limitBytes") else MAX_BYTES.toLong()
                        if ((fault != "silent-truncate" && offset >= threshold) || current.getLong("acceptedBytes") + size > 16L * MAX_BYTES) {
                            val errno = if (fault == "edquot") OsConstants.EDQUOT else OsConstants.ENOSPC
                            event(id, current, "writeFault", JSONObject().put("errno", errno)); throw ErrnoException("bounded synthetic quota", errno)
                        }
                        val count = minOf(size.toLong(), (threshold - offset).coerceAtLeast(0)).toInt()
                        if (count > 0) RandomAccessFile(payload(id), "rw").use { it.seek(offset); it.write(data, 0, count); it.fd.sync() }
                        val accepted = if (fault == "silent-truncate") size else count
                        current.put("writeCalls", current.getInt("writeCalls") + 1)
                            .put("acceptedBytes", current.getLong("acceptedBytes") + accepted)
                            .put("storedWriteBytes", current.getLong("storedWriteBytes") + count)
                        event(id, current, "write", JSONObject().put("offset", offset).put("acceptedBytes", accepted).put("storedBytes", count))
                        val delay = options.getLong("delayMillis"); if (delay > 0) Thread.sleep(delay)
                        accepted
                    }
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = synchronized(lock) {
                        signal?.throwIfCanceled()
                        val current = read(id)
                        if (current.getBoolean("revoked")) {
                            event(id, current, "descriptorPermissionDenied"); throw ErrnoException("synthetic revoked descriptor", OsConstants.EACCES)
                        }
                        require(offset >= 0 && size in 0..data.size)
                        val count = RandomAccessFile(payload(id), "r").use { it.seek(offset); it.read(data, 0, size).coerceAtLeast(0) }
                        event(id, current, "read", JSONObject().put("offset", offset).put("bytes", count)); count
                    }
                    override fun onFsync() = synchronized(lock) {
                        RandomAccessFile(payload(id), "rw").use { it.fd.sync() }; event(id, read(id), "fsync")
                    }
                    override fun onRelease() = synchronized(lock) {
                        val remaining = (handles[id] ?: 1) - 1
                        if (remaining <= 0) handles.remove(id) else handles[id] = remaining
                        event(id, read(id), "close")
                    }
                }, Handler(thread.looper))
        } catch (error: Exception) {
            val remaining = (handles[id] ?: 1) - 1
            if (remaining <= 0) handles.remove(id) else handles[id] = remaining
            throw error
        }
    }
}
