package hostadapter

import android.app.Application
import android.graphics.Bitmap
import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.features.*
import com.realityforge.scanner.imaging.AndroidImageDecoder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AdapterRunner::class)
@Config(sdk=[35],application=Application::class,manifest=Config.NONE,shadows=[OpaqueBitmapBridge::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureAdapterProbeTest {
 @Test(timeout=120_000) fun prerequisitesThenActualAdapterAndCancellationCleanup() {
  HostOpenCvLoader.smoke()
  check(Utils::class.java.classLoader!==Mat::class.java.classLoader)
  exactOpaqueBridgeAndUnsupportedCases()
  val file=File(requireNotNull(System.getProperty("rf.decoder.fixtureDir")),"view-01.jpg")
  val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
  assertEquals("66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c",hash)
  var primary:Throwable?=null
  try { runAdapter(file,hash) }
  catch(failure:Throwable){primary=failure;throw failure}
  finally {
   OpaqueBitmapBridge.reset()
   try {
    val after=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
    assertEquals(hash,after)
   }catch(cleanup:Throwable){if(primary!=null)primary.addSuppressed(cleanup)else throw cleanup}
  }
 }
 private fun runAdapter(file:File,hash:String) {
  val extractor=FeatureExtractor();val identity="${AndroidImageDecoder.POLICY_IDENTITY}|analysisEdge=${extractor.settings.analysisEdge}"
  fun projection(w:Int,h:Int):LensProjection {
   assertEquals(800,w);assertEquals(600,h)
   return LensProjection(760.0,760.0,399.5,299.5)
  }
  fun event(events:MutableList<NativeFeatureCallEvent>,e:NativeFeatureCallEvent) {
   events+=e
   assertTrue(requireNotNull(OpaqueBitmapBridge.rgba).empty()) // production released color Mat before kernel
   assertFalse(requireNotNull(OpaqueBitmapBridge.bitmap).isRecycled)
  }
  OpaqueBitmapBridge.reset();val events=mutableListOf<NativeFeatureCallEvent>()
  val frame=extractor.extractObserved("adapter-positive",hash,file,identity,::projection,{false}){event(events,it)}
  try {
   assertEquals(listOf(NativeFeatureCallEvent.STARTED,NativeFeatureCallEvent.FINISHED),events)
   assertTrue(frame.count>=20);assertEquals(800,frame.width);assertEquals(600,frame.height)
   assertTrue(requireNotNull(OpaqueBitmapBridge.bitmap).isRecycled);assertTrue(requireNotNull(OpaqueBitmapBridge.rgba).empty())
   val xy=frame.coordinates();val colors=frame.colors();val pixels=requireNotNull(OpaqueBitmapBridge.pixels)
   for(i in 0 until frame.count){val x=xy[i*2].toInt().coerceIn(0,799);val y=xy[i*2+1].toInt().coerceIn(0,599);assertEquals(pixels[y*800+x],colors[i])}
   frame.withDescriptors {desc->
    assertEquals(CvType.CV_32F,desc.type());assertEquals(frame.count,desc.rows());assertEquals(128,desc.cols())
    val values=FloatArray(frame.count*128);assertEquals(values.size*4,desc.get(0,0,values));assertTrue(values.all{it.isFinite()&&it in 0f..512f});assertTrue(values.any{it!=0f})
   }
   println("HOST adapter actualFeatures=${frame.count}; decodedBitmapRecycled=true; rgbaReleased=true")
  }finally{frame.close()}
  assertThrows(IllegalStateException::class.java){frame.withDescriptors{}}
  OpaqueBitmapBridge.reset();events.clear();var cancelled=false;var published=false
  val failure=assertThrows(CancellationException::class.java){
   extractor.extractObserved("adapter-cancel",hash,file,identity,::projection,{cancelled}){e->event(events,e);if(e==NativeFeatureCallEvent.FINISHED)cancelled=true}.use{published=true}
  }
  assertEquals("Feature extraction cancelled",failure.message);assertFalse(published)
  assertEquals(listOf(NativeFeatureCallEvent.STARTED,NativeFeatureCallEvent.FINISHED),events)
  assertTrue(requireNotNull(OpaqueBitmapBridge.bitmap).isRecycled);assertTrue(requireNotNull(OpaqueBitmapBridge.rgba).empty())
  OpaqueBitmapBridge.reset();HostOpenCvLoader.checkMappings()
  println("HOST adapter cancellation after kernel return; no frame published; bitmap/rgba cleanup observed")
 }
 private fun exactOpaqueBridgeAndUnsupportedCases() {
  val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);val rgba=Mat()
  try {
   // Independent literals: not computed using the bridge's shifts or fixture decoder.
   bitmap.setPixels(intArrayOf(0xff123456.toInt(),0xffa1b2c3.toInt(),0xff00ff80.toInt(),0xffff0001.toInt()),0,2,0,0,2,2)
   Utils.bitmapToMat(bitmap,rgba)
   assertEquals(CvType.CV_8UC4,rgba.type());assertEquals(2,rgba.rows());assertEquals(2,rgba.cols())
   val got=ByteArray(16);assertEquals(16,rgba.get(0,0,got))
   assertArrayEquals(byteArrayOf(0x12,0x34,0x56,-1,0xa1.toByte(),0xb2.toByte(),0xc3.toByte(),-1,0,0xff.toByte(),0x80.toByte(),-1,0xff.toByte(),0,1,-1),got)
   assertThrows(IllegalArgumentException::class.java){Utils.bitmapToMat(bitmap,rgba,true)}
   bitmap.setPixel(0,0,0x80123456.toInt())
   assertThrows(IllegalArgumentException::class.java){Utils.bitmapToMat(bitmap,rgba)}
   val rgb565=Bitmap.createBitmap(2,2,Bitmap.Config.RGB_565)
   try{assertThrows(IllegalArgumentException::class.java){Utils.bitmapToMat(rgb565,rgba)}}finally{rgb565.recycle()}
  }finally{rgba.release();bitmap.recycle();OpaqueBitmapBridge.reset()}
 }
}
