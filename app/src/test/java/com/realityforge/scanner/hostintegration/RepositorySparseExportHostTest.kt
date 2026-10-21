package com.realityforge.scanner.hostintegration

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.jobs.*
import com.realityforge.scanner.data.operations.*
import com.realityforge.scanner.data.publication.*
import com.realityforge.scanner.export.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * HOST routing regression: real Room job lease, publication transaction and repository export.
 * The one-point preview/PLY are synthetic producer fixtures, NOT reconstructed photographs.
 * Actual native-writer interoperability is a separate gate; no JNI/GPU/device claims here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RepositorySparseExportHostTest {
    @Test(timeout = 120_000) fun currentSparsePlyIsAdvertisedMaterializedAndValidatedAcrossReopen() = runBlocking(Dispatchers.IO) {
        withFixture { f ->
            val bytes = sparsePly().toByteArray(Charsets.US_ASCII)
            f.publish(bytes)
            f.assertExport(bytes)
            f.reopen()
            f.assertExport(bytes)
        }
    }

    @Test(timeout = 120_000) fun verifiedButMalformedOrDensePlyIsNotAdvertisedAsSparse() = runBlocking(Dispatchers.IO) {
        val candidates = listOf(
            sparsePly().replace("1 2 3 12 128 255\n", "1 2 3 12 128\n"),
            sparsePly().replace("sparse_points", "dense_points"),
        )
        for (candidate in candidates) withFixture { f ->
            // Publication verifies byte identity, not PLY semantics: the export adapter must filter it.
            f.publish(candidate.toByteArray(Charsets.US_ASCII))
            val source = f.source()
            val identity = requireNotNull(source.snapshot(f.project))
            val options = source.available(identity)
            assertTrue(options.any { it.id == "json" })
            assertFalse(options.any { it.id == "ply" })
            assertTrue(requireNotNull(f.repository.publications).current(f.project) is PublicationReadResult.Verified)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        var primary: Throwable? = null
        try { f.initialize(); block(f) }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { f.close() } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    private class Fixture {
        private val app: Application = RuntimeEnvironment.getApplication()
        private val root = File(app.cacheDir, "export-host-${UUID.randomUUID()}").apply { check(mkdir()) }
        private val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = File(root, "files").apply { check(isDirectory || mkdir()) }
            override fun getCacheDir(): File = File(root, "cache").apply { check(isDirectory || mkdir()) }
            override fun getApplicationContext(): Context = this
        }
        private val name = "export-host-${UUID.randomUUID()}.db"
        private var database = open()
        var repository = ProjectRepository(context, database.dao(), database = database)
            private set
        lateinit var project: String
        private val key = "sparse-m19-r0-0123456789abcdef"
        private var jobId: JobId by kotlin.properties.Delegates.notNull()
        private fun open() = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
        suspend fun initialize() { project = repository.create("Synthetic export routing fixture") }
        fun reopen() {
            database.close()
            database = open()
            repository = ProjectRepository(context, database.dao(), database = database)
        }
        fun source() = RepositoryExportSource(repository,
            publication = { requireNotNull(repository.publications).current(it) })

        suspend fun publish(ply: ByteArray) {
            val operation = UUID.randomUUID().toString()
            val input = JobInputSnapshot(project, 0, operation,
                sha("synthetic source identity".toByteArray()), sha("synthetic calibration".toByteArray()),
                sha("{}".toByteArray()), sha("synthetic producer identity".toByteArray()), "host-export-fixture-v1")
            val jobs = RoomJobStore(database, database.jobDao())
            val now = System.currentTimeMillis()
            jobId = jobs.enqueue(input, now).jobId
            val running = requireNotNull(jobs.acquireNext("host-export-fixture", now, 120_000))
            assertEquals(jobId, running.lease.jobId)
            val identity = PublicationIdentity(PublicationPurpose.RESULT_SET, project, 0, operation,
                running.lease.attempt, input.inputSha256, input.settingsSha256, input.engineVersion)
            val preview = JSONObject().put("schema", 1).put("representation", "sparse_points")
                .put("scale", "arbitrary").put("revision", 0).put("artifactKey", key)
                .put("registeredCameras", 2).put("pointCount", 1)
                .put("points", JSONArray().put(JSONArray(listOf(1.0, 2.0, 3.0, 12, 128, 255))))
            repository.withProjectAccess(ProjectOperationRequest(project, operation,
                ProjectOperationKind.PUBLISH_RESULT, 0,
                ProjectJobIdentity(jobId.value, running.lease.token))) {
                requireNotNull(repository.publications).beginResult(identity, listOf(
                    ArtifactDeclaration("preview", "preview.json", "sparse_points", true, 4096),
                    ArtifactDeclaration("sparse-ply", "sparse.ply", "sparse_points", true, 4096),
                )).apply {
                    stageBytes("preview", preview.toString().toByteArray(Charsets.UTF_8))
                    stageBytes("sparse-ply", ply)
                    publish()
                }
            }
            assertEquals("COMPLETED", database.jobDao().job(jobId.value)?.state)
            assertEquals(0L, database.dao().project(project)?.resultRevision)
            assertEquals(key, database.dao().project(project)?.resultKey)
            assertTrue(requireNotNull(repository.publications).current(project) is PublicationReadResult.Verified)
            // No legacy flat file can accidentally satisfy the current-publication routing assertion.
            assertFalse(repository.artifact(project, key, "ply").exists())
            assertFalse(repository.artifact(project, key, "json").exists())
        }

        suspend fun assertExport(expected: ByteArray) {
            assertEquals("COMPLETED", database.jobDao().job(jobId.value)?.state)
            val verified = requireNotNull(repository.publications).current(project) as PublicationReadResult.Verified
            assertArrayEquals(expected, requireNotNull(verified.value.artifact("sparse-ply")).readBytes())
            val source = source()
            val identity = requireNotNull(source.snapshot(project))
            assertEquals(0L, identity.revision)
            assertEquals(key, identity.artifactKey)
            val option = source.available(identity).single { it.id == "ply" }
            assertEquals(ExportFormat.PLY, option.format)
            assertEquals(AssetRepresentation.SPARSE_POINTS, option.representation)
            assertEquals(expected.size.toLong(), option.bytes)
            assertEquals(sha(expected), option.contentSha256)
            val request = RepositoryExportSource.request(identity, option)
            val target = File(context.cacheDir, "materialized-${UUID.randomUUID()}.ply")
            var checkpoints = 0
            var primary: Throwable? = null
            try {
                source.materialize(request, target) { checkpoints++ }
                assertTrue(checkpoints > 0)
                assertArrayEquals(expected, target.readBytes())
                source.validate(request, target)
                assertTrue(source.isCurrent(identity))
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                try { check(!target.exists() || target.delete()) }
                catch (cleanup: Throwable) {
                    if (primary == null) throw cleanup
                    if (primary !== cleanup) primary.addSuppressed(cleanup)
                }
            }
        }

        fun close() {
            var failure: Throwable? = null
            val cleanup: List<() -> Unit> = listOf(
                { database.close() },
                { check(!context.getDatabasePath(name).exists() || context.deleteDatabase(name)) },
                { check(!root.exists() || root.deleteRecursively()) },
            )
            cleanup.forEach { action ->
                try { action() } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    companion object {
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun sparsePly() = """
            ply
            format ascii 1.0
            comment RealityForge sparse_points; arbitrary scale; x-right y-down z-forward
            comment representation sparse_points
            element vertex 1
            property float x
            property float y
            property float z
            property uchar red
            property uchar green
            property uchar blue
            end_header
            1 2 3 12 128 255
        """.trimIndent() + "\n"
    }
}
