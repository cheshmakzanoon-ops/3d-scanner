package com.realityforge.scanner.agents.a073
import com.realityforge.scanner.ui.photoreview.*
import org.junit.Assert.*
import org.junit.Test
class ReviewAdmissionTest {
    private val photos=listOf(ReviewPhoto("a","a.jpg",20,30,10,null,null,null,null),ReviewPhoto("b","b.jpg",20,30,10,null,null,null,null))
    private val state=ProjectReviewUiState("p",3,photos,"Scan",canCurate=true)
    @Test fun staleCurationCannotMutateAnotherRevisionOrProject() {
        assertFalse(ReviewAdmission.allowed(state,ReviewAction.Include("p",2,"a",false)))
        assertFalse(ReviewAdmission.allowed(state,ReviewAction.Remove("other",3,"a")))
        assertFalse(ReviewAdmission.allowed(state,ReviewAction.Move("p",3,"missing",1)))
        assertFalse(ReviewAdmission.allowed(state,ReviewAction.Move("p",3,"a",-1)))
        assertTrue(ReviewAdmission.allowed(state,ReviewAction.Move("p",3,"a",1)))
    }
    @Test fun processingUsesActualPersistedSelectionAndFiniteFocal() {
        assertTrue(state.canStart(26.0)); assertFalse(state.canStart(Double.NaN))
        assertFalse(state.copy(photos=photos.map { it.copy(included=false) }).canStart(26.0))
        assertFalse(state.copy(busy=true).canStart(26.0))
    }
    @Test fun unsupportedCountAndKnownOpticalMismatchCannotStart() {
        assertFalse(state.copy(photos=(0..12).map { photos.first().copy(id=it.toString()) }).canStart(26.0))
        assertFalse(state.copy(photos=photos.map { it.copy(calibrationCompatible=false) }).canStart(26.0))
    }
    @Test fun absentCurationBackendOffersNoMutation() {
        assertFalse(ReviewAdmission.allowed(state.copy(canCurate=false),ReviewAction.Include("p",3,"a",false)))
        assertEquals(2,state.selectedCount)
    }
}
