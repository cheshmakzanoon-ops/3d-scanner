package hostadapter

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Explicit HOST substitute for AndroidBitmap_lockPixels only; no CV algorithm shadow. */
@Implements(value=Utils::class,isInAndroidSdk=false)
class OpaqueBitmapBridge {
 companion object {
  var bitmap:Bitmap?=null;var rgba:Mat?=null;var pixels:IntArray?=null
  fun reset(){bitmap=null;rgba=null;pixels=null}
  @Implementation @JvmStatic fun bitmapToMat(source:Bitmap,destination:Mat) = bitmapToMat(source,destination,false)
  @Implementation @JvmStatic fun bitmapToMat(source:Bitmap,destination:Mat,unPremultiplyAlpha:Boolean) {
   require(!unPremultiplyAlpha){"HOST bridge does not implement alpha unpremultiplication"}
   require(!source.isRecycled&&source.config==Bitmap.Config.ARGB_8888){"HOST bridge requires live ARGB_8888"}
   val values=IntArray(source.width*source.height);source.getPixels(values,0,source.width,0,0,source.width,source.height)
   require(values.all{it ushr 24==255}){"HOST bridge requires opaque pixels"}
   val bytes=ByteArray(values.size*4)
   for(i in values.indices){val c=values[i];bytes[4*i]=(c ushr 16).toByte();bytes[4*i+1]=(c ushr 8).toByte();bytes[4*i+2]=c.toByte();bytes[4*i+3]=255.toByte()}
   destination.create(source.height,source.width,CvType.CV_8UC4)
   check(destination.put(0,0,bytes)==bytes.size)
   bitmap=source;rgba=destination;pixels=values
  }
 }
}
