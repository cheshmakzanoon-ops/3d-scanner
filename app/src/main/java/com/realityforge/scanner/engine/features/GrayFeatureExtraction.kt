package com.realityforge.scanner.engine.features

import com.realityforge.scanner.engine.LensProjection
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.SIFT
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Resource admission is explicit, without silently changing quality profiles. */
data class ExtractorSettings(val maxFeatures: Int=3500,val analysisEdge: Int=1600) {
    init { require(maxFeatures in 32..20000 && analysisEdge in 32..4096) }
    fun identity(decoderIdentity: String): String {
        val decoderBytes=validatedFeatureText(decoderIdentity,4096)
        val value="rf-sift-v3|${RootSift.POLICY}\n$maxFeatures\n$analysisEdge\n${Core.VERSION}\n${decoderBytes.size}:$decoderIdentity"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}

enum class NativeFeatureCallEvent { STARTED, FINISHED }

/** Shared production SIFT policy after Android decoding/color conversion.
 * Borrows an upright CV_8UC1 image and a same-coordinate ARGB sampler for this call only.
 * Neither input is retained or released. The caller owns image admission, serialization and
 * pre-conversion cancellation. Only the returned frame owns a surviving native allocation.
 */
internal fun extractGrayFeatures(
    settings: ExtractorSettings, photoId:String, contentSha256:String, decoderIdentity:String,
    identity:String, gray:Mat, lens:LensProjection, argbAt:(Int,Int)->Int,
    cancelled:()->Boolean, observe:((NativeFeatureCallEvent)->Unit)?,
): FeatureFrame {
    fun checkCancelled(){if(cancelled())throw CancellationException("Feature extraction cancelled")}
    val width=gray.cols(); val height=gray.rows()
    require(gray.dims()==2 && gray.type()==CvType.CV_8UC1 &&
        width in 1..settings.analysisEdge && height in 1..settings.analysisEdge)
    // The adapter's previous check precedes image conversion. A cancellation
    // requested during that work must not start another noninterruptible kernel.
    checkCancelled()
    val resources=ArrayList<Mat>(3)
    var retained:Mat?=null
    var sift:SIFT?=null
    try {
        val keys=MatOfKeyPoint().also{resources.add(it)}
        val desc=Mat().also{resources.add(it)}
        val mask=Mat().also{resources.add(it)}
        sift=SIFT.create(settings.maxFeatures)
        observe?.invoke(NativeFeatureCallEvent.STARTED)
        var nativeFailure:Throwable?=null
        try { sift.detectAndCompute(gray,mask,keys,desc) }
        catch(failure:Throwable) { nativeFailure=failure; throw failure }
        finally {
            try { observe?.invoke(NativeFeatureCallEvent.FINISHED) }
            catch(observerFailure:Throwable) {
                val primary=nativeFailure
                if(primary==null)throw observerFailure
                if(observerFailure!==primary)primary.addSuppressed(observerFailure)
            }
        }
        checkCancelled()
        val all=keys.toArray(); val count=minOf(all.size,settings.maxFeatures)
        val xy=FloatArray(count*2); val colors=IntArray(count)
        for(i in 0 until count){
            if(i%128==0)checkCancelled()
            val p=all[i].pt; xy[2*i]=p.x.toFloat();xy[2*i+1]=p.y.toFloat()
            colors[i]=argbAt(p.x.toInt().coerceIn(0,width-1),p.y.toInt().coerceIn(0,height-1))
        }
        val owned=if(count==0)Mat() else desc.rowRange(0,count).useClone()
        retained=owned
        // Normalize once per extracted row, on our clone, never on borrowed pixels or keys.
        // OpenCV 4.12 float[] get/put return BYTES (Mat.cpp mat_copy_data), not elements.
        if(count>0) {
            require(owned.type()==CvType.CV_32F && owned.cols()==RootSift.DIMENSIONS && owned.rows()==count)
            val rawRow=FloatArray(RootSift.DIMENSIONS)
            val rootRow=FloatArray(RootSift.DIMENSIONS)
            for(i in 0 until count) {
                checkCancelled()
                require(owned.get(i,0,rawRow)==RootSift.ROW_BYTES) { "Incomplete SIFT row read" }
                RootSift.normalizeRow(rawRow,rootRow)
                require(owned.put(i,0,rootRow)==RootSift.ROW_BYTES) { "Incomplete RootSIFT row write" }
            }
        }
        checkCancelled()
        val frame=FeatureFrame(photoId,contentSha256,decoderIdentity,identity,width,height,xy,colors,owned,lens)
        retained=null
        return frame
    } finally {
        retained?.release(); sift?.clear()
        for(i in resources.lastIndex downTo 0)resources[i].release()
    }
}
private fun Mat.useClone():Mat=try{clone()}finally{release()}
