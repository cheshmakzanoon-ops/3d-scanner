package com.realityforge.scanner.agents.a018

import com.realityforge.scanner.data.curation.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCurationRandomizedTest {
    @Test fun randomizedReplayIsDeterministicAndPreservesEveryRetainedOriginalHash() = runBlocking {
        val first = runSequence(18_018); val second = runSequence(18_018)
        assertEquals(first, second); assertTrue(first.applied > 150); assertTrue(first.revision.curationVersion >= first.applied)
    }

    private suspend fun runSequence(seed: Int): Result {
        val random = Random(seed); val initial = fixture(count = 12); val store = MemoryStore(initial)
        val removals = AtomicInteger(); val undos = AtomicInteger()
        val svc = service(store, { "removal-${removals.incrementAndGet()}" }, { "undo-${undos.incrementAndGet()}" })
        val hashes = initial.photos.associate { it.id to it.sha256 }; var applied = 0
        repeat(600) { step ->
            val state = requireNotNull(svc.state(PROJECT_ID)); if (state.photos.isEmpty()) return@repeat
            val target = state.photos[random.nextInt(state.photos.size)]
            val command = when (random.nextInt(6)) {
                0 -> PhotoCurationCommand.Include(PROJECT_ID, state.revision, "include-$step", target.id)
                1 -> PhotoCurationCommand.Exclude(PROJECT_ID, state.revision, "exclude-$step", target.id, PhotoCurationReason(PhotoExclusionCode.entries.random(random)))
                2 -> PhotoCurationCommand.Reorder(PROJECT_ID, state.revision, "order-$step", state.photos.map { it.id }.shuffled(random))
                3 -> PhotoCurationCommand.RequestRemoval(PROJECT_ID, state.revision, "remove-$step", target.id, PhotoRemovalReason(PhotoRemovalCode.entries.random(random)))
                4 -> PhotoCurationCommand.RestorePendingRemoval(PROJECT_ID, state.revision, "restore-$step", target.id)
                else -> {
                    val pending = state.photos.filter { it.state == CuratedPhotoState.REMOVAL_PENDING }
                    if (pending.isEmpty() || state.photos.size <= 4) PhotoCurationCommand.Include(PROJECT_ID, state.revision, "fallback-$step", target.id)
                    else pending.random(random).let { PhotoCurationCommand.CompleteRemoval(PROJECT_ID, state.revision, "complete-$step", it.id, requireNotNull(it.pendingRemoval).requestId, it.sha256) }
                }
            }
            val result = svc.execute(command)
            if (result is PhotoCurationOutcome.Applied) {
                applied++; assertInvariant(result.snapshot, hashes)
                if (result.undoToken != null && random.nextInt(5) == 0) {
                    val undo = svc.execute(PhotoCurationCommand.Undo(PROJECT_ID, result.snapshot.revision, "undo-$step", result.undoToken))
                    if (undo is PhotoCurationOutcome.Applied) { applied++; assertInvariant(undo.snapshot, hashes) }
                }
            }
        }
        val final = requireNotNull(svc.state(PROJECT_ID)); assertInvariant(store.snapshot(), hashes); assertEquals(applied, store.commits)
        return Result(final.revision, final.photos.joinToString("|") { "${it.id}:${it.sha256}:${it.position}:${it.state}:${it.exclusion?.code}:${it.pendingRemoval?.requestId}" }, final.selectedPhotoSet.inputFingerprint, applied)
    }

    private data class Result(val revision: CurationRevision, val photos: String, val fingerprint: String, val applied: Int)
}
