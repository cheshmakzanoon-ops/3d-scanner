package com.realityforge.scanner.agents.a018

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.ScanDatabase
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.curation.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/file reopen regression. Execution is an instrumentation gate, not host evidence. */
@RunWith(AndroidJUnit4::class)
class RoomPhotoCurationDeviceTest {
    @Test fun curationReopensWithReasonsOrderAndUnchangedOriginalsAndCompletedPointer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "curation-${UUID.randomUUID()}.db"
        val originals = File(context.cacheDir, "curation-${UUID.randomUUID()}").apply { mkdirs() }
        var database: ScanDatabase? = null
        try {
            val files = (0..2).map { index -> File(originals, "original-$index.bin").apply {
                writeBytes(ByteArray(4096 + index) { ((it * 29 + index * 113) and 255).toByte() })
            } }
            val hashes = files.map(::hash)
            database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
            val db = requireNotNull(database)
            db.dao().insert(ScanProject("curation-project", "Curation", 1_000, revision = 3,
                resultRevision = 3, resultKey = "last-completed-result"))
            files.forEachIndexed { index, file -> db.dao().insert(ScanPhoto("photo-$index", "curation-project",
                file.name, hashes[index], file.length(), 32, 32, null, 1.0, 0.0, 0.0,
                1_000L + index, selectionOrder = index.toLong())) }
            val service = DefaultPhotoCurationService(RoomPhotoCurationStore(db))
            val initial = requireNotNull(service.state("curation-project"))
            val lease = (initial.selectedPhotoSet.admitForReconstruction() as ReconstructionAdmission.Ready).lease
            val excluded = service.execute(PhotoCurationCommand.Exclude("curation-project", initial.revision,
                "exclude", "photo-1", PhotoCurationReason(PhotoExclusionCode.BLUR, "Inspect saved image"))) as PhotoCurationOutcome.Applied
            val reordered = service.execute(PhotoCurationCommand.Reorder("curation-project", excluded.snapshot.revision,
                "reorder", listOf("photo-2", "photo-1", "photo-0"))) as PhotoCurationOutcome.Applied
            assertTrue(service.execute(PhotoCurationCommand.Include("curation-project", initial.revision,
                "stale", "photo-1")) is PhotoCurationOutcome.Rejected)
            db.close()
            database = Room.databaseBuilder(context, ScanDatabase::class.java, name).build()
            val reopenedDb = requireNotNull(database)
            val reopenedService = DefaultPhotoCurationService(RoomPhotoCurationStore(reopenedDb))
            val reopened = requireNotNull(reopenedService.state("curation-project"))
            assertEquals(reordered.snapshot.revision, reopened.revision)
            assertEquals(listOf("photo-2", "photo-0"), reopened.selectedPhotoSet.photos.map { it.id })
            assertEquals("Inspect saved image", reopened.photos[1].exclusion?.note)
            assertTrue(ReconstructionSelectionGuard.verifyPublication(lease, reopened.selectedPhotoSet) is PublicationSelectionCheck.Stale)
            assertEquals(hashes, files.map(::hash))
            assertEquals("last-completed-result", reopenedDb.dao().project("curation-project")?.resultKey)
            assertEquals(3L, reopenedDb.dao().project("curation-project")?.resultRevision)
            val undone = reopenedService.execute(PhotoCurationCommand.Undo("curation-project", reopened.revision,
                "undo", requireNotNull(reordered.undoToken))) as PhotoCurationOutcome.Applied
            assertEquals(listOf("photo-0", "photo-2"), undone.selectedPhotoSet.photos.map { it.id })
            assertEquals(hashes, files.map(::hash))
        } finally {
            database?.close()
            context.deleteDatabase(name)
            originals.deleteRecursively()
        }
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
