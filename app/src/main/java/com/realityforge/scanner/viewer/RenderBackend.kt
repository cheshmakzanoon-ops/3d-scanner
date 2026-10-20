package com.realityforge.scanner.viewer

import android.view.Surface
import android.view.View

/** C14. Implementations own GPU resources; callers retain immutable master files. */
interface RenderControls {
    fun orbit(horizontalDegrees: Float, verticalDegrees: Float)
    fun pan(horizontal: Float, vertical: Float)
    fun magnify(factor: Float)
    fun reset()
}

interface StudioRenderSurface : AutoCloseable {
    val view: View
    val controls: RenderControls
    fun resumeRendering()
    fun pauseRendering()
}

interface RenderBackend : RenderControls, AutoCloseable {
    val capabilities: Set<InspectionMode>
    val statistics: RenderStatistics
    fun attach(surface: Surface, width: Int, height: Int)
    fun detach()
    fun resize(width: Int, height: Int)
    fun draw(frameTimeNanos: Long): Boolean
}

enum class InspectionMode { PHOTOGRAPHIC_UNLIT, NEUTRAL_GEOMETRY }

/** Byte counts are admission estimates, never reported as measured driver residency. */
data class RenderStatistics(
    val estimatedCpuBytes: Long = 0,
    val estimatedGpuBytes: Long = 0,
    val uploadNanos: Long = 0,
    val submittedFrames: Long = 0,
)

data class RenderBudget(
    val maximumCpuBytes: Long = 192L * 1024 * 1024,
    val maximumGpuBytes: Long = 96L * 1024 * 1024,
    val maximumTextureEdge: Int = 4096,
) {
    init { require(maximumCpuBytes > 0 && maximumGpuBytes > 0 && maximumTextureEdge > 0) }

    fun admit(fileBytes: Long, vertexCount: Long, indexCount: Long, imageWidth: Int, imageHeight: Int): RenderStatistics {
        require(fileBytes in 28L..PhotographicGlb.MAX_FILE_BYTES.toLong())
        require(vertexCount in 1L..240_000L && indexCount in 3L..240_000L && indexCount % 3L == 0L)
        require(imageWidth in 1..maximumTextureEdge && imageHeight in 1..maximumTextureEdge)
        val pixels = Math.multiplyExact(imageWidth.toLong(), imageHeight.toLong())
        require(pixels <= PhotographicGlb.MAX_IMAGE_PIXELS)
        val geometry = Math.addExact(Math.multiplyExact(vertexCount, 48L), Math.multiplyExact(indexCount, 4L))
        // Decode + upload + mipchain staging: conservative 3x RGBA. gltfio/source copies: 4x GLB.
        val gpu = Math.addExact(geometry, Math.multiplyExact(pixels, 6L))
        val cpu = Math.addExact(Math.multiplyExact(fileBytes, 4L), Math.addExact(geometry * 2L, pixels * 12L))
        require(cpu <= maximumCpuBytes) { "Model exceeds CPU upload budget." }
        require(gpu <= maximumGpuBytes) { "Model exceeds GPU upload budget." }
        return RenderStatistics(cpu, gpu)
    }
}
