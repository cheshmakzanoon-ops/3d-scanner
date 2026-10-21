package com.realityforge.scanner.export

import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.viewer.PhotographicGlb
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ensureActive
import com.realityforge.scanner.data.publication.PublicationReadResult

/** Adapter for existing completed M19 files. Dense legacy writers are excluded until 037/003 fixes their sparse metadata. */
class RepositoryExportSource(private val repository: ProjectRepository, private val geometryValidator: ((File, Int) -> Unit)? = null, private val imageValidator: ((File) -> Unit)? = null, private val derived: LegacyMeshDerivedExports = LegacyMeshDerivedExports(), private val publication: suspend (String) -> PublicationReadResult = { PublicationReadResult.None }) : ExportSource {
    @Volatile private var resolved: Pair<ArtifactIdentity, Map<String, File>>? = null
    private suspend fun files(projectId: String, key: String, revision: Long): Map<String, File> = when (val state = publication(projectId)) {
        is PublicationReadResult.Invalid -> error("INVALID_PUBLICATION")
        is PublicationReadResult.Verified -> {
            require(state.value.manifest.identity.projectRevision == revision) { "STALE_ARTIFACT" }
            LOGICAL_NAMES.mapNotNull { (extension, name) -> state.value.artifact(name)?.let { extension to it } }.toMap()
        }
        PublicationReadResult.None -> LOGICAL_NAMES.keys.associateWith { repository.artifact(projectId, key, it) }
    }
    override suspend fun isCurrent(identity: ArtifactIdentity): Boolean {
        val p = repository.dao.project(identity.projectId) ?: return false
        if (p.revision != identity.revision || p.resultRevision != identity.revision || p.resultKey != identity.artifactKey) return false
        val json = files(identity.projectId, identity.artifactKey, identity.revision)["json"] ?: return false
        return json.isFile && json.length() in 2..ProjectRepository.MAX_PREVIEW_BYTES && ExportCoordinator.digest(json) == identity.sha256
    }
    suspend fun snapshot(projectId: String): ArtifactIdentity? {
        val result = repository.currentPreview(projectId) ?: return null
        val p = repository.dao.project(projectId) ?: return null
        if (p.resultRevision != p.revision || p.resultKey != result.getString("artifactKey")) return null
        val json = files(projectId, requireNotNull(p.resultKey), p.revision)["json"] ?: return null
        val identity = ArtifactIdentity(projectId, p.revision, requireNotNull(p.resultKey), ExportCoordinator.digest(json))
        return identity.takeIf { isCurrent(it) }
    }
    override suspend fun available(identity: ArtifactIdentity): List<ExportOption> {
        if (!isCurrent(identity)) return emptyList()
        val metadata = repository.currentPreview(identity.projectId) ?: return emptyList()
        val artifacts = files(identity.projectId, identity.artifactKey, identity.revision)
        resolved = identity to artifacts
        val choices = mutableListOf<ExportOption>()
        fun add(id: String, format: ExportFormat, representation: AssetRepresentation, photographic: Boolean = false) {
            val file = artifacts[id] ?: return
            if (file.isFile && file.length() > 0) {
                val candidate = ExportOption(id, format, representation, file.length(), photographic = photographic)
                try {
                    validate(request(identity, candidate), file)
                    choices += candidate.copy(contentSha256 = ExportCoordinator.digest(file))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Invalid completed bytes are not an available export choice. */ }
            }
        }
        add("json", ExportFormat.JSON, AssetRepresentation.DIAGNOSTICS)
        if (metadata.optInt("pointCount") > 0) {
            if (geometryValidator != null) add("glb", ExportFormat.GLB, AssetRepresentation.SPARSE_POINTS)
            add("ply", ExportFormat.PLY, AssetRepresentation.SPARSE_POINTS)
        }
        if ((metadata.optJSONObject("meshPreview")?.optInt("triangleCount") ?: 0) > 0) {
            if (geometryValidator != null) add("mesh.glb", ExportFormat.GLB, AssetRepresentation.OBSERVED_SURFACE)
            add("mesh.ply", ExportFormat.PLY, AssetRepresentation.OBSERVED_SURFACE)
            val mesh = artifacts["mesh.ply"]
            val context = kotlinx.coroutines.currentCoroutineContext()
            for (id in LegacyMeshDerivedExports.IDS) if (mesh != null) try {
                choices += derived.describe(mesh, id) { context.ensureActive() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { /* Unsupported/corrupt mesh is not advertised. */ }
        }
        if ((metadata.optJSONObject("texturePreview")?.optInt("triangleCount") ?: 0) > 0) {
            add("textured.glb", ExportFormat.GLB, AssetRepresentation.PHOTOGRAPHIC_SURFACE, true)
            if (imageValidator != null) add("texture.png", ExportFormat.PNG, AssetRepresentation.PHOTOGRAPHIC_ATLAS, true)
        }
        return choices.takeIf { isCurrent(identity) } ?: emptyList()
    }
    override fun materialize(request: ExportRequest, target: File, checkpoint: () -> Unit) {
        require(request.options.editIdentity == null && request.options.lodIdentity == null) { "VARIANT_UNAVAILABLE" }
        if (request.option.id in LegacyMeshDerivedExports.IDS) {
            derived.materialize(requireNotNull(resolved?.takeIf { it.first == request.artifact }?.second?.get("mesh.ply")) { "STALE_ARTIFACT" }, request, target, checkpoint)
            return
        }
        val source = requireNotNull(resolved?.takeIf { it.first == request.artifact }?.second?.get(request.option.id)) { "STALE_ARTIFACT" }
        source.inputStream().use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(65536); var bytes = 0L
            while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; require(bytes <= request.option.bytes - n); output.write(buffer, 0, n); bytes += n }
            require(bytes == request.option.bytes)
        } }
    }
    override fun validate(request: ExportRequest, file: File) {
        require(file.length() == request.option.bytes)
        if (request.option.id in LegacyMeshDerivedExports.IDS) { derived.validate(request.option, file); return }
        when (request.option.id) {
            "textured.glb" -> PhotographicGlb.read(file)
            "texture.png" -> requireNotNull(imageValidator) { "VALIDATOR_UNAVAILABLE" }(file)
            "json" -> require(ExportCoordinator.digest(file) == request.artifact.sha256)
            "glb", "mesh.glb" -> requireNotNull(geometryValidator) { "VALIDATOR_UNAVAILABLE" }(file, if (request.option.id == "glb") 0 else 4)
            "ply", "mesh.ply" -> LegacyPlyValidation.validate(file, request.option.id == "mesh.ply")
            else -> error("OPTION_UNAVAILABLE")
        }
        request.option.contentSha256?.let { require(ExportCoordinator.digest(file) == it) { "ARTIFACT_BYTES_CHANGED" } }
    }
    companion object {
        private val LOGICAL_NAMES = mapOf("json" to "preview", "glb" to "sparse-glb", "ply" to "sparse-ply",
            "mesh.glb" to "mesh-glb", "mesh.ply" to "mesh-ply", "textured.glb" to "textured-glb", "texture.png" to "texture-png")
        fun request(identity: ArtifactIdentity, option: ExportOption) = ExportRequest(UUID.randomUUID().toString(), identity, option) }
}
