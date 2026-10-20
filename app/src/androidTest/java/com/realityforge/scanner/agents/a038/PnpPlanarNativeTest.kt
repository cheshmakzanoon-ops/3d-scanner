package com.realityforge.scanner.agents.a038
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.NativeCore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*
/** A tilted synthetic plane and rotated camera, not a physical-camera benchmark. */
@RunWith(AndroidJUnit4::class)
class PnpPlanarNativeTest {
 @Test fun calibratedTiltedPlaneRetainsProperPoseAndOutlierIdentities(){
  val rows=DoubleArray(120*5);val a=.22
  for(i in 0 until 120){val x=(i%12-5.5)*.12;val localY=(i/12-4.5)*.1;val y=cos(.17)*localY;val z=4+sin(.17)*localY
   val qx=cos(a)*x+sin(a)*z-.3;val qy=y+.1;val qz=-sin(a)*x+cos(a)*z+.2
   val u=if(i%6==0).6*sin(i.toDouble()) else qx/qz;val v=if(i%6==0).6*cos(i.toDouble()) else qy/qz
   doubleArrayOf(x,y,z,u,v).copyInto(rows,i*5) }
  val result=JSONObject(NativeCore.registerCamera(rows,.001));assertFalse(result.has("error"));assertEquals("calibrated_plane_homography",result.getString("initializationModel"))
  assertTrue(result.getInt("inlierCount")>=99);val t=result.getJSONArray("translation");assertEquals(-.3,t.getDouble(0),.01);assertEquals(.1,t.getDouble(1),.01);assertEquals(.2,t.getDouble(2),.01)
  assertEquals(sin(a),result.getJSONArray("rotation").getDouble(2),.003)
  val inliers=result.getJSONArray("inlierIndices");val unique=(0 until inliers.length()).map {inliers.getInt(it)}.toSet();assertEquals(inliers.length(),unique.size);assertTrue(unique.all {it in 0 until 120})
 }
}
