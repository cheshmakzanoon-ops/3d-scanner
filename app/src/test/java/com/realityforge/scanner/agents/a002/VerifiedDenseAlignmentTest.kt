package com.realityforge.scanner.agents.a002

import com.realityforge.scanner.agents.a009.ArtifactProvenanceFixtures as Fixtures
import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.engine.pipeline.VerifiedDenseAlignment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

/** Synthetic observed rays, real checkpoint validation/codec and production dense selection. */
class VerifiedDenseAlignmentTest {
    @Test fun freshAndDecodedReboundAlignmentSelectTheSameObservedTracks() {
        val plan = Fixtures.plan()
        val checkpoint = Fixtures.checkpoint(plan)
        val geometry = geometry(checkpoint)
        val fresh = VerifiedDenseAlignment.prepare(plan, geometry, checkpoint, 6)
        val next = Fixtures.plan(snapshot = Fixtures.snapshot(binding = Fixtures.binding(attempt = 3)))
        val decoded = AlignmentCheckpointCodec.decode(AlignmentCheckpointCodec.encode(checkpoint))
        val rebound = AlignmentCheckpoint(next.snapshot.binding, next.snapshot.recordHash,
            next[PipelineStage.ALIGNMENT], decoded.acceptance, decoded.cameras, decoded.verifiedMatchGraph, decoded.tracks)
        val resumed = VerifiedDenseAlignment.prepare(next, JSONObject(geometry.toString()), rebound, 6)
        assertEquals(fresh, resumed)
        assertEquals(setOf("a", "b"), fresh.selection.referencePhotoIds.toSet())
        assertEquals(24, fresh.support.size)
        assertTrue(fresh.support.values.all { it == setOf("a", "b") })
        assertEquals(.005, fresh.thresholdNormalized, 1e-12)
        assertEquals(24, fresh.selection.uniqueSparseTracks)
    }

    @Test fun noGlobalSourceIdsDoesNotInventAdditionalAnchorSupport() {
        val plan = Fixtures.plan(); val checkpoint = Fixtures.checkpoint(plan)
        val geometry = geometry(checkpoint).apply { remove("sparseSourceTrackIds") }
        val selected = VerifiedDenseAlignment.prepare(plan, geometry, checkpoint, 2)
        assertEquals(checkpoint.tracks.map { it.trackId.removePrefix("track-").toInt() }.toSet(), selected.support.keys)
        selected.support.forEach { (id, photos) ->
            val observed = checkpoint.tracks.single { it.trackId == "track-$id" }.observations
                .map { checkpoint.cameras[it.cameraIndex].photoId }.toSet()
            assertEquals(observed, photos)
        }
    }

    @Test fun changedGeometryWrongBindingAndDuplicateSourceIdsAreRejected() {
        val plan = Fixtures.plan(); val checkpoint = Fixtures.checkpoint(plan)
        val changed = geometry(checkpoint)
        changed.getJSONArray("cameras").getJSONObject(0).getJSONArray("translation").put(0, .1)
        assertThrows(IllegalArgumentException::class.java) { VerifiedDenseAlignment.prepare(plan, changed, checkpoint, 6) }
        val wrongPlan = Fixtures.plan(snapshot = Fixtures.snapshot(binding = Fixtures.binding(attempt = 9)))
        assertThrows(IllegalArgumentException::class.java) { VerifiedDenseAlignment.prepare(wrongPlan, geometry(checkpoint), checkpoint, 6) }
        val duplicated = geometry(checkpoint)
        duplicated.getJSONArray("sparseSourceTrackIds").put(1, duplicated.getJSONArray("sparseSourceTrackIds").getString(0))
        assertThrows(IllegalArgumentException::class.java) { VerifiedDenseAlignment.prepare(plan, duplicated, checkpoint, 6) }
    }

    @Test fun broadSparseAcceptanceDoesNotFabricateTightDenseSupport() {
        val plan = Fixtures.plan(); val original = Fixtures.checkpoint(plan)
        val tracks = original.tracks.map { track -> SparseTrack(track.trackId, track.x, track.y, track.z,
            track.red, track.green, track.blue, .02,
            track.observations.map { it.copy(xNormalized = it.xNormalized + .02) }) }
        val broad = AlignmentCheckpoint(original.binding, original.inputSnapshotRecordHash, original.identity,
            original.acceptance, original.cameras, original.verifiedMatchGraph, tracks)
        AlignmentCheckpointValidator.validateForReuse(broad, plan.snapshot.binding, plan.snapshot.recordHash,
            plan[PipelineStage.ALIGNMENT]).requireReusable()
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedDenseAlignment.prepare(plan, geometry(broad), broad, 6)
        }
    }

    @Test fun higherFocalCameraCannotBorrowTheLowerFocalPixelTolerance() {
        assertPerCameraPixelSupport(1200.0, 1200.0, .004, 0.0)
    }

    @Test fun anisotropicCameraUsesItsOwnVerticalPixelScale() {
        assertPerCameraPixelSupport(600.0, 1200.0, 0.0, .004)
    }

    private fun assertPerCameraPixelSupport(thirdFx: Double, thirdFy: Double, dx: Double, dy: Double) {
        val plan = Fixtures.plan(); val original = Fixtures.checkpoint(plan)
        val first = original.cameras.first()
        val cameras = original.cameras + AlignmentCamera("c", first.width, first.height,
            thirdFx, thirdFy, first.cx, first.cy, first.rotationWorldToCamera, listOf(-1.0, 0.0, 0.0))
        val tracks = original.tracks.map { track -> SparseTrack(track.trackId, track.x, track.y, track.z,
            track.red, track.green, track.blue, kotlin.math.hypot(dx, dy),
            cameras.mapIndexed { index, camera -> TrackObservation(index,
                track.trackId.removePrefix("track-").toInt(),
                (track.x + camera.translationWorldToCamera[0]) / track.z + dx, track.y / track.z + dy) }) }
        val checkpoint = AlignmentCheckpoint(original.binding, original.inputSnapshotRecordHash, original.identity,
            original.acceptance, cameras, listOf(VerifiedMatchEdge(0, 1, 24, 24, .004),
                VerifiedMatchEdge(0, 2, 24, 24, .004)), tracks)
        // All rays pass broad sparse validation. The third camera's 4.8px residual
        // must still be excluded from dense support while the 2.4px rays survive.
        AlignmentCheckpointValidator.validateForReuse(checkpoint, plan.snapshot.binding, plan.snapshot.recordHash,
            plan[PipelineStage.ALIGNMENT]).requireReusable()
        val prepared = VerifiedDenseAlignment.prepare(plan, geometry(checkpoint), checkpoint, 6)
        assertEquals(.005, prepared.thresholdNormalized, 1e-12)
        assertEquals(24, prepared.support.size)
        assertTrue(prepared.support.values.all { it == setOf("a", "b") })
        assertFalse(prepared.support.values.any { "c" in it })
        assertEquals(setOf("a", "b"), prepared.selection.referencePhotoIds.toSet())
        assertEquals("insufficient_verified_overlap_or_baseline", prepared.selection.exclusions["c"])
    }

    @Test fun profilesInvalidateDenseDescendantsButPreserveSparseReuse() {
        val input = com.realityforge.scanner.data.ProjectInputSnapshot("project", 1,
            listOf("a", "b").map { id -> com.realityforge.scanner.data.ProjectInputPhoto(
                id, Fixtures.hash(id).hex, 100L, 640, 480, 26.0, 0L, Fixtures.hash("meta-$id").hex) })
        val compatibility = com.realityforge.scanner.engine.pipeline.ProductionPipelineSnapshot.create(
            input, Fixtures.hash("optics").hex, 26.0,
            com.realityforge.scanner.processing.resources.ResourceWorkloadSelection.COMPACT)
        val refined = com.realityforge.scanner.engine.pipeline.ProductionPipelineSnapshot.create(
            input, Fixtures.hash("optics").hex, 26.0,
            com.realityforge.scanner.processing.resources.ResourceWorkloadSelection.COMPACT,
            com.realityforge.scanner.engine.pipeline.DensePreviewProfile.VerifiedRefinedCompact)
        val stages = com.realityforge.scanner.engine.pipeline.PipelineStage.entries
        for (stage in stages) {
            if (stage.ordinal <= com.realityforge.scanner.engine.pipeline.PipelineStage.SPARSE.ordinal)
                assertEquals(compatibility.identity(stage), refined.identity(stage))
            else assertNotEquals(compatibility.identity(stage), refined.identity(stage))
        }
        val old = compatibility.copy(settings = compatibility.settings.copy(
            dense = "native-reference-depth-v1|planes-56|resource=" +
                com.realityforge.scanner.processing.resources.ResourceWorkloadSelection.COMPACT.geometryIdentityText))
        assertNotEquals(old.identity(com.realityforge.scanner.engine.pipeline.PipelineStage.DENSE),
            compatibility.identity(com.realityforge.scanner.engine.pipeline.PipelineStage.DENSE))
    }

    @Test fun resourceViewCapAndCameraIndexMappingArePreserved() {
        val ids = (0 until 8).map { "camera-$it" }
        val plan = Fixtures.plan(snapshot = Fixtures.snapshot(photos = ids.map { Fixtures.photo(it) }))
        val original = Fixtures.checkpoint(plan)
        val cameras = ids.mapIndexed { index, id ->
            val camera = original.cameras.first()
            AlignmentCamera(id, camera.width, camera.height, camera.fx, camera.fy, camera.cx, camera.cy,
                camera.rotationWorldToCamera, listOf(-index * .5, 0.0, 0.0))
        }.reversed()
        val tracks = original.tracks.map { track -> SparseTrack(track.trackId, track.x, track.y, track.z,
            track.red, track.green, track.blue, 0.0, cameras.mapIndexed { index, camera ->
                TrackObservation(index, track.trackId.removePrefix("track-").toInt(),
                    (track.x + camera.translationWorldToCamera[0]) / track.z, track.y / track.z)
            }) }
        val checkpoint = AlignmentCheckpoint(original.binding, original.inputSnapshotRecordHash, original.identity,
            original.acceptance, cameras, (1 until cameras.size).map { VerifiedMatchEdge(0, it, 24, 24, 0.0) }, tracks)
        val prepared = VerifiedDenseAlignment.prepare(plan, geometry(checkpoint), checkpoint, 6)
        assertEquals(6, prepared.selection.referencePhotoIds.size)
        assertTrue(prepared.support.values.all { it == ids.toSet() })
        assertTrue(prepared.selection.sourcePhotoIds.all { (ref, sources) ->
            sources.isNotEmpty() && sources.size <= 3 && sources.all { it != ref && it in prepared.selection.referencePhotoIds }
        })
    }

    @Test fun cancellationPropagatesWithoutReturningASelection() {
        val plan = Fixtures.plan(); val checkpoint = Fixtures.checkpoint(plan)
        assertThrows(CancellationException::class.java) {
            VerifiedDenseAlignment.prepare(plan, geometry(checkpoint), checkpoint, 6) { throw CancellationException() }
        }
    }

    private fun geometry(checkpoint: AlignmentCheckpoint) = JSONObject()
        .put("pointCount", checkpoint.tracks.size).put("registeredCameras", checkpoint.cameras.size)
        .put("sparseSourceTrackIds", JSONArray(checkpoint.tracks.indices.map { Fixtures.hash("source-$it").hex }))
        .put("points", JSONArray(checkpoint.tracks.map { track -> JSONArray(listOf(track.x, track.y, track.z,
            track.red, track.green, track.blue, 0, 0, track.trackId.removePrefix("track-").toInt())) }))
        .put("cameras", JSONArray(checkpoint.cameras.map { camera -> JSONObject().put("photoId", camera.photoId)
            .put("rotation", JSONArray(camera.rotationWorldToCamera)).put("translation", JSONArray(camera.translationWorldToCamera)) }))
}
