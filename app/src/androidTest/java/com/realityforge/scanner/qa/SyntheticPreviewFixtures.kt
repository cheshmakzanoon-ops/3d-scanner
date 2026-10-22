package com.realityforge.scanner.qa

import org.json.JSONArray
import org.json.JSONObject

/** Synthetic schema-valid metadata only; never reconstruction or physical-camera evidence. */
object SyntheticPreviewFixtures {
    /** Returns independent mutable JSON so negative tests can safely corrupt their own copy. */
    fun sparsePreview(artifactKey: String, revision: Long): JSONObject {
        require(artifactKey.isNotBlank() && revision >= 0)
        return JSONObject()
            .put("schema", 1)
            .put("artifactKey", artifactKey)
            .put("revision", revision)
            .put("representation", "sparse_points")
            .put("scale", "arbitrary")
            .put("synthetic", true)
            .put("fixtureDescription", "Synthetic sparse-preview instrumentation metadata; not reconstruction evidence.")
            .put("registeredCameras", 2)
            .put("pointCount", 2)
            .put("points", JSONArray("[[0,0,1,128,128,128],[1,0,1,64,192,96]]"))
    }
}
