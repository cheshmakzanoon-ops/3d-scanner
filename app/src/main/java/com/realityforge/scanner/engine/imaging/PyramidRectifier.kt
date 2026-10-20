package com.realityforge.scanner.engine.imaging

import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.geometry.PixelTransform
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.roundToInt

/** Bilinear integer-centre reference; invalid borders stay transparent and masked. */
object PyramidRectifier {
    data class Tile(val firstRow: Int, val rows: Int, val pixels: IntArray, val validity: ByteArray)
    fun tiles(source: IntArray, sourceWidth: Int, sourceHeight: Int, rawToUpright: PixelTransform,
              sourceLens: LensProjection, levelLens: LensProjection, width: Int, height: Int,
              tileRows: Int = 32, cancelled: () -> Boolean = { false }, consume: (Tile) -> Unit) {
        require(sourceWidth in 2..8192 && sourceHeight in 2..8192 && source.size.toLong() == sourceWidth.toLong() * sourceHeight)
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= PyramidLevel.MAX_PIXELS)
        require(tileRows in 1..256)
        for (start in 0 until height step tileRows) {
            if (cancelled()) throw CancellationException("Pyramid rectification cancelled")
            val count = minOf(tileRows, height - start)
            val pixels = IntArray(width * count); val mask = ByteArray(pixels.size)
            for (dy in 0 until count) {
                if (cancelled()) throw CancellationException("Pyramid rectification cancelled")
                val y = start + dy
                for (x in 0 until width) {
                    val rayX = (x - levelLens.cx) / levelLens.fx
                    val rayY = (y - levelLens.cy) / levelLens.fy
                    val (dx, dySource) = sourceLens.distort(rayX, rayY)
                    val (sx, sy) = rawToUpright.inverse(dx * sourceLens.fx + sourceLens.cx, dySource * sourceLens.fy + sourceLens.cy)
                    if (!sx.isFinite() || !sy.isFinite() || sx < 0 || sy < 0 || sx >= sourceWidth - 1 || sy >= sourceHeight - 1) continue
                    val ix = floor(sx).toInt(); val iy = floor(sy).toInt(); val u = sx - ix; val v = sy - iy
                    val p00 = source[iy * sourceWidth + ix]; val p10 = source[iy * sourceWidth + ix + 1]
                    val p01 = source[(iy + 1) * sourceWidth + ix]; val p11 = source[(iy + 1) * sourceWidth + ix + 1]
                    var color = 0xff000000.toInt()
                    for (shift in intArrayOf(16, 8, 0)) {
                        val a = (p00 ushr shift) and 255; val b = (p10 ushr shift) and 255
                        val c = (p01 ushr shift) and 255; val d = (p11 ushr shift) and 255
                        val channel = (a * (1-u) * (1-v) + b * u * (1-v) + c * (1-u) * v + d * u * v).roundToInt().coerceIn(0, 255)
                        color = color or (channel shl shift)
                    }
                    pixels[dy * width + x] = color; mask[dy * width + x] = 1
                }
            }
            if (cancelled()) throw CancellationException("Pyramid rectification cancelled")
            consume(Tile(start, count, pixels, mask))
        }
    }
}
