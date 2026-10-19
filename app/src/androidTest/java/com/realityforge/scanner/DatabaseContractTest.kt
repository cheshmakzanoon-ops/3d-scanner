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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseContractTest {
    @Test
    fun revisionInvalidationAndCascade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insert(ScanProject("test", "Fixture", 0))
            dao.addPhoto(photo(id = "p1", projectId = "test", sha256 = "abc", addedAt = 0))
            assertEquals(1L, dao.project("test")!!.revision)
            assertEquals(0, dao.complete("test", 0, "key", "stale result"))
            assertEquals(1, dao.complete("test", 1, "key", "current result"))

            dao.addPhoto(photo(id = "p2", projectId = "test", sha256 = "def", addedAt = 1))
            assertNull(dao.project("test")!!.resultRevision)
            dao.deleteProject("test")
            assertTrue(dao.photos("test").isEmpty())
        } finally {
            db.close()
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
