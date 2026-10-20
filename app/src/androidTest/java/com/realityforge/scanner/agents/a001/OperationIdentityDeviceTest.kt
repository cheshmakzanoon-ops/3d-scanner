package com.realityforge.scanner.agents.a001

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.ScannerViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val expected = ByteArray(4096) { index -> (index * 31).toByte() }
        application.repository.artifact(first, key, "glb").writeBytes(expected)
        assertEquals(
            1,
            application.repository.dao.complete(first, 0, key, "Agent 001 bound export fixture"),
        )
        val destination = File.createTempFile("a001-export-", ".glb", instrumentation.targetContext.cacheDir)
        destination.delete()

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
                scenario.onActivity {
                    model.select(second)
                    model.completeArtifactExport(request.get(), Uri.fromFile(destination))
                }
                awaitIdle(model)

                assertArrayEquals(expected, destination.readBytes())
                assertNotNull(application.repository.dao.project(first))
                assertEquals(0L, requireNotNull(application.repository.dao.project(second)).revision)
            }
        } finally {
            destination.delete()
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

    private fun awaitProjects(model: ScannerViewModel, vararg ids: String) {
        awaitCondition("projects were not observed") {
            ids.all { id -> model.projects.value.any { it.id == id } }
        }
    }

    private fun awaitProjectResult(model: ScannerViewModel, id: String, key: String) {
        awaitCondition("completed project result was not observed") {
            model.projects.value.any { it.id == id && it.resultKey == key }
        }
    }

    private fun awaitIdle(model: ScannerViewModel) {
        awaitCondition("operation exceeded test deadline: ${model.progress.value}") {
            !model.busy.value
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        if (!condition()) throw AssertionError(message)
    }
}
