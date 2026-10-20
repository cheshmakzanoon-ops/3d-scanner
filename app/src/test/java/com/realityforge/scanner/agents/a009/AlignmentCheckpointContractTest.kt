package com.realityforge.scanner.agents.a009

import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.binding
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.checkpoint
import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures.plan
import com.realityforge.scanner.engine.artifacts.*
import org.junit.Assert.*
import org.junit.Test

class AlignmentCheckpointContractTest {
    @Test fun acceptedSparseAlignmentRoundTripsAndReproducesExactGeometry() {
        val plan = plan()
        val original = checkpoint(plan)
        val restored = AlignmentCheckpointCodec.decode(AlignmentCheckpointCodec.encode(original))
        assertEquals(original, restored)
        assertTrue(
            AlignmentCheckpointValidator.validateForReuse(
                restored, plan.snapshot.binding, plan.snapshot.recordHash,
                plan[PipelineStage.ALIGNMENT],
            ).reusable,
        )
        assertTrue(AlignmentCheckpointComparator.compare(original, restored).withinTolerance)
    }

    @Test fun validEnvelopeChecksumDoesNotBlessInvalidGeometryOrStaleOwnership() {
        val plan = plan()
        val invalidGeometry = AlignmentCheckpointCodec.decode(
            AlignmentCheckpointCodec.encode(checkpoint(plan, invalidRotation = true)),
        )
        assertTrue(
            AlignmentRejection.CAMERA_ROTATION in AlignmentCheckpointValidator.validateForReuse(
                invalidGeometry, plan.snapshot.binding, plan.snapshot.recordHash,
                plan[PipelineStage.ALIGNMENT],
            ).rejections,
        )
        assertTrue(
            AlignmentRejection.TRACK_REPROJECTION_ERROR in AlignmentCheckpointValidator.validateForReuse(
                checkpoint(plan, rms = .2), plan.snapshot.binding, plan.snapshot.recordHash,
                plan[PipelineStage.ALIGNMENT],
            ).rejections,
        )
        assertTrue(
            AlignmentRejection.BINDING_MISMATCH in AlignmentCheckpointValidator.validateForReuse(
                checkpoint(plan), binding(revision = 8), plan.snapshot.recordHash,
                plan[PipelineStage.ALIGNMENT],
            ).rejections,
        )
    }

    @Test fun recomputedChecksumCannotHideWrongProjectionOrPointsBehindCameras() {
        val plan = plan()
        val original = checkpoint(plan)
        fun altered(change: (SparseTrack) -> SparseTrack): AlignmentCheckpoint = AlignmentCheckpoint(
            original.binding, original.inputSnapshotRecordHash, original.identity, original.acceptance,
            original.cameras, original.verifiedMatchGraph, original.tracks.map(change))
        val wrongProjection = altered { track -> SparseTrack(track.trackId, track.x + 1.0, track.y, track.z,
            track.red, track.green, track.blue, 0.0, track.observations) }
        val behind = altered { track -> SparseTrack(track.trackId, track.x, track.y, -track.z,
            track.red, track.green, track.blue, 0.0, track.observations) }
        for ((candidate, expected) in listOf(wrongProjection to AlignmentRejection.TRACK_REPROJECTION_ERROR,
            behind to AlignmentRejection.TRACK_BEHIND_CAMERA)) {
            val bytesWithValidEnvelope = AlignmentCheckpointCodec.encode(candidate)
            val decoded = AlignmentCheckpointCodec.decode(bytesWithValidEnvelope)
            assertTrue(expected in AlignmentCheckpointValidator.validateForReuse(decoded, plan.snapshot.binding,
                plan.snapshot.recordHash, plan[PipelineStage.ALIGNMENT]).rejections)
        }
    }

    @Test fun everyTruncatedAlignmentCheckpointIsRejectedBeforeReuse() {
        val encoded = AlignmentCheckpointCodec.encode(checkpoint())
        for (cut in 0 until encoded.size) {
            assertThrows(ArtifactFormatException::class.java) {
                AlignmentCheckpointCodec.decode(encoded.copyOf(cut))
            }
        }
        val maliciousLength = encoded.copyOf().also {
            it[8] = 0x7f
            it[9] = 0xff.toByte()
            it[10] = 0xff.toByte()
            it[11] = 0xff.toByte()
        }
        assertThrows(ArtifactFormatException::class.java) {
            AlignmentCheckpointCodec.decode(maliciousLength)
        }
    }
}
