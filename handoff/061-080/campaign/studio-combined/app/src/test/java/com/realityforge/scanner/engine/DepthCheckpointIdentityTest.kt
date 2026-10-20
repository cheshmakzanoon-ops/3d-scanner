package com.realityforge.scanner.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DepthCheckpointIdentityTest {
    private fun pixels()=ByteArray(2*64*48*4) { i -> (i*11).toByte() }
    private fun poses()=DoubleArray(32).also { a ->
        for(i in 0..1){a[i*16]=1.0;a[i*16+4]=1.0;a[i*16+8]=1.0
            a[i*16+9]=-i*.5;a[i*16+12]=80.0;a[i*16+13]=80.0;a[i*16+14]=31.5;a[i*16+15]=23.5}
    }
    private fun id(p:ByteArray=pixels(),a:DoubleArray=poses(),near:Double=3.0,
                   key:String="sparse-m16-r1-0123456789abcdef") =
        DepthCheckpointIdentity.fingerprint(key,p,a,64,48,near,7.0,56,3)
    @Test fun exactInputIsReproducibleButChangesInvalidateTheSha256Identity(){
        val original=id()
        assertEquals(64,original.length)
        assertEquals(original,id())
        val changed=pixels();changed[100]=(changed[100]+1).toByte()
        assertNotEquals(original,id(p=changed))
        val cam=poses();cam[9]+=0.001
        assertNotEquals(original,id(a=cam))
        assertNotEquals(original,id(near=3.1))
        assertNotEquals(original,id(key="sparse-m16-r2-0123456789abcdef"))
    }
    @Test fun malformedContextIsRejectedBeforeReadingAnyCache(){
        try { DepthCheckpointIdentity.fingerprint("../escape",pixels(),poses(),64,48,3.0,7.0,56,3); fail() }
        catch (_: IllegalArgumentException) {}
        try { DepthCheckpointIdentity.fingerprint("sparse-m16-r1-0123456789abcdef",ByteArray(17),poses(),64,48,3.0,7.0,56,3);fail() }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun cacheRetainsCurrentAndAtMostOneRecentIdentity(){
        val root=Files.createTempDirectory("rf-depth-jvm").toFile()
        try {
            val keys=listOf("1".repeat(64),"2".repeat(64),"3".repeat(64))
            for((i,key) in keys.withIndex()){
                val dir=DepthCheckpointIdentity.directory(root,key)
                assertTrue(dir.isDirectory)
                dir.setLastModified(1000L+i*1000)
            }
            assertFalse(java.io.File(root,".depth-checkpoints-m18/${keys[0]}").exists())
            assertTrue(java.io.File(root,".depth-checkpoints-m18/${keys[1]}").exists())
            assertTrue(java.io.File(root,".depth-checkpoints-m18/${keys[2]}").exists())
        }finally{root.deleteRecursively()}
    }
}
