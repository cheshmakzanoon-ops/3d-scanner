package com.realityforge.scanner.data

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.realityforge.scanner.data.migrations.ScanDatabaseV1ToV2
import kotlinx.coroutines.flow.Flow

/**
 * Legacy project state codes. They remain text for version-1 compatibility, but are
 * machine values; user-facing copy belongs in [ScanProject.message].
 */
object ProjectLifecycle {
    const val NEW = "NEW"
    const val READY = "READY"
    const val PROCESSING = "PROCESSING"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED = "FAILED"
    const val SPARSE_READY = "SPARSE_READY"
}

object CaptureSessionState {
    const val OPEN = 0
    const val CLOSED = 1
    const val CANCELLED = 2
}

object ReconstructionJobState {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val COMPLETED = 3
    const val FAILED = 4
    const val CANCELLED = 5
    const val STALE = 6
    const val BLOCKED_EXTERNAL = 7
}

object DeletionPhase {
    const val PLANNED = 0
    const val FILES_REMOVING = 1
    const val FILES_REMOVED = 2
    const val DATABASE_REMOVED = 3
    const val COMPLETED = 4
    const val FAILED_RETRYABLE = 5
}

@Entity(
    tableName = "projects",
    indices = [
        Index(value = ["revision"]),
        Index(value = ["state"]),
        Index(value = ["createdAt", "id"]),
        Index(value = ["libraryNameKey", "id"]),
    ],
)
data class ScanProject(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val revision: Long = 0,
    val state: String = ProjectLifecycle.NEW,
    val message: String = "Add photographs to begin.",
    val resultRevision: Long? = null,
    val resultKey: String? = null,
    @ColumnInfo(defaultValue = "0") val curationVersion: Long = 0,
    @ColumnInfo(defaultValue = "''") val libraryNameKey: String = name.lowercase(java.util.Locale.ROOT),
)

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = ScanProject::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CaptureSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureSessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["captureSessionId"]),
        Index(value = ["projectId", "sha256"], unique = true),
        Index(value = ["projectId", "included", "selectionOrder"]),
        Index(value = ["captureSessionId", "captureSequence"], unique = true),
    ],
)
data class ScanPhoto(
    @PrimaryKey val id: String,
    val projectId: String,
    val filename: String,
    val sha256: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val focal35mm: Double?,
    val sharpness: Double,
    val darkFraction: Double,
    val brightFraction: Double,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "1") val included: Boolean = true,
    val selectionOrder: Long? = null,
    val captureSessionId: String? = null,
    val captureSequence: Long? = null,
    val captureMetadataRecord: String? = null,
    val opticalIdentitySha256: String? = null,
    val curationRecord: String? = null,
    val captureSettingsRecord: String? = null,
)

@Entity(
    tableName = "capture_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ScanProject::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["projectId", "stateCode"]),
        Index(value = ["projectId", "identitySha256"], unique = true),
        Index(value = ["id", "projectId"], unique = true),
    ],
)
data class CaptureSessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sourceRevision: Long,
    val identitySha256: String,
    val createdAt: Long,
    val updatedAt: Long,
    val stateCode: Int = CaptureSessionState.OPEN,
    val cameraIdentitySha256: String? = null,
    val lensIdentitySha256: String? = null,
    val closedAt: Long? = null,
)

@Entity(
    tableName = "reconstruction_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ScanProject::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["projectId", "stateCode"]),
        Index(value = ["projectId", "identitySha256"], unique = true),
        Index(value = ["id", "projectId"], unique = true),
        Index(value = ["leaseToken"], unique = true),
        Index(value = ["leaseExpiresAt"]),
    ],
)
data class ReconstructionJobEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val inputRevision: Long,
    val identitySha256: String,
    val settingsSha256: String,
    val engineVersion: String,
    val createdAt: Long,
    val updatedAt: Long,
    val stateCode: Int = ReconstructionJobState.QUEUED,
    val stageCode: Int = 0,
    val attempt: Long = 0,
    val leaseToken: String? = null,
    val leaseOwner: String? = null,
    val leaseExpiresAt: Long? = null,
    val cancelRequestedAt: Long? = null,
    val resultArtifactId: String? = null,
    val terminalCode: String? = null,
)

@Entity(
    tableName = "artifacts",
    foreignKeys = [
        ForeignKey(
            entity = ScanProject::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReconstructionJobEntity::class,
            parentColumns = ["id", "projectId"],
            childColumns = ["jobId", "projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["jobId", "projectId"]),
        Index(value = ["id", "projectId"], unique = true),
        Index(value = ["parentArtifactId"]),
        Index(value = ["projectId", "identitySha256"], unique = true),
        Index(value = ["projectId", "relativePath"], unique = true),
        Index(value = ["projectId", "projectRevision", "committed"]),
    ],
)
data class ScanArtifactEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val projectRevision: Long,
    val kindCode: Int,
    val representationCode: Int,
    val identitySha256: String,
    val relativePath: String,
    val byteSize: Long,
    val contentSha256: String,
    val createdAt: Long,
    val jobId: String? = null,
    val parentArtifactId: String? = null,
    val committed: Boolean = false,
)

@Entity(
    tableName = "edit_recipes",
    foreignKeys = [
        ForeignKey(
            entity = ScanProject::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScanArtifactEntity::class,
            parentColumns = ["id", "projectId"],
            childColumns = ["parentArtifactId", "projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["parentArtifactId", "projectId"]),
        Index(value = ["projectId", "identitySha256"], unique = true),
    ],
)
data class EditRecipeEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val parentArtifactId: String,
    val recipeVersion: Int,
    val operationsJson: String,
    val identitySha256: String,
    val createdAt: Long,
)

@Entity(
    tableName = "deletion_journals",
    indices = [
        Index(value = ["projectId"], unique = true),
        Index(value = ["phaseCode"]),
    ],
)
data class DeletionJournalEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val expectedRevision: Long,
    val rootRelativePath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val phaseCode: Int = DeletionPhase.PLANNED,
    val attempt: Long = 0,
    val lastErrorCode: String? = null,
)

@Dao
interface ScanDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC, id LIMIT 100")
    fun observeProjects(): Flow<List<ScanProject>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeProject(id: String): Flow<ScanProject?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun project(id: String): ScanProject?

    @Query("UPDATE projects SET name = :name, libraryNameKey = :libraryNameKey WHERE id = :id AND revision = :expectedRevision AND state NOT IN ('DELETING', 'DELETING_PHOTO')")
    suspend fun renameExpected(id: String, expectedRevision: Long, name: String, libraryNameKey: String = name.lowercase(java.util.Locale.ROOT)): Int

    @Query("SELECT * FROM photos WHERE projectId = :id ORDER BY COALESCE(selectionOrder, addedAt), addedAt, id")
    suspend fun photos(id: String): List<ScanPhoto>

    @Query("SELECT * FROM photos WHERE projectId = :id ORDER BY COALESCE(selectionOrder, addedAt), addedAt, id")
    fun observePhotos(id: String): Flow<List<ScanPhoto>>

    @Query("SELECT COUNT(*) FROM photos WHERE projectId = :id AND sha256 = :hash")
    suspend fun duplicate(id: String, hash: String): Int

    @Insert
    suspend fun insert(project: ScanProject)

    @Insert
    suspend fun insert(photo: ScanPhoto)

    @Query(
        "UPDATE projects SET revision = revision + 1, state = 'READY', " +
            "message = 'Photos changed. Previous results are no longer current.' WHERE id = :id",
    )
    suspend fun invalidate(id: String)

    @Query(
        "UPDATE projects SET revision = revision + 1, state = 'READY', " +
            "message = 'Photos changed. Previous results are no longer current.' " +
            "WHERE id = :id AND revision = :expectedRevision",
    )
    suspend fun invalidateExpected(id: String, expectedRevision: Long): Int

    @Transaction
    suspend fun addPhoto(photo: ScanPhoto) {
        insert(photo)
        invalidate(photo.projectId)
    }

    @Transaction
    suspend fun addPhotoExpected(photo: ScanPhoto, expectedRevision: Long): Long {
        check(project(photo.projectId)?.revision == expectedRevision) { "Photo destination revision changed." }
        insert(photo)
        check(invalidateExpected(photo.projectId, expectedRevision) == 1) { "Photo revision changed during publication." }
        return expectedRevision + 1
    }

    /** Restore only a newly created root; all rows appear in one transaction. */
    @Transaction
    suspend fun restoreArchiveProject(project: ScanProject, photos: List<ScanPhoto>) {
        require(photos.all { it.projectId == project.id }) { "Archive photo belongs to a different project." }
        require(photos.map { it.id }.distinct().size == photos.size) { "Archive contains duplicate photo identities." }
        insert(project)
        photos.forEach { insert(it) }
    }

    @Query("SELECT * FROM photos WHERE projectId = :projectId AND sha256 = :sha256 LIMIT 1")
    suspend fun photoByHash(projectId: String, sha256: String): ScanPhoto?

    @Query("SELECT projectId FROM capture_sessions WHERE id = :sessionId AND stateCode = 0")
    suspend fun captureSessionProjectId(sessionId: String): String?

    @Transaction
    suspend fun addCapturedPhoto(photo: ScanPhoto): Boolean {
        val sessionId = photo.captureSessionId ?: return false
        if (captureSessionProjectId(sessionId) != photo.projectId) return false
        addPhoto(photo)
        return true
    }

    @Query(
        "UPDATE photos SET included = :included, selectionOrder = :selectionOrder " +
            "WHERE id = :photoId AND projectId = :projectId",
    )
    suspend fun updatePhotoCuration(
        projectId: String,
        photoId: String,
        included: Boolean,
        selectionOrder: Long?,
    ): Int

    @Query(
        "UPDATE photos SET included = :included, selectionOrder = :selectionOrder, curationRecord = :curationRecord " +
            "WHERE projectId = :projectId AND id = :photoId AND sha256 = :sha256",
    )
    suspend fun updatePhotoCurationRecord(
        projectId: String,
        photoId: String,
        sha256: String,
        included: Boolean,
        selectionOrder: Long,
        curationRecord: String,
    ): Int

    @Query(
        "UPDATE projects SET revision = :nextRevision, curationVersion = :nextCurationVersion, " +
            "state = CASE WHEN :inputsChanged THEN 'READY' ELSE state END, " +
            "message = CASE WHEN :inputsChanged THEN 'Photo selection changed. Reconstruct to refresh the result.' ELSE message END " +
            "WHERE id = :projectId AND revision = :expectedRevision AND curationVersion = :expectedCurationVersion " +
            "AND state NOT IN ('DELETING', 'DELETING_PHOTO')",
    )
    suspend fun compareAndSetCurationRevision(
        projectId: String,
        expectedRevision: Long,
        expectedCurationVersion: Long,
        nextRevision: Long,
        nextCurationVersion: Long,
        inputsChanged: Boolean,
    ): Int

    @Transaction
    suspend fun curatePhoto(
        projectId: String,
        photoId: String,
        expectedRevision: Long,
        included: Boolean,
        selectionOrder: Long?,
    ): Boolean {
        if (updatePhotoCuration(projectId, photoId, included, selectionOrder) != 1) return false
        check(invalidateExpected(projectId, expectedRevision) == 1) {
            "Project revision changed while photo curation was being committed."
        }
        return true
    }

    @Query(
        "UPDATE projects SET state = :state, message = :message " +
            "WHERE id = :id AND revision = :revision",
    )
    suspend fun state(id: String, revision: Long, state: String, message: String): Int

    @Query(
        "UPDATE projects SET state = :toState, message = :message " +
            "WHERE id = :id AND revision = :revision AND state = :fromState",
    )
    suspend fun transitionState(
        id: String,
        revision: Long,
        fromState: String,
        toState: String,
        message: String,
    ): Int

    @Query(
        "UPDATE projects SET state = 'SPARSE_READY', resultRevision = :revision, " +
            "resultKey = :resultKey, message = :message " +
            "WHERE id = :id AND revision = :revision",
    )
    suspend fun complete(id: String, revision: Long, resultKey: String, message: String): Int

    @Query(
        "UPDATE projects SET state = 'INTERRUPTED', " +
            "message = 'Previous session interrupted. Originals and completed artifacts are safe; rerun preview.' " +
            "WHERE state = 'PROCESSING'",
    )
    suspend fun recoverInterrupted()

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)
}

@Dao
interface DurableStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCaptureSession(session: CaptureSessionEntity): Long

    @Transaction
    suspend fun createCaptureSessionForCurrentRevision(session: CaptureSessionEntity): Boolean {
        if (projectRevision(session.projectId) != session.sourceRevision) return false
        return insertCaptureSession(session) != -1L
    }

    @Query("SELECT * FROM capture_sessions WHERE id = :id")
    suspend fun captureSession(id: String): CaptureSessionEntity?

    @Query(
        "UPDATE capture_sessions SET stateCode = :toState, updatedAt = :updatedAt, " +
            "closedAt = :updatedAt " +
            "WHERE id = :id AND stateCode = :fromState " +
            "AND :fromState = 0 AND :toState IN (1, 2)",
    )
    suspend fun transitionCaptureSession(
        id: String,
        fromState: Int,
        toState: Int,
        updatedAt: Long,
    ): Int

    @Query("SELECT revision FROM projects WHERE id = :projectId")
    suspend fun projectRevision(projectId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: ReconstructionJobEntity): Long

    @Transaction
    suspend fun createJobForCurrentRevision(job: ReconstructionJobEntity): Boolean {
        if (projectRevision(job.projectId) != job.inputRevision) return false
        return insertJob(job) != -1L
    }

    @Query("SELECT * FROM reconstruction_jobs WHERE id = :id")
    suspend fun job(id: String): ReconstructionJobEntity?

    @Query(
        "UPDATE reconstruction_jobs SET stateCode = 1, attempt = attempt + 1, " +
            "leaseToken = :leaseToken, leaseOwner = :leaseOwner, " +
            "leaseExpiresAt = :leaseExpiresAt, updatedAt = :now, terminalCode = NULL " +
            "WHERE id = :jobId AND attempt = :expectedAttempt " +
            "AND :leaseExpiresAt > :now AND stateCode IN (0, 1, 2) " +
            "AND cancelRequestedAt IS NULL " +
            "AND (leaseToken IS NULL OR leaseExpiresAt IS NULL OR leaseExpiresAt <= :now)",
    )
    suspend fun tryAcquireJob(
        jobId: String,
        expectedAttempt: Long,
        leaseToken: String,
        leaseOwner: String,
        now: Long,
        leaseExpiresAt: Long,
    ): Int

    @Query(
        "UPDATE reconstruction_jobs SET leaseExpiresAt = :leaseExpiresAt, updatedAt = :now " +
            "WHERE id = :jobId AND stateCode = 1 AND attempt = :attempt " +
            "AND :leaseExpiresAt > :now AND leaseToken = :leaseToken " +
            "AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt >= :now",
    )
    suspend fun renewJobLease(
        jobId: String,
        attempt: Long,
        leaseToken: String,
        now: Long,
        leaseExpiresAt: Long,
    ): Int

    @Query(
        "UPDATE reconstruction_jobs SET stateCode = 2, stageCode = :stageCode, " +
            "leaseToken = NULL, leaseOwner = NULL, leaseExpiresAt = NULL, updatedAt = :now " +
            "WHERE id = :jobId AND stateCode = 1 AND attempt = :attempt " +
            "AND leaseToken = :leaseToken AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt >= :now",
    )
    suspend fun pauseJob(
        jobId: String,
        attempt: Long,
        leaseToken: String,
        stageCode: Int,
        now: Long,
    ): Int

    @Query(
        "UPDATE reconstruction_jobs SET cancelRequestedAt = COALESCE(cancelRequestedAt, :now), " +
            "updatedAt = :now WHERE id = :jobId AND stateCode IN (0, 1, 2)",
    )
    suspend fun requestJobCancellation(jobId: String, now: Long): Int

    @Query(
        "UPDATE reconstruction_jobs SET stateCode = 3, resultArtifactId = :resultArtifactId, " +
            "leaseToken = NULL, leaseOwner = NULL, leaseExpiresAt = NULL, " +
            "updatedAt = :now, terminalCode = NULL " +
            "WHERE id = :jobId AND stateCode = 1 AND attempt = :attempt " +
            "AND cancelRequestedAt IS NULL AND leaseToken = :leaseToken " +
            "AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt >= :now " +
            "AND EXISTS (SELECT 1 FROM projects AS p " +
            "WHERE p.id = reconstruction_jobs.projectId " +
            "AND p.revision = reconstruction_jobs.inputRevision) " +
            "AND EXISTS (SELECT 1 FROM artifacts AS a WHERE a.id = :resultArtifactId " +
            "AND a.projectId = reconstruction_jobs.projectId " +
            "AND a.jobId = reconstruction_jobs.id " +
            "AND a.projectRevision = reconstruction_jobs.inputRevision AND a.committed = 1)",
    )
    suspend fun completeJob(
        jobId: String,
        attempt: Long,
        leaseToken: String,
        resultArtifactId: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE reconstruction_jobs SET stateCode = :terminalState, terminalCode = :terminalCode, " +
            "leaseToken = NULL, leaseOwner = NULL, leaseExpiresAt = NULL, updatedAt = :now " +
            "WHERE id = :jobId AND stateCode = 1 AND attempt = :attempt " +
            "AND leaseToken = :leaseToken AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt >= :now " +
            "AND :terminalState IN (4, 5, 6, 7)",
    )
    suspend fun finishJobWithFailure(
        jobId: String,
        attempt: Long,
        leaseToken: String,
        terminalState: Int,
        terminalCode: String,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArtifact(artifact: ScanArtifactEntity)

    @Transaction
    suspend fun createPendingArtifactForCurrentRevision(artifact: ScanArtifactEntity): Boolean {
        if (artifact.committed || projectRevision(artifact.projectId) != artifact.projectRevision) return false
        insertArtifact(artifact)
        return true
    }

    @Query("SELECT * FROM artifacts WHERE id = :id")
    suspend fun artifact(id: String): ScanArtifactEntity?

    @Query(
        "UPDATE artifacts SET committed = 1 WHERE id = :id AND projectId = :projectId " +
            "AND projectRevision = :projectRevision AND committed = 0 " +
            "AND EXISTS (SELECT 1 FROM projects AS p WHERE p.id = :projectId " +
            "AND p.revision = :projectRevision)",
    )
    suspend fun commitArtifact(id: String, projectId: String, projectRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEditRecipe(recipe: EditRecipeEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun beginDeletion(journal: DeletionJournalEntity): Long

    @Transaction
    suspend fun beginDeletionForCurrentRevision(journal: DeletionJournalEntity): Boolean {
        if (journal.phaseCode != DeletionPhase.PLANNED || journal.attempt != 0L) return false
        if (projectRevision(journal.projectId) != journal.expectedRevision) return false
        return beginDeletion(journal) != -1L
    }

    @Query("SELECT * FROM deletion_journals WHERE projectId = :projectId")
    suspend fun deletionJournal(projectId: String): DeletionJournalEntity?

    @Query(
        "UPDATE deletion_journals SET phaseCode = :nextPhase, attempt = attempt + 1, " +
            "updatedAt = :updatedAt, lastErrorCode = :lastErrorCode " +
            "WHERE projectId = :projectId AND phaseCode = :expectedPhase AND attempt = :expectedAttempt " +
            "AND ((:expectedPhase = 0 AND :nextPhase IN (1, 5)) " +
            "OR (:expectedPhase = 1 AND :nextPhase IN (2, 5)) " +
            "OR (:expectedPhase = 2 AND :nextPhase IN (3, 5)) " +
            "OR (:expectedPhase = 3 AND :nextPhase IN (4, 5)) " +
            "OR (:expectedPhase = 5 AND :nextPhase = 1))",
    )
    suspend fun advanceDeletion(
        projectId: String,
        expectedPhase: Int,
        expectedAttempt: Long,
        nextPhase: Int,
        updatedAt: Long,
        lastErrorCode: String?,
    ): Int
}

@Database(
    entities = [
        ScanProject::class,
        ScanPhoto::class,
        CaptureSessionEntity::class,
        ReconstructionJobEntity::class,
        ScanArtifactEntity::class,
        EditRecipeEntity::class,
        DeletionJournalEntity::class,
        com.realityforge.scanner.data.jobs.ReconstructionJobEntity::class,
        com.realityforge.scanner.data.jobs.ReconstructionJobEventEntity::class,
        com.realityforge.scanner.data.storagebudget.StorageReservationEntity::class,
        com.realityforge.scanner.data.deletion.DeletionRequestEntity::class,
        com.realityforge.scanner.data.publication.PublicationHeadEntity::class,
        com.realityforge.scanner.data.publication.PublicationCommittedEntity::class,
        com.realityforge.scanner.data.library.ProjectDuplicateReservation::class,
        com.realityforge.scanner.capture.calibration.CalibrationMutationEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = ScanDatabaseV1ToV2::class),
    ],
)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun dao(): ScanDao
    abstract fun durableStateDao(): DurableStateDao
    abstract fun jobDao(): com.realityforge.scanner.data.jobs.ReconstructionJobDao
    abstract fun executionPublicationDao(): com.realityforge.scanner.data.jobs.ExecutionPublicationDao
    abstract fun storageReservationDao(): com.realityforge.scanner.data.storagebudget.StorageReservationDao
    abstract fun deletionRequestDao(): com.realityforge.scanner.data.deletion.DeletionRequestDao
    abstract fun publicationHeadDao(): com.realityforge.scanner.data.publication.PublicationHeadDao
    abstract fun libraryDao(): com.realityforge.scanner.data.library.ProjectLibraryDao
    abstract fun calibrationMutationDao(): com.realityforge.scanner.capture.calibration.CalibrationMutationDao
}
