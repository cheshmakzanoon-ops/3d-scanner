package com.realityforge.scanner.data.storagebudget

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Entity(tableName = "storage_reservations", indices = [Index("projectId")])
data class StorageReservationEntity(
    @PrimaryKey val operationId: String,
    val projectId: String,
    val revision: Long,
    val remainingBytes: Long,
    val updatedAt: Long,
)

/** Admission, replacement and recovery run in Room transactions, including competing callers. */
@Dao
abstract class StorageReservationDao {
    @Query("SELECT * FROM storage_reservations")
    abstract suspend fun all(): List<StorageReservationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun put(entity: StorageReservationEntity)

    @Query("DELETE FROM storage_reservations WHERE operationId = :operationId AND projectId = :projectId AND revision = :revision")
    abstract suspend fun release(operationId: String, projectId: String, revision: Long): Int

    @Transaction
    open suspend fun reserve(token: ReservationToken, remainingBytes: Long, availableBytes: Long?,
        reclaimableBytes: Long, now: Long): SpaceDecision {
        require(now >= 0)
        val rows = all()
        check(rows.size < 1000 || rows.any { it.operationId == token.operationId }) { "Too many active storage reservations." }
        rows.firstOrNull { it.operationId == token.operationId }?.let {
            check(it.projectId == token.projectId && it.revision == token.revision) { "Storage reservation identity changed." }
        }
        val decision = StorageAccounting.decide(remainingBytes, availableBytes,
            rows.filter { it.operationId != token.operationId }.map { it.remainingBytes }, reclaimableBytes)
        if (decision.admitted) put(StorageReservationEntity(token.operationId, token.projectId,
            token.revision, remainingBytes, now))
        return decision
    }

    /** Caller supplies authoritative running AND resumable IDs after job recovery; no timeout guess. */
    @Transaction
    open suspend fun recover(retainOperationIds: Set<String>): Int {
        var removed = 0
        for (row in all()) if (row.operationId !in retainOperationIds)
            removed += release(row.operationId, row.projectId, row.revision)
        return removed
    }
}

data class StorageBudgetFailure(val token: ReservationToken, val decision: SpaceDecision)

class StorageBudgetService(
    private val dao: StorageReservationDao,
    private val probe: SpaceProbe,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableFailure = MutableStateFlow<StorageBudgetFailure?>(null)
    /** Exact last rejected measurement for the storage screen; never substitute unknown space with zero. */
    val failure: StateFlow<StorageBudgetFailure?> = mutableFailure.asStateFlow()
    suspend fun reserve(token: ReservationToken, estimate: StorageEstimate, reclaimableBytes: Long = 0): SpaceDecision =
        recheck(token, estimate.additionalPeakBytes, reclaimableBytes)

    /** Remaining additional peak decreases after writes; measured free space already accounts for written data. */
    suspend fun recheck(token: ReservationToken, remainingBytes: Long, reclaimableBytes: Long = 0): SpaceDecision {
        val decision = dao.reserve(token, remainingBytes, probe.availableBytes(), reclaimableBytes, clock())
        if (!decision.admitted) mutableFailure.value = StorageBudgetFailure(token, decision)
        else mutableFailure.value?.takeIf { it.token == token }?.let { mutableFailure.compareAndSet(it, null) }
        return decision
    }

    suspend fun requireReservation(token: ReservationToken, estimate: StorageEstimate) {
        val decision = reserve(token, estimate)
        if (!decision.admitted) throw InsufficientStorageException(decision)
    }

    suspend fun release(token: ReservationToken) = dao.release(token.operationId, token.projectId, token.revision)
    suspend fun recover(retainOperationIds: Set<String>) = dao.recover(retainOperationIds)
}
