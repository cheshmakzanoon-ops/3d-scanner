"""Static source contracts. These tests do not substitute for Android compilation/runtime tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import pytest

ROOT=Path(__file__).resolve().parents[1]
ANDROID="{http://schemas.android.com/apk/res/android}"
TOOLS="{http://schemas.android.com/tools}"
APP = Path("app/src/main/java/com/realityforge/scanner")


def _feature_ui():
    """A partially adopted shell must satisfy the full published route contract."""
    source = (ROOT / APP / "ui/ScannerApp.kt").read_text()
    routes = ROOT / APP / "ui/navigation/ResultRoutes.kt"
    return routes.exists() or "ui.navigation.*" in source


def _feature_resources():
    return {node.attrib["name"]: "".join(node.itertext()) for node in
            ET.parse(ROOT / "app/src/main/res/values/agent_071.xml").getroot().findall("string")}


def _export_catalog():
    shell = (ROOT / APP / "ui/ScannerApp.kt").read_text()
    routes = (ROOT / APP / "ui/navigation/ResultRoutes.kt").read_text()
    catalog = (ROOT / APP / "export/RepositoryExportSource.kt").read_text()
    pending = (ROOT / APP / "export/PendingExportStore.kt").read_text()
    # Real provider calls, representation types and versioned identities replace
    # obsolete button variable names; a prose compatibility anchor is insufficient.
    assert "RepositoryExportSource(model.repository" in shell
    assert "GlbStructure.validate(file,mode)" in shell and "GlbStructure.validatePng(file)" in shell
    assert "source.validate(typed," in shell and "source.isCurrent(typed.artifact)" in shell
    assert "exportStore.save(typed)" in shell and "exportStore.restore()" in shell
    assert 'out.writeInt(1)' in pending and 'input.readInt() == 1' in pending
    assert 'MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes)' in pending
    assert "ExportCoordinator.digest(json) == identity.sha256" in catalog
    assert "p.resultRevision != identity.revision" in catalog and "p.resultKey != identity.artifactKey" in catalog
    assert "source.available(identity)" in routes and "onExport(it,option)" in routes
    return catalog


def _delegated_storage(repository):
    services_path = ROOT / APP / "data/operations/ProjectStorageServices.kt"
    adopted = services_path.exists() or "ProjectStorageServices" in repository
    if not adopted:
        return None
    services = services_path.read_text()
    archive = (ROOT / APP / "data/operations/CaptureArchiveServiceImpl.kt").read_text()
    preview = (ROOT / APP / "data/operations/PreviewReopeningServiceImpl.kt").read_text()
    operation = (ROOT / APP / "data/operations/ProjectOperation.kt").read_text()
    assert "val captureArchive: CaptureArchiveService" in services
    assert "captureArchive = CaptureArchiveServiceImpl(rawDao, paths)" in repository
    assert "previewReopening = PreviewReopeningServiceImpl(rawDao, paths)" in repository
    assert "services.captureArchive.export(projectId, destination)" in repository
    assert "services.previewReopening.currentPreview(id)" in repository
    assert "ProjectRepository.newCaptureDigest()" in archive and "digest.update(buffer, 0, read)" in archive
    assert "copied == photo.bytes" in archive and "digest.digest().toHex() == photo.sha256" in archive
    # The completion member must follow original integrity/revision checks and
    # precede ZIP completion; merely declaring the façade helpers cannot pass.
    assert archive.index("dao.project(projectId)?.revision == project.revision") < archive.index("ProjectRepository.captureManifestEntry()") < archive.index("zip.finish()")
    assert "ProjectRepository.validatePreviewIdentity(json, before.revision, key)" in preview
    assert "ProjectRepository.MAX_PREVIEW_BYTES" in preview and "input.read() == -1" in preview
    assert "reader.nextClean()" in preview and "after.revision != before.revision" in preview
    for method, next_method in (("inputSnapshot", "currentResult(projectId"), ("currentResult", "<T> withProjectAccess")):
        start = repository.index(f"override suspend fun {method}(session: ProjectAccessSession)")
        end = repository.index(f"override suspend fun {next_method}", start)
        body = repository[start:end]
        assert body.count("requireStableSession(session)") == 2
        assert "coordinator.acquire(" not in body and "withProjectAccess(" not in body
    assert "session.requireActive(sessionOwnerToken)" in repository
    assert "session?.invalidate()" in repository and "withContext(NonCancellable + Dispatchers.IO) { lease.close() }" in repository
    assert "ownerToken !== expectedOwnerToken || !active.get()" in operation and "active.set(false)" in operation
    assert "RECONSTRUCT(ProjectAccessMode.EXCLUSIVE, true, true)" in operation
    assert "PUBLISH_RESULT(ProjectAccessMode.EXCLUSIVE, true, true)" in operation
    return archive, preview


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
    method_names=re.findall(r"external fun (\w+)\(",kotlin)
    export_names=re.findall(r"Java_com_realityforge_scanner_engine_NativeCore_(\w+)\(\s*JNIEnv\*",cpp)
    # JNI definitions spell JNIEnv immediately after the opening parenthesis;
    # calls between wrappers are intentionally excluded from this inventory.
    assert len(method_names)==len(set(method_names))
    assert len(export_names)==len(set(export_names))
    legacy={"reconstruct","registerCamera","reconstructMultiview","bundleAdjust","reconstructDense","reconstructSurface","reconstructTexturedSurface","reconstructSurfaceCancellable","reconstructTexturedSurfaceCancellable","beginReconstruction","cancelReconstruction","finishReconstruction","reconstructSurfaceCheckpointed","reconstructTexturedSurfaceCheckpointed","quality","exportPoints"}
    scoped={"reconstructCancellable","registerCameraCancellable","reconstructMultiviewCancellable",
            "bundleAdjustCancellable","bundleAdjustProblemCancellable","pollNativeProgress",
            "reconstructDenseRefinedCancellable","reconstructTexturedSurfaceCompactCheckpointed",
            "reconstructTexturedSurfaceRefinedCheckpointed","exportPointsTyped"}
    # Preserve upstream's exact unadopted baseline; the reviewed003 boundary
    # helper also marks adoption, so deleting every scoped export cannot bypass it.
    adopted=bool((set(method_names)|set(export_names)) & scoped) or (ROOT/"reconstruction/include/realityforge/jni_support.hpp").exists()
    expected=legacy|scoped if adopted else legacy
    assert set(method_names)==set(export_names)==expected


def test_typed_point_export_preserves_dense_and_sparse_representation():
    jni=(ROOT/"reconstruction/src/jni.cpp").read_text()
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    assert "result.representation=dense==JNI_TRUE?rf::PointRepresentation::DensePoints:rf::PointRepresentation::SparsePoints" in jni
    assert "exportPointData(env,receiver,rows,path,glb,JNI_FALSE)" in jni
    assert "exportPointData(env,receiver,rows,path,glb,dense)" in jni
    assert re.search(r"NativeCore\.exportPointsTyped\(\s*densePacked,[^\n]*,\s*true\)",pipeline)
    core=(ROOT/"reconstruction/src/core.cpp").read_text()
    assert r'\"mode\":0' in core and "dense_points" in core and "sparse_points" in core


def test_no_dynamic_dependency_versions():
    source=(ROOT/"app/build.gradle.kts").read_text()+(ROOT/"build.gradle.kts").read_text()
    assert not re.search(r'"[^"\n]*:\+"',source)
    assert "latest.release" not in source
    assert 'compileSdk = 36' in source
    assert '28.2.13676358' in source


def test_sparse_output_disclosed_in_ui():
    source=(ROOT/"app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt").read_text()
    if _feature_ui():
        routes=(ROOT/APP/"ui/navigation/ResultRoutes.kt").read_text()
        resources=_feature_resources()
        for name, text in (("a071_sparse", "Sparse points"), ("a071_dense", "Dense points"), ("a071_surface", "Observed open surface")):
            assert resources[name] == text and f"R.string.{name}" in routes
        assert "Arbitrary scale" in resources["a071_unknown_scale"]
        assert "do not establish complete-object geometry or printability" in resources["a071_unknown_scale"]
        assert '"dense" -> if(dense!=null) PointCloudViewer(dense,' in routes
        assert '"surface" -> if(mesh!=null)' in routes and "SurfaceMeshViewer(mesh," in routes
        assert "else -> PointCloudViewer(result," in routes
    else:
        assert "not a finished mesh" in source
        assert "POINTS, not triangles" in source


def test_revision_guard_in_database():
    source=(ROOT/"app/src/main/java/com/realityforge/scanner/data/Database.kt").read_text()
    assert "revision = :revision" in source
    assert "resultRevision = NULL" in source
    assert "@Transaction suspend fun addPhoto" in source

def test_lens_calibration_is_wired_to_all_reconstruction_stages():
    engine=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    graph=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt").read_text()
    model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    lens=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/LensCalibration.kt").read_text()
    assert "m20-agent31-60-v2" in engine and "LensCalibration.read(repository, projectId)" in engine
    assert "LensProjection.COORDINATE_CONVENTION" in engine and "rectifier-alpha-v2" in engine
    assert "projection.normalized" in engine and "c.projection.normalized" in engine
    assert "projection?.normalized" in graph
    assert "fun calibrateLens(" in model and "dao.invalidate(id)" in model
    assert "findChessboardCorners" in lens and "calibrateCamera" in lens


def test_export_artifact_key_supports_milestone_versions():
    src=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    assert 'sparse-(?:m[0-9]{2}-)?r[0-9]+-[0-9a-f]{16}' in src


def test_source_manifest_matches_every_tracked_artifact():
    # Verify the exact tracked set as well as every byte; ignored build outputs
    # are intentionally outside the source tree. Other source contracts stay intact.
    import sys
    sys.path.insert(0, str(ROOT / "tools"))
    from source_manifest import verify
    assert verify(ROOT, "worktree")["files"] >= 105


def test_capture_package_requires_explicit_destination_and_preserves_hashes():
    repository=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    view_model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    screen=(ROOT/"app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt").read_text()
    assert "suspend fun exportCaptureBundle(projectId: String, destination: OutputStream)" in repository
    assert 'MessageDigest.getInstance("SHA-256")' in repository
    assert 'ZipEntry("session.json")' in repository
    _delegated_storage(repository)
    if _feature_ui():
        _export_catalog()
        assert "PendingKind.CAPTURE_ARCHIVE" in screen
        assert 'Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")' in screen
        assert "model.completeCaptureBundleExport(accepted.requestId,uri)" in screen
        assert "Original photographs remain private unless you choose the capture archive" in _feature_resources()["a071_export_help"]
    else:
        assert "ActivityResultContracts.CreateDocument(\"application/zip\")" in screen
        assert "model.exportCaptureBundle(it)" in screen
    assert "openOutputStream(uri, \"wt\")" in view_model


def test_bundle_adjustment_engine_and_jni_have_true_joint_contract():
    engine=(ROOT/"reconstruction/src/bundle_adjustment.cpp").read_text()
    jni=(ROOT/"reconstruction/src/jni.cpp").read_text()
    kotlin=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    assert "computeStep" in engine and "schur" in engine
    assert "out.cameras=std::move(candidateCameras)" in engine
    assert "cameraStep" in engine and "point.touched" in engine
    assert "Java_com_realityforge_scanner_engine_NativeCore_bundleAdjust" in jni
    assert "NativeCore.bundleAdjustProblemCancellable(" in kotlin
    assert "nativeCall(nativeToken, progress)" in kotlin
    assert "projection.distort(" in kotlin and "pixelValues.toDoubleArray()" in kotlin
    assert "nativeBatch.photoIds.toTypedArray()" in kotlin and "nativeBatch.trackIds.toTypedArray()" in kotlin
    assert "unknown-lens:${source.id}" in kotlin and "intrinsicRows" in kotlin
    assert 'joint.getInt("schema")==6' in kotlin and 'joint.getString("intrinsicPolicy")' in kotlin
    assert "verified in 0..proposals && accepted in 0..verified" in kotlin
    assert "bundleAdjustmentFailure" in kotlin
    assert "robustCostAfter" in kotlin


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
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    execution=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/pipeline/DensePreviewProfile.kt").read_text()
    repo=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    app=(ROOT/"app/src/main/java/com/realityforge/scanner/ui/ScannerApp.kt").read_text()
    assert "TEXCOORD_0" in native and "image/png" in native
    assert "depthAgreement(" in native and "visiblePixel(" in native
    assert "NativeCore.reconstructTexturedSurface" in adapter
    assert "NativeCore.reconstructTexturedSurfaceRefinedCheckpointed(" in adapter
    assert "executionProfile: DensePreviewProfile = DensePreviewProfile.Compatibility" in adapter
    assert "executionProfile == DensePreviewProfile.VerifiedRefinedCompact" in adapter
    assert 'ATLAS_ENGINE = "rf-atlas-v2-groups-zlib-radiometry3"' in execution
    assert "maxUvStretch=.02" in execution and "compact2048" in execution
    for source in (adapter,pipeline):
        assert "DenseExecutionPolicy.ATLAS_ENGINE" in source
        assert "DenseExecutionPolicy.texture(executionProfile)" in source
    assert "identity(PipelineStage.TEXTURE)" in adapter
    assert "textureError" in adapter and "textured.glb" in adapter and "texture.png" in adapter
    assert "textured.glb" in repo and "texture.png" in repo
    if _feature_ui():
        catalog=_export_catalog()
        assert 'add("textured.glb", ExportFormat.GLB, AssetRepresentation.PHOTOGRAPHIC_SURFACE, true)' in catalog
        assert 'add("texture.png", ExportFormat.PNG, AssetRepresentation.PHOTOGRAPHIC_ATLAS, true)' in catalog
    else:
        assert "exportTexturedGlb" in app and "exportTexturePng" in app


def test_photographic_viewer_uses_bounded_local_assets_and_on_demand_gl():
    parser=(ROOT/"app/src/main/java/com/realityforge/scanner/viewer/PhotographicGlb.kt").read_text()
    renderer=(ROOT/"app/src/main/java/com/realityforge/scanner/viewer/PhotographicRenderer.kt").read_text()
    screen=(ROOT/"app/src/main/java/com/realityforge/scanner/ui/PhotographicModelViewer.kt").read_text()
    assert "MAX_FILE_BYTES" in parser and "MAX_IMAGE_PIXELS" in parser
    assert "CRC32" in parser and "External model data is not permitted" in parser
    assert "glDrawElements" in renderer and "glTF v=0" in renderer
    assert "RENDERMODE_WHEN_DIRTY" in screen
    assert "Lifecycle.Event.ON_PAUSE" in screen
    loader_path=ROOT/APP/"viewer/loading/PhotographicModelLoader.kt"
    if loader_path.exists() or "PhotographicModelLoader.load(file)" in screen:
        loader=loader_path.read_text()
        structure=(ROOT/APP/"viewer/loading/GlbStructure.kt").read_text()
        backend=(ROOT/APP/"viewer/RenderBackend.kt").read_text()
        assert "PhotographicModelLoader.load(file)" in screen
        assert "owned?.close()" in screen and "surface.close()" in screen
        assert "surface.pauseRendering()" in screen and "interface StudioRenderSurface : AutoCloseable" in backend
        assert "catch (cancelled: CancellationException) { throw cancelled }" in screen
        assert "GlbStructure.readBounded(file, cancelled)" in loader
        assert "GlbStructure.validate(bytes, 4, cancelled)" in loader
        assert "PhotographicGlb.decode(bytes, cancelled)" in loader and "context.ensureActive()" in loader
        assert "assetReference = null; byteReference = null" in loader
        assert "file.length() in 28L..PhotographicGlb.MAX_FILE_BYTES.toLong()" in structure
        assert 'if (cancelled()) throw CancellationException("Model load cancelled.")' in structure
        assert 'require(input.read() == -1) { "Model changed while loading." }' in structure
        assert 'require(!buffer.has("uri"))' in structure and '!image.has("uri")' in structure
    else:
        assert "surface.dispose()" in screen
        assert "PhotographicGlb.read(file)" in screen


def test_session_reopen_and_export_have_revision_and_operation_guards():
    repository=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    assert "MAX_PREVIEW_BYTES" in repository and "suspend fun currentPreview(" in repository
    assert "storedRevision is Int || storedRevision is Long" in repository
    assert "Project changed during capture export" in repository
    _delegated_storage(repository)
    assert "busy.compareAndSet(false, true)" in model and "selectionGeneration" in model
    assert "repository.currentPreview(id)" in model
    assert "stream.use { repository.exportCaptureBundle" in model


def test_adaptive_dense_adapter_has_eight_view_and_bitmap_lifetime_guards():
    adapter=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt").read_text()
    policy=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt").read_text()
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    assert "MAX_VIEWS = 8" in policy and "MAX_SAMPLES = 100_000L" in policy
    assert "DenseWorkload.selectCameraIndices" in adapter
    assert "finally { image.recycle() }" in adapter
    assert "val images = chosen.map" not in adapter
    assert "DenseWorkload.depthBounds" in adapter and "DenseWorkload.atlasPixels" in adapter
    assert "m20-agent31-60-v2" in pipeline and "DenseWorkload.tier" in pipeline
    assert "profile=$executionProfile" in pipeline and "LensProjection.COORDINATE_CONVENTION" in pipeline
    assert "selectedCameraIndices" in adapter and "plannedGridSamples" in adapter
    assert "cameraIds.distinct().size == cameraIds.size" in adapter
    assert "selection.sourcePhotoIds.keys == selection.referencePhotoIds.toSet()" in adapter
    assert "activeIds.size <= DenseWorkload.MAX_VIEWS" in adapter
    assert "Native execution did not preserve the approved source plan" in adapter
    assert "PyramidRectifier.writeUprightRgba(" in adapter and "PixelTransform.exif(" in adapter
    assert "originalToLevel=originalToLevel" in adapter
    assert 'calibrationProvenance="$provenance|decoder=${AndroidImageDecoder.POLICY_IDENTITY}|decodeEdge=$edge"' in adapter


def test_dense_and_texture_cache_identities_consume_the_shared_versioned_policy():
    engine=ROOT/"app/src/main/java/com/realityforge/scanner/engine"
    policy=(engine/"pipeline/DensePreviewProfile.kt").read_text()
    pipeline=(engine/"SparsePipeline.kt").read_text()
    adapter=(engine/"DensePreview.kt").read_text()
    assert "cpu-refined-v3" in policy and "normal3|strict1|normalConsistency1|angle35" in policy
    assert "discontinuityAwareConsistency=false" in policy
    for source in (adapter,pipeline):
        assert "DenseExecutionPolicy.depth(executionProfile)" in source
        assert "DenseExecutionPolicy.texture(executionProfile)" in source
    assert "pipelineSnapshot?.identity(PipelineStage.DENSE)?.digest" in adapter
    assert "pipelineSnapshot?.identity(PipelineStage.TEXTURE)?.digest" in adapter
    assert "val textureInputIdentity" in adapter and 'put("textureInputIdentity"' in adapter


def test_incremental_registration_uses_shared_triangulation_and_verified_landmarks():
    folder=ROOT/"app/src/main/java/com/realityforge/scanner/engine"
    source=(folder/"IncrementalRegistration.kt").read_text()
    bridge=(folder/"NativeFrontierGeometry.kt").read_text()
    pipeline=(folder/"SparsePipeline.kt").read_text()
    assert "identities.size < 2" in source and "frequencies[it.first] == 1" in source
    assert "validatePose(" in source and "projectedError(" in source
    assert "NativeCore.reconstructMultiview" in bridge and "NativeCore.registerCamera" in bridge
    assert "NativeCore.reconstructMultiviewCancellable(" in bridge and "NativeCore.registerCameraCancellable(" in bridge
    assert "IncrementalRegistration.grow(" in pipeline and "incrementalRegistrationFailure" in pipeline
    assert "NativeFrontierGeometry.forOperation(nativeToken)" in pipeline
    assert "m20-agent31-60-v2" in pipeline and ".take(66)" in pipeline
    assert "FeatureExtractor()" in pipeline and "FeatureMatcher()" in pipeline
    assert "FeatureCacheIdentity(photo.sha256, decoderIdentity, settings" in pipeline


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
    adapter=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt").read_text()
    assert "thread_local const std::atomic_bool* flag" in native
    assert "Scope::~Scope() noexcept {flag=previous_;}" in native
    assert "class Cancelled final : public Error" in header
    assert "std::unordered_map<std::uint64_t,std::shared_ptr<Operation>> operations" in jni
    assert "OperationLease lease(token)" in jni
    assert "rf::cancellation::Scope cancellation(lease.state->flag)" in jni
    assert "rf::progress::Scope progress(lease.state->progress)" in jni
    assert "state->busy.exchange(true)" in jni and "activeOperations.fetch_sub(1)" in jni
    assert "if(!env->ExceptionCheck())rf::cancellation::checkpoint();" in jni
    assert 'raise(env,"java/util/concurrent/CancellationException"' in (root/"include/realityforge/jni_support.hpp").read_text()
    assert "Java_com_realityforge_scanner_engine_NativeCore_cancelReconstruction" in jni
    assert "cancellation::checkpoint();" in dense and "cancellation::checkpoint();" in texture
    assert "NativeCore.cancelReconstruction(token)" in model and "task?.cancel()" in model
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
    assert "catch(expected: java.util.concurrent.CancellationException)" in android
    assert "cancellation::Cancelled" in (ROOT/"reconstruction/include/realityforge/jni_support.hpp").read_text()


def test_m18_depth_resume_is_input_identified_atomic_and_native_scoped():
    native=(ROOT/"reconstruction/src/depth_checkpoint.cpp").read_text()
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt").read_text()
    identity=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt").read_text()
    core=(ROOT/"reconstruction/src/dense.cpp").read_text()
    assert 'SHA-256' in identity and 'digest.update(rgba)' in identity
    assert r'private const val M18_DOMAIN = "realityforge-m18-native-reference-depth-v1\u0000"' in identity
    assert 'val M18_COMPATIBILITY = Compatibility(18, M18_DOMAIN)' in identity
    assert 'compatibility: Compatibility = M18_COMPATIBILITY' in identity
    assert 'require(supports(compatibility))' in identity
    assert 'digest.update(compatibility.algorithmDomain.toByteArray(StandardCharsets.UTF_8))' in identity
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
    assert 'output.put("scanDiagnostics",ScanQualityReport.build(output,photos.size))' in pipeline
    if _feature_ui():
        catalog=_export_catalog()
        routes=(ROOT/APP/"ui/navigation/ResultRoutes.kt").read_text()
        resources=_feature_resources()
        assert 'add("json", ExportFormat.JSON, AssetRepresentation.DIAGNOSTICS)' in catalog
        assert 'val evidence=result.optJSONObject("scanDiagnostics")' in routes
        assert 'evidence.optInt("registeredCameraCount")' in routes and 'evidence.optInt("observedMeshTriangles")' in routes
        assert "not whole-object completeness, physical accuracy or a quality score" in resources["a071_evidence_limits"]
        assert "ReviewCommand.CALIBRATE -> beginCalibration()" in ui
        assert "ReviewCommand.CLEAR_CALIBRATION -> model.clearLensCalibration()" in ui
    else:
        assert 'model.export(it, "json")' in ui
    assert '"json", "glb"' in model
    assert "ScanQualityReportDeviceTest" in (ROOT/"app/src/androidTest/java/com/realityforge/scanner/ScanQualityReportDeviceTest.kt").read_text()
