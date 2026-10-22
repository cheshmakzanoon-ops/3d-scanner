package com.realityforge.scanner.agents.a001

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.qa.DeviceOperationAwait
import com.realityforge.scanner.qa.SharedFaultDocumentFixtures
import com.realityforge.scanner.qa.SyntheticPreviewFixtures
import com.realityforge.scanner.viewer.loading.GlbStructure
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationIdentityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var application: RealityForgeApplication
    private val projectIds = mutableListOf<String>()

    @Before
    fun setUp() {
        application = instrumentation.targetContext.applicationContext as RealityForgeApplication
    }

    @After
    fun tearDown() = runBlocking {
        projectIds.reversed().forEach { id -> runCatching { application.repository.delete(id) } }
    }

    @Test
    fun delayedCaptureAfterSelectionChangeAndRecreationTargetsBoundProjectOnce() = runBlocking {
        val first = newProject("Agent 001 capture source")
        val second = newProject("Agent 001 unrelated selection")
        val captured = assetCopy("relief/view-01.jpg", "a001-capture-")
        val duplicateCallback = assetCopy("relief/view-02.jpg", "a001-stale-capture-")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var model = modelFrom(scenario)
            awaitProjects(model, first, second)
            scenario.onActivity {
                model.select(first)
            }
            val request = AtomicReference<String>()
            scenario.onActivity {
                request.set(requireNotNull(model.beginCaptureRequest()))
            }

            // Recreate after launcher identity is bound, then select another project before result.
            scenario.recreate()
            model = modelFrom(scenario)
            awaitProjects(model, first, second)
            scenario.onActivity {
                model.select(second)
                model.completeCapture(request.get(), captured)
            }
            awaitIdle(model)

            assertEquals(1, application.repository.dao.photos(first).size)
            assertEquals(0, application.repository.dao.photos(second).size)
            assertFalse(captured.exists())

            // A duplicated/stale callback is rejected and cannot add another photo anywhere.
            scenario.onActivity {
                model.completeCapture(request.get(), duplicateCallback)
            }
            awaitIdle(model)
            assertEquals(1, application.repository.dao.photos(first).size)
            assertEquals(0, application.repository.dao.photos(second).size)
            assertFalse(duplicateCallback.exists())
        }
    }

    @Test
    fun delayedExportAfterSelectionChangeUsesBoundArtifactBytes() = runBlocking {
        val first = newProject("Agent 001 export source")
        val second = newProject("Agent 001 export unrelated")
        val key = "sparse-m19-r0-0123456789abcdef"
        // A valid explicitly synthetic legacy sparse result, not arbitrary bytes named .glb.
        val points = JSONArray((0 until 512).map { index ->
            JSONArray(listOf(index * .01, (index % 13) * .02, 3.0, index % 256, 64, 192))
        })
        val preview = SyntheticPreviewFixtures.sparsePreview(key,0)
            .put("points",points).put("pointCount",points.length())
        val source = application.repository.artifact(first,key,"glb")
        NativeCore.exportPointCloud(DoubleArray(points.length()*6) { index ->
            points.getJSONArray(index/6).getDouble(index%6)
        },source.path,true,NativeCore.SPARSE_POINTS)
        assertEquals(points.length(),GlbStructure.validate(source,expectedPrimitiveMode=0).vertexCount)
        val expected = source.readBytes()
        assertTrue("Exact-byte transfer must still cover at least the old 4 KiB payload",expected.size>=4096)
        application.repository.writeAtomic(application.repository.artifact(first,key,"json"),preview.toString())
        assertEquals(
            1,
            application.repository.dao.complete(first, 0, key, "Agent 001 bound export fixture"),
        )
        assertEquals(key,requireNotNull(application.repository.currentPreview(first)).getString("artifactKey"))
        val destination = SharedFaultDocumentFixtures.createUri(application)
        var exportFailure: Throwable? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var model = modelFrom(scenario)
                awaitProjectResult(model, first, key)
                scenario.onActivity { model.select(first) }
                val request = AtomicReference<String>()
                scenario.onActivity {
                    request.set(requireNotNull(model.beginArtifactExportRequest("glb")))
                }

                scenario.recreate()
                model = modelFrom(scenario)
                awaitProjects(model, first, second)
                DeviceOperationAwait.verifiedArtifactExport(model) {
                    scenario.onActivity {
                        model.select(second)
                        model.completeArtifactExport(request.get(), destination)
                    }
                }

                assertArrayEquals(expected, SharedFaultDocumentFixtures.read(application,destination))
                assertArrayEquals("Export cannot alter the bound source",expected,source.readBytes())
                assertNotNull(application.repository.dao.project(first))
                assertEquals(0L, requireNotNull(application.repository.dao.project(second)).revision)
            }
        } catch (failure: Throwable) {
            exportFailure = failure
            throw failure
        } finally {
            try { SharedFaultDocumentFixtures.cleanup(application,destination) }
            catch (cleanup: Throwable) {
                val primary = exportFailure
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun newProject(name: String): String = application.repository.create(name).also(projectIds::add)

    private fun assetCopy(path: String, prefix: String): File =
        File.createTempFile(prefix, ".jpg", instrumentation.targetContext.cacheDir).also { target ->
            instrumentation.context.assets.open(path).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }

    private fun modelFrom(scenario: ActivityScenario<MainActivity>): ScannerViewModel {
        val reference = AtomicReference<ScannerViewModel>()
        scenario.onActivity { activity ->
            reference.set(ViewModelProvider(activity)[ScannerViewModel::class.java])
        }
        return requireNotNull(reference.get())
    }

    private suspend fun awaitProjects(model: ScannerViewModel, vararg ids: String) = withTimeout(30_000) {
        model.projects.first { rows -> ids.all { id -> rows.any { it.id == id } } }
    }

    private suspend fun awaitProjectResult(model: ScannerViewModel, id: String, key: String) = withTimeout(30_000) {
        model.projects.first { rows -> rows.any { it.id == id && it.resultKey == key && it.resultRevision == it.revision } }
    }

    private suspend fun awaitIdle(model: ScannerViewModel) = withTimeout(30_000) {
        model.busy.first { !it }
    }
}
