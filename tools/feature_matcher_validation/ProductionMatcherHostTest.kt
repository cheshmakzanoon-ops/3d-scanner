package com.realityforge.scanner.engine.matching

import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.features.FeatureFrame
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.features2d.BFMatcher

/** Executes production matcher/ownership over actual JNI; descriptor fixtures are synthetic. */
class ProductionMatcherHostTest {
 private val pair=PairCandidate("a","b",1.0,"host-fixture")
 private fun frame(id:String,rows:List<FloatArray>):FeatureFrame {
  val mat=if(rows.isEmpty())Mat() else Mat(rows.size,128,CvType.CV_32F)
  try {
   rows.forEachIndexed { i,row -> require(row.size==128);mat.put(i,0,row) }
   return FeatureFrame(id,"0".repeat(64),"host-descriptors","host-settings",64,64,
    FloatArray(rows.size*2){i->if(i%2==0)(i/2%50+1).toFloat() else (i/2*3%50+1).toFloat()},
    IntArray(rows.size){0xff000000.toInt() or (it*701)},mat,LensProjection.estimated(64,64,64.0))
  } catch(t:Throwable){mat.release();throw t}
 }
 private fun scalar(v:Float)=FloatArray(128){v}
 private fun randomRows(n:Int,seed:Long)=java.util.Random(seed).let{r->List(n){FloatArray(128){r.nextFloat()*100f}}}
 private inline fun pairFrames(a:List<FloatArray>,b:List<FloatArray>,block:(FeatureFrame,FeatureFrame)->Unit) {
  frame("a",a).use { aa -> frame("b",b).use { bb -> block(aa,bb) } }
 }
 private fun expected(a:FeatureFrame,b:FeatureFrame,ratio:Double):List<FeatureCorrespondence> = a.withDescriptors { x->b.withDescriptors { y->
  val matcher=BFMatcher.create(Core.NORM_L2,false);val f=ArrayList<MatOfDMatch>();val r=ArrayList<MatOfDMatch>()
  try {
   matcher.knnMatch(x,y,f,2);matcher.knnMatch(y,x,r,2)
   val reverse=r.mapNotNull { row->val m=row.toArray();if(m.size==2 && m[0].distance<ratio*m[1].distance)m[0].queryIdx to m[0].trainIdx else null }.toMap()
   val colors=a.colors()
   f.mapNotNull { row -> val m=row.toArray();if(m.size==2 && m[0].distance<ratio*m[1].distance && reverse[m[0].trainIdx]==m[0].queryIdx){
    val i=m[0].queryIdx;val j=m[0].trainIdx;val p=a.normalized(i);val q=b.normalized(j)
    FeatureCorrespondence(i,j,p.first,p.second,q.first,q.second,colors[i])
   }else null }
  } finally {f.forEach{it.release()};r.forEach{it.release()};matcher.clear()}
 } }
 @Test fun descriptorEndpointAndInvalidInputs() {
  frame("a",List(4){scalar(0f)}).use { a ->
   for(v in listOf(0f,Math.nextDown(512f),512f))frame("b",List(4){scalar(v)}).use { b->assertEquals(0,FeatureMatcher().match(a,b,pair).count) }
   for(v in listOf(Math.nextUp(512f),-Float.MIN_VALUE,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))frame("b",List(4){scalar(v)}).use { b->assertThrows(IllegalArgumentException::class.java){FeatureMatcher().match(a,b,pair)} }
  }
 }
 @Test fun originalEmptyInvalidCancellationAndObserverContracts() {
  pairFrames(List(4){scalar(0f)},List(4){scalar(0f)}) {a,b->
   val m=FeatureMatcher();assertEquals(0,m.match(a,b,pair).count)
   assertThrows(CancellationException::class.java){m.match(a,b,pair,{true})}
   assertThrows(CancellationException::class.java){m.match(a,b,pair){true}}
   for(entry in listOf(true,false)) {
    val stop=CancellationException("observer")
    assertSame(stop,assertThrows(CancellationException::class.java){m.match(a,b,pair,work={_,_,_,_,running->if(running==entry)throw stop})})
    assertEquals(0,m.match(a,b,pair).count)
   }
   assertEquals(0,m.match(a,b,pair,work={_,_,_,_,_->throw IllegalStateException("diagnostic only")}).count)
   frame("b",emptyList()).use { empty->assertEquals(0,m.match(a,empty,pair).count) }
   frame("b",List(4){scalar(Float.NaN)}).use { bad->assertThrows(IllegalArgumentException::class.java){m.match(a,bad,pair)} }
   assertTrue(m.verify(m.match(a,b,pair),.001) is GeometricVerification.Rejected)
  }
 }
 @Test fun differentialUnequalDimensionsAndBlocksCompareOrderedFullPayload() {
  for(n in listOf(3,17,35)) {
   val a=randomRows(n,n.toLong());val b=randomRows(7,100L+n)+a.reversed()
   for(swap in listOf(false,true))pairFrames(if(swap)b else a,if(swap)a else b){x,y->
    val ref=expected(x,y,.67);assertTrue(ref.isNotEmpty())
    for(block in listOf(1,2,17,256))assertEquals("n=$n swap=$swap block=$block",ref,FeatureMatcher(blockSize=block).match(x,y,pair).matches)
   }
  }
 }
 @Test fun selectedReverseRowsAreNoncontiguousAndRemappedInForwardOrder() {
  val b=List(9){scalar(it*40f)};val a=listOf(b[7],b[1],b[4])
  pairFrames(a,b){x,y->for(block in listOf(1,2,17,256)){
   val got=FeatureMatcher(blockSize=block).match(x,y,pair)
   assertEquals(listOf(0 to 7,1 to 1,2 to 4),got.matches.map{it.firstKey to it.secondKey})
   assertEquals(expected(x,y,.67),got.matches)
  }}
 }
 @Test fun manyForwardSurvivorsStillUseCompleteReverseTrainSet() {
  pairFrames(listOf(scalar(0f),scalar(1f),scalar(2f),scalar(400f)),listOf(scalar(0f),scalar(100f),scalar(200f))){a,b->
   for(block in listOf(1,2,17,256)) {
    val got=FeatureMatcher(blockSize=block).match(a,b,pair)
    assertEquals(listOf(0 to 0),got.matches.map{it.firstKey to it.secondKey});assertEquals(expected(a,b,.67),got.matches)
   }
  }
 }
 @Test fun strictRatioBoundaryAndTies() {
  pairFrames(listOf(scalar(0f),scalar(100f)),listOf(scalar(1f),scalar(2f))){a,b->
   for(ratio in listOf(Math.nextDown(.5),.5))assertEquals(0,FeatureMatcher(ratio=ratio).match(a,b,pair).count)
   val accepted=FeatureMatcher(ratio=Math.nextUp(.5)).match(a,b,pair)
   assertEquals(listOf(0 to 0),accepted.matches.map{it.firstKey to it.secondKey})
  }
  pairFrames(List(3){scalar(0f)},List(4){scalar(0f)}){a,b->assertEquals(0,FeatureMatcher().match(a,b,pair).count)}
 }
 @Test fun cancellationAtBothDirectionsEntryExitPreservesIdentityAndBorrowedFrames() {
  val rows=randomRows(5,99)
  pairFrames(rows,rows.reversed()){a,b->
   val ref=expected(a,b,.67)
   for(direction in PairMatchDirection.values())for(entry in listOf(true,false)) {
    val stop=CancellationException("$direction/$entry");var reached=false;val m=FeatureMatcher(blockSize=2)
    assertSame(stop,assertThrows(CancellationException::class.java){m.match(a,b,pair,work={d,_,_,_,running->if(d==direction&&running==entry){reached=true;throw stop}})})
    assertTrue(reached);assertEquals(ref,m.match(a,b,pair).matches)
    assertEquals(ref,m.match(a,b,pair,work={d,_,_,_,running->if(d==direction&&running==entry)throw IllegalStateException("diagnostic only")}).matches)
   }
  }
 }
 @Test fun cancellationAfterNativeBlockAndClosedOwnership() {
  val rows=randomRows(5,101)
  pairFrames(rows,rows.reversed()){a,b->
   val ref=expected(a,b,.67)
   for(direction in PairMatchDirection.values()) {
    var cancelled=false;val m=FeatureMatcher(blockSize=2)
    assertThrows(CancellationException::class.java){m.match(a,b,pair,work={d,_,_,_,running->if(d==direction&&!running)cancelled=true},cancelled={cancelled})}
    assertTrue(cancelled);assertEquals(ref,m.match(a,b,pair).matches)
   }
   a.close();a.close();assertThrows(IllegalStateException::class.java){FeatureMatcher().match(a,b,pair)}
   b.withDescriptors{assertEquals(5,it.rows())}
  }
 }
}
