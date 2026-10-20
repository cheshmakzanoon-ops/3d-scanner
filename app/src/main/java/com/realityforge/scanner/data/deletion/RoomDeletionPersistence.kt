package com.realityforge.scanner.data.deletion

import androidx.room.*
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.curation.CuratedPhotoState
import com.realityforge.scanner.data.curation.PhotoCurationRecordCodec
import com.realityforge.scanner.data.reopening.BoundedJson
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "deletion_requests", indices = [Index("projectId"), Index("completed")])
data class DeletionRequestEntity(
    @PrimaryKey val operationId: String,
    val projectId: String,
    val stateVersion: Long,
    val completed: Boolean,
    val payload: String,
)

@Dao
abstract class DeletionRequestDao {
    @Query("SELECT * FROM deletion_requests WHERE operationId = :id")
    abstract suspend fun load(id: String): DeletionRequestEntity?

    @Query("SELECT * FROM deletion_requests WHERE completed = 0 ORDER BY operationId LIMIT :limit")
    abstract suspend fun recoverable(limit: Int): List<DeletionRequestEntity>

    @Query("SELECT * FROM deletion_requests WHERE projectId = :projectId AND completed = 0 LIMIT 1")
    abstract suspend fun pendingProject(projectId: String): DeletionRequestEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    protected abstract suspend fun project(id: String): ScanProject?

    @Query("SELECT * FROM photos WHERE projectId = :projectId AND id = :photoId")
    protected abstract suspend fun photo(projectId: String, photoId: String): ScanPhoto?

    @Query("SELECT * FROM photos WHERE projectId = :projectId ORDER BY COALESCE(selectionOrder, addedAt), addedAt, id")
    protected abstract suspend fun photos(projectId: String): List<ScanPhoto>

    @Query("UPDATE photos SET selectionOrder = :position, curationRecord = :record WHERE projectId = :projectId AND id = :photoId")
    protected abstract suspend fun reindex(projectId: String, photoId: String, position: Long, record: String?): Int

    @Query("SELECT COUNT(*) FROM photos WHERE projectId = :projectId AND filename = :filename AND id != :photoId")
    protected abstract suspend fun otherReferences(projectId: String, filename: String, photoId: String): Int

    @Query("UPDATE projects SET state = :state, message = 'Deletion pending; local cleanup can be retried.' WHERE id = :id AND revision = :revision")
    protected abstract suspend fun markDeleting(id: String, revision: Long, state: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(request: DeletionRequestEntity)

    @Query("UPDATE deletion_requests SET stateVersion = :nextVersion, completed = :completed, payload = :payload WHERE operationId = :id AND stateVersion = :expectedVersion")
    protected abstract suspend fun update(id: String, expectedVersion: Long, nextVersion: Long, completed: Boolean, payload: String): Int

    @Query("DELETE FROM projects WHERE id = :id AND revision = :expectedRevision AND state = 'DELETING'")
    protected abstract suspend fun removeProject(id: String, expectedRevision: Long): Int

    @Query("DELETE FROM photos WHERE projectId = :projectId AND id = :photoId")
    protected abstract suspend fun removePhoto(projectId: String, photoId: String): Int

    @Query("UPDATE projects SET revision = revision + 1, curationVersion = curationVersion + 1, state = 'READY', message = 'Photograph removed; previous results belong to the earlier retained originals.' WHERE id = :id AND revision = :expectedRevision AND state = 'DELETING_PHOTO'")
    protected abstract suspend fun photoRemoved(id: String, expectedRevision: Long): Int

    @Transaction
    open suspend fun stage(request: ConfirmedDeletionRequest): PendingDeletion {
        load(request.operationId)?.let { existing ->
            return DeletionPayload.decode(existing).also {
                require(it.scope == request.scope && it.expectedRevision == request.expectedRevision &&
                    it.expectedOriginalSha256 == request.expectedOriginalSha256 &&
                    it.expectedRemovalRequestId == request.expectedRemovalRequestId) { "Deletion operation identity changed." }
            }
        }
        check(pendingProject(request.scope.projectId) == null) { "A previous deletion needs to be retried first." }
        val project = requireNotNull(project(request.scope.projectId)) { "Project no longer exists." }
        check(project.revision == request.expectedRevision) { "Project changed before deletion confirmation." }
        val retained = linkedSetOf<String>()
        val targets = when (val scope = request.scope) {
            is DeletionScope.Project -> listOf(OwnedDeletionTarget.ProjectTree(scope.projectId))
            is DeletionScope.Photo -> {
                val photo = requireNotNull(photo(scope.projectId, scope.photoId)) { "Photograph no longer exists." }
                verifyPhotoProof(photo, request.expectedOriginalSha256, request.expectedRemovalRequestId)
                check(photo.filename == "${scope.photoId}.image") { "Original path does not match its owner." }
                if (otherReferences(scope.projectId, photo.filename, scope.photoId) > 0) {
                    retained += "shared-original:${scope.photoId}"
                    emptyList()
                } else listOf(OwnedDeletionTarget.ProjectRelativeNode(scope.projectId, "originals/${photo.filename}"))
            }
        }
        check(markDeleting(project.id, project.revision,
            if (request.scope is DeletionScope.Project) "DELETING" else "DELETING_PHOTO") == 1)
        val pending = PendingDeletion(request.operationId, request.scope, request.expectedRevision,
            request.confirmedAtEpochMillis, DeletionPhase.TOMBSTONED, 0, targets, retainedReferenceIds = retained,
            updatedAtEpochMillis = request.confirmedAtEpochMillis,
            expectedOriginalSha256 = request.expectedOriginalSha256, expectedRemovalRequestId = request.expectedRemovalRequestId)
        insert(DeletionPayload.entity(pending))
        return pending
    }

    @Transaction
    open suspend fun transition(id: String, expectedVersion: Long, transition: DeletionTransition): PendingDeletion? {
        val current = load(id)?.let(DeletionPayload::decode) ?: return null
        if (current.stateVersion != expectedVersion) return null
        val next = DeletionStateReducer.apply(current, transition)
        check(update(id, expectedVersion, next.stateVersion, false, DeletionPayload.encode(next)) == 1)
        return next
    }

    @Transaction
    open suspend fun finalize(id: String, expectedVersion: Long, at: Long): PendingDeletion? {
        val current = load(id)?.let(DeletionPayload::decode) ?: return null
        if (current.stateVersion != expectedVersion) return null
        val complete = DeletionStateReducer.complete(current, at)
        when (val scope = current.scope) {
            is DeletionScope.Project -> check(removeProject(scope.projectId, current.expectedRevision) == 1) {
                "Project changed before deletion finalization."
            }
            is DeletionScope.Photo -> {
                val removing = requireNotNull(photo(scope.projectId, scope.photoId))
                verifyPhotoProof(removing, current.expectedOriginalSha256, current.expectedRemovalRequestId)
                check(removePhoto(scope.projectId, scope.photoId) == 1)
                for ((position, row) in photos(scope.projectId).withIndex()) {
                    val encoded = row.curationRecord?.let { text ->
                        val decoded = PhotoCurationRecordCodec.decode(text)
                        require(decoded.id == row.id && decoded.sha256 == row.sha256 &&
                            decoded.position.toLong() == row.selectionOrder &&
                            (decoded.state == CuratedPhotoState.INCLUDED) == row.included)
                        PhotoCurationRecordCodec.encode(decoded.copy(position = position))
                    }
                    check(reindex(scope.projectId, row.id, position.toLong(), encoded) == 1)
                }
                check(photoRemoved(scope.projectId, current.expectedRevision) == 1)
            }
        }
        check(update(id, expectedVersion, complete.stateVersion, true, DeletionPayload.encode(complete)) == 1)
        return complete
    }

    private fun verifyPhotoProof(photo: ScanPhoto, sha256: String?, requestId: String?) {
        if (sha256 != null) require(photo.sha256 == sha256) { "Original identity changed before deletion." }
        if (requestId != null) {
            val record = PhotoCurationRecordCodec.decode(requireNotNull(photo.curationRecord))
            require(record.id == photo.id && record.sha256 == photo.sha256 &&
                record.state == CuratedPhotoState.REMOVAL_PENDING && record.pendingRemoval?.requestId == requestId) {
                "Original-removal confirmation no longer matches the pending curation request."
            }
        }
    }
}

class RoomDeletionPersistence(private val dao: DeletionRequestDao) : DeletionPersistence {
    override suspend fun stageConfirmed(request: ConfirmedDeletionRequest) = dao.stage(request)
    override suspend fun load(operationId: String) = dao.load(operationId)?.let(DeletionPayload::decode)
    override suspend fun loadRecoverable(limit: Int) = dao.recoverable(limit).map(DeletionPayload::decode)
    override suspend fun transition(operationId: String, expectedStateVersion: Long, transition: DeletionTransition) =
        dao.transition(operationId, expectedStateVersion, transition)
    override suspend fun finalizeDeletion(operationId: String, expectedStateVersion: Long, completedAtEpochMillis: Long) =
        dao.finalize(operationId, expectedStateVersion, completedAtEpochMillis)
    suspend fun pendingProject(projectId: String) = dao.pendingProject(projectId)?.let(DeletionPayload::decode)
}

internal object DeletionPayload {
    fun entity(value: PendingDeletion) = DeletionRequestEntity(value.operationId, value.scope.projectId,
        value.stateVersion, value.phase == DeletionPhase.COMPLETED, encode(value))

    fun encode(value: PendingDeletion): String = JSONObject().put("schema", 2)
        .put("operationId", value.operationId).put("projectId", value.scope.projectId)
        .put("photoId", (value.scope as? DeletionScope.Photo)?.photoId ?: JSONObject.NULL)
        .put("expectedRevision", value.expectedRevision).put("confirmedAt", value.confirmedAtEpochMillis)
        .put("expectedOriginalSha256", value.expectedOriginalSha256 ?: JSONObject.NULL)
        .put("expectedRemovalRequestId", value.expectedRemovalRequestId ?: JSONObject.NULL)
        .put("phase", value.phase.name).put("stateVersion", value.stateVersion)
        .put("targets", JSONArray(value.targets.map { when (it) {
            is OwnedDeletionTarget.ProjectTree -> JSONObject().put("tree", true)
            is OwnedDeletionTarget.ProjectRelativeNode -> JSONObject().put("path", it.relativePath)
        } })).put("retained", JSONArray(value.retainedReferenceIds.toList()))
        .put("attempts", value.attempts).put("entriesRemoved", value.entriesRemoved)
        .put("bytesReclaimed", value.bytesReclaimed).put("entriesVisited", value.entriesVisited)
        .put("lastPath", value.lastRelativePath ?: JSONObject.NULL)
        .put("blockers", JSONArray(value.blockers.map { it.name }))
        .put("failure", value.failure?.let { JSONObject().put("code", it.code.name).put("detail", it.detail) } ?: JSONObject.NULL)
        .put("updatedAt", value.updatedAtEpochMillis).put("completedAt", value.completedAtEpochMillis ?: JSONObject.NULL)
        .toString().also { require(it.toByteArray().size <= 4 * 1024 * 1024) }

    fun decode(entity: DeletionRequestEntity): PendingDeletion {
        val json = BoundedJson.read(entity.payload.byteInputStream(), 4 * 1024 * 1024)
        val schema = number(json, "schema")
        require(schema in 1..2)
        val id = json.getString("projectId")
        val scope = if (json.isNull("photoId")) DeletionScope.Project(id) else DeletionScope.Photo(id, json.getString("photoId"))
        val targets = json.getJSONArray("targets").let { rows ->
            require(rows.length() <= PendingDeletion.MAX_TARGETS)
            List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            if (row.has("tree")) { require(row.get("tree") == true); OwnedDeletionTarget.ProjectTree(id) }
            else OwnedDeletionTarget.ProjectRelativeNode(id, row.getString("path"))
        } }
        val failure = if (json.isNull("failure")) null else json.getJSONObject("failure").let {
            DeletionFailure(DeletionFailureCode.valueOf(it.getString("code")), it.getString("detail"))
        }
        val attempts = number(json, "attempts").also { require(it in 0..Int.MAX_VALUE) }.toInt()
        return PendingDeletion(json.getString("operationId"), scope, number(json, "expectedRevision"),
            number(json, "confirmedAt"), DeletionPhase.valueOf(json.getString("phase")), number(json, "stateVersion"),
            targets, strings(json.getJSONArray("retained")).toSet(), attempts, number(json, "entriesRemoved"),
            number(json, "bytesReclaimed"), number(json, "entriesVisited"),
            if (json.isNull("lastPath")) null else json.getString("lastPath"),
            strings(json.getJSONArray("blockers")).map(DeletionBlocker::valueOf).toSet(), failure, number(json, "updatedAt"),
            if (json.isNull("completedAt")) null else number(json, "completedAt"),
            if (schema == 1L || json.isNull("expectedOriginalSha256")) null else json.getString("expectedOriginalSha256"),
            if (schema == 1L || json.isNull("expectedRemovalRequestId")) null else json.getString("expectedRemovalRequestId")).also {
            require(it.operationId == entity.operationId && it.scope.projectId == entity.projectId &&
                it.stateVersion == entity.stateVersion && (it.phase == DeletionPhase.COMPLETED) == entity.completed)
        }
    }
    private fun strings(rows: JSONArray): List<String> {
        require(rows.length() <= PendingDeletion.MAX_TARGETS)
        return List(rows.length()) { (rows.get(it) as? String)?.also { value -> require(value.length <= 1024) }
            ?: error("Deletion references must be strings.") }
    }
    private fun number(json: JSONObject, key: String): Long {
        val raw = json.get(key)
        require(raw is Int || raw is Long) { "Deletion counters must be integers." }
        return (raw as Number).toLong()
    }
}
