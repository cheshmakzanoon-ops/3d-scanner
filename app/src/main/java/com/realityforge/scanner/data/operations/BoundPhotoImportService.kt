package com.realityforge.scanner.data.operations

import android.content.Context
import android.net.Uri
import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.importing.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.withContext

/** Owner011 provider ingestion delegates original publication and recovery to the owner023 adapter. */
class BoundPhotoImportService(
    context: Context,
    private val dao: ScanDao,
    private val paths: ProjectPathPolicy,
    private val storage: com.realityforge.scanner.data.storagebudget.StorageBudgetService? = null,
) : PhotoImportService {
    private val publication = com.realityforge.scanner.data.publication.JournaledOriginalPublicationPort(dao, paths)
    private val importer = ImportService(
        AndroidImportSourceAccess(context.contentResolver), AndroidImportInspector,
        publication, File(context.filesDir, "import-staging-v1"),
    )

    override suspend fun importPhoto(projectId: String, uri: Uri): Boolean {
        val revision = requireNotNull(dao.project(projectId)).revision
        return import(ImportRequest(UUID.randomUUID().toString(), projectId, revision,
            listOf(ImportSource(uri.toString())))).photos.single().outcome.toSinglePhotoImportResult()
    }

    suspend fun import(request: ImportRequest, progress: suspend (ImportProgress) -> Unit = {}): ImportBatchResult {
        val token = com.realityforge.scanner.data.storagebudget.ReservationToken(request.operationId,
            request.projectId, request.expectedRevision)
        val estimate = com.realityforge.scanner.data.storagebudget.StorageEstimate.photoImport(null)
        storage?.requireReservation(token, estimate)
        return try {
            importer.import(request) { update ->
                storage?.requireReservation(token, estimate)
                progress(update)
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { storage?.release(token) }
        }
    }

    /** Called while C6 holds the fixed project's write reservation. */
    suspend fun recover(projectId: String) {
        importer.recoverAbandoned()
        publication.recover(projectId)
    }

    suspend fun recoverAbandonedStaging(): Int = importer.recoverAbandoned()
}
