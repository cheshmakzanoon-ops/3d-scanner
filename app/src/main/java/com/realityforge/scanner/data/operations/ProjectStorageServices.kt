package com.realityforge.scanner.data.operations

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

interface ProjectCreationService {
    suspend fun create(name: String): String
}

interface PhotoImportService {
    /** True means published; false means an existing verified duplicate. Failures must throw. */
    suspend fun importPhoto(projectId: String, uri: Uri): Boolean
}

interface PreviewReopeningService {
    suspend fun currentPreview(projectId: String): JSONObject?
}

interface CaptureArchiveService {
    suspend fun export(projectId: String, destination: OutputStream): Int
}

interface ProjectDeletionService {
    suspend fun delete(projectId: String)
}

interface AtomicProjectFileWriter {
    fun write(file: File, text: String)
}

data class ProjectStorageServices(
    val creation: ProjectCreationService,
    val photoImport: PhotoImportService,
    val previewReopening: PreviewReopeningService,
    val captureArchive: CaptureArchiveService,
    val deletion: ProjectDeletionService,
    val atomicWriter: AtomicProjectFileWriter,
)
