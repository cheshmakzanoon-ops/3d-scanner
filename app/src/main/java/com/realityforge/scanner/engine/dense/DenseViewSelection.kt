package com.realityforge.scanner.engine.dense

/** Verified sparse support is candidate overlap; this never reports dense completeness. */
object DenseViewSelection {
    class Camera(val photoId:String, centre:List<Double>, tracks:Set<String>, val sharpness:Double, val textureAvailable:Boolean=true) {
        init { require(photoId.isNotBlank() && photoId.length<=256 && centre.size==3 && tracks.size<=20_000) }
        val centre:List<Double> = java.util.Collections.unmodifiableList(ArrayList(centre))
        val tracks:Set<String> = java.util.Collections.unmodifiableSet(HashSet(tracks))
    }
    class Plan(referencePhotoIds:List<String>, sourcePhotoIds:Map<String,List<String>>, exclusions:Map<String,String>, val uniqueSparseTracks:Int) {
        init {
            require(referencePhotoIds.size<=8 && referencePhotoIds.distinct().size==referencePhotoIds.size && exclusions.size<=250 && uniqueSparseTracks in 0..500_000)
            require(sourcePhotoIds.keys==referencePhotoIds.toSet())
            require(sourcePhotoIds.all { (ref,sources) -> sources.size<=3 && sources.distinct().size==sources.size && sources.all { it!=ref && it in referencePhotoIds } })
        }
        val referencePhotoIds:List<String> = java.util.Collections.unmodifiableList(ArrayList(referencePhotoIds))
        val sourcePhotoIds:Map<String,List<String>> = java.util.Collections.unmodifiableMap(sourcePhotoIds.mapValues { java.util.Collections.unmodifiableList(ArrayList(it.value)) })
        val exclusions:Map<String,String> = java.util.Collections.unmodifiableMap(HashMap(exclusions))
        override fun equals(other:Any?) = other is Plan && referencePhotoIds==other.referencePhotoIds && sourcePhotoIds==other.sourcePhotoIds && exclusions==other.exclusions && uniqueSparseTracks==other.uniqueSparseTracks
        override fun hashCode() = listOf(referencePhotoIds,sourcePhotoIds,exclusions,uniqueSparseTracks).hashCode()
    }
    /** Adapter from036 immutable native-window rows and actual040/041 retained support.
     * Native output supplies acceptedSupportByTrack; descriptor summaries are never valid input here.
     */
    fun fromVerifiedNativeTracks(photoIds:List<String>, worldToCamera:List<DoubleArray>, sourceTrackIds:List<String>,
                                 verifiedRows:DoubleArray, acceptedSupportByTrack:Map<Int,Set<Int>>,
                                 measuredSharpness:List<Double>, textureAvailable:List<Boolean>):List<Camera> {
        require(photoIds.size in 2..250 && photoIds.distinct().size==photoIds.size)
        require(worldToCamera.size==photoIds.size && measuredSharpness.size==photoIds.size && textureAvailable.size==photoIds.size)
        require(sourceTrackIds.size<=15_000 && sourceTrackIds.distinct().size==sourceTrackIds.size && sourceTrackIds.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(verifiedRows.size%7==0 && verifiedRows.size/7<=100_000 && verifiedRows.all(Double::isFinite))
        require(acceptedSupportByTrack.size<=sourceTrackIds.size)
        val graphSupport=HashMap<Int,MutableSet<Int>>()
        for(i in verifiedRows.indices step 7){val t=verifiedRows[i];val c=verifiedRows[i+1]
            require(t%1.0==0.0 && t>=0 && t<sourceTrackIds.size && c%1.0==0.0 && c>=0 && c<photoIds.size)
            require(kotlin.math.abs(verifiedRows[i+2])<=10 && kotlin.math.abs(verifiedRows[i+3])<=10 && (4..6).all { verifiedRows[i+it] in 0.0..255.0 })
            require(graphSupport.getOrPut(t.toInt()){HashSet()}.add(c.toInt())) { "Duplicate source camera observation" }
        }
        val tracks=List(photoIds.size){HashSet<String>()}
        for((track,support) in acceptedSupportByTrack){
            require(track in sourceTrackIds.indices && support.size>=2 && support.all {it in photoIds.indices})
            require(graphSupport[track]?.containsAll(support)==true) { "Native support is not in the verified observation snapshot" }
            for(camera in support)tracks[camera].add(sourceTrackIds[track])
        }
        require(measuredSharpness.all {it.isFinite() && it>=0})
        val maximum=measuredSharpness.maxOrNull() ?: 0.0
        return photoIds.indices.map { index ->
            val pose=worldToCamera[index];require(pose.size==12 && pose.all(Double::isFinite))
            val determinant=pose[0]*(pose[4]*pose[8]-pose[5]*pose[7])-pose[1]*(pose[3]*pose[8]-pose[5]*pose[6])+pose[2]*(pose[3]*pose[7]-pose[4]*pose[6])
            require(kotlin.math.abs(determinant-1.0)<.02)
            for(a in 0..2)for(b in 0..2)require(kotlin.math.abs((0..2).sumOf { pose[a*3+it]*pose[b*3+it] }-(if(a==b)1.0 else 0.0))<.025)
            val centre=(0..2).map { k -> -(0..2).sumOf { row -> pose[row*3+k]*pose[9+row] } }
            Camera(photoIds[index],centre,tracks[index],if(maximum>0)measuredSharpness[index]/maximum else 0.0,textureAvailable[index])
        }
    }
    fun select(cameras:List<Camera>, referenceCap:Int=8, sourceCap:Int=3, minimumCommonTracks:Int=8, cancelled:()->Boolean={false}):Plan {
        require(cameras.size<=250 && referenceCap in 2..8 && sourceCap in 1..3 && minimumCommonTracks>=2)
        require(cameras.map{it.photoId}.distinct().size==cameras.size)
        require(cameras.sumOf{it.tracks.size.toLong()}<=500_000)
        cameras.forEach { require(it.photoId.isNotBlank() && it.centre.size==3 && it.centre.all(Double::isFinite))
            require(it.tracks.size<=20_000 && it.tracks.all{t->t.matches(Regex("[0-9a-f]{64}"))} && it.sharpness.isFinite() && it.sharpness in 0.0..1.0) }
        val sorted=cameras.sortedBy{it.photoId}
        val neighbours=sorted.associate { a -> a.photoId to sorted.filter { b ->
            if(cancelled())throw java.util.concurrent.CancellationException("Dense selection cancelled")
            a.photoId!=b.photoId && a.textureAvailable && b.textureAvailable &&
                (0..2).sumOf{k->(a.centre[k]-b.centre[k])*(a.centre[k]-b.centre[k])}>1e-16 &&
                a.tracks.count{it in b.tracks}>=minimumCommonTracks
        }.sortedWith(compareByDescending<Camera>{b->a.tracks.count{it in b.tracks}*(1+.25*minOf(a.sharpness,b.sharpness))}.thenBy{it.photoId}) }
        val covered=HashSet<String>();val references=ArrayList<Camera>();val sources=linkedMapOf<String,List<String>>()
        while(references.size<referenceCap){
            if(cancelled())throw java.util.concurrent.CancellationException("Dense selection cancelled")
            val best=sorted.filter{it !in references && neighbours.getValue(it.photoId).isNotEmpty() && (references.isEmpty() || neighbours.getValue(it.photoId).any { n -> n in references })}
                .maxWithOrNull(compareBy<Camera>{c->c.tracks.count{it !in covered}+.1*c.tracks.size+c.sharpness}.thenByDescending{it.photoId}) ?: break
            references+=best;covered+=best.tracks;sources[best.photoId]=neighbours.getValue(best.photoId).take(sourceCap).map{it.photoId}
        }
        for (ref in references) sources[ref.photoId]=neighbours.getValue(ref.photoId).filter { it in references }.take(sourceCap).map { it.photoId }
        val excluded=sorted.filter{it !in references}.associate{it.photoId to when {
            !it.textureAvailable -> "texture_unavailable"
            neighbours.getValue(it.photoId).isEmpty() -> "insufficient_verified_overlap_or_baseline"
            neighbours.getValue(it.photoId).none { n -> n in references } -> "disconnected_from_selected_component"
            else -> "reference_budget"
        }}
        return Plan(references.map{it.photoId},sources,excluded,covered.size)
    }
}
