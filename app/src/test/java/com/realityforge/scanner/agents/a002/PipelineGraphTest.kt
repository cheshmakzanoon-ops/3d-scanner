package com.realityforge.scanner.agents.a002

import com.realityforge.scanner.engine.pipeline.*
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PipelineGraphTest {
    private class MemoryCheckpointStore : PipelineCheckpointStore {
        private val latest = linkedMapOf<Pair<String, PipelineStage>, PipelineCheckpoint>()
        val commits = mutableListOf<PipelineCheckpoint>()

        override suspend fun latest(projectId: String, stage: PipelineStage): PipelineCheckpoint? =
            latest[projectId to stage]

        override suspend fun commit(checkpoint: PipelineCheckpoint) {
            commits += checkpoint
            latest[checkpoint.identity.projectId to checkpoint.identity.stage] = checkpoint
        }
    }

    private class CountingBackend(
        private val execution: (PipelineStageRequest) -> PipelineStageExecution = { request ->
            PipelineStageExecution.Completed(artifact(request))
        },
    ) {
        val counts = PipelineStage.entries.associateWith { 0 }.toMutableMap()

        val runners: Map<PipelineStage, PipelineStageRunner> = PipelineStage.entries.associateWith { stage ->
            PipelineStageRunner { request ->
                assertEquals(stage, request.identity.stage)
                counts[stage] = counts.getValue(stage) + 1
                execution(request)
            }
        }
    }

    @Test fun unchangedCompletedStagesAreReusedExactly() = runBlocking {
        val store = MemoryCheckpointStore()
        val backend = CountingBackend()
        val graph = PipelineGraph(store, backend.runners)
        val input = snapshot()

        val first = graph.run(input)
        assertEquals(PipelineTerminalState.COMPLETED, first.state)
        assertEquals(PipelineStage.TEXTURE, first.lastCompletedStage)
        assertEquals(PipelineStage.entries.size, store.commits.size)
        assertTrue(first.trace.all { it.action == PipelineTraceAction.EXECUTED })

        val second = graph.run(input)
        assertEquals(PipelineTerminalState.COMPLETED, second.state)
        assertTrue(second.trace.all { it.action == PipelineTraceAction.REUSED })
        PipelineStage.entries.forEach { assertEquals("runner repeated for $it", 1, backend.counts[it]) }
        assertEquals(PipelineStage.entries.size, store.commits.size)
    }

    @Test fun appearanceChangeRecomputesOnlyTextureAndExportChangeRecomputesNothing() = runBlocking {
        val store = MemoryCheckpointStore()
        val backend = CountingBackend()
        val graph = PipelineGraph(store, backend.runners)
        val base = snapshot()
        graph.run(base)

        val exportOnly = base.copy(settings = base.settings.copy(export = "obj-and-glb-v2"))
        val exportResult = graph.run(exportOnly)
        assertTrue(exportResult.trace.all { it.action == PipelineTraceAction.REUSED })
        PipelineStage.entries.forEach { assertEquals(1, backend.counts[it]) }

        val appearance = base.copy(settings = base.settings.copy(texture = "atlas-v2"))
        val appearanceResult = graph.run(appearance)
        for (record in appearanceResult.trace) {
            if (record.stage == PipelineStage.TEXTURE) {
                assertEquals(PipelineTraceAction.RECOMPUTED, record.action)
            } else {
                assertEquals("geometry changed for ${record.stage}", PipelineTraceAction.REUSED, record.action)
            }
        }
        PipelineStage.entries.forEach { stage ->
            assertEquals(if (stage == PipelineStage.TEXTURE) 2 else 1, backend.counts[stage])
        }
    }

    @Test fun denseSettingChangeRecomputesOnlyDenseAndItsDescendants() = runBlocking {
        val store = MemoryCheckpointStore()
        val backend = CountingBackend()
        val graph = PipelineGraph(store, backend.runners)
        val base = snapshot()
        graph.run(base)

        val changed = base.copy(settings = base.settings.copy(dense = "depth-v2"))
        val result = graph.run(changed)
        assertTrace(result, PipelineStage.FEATURES, PipelineTraceAction.REUSED)
        assertTrace(result, PipelineStage.MATCHING, PipelineTraceAction.REUSED)
        assertTrace(result, PipelineStage.SPARSE, PipelineTraceAction.REUSED)
        assertTrace(result, PipelineStage.DENSE, PipelineTraceAction.RECOMPUTED)
        assertTrace(result, PipelineStage.SURFACE, PipelineTraceAction.RECOMPUTED)
        assertTrace(result, PipelineStage.TEXTURE, PipelineTraceAction.RECOMPUTED)
    }

    @Test fun changedPhotoInvalidatesEveryStage() = runBlocking {
        val store = MemoryCheckpointStore()
        val backend = CountingBackend()
        val graph = PipelineGraph(store, backend.runners)
        val base = snapshot()
        graph.run(base)

        val changedPhotos = base.photos.toMutableList().also { photos ->
            photos[0] = photos[0].copy(contentSha256 = "c".repeat(64))
        }
        val result = graph.run(base.copy(photos = changedPhotos))
        assertTrue(result.trace.all { it.action == PipelineTraceAction.RECOMPUTED })
        PipelineStage.entries.forEach { assertEquals(2, backend.counts[it]) }
    }

    @Test fun changedCalibrationKeepsFeaturesButInvalidatesGeometryDescendants() = runBlocking {
        val store = MemoryCheckpointStore()
        val backend = CountingBackend()
        val graph = PipelineGraph(store, backend.runners)
        val base = snapshot()
        graph.run(base)

        val changed = base.copy(calibrationIdentity = sha256Utf8("calibration-v2"))
        val result = graph.run(changed)
        assertTrace(result, PipelineStage.FEATURES, PipelineTraceAction.REUSED)
        PipelineStage.entries.drop(1).forEach { assertTrace(result, it, PipelineTraceAction.RECOMPUTED) }
        assertEquals(1, backend.counts[PipelineStage.FEATURES])
        PipelineStage.entries.drop(1).forEach { assertEquals(2, backend.counts[it]) }
    }

    @Test fun changedEngineVersionInvalidatesEveryStage() = runBlocking {
        val store = MemoryCheckpointStore()
        val backend = CountingBackend()
        val graph = PipelineGraph(store, backend.runners)
        val base = snapshot()
        graph.run(base)

        val result = graph.run(base.copy(engineVersion = "engine-v2"))
        assertTrue(result.trace.all { it.action == PipelineTraceAction.RECOMPUTED })
    }

    @Test fun recoverableTextureFailureKeepsMeshAndNeverClaimsTexturedSuccess() = runBlocking {
        val store = MemoryCheckpointStore()
        val backend = CountingBackend { request ->
            if (request.identity.stage == PipelineStage.TEXTURE) {
                PipelineStageExecution.Unavailable(
                    PipelineFailure(
                        PipelineStage.TEXTURE,
                        "TEXTURE_VISIBILITY_REJECTED",
                        "No source photograph passed visibility checks.",
                        recoverable = true,
                    ),
                )
            } else {
                PipelineStageExecution.Completed(artifact(request))
            }
        }
        val result = PipelineGraph(store, backend.runners).run(snapshot())

        assertEquals(PipelineTerminalState.PARTIAL, result.state)
        assertEquals(PipelineStage.SURFACE, result.lastCompletedStage)
        assertEquals(PipelineRepresentation.OBSERVED_MESH, result.lastValidArtifact?.representation)
        assertFalse(result.artifacts.containsKey(PipelineStage.TEXTURE))
        assertEquals("TEXTURE_VISIBILITY_REJECTED", result.failures.single().code)
        assertNull(store.latest("project-1", PipelineStage.TEXTURE))
    }

    @Test fun explicitStageCancellationDoesNotCommitPartialOutput() = runBlocking {
        val store = MemoryCheckpointStore()
        val initial = CountingBackend()
        PipelineGraph(store, initial.runners).run(snapshot(), PipelineStage.SPARSE)
        val commitsBefore = store.commits.size
        val cancelled = CountingBackend { request ->
            if (request.identity.stage == PipelineStage.DENSE) {
                PipelineStageExecution.Cancelled("User paused dense reconstruction")
            } else {
                PipelineStageExecution.Completed(artifact(request))
            }
        }

        val result = PipelineGraph(store, cancelled.runners).run(snapshot())
        assertEquals(PipelineTerminalState.CANCELLED, result.state)
        assertEquals(PipelineStage.SPARSE, result.lastCompletedStage)
        assertEquals(commitsBefore, store.commits.size)
        assertNull(store.latest("project-1", PipelineStage.DENSE))
        assertTrace(result, PipelineStage.DENSE, PipelineTraceAction.CANCELLED)
    }

    @Test fun coroutineCancellationCarriesLastValidTypedResultAndStillPropagates() = runBlocking {
        val store = MemoryCheckpointStore()
        val initial = CountingBackend()
        PipelineGraph(store, initial.runners).run(snapshot(), PipelineStage.SPARSE)
        val cancelled = CountingBackend { request ->
            if (request.identity.stage == PipelineStage.DENSE) {
                throw CancellationException("native operation stopped")
            }
            PipelineStageExecution.Completed(artifact(request))
        }

        try {
            PipelineGraph(store, cancelled.runners).run(snapshot())
            fail("Cancellation was swallowed")
        } catch (failure: PipelineCancelledException) {
            assertEquals(PipelineTerminalState.CANCELLED, failure.partialResult.state)
            assertEquals(PipelineStage.SPARSE, failure.partialResult.lastCompletedStage)
            assertNull(store.latest("project-1", PipelineStage.DENSE))
            assertEquals("native operation stopped", failure.message)
        }
    }

    @Test fun runnerCannotPublishWrongRepresentationOrIdentity() = runBlocking {
        val store = MemoryCheckpointStore()
        val backend = CountingBackend { request ->
            if (request.identity.stage == PipelineStage.DENSE) {
                val wrongSnapshot = request.snapshot.copy(settings = request.snapshot.settings.copy(dense = "wrong"))
                PipelineStageExecution.Completed(
                    PipelineArtifactReference.forStage(
                        wrongSnapshot.identity(PipelineStage.DENSE),
                        "wrong-dense.bin",
                    ),
                )
            } else {
                PipelineStageExecution.Completed(artifact(request))
            }
        }
        try {
            PipelineGraph(store, backend.runners).run(snapshot())
            fail("Mismatched artifact identity was accepted")
        } catch (_: IllegalArgumentException) {
            assertNull(store.latest("project-1", PipelineStage.DENSE))
        }
    }

    @Test fun currentProductionProfilesNeverSilentlyTruncateTargetJobs() {
        assertEquals(PipelineCapacityProfile.LEGACY_12, PipelineCapacityPolicy.requireSupported(12))
        for ((count, profile) in listOf(
            30 to PipelineCapacityProfile.TARGET_30,
            100 to PipelineCapacityProfile.TARGET_100,
            250 to PipelineCapacityProfile.TARGET_250,
        )) {
            try {
                PipelineCapacityPolicy.requireSupported(count)
                fail("$count-photo target was incorrectly admitted")
            } catch (failure: UnsupportedPipelineProfileException) {
                assertEquals(profile, failure.admission.profile)
                assertEquals(count, failure.admission.photoCount)
                assertTrue(failure.message!!.contains("No photos were silently discarded"))
                assertTrue(failure.message!!.contains("2–12"))
            }
        }
    }

    @Test fun identityIsDeterministicAndBindsParentDigest() {
        val input = snapshot()
        val identities = input.identitiesThrough(PipelineStage.TEXTURE)
        assertEquals(identities, input.identitiesThrough(PipelineStage.TEXTURE))
        assertNull(identities.first().parentDigest)
        identities.zipWithNext().forEach { (parent, child) ->
            assertEquals(parent.digest, child.parentDigest)
        }
        assertEquals(identities.map { it.digest }.toSet().size, identities.size)
    }

    private fun assertTrace(result: PipelineResult, stage: PipelineStage, action: PipelineTraceAction) {
        assertEquals(action, result.trace.single { it.stage == stage }.action)
    }

    private companion object {
        fun snapshot(): PipelineInputSnapshot = PipelineInputSnapshot(
            projectId = "project-1",
            revision = 7,
            photos = listOf(
                PipelinePhotoInput("photo-a", "a".repeat(64), 1600, 1200, 240_000, 26.0),
                PipelinePhotoInput("photo-b", "b".repeat(64), 1600, 1200, 245_000, 26.0),
            ),
            calibrationIdentity = sha256Utf8("calibration-v1"),
            engineVersion = "engine-v1",
            settings = PipelineStageSettings(
                features = "features-v1",
                matching = "matching-v1",
                sparse = "sparse-v1",
                dense = "depth-v1",
                surface = "surface-v1",
                texture = "atlas-v1",
                export = "glb-v1",
            ),
        )

        fun artifact(request: PipelineStageRequest): PipelineArtifactReference =
            PipelineArtifactReference.forStage(
                request.identity,
                "${request.identity.stage.name.lowercase()}-${request.identity.digest.take(12)}.artifact",
                contentSha256 = sha256Utf8(request.identity.digest),
            )
    }
}
