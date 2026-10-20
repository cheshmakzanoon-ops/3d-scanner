package com.realityforge.scanner.data.operations

import android.content.Context
import android.net.Uri
import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.engine.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

class PhotoImportServiceImpl(
    private val context: Context,
    private val dao: ScanDao,
    private val paths: ProjectPathPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) : PhotoImportService {
    override suspend fun importPhoto(projectId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        requireNotNull(dao.project(projectId)) { "Project no longer exists." }
        val originals = paths.originalsDirectory(projectId)
        require(originals.usableSpace > MINIMUM_FREE_BYTES) { "Keep at least 512 MB free before importing." }
        val id = UUID.randomUUID().toString()
        val (temporary, destination) = paths.newOriginalFiles(projectId, id)
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open this photo." }.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count > 0) { "Photo provider returned an invalid zero-length read." }
                        bytes += count
                        require(bytes <= MAX_INPUT_BYTES) { "Photograph exceeds the 150 MB input limit." }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(bytes > 0) { "Photograph is empty." }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (dao.duplicate(projectId, hash) > 0) return@withContext false
            val bitmap = ImageLoader.upright(temporary)
            val statistics = try {
                ImageLoader.quality(bitmap)
            } finally {
                bitmap.recycle()
            }
            val fullBounds = ImageLoader.bounds(temporary)
            val focal = ImageLoader.focal35(temporary)
            AtomicProjectFileWriterImpl.moveReplacing(temporary, destination)
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                dao.addPhoto(
                    ScanPhoto(
                        id = id,
                        projectId = projectId,
                        filename = destination.name,
                        sha256 = hash,
                        bytes = bytes,
                        width = fullBounds.first,
                        height = fullBounds.second,
                        focal35mm = focal,
                        sharpness = statistics[2],
                        darkFraction = statistics[3],
                        brightFraction = statistics[4],
                        addedAt = clock(),
                    ),
                )
                committed = true
            }
            true
        } finally {
            temporary.delete()
            if (!committed) destination.delete()
        }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_INPUT_BYTES = 150L * 1024 * 1024
        private const val MINIMUM_FREE_BYTES = 512L * 1024 * 1024
    }
}
