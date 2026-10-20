package com.realityforge.scanner.data

import com.realityforge.scanner.data.curation.toSelectedPhotoSet

import android.content.Context
import android.net.Uri
import com.realityforge.scanner.data.operations.AtomicProjectFileWriterImpl
import com.realityforge.scanner.data.operations.CaptureArchiveServiceImpl
import com.realityforge.scanner.data.operations.CoordinatedScanDao
import com.realityforge.scanner.data.operations.BoundPhotoImportService
import com.realityforge.scanner.data.operations.PreviewReopeningServiceImpl
import com.realityforge.scanner.data.operations.ProjectAccessException
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
    private val database: ScanDatabase? = null,
) : ProjectAccess {
    private val paths = ProjectPathPolicy(File(context.filesDir, "projects"))
    private val coordinator = providedCoordinator ?: ProjectLeaseCoordinator.shared(paths)
    val storageBudget = database?.let { com.realityforge.scanner.data.storagebudget.StorageBudgetService(
        it.storageReservationDao(), com.realityforge.scanner.data.storagebudget.FileSpaceProbe(context.filesDir)) }
    val publications = database?.let { com.realityforge.scanner.data.operations.ProjectPublicationService(it, paths) }
    private val calibrationPublication = database?.let {
        com.realityforge.scanner.capture.calibration.JournaledCalibrationProfilePublicationPort(paths,
            com.realityforge.scanner.capture.calibration.RoomCalibrationMutationStore(it.calibrationMutationDao()))
    }
    private val services = providedServices ?: defaultServices()
    val curation: com.realityforge.scanner.data.curation.PhotoCurationService by lazy {
        com.realityforge.scanner.data.operations.CoordinatedPhotoCurationService(this,
            com.realityforge.scanner.data.curation.DefaultPhotoCurationService(
                com.realityforge.scanner.data.curation.RoomPhotoCurationStore(requireNotNull(database))))
    }
    val cacheService: com.realityforge.scanner.data.cachegc.ProjectCacheService by lazy {
        com.realityforge.scanner.data.cachegc.ProjectCacheService(this,
            com.realityforge.scanner.data.cachegc.RepositoryCacheReferenceSource(this,
                requireNotNull(database).jobDao(), requireNotNull(publications)),
            File(context.filesDir, "cleanup-journals-v1"))
    }
    private val sessionOwnerToken = Any()

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

    suspend fun importBatch(
        request: com.realityforge.scanner.data.importing.ImportRequest,
        progress: suspend (com.realityforge.scanner.data.importing.ImportProgress) -> Unit = {},
    ): com.realityforge.scanner.data.importing.ImportBatchResult {
        val service = services.photoImport as? BoundPhotoImportService
            ?: error("This repository does not support durable batch import.")
        val access = ProjectOperationRequest(request.projectId, request.operationId,
            ProjectOperationKind.IMPORT_PHOTO, request.expectedRevision)
        return withProjectAccess(access) {
            service.recover(request.projectId)
            service.import(request, progress)
        }
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

    suspend fun saveCalibration(key: com.realityforge.scanner.capture.calibration.CalibrationOperationKey,
        record: com.realityforge.scanner.capture.calibration.CalibrationProfileRecord): com.realityforge.scanner.capture.calibration.CalibrationProfileStoreResult =
        withProjectAccess(ProjectOperationRequest(key.projectId, key.operationId, ProjectOperationKind.MUTATE_INPUTS, key.expectedRevision)) {
            com.realityforge.scanner.capture.calibration.TransactionalProfileStore(requireNotNull(calibrationPublication)).save(key, record)
        }

    suspend fun removeCalibration(key: com.realityforge.scanner.capture.calibration.CalibrationOperationKey): com.realityforge.scanner.capture.calibration.CalibrationProfileStoreResult =
        withProjectAccess(ProjectOperationRequest(key.projectId, key.operationId, ProjectOperationKind.MUTATE_INPUTS, key.expectedRevision)) {
            com.realityforge.scanner.capture.calibration.TransactionalProfileStore(requireNotNull(calibrationPublication)).remove(key)
        }

    suspend fun restoreCaptureArchive(source: java.io.InputStream): String =
        (services.captureArchive as? com.realityforge.scanner.data.archives.ProjectCaptureArchiveService
            ?: error("Capture archive restore is unavailable.")).restore(source)

    suspend fun exportCaptureBundle(projectId: String, destination: OutputStream): Int {
        val project = rawDao.project(projectId) ?: throw ProjectMissingException(projectId)
        val request = ProjectOperationRequest.create(
            projectId = projectId,
            kind = ProjectOperationKind.EXPORT_CAPTURE,
            expectedRevision = project.revision,
        )
        return withProjectAccess(request) { services.captureArchive.export(projectId, destination) }
    }

    suspend fun reopen(projectId: String, previous: Boolean = false): com.realityforge.scanner.data.reopening.ReopenResult {
        val reader = services.previewReopening as? com.realityforge.scanner.data.reopening.ValidatedPreviewReopeningService
            ?: error("Typed reopening is unavailable.")
        return withProjectAccess(ProjectOperationRequest.create(projectId, ProjectOperationKind.READ_CURRENT_RESULT)) {
            reader.reopen(projectId, previous)
        }
    }

    suspend fun delete(id: String) {
        if (services.deletion is com.realityforge.scanner.data.deletion.DurableProjectDeletionService) {
            services.deletion.delete(id)
            return
        }
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
        return withProjectAccess(request) { session -> inputSnapshot(session) }
    }

    override suspend fun inputSnapshot(session: ProjectAccessSession): ProjectInputSnapshot {
        val project = requireStableSession(session)
        val rows = rawDao.photos(project.id)
        val curated = com.realityforge.scanner.data.curation.PhotoCurationPersistence.snapshot(project.id,
            com.realityforge.scanner.data.curation.CurationRevision(project.revision, project.curationVersion),
            rows.map { photo -> com.realityforge.scanner.data.curation.PersistedPhotoCuration(
                photo.id, photo.sha256, photo.included, photo.selectionOrder, photo.addedAt, photo.curationRecord) })
        val selected = curated.toSelectedPhotoSet()
        val indexed = rows.associateBy { it.id }
        val photos = selected.photos.map { selectedPhoto ->
            val photo = requireNotNull(indexed[selectedPhoto.id])
            check(photo.included && photo.sha256 == selectedPhoto.sha256) { "Photo curation projection is inconsistent." }
            paths.photoFile(photo)
            ProjectInputPhoto(
                id = photo.id,
                sha256 = photo.sha256,
                bytes = photo.bytes,
                width = photo.width,
                height = photo.height,
                focal35mm = photo.focal35mm,
                addedAt = photo.addedAt,
                metadataSha256 = InputPhotoIdentity.metadataSha256(photo.captureMetadataRecord,
                    photo.captureSettingsRecord, photo.focal35mm),
            )
        }
        requireStableSession(session)
        return ProjectInputSnapshot(project.id, session.acquiredRevision, photos.toList())
    }

    override suspend fun currentResult(projectId: String): ProjectCurrentResult? {
        val project = rawDao.project(projectId) ?: return null
        val request = ProjectOperationRequest.create(
            projectId = projectId,
            kind = ProjectOperationKind.READ_CURRENT_RESULT,
            expectedRevision = project.revision,
        )
        return withProjectAccess(request) { session -> currentResult(session) }
    }

    override suspend fun currentResult(session: ProjectAccessSession): ProjectCurrentResult? {
        val project = requireStableSession(session)
        val resultRevision = project.resultRevision
        val key = project.resultKey
        val result = if (resultRevision == null || resultRevision != session.acquiredRevision || key == null) {
            null
        } else {
            check(paths.artifactRevision(key) == resultRevision) {
                "Current result identity does not match the project revision."
            }
            val diagnostic = paths.artifact(project.id, key, "json")
            check(diagnostic.isFile) { "Current result diagnostic is missing." }
            ProjectCurrentResult(
                projectId = project.id,
                revision = resultRevision,
                artifactKey = key,
                diagnosticJson = ProjectArtifactReference(
                    projectId = project.id,
                    revision = resultRevision,
                    artifactKey = key,
                    extension = "json",
                    absolutePath = diagnostic.absolutePath,
                ),
            )
        }
        requireStableSession(session)
        return result
    }

    override suspend fun <T> withProjectAccess(
        request: ProjectOperationRequest,
        block: suspend (ProjectAccessSession) -> T,
    ): T {
        val lease = withContext(Dispatchers.IO) { coordinator.acquire(request) }
        var session: ProjectAccessSession? = null
        try {
            val before = rawDao.project(request.projectId) ?: throw ProjectMissingException(request.projectId)
            request.expectedRevision?.let { expected ->
                if (before.revision != expected) {
                    throw StaleProjectRevisionException(request.projectId, expected, before.revision)
                }
            }
            if (before.state in setOf("DELETING", "DELETING_PHOTO") &&
                request.kind != ProjectOperationKind.DELETE_PROJECT) {
                throw ProjectAccessException("Project deletion is pending. Retry deletion before opening or changing this project.")
            }
            val activeSession = ProjectAccessSession(
                request = request,
                acquiredRevision = before.revision,
                acquiredAtEpochMillis = lease.acquiredAtEpochMillis,
                ownerToken = sessionOwnerToken,
            )
            session = activeSession
            val result = coordinator.within(lease) { block(activeSession) }
            if (request.kind.stableRevisionRequired) {
                val after = rawDao.project(request.projectId) ?: throw ProjectMissingException(request.projectId)
                if (after.revision != before.revision) {
                    throw StaleProjectRevisionException(request.projectId, before.revision, after.revision)
                }
            }
            return result
        } finally {
            session?.invalidate()
            withContext(NonCancellable + Dispatchers.IO) { lease.close() }
        }
    }

    private suspend fun requireStableSession(session: ProjectAccessSession): ScanProject {
        session.requireActive(sessionOwnerToken)
        if (!session.request.kind.stableRevisionRequired) {
            throw ProjectAccessException(
                "${session.request.kind.name.lowercase()} does not provide a stable-revision read scope.",
            )
        }
        val project = rawDao.project(session.request.projectId)
            ?: throw ProjectMissingException(session.request.projectId)
        if (project.revision != session.acquiredRevision) {
            throw StaleProjectRevisionException(
                session.request.projectId,
                session.acquiredRevision,
                project.revision,
            )
        }
        return project
    }

    /** Application startup reconciles incomplete writes before admitting a new operation. */
    suspend fun recoverStorage() = withContext(Dispatchers.IO) {
        (services.captureArchive as? com.realityforge.scanner.data.archives.ProjectCaptureArchiveService)?.recover()
        database?.let { com.realityforge.scanner.data.library.RoomProjectLibraryPort(it, paths.root).recoverAbandoned() }
        (services.deletion as? com.realityforge.scanner.data.deletion.DurableProjectDeletionService)?.recoverPending()
        publications?.reconcile()
        val importer = services.photoImport as? BoundPhotoImportService
        importer?.recoverAbandonedStaging()
        if (importer != null && database != null) {
            for (id in database.executionPublicationDao().projectIds()) {
                val project = rawDao.project(id) ?: continue
                if (project.state in setOf("DELETING", "DELETING_PHOTO")) continue
                withProjectAccess(ProjectOperationRequest.create(id, ProjectOperationKind.IMPORT_PHOTO)) {
                    calibrationPublication?.recover(id)?.let { check(it.failures.isEmpty()) {
                        "Optical profile recovery is incomplete. Processing is blocked until the profile can be recovered."
                    } }
                    importer.recover(id)
                }
            }
        }
    }

    private fun defaultServices(): ProjectStorageServices {
        val writer = AtomicProjectFileWriterImpl(paths)
        return ProjectStorageServices(
            creation = ProjectCreationServiceImpl(rawDao, paths),
            photoImport = BoundPhotoImportService(context, rawDao, paths, storageBudget),
            previewReopening = com.realityforge.scanner.data.reopening.ValidatedPreviewReopeningService(rawDao, paths, publications),
            captureArchive = com.realityforge.scanner.data.archives.ProjectCaptureArchiveService(
                com.realityforge.scanner.data.archives.ScanDaoArchiveProjectStore(rawDao, rawDao::restoreArchiveProject),
                paths, com.realityforge.scanner.BuildConfig.VERSION_NAME, storageBudget),
            deletion = database?.let { com.realityforge.scanner.data.deletion.DurableProjectDeletionService(rawDao,
                com.realityforge.scanner.data.deletion.RoomDeletionPersistence(it.deletionRequestDao()), coordinator, paths) }
                ?: ProjectDeletionServiceImpl(rawDao, paths),
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
