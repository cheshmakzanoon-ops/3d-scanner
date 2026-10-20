from pathlib import Path
p=Path('/workspace/agents/002/app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt')
s=p.read_text().replace('import com.realityforge.scanner.engine.features.CachedFeatures','import com.realityforge.scanner.engine.dense.DenseViewSelection\nimport com.realityforge.scanner.engine.tracks.SourceObservation\nimport com.realityforge.scanner.engine.features.CachedFeatures',1)
s=s.replace('import kotlinx.coroutines.Dispatchers','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.cancelAndJoin\nimport kotlinx.coroutines.coroutineScope\nimport kotlinx.coroutines.delay\nimport kotlinx.coroutines.isActive\nimport kotlinx.coroutines.launch',1)
s=s.replace('engineVersion = "m19-c2-feature-services-2|m16-registration-frontier-1|m18-depth-checkpoint-1|${LensProjection.COORDINATE_CONVENTION}"','engineVersion = "m20-agent31-60-v2|${LensProjection.COORDINATE_CONVENTION}|rectifier-alpha-v2|dense-selection-verified-v1"')
s=s.replace('dense = "native-reference-depth-v1|planes-56|memory-tier=$memoryTier"','dense = "native-tiled-depth-v2|rectifier-alpha-v2|verified-selection-v1|planes-56|memory-tier=$memoryTier"')
s=s.replace('texture = "photographic-atlas-v1|memory-tier=$memoryTier"','texture = "photographic-atlas-zlib-v2|rectifier-alpha-v2|memory-tier=$memoryTier"')
s=s.replace('val cache = File(directory, "features").apply { mkdirs() }','''val cachePath = File(directory, "features")
        // The parent is the repository-owned private project directory, not user input.
        require(!java.nio.file.Files.isSymbolicLink(cachePath.toPath())) { "Invalid derived cache root" }
        val cache = cachePath.canonicalFile
        require(cache.parentFile == directory.canonicalFile) { "Derived cache escaped project" }
        check(cache.isDirectory || cache.mkdirs()) { "Derived cache unavailable" }''')
s=s.replace('NativeCore.reconstruct(rows.rows, 1.5 / min(fA, fB))','nativeCall(nativeToken, progress) { NativeCore.reconstructCancellable(rows.rows, 1.5 / min(fA, fB), nativeToken) }')
s=s.replace('NativeCore.registerCamera(packed.toDoubleArray(),2.0 / fC)','nativeCall(nativeToken, progress) { NativeCore.registerCameraCancellable(packed.toDoubleArray(),2.0 / fC,nativeToken) }')
s=s.replace('val growth=IncrementalRegistration.grow(templates,initial,::featurePairs,\n                    NativeFrontierGeometry::triangulate,NativeFrontierGeometry::register)', 'val nativeGeometry=NativeFrontierGeometry.forOperation(nativeToken)\n                val growth=IncrementalRegistration.grow(templates,initial,::featurePairs,\n                    nativeGeometry.triangulate,nativeGeometry.register)')
s=s.replace('// by both anchors. The native optimizer fixes the first two cameras to preserve gauge.','// by both anchors. Native results state their actual gauge and retained support.')
s=s.replace('        var geometryWarning = ','''        // Accepted anchor landmarks are actual two-view support, even if later refinement fails.
        // Stable source IDs derive from the exact feature keys, never temporary native ordinals.
        val anchorIds = (0 until anchorPoints.length()).map { index ->
            val match = anchorPoints.getJSONArray(index).getInt(8)
            require(match in 0 until pairTracks.size)
            minOf(SourceObservation(first.id,pairTracks.firstKeys[match]).identity(),
                SourceObservation(second.id,pairTracks.secondKeys[match]).identity())
        }.toSet()
        var denseSelection: DenseViewSelection.Plan? = DenseViewSelection.select(
            (0 until cameraArray.length()).map { index ->
                val camera=cameraArray.getJSONObject(index)
                val rotation=camera.getJSONArray("rotation");val translation=camera.getJSONArray("translation")
                val centre=(0..2).map { column -> -(0..2).sumOf { row -> rotation.getDouble(row*3+column)*translation.getDouble(row) } }
                val id=camera.getString("photoId")
                DenseViewSelection.Camera(id,centre,if(id==first.id || id==second.id)anchorIds else emptySet(),0.0)
            })
        var geometryWarning = ''',1)
s=s.replace('            val observations = graph.packedRows()','            val nativeBatch = graph.nativeSnapshot(registeredPhotoIndices.map { photos[it].id })\n            val observations = nativeBatch.rows')
s=s.replace('NativeCore.reconstructMultiview(poses, observations, threshold, 12)','nativeCall(nativeToken, progress) { NativeCore.reconstructMultiviewCancellable(poses, observations, threshold, 12,nativeToken) }')
s=s.replace('NativeCore.bundleAdjust(baCameras, baPoints, observations, threshold, 15)','nativeCall(nativeToken, progress) { NativeCore.bundleAdjustCancellable(baCameras, baPoints, observations, threshold, 15,nativeToken) }')
s=s.replace('// It uses the same observation graph, holds both anchor cameras fixed,','// It uses the same observation graph and a checked native gauge,')
s=s.replace('.put("anchorCamerasFixed", 2)','.put("anchorCamerasFixed", joint.getInt("anchorCamerasFixed"))\n                                    .put("gaugeMode",joint.optString("gaugeMode","two_anchors"))')
s=s.replace('.put("gaugeFixed", camera < 2)','.put("gaugeFixed", corrected.optBoolean("fixed",camera < 2))')
needle='''                    val parallax = (0 until newPoints.length()).map'''
insert='''                    val retainedSupport = linkedMapOf<Int,Set<Int>>()
                    val evidence=selectedGeometry.getJSONArray("trackEvidence")
                    require(evidence.length()==newPoints.length()) { "Missing accepted sparse support" }
                    for(index in 0 until evidence.length()) {
                        val item=evidence.getJSONObject(index)
                        val trackValue=item.getDouble("track")
                        require(trackValue.isFinite() && trackValue%1.0==0.0 && trackValue>=0 && trackValue<nativeBatch.trackIds.size)
                        val support=item.getJSONArray("supportingCameras")
                        val cameras=List(support.length()) { i ->
                            val value=support.getDouble(i)
                            require(value.isFinite() && value%1.0==0.0 && value>=0 && value<views.size)
                            value.toInt()
                        }
                        require(cameras.distinct().size==cameras.size)
                        require(retainedSupport.put(trackValue.toInt(),cameras.toSet())==null)
                    }
                    val correctedPoses=List(newCameras.length()) { i ->
                        val camera=newCameras.getJSONObject(i)
                        DoubleArray(12) { k -> if(k<9)camera.getJSONArray("rotation").getDouble(k) else camera.getJSONArray("translation").getDouble(k-9) }
                    }
                    denseSelection=DenseViewSelection.select(DenseViewSelection.fromVerifiedNativeTracks(
                        nativeBatch.photoIds,correctedPoses,nativeBatch.trackIds,observations,retainedSupport,
                        List(views.size){0.0},List(views.size){true}))
                    output.put("sparseSourceTrackIds",JSONArray(nativeBatch.trackIds))
                        .put("sparseTrackEvidence",evidence)
                    val parallax = (0 until newPoints.length()).map'''
assert needle in s;s=s.replace(needle,insert,1)
s=s.replace('pipelineSnapshot,\n            ) { stage','pipelineSnapshot, denseSelection,\n            ) { stage')
s=s.replace('.put("engineVersion", "m19-c2-feature-services-2")','.put("engineVersion", "m20-agent31-60-v2")')
s=s.replace('M06 coupled robust sparse bundle adjustment optimized all registered camera poses (except both fixed anchors) and existing sparse points.','Coupled robust sparse bundle adjustment optimized the accepted camera/point window with the reported native gauge.')
idx=s.index('    private fun pipelineJson(')
s=s[:idx]+'''    /** Pull native work units at bounded frequency. Unknown totals stay indeterminate.
     * The reporter also cancels the owned lease if the surrounding coroutine is cancelled.
     */
    private suspend fun <T> nativeCall(token:Long, progress:suspend(Progress)->Unit, block:()->T):T = coroutineScope {
        require(token>0) { "Missing scoped native operation token" }
        val finished=java.util.concurrent.atomic.AtomicBoolean(false)
        val reporter=launch(Dispatchers.IO) {
            try {
                var lastSequence=-1L
                while(isActive) {
                    delay(100)
                    val value=NativeCore.pollNativeProgress(token)
                    if(value.size==7 && value[0]==1L && value[6]!=lastSequence) {
                        lastSequence=value[6]
                        val labels=arrayOf("Sparse geometry","Dense depth","Surface fusion","Photographic texture","Vulkan depth","Native export","Depth checkpoint")
                        val stage=value[1].takeIf {it in 0L..6L}?.toInt() ?: continue
                        val total=value[5]
                        if(value[4] in 0L..Int.MAX_VALUE.toLong() && total in -1L..Int.MAX_VALUE.toLong()) {
                            progress(Progress(labels[stage],value[4].toInt(),if(total<0)0 else total.toInt()))
                        }
                    }
                }
            } finally { if(!finished.get()) NativeCore.cancelReconstruction(token) }
        }
        try { block() } finally { finished.set(true);reporter.cancelAndJoin() }
    }

'''+s[idx:]
p.write_text(s)
