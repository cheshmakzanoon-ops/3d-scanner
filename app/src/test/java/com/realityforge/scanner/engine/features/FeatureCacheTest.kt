package com.realityforge.scanner.engine.features
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
class FeatureCacheTest {
    private val identity=FeatureCacheIdentity("a".repeat(64),"decoder-v2","sift3500-v2","4.12.0")
    private fun record(id:FeatureCacheIdentity=identity)=CachedFeatures(id,100,80,floatArrayOf(1.2f,3.4f,50f,60f),intArrayOf(7,8),FloatArray(256){it%128.toFloat()})
    @Test fun descriptorDomainIsExactlyFiniteInclusiveFloatEndpoints() {
        fun descriptor(value:Float)=CachedFeatures(identity,32,32,floatArrayOf(1f,1f),intArrayOf(1),FloatArray(128){value})
        for(value in listOf(0f,Math.nextDown(512f),512f)) assertEquals(value,descriptor(value).descriptors()[0],0f)
        for(value in listOf(Math.nextUp(512f),-Float.MIN_VALUE,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java){descriptor(value)}
    }
    @Test fun metadataRejectsMalformedUnicodeAndExcessiveEncodedLength() {
        for(value in listOf("\uD800","\uD801","x".repeat(4097),"\u00e9".repeat(2049)))
            assertThrows(IllegalArgumentException::class.java){identity.copy(decoderTransform=value)}
        assertEquals("\u00e9".repeat(2048),identity.copy(decoderTransform="\u00e9".repeat(2048)).decoderTransform)
    }
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
    @Test fun validChecksumCannotAdmitMalformedGeometryOrDescriptorPayload() {
        val bytes=FeatureCacheFormat.encode(record())
        val offset=44+listOf(identity.originalSha256,identity.decoderTransform,identity.extractorSettings,identity.openCvVersion).sumOf{4+it.toByteArray(Charsets.UTF_8).size}+16
        java.nio.ByteBuffer.wrap(bytes).putFloat(offset,100f)
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(44,bytes.size)).copyInto(bytes,12)
        assertThrows(IllegalArgumentException::class.java){FeatureCacheFormat.decode(bytes,identity)}
    }
    @Test fun reservedFileLeasePreventsUncoordinatedPublicationAndInvalidation() {
        val root=Files.createTempDirectory("features-lease").toFile()
        try {
            val cache=FeatureCache(root,1000000);assertTrue(cache.store(record()))
            val next=identity.copy(originalSha256="c".repeat(64))
            java.nio.channels.FileChannel.open(File(root,".feature-cache.lock").toPath(),java.nio.file.StandardOpenOption.WRITE).use{channel->
                channel.lock().use {
                    val stored=java.util.concurrent.atomic.AtomicReference<Boolean>()
                    val invalidated=java.util.concurrent.atomic.AtomicReference<Boolean>()
                    val writer=Thread{stored.set(FeatureCache(root,1000000).store(record(next)));invalidated.set(cache.invalidate(identity))}
                    writer.start();writer.join(3000);assertFalse(writer.isAlive)
                    assertEquals(false,stored.get());assertEquals(false,invalidated.get())
                    assertTrue(cache.load(identity) is FeatureCacheRead.Hit)
                    assertTrue(cache.load(next) is FeatureCacheRead.Miss)
                }
            }
            assertTrue(cache.store(record(next)))
            assertTrue(cache.invalidate(identity))
        }finally{root.deleteRecursively()}
    }
    @Test fun crossInstanceWritersAndUnavailableStorageRemainSafeMisses() {
        val root=Files.createTempDirectory("features-instances").toFile()
        try {
            val bytes=FeatureCacheFormat.encode(record()).size.toLong()+10
            val first=FeatureCache(root,bytes);val second=FeatureCache(root,bytes)
            val next=identity.copy(originalSha256="c".repeat(64))
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val threads=listOf(Thread{try{repeat(100){first.store(record());first.load(identity)}}catch(t:Throwable){failure.set(t)}},Thread{try{repeat(100){second.store(record(next));second.load(next)}}catch(t:Throwable){failure.set(t)}})
            threads.forEach{it.start()};threads.forEach{it.join()};failure.get()?.let{throw it}
            assertTrue(root.listFiles()!!.filter{it.name.endsWith(".rffc")}.sumOf{it.length()}<=bytes)
            val nonDirectory=File(root,"unavailable").apply{writeText("original")}
            assertFalse(FeatureCache(nonDirectory,10000).store(record()))
            assertEquals("original",nonDirectory.readText())
        }finally{root.deleteRecursively()}
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
            val entry=root.listFiles()!!.single{it.name.endsWith(".rffc")}
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
