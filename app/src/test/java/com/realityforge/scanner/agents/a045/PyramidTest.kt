package com.realityforge.scanner.agents.a045

import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.geometry.PixelTransform
import com.realityforge.scanner.engine.imaging.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class PyramidTest {
    @Test fun tiledIdentityAndAllExifOrientationsAgreeWithIndependentRawSamples() {
        val w = 9; val h = 7; val source = IntArray(w*h) { 0xff000000.toInt() or ((it%w)*20 shl 16) or ((it/w)*25 shl 8) }
        for (orientation in 1..8) {
            val t = PixelTransform.exif(w,h,orientation)
            val lens = LensProjection(20.0,21.0,(t.width-1)/2.0,(t.height-1)/2.0)
            fun render(rows: Int): Pair<IntArray,ByteArray> {
                val pixels = IntArray(t.width*t.height); val mask = ByteArray(pixels.size)
                PyramidRectifier.tiles(source,w,h,t,lens,lens,t.width,t.height,rows) { tile ->
                    tile.pixels.copyInto(pixels,tile.firstRow*t.width); tile.validity.copyInto(mask,tile.firstRow*t.width)
                }
                return pixels to mask
            }
            val a=render(1);val b=render(4);assertArrayEquals(a.first,b.first);assertArrayEquals(a.second,b.second)
            for(y in 0 until t.height)for(x in 0 until t.width){val (sx,sy)=t.inverse(x.toDouble(),y.toDouble()); val index=y*t.width+x
                if(sx>=0 && sy>=0 && sx<w-1 && sy<h-1){assertEquals(1,a.second[index].toInt());assertEquals(source[sy.toInt()*w+sx.toInt()],a.first[index])}
                else assertEquals(0,a.second[index].toInt())
            }
        }
    }
    @Test fun cacheRoundTripCorruptionAndIdentityChanges() {
        val directory=Files.createTempDirectory("rf-pyramid").toFile()
        try { val t=PixelTransform.resize(8,6,4,3);val key=PyramidLevel.identity("a".repeat(64),"calibration-A",t)
            val level=PyramidLevel(key,4,3,t,IntArray(12){it},ByteArray(12){1}); val store=PyramidStore(directory,2048)
            assertTrue(store.store(level));val loaded=store.load(key,t)!!;assertArrayEquals(level.pixels(),loaded.pixels());loaded.close()
            val f=directory.resolve("$key.rfp");val bad=f.readBytes();bad[bad.size-1]=(bad.last().toInt() xor 1).toByte();f.writeBytes(bad)
            assertNull(store.load(key,t));assertNotEquals(key,PyramidLevel.identity("a".repeat(64),"calibration-B",t))
            level.close();try{level.pixels();fail("closed buffers escaped")}catch(_:IllegalStateException){}
        }finally {directory.deleteRecursively()}
    }
    @Test fun callerMutationCannotChangeLevelAndBudgetFailureLeavesNoPartialFile() {
        val t=PixelTransform.identity(20,20);val key=PyramidLevel.identity("b".repeat(64),"fixed-calibration",t)
        val pixels=IntArray(400){it};val valid=ByteArray(400){1};val level=PyramidLevel(key,20,20,t,pixels,valid)
        pixels[0]=12345;valid[0]=0;val copy=level.pixels();copy[1]=999
        assertEquals(0,level.pixels()[0]);assertEquals(1,level.pixels()[1]);assertEquals(1,level.validity()[0].toInt())
        val directory=Files.createTempDirectory("rf-pyramid-budget").toFile()
        try {assertFalse(PyramidStore(directory,1024).store(level));assertTrue(directory.listFiles()!!.none { it.name.endsWith(".rfp") || it.name.endsWith(".partial") })}
        finally {level.close();directory.deleteRecursively()}
    }
    @Test fun cacheRejectsMismatchedTransformEvenWithSameTargetDimensions() {
        val t=PixelTransform.resize(8,6,4,3);val other=PixelTransform.resize(12,9,4,3)
        val key=PyramidLevel.identity("c".repeat(64),"calibration",t)
        val directory=Files.createTempDirectory("rf-pyramid-transform").toFile()
        try {val level=PyramidLevel(key,4,3,t,IntArray(12),ByteArray(12){1});val store=PyramidStore(directory,2048)
            assertTrue(store.store(level));assertNull(store.load(key,other));level.close()}
        finally {directory.deleteRecursively()}
    }
    @Test fun cancellationPublishesNoTile() {
        var published=0
        try {PyramidRectifier.tiles(IntArray(12),4,3,PixelTransform.identity(4,3),LensProjection(4.0,4.0,1.5,1.0),LensProjection(4.0,4.0,1.5,1.0),4,3,cancelled={true}){published++};fail("cancel accepted")}
        catch(_:CancellationException){};assertEquals(0,published)
    }
}
