package com.realityforge.scanner.engine.features

import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.engine.LensProjection
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Resource admission is explicit, without silently changing quality profiles. */
data class ExtractorSettings(val maxFeatures: Int=3500,val analysisEdge: Int=1600) {
    init { require(maxFeatures in 32..20000 && analysisEdge in 32..4096) }
    fun identity(decoderIdentity: String): String {
        val decoderBytes=validatedFeatureText(decoderIdentity,4096)
        val value="rf-sift-v2\n$maxFeatures\n$analysisEdge\n${Core.VERSION}\n${decoderBytes.size}:$decoderIdentity"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}

/** Exactly the baseline SIFT settings; one admitted decoded image per extractor.
 * Cancellation before/after noninterruptible detectAndCompute never publishes a frame.
 */
enum class NativeFeatureCallEvent { STARTED, FINISHED }

class FeatureExtractor(val settings: ExtractorSettings=ExtractorSettings()) {
    @Synchronized fun extract(
        photoId:String,contentSha256:String,file:File,decoderIdentity:String,
        projection:(Int,Int)->LensProjection,cancelled:()->Boolean={false},
    ): FeatureFrame = extractInternal(photoId,contentSha256,file,decoderIdentity,projection,cancelled,null)

    /** Observability only: callbacks bracket the actual noninterruptible OpenCV kernel.
     * Cancellation requested inside this interval is observed after return and resource cleanup.
     * Existing extract() argument/trailing-lambda semantics remain unchanged.
     */
    @Synchronized fun extractObserved(
        photoId:String,contentSha256:String,file:File,decoderIdentity:String,
        projection:(Int,Int)->LensProjection,cancelled:()->Boolean,
        observe:(NativeFeatureCallEvent)->Unit,
    ): FeatureFrame = extractInternal(photoId,contentSha256,file,decoderIdentity,projection,cancelled,observe)

    private fun extractInternal(
        photoId:String,contentSha256:String,file:File,decoderIdentity:String,
        projection:(Int,Int)->LensProjection,cancelled:()->Boolean,
        observe:((NativeFeatureCallEvent)->Unit)?,
    ): FeatureFrame {
        fun checkCancelled(){if(cancelled())throw CancellationException("Feature extraction cancelled")}
        validatedFeatureText(photoId,512)
        require(contentSha256.matches(Regex("[a-f0-9]{64}")))
        val identity=settings.identity(decoderIdentity)
        checkCancelled()
        // Allocate the bounded ownership ledger before acquiring any native/image resource.
        val resources=ArrayList<Mat>(5)
        var bitmap:android.graphics.Bitmap?=null
        var retained:Mat?=null
        var sift:SIFT?=null
        try {
            val decoded=ImageLoader.upright(file,settings.analysisEdge)
            bitmap=decoded
            val rgba=Mat().also{resources.add(it)}
            val gray=Mat().also{resources.add(it)}
            val keys=MatOfKeyPoint().also{resources.add(it)}
            val desc=Mat().also{resources.add(it)}
            val mask=Mat().also{resources.add(it)}
            checkCancelled()
            val lens=projection(decoded.width,decoded.height)
            Utils.bitmapToMat(decoded,rgba)
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            // Explicitly release color conversion before the expensive descriptor kernel.
            rgba.release()
            sift=SIFT.create(settings.maxFeatures)
            observe?.invoke(NativeFeatureCallEvent.STARTED)
            try { sift.detectAndCompute(gray,mask,keys,desc) }
            finally { observe?.invoke(NativeFeatureCallEvent.FINISHED) }
            checkCancelled()
            val all=keys.toArray(); val count=minOf(all.size,settings.maxFeatures)
            val xy=FloatArray(count*2); val colors=IntArray(count)
            for(i in 0 until count){
                if(i%128==0)checkCancelled()
                val p=all[i].pt; xy[2*i]=p.x.toFloat();xy[2*i+1]=p.y.toFloat()
                colors[i]=decoded.getPixel(p.x.toInt().coerceIn(0,decoded.width-1),p.y.toInt().coerceIn(0,decoded.height-1))
            }
            val owned=if(count==0)Mat() else desc.rowRange(0,count).useClone()
            retained=owned
            checkCancelled()
            val frame=FeatureFrame(photoId,contentSha256,decoderIdentity,identity,decoded.width,decoded.height,xy,colors,owned,lens)
            retained=null
            return frame
        } finally {
            retained?.release(); sift?.clear()
            for(i in resources.lastIndex downTo 0)resources[i].release()
            bitmap?.recycle()
        }
    }
    private fun Mat.useClone():Mat=try{clone()}finally{release()}
}
