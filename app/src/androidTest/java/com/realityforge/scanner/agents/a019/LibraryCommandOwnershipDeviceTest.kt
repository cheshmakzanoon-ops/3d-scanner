package com.realityforge.scanner.agents.a019

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.MainActivity
import com.realityforge.scanner.RealityForgeApplication
import com.realityforge.scanner.ScannerViewModel
import com.realityforge.scanner.ui.ScannerApp
import com.realityforge.scanner.uiflow.UiJourneyDriver
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ViewModel admission and active ScannerApp ownership; image input is a synthetic fixture. */
@RunWith(AndroidJUnit4::class)
class LibraryCommandOwnershipDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RealityForgeApplication

    @Test fun returnedCommandCanBeCancelledImmediatelyOrDuringActionWithoutStrandingAdmission() = runBlocking {
        withTimeout(30_000) { app.compositionRoot.execution.awaitRecovery() }
        val id = app.repository.create("Synthetic command ownership ${UUID.randomUUID()}")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = model(scenario)
                withTimeout(30_000) { model.projects.first { rows -> rows.any { it.id == id } } }
                scenario.onActivity { model.select(id) }
                repeat(3) {
                    lateinit var command: Job
                    scenario.onActivity {
                        command = requireNotNull(model.projectCommand { awaitCancellation() })
                        // Same main-thread turn: cancellation can win before the IO action starts.
                        command.cancel()
                    }
                    withTimeout(30_000) { command.join() }
                    assertTrue(command.isCancelled)
                    assertFalse(model.busy.value)
                }
                val entered = CompletableDeferred<Unit>()
                val exited = CompletableDeferred<Unit>()
                lateinit var waiting: Job
                scenario.onActivity {
                    waiting = requireNotNull(model.projectCommand {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally { exited.complete(Unit) }
                    })
                }
                withTimeout(30_000) { entered.await() }
                scenario.onActivity { waiting.cancel() }
                withTimeout(30_000) { waiting.join(); exited.await() }
                assertFalse(model.busy.value)
                assertNotNull(app.repository.dao.project(id))
            }
        } finally { app.repository.delete(id) }
    }

    @Test fun activeLibraryDisposalCancelsItsCopyButDoesNotCancelAnUnrelatedCommand() = runBlocking {
        withTimeout(30_000) { app.compositionRoot.execution.awaitRecovery() }
        val name = "Synthetic copy ownership ${UUID.randomUUID()}"
        val id = app.repository.create(name)
        val photo = File.createTempFile("a019-synthetic-", ".jpg", app.cacheDir)
        try {
            instrumentation.context.assets.open("relief/view-01.jpg").use { input ->
                photo.outputStream().use { input.copyTo(it) }
            }
            assertTrue(app.repository.importPhoto(id, Uri.fromFile(photo)))
            val originals = app.repository.dao.photos(id)
            val hashes = originals.map { hash(app.repository.photoFile(it)) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val model = model(scenario)
                withTimeout(30_000) { model.projects.first { rows -> rows.any { it.id == id } } }
                val ui = UiJourneyDriver()
                ui.startAtLibrary()
                ui.setSoleLabeledText(name, "a072_search", "Search projects")
                ui.scrollToClick(instrumentation.targetContext.getString(com.realityforge.scanner.R.string.a072_duplicate))
                // Hold the actual Room transaction queue after the dialog has bound the source.
                // This deterministically keeps the real duplicate command pending, without a
                // fake adapter or a huge timing-sensitive file copy.
                val locked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val blocker = launch(Dispatchers.IO) {
                    app.compositionRoot.database.withTransaction { locked.complete(Unit); release.await() }
                }
                try {
                    withTimeout(30_000) { locked.await() }
                    ui.click("Save")
                    ui.await { model.busy.value }
                    ui.awaitText("a072-copy-progress")
                    scenario.onActivity { activity -> activity.setContent { Text("Copy route disposed") } }
                    ui.awaitText("Copy route disposed")
                    withTimeout(30_000) { model.busy.first { !it } }
                } finally { release.complete(Unit); withTimeout(30_000) { blocker.join() } }
                assertEquals(hashes, originals.map { hash(app.repository.photoFile(it)) })
                assertEquals(1, app.repository.dao.observeProjects().first().count { it.name == name })

                // A fresh library composition owns no copy. Disposing it must not invoke the
                // ViewModel's global pause/cancel against a subsequent, unrelated operation.
                scenario.onActivity { activity -> activity.setContent { ScannerApp(model) } }
                ui.startAtLibrary()
                val entered = CompletableDeferred<Unit>()
                lateinit var unrelated: Job
                scenario.onActivity {
                    model.select(id)
                    unrelated = requireNotNull(model.projectCommand { entered.complete(Unit); awaitCancellation() })
                }
                try {
                    withTimeout(30_000) { entered.await() }
                    scenario.onActivity { activity -> activity.setContent { Text("Unowned route disposed") } }
                    ui.awaitText("Unowned route disposed")
                    assertTrue("Route disposal cancelled an unrelated command", unrelated.isActive)
                    assertTrue(model.busy.value)
                } finally { unrelated.cancel(); withTimeout(30_000) { unrelated.join() } }
                assertFalse(model.busy.value)
                assertEquals(hashes, originals.map { hash(app.repository.photoFile(it)) })
            }
        } finally { photo.delete(); app.repository.delete(id) }
    }

    private fun model(scenario: ActivityScenario<MainActivity>): ScannerViewModel {
        lateinit var model: ScannerViewModel
        scenario.onActivity { model = ViewModelProvider(it)[ScannerViewModel::class.java] }
        return model
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
