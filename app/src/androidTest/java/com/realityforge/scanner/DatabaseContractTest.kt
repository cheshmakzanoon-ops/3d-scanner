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
        database().use { db ->
            val dao = db.dao()
            dao.insert(ScanProject("test", "Fixture", 0))
            dao.addPhoto(photo("p1", "test", "abc", 0))
            assertEquals(1L, dao.project("test")!!.revision)
            assertEquals(0, dao.complete("test", 0, "key", "stale result"))
            assertEquals(1, dao.complete("test", 1, "key", "current result"))
            dao.addPhoto(photo("p2", "test", "def", 1))
            assertNull(dao.project("test")!!.resultRevision)
            dao.deleteProject("test")
            assertTrue(dao.photos("test").isEmpty())
        }
    }

    @Test
    fun interruptedSessionCannotStayProcessing() = runBlocking {
        database().use { db ->
            db.dao().insert(ScanProject("test", "Fixture", 0, state = "PROCESSING"))
            db.dao().recoverInterrupted()
            assertEquals("INTERRUPTED", db.dao().project("test")!!.state)
        }
    }

    @Test
    fun stalePhotoCurationRollsBackAsOneTransaction() = runBlocking {
        database().use { db ->
            val dao = db.dao()
            dao.insert(ScanProject("curation", "Fixture", 0))
            dao.addPhoto(photo("photo", "curation", "hash", 1))

            val stale = runCatching {
                dao.curatePhoto("curation", "photo", 0, included = false, selectionOrder = 4)
            }
            assertTrue(stale.exceptionOrNull() is IllegalStateException)
            assertTrue(dao.photos("curation").single().included)
            assertNull(dao.photos("curation").single().selectionOrder)
            assertEquals(1L, dao.project("curation")!!.revision)

            assertTrue(dao.curatePhoto("curation", "photo", 1, included = false, selectionOrder = 4))
            val committed = dao.photos("curation").single()
            assertFalse(committed.included)
            assertEquals(4L, requireNotNull(committed.selectionOrder))
            assertEquals(2L, dao.project("curation")!!.revision)
        }
    }

    @Test
    fun captureIdentityAndSameProjectForeignKeysAreEnforced() = runBlocking {
        database().use { db ->
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
            assertFalse(durable.createCaptureSessionForCurrentRevision(session.copy(id = "duplicate")))
            assertFalse(
                durable.createCaptureSessionForCurrentRevision(
                    session.copy(
                        id = "stale",
                        sourceRevision = 1,
                        identitySha256 = "stale-session-identity",
                    ),
                ),
            )
            assertFalse(
                projects.addCapturedPhoto(
                    photo("wrong-project", "b", "wrong", 10, session.id, 0),
                ),
            )
            assertTrue(
                projects.addCapturedPhoto(
                    photo("capture-1", "a", "capture-hash-1", 11, session.id, 0),
                ),
            )
            assertTrue(
                runCatching {
                    projects.addCapturedPhoto(
                        photo("capture-2", "a", "capture-hash-2", 12, session.id, 0),
                    )
                }.isFailure,
            )
            assertEquals(3L, projects.project("a")!!.revision)
            assertEquals(
                1,
                durable.transitionCaptureSession(
                    session.id,
                    CaptureSessionState.OPEN,
                    CaptureSessionState.CLOSED,
                    20,
                ),
            )
            assertFalse(
                projects.addCapturedPhoto(
                    photo("after-close", "a", "after-close", 13, session.id, 1),
                ),
            )
            assertEquals(
                0,
                durable.transitionCaptureSession(
                    session.id,
                    CaptureSessionState.CLOSED,
                    CaptureSessionState.CANCELLED,
                    21,
                ),
            )

            val foreignJob = job("job-b", "b", 2, "job-b-identity")
            assertTrue(durable.createJobForCurrentRevision(foreignJob))
            assertTrue(
                runCatching {
                    durable.createPendingArtifactForCurrentRevision(
                        artifact(
                            "cross-artifact",
                            "a",
                            3,
                            "cross-artifact-identity",
                            "artifacts/cross.glb",
                            foreignJob.id,
                        ),
                    )
                }.isFailure,
            )
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
        }
    }

    @Test
    fun concurrentJobArtifactAndDeletionTransitionsAreCompareAndSet() = runBlocking {
        database().use { db ->
            val projects = db.dao()
            val durable = db.durableStateDao()
            projects.insert(ScanProject("project", "Fixture", 0, revision = 4))

            val job = job("job", "project", 4, "job-identity")
            assertTrue(durable.createJobForCurrentRevision(job))
            assertFalse(durable.createJobForCurrentRevision(job.copy(id = "duplicate-job")))
            assertEquals(
                0,
                durable.tryAcquireJob(job.id, 0, "bad-expiry", "worker", 100, 100),
            )

            val acquisitions = coroutineScope {
                (0 until 8).map { worker ->
                    async(Dispatchers.IO) {
                        durable.tryAcquireJob(
                            job.id,
                            0,
                            "lease-$worker",
                            "worker-$worker",
                            100,
                            10_000,
                        )
                    }
                }.awaitAll()
            }
            assertEquals(1, acquisitions.sum())
            val acquired = durable.job(job.id)!!
            assertEquals(ReconstructionJobState.RUNNING, acquired.stateCode)
            assertEquals(1L, acquired.attempt)
            val token = requireNotNull(acquired.leaseToken)

            assertTrue(
                durable.createPendingArtifactForCurrentRevision(
                    artifact("artifact", "project", 4, "artifact-identity", "artifacts/result.glb", job.id),
                ),
            )
            assertEquals(1, durable.commitArtifact("artifact", "project", 4))
            assertEquals(0, durable.completeJob(job.id, 0, token, "artifact", 200))
            assertEquals(1, durable.completeJob(job.id, 1, token, "artifact", 200))
            assertEquals(ReconstructionJobState.COMPLETED, durable.job(job.id)!!.stateCode)

            val staleJob = job("stale-job", "project", 4, "stale-job-identity", 250)
            assertTrue(durable.createJobForCurrentRevision(staleJob))
            assertEquals(1, durable.tryAcquireJob(staleJob.id, 0, "stale-lease", "worker", 250, 10_000))
            assertTrue(
                durable.createPendingArtifactForCurrentRevision(
                    artifact(
                        "stale-artifact",
                        "project",
                        4,
                        "stale-artifact-identity",
                        "artifacts/stale.glb",
                        staleJob.id,
                        260,
                    ),
                ),
            )
            projects.invalidate("project")
            assertEquals(5L, projects.project("project")!!.revision)
            assertEquals(0, durable.commitArtifact("stale-artifact", "project", 4))
            assertEquals(0, durable.completeJob(staleJob.id, 1, "stale-lease", "stale-artifact", 300))

            val failureJob = job("failure-job", "project", 5, "failure-job-identity", 310)
            assertTrue(durable.createJobForCurrentRevision(failureJob))
            assertEquals(1, durable.tryAcquireJob(failureJob.id, 0, "failure-lease", "worker", 310, 10_000))
            assertEquals(
                0,
                durable.finishJobWithFailure(
                    failureJob.id,
                    1,
                    "failure-lease",
                    ReconstructionJobState.QUEUED,
                    "ILLEGAL_TERMINAL",
                    320,
                ),
            )
            assertEquals(
                1,
                durable.finishJobWithFailure(
                    failureJob.id,
                    1,
                    "failure-lease",
                    ReconstructionJobState.FAILED,
                    "NATIVE_FAILURE",
                    320,
                ),
            )

            val starts = coroutineScope {
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
            assertEquals(1, starts.count { it })
            assertEquals(5, starts.count { !it })
            assertEquals(
                0,
                durable.advanceDeletion(
                    "project",
                    DeletionPhase.PLANNED,
                    0,
                    DeletionPhase.FILES_REMOVED,
                    499,
                    null,
                ),
            )
            assertEquals(
                1,
                durable.advanceDeletion(
                    "project",
                    DeletionPhase.PLANNED,
                    0,
                    DeletionPhase.FILES_REMOVING,
                    500,
                    null,
                ),
            )
            assertEquals(
                0,
                durable.advanceDeletion(
                    "project",
                    DeletionPhase.PLANNED,
                    0,
                    DeletionPhase.FILES_REMOVING,
                    501,
                    null,
                ),
            )
            assertEquals(
                1,
                durable.advanceDeletion(
                    "project",
                    DeletionPhase.FILES_REMOVING,
                    1,
                    DeletionPhase.FAILED_RETRYABLE,
                    502,
                    "DELETE_IO_FAILURE",
                ),
            )
            assertEquals(
                1,
                durable.advanceDeletion(
                    "project",
                    DeletionPhase.FAILED_RETRYABLE,
                    2,
                    DeletionPhase.FILES_REMOVING,
                    503,
                    null,
                ),
            )
            val journal = durable.deletionJournal("project")!!
            assertEquals(DeletionPhase.FILES_REMOVING, journal.phaseCode)
            assertEquals(3L, journal.attempt)
            assertNull(journal.lastErrorCode)
        }
    }

    private fun database(): ScanDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
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
