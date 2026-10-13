package com.realityforge.scanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.realityforge.scanner.engine.DensePreview
import com.realityforge.scanner.engine.DenseWorkload
import com.realityforge.scanner.engine.LensProfile
import com.realityforge.scanner.viewer.PhotographicGlb
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.*

/** Photo files through the actual adapter, with known SYNTHETIC poses; not a complete SfM or handset benchmark. */
@RunWith(AndroidJUnit4::class)
class AdaptiveDensePipelineTest {
    @Test fun eightLocalPhotoFilesReachTheTexturedViewerWithBoundedWorkload() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RealityForgeApplication
        val repo=app.repository
        val id=repo.create("Synthetic eight-view adapter ${UUID.randomUUID()}")
        val originals=mutableListOf<File>()
        val width=160;val height=112;val f=180.0
        try {
            for (cam in 0 until 8) {
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val file=File.createTempFile("eight-view-$cam-", ".png",app.cacheDir).also { originals.add(it) }
                try {
                    val rgba=IntArray(width*height) { i ->
                        val x=(i%width-(width-1)/2.0)*5/f+cam*.55
                        val y=(i/width-(height-1)/2.0)*5/f
                        val c=(120+45*sin(x*10+y*3)+42*cos(x*20-y*12)+27*sin(x*37+y*25)).toInt().coerceIn(0,255)
                        (255 shl 24) or (c shl 16) or (c shl 8) or c
                    }
                    bitmap.setPixels(rgba,0,width,0,0,width,height)
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { bitmap.recycle() }
                assertTrue(repo.importPhoto(id,Uri.fromFile(file)))
            }
            val photos=repo.dao.photos(id)
            val cameras=JSONArray()
            for (i in photos.indices) cameras.put(JSONObject().put("photoId",photos[i].id)
                .put("rotation",JSONArray(listOf(1,0,0,0,1,0,0,0,1)))
                .put("translation",JSONArray(listOf(-i*.55,0.0,0.0))))
            val points=JSONArray()
            for (x in -15..50) for(y in -8..8) points.put(JSONArray(listOf(x*.1,y*.1,5.0)))
            val profile=LensProfile(width,height,f,f,(width-1)/2.0,(height-1)/2.0,
                0.0,0.0,0.0,0.0,0.0,0.0,10,null)
            val key="sparse-m15-r8-0123456789abcdef"
            val stages=mutableListOf<String>()
            val result=DensePreview.reconstruct(repo,id,key,photos,cameras,points,35.0,profile) { stage,_,_ -> stages.add(stage) }
            val dense=result.getJSONObject("dense")
            assertEquals(8,dense.getInt("sourcePhotos"))
            assertEquals(0,dense.getInt("excludedRegisteredCameraCount"))
            assertTrue(dense.getJSONArray("consistentPixelsPerView").getInt(7)>20)
            assertTrue(dense.getJSONObject("workload").getLong("plannedGridSamples")<=DenseWorkload.MAX_SAMPLES)
            assertTrue(stages.contains("Rectifying photographic texture sources"))
            assertTrue("Texturing failed: $result",result.has("texture"))
            val texture=result.getJSONObject("texture")
            assertTrue(texture.getInt("triangleCount")>100)
            val loaded=PhotographicGlb.read(repo.artifact(id,key,"textured.glb"))
            assertEquals(texture.getInt("triangleCount")*3,loaded.indices.size)
            for (photo in photos) assertTrue(repo.photoFile(photo).isFile)
        } finally { originals.forEach { it.delete() };repo.delete(id) }
    }
}
