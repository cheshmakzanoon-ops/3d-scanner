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
            val level=PyramidLevel(key,4,3,t,IntArray(12){0xff000000.toInt() or it},ByteArray(12){1}); val store=PyramidStore(directory,2048)
            assertTrue(store.store(level));val loaded=store.load(key,t)!!;assertArrayEquals(level.pixels(),loaded.pixels());loaded.close()
            val f=directory.resolve("$key.rfp");val bad=f.readBytes();bad[bad.size-1]=(bad.last().toInt() xor 1).toByte();f.writeBytes(bad)
            assertNull(store.load(key,t));assertNotEquals(key,PyramidLevel.identity("a".repeat(64),"calibration-B",t))
            level.close();try{level.pixels();fail("closed buffers escaped")}catch(_:IllegalStateException){}
        }finally {directory.deleteRecursively()}
    }
    @Test fun callerMutationCannotChangeLevelAndBudgetFailureLeavesNoPartialFile() {
        val t=PixelTransform.identity(20,20);val key=PyramidLevel.identity("b".repeat(64),"fixed-calibration",t)
        val pixels=IntArray(400){0xff000000.toInt() or it};val valid=ByteArray(400){1};val level=PyramidLevel(key,20,20,t,pixels,valid)
        pixels[0]=12345;valid[0]=0;val copy=level.pixels();copy[1]=999
        assertEquals(0xff000000.toInt(),level.pixels()[0]);assertEquals(0xff000001.toInt(),level.pixels()[1]);assertEquals(1,level.validity()[0].toInt())
        val directory=Files.createTempDirectory("rf-pyramid-budget").toFile()
        try {assertFalse(PyramidStore(directory,1024).store(level));assertTrue(directory.listFiles()!!.none { it.name.endsWith(".rfp") || it.name.endsWith(".partial") })}
        finally {level.close();directory.deleteRecursively()}
    }
    @Test fun cacheRejectsMismatchedTransformEvenWithSameTargetDimensions() {
        val t=PixelTransform.resize(8,6,4,3);val other=PixelTransform.resize(12,9,4,3)
        val key=PyramidLevel.identity("c".repeat(64),"calibration",t)
        val directory=Files.createTempDirectory("rf-pyramid-transform").toFile()
        try {val level=PyramidLevel(key,4,3,t,IntArray(12){0xff000000.toInt()},ByteArray(12){1});val store=PyramidStore(directory,2048)
            assertTrue(store.store(level));assertNull(store.load(key,other));level.close()}
        finally {directory.deleteRecursively()}
    }
    @Test fun alphaHolesAndPartialAlphaStayInvalidAndTileBuffersCannotBeMutated() {
        val t=PixelTransform.identity(5,5);val lens=LensProjection(10.0,11.0,2.0,2.0)
        val source=IntArray(25){0xff334455.toInt()};source[2*5+2]=0x80334455.toInt();source[1*5+1]=0
        fun render(rows:Int):Pair<IntArray,ByteArray>{val pixels=IntArray(25);val mask=ByteArray(25)
            PyramidRectifier.tiles(source,5,5,t,lens,lens,5,5,rows){tile->
                val p=tile.pixels;val m=tile.validity;p.copyInto(pixels,tile.firstRow*5);m.copyInto(mask,tile.firstRow*5)
                if(p.isNotEmpty()){p[0]=123;assertNotEquals(123,tile.pixels[0])}
            };return pixels to mask}
        val a=render(1);val b=render(4);assertArrayEquals(a.first,b.first);assertArrayEquals(a.second,b.second)
        assertEquals(0,a.second[2*5+2].toInt());assertEquals(0,a.second[1*5+1].toInt());assertEquals(0,a.first[2*5+2])
        val directory=Files.createTempDirectory("rf-pyramid-alpha").toFile()
        try {val key=PyramidLevel.identity("d".repeat(64),"opaque-four-neighbour-mask",t);val level=PyramidLevel(key,5,5,t,a.first,a.second)
            val store=PyramidStore(directory,2048);assertTrue(store.store(level));val cached=store.load(key,t)!!
            assertArrayEquals(a.first,cached.pixels());assertArrayEquals(a.second,cached.validity());cached.close();level.close()}
        finally {directory.deleteRecursively()}
    }
    @Test fun productionUprightRgbaAdapterCachesIdenticalMaskedPhotographs() {
        val w=6;val h=5;val t=PixelTransform.resize(12,10,w,h);val lens=LensProjection(10.0,11.0,2.5,2.0)
        val source=IntArray(w*h){0xff6688aa.toInt()};source[2*w+2]=0x806688aa.toInt()
        val directory=Files.createTempDirectory("rf-pyramid-producer").toFile()
        try {val store=PyramidStore(directory,4096);val a=ByteArray(w*h*4);val b=ByteArray(a.size)
            var decodes=0
            val first=PyramidRectifier.writeUprightRgba({decodes++;source},w,h,lens,a,0,"e".repeat(64),"measured-lens-A",t,store)
            val second=PyramidRectifier.writeUprightRgba({decodes++;source},w,h,lens,b,0,"e".repeat(64),"measured-lens-A",t,store)
            assertFalse(first.hit);assertTrue(first.stored);assertTrue(second.hit);assertArrayEquals(a,b);assertEquals(1,decodes)
            assertEquals(0,a[(2*w+2)*4+3].toInt())
        }finally{directory.deleteRecursively()}
    }
    @Test fun cancellationPublishesNoTile() {
        var published=0
        try {PyramidRectifier.tiles(IntArray(12),4,3,PixelTransform.identity(4,3),LensProjection(4.0,4.0,1.5,1.0),LensProjection(4.0,4.0,1.5,1.0),4,3,cancelled={true}){published++};fail("cancel accepted")}
        catch(_:CancellationException){};assertEquals(0,published)
    }
}
