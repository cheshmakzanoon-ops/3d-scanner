package com.realityforge.scanner.engine

import com.realityforge.scanner.engine.tracks.TrackSnapshot
import com.realityforge.scanner.engine.tracks.digest
import java.util.Collections
import kotlin.math.*

/** Experimental point-only finalization, NOT a production admission increase or pose optimizer.
 * All windows use the same supplied world poses. Every candidate is checked against ALL
 * original observations of its stable global track, not just its proposing window. */
internal object BoundedSparseCoordinator {
    data class Budget(val maxCameras:Int=32, val maxTracks:Int=15000,
        val maxObservations:Int=100000, val maxWindows:Int=128,
        val maxWindowObservations:Int=100000, val maxPackedObservations:Int=200000, val maxCandidateObservationChecks:Int=2000000) {
        init { require(maxCameras in 2..32 && maxTracks in 1..15000 &&
            maxObservations in 2..1000000 && maxWindows in 1..1024 &&
            maxWindowObservations in 2..100000 && maxPackedObservations in 2..2000000 && maxCandidateObservationChecks in 2..20000000) }
    }
    data class Candidate(val sourceTrackId:String,val x:Double,val y:Double,val z:Double)
    data class Seed(val candidate:Candidate,val globalTrackIndex:Int,val observationCount:Int,
        val maxNormalizedError:Double,val maxPixelError:Double)
    data class Receipt(val seeds:List<Seed>,val missingTrackIds:List<String>,val invalidTrackIds:List<String>,
        val inputObservations:Int,val acceptedObservations:Int,val rejectedObservations:Int,
        val packedObservations:Int,val windowsExecuted:Int,val rejectedCandidates:Int,val unsupportedPhotoIds:List<String>)
    /** Arrays belong to this invocation only. A solver must keep poses fixed (iterations=0). */
    data class Window(val photoIds:List<String>,val globalTrackIds:List<String>,
        val cameraRows:DoubleArray,val normalizedRows:DoubleArray)
    private data class Camera(val id:String,val width:Int,val height:Int,val r:List<Double>,val t:List<Double>,val lens:LensProjection) {
        val center=List(3) { axis -> -(0..2).sumOf { r[it*3+axis]*t[it] } }
    }
    private data class Observation(val camera:Int,val key:Int,val px:Double,val py:Double,
        val nx:Double,val ny:Double,val rgb:Int)
    private data class Track(val id:String,val observations:List<Observation>)
    private data class Slice(val track:Int,val observations:List<Observation>)
    private data class Work(val cameras:List<Int>,val slices:List<Slice>)
    class Plan private constructor(private val cameras:List<Camera>,private val tracks:List<Track>,
        private val work:List<Work>,val inputObservations:Int,val packedObservations:Int) {
        val photoIds:List<String> = frozen(cameras.map { it.id })
        val sourceTrackIds:List<String> = frozen(tracks.map { it.id })
        val windowCount:Int get()=work.size
        /** Pure callback boundary: cancellation/solver errors propagate; no partial receipt is published. */
        fun execute(threshold:Double=.003,minParallaxRadians:Double=.001,
            checkpoint:()->Unit={}, solver:(Window)->List<Candidate>):Receipt {
            require(threshold.isFinite() && threshold>0 && threshold<=.025)
            require(minParallaxRadians.isFinite() && minParallaxRadians in .0001..0.1)
            val best=arrayOfNulls<Seed>(tracks.size);val proposed=BooleanArray(tracks.size)
            var rejected=0
            for(window in work) {
                checkpoint()
                val local=window.cameras.withIndex().associate { it.value to it.index }
                val poses=DoubleArray(window.cameras.size*12)
                for((localCamera,globalCamera) in window.cameras.withIndex()) {
                    checkpoint();val camera=cameras[globalCamera]
                    for(k in 0..8)poses[localCamera*12+k]=camera.r[k]
                    for(k in 0..2)poses[localCamera*12+9+k]=camera.t[k]
                }
                val rows=DoubleArray(window.slices.sumOf { it.observations.size }*7)
                var rowOffset=0
                for(slice in window.slices) {
                    checkpoint()
                    for(o in slice.observations) {
                        if((rowOffset/7)%128==0)checkpoint()
                        rows[rowOffset++]=slice.track.toDouble();rows[rowOffset++]=local.getValue(o.camera).toDouble()
                        rows[rowOffset++]=o.nx;rows[rowOffset++]=o.ny
                        rows[rowOffset++]=((o.rgb shr 16) and 255).toDouble()
                        rows[rowOffset++]=((o.rgb shr 8) and 255).toDouble();rows[rowOffset++]=(o.rgb and 255).toDouble()
                    }
                }
                val allowed=window.slices.associate { tracks[it.track].id to it.track }
                val candidates=solver(Window(frozen(window.cameras.map { cameras[it].id }),sourceTrackIds,poses,rows))
                require(candidates.size<=allowed.size) { "Too many window candidates" }
                val seen=HashSet<String>()
                for(candidate in candidates) {
                    checkpoint()
                    val index=requireNotNull(allowed[candidate.sourceTrackId]) { "Candidate has no original window track" }
                    require(seen.add(candidate.sourceTrackId)) { "Duplicate window candidate" }
                    proposed[index]=true
                    val checked=validate(candidate,index,threshold,minParallaxRadians,checkpoint)
                    if(checked==null) { rejected++;continue }
                    val previous=best[index]
                    if(previous==null || compareValuesBy(checked,previous,{it.maxNormalizedError},
                        {it.candidate.x},{it.candidate.y},{it.candidate.z})<0)best[index]=checked
                }
            }
            val accepted=best.filterNotNull();val acceptedCount=accepted.sumOf { it.observationCount }
            val supportedCameras=accepted.flatMap { tracks[it.globalTrackIndex].observations.map { o -> o.camera } }.toSet()
            return Receipt(frozen(accepted),frozen(tracks.indices.filter { !proposed[it] }.map { tracks[it].id }),
                frozen(tracks.indices.filter { proposed[it] && best[it]==null }.map { tracks[it].id }),
                inputObservations,acceptedCount,inputObservations-acceptedCount,packedObservations,work.size,rejected,frozen(cameras.indices.filter { it !in supportedCameras }.map { cameras[it].id }))
        }
        /** Global typed-BA packing is separate from point-only window execution. Original pixels
         * and all observations of accepted tracks are retained, never inferred from window rows.
         * Production adapter admission remains <=12; this experimental path is <=32. */
        fun bundleInput(receipt:Receipt, measuredCalibrationSha256:String?=null,
            threshold:Double=.003, checkpoint:()->Unit={}):TypedBundleAdapter.Input {
            checkpoint()
            require(cameras.size in 3..32 && receipt.seeds.size in 16..15000)
            require(threshold.isFinite() && threshold>0 && threshold<=.025)
            require(measuredCalibrationSha256==null || measuredCalibrationSha256.matches(Regex("[0-9a-f]{64}")))
            val seeds=receipt.seeds.sortedBy { it.globalTrackIndex }
            val acceptedIds=seeds.map { it.candidate.sourceTrackId }
            val partition=acceptedIds+receipt.missingTrackIds+receipt.invalidTrackIds
            require(partition.size==tracks.size && partition.distinct().size==tracks.size && partition.toSet()==sourceTrackIds.toSet()) { "Incomplete or duplicated receipt track partition" }
            require(receipt.inputObservations==inputObservations && receipt.packedObservations==packedObservations && receipt.windowsExecuted==work.size)
            require(receipt.rejectedCandidates in receipt.invalidTrackIds.size..work.sumOf { it.slices.size })
            require(seeds.map { it.globalTrackIndex }.distinct().size==seeds.size)
            require(seeds.all { it.globalTrackIndex in tracks.indices })
            val completeCount=seeds.sumOf { tracks[it.globalTrackIndex].observations.size }
            require(receipt.acceptedObservations==completeCount && receipt.rejectedObservations==inputObservations-completeCount)
            require(seeds.all { it.observationCount==tracks[it.globalTrackIndex].observations.size })
            require(completeCount in 32..100000) { "Global BA observation budget exceeded" }
            val supported=seeds.flatMap { tracks[it.globalTrackIndex].observations.map { o -> o.camera } }.toSet()
            require(receipt.unsupportedPhotoIds==cameras.indices.filter { it !in supported }.map { cameras[it].id })
            require(supported.size==cameras.size) { "Registered cameras lack accepted original observations" }
            // All accepted components must connect to the anchor gauge, not merely have support.
            val parent=IntArray(cameras.size) { it }
            fun root(c:Int):Int { var r=c;while(parent[r]!=r)r=parent[r];return r }
            for(seed in seeds) { checkpoint();val observations=tracks[seed.globalTrackIndex].observations
                for(o in observations)parent[root(o.camera)]=root(observations.first().camera) }
            require(cameras.indices.all { root(it)==root(0) }) { "Disconnected accepted observation graph" }
            val baseline=sqrt((0..2).sumOf { (cameras[0].center[it]-cameras[1].center[it]).let { d -> d*d } })
            require(baseline.isFinite() && baseline>1e-8) { "Pose-and-baseline gauge requires a nonzero anchor baseline" }
            val seedRows=DoubleArray(seeds.size*7);val pixelRows=DoubleArray(completeCount*7)
            var seedOffset=0;var pixelOffset=0
            for(seed in seeds) {
                checkpoint()
                val index=seed.globalTrackIndex
                require(index in tracks.indices && seed.candidate.sourceTrackId==tracks[index].id)
                require(validate(seed.candidate,index,threshold,.001,checkpoint)==seed) { "Invalid global seed or altered validation evidence" }
                val rgb=tracks[index].observations.first().rgb
                seedRows[seedOffset++]=index.toDouble();seedRows[seedOffset++]=seed.candidate.x
                seedRows[seedOffset++]=seed.candidate.y;seedRows[seedOffset++]=seed.candidate.z
                seedRows[seedOffset++]=((rgb shr 16) and 255).toDouble()
                seedRows[seedOffset++]=((rgb shr 8) and 255).toDouble();seedRows[seedOffset++]=(rgb and 255).toDouble()
                for(o in tracks[index].observations) {
                    checkpoint()
                    pixelRows[pixelOffset++]=index.toDouble();pixelRows[pixelOffset++]=o.camera.toDouble()
                    pixelRows[pixelOffset++]=o.px;pixelRows[pixelOffset++]=o.py
                    pixelRows[pixelOffset++]=((o.rgb shr 16) and 255).toDouble()
                    pixelRows[pixelOffset++]=((o.rgb shr 8) and 255).toDouble();pixelRows[pixelOffset++]=(o.rgb and 255).toDouble()
                }
            }
            val models=cameras.map { c -> val p=c.lens
                listOf(c.width.toDouble(),c.height.toDouble(),p.fx,p.fy,p.cx,p.cy,p.k1,p.k2,p.p1,p.p2,p.k3) }
            val groups=cameras.indices.map { camera ->
                val lens=measuredCalibrationSha256?.let { "measured-project:$it" } ?: "unknown-estimated:${cameras[camera].id}"
                digest(("typed-bundle-fixed-v1|$lens|"+models[camera].joinToString(":") { java.lang.Double.toHexString(it) }).toByteArray(Charsets.UTF_8))
            }
            return TypedBundleAdapter.Input(cameras.flatMap { it.r+it.t }.toDoubleArray(),seedRows,
                pixelRows,models.flatten().toDoubleArray(),groups.toTypedArray(),photoIds.toTypedArray(),
                sourceTrackIds.toTypedArray(),measuredCalibrationSha256!=null,tracks.map { t -> t.observations.map { it.camera }.toSet() })
        }
        private fun validate(p:Candidate,index:Int,threshold:Double,minAngle:Double,checkpoint:()->Unit):Seed? {
            if(!listOf(p.x,p.y,p.z).all { it.isFinite() && abs(it)<1e9 })return null
            var maxError=0.0;var maxPixels=0.0
            val rays=ArrayList<List<Double>>()
            for(o in tracks[index].observations) {
                checkpoint()
                val camera=cameras[o.camera];val xyz=listOf(p.x,p.y,p.z)
                val q=List(3) { axis -> (0..2).sumOf { camera.r[axis*3+it]*xyz[it] }+camera.t[axis] }
                if(q[2]<=1e-8)return null
                val x=q[0]/q[2];val y=q[1]/q[2]
                val error=hypot(x-o.nx,y-o.ny)
                if(!error.isFinite() || error>threshold)return null
                val distorted=try { camera.lens.distort(x,y) } catch(_:IllegalArgumentException) { return null }
                val pixel=hypot(distorted.first*camera.lens.fx+camera.lens.cx-o.px,
                    distorted.second*camera.lens.fy+camera.lens.cy-o.py)
                if(!pixel.isFinite())return null
                maxError=max(maxError,error);maxPixels=max(maxPixels,pixel)
                val ray=List(3) { xyz[it]-camera.center[it] };val length=sqrt(ray.sumOf { it*it })
                if(!length.isFinite() || length<=1e-8)return null
                rays.add(ray.map { it/length })
            }
            // No quadratic all-pairs scan: any ray differing sufficiently from the first
            // establishes a conservative positive triangulation angle.
            val first=rays.first()
            if(rays.drop(1).none { ray -> abs((0..2).sumOf { first[it]*ray[it] }) < cos(minAngle) })return null
            return Seed(p,index,tracks[index].observations.size,maxError,maxPixels)
        }
        companion object {
            fun create(photoIds:List<String>,views:List<SparseView>,snapshot:TrackSnapshot,budget:Budget,anchorPhotoIds:List<String>,checkpoint:()->Unit):Plan {
                checkpoint()
                require(photoIds.size==views.size && views.size in 2..budget.maxCameras)
                require(photoIds.all { it.isNotBlank() && '\u0000' !in it && it.toByteArray().size<=1024 } && photoIds.distinct().size==photoIds.size)
                require(snapshot.tracks.size in 1..budget.maxTracks)
                val count=snapshot.tracks.sumOf { it.observations.size }
                require(count<=budget.maxObservations) { "Original observation budget exceeded" }
                require(anchorPhotoIds.size==2 && anchorPhotoIds.distinct().size==2 && anchorPhotoIds.all { it in photoIds })
                val order=anchorPhotoIds.map { photoIds.indexOf(it) } + photoIds.indices.filter { photoIds[it] !in anchorPhotoIds }.sortedBy { photoIds[it] }
                val index=order.withIndex().associate { photoIds[it.value] to it.index }
                val cameras=order.map { k -> checkpoint();val v=views[k]
                    // Revalidate mutable input arrays, then snapshot only used geometry/features.
                    val checked=v.copy(xy=FloatArray(0),colors=IntArray(0))
                    Camera(photoIds[k],checked.width,checked.height,checked.rotation.toList(),checked.translation.toList(),
                        checked.projection ?: LensProjection(checked.focalPixels,checked.focalPixels,(checked.width-1)/2.0,(checked.height-1)/2.0)) }
                val tracks=snapshot.tracks.sortedBy { it.id }.map { track -> checkpoint();Track(track.id,
                    track.observations.map { source ->
                        checkpoint()
                        val camera=requireNotNull(index[source.photo]) { "Unregistered source photograph" }
                        val view=views[order[camera]];val key=source.keypoint
                        require(key in view.colors.indices)
                        val px=view.xy[key*2].toDouble();val py=view.xy[key*2+1].toDouble()
                        require(px in -.5..(view.width-.5) && py in -.5..(view.height-.5))
                        val ray=cameras[camera].lens.normalized(px,py)
                        Observation(camera,key,px,py,ray.first,ray.second,view.colors[key])
                    }.sortedBy { it.camera }) }
                // Production <=12 uses one shared camera window for every source track;
                // heterogeneous visibility must not explode into per-track windows.
                val sharedWindow=if(cameras.size<=12) {
                    val first=0
                    val farthest=(1 until cameras.size).maxBy { c -> (0..2).sumOf { axis ->
                        (cameras[first].center[axis]-cameras[c].center[axis]).let { it*it } } }
                    listOf(first,farthest)+cameras.indices.filter { it!=first && it!=farthest }
                } else null
                val windows=linkedMapOf<List<Int>,MutableList<Slice>>()
                var packed=0
                var candidateChecks=0L
                val windowCounts=HashMap<List<Int>,Int>()
                for((trackId,track) in tracks.withIndex()) {
                    checkpoint()
                    val original=track.observations
                    val first=original.first()
                    fun distance(o:Observation)=(0..2).sumOf { axis -> (cameras[first.camera].center[axis]-cameras[o.camera].center[axis]).let { it*it } }
                    val farthest=original.drop(1).maxBy { distance(it) }
                    require(distance(farthest)>=1e-12) { "Track has no noncoincident triangulation anchors" }
                    val observations=listOf(first,farthest)+original.filter { it!=first && it!=farthest }
                    // Two fixed source observations plus <=10 new ones, including the tail.
                    val chunks=if(observations.size<=12)listOf(observations) else
                        observations.drop(2).chunked(10).map { observations.take(2)+it }
                    for(chunk in chunks) {
                        checkpoint()
                        val key=sharedWindow ?: chunk.map { it.camera }
                        if(key !in windows)require(windows.size<budget.maxWindows) { "Window budget exceeded; no observations truncated" }
                        val next=(windowCounts[key]?:0)+chunk.size
                        require(next<=budget.maxWindowObservations) { "Per-window observation budget exceeded" }
                        require(packed.toLong()+chunk.size<=budget.maxPackedObservations) { "Packed observation budget exceeded" }
                        candidateChecks+=observations.size
                        require(candidateChecks<=budget.maxCandidateObservationChecks) { "Candidate validation budget exceeded" }
                        windows.getOrPut(key) { arrayListOf() }.add(Slice(trackId,chunk))
                        windowCounts[key]=next;packed+=chunk.size
                    }
                }
                return Plan(cameras,tracks,windows.map { Work(it.key,it.value.toList()) },count,packed)
            }
        }
    }
    fun prepare(photoIds:List<String>,views:List<SparseView>,snapshot:TrackSnapshot,budget:Budget=Budget(),
        anchorPhotoIds:List<String> = photoIds.take(2),checkpoint:()->Unit={}):Plan =
        Plan.create(photoIds,views,snapshot,budget,anchorPhotoIds,checkpoint)
    private fun <T> frozen(values:List<T>):List<T> = Collections.unmodifiableList(values.toList())
}
