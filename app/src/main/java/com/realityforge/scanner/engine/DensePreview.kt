package com.realityforge.scanner.engine

import android.graphics.Bitmap
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanPhoto
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/**
 * M09: calibrated CPU depth, observed triangles and camera-ranked photographic UV textures.
 * No watertight guarantees, full photo quality, seam-free atlases, or physical scale.
 * All geometry is expressed in the already-registered sparse anchor coordinate frame.
 * If a view has a mismatched aspect/lens, reject the dense stage instead of inventing rays.
 */
internal object DensePreview {
    private const val EDGE = 256
    private const val TEXTURE_EDGE = 768 // independent, higher-res rectified photographic source
    private const val DEPTH_PLANES = 56
    private const val PIXEL_STRIDE = 3
    private const val MAX_VIEWS = 3

    suspend fun reconstruct(
        repository: ProjectRepository,
        projectId: String,
        artifactKey: String,
        photos: List<ScanPhoto>,
        cameras: JSONArray,
        sparsePoints: JSONArray,
        fallbackFocal35: Double,
        profile: LensProfile?,
    ): JSONObject {
        require(cameras.length() >= 2 && sparsePoints.length() >= 30) {
            "Dense stereo requires two registered cameras and a usable sparse depth prior."
        }
        val chosen = (0 until min(MAX_VIEWS, cameras.length())).map { cameras.getJSONObject(it) }
        val photoLookup = photos.associateBy { it.id }
        val images = chosen.map { camera ->
            coroutineContext.ensureActive()
            val photo = requireNotNull(photoLookup[camera.getString("photoId")])
            val bitmap = ImageLoader.upright(repository.photoFile(photo), EDGE)
            try { photo to bitmap.copy(Bitmap.Config.ARGB_8888, false) }
            finally { bitmap.recycle() }
        }
        try {
            val width = images.first().second.width
            val height = images.first().second.height
            require(width in 32..640 && height in 32..640)
            val cameraRows = DoubleArray(images.size * 16)
            val rectified = ByteArray(images.size * width * height * 4)
            for ((index,pair) in images.withIndex()) {
                coroutineContext.ensureActive()
                val (photo, image) = pair
                require(abs(image.width.toDouble() / image.height - width.toDouble() / height) < .002) {
                    "Dense preview photographs have incompatible aspect ratios."
                }
                // Reframe same-aspect captures to a COMMON pixel grid before producing rays.
                val resized = if (image.width == width && image.height == height) image
                    else Bitmap.createScaledBitmap(image,width,height,true)
                try {
                    val lens = profile?.forScan(width,height,photo.focal35mm) ?:
                        LensProjection.estimated(width,height,
                            ImageLoader.focalPixels(width,height,photo.focal35mm ?: fallbackFocal35))
                    val camera = chosen[index]
                    val rotation = camera.getJSONArray("rotation")
                    val translation = camera.getJSONArray("translation")
                    for (k in 0 until 9) cameraRows[index*16+k] = rotation.getDouble(k)
                    for (k in 0 until 3) cameraRows[index*16+9+k] = translation.getDouble(k)
                    cameraRows[index*16+12] = lens.fx
                    cameraRows[index*16+13] = lens.fy
                    cameraRows[index*16+14] = lens.cx
                    cameraRows[index*16+15] = lens.cy
                    val source = IntArray(width*height)
                    resized.getPixels(source,0,width,0,0,width,height)
                    rectify(source,width,height,lens,rectified,index*width*height*4)
                } finally { if (resized !== image) resized.recycle() }
            }
            // Quantile-bounded prior: never pretend sparse depth bounds are metric dimensions.
            val depths=ArrayList<Double>()
            for (k in 0 until sparsePoints.length()) {
                val xyz=sparsePoints.getJSONArray(k)
                val x=xyz.getDouble(0);val y=xyz.getDouble(1);val z=xyz.getDouble(2)
                for (camera in chosen) {
                    val r=camera.getJSONArray("rotation")
                    val t=camera.getJSONArray("translation")
                    val d=r.getDouble(6)*x+r.getDouble(7)*y+r.getDouble(8)*z+t.getDouble(2)
                    if (d.isFinite() && d>.01 && d<10000)depths.add(d)
                }
            }
            require(depths.size>=40){"Insufficient positive sparse depth priors"}
            depths.sort()
            val q10=depths[(depths.size*.10).toInt()]
            val q90=depths[(depths.size*.90).toInt()]
            val near=(q10*.75).coerceAtLeast(.02)
            val far=max(q90*1.30,near*1.4)
            require(far<100000 && far>near*1.05)
            coroutineContext.ensureActive()
            require(artifactKey.matches(Regex("sparse-m10-r[0-9]+-[0-9a-f]{16}"))) {
                "Invalid M09 artifact key."
            }
            val folder=repository.directory(projectId)
            val glb=File(folder,"$artifactKey.mesh.glb")
            val ply=File(folder,"$artifactKey.mesh.ply")
            val texturedGlb=File(folder,"$artifactKey.textured.glb")
            val atlasPng=File(folder,"$artifactKey.texture.png")
            val glbPartial=File(folder,"${glb.name}.partial")
            val plyPartial=File(folder,"${ply.name}.partial")
            val texturedPartial=File(folder,"${texturedGlb.name}.partial")
            val pngPartial=File(folder,"${atlasPng.name}.partial")
            var publishedGlb=false
            // The atlas samples the *same originals* at higher resolution than the
            // 256-pixel CPU stereo geometry. This never synthesizes photographic detail.
            var textureInputFailure: String? = null
            val photographic = try {
                preparePhotoAtlasInput(repository,images.map{it.first},cameraRows,
                    profile,fallbackFocal35,width,height)
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                textureInputFailure=failure.message ?: "Higher-resolution photograph preprocessing failed"
                null
            }
            try {
                val result=if(photographic!=null) {
                    val (texturePixels,textureWidth,textureHeight)=photographic
                    JSONObject(NativeCore.reconstructTexturedSurface(
                        rectified,width,height,texturePixels,textureWidth,textureHeight,cameraRows,
                        near,far,DEPTH_PLANES,PIXEL_STRIDE,
                        glbPartial.path,plyPartial.path,texturedPartial.path,pngPartial.path))
                } else JSONObject(NativeCore.reconstructSurface(rectified,width,height,cameraRows,
                    near,far,DEPTH_PLANES,PIXEL_STRIDE,glbPartial.path,plyPartial.path))
                coroutineContext.ensureActive()
                require(!result.has("error")) { result.optString("error","Depth or mesh solver failed") }
                val output=result.getJSONObject("dense")
                require(output.getInt("pointCount")>=60 && output.getInt("consistentPixels")>=60) {
                    "Insufficient reciprocal-consistent depth; recapture a textured rigid object."
                }
                output.put("sourcePhotos",chosen.size)
                    .put("rectifiedWidth",width).put("rectifiedHeight",height)
                    .put("depthPlanes",DEPTH_PLANES).put("pixelStride",PIXEL_STRIDE)
                    .put("nearDepthInAnchorUnits",near).put("farDepthInAnchorUnits",far)
                    .put("photographsRectified",true)
                    .put("calibrationProfileApplied",profile!=null)
                    .put("limitations","Bounded CPU stereo with optional actual photographic UV atlas. Open mesh, arbitrary scale, no global appearance or completeness guarantee")
                val composite=JSONObject().put("dense",output)
                val mesh=result.optJSONObject("mesh")
                if (mesh!=null && mesh.optInt("triangleCount")>=40 &&
                    mesh.optInt("vertexCount")>=30 &&
                    glbPartial.isFile && plyPartial.isFile &&
                    glbPartial.length()>100 && plyPartial.length()>100) {
                    coroutineContext.ensureActive()
                    FileOutputStream(glbPartial,true).use { it.fd.sync() }
                    FileOutputStream(plyPartial,true).use { it.fd.sync() }
                    check(glbPartial.renameTo(glb)) { "Cannot publish surface GLB" }
                    publishedGlb=true
                    check(plyPartial.renameTo(ply)) { "Cannot publish surface PLY" }
                    composite.put("mesh",mesh)
                    // Texture errors do NOT invalidate the already-published M08 mesh.
                    val texture=result.optJSONObject("texture")
                    if(texture!=null && texture.optInt("triangleCount")>=40 &&
                        texture.optDouble("faceCoverage")>=.10 &&
                        texturedPartial.isFile && pngPartial.isFile &&
                        texturedPartial.length()>256 && pngPartial.length()>128) {
                        try {
                            coroutineContext.ensureActive()
                            FileOutputStream(texturedPartial,true).use { it.fd.sync() }
                            FileOutputStream(pngPartial,true).use { it.fd.sync() }
                            check(texturedPartial.renameTo(texturedGlb)) { "Cannot publish textured GLB" }
                            check(pngPartial.renameTo(atlasPng)) { "Cannot publish photo atlas PNG" }
                            composite.put("texture",texture)
                        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            texturedGlb.delete();atlasPng.delete()
                            composite.put("textureError",failure.message ?: "Cannot publish photographic texture")
                        }
                    } else composite.put("textureError",textureInputFailure ?: result.optString("textureError",
                        "Insufficient visible photo coverage for UV texturing"))
                } else {
                    composite.put("meshError",result.optString("meshError",
                        "Surface confidence/coverage gate rejected geometry"))
                }
                return composite
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            finally {
                glbPartial.delete()
                plyPartial.delete()
                texturedPartial.delete()
                pngPartial.delete()
                if (publishedGlb && !ply.isFile) glb.delete()
            }
        } finally { images.forEach { it.second.recycle() } }
    }

    /** Collect actual photograph RGB at up to 768px using the SAME camera/crop.
     * Decode one bitmap at a time and rectify only into bounded destination pixels.
     * Intrinsics are scaled from the depth stage so all photo UVs share the gauge.
     */
    private suspend fun preparePhotoAtlasInput(
        repository: ProjectRepository, photos: List<ScanPhoto>, cameraRows: DoubleArray,
        profile: LensProfile?, fallbackFocal35: Double,
        depthWidth: Int, depthHeight: Int,
    ): Triple<ByteArray,Int,Int> {
        val first=ImageLoader.upright(repository.photoFile(photos.first()),TEXTURE_EDGE)
        val w=first.width;val h=first.height
        first.recycle()
        require(w in 32..1024 && h in 32..1024 && w.toLong()*h<=1_048_576)
        val data=ByteArray(photos.size*w*h*4)
        for ((index,photo) in photos.withIndex()) {
            coroutineContext.ensureActive()
            val image=ImageLoader.upright(repository.photoFile(photo),TEXTURE_EDGE)
            try {
                require(abs(image.width.toDouble()/image.height - w.toDouble()/h)<.002) {
                    "Photograph dimensions differ; cannot safely project shared UV charts"
                }
                val scaled=if(image.width==w && image.height==h) image
                    else Bitmap.createScaledBitmap(image,w,h,true)
                try {
                    // A calibrated profile is revalidated for each original photo,
                    // even though the actual projection uses the depth camera's
                    // consistent scaled K (with principal-point pixel-centre mapping).
                    val nativeLens = profile?.forScan(w,h,photo.focal35mm) ?:
                        LensProjection.estimated(w,h,ImageLoader.focalPixels(w,h,
                            photo.focal35mm ?: fallbackFocal35))
                    val sx=w.toDouble()/depthWidth
                    val sy=h.toDouble()/depthHeight
                    val lowFx=cameraRows[index*16+12];val lowFy=cameraRows[index*16+13]
                    val lowCx=cameraRows[index*16+14];val lowCy=cameraRows[index*16+15]
                    val corrected=LensProjection(lowFx*sx,lowFy*sy,(lowCx+.5)*sx-.5,
                        (lowCy+.5)*sy-.5,nativeLens.k1,nativeLens.k2,
                        nativeLens.p1,nativeLens.p2,nativeLens.k3)
                    val argb=IntArray(w*h)
                    scaled.getPixels(argb,0,w,0,0,w,h)
                    rectify(argb,w,h,corrected,data,index*w*h*4)
                } finally { if(scaled!==image)scaled.recycle() }
            } finally { image.recycle() }
        }
        return Triple(data,w,h)
    }

    private fun rectify(source: IntArray,w:Int,h:Int,lens: LensProjection,
                        target: ByteArray,offset:Int) {
        for(y in 0 until h)for(x in 0 until w){
            val out=offset+(y*w+x)*4
            val nx=(x-lens.cx)/lens.fx;val ny=(y-lens.cy)/lens.fy
            val (dx,dy)=lens.distort(nx,ny)
            val sx=dx*lens.fx+lens.cx;val sy=dy*lens.fy+lens.cy
            if(!sx.isFinite()||!sy.isFinite()||sx<0||sy<0||sx>=w-1||sy>=h-1) {
                target[out+3]=0;continue
            }
            val ix=sx.toInt();val iy=sy.toInt();val fx=sx-ix;val fy=sy-iy
            val pixels=intArrayOf(source[iy*w+ix],source[iy*w+ix+1],
                source[(iy+1)*w+ix],source[(iy+1)*w+ix+1])
            for(c in 0..2){
                val shift=16-8*c
                val a=(pixels[0] shr shift) and 255
                val b=(pixels[1] shr shift) and 255
                val d=(pixels[2] shr shift) and 255
                val e=(pixels[3] shr shift) and 255
                val color=(a*(1-fx)*(1-fy)+b*fx*(1-fy)+d*(1-fx)*fy+e*fx*fy)
                target[out+c]=color.roundToInt().coerceIn(0,255).toByte()
            }
            target[out+3]=255.toByte()
        }
    }
}
