package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProjectCreationServiceImpl(
    private val dao: ScanDao,
    private val paths: ProjectPathPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProjectCreationService {
    override suspend fun create(name: String): String = withContext(Dispatchers.IO) {
        val id = ProjectPathPolicy.newProjectId()
        dao.insert(ScanProject(id, name.trim().ifBlank { "Untitled scan" }.take(80), clock()))
        try {
            paths.projectDirectory(id)
        } catch (failure: Throwable) {
            try { dao.deleteProject(id) } catch (_: Throwable) { /* Preserve the directory failure. */ }
            throw failure
        }
        id
    }
}
