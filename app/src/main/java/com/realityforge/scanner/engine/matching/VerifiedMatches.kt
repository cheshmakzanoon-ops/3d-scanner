package com.realityforge.scanner.engine.matching

/** Stable source keypoint IDs; normalized rays are dimensionless and colours are original sRGB bytes. */
data class FeatureCorrespondence(val firstKey:Int,val secondKey:Int,val ax:Double,val ay:Double,val bx:Double,val by:Double,val argb:Int) {
    init {require(firstKey>=0 && secondKey>=0 && listOf(ax,ay,bx,by).all{it.isFinite() && kotlin.math.abs(it)<4})}
}
class TentativeMatches internal constructor(val firstPhotoId:String,val secondPhotoId:String,matches:List<FeatureCorrespondence>,val settingsIdentity:String) {
    val matches:List<FeatureCorrespondence> = java.util.Collections.unmodifiableList(ArrayList(matches))
    val count get()=matches.size
    init {
        require(firstPhotoId<secondPhotoId && settingsIdentity.isNotBlank())
        require(matches.map{it.firstKey}.distinct().size==count && matches.map{it.secondKey}.distinct().size==count)
    }
    fun packedRows()=DoubleArray(count*7){i->val m=matches[i/7];when(i%7){0->m.ax;1->m.ay;2->m.bx;3->m.by;4->(m.argb shr 16 and 255).toDouble();5->(m.argb shr 8 and 255).toDouble();else->(m.argb and 255).toDouble()}}
}
/** Only the native geometric verifier can create accepted observations, never a retrieval score. */
class VerifiedMatches internal constructor(val tentative:TentativeMatches,indices:IntArray,val thresholdNormalized:Double,val nativeResultJson:String) {
    private val selected=indices.copyOf()
    init {require(selected.isNotEmpty() && selected.distinct().size==selected.size && selected.all{it in tentative.matches.indices})}
    fun correspondences():List<FeatureCorrespondence> = selected.map{tentative.matches[it]}
    fun sourceMatchIndices()=selected.copyOf()
}
sealed class GeometricVerification {
    data class Accepted(val matches:VerifiedMatches):GeometricVerification()
    data class Rejected(val reason:String):GeometricVerification()
}
