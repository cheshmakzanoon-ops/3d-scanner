package com.realityforge.scanner.data.importing

import com.realityforge.scanner.capture.metadata.CaptureMetadata
import java.io.File
import java.io.InputStream
import java.util.UUID

data class ImportSource(val uri: String, val captureMetadata: CaptureMetadata? = null,
    val captureSettings: Map<String, String> = emptyMap()) {
    init {
        require(uri.isNotBlank() && uri.length <= 8192)
        CaptureSettingsFields.validate(captureSettings)
        require(captureSettings.isEmpty() ||
            captureMetadata?.captureSources?.contains(com.realityforge.scanner.capture.metadata.CaptureSource.CAMERAX_CAPTURE) == true) {
            "Capture settings require an app camera observation."
        }
    }
}

data class ImportRequest(
    val operationId: String,
    val projectId: String,
    val expectedRevision: Long,
    val sources: List<ImportSource>,
) {
    init {
        requireUuid(operationId); requireUuid(projectId)
        require(expectedRevision >= 0 && sources.size in 1..250)
    }
}

data class ImportBudgets(
    val maxPhotoBytes: Long = 150L * 1024 * 1024,
    val maxBatchBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxPhotos: Int = 250,
    val freeSpaceHeadroomBytes: Long = 512L * 1024 * 1024,
) {
    init {
        require(maxPhotoBytes in 1..150L * 1024 * 1024)
        require(maxBatchBytes >= maxPhotoBytes && maxPhotos in 1..250)
        require(freeSpaceHeadroomBytes >= 0)
    }
}

data class ImportedPhotoInfo(
    val width: Int,
    val height: Int,
    val focal35mm: Double?,
    val sharpness: Double,
    val darkFraction: Double,
    val brightFraction: Double,
    val captureMetadata: CaptureMetadata,
    val captureSettings: Map<String, String> = emptyMap(),
) { init { CaptureSettingsFields.validate(captureSettings) } }

/** Only actual camera-result fields, never arbitrary provider strings, enter the durable record. */
object CaptureSettingsFields {
    private val nonnegativeIntegers = setOf("sensorTimestampNanos", "frameNumber")
    private val positiveIntegers = setOf("exposureTimeNanos", "sensitivityIso")
    private val positiveDecimals = setOf("aperture", "zoomRatio", "focalLengthMm")
    private val signedIntegers = setOf("cropLeft", "cropTop", "cropRight", "cropBottom")
    private val locks = setOf("aeLocked", "awbLocked")
    private val known = nonnegativeIntegers + positiveIntegers + positiveDecimals + signedIntegers + locks +
        setOf("focusDistanceDiopters", "confirmationSource")

    fun validate(fields: Map<String, String>) {
        require(fields.size <= 32)
        fields.forEach { (key, value) ->
            require(key.length <= 64 && value.length <= 256 && key in known)
            val valid = when (key) {
                in nonnegativeIntegers -> value.toLongOrNull()?.let { it >= 0 && it.toString() == value } == true
                in positiveIntegers -> value.toLongOrNull()?.let { it > 0 && it.toString() == value } == true
                in signedIntegers -> value.toIntOrNull()?.toString() == value
                in positiveDecimals -> value.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true
                "focusDistanceDiopters" -> value.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true
                in locks -> value == "true" || value == "false"
                "confirmationSource" -> value == "CAMERA2_CAPTURE_RESULT"
                else -> false
            }
            require(valid) { "Invalid observed camera setting: $key" }
        }
    }
}

data class StagedImport(
    val operationId: String,
    val sourceIndex: Int,
    val projectId: String,
    val expectedRevision: Long,
    val file: File,
    val sha256: String,
    val bytes: Long,
    val metadata: ImportedPhotoInfo,
)

sealed interface ImportPublicationResult {
    data class Imported(val photoId: String, val committedRevision: Long) : ImportPublicationResult
    data class Duplicate(val photoId: String, val revision: Long) : ImportPublicationResult
    /** Existing row is present but its original cannot be verified. No repair/publication occurred,
     * and no journal-backed recovery ownership is implied. Keep the external source for repair. */
    data class OriginalIntegrityFailure(val photoId: String, val message: String, val revision: Long) : ImportPublicationResult
    data class Rejected(val code: String, val message: String, val revision: Long) : ImportPublicationResult
    /** A durable journal owns these bytes, but publication could not be confirmed or rolled back.
     * [revision] is the last confirmed revision, not a claim about the current database state. */
    data class PendingRecovery(val photoId: String, val message: String, val revision: Long) : ImportPublicationResult
}

/** The owner commits file + database under a revision checked publication journal. */
fun interface ImportPublicationPort {
    suspend fun publish(photo: StagedImport): ImportPublicationResult
}

fun interface ImportSourceAccess { fun open(source: ImportSource): InputStream }
fun interface ImportInspector { fun inspect(file: File, source: ImportSource): ImportedPhotoInfo }

data class ImportProgress(
    val operationId: String,
    val projectId: String,
    val sourceIndex: Int,
    val sourceCount: Int,
    val copiedBytes: Long,
)

data class ImportPhotoResult(
    val sourceIndex: Int,
    val uri: String,
    val outcome: ImportPublicationResult,
)

data class ImportBatchResult(
    val operationId: String,
    val projectId: String,
    val initialRevision: Long,
    /** Last confirmed revision; a pending publication must be recovered before another import. */
    val finalRevision: Long,
    val photos: List<ImportPhotoResult>,
) {
    val importedCount: Int get() = photos.count { it.outcome is ImportPublicationResult.Imported }
    val duplicateCount: Int get() = photos.count { it.outcome is ImportPublicationResult.Duplicate }
    val rejectedCount: Int get() = photos.count { it.outcome is ImportPublicationResult.Rejected }
    val integrityFailureCount: Int get() = photos.count { it.outcome is ImportPublicationResult.OriginalIntegrityFailure }
    val pendingCount: Int get() = photos.count { it.outcome is ImportPublicationResult.PendingRecovery }
}

internal fun requireUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "Invalid import identity."
    }
}
