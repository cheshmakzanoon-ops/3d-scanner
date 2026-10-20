package com.realityforge.scanner.engine.geometry

/** Immutable affine map of pixel centres. Integer (0,0) is the first pixel centre.
 * Operations act on EXIF stored pixels; crop and resize follow orientation.
 * Resampling uses x'=(x+.5)*scale-.5. Camera rays remain x-right/y-down/z-forward;
 * orientation changes pixel coordinates, never world scale or camera pose.
 */
class PixelTransform private constructor(
    val sourceWidth: Int, val sourceHeight: Int,
    val width: Int, val height: Int,
    val a: Double, val b: Double, val tx: Double,
    val c: Double, val d: Double, val ty: Double,
) {
    init { require(sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0); require(listOf(a,b,tx,c,d,ty).all(Double::isFinite)); require((a*d-b*c).isFinite() && kotlin.math.abs(a*d-b*c)>1e-15) }
    fun map(x: Double,y: Double): Pair<Double,Double> {
        require(x.isFinite() && y.isFinite())
        val result=(a*x+b*y+tx) to (c*x+d*y+ty)
        require(result.first.isFinite() && result.second.isFinite()) { "Pixel mapping overflow" }
        return result
    }
    fun inverse(x: Double,y: Double): Pair<Double,Double> {
        require(x.isFinite() && y.isFinite())
        val determinant=a*d-b*c
        val result=((d*(x-tx)-b*(y-ty))/determinant) to ((a*(y-ty)-c*(x-tx))/determinant)
        require(result.first.isFinite() && result.second.isFinite()) { "Pixel inverse overflow" }
        return result
    }
    /** Return next(this(pixel)); next's input dimensions are the current dimensions. */
    fun then(next: PixelTransform): PixelTransform {
        require(width==next.sourceWidth && height==next.sourceHeight) { "Pixel transform domains do not compose" }
        return PixelTransform(sourceWidth,sourceHeight,next.width,next.height,
        next.a*a+next.b*c,next.a*b+next.b*d,next.a*tx+next.b*ty+next.tx,
        next.c*a+next.d*c,next.c*b+next.d*d,next.c*tx+next.d*ty+next.ty)
    }
    /** Transform full intrinsic matrix, including orientation/skew; no lossy fx/fy shortcut. */
    fun intrinsics(fx: Double,fy: Double,cx: Double,cy: Double): DoubleArray {
        require(fx.isFinite() && fy.isFinite() && fx>0 && fy>0)
        val p=map(cx,cy)
        return doubleArrayOf(a*fx,b*fy,p.first,c*fx,d*fy,p.second,0.0,0.0,1.0).also { require(it.all(Double::isFinite)) }
    }
    companion object {
        const val VERSION = 2
        fun identity(width: Int,height: Int)=PixelTransform(width,height,width,height,1.0,0.0,0.0,0.0,1.0,0.0)
        fun resize(width: Int,height: Int,targetWidth: Int,targetHeight: Int): PixelTransform {
            require(width>0 && height>0)
            val sx=targetWidth.toDouble()/width; val sy=targetHeight.toDouble()/height
            return PixelTransform(width,height,targetWidth,targetHeight,sx,0.0,(sx-1)/2,0.0,sy,(sy-1)/2)
        }
        fun crop(width: Int,height: Int,left: Int,top: Int,cropWidth: Int,cropHeight: Int): PixelTransform {
            require(left>=0 && top>=0 && cropWidth>0 && cropHeight>0 && left.toLong()+cropWidth<=width && top.toLong()+cropHeight<=height)
            return PixelTransform(width,height,cropWidth,cropHeight,1.0,0.0,-left.toDouble(),0.0,1.0,-top.toDouble())
        }
        fun exif(width: Int,height: Int,orientation: Int): PixelTransform {
            require(width>0 && height>0)
            val w=width-1.0; val h=height-1.0
            return when(orientation) {
                1 -> identity(width,height)
                2 -> PixelTransform(width,height,width,height,-1.0,0.0,w,0.0,1.0,0.0)
                3 -> PixelTransform(width,height,width,height,-1.0,0.0,w,0.0,-1.0,h)
                4 -> PixelTransform(width,height,width,height,1.0,0.0,0.0,0.0,-1.0,h)
                5 -> PixelTransform(width,height,height,width,0.0,1.0,0.0,1.0,0.0,0.0)
                6 -> PixelTransform(width,height,height,width,0.0,-1.0,h,1.0,0.0,0.0)
                7 -> PixelTransform(width,height,height,width,0.0,-1.0,h,-1.0,0.0,w)
                8 -> PixelTransform(width,height,height,width,0.0,1.0,0.0,-1.0,0.0,w)
                else -> throw IllegalArgumentException("Unsupported EXIF orientation")
            }
        }
    }
}
