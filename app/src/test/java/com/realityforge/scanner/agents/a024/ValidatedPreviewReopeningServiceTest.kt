package com.realityforge.scanner.agents.a024

import com.realityforge.scanner.data.ScanDao
import com.realityforge.scanner.data.ScanProject
import com.realityforge.scanner.data.operations.ProjectPathPolicy
import com.realityforge.scanner.data.publication.*
import com.realityforge.scanner.data.reopening.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID

/** Real publication files and strict reader; the DAO seam supplies controlled project snapshots. */
class ValidatedPreviewReopeningServiceTest {
    @Test fun changedInputsExposeLatestCompletedSetAsPreviousWithoutSkippingIt() = runBlocking {
        withFixture { fixture ->
            fixture.publish(2)
            val earlierSet = (fixture.gateway.current(fixture.id) as PublicationReadResult.Verified).value.manifest.setId
            fixture.publish(3)
            val latestSet = (fixture.gateway.current(fixture.id) as PublicationReadResult.Verified).value.manifest.setId
            val reader = fixture.reader()
            val current = reader.reopen(fixture.id) as ReopenResult.Ready
            assertEquals(3L, current.model.revision)
            assertEquals(latestSet, current.publicationSetId)
            val previous = reader.reopen(fixture.id, previous = true) as ReopenResult.Ready
            assertEquals(2L, previous.model.revision)
            assertEquals(earlierSet, previous.publicationSetId)
            fixture.row = fixture.row.copy(revision = 4)
            assertSame(ReopenResult.Missing, reader.reopen(fixture.id))
            assertNull(reader.currentPreview(fixture.id))
            val reopened = reader.reopen(fixture.id, previous = true) as ReopenResult.Ready
            assertEquals(latestSet, reopened.publicationSetId)
            val retained = reopened.model
            assertEquals(3L, retained.revision)
            assertEquals(fixture.key(3), retained.artifactKey)
            assertTrue(retained.previousResult)
            assertEquals(2L, (fixture.gateway.previous(fixture.id) as PublicationReadResult.Verified).value.pointer.projectRevision)
        }
    }

    @Test fun legacyLastCompletedPreviewRemainsExplicitlyReadOnlyAfterInputChange() = runBlocking {
        withFixture { fixture ->
            fixture.row = fixture.row.copy(revision = 4, resultRevision = 3, resultKey = fixture.key(3))
            val file = fixture.paths.artifact(fixture.id, fixture.key(3), "json")
            file.writeText(fixture.preview(3).toString())
            val before = file.readBytes()
            val reader = ValidatedPreviewReopeningService(fixture.dao, fixture.paths)
            assertSame(ReopenResult.Missing, reader.reopen(fixture.id))
            val reopened = reader.reopen(fixture.id, true) as ReopenResult.Ready
            assertNull(reopened.publicationSetId)
            val previous = reopened.model
            assertTrue(previous.previousResult)
            assertEquals(3L, previous.revision)
            assertEquals(DisplayPoint(3.0, 0.0, 1.0, 12, 128, 255), previous.sparsePoints.single())
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun sameRevisionRerunCannotRetargetThePreviouslyReopenedPublicationSet() = runBlocking {
        withFixture { fixture ->
            fixture.publish(3)
            val original = fixture.reader().reopen(fixture.id) as ReopenResult.Ready
            val originalSet = requireNotNull(original.publicationSetId)
            fixture.publish(3, operation = "reopen-3-next-attempt", pointX = 9.0)
            val newer = fixture.reader().reopen(fixture.id) as ReopenResult.Ready
            assertEquals(original.model.revision, newer.model.revision)
            assertEquals(original.model.artifactKey, newer.model.artifactKey)
            assertNotEquals(originalSet, newer.publicationSetId)
            assertEquals(3.0, original.model.sparsePoints.single().x, 0.0)
            assertEquals(9.0, newer.model.sparsePoints.single().x, 0.0)
            val pinned = fixture.gateway.committed(fixture.id, originalSet) as PublicationReadResult.Verified
            assertEquals(originalSet, pinned.value.manifest.setId)
            val pinnedJson = JSONObject(requireNotNull(pinned.value.artifact("preview")).readText())
            assertEquals(3.0, pinnedJson.getJSONArray("points").getJSONArray(0).getDouble(0), 0.0)
        }
    }

    @Test fun corruptLatestCompletedSetIsNotSilentlySubstitutedWithAnOlderResult() = runBlocking {
        withFixture { fixture ->
            fixture.publish(2)
            fixture.publish(3)
            fixture.row = fixture.row.copy(revision = 4)
            val current = (fixture.gateway.current(fixture.id) as PublicationReadResult.Verified).value
            requireNotNull(current.artifact("preview")).writeText("{\"schema\":1}")
            assertTrue(fixture.reader().reopen(fixture.id, true) is ReopenResult.Invalid)
            assertTrue(fixture.gateway.previous(fixture.id) is PublicationReadResult.Verified)
        }
    }

    @Test fun revisionChangeDuringReadCannotPublishAStaleDisplayModel() = runBlocking {
        withFixture { fixture ->
            fixture.publish(3)
            var calls = 0
            fixture.projectRead = {
                if (++calls == 1) fixture.row else fixture.row.copy(revision = 4)
            }
            assertSame(ReopenResult.Changed, fixture.reader().reopen(fixture.id))
            assertEquals(2, calls)
        }
    }

    @Test fun bytesChangedAfterPublicationVerificationFailTheReaderHashCheck() = runBlocking {
        withFixture { fixture ->
            fixture.publish(3)
            val changedAfterVerification = object : VerifiedArtifactSetRepository by fixture.gateway {
                override fun current(projectId: String): PublicationReadResult {
                    val before = fixture.gateway.current(projectId) as PublicationReadResult.Verified
                    val file = requireNotNull(before.value.artifact("preview"))
                    val changed = fixture.preview(3).apply { getJSONArray("points").getJSONArray(0).put(0, 9.0) }.toString()
                    assertEquals(file.length(), changed.toByteArray().size.toLong())
                    file.writeText(changed)
                    return before
                }
            }
            val result = ValidatedPreviewReopeningService(fixture.dao, fixture.paths, changedAfterVerification)
                .reopen(fixture.id)
            assertTrue(result.toString(), result is ReopenResult.Invalid)
            assertTrue((result as ReopenResult.Invalid).detail.contains("Preview bytes changed"))
        }
    }

    private class Fixture(directory: File) {
        val id = UUID.randomUUID().toString()
        val paths = ProjectPathPolicy(File(directory, "projects"))
        var row = ScanProject(id, "Typed reopening fixture", 1, revision = 0)
        var projectRead: (() -> ScanProject?)? = null
        val dao = Proxy.newProxyInstance(ScanDao::class.java.classLoader, arrayOf(ScanDao::class.java)) { _, method, args ->
            check(method.name == "project" && args?.firstOrNull() == id) { "Unexpected DAO call: ${method.name}" }
            projectRead?.invoke() ?: row
        } as ScanDao
        private val metadata = TestMetadata(id)
        val gateway = DefaultPublicationGateway(ArtifactPublicationCoordinator(File(paths.projectDirectory(id), "publication"), metadata))

        fun reader() = ValidatedPreviewReopeningService(dao, paths, gateway)
        fun key(revision: Long) = "sparse-m19-r$revision-0123456789abcdef"
        fun preview(revision: Long) = JSONObject().put("schema", 1).put("representation", "sparse_points")
            .put("scale", "arbitrary").put("revision", revision).put("artifactKey", key(revision))
            .put("registeredCameras", 2).put("pointCount", 1)
            .put("points", JSONArray().put(JSONArray(listOf(revision.toDouble(), 0.0, 1.0, 12, 128, 255))))

        fun publish(revision: Long, operation: String = "reopen-$revision", pointX: Double = revision.toDouble()) {
            metadata.start(revision, operation)
            val identity = PublicationIdentity(PublicationPurpose.RESULT_SET, id, revision, operation, 1,
                "0".repeat(64), "1".repeat(64), "reader-fixture-v1")
            gateway.beginResult(identity, listOf(ArtifactDeclaration("preview", "preview.json", "sparse_points", true, 4096))).apply {
                val encodedPreview = preview(revision).apply { getJSONArray("points").getJSONArray(0).put(0, pointX) }
                stageBytes("preview", encodedPreview.toString().toByteArray())
                publish()
            }
            row = row.copy(revision = revision, resultRevision = revision, resultKey = key(revision))
        }
    }

    private class TestMetadata(projectId: String) : PublicationMetadataPort {
        private var value = ProjectPublicationSnapshot(projectId, 0, false, null, null, null, null, null, 0)
        override fun snapshot(projectId: String) = value.takeIf { it.projectId == projectId }
        fun start(revision: Long, operation: String) {
            value = value.copy(projectRevision = revision, activeOperationId = operation, activeAttempt = 1,
                activeOperationState = DurableOperationState.RUNNING, metadataVersion = value.metadataVersion + 1)
        }
        override fun commit(request: PublicationCommitRequest): PublicationCommitResult {
            require(request.identity.projectId == value.projectId && request.identity.projectRevision == value.projectRevision)
            require(request.identity.operationId == value.activeOperationId && request.identity.attempt == value.activeAttempt)
            require(value.activeOperationState == DurableOperationState.RUNNING)
            require(request.expectedMetadataVersion == value.metadataVersion && request.expectedCurrentResultSetId == value.currentResult?.setId)
            value = value.copy(currentResult = request.pointer, previousCompletedResult = value.currentResult,
                activeOperationState = DurableOperationState.COMPLETED, metadataVersion = value.metadataVersion + 1)
            return PublicationCommitResult.Committed(request.pointer, value.currentResult, value.previousCompletedResult, value.metadataVersion)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("a024-reopening-").toFile()
        try { block(Fixture(directory)) } finally { check(directory.deleteRecursively()) }
    }
}
