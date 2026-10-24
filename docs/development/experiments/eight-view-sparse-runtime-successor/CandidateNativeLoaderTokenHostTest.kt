package com.realityforge.scanner.hostintegration

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.export.NativeAssetExports
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** HOST native loader/token prerequisite only: no photos, geometry, admission or device evidence. */
@RunWith(SandboxNativeLoaderRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateNativeLoaderTokenHostTest {
    @Test(timeout = 120_000) fun actualLoadersAndOwnedTokenLifecycleUseVerifiedLibraries() {
        val capability = QualifiedCapability.load()
        val openCv = capability.nativeFile("opencv")
        val shim = capability.nativeFile("shim")
        val engine = capability.nativeFile("engine")
        val aliases = File(required("aliasDir")).canonicalFile
        assertEquals(aliases.path, System.getProperty("java.library.path"))
        assertEquals(openCv, File(aliases, "libopencv_java4.so").canonicalFile)
        assertEquals(shim, File(aliases, "librealityforge.so").canonicalFile)
        assertEquals(setOf("libopencv_java4.so", "librealityforge.so"), aliases.list()!!.toSet())
        assertTrue("No parent/native preload is permitted", nativeMappings().isEmpty())
        val loader = javaClass.classLoader
        assertTrue(loader.javaClass.name.contains("SandboxClassLoader"))
        val names = listOf("org.opencv.android.OpenCVLoader", "org.opencv.android.StaticHelper",
            "org.opencv.core.Core", "org.opencv.core.Mat", "com.realityforge.scanner.engine.NativeCore",
            "com.realityforge.scanner.export.NativeAssetExports")
        for (name in names) {
            val type = Class.forName(name, false, loader)
            assertSame("Native owner must be sandbox acquired: $name", loader, type.classLoader)
            val resource = name.replace('.', '/') + ".class"
            val resources = loader.getResources(resource).toList()
            CapabilityIdentity.originalResources(resources, resource, required("classSha.$name"))
            val bytes = requireNotNull(loader.getResourceAsStream(resource)).use { it.readBytes() }
            assertEquals("Original class resource identity: $name", required("classSha.$name"), sha(bytes))
            if (name == "com.realityforge.scanner.engine.NativeCore")
                assertEquals(capability.value.getJSONObject("inputs").getJSONObject("nativeOwner").getString("sha256"), sha(bytes))
            if (name == "com.realityforge.scanner.export.NativeAssetExports")
                assertEquals(capability.value.getJSONObject("inputs").getJSONObject("exportNativeOwner").getString("sha256"), sha(bytes))
        }
        assertSame(loader, Log::class.java.classLoader)
        assertTrue(Modifier.isNative(NativeCore::class.java.getDeclaredMethod("beginReconstruction").modifiers))
        val exportMethod = NativeAssetExports::class.java.getDeclaredMethod("writeObserved",
            String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java, Runnable::class.java)
        assertTrue(Modifier.isNative(exportMethod.modifiers)); assertFalse(Modifier.isStatic(exportMethod.modifiers))
        assertEquals(Void.TYPE, exportMethod.returnType)
        assertTrue("Must be actual StaticHelper/native loading, never a substituted true", OpenCVLoader.initLocal())
        assertEquals("4.12.0", Core.getVersionString())
        assertEquals("4.12.0", Core.VERSION)
        Core.setNumThreads(1)
        assertTrue(Core.getNumThreads() <= 1)
        val mat = Mat()
        try { assertTrue(mat.empty()) } finally { mat.release() }
        assertEquals(setOf(openCv.path), nativeMappings())
        // Exercises the existing native software graphics runtime, not a GPU or bridge shadow.
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(0xff123456.toInt()); assertEquals(0xff123456.toInt(), bitmap.getPixel(1, 1)) }
        finally { bitmap.recycle() }
        val token = NativeCore.beginReconstruction()
        assertTrue(token > 0)
        var primary: Throwable? = null
        try {
            val progress = NativeCore.pollNativeProgress(token)
            assertEquals(7, progress.size); assertEquals(1L, progress[0])
            assertTrue(progress[1] in 0L..6L)
            assertTrue(progress[4] >= 0 && progress[5] >= -1 && progress[6] >= 0)
            assertTrue(NativeCore.cancelReconstruction(token))
            assertEquals(7, NativeCore.pollNativeProgress(token).size)
            assertEquals(setOf(openCv.path, shim.path, engine.path), nativeMappings())
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { assertTrue("Owned token must finish", NativeCore.finishReconstruction(token)) }
            catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
        assertFalse(NativeCore.cancelReconstruction(token))
        assertFalse(NativeCore.finishReconstruction(token))
        try { NativeCore.pollNativeProgress(token); fail("Finished token must be rejected") }
        catch (_: IllegalArgumentException) { /* actual JNI boundary rejection */ }
        // Resolve the actual exporter JNI symbol. Its first guarded rejection precedes all file IO.
        var exportCheckpoints = 0
        try {
            NativeAssetExports.writeObserved("rf-host-export-same-path", "rf-host-export-same-path", 0,
                "0".repeat(64), Runnable { exportCheckpoints++ })
            fail("Exporter must reject source overwrite before file IO")
        } catch (expected: IllegalArgumentException) { assertEquals("SOURCE_OVERWRITE", expected.message) }
        assertEquals(1, exportCheckpoints)
        assertEquals(setOf(openCv.path, shim.path, engine.path), nativeMappings())
        capability.verifyCurrent()
    }
    private fun required(key: String) = requireNotNull(System.getProperty("rf.loader.$key"))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun nativeMappings(): Set<String> = RuntimeProviderIdentity.ownMaps()
        .filter { File(it.path.removeSuffix(" (deleted)")).name.let { name -> name.startsWith("libopencv") || name.startsWith("librealityforge") } }
        .map { entry -> require(!entry.path.endsWith(" (deleted)")); File(entry.path).canonicalPath }.toSet()
}
