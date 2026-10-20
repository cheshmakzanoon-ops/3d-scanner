package com.realityforge.scanner.domain

import java.util.Base64

/**
 * Contract C1: immutable identity for every operation that can outlive its initiating UI event.
 *
 * Project, revision and request identity are captured before an activity-result launcher or a
 * coroutine can suspend. A completion callback must consume the matching request; it must never
 * infer its destination from the project's later UI selection.
 */
data class OperationId(val value: String) {
    init { require(value.isNotBlank()) { "Operation ID cannot be blank." } }
}

data class RequestId(val value: String) {
    init { require(value.isNotBlank()) { "Request ID cannot be blank." } }
}

data class SessionId(val value: String) {
    init { require(value.isNotBlank()) { "Session ID cannot be blank." } }
}

data class JobId(val value: String) {
    init { require(value.isNotBlank()) { "Job ID cannot be blank." } }
}

data class ProjectRevision(
    val projectId: String,
    val revision: Long,
) {
    init {
        require(projectId.isNotBlank()) { "Project ID cannot be blank." }
        require(revision >= 0L) { "Project revision cannot be negative." }
    }
}

enum class OperationKind {
    CREATE_PROJECT,
    IMPORT_PHOTOS,
    CAPTURE_PHOTO,
    CALIBRATE_LENS,
    CLEAR_CALIBRATION,
    RECONSTRUCT,
    DELETE_PROJECT,
    EXPORT_CAPTURE_BUNDLE,
    EXPORT_ARTIFACT,
}

data class OperationContext(
    val operationId: OperationId,
    val jobId: JobId,
    val kind: OperationKind,
    val source: ProjectRevision?,
    val requestId: RequestId? = null,
    val sessionId: SessionId? = null,
    val selectionGeneration: Long,
    val startedAtEpochMillis: Long,
)

sealed class BoundExternalRequest {
    abstract val requestId: RequestId
    abstract val project: ProjectRevision
    abstract val selectionGeneration: Long
    abstract val createdAtEpochMillis: Long
    abstract val kind: OperationKind
    open val sessionId: SessionId? = null

    data class ImportPhotos(
        override val requestId: RequestId,
        override val project: ProjectRevision,
        override val selectionGeneration: Long,
        override val createdAtEpochMillis: Long,
    ) : BoundExternalRequest() {
        override val kind: OperationKind = OperationKind.IMPORT_PHOTOS
    }

    data class CapturePhoto(
        override val requestId: RequestId,
        override val project: ProjectRevision,
        override val selectionGeneration: Long,
        override val createdAtEpochMillis: Long,
        override val sessionId: SessionId,
    ) : BoundExternalRequest() {
        override val kind: OperationKind = OperationKind.CAPTURE_PHOTO
    }

    data class CalibrateLens(
        override val requestId: RequestId,
        override val project: ProjectRevision,
        override val selectionGeneration: Long,
        override val createdAtEpochMillis: Long,
    ) : BoundExternalRequest() {
        override val kind: OperationKind = OperationKind.CALIBRATE_LENS
    }

    data class CaptureBundleExport(
        override val requestId: RequestId,
        override val project: ProjectRevision,
        override val selectionGeneration: Long,
        override val createdAtEpochMillis: Long,
    ) : BoundExternalRequest() {
        override val kind: OperationKind = OperationKind.EXPORT_CAPTURE_BUNDLE
    }

    data class ArtifactExport(
        override val requestId: RequestId,
        override val project: ProjectRevision,
        override val selectionGeneration: Long,
        override val createdAtEpochMillis: Long,
        val artifactKey: String,
        val resultRevision: Long,
        val extension: String,
    ) : BoundExternalRequest() {
        override val kind: OperationKind = OperationKind.EXPORT_ARTIFACT

        init {
            require(artifactKey.isNotBlank()) { "Artifact key cannot be blank." }
            require(resultRevision >= 0L) { "Result revision cannot be negative." }
            require(extension.isNotBlank()) { "Export extension cannot be blank." }
        }
    }
}

/** Versioned, Android-free persistence for pending activity-result identity. */
object BoundExternalRequestCodec {
    private const val VERSION = "1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(request: BoundExternalRequest): String {
        val fields = when (request) {
            is BoundExternalRequest.ImportPhotos -> listOf(
                VERSION, "IMPORT", request.requestId.value, request.project.projectId,
                request.project.revision.toString(), request.selectionGeneration.toString(),
                request.createdAtEpochMillis.toString(), "", "", "", "",
            )
            is BoundExternalRequest.CapturePhoto -> listOf(
                VERSION, "CAPTURE", request.requestId.value, request.project.projectId,
                request.project.revision.toString(), request.selectionGeneration.toString(),
                request.createdAtEpochMillis.toString(), request.sessionId.value, "", "", "",
            )
            is BoundExternalRequest.CalibrateLens -> listOf(
                VERSION, "CALIBRATE", request.requestId.value, request.project.projectId,
                request.project.revision.toString(), request.selectionGeneration.toString(),
                request.createdAtEpochMillis.toString(), "", "", "", "",
            )
            is BoundExternalRequest.CaptureBundleExport -> listOf(
                VERSION, "CAPTURE_BUNDLE", request.requestId.value, request.project.projectId,
                request.project.revision.toString(), request.selectionGeneration.toString(),
                request.createdAtEpochMillis.toString(), "", "", "", "",
            )
            is BoundExternalRequest.ArtifactExport -> listOf(
                VERSION, "ARTIFACT", request.requestId.value, request.project.projectId,
                request.project.revision.toString(), request.selectionGeneration.toString(),
                request.createdAtEpochMillis.toString(), "", request.artifactKey,
                request.resultRevision.toString(), request.extension,
            )
        }
        return fields.joinToString(".") { encoder.encodeToString(it.toByteArray(Charsets.UTF_8)) }
    }

    fun decode(encoded: String): BoundExternalRequest {
        val fields = encoded.split('.').map {
            decoder.decode(it).toString(Charsets.UTF_8)
        }
        require(fields.size == 11 && fields[0] == VERSION) { "Unsupported pending-request record." }
        val requestId = RequestId(fields[2])
        val project = ProjectRevision(fields[3], fields[4].toLong())
        val selectionGeneration = fields[5].toLong()
        val createdAt = fields[6].toLong()
        require(selectionGeneration >= 0L && createdAt >= 0L) { "Invalid pending-request metadata." }
        return when (fields[1]) {
            "IMPORT" -> BoundExternalRequest.ImportPhotos(requestId, project, selectionGeneration, createdAt)
            "CAPTURE" -> BoundExternalRequest.CapturePhoto(
                requestId, project, selectionGeneration, createdAt, SessionId(fields[7]),
            )
            "CALIBRATE" -> BoundExternalRequest.CalibrateLens(requestId, project, selectionGeneration, createdAt)
            "CAPTURE_BUNDLE" -> BoundExternalRequest.CaptureBundleExport(requestId, project, selectionGeneration, createdAt)
            "ARTIFACT" -> BoundExternalRequest.ArtifactExport(
                requestId, project, selectionGeneration, createdAt,
                fields[8], fields[9].toLong(), fields[10],
            )
            else -> error("Unknown pending-request kind.")
        }
    }
}
