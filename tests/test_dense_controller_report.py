"""Read persisted controller envelopes using actual synthetic native diagnostics.

These are host reader checks, not an installed Android reconstruction journey.
"""
from copy import deepcopy
import json
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import dense_loss_report as report


@pytest.fixture(scope="module")
def native_result():
    from tools.native_api import NativeEngine
    from tools.qa.dense_profile_sweep import synthetic_fixture

    images, cameras = synthetic_fixture()
    return NativeEngine().surface(images, cameras, 3.1, 7.0, 56, 3)


def controller_envelope(native):
    # SparsePipeline.kt copies these two complete native sections. Its root
    # schema describes a published model, not the originating diagnostics.
    return {
        "schema": 1,
        "representation": "synthetic-controller-envelope",
        "denseStatus": "ready",
        "meshStatus": "ready",
        "densePreview": deepcopy(native["dense"]),
        "meshPreview": deepcopy(native["mesh"]),
    }


def test_controller_envelope_preserves_native_report_and_retention(native_result):
    envelope = controller_envelope(native_result)
    before = deepcopy(envelope)
    assert report.build_report(envelope) == report.build_report(native_result)
    assert report.build_surface_report(envelope) == report.build_surface_report(native_result)
    assert envelope == before


@pytest.mark.parametrize("shape", ["raw", "preview", "direct", "standalone"])
def test_dense_input_shapes_remain_readable(native_result, shape):
    dense = native_result["dense"]
    artifact = {
        "raw": native_result,
        "preview": controller_envelope(native_result),
        "direct": dense,
        "standalone": dense["lossDiagnostics"],
    }[shape]
    assert report.build_report(artifact) == report.build_report(native_result)


@pytest.mark.parametrize("shape", ["raw", "preview", "direct", "standalone"])
def test_surface_input_shapes_remain_readable(native_result, shape):
    mesh = native_result["mesh"]
    artifact = {
        "raw": native_result,
        "preview": controller_envelope(native_result),
        "direct": mesh,
        "standalone": mesh["lossDiagnostics"],
    }[shape]
    actual = report.build_surface_report(artifact)
    expected = report.build_surface_report(native_result)
    assert actual.candidates == expected.candidates
    assert actual.accepted == expected.accepted
    if shape == "standalone":
        assert actual.retention is None
    else:
        assert actual.retention == expected.retention


@pytest.mark.parametrize("native_key,preview_key,builder", [
    ("dense", "densePreview", report.build_report),
    ("mesh", "meshPreview", report.build_surface_report),
])
def test_identical_result_aliases_are_allowed(native_result, native_key, preview_key, builder):
    artifact = controller_envelope(native_result)
    artifact[native_key] = deepcopy(artifact[preview_key])
    assert builder(artifact) == builder(native_result)


@pytest.mark.parametrize("native_key,preview_key,builder", [
    ("dense", "densePreview", report.build_report),
    ("mesh", "meshPreview", report.build_surface_report),
])
@pytest.mark.parametrize("change", ["counter", "boolean_alias", "extra_metadata"])
def test_conflicting_aliases_are_rejected(native_result, native_key, preview_key, builder, change):
    artifact = controller_envelope(native_result)
    artifact[native_key] = deepcopy(artifact[preview_key])
    changed = artifact[preview_key]
    if change == "counter":
        changed["lossDiagnostics"]["viewCount"] += 1
    elif change == "boolean_alias":
        # Mapping equality treats true == 1; alias validation must not.
        changed["lossDiagnostics"]["available"] = 1
    else:
        changed["geometryReused"] = True
    with pytest.raises(report.MalformedDiagnostics, match="Conflicting"):
        builder(artifact)


@pytest.mark.parametrize("key,builder", [
    ("dense", report.build_report),
    ("densePreview", report.build_report),
    ("mesh", report.build_surface_report),
    ("meshPreview", report.build_surface_report),
])
@pytest.mark.parametrize("value", [None, False, 0, [], "unavailable"])
def test_present_nonobject_sections_are_malformed(key, builder, value):
    with pytest.raises(report.MalformedDiagnostics, match="not an object"):
        builder({key: value})


def test_old_persisted_preview_without_diagnostics_remains_unknown():
    artifact = {
        "schema": 1,
        "densePreview": {"pointCount": 25, "geometryReused": True},
        "meshPreview": {"vertexCount": 20, "triangleCount": 10},
    }
    with pytest.raises(report.UnavailableDiagnostics):
        report.build_report(artifact)
    assert report.build_surface_report(artifact) is None


@pytest.mark.parametrize("section", ["dense", "densePreview"])
@pytest.mark.parametrize("value", [False, 0, [], "unavailable"])
def test_malformed_enclosed_dense_diagnostics_do_not_become_unknown(section, value):
    with pytest.raises(report.MalformedDiagnostics, match="not an object"):
        report.build_report({section: {"lossDiagnostics": value}})


def test_surface_diagnostics_in_dense_preview_are_not_silently_ignored(native_result):
    artifact = {"densePreview": {"lossDiagnostics": native_result["mesh"]["lossDiagnostics"]}}
    with pytest.raises(report.MalformedDiagnostics, match="Dense result contains surface"):
        report.build_report(artifact)


def test_sparse_only_published_schema_is_not_a_dense_diagnostic_schema():
    artifact = {"schema": 1, "points": [], "denseStatus": "unavailable"}
    with pytest.raises(report.UnavailableDiagnostics):
        report.build_report(artifact)
    assert report.build_surface_report(artifact) is None


def test_cached_surface_without_originating_counts_remains_unknown(native_result):
    artifact = controller_envelope(native_result)
    artifact["meshPreview"]["lossDiagnostics"].update(
        available=False, viewCount=None, views=None, topologyAdmission=None,
        removedExactFaces=None, triangleLimitReached=None,
    )
    with pytest.raises(report.UnavailableDiagnostics):
        report.build_surface_report(artifact)


def test_reused_geometry_keeps_originating_counts_and_does_not_mutate_artifact(native_result):
    artifact = controller_envelope(native_result)
    artifact["densePreview"]["geometryReused"] = True
    before = deepcopy(artifact)
    dense = report.build_report(artifact)
    surface = report.build_surface_report(artifact)
    assert dense == report.build_report(native_result)
    assert surface == report.build_surface_report(native_result)
    carried = report.receipt(dense, "historical synthetic snapshot", surface)
    assert carried["surface"]["scope"] == "ORIGINATING_SURFACE_EVALUATION"
    assert artifact == before


@pytest.mark.parametrize("section,preview,builder", [
    ("dense", "densePreview", report.build_report),
    ("mesh", "meshPreview", report.build_surface_report),
])
def test_direct_and_enclosed_diagnostics_are_ambiguous(native_result, section, preview, builder):
    artifact = {
        preview: native_result[section],
        "lossDiagnostics": native_result[section]["lossDiagnostics"],
    }
    with pytest.raises(report.MalformedDiagnostics, match="Ambiguous"):
        builder(artifact)


def test_report_cli_reads_saved_controller_preview(native_result, tmp_path, capsys):
    path = tmp_path / "before-reopen-preview.json"
    output = tmp_path / "loss-receipt.json"
    path.write_text(json.dumps(controller_envelope(native_result)), encoding="utf-8")
    before = path.read_bytes()
    assert report.main(["--input", str(path), "--json-out", str(output)]) == 0
    receipt = json.loads(output.read_text())
    assert receipt["totals"]["evaluated"] > 0
    assert receipt["surface"]["retention"] is not None
    assert "Surface fusion gate funnel" in capsys.readouterr().out
    assert path.read_bytes() == before
