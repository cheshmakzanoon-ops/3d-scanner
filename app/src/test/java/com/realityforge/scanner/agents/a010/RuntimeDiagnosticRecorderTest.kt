package com.realityforge.scanner.agents.a010

import com.realityforge.scanner.data.operations.ProjectAccessException
import com.realityforge.scanner.data.operations.StaleProjectRevisionException
import com.realityforge.scanner.data.storagebudget.InsufficientStorageException
import com.realityforge.scanner.data.storagebudget.StorageAccounting
import com.realityforge.scanner.engine.diagnostics.*
import com.realityforge.scanner.engine.nativebridge.NativeBoundaryFailure
import com.realityforge.scanner.engine.nativebridge.NativeResourceExhaustedException
import com.realityforge.scanner.processing.resources.ResourceAction
import com.realityforge.scanner.processing.resources.ResourceCheckpointPause
import com.realityforge.scanner.processing.resources.ResourcePolicyDecision
import com.realityforge.scanner.processing.resources.WorkloadPlan
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticRecorderTest {
    private val scope = DiagnosticScope("job-runtime-a010", 2, 7)

    @Test fun measuredCountersDoNotInventPublicationOrGlobalProgress() {
        val recorder = RuntimeDiagnosticRecorder(scope)
        recorder.progress(ScanStage.FEATURE_EXTRACTION, 0, 3, ProgressUnit.PHOTOS)
        recorder.progress(ScanStage.FEATURE_EXTRACTION, 2, 3, ProgressUnit.PHOTOS)
        recorder.progress(ScanStage.FEATURE_EXTRACTION, 3, 3, ProgressUnit.PHOTOS)
        assertTrue(recorder.snapshot().events.isEmpty())
        recorder.outcome(ScanStage.FEATURE_EXTRACTION, StageOutcome.SUCCEEDED)
        recorder.progress(ScanStage.DENSE_DEPTH, 0, null, ProgressUnit.STEPS)
        val snapshot = recorder.snapshot()
        assertEquals(listOf(0L, 1L, 2L, 4L), snapshot.progress.map { it.sequence })
        assertEquals(listOf(3L), snapshot.events.map { it.sequence })
        assertTrue(snapshot.progress.all { it.scope == scope })
        assertEquals(3L, recorder.latest(ScanStage.FEATURE_EXTRACTION)!!.completed)
        assertNull(recorder.latest(ScanStage.DENSE_DEPTH)!!.stageFractionOrNull())
        assertEquals(ScanStage.FEATURE_EXTRACTION, snapshot.events.single().stage)
    }

    @Test fun rejectedCounterOrUnitChangesCannotMutateAcceptedHistory() {
        val recorder = RuntimeDiagnosticRecorder(scope)
        recorder.progress(ScanStage.MATCHING, 3, 8, ProgressUnit.PAIRS)
        assertThrows(IllegalArgumentException::class.java) {
            recorder.progress(ScanStage.MATCHING, 2, 8, ProgressUnit.PAIRS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recorder.progress(ScanStage.MATCHING, 4, 9, ProgressUnit.PAIRS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recorder.progress(ScanStage.MATCHING, 4, 8, ProgressUnit.ITEMS)
        }
        val next = recorder.progress(ScanStage.MATCHING, 4, 8, ProgressUnit.PAIRS)
        assertEquals(1L, next.sequence)
        assertEquals(listOf(3L, 4L), recorder.snapshot().progress.map { it.completed })
    }

    @Test fun unknownTotalsAndReusedCheckpointsRetainTheirActualMeaning() {
        val recorder = RuntimeDiagnosticRecorder(scope)
        recorder.progress(ScanStage.DENSE_DEPTH, 0, null, ProgressUnit.PIXELS)
        recorder.progress(ScanStage.DENSE_DEPTH, 100, null, ProgressUnit.PIXELS)
        recorder.progress(ScanStage.DENSE_DEPTH, 150, 200, ProgressUnit.PIXELS)
        recorder.progress(ScanStage.DENSE_DEPTH, 200, 200, ProgressUnit.PIXELS)
        recorder.progress(ScanStage.SURFACE, 0, null, ProgressUnit.STEPS, reusedCheckpoint = true)
        recorder.outcome(ScanStage.SURFACE, StageOutcome.SUCCEEDED)
        val reused = recorder.latest(ScanStage.SURFACE)!!
        assertTrue(reused.reusedCheckpoint)
        assertEquals(0L, reused.completed)
        assertNull(reused.total)
        assertNull(reused.stageFractionOrNull())
        assertEquals(.75, recorder.snapshot().progress[2].stageFractionOrNull()!!, 0.0)
    }

    @Test fun aNewAttemptCanResetCountersButRetiredCallbacksCannotReactivateTheOldOne() {
        val old = RuntimeDiagnosticRecorder(scope)
        old.progress(ScanStage.MATCHING, 7, 8, ProgressUnit.PAIRS)
        old.close()
        val retry = RuntimeDiagnosticRecorder(scope.copy(attempt = 3))
        retry.progress(ScanStage.MATCHING, 0, 8, ProgressUnit.PAIRS)
        assertThrows(IllegalStateException::class.java) {
            old.progress(ScanStage.MATCHING, 8, 8, ProgressUnit.PAIRS)
        }
        assertThrows(IllegalStateException::class.java) {
            old.outcome(ScanStage.MATCHING, StageOutcome.SUCCEEDED)
        }
        assertEquals(7L, old.snapshot().progress.single().completed)
        assertEquals(0L, retry.snapshot().progress.single().completed)
        assertNotEquals(old.snapshot().scope, retry.snapshot().scope)
    }

    @Test fun snapshotsAreImmutableAndCombinedEventRetentionIsBounded() {
        val recorder = RuntimeDiagnosticRecorder(scope, maxEvents = 4)
        recorder.progress(ScanStage.EXPORT, 0, 8, ProgressUnit.ITEMS)
        recorder.outcome(ScanStage.TEXTURE, StageOutcome.NOT_RUN)
        val retained = recorder.snapshot()
        assertThrows(UnsupportedOperationException::class.java) {
            (retained.progress as MutableList<StageProgress>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (retained.events as MutableList<DiagnosticEvent>).clear()
        }
        (1L..8L).forEach { recorder.progress(ScanStage.EXPORT, it, 8, ProgressUnit.ITEMS) }
        val latest = recorder.snapshot()
        assertEquals(4, latest.progress.size + latest.events.size)
        assertEquals(listOf(6L, 7L, 8L, 9L), latest.progress.map { it.sequence })
        assertEquals(1, retained.progress.size)
        assertEquals(1, retained.events.size)
    }

    @Test fun boundedHistoryDoesNotEraseTheRetainedFailureReason() {
        val recorder = RuntimeDiagnosticRecorder(scope, maxEvents = 2)
        val failure = recorder.recordFailure(ScanStage.DENSE_DEPTH, NativeResourceExhaustedException())
        assertEquals(ScanFailureCode.RESOURCE_EXHAUSTED, failure.code)
        repeat(5) { recorder.progress(ScanStage.EXPORT, it.toLong(), 5, ProgressUnit.ITEMS) }
        assertTrue(recorder.snapshot().events.isEmpty())
        assertEquals(failure, recorder.snapshot().failures.single())
        assertThrows(UnsupportedOperationException::class.java) {
            (recorder.snapshot().failures as MutableList<ScanFailure>).clear()
        }
    }

    @Test fun concurrentStageFactsHaveOneStrictlyOrderedBoundedSequence() {
        val recorder = RuntimeDiagnosticRecorder(scope)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val stages = listOf(ScanStage.FEATURE_EXTRACTION, ScanStage.MATCHING,
                ScanStage.CALIBRATION, ScanStage.EXPORT)
            val futures = stages.map { stage -> pool.submit(Callable {
                check(start.await(5, TimeUnit.SECONDS))
                for (count in 0L..20L) recorder.progress(stage, count, 20, ProgressUnit.ITEMS)
            }) }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            val snapshot = recorder.snapshot()
            assertEquals(64, snapshot.progress.size)
            assertEquals((20L..83L).toList(), snapshot.progress.map { it.sequence })
            stages.forEach { assertEquals(20L, recorder.latest(it)!!.completed) }
        } finally { pool.shutdownNow() }
    }

    @Test fun operationalFailuresUseKnownTypesAndNeverExceptionMessageParsing() {
        val memory = OperationalFailureMapper.native(ScanStage.DENSE_DEPTH, NativeBoundaryFailure.RESOURCE_EXHAUSTED)
        val cancelled = OperationalFailureMapper.native(ScanStage.DENSE_DEPTH, NativeBoundaryFailure.CANCELLED)
        val paused = OperationalFailureMapper.from(ScanStage.DENSE_DEPTH, ResourceCheckpointPause(
            ResourcePolicyDecision(ResourceAction.PAUSE_AT_CHECKPOINT, WorkloadPlan(depthEdge = 256,
                estimatedManagedBytes = 1, estimatedNativeBytes = 1, estimatedTransientStorageBytes = 1),
                setOf("thermal"), 20)))
        assertEquals(ScanFailureCode.RESOURCE_EXHAUSTED, memory.code)
        assertEquals(StageOutcome.CANCELLED, cancelled.outcome)
        assertEquals(ScanFailureCode.RESOURCE_PAUSED, paused.code)
        assertEquals(StageOutcome.PAUSED, paused.outcome)
        assertEquals(RecoveryAction.RESUME_JOB, paused.action)
        assertEquals(ScanFailureCode.REVISION_MISMATCH, OperationalFailureMapper.from(ScanStage.EXPORT,
            StaleProjectRevisionException("project-private", 7, 8)).code)
        assertEquals(ScanFailureCode.PROJECT_ACCESS_BLOCKED, OperationalFailureMapper.from(ScanStage.EXPORT,
            ProjectAccessException("/data/user/0/private/recovery.json")).code)
        assertEquals(ScanFailureCode.IO_FAILURE, OperationalFailureMapper.from(ScanStage.EXPORT,
            IOException("CANCELLED RESOURCE_EXHAUSTED /data/user/0/private/photo.jpg")).code)
        assertEquals(ScanFailureCode.STORAGE_PERMISSION_REVOKED, OperationalFailureMapper.from(ScanStage.EXPORT,
            SecurityException("content://private/photo")).code)
    }

    @Test fun unknownStorageMeasurementIsNotReportedAsZeroAvailableBytesOrFullStorage() {
        val decision = StorageAccounting.decide(500, null, emptyList(), safetyBytes = 0)
        val failure = OperationalFailureMapper.from(ScanStage.EXPORT, InsufficientStorageException(decision))
        assertEquals(ScanFailureCode.STORAGE_MEASUREMENT_UNAVAILABLE, failure.code)
        assertEquals(StageOutcome.PAUSED, failure.outcome)
        assertEquals(DiagnosticValue.Count(500), failure.context.values[DiagnosticContextKey.REQUIRED_BYTES])
        assertFalse(failure.context.values.containsKey(DiagnosticContextKey.AVAILABLE_BYTES))
        val measured = OperationalFailureMapper.from(ScanStage.EXPORT, InsufficientStorageException(
            StorageAccounting.decide(500, 200, emptyList(), safetyBytes = 0)))
        assertEquals(ScanFailureCode.STORAGE_FULL, measured.code)
        assertEquals(DiagnosticValue.Count(200), measured.context.values[DiagnosticContextKey.AVAILABLE_BYTES])
    }

    @Test fun anOwnerTypedFailureKeepsItsStageContextAndCannotBecomeSuccessful() {
        val failure = ScanFailure(ScanFailureCode.TEXTURE_UNAVAILABLE, ScanStage.TEXTURE, StageOutcome.FAILED,
            RecoveryAction.RECAPTURE_TEXTURE, "scan.failure.no_supported_faces",
            DiagnosticContext.of(DiagnosticContextKey.OBSERVATION_COUNT to DiagnosticValue.Count(0)))
        val recorder = RuntimeDiagnosticRecorder(scope)
        assertEquals(failure, recorder.recordFailure(ScanStage.DENSE_DEPTH, DiagnosticOperationException(failure)))
        assertEquals(ScanStage.TEXTURE, recorder.snapshot().events.single().stage)
        assertThrows(IllegalArgumentException::class.java) {
            recorder.outcome(ScanStage.TEXTURE, StageOutcome.SUCCEEDED, failure)
        }
        assertEquals(1, recorder.snapshot().events.size)
        listOf("/data/user/0/private.jpg", "../private.jpg", "content://private/photo", "file:/tmp/image").forEach {
            assertThrows(IllegalArgumentException::class.java) { DiagnosticValue.Text(it) }
        }
    }
}
