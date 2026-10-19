package com.realityforge.scanner.capture.analysis

/**
 * Upright, tightly packed, bounded luma pixels. [sourceRotationDegrees] records the CameraX
 * rotation that was applied while making this frame upright.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    pixels: ByteArray,
    val timestampNanos: Long,
    val sourceRotationDegrees: Int,
) {
    val pixels: ByteArray = pixels.copyOf()

    init {
        require(width >= 3 && height >= 3)
        require(width.toLong() * height <= Int.MAX_VALUE)
        require(pixels.size == width * height)
        require(timestampNanos >= 0)
        require(sourceRotationDegrees in CaptureQualityObservation.VALID_ROTATIONS)
    }

    val byteCount: Int get() = pixels.size

    fun retainedCopy(): LumaFrame = LumaFrame(
        width = width,
        height = height,
        pixels = pixels,
        timestampNanos = timestampNanos,
        sourceRotationDegrees = sourceRotationDegrees,
    )

    internal fun unsigned(index: Int): Int = pixels[index].toInt() and 0xff
}
