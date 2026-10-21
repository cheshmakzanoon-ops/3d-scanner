package com.realityforge.scanner.agents.a027

import com.realityforge.scanner.capture.calibration.CalibrationCoverageEvidence
import com.realityforge.scanner.capture.calibration.CalibrationOperationKey
import com.realityforge.scanner.capture.calibration.CalibrationProfileCodec
import com.realityforge.scanner.capture.calibration.CalibrationProfilePublication
import com.realityforge.scanner.capture.calibration.CalibrationProfilePublicationPort
import com.realityforge.scanner.capture.calibration.CalibrationProfileStoreResult
import com.realityforge.scanner.capture.calibration.CalibrationPublicationRequest
import com.realityforge.scanner.capture.calibration.TransactionalProfileStore
import com.realityforge.scanner.engine.LensProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionalProfileStoreTest {
    @Test
    fun saveStagesCanonicalBytesAndAcceptsOnlyMatchingCommitIdentity() = runBlocking {
        var staged: CalibrationPublicationRequest? = null
        val record = CalibrationProfileCodec.aggregateOnly(profile)
        val store = TransactionalProfileStore(CalibrationProfilePublicationPort { request ->
            staged = request
            object : CalibrationProfilePublication {
                override suspend fun commit() = CalibrationProfileStoreResult.Saved(record.profileId, 8)
                override suspend fun abort() = Unit
            }
        })

        val result = store.save(operation, record)

        assertEquals(CalibrationProfileStoreResult.Saved(record.profileId, 8), result)
        assertEquals(record.profileId, staged?.profileId)
        assertArrayEquals(
            CalibrationProfileCodec.encode(record).toByteArray(),
            requireNotNull(staged?.encodedProfile),
        )
    }

    @Test
    fun staleRevisionIsReturnedWithoutFalseSuccess() = runBlocking {
        val record = CalibrationProfileCodec.aggregateOnly(profile)
        val store = TransactionalProfileStore(CalibrationProfilePublicationPort {
            object : CalibrationProfilePublication {
                override suspend fun commit() = CalibrationProfileStoreResult.Stale(7, 9)
                override suspend fun abort() = Unit
            }
        })

        assertEquals(CalibrationProfileStoreResult.Stale(7, 9), store.save(operation, record))
    }

    @Test
    fun cancellationAfterStagingAlwaysAborts() = runBlocking {
        val record = CalibrationProfileCodec.aggregateOnly(profile)
        val commitEntered = CompletableDeferred<Unit>()
        var aborted = false
        val store = TransactionalProfileStore(CalibrationProfilePublicationPort {
            object : CalibrationProfilePublication {
                override suspend fun commit(): CalibrationProfileStoreResult {
                    commitEntered.complete(Unit)
                    awaitCancellation()
                }

                override suspend fun abort() {
                    aborted = true
                }
            }
        })
        val job = launch { store.save(operation, record) }
        commitEntered.await()

        job.cancelAndJoin()

        assertTrue(aborted)
    }

    @Test
    fun removalUsesSameRevisionBoundPublicationProtocol() = runBlocking {
        var staged: CalibrationPublicationRequest? = null
        val store = TransactionalProfileStore(CalibrationProfilePublicationPort { request ->
            staged = request
            object : CalibrationProfilePublication {
                override suspend fun commit() = CalibrationProfileStoreResult.Removed(8)
                override suspend fun abort() = Unit
            }
        })

        assertEquals(CalibrationProfileStoreResult.Removed(8), store.remove(operation))
        assertEquals(null, staged?.encodedProfile)
        assertEquals(null, staged?.profileId)
    }

    private companion object {
        val operation = CalibrationOperationKey(
            projectId = "00000000-0000-0000-0000-000000000027",
            expectedRevision = 7,
            operationId = "calibration-save-27",
        )
        val profile = LensProfile(
            1600,
            1200,
            1350.0,
            1348.0,
            799.5,
            599.5,
            -0.08,
            0.02,
            0.001,
            -0.001,
            0.0,
            0.42,
            12,
            26.0,
        )
    }
}
