package com.realityforge.scanner.qa

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The single shared test-only SAF fault provider reserved for Agent 088. All data is generated
 * fixture data in this test APK's cache. Production transfers use normal URI grants, never shell
 * permissions. A DUMP-protected test receiver creates/inspects fixtures as the provider owner;
 * its narrow setup grant is dropped before the application transfer starts.
 *
 * ProxyFileDescriptorCallback exercises actual kernel/provider I/O: SHORT_WRITES reports short
 * write counts; DISK_FULL throws ENOSPC. SILENT_TRUNCATION separately models a provider that
 * acknowledges bytes it failed to retain, which only destination read-back can detect.
 */
class SharedFaultDocumentProvider : DocumentsProvider() {
    enum class Mode { EXACT, SHORT_WRITES, SILENT_TRUNCATION, DISK_FULL, REVOKED, REVOKE_AFTER_WRITE, UNAVAILABLE }

    private data class Fixture(val id: String, val file: File, val mode: Mode, val quota: Long,
        var writeCalls: Long = 0, var shortWriteCalls: Long = 0, var deleted: Boolean = false)

    private val fixtures = ConcurrentHashMap<String, Fixture>()
    private lateinit var root: File
    private lateinit var ioThread: HandlerThread
    private lateinit var ioHandler: Handler

    override fun onCreate(): Boolean {
        root = File(requireNotNull(context).cacheDir, "shared-saf-faults").also { check(it.isDirectory || it.mkdir()) }
        ioThread = HandlerThread("shared-saf-proxy").apply { start() }
        ioHandler = Handler(ioThread.looper)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.newRow().apply {
            addIfPresent(cursor, Root.COLUMN_ROOT_ID, ROOT_ID)
            addIfPresent(cursor, Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            addIfPresent(cursor, Root.COLUMN_TITLE, "Synthetic SAF fault fixtures")
            addIfPresent(cursor, Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
            addIfPresent(cursor, Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
            addIfPresent(cursor, Root.COLUMN_MIME_TYPES, "application/octet-stream")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { includeDocument(this, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        require(parentDocumentId == ROOT_ID)
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            fixtures.values.filter { !it.deleted }.sortedBy { it.id }.take(MAX_FIXTURES).forEach { includeDocument(this, it.id) }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == ROOT_ID && displayName.length in 1..128 && mimeType == "application/octet-stream")
        return createFixture(Mode.EXACT, ByteArray(0), DEFAULT_QUOTA).id
    }

    override fun deleteDocument(documentId: String) {
        val fixture = fixtures[documentId] ?: throw FileNotFoundException("Unknown generated fixture")
        synchronized(fixture) {
            if (fixture.file.exists() && !fixture.file.delete()) throw FileNotFoundException("Fixture cleanup failed")
            fixture.deleted = true
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val fixture = existing(documentId)
        if (fixture.mode == Mode.UNAVAILABLE) throw FileNotFoundException("Generated unavailable document")
        if (fixture.mode == Mode.REVOKED) throw SecurityException("Generated revoked document")
        if (mode == "r") return ParcelFileDescriptor.open(fixture.file, ParcelFileDescriptor.MODE_READ_ONLY)
        require(mode == "w" || mode == "wt") { "Fixture only supports streaming read/write" }
        val output = RandomAccessFile(fixture.file, "rw")
        output.setLength(0)
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = synchronized(fixture) { output.length() }

            override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = synchronized(fixture) {
                if (fixture.deleted) throw ErrnoException("write deleted fixture", OsConstants.ENOENT)
                if (offset < 0 || size < 0 || size > data.size || offset > MAX_DOCUMENT_BYTES - size)
                    throw ErrnoException("bounded fixture write", OsConstants.EFBIG)
                fixture.writeCalls++
                if (fixture.mode == Mode.DISK_FULL && offset >= fixture.quota)
                    throw ErrnoException("generated quota exhausted", OsConstants.ENOSPC)
                val written = when (fixture.mode) {
                    Mode.SHORT_WRITES -> minOf(size, 1024).also { if (it < size) fixture.shortWriteCalls++ }
                    Mode.DISK_FULL, Mode.SILENT_TRUNCATION -> minOf(size.toLong(), (fixture.quota - offset).coerceAtLeast(0)).toInt()
                    else -> size
                }
                output.seek(offset)
                if (written > 0) output.write(data, 0, written)
                if (fixture.mode == Mode.REVOKE_AFTER_WRITE && fixture.writeCalls == 1L)
                    requireNotNull(context).revokeUriPermission(documentUri(documentId), GRANT_FLAGS)
                if (fixture.mode == Mode.SILENT_TRUNCATION) size else written
            }

            override fun onFsync() { synchronized(fixture) { output.fd.sync() } }
            override fun onRelease() { synchronized(fixture) { output.close() } }
        }
        return try {
            requireNotNull(context).getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY, callback, ioHandler)
        } catch (failure: Exception) {
            output.close()
            throw failure
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        super.call(method, arg, extras)?.let { return it }
        if (!method.startsWith(CONTROL_PREFIX)) return null
        check(Binder.getCallingUid() == Process.myUid()) { "Fixture control is available only to its test APK owner" }
        return when (method.removePrefix(CONTROL_PREFIX)) {
            "create" -> {
                val fields = requireNotNull(extras)
                val packageName = requireNotNull(fields.getString("grantPackage"))
                val owner = requireNotNull(context)
                check(owner.packageManager.checkSignatures(owner.packageName, packageName) == PackageManager.SIGNATURE_MATCH)
                val fixture = createFixture(Mode.valueOf(requireNotNull(fields.getString("mode"))),
                    fields.getByteArray("initialBytes") ?: ByteArray(0), fields.getLong("quota", DEFAULT_QUOTA))
                val uri = documentUri(fixture.id)
                owner.grantUriPermission(packageName, uri, GRANT_FLAGS)
                if (fixture.mode == Mode.REVOKED) owner.revokeUriPermission(uri, GRANT_FLAGS)
                Bundle().apply { putString("uri", uri.toString()) }
            }
            "inspect" -> {
                val fixture = fixtures[requireNotNull(arg)] ?: throw FileNotFoundException("Unknown fixture")
                synchronized(fixture) { Bundle().apply {
                    putBoolean("exists", fixture.file.isFile && !fixture.deleted)
                    putLong("bytes", if (fixture.file.isFile) fixture.file.length() else 0)
                    putLong("writeCalls", fixture.writeCalls)
                    putLong("shortWriteCalls", fixture.shortWriteCalls)
                    if (fixture.file.isFile) putString("sha256", sha256(fixture.file))
                } }
            }
            "read" -> {
                val fixture = existing(requireNotNull(arg))
                synchronized(fixture) {
                    check(fixture.file.length() <= MAX_INSPECTION_BYTES)
                    Bundle().apply { putByteArray("contents", fixture.file.readBytes()) }
                }
            }
            "cleanup" -> {
                val id = requireNotNull(arg)
                fixtures[id]?.let { deleteDocument(id); fixtures.remove(id) }
                requireNotNull(context).revokeUriPermission(documentUri(id), GRANT_FLAGS)
                Bundle()
            }
            else -> throw IllegalArgumentException("Unknown fixture control")
        }
    }

    private fun createFixture(mode: Mode, initial: ByteArray, quota: Long): Fixture {
        require(initial.size <= MAX_INSPECTION_BYTES && quota in 1..MAX_DOCUMENT_BYTES && fixtures.size < MAX_FIXTURES)
        val id = UUID.randomUUID().toString()
        val file = File(root, id)
        check(file.createNewFile())
        file.writeBytes(initial)
        return Fixture(id, file, mode, quota).also { check(fixtures.putIfAbsent(id, it) == null) }
    }

    private fun existing(id: String): Fixture = fixtures[id]?.takeIf { !it.deleted && it.file.isFile }
        ?: throw FileNotFoundException("Fixture is missing")

    private fun includeDocument(cursor: MatrixCursor, id: String) {
        val file = if (id == ROOT_ID) null else existing(id).file
        cursor.newRow().apply {
            addIfPresent(cursor, Document.COLUMN_DOCUMENT_ID, id)
            addIfPresent(cursor, Document.COLUMN_DISPLAY_NAME, if (file == null) "Synthetic SAF fixtures" else "$id.fixture")
            addIfPresent(cursor, Document.COLUMN_MIME_TYPE, if (file == null) Document.MIME_TYPE_DIR else "application/octet-stream")
            addIfPresent(cursor, Document.COLUMN_FLAGS,
                if (file == null) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE)
            addIfPresent(cursor, Document.COLUMN_SIZE, file?.length())
        }
    }

    private fun MatrixCursor.RowBuilder.addIfPresent(cursor: MatrixCursor, name: String, value: Any?) {
        if (cursor.getColumnIndex(name) >= 0) add(name, value)
    }

    /** Test fixture control runs as the owning test APK, which is allowed to issue narrow URI grants. */
    class ControlReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val token = Binder.clearCallingIdentity()
            try {
                require(intent.action == CONTROL_ACTION)
                val result = context.contentResolver.call(Uri.parse("content://$AUTHORITY"),
                    CONTROL_PREFIX + requireNotNull(intent.getStringExtra("method")), intent.getStringExtra("id"), intent.getBundleExtra("fields"))
                setResultExtras(Bundle().apply { putBoolean("ok", true); putBundle("result", result) })
            } catch (failure: Exception) {
                setResultExtras(Bundle().apply { putBoolean("ok", false); putString("error", failure.toString()) })
            } finally { Binder.restoreCallingIdentity(token) }
        }
    }

    data class Inspection(val exists: Boolean, val bytes: Long, val sha256: String?, val writeCalls: Long, val shortWriteCalls: Long)

    companion object {
        const val AUTHORITY = "com.realityforge.scanner.test.shared.fault-documents"
        private const val ROOT_ID = "fixtures"
        private const val CONTROL_PREFIX = "rf-fixture:"
        private const val CONTROL_ACTION = "com.realityforge.scanner.test.CONTROL_FAULT_DOCUMENT"
        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private const val DEFAULT_QUOTA = 12L * 1024
        private const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024
        private const val MAX_INSPECTION_BYTES = 512 * 1024
        private const val MAX_FIXTURES = 128
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
            Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
        private val controlLock = Any()
        private fun documentUri(id: String) = DocumentsContract.buildDocumentUri(AUTHORITY, id)

        fun createUri(context: Context, mode: Mode = Mode.EXACT, initialBytes: ByteArray = ByteArray(0),
            quotaBytes: Long = DEFAULT_QUOTA): Uri {
            val result = control(context, "create", fields = Bundle().apply {
                putString("mode", mode.name); putString("grantPackage", context.packageName)
                putByteArray("initialBytes", initialBytes); putLong("quota", quotaBytes)
            })
            return Uri.parse(requireNotNull(result.getString("uri")))
        }

        fun inspect(context: Context, uri: Uri): Inspection {
            val fields = control(context, "inspect", uri)
            return Inspection(fields.getBoolean("exists"), fields.getLong("bytes"), fields.getString("sha256"),
                fields.getLong("writeCalls"), fields.getLong("shortWriteCalls"))
        }

        fun read(context: Context, uri: Uri): ByteArray = requireNotNull(control(context, "read", uri).getByteArray("contents"))
        fun hash(context: Context, uri: Uri): String = requireNotNull(inspect(context, uri).sha256)
        fun cleanup(context: Context, uri: Uri) { control(context, "cleanup", uri) }

        private fun control(context: Context, method: String, uri: Uri? = null, fields: Bundle? = null): Bundle = synchronized(controlLock) {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Fixture control must run off the Android main thread" }
            uri?.let { require(it.authority == AUTHORITY) }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val completed = CountDownLatch(1)
            val answer = AtomicReference<Bundle?>()
            val intent = Intent(CONTROL_ACTION).setComponent(ComponentName(instrumentation.context.packageName,
                ControlReceiver::class.java.name)).putExtra("method", method)
                .putExtra("id", uri?.let(DocumentsContract::getDocumentId)).putExtra("fields", fields)
            // DUMP is only for sending to this test-only control receiver. The app never adopts
            // MANAGE_DOCUMENTS and the actual read/write/cleanup matrix runs after this is dropped.
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.DUMP)
            try {
                context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        answer.set(getResultExtras(false)); completed.countDown()
                    }
                }, Handler(Looper.getMainLooper()), 0, null, null)
                check(completed.await(15, TimeUnit.SECONDS)) { "Timed out preparing the shared document fixture" }
                val result = requireNotNull(answer.get()) { "Shared fixture receiver returned no result" }
                check(result.getBoolean("ok")) { result.getString("error") ?: "Shared fixture control failed" }
                result.getBundle("result") ?: Bundle()
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
