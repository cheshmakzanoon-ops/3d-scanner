from pathlib import Path
p=Path('/workspace/agents/002/app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt')
s=p.read_text()
s=s.replace('import com.realityforge.scanner.engine.dense.DenseViewSelection','import androidx.exifinterface.media.ExifInterface\nimport com.realityforge.scanner.engine.dense.DenseViewSelection',1)
s=s.replace('sparse = "incremental-frontier-v1|joint-ba-v1|assumed-focal35=$assumedFocal35"','sparse = "stable-frontier-v2|typed-bundle-pixel-v1|profile=$executionProfile|unknown-lens-groups-fixed|focal-policy-prior5pct-bounds15pct|assumed-focal35=$assumedFocal35"')
s=s.replace('''        var denseSelection: DenseViewSelection.Plan? =''','''        var acceptedSparseSupport: Map<Int,Set<String>> = (0 until anchorPoints.length()).associate { index ->
            anchorPoints.getJSONArray(index).getInt(8) to setOf(first.id,second.id)
        }
        var sparseThresholdNormalized=1.5/minOf(output.getDouble("firstFocalPixels"),output.getDouble("secondFocalPixels"))
        var denseSelection: DenseViewSelection.Plan? =''',1)
old='''                            val joint = JSONObject(nativeCall(nativeToken, progress) { NativeCore.bundleAdjustCancellable(baCameras, baPoints, observations, threshold, 15,nativeToken) })'''
new='''                            val joint = if(executionProfile==DensePreviewProfile.VerifiedRefinedCompact) {
                                val seeded=(0 until seeds.length()).map {seeds.getJSONArray(it).getInt(8)}.toSet()
                                val pixelValues=ArrayList<Double>()
                                for(base in observations.indices step 7) {
                                    if(observations[base].toInt() !in seeded)continue
                                    val camera=observations[base+1].toInt()
                                    val projection=requireNotNull(views[camera].projection)
                                    val distorted=projection.distort(observations[base+2],observations[base+3])
                                    pixelValues+=observations[base];pixelValues+=observations[base+1]
                                    pixelValues+=distorted.first*projection.fx+projection.cx
                                    pixelValues+=distorted.second*projection.fy+projection.cy
                                    for(k in 4..6)pixelValues+=observations[base+k]
                                }
                                val intrinsicRows=DoubleArray(views.size*11)
                                val groupIds=Array(views.size) { camera ->
                                    val view=views[camera];val projection=requireNotNull(view.projection)
                                    val row=doubleArrayOf(view.width.toDouble(),view.height.toDouble(),projection.fx,projection.fy,
                                        projection.cx,projection.cy,projection.k1,projection.k2,projection.p1,projection.p2,projection.k3)
                                    row.copyInto(intrinsicRows,camera*11)
                                    val source=photos[registeredPhotoIndices[camera]]
                                    val lensIdentity=if(activeProfile!=null) "measured-project:${sha256Utf8(calibrationSource)}" else runCatching {
                                        val exif=ExifInterface(repository.photoFile(source))
                                        val make=exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
                                        val model=exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty()
                                        val lens=exif.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty()
                                        // Unknown lens sharing must not manufacture multi-view observability.
                                        if(make.isBlank()||model.isBlank()||lens.isBlank()) "unknown-lens:${source.id}"
                                        else "estimated-exif:$make:$model:$lens:${source.focal35mm}"
                                    }.getOrDefault("unknown-lens:${source.id}")
                                    sha256Utf8(lensIdentity+row.joinToString(":") { java.lang.Double.toHexString(it) })
                                }
                                JSONObject(nativeCall(nativeToken,progress) {
                                    NativeCore.bundleAdjustProblemCancellable(baCameras,baPoints,pixelValues.toDoubleArray(),
                                        intrinsicRows,groupIds,nativeBatch.photoIds.toTypedArray(),nativeBatch.trackIds.toTypedArray(),
                                        activeProfile!=null,true,threshold,15,nativeToken)
                                })
                            } else JSONObject(nativeCall(nativeToken, progress) { NativeCore.bundleAdjustCancellable(baCameras, baPoints, observations, threshold, 15,nativeToken) })'''
assert old in s;s=s.replace(old,new,1)
needle='''                            if (acceptable) {
                                selectedGeometry = joint'''
replacement='''                            if (acceptable) {
                                joint.optJSONArray("cameraIntrinsics")?.let { models ->
                                    require(models.length()==views.size)
                                    for(camera in views.indices) {
                                        val row=models.getJSONArray(camera);require(row.length()==11)
                                        require(row.getDouble(0)==views[camera].width.toDouble() && row.getDouble(1)==views[camera].height.toDouble())
                                        val parameters=DoubleArray(9){row.getDouble(it+2)}
                                        require(parameters.all(Double::isFinite))
                                        LensProjection(parameters[0],parameters[1],parameters[2],parameters[3],parameters[4],parameters[5],parameters[6],parameters[7],parameters[8])
                                    }
                                }
                                selectedGeometry = joint'''
assert needle in s;s=s.replace(needle,replacement,1)
s=s.replace('''.put("medianAfterNormalized", joint.getDouble("medianErrorAfterNormalized")))''','''.put("medianAfterNormalized", joint.getDouble("medianErrorAfterNormalized"))
                                    .put("intrinsicPolicy",joint.optString("intrinsicPolicy","fixed_legacy_normalized"))
                                    .put("acceptedIntrinsicGroups",joint.optInt("acceptedIntrinsicGroups",0))
                                    .put("loopProposalCount",joint.optInt("loopProposalCount",0))
                                    .put("verifiedLoopCount",joint.optInt("verifiedLoopCount",0))
                                    .put("acceptedLoopCorrections",joint.optInt("acceptedLoopCorrections",0)))''',1)
s=s.replace('''                            .put("gaugeFixed", corrected.optBoolean("fixed",camera < 2))''','''                            .put("gaugeFixed", corrected.optBoolean("fixed",camera < 2))
                        selectedGeometry.optJSONArray("cameraIntrinsics")?.let { models ->
                            original.put("refinedIntrinsics",models.getJSONArray(camera))
                        }''',1)
s=s.replace('''                    denseSelection=verifiedSelection''','''                    denseSelection=verifiedSelection
                    acceptedSparseSupport=retainedSupport.mapValues { (_,cameras) -> cameras.map {nativeBatch.photoIds[it]}.toSet() }
                    sparseThresholdNormalized=threshold''',1)
s=s.replace('''pipelineSnapshot, denseSelection, executionProfile,
''','''pipelineSnapshot, denseSelection, executionProfile, acceptedSparseSupport, sparseThresholdNormalized,
''',1)
s=s.replace('''NativeCore.exportPoints(densePacked,temporary.path,extension=="dense.glb")''','''NativeCore.exportPointsTyped(densePacked,temporary.path,extension=="dense.glb",true)''')
p.write_text(s)
