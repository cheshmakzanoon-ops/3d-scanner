package com.realityforge.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.ui.PhotoGlView
import com.realityforge.scanner.ui.PhotographicModelViewer
import com.realityforge.scanner.viewer.PhotographicGlb
import com.realityforge.scanner.viewer.PhotographicRenderer
import com.realityforge.scanner.viewer.filament.FilamentStudioView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Analytical color/UV fixtures are synthetic; they do not claim real-object scan quality. */
@RunWith(AndroidJUnit4::class)
class PhotographicViewerTest {
    /** Shared analytical fixture for owner070's real AndroidView replacement regression. */
    internal fun photographicFixtureBytes(): ByteArray = fixture().pack()
    private data class Fixture(val json: JSONObject, val binary: ByteArray) {
        fun pack(): ByteArray {
            var text = json.toString().toByteArray()
            text += ByteArray((4 - text.size % 4) % 4) { 32 }
            val bin = binary + ByteArray((4 - binary.size % 4) % 4)
            return ByteBuffer.allocate(28 + text.size + bin.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546c67).putInt(2).putInt(28 + text.size + bin.size)
                .putInt(text.size).putInt(0x4e4f534a).put(text)
                .putInt(bin.size).putInt(0x004e4942).put(bin).array()
        }
    }
    private fun fixture(mask: Boolean = false, palette: IntArray? = null, constantUv: Boolean = false): Fixture {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val colors = palette ?: intArrayOf(if (mask) Color.TRANSPARENT else Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (y in 0..7) for (x in 0..7) bitmap.setPixel(x, y, colors[(y / 4) * 2 + x / 4])
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val bin = ByteBuffer.allocate(152 + png.size).order(ByteOrder.LITTLE_ENDIAN)
        for (v in floatArrayOf(-1f,1f,0f, -1f,-1f,0f, 1f,1f,0f, 1f,-1f,0f)) bin.putFloat(v)
        repeat(4) { bin.putFloat(0f).putFloat(0f).putFloat(1f) }
        for (v in floatArrayOf(0f,0f,0f,1f,1f,0f,1f,1f)) bin.putFloat(if (constantUv) .5f else v)
        for (v in intArrayOf(0,1,2,2,1,3)) bin.putInt(v)
        bin.put(png)
        val json = JSONObject("""{
          "asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
          "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"TEXCOORD_0":2},"indices":3,"mode":4,"material":0}]}],
          "buffers":[{"byteLength":${bin.capacity()}}],
          "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48},
            {"buffer":0,"byteOffset":48,"byteLength":48},{"buffer":0,"byteOffset":96,"byteLength":32},
            {"buffer":0,"byteOffset":128,"byteLength":24},{"buffer":0,"byteOffset":152,"byteLength":${png.size}}],
          "accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","min":[-1,-1,0],"max":[1,1,0]},
            {"bufferView":1,"componentType":5126,"count":4,"type":"VEC3"},
            {"bufferView":2,"componentType":5126,"count":4,"type":"VEC2"},
            {"bufferView":3,"componentType":5125,"count":6,"type":"SCALAR"}],
          "materials":[{"doubleSided":true,"alphaMode":"MASK","alphaCutoff":0.5,
            "pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1}}],
          "textures":[{"source":0,"sampler":0}],"samplers":[{"minFilter":9729,"magFilter":9729,"wrapS":33071,"wrapT":33071}],
          "images":[{"bufferView":4,"mimeType":"image/png"}]
        }""")
        return Fixture(json, bin.array())
    }
    private fun rejects(bytes: ByteArray) {
        try { PhotographicGlb.decode(bytes); fail("Unsafe/unsupported model was accepted") }
        catch (expected: Exception) { /* Parser rejection, never an allocation or renderer crash. */ }
    }
    @Test fun boundedLoaderPreservesActualCoordinatesUvsAndIndices() {
        val f = fixture(); val bytes = f.pack(); val original = bytes.copyOf()
        val asset = PhotographicGlb.decode(bytes)
        assertEquals(8, asset.imageWidth); assertEquals(8, asset.imageHeight)
        assertEquals(12, asset.positions.size); assertEquals(8, asset.uv.size)
        assertArrayEquals(intArrayOf(0,1,2,2,1,3), asset.indices)
        assertEquals(0f, asset.uv[1], 0f); assertEquals(1f, asset.uv[3], 0f)
        assertArrayEquals(original, bytes)
    }
    @Test fun malformedHeadersAndChunkBoundsFailClosed() {
        val bytes = fixture().pack()
        for (offset in listOf(0,4,8,12,16)) {
            val bad = bytes.copyOf(); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, -1); rejects(bad)
        }
        rejects(bytes.copyOf(bytes.size - 1)); rejects(bytes + byteArrayOf(0,0,0,0))
    }
    @Test fun externalDataTransformsAndExtensionsAreRejected() {
        val cases: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("buffers").getJSONObject(0).put("uri", "https://example.invalid/data.bin") },
            { it.getJSONArray("images").getJSONObject(0).put("uri", "file:///private/photo.png") },
            { it.getJSONArray("nodes").getJSONObject(0).put("scale", JSONArray("[1,2,3]")) },
            { it.put("extensionsRequired", JSONArray("[\"EXT_unknown\"]")) },
            { it.getJSONArray("accessors").getJSONObject(0).put("sparse", JSONObject()) },
            { it.getJSONArray("accessors").getJSONObject(0).put("count", 1.5) },
            { it.getJSONArray("accessors").getJSONObject(0).put("count", 999999999) },
            { it.getJSONArray("bufferViews").getJSONObject(0).put("byteOffset", 2147483647) },
            { it.getJSONArray("accessors").getJSONObject(0).put("byteOffset", 1) },
            { it.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0).put("mode", 0) },
        )
        for (modify in cases) { val f = fixture(); modify(f.json); rejects(f.pack()) }
    }
    @Test fun nonfiniteCoordinatesBadIndicesAndCorruptPngAreRejected() {
        for ((offset, value) in listOf(0 to Float.NaN.toRawBits(), 96 to Float.POSITIVE_INFINITY.toRawBits(), 128 to -1)) {
            val f = fixture(); ByteBuffer.wrap(f.binary).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value); rejects(f.pack())
        }
        val f = fixture(); f.binary[f.binary.size - 1] = (f.binary.last().toInt() xor 1).toByte(); rejects(f.pack())
    }
    @Test fun pngBudgetIsEnforcedBeforeImageDecode() {
        val f = fixture(); val b = ByteBuffer.wrap(f.binary).order(ByteOrder.BIG_ENDIAN)
        b.putInt(152 + 16, 100000) // Correct CRC, invalid dimensions: cannot disguise a huge bitmap.
        val crc = CRC32().apply { update(f.binary, 152 + 12, 17) }.value
        b.putInt(152 + 29, crc.toInt()); rejects(f.pack())
    }
    @Test fun openGlRendersCorrectPhotographicQuadrantsWithoutVerticalFlip() {
        val asset = PhotographicGlb.decode(fixture().pack())
        EglSurface().use {
            val renderer = PhotographicRenderer(asset)
            renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null,128,128); renderer.onDrawFrame(null)
            assertNull(renderer.failure); assertEquals(1, renderer.framesDrawn)
            assertColor(pixel(48,80), 255,0,0)
            assertColor(pixel(80,80), 0,255,0)
            assertColor(pixel(48,48), 0,0,255)
            assertColor(pixel(80,48), 255,255,0)
            renderer.orbit(22f,10f); renderer.magnify(1.3f); renderer.onDrawFrame(null)
            renderer.reset(); renderer.onDrawFrame(null)
            assertColor(pixel(48,80), 255,0,0)
            renderer.releaseGlResources(); renderer.releaseGlResources()
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        }
    }
    @Test fun photographicFilteringUsesLinearLightAndEncodesOnceAfterContextLoss() {
        // Synthetic constant-UV fixture samples exactly between opaque black/white texels.
        // Saturated quadrant interiors cannot detect the encoded-space filtering defect.
        val cases = listOf(
            intArrayOf(Color.BLACK, Color.WHITE, Color.BLACK, Color.WHITE) to 188,
            IntArray(4) { Color.rgb(128, 128, 128) } to 128,
            IntArray(4) { Color.argb(128, 128, 128, 128) } to 128,
        )
        for ((palette, expected) in cases) {
            val renderer = PhotographicRenderer(PhotographicGlb.decode(fixture(palette = palette, constantUv = true).pack()))
            repeat(2) { pass ->
                EglSurface().use {
                    renderer.onSurfaceCreated(null, null)
                    renderer.onSurfaceChanged(null, 128, 128); renderer.onDrawFrame(null)
                    assertNull(renderer.failure)
                    val actual = pixel(64, 64)
                    for (channel in 0..2) assertTrue("Expected $expected, got ${actual[channel]}", kotlin.math.abs(actual[channel] - expected) <= 2)
                    if (pass == 1) renderer.releaseGlResources()
                    // First close deliberately loses context without explicit deletion.
                }
            }
        }
        val masked = PhotographicRenderer(PhotographicGlb.decode(fixture(
            palette = IntArray(4) { Color.argb(127, 128, 128, 128) }, constantUv = true,
        ).pack()))
        EglSurface().use {
            masked.onSurfaceCreated(null, null)
            masked.onSurfaceChanged(null, 128, 128); masked.onDrawFrame(null)
            assertNull(masked.failure)
            val actual = pixel(64, 64)
            assertTrue(actual[0] < 30 && actual[1] < 30 && actual[2] < 30)
            masked.releaseGlResources()
        }
    }
    @Test fun maskedPixelsStayEmptyAndContextLossReuploadsTheAtlas() {
        val renderer = PhotographicRenderer(PhotographicGlb.decode(fixture(true).pack()))
        repeat(2) {
            EglSurface().use {
                renderer.onSurfaceCreated(null, null); renderer.onSurfaceChanged(null,128,128); renderer.onDrawFrame(null)
                assertNull(renderer.failure)
                val empty = pixel(48,80)
                assertTrue(empty[0] < 30 && empty[1] < 30 && empty[2] < 30)
                assertColor(pixel(80,80), 0,255,0)
                // Closing EGL without deleting simulates context loss; same renderer must rebuild.
            }
        }
        assertEquals(2, renderer.framesDrawn)
    }
    @Test fun actualDialogViewerRendersAfterActivityPauseAndResume() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val file = File(inst.targetContext.cacheDir, "viewer-fixture.glb").apply { writeBytes(fixture().pack()) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { PhotographicModelViewer(file) {} } }
                val surface = waitForSurface()
                val before = surface.renderedFrames
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val resumed = waitForSurface(before)
                assertNull(resumed.renderFailure)
            }
        } finally { file.delete() }
    }
    @Test fun filamentRestoresCameraPausesAndRejectsOversizedResize() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val bytes=fixture().pack(); val original=bytes.copyOf()
        val failure=AtomicReference<String?>(null)
        val failureCount=java.util.concurrent.atomic.AtomicInteger(0)
        val current=AtomicReference<FilamentStudioView?>(null)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val surface=remember { FilamentStudioView(activity,bytes,onFailure={ failure.set(it); failureCount.incrementAndGet() }).also {
                        // The view receives restored controls before its Surface/backend exists.
                        it.orbit(35f,10f); it.magnify(1.2f); current.set(it)
                    } }
                    DisposableEffect(surface) { onDispose { surface.dispose() } }
                    AndroidView(factory={ surface })
                }
            }
            fun frames(): Long {
                var count=0L
                inst.runOnMainSync { count=current.get()?.renderStatistics?.submittedFrames ?: 0L }
                assertNull(failure.get())
                return count
            }
            fun awaitFrame(previous: Long) {
                val deadline=android.os.SystemClock.elapsedRealtime()+30_000
                while (android.os.SystemClock.elapsedRealtime()<deadline) {
                    if (frames()>previous) return
                    Thread.sleep(50)
                }
                fail("Filament did not submit a fixture frame; inspect actual driver support")
            }
            awaitFrame(0)
            inst.waitForIdleSync()
            Thread.sleep(250) // Let initial Surface/layout notifications settle.
            val idleFrames=frames()
            Thread.sleep(250)
            assertEquals("Static inspection must not redraw continuously",idleFrames,frames())
            var restoredPose=FloatArray(0)
            var restoredModel=FloatArray(0)
            inst.runOnMainSync {
                restoredPose=current.get()!!.cameraViewMatrix()
                restoredModel=current.get()!!.displayModelMatrix()
            }
            var beforeReset=0L
            inst.runOnMainSync { beforeReset=current.get()!!.renderStatistics.submittedFrames; current.get()!!.reset() }
            awaitFrame(beforeReset)
            inst.runOnMainSync {
                assertFalse(restoredPose.contentEquals(current.get()!!.cameraViewMatrix()))
                assertFalse(restoredModel.contentEquals(current.get()!!.displayModelMatrix()))
            }
            // Every display mutation must wake the otherwise idle renderer.
            for (change in listOf<(FilamentStudioView)->Unit>(
                { it.orbit(5f,3f) }, { it.pan(.1f,.1f) }, { it.magnify(1.1f) },
                { it.surfaceChanged(it.holder,android.graphics.PixelFormat.RGBA_8888,it.width,it.height) },
            )) {
                val before=frames()
                inst.runOnMainSync { change(current.get()!!) }
                awaitFrame(before)
            }
            inst.runOnMainSync { current.get()!!.pauseRendering() }
            val paused=frames(); Thread.sleep(150); assertEquals(paused,frames())
            inst.runOnMainSync { current.get()!!.resumeRendering() }
            awaitFrame(paused)
            inst.runOnMainSync {
                current.get()!!.surfaceChanged(current.get()!!.holder,android.graphics.PixelFormat.RGBA_8888,8192,8192)
                current.get()!!.dispose(); current.get()!!.dispose()
            }
            assertNotNull(failure.get()); assertEquals(1,failureCount.get())
        }
        assertArrayEquals(original,bytes)
    }
    private fun waitForSurface(previousFrames: Int = 0): PhotoGlView {
        val inst = InstrumentationRegistry.getInstrumentation()
        fun find(v: View): PhotoGlView? {
            if (v is PhotoGlView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 20000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var result: PhotoGlView? = null
            inst.runOnMainSync { result = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) } }
            result?.let { assertNull(it.renderFailure); if (it.renderedFrames > previousFrames) return it }
            Thread.sleep(50)
        }
        throw AssertionError("Photographic dialog did not render within the test deadline")
    }
    private fun assertColor(actual: IntArray, r: Int, g: Int, b: Int) {
        for ((i, expected) in listOf(r,g,b).withIndex()) assertEquals("channel $i", expected.toDouble(), actual[i].toDouble(), 4.0)
    }
    private fun pixel(x: Int, y: Int): IntArray {
        GLES30.glFinish()
        val bytes = ByteBuffer.allocateDirect(4)
        GLES30.glReadPixels(x,y,1,1,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,bytes)
        assertEquals(GLES30.GL_NO_ERROR,GLES30.glGetError())
        return IntArray(4) { bytes.get(it).toInt() and 255 }
    }
    private class EglSurface : Closeable {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val surface: android.opengl.EGLSurface
        init {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val config = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
                EGL14.EGL_DEPTH_SIZE,16,EGL14.EGL_NONE),0,config,0,1,count,0) && count[0] == 1)
            context = EGL14.eglCreateContext(display,config[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display,config[0],intArrayOf(EGL14.EGL_WIDTH,128,EGL14.EGL_HEIGHT,128,EGL14.EGL_NONE),0)
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,surface,surface,context))
        }
        override fun close() {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context); EGL14.eglTerminate(display)
        }
    }
}
