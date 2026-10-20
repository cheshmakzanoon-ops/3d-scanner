package com.realityforge.scanner.engine.imaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.realityforge.scanner.engine.LensProjection
import com.realityforge.scanner.engine.geometry.PixelTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PyramidRectifierTest {
    @Test fun asymmetricGradientUsesExactPixelCentresAndInvalidBorderMask() {
        val input=IntArray(8*6){0xff000000.toInt() or ((it%8)*25 shl 16) or ((it/8)*35 shl 8)}
        val lens=LensProjection(30.0,29.0,3.1,2.2)
        var valid=0
        PyramidRectifier.tiles(input,8,6,PixelTransform.identity(8,6),lens,lens,8,6,2){tile ->
            for(i in tile.pixels.indices)if(tile.validity[i].toInt()==1){assertEquals(input[tile.firstRow*8+i],tile.pixels[i]);valid++}
        }
        assertTrue(valid>=30)
    }
}
