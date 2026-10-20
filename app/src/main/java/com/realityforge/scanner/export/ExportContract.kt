package com.realityforge.scanner.export

import java.util.UUID

enum class AssetRepresentation { SPARSE_POINTS, DENSE_POINTS, OBSERVED_SURFACE, PHOTOGRAPHIC_SURFACE, DIAGNOSTICS, PHOTOGRAPHIC_ATLAS, ORIGINALS }
enum class ExportFormat(val mime: String, val extension: String) {
    GLB("model/gltf-binary", "glb"), PLY("application/octet-stream", "ply"),
    OBJ_PACKAGE("application/zip", "zip"), STL("model/stl", "stl"),
    JSON("application/json", "json"), PNG("image/png", "png"), CAPTURE_ARCHIVE("application/zip", "zip")
}

data class ArtifactIdentity(val projectId: String, val revision: Long, val artifactKey: String, val sha256: String) {
    init {
        require(UUID.fromString(projectId).toString() == projectId)
        require(revision >= 0 && artifactKey.isNotBlank() && artifactKey.length <= 180)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

data class ExportOptions(val editIdentity: String? = null, val lodIdentity: String? = null, val acknowledgedArbitraryScale: Boolean = false) {
    init { for (id in listOfNotNull(editIdentity, lodIdentity)) require(id.matches(Regex("[a-f0-9]{64}"))) }
}

/** Offered only by a serializer/validator which can actually produce this representation. */
data class ExportOption(val id: String, val format: ExportFormat, val representation: AssetRepresentation,
    val bytes: Long, val arbitraryScale: Boolean = true, val photographic: Boolean = false) {
    init {
        require(id.matches(Regex("[a-z0-9_.-]{1,80}")) && bytes > 0)
        require(!photographic || representation == AssetRepresentation.PHOTOGRAPHIC_SURFACE || representation == AssetRepresentation.PHOTOGRAPHIC_ATLAS)
        require(format != ExportFormat.STL || representation in setOf(AssetRepresentation.OBSERVED_SURFACE, AssetRepresentation.PHOTOGRAPHIC_SURFACE))
        require(format != ExportFormat.OBJ_PACKAGE || representation in setOf(AssetRepresentation.OBSERVED_SURFACE, AssetRepresentation.PHOTOGRAPHIC_SURFACE))
        require(format != ExportFormat.PNG || representation == AssetRepresentation.PHOTOGRAPHIC_ATLAS)
    }
}

data class ExportRequest(val requestId: String, val artifact: ArtifactIdentity, val option: ExportOption, val options: ExportOptions = ExportOptions()) {
    init {
        require(UUID.fromString(requestId).toString() == requestId)
        require(option.format != ExportFormat.STL || !option.arbitraryScale || options.acknowledgedArbitraryScale)
    }
}

sealed interface ExportOutcome {
    data class Completed(val requestId: String, val sha256: String, val bytes: Long) : ExportOutcome
    data class Failed(val requestId: String, val code: String, val destinationMayBePartial: Boolean) : ExportOutcome
    data class Cancelled(val requestId: String, val destinationMayBePartial: Boolean) : ExportOutcome
}

class ExportCancelled : RuntimeException()

/** The transport commits only after all bytes and source revision are checked. Abort must attempt cleanup. */
interface ExportDestination {
    fun open(): java.io.OutputStream
    fun commit()
    fun abort(): Boolean
}

/** Implemented by the actual artifact/serializer owner; callers must not infer a current project from selection. */
interface ExportSource {
    suspend fun isCurrent(identity: ArtifactIdentity): Boolean
    suspend fun available(identity: ArtifactIdentity): List<ExportOption>
    fun materialize(request: ExportRequest, target: java.io.File, checkpoint: () -> Unit)
    fun validate(request: ExportRequest, file: java.io.File)
}
