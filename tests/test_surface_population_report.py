"""Surface population and availability validation against native synthetic output."""
from copy import deepcopy
import json
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import dense_loss_report as report


POPULATION_FIELDS = (
    "inputSamples", "acceptedSamples", "confidenceRejected", "vertexLimitRejected",
)
UNAVAILABLE_FIELDS = (
    "viewCount", "removedExactFaces", "views", "triangleLimitReached",
    "topologyAdmission",
)


@pytest.fixture(scope="module")
def native_result():
    """Reconstruct a labeled synthetic fixture with the actual host producer."""
    pytest.importorskip("numpy")
    from tools.native_api import NativeEngine
    from tools.qa.dense_profile_sweep import synthetic_fixture

    try:
        engine = NativeEngine()
    except (FileNotFoundError, OSError) as failure:
        pytest.skip(f"native library unavailable: {failure}")
    images, cameras = synthetic_fixture()
    return engine.surface(images, cameras, 3.1, 7.0, 56, 3)


def test_native_surface_population_is_preserved(native_result):
    original = deepcopy(native_result)
    mesh = native_result["mesh"]
    diagnostics = mesh["lossDiagnostics"]
    assert mesh["inputSamples"] > 0
    assert mesh["inputSamples"] == sum(view["inputSamples"] for view in diagnostics["views"])
    assert mesh["acceptedSamples"] == sum(view["acceptedSamples"] for view in diagnostics["views"])
    for view in diagnostics["views"]:
        assert view["inputSamples"] == (
            view["acceptedSamples"] + view["confidenceRejected"] + view["vertexLimitRejected"]
        )
    built = report.build_surface_report(native_result)
    assert built.retention.accepted_samples == mesh["acceptedSamples"]
    assert native_result == original


@pytest.mark.parametrize("target", ["mesh", "view"])
def test_real_accepted_surface_cannot_claim_zero_input(native_result, target):
    artifact = deepcopy(native_result)
    population = artifact["mesh"]
    if target == "view":
        population = population["lossDiagnostics"]["views"][0]
    assert population["acceptedSamples"] > 0
    population["inputSamples"] = 0
    with pytest.raises(report.MalformedDiagnostics, match="inputSamples do not reconcile"):
        report.build_surface_report(artifact)


@pytest.mark.parametrize("target,field", [
    ("mesh", "inputSamples"), ("mesh", "acceptedSamples"),
    *(("view", field) for field in POPULATION_FIELDS),
])
@pytest.mark.parametrize("bad", [True, -1, 1.5, "0", None, report.MAX_SAMPLES + 1])
def test_surface_population_requires_bounded_integers(native_result, target, field, bad):
    artifact = deepcopy(native_result)
    population = artifact["mesh"]
    if target == "view":
        population = population["lossDiagnostics"]["views"][0]
    population[field] = bad
    with pytest.raises(report.MalformedDiagnostics, match="bounded non-negative integer"):
        report.build_surface_report(artifact)


@pytest.mark.parametrize("field", POPULATION_FIELDS)
@pytest.mark.parametrize("standalone", [False, True])
def test_missing_surface_population_is_not_inferred_as_zero(native_result, field, standalone):
    artifact = deepcopy(native_result)
    diagnostics = artifact["mesh"]["lossDiagnostics"]
    del diagnostics["views"][0][field]
    with pytest.raises(report.MalformedDiagnostics, match=field):
        report.build_surface_report(diagnostics if standalone else artifact)


def test_missing_mesh_input_population_is_unknown_only_for_standalone(native_result):
    artifact = deepcopy(native_result)
    del artifact["mesh"]["inputSamples"]
    with pytest.raises(report.MalformedDiagnostics, match="mesh.inputSamples"):
        report.build_surface_report(artifact)
    standalone = artifact["mesh"]["lossDiagnostics"]
    built = report.build_surface_report(standalone)
    assert built.retention is None
    assert report.surface_receipt(built)["retentionStatus"] == "UNAVAILABLE_WITHOUT_MESH_COUNTS"


@pytest.mark.parametrize("schema", [3, 4])
@pytest.mark.parametrize("field", POPULATION_FIELDS)
def test_standalone_views_still_require_complete_population_accounting(native_result, schema, field):
    diagnostics = deepcopy(native_result["mesh"]["lossDiagnostics"])
    diagnostics["schema"] = schema
    assert report.build_surface_report(diagnostics).retention is None
    diagnostics["views"][0][field] += 1
    with pytest.raises(report.MalformedDiagnostics, match="inputSamples do not reconcile"):
        report.build_surface_report(diagnostics)


def test_standalone_combined_surface_population_respects_global_bound(native_result):
    diagnostics = deepcopy(native_result["mesh"]["lossDiagnostics"])
    for index, view in enumerate(diagnostics["views"]):
        count = report.MAX_SAMPLES if index == 0 else 1
        view.update(inputSamples=count, acceptedSamples=count,
                    confidenceRejected=0, vertexLimitRejected=0)
    with pytest.raises(report.MalformedDiagnostics, match="Surface total inputSamples"):
        report.build_surface_report(diagnostics)


def unavailable_artifact(native_result):
    artifact = deepcopy(native_result)
    diagnostics = artifact["mesh"]["lossDiagnostics"]
    diagnostics["available"] = False
    diagnostics.update({field: None for field in UNAVAILABLE_FIELDS})
    return artifact


def test_unavailable_native_shape_preserves_unknown_surface_with_valid_dense(native_result, tmp_path):
    artifact = unavailable_artifact(native_result)
    with pytest.raises(report.UnavailableDiagnostics):
        report.build_surface_report(artifact)
    source, output = tmp_path / "source.json", tmp_path / "receipt.json"
    source.write_text(json.dumps(artifact))
    assert report.main(["--input", str(source), "--json-out", str(output)]) == 0
    carried = json.loads(output.read_text())
    assert carried["surface"] is None
    assert carried["totals"]["evaluated"] > 0


@pytest.mark.parametrize("field,value", [
    ("viewCount", 0), ("removedExactFaces", 0), ("views", []),
    ("triangleLimitReached", False), ("topologyAdmission", {}),
])
def test_unavailable_surface_rejects_even_zero_or_empty_measurements(native_result, field, value):
    artifact = unavailable_artifact(native_result)
    artifact["mesh"]["lossDiagnostics"][field] = value
    with pytest.raises(report.MalformedDiagnostics, match=f"measured {field}"):
        report.build_surface_report(artifact)


def test_dense_sibling_cannot_hide_contradictory_unavailable_surface(native_result, tmp_path, capsys):
    artifact = deepcopy(native_result)
    artifact["mesh"]["lossDiagnostics"]["available"] = False
    source, output = tmp_path / "source.json", tmp_path / "receipt.json"
    source.write_text(json.dumps(artifact))
    assert report.main(["--input", str(source), "--json-out", str(output)]) == 1
    assert "INVALID" in capsys.readouterr().err
    assert not output.exists()
