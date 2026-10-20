package com.realityforge.scanner.engine.artifacts

object StageManifestCodec {
    private const val MAGIC = "RFSM"
    private const val WIRE_VERSION = 1
    private const val SCHEMA_VERSION = 1
    const val MAX_MANIFEST_BYTES: Int = 1024 * 1024

    fun encode(manifest: StageManifest): ByteArray {
        val payload = CanonicalWriter(MAX_MANIFEST_BYTES).apply {
            int(SCHEMA_VERSION)
            binding(manifest.binding)
            hash(manifest.inputSnapshotRecordHash)
            stageIdentity(manifest.identity)
            string(manifest.completion.wireId, 32)
            boolean(manifest.acceptance != null)
            manifest.acceptance?.let { acceptance ->
                string(acceptance.kind.wireId, 32)
                string(acceptance.validatorId, 96)
                string(acceptance.validatorVersion, 96)
                hash(acceptance.evidenceHash)
                boolean(acceptance.accepted)
            }
            count(manifest.dependencies.size, PipelineStage.entries.size, "manifest dependency")
            manifest.dependencies.sortedBy { it.stage.ordinal }.forEach { dependency ->
                string(dependency.stage.wireId, 32)
                hash(dependency.stageIdentityHash)
                hash(dependency.manifestHash)
            }
            count(manifest.artifacts.size, StageManifest.MAX_ARTIFACTS, "artifact")
            manifest.artifacts.sortedBy { it.relativePath }.forEach { artifact ->
                string(artifact.relativePath, 512)
                string(artifact.representation.wireId, 64)
                string(artifact.mediaType, 128)
                long(artifact.byteSize)
                hash(artifact.contentHash)
                hash(artifact.stageIdentityHash)
                string(artifact.completion.wireId, 32)
                boolean(artifact.requiredForReuse)
            }
        }.toByteArray()
        return WireEnvelope.encode(MAGIC, WIRE_VERSION, payload, MAX_MANIFEST_BYTES)
    }

    fun decode(encoded: ByteArray): StageManifest = decodeArtifactContract("stage manifest") {
        val payload = WireEnvelope.decode(encoded, MAGIC, WIRE_VERSION, MAX_MANIFEST_BYTES)
        val reader = CanonicalReader(payload, MAX_MANIFEST_BYTES)
        val schema = reader.int("stage manifest schema")
        if (schema != SCHEMA_VERSION) {
            throw ArtifactFormatException("Unsupported stage manifest schema: $schema")
        }
        val binding = reader.binding()
        val snapshotHash = reader.hash("input snapshot record SHA-256")
        val identity = reader.stageIdentity()
        val completion = CompletionState.fromWireId(reader.string(32, "stage completion"))
        val acceptance = if (reader.boolean("stage acceptance presence")) {
            StageAcceptance(
                kind = AcceptanceKind.fromWireId(reader.string(32, "acceptance kind")),
                validatorId = reader.string(96, "validator ID"),
                validatorVersion = reader.string(96, "validator version"),
                evidenceHash = reader.hash("acceptance evidence SHA-256"),
                accepted = reader.boolean("accepted evidence"),
            )
        } else null
        val dependencyCount = reader.count(PipelineStage.entries.size, 68, "manifest dependency")
        val dependencies = ArrayList<ManifestDependency>(dependencyCount)
        repeat(dependencyCount) {
            dependencies += ManifestDependency(
                stage = PipelineStage.fromWireId(reader.string(32, "dependency stage")),
                stageIdentityHash = reader.hash("dependency stage identity SHA-256"),
                manifestHash = reader.hash("dependency manifest SHA-256"),
            )
        }
        val artifactCount = reader.count(StageManifest.MAX_ARTIFACTS, 118, "artifact")
        val artifacts = ArrayList<ArtifactRef>(artifactCount)
        repeat(artifactCount) {
            artifacts += ArtifactRef(
                relativePath = reader.string(512, "artifact relative path"),
                representation = RepresentationKind.fromWireId(reader.string(64, "artifact representation")),
                mediaType = reader.string(128, "artifact media type"),
                byteSize = reader.long("artifact byte size"),
                contentHash = reader.hash("artifact content SHA-256"),
                stageIdentityHash = reader.hash("artifact stage identity SHA-256"),
                completion = CompletionState.fromWireId(reader.string(32, "artifact completion")),
                requiredForReuse = reader.boolean("artifact required-for-reuse"),
            )
        }
        reader.finish("stage manifest")
        return StageManifest(
            binding = binding,
            inputSnapshotRecordHash = snapshotHash,
            identity = identity,
            completion = completion,
            acceptance = acceptance,
            dependencies = dependencies,
            artifacts = artifacts,
        )
    }

    fun hash(manifest: StageManifest): Sha256 = Sha256.digest(encode(manifest))
}

internal fun CanonicalWriter.stageIdentity(identity: StageIdentity) {
    hash(identity.identityHash)
    string(identity.stage.wireId, 32)
    string(identity.algorithm.id, 96)
    string(identity.algorithm.version, 96)
    hash(identity.algorithm.implementationHash)
    count(identity.parameters.entries.size, CanonicalParameters.MAX_ENTRIES, "stage parameter")
    identity.parameters.entries.toSortedMap().forEach { (key, value) ->
        string(key, CanonicalParameters.MAX_KEY_BYTES)
        string(value, CanonicalParameters.MAX_VALUE_BYTES)
    }
    count(identity.inputComponents.size, InputComponent.entries.size, "stage input component")
    identity.inputComponents.toSortedMap(compareBy { it.ordinal }).forEach { (component, componentHash) ->
        string(component.wireId, 32)
        hash(componentHash)
    }
    count(identity.parents.size, PipelineStage.entries.size, "stage parent")
    identity.parents.sortedBy { it.stage.ordinal }.forEach { parent ->
        string(parent.stage.wireId, 32)
        hash(parent.identityHash)
    }
}

internal fun CanonicalReader.stageIdentity(): StageIdentity {
    val declaredHash = hash("declared stage identity SHA-256")
    val stage = PipelineStage.fromWireId(string(32, "stage identity stage"))
    val algorithm = AlgorithmSpec(
        id = string(96, "algorithm ID"),
        version = string(96, "algorithm version"),
        implementationHash = hash("algorithm implementation SHA-256"),
    )
    val parameterCount = count(CanonicalParameters.MAX_ENTRIES, 8, "stage parameter")
    val parameters = linkedMapOf<String, String>()
    repeat(parameterCount) {
        val key = string(CanonicalParameters.MAX_KEY_BYTES, "parameter key")
        if (parameters.containsKey(key)) throw ArtifactFormatException("Duplicate stage parameter: $key")
        parameters[key] = string(CanonicalParameters.MAX_VALUE_BYTES, "parameter value")
    }
    val componentCount = count(InputComponent.entries.size, 36, "stage input component")
    val components = linkedMapOf<InputComponent, Sha256>()
    repeat(componentCount) {
        val component = InputComponent.fromWireId(string(32, "input component"))
        if (components.containsKey(component)) throw ArtifactFormatException("Duplicate input component: $component")
        components[component] = hash("input component SHA-256")
    }
    val parentCount = count(PipelineStage.entries.size, 36, "stage parent")
    val parents = ArrayList<StageParent>(parentCount)
    repeat(parentCount) {
        val parent = PipelineStage.fromWireId(string(32, "parent stage"))
        if (parents.any { it.stage == parent }) throw ArtifactFormatException("Duplicate parent stage: $parent")
        parents += StageParent(parent, hash("parent stage identity SHA-256"))
    }
    val identity = StageIdentity(
        stage = stage,
        algorithm = algorithm,
        parameters = CanonicalParameters.of(parameters),
        inputComponents = components,
        parents = parents,
    )
    if (identity.identityHash != declaredHash) {
        throw ArtifactFormatException("Stage identity SHA-256 does not match its canonical fields")
    }
    return identity
}

internal fun CanonicalWriter.acceptance(value: StageAcceptance) {
    string(value.kind.wireId, 32)
    string(value.validatorId, 96)
    string(value.validatorVersion, 96)
    hash(value.evidenceHash)
    boolean(value.accepted)
}

internal fun CanonicalReader.acceptance(): StageAcceptance = StageAcceptance(
    kind = AcceptanceKind.fromWireId(string(32, "acceptance kind")),
    validatorId = string(96, "validator ID"),
    validatorVersion = string(96, "validator version"),
    evidenceHash = hash("acceptance evidence SHA-256"),
    accepted = boolean("accepted evidence"),
)

data class ArtifactProbe(val byteSize: Long, val contentHash: Sha256)

enum class ManifestRejection {
    BINDING_MISMATCH,
    SNAPSHOT_MISMATCH,
    STAGE_IDENTITY_MISMATCH,
    STAGE_NOT_COMPLETE,
    ACCEPTANCE_MISSING_OR_REJECTED,
    ACCEPTANCE_KIND_MISMATCH,
    DEPENDENCY_MISSING,
    DEPENDENCY_BINDING_MISMATCH,
    DEPENDENCY_SNAPSHOT_MISMATCH,
    DEPENDENCY_IDENTITY_MISMATCH,
    DEPENDENCY_MANIFEST_MISMATCH,
    DEPENDENCY_NOT_COMPLETE,
    ARTIFACT_MISSING,
    ARTIFACT_SIZE_MISMATCH,
    ARTIFACT_HASH_MISMATCH,
    ARTIFACT_NOT_COMPLETE,
}

data class ManifestValidationResult(val rejections: Set<ManifestRejection>) {
    val reusable: Boolean get() = rejections.isEmpty()

    fun requireReusable() {
        if (!reusable) throw ArtifactFormatException("Stage manifest is not reusable: ${rejections.joinToString()}")
    }
}

object StageManifestValidator {
    fun validateForReuse(
        manifest: StageManifest,
        expectedBinding: ArtifactBinding,
        expectedSnapshotRecordHash: Sha256,
        expectedIdentity: StageIdentity,
        parentManifests: Map<PipelineStage, StageManifest>,
        artifacts: Map<String, ArtifactProbe>,
    ): ManifestValidationResult {
        val failures = linkedSetOf<ManifestRejection>()
        if (manifest.binding != expectedBinding) failures += ManifestRejection.BINDING_MISMATCH
        if (manifest.inputSnapshotRecordHash != expectedSnapshotRecordHash) {
            failures += ManifestRejection.SNAPSHOT_MISMATCH
        }
        if (manifest.identity.identityHash != expectedIdentity.identityHash) {
            failures += ManifestRejection.STAGE_IDENTITY_MISMATCH
        }
        if (manifest.completion != CompletionState.COMPLETE) failures += ManifestRejection.STAGE_NOT_COMPLETE
        val acceptance = manifest.acceptance
        if (acceptance == null || !acceptance.accepted) {
            failures += ManifestRejection.ACCEPTANCE_MISSING_OR_REJECTED
        } else if (acceptance.kind != StageManifest.requiredAcceptance(manifest.identity.stage)) {
            failures += ManifestRejection.ACCEPTANCE_KIND_MISMATCH
        }

        manifest.dependencies.forEach { dependency ->
            val parent = parentManifests[dependency.stage]
            if (parent == null) {
                failures += ManifestRejection.DEPENDENCY_MISSING
            } else {
                if (parent.binding != manifest.binding) {
                    failures += ManifestRejection.DEPENDENCY_BINDING_MISMATCH
                }
                if (parent.inputSnapshotRecordHash != manifest.inputSnapshotRecordHash) {
                    failures += ManifestRejection.DEPENDENCY_SNAPSHOT_MISMATCH
                }
                if (parent.identity.identityHash != dependency.stageIdentityHash ||
                    parent.identity.stage != dependency.stage
                ) {
                    failures += ManifestRejection.DEPENDENCY_IDENTITY_MISMATCH
                }
                if (StageManifestCodec.hash(parent) != dependency.manifestHash) {
                    failures += ManifestRejection.DEPENDENCY_MANIFEST_MISMATCH
                }
                if (parent.completion != CompletionState.COMPLETE || parent.acceptance?.accepted != true) {
                    failures += ManifestRejection.DEPENDENCY_NOT_COMPLETE
                }
            }
        }

        manifest.artifacts.filter { it.requiredForReuse }.forEach { reference ->
            if (reference.completion != CompletionState.COMPLETE) {
                failures += ManifestRejection.ARTIFACT_NOT_COMPLETE
            }
            val actual = artifacts[reference.relativePath]
            if (actual == null) {
                failures += ManifestRejection.ARTIFACT_MISSING
            } else {
                if (actual.byteSize != reference.byteSize) failures += ManifestRejection.ARTIFACT_SIZE_MISMATCH
                if (actual.contentHash != reference.contentHash) failures += ManifestRejection.ARTIFACT_HASH_MISMATCH
            }
        }
        return ManifestValidationResult(failures)
    }
}
