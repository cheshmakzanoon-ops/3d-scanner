package com.realityforge.scanner.agents.a037
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.NativeCore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import kotlin.math.*
/** Synthetic analytic observations; independent poses are withheld from native SfM. */
@RunWith(AndroidJUnit4::class)
class InitializerNativeTest {
 @Test fun recoversMeasuredPoseAndReportsRotationOnly(){
  val random=Random(37017);val rows=DoubleArray(120*7);val rotationOnly=DoubleArray(120*7)
  for(i in 0 until 120){val x=random.nextDouble()*2-1;val y=random.nextDouble()*2-1;val z=3+random.nextDouble()*4;val a=.08
   val qx=cos(a)*x+sin(a)*z;val qz=-sin(a)*x+cos(a)*z
   val values=doubleArrayOf(x/z,y/z,(qx-1)/qz,y/qz,120.0,160.0,200.0)
   values.copyInto(rows,i*7);values.copyInto(rotationOnly,i*7);rotationOnly[i*7+2]=qx/qz }
  val result=JSONObject(NativeCore.reconstruct(rows,.002));assertFalse(result.has("error"));assertTrue(result.getInt("pointCount")>=115)
  assertTrue(result.getJSONArray("translation").getDouble(0)<-.99);assertEquals(sin(.08),result.getJSONArray("rotation").getDouble(2),1e-5)
  val unsupported=JSONObject(NativeCore.reconstruct(rotationOnly,.002));assertTrue(unsupported.has("error"));assertEquals("ROTATION_ONLY",unsupported.getString("errorCode"))
 }
}
