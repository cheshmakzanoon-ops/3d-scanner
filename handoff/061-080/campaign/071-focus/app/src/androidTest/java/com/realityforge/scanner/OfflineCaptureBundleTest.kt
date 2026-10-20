package com.realityforge.scanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.engine.NativeCore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class OfflineCaptureBundleTest {
    @Test fun importsAndExportsOriginalBytesWithoutNetworkPermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ScanDatabase::class.java).build()
        val repository = ProjectRepository(context, database.dao())
        val project = repository.create("Offline capture contract")
        val temporary = mutableListOf<File>()
        try {
            for (seed in 1..3) {
                val random = Random(seed)
                val pixels = IntArray(160 * 120) {
                    android.graphics.Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256))
                }
                val bmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
                bmp.setPixels(pixels, 0, 160, 0, 0, 160, 120)
                val photo = File.createTempFile("rf-m05-$seed", ".jpg", context.cacheDir)
                temporary.add(photo)
                photo.outputStream().use { assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                bmp.recycle()
                val uri = Uri.fromFile(photo)
                assertTrue(repository.importPhoto(project, uri))
                assertFalse(repository.importPhoto(project, uri)) // SHA-256 duplicate
            }
            val written = ByteArrayOutputStream()
            assertEquals(3, repository.exportCaptureBundle(project, written))
            val entries = HashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(written.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val bytes = zip.readBytes()
                    entries[entry.name] = bytes
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(requireNotNull(entries["session.json"]).toString(Charsets.UTF_8))
            assertEquals(1,manifest.getInt("schema"))
            assertFalse(manifest.getBoolean("physicalCaptureVerified"))
            assertFalse(manifest.getBoolean("calibrationIncluded"))
            val photos = manifest.getJSONArray("photos")
            assertEquals(3, photos.length())
            for (i in 0 until photos.length()) {
                val item = photos.getJSONObject(i)
                val bytes = requireNotNull(entries[item.getString("path")])
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                assertEquals(item.getString("sha256"),digest)
                assertEquals(item.getLong("bytes"),bytes.size.toLong())
            }
            val key = "sparse-m04-r2-0123456789abcdef"
            assertEquals("$key.glb", repository.artifact(project,key,"glb").name)
            assertEquals("$key.json", repository.artifact(project,key,"json").name)
        } finally {
            temporary.forEach { it.delete() }
            repository.delete(project)
            database.close()
        }
    }

    @Test fun nativeMultiViewTriangulationRunsThroughJniOnEmulator() {
        val rotations = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val translations = listOf(doubleArrayOf(0.0,0.0,0.0),
            doubleArrayOf(-1.0,0.0,0.0),doubleArrayOf(-1.7,0.05,0.0))
        val poses = translations.flatMap { (rotations+it).asIterable() }.toDoubleArray()
        val observations = ArrayList<Double>()
        val random = Random(777)
        for (point in 0 until 90) {
            val x = random.nextDouble(-1.0,1.0)
            val y = random.nextDouble(-0.8,0.8)
            val z = random.nextDouble(3.0,7.0)
            for (camera in 0..2) {
                observations.add(point.toDouble())
                observations.add(camera.toDouble())
                observations.add((x+translations[camera][0])/z)
                observations.add((y+translations[camera][1])/z)
                observations.add(140.0);observations.add(170.0);observations.add(190.0)
            }
        }
        val result = JSONObject(NativeCore.reconstructMultiview(poses, observations.toDoubleArray(), .003, 8))
        assertFalse(result.toString(), result.has("error"))
        assertEquals(3,result.getInt("registeredCameras"))
        assertTrue(result.getInt("pointCount") >= 85)
        assertEquals("sparse_points",result.getString("representation"))
        assertEquals(2,result.getInt("anchorCamerasFixed"))
    }
}
