package com.realityforge.scanner.viewer.loading

import com.realityforge.scanner.viewer.PhotographicAsset
import com.realityforge.scanner.viewer.PhotographicGlb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One owner per load. Closing drops CPU references after the renderer releases
 * its resources; this is not permission to mutate the arrays shared with GLES.
 */
class LoadedPhotographicModel internal constructor(
    asset: PhotographicAsset,
    bytes: ByteArray,
    val summary: GlbSummary,
) : Closeable {
    private var assetReference: PhotographicAsset? = asset
    private var byteReference: ByteArray? = bytes
    val asset: PhotographicAsset @Synchronized get() = checkNotNull(assetReference) { "Model is closed." }
    /** Read-only original GLB, for validated gltfio upload. Does not copy bytes. */
    @Synchronized fun glbBytes(): ByteBuffer = ByteBuffer.wrap(checkNotNull(byteReference) { "Model is closed." })
        .asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
    @Synchronized override fun close() { assetReference = null; byteReference = null }
}

object PhotographicModelLoader {
    /** Cancellation is checked on IO, JSON/PNG validation and attribute loops.
     * No background load survives its caller's coroutine job.
     */
    suspend fun load(file: File): LoadedPhotographicModel = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val cancelled = { context.ensureActive(); false }
        val bytes = GlbStructure.readBounded(file, cancelled)
        val summary = GlbStructure.validate(bytes, 4, cancelled)
        require(summary.hasPhotographicTexture) { "Model has no photographic atlas." }
        val asset = PhotographicGlb.decode(bytes, cancelled)
        context.ensureActive()
        LoadedPhotographicModel(asset, bytes, summary)
    }
}
