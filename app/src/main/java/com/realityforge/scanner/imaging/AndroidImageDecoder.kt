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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Offline, bounded Android decoder. It never writes to the source file. */
object AndroidImageDecoder {
    const val POLICY_IDENTITY = "rf-owned-image-v3:srgb-argb8888:exif8-centres:ceil-power2:defaultEdge1600:defaultPeak67108864"
    private val srgb: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    suspend fun decode(request: DecodeRequest): OwnedImage = decodeOwned(Dispatchers.IO) {
        decodeBlocking(request)
    }

    /** Keep ownership outside withContext: prompt cancellation can discard its return value
     * while dispatching back to the caller, even after an IO-side ensureActive succeeded.
     * The IO block returns Unit; only a successful dispatcher handoff transfers the owner.
     */
    internal suspend fun decodeOwned(dispatcher: CoroutineDispatcher, decode: () -> OwnedImage): OwnedImage {
        var owned: OwnedImage? = null
        try {
            withContext(dispatcher) {
                coroutineContext.ensureActive()
                owned = decode()
                coroutineContext.ensureActive()
            }
            val result = checkNotNull(owned)
            owned = null
            return result
        } finally {
            owned?.close()
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
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            fail(ImageDecodeFailure.CORRUPT_IMAGE, "Image header has no valid pixel dimensions.")
        }
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

    /** Validated header/EXIF inspection only: no pixel bitmap is allocated. */
    fun inspectUpright(request: DecodeRequest): ImageInspection {
        val bounds = inspect(request)
        val orientation = if (request.applyExifOrientation) readOrientation(request.file, bounds.format)
            else OrientationRead(ExifOrientation.NORMAL, OrientationEvidence.ABSENT, emptySet())
        return ImageInspection(bounds, orientation.orientation, orientation.evidence, orientation.warnings)
    }

    fun decodeBlocking(request: DecodeRequest): OwnedImage {
        val inspection = inspectUpright(request)
        val bounds = inspection.source
        val plan = DecodePlanner.plan(bounds, inspection.orientation, request)
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
            val (uprightWidth, uprightHeight) = inspection.orientation.outputDimensions(
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
            val requiresRender = inspection.orientation != ExifOrientation.NORMAL ||
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
                        inspection.orientation.renderMatrixValues(
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
                orientation = inspection.orientation,
                orientationEvidence = inspection.orientationEvidence,
                sampleSize = plan.sampleSize,
                width = finalBitmap.width,
                height = finalBitmap.height,
                estimatedPeakBytes = plan.estimatedPeakBytes,
                measuredPeakBytes = measuredPeak,
                warnings = inspection.warnings,
            )
            // OwnedImage/its lock can allocate. Keep bitmap tracking live until
            // construction succeeds so a late allocation failure still recycles it.
            val owner = OwnedImage(finalBitmap, info)
            if (finalBitmap === rendered) rendered = null else decoded = null
            return owner
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

    private fun readOrientation(file: File, format: ImageFormat): OrientationRead = try {
        val exif = ExifInterface(file)
        val attribute = exif.getAttribute(ExifInterface.TAG_ORIENTATION)
        val range = exif.getAttributeRange(ExifInterface.TAG_ORIENTATION)
        val value = attribute?.trim()?.toIntOrNull()
        val orientation = value?.let(ExifOrientation::fromExifValue)
        if (orientation != null) {
            // Valid derived HEIF rotations can lack a physical TIFF byte range.
            OrientationRead(orientation, OrientationEvidence.PRESENT, emptySet())
        } else {
            // AndroidX synthesizes undefined0 both for genuinely missing tags and
            // discarded malformed entries (e.g. incompatible ASCII storage). Its
            // attribute/range API alone cannot prove absence. Only the bounded
            // independent container reader may do that; uncertainty is not absence.
            val synthetic = (attribute == null && range == null) ||
                (value == ExifInterface.ORIENTATION_UNDEFINED && (range == null || range[0] < 0))
            val absent = synthetic && PhysicalOrientationPresence.read(file, format) ==
                PhysicalOrientationPresence.Evidence.ABSENT
            OrientationRead(
                ExifOrientation.NORMAL,
                if (absent) OrientationEvidence.ABSENT else OrientationEvidence.MALFORMED,
                setOf(if (absent) ImageDecodeWarning.EXIF_ORIENTATION_ABSENT
                    else ImageDecodeWarning.EXIF_ORIENTATION_MALFORMED),
            )
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
