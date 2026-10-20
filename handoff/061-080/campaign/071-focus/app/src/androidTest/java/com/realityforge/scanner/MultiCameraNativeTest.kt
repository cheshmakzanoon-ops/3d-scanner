package com.realityforge.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.viewer.PhotographicGlb
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

/** Eight synthetic photographs of a known plane. Not a physical phone capture. */
@RunWith(AndroidJUnit4::class)
class MultiCameraNativeTest {
    @Test fun eightImageBuffersReachMeshAndPhotographicAtlasWithoutDroppingLastViews() {
        val width=104; val height=76; val n=8; val focal=116.0
        val pixels=ByteArray(n*width*height*4)
        val cameras=DoubleArray(n*16)
        for (cam in 0 until n) {
            val base=cam*16
            doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0).copyInto(cameras,base)
            cameras[base+9]=-cam*.55
            cameras[base+12]=focal; cameras[base+13]=focal
            cameras[base+14]=(width-1)/2.0; cameras[base+15]=(height-1)/2.0
            for (y in 0 until height) for (x in 0 until width) {
                val wx=(x-(width-1)/2.0)*5/focal+cam*.55
                val wy=(y-(height-1)/2.0)*5/focal
                val c=(120+45*sin(wx*10+wy*3)+42*cos(wx*20-wy*12)+27*sin(wx*37+wy*25))
                    .toInt().coerceIn(0,255).toByte()
                val i=((cam*height+y)*width+x)*4
                pixels[i]=c;pixels[i+1]=c;pixels[i+2]=c;pixels[i+3]=255.toByte()
            }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.cacheDir,"multicamera-native-test").apply { mkdirs() }
        try {
            val glb=File(folder,"photo.glb")
            val result=JSONObject(NativeCore.reconstructTexturedSurface(pixels,width,height,
                pixels,width,height,cameras,3.2,7.2,56,3,
                File(folder,"mesh.glb").path,File(folder,"mesh.ply").path,
                glb.path,File(folder,"atlas.png").path))
            assertFalse(result.toString().take(400),result.has("error"))
            assertFalse(result.toString().take(400),result.has("meshError"))
            assertFalse(result.toString().take(400),result.has("textureError"))
            val dense=result.getJSONObject("dense")
            val counts=dense.getJSONArray("consistentPixelsPerView")
            assertEquals(8,counts.length())
            assertTrue(counts.getInt(7)>20)
            assertTrue(dense.getLong("peakLuminanceBytes")<=4L*width*height*4)
            val texture=result.getJSONObject("texture")
            assertEquals(8,texture.getJSONArray("cameraTriangleCounts").length())
            assertTrue(texture.getInt("triangleCount")>100)
            val loaded=PhotographicGlb.read(glb)
            assertEquals(texture.getInt("triangleCount")*3,loaded.indices.size)
            assertEquals((width+6)*3,loaded.imageWidth)
            assertEquals((height+6)*3,loaded.imageHeight)
        } finally { folder.deleteRecursively() }
    }
}
