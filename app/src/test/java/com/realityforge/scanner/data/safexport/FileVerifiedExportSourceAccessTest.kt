package com.realityforge.scanner.data.safexport

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.FileInputStream
import java.nio.file.Files
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FileVerifiedExportSourceAccessTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runNext() { check(work.isNotEmpty()); work.removeFirst().run() }
        fun drain() { while (work.isNotEmpty()) runNext() }
    }

    private class TrackedStream : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        var closes = 0
        override fun close() { closes++; super.close() }
    }

    @Test fun cancellationDuringReturnHandoffClosesUndeliveredStream() {
        val caller = QueuedDispatcher()
        val worker = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val stream = TrackedStream()
        var delivered = false
        try {
            val job = scope.launch {
                openOwned(worker) { stream }.use { delivered = true }
            }
            caller.runNext() // Enter openOwned and suspend at IO dispatch.
            worker.runNext() // Open succeeded; return to caller is still queued.
            assertEquals(0, stream.closes)
            job.cancel()
            caller.drain()
            assertTrue(job.isCancelled)
            assertFalse(delivered)
            assertEquals(1, stream.closes)
        } finally { scope.cancel(); worker.drain(); caller.drain() }
    }

    @Test fun cancellationInsideOpenClosesNewStream() {
        val caller = QueuedDispatcher()
        val worker = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val stream = TrackedStream()
        var delivered = false
        lateinit var job: Job
        try {
            job = scope.launch {
                openOwned(worker) { job.cancel(); stream }.use { delivered = true }
            }
            caller.runNext(); worker.runNext(); caller.drain()
            assertTrue(job.isCancelled)
            assertFalse(delivered)
            assertEquals(1, stream.closes)
        } finally { scope.cancel(); worker.drain(); caller.drain() }
    }

    @Test fun successfulHandoffTransfersOwnershipToCaller() {
        val caller = QueuedDispatcher()
        val worker = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val stream = TrackedStream()
        var delivered = false
        try {
            val job = scope.launch {
                openOwned(worker) { stream }.use {
                    assertSame(stream, it)
                    assertEquals(0, stream.closes)
                    assertEquals(1, it.read())
                    delivered = true
                }
            }
            caller.runNext(); worker.runNext(); caller.drain()
            assertTrue(job.isCompleted && !job.isCancelled)
            assertTrue(delivered)
            assertEquals(1, stream.closes)
        } finally { scope.cancel(); worker.drain(); caller.drain() }
    }

    @Test fun cancelledBeforeDispatchDoesNotOpenStream() {
        val caller = QueuedDispatcher()
        val worker = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + caller)
        var opens = 0
        try {
            val job = scope.launch { openOwned(worker) { opens++; TrackedStream() }.close() }
            caller.runNext(); job.cancel(); worker.runNext(); caller.drain()
            assertTrue(job.isCancelled)
            assertEquals(0, opens)
        } finally { scope.cancel(); worker.drain(); caller.drain() }
    }

    @Test fun failedOpenPropagatesWithoutSuccessfulHandoff() {
        val caller = QueuedDispatcher()
        val worker = QueuedDispatcher()
        val expected = IOException("source unavailable")
        var failure: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + caller + CoroutineExceptionHandler { _, e -> failure = e })
        try {
            val job = scope.launch { openOwned(worker) { throw expected }.close() }
            caller.runNext(); worker.runNext(); caller.drain()
            assertTrue(job.isCancelled)
            assertSame(expected, failure)
        } finally { scope.cancel(); worker.drain(); caller.drain() }
    }

    @Test fun cancellationKeepsPrimaryFailureWhenCloseAlsoThrows() {
        val caller = QueuedDispatcher()
        val worker = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val closeFailure = IOException("close failed")
        var closes = 0
        var cancellation: CancellationException? = null
        val stream = object : InputStream() {
            override fun read() = -1
            override fun close() { closes++; throw closeFailure }
        }
        try {
            val job = scope.launch {
                try { openOwned(worker) { stream }.close() }
                catch (failure: CancellationException) { cancellation = failure; throw failure }
            }
            caller.runNext(); worker.runNext(); job.cancel(); caller.drain()
            assertTrue(job.isCancelled)
            assertEquals(1, closes)
            assertNotNull(cancellation)
            assertTrue(cancellation!!.suppressed.contains(closeFailure))
        } finally { scope.cancel(); worker.drain(); caller.drain() }
    }

    @Test fun publicOpenTransfersRealDescriptorAndPreservesIdentityChecks() = runBlocking {
        val path = Files.createTempFile("export-source-owner-", ".bin")
        try {
            Files.write(path, byteArrayOf(4, 5, 6))
            val identity = ExportSourceIdentity("project", 1, "mesh", "surface")
            val source = VerifiedExportSource(identity, 3, "0".repeat(64), "scan.ply", "application/octet-stream")
            val access = FileVerifiedExportSourceAccess(ExportSourceFileResolver { ResolvedExportFile(identity, path.toFile()) })
            val stream = access.open(source) as FileInputStream
            assertTrue(stream.fd.valid())
            stream.use { assertEquals(4, it.read()) }
            assertFalse(stream.fd.valid())
            val changed = FileVerifiedExportSourceAccess(ExportSourceFileResolver {
                ResolvedExportFile(identity.copy(revision = 2), path.toFile())
            })
            try { changed.open(source).use { fail("changed identity must refuse") }; fail("missing rejection") }
            catch (_: SourceChangedException) { }
        } finally { Files.deleteIfExists(path) }
    }

}
