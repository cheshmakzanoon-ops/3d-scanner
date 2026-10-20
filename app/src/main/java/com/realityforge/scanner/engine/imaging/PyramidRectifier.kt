package com.realityforge.scanner.engine.imaging

import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.geometry.PixelTransform
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.roundToInt

/** Bilinear integer-centre reference; invalid borders stay transparent and masked. */
object PyramidRectifier {
    /** Immutable snapshot; copy-out accessors transfer independent bounded buffers to consumers. */
    class Tile internal constructor(val firstRow: Int, val rows: Int, pixels: IntArray, validity: ByteArray) {
        private val data=pixels
        private val mask=validity
        val pixels:IntArray get()=data.copyOf()
        val validity:ByteArray get()=mask.copyOf()
    }
    fun tiles(source: IntArray, sourceWidth: Int, sourceHeight: Int, rawToUpright: PixelTransform,
              sourceLens: LensProjection, levelLens: LensProjection, width: Int, height: Int,
              tileRows: Int = 32, cancelled: () -> Boolean = { false }, consume: (Tile) -> Unit) {
        require(sourceWidth in 2..8192 && sourceHeight in 2..8192 && source.size.toLong() == sourceWidth.toLong() * sourceHeight)
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= PyramidLevel.MAX_PIXELS)
        require(tileRows in 1..256)
        require(rawToUpright.sourceWidth == sourceWidth && rawToUpright.sourceHeight == sourceHeight) { "EXIF transform source domain mismatch" }
        for (start in 0 until height step tileRows) {
            if (cancelled()) throw CancellationException("Pyramid rectification cancelled")
            val count = minOf(tileRows, height - start)
            val pixels = IntArray(width * count); val mask = ByteArray(pixels.size)
            for (dy in 0 until count) {
                if (cancelled()) throw CancellationException("Pyramid rectification cancelled")
                val y = start + dy
                for (x in 0 until width) {
                    val rayX = (x - levelLens.cx) / levelLens.fx
                    val rayY = (y - levelLens.cy) / levelLens.fy
                    val (dx, dySource) = sourceLens.distort(rayX, rayY)
                    val (sx, sy) = rawToUpright.inverse(dx * sourceLens.fx + sourceLens.cx, dySource * sourceLens.fy + sourceLens.cy)
                    if (!sx.isFinite() || !sy.isFinite() || sx < 0 || sy < 0 || sx >= sourceWidth - 1 || sy >= sourceHeight - 1) continue
                    val ix = floor(sx).toInt(); val iy = floor(sy).toInt(); val u = sx - ix; val v = sy - iy
                    val p00 = source[iy * sourceWidth + ix]; val p10 = source[iy * sourceWidth + ix + 1]
                    val p01 = source[(iy + 1) * sourceWidth + ix]; val p11 = source[(iy + 1) * sourceWidth + ix + 1]
                    // Conservative four-neighbour footprint: no interpolation through transparent or partial-alpha holes.
                    if ((p00 ushr 24)!=255 || (p10 ushr 24)!=255 || (p01 ushr 24)!=255 || (p11 ushr 24)!=255) continue
                    var color = 0xff000000.toInt()
                    for (channelIndex in 0..2) {
                        val shift = 16 - 8 * channelIndex
                        val a = (p00 ushr shift) and 255; val b = (p10 ushr shift) and 255
                        val c = (p01 ushr shift) and 255; val d = (p11 ushr shift) and 255
                        val channel = (a * (1-u) * (1-v) + b * u * (1-v) + c * (1-u) * v + d * u * v).roundToInt().coerceIn(0, 255)
                        color = color or (channel shl shift)
                    }
                    pixels[dy * width + x] = color; mask[dy * width + x] = 1
                }
            }
            if (cancelled()) throw CancellationException("Pyramid rectification cancelled")
            consume(Tile(start, count, pixels, mask))
        }
    }
    data class CacheResult(val identity:String,val hit:Boolean,val stored:Boolean)
    /** Producer adapter for an ALREADY EXIF-upright decoded/resized bitmap's ARGB pixels.
     * Full originalToLevel identity is metadata; do not apply EXIF a second time here.
     * Cache allocation is optional: pass null when030 has not admitted extra level buffers.
     */
    fun writeUprightRgba(source:IntArray,width:Int,height:Int,lens:LensProjection,
                         target:ByteArray,offset:Int,originalSha256:String,calibrationProvenance:String,
                         originalToLevel:PixelTransform,store:PyramidStore?=null,
                         cancelled:()->Boolean={false}):CacheResult {
        require(calibrationProvenance.isNotBlank() && calibrationProvenance.length<=1024)
        require(width>0 && height>0 && width.toLong()*height<=PyramidLevel.MAX_PIXELS && source.size.toLong()==width.toLong()*height)
        require(originalToLevel.width==width && originalToLevel.height==height)
        require(offset>=0 && offset.toLong()+width.toLong()*height*4<=target.size)
        val coefficients=listOf(lens.fx,lens.fy,lens.cx,lens.cy,lens.k1,lens.k2,lens.p1,lens.p2,lens.k3).joinToString(","){java.lang.Double.toHexString(it)}
        val key=PyramidLevel.identity(originalSha256,"upright-rgba-v2|opaque-four-neighbour|invalid-border|$calibrationProvenance|$coefficients",originalToLevel)
        fun pack(pixels:IntArray,mask:ByteArray,start:Int){
            for(i in pixels.indices){if(i%width==0 && cancelled())throw CancellationException("Pyramid packing cancelled")
                val p=offset+(start+i)*4;val c=pixels[i]
                if(mask[i].toInt()==1){target[p]=(c ushr 16).toByte();target[p+1]=(c ushr 8).toByte();target[p+2]=c.toByte();target[p+3]=255.toByte()}
                else {target[p]=0;target[p+1]=0;target[p+2]=0;target[p+3]=0}
            }
        }
        if(cancelled())throw CancellationException("Pyramid lookup cancelled")
        store?.load(key,originalToLevel)?.use { cached ->
            pack(cached.pixels(),cached.validity(),0);return CacheResult(key,true,true)
        }
        val pixels=if(store!=null)IntArray(width*height) else null
        val mask=if(store!=null)ByteArray(width*height) else null
        tiles(source,width,height,PixelTransform.identity(width,height),lens,lens,width,height,cancelled=cancelled){tile->
            val p=tile.pixels;val valid=tile.validity;pack(p,valid,tile.firstRow*width)
            if(pixels!=null && mask!=null){p.copyInto(pixels,tile.firstRow*width);valid.copyInto(mask,tile.firstRow*width)}
        }
        if(cancelled())throw CancellationException("Pyramid completion cancelled")
        val stored=if(store!=null && pixels!=null && mask!=null)PyramidLevel(key,width,height,originalToLevel,pixels,mask).use { store.store(it) } else false
        return CacheResult(key,false,stored)
    }

}
