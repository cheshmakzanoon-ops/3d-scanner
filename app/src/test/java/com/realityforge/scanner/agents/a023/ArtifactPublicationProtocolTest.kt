package com.realityforge.scanner.agents.a023

import com.realityforge.scanner.data.publication.ArtifactDeclaration
import com.realityforge.scanner.data.publication.ArtifactPublicationCoordinator
import com.realityforge.scanner.data.publication.DefaultPublicationGateway
import com.realityforge.scanner.data.publication.DurableOperationState
import com.realityforge.scanner.data.publication.ProjectPublicationSnapshot
import com.realityforge.scanner.data.publication.PublicationBoundary
import com.realityforge.scanner.data.publication.PublicationCommitRequest
import com.realityforge.scanner.data.publication.PublicationCommitResult
import com.realityforge.scanner.data.publication.PublicationFaultInjector
import com.realityforge.scanner.data.publication.PublicationIdentity
import com.realityforge.scanner.data.publication.PublicationMetadataPort
import com.realityforge.scanner.data.publication.PublicationPlan
import com.realityforge.scanner.data.publication.PublicationPointer
import com.realityforge.scanner.data.publication.PublicationPurpose
import com.realityforge.scanner.data.publication.PublicationReadResult
import com.realityforge.scanner.data.publication.PublicationRejection
import com.realityforge.scanner.data.publication.SimulatedProcessDeath
import com.realityforge.scanner.data.publication.VerifiedArtifactSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ArtifactPublicationProtocolTest {
    @Test
    fun everyCrashBoundaryRecoversWithoutReplacingCurrentWithPartialData() {
        for (boundary in PublicationBoundary.values()) {
            withRoot("crash") { root ->
                val metadata = FakeMetadata(PROJECT_ID, revision = 7)
                val baseline = publishResult(root, metadata, "baseline", "baseline")
                metadata.start("rerun")
                var crashed = false
                val coordinator = ArtifactPublicationCoordinator(
                    root,
                    metadata,
                    faultInjector = PublicationFaultInjector { reached ->
                        if (!crashed && reached == boundary) {
                            crashed = true
                            throw SimulatedProcessDeath(reached)
                        }
                    },
                )

                assertThrows(SimulatedProcessDeath::class.java) {
                    coordinator.begin(PublicationPlan(identity(metadata, "rerun"), RESULT_DECLARATIONS)).apply {
                        stageBytes("preview", "new-preview".toByteArray())
                        stageBytes("sparseGlb", "new-glb".toByteArray())
                        publish()
                    }
                }.also { assertEquals(boundary, it.boundary) }
                assertTrue("Crash boundary was not reached: $boundary", crashed)

                val report = ArtifactPublicationCoordinator(root, metadata).recoverAll()
                val snapshot = requireNotNull(metadata.snapshot(PROJECT_ID))
                if (boundary == PublicationBoundary.JOURNAL_OPENED || boundary == PublicationBoundary.ARTIFACT_SYNCED) {
                    assertEquals("Incomplete data became current at $boundary", baseline.pointer.setId, snapshot.currentResult?.setId)
                    assertTrue("Incomplete publication should abort", report.aborted >= 1)
                } else {
                    assertNotEquals("Sealed publication did not resume at $boundary", baseline.pointer.setId, snapshot.currentResult?.setId)
                    assertEquals("Last completed result was not preserved", baseline.pointer.setId, snapshot.previousCompletedResult?.setId)
                    assertTrue("Recoverable publication should complete", report.completed >= 1)
                    assertTrue(ArtifactPublicationCoordinator(root, metadata).current(PROJECT_ID) is PublicationReadResult.Verified)
                    assertTrue(ArtifactPublicationCoordinator(root, metadata).previous(PROJECT_ID) is PublicationReadResult.Verified)
                }

                val repeated = ArtifactPublicationCoordinator(root, metadata).recoverAll()
                assertEquals("Recovery must be idempotent", 0, repeated.completed + repeated.aborted + repeated.stale)
            }
        }
    }

    @Test
    fun changedRevisionAfterPromotionMakesSetStaleAndPreservesBaseline() = withRoot("stale") { root ->
        val metadata = FakeMetadata(PROJECT_ID, revision = 3)
        val baseline = publishResult(root, metadata, "baseline", "one")
        metadata.start("stale-run")
        val coordinator = ArtifactPublicationCoordinator(
            root,
            metadata,
            faultInjector = PublicationFaultInjector {
                if (it == PublicationBoundary.DIRECTORY_PROMOTED) throw SimulatedProcessDeath(it)
            },
        )
        assertThrows(SimulatedProcessDeath::class.java) {
            coordinator.begin(PublicationPlan(identity(metadata, "stale-run"), RESULT_DECLARATIONS)).apply {
                stageBytes("preview", "stale-preview".toByteArray())
                stageBytes("sparseGlb", "stale-glb".toByteArray())
                publish()
            }
        }

        metadata.changeRevision()
        val report = ArtifactPublicationCoordinator(root, metadata).recoverAll()
        assertTrue(report.stale >= 1)
        assertEquals(baseline.pointer.setId, metadata.snapshot(PROJECT_ID)?.currentResult?.setId)
        assertTrue(ArtifactPublicationCoordinator(root, metadata).current(PROJECT_ID) is PublicationReadResult.Verified)
    }

    @Test
    fun cancellationRemovesOnlyUncommittedSet() = withRoot("cancel") { root ->
        val metadata = FakeMetadata(PROJECT_ID, revision = 2)
        val baseline = publishResult(root, metadata, "baseline", "safe")
        metadata.start("cancelled")
        val session = ArtifactPublicationCoordinator(root, metadata)
            .begin(PublicationPlan(identity(metadata, "cancelled"), RESULT_DECLARATIONS))
        session.stageBytes("preview", "partial".toByteArray())
        session.cancel("User cancelled.")

        assertEquals(baseline.pointer.setId, metadata.snapshot(PROJECT_ID)?.currentResult?.setId)
        assertFalse(File(root, "staging/$PROJECT_ID/cancelled-a1").exists())
        assertTrue(session.state.name == "ABORTED")
    }

    @Test
    fun tamperedOrExtraMembersNeverCommit() = withRoot("tamper") { root ->
        val metadata = FakeMetadata(PROJECT_ID, revision = 5)
        val baseline = publishResult(root, metadata, "baseline", "safe")

        metadata.start("tamper")
        val tampered = ArtifactPublicationCoordinator(root, metadata)
            .begin(PublicationPlan(identity(metadata, "tamper"), RESULT_DECLARATIONS))
        tampered.stageBytes("preview", "valid".toByteArray())
        tampered.stageBytes("sparseGlb", "valid-glb".toByteArray())
        tampered.seal()
        File(root, "staging/$PROJECT_ID/tamper-a1/preview/result.json").writeText("tampered")
        assertThrows(Exception::class.java) { tampered.publish() }
        assertEquals(baseline.pointer.setId, metadata.snapshot(PROJECT_ID)?.currentResult?.setId)

        metadata.start("extra")
        val extra = ArtifactPublicationCoordinator(root, metadata)
            .begin(PublicationPlan(identity(metadata, "extra"), RESULT_DECLARATIONS))
        extra.stageBytes("preview", "valid".toByteArray())
        extra.stageBytes("sparseGlb", "valid-glb".toByteArray())
        extra.seal()
        File(root, "staging/$PROJECT_ID/extra-a1/untracked.bin").apply {
            parentFile.mkdirs()
            writeText("not in manifest")
        }
        assertThrows(Exception::class.java) { extra.publish() }
        assertEquals(baseline.pointer.setId, metadata.snapshot(PROJECT_ID)?.currentResult?.setId)
    }

    @Test
    fun committedArtifactCorruptionIsReportedRatherThanRendered() = withRoot("read-corrupt") { root ->
        val metadata = FakeMetadata(PROJECT_ID, revision = 9)
        val baseline = publishResult(root, metadata, "baseline", "old")
        val latest = publishResult(root, metadata, "latest", "new")
        requireNotNull(latest.artifact("preview")).writeText("post-commit-corruption")

        val coordinator = ArtifactPublicationCoordinator(root, metadata)
        assertTrue(coordinator.current(PROJECT_ID) is PublicationReadResult.Invalid)
        assertTrue(coordinator.previous(PROJECT_ID) is PublicationReadResult.Verified)
        assertEquals(baseline.pointer.setId, metadata.snapshot(PROJECT_ID)?.previousCompletedResult?.setId)
    }

    @Test
    fun originalImportPreservesExternalBytesAndLastCompletedResult() = withRoot("import") { root ->
        val metadata = FakeMetadata(PROJECT_ID, revision = 11)
        val baseline = publishResult(root, metadata, "baseline", "result")
        val original = File(root.parentFile, "a023-external-${System.nanoTime()}.jpg")
            .apply { writeBytes(ByteArray(8192) { (it % 251).toByte() }) }
        try {
            val before = sha256(original.readBytes())
            metadata.start("import")
            val gateway = DefaultPublicationGateway(ArtifactPublicationCoordinator(root, metadata))
            val session = gateway.beginOriginalImport(
                identity(metadata, "import", PublicationPurpose.ORIGINAL_IMPORT),
                listOf(
                    ArtifactDeclaration(
                        "photo-1",
                        "originals/photo-1.image",
                        DefaultPublicationGateway.REPRESENTATION_ORIGINAL_PHOTO,
                        required = true,
                        maxBytes = 1024 * 1024,
                    ),
                    ArtifactDeclaration(
                        "metadata",
                        "import/session.bin",
                        DefaultPublicationGateway.REPRESENTATION_IMPORT_METADATA,
                        required = true,
                        maxBytes = 1024,
                    ),
                ),
            )
            session.stageFile("photo-1", original)
            session.stageBytes("metadata", "bound-import-metadata".toByteArray())
            val imported = session.publish()

            assertEquals(before, sha256(original.readBytes()))
            assertEquals(before, sha256(requireNotNull(imported.artifact("photo-1")).readBytes()))
            val snapshot = requireNotNull(metadata.snapshot(PROJECT_ID))
            assertNull("Changed inputs cannot retain a stale current result", snapshot.currentResult)
            assertEquals(baseline.pointer.setId, snapshot.previousCompletedResult?.setId)
            assertTrue(gateway.previous(PROJECT_ID) is PublicationReadResult.Verified)
            assertTrue(gateway.committed(PROJECT_ID, imported.pointer.setId) is PublicationReadResult.Verified)
        } finally {
            original.delete()
        }
    }

    @Test
    fun transactionCommittedBeforeCallerFailureReconcilesWithoutDeletingSet() = withRoot("ambiguous") { root ->
        val metadata = FakeMetadata(PROJECT_ID, revision = 4)
        val baseline = publishResult(root, metadata, "baseline", "old")
        metadata.start("ambiguous")
        metadata.throwAfterCommit = true
        val coordinator = ArtifactPublicationCoordinator(root, metadata)
        val session = coordinator.begin(PublicationPlan(identity(metadata, "ambiguous"), RESULT_DECLARATIONS))
        session.stageBytes("preview", "new".toByteArray())
        session.stageBytes("sparseGlb", "new-glb".toByteArray())

        assertThrows(IllegalStateException::class.java) { session.publish() }
        val snapshot = requireNotNull(metadata.snapshot(PROJECT_ID))
        assertNotEquals(baseline.pointer.setId, snapshot.currentResult?.setId)
        assertEquals(baseline.pointer.setId, snapshot.previousCompletedResult?.setId)
        assertTrue(ArtifactPublicationCoordinator(root, metadata).current(PROJECT_ID) is PublicationReadResult.Verified)
        assertEquals(0, ArtifactPublicationCoordinator(root, metadata).recoverAll().completed)
    }

    @Test
    fun unavailableMetadataLookupRetainsPromotedSetForLaterRecovery() = withRoot("metadata-unavailable") { root ->
        val metadata = FakeMetadata(PROJECT_ID, revision = 6)
        val baseline = publishResult(root, metadata, "baseline", "old")
        metadata.start("waiting")
        val coordinator = ArtifactPublicationCoordinator(
            root,
            metadata,
            faultInjector = PublicationFaultInjector {
                if (it == PublicationBoundary.DIRECTORY_PROMOTED) throw SimulatedProcessDeath(it)
            },
        )
        assertThrows(SimulatedProcessDeath::class.java) {
            coordinator.begin(PublicationPlan(identity(metadata, "waiting"), RESULT_DECLARATIONS)).apply {
                stageBytes("preview", "waiting-preview".toByteArray())
                stageBytes("sparseGlb", "waiting-glb".toByteArray())
                publish()
            }
        }

        metadata.failCommittedLookup = true
        val deferred = ArtifactPublicationCoordinator(root, metadata).recoverAll()
        assertTrue(deferred.unchanged >= 1)
        assertEquals(baseline.pointer.setId, metadata.snapshot(PROJECT_ID)?.currentResult?.setId)
        assertTrue(File(root, "sets/$PROJECT_ID").walkTopDown().any { it.isFile })

        metadata.failCommittedLookup = false
        val resumed = ArtifactPublicationCoordinator(root, metadata).recoverAll()
        assertEquals(1, resumed.completed)
        assertEquals(baseline.pointer.setId, metadata.snapshot(PROJECT_ID)?.previousCompletedResult?.setId)
    }

    @Test
    fun corruptJournalIsQuarantinedAndNotInterpreted() = withRoot("corrupt-journal") { root ->
        val metadata = FakeMetadata(PROJECT_ID, revision = 1)
        val journal = File(root, "journals/$PROJECT_ID/bad-a1.rfpj")
        journal.parentFile.mkdirs()
        journal.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val report = ArtifactPublicationCoordinator(root, metadata).recoverAll()
        assertEquals(1, report.corruptJournals)
        assertFalse(journal.exists())
        assertTrue(File(root, "quarantine").walkTopDown().any { it.isFile && it.name.endsWith(".corrupt") })
    }

    @Test
    fun traversalDuplicatePathsAndSymlinkComponentsAreRejected() = withRoot("paths") { root ->
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactDeclaration("escape", "../outside", "preview-json", true, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublicationIdentity(
                PublicationPurpose.RESULT_SET,
                "../bad",
                0,
                "op",
                1,
                digest("a"),
                digest("b"),
                "v1",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublicationPlan(
                PublicationIdentity(PublicationPurpose.RESULT_SET, "p", 0, "op", 1, digest("a"), digest("b"), "v1"),
                listOf(
                    ArtifactDeclaration("a", "same", "x", true, 10),
                    ArtifactDeclaration("b", "same", "y", true, 10),
                ),
            )
        }

        val metadata = FakeMetadata(PROJECT_ID, revision = 1).apply { start("symlink") }
        val outside = Files.createTempDirectory("rf-a023-outside-")
        try {
            val projectStage = File(root, "staging/$PROJECT_ID")
            projectStage.parentFile.mkdirs()
            try {
                Files.createSymbolicLink(projectStage.toPath(), outside)
            } catch (unsupported: Exception) {
                assumeNoException("Symbolic links unavailable on this host", unsupported)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ArtifactPublicationCoordinator(root, metadata)
                    .begin(PublicationPlan(identity(metadata, "symlink"), RESULT_DECLARATIONS))
            }
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    private fun publishResult(
        root: File,
        metadata: FakeMetadata,
        operation: String,
        marker: String,
    ): VerifiedArtifactSet {
        metadata.start(operation)
        val session = ArtifactPublicationCoordinator(root, metadata)
            .begin(PublicationPlan(identity(metadata, operation), RESULT_DECLARATIONS))
        session.stageBytes("preview", "{\"marker\":\"$marker\"}".toByteArray())
        session.stageBytes("sparseGlb", "glb-$marker".toByteArray())
        return session.publish()
    }

    private fun identity(
        metadata: FakeMetadata,
        operation: String,
        purpose: PublicationPurpose = PublicationPurpose.RESULT_SET,
    ): PublicationIdentity {
        val snapshot = requireNotNull(metadata.snapshot(PROJECT_ID))
        return PublicationIdentity(
            purpose = purpose,
            projectId = PROJECT_ID,
            projectRevision = snapshot.projectRevision,
            operationId = operation,
            attempt = 1,
            inputIdentitySha256 = digest("inputs-$operation-${snapshot.projectRevision}"),
            settingsIdentitySha256 = digest("settings-$operation"),
            engineVersion = "agent-023-host-test-1",
        )
    }

    private inline fun withRoot(prefix: String, block: (File) -> Unit) {
        val root = Files.createTempDirectory("rf-a023-$prefix-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeMetadata(
        projectId: String,
        revision: Long,
    ) : PublicationMetadataPort {
        private var value = ProjectPublicationSnapshot(
            projectId = projectId,
            projectRevision = revision,
            deleted = false,
            activeOperationId = null,
            activeAttempt = null,
            activeOperationState = null,
            currentResult = null,
            previousCompletedResult = null,
            metadataVersion = 0,
        )
        private val imports = LinkedHashMap<String, PublicationPointer>()
        var throwAfterCommit: Boolean = false
        var failCommittedLookup: Boolean = false

        override fun snapshot(projectId: String): ProjectPublicationSnapshot? = synchronized(this) {
            value.takeIf { it.projectId == projectId }
        }

        fun start(operationId: String, attempt: Long = 1): ProjectPublicationSnapshot = synchronized(this) {
            value = value.copy(
                activeOperationId = operationId,
                activeAttempt = attempt,
                activeOperationState = DurableOperationState.RUNNING,
                metadataVersion = value.metadataVersion + 1,
            )
            value
        }

        fun changeRevision(): ProjectPublicationSnapshot = synchronized(this) {
            value = value.copy(
                projectRevision = value.projectRevision + 1,
                metadataVersion = value.metadataVersion + 1,
            )
            value
        }

        override fun committedPointer(projectId: String, setId: String): PublicationPointer? = synchronized(this) {
            if (failCommittedLookup) throw IllegalStateException("Injected metadata lookup outage")
            imports[setId] ?: super.committedPointer(projectId, setId)
        }

        override fun commit(request: PublicationCommitRequest): PublicationCommitResult = synchronized(this) {
            committedPointer(request.identity.projectId, request.pointer.setId)?.let {
                return PublicationCommitResult.AlreadyCommitted(it, value.metadataVersion)
            }
            val rejection = when {
                value.projectId != request.identity.projectId -> PublicationRejection.PROJECT_MISSING
                value.deleted -> PublicationRejection.PROJECT_DELETED
                value.projectRevision != request.identity.projectRevision -> PublicationRejection.REVISION_CHANGED
                value.activeOperationId != request.identity.operationId -> PublicationRejection.OPERATION_CHANGED
                value.activeAttempt != request.identity.attempt -> PublicationRejection.ATTEMPT_CHANGED
                value.activeOperationState != DurableOperationState.RUNNING -> PublicationRejection.OPERATION_NOT_RUNNING
                value.currentResult?.setId != request.expectedCurrentResultSetId -> PublicationRejection.CURRENT_RESULT_CHANGED
                value.metadataVersion != request.expectedMetadataVersion -> PublicationRejection.METADATA_VERSION_CHANGED
                else -> null
            }
            if (rejection != null) return PublicationCommitResult.Rejected(rejection)

            when (request.identity.purpose) {
                PublicationPurpose.RESULT_SET -> {
                    val oldCurrent = value.currentResult
                    value = value.copy(
                        currentResult = request.pointer,
                        previousCompletedResult = oldCurrent ?: value.previousCompletedResult,
                        activeOperationState = DurableOperationState.COMPLETED,
                        metadataVersion = value.metadataVersion + 1,
                    )
                }
                PublicationPurpose.ORIGINAL_IMPORT -> {
                    imports[request.pointer.setId] = request.pointer
                    value = value.copy(
                        projectRevision = value.projectRevision + 1,
                        currentResult = null,
                        previousCompletedResult = value.currentResult ?: value.previousCompletedResult,
                        activeOperationState = DurableOperationState.COMPLETED,
                        metadataVersion = value.metadataVersion + 1,
                    )
                }
            }
            val committed = PublicationCommitResult.Committed(
                committedPointer = request.pointer,
                currentResult = value.currentResult,
                previousCompletedResult = value.previousCompletedResult,
                metadataVersion = value.metadataVersion,
            )
            if (throwAfterCommit) {
                throwAfterCommit = false
                throw IllegalStateException("Injected caller failure after durable metadata transaction")
            }
            return committed
        }
    }

    private companion object {
        const val PROJECT_ID = "project-1"
        val RESULT_DECLARATIONS = listOf(
            ArtifactDeclaration("preview", "preview/result.json", "preview-json", true, 1024 * 1024),
            ArtifactDeclaration("sparseGlb", "exports/result.glb", "sparse-glb", true, 1024 * 1024),
            ArtifactDeclaration("texture", "textures/atlas.png", "texture-png", false, 1024 * 1024),
        )

        fun digest(text: String): String = sha256(text.toByteArray())

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
