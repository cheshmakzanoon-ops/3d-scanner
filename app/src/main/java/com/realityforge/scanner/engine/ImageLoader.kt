package com.realityforge.scanner.engine

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.realityforge.scanner.imaging.AnalysisPixels
import com.realityforge.scanner.imaging.AndroidImageDecoder
import com.realityforge.scanner.imaging.DecodeRequest
import com.realityforge.scanner.imaging.ImageDecodeLimits
import com.realityforge.scanner.imaging.OwnedImage
import java.io.File
import kotlin.math.hypot

/**
 * Compatibility surface for the existing engine. New code should prefer [decode] or
 * [decodeBlocking], retain the returned [OwnedImage] only for the operation that owns it, and close
 * it deterministically. [upright] transfers a caller-owned bitmap for legacy call sites; callers
 * must recycle it. All decode methods are read-only with respect to the original file.
 */
object ImageLoader {
    const val ANALYSIS_EDGE = ImageDecodeLimits.DEFAULT_MAX_EDGE

    /** Full encoded dimensions before EXIF orientation. */
    fun bounds(file: File): Pair<Int, Int> =
        AndroidImageDecoder.inspect(DecodeRequest(file = file)).let { it.width to it.height }

    /** Off-main-thread owned decode for new import, capture, analysis and preview integrations. */
    suspend fun decode(request: DecodeRequest): OwnedImage = AndroidImageDecoder.decode(request)

    /** Blocking owned decode for existing background engine stages and deterministic tests. */
    fun decodeBlocking(request: DecodeRequest): OwnedImage = AndroidImageDecoder.decodeBlocking(request)

    /**
     * Decode at bounded resolution, apply all eight EXIF orientations and convert to sRGB
     * ARGB_8888. Ownership is transferred to the caller, which must recycle the bitmap.
     */
    fun upright(file: File, maxEdge: Int = ANALYSIS_EDGE): Bitmap {
        val owned = decodeBlocking(DecodeRequest(file = file, maxEdge = maxEdge))
        return try {
            owned.transferBitmap()
        } finally {
            owned.close()
        }
    }

    /** Optional 35 mm-equivalent focal metadata. Missing or malformed EXIF remains unknown. */
    fun focal35(file: File): Double? = runCatching {
        ExifInterface(file)
            .getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0)
            .takeIf { it in 10.0..600.0 }
    }.getOrNull()

    fun focalPixels(width: Int, height: Int, focal35: Double): Double =
        focal35 * hypot(width.toDouble(), height.toDouble()) / hypot(36.0, 24.0)

    /**
     * Convert one bitmap row at a time into a single luma buffer. This avoids the previous
     * bitmap-sized IntArray plus bitmap-sized ByteArray overlap. Transparent pixels are composited
     * over black according to AnalysisColorContract before native quality analysis.
     */
    fun quality(bitmap: Bitmap): DoubleArray =
        NativeCore.quality(AnalysisPixels.grayscale(bitmap), bitmap.width, bitmap.height)
}
