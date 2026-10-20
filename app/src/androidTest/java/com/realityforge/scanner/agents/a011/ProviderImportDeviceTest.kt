package com.realityforge.scanner.agents.a011

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.capture.metadata.CaptureMetadataCodec
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.importing.*
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual provider streams, decoder/JNI quality, C6 repository reservation and Room publication. */
@RunWith(AndroidJUnit4::class)
class ProviderImportDeviceTest {
    @Test fun mixedProviderAndLocalBatchPreservesExactBytesAndFixedDestinationAcrossReopen() = runBlocking {
        withFixture { context, database, repository, databaseName ->
            val project = repository.create("Fixed import destination")
            val provider = providerUri("pattern-png")
            val providerBytes = context.contentResolver.openInputStream(provider)!!.use { it.readBytes() }
            assertEquals("image/jpeg", context.contentResolver.getType(provider))
            // The actual header is PNG despite the provider advertising JPEG and Long.MAX_VALUE bytes.
            assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), providerBytes.take(4).toByteArray())
            val local = File(context.cacheDir, "local-rotated.jpg")
            createLocalJpeg(local)
            val localBytes = local.readBytes()
            val request = ImportRequest(UUID.randomUUID().toString(), project, 0, listOf(
                ImportSource(provider.toString()), ImportSource(provider.toString()),
                ImportSource(providerUri("revoked").toString()), ImportSource(providerUri("corrupt").toString()),
                ImportSource(Uri.fromFile(local).toString())))
            val otherProject = repository.create("Created after the picker request")
            val result = repository.importBatch(request)
            assertEquals(2, result.importedCount)
            assertEquals(1, result.duplicateCount)
            assertEquals(2, result.rejectedCount)
            assertEquals(0, result.pendingCount)
            assertEquals(2L, result.finalRevision)
            assertEquals("SOURCE_PERMISSION_LOST", (result.photos[2].outcome as ImportPublicationResult.Rejected).code)
            val photos = database.dao().photos(project)
            assertEquals(2, photos.size)
            assertTrue(database.dao().photos(otherProject).isEmpty())
            assertEquals(2L, database.dao().project(project)?.revision)
            val expected = listOf(providerBytes, localBytes)
            photos.forEachIndexed { index, photo ->
                assertEquals(project, photo.projectId)
                assertEquals(sha(expected[index].inputStream()), photo.sha256)
                assertArrayEquals(expected[index], repository.photoFile(photo).readBytes())
                assertEquals(80, photo.width)
                assertEquals(64, photo.height)
                val encoded = requireNotNull(photo.captureMetadataRecord).toByteArray(Charsets.UTF_8)
                assertArrayEquals(encoded, CaptureMetadataCodec.encode(CaptureMetadataCodec.decode(encoded)))
            }
            assertArrayEquals(localBytes, local.readBytes())
            assertArrayEquals(providerBytes, context.contentResolver.openInputStream(provider)!!.use { it.readBytes() })
            assertTrue(File(context.filesDir, "import-staging-v1").listFiles().orEmpty().isEmpty())
            assertFalse(repository.directory(project).listFiles().orEmpty().any { it.name.endsWith(".journal") })
            database.close()
            Room.databaseBuilder(context, ScanDatabase::class.java, databaseName).build().use { reopened ->
                val recovered = ProjectRepository(context, reopened.dao(), database = reopened)
                recovered.recoverStorage()
                assertEquals(2L, reopened.dao().project(project)?.revision)
                assertEquals(photos.map { it.sha256 }, reopened.dao().photos(project).map { sha(recovered.photoFile(it).inputStream()) })
                assertTrue(reopened.dao().photos(otherProject).isEmpty())
            }
        }
    }

    @Test fun cancellationDuringRealProviderStreamingPublishesNothingAndRecoveryLeavesNoStaging() = runBlocking {
        withFixture { context, database, repository, _ ->
            val project = repository.create("Cancelled provider stream")
            val request = ImportRequest(UUID.randomUUID().toString(), project, 0,
                listOf(ImportSource(providerUri("large-png").toString())))
            var progressCount = 0
            try {
                repository.importBatch(request) {
                    progressCount++
                    throw CancellationException("Injected after bounded provider progress")
                }
                fail("Provider cancellation was swallowed")
            } catch (_: CancellationException) { }
            assertEquals(1, progressCount)
            repository.recoverStorage()
            assertEquals(0L, database.dao().project(project)?.revision)
            assertTrue(database.dao().photos(project).isEmpty())
            assertTrue(File(context.filesDir, "import-staging-v1").listFiles().orEmpty().isEmpty())
            assertTrue(File(repository.directory(project), "originals").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun roomPublicationFaultsDistinguishAbortedCommittedAndUnknownOutcomesBeforeRecovery() = runBlocking {
        for (fault in listOf("before-commit", "lost-commit-reply", "uncertain-readback")) {
            withFixture { context, database, _, _ ->
                val enabled = AtomicBoolean(true)
                val publicationAttempted = AtomicBoolean(false)
                val actual = database.dao()
                val injected = object : ScanDao by actual {
                    override suspend fun addPhotoExpected(photo: ScanPhoto, expectedRevision: Long): Long {
                        if (!enabled.get()) return actual.addPhotoExpected(photo, expectedRevision)
                        publicationAttempted.set(true)
                        if (fault == "lost-commit-reply") actual.addPhotoExpected(photo, expectedRevision)
                        throw IOException("Injected Room publication boundary failure")
                    }
                    override suspend fun photoByHash(projectId: String, sha256: String): ScanPhoto? {
                        if (enabled.get() && publicationAttempted.get() && fault == "uncertain-readback") {
                            throw IOException("Injected unavailable publication readback")
                        }
                        return actual.photoByHash(projectId, sha256)
                    }
                }
                val repository = ProjectRepository(context, injected, database = database)
                val project = repository.create("Publication fault: $fault")
                val source = providerUri("pattern-png")
                val expectedHash = sha(context.contentResolver.openInputStream(source)!!)
                val result = repository.importBatch(ImportRequest(UUID.randomUUID().toString(), project, 0,
                    listOf(ImportSource(source.toString()))))
                assertTrue(publicationAttempted.get())
                when (fault) {
                    "before-commit" -> {
                        assertEquals("PUBLICATION_ABORTED", (result.photos.single().outcome as ImportPublicationResult.Rejected).code)
                        assertEquals(0, result.importedCount)
                        assertTrue(actual.photos(project).isEmpty())
                        assertTrue(File(repository.directory(project), "originals").listFiles().orEmpty().isEmpty())
                    }
                    "lost-commit-reply" -> {
                        assertEquals(1, result.importedCount)
                        assertEquals(1L, result.finalRevision)
                        assertEquals(1, actual.photos(project).size)
                    }
                    "uncertain-readback" -> {
                        assertEquals(1, result.pendingCount)
                        assertEquals(0, result.importedCount)
                        assertEquals(0, result.rejectedCount)
                        assertTrue(actual.photos(project).isEmpty())
                        assertTrue(repository.directory(project).listFiles().orEmpty().any { it.name.endsWith(".journal") })
                    }
                }
                enabled.set(false)
                repository.recoverStorage()
                val retained = actual.photos(project)
                assertEquals(if (fault == "before-commit") 0 else 1, retained.size)
                retained.forEach { photo ->
                    assertEquals(expectedHash, photo.sha256)
                    assertEquals(expectedHash, sha(repository.photoFile(photo).inputStream()))
                }
                assertFalse(repository.directory(project).listFiles().orEmpty().any { it.name.endsWith(".journal") })
                assertTrue(File(context.filesDir, "import-staging-v1").listFiles().orEmpty().isEmpty())
            }
        }
    }

    private suspend fun withFixture(block: suspend (Context, ScanDatabase, ProjectRepository, String) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "a011-import-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(root, "files").apply { check(isDirectory || mkdir()) }
            override fun getCacheDir(): File = File(root, "cache").apply { check(isDirectory || mkdir()) }
        }
        val name = "a011-import-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        try { block(context, database, ProjectRepository(context, database.dao(), database = database), name) }
        finally { database.close(); base.deleteDatabase(name); root.deleteRecursively() }
    }

    private fun createLocalJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(80, 64, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(80 * 64) { index ->
                -0x1000000 or (((index % 80) * 3) shl 16) or (((index / 80) * 4) shl 8) or (index % 251)
            }
            bitmap.setPixels(pixels, 0, 80, 0, 0, 80, 64)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it)) }
        } finally { bitmap.recycle() }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
    }
    private fun providerUri(key: String): Uri = Uri.parse("content://${FixturePhotoProvider.AUTHORITY}/$key")
    private fun sha(stream: InputStream): String = stream.use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) hash.update(buffer, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
