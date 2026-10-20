package com.realityforge.scanner.imaging

import android.graphics.Bitmap
import java.io.Closeable

/**
 * Sole owner of a decoded bitmap. Close is idempotent and recycles exactly once. A borrowed bitmap
 * is valid only while this owner remains open; [transferBitmap] explicitly hands recycling duty to
 * the caller for legacy APIs that cannot yet carry an OwnedImage.
 */
class OwnedImage internal constructor(
    bitmap: Bitmap,
    val info: DecodedImageInfo,
) : Closeable {
    private val lock = Any()
    private var ownedBitmap: Bitmap? = bitmap

    val isClosed: Boolean
        get() = synchronized(lock) { ownedBitmap == null }

    val width: Int
        get() = withBitmap { it.width }

    val height: Int
        get() = withBitmap { it.height }

    fun <T> withBitmap(block: (Bitmap) -> T): T = synchronized(lock) {
        block(requireOpen())
    }

    /** Borrow without transferring ownership. The returned value must not outlive this owner. */
    fun borrowBitmap(): Bitmap = synchronized(lock) { requireOpen() }

    /** Transfer ownership to a compatibility caller, which must recycle the returned bitmap. */
    fun transferBitmap(): Bitmap = synchronized(lock) {
        val bitmap = requireOpen()
        ownedBitmap = null
        bitmap
    }

    override fun close() {
        val bitmap = synchronized(lock) {
            val value = ownedBitmap
            ownedBitmap = null
            value
        }
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun requireOpen(): Bitmap = ownedBitmap?.takeUnless { it.isRecycled }
        ?: throw ImageDecodeException(ImageDecodeFailure.CLOSED_IMAGE, "Decoded image is already closed.")
}
