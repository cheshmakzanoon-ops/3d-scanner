package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PhotoCurationPersistenceTest {
    @Test fun everyReasonAndPendingRemovalSurvivesCanonicalRoundTrip() {
        val base = fixture().photos.first()
        val records = mutableListOf(base)
        PhotoExclusionCode.entries.forEach { reason -> records += base.copy(
            state = CuratedPhotoState.EXCLUDED, exclusion = PhotoCurationReason(reason, "پروژه · café\nsecond line")) }
        PhotoRemovalCode.entries.forEach { reason -> records += base.copy(
            state = CuratedPhotoState.REMOVAL_PENDING,
            pendingRemoval = PendingPhotoRemoval("request-$reason", 1234,
                PhotoRemovalReason(reason, "retain the reason"), RestorablePhotoState.EXCLUDED,
                PhotoCurationReason(PhotoExclusionCode.BLUR, "checked at full size"))) }
        records.forEach { photo ->
            val record = PhotoCurationRecordCodec.encode(photo)
            assertEquals(photo, PhotoCurationRecordCodec.decode(record))
            assertTrue(record.length <= PhotoCurationRecordCodec.MAX_RECORD_CHARS)
        }
    }

    @Test fun truncatedExtendedNoncanonicalAndOverBudgetRecordsAreRejected() {
        val record = PhotoCurationRecordCodec.encode(fixture().photos.first())
        val prefix = "rf-curation-v1:"
        val bytes = Base64.getUrlDecoder().decode(record.removePrefix(prefix))
        val invalid = listOf(
            "rf-curation-v2:" + record.removePrefix(prefix), record + "=", record.dropLast(3),
            prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes + byteArrayOf(0)),
            prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf().apply { this[0] = 127 }),
            "x".repeat(PhotoCurationRecordCodec.MAX_RECORD_CHARS + 1),
        )
        invalid.forEach { malformed -> assertThrows(Exception::class.java) { PhotoCurationRecordCodec.decode(malformed) } }
    }

    @Test fun legacyRowsBecomeDeterministicSelectionAndNewOriginalAppendsWithoutChangingOldRecords() {
        val original = fixture().photos.first()
        val rows = listOf(
            PersistedPhotoCuration("photo-3", "3".padStart(64, '0'), true, null, 3_000, null),
            PersistedPhotoCuration("photo-2", "2".padStart(64, '0'), false, 1, 2_000, null),
            PersistedPhotoCuration(original.id, original.sha256, true, 0, 1_000, PhotoCurationRecordCodec.encode(original)),
        )
        val snapshot = PhotoCurationPersistence.snapshot(PROJECT_ID, CurationRevision(7, 1), rows)
        assertEquals(listOf("photo-1", "photo-2", "photo-3"), snapshot.photos.map { it.id })
        assertEquals(PhotoExclusionCode.USER_CHOICE, snapshot.photos[1].exclusion?.code)
        assertEquals(original, snapshot.photos[0])
    }

    @Test fun corruptRecordAndMismatchedProjectionsNeverFallBackToIncluded() {
        val photo = fixture().photos.first()
        val row = PersistedPhotoCuration(photo.id, photo.sha256, true, 0, 1_000, PhotoCurationRecordCodec.encode(photo))
        listOf(row.copy(id = "another-original"), row.copy(sha256 = "f".repeat(64)),
            row.copy(included = false), row.copy(selectionOrder = 2), row.copy(record = "broken")).forEach { bad ->
            assertThrows(Exception::class.java) { PhotoCurationPersistence.snapshot(PROJECT_ID, CurationRevision(7, 0), listOf(bad)) }
        }
    }

    @Test fun persistenceRejectsForgedRevisionsChangeFlagsAndOriginalDeletion() {
        val current = fixture()
        val next = current.copy(revision = CurationRevision(8, 1), photos = current.photos.map {
            if (it.position == 0) it.copy(state = CuratedPhotoState.EXCLUDED,
                exclusion = PhotoCurationReason(PhotoExclusionCode.BLUR)) else it
        })
        val request = CurationCommitRequest("exclude", PROJECT_ID, current.revision, next,
            CurationChange(CurationChangeKind.RECONSTRUCTION_INPUTS, true, true))
        assertNull(PhotoCurationPersistence.validateCommit(current, request))
        assertNotNull(PhotoCurationPersistence.validateCommit(current,
            request.copy(nextSnapshot = next.copy(revision = CurationRevision(9, 1)))))
        assertNotNull(PhotoCurationPersistence.validateCommit(current,
            request.copy(change = CurationChange(CurationChangeKind.METADATA_ONLY, false, false))))
        val removed = next.copy(photos = next.photos.dropLast(1))
        assertEquals("ORIGINAL_REMOVAL_REQUIRES_VERIFIED_JOURNAL",
            PhotoCurationPersistence.validateCommit(current, request.copy(nextSnapshot = removed))?.code)
    }
}
