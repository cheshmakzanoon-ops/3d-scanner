"""Static source contracts. These tests do not substitute for Android compilation/runtime tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import pytest

ROOT=Path(__file__).resolve().parents[1]
ANDROID="{http://schemas.android.com/apk/res/android}"
TOOLS="{http://schemas.android.com/tools}"


def test_surface_fusion_version_matches_policy_and_device_assertion():
    native=(ROOT/"reconstruction/src/surface.cpp").read_text()
    policy=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/pipeline/DensePreviewProfile.kt").read_text()
    device=(ROOT/"app/src/androidTest/java/com/realityforge/scanner/agents/a002/PipelineServicesDeviceTest.kt").read_text()
    # A producer-version migration must update the real instrumentation equality too.
    native_versions=re.findall(r'\\"fusionVersion\\":(\d+)', native)
    policy_versions=re.findall(r'const val SURFACE_ENGINE = "rf-observed-open-mesh-v(\d+)-', policy)
    device_versions=re.findall(
        r'assertEquals\("Surface producer must match the stage policy",\s*(\d+),\s*'
        r'result\.getJSONObject\("meshPreview"\)\.getInt\("fusionVersion"\)\)', device)
    assert len(native_versions)==len(policy_versions)==len(device_versions)==1
    assert native_versions==policy_versions==device_versions


def test_manifest_strips_network_permissions():
    manifest=ET.parse(ROOT/"app/src/main/AndroidManifest.xml").getroot()
    permissions={x.attrib[ANDROID+"name"]:x.attrib for x in manifest.findall("uses-permission")}
    assert permissions["android.permission.INTERNET"][TOOLS+"node"]=="remove"
    assert permissions["android.permission.ACCESS_NETWORK_STATE"][TOOLS+"node"]=="remove"
    assert manifest.find("application").attrib[ANDROID+"allowBackup"]=="false"

@pytest.mark.parametrize("path",list((ROOT/"app/src/main").rglob("*.xml")))
def test_xml_well_formed(path):ET.parse(path)


def test_jni_names_match_declared_methods():
    kotlin=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/NativeCore.kt").read_text()
    cpp=(ROOT/"reconstruction/src/jni.cpp").read_text()
    methods=set(re.findall(r"external fun (\w+)\(",kotlin))
    exports=set(re.findall(r"Java_com_realityforge_scanner_engine_NativeCore_(\w+)\(",cpp))
    required={"reconstruct","registerCamera","reconstructMultiview","bundleAdjust","reconstructDense","reconstructSurface","reconstructTexturedSurface","reconstructSurfaceCancellable","reconstructTexturedSurfaceCancellable","beginReconstruction","cancelReconstruction","finishReconstruction","reconstructSurfaceCheckpointed","reconstructTexturedSurfaceCheckpointed","quality","exportPoints","exportPointCloud"}
    assert methods == exports
    assert required <= methods


def test_no_dynamic_dependency_versions():
    source=(ROOT/"app/build.gradle.kts").read_text()+(ROOT/"build.gradle.kts").read_text()
    assert not re.search(r'"[^"\n]*:\+"',source)
    assert "latest.release" not in source
    assert 'compileSdk = 36' in source
    assert '28.2.13676358' in source


def test_sparse_output_disclosed_in_ui():
    source=(ROOT/"app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt").read_text()
    assert "not a finished mesh" in source
    assert "POINTS, not triangles" in source


def test_revision_guard_in_database():
    source=(ROOT/"app/src/main/java/com/realityforge/scanner/data/Database.kt").read_text()
    repository=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    assert "revision = :revision" in source
    assert re.search(r"@Transaction\s+suspend fun addPhoto\(", source)
    assert "invalidateExpected" in source and "expectedRevision" in source
    # An edit invalidates currentness by revision without erasing the last completed pointer.
    assert "resultRevision = NULL" not in source
    assert "project.resultRevision != project.revision" in repository

def test_lens_calibration_is_wired_to_all_reconstruction_stages():
    engine=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    graph=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt").read_text()
    model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    lens=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/LensCalibration.kt").read_text()
    assert "LensCalibration.readRecord(repository, projectId)" in engine
    assert "CalibrationCompatibility" in engine
    assert "projection.normalized" in engine and "c.projection.normalized" in engine
    assert "projection?.normalized" in graph
    assert "fun calibrateLens(" in model and "repository.saveCalibration(" in model
    assert "repository.removeCalibration(" in model
    mutation=(ROOT/"app/src/main/java/com/realityforge/scanner/capture/calibration/RoomCalibrationMutationStore.kt").read_text()
    assert "revision = :expectedRevision" in mutation and "revision = revision + 1" in mutation
    assert "markCommitted(operationId, revision)" in mutation
    assert "findChessboardCorners" in lens and "calibrateCamera" in lens


def test_export_artifact_key_supports_milestone_versions():
    src=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    assert 'sparse-(?:m[0-9]{2}-)?r[0-9]+-[0-9a-f]{16}' in src


def test_source_manifest_matches_every_tracked_artifact():
    import hashlib
    import subprocess
    manifest=(ROOT/"SOURCE_MANIFEST.sha256").read_text().splitlines()
    listed={row.split("  ",1)[1] for row in manifest}
    assert len(listed) == len(manifest)
    tracked=set(subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().rstrip("\0").split("\0"))
    assert listed == tracked - {"SOURCE_MANIFEST.sha256"}
    for row in manifest:
        expected,path=row.split("  ",1)
        candidate=ROOT/path
        assert candidate.is_file(), path
        assert hashlib.sha256(candidate.read_bytes()).hexdigest()==expected, path


def test_capture_package_requires_explicit_destination_and_preserves_hashes():
    repository=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    view_model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    screen=(ROOT/"app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt").read_text()
    assert "suspend fun exportCaptureBundle(projectId: String, destination: OutputStream)" in repository
    archive=(ROOT/"app/src/main/java/com/realityforge/scanner/data/archives/CaptureArchive.kt").read_text()
    assert '"session.json"' in archive and '"sha256"' in archive
    assert 'rememberBoundExporter(model, "capture.zip", "application/zip")' in screen
    assert "ActivityResultContracts.CreateDocument(mimeType)" in screen
    assert "model.beginCaptureBundleExportRequest()" in screen
    assert "model.completeCaptureBundleExport(captured, uri)" in screen
    assert "exportTransfers.captureArchive(request, uri" in view_model
    adapter=(ROOT/"app/src/main/java/com/realityforge/scanner/application/BoundSafExportService.kt").read_text()
    transfer=(ROOT/"app/src/main/java/com/realityforge/scanner/data/safexport/DestinationTransferService.kt").read_text()
    assert "ProjectOperationKind.EXPORT_CAPTURE, request.project.revision" in adapter
    assert "DestinationOwnership.NEWLY_CREATED" in adapter
    assert "DestinationTransferService(access, AndroidSafDestinationStore(context))" in adapter
    assert "CompletionEvidence.READ_BACK_SHA256" in transfer and "TransferOutcome.CompletedUnverified" in transfer


def test_bundle_adjustment_engine_and_jni_have_true_joint_contract():
    engine=(ROOT/"reconstruction/src/bundle_adjustment.cpp").read_text()
    jni=(ROOT/"reconstruction/src/jni.cpp").read_text()
    kotlin=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    assert "computeStep" in engine and "schur" in engine
    assert "out.cameras=std::move(candidateCameras)" in engine
    assert "cameraStep" in engine and "point.touched" in engine
    assert "Java_com_realityforge_scanner_engine_NativeCore_bundleAdjust" in jni
    execution=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/FinalSparseExecution.kt").read_text()
    coordinator=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/BoundedSparseCoordinator.kt").read_text()
    assert "FinalSparseExecution.execute(sourcePhotoIds, views, stableTracks" in kotlin
    assert "graph.stableSnapshot(sourcePhotoIds)" in kotlin
    assert "NativeCore.bundleAdjustProblemCancellable(input.cameraRows" in kotlin
    assert "TypedBundleAdapter.validateResult(input,joint)" in execution
    assert "views.size in 3..12" in execution
    assert "checkpoint = { sparseContext.ensureActive() }" in kotlin
    assert "view.xy[key*2]" in coordinator and "view.xy[key*2+1]" in coordinator
    assert "pixelRows[pixelOffset++]=o.px" in coordinator and "pixelRows[pixelOffset++]=o.py" in coordinator
    adapter=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/TypedBundleAdapter.kt").read_text()
    assert "view.xy[2*key]" in adapter and "view.xy[2*key+1]" in adapter
    assert "pose_and_baseline" in adapter
    assert "Java_com_realityforge_scanner_engine_NativeCore_bundleAdjustCancellable" in jni
    typed = jni.split("Java_com_realityforge_scanner_engine_NativeCore_bundleAdjustProblemCancellable(", 1)[1].split('extern "C"', 1)[0]
    assert "withOperation(env,token" in typed and "rf::jni::bundleProblemJson(" in typed
    assert "models!=(cameras/12)*11" in typed and "photos!=cameras/12" in typed
    assert "strings(groupIds,groups,1024)" in typed and "strings(sourceTrackIds,tracks,64)" in typed
    assert "bundleAdjustmentFailure" in kotlin
    assert "robustCostAfter" in execution


def test_sparse_publication_is_staged_before_fallible_progress_and_reference_swap():
    # This is a source contract only; actual helper/JNI fault injection lives in Android/JVM tests.
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    publication=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/FinalSparsePublication.kt").read_text()
    block=pipeline.split("val staged = FinalSparsePublication.prepare",1)[1].split("} catch (cancelled:",1)[0]
    before,after=block.split("output = staged.output",1)
    assert "progress(Progress(" in before and "coroutineContext.ensureActive()" in before
    assert "measured(ScanStage.BUNDLE_ADJUSTMENT, 1" in before
    assert "cameraArray = staged.cameras" in after and "checkpointObservations = stagedObservations" in after
    assert "progress(" not in after and "measured(" not in after
    assert "JSONObject(original.toString())" in publication and "JSONArray(originalCameras.toString())" in publication
    assert "result.input.photoIds.indexOf(sourcePhotoIds[camera])" in publication
    diagnostics=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparseFinalizationDiagnostics.kt").read_text()
    assert "SparseFinalizationDiagnostics.record(output, failure)" in pipeline
    assert 'output.put("finalSparseOutcome", "verified_anchor_fallback")' in diagnostics


def test_dense_source_is_bounded_cpu_reference_and_preserves_sparse_fallback():
    native=(ROOT/"reconstruction/src/dense.cpp").read_text()
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    store=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    assert "settings.maxPoints" in native and "reconstructDense(" in native
    assert "reciprocal" in native.lower() and "depthConsistencyFraction" in native
    assert "denseFailure" in pipeline and 'output.put("densePreview",surface.getJSONObject("dense"))' in pipeline
    assert '"dense.glb"' in store and '"dense.ply"' in store


def test_m09_camera_projected_uvs_are_embedded_and_exportable():
    native=(ROOT/"reconstruction/src/texture.cpp").read_text()
    adapter=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt").read_text()
    repo=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    app=(ROOT/"app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt").read_text()
    assert "TEXCOORD_0" in native and "image/png" in native
    assert "depthAgreement(" in native and "visiblePixel(" in native
    assert "NativeCore.reconstructTexturedSurface" in adapter
    assert "textureError" in adapter and "textured.glb" in adapter and "texture.png" in adapter
    assert "textured.glb" in repo and "texture.png" in repo
    assert "exportTexturedGlb" in app and "exportTexturePng" in app


def test_photographic_viewer_uses_bounded_local_assets_and_on_demand_gl():
    parser=(ROOT/"app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt").read_text()
    renderer=(ROOT/"app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt").read_text()
    screen=(ROOT/"app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt").read_text()
    assert "MAX_FILE_BYTES" in parser and "MAX_IMAGE_PIXELS" in parser
    assert "CRC32" in parser and "External model data is not permitted" in parser
    assert "glDrawElements" in renderer and "glTF v=0" in renderer
    assert "RENDERMODE_WHEN_DIRTY" in screen
    assert "Lifecycle.Event.ON_PAUSE -> surface.pauseRendering()" in screen
    assert "owner.lifecycle.removeObserver(observer); surface.close()" in screen
    assert "override fun close()=dispose()" in screen and "painter.releaseGlResources()" in screen
    assert "PhotographicModelLoader.load(file)" in screen and "onDispose { owned?.close() }" in screen
    loader=(ROOT/"app/src/main/java/com/realityforge/scanner/viewer/loading/PhotographicModelLoader.kt").read_text()
    assert "withContext(Dispatchers.IO)" in loader and "GlbStructure.readBounded(file, cancelled)" in loader
    assert "GlbStructure.validate(bytes, 4, cancelled)" in loader and "PhotographicGlb.decode(bytes, cancelled)" in loader
    assert "context.ensureActive()" in loader and "assetReference = null; byteReference = null" in loader


def test_session_reopen_and_export_have_revision_and_operation_guards():
    repository=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    assert "MAX_PREVIEW_BYTES" in repository and "suspend fun currentPreview(" in repository
    assert "storedRevision is Int || storedRevision is Long" in repository
    archive=(ROOT/"app/src/main/java/com/realityforge/scanner/data/archives/ProjectCaptureArchiveService.kt").read_text()
    assert "store.project(projectId)?.revision } == project.revision" in archive
    assert "busy.compareAndSet(false, true)" in model and "selectionGeneration" in model
    assert "repository.currentPreview(id)" in model
    assert "exportTransfers.captureArchive(request, uri" in model
    assert "exportTransfers.artifact(request, uri" in model


def test_adaptive_dense_adapter_has_eight_view_and_bitmap_lifetime_guards():
    adapter=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt").read_text()
    policy=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt").read_text()
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    assert "MAX_VIEWS = 8" in policy and "MAX_SAMPLES = 100_000L" in policy
    assert "DenseWorkload.selectCameraIndices" in adapter
    assert "finally { image.recycle() }" in adapter
    assert "val images = chosen.map" not in adapter
    assert "DenseWorkload.depthBounds" in adapter and "DenseWorkload.atlasPixels" in adapter
    snapshot=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/pipeline/ProductionPipelineSnapshot.kt").read_text()
    assert "ProductionPipelineSnapshot.create(" in pipeline
    assert "resources.geometryIdentityText" in snapshot and "resources.appearanceIdentityText" in snapshot
    assert "resourceSelection.geometryIdentitySha256" in adapter
    assert "selectedCameraIndices" in adapter and "plannedGridSamples" in adapter


def test_incremental_registration_uses_shared_triangulation_and_verified_landmarks():
    folder=ROOT/"app/src/main/java/com/realityforge/scanner/engine"
    source=(folder/"IncrementalRegistration.kt").read_text()
    bridge=(folder/"NativeFrontierGeometry.kt").read_text()
    pipeline=(folder/"SparsePipeline.kt").read_text()
    assert "featureGroups[i].size < 2" in source and "supported.size>=2" in source
    assert "identities.isEmpty() || identities.toSet().size != 1" in source
    assert "frequencies[it.first] == 1" in source
    identity=(folder/"pipeline/ProductionPipelineSnapshot.kt").read_text()
    assert "${com.realityforge.scanner.engine.IncrementalRegistration.POLICY}" in identity
    assert "registration-frontier-v3|$POLICY" in source
    assert "validatePose(" in source and "projectedError(" in source
    assert "NativeCore.reconstructMultiview" in bridge and "NativeCore.registerCamera" in bridge
    session=(folder/"AdmittedRegistrationSession.kt").read_text()
    assert "AdmittedRegistrationSession.grow(directory,fingerprint,photos.map { it.id }" in pipeline
    assert "IncrementalRegistration.grow(templates,anchors" in session and "incrementalRegistrationFailure" in pipeline
    assert "SparseFrontierCheckpointStore(projectDirectory)" in session
    assert "pairBudget=photoIds.size*(photoIds.size-1)/2" in session and "templates.size in 2..12" in session
    assert "if (photos.size >= 3)" in pipeline and "growth.cameras.drop(initial.size)" not in pipeline
    assert "frontierIsCompletedAlignment" in pipeline and '"not_guaranteed"' in pipeline
    identity=(folder/"pipeline/ProductionPipelineSnapshot.kt").read_text()
    compatibility=(folder/"pipeline/ProductionEngineCompatibility.kt").read_text()
    assert "m20-integrated-production-1" in compatibility and "native-cancellable-v2" in compatibility
    assert "NativeFrontierGeometry.forOperation(nativeToken)" in pipeline
    assert "nativeFrontier.triangulate,nativeFrontier.register" in pipeline
    assert "NativeCore.reconstructMultiviewCancellable(" in bridge and "NativeCore.registerCameraCancellable(" in bridge
    assert "ProductionEngineCompatibility.VALUE" in identity and "pipelineSnapshot.engineVersion" in pipeline
    assert "check(it.size <= AdmittedPairPlan.MAX_PAIRS)" in pipeline
    assert "AdmittedPairPlan.build(summaries)" in pipeline
    assert "candidates.associateWith { savedMatches.getValue(it) }" in pipeline
    assert '"summarySimilarityIsGeometricEvidence", false' in pipeline


def test_track_snapshot_enforces_native_observation_bound():
    source=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt").read_text()
    assert "observations + group.size > 100000" in source
    assert "val groups = snapshot(maxTracks)" in source


def test_m17_native_cancellation_uses_scoped_tokens_not_global_stop():
    root=ROOT/"reconstruction"
    native=(root/"src/cancellation.cpp").read_text()
    header=(root/"include/realityforge/cancellation.hpp").read_text()
    jni=(root/"src/jni.cpp").read_text()
    dense=(root/"src/dense.cpp").read_text()
    texture=(root/"src/texture.cpp").read_text()
    model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    execution=(ROOT/"app/src/main/java/com/realityforge/scanner/work/execution/ReconstructionExecutionController.kt").read_text()
    adapter=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt").read_text()
    assert "thread_local const std::atomic_bool* flag" in native
    assert "Scope::~Scope() noexcept {flag=previous_;}" in native
    assert "class Cancelled final : public Error" in header
    assert "std::unordered_map<std::uint64_t,std::shared_ptr<Operation>> operations" in jni
    operation = jni.split("struct Operation {", 1)[1].split("};", 1)[0]
    assert "std::make_shared<std::atomic_bool>(false)" in operation
    assert "std::make_shared<rf::progress::State>()" in operation
    assert "OperationLease lease(token)" in jni and "state->busy.exchange(true)" in jni
    assert "rf::cancellation::Scope cancellation(lease.state->flag)" in jni
    assert "rf::progress::Scope progress(lease.state->progress)" in jni
    assert "state->busy.store(false);activeOperations.fetch_sub(1)" in jni
    assert "if(!env->ExceptionCheck())rf::cancellation::checkpoint()" in jni
    progress=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/pipeline/NativeStageProgress.kt").read_text()
    assert "NativeCore.pollNativeProgress(token)" in progress
    assert "if (!finished.get()) NativeCore.cancelReconstruction(token)" in progress
    assert "withContext(NonCancellable) { reporter.cancelAndJoin() }" in progress
    assert "Java_com_realityforge_scanner_engine_NativeCore_cancelReconstruction" in jni
    assert "cancellation::checkpoint();" in dense and "cancellation::checkpoint();" in texture
    assert "NativeCore.cancelReconstruction" in execution and "task?.cancel()" in model
    assert "root.execution.pause" in model
    assert "NativeCore.reconstructSurfaceCheckpointed" in adapter
    assert "NativeCore.reconstructTexturedSurfaceCheckpointed" in adapter
    assert "Java_com_realityforge_scanner_engine_NativeCore_reconstructSurfaceCancellable" in jni
    assert "Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurfaceCancellable" in jni
    assert 'glbPartial.delete()' in adapter and 'texturedPartial.delete()' in adapter


def test_cancelled_worker_has_native_and_android_regressions():
    cpp=(ROOT/"reconstruction/tests/cancellation_test.cpp").read_text()
    android=(ROOT/"app/src/androidTest/java/com/realityforge/scanner/NativeSmokeTest.kt").read_text()
    assert "long-running dense scan observes cancellation" in cpp
    assert "nested scopes restore caller" in cpp
    assert "nativeCancellationTokensAreIsolatedAndRejectStaleHandles" in android
    isolated = android.split("fun nativeCancellationTokensAreIsolatedAndRejectStaleHandles()", 1)[1].split("@Test", 1)[0]
    assert isolated.count("catch(expected: java.util.concurrent.CancellationException)") == 2
    assert "assertFalse(glb.exists()||ply.exists()||photo.exists()||png.exists())" in isolated
    assert 'assertFalse(healthy.toString().take(150),healthy.has("error"))' in isolated
    assert "assertFalse(NativeCore.cancelReconstruction(first))" in isolated
    assert "catch(expected: IllegalArgumentException)" in isolated


def test_m18_depth_resume_is_input_identified_atomic_and_native_scoped():
    native=(ROOT/"reconstruction/src/depth_checkpoint.cpp").read_text()
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt").read_text()
    identity=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt").read_text()
    core=(ROOT/"reconstruction/src/dense.cpp").read_text()
    assert 'SHA-256' in identity and 'digest.update(rgba)' in identity
    assert 'M18_DOMAIN = "realityforge-m18-native-reference-depth-v3' in identity
    assert "digest.update(compatibility.algorithmDomain" in identity and "require(supports(compatibility))" in identity
    assert "cameraRows.forEach" in identity and "long(planes.toLong())" in identity
    assert "deleteRecursively" not in identity
    assert 'std::filesystem::rename(temporary,target,ec)' in native
    assert 'read32(bytes,28+64)!=crc32(' in native
    assert 'thread_local const Scope* active' in native
    assert 'depth_checkpoint::load(ref,map)' in core and 'depth_checkpoint::store(ref,map)' in core
    assert 'reconstructTexturedSurfaceCheckpointed(' in pipeline
    assert 'reconstructSurfaceCheckpointed(' in pipeline
    assert 'depthCheckpointResumed' in pipeline and 'depthCheckpointWritten' in pipeline
    assert 'depth_checkpoint::Scope cache' in (ROOT/"reconstruction/src/jni.cpp").read_text()


def test_m19_persisted_scan_evidence_and_local_export():
    root=ROOT/"app/src/main/java/com/realityforge/scanner"
    engine=(root/"engine/ScanQualityEvidence.kt").read_text()
    report=(root/"engine/ScanQualityReport.kt").read_text()
    pipeline=(root/"engine/SparsePipeline.kt").read_text()
    ui=(root/"ui/ScannerApp.kt").read_text()
    model=(root/"ScannerViewModel.kt").read_text()
    assert "RECIPROCAL" in engine.upper() and "PHOTOS_NOT_REGISTERED" in engine
    assert "NOT 360-degree completeness" in report
    assert 'ScanQualityReport.build(output, photos.size, diagnosticRecorder?.snapshot())' in pipeline
    assert 'diagnosticProgress.collectAsStateWithLifecycle()' in ui
    assert 'measured.scope.sourceRevision == job.input.projectRevision' in ui
    assert 'rememberBoundExporter(model, "json", "application/json")' in ui
    assert "model.beginArtifactExportRequest(extension)" in ui
    assert "model.completeArtifactExport(captured, uri)" in ui
    assert '"json", "glb"' in model
    assert "ScanQualityReportDeviceTest" in (ROOT/"app/src/androidTest/java/com/realityforge/scanner/ScanQualityReportDeviceTest.kt").read_text()


def test_distinct_pnp_policy_invalidates_sparse_and_frontier_reuse():
    folder = ROOT / 'app/src/main/java/com/realityforge/scanner/engine'
    frontier = (folder / 'IncrementalRegistration.kt').read_text()
    snapshot = (folder / 'pipeline/ProductionPipelineSnapshot.kt').read_text()
    assert 'const val PNP_POLICY = "native-pnp-exact-observation-support-v1"' in frontier
    assert 'registration-frontier-v3|$POLICY|$PNP_POLICY' in frontier
    assert '${com.realityforge.scanner.engine.IncrementalRegistration.PNP_POLICY}' in snapshot
    # Exact geometric support dedup follows, never masks, duplicate feature-ID rejection.
    assert frontier.index('frequencies[it.first] == 1') < frontier.index('geometry.add(it.second)')
