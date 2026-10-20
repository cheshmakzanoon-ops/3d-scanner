package com.realityforge.scanner.agents.a073
import com.realityforge.scanner.ui.photoreview.*
import com.realityforge.scanner.data.curation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit transactional in-memory fixture; no production database/file store is replaced. */
private class TestCurationStore(var snapshot:CurationSnapshot):PhotoCurationStore {
    override suspend fun read(projectId:String)=snapshot.takeIf { it.projectId==projectId }
    override suspend fun commit(request:CurationCommitRequest):CurationStoreCommitResult {
        if(snapshot.revision!=request.expectedRevision)return CurationStoreCommitResult.Conflict(snapshot)
        snapshot=request.nextSnapshot;return CurationStoreCommitResult.Committed(snapshot)
    }
}
class PhotoCurationPresenterTest {
    private val hashA="a".repeat(64);private val hashB="b".repeat(64)
    private fun metadata()=ProjectReviewUiState("project",3,listOf(
        ReviewPhoto("a","a.image",100,100,4,null,null,null,null,sha256=hashA),ReviewPhoto("b","b.image",100,100,4,null,null,null,null,sha256=hashB)),"Synthetic curation")
    private fun store()=TestCurationStore(CurationSnapshot("project",CurationRevision(3,0),listOf(CuratedPhoto("a",hashA,0),CuratedPhoto("b",hashB,1))))
    @Test fun committedSelectionAndUndoDriveVisibleCountsAndMonotonicRevisions()=runBlocking {
        val store=store();val presenter=PhotoCurationPresenter(DefaultPhotoCurationService(store),{"typed failure"},{"failure"},selectionAwareReconstruction=true)
        presenter.attach(metadata());assertTrue(presenter.execute(ReviewAction.Include("project",3,"a",false)))
        assertEquals(1,presenter.state.value.review!!.selectedCount);assertEquals(4,presenter.state.value.review!!.revision)
        assertFalse(presenter.execute(ReviewAction.Include("project",3,"b",false)))
        assertTrue(presenter.execute(ReviewAction.Command("project",4,ReviewCommand.UNDO)))
        assertEquals(2,presenter.state.value.review!!.selectedCount);assertEquals(5,presenter.state.value.review!!.revision)
        assertEquals(listOf(hashA,hashB),store.snapshot.photos.map { it.sha256 })
    }
    @Test fun noDeletionSinkMeansNoUnacknowledgedRemovalAndNoFakeReconstruction()=runBlocking {
        val store=store();val presenter=PhotoCurationPresenter(DefaultPhotoCurationService(store),{"typed failure"},{"failure"})
        presenter.attach(metadata());assertFalse(presenter.state.value.review!!.canRemove)
        assertFalse(presenter.execute(ReviewAction.Remove("project",3,"a")))
        assertFalse(presenter.state.value.review!!.canStart(26.0));assertEquals(2,store.snapshot.photos.size)
    }
    @Test fun mismatchedOriginalHashesFailClosedInsteadOfShowingAnotherPhoto()=runBlocking {
        val presenter=PhotoCurationPresenter(DefaultPhotoCurationService(store()),{"typed failure"},{"identity mismatch"})
        presenter.attach(metadata().copy(photos=metadata().photos.map {it.copy(sha256="c".repeat(64))}))
        assertNull(presenter.state.value.review);assertEquals("identity mismatch",presenter.state.value.failure)
    }
    @Test fun revisionConflictReloadsActualStoreWithoutRetryingCommand()=runBlocking {
        val store=store();val presenter=PhotoCurationPresenter(DefaultPhotoCurationService(store),{"conflict"},{"failure"})
        presenter.attach(metadata());store.snapshot=store.snapshot.copy(revision=CurationRevision(4,1))
        assertFalse(presenter.execute(ReviewAction.Include("project",3,"a",false)))
        assertEquals(4,presenter.state.value.review!!.revision);assertEquals(2,presenter.state.value.review!!.selectedCount)
    }
}
