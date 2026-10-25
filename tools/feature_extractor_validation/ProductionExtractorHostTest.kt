package com.realityforge.scanner.engine.features

import com.realityforge.scanner.engine.LensProjection
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.*
import org.opencv.features2d.SIFT

/** Synthetic upright grayscale inputs; no Android decoder, captured-photo or device claim. */
class ProductionExtractorHostTest {
    private val settings=ExtractorSettings(256,512)
    private fun texture():Mat {
        val m=Mat(384,416,CvType.CV_8UC1)
        val random=java.util.Random(479)
        val bytes=ByteArray(384*416){random.nextInt(256).toByte()}
        m.put(0,0,bytes)
        return m
    }
    private fun color(x:Int,y:Int)=0xff000000.toInt() or (x shl 12) or y
    private fun run(gray:Mat,s:ExtractorSettings=settings,
        lens:LensProjection=LensProjection.estimated(gray.cols(),gray.rows(),420.0),
        sample:(Int,Int)->Int=::color, cancelled:()->Boolean={false},
        observe:((NativeFeatureCallEvent)->Unit)?=null,
    )=extractGrayFeatures(s,"synthetic","0".repeat(64),"host-synthetic-gray",s.identity("host-synthetic-gray"),
        gray,lens,sample,cancelled,observe)

    @Test fun exactOrderedBaselineDescriptorsCoordinatesColorsAndIdentity() {
        val gray=texture(); val keys=MatOfKeyPoint();val desc=Mat();val mask=Mat()
        val sift=SIFT.create(settings.maxFeatures)
        try {
            sift.detectAndCompute(gray,mask,keys,desc)
            val reference=keys.toArray();assertTrue(reference.size>0)
            run(gray).use { a -> run(gray).use { b ->
                assertEquals(minOf(reference.size,settings.maxFeatures),a.count)
                assertArrayEquals(a.coordinates(),b.coordinates(),0f)
                val xy=a.coordinates();val colors=a.colors()
                for(i in 0 until a.count){
                    assertEquals(reference[i].pt.x.toFloat(),xy[2*i],0f)
                    assertEquals(reference[i].pt.y.toFloat(),xy[2*i+1],0f)
                    assertEquals(color(reference[i].pt.x.toInt().coerceIn(0,gray.cols()-1),reference[i].pt.y.toInt().coerceIn(0,gray.rows()-1)),colors[i])
                }
                val bounded=desc.rowRange(0,a.count)
                try {a.withDescriptors { own ->
                    val raw=FloatArray(128);val actual=FloatArray(128)
                    for(row in 0 until a.count) {
                        assertEquals(512,bounded.get(row,0,raw));assertEquals(512,own.get(row,0,actual))
                        val sum=raw.sumOf { it.toDouble() };assertTrue(sum>0.0)
                        val expected=FloatArray(128){kotlin.math.sqrt(raw[it].toDouble()/sum).toFloat()}
                        assertArrayEquals(expected,actual,0f)
                        assertEquals(1.0,actual.sumOf { it.toDouble()*it },1e-6)
                    }
                    b.withDescriptors {assertEquals(0.0,Core.norm(own,it),0.0)}
                }} finally {bounded.release()}
                assertNotEquals("446c29d247055c57d9a45085db05c846470fa351aadfedf34f97c853b590c746",a.settingsIdentity)
                assertEquals(settings.identity("host-synthetic-gray"),a.settingsIdentity)
                assertEquals("synthetic",a.photoId);assertEquals("0".repeat(64),a.contentSha256)
                assertEquals("host-synthetic-gray",a.decoderIdentity)
                assertEquals(gray.cols(),a.width);assertEquals(gray.rows(),a.height)
            }}
        } finally {sift.clear();keys.release();desc.release();mask.release();gray.release()}
    }
    @Test fun floatRowTransfersReturnBytesAndOnlyTouchRequestedRow() {
        val mat=Mat.zeros(2,128,CvType.CV_32F)
        try {
            val input=FloatArray(128){(it+1).toFloat()};val output=FloatArray(128)
            assertEquals(512,mat.put(1,0,input));assertEquals(512,mat.get(1,0,output))
            assertArrayEquals(input,output,0f)
            assertEquals(512,mat.get(0,0,output));assertTrue(output.all{it==0f})
        }finally{mat.release()}
    }
    @Test fun cancellationDuringNormalizationDoesNotPublishOrMutateBorrowedGray() {
        val gray=texture();val original=gray.clone()
        try {
            val count=run(gray).use{it.count};var samples=0;var normalizationChecks=0
            assertThrows(CancellationException::class.java){run(gray,
                sample={x,y->samples++;color(x,y)},
                cancelled={if(samples==count){normalizationChecks++;normalizationChecks==2}else false})}
            assertEquals(count,samples);assertEquals(2,normalizationChecks)
            assertEquals(0.0,Core.norm(gray,original),0.0)
            run(gray).use{assertEquals(count,it.count)}
        }finally{gray.release();original.release()}
    }
    @Test fun configuredCapAndNormalizationStayRowAligned() {
        val gray=texture()
        try {
            val lens=LensProjection(420.0,415.0,200.0,190.0,.02,-.001,.0001,-.0002,0.0)
            run(gray,ExtractorSettings(32,512),lens).use { f ->
                assertTrue(f.count>0);assertTrue(f.count<=32)
                val xy=f.coordinates();val colors=f.colors()
                for(i in 0 until f.count){
                    assertEquals(lens.normalized(xy[2*i].toDouble(),xy[2*i+1].toDouble()),f.normalized(i))
                    assertEquals(color(xy[2*i].toInt(),xy[2*i+1].toInt()),colors[i])
                }
                f.withDescriptors{assertEquals(f.count,it.rows());assertEquals(128,it.cols());assertEquals(CvType.CV_32F,it.type())}
            }
        }finally{gray.release()}
    }
    @Test fun blankFrameAndIndependentOwnedDescriptorLifetime() {
        val blank=Mat.zeros(64,64,CvType.CV_8UC1)
        try{run(blank).use{assertEquals(0,it.count);it.withDescriptors{d->assertTrue(d.empty())}};assertFalse(blank.empty())}
        finally{blank.release()}
        val gray=texture();val f=try{run(gray)}finally{gray.release()}
        try {assertTrue(f.count>0);f.withDescriptors{assertTrue(Core.norm(it)>0)}}finally{f.close()}
        f.close();assertThrows(IllegalStateException::class.java){f.withDescriptors{it.rows()}}
    }
    @Test fun borrowedGrayUnchangedOnSuccessAndCallbackFailure() {
        val gray=texture();val original=gray.clone()
        try {
            run(gray).close();assertEquals(0.0,Core.norm(gray,original),0.0)
            val failure=IllegalStateException("sampler")
            assertSame(failure,assertThrows(IllegalStateException::class.java){run(gray,sample={_,_->throw failure})})
            assertEquals(0.0,Core.norm(gray,original),0.0)
            run(gray).use{assertTrue(it.count>0)}
        }finally{gray.release();original.release()}
    }
    @Test fun observerExceptionsKeepExistingEntryExitSemantics() {
        val gray=texture()
        try {
            for(target in NativeFeatureCallEvent.values()){
                val events=ArrayList<NativeFeatureCallEvent>();val failure=IllegalStateException(target.name)
                assertSame(failure,assertThrows(IllegalStateException::class.java){run(gray,observe={e->events.add(e);if(e==target)throw failure})})
                assertEquals(if(target==NativeFeatureCallEvent.STARTED)listOf(target) else NativeFeatureCallEvent.values().toList(),events)
                assertFalse(gray.empty());run(gray).close()
            }
        }finally{gray.release()}
    }
    @Test fun cancellationAlreadyRequestedAtGrayBoundaryNeverStartsKernel() {
        val gray=texture();val original=gray.clone()
        try {
            val events=ArrayList<NativeFeatureCallEvent>()
            var samples=0
            assertThrows(CancellationException::class.java){run(gray,
                sample={x,y->samples++;color(x,y)},cancelled={true},observe={events.add(it)})}
            assertTrue("Cancellation after image conversion must not launch SIFT",events.isEmpty())
            assertEquals(0,samples)
            assertEquals(0.0,Core.norm(gray,original),0.0)
            run(gray).use{assertTrue(it.count>0)}
        }finally{gray.release();original.release()}
    }
    @Test fun callbackCancellationIsObservedAfterKernelWithoutPublication() {
        val gray=texture()
        try {
            for(target in NativeFeatureCallEvent.values()){
                var stop=false;val events=ArrayList<NativeFeatureCallEvent>()
                assertThrows(CancellationException::class.java){run(gray,cancelled={stop},observe={e->events.add(e);if(e==target)stop=true})}
                assertEquals(NativeFeatureCallEvent.values().toList(),events)
                assertFalse(gray.empty());run(gray).close()
            }
        }finally{gray.release()}
    }
    @Test fun cancellationAtRowCadenceAndFinalOwnershipCheck() {
        val gray=texture()
        try {
            val count=run(gray).use{it.count};assertTrue("fixture must exercise row128",count>128)
            var samples=0;var stop=false
            assertThrows(CancellationException::class.java){run(gray,sample={x,y->samples++;stop=true;color(x,y)},cancelled={stop})}
            assertEquals(128,samples)
            samples=0;stop=false
            assertThrows(CancellationException::class.java){run(gray,sample={x,y->samples++;if(samples==count)stop=true;color(x,y)},cancelled={stop})}
            assertEquals(count,samples)
            run(gray).use{assertEquals(count,it.count)}
        }finally{gray.release()}
    }
    @Test fun internalGrayPreconditionsAndLateFrameValidationRemainExplicit() {
        val empty=Mat();val color=Mat.zeros(64,64,CvType.CV_8UC4);val tooWide=Mat.zeros(32,513,CvType.CV_8UC1)
        try {for(gray in listOf(empty,color,tooWide)){
            val events=ArrayList<NativeFeatureCallEvent>()
            assertThrows(IllegalArgumentException::class.java){run(gray,lens=LensProjection.estimated(64,64,420.0),observe={events.add(it)})}
            assertTrue(events.isEmpty())
        }}finally{empty.release();color.release();tooWide.release()}
        val gray=texture()
        try {
            val events=ArrayList<NativeFeatureCallEvent>()
            assertThrows(IllegalArgumentException::class.java){extractGrayFeatures(settings," ","0".repeat(64),"d","identity",gray,
                LensProjection.estimated(gray.cols(),gray.rows(),420.0),::color,{false},{events.add(it)})}
            // Public adapter validates before decode; the internal seam receives validated metadata.
            assertEquals(NativeFeatureCallEvent.values().toList(),events)
            assertFalse(gray.empty());run(gray).close()
        }finally{gray.release()}
    }
    @Test fun realNativeFailureStillEmitsFinishedAndRemainsDiagnosable() {
        val gray=texture();val events=ArrayList<NativeFeatureCallEvent>()
        try {
            // Deliberate synchronous ownership fault injection: empty live Mat header before JNI.
            // No concurrent mutation, freed Java/native header, or simulated SIFT exception.
            val failure=assertThrows(CvException::class.java){run(gray,observe={event->
                events.add(event)
                if(event==NativeFeatureCallEvent.STARTED)gray.release()
            })}
            assertTrue(failure.message.orEmpty().contains("image is empty"))
            assertEquals(NativeFeatureCallEvent.values().toList(),events)
            assertTrue(failure.suppressed.isEmpty())
        }finally{gray.release()}
    }
    @Test fun finishedObserverFailureIsSuppressedBehindRealNativeFailure() {
        val gray=texture();val events=ArrayList<NativeFeatureCallEvent>()
        val secondary=IllegalStateException("distinct FINISHED observer failure")
        try {
            val failure=assertThrows(CvException::class.java){run(gray,observe={event->
                events.add(event)
                when(event){
                    NativeFeatureCallEvent.STARTED->gray.release()
                    NativeFeatureCallEvent.FINISHED->throw secondary
                }
            })}
            assertTrue(failure.message.orEmpty().contains("image is empty"))
            assertEquals(NativeFeatureCallEvent.values().toList(),events)
            assertEquals(1,failure.suppressed.size)
            assertSame(secondary,failure.suppressed.single())
            // The native throwable is observed only after unwinding: no identity claim for it.
        }finally{gray.release()}
    }

}
