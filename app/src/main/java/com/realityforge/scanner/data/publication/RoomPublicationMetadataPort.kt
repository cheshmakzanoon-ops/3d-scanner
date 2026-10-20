package com.realityforge.scanner.data.publication

import androidx.room.*
import com.realityforge.scanner.data.ScanProject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.concurrent.Callable

@Entity(tableName = "publication_heads")
data class PublicationHeadEntity(
    @PrimaryKey val projectId: String,
    val metadataVersion: Long,
    val currentPointer: String?,
    val previousPointer: String?,
)

@Entity(tableName = "publication_commits", primaryKeys = ["projectId", "setId"], indices = [Index("projectId")])
data class PublicationCommittedEntity(val projectId: String, val setId: String, val pointer: String)

@Dao
abstract class PublicationHeadDao {
    @Query("SELECT * FROM publication_heads WHERE projectId = :projectId")
    abstract fun head(projectId: String): PublicationHeadEntity?

    @Query("SELECT * FROM projects WHERE id = :projectId")
    abstract fun project(projectId: String): ScanProject?

    @Query("SELECT * FROM publication_commits WHERE projectId = :projectId AND setId = :setId")
    abstract fun committed(projectId: String, setId: String): PublicationCommittedEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun initialize(head: PublicationHeadEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun addCommitted(commit: PublicationCommittedEntity)

    @Query("UPDATE publication_heads SET metadataVersion = :nextVersion, currentPointer = :currentPointer, previousPointer = :previousPointer WHERE projectId = :projectId AND metadataVersion = :expectedVersion")
    abstract fun compareAndSet(projectId: String, expectedVersion: Long, nextVersion: Long,
        currentPointer: String?, previousPointer: String?): Int
}

data class ActivePublicationOperation(val operationId: String, val attempt: Long, val state: DurableOperationState)

/** Reader executes synchronously on the current Room transaction thread, including delivery-time attempt/state checks. */
fun interface PublicationOperationReader { fun current(projectId: String): ActivePublicationOperation? }

/** Heads and committed-set receipts are changed in the same Room transaction. Files have already been promoted and verified. */
class RoomPublicationMetadataPort(
    private val database: RoomDatabase,
    private val heads: PublicationHeadDao,
    private val operations: PublicationOperationReader,
    private val onResultCommitted: ((PublicationCommitRequest) -> Unit)? = null,
    private val onOriginalImportCommitted: ((PublicationCommitRequest) -> Boolean)? = null,
) : PublicationMetadataPort {
    override fun snapshot(projectId: String): ProjectPublicationSnapshot? = database.runInTransaction(Callable {
        snapshotInTransaction(projectId)
    })

    private fun snapshotInTransaction(projectId: String): ProjectPublicationSnapshot? {
        val project = heads.project(projectId) ?: return null
        val head = heads.head(projectId)
        val operation = operations.current(projectId)
        return ProjectPublicationSnapshot(projectId, project.revision,
            project.state == "DELETING" || project.state == "DELETING_PHOTO",
            operation?.operationId, operation?.attempt, operation?.state,
            head?.currentPointer?.let(PublicationPointerCodec::decode),
            head?.previousPointer?.let(PublicationPointerCodec::decode), head?.metadataVersion ?: 0)
    }

    override fun committedPointer(projectId: String, setId: String): PublicationPointer? =
        heads.committed(projectId, setId)?.let { PublicationPointerCodec.decode(it.pointer) }

    override fun commit(request: PublicationCommitRequest): PublicationCommitResult = database.runInTransaction(Callable {
        val identity = request.identity
        val pointer = request.pointer
        require(pointer.projectId == identity.projectId && pointer.projectRevision == identity.projectRevision &&
            pointer.operationId == identity.operationId && pointer.attempt == identity.attempt && pointer.purpose == identity.purpose)
        require(pointer.relativeDirectory == "sets/${identity.projectId}/${pointer.setId}")
        val previousCommit = committedPointer(identity.projectId, pointer.setId)
        if (previousCommit != null) {
            require(previousCommit == pointer) { "An artifact-set identity was reused for a different pointer." }
            return@Callable PublicationCommitResult.AlreadyCommitted(previousCommit, heads.head(identity.projectId)?.metadataVersion ?: 0)
        }
        val current = snapshotInTransaction(identity.projectId)
            ?: return@Callable PublicationCommitResult.Rejected(PublicationRejection.PROJECT_MISSING)
        val rejection = when {
            current.deleted -> PublicationRejection.PROJECT_DELETED
            current.projectRevision != identity.projectRevision -> PublicationRejection.REVISION_CHANGED
            current.activeOperationId != identity.operationId -> PublicationRejection.OPERATION_CHANGED
            current.activeAttempt != identity.attempt -> PublicationRejection.ATTEMPT_CHANGED
            current.activeOperationState != DurableOperationState.RUNNING -> PublicationRejection.OPERATION_NOT_RUNNING
            current.metadataVersion != request.expectedMetadataVersion -> PublicationRejection.METADATA_VERSION_CHANGED
            current.currentResult?.setId != request.expectedCurrentResultSetId -> PublicationRejection.CURRENT_RESULT_CHANGED
            identity.purpose == PublicationPurpose.ORIGINAL_IMPORT && onOriginalImportCommitted == null -> PublicationRejection.PURPOSE_UNSUPPORTED
            else -> null
        }
        if (rejection != null) return@Callable PublicationCommitResult.Rejected(rejection)
        heads.initialize(PublicationHeadEntity(identity.projectId, 0, null, null))
        val nextVersion = Math.addExact(current.metadataVersion, 1)
        val nextCurrent = if (identity.purpose == PublicationPurpose.RESULT_SET) pointer else current.currentResult
        val nextPrevious = if (identity.purpose == PublicationPurpose.RESULT_SET) current.currentResult else current.previousCompletedResult
        if (identity.purpose == PublicationPurpose.ORIGINAL_IMPORT)
            check(requireNotNull(onOriginalImportCommitted).invoke(request)) { "Original-import metadata was not committed." }
        else onResultCommitted?.invoke(request)
        check(heads.compareAndSet(identity.projectId, current.metadataVersion, nextVersion,
            nextCurrent?.let(PublicationPointerCodec::encode), nextPrevious?.let(PublicationPointerCodec::encode)) == 1)
        heads.addCommitted(PublicationCommittedEntity(identity.projectId, pointer.setId, PublicationPointerCodec.encode(pointer)))
        PublicationCommitResult.Committed(pointer, nextCurrent, nextPrevious, nextVersion)
    })
}

internal object PublicationPointerCodec {
    fun encode(pointer: PublicationPointer): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeUTF(pointer.purpose.name); out.writeUTF(pointer.projectId)
            out.writeLong(pointer.projectRevision); out.writeUTF(pointer.operationId); out.writeLong(pointer.attempt)
            out.writeUTF(pointer.setId); out.writeUTF(pointer.manifestSha256); out.writeUTF(pointer.relativeDirectory)
            out.writeLong(pointer.publishedAtEpochMillis)
            with(pointer.durability) {
                out.writeBoolean(artifactContentsSynced); out.writeBoolean(manifestSynced); out.writeBoolean(atomicDirectoryPromotion)
                out.writeBoolean(stagingDirectorySynced); out.writeBoolean(destinationDirectorySynced)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String): PublicationPointer {
        require(encoded.length in 1..4096) { "Stored publication pointer exceeds the supported size." }
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            require(input.readInt() == 1) { "Unsupported publication-pointer schema." }
            val pointer = PublicationPointer(PublicationPurpose.valueOf(input.readUTF()), input.readUTF(), input.readLong(),
                input.readUTF(), input.readLong(), input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(),
                PublicationDurability(input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean()))
            require(input.read() == -1) { "Unexpected data after publication pointer." }
            pointer
        }
    }
}
