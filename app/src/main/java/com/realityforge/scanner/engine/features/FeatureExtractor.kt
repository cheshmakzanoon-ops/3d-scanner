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
        require(decoderIdentity.isNotBlank())
        val value="rf-sift-v2\n$maxFeatures\n$analysisEdge\n${Core.VERSION}\n${decoderIdentity.length}:$decoderIdentity"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}

/** Exactly the baseline SIFT settings; one admitted decoded image per extractor.
 * Cancellation before/after noninterruptible detectAndCompute never publishes a frame.
 */
class FeatureExtractor(val settings: ExtractorSettings=ExtractorSettings()) {
    @Synchronized fun extract(
        photoId:String,contentSha256:String,file:File,decoderIdentity:String,
        projection:(Int,Int)->LensProjection,cancelled:()->Boolean={false},
    ): FeatureFrame {
        fun checkCancelled(){if(cancelled())throw CancellationException("Feature extraction cancelled")}
        require(photoId.isNotBlank() && contentSha256.matches(Regex("[a-f0-9]{64}")))
        val identity=settings.identity(decoderIdentity)
        checkCancelled()
        val bitmap=ImageLoader.upright(file,settings.analysisEdge)
        val rgba=Mat(); val gray=Mat(); val keys=MatOfKeyPoint(); val desc=Mat(); val mask=Mat()
        var retained:Mat?=null
        var sift:SIFT?=null
        try {
            checkCancelled()
            val lens=projection(bitmap.width,bitmap.height)
            Utils.bitmapToMat(bitmap,rgba)
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            // Explicitly release color conversion before the expensive descriptor kernel.
            rgba.release()
            sift=SIFT.create(settings.maxFeatures)
            sift.detectAndCompute(gray,mask,keys,desc)
            checkCancelled()
            val all=keys.toArray(); val count=minOf(all.size,settings.maxFeatures)
            val xy=FloatArray(count*2); val colors=IntArray(count)
            for(i in 0 until count){
                if(i%128==0)checkCancelled()
                val p=all[i].pt; xy[2*i]=p.x.toFloat();xy[2*i+1]=p.y.toFloat()
                colors[i]=bitmap.getPixel(p.x.toInt().coerceIn(0,bitmap.width-1),p.y.toInt().coerceIn(0,bitmap.height-1))
            }
            val owned=if(count==0)Mat() else desc.rowRange(0,count).useClone()
            retained=owned
            checkCancelled()
            val frame=FeatureFrame(photoId,contentSha256,decoderIdentity,identity,bitmap.width,bitmap.height,xy,colors,owned,lens)
            retained=null
            return frame
        } finally {
            retained?.release(); sift?.clear(); rgba.release();gray.release();keys.release();desc.release();mask.release();bitmap.recycle()
        }
    }
    private fun Mat.useClone():Mat=try{clone()}finally{release()}
}
