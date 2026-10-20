package com.realityforge.scanner.agents.a001

import com.realityforge.scanner.application.OperationCoordinator
import com.realityforge.scanner.domain.BoundExternalRequest
import com.realityforge.scanner.domain.BoundExternalRequestCodec
import com.realityforge.scanner.domain.OperationKind
import com.realityforge.scanner.domain.ProjectRevision
import com.realityforge.scanner.domain.RequestId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationCoordinatorTest {
    private class IDs {
        private var next = 0
        fun take(): String = "id-${++next}"
    }

    @Test
    fun delayedCaptureKeepsOriginalProjectAndRevision() {
        val ids = IDs()
        val coordinator = OperationCoordinator(ids::take) { 1234L }
        val original = ProjectRevision("project-a", 7)
        val request = requireNotNull(coordinator.bindCapture(original, generation = 11))

        // UI selection changing to another project is deliberately not an input to claimPending.
        val claimed = requireNotNull(
            coordinator.claimPending(request.requestId, OperationKind.CAPTURE_PHOTO),
        )
        assertEquals(original, claimed.first.project)
        assertEquals(original, claimed.second.source)
        assertEquals(request.sessionId, claimed.second.sessionId)
        assertEquals(11, claimed.second.selectionGeneration)
        assertTrue(coordinator.finish(claimed.second.operationId))
    }

    @Test
    fun activityResultRequestIsConsumedExactlyOnce() {
        val ids = IDs()
        val coordinator = OperationCoordinator(ids::take) { 2222L }
        val request = requireNotNull(
            coordinator.bindImport(ProjectRevision("project-a", 3), generation = 2),
        )
        val first = coordinator.claimPending(request.requestId, OperationKind.IMPORT_PHOTOS)
        assertNotNull(first)
        assertNull(coordinator.claimPending(request.requestId, OperationKind.IMPORT_PHOTOS))
        assertTrue(coordinator.finish(requireNotNull(first).second.operationId))
        assertNull(coordinator.claimPending(request.requestId, OperationKind.IMPORT_PHOTOS))
    }

    @Test
    fun pendingRequestPreventsConflictingDirectOperationWithoutCancellingSelection() {
        val ids = IDs()
        val coordinator = OperationCoordinator(ids::take) { 3333L }
        val request = requireNotNull(
            coordinator.bindCalibration(ProjectRevision("project-a", 1), generation = 5),
        )
        assertNull(
            coordinator.reserveDirect(
                OperationKind.RECONSTRUCT,
                ProjectRevision("project-b", 9),
                selectionGeneration = 6,
            ),
        )
        assertNotNull(coordinator.cancelPending(request.requestId))
        assertNotNull(
            coordinator.reserveDirect(
                OperationKind.RECONSTRUCT,
                ProjectRevision("project-b", 9),
                selectionGeneration = 6,
            ),
        )
    }

    @Test
    fun staleFinishCannotReleaseAnotherOperation() {
        val ids = IDs()
        val coordinator = OperationCoordinator(ids::take) { 4444L }
        val first = requireNotNull(
            coordinator.reserveDirect(
                OperationKind.CREATE_PROJECT,
                source = null,
                selectionGeneration = 0,
            ),
        )
        assertFalse(coordinator.finish(com.realityforge.scanner.domain.OperationId("other")))
        assertEquals(first, coordinator.activeOperation())
        assertTrue(coordinator.finish(first.operationId))
        val second = requireNotNull(
            coordinator.reserveDirect(
                OperationKind.CREATE_PROJECT,
                source = null,
                selectionGeneration = 1,
            ),
        )
        assertNotEquals(first.operationId, second.operationId)
    }

    @Test
    fun pendingRequestsRoundTripWithArtifactProvenance() {
        val ids = IDs()
        val first = OperationCoordinator(ids::take) { 5555L }
        val request = requireNotNull(
            first.bindArtifact(
                project = ProjectRevision("project-a", 12),
                generation = 4,
                artifactKey = "sparse-m19-r12-0123456789abcdef",
                resultRevision = 12,
                extension = "textured.glb",
            ),
        )
        val encoded = BoundExternalRequestCodec.encode(request)
        val decoded = BoundExternalRequestCodec.decode(encoded)
        assertEquals(request, decoded)

        val second = OperationCoordinator(ids::take) { 6666L }
        second.restorePending(listOf(decoded))
        val restored = second.pendingRequests().single() as BoundExternalRequest.ArtifactExport
        assertEquals("sparse-m19-r12-0123456789abcdef", restored.artifactKey)
        assertEquals(12L, restored.resultRevision)
        assertEquals("textured.glb", restored.extension)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCodecVersionIsRejected() {
        val ids = IDs()
        val coordinator = OperationCoordinator(ids::take) { 7777L }
        val request = requireNotNull(
            coordinator.bindCaptureBundle(ProjectRevision("project-a", 1), generation = 1),
        )
        val encoded = BoundExternalRequestCodec.encode(request)
        val fields = encoded.split('.').toMutableList()
        fields[0] = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("99".toByteArray())
        BoundExternalRequestCodec.decode(fields.joinToString("."))
    }

    @Test
    fun wrongKindDoesNotConsumeRequest() {
        val ids = IDs()
        val coordinator = OperationCoordinator(ids::take) { 8888L }
        val request = requireNotNull(
            coordinator.bindImport(ProjectRevision("project-a", 1), generation = 1),
        )
        assertNull(coordinator.claimPending(request.requestId, OperationKind.CAPTURE_PHOTO))
        assertEquals(RequestId(request.requestId.value), coordinator.pendingRequests().single().requestId)
    }
}
