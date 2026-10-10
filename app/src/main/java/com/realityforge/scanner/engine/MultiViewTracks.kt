package com.realityforge.scanner.engine

import kotlin.math.abs
import kotlin.math.sqrt

/** A calibrated registered image: keypoint pixels are in the EXIF-upright analysis image. */
internal data class SparseView(
    val width: Int,
    val height: Int,
    val focalPixels: Double,
    val xy: FloatArray,
    val colors: IntArray,
    val rotation: DoubleArray,
    val translation: DoubleArray,
    val projection: LensProjection? = null,
) {
    init {
        require(width > 2 && height > 2 && focalPixels > 0 && focalPixels.isFinite())
        require(xy.size == colors.size * 2 && rotation.size == 9 && translation.size == 3)
    }
    fun normalized(key: Int): Pair<Double, Double> =
        projection?.normalized(xy[2*key].toDouble(), xy[2*key+1].toDouble()) ?:
            (((xy[2*key] - (width-1)/2.0) / focalPixels) to
                ((xy[2*key+1] - (height-1)/2.0) / focalPixels))
}

/**
 * Conflict-aware union of reciprocal feature pairs, constrained by registered camera epipolar geometry.
 * Never merges components containing different keypoints from the same photograph.
 * Camera observations use Brown-Conrady undistortion when a verified project profile exists.
 * Does not measure physical scale or generate dense/textured geometry.
 */
internal class MultiViewTrackGraph(private val views: List<SparseView>) {
    init { require(views.size in 2..12) }
    private val parents = HashMap<Long, Long>()
    private val members = HashMap<Long, MutableMap<Int, Int>>()
    var acceptedEdges: Int = 0
        private set
    var rejectedEdges: Int = 0
        private set

    private fun node(camera: Int, key: Int) = (camera.toLong() shl 32) or (key.toLong() and 0xffffffffL)
    private fun root(id: Long): Long {
        val current = parents[id] ?: return id
        if (current == id) return id
        val representative = root(current)
        parents[id] = representative
        return representative
    }
    private fun make(camera: Int, key: Int): Long {
        require(camera in views.indices && key in views[camera].colors.indices)
        val id = node(camera, key)
        if (parents.putIfAbsent(id, id) == null) members[id] = hashMapOf(camera to key)
        return id
    }
    private fun epipolarOK(a: Int, keyA: Int, b: Int, keyB: Int): Boolean {
        val p = views[a]
        val q = views[b]
        val (x, y) = p.normalized(keyA)
        val (u, v) = q.normalized(keyB)
        // Relative world-to-camera transform: Rba = Rb * Ra^T, tba = tb - Rba*ta.
        val r = DoubleArray(9) { index ->
            val row = index / 3
            val col = index % 3
            (0..2).sumOf { k -> q.rotation[row * 3 + k] * p.rotation[col * 3 + k] }
        }
        val t = DoubleArray(3) { index -> q.translation[index] - (0..2).sumOf { k -> r[index * 3 + k] * p.translation[k] } }
        val baseline = sqrt(t.sumOf { it * it })
        if (baseline < 1e-7) return false
        val ra = doubleArrayOf(r[0]*x+r[1]*y+r[2], r[3]*x+r[4]*y+r[5], r[6]*x+r[7]*y+r[8])
        val ea = doubleArrayOf(t[1]*ra[2]-t[2]*ra[1], t[2]*ra[0]-t[0]*ra[2], t[0]*ra[1]-t[1]*ra[0])
        val tb = doubleArrayOf(t[1]*v-t[2]*1.0, t[2]*u-t[0]*v, t[0]*1.0-t[1]*u)
        // E^T b = Rba^T * (b cross t), equivalent to -Rba^T*(t cross b).
        val eb = doubleArrayOf(
            r[0]*tb[0]+r[3]*tb[1]+r[6]*tb[2],
            r[1]*tb[0]+r[4]*tb[1]+r[7]*tb[2],
            r[2]*tb[0]+r[5]*tb[1]+r[8]*tb[2],
        )
        val residual = u*ea[0]+v*ea[1]+ea[2]
        val denominator = ea[0]*ea[0]+ea[1]*ea[1]+eb[0]*eb[0]+eb[1]*eb[1]
        val tolerance = 4.0 / minOf(p.focalPixels,q.focalPixels)
        return denominator > 1e-18 && (residual*residual/denominator) <= tolerance*tolerance
    }
    fun add(cameraA: Int, keyA: Int, cameraB: Int, keyB: Int): Boolean {
        require(cameraA in views.indices && cameraB in views.indices && cameraA != cameraB)
        require(keyA in views[cameraA].colors.indices && keyB in views[cameraB].colors.indices)
        if (!epipolarOK(cameraA,keyA,cameraB,keyB)) { ++rejectedEdges; return false }
        val a = make(cameraA,keyA)
        val b = make(cameraB,keyB)
        var rootA = root(a)
        var rootB = root(b)
        if (rootA == rootB) { ++acceptedEdges; return true }
        var groupA = requireNotNull(members[rootA])
        var groupB = requireNotNull(members[rootB])
        if (groupB.keys.any { it in groupA }) { ++rejectedEdges; return false }
        if (groupA.size < groupB.size) {
            val tempRoot = rootA; rootA = rootB; rootB = tempRoot
            val tempGroup = groupA; groupA = groupB; groupB = tempGroup
        }
        parents[rootB] = rootA
        groupA.putAll(groupB)
        members.remove(rootB)
        ++acceptedEdges
        return true
    }
    fun packedRows(maxTracks: Int = 15000): DoubleArray {
        val groups = members.values.filter { it.size >= 2 }
            .sortedWith(compareBy({ it.entries.minOf { e -> node(e.key,e.value) } }, { it.size }))
            .take(maxTracks)
        val packed = ArrayList<Double>(groups.sumOf { it.size } * 7)
        for ((trackId,group) in groups.withIndex()) {
            for ((camera,key) in group.toSortedMap()) {
                val frame=views[camera]
                val (u,v)=frame.normalized(key)
                if (!u.isFinite() || !v.isFinite() || abs(u)>10 || abs(v)>10) continue
                val color=frame.colors[key]
                packed.add(trackId.toDouble());packed.add(camera.toDouble())
                packed.add(u);packed.add(v)
                packed.add(((color shr 16) and 255).toDouble())
                packed.add(((color shr 8) and 255).toDouble())
                packed.add((color and 255).toDouble())
            }
        }
        return packed.toDoubleArray()
    }
    fun matchedTrackCount(): Int = members.values.count { it.size >= 2 }
}
