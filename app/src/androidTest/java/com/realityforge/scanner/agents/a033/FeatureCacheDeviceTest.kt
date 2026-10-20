package com.realityforge.scanner.agents.a033

import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.features.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Real Android private-filesystem/NIO lease and atomic-publication checks, synthetic feature data. */
class FeatureCacheDeviceTest {
    @Test fun privateAndroidCacheHitsCorruptionRecomputesAndEvictionPreservesUnownedFiles() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File.createTempFile("a033-cache-","",context.cacheDir).canonicalFile
        assertTrue(root.delete());assertTrue(root.mkdir())
        try {
            val original=File(root,"unowned-original-canary.jpg").apply{writeBytes(byteArrayOf(1,2,3,4))}
            val before=MessageDigest.getInstance("SHA-256").digest(original.readBytes())
            val identity=FeatureCacheIdentity("a".repeat(64),"android-upright-v2","sift-v2","4.12.0")
            fun record(id:FeatureCacheIdentity)=CachedFeatures(id,64,48,floatArrayOf(11f,17f),intArrayOf(0xff123456.toInt()),FloatArray(128){it.toFloat()})
            val cache=FeatureCache(root,FeatureCacheFormat.encode(record(identity)).size.toLong()+10)
            assertTrue("Android atomic-move/file-lock cache publication failed",cache.store(record(identity)))
            val loaded=cache.load(identity)
            assertTrue(loaded is FeatureCacheRead.Hit)
            assertArrayEquals(record(identity).descriptors(),(loaded as FeatureCacheRead.Hit).record.descriptors(),0f)
            val file=root.listFiles()!!.single{it.name.endsWith(".rffc")}
            val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
            assertTrue(cache.load(identity) is FeatureCacheRead.Miss)
            assertTrue(cache.store(record(identity)))
            assertTrue(cache.load(identity) is FeatureCacheRead.Hit)
            val next=identity.copy(originalSha256="b".repeat(64))
            assertTrue(FeatureCache(root,FeatureCacheFormat.encode(record(next)).size.toLong()+10).store(record(next)))
            assertTrue(cache.load(identity) is FeatureCacheRead.Miss)
            assertTrue(cache.load(next) is FeatureCacheRead.Hit)
            assertArrayEquals(before,MessageDigest.getInstance("SHA-256").digest(original.readBytes()))
            assertTrue(root.listFiles()!!.none{it.name.endsWith(".partial")})
        }finally{root.deleteRecursively()}
    }
}
