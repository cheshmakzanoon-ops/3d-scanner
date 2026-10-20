package com.realityforge.scanner.imaging

import android.graphics.Bitmap

/** Bounded ARGB-to-luma conversion used by native quality analysis. */
object AnalysisPixels {
    const val MAX_ANALYSIS_PIXELS = 16_777_216L

    fun estimatedWorkingBytes(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) {
            throw ImageDecodeException(ImageDecodeFailure.CORRUPT_IMAGE, "Bitmap dimensions are invalid.")
        }
        val pixels = width.toLong() * height
        if (pixels > MAX_ANALYSIS_PIXELS) {
            throw ImageDecodeException(
                ImageDecodeFailure.RESOURCE_LIMIT,
                "Quality analysis is limited to $MAX_ANALYSIS_PIXELS pixels.",
            )
        }
        return pixels + width.toLong() * Int.SIZE_BYTES
    }

    fun grayscale(bitmap: Bitmap): ByteArray {
        if (bitmap.isRecycled) {
            throw ImageDecodeException(ImageDecodeFailure.CLOSED_IMAGE, "Cannot analyze a recycled bitmap.")
        }
        estimatedWorkingBytes(bitmap.width, bitmap.height)
        try {
            val gray = ByteArray(bitmap.width * bitmap.height)
            val row = IntArray(bitmap.width)
            var destination = 0
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (pixel in row) gray[destination++] = AnalysisColorContract.lumaFromArgb(pixel).toByte()
            }
            return gray
        } catch (failure: OutOfMemoryError) {
            throw ImageDecodeException(
                ImageDecodeFailure.RESOURCE_LIMIT,
                "Quality analysis exceeded the available memory budget.",
                failure,
            )
        }
    }
}
