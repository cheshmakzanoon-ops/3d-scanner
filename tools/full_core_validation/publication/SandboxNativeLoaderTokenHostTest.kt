package com.realityforge.scanner.hostintegration

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.realityforge.scanner.engine.NativeCore
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
class SandboxNativeLoaderTokenHostTest {
    @Test(timeout = 120_000) fun actualLoadersAndOwnedTokenLifecycleUseVerifiedLibraries() {
        val openCv = verified("opencv", "e573a54e917ca142706d37537058009000c0df9157bc46cb66fbfec5a522d2d1")
        val shim = verified("shim", "519a3d15329520211d1d67756fe619308453e35445b349f2c162362b941af9ea")
        val engine = verified("engine", "4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b")
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
            val bytes = requireNotNull(loader.getResourceAsStream(resource)).use { it.readBytes() }
            assertEquals("Original class resource identity: $name", required("classSha.$name"), sha(bytes))
        }
        assertSame(loader, Log::class.java.classLoader)
        assertTrue(Modifier.isNative(NativeCore::class.java.getDeclaredMethod("beginReconstruction").modifiers))
        val exportOwner = Class.forName("com.realityforge.scanner.export.NativeAssetExports", false, loader)
        assertTrue("Export entrypoint must remain original native method", Modifier.isNative(
            exportOwner.getDeclaredMethod("writeObserved", String::class.java, String::class.java,
                Int::class.javaPrimitiveType, String::class.java, Runnable::class.java).modifiers))
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
        verified("opencv", "e573a54e917ca142706d37537058009000c0df9157bc46cb66fbfec5a522d2d1")
        verified("shim", "519a3d15329520211d1d67756fe619308453e35445b349f2c162362b941af9ea")
        verified("engine", "4384f676ab950a3e615d286be8d8e9e4f16e28e22fa5fda4177b39445f6c786b")
    }
    private fun required(key: String) = requireNotNull(System.getProperty("rf.loader.$key"))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun verified(key: String, expected: String): File = File(required(key)).canonicalFile.also {
        require(it.isFile && it.length() in 1..(128L * 1024 * 1024))
        assertEquals(expected, sha(it.readBytes()))
    }
    private fun nativeMappings(): Set<String> = File("/proc/self/maps").readLines()
        .mapNotNull { it.split(Regex("\\s+"), limit = 6).getOrNull(5) }
        .filter { it.startsWith("/") }
        .map { File(it).canonicalFile }
        .filter { it.name.startsWith("libopencv") || it.name.startsWith("librealityforge") }
        .map { it.path }.toSet()
}
