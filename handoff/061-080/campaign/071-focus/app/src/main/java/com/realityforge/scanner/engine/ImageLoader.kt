package com.realityforge.scanner.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object ImageLoader {
    const val ANALYSIS_EDGE = 1600
    fun bounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth in 3..32768 && options.outHeight in 3..32768) { "Unsupported or corrupt photograph." }
        require(options.outWidth.toLong() * options.outHeight <= 200_000_000) { "Image exceeds the 200-megapixel input safety limit." }
        return options.outWidth to options.outHeight
    }
    /** Decode at bounded resolution BEFORE orientation; original bytes are never modified. */
    fun upright(file: File, maxEdge: Int = ANALYSIS_EDGE): Bitmap {
        require(maxEdge in 32..4096)
        val (w, h) = bounds(file)
        var sample = 1
        while (max(w, h) / sample > maxEdge * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "Cannot decode photograph." }
        val exif = ExifInterface(file)
        val matrix = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        val scale = maxEdge.toDouble() / max(bitmap.width, bitmap.height)
        if (scale < 1.0) {
            val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(3), (bitmap.height * scale).roundToInt().coerceAtLeast(3), true)
            if (smaller !== bitmap) bitmap.recycle()
            bitmap = smaller
        }
        return bitmap
    }
    fun focal35(file: File): Double? = runCatching {
        ExifInterface(file).getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0).takeIf { it in 10.0..600.0 }
    }.getOrNull()
    fun focalPixels(width: Int, height: Int, focal35: Double): Double =
        focal35 * hypot(width.toDouble(), height.toDouble()) / hypot(36.0, 24.0)

    fun quality(bitmap: Bitmap): DoubleArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gray = ByteArray(pixels.size) { i ->
            val c = pixels[i]
            (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8).toByte()
        }
        return NativeCore.quality(gray, bitmap.width, bitmap.height)
    }
}
