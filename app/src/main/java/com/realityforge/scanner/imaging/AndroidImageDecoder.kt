package com.realityforge.scanner.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Offline, bounded Android decoder. It never writes to the source file. */
object AndroidImageDecoder {
    private val srgb: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    suspend fun decode(request: DecodeRequest): OwnedImage = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val owned = decodeBlocking(request)
        try {
            // Cancellation delivered while BitmapFactory was executing must not strand its bitmap.
            coroutineContext.ensureActive()
            owned
        } catch (cancelled: CancellationException) {
            owned.close()
            throw cancelled
        }
    }

    fun inspect(request: DecodeRequest): ImageBounds {
        DecodePlanner.validateRequest(request)
        val file = request.file
        if (!file.isFile) {
            fail(ImageDecodeFailure.FILE_NOT_FOUND, "Photograph does not exist or is not a regular file.")
        }
        val encodedBytes = file.length()
        if (encodedBytes !in 1..request.maxEncodedBytes) {
            fail(
                ImageDecodeFailure.ENCODED_SIZE_LIMIT,
                "Photograph exceeds the ${request.maxEncodedBytes}-byte encoded input limit.",
            )
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.path, options)
        } catch (failure: OutOfMemoryError) {
            fail(ImageDecodeFailure.RESOURCE_LIMIT, "Image header inspection exhausted memory.", failure)
        } catch (failure: RuntimeException) {
            fail(ImageDecodeFailure.CORRUPT_IMAGE, "Image header is malformed.", failure)
        }
        val mimeType = options.outMimeType?.lowercase(Locale.ROOT)
        val format = ImageFormat.fromMimeType(mimeType)
        if (format == null) {
            if (mimeType != null) {
                fail(ImageDecodeFailure.UNSUPPORTED_FORMAT, "Unsupported image format: $mimeType.")
            }
            fail(ImageDecodeFailure.CORRUPT_IMAGE, "Unsupported or corrupt photograph.")
        }
        val bounds = ImageBounds(options.outWidth, options.outHeight, format, encodedBytes)
        DecodePlanner.validateBounds(bounds, request)
        return bounds
    }

    fun decodeBlocking(request: DecodeRequest): OwnedImage {
        val bounds = inspect(request)
        val orientationRead = if (request.applyExifOrientation) readOrientation(request.file)
            else OrientationRead(ExifOrientation.NORMAL, OrientationEvidence.ABSENT, emptySet())
        val plan = DecodePlanner.plan(bounds, orientationRead.orientation, request)
        var decoded: Bitmap? = null
        var rendered: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = plan.sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = srgb
                inScaled = false
                inMutable = false
            }
            decoded = try {
                BitmapFactory.decodeFile(request.file.path, options)
            } catch (failure: RuntimeException) {
                fail(ImageDecodeFailure.DECODE_FAILED, "Image pixel decoder failed.", failure)
            } ?: fail(ImageDecodeFailure.DECODE_FAILED, "Cannot decode photograph pixels.")
            if (decoded.isRecycled || decoded.width <= 0 || decoded.height <= 0) {
                fail(ImageDecodeFailure.DECODE_FAILED, "Decoder returned an invalid bitmap.")
            }
            val (uprightWidth, uprightHeight) = orientationRead.orientation.outputDimensions(
                decoded.width,
                decoded.height,
            )
            val (outputWidth, outputHeight) = DecodePlanner.fitWithin(
                uprightWidth,
                uprightHeight,
                request.maxEdge,
            )
            val decodedBytes = allocationBytes(decoded)
            val outputBytes = DecodePlanner.checkedBitmapBytes(outputWidth, outputHeight)
            val requiresRender = orientationRead.orientation != ExifOrientation.NORMAL ||
                outputWidth != decoded.width || outputHeight != decoded.height ||
                decoded.config != Bitmap.Config.ARGB_8888 || decoded.colorSpace?.isSrgb != true
            var measuredPeak = if (requiresRender) checkedAdd(decodedBytes, outputBytes) else decodedBytes
            if (measuredPeak > request.maxPeakBytes) {
                fail(
                    ImageDecodeFailure.RESOURCE_LIMIT,
                    "Decoded image requires $measuredPeak bytes, above the ${request.maxPeakBytes}-byte budget.",
                )
            }
            val finalBitmap = if (requiresRender) {
                val sourceBitmap = requireNotNull(decoded)
                rendered = Bitmap.createBitmap(
                    outputWidth,
                    outputHeight,
                    Bitmap.Config.ARGB_8888,
                    sourceBitmap.hasAlpha(),
                    srgb,
                )
                measuredPeak = checkedAdd(decodedBytes, allocationBytes(requireNotNull(rendered)))
                if (measuredPeak > request.maxPeakBytes) {
                    fail(
                        ImageDecodeFailure.RESOURCE_LIMIT,
                        "Rendered image requires $measuredPeak bytes, above the ${request.maxPeakBytes}-byte budget.",
                    )
                }
                val targetBitmap = requireNotNull(rendered)
                val matrix = Matrix().apply {
                    setValues(
                        orientationRead.orientation.renderMatrixValues(
                            sourceBitmap.width,
                            sourceBitmap.height,
                            outputWidth,
                            outputHeight,
                        ),
                    )
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                Canvas(targetBitmap).drawBitmap(sourceBitmap, matrix, paint)
                sourceBitmap.recycle()
                decoded = null
                targetBitmap
            } else {
                requireNotNull(decoded)
            }
            val info = DecodedImageInfo(
                source = bounds,
                orientation = orientationRead.orientation,
                orientationEvidence = orientationRead.evidence,
                sampleSize = plan.sampleSize,
                width = finalBitmap.width,
                height = finalBitmap.height,
                estimatedPeakBytes = plan.estimatedPeakBytes,
                measuredPeakBytes = measuredPeak,
                warnings = orientationRead.warnings,
            )
            if (finalBitmap === rendered) rendered = null else decoded = null
            return OwnedImage(finalBitmap, info)
        } catch (failure: ImageDecodeException) {
            throw failure
        } catch (failure: OutOfMemoryError) {
            fail(ImageDecodeFailure.RESOURCE_LIMIT, "Image decode exceeded the available memory budget.", failure)
        } catch (failure: RuntimeException) {
            fail(ImageDecodeFailure.TRANSFORM_FAILED, "Image orientation or color conversion failed.", failure)
        } finally {
            recycle(decoded)
            recycle(rendered)
        }
    }

    private fun readOrientation(file: File): OrientationRead = try {
        val attribute = ExifInterface(file).getAttribute(ExifInterface.TAG_ORIENTATION)
        if (attribute == null) {
            OrientationRead(
                ExifOrientation.NORMAL,
                OrientationEvidence.ABSENT,
                setOf(ImageDecodeWarning.EXIF_ORIENTATION_ABSENT),
            )
        } else {
            val orientation = attribute.trim().toIntOrNull()?.let(ExifOrientation::fromExifValue)
            if (orientation == null) {
                OrientationRead(
                    ExifOrientation.NORMAL,
                    OrientationEvidence.MALFORMED,
                    setOf(ImageDecodeWarning.EXIF_ORIENTATION_MALFORMED),
                )
            } else OrientationRead(orientation, OrientationEvidence.PRESENT, emptySet())
        }
    } catch (_: IOException) {
        OrientationRead(
            ExifOrientation.NORMAL,
            OrientationEvidence.MALFORMED,
            setOf(ImageDecodeWarning.EXIF_ORIENTATION_MALFORMED),
        )
    } catch (_: RuntimeException) {
        OrientationRead(
            ExifOrientation.NORMAL,
            OrientationEvidence.MALFORMED,
            setOf(ImageDecodeWarning.EXIF_ORIENTATION_MALFORMED),
        )
    }

    private fun allocationBytes(bitmap: Bitmap): Long = try {
        bitmap.allocationByteCount.toLong()
    } catch (_: RuntimeException) {
        DecodePlanner.checkedBitmapBytes(bitmap.width, bitmap.height)
    }

    private fun checkedAdd(first: Long, second: Long): Long {
        if (first > Long.MAX_VALUE - second) {
            fail(ImageDecodeFailure.RESOURCE_LIMIT, "Bitmap allocation size overflow.")
        }
        return first + second
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun fail(
        failure: ImageDecodeFailure,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw ImageDecodeException(failure, message, cause)

    private data class OrientationRead(
        val orientation: ExifOrientation,
        val evidence: OrientationEvidence,
        val warnings: Set<ImageDecodeWarning>,
    )
}
