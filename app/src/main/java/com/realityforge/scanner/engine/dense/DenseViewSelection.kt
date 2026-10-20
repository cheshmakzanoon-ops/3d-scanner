package com.realityforge.scanner.engine.dense

/** Verified sparse support is candidate overlap; this never reports dense completeness. */
object DenseViewSelection {
    class Camera(val photoId:String, centre:List<Double>, tracks:Set<String>, val sharpness:Double, val textureAvailable:Boolean=true) {
        val centre:List<Double> = java.util.Collections.unmodifiableList(ArrayList(centre))
        val tracks:Set<String> = java.util.Collections.unmodifiableSet(HashSet(tracks))
    }
    class Plan(referencePhotoIds:List<String>, sourcePhotoIds:Map<String,List<String>>, exclusions:Map<String,String>, val uniqueSparseTracks:Int) {
        val referencePhotoIds:List<String> = java.util.Collections.unmodifiableList(ArrayList(referencePhotoIds))
        val sourcePhotoIds:Map<String,List<String>> = java.util.Collections.unmodifiableMap(sourcePhotoIds.mapValues { java.util.Collections.unmodifiableList(ArrayList(it.value)) })
        val exclusions:Map<String,String> = java.util.Collections.unmodifiableMap(HashMap(exclusions))
        override fun equals(other:Any?) = other is Plan && referencePhotoIds==other.referencePhotoIds && sourcePhotoIds==other.sourcePhotoIds && exclusions==other.exclusions && uniqueSparseTracks==other.uniqueSparseTracks
        override fun hashCode() = listOf(referencePhotoIds,sourcePhotoIds,exclusions,uniqueSparseTracks).hashCode()
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
