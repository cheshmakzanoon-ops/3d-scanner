package com.realityforge.scanner.agents.a007

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.jobs.IsolatedJobStoreDatabase
import com.realityforge.scanner.data.jobs.JobId
import com.realityforge.scanner.data.jobs.JobIdFactory
import com.realityforge.scanner.data.jobs.JobInputSnapshot
import com.realityforge.scanner.data.jobs.JobLeaseRejectedException
import com.realityforge.scanner.data.jobs.JobTokenFactory
import com.realityforge.scanner.data.jobs.RoomJobStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.fail

abstract class DurableJobStoreDeviceSupport {
    protected val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    protected fun input(projectId: String) = JobInputSnapshot(
        projectId = projectId,
        projectRevision = 1,
        requestId = "request-$projectId",
        inputSha256 = "a".repeat(64),
        calibrationSha256 = "b".repeat(64),
        settingsSha256 = "c".repeat(64),
        engineSha256 = "d".repeat(64),
        engineVersion = "0.19.0-m19",
    )

    protected fun store(
        database: IsolatedJobStoreDatabase,
        prefix: String,
        historyLimit: Int = 64,
    ): RoomJobStore {
        val jobCounter = AtomicInteger()
        val tokenCounter = AtomicInteger()
        return RoomJobStore(
            database = database,
            dao = database.jobDao(),
            jobIdFactory = JobIdFactory { JobId("$prefix-job-${jobCounter.incrementAndGet()}") },
            tokenFactory = JobTokenFactory { "$prefix-token-${tokenCounter.incrementAndGet()}" },
            historyLimit = historyLimit,
        )
    }

    protected fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    protected fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    protected suspend fun expectLeaseRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected stale lease rejection")
        } catch (_: JobLeaseRejectedException) {
            // Expected typed fencing failure.
        }
    }
}
