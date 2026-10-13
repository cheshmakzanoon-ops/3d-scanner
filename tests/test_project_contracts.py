"""Static source contracts. These tests do not substitute for Android compilation/runtime tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import pytest

ROOT=Path(__file__).resolve().parents[1]
ANDROID="{http://schemas.android.com/apk/res/android}"
TOOLS="{http://schemas.android.com/tools}"


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
    assert methods==exports=={"reconstruct","registerCamera","reconstructMultiview","bundleAdjust","reconstructDense","reconstructSurface","reconstructTexturedSurface","reconstructSurfaceCancellable","reconstructTexturedSurfaceCancellable","beginReconstruction","cancelReconstruction","finishReconstruction","reconstructSurfaceCheckpointed","reconstructTexturedSurfaceCheckpointed","quality","exportPoints"}


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
    assert "revision = :revision" in source
    assert "resultRevision = NULL" in source
    assert "@Transaction suspend fun addPhoto" in source

def test_lens_calibration_is_wired_to_all_reconstruction_stages():
    engine=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt").read_text()
    graph=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/MultiViewTracks.kt").read_text()
    model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    lens=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/LensCalibration.kt").read_text()
    assert "m16-registration-frontier-1" in engine and "LensCalibration.read(repository, projectId)" in engine
    assert "projection.normalized" in engine and "c.projection.normalized" in engine
    assert "projection?.normalized" in graph
    assert "fun calibrateLens(" in model and "dao.invalidate(id)" in model
    assert "findChessboardCorners" in lens and "calibrateCamera" in lens


def test_export_artifact_key_supports_milestone_versions():
    src=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    assert 'sparse-(?:m[0-9]{2}-)?r[0-9]+-[0-9a-f]{16}' in src


def test_source_manifest_matches_every_tracked_artifact():
    import hashlib
    manifest=(ROOT/"SOURCE_MANIFEST.sha256").read_text().splitlines()
    assert len(manifest) >= 105
    assert len(set(row.split("  ",1)[1] for row in manifest)) == len(manifest)
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
    assert 'MessageDigest.getInstance("SHA-256")' in repository
    assert 'ZipEntry("session.json")' in repository
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
    assert "NativeCore.bundleAdjust(" in kotlin
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
    assert "Lifecycle.Event.ON_PAUSE" in screen and "surface.dispose()" in screen
    assert "PhotographicGlb.read(file)" in screen


def test_session_reopen_and_export_have_revision_and_operation_guards():
    repository=(ROOT/"app/src/main/java/com/realityforge/scanner/data/ProjectRepository.kt").read_text()
    model=(ROOT/"app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt").read_text()
    assert "MAX_PREVIEW_BYTES" in repository and "suspend fun currentPreview(" in repository
    assert "storedRevision is Int || storedRevision is Long" in repository
    assert "Project changed during capture export" in repository
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
    assert "m16-registration-frontier-1" in pipeline and "DenseWorkload.tier" in pipeline
    assert "selectedCameraIndices" in adapter and "plannedGridSamples" in adapter


def test_incremental_registration_uses_shared_triangulation_and_verified_landmarks():
    folder=ROOT/"app/src/main/java/com/realityforge/scanner/engine"
    source=(folder/"IncrementalRegistration.kt").read_text()
    bridge=(folder/"NativeFrontierGeometry.kt").read_text()
    pipeline=(folder/"SparsePipeline.kt").read_text()
    assert "identities.size < 2" in source and "frequencies[it.first] == 1" in source
    assert "validatePose(" in source and "projectedError(" in source
    assert "NativeCore.reconstructMultiview" in bridge and "NativeCore.registerCamera" in bridge
    assert "IncrementalRegistration.grow(" in pipeline and "incrementalRegistrationFailure" in pipeline
    assert "m16-registration-frontier-1" in pipeline and ".take(66)" in pipeline


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
    assert "std::unordered_map<std::uint64_t,std::shared_ptr<std::atomic_bool>> operations" in jni
    assert "rf::cancellation::Scope scope(flag.get())" in jni
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
    assert "Reconstruction cancelled" in android


def test_m18_depth_resume_is_input_identified_atomic_and_native_scoped():
    native=(ROOT/"reconstruction/src/depth_checkpoint.cpp").read_text()
    pipeline=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DensePreview.kt").read_text()
    identity=(ROOT/"app/src/main/java/com/realityforge/scanner/engine/DepthCheckpointIdentity.kt").read_text()
    core=(ROOT/"reconstruction/src/dense.cpp").read_text()
    assert 'SHA-256' in identity and 'digest.update(rgba)' in identity
    assert 'digest.update("realityforge-m18-native-reference-depth-v1' in identity
    assert 'std::filesystem::rename(temporary,target,ec)' in native
    assert 'read32(bytes,28+64)!=crc32(' in native
    assert 'thread_local const Scope* active' in native
    assert 'depth_checkpoint::load(ref,map)' in core and 'depth_checkpoint::store(ref,map)' in core
    assert 'reconstructTexturedSurfaceCheckpointed(' in pipeline
    assert 'reconstructSurfaceCheckpointed(' in pipeline
    assert 'depthCheckpointResumed' in pipeline and 'depthCheckpointWritten' in pipeline
    assert 'depth_checkpoint::Scope cache' in (ROOT/"reconstruction/src/jni.cpp").read_text()
