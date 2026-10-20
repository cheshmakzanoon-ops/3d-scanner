package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.*
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
        require(colors.size <= 1_000_000 && xy.size == colors.size * 2 && rotation.size == 9 && translation.size == 3)
        require(xy.all { it.isFinite() } && rotation.all { it.isFinite() } && translation.all { it.isFinite() && abs(it)<1e9 })
        for (a in 0..2) for (b in 0..2) require(abs((0..2).sumOf { rotation[a*3+it]*rotation[b*3+it] }-if(a==b)1.0 else 0.0)<.01)
        val r=rotation
        require(abs(r[0]*(r[4]*r[8]-r[5]*r[7])-r[1]*(r[3]*r[8]-r[5]*r[6])+r[2]*(r[3]*r[7]-r[4]*r[6])-1)<.01)
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
internal class MultiViewTrackGraph(sourceViews: List<SparseView>) {
    private val views: List<SparseView>
    init {
        require(sourceViews.size in 2..250 && sourceViews.sumOf {it.colors.size.toLong()}<=1_000_000)
        views=sourceViews.map { it.copy(xy=it.xy.copyOf(),colors=it.colors.copyOf(),rotation=it.rotation.copyOf(),translation=it.translation.copyOf()) }
    }
    enum class Rejection { INVALID_RAY, EPIPOLAR, SAME_PHOTO_CONFLICT, NODE_BUDGET }
    private val rejections = mutableMapOf<Rejection,Int>()
    fun rejectionCounts(): Map<Rejection,Int> = rejections.toMap()
    private fun reject(reason: Rejection): Boolean { ++rejectedEdges;rejections[reason]=(rejections[reason]?:0)+1;return false }
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
        for ((camera,key) in listOf(cameraA to keyA,cameraB to keyB)) {
            val uv=runCatching { views[camera].normalized(key) }.getOrNull()
            if(uv==null || !uv.first.isFinite() || !uv.second.isFinite() || abs(uv.first)>10 || abs(uv.second)>10) return reject(Rejection.INVALID_RAY)
        }
        if (!epipolarOK(cameraA,keyA,cameraB,keyB)) return reject(Rejection.EPIPOLAR)
        if(parents.size+listOf(node(cameraA,keyA),node(cameraB,keyB)).count { it !in parents }>1_000_000) return reject(Rejection.NODE_BUDGET)
        val a = make(cameraA,keyA)
        val b = make(cameraB,keyB)
        var rootA = root(a)
        var rootB = root(b)
        if (rootA == rootB) { ++acceptedEdges; return true }
        var groupA = requireNotNull(members[rootA])
        var groupB = requireNotNull(members[rootB])
        if (groupB.keys.any { it in groupA }) return reject(Rejection.SAME_PHOTO_CONFLICT)
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
    /** Stable track IDs shared by native rows and incremental landmark provenance.
     * Take whole tracks only; never exceed the native 100,000-observation contract.
     */
    fun snapshot(maxTracks: Int = 15000): List<Map<Int, Int>> {
        require(maxTracks in 1..15000)
        val ordered = members.values.filter { it.size >= 2 }
            .sortedWith(compareBy({ it.entries.minOf { e -> node(e.key,e.value) } }, { it.size }))
        val selected = ArrayList<Map<Int, Int>>()
        var observations = 0
        for (group in ordered) {
            if (selected.size == maxTracks || observations + group.size > 100000) break
            selected.add(group.toSortedMap())
            observations += group.size
        }
        return selected
    }
    fun packedRows(maxTracks: Int = 15000): DoubleArray {
        require(views.size<=12) { "Use bounded nativeBatches for larger graphs" }
        val groups = snapshot(maxTracks)
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
    fun stableSnapshot(photoIds: List<String>): TrackSnapshot {
        require(photoIds.size==views.size && photoIds.distinct().size==photoIds.size)
        val tracks=members.values.filter { it.size>=2 }.map { group ->
            val observations=group.map { SourceObservation(photoIds[it.key],it.value) }.sorted()
            StableTrack(observations.minOf { it.identity() },observations)
        }.sortedBy { it.id }
        return TrackSnapshot(tracks)
    }
    /** Whole-track windows only. A track longer than a native window is explicitly
     * unsupported rather than silently chopped and relabeled as a full track. */
    fun nativeBatches(photoIds: List<String>, maxCameras: Int=12, maxObservations: Int=100000): List<NativeTrackBatch> {
        require(maxCameras in 2..12 && maxObservations in 2..100000)
        val snapshot=stableSnapshot(photoIds);val byPhoto=photoIds.withIndex().associate { it.value to it.index }
        val result=mutableListOf<NativeTrackBatch>();var cameras=sortedSetOf<String>();var tracks=mutableListOf<StableTrack>();var count=0
        fun flush(){if(tracks.isEmpty())return;val photos=cameras.toList();val rows=DoubleArray(count*7);var offset=0
            for((id,track) in tracks.withIndex())for(o in track.observations){val v=views[byPhoto.getValue(o.photo)];val uv=v.normalized(o.keypoint);val color=v.colors[o.keypoint]
                for(value in doubleArrayOf(id.toDouble(),photos.indexOf(o.photo).toDouble(),uv.first,uv.second,((color shr 16) and 255).toDouble(),((color shr 8) and 255).toDouble(),(color and 255).toDouble())) rows[offset++]=value }
            result.add(NativeTrackBatch(photos,tracks.map { it.id },rows));cameras=sortedSetOf();tracks=mutableListOf();count=0 }
        for(t in snapshot.tracks){require(t.observations.size<=maxCameras && t.observations.size<=maxObservations) { "Track exceeds native whole-track window; choose an explicit supported subgraph" }
            if((cameras+t.observations.map { it.photo }).size>maxCameras || count+t.observations.size>maxObservations)flush()
            cameras.addAll(t.observations.map { it.photo });tracks.add(t);count+=t.observations.size }
        flush();return result
    }
    fun matchedTrackCount(): Int = members.values.count { it.size >= 2 }
}
