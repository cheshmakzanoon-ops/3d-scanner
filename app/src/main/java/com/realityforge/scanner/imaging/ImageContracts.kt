package com.realityforge.scanner.imaging

import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Formats intentionally supported by the offline photograph-analysis path. */
enum class ImageFormat {
    JPEG,
    PNG,
    WEBP,
    HEIF_HEIC;

    companion object {
        fun fromMimeType(mimeType: String?): ImageFormat? = when (mimeType?.lowercase(Locale.ROOT)) {
            "image/jpeg", "image/jpg" -> JPEG
            "image/png" -> PNG
            "image/webp" -> WEBP
            "image/heif", "image/heic", "image/heif-sequence", "image/heic-sequence" -> HEIF_HEIC
            else -> null
        }
    }
}

enum class ImageDecodeFailure {
    INVALID_REQUEST,
    FILE_NOT_FOUND,
    ENCODED_SIZE_LIMIT,
    UNSUPPORTED_FORMAT,
    CORRUPT_IMAGE,
    DIMENSION_LIMIT,
    SOURCE_PIXEL_LIMIT,
    RESOURCE_LIMIT,
    DECODE_FAILED,
    TRANSFORM_FAILED,
    CLOSED_IMAGE,
}

/** Typed failure retained through the legacy ImageLoader adapter as an IllegalArgumentException. */
class ImageDecodeException(
    val failure: ImageDecodeFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

enum class ImageDecodeWarning {
    EXIF_ORIENTATION_ABSENT,
    EXIF_ORIENTATION_MALFORMED,
}

enum class OrientationEvidence {
    PRESENT,
    ABSENT,
    MALFORMED,
}

data class PixelCoordinate(val x: Int, val y: Int)

/**
 * The eight TIFF/EXIF orientation values. Coordinate helpers are deliberately Android-free so
 * golden orientation behavior can be verified by local JVM tests.
 */
enum class ExifOrientation(
    val exifValue: Int,
    val swapsAxes: Boolean,
) {
    NORMAL(1, false),
    FLIP_HORIZONTAL(2, false),
    ROTATE_180(3, false),
    FLIP_VERTICAL(4, false),
    TRANSPOSE(5, true),
    ROTATE_90_CW(6, true),
    TRANSVERSE(7, true),
    ROTATE_270_CW(8, true);

    fun outputDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
        if (swapsAxes) sourceHeight to sourceWidth else sourceWidth to sourceHeight

    /** Return the source pixel represented by an upright output pixel. */
    fun sourceCoordinate(
        outputX: Int,
        outputY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): PixelCoordinate {
        require(sourceWidth > 0 && sourceHeight > 0)
        val (outputWidth, outputHeight) = outputDimensions(sourceWidth, sourceHeight)
        require(outputX in 0 until outputWidth && outputY in 0 until outputHeight)
        return when (this) {
            NORMAL -> PixelCoordinate(outputX, outputY)
            FLIP_HORIZONTAL -> PixelCoordinate(sourceWidth - 1 - outputX, outputY)
            ROTATE_180 -> PixelCoordinate(sourceWidth - 1 - outputX, sourceHeight - 1 - outputY)
            FLIP_VERTICAL -> PixelCoordinate(outputX, sourceHeight - 1 - outputY)
            TRANSPOSE -> PixelCoordinate(outputY, outputX)
            ROTATE_90_CW -> PixelCoordinate(outputY, sourceHeight - 1 - outputX)
            TRANSVERSE -> PixelCoordinate(sourceWidth - 1 - outputY, sourceHeight - 1 - outputX)
            ROTATE_270_CW -> PixelCoordinate(sourceWidth - 1 - outputY, outputX)
        }
    }

    /**
     * Source-to-target affine transform for Android Canvas, including final scaling. Values use
     * the standard 3x3 Matrix order [scaleX, skewX, translateX, skewY, scaleY, translateY, ...].
     */
    fun renderMatrixValues(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): FloatArray {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
        val (uprightWidth, uprightHeight) = outputDimensions(sourceWidth, sourceHeight)
        val base = when (this) {
            NORMAL -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            FLIP_HORIZONTAL -> doubleArrayOf(-1.0, 0.0, sourceWidth.toDouble(), 0.0, 1.0, 0.0)
            ROTATE_180 -> doubleArrayOf(
                -1.0, 0.0, sourceWidth.toDouble(),
                0.0, -1.0, sourceHeight.toDouble(),
            )
            FLIP_VERTICAL -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, sourceHeight.toDouble())
            TRANSPOSE -> doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
            ROTATE_90_CW -> doubleArrayOf(0.0, -1.0, sourceHeight.toDouble(), 1.0, 0.0, 0.0)
            TRANSVERSE -> doubleArrayOf(
                0.0, -1.0, sourceHeight.toDouble(),
                -1.0, 0.0, sourceWidth.toDouble(),
            )
            ROTATE_270_CW -> doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, sourceWidth.toDouble())
        }
        val sx = targetWidth.toDouble() / uprightWidth
        val sy = targetHeight.toDouble() / uprightHeight
        return floatArrayOf(
            (base[0] * sx).toFloat(),
            (base[1] * sx).toFloat(),
            (base[2] * sx).toFloat(),
            (base[3] * sy).toFloat(),
            (base[4] * sy).toFloat(),
            (base[5] * sy).toFloat(),
            0f,
            0f,
            1f,
        )
    }

    companion object {
        fun fromExifValue(value: Int): ExifOrientation? = entries.firstOrNull { it.exifValue == value }
    }
}

data class ImageBounds(
    val width: Int,
    val height: Int,
    val format: ImageFormat,
    val encodedBytes: Long,
) {
    val pixelCount: Long get() = width.toLong() * height
}

data class DecodeRequest(
    val file: File,
    val maxEdge: Int = ImageDecodeLimits.DEFAULT_MAX_EDGE,
    val maxEncodedBytes: Long = ImageDecodeLimits.DEFAULT_MAX_ENCODED_BYTES,
    val maxSourcePixels: Long = ImageDecodeLimits.DEFAULT_MAX_SOURCE_PIXELS,
    val maxPeakBytes: Long = ImageDecodeLimits.DEFAULT_MAX_PEAK_BYTES,
    val applyExifOrientation: Boolean = true,
    val allowedFormats: Set<ImageFormat> = ImageDecodeLimits.DEFAULT_FORMATS,
)

object ImageDecodeLimits {
    const val MIN_SOURCE_DIMENSION = 3
    const val MAX_SOURCE_DIMENSION = 32_768
    const val MIN_MAX_EDGE = 32
    const val MAX_MAX_EDGE = 4_096
    const val DEFAULT_MAX_EDGE = 1_600
    const val DEFAULT_MAX_SOURCE_PIXELS = 200_000_000L
    const val DEFAULT_MAX_ENCODED_BYTES = 150L * 1024 * 1024
    const val DEFAULT_MAX_PEAK_BYTES = 64L * 1024 * 1024
    const val MIN_PEAK_BYTES = 1L * 1024 * 1024
    const val MAX_PEAK_BYTES = 512L * 1024 * 1024
    const val ARGB_8888_BYTES_PER_PIXEL = 4L
    const val PLANNER_OVERHEAD_BYTES = 256L * 1024

    val DEFAULT_FORMATS: Set<ImageFormat> = setOf(
        ImageFormat.JPEG,
        ImageFormat.PNG,
        ImageFormat.WEBP,
        ImageFormat.HEIF_HEIC,
    )
}

data class DecodePlan(
    val sampleSize: Int,
    val sampledWidth: Int,
    val sampledHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val estimatedPeakBytes: Long,
)

object DecodePlanner {
    fun validateRequest(request: DecodeRequest) {
        if (request.maxEdge !in ImageDecodeLimits.MIN_MAX_EDGE..ImageDecodeLimits.MAX_MAX_EDGE) {
            fail(ImageDecodeFailure.INVALID_REQUEST, "Maximum decode edge must be 32–4096 pixels.")
        }
        if (request.maxEncodedBytes !in 1..(2L * 1024 * 1024 * 1024)) {
            fail(ImageDecodeFailure.INVALID_REQUEST, "Encoded-byte limit is invalid.")
        }
        if (request.maxSourcePixels !in 1..500_000_000L) {
            fail(ImageDecodeFailure.INVALID_REQUEST, "Source-pixel limit is invalid.")
        }
        if (request.maxPeakBytes !in ImageDecodeLimits.MIN_PEAK_BYTES..ImageDecodeLimits.MAX_PEAK_BYTES) {
            fail(ImageDecodeFailure.INVALID_REQUEST, "Peak decode budget must be 1–512 MiB.")
        }
        if (request.allowedFormats.isEmpty()) {
            fail(ImageDecodeFailure.INVALID_REQUEST, "At least one image format must be enabled.")
        }
    }

    fun validateBounds(bounds: ImageBounds, request: DecodeRequest) {
        validateRequest(request)
        if (bounds.format !in request.allowedFormats) {
            fail(ImageDecodeFailure.UNSUPPORTED_FORMAT, "Image format ${bounds.format} is not enabled.")
        }
        if (bounds.encodedBytes !in 1..request.maxEncodedBytes) {
            fail(
                ImageDecodeFailure.ENCODED_SIZE_LIMIT,
                "Photograph exceeds the ${request.maxEncodedBytes}-byte encoded input limit.",
            )
        }
        if (bounds.width !in ImageDecodeLimits.MIN_SOURCE_DIMENSION..ImageDecodeLimits.MAX_SOURCE_DIMENSION ||
            bounds.height !in ImageDecodeLimits.MIN_SOURCE_DIMENSION..ImageDecodeLimits.MAX_SOURCE_DIMENSION
        ) {
            fail(ImageDecodeFailure.DIMENSION_LIMIT, "Photograph dimensions are unsupported.")
        }
        if (bounds.pixelCount > request.maxSourcePixels) {
            fail(
                ImageDecodeFailure.SOURCE_PIXEL_LIMIT,
                "Photograph exceeds the ${request.maxSourcePixels}-pixel input safety limit.",
            )
        }
    }

    fun plan(
        bounds: ImageBounds,
        orientation: ExifOrientation,
        request: DecodeRequest,
    ): DecodePlan {
        validateBounds(bounds, request)
        var sampleSize = 1
        while (true) {
            val sampledWidth = ceilDiv(bounds.width, sampleSize)
            val sampledHeight = ceilDiv(bounds.height, sampleSize)
            val (uprightWidth, uprightHeight) = orientation.outputDimensions(sampledWidth, sampledHeight)
            val (outputWidth, outputHeight) = fitWithin(uprightWidth, uprightHeight, request.maxEdge)
            val decodedBytes = checkedBitmapBytes(sampledWidth, sampledHeight)
            val outputBytes = checkedBitmapBytes(outputWidth, outputHeight)
            // Conservatively reserve both source and rendered destination plus small decoder overhead.
            val estimatedPeak = safeAdd(
                safeAdd(decodedBytes, outputBytes),
                ImageDecodeLimits.PLANNER_OVERHEAD_BYTES,
            )
            val edgeBounded = max(sampledWidth, sampledHeight) <= request.maxEdge * 2
            if (edgeBounded && estimatedPeak <= request.maxPeakBytes) {
                return DecodePlan(
                    sampleSize,
                    sampledWidth,
                    sampledHeight,
                    outputWidth,
                    outputHeight,
                    estimatedPeak,
                )
            }
            if (sampleSize >= ImageDecodeLimits.MAX_SOURCE_DIMENSION) {
                fail(
                    ImageDecodeFailure.RESOURCE_LIMIT,
                    "Image cannot fit the ${request.maxPeakBytes}-byte decode budget.",
                )
            }
            sampleSize = (sampleSize * 2).coerceAtMost(ImageDecodeLimits.MAX_SOURCE_DIMENSION)
        }
    }

    fun fitWithin(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxEdge > 0)
        val largest = max(width, height)
        if (largest <= maxEdge) {
            return width.coerceAtLeast(ImageDecodeLimits.MIN_SOURCE_DIMENSION) to
                height.coerceAtLeast(ImageDecodeLimits.MIN_SOURCE_DIMENSION)
        }
        val scale = maxEdge.toDouble() / largest
        // Preserve the legacy three-pixel minimum so downstream native kernels never receive a
        // degenerate one- or two-pixel axis from an extreme panorama.
        return (width * scale).roundToInt().coerceIn(ImageDecodeLimits.MIN_SOURCE_DIMENSION, maxEdge) to
            (height * scale).roundToInt().coerceIn(ImageDecodeLimits.MIN_SOURCE_DIMENSION, maxEdge)
    }

    fun checkedBitmapBytes(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) {
            fail(ImageDecodeFailure.CORRUPT_IMAGE, "Decoded image has invalid dimensions.")
        }
        val pixels = width.toLong() * height
        if (pixels > Long.MAX_VALUE / ImageDecodeLimits.ARGB_8888_BYTES_PER_PIXEL) {
            fail(ImageDecodeFailure.RESOURCE_LIMIT, "Bitmap allocation size overflow.")
        }
        return pixels * ImageDecodeLimits.ARGB_8888_BYTES_PER_PIXEL
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ceil(value.toDouble() / divisor).toInt().coerceAtLeast(1)

    private fun safeAdd(a: Long, b: Long): Long {
        if (a > Long.MAX_VALUE - b) {
            fail(ImageDecodeFailure.RESOURCE_LIMIT, "Bitmap allocation size overflow.")
        }
        return a + b
    }

    private fun fail(failure: ImageDecodeFailure, message: String): Nothing =
        throw ImageDecodeException(failure, message)
}

data class DecodedImageInfo(
    val source: ImageBounds,
    val orientation: ExifOrientation,
    val orientationEvidence: OrientationEvidence,
    val sampleSize: Int,
    val width: Int,
    val height: Int,
    val estimatedPeakBytes: Long,
    val measuredPeakBytes: Long,
    val warnings: Set<ImageDecodeWarning>,
    val colorContract: String = AnalysisColorContract.DECODED_COLOR_CONTRACT,
    val alphaContract: String = AnalysisColorContract.DECODED_ALPHA_CONTRACT,
)

object AnalysisColorContract {
    const val DECODED_COLOR_CONTRACT = "sRGB ARGB_8888"
    const val DECODED_ALPHA_CONTRACT = "decoded alpha preserved; analysis composites transparency over black"
    const val LUMA_CONTRACT = "integer Rec.601 approximation: (77R + 150G + 29B) / 256, alpha over black"

    fun lumaFromArgb(argb: Int): Int {
        val alpha = argb ushr 24 and 0xff
        val red = argb ushr 16 and 0xff
        val green = argb ushr 8 and 0xff
        val blue = argb and 0xff
        // Preserve the legacy quality path exactly for opaque pixels; only alpha handling is new.
        val opaqueLuma = (red * 77 + green * 150 + blue * 29) ushr 8
        return (opaqueLuma * alpha + 127) / 255
    }
}
