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
    assert methods==exports=={"reconstruct","registerCamera","reconstructMultiview","bundleAdjust","reconstructDense","quality","exportPoints"}


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
    assert "m07-cpu-stereo-1" in engine and "LensCalibration.read(repository, projectId)" in engine
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
    assert "denseFailure" in pipeline and 'output.put("densePreview",dense)' in pipeline
    assert '"dense.glb"' in store and '"dense.ply"' in store
