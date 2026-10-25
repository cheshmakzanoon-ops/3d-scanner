package com.realityforge.scanner.data.reopening

import com.realityforge.scanner.engine.PhotographicCoverage
import com.realityforge.scanner.engine.ScanQualityReport
import com.realityforge.scanner.engine.SurfaceTopologySummary
import com.realityforge.scanner.engine.diagnostics.DiagnosticReportExpectation
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

data class DisplayPoint(val x: Double, val y: Double, val z: Double, val red: Int, val green: Int, val blue: Int)
data class DisplayTriangle(val a: Int, val b: Int, val c: Int)
enum class ScanRepresentation { SPARSE_POINTS, DENSE_POINTS, OBSERVED_MESH, PHOTOGRAPHIC_MESH }
data class ScanDisplayModel(
    val schema: Int,
    val revision: Long,
    val artifactKey: String,
    val sparsePoints: List<DisplayPoint>,
    val densePoints: List<DisplayPoint>,
    val meshVertices: List<DisplayPoint>,
    val triangles: List<DisplayTriangle>,
    val capabilities: Set<ScanRepresentation>,
    val unavailableReasons: Map<String, String>,
    val previousResult: Boolean = false,
    val surfaceTopology: SurfaceTopologySummary = SurfaceTopologySummary(),
)

sealed interface ReopenResult {
    /** The set identifier comes from a verified publication manifest, never from preview JSON. */
    data class Ready(val model: ScanDisplayModel, val publicationSetId: String? = null) : ReopenResult
    data class Invalid(val code: String, val detail: String,
        val recovery: List<String> = listOf("previous_result", "rerun_from_originals")) : ReopenResult
    data object Missing : ReopenResult
    data object Changed : ReopenResult
}

/** Explicit adapters for native sparse schemas 1/3/6 and their bounded 7/8/10 children. */
object PreviewSchemaValidator {
    const val MAX_POINTS = 100_000
    const val MAX_VERTICES = 200_000
    const val MAX_TRIANGLES = 400_000
    const val MAX_BYTES = 32 * 1024 * 1024

    fun validate(json: JSONObject, expectedRevision: Long, expectedArtifactKey: String,
        previousResult: Boolean = false, checkCancelled: () -> Unit = {}): ReopenResult = try {
        val schema = integer(json, "schema", 1, 6).toInt()
        require(schema in setOf(1, 3, 6)) { "Unsupported sparse preview schema $schema." }
        val revision = integer(json, "revision", 0, Long.MAX_VALUE)
        require(revision == expectedRevision && string(json, "artifactKey") == expectedArtifactKey) { "Preview identity changed." }
        require(string(json, "representation") == "sparse_points") { "Expected explicitly identified sparse points." }
        require(string(json, "scale") == "arbitrary") { "This preview schema does not declare measured units." }
        val points = pointRows(json, "points", "pointCount", MAX_POINTS, checkCancelled)
        val cameras = integer(json, "registeredCameras", 2, 250).toInt()
        optionalNumber(json, "medianParallaxDegrees", 0.0, 180.0)
        optionalNumber(json, "medianErrorNormalized", 0.0, 1e6)
        if (json.has("cameras")) {
            val rows = array(json, "cameras")
            require(rows.length() == cameras) { "Camera count does not match rows." }
            for (i in 0 until rows.length()) {
                val row = rows.get(i) as? JSONObject ?: error("Camera must be an object.")
                numericArray(array(row, "rotation"), 9)
                numericArray(array(row, "translation"), 3)
                string(row, "photoId")
            }
        }
        val capabilities = linkedSetOf(ScanRepresentation.SPARSE_POINTS)
        val missing = linkedMapOf<String, String>()
        val dense = stage(json, "dense", missing)
        val densePoints = if (dense == null) emptyList() else {
            require(integer(dense, "schema", 7, 7) == 7L && string(dense, "representation") == "dense_points")
            val parsed = pointRows(dense, "points", "pointCount", MAX_POINTS, checkCancelled)
            integer(dense, "triangleCount", 0, 0)
            require(dense.get("textured") == false && string(dense, "scale") == "arbitrary")
            for (key in listOf("sampledPixels", "photometricPixels", "consistentPixels", "sourcePhotos",
                "rectifiedWidth", "rectifiedHeight", "pixelStride", "excludedRegisteredCameraCount")) {
                if (dense.has(key)) integer(dense, key, 0, 100_000_000)
            }
            capabilities += ScanRepresentation.DENSE_POINTS
            parsed
        }
        val mesh = stage(json, "mesh", missing)
        val vertices: List<DisplayPoint>
        val triangles: List<DisplayTriangle>
        if (mesh == null) { vertices = emptyList(); triangles = emptyList() } else {
            require(dense != null) { "Mesh has no successful depth prerequisite." }
            require(integer(mesh, "schema", 8, 8) == 8L && string(mesh, "representation") == "observed_open_triangle_mesh")
            // Schema 8 geometry remains backward compatible. The optional versioned
            // ledger diagnostics do not turn accepted samples into mesh contributors.
            val ledgerFields = listOf("observationLedgerVersion", "attachedObservationCount",
                "unattachedObservationCount", "exactDuplicateSupportCount")
            if (ledgerFields.any(mesh::has)) {
                integer(mesh, "observationLedgerVersion", 1, 2)
                val input = integer(mesh, "inputSamples", 0, 100_000)
                val accepted = integer(mesh, "acceptedSamples", 0, input)
                val attached = integer(mesh, "attachedObservationCount", 0, accepted)
                val unattached = integer(mesh, "unattachedObservationCount", 0, accepted)
                require(attached + unattached == accepted) { "Accepted observation ledger does not reconcile." }
                integer(mesh, "exactDuplicateSupportCount", 0, attached)
            }
            require(mesh.get("textured") == false && mesh.get("watertight") == false && string(mesh, "scale") == "arbitrary")
            vertices = pointRows(mesh, "vertices", "vertexCount", MAX_VERTICES, checkCancelled)
            // Aliases are representation vertices, never additional observations.
            // Version 1 and pre-ledger previews remain readable without these fields.
            val aliasFields = listOf("topologyAliasSources", "canonicalVertexCount",
                "preAliasInvalidVertexLinks", "postAliasInvalidVertexLinks", "unsupportedAliasStars")
            val aliasLedger = mesh.has("observationLedgerVersion") &&
                integer(mesh, "observationLedgerVersion", 1, 2) == 2L
            if (mesh.has("fusionVersion") && integer(mesh, "fusionVersion", 1, 13) == 13L)
                require(aliasLedger) { "Fusion version 13 requires explicit topology provenance." }
            if (aliasLedger || aliasFields.any(mesh::has)) {
                require(aliasLedger) { "Topology aliases require provenance version 2." }
                integer(mesh, "fusionVersion", 13, 13)
                val canonical = integer(mesh, "canonicalVertexCount", 1, vertices.size.toLong()).toInt()
                val aliases = array(mesh, "topologyAliasSources")
                require(aliases.length() == vertices.size - canonical) { "Topology alias count does not reconcile." }
                integer(mesh, "preAliasInvalidVertexLinks", 0, canonical.toLong())
                integer(mesh, "postAliasInvalidVertexLinks", 0, vertices.size.toLong())
                integer(mesh, "unsupportedAliasStars", 0, canonical.toLong())
                val vertexRows = array(mesh, "vertices")
                for (i in 0 until vertexRows.length()) {
                    if (i % 256 == 0) checkCancelled()
                    require(vertexRows.getJSONArray(i).length() == 11) { "Provenance version 2 requires complete vertex attributes." }
                }
                for (i in 0 until aliases.length()) {
                    if (i % 256 == 0) checkCancelled()
                    val source = strictInteger(aliases.get(i), 0, canonical.toLong() - 1).toInt()
                    val original = vertexRows.getJSONArray(source)
                    val alias = vertexRows.getJSONArray(canonical + i)
                    require(original.length() == alias.length()) { "Topology alias attributes differ." }
                    for (j in 0 until original.length()) {
                        require(original.getDouble(j).toBits() == alias.getDouble(j).toBits()) {
                            "Topology alias attributes differ."
                        }
                    }
                }
            }
            val rows = array(mesh, "triangles")
            val count = integer(mesh, "triangleCount", 1, MAX_TRIANGLES.toLong()).toInt()
            require(rows.length() == count) { "Triangle count does not match rows." }
            val validated = ArrayList<DisplayTriangle>(count)
            for (i in 0 until count) {
                if (i % 512 == 0) checkCancelled()
                val row = rows.get(i) as? JSONArray ?: error("Triangle must be an array.")
                require(row.length() == 3) { "Triangle must have exactly three indices." }
                val a = strictInteger(row.get(0), 0, vertices.lastIndex.toLong()).toInt()
                val b = strictInteger(row.get(1), 0, vertices.lastIndex.toLong()).toInt()
                val c = strictInteger(row.get(2), 0, vertices.lastIndex.toLong()).toInt()
                require(a != b && a != c && b != c) { "Triangle repeats a vertex." }
                validated += DisplayTriangle(a, b, c)
            }
            triangles = Collections.unmodifiableList(validated)
            capabilities += ScanRepresentation.OBSERVED_MESH
        }
        val texture = stage(json, "texture", missing)
        if (texture != null) {
            require(mesh != null) { "Photographic texture has no mesh prerequisite." }
            integer(texture, "schema", 10, 10)
            val representation = string(texture, "representation")
            require(representation in setOf("camera_ranked_photographic_atlas", "compact_visible_photographic_atlas") &&
                texture.get("textured") == true) { "Unsupported photographic atlas representation." }
            val width = integer(texture, "atlasWidth", 1, 4096)
            val height = integer(texture, "atlasHeight", 1, 4096)
            require(width * height <= 4_500_000) { "Photographic atlas exceeds the viewer budget." }
            integer(texture, "triangleCount", 1, triangles.size.toLong())
            number(texture, "faceCoverage", 0.0, 1.0)
            if (representation == "compact_visible_photographic_atlas") {
                // The native compact baker retains the observed mesh, including unsupported faces.
                // It blends texels, not per-face camera labels: null switch counts are NOT zeros.
                require(texture.get("compactCharts") == true && texture.get("watertight") == false &&
                    string(texture, "scale") == "arbitrary" && string(texture, "atlasFormat") == "image/png") {
                    "Compact atlas metadata is inconsistent."
                }
                require(integer(texture, "inputTriangles", 1, MAX_TRIANGLES.toLong()) == triangles.size.toLong() &&
                    integer(texture, "triangleCount", 1, MAX_TRIANGLES.toLong()) == triangles.size.toLong()) {
                    "Compact atlas must retain the observed mesh triangles."
                }
                val visibility = PhotographicCoverage.compactVisibility(texture)
                for (key in listOf("viewSwitchEdgesBefore", "viewSwitchEdges", "reassignedFaces"))
                    require(texture.has(key) && texture.isNull(key)) { "Compact atlas $key must be explicitly inapplicable." }
                val supported = integer(texture, "supportedTexels", 1, width * height)
                require(supported >= visibility.supportedFaces!!.toLong()) {
                    "Compact supported faces exceed supported texels."
                }
                val unsupported = integer(texture, "unsupportedTexels", 0, width * height)
                require(supported + unsupported <= width * height) { "Compact texel counts exceed the atlas." }
                integer(texture, "blendedTexels", 0, supported)
                integer(texture, "chartCount", 1, triangles.size.toLong())
                PhotographicCoverage.requireSerializedFraction(
                    number(texture, "atlasUtilization", 0.0, 1.0),
                    supported.toDouble() / (width * height), "Compact atlas utilization")
                number(texture, "maximumUvStretch", 0.0, 1e6)
                number(texture, "texelsPerArbitraryUnit", 0.0, 1e12)
                number(texture, "meanBlendResidual", 0.0, 1e12)
            } else {
                if (texture.has("compactCharts")) require(texture.get("compactCharts") == false) {
                    "Camera-ranked atlas cannot declare compact charts."
                }
                for (key in listOf("viewSwitchEdgesBefore", "viewSwitchEdges"))
                    if (texture.has(key)) integer(texture, key, 0, 100_000_000)
            }
            if (texture.has("exposureSamples")) integer(texture, "exposureSamples", 0, 100_000_000)
            capabilities += ScanRepresentation.PHOTOGRAPHIC_MESH
        }
        val validatedDiagnostics = if (json.has("scanDiagnostics")) {
            val diagnostics = json.get("scanDiagnostics") as? JSONObject ?: error("Diagnostics must be an object.")
            // M19 report schema 1 carries no independent identity. Its enclosing preview is
            // checked above; migration keeps the report identity unknown instead of fabricating it.
            val expectation = if (integer(diagnostics, "schema", 1, 2) == 1L) DiagnosticReportExpectation()
                else DiagnosticReportExpectation(sourceRevision = expectedRevision, artifactId = expectedArtifactKey)
            ScanQualityReport.validateAndRedact(diagnostics, expectation)
        } else null
        checkCancelled()
        ReopenResult.Ready(ScanDisplayModel(schema, revision, expectedArtifactKey, points, densePoints,
            vertices, triangles, Collections.unmodifiableSet(capabilities), Collections.unmodifiableMap(missing), previousResult,
            SurfaceTopologySummary.fromValidatedReport(validatedDiagnostics)))
    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled
    } catch (failure: Exception) {
        ReopenResult.Invalid("invalid_preview", failure.message?.take(300) ?: "Saved preview validation failed.")
    }

    private fun stage(root: JSONObject, name: String, missing: MutableMap<String, String>): JSONObject? {
        val key = "${name}Preview"
        val status = if (root.has("${name}Status")) string(root, "${name}Status") else null
        require(status == null || status in setOf("ready", "unavailable")) { "Unknown $name status." }
        if (!root.has(key)) {
            require(status != "ready") { "$name is marked ready but its preview is missing." }
            missing[name] = if (root.has("${name}Failure")) string(root, "${name}Failure")
                else "This saved schema contains no $name result."
            return null
        }
        require(status == "ready") { "$name preview is not marked completed." }
        return root.get(key) as? JSONObject ?: error("$name preview must be an object.")
    }

    private fun pointRows(root: JSONObject, field: String, countField: String, maximum: Int,
        checkCancelled: () -> Unit): List<DisplayPoint> {
        val rows = array(root, field)
        val count = integer(root, countField, 1, maximum.toLong()).toInt()
        require(rows.length() == count) { "$countField does not match array length." }
        // Validate every row BEFORE allocating the display list.
        for (i in 0 until count) {
            if (i % 512 == 0) checkCancelled()
            val row = rows.get(i) as? JSONArray ?: error("$field row is not an array.")
            require(row.length() in 6..12) { "$field row has an unsupported layout." }
            for (j in 0 until row.length()) finite(row.get(j), -1e12, 1e12)
            for (j in 3..5) strictInteger(row.get(j), 0, 255)
        }
        return Collections.unmodifiableList(List(count) { i ->
            val row = rows.getJSONArray(i)
            DisplayPoint(row.getDouble(0), row.getDouble(1), row.getDouble(2), row.getInt(3), row.getInt(4), row.getInt(5))
        })
    }

    private fun array(json: JSONObject, key: String) = json.get(key) as? JSONArray ?: error("$key must be an array.")
    private fun string(json: JSONObject, key: String): String = (json.get(key) as? String)?.also {
        require(it.isNotBlank() && it.length <= 2048 && '\u0000' !in it) { "$key is invalid." }
    } ?: error("$key must be a string.")
    private fun integer(json: JSONObject, key: String, min: Long, max: Long) = strictInteger(json.get(key), min, max)
    private fun strictInteger(raw: Any, min: Long, max: Long): Long {
        require(raw is Int || raw is Long) { "Expected an integer value without numeric coercion." }
        return (raw as Number).toLong().also { require(it in min..max) { "Integer is outside the supported bounds." } }
    }
    private fun finite(raw: Any, min: Double, max: Double): Double {
        require(raw is Number) { "Expected a numeric value without string coercion." }
        return raw.toDouble().also { require(it.isFinite() && it in min..max) { "Number is not finite or outside the supported bounds." } }
    }
    private fun number(json: JSONObject, key: String, min: Double, max: Double) = finite(json.get(key), min, max)
    private fun optionalNumber(json: JSONObject, key: String, min: Double, max: Double) {
        if (json.has(key)) number(json, key, min, max)
    }
    private fun numericArray(array: JSONArray, count: Int) {
        require(array.length() == count)
        for (i in 0 until count) finite(array.get(i), -1e12, 1e12)
    }
}

/** Strict UTF-8, bounded framing and depth checks before JSONObject's allocating parser. */
object BoundedJson {
    fun read(input: InputStream, maxBytes: Int, checkCancelled: () -> Unit = {}): JSONObject {
        require(maxBytes > 0)
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        var zeroReads = 0
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) { require(++zeroReads < 16) { "Input provider made no progress." }; continue }
            zeroReads = 0
            require(count <= maxBytes - output.size()) { "JSON exceeds the bounded size." }
            output.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
        var depth = 0; var quoted = false; var escaped = false
        for (i in text.indices) {
            if (i % 8192 == 0) checkCancelled()
            val c = text[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "JSON nesting exceeds the supported limit." } }
                '}', ']' -> { depth--; require(depth >= 0) { "JSON framing is invalid." } }
            }
        }
        require(depth == 0 && !quoted) { "JSON is incomplete." }
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: error("JSON root must be an object.")
        require(tokener.nextClean() == '\u0000') { "Unexpected data follows JSON." }
        return json
    }
}
