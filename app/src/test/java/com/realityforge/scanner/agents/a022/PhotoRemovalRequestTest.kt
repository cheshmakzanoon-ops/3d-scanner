package com.realityforge.scanner.agents.a022

import com.realityforge.scanner.application.PhotoRemovalRequest
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.data.curation.CuratedPhoto
import com.realityforge.scanner.data.curation.CuratedPhotoState
import com.realityforge.scanner.data.curation.PendingPhotoRemoval
import com.realityforge.scanner.data.curation.PhotoCurationRecordCodec
import com.realityforge.scanner.data.curation.PhotoRemovalCode
import com.realityforge.scanner.data.curation.PhotoRemovalReason
import com.realityforge.scanner.data.curation.RestorablePhotoState
import com.realityforge.scanner.domain.ProjectRevision
import org.junit.Assert.*
import org.junit.Test

class PhotoRemovalRequestTest {
    private val source = ProjectRevision("project", 7)
    private val photo = ScanPhoto("photo", "project", "photo.jpg", "a".repeat(64), 123,
        16, 16, null, 1.0, 0.0, 0.0, 100)

    @Test fun confirmationCannotFollowAChangedSelectionRevisionPhotoOrOriginal() {
        val bound = PhotoRemovalRequest.bind(source, photo)
        bound.requireSource(source)
        assertTrue(bound.matches(source, photo))
        assertFalse(bound.matches(source.copy(revision = 8), photo))
        assertFalse(bound.matches(source, photo.copy(id = "other")))
        assertFalse(bound.matches(source, photo.copy(sha256 = "b".repeat(64))))
        assertFalse(bound.matches(source, photo.copy(bytes = 124)))
        assertThrows(IllegalStateException::class.java) { bound.requireSource(ProjectRevision("other", 7)) }
        assertThrows(IllegalStateException::class.java) { bound.requireSource(source.copy(revision = 8)) }
        assertThrows(IllegalArgumentException::class.java) { PhotoRemovalRequest.bind(source, photo.copy(projectId = "other")) }
    }

    @Test fun pendingRemovalIdentitySurvivesRetryAndCannotBecomeAnotherRequest() {
        val pending = PendingPhotoRemoval("request", 100, PhotoRemovalReason(PhotoRemovalCode.USER_REQUEST),
            RestorablePhotoState.INCLUDED)
        val curated = CuratedPhoto(photo.id, photo.sha256, 0, CuratedPhotoState.REMOVAL_PENDING,
            pendingRemoval = pending)
        val row = photo.copy(included = false, selectionOrder = 0,
            curationRecord = PhotoCurationRecordCodec.encode(curated))
        val bound = PhotoRemovalRequest.bind(source, row)
        assertEquals("request", bound.pendingRemovalRequestId)
        assertEquals(bound, PhotoRemovalRequest.bind(source, row))
        val replacement = row.copy(curationRecord = PhotoCurationRecordCodec.encode(
            curated.copy(pendingRemoval = pending.copy(requestId = "new-request"))))
        assertFalse(bound.matches(source, replacement))
    }

    @Test fun malformedOrInconsistentCanonicalMetadataCannotOpenAConfirmation() {
        val curated = CuratedPhoto(photo.id, photo.sha256, 0)
        val row = photo.copy(selectionOrder = 0, curationRecord = PhotoCurationRecordCodec.encode(curated))
        assertTrue(PhotoRemovalRequest.bind(source, row).matches(source, row))
        listOf(row.copy(curationRecord = "broken"), row.copy(selectionOrder = 1), row.copy(included = false),
            row.copy(curationRecord = PhotoCurationRecordCodec.encode(curated.copy(sha256 = "b".repeat(64)))))
            .forEach { changed -> assertThrows(IllegalArgumentException::class.java) { PhotoRemovalRequest.bind(source, changed) } }
    }
}
