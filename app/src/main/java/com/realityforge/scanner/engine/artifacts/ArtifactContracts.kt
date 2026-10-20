package com.realityforge.scanner.engine.artifacts

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Lower-case, canonical SHA-256 used by every persisted artifact contract. */
data class Sha256 private constructor(val hex: String) {
    init {
        require(HEX.matches(hex)) { "SHA-256 must be 64 lower-case hexadecimal characters" }
    }

    fun toByteArray(): ByteArray = ByteArray(32) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    override fun toString(): String = hex

    companion object {
        private val HEX = Regex("[0-9a-f]{64}")
        val ZERO: Sha256 = Sha256("0".repeat(64))

        fun parse(value: String): Sha256 = Sha256(value)

        fun digest(bytes: ByteArray): Sha256 = fromBytes(
            MessageDigest.getInstance("SHA-256").digest(bytes),
        )

        fun digestUtf8(value: String): Sha256 = digest(value.toByteArray(StandardCharsets.UTF_8))

        fun fromBytes(bytes: ByteArray): Sha256 {
            require(bytes.size == 32) { "SHA-256 binary value must contain 32 bytes" }
            return Sha256(bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) })
        }
    }
}

enum class PipelineStage(val wireId: String) {
    FEATURES("features"),
    MATCHES("matches"),
    ALIGNMENT("alignment"),
    DENSE_DEPTH("dense-depth"),
    SURFACE("surface"),
    TEXTURE("texture"),
    LOD("lod"),
    EXPORT("export");

    companion object {
        fun fromWireId(value: String): PipelineStage = entries.firstOrNull { it.wireId == value }
            ?: throw ArtifactFormatException("Unknown pipeline stage: $value")
    }
}

enum class InputComponent(val wireId: String) {
    PHOTOS("photos"),
    CALIBRATION("calibration"),
    COORDINATES("coordinates"),
    ENGINE("engine");

    companion object {
        fun fromWireId(value: String): InputComponent = entries.firstOrNull { it.wireId == value }
            ?: throw ArtifactFormatException("Unknown input component: $value")
    }
}

enum class RepresentationKind(val wireId: String) {
    FEATURE_SET("feature-set"),
    MATCH_GRAPH("match-graph"),
    CAMERA_ALIGNMENT("camera-alignment"),
    SPARSE_POINT_CLOUD("sparse-point-cloud"),
    DEPTH_MAP_SET("depth-map-set"),
    DENSE_POINT_CLOUD("dense-point-cloud"),
    TRIANGLE_MESH("triangle-mesh"),
    TEXTURE_ATLAS("texture-atlas"),
    TEXTURED_MESH("textured-mesh"),
    LOD_SET("lod-set"),
    EXPORT_PACKAGE("export-package"),
    DIAGNOSTIC_REPORT("diagnostic-report");

    companion object {
        fun fromWireId(value: String): RepresentationKind = entries.firstOrNull { it.wireId == value }
            ?: throw ArtifactFormatException("Unknown representation kind: $value")
    }
}

enum class CompletionState(val wireId: String) {
    INCOMPLETE("incomplete"),
    COMPLETE("complete"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromWireId(value: String): CompletionState = entries.firstOrNull { it.wireId == value }
            ?: throw ArtifactFormatException("Unknown completion state: $value")
    }
}

enum class AcceptanceKind(val wireId: String) {
    STRUCTURAL("structural"),
    GEOMETRY("geometry"),
    APPEARANCE("appearance"),
    INTEROPERABILITY("interoperability");

    companion object {
        fun fromWireId(value: String): AcceptanceKind = entries.firstOrNull { it.wireId == value }
            ?: throw ArtifactFormatException("Unknown acceptance kind: $value")
    }
}

class ArtifactFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Job/revision ownership is deliberately separate from content identity. */
data class ArtifactBinding(
    val projectId: String,
    val projectRevision: Long,
    val jobId: String,
    val attempt: Long,
) {
    init {
        validateToken("projectId", projectId, 128)
        require(projectRevision >= 0) { "projectRevision must be non-negative" }
        validateToken("jobId", jobId, 128)
        require(attempt >= 0) { "attempt must be non-negative" }
    }
}

data class SelectedPhotoInput(
    val photoId: String,
    val contentHash: Sha256,
    val captureMetadataHash: Sha256,
    val width: Int,
    val height: Int,
) {
    init {
        validateToken("photoId", photoId, 160)
        require(width in 1..200_000 && height in 1..200_000) { "Photo dimensions are out of bounds" }
        require(width.toLong() * height.toLong() <= 4_000_000_000L) { "Photo pixel count is out of bounds" }
    }
}

/**
 * Immutable selected input set. List order is the reconstruction order; excluded photos are absent.
 * The component hashes exclude job ownership so callers can reason about content reuse explicitly.
 */
class InputSnapshot(
    val binding: ArtifactBinding,
    selectedPhotos: List<SelectedPhotoInput>,
    val calibrationHash: Sha256,
    val coordinateConvention: String,
    val engineCompatibilityVersion: String,
) {
    val selectedPhotos: List<SelectedPhotoInput> = selectedPhotos.toList()

    init {
        require(this.selectedPhotos.size in 1..MAX_PHOTOS) { "Selected photo count is out of bounds" }
        require(this.selectedPhotos.map { it.photoId }.toSet().size == this.selectedPhotos.size) {
            "Selected photo IDs must be unique"
        }
        validateToken("coordinateConvention", coordinateConvention, 192)
        validateToken("engineCompatibilityVersion", engineCompatibilityVersion, 96)
    }

    val componentHashes: Map<InputComponent, Sha256> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        linkedMapOf(
            InputComponent.PHOTOS to hashPhotos(selectedPhotos),
            InputComponent.CALIBRATION to calibrationHash,
            InputComponent.COORDINATES to Sha256.digestUtf8("rf-coordinate-v1\u0000$coordinateConvention"),
            InputComponent.ENGINE to Sha256.digestUtf8("rf-engine-compat-v1\u0000$engineCompatibilityVersion"),
        )
    }

    /** Hash of all content-affecting fields, excluding project/job ownership. */
    val contentHash: Sha256 by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val writer = CanonicalWriter(MAX_SNAPSHOT_BYTES)
        writer.string("realityforge-input-snapshot-content-v1", 64)
        InputComponent.entries.forEach { component ->
            writer.string(component.wireId, 32)
            writer.hash(requireNotNull(componentHashes[component]))
        }
        Sha256.digest(writer.toByteArray())
    }

    /** Hash of the exact serialized record, including revision/job/attempt ownership. */
    val recordHash: Sha256 by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Sha256.digest(InputSnapshotCodec.encode(this))
    }

    override fun equals(other: Any?): Boolean = other is InputSnapshot &&
        binding == other.binding && selectedPhotos == other.selectedPhotos &&
        calibrationHash == other.calibrationHash && coordinateConvention == other.coordinateConvention &&
        engineCompatibilityVersion == other.engineCompatibilityVersion

    override fun hashCode(): Int {
        var result = binding.hashCode()
        result = 31 * result + selectedPhotos.hashCode()
        result = 31 * result + calibrationHash.hashCode()
        result = 31 * result + coordinateConvention.hashCode()
        result = 31 * result + engineCompatibilityVersion.hashCode()
        return result
    }

    override fun toString(): String = "InputSnapshot(binding=$binding, selectedPhotos=${selectedPhotos.size}, " +
        "calibrationHash=$calibrationHash, coordinateConvention=$coordinateConvention, " +
        "engineCompatibilityVersion=$engineCompatibilityVersion)"

    companion object {
        const val MAX_PHOTOS: Int = 4_096
        internal const val MAX_SNAPSHOT_BYTES: Int = 2 * 1024 * 1024

        private fun hashPhotos(photos: List<SelectedPhotoInput>): Sha256 {
            val writer = CanonicalWriter(MAX_SNAPSHOT_BYTES)
            writer.string("realityforge-selected-photos-v1", 64)
            writer.count(photos.size, MAX_PHOTOS, "selected photo")
            photos.forEachIndexed { order, photo ->
                writer.int(order)
                writer.string(photo.photoId, 160)
                writer.hash(photo.contentHash)
                writer.hash(photo.captureMetadataHash)
                writer.int(photo.width)
                writer.int(photo.height)
            }
            return Sha256.digest(writer.toByteArray())
        }
    }
}

internal fun validateToken(label: String, value: String, maxUtf8Bytes: Int) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= maxUtf8Bytes) { "$label length is out of bounds" }
    require(value.none { it == '\u0000' || it == '\r' || it == '\n' || it.isISOControl() }) {
        "$label contains a forbidden control character"
    }
}

internal fun validateRelativePath(path: String) {
    val bytes = path.toByteArray(StandardCharsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= 512) { "Artifact path length is out of bounds" }
    require(!path.startsWith('/') && !path.startsWith('\\')) { "Artifact path must be relative" }
    require('\\' !in path && '\u0000' !in path && '\r' !in path && '\n' !in path) {
        "Artifact path contains a forbidden character"
    }
    val segments = path.split('/')
    require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
        "Artifact path contains an unsafe segment"
    }
}
