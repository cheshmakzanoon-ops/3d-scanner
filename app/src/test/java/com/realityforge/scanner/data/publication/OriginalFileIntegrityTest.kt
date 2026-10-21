package com.realityforge.scanner.data.publication

import com.realityforge.scanner.capture.session.*
import com.realityforge.scanner.data.importing.ImportPublicationResult
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginalFileIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "valid original".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun matches(file: File) = OriginalFileIntegrity.matches(file, hash, bytes.size.toLong(), 1024)
    @Test fun validOriginalPassesWithoutMutation() {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        assertTrue(matches(file)); assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun missingTruncatedAndSameLengthCorruptOriginalFailWithoutRepair() {
        val file = File(temporary.root, "original")
        assertFalse(matches(file)); assertFalse(file.exists())
        file.writeBytes(bytes.copyOf(3)); assertFalse(matches(file)); assertEquals(3L, file.length())
        file.writeBytes(ByteArray(bytes.size)); assertFalse(matches(file)); assertArrayEquals(ByteArray(bytes.size), file.readBytes())
    }
    @Test fun symlinkAndDirectoryFailWithoutTouchingTarget() {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val link = File(temporary.root, "link"); Files.createSymbolicLink(link.toPath(), file.toPath())
        assertFalse(matches(link)); assertFalse(matches(temporary.root)); assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun invalidBoundsAndHashFail() {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        assertFalse(OriginalFileIntegrity.matches(file, hash, 0, 1024))
        assertFalse(OriginalFileIntegrity.matches(file, hash, bytes.size.toLong(), 1))
        assertFalse(OriginalFileIntegrity.matches(file, "invalid", bytes.size.toLong(), 1024))
    }
    @Test fun integrityFailureNeverBecomesCaptureRejectionOrDeletesSource() = runBlocking {
        val staging = CaptureStagingStore(temporary.newFolder())
        val controller = CaptureSessionController(CaptureSessionIdentity("project", 7, "session"), staging)
        controller.markReady()
        val attempt = requireNotNull(controller.beginCapture()).also { it.stagedFile.writeBytes(bytes) }
        val photo = (controller.onImageSaved(attempt, CaptureMetadataSnapshot(capturedAtEpochMillis = 1)) as SavedImageDecision.Deliver).photo
        val outcome = ImportPublicationResult.OriginalIntegrityFailure("existing", "Existing saved original needs repair; no automatic repair was performed.", 7).toCaptureCommitOutcome(7)
        assertTrue(outcome is CaptureCommitOutcome.Uncertain)
        finishCapturePublication(photo, outcome, controller, staging, { fail("Must not run guidance") }, { fail() })
        assertArrayEquals(bytes, photo.stagedFile.readBytes()); assertEquals(7L, controller.state.value.expectedRevision)
        assertEquals(0, controller.state.value.savedCount); controller.markReady(); assertNull(controller.beginCapture())
        assertFalse(controller.state.value.message.orEmpty().contains("reopen", true))
    }
}
