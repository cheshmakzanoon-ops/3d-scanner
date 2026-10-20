package com.realityforge.scanner.engine

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

    suspend fun grow(
        templates: List<SparseView>,
        initial: List<RegisteredFrame>,
        matches: suspend (Int,Int) -> FeaturePairs,
        triangulate: (DoubleArray,DoubleArray,Double) -> List<FrontierPoint>,
        register: (DoubleArray,Double) -> FrontierPose,
        progress: suspend (String,Int,Int) -> Unit = { _,_,_ -> },
    ): RegistrationGrowth {
        require(templates.size in 2..12 && initial.size in 2..templates.size)
        require(initial.map { it.photo }.toSet().size == initial.size &&
            initial.all { it.photo in templates.indices })
        val cameras=initial.toMutableList()
        val attempts=ArrayList<RegistrationAttempt>()
        // Each successful round appends exactly one camera; one unsuccessful round terminates.
        while (cameras.size < templates.size) {
            coroutineContext.ensureActive()
            val pending=templates.indices.filter { photo -> cameras.none { it.photo == photo } }
            val graph=MultiViewTrackGraph(cameras.map { it.view })
            var pairIndex=0
            val pairCount=cameras.size*(cameras.size-1)/2
            for (a in cameras.indices) for (b in a+1 until cameras.size) {
                progress("Linking registered camera features",pairIndex++,pairCount)
                coroutineContext.ensureActive()
                val pair=matches(cameras[a].photo,cameras[b].photo)
                for (k in pair.first.indices) graph.add(a,pair.first[k],b,pair.second[k])
            }
            val groups=graph.snapshot()
            val observations=graph.packedRows()
            if (groups.size<16) break
            val cameraRows=DoubleArray(cameras.size*12) {
                val view=cameras[it/12].view
                if(it%12<9)view.rotation[it%12] else view.translation[it%12-9]
            }
            val threshold=3.0/cameras.minOf { it.view.focalPixels }
            progress("Triangulating registration landmarks",0,1)
            coroutineContext.ensureActive()
            val points=triangulate(cameraRows,observations,threshold)
            require(points.size<=15000 && points.map { it.track }.toSet().size==points.size)
            val landmarks=points.mapNotNull { point ->
                require(point.track in groups.indices && point.xyz.size==3 && point.xyz.all { it.isFinite() })
                val supported=groups[point.track].filter { (local,key) ->
                    val view=cameras[local].view
                    val uv=runCatching { view.normalized(key) }.getOrNull()
                    uv!=null && projectedError(view,point.xyz,uv)<=threshold*2
                }.mapKeys { (local,_) -> cameras[local].photo }
                if(supported.size>=2)Landmark(point.xyz,supported) else null
            }
            if(landmarks.size<16) break
            var added=false
            for ((step,photo) in pending.withIndex()) {
                coroutineContext.ensureActive()
                progress("Registering beyond the anchor pair",step,pending.size)
                val maps=HashMap<Int,Map<Int,Int>>()
                for(camera in cameras) {
                    val pair=matches(camera.photo,photo)
                    maps[camera.photo]=pair.first.indices.associate { pair.first[it] to pair.second[it] }
                }
                val rows=candidateRows(landmarks.map { it.xyz },landmarks.map { it.features },maps,templates[photo])
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
                    added=true
                    break // Re-triangulate with the new camera before trying the remaining views.
                } catch(cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch(failure: IllegalArgumentException) {
                    attempts.add(RegistrationAttempt(photo,cameras.size,count,false,failure.message.orEmpty().take(200)))
                }
            }
            if(!added) break
        }
        return RegistrationGrowth(cameras.toList(),attempts.toList(),
            templates.indices.filter { photo -> cameras.none { it.photo==photo } })
    }
}
