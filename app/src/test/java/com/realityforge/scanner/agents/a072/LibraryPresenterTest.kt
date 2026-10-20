package com.realityforge.scanner.agents.a072
import com.realityforge.scanner.ui.library.*
import com.realityforge.scanner.data.*
import com.realityforge.scanner.data.library.*
import com.realityforge.scanner.data.library.LibraryQuery as ServiceQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Transactional in-memory test port; this is not a production database implementation. */
private class TestLibraryPort(val projects:MutableMap<String,ScanProject>,val photos:MutableMap<String,List<ScanPhoto>>):ProjectLibraryPort {
    val queries=mutableListOf<ServiceQuery>()
    private val reservations=mutableMapOf<String,ScanProject>()
    override suspend fun summaries(query:ServiceQuery):List<ProjectSummary> { queries+=query;return LibrarySelection.page(projects.values.map { ProjectSummary(it.id,it.name,it.createdAt,it.revision,it.state,photos[it.id].orEmpty().size,it.resultKey!=null && it.resultRevision==it.revision) },query) }
    override suspend fun rename(id:String,expectedRevision:Long,name:String):Boolean { val p=projects[id]?:return false;if(p.revision!=expectedRevision)return false;projects[id]=p.copy(name=name);return true }
    override suspend fun snapshot(id:String)=projects[id]?.let { it to photos[id].orEmpty() }
    override suspend fun reserveDuplicate(sourceId:String,sourceRevision:Long,newId:String,name:String):Boolean { if(projects[sourceId]?.revision!=sourceRevision)return false;reservations[newId]=ScanProject(newId,name,100);return true }
    override suspend fun publishDuplicate(newId:String,sourceId:String,sourceRevision:Long,photos:List<ScanPhoto>):Boolean { if(projects[sourceId]?.revision!=sourceRevision)return false;projects[newId]=reservations.remove(newId)?:return false;this.photos[newId]=photos;return true }
    override suspend fun abortDuplicate(newId:String) { reservations.remove(newId);projects.remove(newId);photos.remove(newId) }
}
class LibraryPresenterTest {
    @Test fun renameUsesActualServiceAndStaleRevisionCannotOptimisticallyChangeTitle()=runBlocking {
        val id=UUID.randomUUID().toString();val port=TestLibraryPort(mutableMapOf(id to ScanProject(id,"old",0,revision=4)),mutableMapOf())
        val dir=Files.createTempDirectory("library-presenter").toFile()
        try { val presenter=LibraryPresenter(ProjectLibraryService(port,dir)) { "failed" };presenter.refresh()
            assertFalse(presenter.mutate(LibraryAction.Rename(id,3,"wrong")));assertEquals("old",presenter.state.value.projects.single().name)
            assertTrue(presenter.mutate(LibraryAction.Rename(id,4,"new")));assertEquals("new",presenter.state.value.projects.single().name)
        } finally { dir.deleteRecursively() }
    }
    @Test fun queryPagesAreBoundedAndMergedWithStableIds()=runBlocking {
        val projects=(0..149).map {UUID.randomUUID().toString() to it}.associate { (id,n)->id to ScanProject(id,"Item$n",n.toLong()) }.toMutableMap()
        val dir=Files.createTempDirectory("library-presenter").toFile()
        try { val port=TestLibraryPort(projects,mutableMapOf());val presenter=LibraryPresenter(ProjectLibraryService(port,dir)) { "failed" }
            presenter.refresh();assertEquals(100,presenter.state.value.projects.size);presenter.loadMore();assertEquals(150,presenter.state.value.projects.size)
            assertEquals(listOf(0,100),port.queries.map { it.offset });assertTrue(port.queries.all { it.limit==100 })
        } finally { dir.deleteRecursively() }
    }
    @Test fun duplicateCopiesVerifiedIndependentOriginalsThroughRealService()=runBlocking {
        val id=UUID.randomUUID().toString();val dir=Files.createTempDirectory("library-duplicate").toFile();val data=byteArrayOf(1,2,3,4)
        val original=File(dir,"$id/originals/source.image");original.parentFile.mkdirs();original.writeBytes(data)
        val hash=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val photo=ScanPhoto("p",id,"source.image",hash,4,20,20,null,0.0,0.0,0.0,0)
        val port=TestLibraryPort(mutableMapOf(id to ScanProject(id,"source",0,revision=2)),mutableMapOf(id to listOf(photo)))
        try { val presenter=LibraryPresenter(ProjectLibraryService(port,dir)) { "failed" };presenter.refresh()
            assertTrue(presenter.mutate(LibraryAction.Duplicate(id,2,"copy")))
            val copyId=port.projects.keys.single { it!=id };val copy=File(dir,"$copyId/originals/${port.photos.getValue(copyId).single().filename}")
            assertArrayEquals(data,copy.readBytes());copy.writeBytes(byteArrayOf(9));assertArrayEquals(data,original.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
