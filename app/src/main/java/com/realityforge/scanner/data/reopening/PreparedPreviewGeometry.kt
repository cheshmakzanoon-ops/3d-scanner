package com.realityforge.scanner.data.reopening

import com.realityforge.scanner.engine.SurfaceTopologySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.sqrt

/** Private buffers expose read-only coordinates; a viewer cannot mutate validated source geometry. */
class PreparedPointCloud internal constructor(private val xyz: FloatArray, private val colors: IntArray) {
    val pointCount: Int get() = colors.size
    fun x(index: Int): Float = xyz[index * 3]
    fun y(index: Int): Float = xyz[index * 3 + 1]
    fun z(index: Int): Float = xyz[index * 3 + 2]
    fun rgb(index: Int): Int = colors[index]
}

/** Canvas samples the same bounded set of faces as the existing diagnostic viewer. */
class PreparedSurface internal constructor(
    val vertices: PreparedPointCloud,
    private val indices: IntArray,
    val sourceTriangleCount: Int,
) {
    val triangleCount: Int get() = indices.size / 3
    fun index(triangle: Int, corner: Int): Int {
        require(corner in 0..2)
        return indices[triangle * 3 + corner]
    }
}

data class PreparedPreviewGeometry(
    val revision: Long,
    val artifactKey: String,
    val previousResult: Boolean,
    val sparse: PreparedPointCloud,
    val dense: PreparedPointCloud?,
    val mesh: PreparedSurface?,
    val capabilities: Set<ScanRepresentation>,
    val unavailableReasons: Map<String, String>,
    val surfaceTopology: SurfaceTopologySummary = SurfaceTopologySummary(),
)

/**
 * Run all JSON-independent coordinate conversion, normalization and face selection off the main
 * thread. Call this only with a successful strict reopening result, then bind the returned identity
 * to the still-selected project/revision before displaying it. No JSONObject reaches a renderer.
 */
suspend fun preparePreviewGeometry(model: ScanDisplayModel): PreparedPreviewGeometry = withContext(Dispatchers.Default) {
    val scope = currentCoroutineContext()
    val checkCancelled = { scope.ensureActive() }
    require(model.sparsePoints.size in 1..PreviewSchemaValidator.MAX_POINTS)
    require(model.densePoints.size <= PreviewSchemaValidator.MAX_POINTS)
    require(model.meshVertices.size <= PreviewSchemaValidator.MAX_VERTICES)
    require(model.triangles.size <= PreviewSchemaValidator.MAX_TRIANGLES)
    val sparse = normalize(model.sparsePoints, checkCancelled)
    val dense = if (model.densePoints.isEmpty()) null else normalize(model.densePoints, checkCancelled)
    val mesh = if (model.meshVertices.isEmpty()) {
        require(model.triangles.isEmpty())
        null
    } else {
        require(model.triangles.isNotEmpty())
        // Validate before allocating face buffers, even for callers constructing a typed DTO directly.
        model.triangles.forEachIndexed { index, face ->
            if (index % 512 == 0) checkCancelled()
            require(face.a in model.meshVertices.indices && face.b in model.meshVertices.indices && face.c in model.meshVertices.indices)
            require(face.a != face.b && face.a != face.c && face.b != face.c)
        }
        val vertices = normalize(model.meshVertices, checkCancelled)
        val stride = ceil(model.triangles.size / 12_000.0).toInt().coerceAtLeast(1)
        val displayedCount = (model.triangles.size - 1) / stride + 1
        val indices = IntArray(displayedCount * 3)
        var destination = 0
        for (source in model.triangles.indices step stride) {
            if (source % 512 == 0) checkCancelled()
            val face = model.triangles[source]
            indices[destination++] = face.a
            indices[destination++] = face.b
            indices[destination++] = face.c
        }
        PreparedSurface(vertices, indices, model.triangles.size)
    }
    checkCancelled()
    PreparedPreviewGeometry(model.revision, model.artifactKey, model.previousResult, sparse, dense, mesh,
        Collections.unmodifiableSet(LinkedHashSet(model.capabilities)),
        Collections.unmodifiableMap(LinkedHashMap(model.unavailableReasons)), model.surfaceTopology)
}

private fun normalize(points: List<DisplayPoint>, checkCancelled: () -> Unit): PreparedPointCloud {
    require(points.isNotEmpty())
    // Convert the existing camera/world display convention once. Double precision avoids an
    // intermediate overflow or precision loss before the bounded normalized floats are produced.
    val x = DoubleArray(points.size)
    val y = DoubleArray(points.size)
    val z = DoubleArray(points.size)
    val colors = IntArray(points.size)
    points.forEachIndexed { index, point ->
        if (index % 512 == 0) checkCancelled()
        require(point.x.isFinite() && point.x in -1e12..1e12 && point.y.isFinite() &&
            point.y in -1e12..1e12 && point.z.isFinite() && point.z in -1e12..1e12)
        require(point.red in 0..255 && point.green in 0..255 && point.blue in 0..255)
        x[index] = point.x; y[index] = -point.y; z[index] = -point.z
        colors[index] = (point.red shl 16) or (point.green shl 8) or point.blue
    }
    x.sort(); checkCancelled(); y.sort(); checkCancelled(); z.sort(); checkCancelled()
    val cx = x[points.size / 2]; val cy = y[points.size / 2]; val cz = z[points.size / 2]
    val radii = DoubleArray(points.size) { index ->
        if (index % 512 == 0) checkCancelled()
        val point = points[index]
        val dx = point.x - cx; val dy = -point.y - cy; val dz = -point.z - cz
        sqrt(dx * dx + dy * dy + dz * dz)
    }
    radii.sort(); checkCancelled()
    val radius = radii[(points.size * .95).toInt().coerceAtMost(points.lastIndex)].coerceAtLeast(.001)
    val xyz = FloatArray(points.size * 3)
    points.forEachIndexed { index, point ->
        if (index % 512 == 0) checkCancelled()
        xyz[index * 3] = ((point.x - cx) / radius).toFloat()
        xyz[index * 3 + 1] = ((-point.y - cy) / radius).toFloat()
        xyz[index * 3 + 2] = ((-point.z - cz) / radius).toFloat()
    }
    return PreparedPointCloud(xyz, colors)
}
