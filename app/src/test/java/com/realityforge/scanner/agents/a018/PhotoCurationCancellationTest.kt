package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

class PhotoCurationCancellationTest {
    @Test fun coroutineCancellationIsNeverConvertedToStoreFailure() {
        val readStore = object : PhotoCurationStore {
            override suspend fun read(projectId: String): CurationSnapshot? = throw CancellationException("stop")
            override suspend fun commit(request: CurationCommitRequest): CurationStoreCommitResult = error("unreachable")
        }
        assertCancelled { service(readStore).execute(PhotoCurationCommand.Include(PROJECT_ID, CurationRevision(7, 0), "read", "photo-1")) }
        val commitStore = object : PhotoCurationStore {
            override suspend fun read(projectId: String) = fixture()
            override suspend fun commit(request: CurationCommitRequest): CurationStoreCommitResult = throw CancellationException("stop")
        }
        assertCancelled { service(commitStore).execute(PhotoCurationCommand.Exclude(PROJECT_ID, CurationRevision(7, 0), "commit", "photo-1", PhotoCurationReason(PhotoExclusionCode.BLUR))) }
    }

    private fun assertCancelled(block: suspend () -> Unit) {
        try { runBlocking { block() }; fail("Cancellation was swallowed") } catch (_: CancellationException) { }
    }
}
