package com.realityforge.scanner.engine.matching

/** Retrieval evidence only. An edge does not establish overlap, a pose or coverage. */
data class PairCandidate(val firstPhotoId:String,val secondPhotoId:String,val score:Double,val provenance:String) {
    init {require(firstPhotoId.isNotBlank() && firstPhotoId<secondPhotoId && score.isFinite() && score in 0.0..1.0 && provenance.isNotBlank())}
}
class PhotoFeatureSummary(val photoId:String,signature:FloatArray) {
    private val bins=signature.copyOf()
    val dimension get()=bins.size
    init {require(photoId.isNotBlank() && bins.size in 8..256 && bins.all{it.isFinite() && it>=0})}
    fun signature()=bins.copyOf()
    companion object {
        /** Streaming-compatible descriptor mean, never retains native feature buffers. */
        fun siftMean(photoId:String,descriptors:FloatArray):PhotoFeatureSummary {
            require(descriptors.size%128==0 && descriptors.size<=20000*128 && descriptors.all{it.isFinite() && it>=0 && it<=512})
            val bins=FloatArray(128)
            if(descriptors.isNotEmpty())for(i in descriptors.indices)bins[i%128]+=descriptors[i]/(descriptors.size/128)
            return PhotoFeatureSummary(photoId,bins)
        }
    }
}
