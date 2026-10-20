package com.realityforge.scanner.viewer

import android.view.Surface
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

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

/** Shared display-only convention: V(pan,distance) * Rx(pitch) * Ry(yaw) * normalization. */
object DisplayCamera {
    fun rotation(yawDegrees: Float,pitchDegrees: Float,result: FloatArray): FloatArray {
        require(yawDegrees.isFinite() && pitchDegrees.isFinite() && result.size>=16)
        val yaw=Math.toRadians(yawDegrees.toDouble()); val pitch=Math.toRadians(pitchDegrees.toDouble())
        val cy=cos(yaw).toFloat(); val sy=sin(yaw).toFloat(); val cp=cos(pitch).toFloat(); val sp=sin(pitch).toFloat()
        result.fill(0f)
        result[0]=cy; result[1]=sp*sy; result[2]=-cp*sy
        result[5]=cp; result[6]=sp
        result[8]=sy; result[9]=-sp*cy; result[10]=cp*cy; result[15]=1f
        return result
    }
    fun distance(width: Int,height: Int,zoom: Float): Float {
        require(width>0 && height>0 && zoom.isFinite() && zoom>0)
        return 3f/minOf(1f,width.toFloat()/height)/zoom
    }
    fun view(panX: Float,panY: Float,distance: Float,result: FloatArray): FloatArray {
        require(panX.isFinite() && panY.isFinite() && distance.isFinite() && distance>0 && result.size>=16)
        result.fill(0f); result[0]=1f; result[5]=1f; result[10]=1f; result[15]=1f
        result[12]=-panX; result[13]=-panY; result[14]=-distance
        return result
    }
}

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

    fun admitViewport(modelGpuBytes: Long, width: Int, height: Int): Long {
        require(modelGpuBytes >= 0 && width in 1..8192 && height in 1..8192)
        // Color/depth plus multiple swap-chain images; conservative admission, not a driver measurement.
        val estimated = Math.addExact(modelGpuBytes, Math.multiplyExact(width.toLong()*height,16L))
        require(estimated <= maximumGpuBytes) { "Model and viewport exceed the GPU admission budget." }
        return estimated
    }
}
