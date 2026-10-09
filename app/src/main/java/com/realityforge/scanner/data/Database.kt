package com.realityforge.scanner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ScanProject(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val revision: Long = 0,
    val state: String = "NEW",
    val message: String = "Add photographs to begin.",
    val resultRevision: Long? = null,
    val resultKey: String? = null,
)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(entity = ScanProject::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["projectId"]), Index(value = ["projectId", "sha256"], unique = true)],
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
)

@Dao
interface ScanDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC") fun observeProjects(): Flow<List<ScanProject>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun project(id: String): ScanProject?
    @Query("SELECT * FROM photos WHERE projectId = :id ORDER BY addedAt, id") suspend fun photos(id: String): List<ScanPhoto>
    @Query("SELECT * FROM photos WHERE projectId = :id ORDER BY addedAt, id") fun observePhotos(id: String): Flow<List<ScanPhoto>>
    @Query("SELECT COUNT(*) FROM photos WHERE projectId = :id AND sha256 = :hash") suspend fun duplicate(id: String, hash: String): Int
    @Insert suspend fun insert(project: ScanProject)
    @Insert suspend fun insert(photo: ScanPhoto)
    @Query("UPDATE projects SET revision = revision + 1, state = 'READY', resultRevision = NULL, resultKey = NULL, message = 'Photos changed. Previous results are no longer current.' WHERE id = :id") suspend fun invalidate(id: String)
    @Transaction suspend fun addPhoto(photo: ScanPhoto) { insert(photo); invalidate(photo.projectId) }
    @Query("UPDATE projects SET state = :state, message = :message, resultRevision = CASE WHEN :state = 'PROCESSING' THEN NULL ELSE resultRevision END, resultKey = CASE WHEN :state = 'PROCESSING' THEN NULL ELSE resultKey END WHERE id = :id AND revision = :revision")
    suspend fun state(id: String, revision: Long, state: String, message: String): Int
    @Query("UPDATE projects SET state = 'SPARSE_READY', resultRevision = :revision, resultKey = :resultKey, message = :message WHERE id = :id AND revision = :revision")
    suspend fun complete(id: String, revision: Long, resultKey: String, message: String): Int
    @Query("UPDATE projects SET state = 'INTERRUPTED', message = 'Previous session interrupted. Originals and completed artifacts are safe; rerun preview.' WHERE state = 'PROCESSING'")
    suspend fun recoverInterrupted()
    @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteProject(id: String)
}

@Database(entities = [ScanProject::class, ScanPhoto::class], version = 1, exportSchema = true)
abstract class ScanDatabase : RoomDatabase() { abstract fun dao(): ScanDao }
