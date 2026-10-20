package com.realityforge.scanner.engine.artifacts

import java.nio.charset.StandardCharsets

data class AlgorithmSpec(
    val id: String,
    val version: String,
    val implementationHash: Sha256,
) {
    init {
        validateToken("algorithm id", id, 96)
        validateToken("algorithm version", version, 96)
    }
}

class CanonicalParameters private constructor(private val values: Map<String, String>) {
    val entries: Map<String, String> get() = values

    val hash: Sha256 by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val writer = CanonicalWriter(MAX_PARAMETER_BYTES)
        writer.string("realityforge-stage-parameters-v1", 64)
        writer.count(values.size, MAX_ENTRIES, "parameter")
        values.toSortedMap().forEach { (key, value) ->
            writer.string(key, MAX_KEY_BYTES)
            writer.string(value, MAX_VALUE_BYTES)
        }
        Sha256.digest(writer.toByteArray())
    }

    override fun equals(other: Any?): Boolean = other is CanonicalParameters && values == other.values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = values.toString()

    companion object {
        const val MAX_ENTRIES = 128
        const val MAX_KEY_BYTES = 64
        const val MAX_VALUE_BYTES = 1_024
        internal const val MAX_PARAMETER_BYTES = 192 * 1024
        private val KEY = Regex("[a-z][a-z0-9_.-]{0,63}")

        val EMPTY: CanonicalParameters = CanonicalParameters(emptyMap())

        fun of(values: Map<String, String>): CanonicalParameters {
            require(values.size <= MAX_ENTRIES) { "Too many stage parameters" }
            val copy = values.toSortedMap()
            copy.forEach { (key, value) ->
                require(KEY.matches(key)) { "Invalid parameter key: $key" }
                require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_VALUE_BYTES) {
                    "Parameter value is too large: $key"
                }
                require(value.none { it == '\u0000' || it == '\r' || it == '\n' }) {
                    "Parameter value contains a forbidden control character: $key"
                }
            }
            return CanonicalParameters(copy)
        }
    }
}

data class StageParent(val stage: PipelineStage, val identityHash: Sha256)

class StageIdentity(
    val stage: PipelineStage,
    val algorithm: AlgorithmSpec,
    val parameters: CanonicalParameters,
    inputComponents: Map<InputComponent, Sha256>,
    parents: List<StageParent>,
) {
    val inputComponents: Map<InputComponent, Sha256> = inputComponents.toMap()
    val parents: List<StageParent> = parents.toList()

    init {
        require(this.inputComponents.isNotEmpty()) { "A stage identity must include at least one input component" }
        require(this.inputComponents.size <= InputComponent.entries.size) { "Too many input components" }
        require(this.parents.size <= PipelineStage.entries.size) { "Too many stage parents" }
        require(this.parents.map { it.stage }.toSet().size == this.parents.size) { "Parent stages must be unique" }
        require(this.parents.none { it.stage == stage }) { "A stage cannot depend on itself" }
        this.parents.forEach { parent ->
            require(parent.stage.ordinal < stage.ordinal || stage == PipelineStage.EXPORT) {
                "Stage parent must precede the child stage"
            }
        }
    }

    val identityHash: Sha256 by lazy(LazyThreadSafetyMode.PUBLICATION) { Sha256.digest(canonicalBytes()) }

    internal fun canonicalBytes(): ByteArray {
        val writer = CanonicalWriter(256 * 1024)
        writer.string("realityforge-stage-identity-v1", 64)
        writer.string(stage.wireId, 32)
        writer.string(algorithm.id, 96)
        writer.string(algorithm.version, 96)
        writer.hash(algorithm.implementationHash)
        writer.hash(parameters.hash)
        writer.count(inputComponents.size, InputComponent.entries.size, "input component")
        inputComponents.toSortedMap(compareBy { it.ordinal }).forEach { (component, hash) ->
            writer.string(component.wireId, 32)
            writer.hash(hash)
        }
        writer.count(parents.size, PipelineStage.entries.size, "stage parent")
        parents.sortedBy { it.stage.ordinal }.forEach { parent ->
            writer.string(parent.stage.wireId, 32)
            writer.hash(parent.identityHash)
        }
        return writer.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is StageIdentity &&
        stage == other.stage && algorithm == other.algorithm && parameters == other.parameters &&
        inputComponents == other.inputComponents && parents == other.parents

    override fun hashCode(): Int {
        var result = stage.hashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + parameters.hashCode()
        result = 31 * result + inputComponents.hashCode()
        result = 31 * result + parents.hashCode()
        return result
    }

    override fun toString(): String = "StageIdentity(stage=$stage, identityHash=$identityHash)"
}

data class StageAcceptance(
    val kind: AcceptanceKind,
    val validatorId: String,
    val validatorVersion: String,
    val evidenceHash: Sha256,
    val accepted: Boolean,
) {
    init {
        validateToken("validatorId", validatorId, 96)
        validateToken("validatorVersion", validatorVersion, 96)
        require(!accepted || evidenceHash != Sha256.ZERO) {
            "Accepted evidence must have a non-zero content hash"
        }
    }
}

data class ArtifactRef(
    val relativePath: String,
    val representation: RepresentationKind,
    val mediaType: String,
    val byteSize: Long,
    val contentHash: Sha256,
    val stageIdentityHash: Sha256,
    val completion: CompletionState,
    val requiredForReuse: Boolean = true,
) {
    init {
        validateRelativePath(relativePath)
        require(byteSize in 0..MAX_ARTIFACT_BYTES) { "Artifact byte size is out of bounds" }
        require(completion != CompletionState.COMPLETE || byteSize > 0) { "A complete artifact cannot be empty" }
        require(MEDIA_TYPE.matches(mediaType)) { "Invalid artifact media type" }
        require(completion == CompletionState.COMPLETE || !requiredForReuse) {
            "A required artifact cannot be partial"
        }
    }

    companion object {
        const val MAX_ARTIFACT_BYTES: Long = 8L * 1024 * 1024 * 1024 * 1024
        private val MEDIA_TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+/-]{0,127}")
    }
}

data class ManifestDependency(
    val stage: PipelineStage,
    val stageIdentityHash: Sha256,
    val manifestHash: Sha256,
)

class StageManifest(
    val binding: ArtifactBinding,
    val inputSnapshotRecordHash: Sha256,
    val identity: StageIdentity,
    val completion: CompletionState,
    val acceptance: StageAcceptance?,
    dependencies: List<ManifestDependency>,
    artifacts: List<ArtifactRef>,
) {
    val dependencies: List<ManifestDependency> = dependencies.toList()
    val artifacts: List<ArtifactRef> = artifacts.toList()

    init {
        require(this.dependencies.size <= PipelineStage.entries.size) { "Too many manifest dependencies" }
        require(this.dependencies.map { it.stage }.toSet().size == this.dependencies.size) {
            "Manifest dependency stages must be unique"
        }
        val identityParents = identity.parents.associate { it.stage to it.identityHash }
        require(this.dependencies.associate { it.stage to it.stageIdentityHash } == identityParents) {
            "Manifest dependencies must exactly match stage identity parents"
        }
        require(this.artifacts.size <= MAX_ARTIFACTS) { "Too many artifacts in one stage manifest" }
        require(this.artifacts.map { it.relativePath }.toSet().size == this.artifacts.size) {
            "Artifact paths must be unique"
        }
        this.artifacts.forEach { artifact ->
            require(artifact.stageIdentityHash == identity.identityHash) {
                "Artifact stage identity does not match its manifest"
            }
            require(artifact.representation in allowedRepresentations(identity.stage)) {
                "Artifact representation ${artifact.representation} does not belong to ${identity.stage}"
            }
        }
        if (completion == CompletionState.COMPLETE) {
            require(this.artifacts.isNotEmpty()) { "A completed stage must publish at least one artifact" }
            require(this.artifacts.any { it.requiredForReuse }) { "A completed stage requires at least one reusable artifact" }
            require(this.artifacts.all { it.completion == CompletionState.COMPLETE }) {
                "A completed stage cannot contain partial artifacts"
            }
            requireNotNull(acceptance) { "A completed stage requires independent acceptance evidence" }
            require(acceptance.accepted) { "A completed stage must have accepted evidence" }
            require(acceptance.kind == requiredAcceptance(identity.stage)) {
                "Acceptance kind does not match stage semantics"
            }
        } else if (acceptance != null) {
            require(!acceptance.accepted) { "An incomplete/failed stage cannot carry accepted evidence" }
        }
    }

    override fun equals(other: Any?): Boolean = other is StageManifest &&
        binding == other.binding && inputSnapshotRecordHash == other.inputSnapshotRecordHash &&
        identity == other.identity && completion == other.completion && acceptance == other.acceptance &&
        dependencies == other.dependencies && artifacts == other.artifacts

    override fun hashCode(): Int {
        var result = binding.hashCode()
        result = 31 * result + inputSnapshotRecordHash.hashCode()
        result = 31 * result + identity.hashCode()
        result = 31 * result + completion.hashCode()
        result = 31 * result + (acceptance?.hashCode() ?: 0)
        result = 31 * result + dependencies.hashCode()
        result = 31 * result + artifacts.hashCode()
        return result
    }

    override fun toString(): String = "StageManifest(stage=${identity.stage}, completion=$completion, artifacts=${artifacts.size})"

    companion object {
        const val MAX_ARTIFACTS: Int = 256

        fun allowedRepresentations(stage: PipelineStage): Set<RepresentationKind> = when (stage) {
            PipelineStage.FEATURES -> setOf(RepresentationKind.FEATURE_SET, RepresentationKind.DIAGNOSTIC_REPORT)
            PipelineStage.MATCHES -> setOf(RepresentationKind.MATCH_GRAPH, RepresentationKind.DIAGNOSTIC_REPORT)
            PipelineStage.ALIGNMENT -> setOf(
                RepresentationKind.CAMERA_ALIGNMENT,
                RepresentationKind.SPARSE_POINT_CLOUD,
                RepresentationKind.DIAGNOSTIC_REPORT,
            )
            PipelineStage.DENSE_DEPTH -> setOf(
                RepresentationKind.DEPTH_MAP_SET,
                RepresentationKind.DENSE_POINT_CLOUD,
                RepresentationKind.DIAGNOSTIC_REPORT,
            )
            PipelineStage.SURFACE -> setOf(
                RepresentationKind.DENSE_POINT_CLOUD,
                RepresentationKind.TRIANGLE_MESH,
                RepresentationKind.DIAGNOSTIC_REPORT,
            )
            PipelineStage.TEXTURE -> setOf(
                RepresentationKind.TEXTURE_ATLAS,
                RepresentationKind.TEXTURED_MESH,
                RepresentationKind.DIAGNOSTIC_REPORT,
            )
            PipelineStage.LOD -> setOf(
                RepresentationKind.LOD_SET,
                RepresentationKind.TRIANGLE_MESH,
                RepresentationKind.TEXTURED_MESH,
                RepresentationKind.DIAGNOSTIC_REPORT,
            )
            PipelineStage.EXPORT -> setOf(RepresentationKind.EXPORT_PACKAGE, RepresentationKind.DIAGNOSTIC_REPORT)
        }

        fun requiredAcceptance(stage: PipelineStage): AcceptanceKind = when (stage) {
            PipelineStage.FEATURES, PipelineStage.MATCHES -> AcceptanceKind.STRUCTURAL
            PipelineStage.ALIGNMENT, PipelineStage.DENSE_DEPTH,
            PipelineStage.SURFACE, PipelineStage.LOD -> AcceptanceKind.GEOMETRY
            PipelineStage.TEXTURE -> AcceptanceKind.APPEARANCE
            PipelineStage.EXPORT -> AcceptanceKind.INTEROPERABILITY
        }
    }
}
