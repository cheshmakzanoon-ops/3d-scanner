package com.realityforge.scanner.qa

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.qa.SharedFaultDocumentProvider.Mode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Instrumentation-process client only; no manifest component may depend on this Kotlin helper. */
object SharedFaultDocumentFixtures {
    data class Inspection(val exists: Boolean, val bytes: Long, val sha256: String?, val writeCalls: Long, val shortWriteCalls: Long)

    const val AUTHORITY = SharedFaultDocumentProvider.AUTHORITY
    private const val CONTROL_PREFIX = "rf-fixture:"
    private const val CONTROL_ACTION = "com.realityforge.scanner.test.CONTROL_FAULT_DOCUMENT"
    private const val DEFAULT_QUOTA = 12L * 1024
    private val controlLock = Any()

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
            SharedFaultDocumentProvider.ControlReceiver::class.java.name)).putExtra("method", method)
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

}
