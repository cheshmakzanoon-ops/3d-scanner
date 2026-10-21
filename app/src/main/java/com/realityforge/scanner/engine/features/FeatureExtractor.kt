package com.realityforge.scanner.engine.features

import com.realityforge.scanner.engine.ImageLoader
import com.realityforge.scanner.engine.LensProjection
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.CancellationException

/** Exactly the baseline SIFT settings; one admitted decoded image per extractor.
 * Cancellation before/after noninterruptible detectAndCompute never publishes a frame.
 */
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
        val resources=ArrayList<Mat>(2)
        var bitmap:android.graphics.Bitmap?=null
        try {
            val decoded=ImageLoader.upright(file,settings.analysisEdge)
            bitmap=decoded
            val rgba=Mat().also{resources.add(it)}
            val gray=Mat().also{resources.add(it)}
            checkCancelled()
            val lens=projection(decoded.width,decoded.height)
            Utils.bitmapToMat(decoded,rgba)
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            // Explicitly release color conversion before the expensive descriptor kernel.
            rgba.release()
            return extractGrayFeatures(settings,photoId,contentSha256,decoderIdentity,identity,
                gray,lens,decoded::getPixel,cancelled,observe)
        } finally {
            for(i in resources.lastIndex downTo 0)resources[i].release()
            bitmap?.recycle()
        }
    }
}
