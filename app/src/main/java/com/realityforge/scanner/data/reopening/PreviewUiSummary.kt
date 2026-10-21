package com.realityforge.scanner.data.reopening

import org.json.JSONArray
import org.json.JSONObject

/**
 * A bounded display projection of an already validated preview. This is not a saved preview,
 * geometry source, diagnostic export, or replacement for PreviewSchemaValidator.
 *
 * Only fixed field names are inspected. No source object is serialized, traversed, or retained;
 * large geometry arrays and diagnostic histories can be released after viewer preparation.
 */
internal object PreviewUiSummary {
    const val MAX_IDENTIFIER_CHARS = 160
    const val MAX_TEXT_CHARS = 512
    const val MAX_ACTION_CHARS = 256
    const val MAX_RECOMMENDATIONS = 4

    fun fromValidated(source: JSONObject): JSONObject = JSONObject().apply {
        val artifactKey = source.opt("artifactKey")
        require(artifactKey is String && artifactKey.isNotEmpty() &&
            artifactKey.length <= MAX_IDENTIFIER_CHARS && artifactKey.none(Char::isISOControl)) {
            "The validated preview must have a bounded artifact identity."
        }
        val revision = source.opt("revision")
        require((revision is Int || revision is Long) && (revision as Number).toLong() >= 0) {
            "The validated preview must have a nonnegative revision."
        }
        // Never truncate identity: the UI must still bind the exact validated viewer artifact.
        put("artifactKey", artifactKey)
        put("revision", (revision as Number).toLong())
        copyCounts(source, this, TOP_COUNTS)
        copyNumbers(source, this, TOP_NUMBERS)
        for (key in FAILURE_TEXT) copyText(source, this, key, MAX_TEXT_CHARS)
        for (key in STAGE_STATUS) copyText(source, this, key, 32)
        stage(source, "densePreview", DENSE_COUNTS, DENSE_NUMBERS, DENSE_FLAGS)?.let { put("densePreview", it) }
        stage(source, "meshPreview", MESH_COUNTS, MESH_NUMBERS, MESH_FLAGS)?.let { put("meshPreview", it) }
        stage(source, "texturePreview", TEXTURE_COUNTS, TEXTURE_NUMBERS, TEXTURE_FLAGS)?.let {
            put("texturePreview", it)
        }
        source.optJSONObject("scanDiagnostics")?.let { diagnostics ->
            val summary = JSONObject()
            copyCounts(diagnostics, summary, DIAGNOSTIC_COUNTS)
            copyNumbers(diagnostics, summary, DIAGNOSTIC_NUMBERS)
            copyText(diagnostics, summary, "status", 80)
            val actions = JSONArray()
            diagnostics.optJSONArray("recommendations")?.let { recommendations ->
                // Inspect the first four entries only, even if a source has millions of entries.
                for (index in 0 until minOf(recommendations.length(), MAX_RECOMMENDATIONS)) {
                    val recommendation = recommendations.optJSONObject(index) ?: continue
                    val action = recommendation.opt("action") as? String ?: continue
                    actions.put(JSONObject().put("action", boundedText(action, MAX_ACTION_CHARS)))
                }
            }
            summary.put("recommendations", actions)
            put("scanDiagnostics", summary)
        }
    }

    private fun stage(source: JSONObject, key: String, counts: List<String>, numbers: List<String>,
        flags: List<String>): JSONObject? = source.optJSONObject(key)?.let { original ->
        JSONObject().apply {
            copyCounts(original, this, counts)
            copyNumbers(original, this, numbers)
            for (field in flags) (original.opt(field) as? Boolean)?.let { put(field, it) }
            copyText(original, this, "representation", 80)
            copyText(original, this, "scale", 32)
        }
    }

    private fun copyCounts(source: JSONObject, target: JSONObject, keys: List<String>) {
        for (key in keys) when (val value = source.opt(key)) {
            JSONObject.NULL -> target.put(key, JSONObject.NULL)
            is Int -> if (value >= 0) target.put(key, value)
            is Long -> if (value >= 0) target.put(key, value)
        }
    }

    private fun copyNumbers(source: JSONObject, target: JSONObject, keys: List<String>) {
        for (key in keys) {
            val value = source.opt(key)
            if (value === JSONObject.NULL) target.put(key, JSONObject.NULL)
            else if (value is Number) {
                // Copy an immutable primitive, never an arbitrary Number implementation.
                val number = value.toDouble()
                if (number.isFinite()) target.put(key, number)
            }
        }
    }

    private fun copyText(source: JSONObject, target: JSONObject, key: String, limit: Int) {
        val value = source.opt(key) as? String ?: return
        target.put(key, boundedText(value, limit))
    }

    private fun boundedText(value: String, limit: Int): String {
        var end = minOf(value.length, limit)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1])) end--
        return buildString(end) {
            for (index in 0 until end) append(if (value[index].isISOControl()) ' ' else value[index])
        }
    }

    private val TOP_COUNTS = listOf("schema", "pointCount", "registeredCameras")
    private val TOP_NUMBERS = listOf("medianParallaxDegrees", "medianErrorNormalized")
    private val FAILURE_TEXT = listOf("denseFailure", "meshFailure", "textureFailure")
    private val STAGE_STATUS = listOf("denseStatus", "meshStatus", "textureStatus")
    private val DENSE_COUNTS = listOf("schema", "pointCount", "triangleCount", "sourcePhotos", "rectifiedWidth",
        "rectifiedHeight", "pixelStride", "excludedRegisteredCameraCount", "sampledPixels", "photometricPixels",
        "consistentPixels", "depthPlanes", "depthCheckpointSchema", "depthCheckpointResumed", "depthCheckpointWritten",
        "resumedDepthMaps", "writtenDepthMaps", "peakCachedLuminanceBytes")
    private val DENSE_NUMBERS = listOf("nearDepthInAnchorUnits", "farDepthInAnchorUnits")
    private val DENSE_FLAGS = listOf("textured", "photographsRectified", "calibrationProfileApplied",
        "surfaceCheckpointAvailable", "surfaceCheckpointWritten", "geometryReused", "textureReused")
    private val MESH_COUNTS = listOf("schema", "vertexCount", "triangleCount", "boundaryEdges", "nonManifoldEdges",
        "connectedComponents", "selfIntersections", "nonmanifoldEdges", "intersections")
    private val MESH_NUMBERS = emptyList<String>()
    private val MESH_FLAGS = listOf("textured", "watertight", "topologyIncomplete")
    private val TEXTURE_COUNTS = listOf("schema", "atlasWidth", "atlasHeight", "triangleCount", "inputTriangles",
        "viewSwitchEdgesBefore", "viewSwitchEdges", "exposureSamples")
    private val TEXTURE_NUMBERS = listOf("faceCoverage")
    private val TEXTURE_FLAGS = listOf("textured")
    private val DIAGNOSTIC_COUNTS = listOf("schema", "importedPhotoCount", "registeredCameraCount", "sparsePointCount",
        "sampledDepthPixels", "reciprocalDepthPixels", "observedMeshTriangles", "observedMeshVertices")
    private val DIAGNOSTIC_NUMBERS = listOf("registeredPhotoFraction", "medianParallaxDegrees",
        "medianReprojectionNormalized", "reciprocalDepthFraction", "photographicallyCoveredFaceFraction")
}
