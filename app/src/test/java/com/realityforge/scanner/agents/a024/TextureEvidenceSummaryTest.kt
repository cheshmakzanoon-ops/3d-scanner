package com.realityforge.scanner.agents.a024

import com.realityforge.scanner.data.reopening.PreviewUiSummary
import com.realityforge.scanner.ui.textureCameraSwitchSummary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TextureEvidenceSummaryTest {
    @Test fun compactNullCountersRemainInapplicableAfterActualUiProjection() {
        val source = JSONObject().put("artifactKey", "synthetic-compact-summary").put("revision", 1)
            .put("texturePreview", JSONObject().put("representation", "compact_visible_photographic_atlas")
                .put("viewSwitchEdgesBefore", JSONObject.NULL).put("viewSwitchEdges", JSONObject.NULL))
        val projected = PreviewUiSummary.fromValidated(source).getJSONObject("texturePreview")
        assertTrue(projected.isNull("viewSwitchEdges"))
        assertEquals("Camera-switch edges: not applicable to blended compact atlases.", textureCameraSwitchSummary(projected))
    }

    @Test fun cameraRankedCountsRemainMeasuredIncludingGenuineZeros() {
        for ((before, after) in listOf(16 to 7, 0 to 0)) {
            val texture = JSONObject().put("representation", "camera_ranked_photographic_atlas")
                .put("viewSwitchEdgesBefore", before).put("viewSwitchEdges", after)
            assertEquals("Camera-switch edges: $before → $after.", textureCameraSwitchSummary(texture))
        }
    }

    @Test fun absentNullAndNonIntegerMeasurementsNeverBecomeZeros() {
        for (bad in listOf<Any>(JSONObject.NULL, "0", 0.0, -1, 100_000_001)) {
            val texture = JSONObject().put("representation", "camera_ranked_photographic_atlas")
                .put("viewSwitchEdgesBefore", 0).put("viewSwitchEdges", bad)
            assertEquals("Camera-switch edges: unavailable.", textureCameraSwitchSummary(texture))
        }
        assertEquals("Camera-switch edges: unavailable.", textureCameraSwitchSummary(JSONObject()))
    }
}
