package com.realityforge.scanner.engine.features

import org.junit.Assert.*
import org.junit.Test

/** Run also with -Xmx48m: each malformed input fits, but a second copy does not.
 * These are synthetic caller arrays, not claims about corrupt on-disk cache decoding.
 */
class FeatureCacheAdmissionTest {
    private val identity = FeatureCacheIdentity("a".repeat(64), "decoder", "sift", "4.12.0")

    @Test fun oversizedCoordinatesRejectBeforeDefensiveCopy() {
        val oversized = FloatArray(8_000_000)
        assertThrows(IllegalArgumentException::class.java) {
            CachedFeatures(identity, 32, 32, oversized, IntArray(0), FloatArray(0))
        }
    }

    @Test fun oversizedColorsRejectBeforeDefensiveCopy() {
        val oversized = IntArray(8_000_000)
        assertThrows(IllegalArgumentException::class.java) {
            CachedFeatures(identity, 32, 32, FloatArray(0), oversized, FloatArray(0))
        }
    }

    @Test fun oversizedDescriptorsRejectBeforeDefensiveCopy() {
        val oversized = FloatArray(8_000_000)
        assertThrows(IllegalArgumentException::class.java) {
            CachedFeatures(identity, 32, 32, FloatArray(0), IntArray(0), oversized)
        }
    }

    @Test fun dimensionsAndShapesRejectWithoutChangingAcceptedBoundary() {
        for ((width, height) in listOf(2 to 32, 32 to 2, 4097 to 32, 32 to 4097,
            Int.MIN_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                CachedFeatures(identity, width, height, FloatArray(0), IntArray(0), FloatArray(0))
            }
        }
        for ((xy, colors, desc) in listOf(
            Triple(FloatArray(1), IntArray(0), FloatArray(0)),
            Triple(FloatArray(2), IntArray(1), FloatArray(127)),
            Triple(FloatArray(2), IntArray(1), FloatArray(129)),
            Triple(FloatArray(0), IntArray(20_001), FloatArray(0)))) {
            assertThrows(IllegalArgumentException::class.java) {
                CachedFeatures(identity, 32, 32, xy, colors, desc)
            }
        }
        assertEquals(0, CachedFeatures(identity, 3, 4096, FloatArray(0), IntArray(0), FloatArray(0)).count)
        assertEquals(20_000, CachedFeatures(identity, 4096, 3,
            FloatArray(40_000), IntArray(20_000), FloatArray(20_000 * 128)).count)
    }

    @Test fun admittedRecordStillDefensivelyCopiesInputsAndOutputs() {
        val xy = floatArrayOf(1f, 2f)
        val colors = intArrayOf(0xff123456.toInt())
        val descriptors = FloatArray(128) { 5f }
        val record = CachedFeatures(identity, 32, 32, xy, colors, descriptors)
        xy.fill(Float.NaN); colors.fill(0); descriptors.fill(Float.NaN)
        record.coordinates().fill(Float.NaN)
        record.colors().fill(0)
        record.descriptors().fill(Float.NaN)
        assertArrayEquals(floatArrayOf(1f, 2f), record.coordinates(), 0f)
        assertArrayEquals(intArrayOf(0xff123456.toInt()), record.colors())
        assertArrayEquals(FloatArray(128) { 5f }, record.descriptors(), 0f)
        val reopened = FeatureCacheFormat.decode(FeatureCacheFormat.encode(record), identity)
        assertArrayEquals(record.descriptors(), reopened.descriptors(), 0f)
    }
}
