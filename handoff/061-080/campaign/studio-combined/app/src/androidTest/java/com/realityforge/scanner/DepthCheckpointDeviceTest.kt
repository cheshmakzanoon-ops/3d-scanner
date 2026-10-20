package com.realityforge.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.NativeCore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class DepthCheckpointDeviceTest {
    @Test fun perReferenceDepthRestartsFromPersistedFilesAndRejectsCorruption() {
        val w=72;val h=56;val fx=93.0;val count=2
        val cameras=DoubleArray(count*16)
        val pixels=ByteArray(count*w*h*4)
        for (cam in 0 until count) {
            val base=cam*16
            cameras[base]=1.0;cameras[base+4]=1.0;cameras[base+8]=1.0
            cameras[base+9]=-cam*.4
            cameras[base+12]=fx;cameras[base+13]=fx
            cameras[base+14]=(w-1)/2.0;cameras[base+15]=(h-1)/2.0
            for (y in 0 until h)for (x in 0 until w) {
                val wx=(x-(w-1)/2.0)*5/fx+cam*.4
                val signal=(128+40*sin(wx*15+y*.12)+43*cos(wx*29-y*.21)).toInt().coerceIn(0,255)
                val offset=((cam*h+y)*w+x)*4
                for (k in 0..2)pixels[offset+k]=signal.toByte()
                pixels[offset+3]=255.toByte()
            }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"m18-instrument-${System.nanoTime()}").apply { mkdirs() }
        val identity="abcdef0123456789".repeat(4)
        try {
            fun call(): JSONObject {
                val token=NativeCore.beginReconstruction()
                try {
                    return JSONObject(NativeCore.reconstructSurfaceCheckpointed(
                        pixels,w,h,cameras,3.2,7.2,36,3,
                        File(root,"surface.glb").path,File(root,"surface.ply").path,
                        token,root.path,identity))
                } finally { assertTrue(NativeCore.finishReconstruction(token)) }
            }
            val first=call()
            assertFalse(first.toString().take(300),first.has("error"))
            assertEquals(count,first.getJSONObject("dense").getInt("writtenDepthMaps"))
            assertEquals(0,first.getJSONObject("dense").getInt("resumedDepthMaps"))
            val firstDepth=File(root,"ref-0.bin")
            assertTrue(firstDepth.isFile && firstDepth.length()>96)
            val resumed=call()
            assertFalse(resumed.toString().take(300),resumed.has("error"))
            assertEquals(count,resumed.getJSONObject("dense").getInt("resumedDepthMaps"))
            assertEquals(0,resumed.getJSONObject("dense").getInt("writtenDepthMaps"))
            firstDepth.outputStream().use { it.write(byteArrayOf(1,2,3)) }
            val repaired=call()
            assertFalse(repaired.toString().take(300),repaired.has("error"))
            assertEquals(1,repaired.getJSONObject("dense").getInt("resumedDepthMaps"))
            assertEquals(1,repaired.getJSONObject("dense").getInt("writtenDepthMaps"))
        } finally { root.deleteRecursively() }
    }
}
