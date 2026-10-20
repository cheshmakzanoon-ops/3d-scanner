package com.realityforge.scanner.agents.a023

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.data.publication.ArtifactDeclaration
import com.realityforge.scanner.data.publication.ArtifactPublicationCoordinator
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PublicationRecoveryInstrumentationTest {
    @Test
    fun appPrivateFilesystemRecoversPromotedSetAndPreservesLastCompleted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "agent-023-publication-${UUID.randomUUID()}")
        try {
            val metadata = InstrumentedMetadata(PROJECT_ID, revision = 12)
            val baseline = publish(root, metadata, "baseline", "old")
            metadata.start("interrupted")
            val interrupted = ArtifactPublicationCoordinator(
                root,
                metadata,
                faultInjector = PublicationFaultInjector {
                    if (it == PublicationBoundary.DIRECTORY_PROMOTED) throw SimulatedProcessDeath(it)
                },
            )
            assertThrows(SimulatedProcessDeath::class.java) {
                interrupted.begin(PublicationPlan(identity(metadata, "interrupted"), DECLARATIONS)).apply {
                    stageBytes("preview", "android-preview".toByteArray())
                    stageBytes("model", "android-model".toByteArray())
                    publish()
                }
            }

            // Recreate the coordinator to exercise only persisted filesystem state.
            val restarted = ArtifactPublicationCoordinator(root, metadata)
            val report = restarted.recoverAll()
            val snapshot = requireNotNull(metadata.snapshot(PROJECT_ID))
            assertEquals(1, report.completed)
            assertNotEquals(baseline.pointer.setId, snapshot.currentResult?.setId)
            assertEquals(baseline.pointer.setId, snapshot.previousCompletedResult?.setId)
            assertTrue(restarted.current(PROJECT_ID) is PublicationReadResult.Verified)
            assertTrue(restarted.previous(PROJECT_ID) is PublicationReadResult.Verified)

            val repeated = ArtifactPublicationCoordinator(root, metadata).recoverAll()
            assertEquals(0, repeated.completed + repeated.aborted + repeated.stale)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun publish(
        root: File,
        metadata: InstrumentedMetadata,
        operation: String,
        marker: String,
    ) = run {
        metadata.start(operation)
        ArtifactPublicationCoordinator(root, metadata)
            .begin(PublicationPlan(identity(metadata, operation), DECLARATIONS))
            .apply {
                stageBytes("preview", "preview-$marker".toByteArray())
                stageBytes("model", "model-$marker".toByteArray())
            }
            .publish()
    }

    private fun identity(metadata: InstrumentedMetadata, operation: String): PublicationIdentity {
        val snapshot = requireNotNull(metadata.snapshot(PROJECT_ID))
        return PublicationIdentity(
            purpose = PublicationPurpose.RESULT_SET,
            projectId = PROJECT_ID,
            projectRevision = snapshot.projectRevision,
            operationId = operation,
            attempt = 1,
            inputIdentitySha256 = digest("input-$operation-${snapshot.projectRevision}"),
            settingsIdentitySha256 = digest("settings-$operation"),
            engineVersion = "agent-023-android-test-1",
        )
    }

    private class InstrumentedMetadata(
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

        override fun snapshot(projectId: String): ProjectPublicationSnapshot? = synchronized(this) {
            value.takeIf { it.projectId == projectId }
        }

        fun start(operationId: String) = synchronized(this) {
            value = value.copy(
                activeOperationId = operationId,
                activeAttempt = 1,
                activeOperationState = DurableOperationState.RUNNING,
                metadataVersion = value.metadataVersion + 1,
            )
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

            val oldCurrent = value.currentResult
            value = value.copy(
                currentResult = request.pointer,
                previousCompletedResult = oldCurrent ?: value.previousCompletedResult,
                activeOperationState = DurableOperationState.COMPLETED,
                metadataVersion = value.metadataVersion + 1,
            )
            PublicationCommitResult.Committed(
                committedPointer = request.pointer,
                currentResult = value.currentResult,
                previousCompletedResult = value.previousCompletedResult,
                metadataVersion = value.metadataVersion,
            )
        }
    }

    private companion object {
        const val PROJECT_ID = "android-project-23"
        val DECLARATIONS = listOf(
            ArtifactDeclaration("preview", "preview/result.json", "preview-json", true, 1024 * 1024),
            ArtifactDeclaration("model", "exports/result.glb", "sparse-glb", true, 1024 * 1024),
        )

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
