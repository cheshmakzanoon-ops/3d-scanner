package com.realityforge.scanner.data.operations

import com.realityforge.scanner.data.ScanDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class ProjectDeletionServiceImpl(
    private val dao: ScanDao,
    private val paths: ProjectPathPolicy,
) : ProjectDeletionService {
    override suspend fun delete(projectId: String) = withContext(Dispatchers.IO) {
        val directory = paths.projectDirectory(projectId, create = false)
        dao.deleteProject(projectId)
        if (!directory.exists()) return@withContext
        try {
            Files.walkFileTree(
                directory.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (failure: Exception) {
            throw ProjectAccessException(
                "Project record was removed, but some private files remain for recovery cleanup.",
                failure,
            )
        }
    }
}
