package com.realityforge.scanner.export

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

/** Synthetic asymmetric triangle; verifies the actual packaged JNI writer, not a fake model. */
class NativeObservedExportDeviceTest {
    @Test fun actualNativeObjStlAndCancellationPreserveSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "native-observed-").toFile()
        try {
            val source = File(directory, "mesh.ply").apply { writeText(FIXTURE) }
            val hash = ExportCoordinator.digest(source)
            val exporter = LegacyMeshDerivedExports(directory)
            for (id in LegacyMeshDerivedExports.IDS) {
                val option = exporter.describe(source, id)
                val request = ExportRequest(UUID.randomUUID().toString(), ArtifactIdentity(UUID.randomUUID().toString(), 1, "synthetic", "a".repeat(64)),
                    option, ExportOptions(acknowledgedArbitraryScale = true))
                val staged = File.createTempFile("output", ".partial", directory)
                exporter.materialize(source, request, staged) {}; exporter.validate(option, staged)
                if (id == "mesh.obj.zip") ZipFile(staged).use { zip ->
                    val obj = zip.getInputStream(zip.getEntry("model.obj")).bufferedReader().readText()
                    assertTrue(obj.contains("v 1 -2 -3")); assertTrue(obj.contains("f 1//1 2//2 3//3"))
                } else {
                    val bytes = ByteBuffer.wrap(staged.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    assertEquals(134, bytes.limit()); assertEquals(1, bytes.getInt(80))
                    assertEquals(-1f, bytes.getFloat(92), 0f); assertEquals(1f, bytes.getFloat(96), 0f)
                    assertEquals(-2f, bytes.getFloat(100), 0f); assertEquals(-3f, bytes.getFloat(104), 0f)
                }
            }
            assertEquals(hash, ExportCoordinator.digest(source))
            try { NativeAssetExports.writeObserved(source.path, source.path, 0, hash, Runnable {}); fail("Source overwrite accepted") }
            catch (_: IllegalArgumentException) { }
            val cancelled = File(directory, "cancelled.obj")
            try { NativeAssetExports.writeObserved(source.path, cancelled.path, 0, hash, Runnable { throw ExportCancelled() }); fail("Cancellation ignored") }
            catch (_: ExportCancelled) { }
            assertFalse(cancelled.exists()); assertEquals(hash, ExportCoordinator.digest(source))
        } finally { directory.deleteRecursively() }
    }
    companion object {
        private val FIXTURE = "ply\nformat ascii 1.0\ncomment RealityForge observed open surface; arbitrary scale; no UV textures\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nproperty float nx\nproperty float ny\nproperty float nz\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n1 2 3 0 0 1 255 0 0\n3 2 3 0 0 1 0 255 0\n1 5 3 0 0 1 0 0 255\n3 0 1 2\n"
    }
}
