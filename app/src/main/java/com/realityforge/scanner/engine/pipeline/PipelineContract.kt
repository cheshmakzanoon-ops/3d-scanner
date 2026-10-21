package com.realityforge.scanner.engine.pipeline

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Versioned orchestration contract for reconstruction stages.
 *
 * A stage identity is deliberately narrower than the whole scan configuration:
 * changing texture settings does not invalidate geometry, and export settings do
 * not invalidate reconstruction at all. Photo content, calibration, stage settings
 * and engine versions are included at the first stage that consumes them and flow
 * into all descendants through the parent identity.
 */
enum class PipelineRepresentation {
    FEATURE_SET,
    VERIFIED_MATCHES,
    SPARSE_POINTS,
    DENSE_POINTS,
    OBSERVED_MESH,
    PHOTOGRAPHIC_MESH,
}

enum class PipelineStage(
    val parent: PipelineStage?,
    val representation: PipelineRepresentation,
) {
    FEATURES(null, PipelineRepresentation.FEATURE_SET),
    MATCHING(FEATURES, PipelineRepresentation.VERIFIED_MATCHES),
    SPARSE(MATCHING, PipelineRepresentation.SPARSE_POINTS),
    DENSE(SPARSE, PipelineRepresentation.DENSE_POINTS),
    SURFACE(DENSE, PipelineRepresentation.OBSERVED_MESH),
    TEXTURE(SURFACE, PipelineRepresentation.PHOTOGRAPHIC_MESH),
    ;

    companion object {
        val executionOrder: List<PipelineStage> = entries.toList()

        fun through(target: PipelineStage): List<PipelineStage> =
            executionOrder.take(target.ordinal + 1)
    }
}

data class PipelinePhotoInput(
    val id: String,
    val contentSha256: String,
    val width: Int,
    val height: Int,
    val byteCount: Long,
    val focal35mm: Double?,
    val captureMetadataSha256: String? = null,
) {
    init {
        require(captureMetadataSha256 == null || captureMetadataSha256.matches(SHA256))
        require(id.isNotBlank()) { "Photo identity is required." }
        require(contentSha256.matches(SHA256)) { "Photo content identity must be lowercase SHA-256." }
        require(width > 0 && height > 0 && byteCount > 0) { "Photo dimensions and byte count must be positive." }
        require(focal35mm == null || focal35mm.isFinite()) { "Photo focal metadata must be finite." }
    }

    internal fun addFeatureIdentity(digest: StableDigest) {
        digest.text(id)
        digest.text(contentSha256)
        digest.long(width.toLong())
        digest.long(height.toLong())
        digest.long(byteCount)
    }

    internal fun addGeometryMetadata(digest: StableDigest) {
        digest.text(id)
        digest.long(focal35mm?.toBits() ?: Long.MIN_VALUE)
        digest.text(captureMetadataSha256 ?: "legacy-unknown-metadata-v1")
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class PipelineStageSettings(
    val features: String,
    val matching: String,
    val sparse: String,
    val dense: String,
    val surface: String,
    val texture: String,
    /** Export is intentionally excluded from every reconstruction stage identity. */
    val export: String,
) {
    init {
        require(listOf(features, matching, sparse, dense, surface, texture, export).all { it.isNotBlank() }) {
            "Every pipeline settings group must have an explicit versioned identity."
        }
    }

    fun forStage(stage: PipelineStage): String = when (stage) {
        PipelineStage.FEATURES -> features
        PipelineStage.MATCHING -> matching
        PipelineStage.SPARSE -> sparse
        PipelineStage.DENSE -> dense
        PipelineStage.SURFACE -> surface
        PipelineStage.TEXTURE -> texture
    }
}

data class PipelineStageIdentity(
    val stage: PipelineStage,
    val digest: String,
    val parentDigest: String?,
    val projectId: String,
    val sourceRevision: Long,
    val schema: Int = SCHEMA,
) {
    init {
        require(digest.matches(SHA256)) { "Stage identity must be lowercase SHA-256." }
        require(parentDigest == null || parentDigest.matches(SHA256)) { "Invalid parent stage identity." }
        require(projectId.isNotBlank() && sourceRevision >= 0) { "Invalid project/revision identity." }
        require(schema == SCHEMA) { "Unsupported pipeline identity schema $schema." }
        require((stage.parent == null) == (parentDigest == null)) { "Stage parent identity does not match the stage graph." }
    }

    companion object {
        const val SCHEMA = 2
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class PipelineInputSnapshot(
    val projectId: String,
    val revision: Long,
    val photos: List<PipelinePhotoInput>,
    /** SHA-256 of the exact calibration profile, or the versioned no-profile sentinel. */
    val calibrationIdentity: String,
    val engineVersion: String,
    val settings: PipelineStageSettings,
) {
    init {
        require(projectId.isNotBlank()) { "Project identity is required." }
        require(revision >= 0) { "Project revision cannot be negative." }
        require(photos.size >= 2) { "At least two immutable photo inputs are required." }
        require(photos.map { it.id }.toSet().size == photos.size) { "Repeated photo identity in pipeline input." }
        require(calibrationIdentity.isNotBlank()) { "Calibration identity must be explicit." }
        require(engineVersion.isNotBlank()) { "Engine version must be explicit." }
    }

    /** Compute the exact reusable identity for one stage. */
    fun identity(stage: PipelineStage): PipelineStageIdentity {
        val parent = stage.parent?.let(::identity)
        val digest = StableDigest()
            .text("realityforge-pipeline-stage")
            .long(PipelineStageIdentity.SCHEMA.toLong())
            .text(stage.name)
            .text(projectId)
            .text(engineVersion)
            .text(parent?.digest ?: "root")
            .text(settings.forStage(stage))

        when (stage) {
            PipelineStage.FEATURES -> {
                digest.long(photos.size.toLong())
                photos.forEach { it.addFeatureIdentity(digest) }
            }
            PipelineStage.MATCHING -> {
                digest.text(calibrationIdentity)
                photos.forEach { it.addGeometryMetadata(digest) }
            }
            PipelineStage.SPARSE -> Unit
            PipelineStage.DENSE -> digest.text(calibrationIdentity)
            PipelineStage.SURFACE -> Unit
            PipelineStage.TEXTURE -> {
                // Texture consumes the original photographs and camera calibration again.
                digest.text(calibrationIdentity)
                photos.forEach { it.addFeatureIdentity(digest) }
            }
        }
        return PipelineStageIdentity(stage, digest.finish(), parent?.digest, projectId, revision)
    }

    fun identitiesThrough(target: PipelineStage): List<PipelineStageIdentity> =
        PipelineStage.through(target).map(::identity)
}

data class PipelineArtifactReference(
    val artifactId: String,
    val identity: PipelineStageIdentity,
    val representation: PipelineRepresentation,
    val primaryLocation: String,
    val companionLocations: List<String> = emptyList(),
    val contentSha256: String? = null,
) {
    init {
        require(artifactId.isNotBlank() && primaryLocation.isNotBlank()) { "Artifact ID and location are required." }
        require(representation == identity.stage.representation) {
            "${identity.stage} cannot publish $representation."
        }
        require(companionLocations.none { it.isBlank() }) { "Companion artifact locations cannot be blank." }
        require(contentSha256 == null || contentSha256.matches(SHA256)) { "Invalid artifact content SHA-256." }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun forStage(
            identity: PipelineStageIdentity,
            primaryLocation: String,
            companionLocations: List<String> = emptyList(),
            contentSha256: String? = null,
        ): PipelineArtifactReference = PipelineArtifactReference(
            artifactId = "${identity.stage.name.lowercase()}-${identity.digest.take(24)}",
            identity = identity,
            representation = identity.stage.representation,
            primaryLocation = primaryLocation,
            companionLocations = companionLocations,
            contentSha256 = contentSha256,
        )
    }
}

data class PipelineFailure(
    val stage: PipelineStage,
    val code: String,
    val message: String,
    val recoverable: Boolean,
) {
    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) { "Failure code must be a stable uppercase identifier." }
        require(message.isNotBlank()) { "Failure reason is required." }
    }
}

sealed interface PipelineStageOutcome {
    val stage: PipelineStage

    data class Completed(val artifact: PipelineArtifactReference) : PipelineStageOutcome {
        override val stage: PipelineStage = artifact.identity.stage
    }

    data class Unavailable(val failure: PipelineFailure) : PipelineStageOutcome {
        override val stage: PipelineStage = failure.stage
    }
}

enum class PipelineCapacityProfile(
    val maximumPhotos: Int,
    val implemented: Boolean,
    val deviceQualified: Boolean = false,
) {
    LEGACY_12(12, true),
    TARGET_30(30, false),
    TARGET_100(100, false),
    TARGET_250(250, false),
    ABOVE_250(Int.MAX_VALUE, false),
}

sealed interface PipelineAdmission {
    val profile: PipelineCapacityProfile
    val photoCount: Int

    data class Supported(
        override val profile: PipelineCapacityProfile,
        override val photoCount: Int,
    ) : PipelineAdmission

    data class Unsupported(
        override val profile: PipelineCapacityProfile,
        override val photoCount: Int,
        val reason: String,
    ) : PipelineAdmission
}

class UnsupportedPipelineProfileException(
    val admission: PipelineAdmission.Unsupported,
) : IllegalArgumentException(admission.reason)

/**
 * The current algorithm implementation is bounded to twelve photos; device qualification is a separate gate.
 * Larger target profiles use this same production admission point, but fail visibly until
 * camera/track/resource owners provide measured bounded implementations and device evidence.
 */
object PipelineCapacityPolicy {
    fun profileFor(photoCount: Int): PipelineCapacityProfile {
        require(photoCount >= 0) { "Photo count cannot be negative." }
        return when {
            photoCount <= 12 -> PipelineCapacityProfile.LEGACY_12
            photoCount <= 30 -> PipelineCapacityProfile.TARGET_30
            photoCount <= 100 -> PipelineCapacityProfile.TARGET_100
            photoCount <= 250 -> PipelineCapacityProfile.TARGET_250
            else -> PipelineCapacityProfile.ABOVE_250
        }
    }

    fun admit(photoCount: Int): PipelineAdmission {
        val profile = profileFor(photoCount)
        return if (profile.implemented) {
            PipelineAdmission.Supported(profile, photoCount)
        } else {
            PipelineAdmission.Unsupported(
                profile,
                photoCount,
                "${profile.name} requested with $photoCount photos, but the current bounded algorithm supports at most " +
                    "${PipelineCapacityProfile.LEGACY_12.maximumPhotos}. No photos were silently discarded; " +
                    "use 2–12 photos until bounded camera/track/memory validation is integrated.",
            )
        }
    }

    fun requireSupported(photoCount: Int): PipelineCapacityProfile = when (val admission = admit(photoCount)) {
        is PipelineAdmission.Supported -> admission.profile
        is PipelineAdmission.Unsupported -> throw UnsupportedPipelineProfileException(admission)
    }
}

fun sha256Utf8(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).toHex()

internal class StableDigest {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun text(value: String): StableDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        long(bytes.size.toLong())
        digest.update(bytes)
        return this
    }

    fun long(value: Long): StableDigest {
        for (shift in 0 until Long.SIZE_BITS step Byte.SIZE_BITS) {
            digest.update((value ushr shift).toByte())
        }
        return this
    }

    fun finish(): String = digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
