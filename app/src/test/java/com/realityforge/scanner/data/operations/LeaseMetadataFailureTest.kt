package com.realityforge.scanner.data.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.UUID

class LeaseMetadataFailureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedSharedMetadataWriteDoesNotLeavePhantomReader() {
        val paths = ProjectPathPolicy(temporary.newFolder("projects"))
        var storageFull = false
        FileProjectLeaseStore(paths, beforeMetadataWrite = {
            if (storageFull) throw IOException("Injected storage exhaustion")
        }).use { store ->
            val project = UUID.randomUUID().toString()
            val first = store.acquire(request(project, ProjectOperationKind.READ_INPUTS))
            storageFull = true
            val failure = runCatching {
                store.acquire(request(project, ProjectOperationKind.EXPORT_CAPTURE))
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("Only the successfully acquired reader remains", 1, store.activeCount(project))
            // Cleanup must work while the storage failure is still present.
            first.close()
            assertEquals(0, store.activeCount(project))
            storageFull = false
            store.acquire(request(project, ProjectOperationKind.MUTATE_INPUTS)).use {
                assertEquals(1, store.activeCount(project))
            }
            assertEquals(0, store.activeCount(project))
        }
    }

    private fun request(project: String, kind: ProjectOperationKind) =
        ProjectOperationRequest.create(project, kind, 0)
}
