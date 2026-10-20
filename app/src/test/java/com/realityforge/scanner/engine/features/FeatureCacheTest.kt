package com.realityforge.scanner.engine.features
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
class FeatureCacheTest {
    private val identity=FeatureCacheIdentity("a".repeat(64),"decoder-v2","sift3500-v2","4.12.0")
    private fun record(id:FeatureCacheIdentity=identity)=CachedFeatures(id,100,80,floatArrayOf(1.2f,3.4f,50f,60f),intArrayOf(7,8),FloatArray(256){it%128.toFloat()})
    @Test fun exactRoundTripAndEveryTruncationRejects() {
        val bytes=FeatureCacheFormat.encode(record())
        val result=FeatureCacheFormat.decode(bytes,identity)
        assertArrayEquals(record().descriptors(),result.descriptors(),0f)
        for(n in bytes.indices)assertThrows(Exception::class.java){FeatureCacheFormat.decode(bytes.copyOf(n),identity)}
        assertThrows(IllegalArgumentException::class.java){FeatureCacheFormat.decode(bytes+byteArrayOf(0),identity)}
        assertThrows(IllegalArgumentException::class.java){FeatureCacheFormat.decode(bytes,identity.copy(extractorSettings="different"))}
    }
    @Test fun finiteBitFlipAndInvalidCoordinatesFail() {
        val bytes=FeatureCacheFormat.encode(record());bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java){FeatureCacheFormat.decode(bytes,identity)}
        assertThrows(IllegalArgumentException::class.java){CachedFeatures(identity,100,80,floatArrayOf(100f,1f),intArrayOf(1),FloatArray(128))}
        assertThrows(IllegalArgumentException::class.java){CachedFeatures(identity,100,80,floatArrayOf(1f,1f),intArrayOf(1),FloatArray(128){Float.NaN})}
    }
    @Test fun byteEvictionPreservesUnownedOriginalAndBudgetFailureIsMiss() {
        val root=Files.createTempDirectory("features-test").toFile()
        try {
            val original=File(root,"original.jpg").apply{writeText("preserve")}
            val budget=FeatureCacheFormat.encode(record()).size.toLong()+10
            val cache=FeatureCache(root,budget)
            assertTrue(cache.store(record()))
            assertTrue(cache.load(identity) is FeatureCacheRead.Hit)
            val next=identity.copy(originalSha256="b".repeat(64));assertTrue(cache.store(record(next)))
            assertTrue(cache.load(identity) is FeatureCacheRead.Miss)
            assertTrue(cache.load(next) is FeatureCacheRead.Hit)
            assertEquals("preserve",original.readText())
            assertFalse(FeatureCache(root,1).store(record()))
            assertTrue(root.listFiles()!!.none{it.name.endsWith(".partial")})
        }finally{root.deleteRecursively()}
    }
    @Test fun oversizedOpenedFileAndSymbolicRootsAreRejected() {
        val root=Files.createTempDirectory("features-sparse").toFile()
        val link=File(root.parentFile,root.name+"-link")
        try {
            val cache=FeatureCache(root,1000000);assertTrue(cache.store(record()))
            val entry=root.listFiles()!!.single()
            java.io.RandomAccessFile(entry,"rw").use{it.setLength(4L*1024*1024*1024)}
            assertTrue(cache.load(identity) is FeatureCacheRead.Miss)
            Files.createSymbolicLink(link.toPath(),root.toPath())
            assertFalse(FeatureCache(link,1000000).store(record()))
        }finally{link.delete();root.deleteRecursively()}
    }
    @Test fun concurrentAtomicWritesNeverExposePartialPayloads() {
        val root=Files.createTempDirectory("features-concurrent").toFile()
        try {
            val cache=FeatureCache(root,1000000)
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val threads=(0..3).map{Thread{try{repeat(30){assertTrue(cache.store(record()));assertTrue(cache.load(identity) is FeatureCacheRead.Hit)}}catch(t:Throwable){failure.set(t)}}.apply{start()}}
            threads.forEach{it.join()}
            failure.get()?.let{throw it}
            assertTrue(cache.load(identity) is FeatureCacheRead.Hit)
        }finally{root.deleteRecursively()}
    }
}
