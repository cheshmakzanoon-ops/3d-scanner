package com.realityforge.scanner.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

class DepthCheckpointIdentityTest {
    private fun pixels() = ByteArray(2 * 64 * 48 * 4) { index -> (index * 11).toByte() }
    private fun poses() = DoubleArray(32).also { values ->
        for (camera in 0..1) {
            values[camera * 16] = 1.0
            values[camera * 16 + 4] = 1.0
            values[camera * 16 + 8] = 1.0
            values[camera * 16 + 9] = -camera * .5
            values[camera * 16 + 12] = 80.0
            values[camera * 16 + 13] = 80.0
            values[camera * 16 + 14] = 31.5
            values[camera * 16 + 15] = 23.5
        }
    }
    private fun id(
        pixels: ByteArray = pixels(),
        poses: DoubleArray = poses(),
        near: Double = 3.0,
        key: String = "sparse-m16-r1-0123456789abcdef",
    ) = DepthCheckpointIdentity.fingerprint(key, pixels, poses, 64, 48, near, 7.0, 56, 3)

    @Test fun exactInputIsReproducibleButChangesInvalidateTheSha256Identity() {
        val original = id()
        assertEquals(64, original.length)
        assertEquals(original, id())
        val changed = pixels().also { it[100] = (it[100] + 1).toByte() }
        assertNotEquals(original, id(pixels = changed))
        val camera = poses().also { it[9] += 0.001 }
        assertNotEquals(original, id(poses = camera))
        assertNotEquals(original, id(near = 3.1))
        assertNotEquals(original, id(key = "sparse-m16-r2-0123456789abcdef"))
    }

    @Test fun m18FingerprintRemainsBitForBitCompatibleAndUnknownFormatsAreRejected() {
        val random = Random(9)
        repeat(50) { iteration ->
            val cameraCount = 2 + random.nextInt(3)
            val width = 32 + random.nextInt(32)
            val height = 32 + random.nextInt(24)
            val rows = DoubleArray(cameraCount * 16)
            for (camera in 0 until cameraCount) {
                rows[camera * 16] = 1.0
                rows[camera * 16 + 4] = 1.0
                rows[camera * 16 + 8] = 1.0
                rows[camera * 16 + 9] = -camera * .25
                rows[camera * 16 + 12] = 80.0
                rows[camera * 16 + 13] = 81.0
                rows[camera * 16 + 14] = (width - 1) / 2.0
                rows[camera * 16 + 15] = (height - 1) / 2.0
            }
            val rgba = ByteArray(cameraCount * width * height * 4).also(random::nextBytes)
            val key = "sparse-m16-r${iteration + 1}-${iteration.toString(16).padStart(16, '0')}"
            assertEquals(
                legacyFingerprint(key, rgba, rows, width, height, 2.0, 8.0, 56, 3),
                DepthCheckpointIdentity.fingerprint(key, rgba, rows, width, height, 2.0, 8.0, 56, 3),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DepthCheckpointIdentity.fingerprint(
                "sparse-m16-r1-0123456789abcdef", pixels(), poses(), 64, 48, 3.0, 7.0, 56, 3,
                DepthCheckpointIdentity.Compatibility(19, "future-depth-format\u0000"),
            )
        }
    }

    @Test fun malformedContextIsRejectedBeforeReadingAnyCache() {
        assertThrows(IllegalArgumentException::class.java) {
            DepthCheckpointIdentity.fingerprint("../escape", pixels(), poses(), 64, 48, 3.0, 7.0, 56, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DepthCheckpointIdentity.fingerprint(
                "sparse-m16-r1-0123456789abcdef", ByteArray(17), poses(), 64, 48, 3.0, 7.0, 56, 3,
            )
        }
    }

    @Test fun cacheRetainsCurrentAndAtMostOneRecentIdentityAndRemovesPartialSiblings() {
        val root = Files.createTempDirectory("rf-depth-jvm").toFile()
        try {
            val keys = listOf("1".repeat(64), "2".repeat(64), "3".repeat(64))
            for ((index, key) in keys.withIndex()) {
                val directory = DepthCheckpointIdentity.directory(root, key)
                java.io.File(directory, "ref-0.bin.partial-9").writeText("partial")
                DepthCheckpointIdentity.directory(root, key)
                assertFalse(java.io.File(directory, "ref-0.bin.partial-9").exists())
                directory.setLastModified(1_000L + index * 1_000)
            }
            assertFalse(java.io.File(root, ".depth-checkpoints-m18/${keys[0]}").exists())
            assertTrue(java.io.File(root, ".depth-checkpoints-m18/${keys[1]}").exists())
            assertTrue(java.io.File(root, ".depth-checkpoints-m18/${keys[2]}").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun legacyFingerprint(
        artifactKey: String,
        rgba: ByteArray,
        cameraRows: DoubleArray,
        width: Int,
        height: Int,
        near: Double,
        far: Double,
        planes: Int,
        stride: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("realityforge-m18-native-reference-depth-v1\u0000".toByteArray(StandardCharsets.UTF_8))
        digest.update(artifactKey.toByteArray(StandardCharsets.UTF_8))
        fun long(value: Long) {
            for (index in 0 until 8) digest.update((value ushr (8 * index)).toByte())
        }
        long(width.toLong())
        long(height.toLong())
        long(cameraRows.size.toLong())
        long(planes.toLong())
        long(stride.toLong())
        long(near.toBits())
        long(far.toBits())
        cameraRows.forEach { long(it.toBits()) }
        digest.update(rgba)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
