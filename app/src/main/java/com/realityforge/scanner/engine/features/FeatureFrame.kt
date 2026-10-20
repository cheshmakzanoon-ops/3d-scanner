package com.realityforge.scanner.engine.features

import com.realityforge.scanner.engine.LensProjection
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.Closeable

/** Reject unbounded metadata before encoding; UTF-8 replacement must never alias identities. */
internal fun validatedFeatureText(value:String,maxBytes:Int):ByteArray {
    require(value.length in 1..maxBytes && value.isNotBlank()) { "Invalid feature metadata length" }
    val bytes=value.toByteArray(Charsets.UTF_8)
    require(bytes.size<=maxBytes && bytes.toString(Charsets.UTF_8)==value) { "Invalid feature metadata UTF-8" }
    return bytes
}

/** A single owned SIFT descriptor allocation. close() invalidates all descriptor access.
 * Keypoint identity is its immutable row number in this exact frame/settings identity.
 * Callers may retain small metadata; a collection must not retain every open frame.
 */
class FeatureFrame internal constructor(
    val photoId: String, val contentSha256: String, val decoderIdentity: String,
    val settingsIdentity: String, val width: Int, val height: Int,
    xy: FloatArray, colors: IntArray, private val ownedDescriptors: Mat,
    val projection: LensProjection,
) : Closeable {
    init {
        validatedFeatureText(photoId,512)
        require(contentSha256.matches(Regex("[a-f0-9]{64}")))
        validatedFeatureText(decoderIdentity,4096);validatedFeatureText(settingsIdentity,4096)
        // Validate bounds before defensive copies or any native descriptor inspection.
        require(width in 3..4096 && height in 3..4096 && colors.size in 0..20000 && xy.size==colors.size*2)
        require(xy.indices.all { i -> xy[i].isFinite() && xy[i]>=0 && xy[i] < if(i%2==0)width else height })
        require(if(colors.isEmpty())ownedDescriptors.empty() else (ownedDescriptors.type()==CvType.CV_32F && ownedDescriptors.rows()==colors.size && ownedDescriptors.cols()==128))
    }
    private val coordinates=xy.copyOf()
    private val argb=colors.copyOf()
    private var closed=false
    val count: Int get()=argb.size
    val descriptorBytes: Long get()=count*128L*4
    fun coordinates()=coordinates.copyOf()
    fun colors()=argb.copyOf()
    fun normalized(index: Int): Pair<Double,Double> {
        require(index in 0 until count)
        return projection.normalized(coordinates[index*2].toDouble(),coordinates[index*2+1].toDouble())
    }
    @Synchronized fun <T> withDescriptors(block:(Mat)->T): T {
        check(!closed) { "FeatureFrame is closed" }
        return block(ownedDescriptors)
    }
    @Synchronized override fun close() { if(!closed){closed=true;ownedDescriptors.release()} }
}
