package com.realityforge.scanner.agents.a012

import android.graphics.Bitmap
import com.realityforge.scanner.imaging.AndroidImageDecoder
import com.realityforge.scanner.imaging.DecodedImageInfo
import com.realityforge.scanner.imaging.ExifOrientation
import com.realityforge.scanner.imaging.ImageBounds
import com.realityforge.scanner.imaging.ImageFormat
import com.realityforge.scanner.imaging.OrientationEvidence
import com.realityforge.scanner.imaging.OwnedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Real Android Bitmap ownership with deterministic coroutine dispatcher handoffs. */
class ImageOwnershipHandoffInstrumentedTest {
    private class QueuedDispatcher:CoroutineDispatcher() {
        val queue=LinkedBlockingQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable){queue.add(block)}
        fun next():Runnable=checkNotNull(queue.poll(10,TimeUnit.SECONDS)){"Dispatcher handoff did not arrive"}
    }
    private fun owner(bitmap:Bitmap)=OwnedImage(bitmap,DecodedImageInfo(
        ImageBounds(3,3,ImageFormat.PNG,1),ExifOrientation.NORMAL,OrientationEvidence.PRESENT,
        1,3,3,36,36,emptySet()))

    @Test fun cancellationAfterIoCompletesBeforeCallerResumeRecyclesActualBitmap() {
        val bitmap=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888)
        val caller=QueuedDispatcher()
        val io=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope=CoroutineScope(caller+SupervisorJob())
        val decoded=AtomicReference<OwnedImage>()
        try {
            val job=scope.launch {
                AndroidImageDecoder.decodeOwned(io){owner(bitmap).also{decoded.set(it)}}.use {
                    fail("Cancelled dispatcher handoff delivered image ownership")
                }
            }
            caller.next().run() // starts decode and suspends while switching to IO
            val resume=caller.next() // IO finished; result is queued but not yet delivered
            assertNotNull(decoded.get())
            assertFalse(bitmap.isRecycled)
            job.cancel()
            resume.run()
            runBlocking{withTimeout(10000){job.join()}}
            assertTrue(job.isCancelled)
            assertTrue("Undelivered owner must close after prompt cancellation",decoded.get().isClosed)
            assertTrue("Undelivered Android bitmap must be recycled",bitmap.isRecycled)
        }finally{scope.cancel();io.close();if(!bitmap.isRecycled)bitmap.recycle()}
    }

    @Test fun successfulCallerResumeTransfersOwnershipUntilCallerCloses() {
        val bitmap=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888)
        val caller=QueuedDispatcher()
        val io=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope=CoroutineScope(caller+SupervisorJob())
        val delivered=CompletableDeferred<OwnedImage>()
        try {
            val job=scope.launch {delivered.complete(AndroidImageDecoder.decodeOwned(io){owner(bitmap)})}
            caller.next().run()
            caller.next().run()
            val image=runBlocking{withTimeout(10000){delivered.await()}}
            assertFalse(image.isClosed);assertFalse(bitmap.isRecycled)
            image.close();image.close()
            assertTrue(bitmap.isRecycled)
            runBlocking{withTimeout(10000){job.join()}}
        }finally{scope.cancel();io.close();if(!bitmap.isRecycled)bitmap.recycle()}
    }
}
