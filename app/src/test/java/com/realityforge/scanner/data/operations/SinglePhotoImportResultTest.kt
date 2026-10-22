package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.importing.ImportPublicationResult
import org.junit.Assert.*
import org.junit.Test

class SinglePhotoImportResultTest {
    @Test fun onlyPublishedAndVerifiedDuplicateHaveBooleanResults() {
        assertTrue(ImportPublicationResult.Imported("photo", 8).toSinglePhotoImportResult())
        assertFalse(ImportPublicationResult.Duplicate("photo", 7).toSinglePhotoImportResult())
    }

    @Test fun rejectionPermissionLossAndUncertainPublicationRemainFailures() {
        val outcomes = listOf(
            ImportPublicationResult.Rejected("IMPORT_REJECTED", "Truncated photograph", 7),
            ImportPublicationResult.Rejected("SOURCE_PERMISSION_LOST", "Access revoked", 7),
            ImportPublicationResult.PendingRecovery("photo", "Journal owns the staged bytes", 7),
            ImportPublicationResult.OriginalIntegrityFailure("photo", "Keep the external source", 7),
        )
        for (outcome in outcomes) {
            try {
                outcome.toSinglePhotoImportResult()
                fail("Non-success was collapsed into a duplicate: $outcome")
            } catch (failure: PhotoImportFailureException) {
                assertSame(outcome, failure.outcome)
                assertTrue(requireNotNull(failure.message).isNotBlank())
            }
        }
    }
}
