package com.realityforge.scanner.domain

import org.junit.Assert.*
import org.junit.Test

class PresentationReadFenceTest {
    private val original = PresentationReadIdentity("A", 4, "result-A", 4, "SPARSE_READY")
    private val fence = PresentationReadFence(7, original)

    @Test fun unchangedDuplicateOrRejectedImportCanRepublishCurrentResult() {
        assertTrue(fence.isCurrent(7, "A", original.copy(), false))
    }
    @Test fun restoredPickerGenerationIsNotConsumedByRepeatedPresentationChecks() {
        repeat(3) { assertTrue(fence.isCurrent(7, "A", original, false)) }
        assertEquals(7L, fence.selectionGeneration)
    }
    @Test fun oldProjectAndAtoBtoAReadCannotPublish() {
        assertFalse(fence.isCurrent(7, "B", original.copy(projectId = "B"), false))
        assertFalse(fence.isCurrent(9, "A", original, false))
    }
    @Test fun changedInputsCannotResurrectPreviousResult() {
        assertFalse(fence.isCurrent(7, "A", original.copy(revision = 5), false))
    }
    @Test fun sameRevisionReplacementAndChangedRecoveryStateRejectOldRead() {
        assertFalse(fence.isCurrent(7, "A", original.copy(resultKey = "new-result"), false))
        assertFalse(fence.isCurrent(7, "A", original.copy(resultRevision = null), false))
        assertFalse(fence.isCurrent(7, "A", original.copy(state = "RECOVERY_REQUIRED"), false))
    }
    @Test fun activeOperationAndMissingSelectionRejectRead() {
        assertFalse(fence.isCurrent(7, "A", original, true))
        assertFalse(fence.isCurrent(7, null, original, false))
        assertFalse(fence.isCurrent(7, "A", null, false))
    }
}
