package com.realityforge.scanner

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.viewer.PhotographicGlb
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    @Test fun grayscaleStatisticsCrossJni() {
        val q = NativeCore.quality(ByteArray(256) { 128.toByte() }, 16, 16)
        assertEquals(128.0, q[0], 0.0)
        assertEquals(0.0, q[2], 0.0)
    }
    @Test fun recoverTranslatedCameraOnDevice() {
        val random = Random(9)
        val rows = DoubleArray(80 * 7)
        for (i in 0 until 80) {
            val x = random.nextDouble(-1.0, 1.0); val y = random.nextDouble(-1.0, 1.0); val z = random.nextDouble(3.0, 8.0)
            val values = doubleArrayOf(x / z, y / z, (x - 1) / z, y / z, 100.0, 190.0, 220.0)
            values.copyInto(rows, i * 7)
        }
        val result = JSONObject(NativeCore.reconstruct(rows, .002))
        assertFalse(result.toString(), result.has("error"))
        assertTrue(result.getInt("pointCount") >= 75)
    }
    @Test fun registerThirdPoseOnDevice() {
        val random = Random(121)
        val tracks = DoubleArray(80 * 5)
        for (i in 0 until 80) {
            val x = random.nextDouble(-1.0, 1.0)
            val y = random.nextDouble(-1.0, 1.0)
            val z = random.nextDouble(3.0, 8.0)
            val values = doubleArrayOf(x, y, z, (x - 1.2) / z, (y + 0.1) / z)
            values.copyInto(tracks, i * 5)
        }
        val result = JSONObject(NativeCore.registerCamera(tracks, .003))
        assertFalse(result.toString(), result.has("error"))
        assertTrue(result.getInt("inlierCount") >= 75)
        assertEquals(-1.2, result.getJSONArray("translation").getDouble(0), .02)
    }
    @Test fun jointBundleAdjustmentRunsThroughArmOrEmulatorJni() {
        val random = Random(606)
        val trueCameraTranslations = arrayOf(0.0, -1.0, -1.8)
        val rotations = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val cameras = trueCameraTranslations.flatMapIndexed { index, value ->
            (rotations + doubleArrayOf(value + if (index == 2) 0.022 else 0.0,
                if (index == 2) -0.02 else 0.0, 0.0)).asList()
        }.toDoubleArray()
        val points = DoubleArray(96 * 7)
        val observations = DoubleArray(96 * 3 * 7)
        for (i in 0 until 96) {
            val x=random.nextDouble(-1.0,1.0)
            val y=random.nextDouble(-1.0,1.0)
            val z=random.nextDouble(3.0,8.0)
            val seed=doubleArrayOf(i.toDouble(),x+0.006,y-0.004,z+0.008,125.0,172.0,210.0)
            seed.copyInto(points,i*7)
            for (camera in 0 until 3) {
                val row=doubleArrayOf(i.toDouble(),camera.toDouble(),
                    (x+trueCameraTranslations[camera])/z,y/z,125.0,172.0,210.0)
                row.copyInto(observations,(i*3+camera)*7)
            }
        }
        val result=JSONObject(NativeCore.bundleAdjust(cameras,points,observations,0.025,15))
        assertFalse(result.toString(),result.has("error"))
        assertEquals("coupled_huber_schur_lm",result.getString("optimization"))
        assertTrue(result.getInt("pointCount")>=90)
        assertTrue(result.getDouble("robustCostAfter")<result.getDouble("robustCostBefore"))
        assertEquals(2,result.getInt("anchorCamerasFixed"))
        assertEquals(-1.0,result.getJSONArray("cameras").getJSONObject(1)
            .getJSONArray("translation").getDouble(0),0.0)
    }
    @Test fun rectifiedCpuDenseStereoReconstructsActualPixelsThroughJni() {
        val width=104;val height=76;val fx=116.0
        val cx=(width-1)/2.0;val cy=(height-1)/2.0
        val count=3
        val cameras=DoubleArray(count*16)
        val pixels=ByteArray(count*width*height*4)
        for (camera in 0 until count) {
            val offset=camera*16
            val rotation=doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
            rotation.copyInto(cameras,offset)
            cameras[offset+9]=-camera*0.55
            cameras[offset+12]=fx;cameras[offset+13]=fx
            cameras[offset+14]=cx;cameras[offset+15]=cy
            for (y in 0 until height)for(x in 0 until width) {
                val wx=(x-cx)*5.0/fx+camera*.55
                val wy=(y-cy)*5.0/fx
                val signal=120 + 45*kotlin.math.sin(wx*10+wy*3) +
                    42*kotlin.math.cos(wx*20-wy*12) + 27*kotlin.math.sin(wx*37+wy*25)
                val c=signal.toInt().coerceIn(0,255)
                val idx=(((camera*height+y)*width+x)*4)
                pixels[idx]=c.toByte()
                pixels[idx+1]=c.toByte()
                pixels[idx+2]=c.toByte()
                pixels[idx+3]=255.toByte()
            }
        }
        val result=JSONObject(NativeCore.reconstructDense(pixels,width,height,cameras,3.2,7.2,56,3))
        assertFalse(result.toString().take(400),result.has("error"))
        assertEquals("dense_points",result.getString("representation"))
        assertEquals(0,result.getInt("triangleCount"))
        assertTrue(result.getInt("photometricPixels")>100)
        assertTrue(result.getInt("pointCount")>50)
        // M08 must actually create indexed TRIANGLES through the installed NDK .so.
        val root=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val glb=File(root,"realityforge-m08-smoke.glb")
        val ply=File(root,"realityforge-m08-smoke.ply")
        try {
            val surface=JSONObject(NativeCore.reconstructSurface(pixels,width,height,cameras,
                3.2,7.2,56,3,glb.path,ply.path))
            assertFalse(surface.toString().take(300),surface.has("error"))
            assertFalse(surface.toString().take(300),surface.has("meshError"))
            val mesh=surface.getJSONObject("mesh")
            assertEquals("observed_open_triangle_mesh",mesh.getString("representation"))
            assertTrue(mesh.getInt("triangleCount")>30)
            assertTrue(mesh.getInt("vertexCount")>30)
            assertEquals(mesh.getInt("triangleCount"),mesh.getJSONArray("triangles").length())
            assertTrue(glb.isFile && glb.length()>1024)
            assertTrue(ply.isFile && ply.length()>1024)
            val header=glb.inputStream().use { input ->
                val bytes=ByteArray(4)
                assertEquals(4,input.read(bytes))
                bytes
            }
            assertArrayEquals(byteArrayOf(0x67,0x6c,0x54,0x46),header)
            // M09 must export photo pixels as a real PNG and embedded GLB UV atlas.
            val textured=File(root,"realityforge-m09-smoke.glb")
            val atlas=File(root,"realityforge-m09-atlas.png")
            try {
                val complete=JSONObject(NativeCore.reconstructTexturedSurface(
                    pixels,width,height,pixels,width,height,cameras,
                    3.2,7.2,56,3,glb.path,ply.path,textured.path,atlas.path))
                assertFalse(complete.toString().take(400),complete.has("error"))
                assertFalse(complete.toString().take(400),complete.has("meshError"))
                assertFalse(complete.toString().take(400),complete.has("textureError"))
                val uv=complete.getJSONObject("texture")
                assertTrue(uv.getInt("triangleCount")>30)
                assertTrue(uv.getDouble("faceCoverage")>.90)
                assertEquals("upper_left",uv.getString("uvOrigin"))
                assertTrue(uv.getInt("viewSwitchEdges")<=uv.getInt("viewSwitchEdgesBefore"))
                assertTrue(uv.getDouble("overlapMedianLogErrorAfter")<=uv.getDouble("overlapMedianLogErrorBefore")+1e-9)
                assertTrue(textured.isFile && textured.length()>1024)
                val inspected = PhotographicGlb.read(textured)
                assertEquals(uv.getInt("triangleCount") * 3, inspected.indices.size)
                assertEquals(uv.getInt("atlasWidth"), inspected.imageWidth)
                assertEquals(uv.getInt("atlasHeight"), inspected.imageHeight)
                assertTrue(atlas.isFile && atlas.length()>128)
                val signature=atlas.inputStream().use { input ->
                    ByteArray(8).also { assertEquals(8,input.read(it)) }
                }
                assertArrayEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10),signature)
                val gltfMagic=textured.inputStream().use { input ->
                    ByteArray(4).also { assertEquals(4,input.read(it)) }
                }
                assertArrayEquals(byteArrayOf(0x67,0x6c,0x54,0x46),gltfMagic)
            } finally { textured.delete();atlas.delete() }
        } finally { glb.delete(); ply.delete() }
    }
    @Test fun invalidPhotoTextureBuffersAreRejectedBeforeNativeAccess() {
        val rgba=ByteArray(2*64*64*4)
        val invalid=ByteArray(13)
        val cameras=DoubleArray(2*16)
        try {
            NativeCore.reconstructTexturedSurface(rgba,64,64,invalid,64,64,cameras,
                2.0,7.0,24,3,"/tmp/a.glb","/tmp/b.ply","/tmp/c.glb","/tmp/d.png")
            fail("Mismatched photo buffer must be rejected")
        } catch(expected: IllegalArgumentException) { /* JNI rejects before native read */ }
    }
    @Test fun nativeCancellationTokensAreIsolatedAndRejectStaleHandles() {
        val first=NativeCore.beginReconstruction()
        val second=NativeCore.beginReconstruction()
        val w=64;val h=64
        val rgba=ByteArray(2*w*h*4) { i -> if(i%4==3) 255.toByte() else 128.toByte() }
        val camera=DoubleArray(32)
        for(i in 0..1) {
            val base=i*16
            camera[base]=1.0;camera[base+4]=1.0;camera[base+8]=1.0
            camera[base+9]=-i*.5
            camera[base+12]=84.0;camera[base+13]=84.0
            camera[base+14]=31.5;camera[base+15]=31.5
        }
        val root=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val glb=File(root,"rf-cancel-m17.glb")
        val ply=File(root,"rf-cancel-m17.ply")
        val photo=File(root,"rf-cancel-m17-textured.glb")
        val png=File(root,"rf-cancel-m17-atlas.png")
        listOf(glb,ply,photo,png).forEach { it.delete() }
        try {
            assertTrue(first>0L&&second>first)
            assertTrue(NativeCore.cancelReconstruction(first))
            assertFalse(NativeCore.cancelReconstruction(-1))
            val stopped=JSONObject(NativeCore.reconstructSurfaceCancellable(rgba,w,h,camera,
                3.0,7.0,24,3,glb.path,ply.path,first))
            assertEquals("Reconstruction cancelled",stopped.getString("error"))
            val stoppedTexture=JSONObject(NativeCore.reconstructTexturedSurfaceCancellable(
                rgba,w,h,rgba,w,h,camera,3.0,7.0,24,3,
                glb.path,ply.path,photo.path,png.path,first))
            assertEquals("Reconstruction cancelled",stoppedTexture.getString("error"))
            assertFalse(glb.exists()||ply.exists()||photo.exists()||png.exists())
            val healthy=JSONObject(NativeCore.reconstructSurfaceCancellable(rgba,w,h,camera,
                3.0,7.0,24,3,glb.path,ply.path,second))
            assertFalse(healthy.toString().take(150),healthy.has("error"))
            assertTrue(healthy.has("dense"))
        } finally {
            assertTrue(NativeCore.finishReconstruction(first))
            assertTrue(NativeCore.finishReconstruction(second))
            listOf(glb,ply,photo,png).forEach { it.delete() }
        }
        assertFalse(NativeCore.cancelReconstruction(first))
        try {
            NativeCore.reconstructSurfaceCancellable(rgba,w,h,camera,
                3.0,7.0,24,3,glb.path,ply.path,first)
            fail("Finished lease was accepted")
        }catch(expected: IllegalArgumentException){ }
    }
    @Test fun bundledOpenCvInitializesWithoutDownload() { assertTrue(OpenCVLoader.initLocal()) }
    @Test fun mergedApkHasNoInternetPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains("android.permission.INTERNET"))
    }
}
