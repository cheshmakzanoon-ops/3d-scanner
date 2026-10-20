package com.realityforge.scanner.engine.imaging

import com.realityforge.scanner.engine.geometry.PixelTransform
import java.io.Closeable
import java.security.MessageDigest

/** One owned level; no Bitmap/Mat/native pointer escapes. Closing clears managed buffers. */
class PyramidLevel(
    val identity: String, val width: Int, val height: Int,
    val originalToLevel: PixelTransform, pixels: IntArray, validity: ByteArray,
) : Closeable {
    private var data: IntArray? = pixels.copyOf()
    private var mask: ByteArray? = validity.copyOf()
    init {
        require(identity.matches(Regex("[0-9a-f]{64}")))
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= MAX_PIXELS)
        require(originalToLevel.width == width && originalToLevel.height == height)
        require(pixels.size == width * height && validity.size == pixels.size)
        require(validity.all { it == 0.toByte() || it == 1.toByte() })
    }
    @Synchronized fun pixels(): IntArray = checkNotNull(data) { "Level closed" }.copyOf()
    @Synchronized fun validity(): ByteArray = checkNotNull(mask) { "Level closed" }.copyOf()
    @Synchronized override fun close() { data?.fill(0); mask?.fill(0); data = null; mask = null }
    companion object {
        const val SCHEMA = 1
        const val MAX_PIXELS = 4_500_000L
        /** Inputs include source SHA256, calibration, border mode and full coordinate/settings identity. */
        fun identity(sourceSha256: String, settingsIdentity: String, transform: PixelTransform): String {
            require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
            require(settingsIdentity.isNotBlank() && settingsIdentity.length <= 8192)
            val fields = listOf("rf-pyramid-$SCHEMA", sourceSha256, settingsIdentity,
                PixelTransform.VERSION.toString(), transform.width.toString(), transform.height.toString(),
                transform.a.toHexString(), transform.b.toHexString(), transform.tx.toHexString(),
                transform.c.toHexString(), transform.d.toHexString(), transform.ty.toHexString())
            return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("\n").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        private fun Double.toHexString() = java.lang.Double.toHexString(this)
    }
}
