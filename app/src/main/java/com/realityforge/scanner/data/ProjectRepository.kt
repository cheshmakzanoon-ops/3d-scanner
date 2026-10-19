package com.realityforge.scanner.data

import android.content.Context
import android.net.Uri
import com.realityforge.scanner.data.operations.AtomicProjectFileWriterImpl
import com.realityforge.scanner.data.operations.CaptureArchiveServiceImpl
import com.realityforge.scanner.data.operations.CoordinatedScanDao
import com.realityforge.scanner.data.operations.PhotoImportServiceImpl
import com.realityforge.scanner.data.operations.PreviewReopeningServiceImpl
import com.realityforge.scanner.data.operations.ProjectAccessSession
import com.realityforge.scanner.data.operations.ProjectCreationServiceImpl
import com.realityforge.scanner.data.operations.ProjectDeletionServiceImpl
import com.realityforge.scanner.data.operations.ProjectLeaseCoordinator
import com.realityforge.scanner.data.operations.ProjectMissingException
import com.realityforge.scanner.data.operations.ProjectOperationKind
import com.realityforge.scanner.data.operations.ProjectOperationRequest
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import com.realityforge.scanner.data.operations.ProjectStorageServices
import com.realityforge.scanner.data.operations.StaleProjectRevisionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry

/**
 * Thin C6 façade for project identity, canonical paths and operation reservations.
 * Storage behavior is delegated to one service per operation so incoming owners can replace
 * a service without rebuilding a second repository or bypassing the conflict policy.
 */
class ProjectRepository(
    private val context: Context,
    private val rawDao: ScanDao,
    providedServices: ProjectStorageServices? = null,
    providedCoordinator: ProjectLeaseCoordinator? = null,
) : ProjectAccess {
    private val paths = ProjectPathPolicy(File(context.filesDir, "projects"))
    private val coordinator = providedCoordinator ?: ProjectLeaseCoordinator.shared(paths)
    private val services = providedServices ?: defaultServices()

    /** Compatibility adapter. New application code should consume ProjectAccess and focused services. */
    val dao: ScanDao = CoordinatedScanDao(rawDao, coordinator, paths)

    fun directory(id: String): File = paths.projectDirectory(id)

    fun artifact(id: String, key: String, extension: String): File {
        require(ARTIFACT_KEY_CONTRACT.matches(key)) { "Invalid artifact key." }
        require(extension in SUPPORTED_ARTIFACT_EXTENSIONS) { "Invalid artifact extension." }
        return paths.artifact(id, key, extension)
    }

    fun photoFile(photo: ScanPhoto): File = paths.photoFile(photo)

    suspend fun create(name: String): String = services.creation.create(name)

    suspend fun importPhoto(projectId: String, uri: Uri): Boolean {
        val project = rawDao.project(projectId) ?: throw ProjectMissingException(projectId)
        val request = ProjectOperationRequest.create(
            projectId = projectId,
            kind = ProjectOperationKind.IMPORT_PHOTO,
            expectedRevision = project.revision,
        )
        return withProjectAccess(request) { services.photoImport.importPhoto(projectId, uri) }
    }

    suspend fun currentPreview(id: String): JSONObject? {
        val project = rawDao.project(id) ?: return null
        if (project.resultRevision == null || project.resultRevision != project.revision) return null
        val request = ProjectOperationRequest.create(
            projectId = id,
            kind = ProjectOperationKind.READ_CURRENT_RESULT,
            expectedRevision = project.revision,
        )
        return withProjectAccess(request) { services.previewReopening.currentPreview(id) }
    }

    suspend fun exportCaptureBundle(projectId: String, destination: OutputStream): Int {
        val project = rawDao.project(projectId) ?: throw ProjectMissingException(projectId)
        val request = ProjectOperationRequest.create(
            projectId = projectId,
            kind = ProjectOperationKind.EXPORT_CAPTURE,
            expectedRevision = project.revision,
        )
        return withProjectAccess(request) { services.captureArchive.export(projectId, destination) }
    }

    suspend fun delete(id: String) {
        val project = rawDao.project(id) ?: throw ProjectMissingException(id)
        val request = ProjectOperationRequest.create(
            projectId = id,
            kind = ProjectOperationKind.DELETE_PROJECT,
            expectedRevision = project.revision,
        )
        withProjectAccess(request) { services.deletion.delete(id) }
    }

    /** Atomic within this private filesystem; power-loss durability remains owner 023's publication gate. */
    fun writeAtomic(file: File, text: String) {
        val owned = paths.requireOwnedFile(file)
        val project = kotlinx.coroutines.runBlocking(Dispatchers.IO) { rawDao.project(owned.projectId) }
            ?: throw ProjectMissingException(owned.projectId)
        val request = ProjectOperationRequest.create(
            projectId = owned.projectId,
            kind = ProjectOperationKind.ATOMIC_PROJECT_WRITE,
            expectedRevision = project.revision,
        )
        val lease = coordinator.acquire(request)
        try {
            val acquired = kotlinx.coroutines.runBlocking(Dispatchers.IO) { rawDao.project(owned.projectId) }
                ?: throw ProjectMissingException(owned.projectId)
            if (acquired.revision != project.revision) {
                throw StaleProjectRevisionException(owned.projectId, project.revision, acquired.revision)
            }
            services.atomicWriter.write(owned.file, text)
        } finally {
            lease.close()
        }
    }

    override suspend fun inputSnapshot(projectId: String): ProjectInputSnapshot {
        val project = rawDao.project(projectId) ?: throw ProjectMissingException(projectId)
        val request = ProjectOperationRequest.create(
            projectId = projectId,
            kind = ProjectOperationKind.READ_INPUTS,
            expectedRevision = project.revision,
        )
        return withProjectAccess(request) {
            val photos = rawDao.photos(projectId).map { photo ->
                paths.photoFile(photo)
                ProjectInputPhoto(
                    id = photo.id,
                    sha256 = photo.sha256,
                    bytes = photo.bytes,
                    width = photo.width,
                    height = photo.height,
                    focal35mm = photo.focal35mm,
                    addedAt = photo.addedAt,
                )
            }
            ProjectInputSnapshot(projectId, project.revision, photos.toList())
        }
    }

    override suspend fun currentResult(projectId: String): ProjectCurrentResult? {
        val project = rawDao.project(projectId) ?: return null
        val resultRevision = project.resultRevision ?: return null
        if (resultRevision != project.revision) return null
        val key = project.resultKey ?: return null
        val request = ProjectOperationRequest.create(
            projectId = projectId,
            kind = ProjectOperationKind.READ_CURRENT_RESULT,
            expectedRevision = project.revision,
        )
        return withProjectAccess(request) {
            check(paths.artifactRevision(key) == resultRevision) {
                "Current result identity does not match the project revision."
            }
            val diagnostic = paths.artifact(projectId, key, "json")
            check(diagnostic.isFile) { "Current result diagnostic is missing." }
            ProjectCurrentResult(
                projectId = projectId,
                revision = resultRevision,
                artifactKey = key,
                diagnosticJson = ProjectArtifactReference(
                    projectId = projectId,
                    revision = resultRevision,
                    artifactKey = key,
                    extension = "json",
                    absolutePath = diagnostic.absolutePath,
                ),
            )
        }
    }

    override suspend fun <T> withProjectAccess(
        request: ProjectOperationRequest,
        block: suspend (ProjectAccessSession) -> T,
    ): T {
        val lease = withContext(Dispatchers.IO) { coordinator.acquire(request) }
        try {
            val before = rawDao.project(request.projectId) ?: throw ProjectMissingException(request.projectId)
            request.expectedRevision?.let { expected ->
                if (before.revision != expected) {
                    throw StaleProjectRevisionException(request.projectId, expected, before.revision)
                }
            }
            val result = block(ProjectAccessSession(request, before.revision, lease.acquiredAtEpochMillis))
            if (request.kind.stableRevisionRequired) {
                val after = rawDao.project(request.projectId) ?: throw ProjectMissingException(request.projectId)
                if (after.revision != before.revision) {
                    throw StaleProjectRevisionException(request.projectId, before.revision, after.revision)
                }
            }
            return result
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { lease.close() }
        }
    }

    private fun defaultServices(): ProjectStorageServices {
        val writer = AtomicProjectFileWriterImpl(paths)
        return ProjectStorageServices(
            creation = ProjectCreationServiceImpl(rawDao, paths),
            photoImport = PhotoImportServiceImpl(context, rawDao, paths),
            previewReopening = PreviewReopeningServiceImpl(rawDao, paths),
            captureArchive = CaptureArchiveServiceImpl(rawDao, paths),
            deletion = ProjectDeletionServiceImpl(rawDao, paths),
            atomicWriter = writer,
        )
    }

    companion object {
        const val MAX_PREVIEW_BYTES = 32L * 1024 * 1024
        private val ARTIFACT_KEY_CONTRACT = Regex("sparse-(?:m[0-9]{2}-)?r[0-9]+-[0-9a-f]{16}")
        val SUPPORTED_ARTIFACT_EXTENSIONS = setOf(
            "json", "glb", "ply", "dense.glb", "dense.ply", "mesh.glb", "mesh.ply",
            "textured.glb", "texture.png",
        )

        internal fun newCaptureDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")
        internal fun captureManifestEntry(): ZipEntry = ZipEntry("session.json")
        internal const val CAPTURE_REVISION_CHANGED =
            "Project changed during capture export; discard this incomplete archive."

        internal fun validatePreviewIdentity(json: JSONObject, expectedRevision: Long, expectedKey: String) {
            val storedRevision = json.get("revision")
            check(
                (storedRevision is Int || storedRevision is Long) &&
                    (storedRevision as Number).toLong() == expectedRevision &&
                    json.getString("artifactKey") == expectedKey,
            ) { "Saved preview belongs to a different reconstruction." }
        }
    }
}
