package com.realityforge.scanner.engine

import com.realityforge.scanner.processing.resources.ResourceWorkloadSelection
import kotlin.math.max
import kotlin.math.roundToInt

/** Allocation/sampling policy, not a phone-performance or object-completeness estimate. */
internal data class DenseWorkload(
    val width: Int, val height: Int, val viewCount: Int, val depthEdge: Int,
    val textureEdge: Int, val pixelStride: Int, val sampledGridPixels: Long,
    val packedDepthBytes: Long, val maximumCachedLuminanceBytes: Long,
    val memoryTier: String,
    val resourceSelectionIdentity: String? = null,
    val sampledGridLimit: Long = MAX_SAMPLES,
    val nativeThreads: Int = 1,
) {
    companion object {
        const val MAX_VIEWS = 8
        const val MAX_SAMPLES = 100_000L
        const val MAX_ATLAS_PIXELS = 4_500_000L
        const val STANDARD_HEAP_BYTES = 256L * 1024 * 1024

        fun tier(heapBytes: Long): String {
            require(heapBytes >= 128L * 1024 * 1024) { "Insufficient managed heap for bounded reconstruction" }
            return if (heapBytes >= STANDARD_HEAP_BYTES) "standard" else "compact"
        }
        fun depthEdge(heapBytes: Long): Int = if (tier(heapBytes) == "standard") 384 else 256

        /** Keep anchors and distribute remaining slots across the registered sequence, not just its prefix. */
        fun selectCameraIndices(registered: Int, maximumViews: Int = MAX_VIEWS): IntArray {
            require(registered in 2..12) { "Expected two to twelve registered cameras" }
            require(maximumViews in 2..MAX_VIEWS)
            if (registered <= maximumViews) return IntArray(registered) { it }
            if (maximumViews == 2) return intArrayOf(0, 1)
            if (maximumViews == 3) return intArrayOf(0, 1, registered - 1)
            return intArrayOf(0, 1) + IntArray(maximumViews - 2) { slot ->
                2 + (slot * (registered - 3).toDouble() / (maximumViews - 3)).roundToInt()
            }
        }
        fun gridSamples(width: Int, height: Int, views: Int, stride: Int): Long {
            require(width in 32..640 && height in 32..640 && views in 2..MAX_VIEWS && stride in 2..8)
            // Exact M14 loop: radius=2, start=3, stop when coordinate >= dimension-radius-2.
            fun count(size: Int): Long = max(0, (size - 8 + stride) / stride).toLong()
            return count(width) * count(height) * views
        }
        fun plan(width: Int, height: Int, views: Int, heapBytes: Long): DenseWorkload {
            val edge = depthEdge(heapBytes)
            require(width in 32..edge && height in 32..edge && views in 2..MAX_VIEWS)
            val stride = (3..8).firstOrNull { gridSamples(width, height, views, it) <= MAX_SAMPLES }
                ?: error("Reconstruction sampling budget exceeded")
            val compact = tier(heapBytes) == "compact"
            val texture = if (compact) 512 else if (views > 4) 640 else 768
            return DenseWorkload(width, height, views, edge, texture, stride,
                gridSamples(width, height, views, stride), width.toLong()*height*4*views,
                width.toLong()*height*4*minOf(views, 4), tier(heapBytes))
        }

        fun plan(width: Int, height: Int, views: Int, selection: ResourceWorkloadSelection): DenseWorkload {
            require(width in 32..selection.depthEdge && height in 32..selection.depthEdge && views in 2..selection.maximumViews)
            val stride = (3..8).firstOrNull { gridSamples(width, height, views, it) <= selection.maximumGridSamples }
                ?: error("Selected reconstruction sampling budget exceeded")
            val texture = minOf(selection.textureEdgeLimit, if (views > 4) 640 else 768)
            return DenseWorkload(width, height, views, selection.depthEdge, texture, stride,
                gridSamples(width, height, views, stride), width.toLong() * height * 4 * views,
                width.toLong() * height * 4 * minOf(views, 4), selection.memoryTier,
                selection.identitySha256, selection.maximumGridSamples, selection.nativeThreads)
        }
        fun atlasPixels(width: Int, height: Int, views: Int): Long {
            require(width in 32..1024 && height in 32..1024 && views in 2..MAX_VIEWS)
            val columns = if (views > 4) 3 else 2
            val rows = (views + columns - 1) / columns
            val pixels = (width.toLong()+6)*columns*(height.toLong()+6)*rows
            require(pixels <= MAX_ATLAS_PIXELS) { "Photographic atlas exceeds the viewer budget" }
            return pixels
        }

        /** Only image-visible positive camera-Z observations contribute to the common depth interval. */
        fun depthBounds(cameraRows: DoubleArray, points: DoubleArray, width: Int, height: Int): Pair<Double, Double> {
            require(cameraRows.size % 16 == 0 && cameraRows.size/16 in 2..MAX_VIEWS)
            require(points.size % 3 == 0 && points.size/3 in 30..30_000)
            require(cameraRows.all { it.isFinite() } && points.all { it.isFinite() })
            require(width in 32..640 && height in 32..640)
            val depths = ArrayList<Double>()
            for (cam in cameraRows.indices step 16) {
                require(cameraRows[cam+12] > 0 && cameraRows[cam+13] > 0)
                for (p in points.indices step 3) {
                    val x=points[p]; val y=points[p+1]; val z=points[p+2]
                    val qx=cameraRows[cam]*x+cameraRows[cam+1]*y+cameraRows[cam+2]*z+cameraRows[cam+9]
                    val qy=cameraRows[cam+3]*x+cameraRows[cam+4]*y+cameraRows[cam+5]*z+cameraRows[cam+10]
                    val qz=cameraRows[cam+6]*x+cameraRows[cam+7]*y+cameraRows[cam+8]*z+cameraRows[cam+11]
                    if (qz <= .01 || qz >= 10000) continue
                    val u=cameraRows[cam+12]*qx/qz+cameraRows[cam+14]
                    val v=cameraRows[cam+13]*qy/qz+cameraRows[cam+15]
                    if (u >= 2 && u <= width-3 && v >= 2 && v <= height-3) depths.add(qz)
                }
            }
            require(depths.size >= 40) { "Insufficient image-visible sparse depth priors" }
            depths.sort()
            val near=(depths[(depths.size*.10).toInt()]*.75).coerceAtLeast(.02)
            val far=max(depths[(depths.size*.90).toInt()]*1.30, near*1.4)
            require(far.isFinite() && far < 100000 && far > near*1.05)
            return near to far
        }
    }
}
