package com.realityforge.scanner.agents.a027

import com.realityforge.scanner.capture.calibration.*
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import com.realityforge.scanner.engine.LensProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class JournaledCalibrationPublicationTest {
    private class MemoryMetadata(var revision: Long = 7) : CalibrationMutationStore {
        val rows = linkedMapOf<String, CalibrationMutationEntity>()
        override suspend fun load(operationId: String) = rows[operationId]
        override suspend fun pending(projectId: String) = rows.values.filter { it.projectId == projectId && it.committedRevision == null }
        override suspend fun prepare(record: CalibrationMutationEntity): CalibrationProfileStoreResult? {
            rows[record.operationId]?.let {
                require(it.copy(committedRevision = null) == record)
                return it.committedResult()
            }
            if (revision != record.expectedRevision) return CalibrationProfileStoreResult.Stale(record.expectedRevision, revision)
            rows[record.operationId] = record
            return null
        }
        override suspend fun commit(operationId: String): CalibrationProfileStoreResult {
            val row = requireNotNull(rows[operationId])
            row.committedResult()?.let { return it }
            if (row.expectedRevision != revision) return CalibrationProfileStoreResult.Stale(row.expectedRevision, revision)
            val committed = row.copy(committedRevision = ++revision)
            rows[operationId] = committed
            return requireNotNull(committed.committedResult())
        }
        override suspend fun discardUncommitted(operationId: String) {
            if (rows[operationId]?.committedRevision == null) rows.remove(operationId)
        }
    }

    @Test fun saveAndRemoveSwitchProfileWithExactlyOneRevisionAndIdempotentReceipt() = runBlocking {
        val root = Files.createTempDirectory("profile-publish").toFile()
        try {
            val paths = ProjectPathPolicy(root)
            val metadata = MemoryMetadata()
            val port = JournaledCalibrationProfilePublicationPort(paths, metadata)
            val store = TransactionalProfileStore(port)
            val key = CalibrationOperationKey(PROJECT, 7, "save-one")
            val profile = record(1350.0)
            val result = store.save(key, profile)
            assertEquals(CalibrationProfileStoreResult.Saved(profile.profileId, 8), result)
            assertEquals(result, store.save(key, profile))
            assertEquals(8L, metadata.revision)
            assertEquals(profile.profileId, CalibrationProfileCodec.decode(profileFile(paths).readText()).profileId)
            assertEquals(CalibrationProfileStoreResult.Removed(9), store.remove(CalibrationOperationKey(PROJECT, 8, "remove-one")))
            assertFalse(profileFile(paths).exists())
            assertTrue(port.recover(PROJECT).failures.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun allSavedFilesystemBoundariesRecoverOldOrCommittedOpticalModelForSaveAndRemove() = runBlocking {
        for (remove in listOf(false, true)) for (boundary in CalibrationMutationBoundary.entries) {
            val root = Files.createTempDirectory("profile-crash").toFile()
            val snapshot = Files.createTempDirectory("profile-disk-image").toFile()
            try {
                val paths = ProjectPathPolicy(root)
                val before = CalibrationProfileCodec.encode(record(1350.0))
                profileFile(paths).writeText(before)
                val after = record(1380.0)
                val metadata = MemoryMetadata()
                var savedRows = emptyMap<String, CalibrationMutationEntity>()
                var savedRevision = 0L
                val port = JournaledCalibrationProfilePublicationPort(paths, metadata, CalibrationMutationFaultInjector {
                    if (it == boundary) {
                        root.copyRecursively(snapshot, overwrite = true)
                        savedRows = metadata.rows.toMap()
                        savedRevision = metadata.revision
                        throw InterruptedProfileMutation()
                    }
                })
                val store = TransactionalProfileStore(port)
                try {
                    val key = CalibrationOperationKey(PROJECT, 7, UUID.randomUUID().toString())
                    if (remove) store.remove(key) else store.save(key, after)
                    fail("Expected a fault at $boundary")
                } catch (_: InterruptedProfileMutation) { }
                root.deleteRecursively(); snapshot.copyRecursively(root)
                metadata.rows.clear(); metadata.rows.putAll(savedRows); metadata.revision = savedRevision
                val report = JournaledCalibrationProfilePublicationPort(paths, metadata).recover(PROJECT)
                assertTrue("$boundary: ${report.failures}", report.failures.isEmpty())
                val committed = boundary == CalibrationMutationBoundary.METADATA_COMMITTED
                assertEquals(if (committed) 8L else 7L, metadata.revision)
                if (committed && remove) assertFalse(profileFile(paths).exists())
                else assertEquals(if (committed) CalibrationProfileCodec.encode(after) else before, profileFile(paths).readText())
                assertEquals(if (committed) 1 else 0, report.committed)
                assertEquals(if (committed) 0 else 1, report.rolledBack)
                assertTrue(JournaledCalibrationProfilePublicationPort(paths, metadata).recover(PROJECT).failures.isEmpty())
            } finally { root.deleteRecursively(); snapshot.deleteRecursively() }
        }
    }

    @Test fun staleRevisionLeavesOldProfileAndUnjournaledPreparationCanRecover() = runBlocking {
        val root = Files.createTempDirectory("profile-stale").toFile()
        try {
            val paths = ProjectPathPolicy(root)
            val metadata = MemoryMetadata()
            val old = CalibrationProfileCodec.encode(record(1350.0))
            profileFile(paths).writeText(old)
            val port = JournaledCalibrationProfilePublicationPort(paths, metadata)
            assertEquals(CalibrationProfileStoreResult.Stale(6, 7),
                TransactionalProfileStore(port).save(CalibrationOperationKey(PROJECT, 6, "stale"), record(1380.0)))
            assertEquals(old, profileFile(paths).readText())
            metadata.prepare(CalibrationMutationEntity("prepared-before-crash", PROJECT, 7,
                CalibrationProfileMutationKind.REMOVE.name, null, null))
            assertEquals(1, metadata.pending(PROJECT).size)
            assertTrue(port.recover(PROJECT).failures.isEmpty())
            assertTrue(metadata.pending(PROJECT).isEmpty())
            assertEquals(old, profileFile(paths).readText())
        } finally { root.deleteRecursively() }
    }

    private class InterruptedProfileMutation : IllegalStateException("Injected profile publication interruption")
    private fun profileFile(paths: ProjectPathPolicy) = paths.projectFile(PROJECT, "camera-calibration-v1.json")
    private fun record(focalPixels: Double) = CalibrationProfileCodec.aggregateOnly(LensProfile(
        1600, 1200, focalPixels, focalPixels - 2, 799.5, 599.5, -.08, .02, .001, -.001, 0.0, .42, 12, 26.0))
    companion object { const val PROJECT = "00000000-0000-0000-0000-000000000027" }
}
