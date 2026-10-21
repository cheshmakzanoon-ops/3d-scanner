package com.realityforge.scanner.agents.a018

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.curation.CuratedPhoto
import com.realityforge.scanner.data.curation.CurationCommitRequest
import com.realityforge.scanner.data.curation.CurationRevision
import com.realityforge.scanner.data.curation.CurationSnapshot
import com.realityforge.scanner.data.curation.CurationStoreCommitResult
import com.realityforge.scanner.data.curation.DefaultPhotoCurationService
import com.realityforge.scanner.data.curation.PhotoCurationCommand
import com.realityforge.scanner.data.curation.PhotoCurationOutcome
import com.realityforge.scanner.data.curation.PhotoCurationReason
import com.realityforge.scanner.data.curation.PhotoCurationStore
import com.realityforge.scanner.data.curation.PhotoExclusionCode
import com.realityforge.scanner.data.curation.PublicationSelectionCheck
import com.realityforge.scanner.data.curation.ReconstructionAdmission
import com.realityforge.scanner.data.curation.ReconstructionSelectionGuard
import com.realityforge.scanner.data.curation.admitForReconstruction
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-runtime contract only; Agent 073 owns the actual photo-review UI integration. */
@RunWith(AndroidJUnit4::class)
class PhotoCurationContractDeviceTest {
    @Test
    fun excludingAndReorderingRealPrivateFilesPreservesEveryOriginalByteAndStalesTheOldLease() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "agent-018-${System.nanoTime()}").apply { mkdirs() }
        try {
            val files = (1..3).map { number ->
                File(root, "photo-$number.bin").apply {
                    writeBytes(ByteArray(2_048 + number * 31) { index -> (number * 17 + index * 13).toByte() })
                }
            }
            val hashes = files.map { sha256(it) }
            val projectId = "device-project-018"
            val store = DeviceMemoryStore(
                CurationSnapshot(
                    projectId = projectId,
                    revision = CurationRevision(3, 0),
                    photos = files.mapIndexed { index, _ ->
                        CuratedPhoto("device-photo-${index + 1}", hashes[index], index)
                    },
                ),
            )
            val service = DefaultPhotoCurationService(
                store = store,
                clockMillis = { 1_800_000_000_000L },
                removalRequestId = { "device-removal" },
                undoTokenId = { "device-undo" },
            )
            val before = requireNotNull(service.selectedPhotos(projectId))
            val lease = (before.admitForReconstruction() as ReconstructionAdmission.Ready).lease
            val excluded = service.execute(
                PhotoCurationCommand.Exclude(
                    projectId,
                    before.revision,
                    "device-exclude",
                    "device-photo-2",
                    PhotoCurationReason(PhotoExclusionCode.BLUR),
                ),
            ) as PhotoCurationOutcome.Applied
            val reordered = service.execute(
                PhotoCurationCommand.Reorder(
                    projectId,
                    excluded.snapshot.revision,
                    "device-reorder",
                    listOf("device-photo-3", "device-photo-1", "device-photo-2"),
                ),
            ) as PhotoCurationOutcome.Applied

            assertEquals(listOf("device-photo-3", "device-photo-1"), reordered.selectedPhotoSet.photos.map { it.id })
            assertTrue(ReconstructionSelectionGuard.verifyPublication(lease, reordered.selectedPhotoSet) is PublicationSelectionCheck.Stale)
            assertEquals(hashes, files.map { sha256(it) })
            assertEquals(hashes, reordered.snapshot.photos.sortedBy { it.id }.map { it.sha256 })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private class DeviceMemoryStore(initial: CurationSnapshot) : PhotoCurationStore {
        private var snapshot = initial

        override suspend fun read(projectId: String): CurationSnapshot? = snapshot.takeIf { it.projectId == projectId }

        override suspend fun commit(request: CurationCommitRequest): CurationStoreCommitResult {
            if (snapshot.revision != request.expectedRevision) {
                return CurationStoreCommitResult.Conflict(snapshot)
            }
            snapshot = request.nextSnapshot
            return CurationStoreCommitResult.Committed(snapshot)
        }
    }
}
