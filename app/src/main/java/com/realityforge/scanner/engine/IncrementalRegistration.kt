package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.registration.*
import java.security.MessageDigest
import java.nio.ByteBuffer
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/** Only feature identities are cached; no descriptors, decoded bitmaps, or world coordinates. */
internal data class FeaturePairs(val first: IntArray, val second: IntArray) {
    init {
        require(first.size == second.size && first.size <= 4000)
        require(first.toSet().size == first.size && second.toSet().size == second.size) {
            "Reciprocal matches must be one-to-one"
        }
        require(first.all { it >= 0 } && second.all { it >= 0 })
    }
    fun reversed() = FeaturePairs(second, first)
}
internal data class RegisteredFrame(val photo: Int, val view: SparseView)
internal data class FrontierPoint(val track: Int, val xyz: DoubleArray)
internal data class FrontierPose(
    val rotation: DoubleArray, val translation: DoubleArray, val inliers: IntArray,
)
internal data class RegistrationAttempt(
    val photo: Int, val registeredBasis: Int, val tracks: Int, val accepted: Boolean, val reason: String,
)
internal data class RegistrationGrowth(
    val cameras: List<RegisteredFrame>, val attempts: List<RegistrationAttempt>, val unregistered: List<Int>,
)

/**
 * Grow the sparse coordinate frame instead of requiring every image to see both anchors.
 * All landmarks are freshly triangulated in the current, shared frame. A candidate feature
 * must agree through at least TWO already-registered, reprojection-supported observations.
 * Does not estimate intrinsics, invent unseen features, or alter the first two camera poses.
 */
internal object IncrementalRegistration {
    private data class Landmark(val xyz: DoubleArray, val features: Map<Int, Int>)

    private fun projectedError(view: SparseView, xyz: DoubleArray, image: Pair<Double, Double>): Double {
        val r = view.rotation; val t = view.translation
        val x=r[0]*xyz[0]+r[1]*xyz[1]+r[2]*xyz[2]+t[0]
        val y=r[3]*xyz[0]+r[4]*xyz[1]+r[5]*xyz[2]+t[1]
        val z=r[6]*xyz[0]+r[7]*xyz[1]+r[8]*xyz[2]+t[2]
        return if (z > 1e-8 && x.isFinite() && y.isFinite() && z.isFinite())
            hypot(x/z-image.first,y/z-image.second) else Double.POSITIVE_INFINITY
    }

    /** For duplicate candidate identities, reject EVERY competing landmark (not first wins). */
    internal fun candidateRows(
        world: List<DoubleArray>, featureGroups: List<Map<Int, Int>>,
        destinationBySource: Map<Int, Map<Int, Int>>, candidate: SparseView,
    ): DoubleArray {
        require(world.size == featureGroups.size && world.size <= 15000)
        val candidates = ArrayList<Pair<Int, DoubleArray>>()
        for (i in world.indices) {
            val xyz=world[i]
            require(xyz.size == 3 && xyz.all { it.isFinite() })
            val identities=featureGroups[i].mapNotNull { (photo,key) -> destinationBySource[photo]?.get(key) }
            // A disagreement is not resolved by a majority vote over possibly incorrect matches.
            if (identities.size < 2 || identities.toSet().size != 1) continue
            val key=identities.first()
            if (key !in candidate.colors.indices) continue
            val uv=runCatching { candidate.normalized(key) }.getOrNull() ?: continue
            if (!uv.first.isFinite() || !uv.second.isFinite() || abs(uv.first)>10 || abs(uv.second)>10) continue
            candidates.add(key to doubleArrayOf(xyz[0],xyz[1],xyz[2],uv.first,uv.second))
        }
        val frequencies=candidates.groupingBy { it.first }.eachCount()
        val unique=candidates.filter { frequencies[it.first] == 1 }.take(5000)
        return DoubleArray(unique.size*5) { unique[it/5].second[it%5] }
    }

    private fun validatePose(candidate: SparseView, rows: DoubleArray, pose: FrontierPose, threshold: Double) {
        require(pose.rotation.size == 9 && pose.translation.size == 3)
        require((pose.rotation.asList()+pose.translation.asList()).all { it.isFinite() })
        val r=pose.rotation
        val determinant=r[0]*(r[4]*r[8]-r[5]*r[7])-r[1]*(r[3]*r[8]-r[5]*r[6])+r[2]*(r[3]*r[7]-r[4]*r[6])
        require(abs(determinant-1.0)<.01) { "Non-rigid candidate pose" }
        for (a in 0..2) for (b in 0..2) {
            val product=(0..2).sumOf { r[a*3+it]*r[b*3+it] }
            require(abs(product-if(a==b)1.0 else 0.0)<.01) { "Non-orthonormal candidate pose" }
        }
        val count=rows.size/5
        require(pose.inliers.size>=maxOf(16,ceil(count*.28).toInt()) &&
            pose.inliers.toSet().size==pose.inliers.size && pose.inliers.all { it in 0 until count }) {
            "Insufficient unique pose inliers"
        }
        val view=candidate.copy(rotation=r,translation=pose.translation)
        for (index in pose.inliers) {
            val base=index*5
            val xyz=doubleArrayOf(rows[base],rows[base+1],rows[base+2])
            require(projectedError(view,xyz,rows[base+3] to rows[base+4]) <= threshold*1.001) {
                "Pose inlier failed independent positive-depth/reprojection check"
            }
        }
    }

    /** Preserve the original trailing-progress-lambda API for existing pipeline
     * and instrumentation callers. New persisted sessions pass explicit IDs. */
    suspend fun grow(
        templates: List<SparseView>, initial: List<RegisteredFrame>,
        matches: suspend (Int,Int) -> FeaturePairs,
        triangulate: (DoubleArray,DoubleArray,Double) -> List<FrontierPoint>,
        register: (DoubleArray,Double) -> FrontierPose,
        progress: suspend (String,Int,Int) -> Unit = { _,_,_ -> },
    ): RegistrationGrowth = grow(templates,initial,matches,triangulate,register,
        photoIds=templates.indices.map {it.toString()},progress=progress)

    suspend fun grow(
        templates: List<SparseView>,
        initial: List<RegisteredFrame>,
        matches: suspend (Int,Int) -> FeaturePairs,
        triangulate: (DoubleArray,DoubleArray,Double) -> List<FrontierPoint>,
        register: (DoubleArray,Double) -> FrontierPose,
        photoIds: List<String>,
        resume: RegistrationFrontier? = null,
        checkpoint: (RegistrationFrontier) -> Unit = {},
        neighbors: ((Int) -> Set<Int>)? = null,
        pairBudget: Int = 100000,
        progress: suspend (String,Int,Int) -> Unit = { _,_,_ -> },
    ): RegistrationGrowth {
        require(templates.size in 2..250 && initial.size in 2..templates.size)
        require(photoIds.size==templates.size && photoIds.distinct().size==photoIds.size && photoIds.all {it.isNotBlank()&&it.toByteArray().size<=1024})
        require(pairBudget in 1..1000000)
        require(initial.map { it.photo }.toSet().size == initial.size &&
            initial.all { it.photo in templates.indices })
        val digest=MessageDigest.getInstance("SHA-256");digest.update("registration-frontier-v2".toByteArray())
        for(index in templates.indices.sortedBy {photoIds[it]}){
            digest.update(photoIds[index].toByteArray());digest.update(0.toByte());val view=templates[index]
            digest.update(ByteBuffer.allocate(20).putInt(view.width).putInt(view.height).putDouble(view.focalPixels).putInt(view.colors.size).array())
            for(value in view.xy)digest.update(ByteBuffer.allocate(4).putFloat(value).array())
            digest.update(view.projection.toString().toByteArray())
        }
        val identity=digest.digest().joinToString(""){"%02x".format(it)}
        if(resume!=null)require(resume.inputIdentity==identity && resume.photoIds.toSet()==photoIds.toSet()) { "Stale registration frontier" }
        val cameras=(resume?.cameras?.map { c -> val photo=photoIds.indexOf(c.photoId);RegisteredFrame(photo,templates[photo].copy(rotation=c.rotation.toDoubleArray(),translation=c.translation.toDoubleArray())) }?:initial).toMutableList()
        for(anchor in initial){val recovered=cameras.firstOrNull {it.photo==anchor.photo};require(recovered!=null && recovered.view.rotation.contentEquals(anchor.view.rotation) && recovered.view.translation.contentEquals(anchor.view.translation)) { "Resume changed anchor frame" }}
        val attempts=ArrayList<RegistrationAttempt>();val failures=(resume?.failureReasons?:emptyMap()).toMutableMap()
        var sourceTracks=resume?.trackSnapshot?:ByteArray(0)
        fun save(){checkpoint(RegistrationFrontier(identity,photoIds.toList(),cameras.map {FrontierCamera(photoIds[it.photo],it.view.rotation.toList(),it.view.translation.toList())},failures.toMap(),sourceTracks.copyOf()))}
        val cache=object:LinkedHashMap<Pair<Int,Int>,FeaturePairs>(512,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Pair<Int,Int>,FeaturePairs>)=size>512}
        var evaluations=0
        suspend fun pair(a:Int,b:Int):FeaturePairs{
            val key=minOf(a,b) to maxOf(a,b);var value=cache[key]
            if(value==null){require(evaluations<pairBudget){"Registration pair-evaluation budget exhausted"};++evaluations;value=matches(key.first,key.second);cache[key]=value}
            return if(a==key.first)value else value.reversed()
        }
        // Each successful round appends exactly one camera; one unsuccessful round terminates.
        while (cameras.size < templates.size) {
            coroutineContext.ensureActive()
            val pending=templates.indices.filter { photo -> cameras.none { it.photo == photo } }
            // A native window is capped at12 cameras while all poses retain the
            // same world frame. Preserve anchors plus the newest frontier support.
            val window=if(cameras.size<=12)cameras.toList() else cameras.take(2)+cameras.takeLast(10)
            val graph=MultiViewTrackGraph(window.map { it.view })
            var pairIndex=0
            val pairCount=window.size*(window.size-1)/2
            for (a in window.indices) for (b in a+1 until window.size) {
                progress("Linking registered camera features",pairIndex++,pairCount)
                coroutineContext.ensureActive()
                if(neighbors!=null && window[b].photo !in neighbors(window[a].photo) && window[a].photo !in neighbors(window[b].photo))continue
                val links=pair(window[a].photo,window[b].photo)
                for (k in links.first.indices) graph.add(a,links.first[k],b,links.second[k])
            }
            sourceTracks=graph.stableSnapshot(window.map {photoIds[it.photo]}).encode()
            val groups=graph.snapshot()
            val observations=graph.packedRows()
            if (groups.size<16){for(photo in pending)failures[photoIds[photo]]="Insufficient verified registered tracks in the admitted native window";break}
            val cameraRows=DoubleArray(window.size*12) {
                val view=window[it/12].view
                if(it%12<9)view.rotation[it%12] else view.translation[it%12-9]
            }
            val threshold=3.0/window.minOf { it.view.focalPixels }
            progress("Triangulating registration landmarks",0,1)
            coroutineContext.ensureActive()
            val points=triangulate(cameraRows,observations,threshold)
            require(points.size<=15000 && points.map { it.track }.toSet().size==points.size)
            val landmarks=points.mapNotNull { point ->
                require(point.track in groups.indices && point.xyz.size==3 && point.xyz.all { it.isFinite() })
                val supported=groups[point.track].filter { (local,key) ->
                    val view=window[local].view
                    val uv=runCatching { view.normalized(key) }.getOrNull()
                    uv!=null && projectedError(view,point.xyz,uv)<=threshold*2
                }.mapKeys { (local,_) -> window[local].photo }
                if(supported.size>=2)Landmark(point.xyz,supported) else null
            }
            if(landmarks.size<16){for(photo in pending)failures[photoIds[photo]]="Insufficient positive-depth reprojection-supported landmarks";break}
            var added=false
            // Rank actual unambiguous two-view landmark support, independent of
            // import ordering. Retain only8 bounded candidate row buffers.
            val ranked=mutableListOf<Pair<Int,DoubleArray>>()
            for(photo in pending.sortedBy {photoIds[it]}){
                coroutineContext.ensureActive();val maps=HashMap<Int,Map<Int,Int>>()
                for(camera in window){if(neighbors!=null && photo !in neighbors(camera.photo) && camera.photo !in neighbors(photo))continue
                    val links=pair(camera.photo,photo);maps[camera.photo]=links.first.indices.associate { links.first[it] to links.second[it] }}
                val rows=candidateRows(landmarks.map {it.xyz},landmarks.map {it.features},maps,templates[photo])
                if(rows.size/5<16){failures[photoIds[photo]]="Fewer than16 unambiguous two-view-supported landmarks";continue}
                ranked.add(photo to rows);ranked.sortWith(compareByDescending<Pair<Int,DoubleArray>>{it.second.size}.thenBy {photoIds[it.first]});if(ranked.size>8)ranked.removeAt(ranked.lastIndex)
            }
            for ((step,candidate) in ranked.withIndex()) {
                val (photo,rows)=candidate
                coroutineContext.ensureActive()
                progress("Registering beyond the anchor pair",step,ranked.size)
                val count=rows.size/5
                if(count<16) {
                    attempts.add(RegistrationAttempt(photo,cameras.size,count,false,"Fewer than 16 unambiguous two-view-supported landmarks"))
                    continue
                }
                val gate=2.0/templates[photo].focalPixels
                try {
                    val pose=register(rows,gate)
                    coroutineContext.ensureActive()
                    validatePose(templates[photo],rows,pose,gate)
                    attempts.add(RegistrationAttempt(photo,cameras.size,count,true,"Verified native PnP and reprojection consensus"))
                    cameras.add(RegisteredFrame(photo,templates[photo].copy(
                        rotation=pose.rotation.copyOf(),translation=pose.translation.copyOf())))
                    failures.remove(photoIds[photo]);save()
                    added=true
                    break // Re-triangulate with the new camera before trying the remaining views.
                } catch(cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch(failure: IllegalArgumentException) {
                    val reason=failure.message.orEmpty().take(200);failures[photoIds[photo]]=reason
                    attempts.add(RegistrationAttempt(photo,cameras.size,count,false,reason))
                }
            }
            if(!added) break
        }
        save()
        return RegistrationGrowth(cameras.toList(),attempts.toList(),
            templates.indices.filter { photo -> cameras.none { it.photo==photo } })
    }
}
