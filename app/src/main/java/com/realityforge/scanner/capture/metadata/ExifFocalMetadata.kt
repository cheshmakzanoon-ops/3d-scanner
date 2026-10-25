package com.realityforge.scanner.capture.metadata

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Read-only shared focal interpretation for import and legacy ImageLoader callers. */
object ExifFocalMetadata {
    fun read(file: File, exif: ExifInterface = ExifInterface(file)): FieldEvidence<Double> {
        val origin = EvidenceOrigin.EXIF
        // A present malformed/direct tag is not silently replaced with other evidence.
        if (exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM) != null) {
            val direct = number(exif, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
            return direct?.takeIf { it.isFinite() && it in 10.0..600.0 }
                ?.let { FieldEvidence.observed(it, origin) } ?: FieldEvidence.missing(origin)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = integer(exif, ExifInterface.TAG_PIXEL_X_DIMENSION)
        val height = integer(exif, ExifInterface.TAG_PIXEL_Y_DIMENSION)
        // If TIFF and EXIF both declare dimensions they must agree as encoded axes.
        for ((tag, expected) in listOf(ExifInterface.TAG_IMAGE_WIDTH to width, ExifInterface.TAG_IMAGE_LENGTH to height)) {
            if (exif.getAttribute(tag) != null && integer(exif, tag) != expected) return FieldEvidence.missing(origin)
        }
        val orientation = integer(exif, ExifInterface.TAG_ORIENTATION)
        if (exif.getAttribute(ExifInterface.TAG_ORIENTATION) != null && orientation == null) return FieldEvidence.missing(origin)
        val zoom = number(exif, ExifInterface.TAG_DIGITAL_ZOOM_RATIO)
        if (exif.getAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO) != null && zoom == null) return FieldEvidence.missing(origin)
        val value = ExifFocalPlaneEstimate.equivalent35mm(
            number(exif, ExifInterface.TAG_FOCAL_LENGTH),
            number(exif, ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION),
            number(exif, ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION),
            integer(exif, ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT),
            width, height, bounds.outWidth, bounds.outHeight, orientation, zoom,
        ) ?: return FieldEvidence.missing(origin)
        return FieldEvidence.derived(value, setOf(origin, EvidenceOrigin.ENCODED_IMAGE_METADATA))
    }

    private fun number(exif: ExifInterface, tag: String): Double? =
        if (exif.getAttribute(tag) == null) null
        else runCatching { exif.getAttributeDouble(tag, Double.NaN) }.getOrNull()
    private fun integer(exif: ExifInterface, tag: String): Int? = exif.getAttribute(tag)?.toIntOrNull()
}
