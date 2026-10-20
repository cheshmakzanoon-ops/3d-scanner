package com.realityforge.scanner.agents.a002

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.DepthCheckpointIdentity
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.nativebridge.NativeResultJson
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android JNI and private checkpoint files; the projected random-color plane is synthetic. */
@RunWith(AndroidJUnit4::class)
class NativeSurfaceCheckpointDeviceTest {
    @Test fun nativeCheckpointReusesExactGeometryForLargerAtlasAndRejectsChangedCamera() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "a002-surface-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val token = NativeCore.beginReconstruction()
        try {
            val (rgba, cameras) = fixture()
            val identity = DepthCheckpointIdentity.fingerprint("sparse-m18-r1-0123456789abcdef",
                rgba, cameras, WIDTH, HEIGHT, 3.2, 7.2, 56, 3)
            val directory = DepthCheckpointIdentity.directory(root, identity)
            val initial = NativeResultJson.decode(NativeCore.reconstructTexturedSurfaceCheckpointed(
                rgba, WIDTH, HEIGHT, rgba, WIDTH, HEIGHT, cameras, 3.2, 7.2, 56, 3,
                File(root, "initial.mesh.glb.partial").path, File(root, "initial.mesh.ply.partial").path,
                File(root, "initial.photo.glb.partial").path, File(root, "initial.photo.png.partial").path,
                token, directory.path, identity))
            assertFalse(initial.has("error"))
            assertTrue(initial.getBoolean("surfaceCheckpointWritten"))
            assertTrue(initial.getJSONObject("mesh").getInt("triangleCount") >= 40)
            val mesh = File(directory, "surface-v1.rfmesh")
            assertTrue(mesh.length() in 1..12L * 1024 * 1024)
            val protectedFiles = (listOf(mesh) + (0..2).map { File(directory, "ref-$it.bin") }).associateWith(::sha)
            val larger = ByteArray(3 * WIDTH * 2 * HEIGHT * 2 * 4)
            for (camera in 0..2) for (y in 0 until HEIGHT * 2) for (x in 0 until WIDTH * 2) {
                val source = (camera * WIDTH * HEIGHT + y / 2 * WIDTH + x / 2) * 4
                val target = (camera * WIDTH * 2 * HEIGHT * 2 + y * WIDTH * 2 + x) * 4
                rgba.copyInto(larger, target, source, source + 4)
            }
            val replay = NativeResultJson.decode(NativeCore.textureAcceptedSurfaceCheckpointed(
                rgba, WIDTH, HEIGHT, larger, WIDTH * 2, HEIGHT * 2, cameras, mesh.path,
                File(root, "replay.photo.glb.partial").path, File(root, "replay.photo.png.partial").path,
                token, directory.path, identity))
            assertTrue(replay.getBoolean("geometryReused"))
            assertEquals(initial.getJSONObject("mesh").toString(), replay.getJSONObject("mesh").toString())
            assertEquals(initial.getJSONObject("dense").getJSONArray("points").toString(),
                replay.getJSONObject("dense").getJSONArray("points").toString())
            assertEquals(0, replay.getJSONObject("dense").getInt("writtenDepthMaps"))
            assertTrue(replay.getJSONObject("texture").getInt("atlasWidth") > initial.getJSONObject("texture").getInt("atlasWidth"))
            protectedFiles.forEach { (file, hash) -> assertEquals(hash, sha(file)) }
            val changed = cameras.copyOf().apply { this[16 + 9] += .01 }
            val rejectedGlb = File(root, "rejected.glb.partial")
            val rejected = NativeResultJson.decode(NativeCore.textureAcceptedSurfaceCheckpointed(
                rgba, WIDTH, HEIGHT, rgba, WIDTH, HEIGHT, changed, mesh.path, rejectedGlb.path,
                File(root, "rejected.png.partial").path, token, directory.path, identity))
            assertEquals("CHECKPOINT_REJECTED", rejected.getString("errorCode"))
            assertFalse(rejectedGlb.exists())
            assertTrue(NativeCore.cancelReconstruction(token))
            try {
                NativeResultJson.decode(NativeCore.textureAcceptedSurfaceCheckpointed(
                    rgba, WIDTH, HEIGHT, rgba, WIDTH, HEIGHT, cameras, mesh.path,
                    File(root, "cancelled.glb.partial").path, File(root, "cancelled.png.partial").path,
                    token, directory.path, identity))
                fail("Cancelled native replay was accepted")
            } catch (_: CancellationException) { }
            protectedFiles.forEach { (file, hash) -> assertEquals(hash, sha(file)) }
        } finally {
            NativeCore.finishReconstruction(token)
            root.deleteRecursively()
        }
    }

    @Test fun invalidJniBufferShapeIsRejectedBeforeNativeCopies() {
        val token = NativeCore.beginReconstruction()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                NativeCore.textureAcceptedSurfaceCheckpointed(ByteArray(1), WIDTH, HEIGHT,
                    ByteArray(1), WIDTH, HEIGHT, DoubleArray(32), "mesh", "output", "png", token,
                    "directory", "0".repeat(64))
            }
        } finally { NativeCore.finishReconstruction(token) }
    }

    private fun fixture(): Pair<ByteArray, DoubleArray> {
        val random = Random(188)
        val texture = IntArray(256 * 256 * 3) { random.nextInt(256) }
        fun sample(x: Double, y: Double, channel: Int): Int {
            val sx = (x * 31 + 128).coerceIn(0.0, 254.999)
            val sy = (y * 31 + 128).coerceIn(0.0, 254.999)
            val ix = sx.toInt(); val iy = sy.toInt(); val dx = sx - ix; val dy = sy - iy
            fun pixel(xx: Int, yy: Int) = texture[((iy + yy) * 256 + ix + xx) * 3 + channel]
            return ((1 - dx) * (1 - dy) * pixel(0, 0) + dx * (1 - dy) * pixel(1, 0) +
                (1 - dx) * dy * pixel(0, 1) + dx * dy * pixel(1, 1)).roundToInt().coerceIn(0, 255)
        }
        val cameras = DoubleArray(48)
        val rgba = ByteArray(3 * WIDTH * HEIGHT * 4)
        for (camera in 0..2) {
            for (k in 0..8) cameras[camera * 16 + k] = if (k % 4 == 0) 1.0 else 0.0
            cameras[camera * 16 + 9] = -camera * .55
            cameras[camera * 16 + 12] = 116.0; cameras[camera * 16 + 13] = 116.0
            cameras[camera * 16 + 14] = (WIDTH - 1) / 2.0; cameras[camera * 16 + 15] = (HEIGHT - 1) / 2.0
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val worldX = (x - (WIDTH - 1) / 2.0) * 5 / 116 + camera * .55
                val worldY = (y - (HEIGHT - 1) / 2.0) * 5 / 116
                val pixel = (camera * WIDTH * HEIGHT + y * WIDTH + x) * 4
                for (channel in 0..2) rgba[pixel + channel] = sample(worldX, worldY, channel).toByte()
                rgba[pixel + 3] = 255.toByte()
            }
        }
        return rgba to cameras
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object { const val WIDTH = 104; const val HEIGHT = 76 }
}
