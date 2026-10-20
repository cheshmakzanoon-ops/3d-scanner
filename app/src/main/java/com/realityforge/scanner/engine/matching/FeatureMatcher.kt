package com.realityforge.scanner.engine.matching

import com.realityforge.scanner.engine.NativeCore
import com.realityforge.scanner.engine.features.FeatureFrame
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.features2d.BFMatcher
import java.util.concurrent.CancellationException

/** Exact reciprocal SIFT ratio test. Chunking bounds kNN native output buffers, not quality.
 * FeatureFrames are borrowed under their ownership locks in canonical photo-ID order.
 */
class FeatureMatcher(val ratio:Double=.67,val blockSize:Int=256) {
    init {require(ratio.isFinite() && ratio in .1.. .95 && blockSize in 1..1024)}
    val settingsIdentity get()="rf-bf-l2-reciprocal-v2:ratio=$ratio:opencv=${Core.VERSION}"
    fun match(first:FeatureFrame,second:FeatureFrame,candidate:PairCandidate,cancelled:()->Boolean={false}):TentativeMatches {
        require(first.photoId==candidate.firstPhotoId && second.photoId==candidate.secondPhotoId)
        fun checkCancelled(){if(cancelled())throw CancellationException("Feature matching cancelled")}
        checkCancelled()
        if(first.count<2 || second.count<2)return TentativeMatches(first.photoId,second.photoId,emptyList(),settingsIdentity)
        return first.withDescriptors{a->second.withDescriptors{b->
            validate(a,first.count);validate(b,second.count)
            var matcher:BFMatcher?=null
            val reverse=IntArray(second.count){-1};val out=ArrayList<FeatureCorrespondence>()
            val colors=first.colors()
            fun eachAccepted(query:Mat,train:Mat,accept:(Int,Int)->Unit) {
                for(start in 0 until query.rows() step blockSize) {
                    checkCancelled()
                    val end=minOf(start+blockSize,query.rows())
                    val rows=ArrayList<MatOfDMatch>()
                    var part:Mat?=null
                    try {
                        val queryBlock=query.rowRange(start,end)
                        part=queryBlock
                        requireNotNull(matcher).knnMatch(queryBlock,train,rows,2);checkCancelled()
                        for(row in rows){
                            val pair=row.toArray()
                            if(pair.size<2)continue
                            val x=pair[0];val y=pair[1]
                            require(x.queryIdx in 0 until end-start && x.trainIdx in 0 until train.rows())
                            if(x.distance.isFinite() && y.distance.isFinite() && x.distance>=0 && x.distance<ratio*y.distance)accept(start+x.queryIdx,x.trainIdx)
                        }
                    }finally{rows.forEach{it.release()};part?.release()}
                }
            }
            try {
                matcher=BFMatcher.create(Core.NORM_L2,false)
                eachAccepted(b,a){j,i->reverse[j]=i}
                eachAccepted(a,b){i,j->if(reverse[j]==i){
                    val ar=runCatching{first.normalized(i)}.getOrNull()
                    val br=runCatching{second.normalized(j)}.getOrNull()
                    if(ar!=null && br!=null)out.add(FeatureCorrespondence(i,j,ar.first,ar.second,br.first,br.second,colors[i]))
                }}
                checkCancelled()
                TentativeMatches(first.photoId,second.photoId,out,settingsIdentity)
            }finally{matcher?.clear()}
        }}
    }
    fun verify(tentative:TentativeMatches,thresholdNormalized:Double,cancelled:()->Boolean={false}):GeometricVerification {
        require(thresholdNormalized.isFinite() && thresholdNormalized>0 && thresholdNormalized<=.05)
        if(cancelled())throw CancellationException("Geometric verification cancelled")
        if(tentative.count<20)return GeometricVerification.Rejected("Fewer than 20 reciprocal SIFT matches")
        val raw=NativeCore.reconstruct(tentative.packedRows(),thresholdNormalized)
        if(cancelled())throw CancellationException("Geometric verification cancelled")
        val result=JSONObject(raw)
        if(result.has("error"))return GeometricVerification.Rejected(result.getString("error").take(512))
        require(result.getString("representation")=="sparse_points" && result.getInt("registeredCameras")==2)
        val points=result.getJSONArray("points");require(points.length()==result.getInt("pointCount") && points.length()>0)
        val indices=IntArray(points.length()){i->
            val row=points.getJSONArray(i);require(row.length()>=9)
            val value=row.getDouble(8);require(value.isFinite() && value==value.toInt().toDouble())
            for(j in 0 until 9)require(row.getDouble(j).isFinite())
            value.toInt()
        }
        return GeometricVerification.Accepted(VerifiedMatches(tentative,indices,thresholdNormalized,raw))
    }
    private fun validate(mat:Mat,count:Int) {
        require(count in 2..20000 && mat.rows()==count && mat.cols()==128 && mat.type()==CvType.CV_32F)
        require(Core.checkRange(mat,true,0.0,513.0)) { "Nonfinite or invalid SIFT descriptors" }
    }
}
