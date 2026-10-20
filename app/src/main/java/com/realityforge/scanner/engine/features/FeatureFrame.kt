package com.realityforge.scanner.engine.features

import com.realityforge.scanner.engine.LensProjection
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.Closeable

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
    private val coordinates=xy.copyOf()
    private val argb=colors.copyOf()
    private var closed=false
    val count: Int get()=argb.size
    val descriptorBytes: Long get()=count*128L*4
    init {
        require(photoId.isNotBlank() && contentSha256.matches(Regex("[a-f0-9]{64}")))
        require(decoderIdentity.isNotBlank() && settingsIdentity.isNotBlank())
        require(width>0 && height>0 && coordinates.size==count*2)
        require(coordinates.indices.all { i -> coordinates[i].isFinite() && coordinates[i]>=0 && coordinates[i] < if(i%2==0)width else height })
        require(if(count==0)ownedDescriptors.empty() else (ownedDescriptors.type()==CvType.CV_32F && ownedDescriptors.rows()==count && ownedDescriptors.cols()==128))
    }
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
