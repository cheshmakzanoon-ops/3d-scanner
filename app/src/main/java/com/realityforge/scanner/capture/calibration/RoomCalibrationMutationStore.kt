package com.realityforge.scanner.capture.calibration

import androidx.room.*
import com.realityforge.scanner.data.ScanProject

@Entity(tableName = "calibration_mutations", indices = [Index("projectId")])
data class CalibrationMutationEntity(
    @PrimaryKey val operationId: String,
    val projectId: String,
    val expectedRevision: Long,
    val kind: String,
    val profileId: String?,
    val targetSha256: String?,
    val committedRevision: Long? = null,
) {
    init {
        CalibrationOperationKey(projectId, expectedRevision, operationId)
        val mutation = CalibrationProfileMutationKind.valueOf(kind)
        if (mutation == CalibrationProfileMutationKind.SAVE) {
            require(profileId?.matches(Regex("cal-v2-[0-9a-f]{64}")) == true)
            requireSha256(requireNotNull(targetSha256))
        } else require(profileId == null && targetSha256 == null)
        require(committedRevision == null || committedRevision == Math.addExact(expectedRevision, 1))
    }

    fun committedResult(): CalibrationProfileStoreResult? = committedRevision?.let {
        if (kind == CalibrationProfileMutationKind.SAVE.name)
            CalibrationProfileStoreResult.Saved(requireNotNull(profileId), it)
        else CalibrationProfileStoreResult.Removed(it)
    }
}

interface CalibrationMutationStore {
    suspend fun prepare(record: CalibrationMutationEntity): CalibrationProfileStoreResult?
    suspend fun load(operationId: String): CalibrationMutationEntity?
    suspend fun pending(projectId: String): List<CalibrationMutationEntity>
    suspend fun commit(operationId: String): CalibrationProfileStoreResult
    suspend fun discardUncommitted(operationId: String)
}

/** Revision change and durable committed receipt are inseparable; previous geometry remains identifiable as stale. */
@Dao
abstract class CalibrationMutationDao {
    @Query("SELECT * FROM calibration_mutations WHERE operationId = :operationId")
    abstract suspend fun load(operationId: String): CalibrationMutationEntity?

    @Query("SELECT * FROM projects WHERE id = :projectId")
    protected abstract suspend fun project(projectId: String): ScanProject?

    @Query("SELECT COUNT(*) FROM calibration_mutations WHERE projectId = :projectId AND committedRevision IS NULL")
    protected abstract suspend fun pendingCount(projectId: String): Int

    @Query("SELECT * FROM calibration_mutations WHERE projectId = :projectId AND committedRevision IS NULL")
    abstract suspend fun pending(projectId: String): List<CalibrationMutationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(record: CalibrationMutationEntity)

    @Query("UPDATE projects SET revision = revision + 1, state = 'READY', message = 'Lens profile changed; previous results belong to the earlier optical model.' WHERE id = :projectId AND revision = :expectedRevision AND state NOT IN ('DELETING', 'DELETING_PHOTO')")
    protected abstract suspend fun advance(projectId: String, expectedRevision: Long): Int

    @Query("UPDATE calibration_mutations SET committedRevision = :revision WHERE operationId = :operationId AND committedRevision IS NULL")
    protected abstract suspend fun markCommitted(operationId: String, revision: Long): Int

    @Query("DELETE FROM calibration_mutations WHERE operationId = :operationId AND committedRevision IS NULL")
    abstract suspend fun discardUncommitted(operationId: String)

    @Transaction
    open suspend fun prepare(record: CalibrationMutationEntity): CalibrationProfileStoreResult? {
        require(record.committedRevision == null)
        load(record.operationId)?.let {
            require(it.copy(committedRevision = null) == record) { "Calibration operation identity was reused." }
            return it.committedResult()
        }
        val project = project(record.projectId)
        if (project?.revision != record.expectedRevision)
            return CalibrationProfileStoreResult.Stale(record.expectedRevision, project?.revision)
        if (project.state == "DELETING" || project.state == "DELETING_PHOTO")
            return CalibrationProfileStoreResult.Rejected("PROJECT_DELETING", "Finish pending project cleanup before changing calibration.")
        check(pendingCount(record.projectId) == 0) { "A previous calibration change needs recovery before another can start." }
        insert(record)
        return null
    }

    @Transaction
    open suspend fun commit(operationId: String): CalibrationProfileStoreResult {
        val record = requireNotNull(load(operationId)) { "Calibration mutation record is missing." }
        record.committedResult()?.let { return it }
        val project = project(record.projectId)
        if (project?.revision != record.expectedRevision)
            return CalibrationProfileStoreResult.Stale(record.expectedRevision, project?.revision)
        if (project.state == "DELETING" || project.state == "DELETING_PHOTO")
            return CalibrationProfileStoreResult.Rejected("PROJECT_DELETING", "Project deletion began before calibration could be committed.")
        val revision = Math.addExact(record.expectedRevision, 1)
        check(advance(record.projectId, record.expectedRevision) == 1)
        check(markCommitted(operationId, revision) == 1)
        return requireNotNull(record.copy(committedRevision = revision).committedResult())
    }
}

class RoomCalibrationMutationStore(private val dao: CalibrationMutationDao) : CalibrationMutationStore {
    override suspend fun prepare(record: CalibrationMutationEntity) = dao.prepare(record)
    override suspend fun load(operationId: String) = dao.load(operationId)
    override suspend fun pending(projectId: String) = dao.pending(projectId)
    override suspend fun commit(operationId: String) = dao.commit(operationId)
    override suspend fun discardUncommitted(operationId: String) = dao.discardUncommitted(operationId)
}
