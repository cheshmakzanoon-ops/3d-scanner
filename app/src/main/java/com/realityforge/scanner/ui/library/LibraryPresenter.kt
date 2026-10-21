package com.realityforge.scanner.ui.library

import com.realityforge.scanner.data.library.ProjectLibraryService
import com.realityforge.scanner.data.library.LibraryOrder
import com.realityforge.scanner.data.library.LibraryQuery as ServiceQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

/** UI adapter over Agent019's real service. The application must supply its transactional port. */
class LibraryPresenter(private val service:ProjectLibraryService,private val failureMessage:(Throwable)->String) {
    private val mutable=MutableStateFlow(LibraryUiState(emptyList(),loading=true,canRename=true,canDuplicate=true))
    val state:StateFlow<LibraryUiState> = mutable.asStateFlow()
    private val mutation=Mutex()
    private val generation=AtomicLong()
    private var query=ServiceQuery()
    private var activeFilter=LibraryFilter.ALL
    suspend fun refresh(search:String="",sort:LibrarySort=LibrarySort.NEWEST,filter:LibraryFilter=LibraryFilter.ALL) {
        activeFilter=filter
        query=ServiceQuery(search=search.take(200),order=when(sort) { LibrarySort.NEWEST->LibraryOrder.NEWEST; LibrarySort.OLDEST->LibraryOrder.OLDEST; LibrarySort.NAME->LibraryOrder.NAME_ASC },states=when(filter) { LibraryFilter.NEEDS_ATTENTION->setOf("INTERRUPTED","FAILED","ERROR"); else->emptySet() },limit=100)
        load(query,filter)
    }
    suspend fun reload() { query=query.copy(offset=0);load(query) }
    private suspend fun load(request:ServiceQuery,filter:LibraryFilter=activeFilter) {
        val ticket=generation.incrementAndGet()
        mutable.update { it.copy(loading=true,error=null) }
        try {
            val summaries=service.query(request)
            val rows=summaries.filter { filter!=LibraryFilter.RESULTS || it.hasCurrentResult }.map {
                LibraryProject(it.id,it.name,it.createdAt,it.revision,it.state,"",it.hasCurrentResult,it.photoCount)
            }
            if(ticket==generation.get()) mutable.update { it.copy(projects=rows,loading=false,canLoadMore=summaries.size==request.limit) }
        } catch(cancelled:CancellationException) { if(ticket==generation.get()) mutable.update { it.copy(loading=false) };throw cancelled }
        catch(error:Exception) { if(ticket==generation.get()) mutable.update { it.copy(loading=false,error=failureMessage(error)) } }
    }
    /** Load another bounded page without discarding existing stable project identities. */
    suspend fun loadMore() {
        if(mutable.value.loading || !mutable.value.canLoadMore) return
        val existing=mutable.value.projects
        val next=query.copy(offset=query.offset+query.limit)
        val ticket=generation.incrementAndGet();mutable.update { it.copy(loading=true) }
        try {
            val summaries=service.query(next)
            val rows=summaries.filter { activeFilter!=LibraryFilter.RESULTS || it.hasCurrentResult }.map { LibraryProject(it.id,it.name,it.createdAt,it.revision,it.state,"",it.hasCurrentResult,it.photoCount) }
            if(ticket==generation.get()) { query=next;mutable.update { it.copy(projects=(existing+rows).distinctBy(LibraryProject::id),loading=false,canLoadMore=summaries.size==next.limit) } }
        } catch(cancelled:CancellationException) { if(ticket==generation.get()) mutable.update { it.copy(loading=false) };throw cancelled }
        catch(error:Exception) { if(ticket==generation.get()) mutable.update { it.copy(loading=false,error=failureMessage(error)) } }
    }
    suspend fun mutate(action:LibraryAction):Boolean {
        if(!mutation.tryLock()) return false
        mutable.update { it.copy(busy=true,error=null) }
        try {
            when(action) {
                is LibraryAction.Rename->check(service.rename(action.projectId,action.revision,action.name)) { "Project revision changed" }
                is LibraryAction.Duplicate->{
                    val source=mutable.value.projects.find { it.id==action.projectId && it.revision==action.revision }?:return false
                    service.duplicate(action.projectId,action.revision,action.name?:source.name) { progress -> mutable.update { it.copy(copyingBytes=progress.copiedBytes,totalCopyBytes=progress.totalBytes) } }
                }
                else->return false
            }
            query=query.copy(offset=0);load(query);return true
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) { mutable.update { it.copy(error=failureMessage(error)) };return false }
        finally { mutable.update { it.copy(busy=false,copyingBytes=null,totalCopyBytes=null) };mutation.unlock() }
    }
}
