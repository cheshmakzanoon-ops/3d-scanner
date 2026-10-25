package com.realityforge.scanner.engine.matching

import com.realityforge.scanner.engine.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class MatchInputOrderTest {
    private val colors = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt(), 0xff789abc.toInt())
    private val physical = listOf(
        FeatureCorrespondence(0, 2, .1, .2, .7, .8, colors[0]),
        FeatureCorrespondence(1, 0, .3, .4, .9, 1.0, colors[1]),
        FeatureCorrespondence(2, 1, .5, .6, 1.1, 1.2, colors[2]))

    @Test fun oppositeUuidOrderPreservesEveryKeyRayAndPhysicalFirstColor() {
        val forward = TentativeMatches("a", "z", physical, "fixed-features")
        val reverse = TentativeMatches("a", "z", physical.map {
            FeatureCorrespondence(it.secondKey, it.firstKey, it.bx, it.by, it.ax, it.ay, 0)
        }.sortedBy { it.firstKey }, "fixed-features")
        assertNotEquals(physical.map { it.firstKey }, reverse.matches.map { it.secondKey })
        assertEquals(physical, forward.inInputOrder("a", colors))
        assertEquals(physical, reverse.inInputOrder("z", colors))
        // Observed pixels are subsequently gathered by these synchronized keys, not row number.
        val firstPixels = listOf(10 to 20, 30 to 40, 50 to 60)
        val secondPixels = listOf(90 to 100, 110 to 120, 70 to 80)
        assertEquals(physical.map { firstPixels[it.firstKey] to secondPixels[it.secondKey] },
            reverse.inInputOrder("z", colors).map { firstPixels[it.firstKey] to secondPixels[it.secondKey] })
        assertEquals(listOf(0, 1, 2), reverse.matches.map { it.firstKey }) // source remains immutable
    }

    @Test fun permutationsEmptyAndInvalidIdentityAreExplicit() {
        for (rows in listOf(physical, physical.reversed(), listOf(physical[1], physical[2], physical[0])))
            assertEquals(physical, TentativeMatches("a", "z", rows, "fixed").inInputOrder("a", colors))
        val empty = TentativeMatches("a", "z", emptyList(), "fixed")
        assertTrue(empty.inInputOrder("z", intArrayOf()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { empty.inInputOrder("unknown", colors) }
        assertThrows(IllegalArgumentException::class.java) {
            TentativeMatches("a", "z", physical, "fixed").inInputOrder("a", intArrayOf())
        }
    }

    @Test fun newRowPolicyInvalidatesMatchingAndAllDescendantsButNotFeatures() {
        val old = PipelineInputSnapshot("fixed", 1, listOf(
            PipelinePhotoInput("a", "a".repeat(64), 64, 64, 100, 88.0),
            PipelinePhotoInput("z", "b".repeat(64), 64, 64, 100, 88.0)),
            "c".repeat(64), "unchanged", PipelineStageSettings("features", "pair-cache-v3", "sparse", "dense", "surface", "texture", "export"))
        val current = old.copy(settings = old.settings.copy(matching = old.settings.matching + "|" + TentativeMatches.INPUT_ORDER_POLICY))
        for (stage in PipelineStage.entries) {
            if (stage == PipelineStage.FEATURES) assertEquals(old.identity(stage), current.identity(stage))
            else assertNotEquals(old.identity(stage), current.identity(stage))
        }
    }
}
