package com.realityforge.scanner.engine.features

import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.matching.*
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** HOST fixture ingress only. Actual production SIFT/matching/verification; no perfect matches injected. */
class ProductionFrontendHostTest {
 private val root=File(requireNotNull(System.getProperty("frontend.fixtures")))
 private val calibration=JSONObject(File(root,"calibration.json").readText())
 private val settings=ExtractorSettings() // production3500 features/1600 edge
 private val pair=PairCandidate("a","b",1.0,"synthetic-relief-regression")
 private val threshold=1.5/760.0
 private fun camera(index:Int)=calibration.getJSONArray("cameras").getJSONObject(index)
 private fun vector(o:JSONObject,key:String)=o.getJSONArray(key).let{a->DoubleArray(a.length()){a.getDouble(it)}}
 private fun matrix(o:JSONObject,key:String)=o.getJSONArray(key).let{a->DoubleArray(9){a.getJSONArray(it/3).getDouble(it%3)}}
 private fun frame(id:String,index:Int):FeatureFrame {
  assertTrue(calibration.getBoolean("synthetic"))
  val cam=camera(index);val file=File(root,cam.getString("image"))
  val image=requireNotNull(ImageIO.read(file)){"JDK ImageIO could not decode synthetic JPEG"}
  val rgba=Mat();val gray=Mat()
  try {
   assertEquals(calibration.getInt("width"),image.width);assertEquals(calibration.getInt("height"),image.height)
   assertEquals(800,image.width);assertEquals(600,image.height)
   // Explicit JavaImageIO sRGB ARGB -> RGBA fixture ingress. Not AndroidImageDecoder/Bitmap evidence.
   rgba.create(image.height,image.width,CvType.CV_8UC4)
   val row=ByteArray(image.width*4)
   for(y in 0 until image.height){for(x in 0 until image.width){val c=image.getRGB(x,y);val i=x*4
    row[i]=(c ushr 16).toByte();row[i+1]=(c ushr 8).toByte();row[i+2]=c.toByte();row[i+3]=(c ushr 24).toByte()}
    rgba.put(y,0,row)
   }
   Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
   val k=matrix(cam,"K");assertEquals(760.0,k[0],0.0);assertEquals(760.0,k[4],0.0)
   val lens=LensProjection(k[0],k[4],k[2],k[5])
   val content=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
   val decoder="host-java-imageio-rgba-to-gray-v1";val identity=settings.identity(decoder)
   return extractGrayFeatures(settings,id,content,decoder,identity,gray,lens,image::getRGB,{false},null)
  } finally {rgba.release();gray.release();image.flush()}
 }
 @Test fun calibratedReliefExtractMatchVerifyAcceptsNonplanarBaseline() {
  frame("a",0).use { a->frame("b",1).use { b->
   assertTrue(a.count>=20);assertTrue(b.count>=20)
   val matcher=FeatureMatcher(blockSize=17);val tentative=matcher.match(a,b,pair)
   assertTrue("Must reach native verifier",tentative.count>=20)
   val result=matcher.verify(tentative,threshold)
   assertTrue("Calibrated synthetic baseline rejected: $result",result is GeometricVerification.Accepted)
   val accepted=(result as GeometricVerification.Accepted).matches
   val output=JSONObject(accepted.nativeResultJson)
   assertEquals("sparse_points",output.getString("representation"));assertEquals("arbitrary",output.getString("scale"))
   assertEquals(2,output.getInt("registeredCameras"));assertEquals(tentative.count,output.getInt("inputMatches"))
   val points=output.getJSONArray("points");assertTrue(points.length()>=20)
   assertEquals(points.length(),output.getInt("pointCount"));assertEquals(points.length(),accepted.correspondences().size)
   val ids=accepted.sourceMatchIndices();assertEquals(ids.size,ids.distinct().size)
   assertTrue(ids.all{it in tentative.matches.indices});assertEquals(ids.map{tentative.matches[it]},accepted.correspondences())
   val rotation=vector(output,"rotation");val translation=vector(output,"translation")
   assertEquals(9,rotation.size);assertEquals(3,translation.size);assertTrue(rotation.all{it.isFinite()}&&translation.all{it.isFinite()})
   for(i in 0..2)for(j in 0..2)assertEquals(if(i==j)1.0 else 0.0,(0..2).sumOf{k->rotation[3*i+k]*rotation[3*j+k]},1e-6)
   val det=rotation[0]*(rotation[4]*rotation[8]-rotation[5]*rotation[7])-rotation[1]*(rotation[3]*rotation[8]-rotation[5]*rotation[6])+rotation[2]*(rotation[3]*rotation[7]-rotation[4]*rotation[6])
   assertEquals(1.0,det,1e-6)
   assertArrayEquals(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),matrix(camera(0),"R"),0.0)
   assertArrayEquals(doubleArrayOf(0.0,0.0,0.0),vector(camera(0),"t"),0.0)
   val expectedR=matrix(camera(1),"R");val expectedT=vector(camera(1),"t")
   val rotationError=Math.toDegrees(acos(((rotation.indices.sumOf{rotation[it]*expectedR[it]}-1.0)/2.0).coerceIn(-1.0,1.0)))
   val norm=sqrt(translation.sumOf{it*it});val expectedNorm=sqrt(expectedT.sumOf{it*it})
   assertEquals(1.0,norm,1e-6)
   val directionDot=(0..2).sumOf{translation[it]*expectedT[it]}/(norm*expectedNorm)
   assertTrue("Rotation error $rotationError degrees",rotationError<2.0)
   assertTrue("Translation direction dot $directionDot",directionDot>.98)
   val median=output.getDouble("medianErrorNormalized");assertTrue(median.isFinite()&&median>=0&&median<=threshold)
   assertTrue(output.getDouble("medianParallaxDegrees")>0)
   for(i in 0 until points.length()){
    val p=points.getJSONArray(i);assertEquals(9,p.length());assertTrue((0..8).all{p.getDouble(it).isFinite()})
    val source=tentative.matches[ids[i]];assertEquals(ids[i],p.getInt(8))
    assertEquals(source.argb ushr 16 and 255,p.getInt(3));assertEquals(source.argb ushr 8 and 255,p.getInt(4));assertEquals(source.argb and 255,p.getInt(5))
    assertTrue(p.getDouble(2)>0);assertTrue(rotation[6]*p.getDouble(0)+rotation[7]*p.getDouble(1)+rotation[8]*p.getDouble(2)+translation[2]>0)
   }
   println(JSONObject().put("case","relief_acceptance").put("firstFeatures",a.count).put("secondFeatures",b.count).put("tentative",tentative.count).put("points",points.length()).put("rotationErrorDegrees",rotationError).put("translationDirectionDot",directionDot).put("medianErrorNormalized",median).put("scale","arbitrary"))
  }}
 }
 @Test fun independentlyExtractedSameViewReachesNativeAndRejectsUnobservableBaseline() {
  frame("a",0).use { a->frame("b",0).use { b->
   assertNotEquals(a.photoId,b.photoId);assertEquals(a.contentSha256,b.contentSha256)
   val matcher=FeatureMatcher(blockSize=17);val tentative=matcher.match(a,b,pair)
   assertTrue("Negative must reach JNI, not Kotlin <20 shortcut",tentative.count>=20)
   val result=matcher.verify(tentative,threshold)
   assertTrue(result is GeometricVerification.Rejected)
   assertEquals("Rotation-only or unobservable baseline",(result as GeometricVerification.Rejected).reason)
   println(JSONObject().put("case","same_view_native_rejection").put("tentative",tentative.count).put("reason",result.reason))
  }}
 }
}
