package com.realityforge.scanner.data

import com.realityforge.scanner.data.operations.ProjectAccessSession
import com.realityforge.scanner.data.operations.ProjectOperationRequest

/**
 * Stable project boundary consumed by application and pipeline owners.
 *
 * Callers bind an operation to a concrete project, revision and operation ID before
 * suspending. Reconstruction and publication requests also carry a durable job attempt.
 * The repository validates that identity after obtaining the project reservation.
 */
interface ProjectAccess {
    /** Convenience read that acquires and releases its own READ_INPUTS reservation. */
    suspend fun inputSnapshot(projectId: String): ProjectInputSnapshot

    /** Read inputs through an already-held stable-revision reservation without nesting a lease. */
    suspend fun inputSnapshot(session: ProjectAccessSession): ProjectInputSnapshot

    /** Convenience read that acquires and releases its own READ_CURRENT_RESULT reservation. */
    suspend fun currentResult(projectId: String): ProjectCurrentResult?

    /** Read the current result through an already-held stable-revision reservation. */
    suspend fun currentResult(session: ProjectAccessSession): ProjectCurrentResult?

    suspend fun <T> withProjectAccess(
        request: ProjectOperationRequest,
        block: suspend (ProjectAccessSession) -> T,
    ): T
}

data class ProjectInputPhoto(
    val id: String,
    val sha256: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val focal35mm: Double?,
    val addedAt: Long,
    val metadataSha256: String = InputPhotoIdentity.metadataSha256(null, null, null),
)

data class ProjectInputSnapshot(
    val projectId: String,
    val revision: Long,
    val photos: List<ProjectInputPhoto>,
)

data class ProjectCurrentResult(
    val projectId: String,
    val revision: Long,
    val artifactKey: String,
    val diagnosticJson: ProjectArtifactReference,
)

data class ProjectArtifactReference(
    val projectId: String,
    val revision: Long,
    val artifactKey: String,
    val extension: String,
    val absolutePath: String,
)

/** Length-delimited optical/still metadata is part of the input identity even when JPEG bytes match. */
object InputPhotoIdentity {
    fun metadataSha256(captureMetadataRecord: String?, captureSettingsRecord: String?, focal35mm: Double?): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        listOf("capture-metadata-v1", captureMetadataRecord, captureSettingsRecord,
            focal35mm?.toBits()?.toString()).forEach { value ->
            if (value == null) digest.update(java.nio.ByteBuffer.allocate(4).putInt(-1).array()) else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 128 * 1024) { "Stored capture metadata exceeds its bound." }
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }

    fun snapshotSha256(input: ProjectInputSnapshot): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        input.photos.forEach { photo ->
            listOf(photo.id, photo.sha256, photo.bytes.toString(), photo.width.toString(), photo.height.toString(),
                photo.metadataSha256).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
}
