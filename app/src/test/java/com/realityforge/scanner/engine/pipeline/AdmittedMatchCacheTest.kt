package com.realityforge.scanner.engine.pipeline

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AdmittedMatchCacheTest {
    private val identity = PipelineStageIdentity(PipelineStage.MATCHING, "a".repeat(64), "b".repeat(64), "project", 1)
    private fun record(count: Int = 1) = FeatureMatchRecord(DoubleArray(count * 7) { if (it % 7 < 4) .01 else 127.0 },
        IntArray(count) { it }, IntArray(count) { it })

    @Test fun completeTwelvePhotoGraphIncludingNonAnchorPairsRoundTrips() {
        val root = Files.createTempDirectory("admitted-matches").toFile()
        try {
            val cache = FeatureMatchCache(root, identity, 12)
            val records = buildMap { for (a in 0..11) for (b in a + 1..11) put(a to b, record()) }
            assertEquals(66, records.size)
            cache.save(records)
            val loaded = requireNotNull(cache.load())
            assertEquals(records.keys, loaded.keys)
            assertArrayEquals(records.getValue(10 to 11).rows, loaded.getValue(10 to 11).rows, 0.0)
            assertArrayEquals(records.getValue(0 to 11).firstKeys, loaded.getValue(0 to 11).firstKeys)
        } finally { root.deleteRecursively() }
    }

    @Test fun maximumAdmittedRowsHaveExplicitSerializedBound() {
        val root = Files.createTempDirectory("admitted-max-matches").toFile()
        try {
            val row = record(4000)
            val records = buildMap { for (a in 0..11) for (b in a + 1..11) put(a to b, row) }
            val cache = FeatureMatchCache(root, identity, 12)
            cache.save(records)
            assertEquals(78L + 66L * (12L + 4000L * 64L), cache.file.length())
            assertTrue(cache.file.length() < 17L * 1024 * 1024)
            assertEquals(66, requireNotNull(cache.load()).size)
            assertThrows(IllegalArgumentException::class.java) { FeatureMatchCache(root, identity, 13) }
        } finally { root.deleteRecursively() }
    }

    @Test fun cancelledSerializationPreservesPreviousGraphAndCleansTemporaryFile() {
        val root = Files.createTempDirectory("admitted-cancel-matches").toFile()
        try {
            val cache = FeatureMatchCache(root, identity, 12)
            cache.save(mapOf((0 to 1) to record()))
            val before = cache.file.readBytes()
            var checks = 0
            assertThrows(CancellationException::class.java) {
                cache.save(mapOf((10 to 11) to record(1000))) {
                    if (++checks == 4) throw CancellationException("cancelled")
                }
            }
            assertArrayEquals(before, cache.file.readBytes())
            assertEquals(setOf(0 to 1), requireNotNull(cache.load()).keys)
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".partial") })
            assertThrows(CancellationException::class.java) { cache.load { throw CancellationException("cancelled") } }
        } finally { root.deleteRecursively() }
    }

    @Test fun changedIdentityAndOldFormatAreMissesEvenWithMatchingChecksum() {
        val root = Files.createTempDirectory("admitted-old-matches").toFile()
        try {
            val cache = FeatureMatchCache(root, identity, 12)
            cache.save(mapOf((10 to 11) to record()))
            assertNull(FeatureMatchCache(root, identity.copy(digest="c".repeat(64)), 12).load())
            val bytes = cache.file.readBytes()
            java.nio.ByteBuffer.wrap(bytes).putInt(0x52464d32)
            cache.file.writeBytes(bytes)
            File(root, "${cache.file.name}.sha256").writeText(PipelineArtifactSession.hashFile(cache.file).hex)
            assertNull(cache.load())
            assertArrayEquals(bytes, cache.file.readBytes())
        } finally { root.deleteRecursively() }
    }
}
