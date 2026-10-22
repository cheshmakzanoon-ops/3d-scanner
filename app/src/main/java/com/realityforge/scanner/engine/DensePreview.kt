package com.realityforge.scanner.engine

import android.graphics.Bitmap
import com.realityforge.scanner.imaging.AndroidImageDecoder
import androidx.exifinterface.media.ExifInterface
import com.realityforge.scanner.engine.geometry.PixelTransform
import com.realityforge.scanner.engine.imaging.PyramidRectifier
import com.realityforge.scanner.engine.imaging.PyramidStore
import com.realityforge.scanner.engine.dense.DenseViewSelection.Plan as DenseSelectionPlan
import com.realityforge.scanner.engine.pipeline.withNativeStageProgress
import com.realityforge.scanner.engine.pipeline.DensePreviewProfile
import com.realityforge.scanner.engine.pipeline.DenseExecutionPolicy
import com.realityforge.scanner.engine.pipeline.StableDigest
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanPhoto
import com.realityforge.scanner.engine.pipeline.PipelineArtifactReference
import com.realityforge.scanner.engine.pipeline.PipelineFailure
import com.realityforge.scanner.engine.pipeline.PipelineInputSnapshot
import com.realityforge.scanner.engine.pipeline.PipelineStage
import com.realityforge.scanner.engine.pipeline.PipelineStageOutcome
import com.realityforge.scanner.engine.pipeline.VerifiedSurfaceCheckpoint
import com.realityforge.scanner.engine.pipeline.sha256Utf8
import com.realityforge.scanner.processing.resources.ResourceWorkloadSelection
import com.realityforge.scanner.processing.resources.ResourceCheckpointPause
import com.realityforge.scanner.engine.nativebridge.NativeResultJson
import com.realityforge.scanner.engine.nativebridge.NativeResourceExhaustedException
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.coroutines.coroutineContext
import kotlin.math.*

/**
 * M18: resumable per-reference, adaptive eight-view calibrated depth, observed triangles and photographic UV textures.
 * No watertight guarantees, full photo quality, seam-free atlases, or physical scale.
 * All geometry is expressed in the already-registered sparse anchor coordinate frame.
 * If a view has a mismatched aspect/lens, reject the dense stage instead of inventing rays.
 */
internal data class DensePreviewResult(
    val payload: JSONObject,
    val outcomes: List<PipelineStageOutcome>,
)

internal object DensePreview {
    private const val DEPTH_PLANES = 56

    /** Compatibility adapter retained for existing callers and instrumentation. */
    suspend fun reconstruct(
        repository: ProjectRepository,
        projectId: String,
        artifactKey: String,
        photos: List<ScanPhoto>,
        cameras: JSONArray,
        sparsePoints: JSONArray,
        fallbackFocal35: Double,
        profile: LensProfile?,
        nativeToken: Long = 0L,
        selection: DenseSelectionPlan? = null,
        executionProfile: DensePreviewProfile = DensePreviewProfile.Compatibility,
        acceptedSparseSupport: Map<Int,Set<String>> = emptyMap(),
        sparseThresholdNormalized: Double? = null,
        onProgress: suspend (String, Int, Int) -> Unit = { _, _, _ -> },
    ): JSONObject = reconstructInternal(
        repository, projectId, artifactKey, photos, cameras, sparsePoints,
        fallbackFocal35, profile, nativeToken, null, ResourceWorkloadSelection.COMPACT, null,
        selection, executionProfile, acceptedSparseSupport, sparseThresholdNormalized, onProgress,
    ).payload

    /** Typed C2 adapter used by SparsePipeline while dense providers are extracted incrementally. */
    suspend fun reconstructTyped(
        repository: ProjectRepository,
        projectId: String,
        artifactKey: String,
        photos: List<ScanPhoto>,
        cameras: JSONArray,
        sparsePoints: JSONArray,
        fallbackFocal35: Double,
        profile: LensProfile?,
        nativeToken: Long,
        pipelineSnapshot: PipelineInputSnapshot,
        resourceSelection: ResourceWorkloadSelection = ResourceWorkloadSelection.COMPACT,
        surfaceCheckpoint: VerifiedSurfaceCheckpoint? = null,
        selection: DenseSelectionPlan? = null,
        executionProfile: DensePreviewProfile = DensePreviewProfile.Compatibility,
        acceptedSparseSupport: Map<Int,Set<String>> = emptyMap(),
        sparseThresholdNormalized: Double? = null,
        onProgress: suspend (String, Int, Int) -> Unit = { _, _, _ -> },
    ): DensePreviewResult = reconstructInternal(
        repository, projectId, artifactKey, photos, cameras, sparsePoints,
        fallbackFocal35, profile, nativeToken, pipelineSnapshot, resourceSelection, surfaceCheckpoint,
        selection, executionProfile, acceptedSparseSupport, sparseThresholdNormalized, onProgress,
    )

    private suspend fun reconstructInternal(
        repository: ProjectRepository,
        projectId: String,
        artifactKey: String,
        photos: List<ScanPhoto>,
        cameras: JSONArray,
        sparsePoints: JSONArray,
        fallbackFocal35: Double,
        profile: LensProfile?,
        nativeToken: Long,
        pipelineSnapshot: PipelineInputSnapshot?,
        resourceSelection: ResourceWorkloadSelection,
        surfaceCheckpoint: VerifiedSurfaceCheckpoint?,
        selection: DenseSelectionPlan?,
        executionProfile: DensePreviewProfile,
        acceptedSparseSupport: Map<Int,Set<String>>,
        sparseThresholdNormalized: Double?,
        onProgress: suspend (String, Int, Int) -> Unit,
    ): DensePreviewResult {
        require(cameras.length() in 2..(if(selection==null)12 else 250) && sparsePoints.length() in 30..30000) {
            "Dense stereo requires two registered cameras and a usable sparse depth prior."
        }
        require(artifactKey.matches(Regex("sparse-m16-r[0-9]+-[0-9a-f]{16}"))) {
            "Invalid reconstruction artifact key."
        }
        // The selector has verified the complete C8 dependency closure and exact current sparse
        // frame. An unchanged appearance/export job needs no image decoding or native geometry.
        if (DenseExecutionPolicy.supportsCompleteTextureReuse(executionProfile) && surfaceCheckpoint?.completeTexture != null) {
            return reuseCompleteResult(repository.directory(projectId), artifactKey, surfaceCheckpoint,
                pipelineSnapshot, onProgress)
        }
        require(executionProfile != DensePreviewProfile.VerifiedRefinedCompact || selection != null) { "Refined execution requires verified sparse view evidence" }
        val cameraIds = (0 until cameras.length()).map { cameras.getJSONObject(it).getString("photoId") }
        require(cameraIds.distinct().size == cameraIds.size) { "Repeated registered camera identity" }
        val selected = if (selection == null) DenseWorkload.selectCameraIndices(cameras.length(), resourceSelection.maximumViews).toList() else {
            require(selection.referencePhotoIds.size in 2..DenseWorkload.MAX_VIEWS) { "No connected verified dense reference set" }
            require(selection.sourcePhotoIds.keys == selection.referencePhotoIds.toSet())
            require(selection.sourcePhotoIds.values.all { it.size in 1..3 }) { "Dense reference lacks verified source candidates" }
            val activeIds = (selection.referencePhotoIds + selection.sourcePhotoIds.values.flatten()).distinct()
            require(activeIds.size <= DenseWorkload.MAX_VIEWS) { "Dense source plan exceeds admitted active-camera budget" }
            activeIds.map { id -> cameraIds.indexOf(id).also { require(it >= 0) { "Dense plan references an unregistered photograph" } } }
        }
        require(selected.size <= resourceSelection.maximumViews) { "Dense source plan exceeds admitted resource profile" }
        val chosen = selected.map { cameras.getJSONObject(it) }
        val photoLookup = photos.associateBy { it.id }
        val chosenPhotos = chosen.map { camera -> requireNotNull(photoLookup[camera.getString("photoId")]) }
        require(chosenPhotos.map { it.id }.toSet().size == chosenPhotos.size) { "Repeated camera/photo identity" }
        val edge = resourceSelection.depthEdge
        // At most one original decoder bitmap and its optional rescaled copy exist at a time.
        val dimensions = ImageLoader.upright(repository.photoFile(chosenPhotos.first()), edge).let { first ->
            try { first.width to first.height } finally { first.recycle() }
        }
        val width = dimensions.first; val height = dimensions.second
        val workload = DenseWorkload.plan(width, height, chosen.size, resourceSelection)
        org.opencv.core.Core.setNumThreads(workload.nativeThreads)
        val cameraRows = DoubleArray(chosen.size * 16)
        val rectified = ByteArray(workload.packedDepthBytes.toInt())
        val depthLevelIdentities = ArrayList<String>(chosen.size)
        for ((index,photo) in chosenPhotos.withIndex()) {
            coroutineContext.ensureActive()
            onProgress("Rectifying depth photographs", index, chosenPhotos.size)
            val camera = chosen[index]
            val lens = cameraLens(camera,width,height,photo,profile,fallbackFocal35)
            val rotation = camera.getJSONArray("rotation"); val translation = camera.getJSONArray("translation")
            for (k in 0 until 9) cameraRows[index*16+k] = rotation.getDouble(k)
            for (k in 0 until 3) cameraRows[index*16+9+k] = translation.getDouble(k)
            cameraRows[index*16+12] = lens.fx; cameraRows[index*16+13] = lens.fy
            cameraRows[index*16+14] = lens.cx; cameraRows[index*16+15] = lens.cy
            depthLevelIdentities += writePhotoLevel(repository,photo,edge,width,height,lens,rectified,index*width*height*4,
                calibrationProvenance(camera,profile,photo,fallbackFocal35))
        }
        onProgress("Rectifying depth photographs", chosenPhotos.size, chosenPhotos.size)
        val refined = executionProfile == DensePreviewProfile.VerifiedRefinedCompact
        val approvedAdjacency = if(refined) {
            val plan=requireNotNull(selection)
            chosenPhotos.flatMap { photo -> val sources=plan.sourcePhotoIds.getValue(photo.id)
                listOf(sources.size)+sources.map { id -> chosenPhotos.indexOfFirst {it.id==id}.also { require(it>=0) } }
            }.toIntArray()
        } else IntArray(0)
        val coarse = if(refined) prepareCoarseInput(rectified,width,height,cameraRows) else null
        val xyz = DoubleArray(sparsePoints.length()*3) { i -> sparsePoints.getJSONArray(i/3).getDouble(i%3) }
        val (near,far) = DenseWorkload.depthBounds(cameraRows,xyz,width,height)
        val sparsePriors=if(refined) prepareSparsePriors(sparsePoints,acceptedSparseSupport,chosenPhotos.map {it.id},cameraRows,width,height,sparseThresholdNormalized) else SparsePriors(DoubleArray(0),"compatibility-no-refined-priors")
        coroutineContext.ensureActive()
        require(artifactKey.matches(Regex("sparse-m16-r[0-9]+-[0-9a-f]{16}"))) {
            "Invalid reconstruction artifact key."
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
        // This attempt may remove only paths it creates. A leftover path is recovery evidence,
        // never proof that the current attempt owns it. C6 excludes competing project writers.
        for (destination in listOf(glb, ply, texturedGlb, atlasPng, glbPartial, plyPartial, texturedPartial, pngPartial)) {
            check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "A reconstruction output already exists; preserve it and retry project recovery."
            }
        }
        var publishedGlb=false
        // The atlas samples the *same originals* at higher resolution than the
        // adaptive CPU stereo geometry. This never synthesizes photographic detail.
        var textureInputFailure: String? = null
        val photographic = try {
            preparePhotoAtlasInput(repository,chosenPhotos,chosen,cameraRows,
                profile,fallbackFocal35,width,height,workload.textureEdge,onProgress)
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (paused: ResourceCheckpointPause) { throw paused }
        catch (exhausted: NativeResourceExhaustedException) { throw exhausted }
        catch (failure: Exception) {
            textureInputFailure=failure.message ?: "Higher-resolution photograph preprocessing failed"
            null
        }
        try {
            require(nativeToken>0) { "Missing scoped native operation token" }
            coroutineContext.ensureActive()
            onProgress("Solving multi-view depth and texture",0,1)
            val denseStageDigest=pipelineSnapshot?.identity(PipelineStage.DENSE)?.digest
            // Publication attempts have unique artifact names; reusable depth uses the immutable stage instead.
            val depthArtifactKey=if(pipelineSnapshot!=null)
                DepthCheckpointIdentity.contentGeometryKey(requireNotNull(denseStageDigest)) else artifactKey
            val baseIdentity=DepthCheckpointIdentity.fingerprint(
                depthArtifactKey,rectified,cameraRows,width,height,near,far,DEPTH_PLANES,workload.pixelStride,
                resourceSelectionIdentity = resourceSelection.geometryIdentitySha256)
            val identity=StableDigest().text("rf-002-dense-producer-v3").text(baseIdentity).text(executionProfile.name)
                .text(denseStageDigest ?: "compatibility-adapter")
                .text("044-approved-adjacency-v1|045-upright-rgba-v2")
                .text(DenseExecutionPolicy.depth(executionProfile))
            chosenPhotos.forEachIndexed { i,photo -> identity.text(photo.id).text(photo.sha256).text(depthLevelIdentities[i]) }
            identity.text(sparsePriors.identity).long(sparsePriors.rows.size.toLong())
            sparsePriors.rows.forEach {identity.long(it.toBits())}
            identity.long(approvedAdjacency.size.toLong());approvedAdjacency.forEach { identity.long(it.toLong()) }
            if(coarse!=null){identity.long(coarse.width.toLong()).long(coarse.height.toLong()).text(bytesSha256(coarse.pixels))
                coarse.cameras.forEach { identity.long(it.toBits()) }}
            val depthIdentity=identity.finish()
            // Atlas policy and photograph buffers cannot invalidate reusable depth maps.
            val textureInputIdentity=photographic?.let { source ->
                val textureIdentity=StableDigest().text("rf-002-photographic-input-v1").text(depthIdentity)
                    .text(pipelineSnapshot?.identity(PipelineStage.TEXTURE)?.digest ?: "compatibility-adapter")
                    .text(DenseExecutionPolicy.ATLAS_ENGINE)
                    .text(DenseExecutionPolicy.texture(executionProfile))
                    .long(source.width.toLong()).long(source.height.toLong()).text(bytesSha256(source.pixels))
                source.identities.forEach {textureIdentity.text(it)}
                textureIdentity.finish()
            }
            val checkpointDirectory=DepthCheckpointIdentity.directory(folder,depthIdentity)
            val reusable = surfaceCheckpoint?.takeIf { it.depthIdentity == depthIdentity &&
                it.depthDirectory.canonicalFile == checkpointDirectory.canonicalFile }
            val retextured = if (reusable != null && photographic != null) {
                val (texturePixels, textureWidth, textureHeight) = photographic
                val candidate = NativeResultJson.decode(NativeCore.textureAcceptedSurfaceCheckpointed(
                    rectified, width, height, texturePixels, textureWidth, textureHeight, cameraRows,
                    reusable.surfaceFile.path, texturedPartial.path, pngPartial.path, nativeToken,
                    checkpointDirectory.path, depthIdentity))
                // A corrupt or incompatible cache is recomputed through the normal solver. Native
                // cancellation and resource exhaustion have already been propagated by the decoder.
                candidate.takeUnless { it.optString("errorCode") == "CHECKPOINT_REJECTED" }
            } else if (reusable != null) {
                JSONObject(reusable.payload.toString()).apply {
                    remove("texture")
                    put("textureError", textureInputFailure ?: "Photographic sources are unavailable")
                    put("geometryReused", true)
                    put("surfaceCheckpointAvailable", true)
                    put("surfaceCheckpointWritten", false)
                    getJSONObject("dense").put("writtenDepthMaps", 0).put("resumedDepthMaps", 0)
                        .put("depthCheckpointWritten", 0).put("depthCheckpointResumed", 0)
                        .put("peakLuminanceBytes", 0)
                }
            } else null
            val result=withNativeStageProgress(nativeToken,onProgress) { retextured ?: if(photographic!=null) {
                val (texturePixels,textureWidth,textureHeight)=photographic
                if(refined) {
                    val level=requireNotNull(coarse)
                    NativeResultJson.decode(NativeCore.reconstructTexturedSurfaceRefinedCheckpointed(
                        rectified,width,height,texturePixels,textureWidth,textureHeight,cameraRows,
                        near,far,DEPTH_PLANES,workload.pixelStride,
                        glbPartial.path,plyPartial.path,texturedPartial.path,pngPartial.path,
                        sparsePriors.rows,level.pixels,level.width,level.height,level.cameras,depthIdentity,
                        approvedAdjacency,true,nativeToken,checkpointDirectory.path,depthIdentity))
                } else NativeResultJson.decode(NativeCore.reconstructTexturedSurfaceCheckpointed(
                    rectified,width,height,texturePixels,textureWidth,textureHeight,cameraRows,
                    near,far,DEPTH_PLANES,workload.pixelStride,
                    glbPartial.path,plyPartial.path,texturedPartial.path,pngPartial.path,nativeToken,
                    checkpointDirectory.path,depthIdentity))
            } else {
                require(!refined) { "Refined photographic export requires valid photograph sources" }
                NativeResultJson.decode(NativeCore.reconstructSurfaceCheckpointed(rectified,width,height,cameraRows,
                near,far,DEPTH_PLANES,workload.pixelStride,glbPartial.path,plyPartial.path,nativeToken,
                checkpointDirectory.path,depthIdentity)) } }
            coroutineContext.ensureActive()
            onProgress("Solving multi-view depth and texture",1,1)
            // C++ cancellation must not be treated as a nonfatal M08 mesh/texture
            // quality warning: no partial asset may be published after Pause.
            require(!result.has("error")) { result.optString("error","Depth or mesh solver failed") }
            val geometryReused = result.optBoolean("geometryReused", false)
            if (geometryReused) {
                val accepted = requireNotNull(reusable)
                copyVerified(accepted.meshGlb, glbPartial, accepted.meshGlbBytes, accepted.meshGlbSha256)
                copyVerified(accepted.meshPly, plyPartial, accepted.meshPlyBytes, accepted.meshPlySha256)
            }
            val output=result.getJSONObject("dense")
            require(output.getInt("pointCount")>=60 && output.getInt("consistentPixels")>=60) {
                "Insufficient reciprocal-consistent depth; recapture a textured rigid object."
            }
            if(refined){
                val executed=output.getJSONArray("sourceViewIndices")
                require(executed.length()==chosenPhotos.size) { "Approved source plan reference count changed" }
                chosenPhotos.forEachIndexed { ref,photo ->
                    val expected=requireNotNull(selection).sourcePhotoIds.getValue(photo.id).map { id -> chosenPhotos.indexOfFirst { it.id==id } }
                    val actual=executed.getJSONArray(ref)
                    require(actual.length()==expected.size && expected.indices.all { actual.getInt(it)==expected[it] }) { "Native execution did not preserve the approved source plan" }
                }
            }
            output.put("sourcePhotos",chosen.size)
                .put("rectifiedWidth",width).put("rectifiedHeight",height)
                .put("depthPlanes",DEPTH_PLANES).put("pixelStride",workload.pixelStride)
                .put("selectedCameraIndices",JSONArray(selected.toList()))
                .put("selectedPhotoIds",JSONArray(chosenPhotos.map { it.id }))
                .put("excludedRegisteredCameraCount",cameras.length()-chosen.size)
                .put("selectionMethod",if(selection!=null)"verified_sparse_tracks" else "legacy_index_compatibility")
                .put("selectionPolicyVersion",if(selection!=null)"verified-track-cover-connected-8x3-v1" else "legacy-index-m18")
                .put("executionProfile",executionProfile.name)
                .put("executedSourceViewIndices",output.getJSONArray("sourceViewIndices"))
                .put("executedSourcePhotoIds",JSONArray().also { actual ->
                    val sources=output.getJSONArray("sourceViewIndices")
                    require(sources.length()==chosenPhotos.size) { "Native source plan lost a reference" }
                    for(ref in chosenPhotos.indices){val row=sources.getJSONArray(ref);val ids=JSONArray()
                        for(k in 0 until row.length()){val index=row.getInt(k);require(index in chosenPhotos.indices && index!=ref);ids.put(chosenPhotos[index].id)}
                        actual.put(ids)
                    }
                })
                .put("rectifierProducerVersion","upright-rgba-v2|opaque-four-neighbour|invalid-border")
                .put("rectifiedLevelIdentities",JSONArray(depthLevelIdentities))
                .put("approvedSourceAdjacencyApplied",refined)
                .put("depthInputIdentity",depthIdentity)
                .put("refinementInputIdentity",if(refined)depthIdentity else JSONObject.NULL)
                .put("coarseInputWidth",coarse?.width ?: 0).put("coarseInputHeight",coarse?.height ?: 0)
                .put("sparsePriorObservationCount",sparsePriors.rows.size/6)
                .put("sparsePriorBoundPolicy","normalized-threshold-over-measured-sine-parallax-v1; algorithmic bound, not physical sigma")
                .put("candidateSourcePhotoIds",selection?.let { JSONObject(it.sourcePhotoIds) } ?: JSONObject.NULL)
                .put("selectionExclusions",selection?.let { JSONObject(it.exclusions) } ?: JSONObject.NULL)
                .put("rectificationConvention",LensProjection.COORDINATE_CONVENTION)
                .put("workload",JSONObject().put("memoryTier",workload.memoryTier)
                    .put("depthEdgeLimit",edge).put("textureEdgeLimit",workload.textureEdge)
                    .put("sampledGridLimit",workload.sampledGridLimit)
                    .put("resourceSelectionVersion",resourceSelection.version)
                    .put("resourceSelectionIdentity",resourceSelection.identitySha256)
                    .put("nativeThreads",workload.nativeThreads)
                    .put("plannedGridSamples",workload.sampledGridPixels)
                    .put("packedDepthBytes",workload.packedDepthBytes)
                    .put("maximumCachedLuminanceBytes",workload.maximumCachedLuminanceBytes)
                    .put("memoryMeasurement","allocation bounds only; not process RSS"))
                .put("nearDepthInAnchorUnits",near).put("farDepthInAnchorUnits",far)
                .put("depthCheckpointSchema",18)
                .put("depthCheckpointIdentity",depthIdentity)
                .put("surfaceCheckpointAvailable", result.optBoolean("surfaceCheckpointAvailable", false))
                .put("surfaceCheckpointWritten", result.optBoolean("surfaceCheckpointWritten", false))
                .put("geometryReused", geometryReused)
                .put("depthCheckpointResumed",output.optInt("resumedDepthMaps"))
                .put("depthCheckpointWritten",output.optInt("writtenDepthMaps"))
                .put("photographsRectified",true)
                .put("calibrationProfileApplied",profile!=null)
                .put("refinedIntrinsicsApplied",chosen.any {it.has("refinedIntrinsics")})
                .put("limitations","Bounded CPU stereo with optional actual photographic UV atlas. Open mesh, arbitrary scale, no global appearance or completeness guarantee")
            val composite=JSONObject().put("dense",output)
                .put("geometryReused", geometryReused)
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
                val texture=result.optJSONObject("texture")?.also {
                    it.put("textureInputIdentity",textureInputIdentity ?: JSONObject.NULL)
                }
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
                    catch (paused: ResourceCheckpointPause) { throw paused }
                    catch (exhausted: NativeResourceExhaustedException) { throw exhausted }
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
            val outcomes = pipelineSnapshot?.let { snapshot ->
                typedOutcomes(snapshot, artifactKey, composite, glb, ply, texturedGlb, atlasPng)
            }.orEmpty()
            return DensePreviewResult(composite, outcomes)
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        finally {
            glbPartial.delete()
            plyPartial.delete()
            texturedPartial.delete()
            pngPartial.delete()
            if (publishedGlb && !ply.isFile) glb.delete()
        }
    }

    private suspend fun reuseCompleteResult(
        folder: File,
        artifactKey: String,
        accepted: VerifiedSurfaceCheckpoint,
        snapshot: PipelineInputSnapshot?,
        onProgress: suspend (String, Int, Int) -> Unit,
    ): DensePreviewResult {
        val texture = requireNotNull(accepted.completeTexture)
        val glb = File(folder, "$artifactKey.mesh.glb")
        val ply = File(folder, "$artifactKey.mesh.ply")
        val textured = File(folder, "$artifactKey.textured.glb")
        val atlas = File(folder, "$artifactKey.texture.png")
        onProgress("Reusing verified geometry and photographic texture", 0, 4)
        copyVerified(accepted.meshGlb, glb, accepted.meshGlbBytes, accepted.meshGlbSha256)
        onProgress("Reusing verified geometry and photographic texture", 1, 4)
        copyVerified(accepted.meshPly, ply, accepted.meshPlyBytes, accepted.meshPlySha256)
        onProgress("Reusing verified geometry and photographic texture", 2, 4)
        copyVerified(texture.texturedGlb, textured, texture.texturedGlbBytes, texture.texturedGlbSha256)
        onProgress("Reusing verified geometry and photographic texture", 3, 4)
        copyVerified(texture.atlasPng, atlas, texture.atlasPngBytes, texture.atlasPngSha256)
        onProgress("Reusing verified geometry and photographic texture", 4, 4)
        val payload = JSONObject(accepted.payload.toString()).put("geometryReused", true).put("textureReused", true)
        payload.getJSONObject("dense").put("geometryReused", true)
            .put("surfaceCheckpointAvailable", true).put("surfaceCheckpointWritten", false)
            .put("depthCheckpointWritten", 0).put("depthCheckpointResumed", 0)
            .put("writtenDepthMaps", 0).put("resumedDepthMaps", 0)
            .put("peakLuminanceBytes", 0)
        return DensePreviewResult(payload, snapshot?.let {
            typedOutcomes(it, artifactKey, payload, glb, ply, textured, atlas)
        }.orEmpty())
    }

    /** Copy only the exact C8-verified bytes. Existing accepted outputs are never replaced. */
    private suspend fun copyVerified(source: File, destination: File, bytes: Long, sha256: String) {
        require(bytes in 1..64L * 1024 * 1024 && sha256.matches(Regex("[0-9a-f]{64}")))
        require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) && source.length() == bytes)
        if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
            require(Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS) && destination.length() == bytes &&
                fileSha256(destination) == sha256) { "An existing artifact has different bytes; preserve it and retry recovery." }
            // The destination may be the same immutable source under an unchanged artifact key.
            coroutineContext.ensureActive()
            require(fileSha256(source) == sha256) { "Accepted source changed before reuse." }
            return
        }
        val temporary = File.createTempFile(".verified-reuse-", ".partial", destination.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var observed = 0L
            FileInputStream(source).use { input -> FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count > 0 && count.toLong() <= bytes - observed) { "Accepted source changed during reuse." }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count); observed += count
                }
                output.fd.sync()
            } }
            require(observed == bytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == sha256) {
                "Accepted source hash changed during reuse."
            }
            coroutineContext.ensureActive()
            check(!destination.exists() && temporary.renameTo(destination)) { "Cannot stage verified reused artifact." }
        } finally { temporary.delete() }
    }


    private fun typedOutcomes(
        snapshot: PipelineInputSnapshot,
        artifactKey: String,
        composite: JSONObject,
        meshGlb: File,
        meshPly: File,
        texturedGlb: File,
        atlasPng: File,
    ): List<PipelineStageOutcome> {
        val outcomes = mutableListOf<PipelineStageOutcome>()
        val denseIdentity = snapshot.identity(PipelineStage.DENSE)
        outcomes += PipelineStageOutcome.Completed(
            PipelineArtifactReference.forStage(
                denseIdentity,
                "$artifactKey.json#densePreview",
                contentSha256 = sha256Utf8(composite.getJSONObject("dense").toString()),
            ),
        )

        if (composite.has("mesh") && meshGlb.isFile && meshPly.isFile) {
            outcomes += PipelineStageOutcome.Completed(
                PipelineArtifactReference.forStage(
                    snapshot.identity(PipelineStage.SURFACE),
                    meshGlb.name,
                    companionLocations = listOf(meshPly.name),
                    contentSha256 = fileSha256(meshGlb),
                ),
            )
        } else {
            outcomes += PipelineStageOutcome.Unavailable(
                PipelineFailure(
                    PipelineStage.SURFACE,
                    "SURFACE_QUALITY_REJECTED",
                    composite.optString("meshError", "Insufficient supported surface geometry"),
                    recoverable = true,
                ),
            )
        }

        if (composite.has("texture") && texturedGlb.isFile && atlasPng.isFile) {
            outcomes += PipelineStageOutcome.Completed(
                PipelineArtifactReference.forStage(
                    snapshot.identity(PipelineStage.TEXTURE),
                    texturedGlb.name,
                    companionLocations = listOf(atlasPng.name),
                    contentSha256 = fileSha256(texturedGlb),
                ),
            )
        } else {
            outcomes += PipelineStageOutcome.Unavailable(
                PipelineFailure(
                    PipelineStage.TEXTURE,
                    "TEXTURE_UNAVAILABLE",
                    composite.optString("textureError", "Photographic texture was not produced"),
                    recoverable = true,
                ),
            )
        }
        return outcomes
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Collect actual photograph RGB at up to 768px using the SAME camera/crop.
     * Decode one bitmap at a time and rectify only into bounded destination pixels.
     * Intrinsics are scaled from the depth stage so all photo UVs share the gauge.
     */
    private data class PhotoAtlasInput(val pixels:ByteArray,val width:Int,val height:Int,val identities:List<String>)
    private data class CoarseInput(val pixels:ByteArray,val width:Int,val height:Int,val cameras:DoubleArray)

    private data class SparsePriors(val rows:DoubleArray,val identity:String)

    /** Only native-retained track/photo pairs are visible evidence. Projection is an additional image-bound gate. */
    private suspend fun prepareSparsePriors(points:JSONArray,support:Map<Int,Set<String>>,photos:List<String>,cameras:DoubleArray,
                                            width:Int,height:Int,threshold:Double?):SparsePriors {
        val context=coroutineContext
        context.ensureActive()
        require(support.size<=20_000)
        var supportObservations=0
        for((index,entry) in support.entries.withIndex()){
            if(index%32==0)context.ensureActive()
            require(entry.key>=0 && entry.value.size in 2..250)
            supportObservations+=entry.value.size
            require(supportObservations<=100_000) { "Accepted sparse support exceeds observation budget" }
            require(entry.value.none(String::isBlank))
        }
        require(threshold==null || (threshold.isFinite() && threshold>0 && threshold<=.05))
        val identity=StableDigest().text("normalized-threshold-over-measured-sine-parallax-v1; algorithmic-bound-not-physical-sigma")
            .long(threshold?.toBits() ?: Long.MIN_VALUE)
        context.ensureActive()
        val orderedSupport=support.toSortedMap()
        context.ensureActive()
        orderedSupport.entries.forEachIndexed { index,(track,ids) ->
            if(index%32==0)context.ensureActive()
            identity.long(track.toLong()).long(ids.size.toLong());ids.sorted().forEach {identity.text(it)}
        }
        val rows=ArrayList<Double>();val seen=HashSet<Int>()
        for(index in 0 until points.length()){
            if(index%32==0)context.ensureActive()
            val point=points.getJSONArray(index)
            require(point.length()>=9) { "Sparse priors require explicit retained native track IDs" }
            val id=point.getDouble(8);require(id.isFinite() && id>=0 && id<=Int.MAX_VALUE && id%1.0==0.0 && seen.add(id.toInt()))
            val xyz=DoubleArray(3){point.getDouble(it)};require(xyz.all(Double::isFinite))
            identity.long(id.toLong());xyz.forEach {identity.long(it.toBits())}
            val supported=support[id.toInt()] ?: continue
            if(threshold==null)continue
            val cameraIndices=photos.indices.filter { photos[it] in supported }
            if(cameraIndices.size<2)continue
            val rays=cameraIndices.map { camera -> val base=camera*16
                DoubleArray(3) { k -> xyz[k]+(0..2).sumOf { row -> cameras[base+row*3+k]*cameras[base+9+row] } }
            }
            var maxSine=0.0
            for(a in rays.indices)for(b in 0 until a){val x=rays[a];val y=rays[b]
                val xn=sqrt(x.sumOf {it*it});val yn=sqrt(y.sumOf {it*it})
                if(!xn.isFinite() || !yn.isFinite() || xn<=1e-12 || yn<=1e-12)continue
                val dot=(0..2).sumOf { x[it]/xn*(y[it]/yn) }.coerceIn(-1.0,1.0)
                // Opposite/behind rays are not a usable small-angle triangulation observation.
                if(dot<=0)continue
                maxSine=max(maxSine,sqrt((1-dot*dot).coerceAtLeast(0.0)))
            }
            if(maxSine<1e-4)continue
            val uncertainty=threshold/maxSine
            if(!uncertainty.isFinite() || uncertainty>0.25)continue
            for(camera in cameraIndices){
                if(rows.size/6>=20_000)break
                val base=camera*16
                val q=DoubleArray(3){r -> (0..2).sumOf {c -> cameras[base+r*3+c]*xyz[c]}+cameras[base+9+r]}
                if(!q.all(Double::isFinite) || q[2]<=.01)continue
                val x=cameras[base+12]*q[0]/q[2]+cameras[base+14];val y=cameras[base+13]*q[1]/q[2]+cameras[base+15]
                if(!x.isFinite() || !y.isFinite() || x<2 || x>width-3 || y<2 || y>height-3)continue
                rows.addAll(listOf(camera.toDouble(),x,y,q[2],uncertainty,1.0))
            }
        }
        support.keys.forEachIndexed { index,track ->
            if(index%32==0)context.ensureActive()
            require(track in seen) { "Sparse support does not belong to the accepted point snapshot" }
        }
        context.ensureActive()
        return SparsePriors(rows.toDoubleArray(),identity.finish())
    }

    /** Real observed coarse pixels; no extra original decode, inferred depths, or visibility labels. */
    private suspend fun prepareCoarseInput(full:ByteArray,width:Int,height:Int,cameras:DoubleArray):CoarseInput {
        val context=coroutineContext
        val w=width/2;val h=height/2;val views=cameras.size/16
        require(w>=32 && h>=32 && w<width && h<height) { "Photographs are too small for bounded coarse refinement" }
        val extra=w.toLong()*h*4*views+width.toLong()*height*4+w.toLong()*32*18
        val runtime=Runtime.getRuntime();val free=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())
        require(extra<=runtime.maxMemory()/16 && extra<=free/4) { "Insufficient managed heap for bounded coarse refinement" }
        val pixels=ByteArray(w*h*4*views);val source=IntArray(width*height);val rows=cameras.copyOf()
        val resize=PixelTransform.resize(width,height,w,h)
        for(camera in 0 until views){
            context.ensureActive()
            val offset=camera*width*height*4
            for(i in source.indices){val p=offset+i*4;source[i]=((full[p+3].toInt() and 255) shl 24) or
                ((full[p].toInt() and 255) shl 16) or ((full[p+1].toInt() and 255) shl 8) or (full[p+2].toInt() and 255)}
            val base=camera*16;val k=resize.intrinsics(cameras[base+12],cameras[base+13],cameras[base+14],cameras[base+15])
            rows[base+12]=k[0];rows[base+13]=k[4];rows[base+14]=k[2];rows[base+15]=k[5]
            val input=LensProjection(cameras[base+12],cameras[base+13],cameras[base+14],cameras[base+15],0.0,0.0,0.0,0.0,0.0)
            val output=LensProjection(k[0],k[4],k[2],k[5],0.0,0.0,0.0,0.0,0.0)
            PyramidRectifier.tiles(source,width,height,PixelTransform.identity(width,height),input,output,w,h,
                cancelled={context[kotlinx.coroutines.Job]?.isActive==false}) { tile ->
                val colors=tile.pixels;val valid=tile.validity
                for(i in colors.indices){val p=(camera*w*h+tile.firstRow*w+i)*4;val color=colors[i]
                    if(valid[i].toInt()==1){pixels[p]=(color ushr 16).toByte();pixels[p+1]=(color ushr 8).toByte();pixels[p+2]=color.toByte();pixels[p+3]=255.toByte()}}
            }
        }
        context.ensureActive()
        return CoarseInput(pixels,w,h,rows)
    }

    private fun bytesSha256(bytes:ByteArray):String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private suspend fun preparePhotoAtlasInput(
        repository: ProjectRepository, photos: List<ScanPhoto>, cameras:List<JSONObject>, cameraRows: DoubleArray,
        profile: LensProfile?, fallbackFocal35: Double,
        depthWidth: Int, depthHeight: Int, textureEdge: Int,
        onProgress: suspend (String, Int, Int) -> Unit,
    ): PhotoAtlasInput {
        val dimensions=ImageLoader.upright(repository.photoFile(photos.first()),textureEdge).let { first ->
            try { first.width to first.height } finally { first.recycle() }
        }
        val w=dimensions.first;val h=dimensions.second
        DenseWorkload.atlasPixels(w,h,photos.size)
        require(w in 32..1024 && h in 32..1024 && w.toLong()*h<=1_048_576)
        val data=ByteArray(photos.size*w*h*4)
        val identities=ArrayList<String>(photos.size)
        for ((index,photo) in photos.withIndex()) {
            coroutineContext.ensureActive()
            onProgress("Rectifying photographic texture sources",index,photos.size)
            val camera=cameras[index]
            val nativeLens = cameraLens(camera,w,h,photo,profile,fallbackFocal35)
            // 031 owns the exact pixel-centre transformation; do not duplicate private scaling formulas.
            val transform = PixelTransform.resize(depthWidth,depthHeight,w,h)
            val k = transform.intrinsics(cameraRows[index*16+12],cameraRows[index*16+13],cameraRows[index*16+14],cameraRows[index*16+15])
            val corrected = LensProjection(k[0],k[4],k[2],k[5],nativeLens.k1,nativeLens.k2,nativeLens.p1,nativeLens.p2,nativeLens.k3)
            identities += writePhotoLevel(repository,photo,textureEdge,w,h,corrected,data,index*w*h*4,
                calibrationProvenance(camera,profile,photo,fallbackFocal35))
        }
        onProgress("Rectifying photographic texture sources",photos.size,photos.size)
        return PhotoAtlasInput(data,w,h,identities)
    }

    private fun cameraLens(camera:JSONObject,width:Int,height:Int,photo:ScanPhoto,profile:LensProfile?,fallback:Double):LensProjection {
        val row=camera.optJSONArray("refinedIntrinsics") ?: return profile?.forScan(width,height,photo.focal35mm)
            ?: LensProjection.estimated(width,height,ImageLoader.focalPixels(width,height,photo.focal35mm ?: fallback))
        require(row.length()==11)
        val values=DoubleArray(11){row.getDouble(it)}
        require(values.all(Double::isFinite) && values[0]%1.0==0.0 && values[1]%1.0==0.0 && values[0] in 2.0..100000.0 && values[1] in 2.0..100000.0)
        require(values[2]>0 && values[3]>0 && (6..10).all {abs(values[it])<=2.0})
        require(abs(values[0]/values[1]-width.toDouble()/height)<.005) { "Refined intrinsic image domain differs from photograph" }
        val k=PixelTransform.resize(values[0].toInt(),values[1].toInt(),width,height).intrinsics(values[2],values[3],values[4],values[5])
        return LensProjection(k[0],k[4],k[2],k[5],values[6],values[7],values[8],values[9],values[10])
    }

    private fun calibrationProvenance(camera:JSONObject,profile: LensProfile?, photo: ScanPhoto, fallback: Double): String =
        if(camera.has("refinedIntrinsics")) "native-bundle-schema1:${camera.getJSONArray("refinedIntrinsics")}:${camera.optString("intrinsicPolicy","explicit-model")}:${LensProjection.COORDINATE_CONVENTION}"
        else if (profile == null) "estimated-focal35:${photo.focal35mm ?: fallback}:${LensProjection.COORDINATE_CONVENTION}"
        else "measured-checkerboard:${profile.width}x${profile.height}:rms=${profile.reprojectionRmsPixels}:views=${profile.acceptedViews}:focal=${profile.focal35mm}:${LensProjection.COORDINATE_CONVENTION}"

    /** Cache admission never increases030 image/view/sample caps. Conservative transient-heap estimate only. */
    private fun pyramidStore(repository: ProjectRepository, projectId: String, pixels: Long): PyramidStore? = runCatching {
        val runtime = Runtime.getRuntime()
        val transientBytes = Math.multiplyExact(pixels,48L)
        val freeHeap = runtime.maxMemory() - (runtime.totalMemory()-runtime.freeMemory())
        if (transientBytes > runtime.maxMemory()/16 || transientBytes > freeHeap/4) return null
        val project = repository.directory(projectId).absoluteFile
        val projects = requireNotNull(project.parentFile)
        require(projects.canonicalFile.parentFile == projects.parentFile?.canonicalFile && projects.canonicalFile.name == projects.name)
        val canonical = project.canonicalFile
        require(canonical.parentFile == projects.canonicalFile && canonical.name == projectId)
        val folder = File(project,".pyramid-levels-v2")
        require(folder.canonicalFile.parentFile == canonical && folder.canonicalFile.name == folder.name)
        require(folder.mkdirs() || folder.isDirectory)
        require(folder.canonicalFile.parentFile == canonical && folder.canonicalFile.name == folder.name)
        PyramidStore(folder,minOf(32L*1024*1024,runtime.maxMemory()/8))
    }.getOrNull()

    private suspend fun writePhotoLevel(repository: ProjectRepository, photo: ScanPhoto, edge: Int,
        width: Int, height: Int, lens: LensProjection, target: ByteArray, offset: Int, provenance: String):String {
        coroutineContext.ensureActive()
        val context = coroutineContext
        val original = repository.photoFile(photo)
        val project = repository.directory(photo.projectId).canonicalFile
        require(original.canonicalFile.toPath().startsWith(project.toPath()) && original.canonicalFile != project) { "Original photograph escapes its private project" }
        val (rawWidth,rawHeight) = ImageLoader.bounds(original)
        val orientation = ExifInterface(original).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
            .let { if(it==ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else it }
        val upright = PixelTransform.exif(rawWidth,rawHeight,orientation)
        require(abs(upright.width.toDouble()/upright.height-width.toDouble()/height)<.005) { "Photographs have incompatible aspect/crop" }
        val originalToLevel = upright.then(PixelTransform.resize(upright.width,upright.height,width,height))
        val store = pyramidStore(repository,photo.projectId,width.toLong()*height)
        val result=PyramidRectifier.writeUprightRgba(sourceArgb = {
            context.ensureActive()
            val image = ImageLoader.upright(original,edge)
            try {
                require(abs(image.width.toDouble()/image.height-width.toDouble()/height)<.005) { "Decoded photograph dimensions changed" }
                val scaled = if(image.width==width && image.height==height) image else Bitmap.createScaledBitmap(image,width,height,true)
                try { IntArray(width*height).also { scaled.getPixels(it,0,width,0,0,width,height) } }
                finally { if(scaled!==image)scaled.recycle() }
            } finally { image.recycle() }
        }, width=width,height=height,lens=lens,target=target,offset=offset,originalSha256=photo.sha256,
            calibrationProvenance="$provenance|decoder=${AndroidImageDecoder.POLICY_IDENTITY}|decodeEdge=$edge",originalToLevel=originalToLevel,store=store,
            cancelled = { context[kotlinx.coroutines.Job]?.isActive == false })
        context.ensureActive()
        return result.identity
    }
}
