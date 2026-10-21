package com.realityforge.scanner.engine.features

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SparseFeatureCacheTest {
    private val hash = "a".repeat(64)
    private fun store(cache: SparseFeatureCache) = cache.store(hash, 100, 80,
        floatArrayOf(1f, 2f), intArrayOf(0xff123456.toInt()), FloatArray(128) { 32f })

    @Test fun productionRoundTripCorruptionAndChangedOriginalAreSafeMisses() {
        val root = Files.createTempDirectory("sparse-feature-adapter").toFile()
        try {
            val cache = SparseFeatureCache(root)
            assertTrue(store(cache))
            assertTrue(cache.artifactFile(hash).isFile)
            val restored = requireNotNull(cache.load(hash))
            assertArrayEquals(floatArrayOf(1f, 2f), restored.coordinates(), 0f)
            assertNull(cache.load("b".repeat(64)))
            val entry = root.listFiles()!!.single { it.extension == "rffc" }
            val bytes = entry.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            entry.writeBytes(bytes)
            assertNull(cache.load(hash))
            assertTrue(store(cache))
            assertNotNull(cache.load(hash))
        } finally { root.deleteRecursively() }
    }

    @Test fun reusableCodecCannotBypassProductionResourceProfile() {
        val root = Files.createTempDirectory("sparse-feature-limits").toFile()
        try {
            val generic = FeatureCache(root, SparseFeatureCache.BYTE_BUDGET)
            assertTrue(generic.store(CachedFeatures(SparseFeatureCache.identity(hash), 2000, 80,
                floatArrayOf(), intArrayOf(), floatArrayOf())))
            assertNull(SparseFeatureCache(root).load(hash))
        } finally { root.deleteRecursively() }
    }

    @Test fun cacheUnavailableDoesNotRequireDiscardingFreshFeatures() {
        val root = Files.createTempDirectory("sparse-feature-unavailable").toFile()
        try {
            val unavailable = File(root, "file-not-directory").apply { writeText("preserve") }
            val cache = SparseFeatureCache(unavailable)
            assertFalse(store(cache))
            assertNull(cache.load(hash))
            assertEquals("preserve", unavailable.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun decoderExtractorAndEngineChangesInvalidateIdentity() {
        val record = CachedFeatures(SparseFeatureCache.identity(hash), 80, 80,
            floatArrayOf(), intArrayOf(), floatArrayOf())
        val bytes = FeatureCacheFormat.encode(record)
        val identity = record.identity
        for (changed in listOf(identity.copy(decoderTransform="changed"),
            identity.copy(extractorSettings="changed"), identity.copy(openCvVersion="changed"))) {
            assertThrows(IllegalArgumentException::class.java) { FeatureCacheFormat.decode(bytes, changed) }
        }
    }
}
