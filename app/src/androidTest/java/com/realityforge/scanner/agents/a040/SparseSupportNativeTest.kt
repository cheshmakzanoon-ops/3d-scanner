package com.realityforge.scanner.agents.a040
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.NativeCore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
/** Synthetic projected landmarks test actual Android JNI and source support. */
@RunWith(AndroidJUnit4::class)
class SparseSupportNativeTest {
 @Test fun retainsTrackIdentityAndOnlyObservedCameraSupport(){
  val cameras=DoubleArray(36);for(c in 0..2){for(k in 0..8)cameras[c*12+k]=if(k%4==0)1.0 else 0.0;cameras[c*12+9]=-c*.5}
  val rows=DoubleArray(30*3*7);for(track in 0 until 30)for(c in 0..2){val x=(track%6-2.5)*.12;val y=(track/6-2)*.13;val z=4.0+track%4*.2
   doubleArrayOf((track+300).toDouble(),c.toDouble(),(x-c*.5)/z,y/z,100.0,150.0,200.0).copyInto(rows,(track*3+c)*7) }
  val result=JSONObject(NativeCore.reconstructMultiview(cameras,rows,.003,0));assertFalse(result.has("error"));assertEquals(30,result.getInt("pointCount"))
  val points=result.getJSONArray("points");for(i in 0 until points.length()){val p=points.getJSONArray(i);val source=p.getInt(8)-300;assertTrue(source in 0 until 30);assertEquals(4.0+source%4*.2,p.getDouble(2),1e-6)}
  val evidence=result.getJSONArray("trackEvidence");assertEquals(30,evidence.length());for(i in 0 until evidence.length())assertEquals(3,evidence.getJSONObject(i).getJSONArray("supportingCameras").length())
 }
}
