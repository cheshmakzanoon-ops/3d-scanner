package com.realityforge.scanner

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.CaptureSessionEntity
import com.realityforge.scanner.data.CaptureSessionState
import com.realityforge.scanner.data.DeletionJournalEntity
import com.realityforge.scanner.data.DeletionPhase
import com.realityforge.scanner.data.ReconstructionJobEntity
import com.realityforge.scanner.data.ReconstructionJobState
import com.realityforge.scanner.data.ScanArtifactEntity
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.operations.AtomicProjectFileWriterImpl
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import com.realityforge.scanner.data.reopening.ReopenResult
import com.realityforge.scanner.data.reopening.ValidatedPreviewReopeningService
import com.realityforge.scanner.qa.SyntheticPreviewFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseContractTest {
    @Test
    fun revisionInvalidationAndCascade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        var fixtureRoot: File? = null
        var primaryFailure: Throwable? = null
        try {
            // This test owns only its generated cache root, never the application's
            // shared projects directory or a globally named "test" project.
            val ownedRoot = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(),
                "database-retained-preview-").toFile().also { fixtureRoot = it }
            val paths = ProjectPathPolicy(ownedRoot)
            val dao = db.dao()
            // The same strict reader used by ProjectRepository.defaultServices().
            val reader = ValidatedPreviewReopeningService(dao, paths)
            val id = UUID.randomUUID().toString()
            dao.insert(ScanProject(id, "Synthetic retained-result fixture", 0))
            dao.addPhoto(photo(id = UUID.randomUUID().toString(), projectId = id, sha256 = "abc", addedAt = 0))
            assertEquals(1L, requireNotNull(dao.project(id)).revision)
            assertEquals(0, dao.complete(id, 0, "sparse-m10-r0-0123456789abcdef", "stale result"))

            val key = "sparse-m10-r1-0123456789abcdef"
            val savedPreview = paths.artifact(id, key, "json")
            AtomicProjectFileWriterImpl(paths).write(savedPreview,
                SyntheticPreviewFixtures.sparsePreview(key, 1L).toString())
            val acceptedBytes = savedPreview.readBytes()
            assertEquals(1, dao.complete(id, 1, key, "Synthetic current sparse result"))
            assertEquals(key, requireNotNull(reader.currentPreview(id)).getString("artifactKey"))
            val current = reader.reopen(id)
            assertTrue("Valid synthetic preview must reopen as current: $current", current is ReopenResult.Ready)
            val currentModel = (current as ReopenResult.Ready).model
            assertFalse(currentModel.previousResult)
            assertEquals(1L, currentModel.revision)
            assertEquals(key, currentModel.artifactKey)

            dao.addPhoto(photo(id = UUID.randomUUID().toString(), projectId = id, sha256 = "def", addedAt = 1))
            val invalidated = requireNotNull(dao.project(id))
            assertEquals(2L, invalidated.revision)
            assertEquals("READY", invalidated.state)
            assertEquals(1L, requireNotNull(invalidated.resultRevision))
            assertEquals(key, invalidated.resultKey)
            assertNull(reader.currentPreview(id))
            assertEquals(ReopenResult.Missing, reader.reopen(id))

            val previous = reader.reopen(id, previous = true)
            assertTrue("Retained result must be explicitly reopenable as previous: $previous", previous is ReopenResult.Ready)
            val previousModel = (previous as ReopenResult.Ready).model
            assertTrue(previousModel.previousResult)
            assertEquals(1L, previousModel.revision)
            assertEquals(key, previousModel.artifactKey)
            assertEquals(currentModel.sparsePoints, previousModel.sparsePoints)
            assertArrayEquals("Input edits must retain completed preview bytes", acceptedBytes, savedPreview.readBytes())

            // A late completion for revision 1 cannot replace the retained identity
            // or make that output current at revision 2.
            assertEquals(0, dao.complete(id, 1, "sparse-m10-r1-fedcba9876543210", "late stale completion"))
            val afterStaleCompletion = requireNotNull(dao.project(id))
            assertEquals(invalidated, afterStaleCompletion)
            assertNull(reader.currentPreview(id))
            assertEquals(previous, reader.reopen(id, previous = true))
            assertArrayEquals(acceptedBytes, savedPreview.readBytes())
            assertEquals(2, dao.photos(id).size)
            dao.deleteProject(id)
            assertNull(dao.project(id))
            assertTrue(dao.photos(id).isEmpty())
            assertEquals(ReopenResult.Missing, reader.reopen(id, previous = true))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            runCatching { db.close() }.exceptionOrNull()?.let(cleanupFailures::add)
            runCatching {
                fixtureRoot?.let { check(!it.exists() || it.deleteRecursively()) { "Cannot remove owned database fixture" } }
            }.exceptionOrNull()?.let(cleanupFailures::add)
            val primary = primaryFailure
            if (primary != null) cleanupFailures.forEach(primary::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test
    fun interruptedSessionCannotStayProcessing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insert(ScanProject("test", "Fixture", 0, state = "PROCESSING"))
            dao.recoverInterrupted()
            assertEquals("INTERRUPTED", dao.project("test")!!.state)
        } finally {
            db.close()
        }
    }

    @Test
    fun stalePhotoCurationRollsBackAsOneTransaction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insert(ScanProject("curation", "Fixture", 0))
            dao.addPhoto(photo(id = "photo", projectId = "curation", sha256 = "hash", addedAt = 1))

            val stale = runCatching {
                dao.curatePhoto(
                    projectId = "curation",
                    photoId = "photo",
                    expectedRevision = 0,
                    included = false,
                    selectionOrder = 4,
                )
            }
            assertTrue(stale.exceptionOrNull() is IllegalStateException)
            val afterRollback = dao.photos("curation").single()
            assertTrue(afterRollback.included)
            assertNull(afterRollback.selectionOrder)
            assertEquals(1L, dao.project("curation")!!.revision)

            assertTrue(
                dao.curatePhoto(
                    projectId = "curation",
                    photoId = "photo",
                    expectedRevision = 1,
                    included = false,
                    selectionOrder = 4,
                ),
            )
            val committed = dao.photos("curation").single()
            assertFalse(committed.included)
            assertEquals(4L, requireNotNull(committed.selectionOrder))
            assertEquals(2L, dao.project("curation")!!.revision)
        } finally {
            db.close()
        }
    }

    @Test
    fun captureIdentityAndSameProjectForeignKeysAreEnforced() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val projects = db.dao()
            val durable = db.durableStateDao()
            projects.insert(ScanProject("a", "Project A", 0, revision = 2))
            projects.insert(ScanProject("b", "Project B", 0, revision = 2))

            val session = CaptureSessionEntity(
                id = "session-a",
                projectId = "a",
                sourceRevision = 2,
                identitySha256 = "capture-session-identity",
                createdAt = 10,
                updatedAt = 10,
                cameraIdentitySha256 = "camera-a",
                lensIdentitySha256 = "lens-a",
            )
            assertTrue(durable.createCaptureSessionForCurrentRevision(session))
            assertFalse(durable.createCaptureSessionForCurrentRevision(session.copy(id = "duplicate-session")))
            assertFalse(
                durable.createCaptureSessionForCurrentRevision(
                    session.copy(
                        id = "stale-session",
                        sourceRevision = 1,
                        identitySha256 = "stale-capture-session-identity",
                    ),
                ),
            )
            assertFalse(
                projects.addCapturedPhoto(
                    photo(
                        id = "cross-project-capture",
                        projectId = "b",
                        sha256 = "cross-project-capture-hash",
                        addedAt = 10,
                        captureSessionId = session.id,
                        captureSequence = 0,
                    ),
                ),
            )
            assertTrue(
                projects.addCapturedPhoto(
                    photo(
                        id = "capture-1",
                        projectId = "a",
                        sha256 = "capture-hash-1",
                        addedAt = 11,
                        captureSessionId = session.id,
                        captureSequence = 0,
                    ),
                ),
            )
            assertTrue(
                runCatching {
                    projects.addCapturedPhoto(
                        photo(
                            id = "capture-2",
                            projectId = "a",
                            sha256 = "capture-hash-2",
                            addedAt = 12,
                            captureSessionId = session.id,
                            captureSequence = 0,
                        ),
                    )
                }.isFailure,
            )
            assertEquals(3L, projects.project("a")!!.revision)
            assertEquals(
                1,
                durable.transitionCaptureSession(
                    id = session.id,
                    fromState = CaptureSessionState.OPEN,
                    toState = CaptureSessionState.CLOSED,
                    updatedAt = 20,
                ),
            )
            assertFalse(
                projects.addCapturedPhoto(
                    photo(
                        id = "capture-after-close",
                        projectId = "a",
                        sha256 = "capture-after-close-hash",
                        addedAt = 13,
                        captureSessionId = session.id,
                        captureSequence = 1,
                    ),
                ),
            )
            assertEquals(
                0,
                durable.transitionCaptureSession(
                    id = session.id,
                    fromState = CaptureSessionState.CLOSED,
                    toState = CaptureSessionState.CANCELLED,
                    updatedAt = 21,
                ),
            )

            val foreignJob = job(
                id = "job-b",
                projectId = "b",
                revision = 2,
                identity = "job-b-identity",
            )
            assertTrue(durable.createJobForCurrentRevision(foreignJob))
            assertTrue(
                runCatching {
                    durable.createPendingArtifactForCurrentRevision(
                        artifact(
                            id = "cross-project-artifact",
                            projectId = "a",
                            revision = 3,
                            identity = "cross-project-artifact-identity",
                            relativePath = "artifacts/cross.glb",
                            jobId = foreignJob.id,
                        ),
                    )
                }.isFailure,
            )
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun concurrentJobArtifactAndDeletionTransitionsAreCompareAndSet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val projects = db.dao()
            val durable = db.durableStateDao()
            projects.insert(ScanProject("project", "Fixture", 0, revision = 4))

            val reconstruction = job(
                id = "job",
                projectId = "project",
                revision = 4,
                identity = "job-identity",
            )
            assertTrue(durable.createJobForCurrentRevision(reconstruction))
            assertFalse(
                durable.createJobForCurrentRevision(
                    reconstruction.copy(id = "duplicate-job"),
                ),
            )
            assertEquals(
                0,
                durable.tryAcquireJob(
                    jobId = reconstruction.id,
                    expectedAttempt = 0,
                    leaseToken = "bad-expiry",
                    leaseOwner = "worker",
                    now = 100,
                    leaseExpiresAt = 100,
                ),
            )

            val acquisitions = coroutineScope {
                (0 until 8).map { worker ->
                    async(Dispatchers.IO) {
                        durable.tryAcquireJob(
                            jobId = reconstruction.id,
                            expectedAttempt = 0,
                            leaseToken = "lease-$worker",
                            leaseOwner = "worker-$worker",
                            now = 100,
                            leaseExpiresAt = 10_000,
                        )
                    }
                }.awaitAll()
            }
            assertEquals(1, acquisitions.sum())
            val acquired = durable.job(reconstruction.id)!!
            assertEquals(ReconstructionJobState.RUNNING, acquired.stateCode)
            assertEquals(1L, acquired.attempt)
            val token = requireNotNull(acquired.leaseToken)

            assertTrue(
                durable.createPendingArtifactForCurrentRevision(
                    artifact(
                        id = "artifact",
                        projectId = "project",
                        revision = 4,
                        identity = "artifact-identity",
                        relativePath = "artifacts/result.glb",
                        jobId = reconstruction.id,
                    ),
                ),
            )
            assertEquals(1, durable.commitArtifact("artifact", "project", 4))
            assertEquals(
                0,
                durable.completeJob(
                    jobId = reconstruction.id,
                    attempt = 0,
                    leaseToken = token,
                    resultArtifactId = "artifact",
                    now = 200,
                ),
            )
            assertEquals(
                1,
                durable.completeJob(
                    jobId = reconstruction.id,
                    attempt = 1,
                    leaseToken = token,
                    resultArtifactId = "artifact",
                    now = 200,
                ),
            )
            assertEquals(ReconstructionJobState.COMPLETED, durable.job(reconstruction.id)!!.stateCode)

            val staleJob = job(
                id = "stale-job",
                projectId = "project",
                revision = 4,
                identity = "stale-job-identity",
                createdAt = 250,
            )
            assertTrue(durable.createJobForCurrentRevision(staleJob))
            assertEquals(
                1,
                durable.tryAcquireJob(
                    jobId = staleJob.id,
                    expectedAttempt = 0,
                    leaseToken = "stale-lease",
                    leaseOwner = "worker",
                    now = 250,
                    leaseExpiresAt = 10_000,
                ),
            )
            assertTrue(
                durable.createPendingArtifactForCurrentRevision(
                    artifact(
                        id = "stale-artifact",
                        projectId = "project",
                        revision = 4,
                        identity = "stale-artifact-identity",
                        relativePath = "artifacts/stale.glb",
                        jobId = staleJob.id,
                        createdAt = 260,
                    ),
                ),
            )
            projects.invalidate("project")
            assertEquals(5L, projects.project("project")!!.revision)
            assertEquals(0, durable.commitArtifact("stale-artifact", "project", 4))
            assertEquals(
                0,
                durable.completeJob(
                    jobId = staleJob.id,
                    attempt = 1,
                    leaseToken = "stale-lease",
                    resultArtifactId = "stale-artifact",
                    now = 300,
                ),
            )

            val failureJob = job(
                id = "failure-job",
                projectId = "project",
                revision = 5,
                identity = "failure-job-identity",
                createdAt = 310,
            )
            assertTrue(durable.createJobForCurrentRevision(failureJob))
            assertEquals(
                1,
                durable.tryAcquireJob(
                    jobId = failureJob.id,
                    expectedAttempt = 0,
                    leaseToken = "failure-lease",
                    leaseOwner = "worker",
                    now = 310,
                    leaseExpiresAt = 10_000,
                ),
            )
            assertEquals(
                0,
                durable.finishJobWithFailure(
                    jobId = failureJob.id,
                    attempt = 1,
                    leaseToken = "failure-lease",
                    terminalState = ReconstructionJobState.QUEUED,
                    terminalCode = "ILLEGAL_TERMINAL",
                    now = 320,
                ),
            )
            assertEquals(
                1,
                durable.finishJobWithFailure(
                    jobId = failureJob.id,
                    attempt = 1,
                    leaseToken = "failure-lease",
                    terminalState = ReconstructionJobState.FAILED,
                    terminalCode = "NATIVE_FAILURE",
                    now = 320,
                ),
            )

            val journalResults = coroutineScope {
                (0 until 6).map { worker ->
                    async(Dispatchers.IO) {
                        durable.beginDeletionForCurrentRevision(
                            DeletionJournalEntity(
                                id = "deletion-$worker",
                                projectId = "project",
                                expectedRevision = 5,
                                rootRelativePath = "projects/project",
                                createdAt = 400,
                                updatedAt = 400,
                            ),
                        )
                    }
                }.awaitAll()
            }
            assertEquals(1, journalResults.count { it })
            assertEquals(5, journalResults.count { !it })
            assertEquals(
                0,
                durable.advanceDeletion(
                    projectId = "project",
                    expectedPhase = DeletionPhase.PLANNED,
                    expectedAttempt = 0,
                    nextPhase = DeletionPhase.FILES_REMOVED,
                    updatedAt = 499,
                    lastErrorCode = null,
                ),
            )
            assertEquals(
                1,
                durable.advanceDeletion(
                    projectId = "project",
                    expectedPhase = DeletionPhase.PLANNED,
                    expectedAttempt = 0,
                    nextPhase = DeletionPhase.FILES_REMOVING,
                    updatedAt = 500,
                    lastErrorCode = null,
                ),
            )
            assertEquals(
                0,
                durable.advanceDeletion(
                    projectId = "project",
                    expectedPhase = DeletionPhase.PLANNED,
                    expectedAttempt = 0,
                    nextPhase = DeletionPhase.FILES_REMOVING,
                    updatedAt = 501,
                    lastErrorCode = null,
                ),
            )
            assertEquals(
                1,
                durable.advanceDeletion(
                    projectId = "project",
                    expectedPhase = DeletionPhase.FILES_REMOVING,
                    expectedAttempt = 1,
                    nextPhase = DeletionPhase.FAILED_RETRYABLE,
                    updatedAt = 502,
                    lastErrorCode = "DELETE_IO_FAILURE",
                ),
            )
            assertEquals(
                1,
                durable.advanceDeletion(
                    projectId = "project",
                    expectedPhase = DeletionPhase.FAILED_RETRYABLE,
                    expectedAttempt = 2,
                    nextPhase = DeletionPhase.FILES_REMOVING,
                    updatedAt = 503,
                    lastErrorCode = null,
                ),
            )
            val journal = durable.deletionJournal("project")!!
            assertEquals(DeletionPhase.FILES_REMOVING, journal.phaseCode)
            assertEquals(3L, journal.attempt)
            assertNull(journal.lastErrorCode)
        } finally {
            db.close()
        }
    }

    private fun photo(
        id: String,
        projectId: String,
        sha256: String,
        addedAt: Long,
        captureSessionId: String? = null,
        captureSequence: Long? = null,
    ) = ScanPhoto(
        id = id,
        projectId = projectId,
        filename = "$id.image",
        sha256 = sha256,
        bytes = 10,
        width = 10,
        height = 10,
        focal35mm = null,
        sharpness = 1.0,
        darkFraction = 0.0,
        brightFraction = 0.0,
        addedAt = addedAt,
        captureSessionId = captureSessionId,
        captureSequence = captureSequence,
    )

    private fun job(
        id: String,
        projectId: String,
        revision: Long,
        identity: String,
        createdAt: Long = 1,
    ) = ReconstructionJobEntity(
        id = id,
        projectId = projectId,
        inputRevision = revision,
        identitySha256 = identity,
        settingsSha256 = "settings-$identity",
        engineVersion = "engine-v1",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun artifact(
        id: String,
        projectId: String,
        revision: Long,
        identity: String,
        relativePath: String,
        jobId: String,
        createdAt: Long = 150,
    ) = ScanArtifactEntity(
        id = id,
        projectId = projectId,
        projectRevision = revision,
        kindCode = 1,
        representationCode = 1,
        identitySha256 = identity,
        relativePath = relativePath,
        byteSize = 20,
        contentSha256 = "content-$identity",
        createdAt = createdAt,
        jobId = jobId,
    )
}
