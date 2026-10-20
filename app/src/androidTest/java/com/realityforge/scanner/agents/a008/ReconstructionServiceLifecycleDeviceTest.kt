package com.realityforge.scanner.agents.a008

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.data.jobs.JobEventKind
import com.realityforge.scanner.data.jobs.JobSnapshot
import com.realityforge.scanner.data.jobs.ReconstructionJobState
import com.realityforge.scanner.data.publication.PublicationReadResult
import com.realityforge.scanner.engine.diagnostics.ScanStage
import com.realityforge.scanner.work.execution.ReconstructionService
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Activity, Android service, Room, JNI and synthetic photographic fixtures. No service or
 * pipeline is replaced by a fake. These tests do not establish physical capture or timeout quotas.
 */
@RunWith(AndroidJUnit4::class)
class ReconstructionServiceLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RealityForgeApplication
    private val execution get() = app.compositionRoot.execution

    @Test
    fun rotationBackgroundAndRepeatedStartKeepOneAttemptAndPreserveOriginals() = runBlocking {
        withFixture { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val started = startRealPipeline(scenario, fixture)
                repeat(4) { startService(ReconstructionService.ACTION_START) }
                // A malformed command delivered to the real service must not stop its live worker.
                startService("com.realityforge.scanner.INVALID_TEST_COMMAND")
                scenario.recreate()
                scenario.moveToState(Lifecycle.State.CREATED)
                delay(250)
                val afterBackground = requireNotNull(execution.jobs.snapshot(started.jobId))
                assertEquals(started.input, afterBackground.input)
                assertEquals(started.attempt, afterBackground.attempt)
                assertFalse("Activity background must not become user cancellation",
                    afterBackground.state == ReconstructionJobState.CANCELLED)
                scenario.moveToState(Lifecycle.State.RESUMED)

                val finished = awaitSettled(started)
                execution.awaitWorkerStopped()
                awaitServiceStopped()
                val history = execution.jobs.observeHistory(started.jobId).first()
                assertEquals(1, history.count { it.kind == JobEventKind.ACQUIRED })
                assertFalse("Rotation/onStop must not impersonate a Pause action", history.any {
                    it.code == "PAUSED_BY_USER" || it.code == "PROCESSING_INTERRUPTED"
                })
                assertEquals(started.attempt, finished.attempt)
                assertOriginals(fixture)
                assertPublicationState(fixture, finished)
            }
        }
    }

    @Test
    fun actualTaskRemovalStopsServiceAndFencesInterruptedWork() = runBlocking {
        withFixture { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val started = startRealPipeline(scenario, fixture)
                // This removes an actual Android task and exercises Service.onTaskRemoved, not a
                // direct call to that method on an unattached Service object.
                scenario.onActivity { it.finishAndRemoveTask() }
                val settled = awaitSettled(started)
                withTimeout(45_000) { execution.awaitWorkerStopped() }
                awaitServiceStopped()
                assertEquals(started.attempt, settled.attempt)
                assertTrue("Task removal must pause the attempt or observe its already committed result: $settled",
                    settled.state in setOf(ReconstructionJobState.PAUSED, ReconstructionJobState.COMPLETED,
                        ReconstructionJobState.FAILED))
                if (settled.state == ReconstructionJobState.PAUSED) {
                    assertEquals("PROCESSING_INTERRUPTED", settled.outcomeCode)
                    assertNull(settled.lease)
                }
                assertOriginals(fixture)
                assertPublicationState(fixture, settled)
            }
        }
    }

    @Test
    fun repeatedEmptyAndMalformedStartsDrainTheActualForegroundService() = runBlocking {
        execution.awaitRecovery()
        withTimeout(45_000) { execution.awaitWorkerStopped() }
        assertFalse("This fixture requires no unrelated queued work", execution.jobs.observeQueue().first()
            .any { it.state == ReconstructionJobState.QUEUED || it.state == ReconstructionJobState.RUNNING })
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(8) { startService(ReconstructionService.ACTION_START) }
            startService("com.realityforge.scanner.INVALID_TEST_COMMAND")
            instrumentation.waitForIdleSync()
            // Let framework start messages run before accepting absence as a completed drain.
            delay(250)
            awaitServiceStopped()
            assertFalse(execution.jobs.observeQueue().first().any { it.state == ReconstructionJobState.RUNNING })
        }
    }

    private suspend fun startRealPipeline(scenario: ActivityScenario<MainActivity>, fixture: Fixture): JobSnapshot {
        val holder = AtomicReference<ScannerViewModel>()
        scenario.onActivity { holder.set(ViewModelProvider(it)[ScannerViewModel::class.java]) }
        val model = requireNotNull(holder.get())
        awaitCondition(30_000) { model.projects.value.any { it.id == fixture.projectId } }
        scenario.onActivity { model.select(fixture.projectId); model.reconstruct(32.88) }
        val started = withTimeout(45_000) {
            execution.current.filterNotNull().first {
                it.input.projectId == fixture.projectId && it.state == ReconstructionJobState.RUNNING
            }
        }
        // Require measured progress from the real pipeline. Merely queuing a job cannot pass.
        withTimeout(45_000) {
            execution.diagnosticProgress.filterNotNull().first {
                it.scope.jobId == started.jobId.value && it.scope.attempt.toLong() == started.attempt &&
                    it.stage in setOf(ScanStage.FEATURE_EXTRACTION, ScanStage.MATCHING,
                        ScanStage.CAMERA_REGISTRATION, ScanStage.SPARSE_GEOMETRY, ScanStage.DENSE_DEPTH)
            }
        }
        return started
    }

    private suspend fun awaitSettled(started: JobSnapshot): JobSnapshot = withTimeout(180_000) {
        execution.jobs.observe(started.jobId).filterNotNull().first {
            it.state == ReconstructionJobState.PAUSED || it.state.isTerminal
        }
    }

    private fun startService(action: String) {
        ContextCompat.startForegroundService(app, Intent(app, ReconstructionService::class.java).setAction(action))
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitServiceStopped() = awaitCondition(30_000) {
        // Since API 26 this returns only the caller's services, which is exactly the desired scope.
        app.getSystemService(ActivityManager::class.java).getRunningServices(32)
            .none { it.service.className == ReconstructionService::class.java.name }
    }

    private suspend fun awaitCondition(timeoutMillis: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) delay(20)
        check(predicate()) { "The real platform lifecycle did not reach the expected state in $timeoutMillis ms." }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        execution.awaitRecovery()
        withTimeout(45_000) { execution.awaitWorkerStopped() }
        val project = app.repository.create("SYNTHETIC service lifecycle ${UUID.randomUUID()}")
        val temporary = mutableListOf<File>()
        try {
            for (index in 1..3) {
                val photo = File.createTempFile("a008-$index-", ".jpg", app.cacheDir).also(temporary::add)
                instrumentation.context.assets.open("relief/view-0$index.jpg").use { source ->
                    photo.outputStream().use { source.copyTo(it) }
                }
                assertTrue(app.repository.importPhoto(project, Uri.fromFile(photo)))
            }
            val originals = app.repository.dao.photos(project).associate { it.id to hash(app.repository.photoFile(it)) }
            val current = requireNotNull(app.repository.dao.project(project))
            block(Fixture(project, current.revision, current.resultKey, originals))
        } finally {
            val active = execution.current.value?.takeIf { it.input.projectId == project }
            if (active != null && !active.state.isTerminal) execution.cancel(active.jobId)
            withTimeout(45_000) { execution.awaitWorkerStopped() }
            temporary.forEach { it.delete() }
            app.repository.delete(project)
        }
    }

    private suspend fun assertOriginals(fixture: Fixture) {
        val actual = app.repository.dao.photos(fixture.projectId)
            .associate { it.id to hash(app.repository.photoFile(it)) }
        assertEquals(fixture.originals, actual)
        assertEquals(fixture.revision, requireNotNull(app.repository.dao.project(fixture.projectId)).revision)
    }

    private suspend fun assertPublicationState(fixture: Fixture, snapshot: JobSnapshot) {
        val project = requireNotNull(app.repository.dao.project(fixture.projectId))
        if (snapshot.state == ReconstructionJobState.COMPLETED) {
            assertTrue(requireNotNull(app.repository.publications).committed(fixture.projectId,
                requireNotNull(snapshot.resultArtifactIdentity)) is PublicationReadResult.Verified)
            assertEquals(project.resultKey, requireNotNull(app.repository.currentPreview(fixture.projectId)).getString("artifactKey"))
        } else {
            assertEquals("An interrupted or failed scan must retain the prior result", fixture.previousResult, project.resultKey)
        }
    }

    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private data class Fixture(val projectId: String, val revision: Long, val previousResult: String?,
        val originals: Map<String, String>)
}
