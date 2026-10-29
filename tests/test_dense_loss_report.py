"""Read-only photometric loss report contracts; no reconstruction is rerun here."""
import json
import sys
from pathlib import Path
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import dense_loss_report as report


def view(index, accepted, **counts):
    rejections = {reason: counts.get(reason, 0) for reason in report.REASONS}
    evaluated = accepted + sum(rejections.values())
    return {
        "referenceView": index,
        "photometric": {
            "coverage": "COMPLETE",
            "sampledSamples": evaluated,
            "unknownSamples": 0,
            "evaluatedSamples": evaluated,
            "acceptedSamples": accepted,
            "rejectionCounts": rejections,
            "validReferenceMeanHistogram": [evaluated - rejections["invalidReferencePatch"]]
            + [0] * 15,
            "validLowVarianceMeanHistogram": [rejections["validLowVariance"]] + [0] * 15,
        },
        "reciprocal": {"coverage": "COMPLETE", "evaluatedSamples": 0},
    }


def document(*views):
    return {
        "lossDiagnostics": {
            "schema": 1,
            "scope": "ORIGINATING_DENSE_BRANCHES",
            "available": True,
            "viewCount": len(views),
            "views": list(views),
        }
    }


def test_report_totals_and_dominant_reason():
    built = report.build_report(
        document(
            view(0, 10, invalidOrBoundaryBest=6, ncc=2),
            view(1, 40, invalidOrBoundaryBest=9, ncc=1),
        )
    )
    assert built.evaluated == 68
    assert built.accepted == 50
    assert built.rejected == 18
    assert built.totals["invalidOrBoundaryBest"] == 15
    reason, share = built.dominant()
    assert reason == "invalidOrBoundaryBest"
    assert share == pytest.approx(15 / 18)
    band, indicated = report.classify(built)
    assert band == "DECISIVE"
    assert "range" in indicated
    text = report.render_markdown(built, "unit-fixture")
    assert "unit-fixture" in text
    assert "not authorization to change a gate" in text
    carried = report.receipt(built, "unit-fixture")
    assert carried["classification"] == "DESCRIPTIVE_PHOTOMETRIC_LOSS_NOT_QUALITY_SCORE"
    assert carried["totals"]["rejectionCounts"]["invalidOrBoundaryBest"] == 15


def test_schema_two_boundary_subreasons_are_carried_and_reconciled():
    upgraded = document(view(0, 10, invalidOrBoundaryBest=6), view(1, 10))
    upgraded["lossDiagnostics"]["schema"] = 2
    upgraded["lossDiagnostics"]["views"][0]["photometric"]["rejectionCounts"][
        "invalidOrBoundaryBestReasons"
    ] = {"ambiguousMinimum": 1, "nearBoundaryBest": 0, "farBoundaryBest": 5}
    built = report.build_report(upgraded)
    assert built.views[0].boundary == {
        "ambiguousMinimum": 1,
        "nearBoundaryBest": 0,
        "farBoundaryBest": 5,
    }
    assert "farBoundaryBest 5" in report.render_markdown(built)
    assert "far edge dominates" in report.classify(built)[1]
    assert report.receipt(built)["views"][0]["invalidOrBoundaryBestReasons"] == {
        "ambiguousMinimum": 1,
        "nearBoundaryBest": 0,
        "farBoundaryBest": 5,
    }
    upgraded["lossDiagnostics"]["views"][0]["photometric"]["rejectionCounts"][
        "invalidOrBoundaryBestReasons"
    ]["farBoundaryBest"] = 6
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(upgraded)


def test_no_rejections_is_reported_without_a_ranking():
    built = report.build_report(document(view(0, 12), view(1, 12)))
    assert built.dominant() == (None, 0.0)
    assert report.classify(built)[0] == "NO_REJECTIONS"


def test_mixed_profile_is_not_called_decisive():
    built = report.build_report(
        document(
            view(0, 10, invalidOrBoundaryBest=2, ncc=2, uniqueness=2),
            view(1, 10, validLowVariance=2, invalidInverseDepth=2),
        )
    )
    assert report.classify(built)[0] == "NO_SINGLE_CAUSE"


def test_broken_accounting_is_rejected():
    broken = document(view(0, 10, ncc=1))
    broken["lossDiagnostics"]["views"][0]["photometric"]["acceptedSamples"] = 11
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(broken)


def test_unavailable_and_malformed_artifacts_exit_nonzero(tmp_path, capsys):
    absent = tmp_path / "absent.json"
    absent.write_text(json.dumps({"lossDiagnostics": {"schema": 1, "available": False}}))
    assert report.main(["--input", str(absent)]) == 2
    assert "UNAVAILABLE" in capsys.readouterr().err

    unknown = tmp_path / "unknown.json"
    unknown.write_text(json.dumps({"lossDiagnostics": {"schema": 99, "available": True}}))
    assert report.main(["--input", str(unknown)]) == 1
    assert "INVALID" in capsys.readouterr().err

    not_json = tmp_path / "not.json"
    not_json.write_text("not json")
    assert report.main(["--input", str(not_json)]) == 1


def test_cli_writes_a_receipt(tmp_path, capsys):
    artifact = tmp_path / "dense.json"
    artifact.write_text(
        json.dumps(document(view(0, 4, ncc=4), view(1, 6, validLowVariance=2)))
    )
    out = tmp_path / "loss.json"
    assert report.main(["--input", str(artifact), "--json-out", str(out), "--label", "cli"]) == 0
    assert "Photometric depth loss - cli" in capsys.readouterr().out
    carried = json.loads(out.read_text())
    assert carried["dominant"]["reason"] == "ncc"
    assert carried["totals"]["evaluated"] == 16
    assert carried["views"][1]["rejectionCounts"]["validLowVariance"] == 2


def test_accepts_surface_wrapper_and_reports_real_producer_output():
    """End-to-end against the actual native producer when its library is built."""
    numpy = pytest.importorskip("numpy")
    sys.path.insert(0, str(ROOT / "tools"))
    try:
        from native_api import NativeEngine
        NativeEngine()
    except (FileNotFoundError, OSError) as failure:
        pytest.skip(f"native library unavailable: {failure}")

    width, height = 104, 76
    texture = numpy.random.default_rng(504).integers(0, 256, (256, 256, 3))
    y, x = numpy.mgrid[:height, :width]
    images, cameras = [], []
    for camera in range(3):
        u = numpy.clip(((x - 51.5) / 116 * 5 + camera * .55) * 31 + 128, 0, 254.999)
        v = numpy.clip(((y - 37.5) / 116 * 5) * 31 + 128, 0, 254.999)
        ix, iy = u.astype(int), v.astype(int)
        a, b = (u - ix)[..., None], (v - iy)[..., None]
        rgb = numpy.rint((1 - a) * (1 - b) * texture[iy, ix] + a * (1 - b) * texture[iy, ix + 1]
                         + (1 - a) * b * texture[iy + 1, ix] + a * b * texture[iy + 1, ix + 1]).astype(numpy.uint8)
        images.append(numpy.concatenate((rgb, numpy.full((height, width, 1), 255, numpy.uint8)), axis=2))
        cameras.append([1, 0, 0, 0, 1, 0, 0, 0, 1, -camera * .55, 0, 0, 116, 116, 51.5, 37.5])
    result = NativeEngine().surface(
        numpy.asarray(images), numpy.asarray(cameras, dtype=numpy.float64), 3.1, 7.0, 56, 3
    )
    built = report.build_report(result)
    assert built.evaluated > 0 and built.accepted <= built.evaluated
    assert built.rejected == sum(built.totals.values())
    assert "SYNTHETIC" not in report.render_markdown(built)
    funnel = report.build_surface_report(result)
    assert funnel is not None and funnel.candidates > 0
    steps = funnel.funnel()
    assert steps[0].reached == funnel.candidates
    assert funnel.accepted == funnel.candidates - sum(step.failed for step in steps)
    assert sum(funnel.missing_normal.values()) == funnel.gates["missingNormal"]


def surface_view(camera, accepted=0, **gates):
    counts = {gate: gates.get(gate, 0) for gate in report.SURFACE_GATES}
    counts[report.SURFACE_ACCEPTED] = accepted
    return {
        "camera": camera,
        "fusionCandidates": sum(counts.values()),
        "fusionCandidateFirstGate": counts,
        "missingNormalReasons": {
            "sourceNormal": counts["missingNormal"],
            "accumulatorNormal": 0,
            "bothNormals": 0,
        },
    }


def surface_document(*views):
    return {
        "mesh": {
            "lossDiagnostics": {
                "schema": 3,
                "scope": report.SURFACE_SCOPE,
                "available": True,
                "viewCount": len(views),
                "views": list(views),
            }
        }
    }


def test_surface_funnel_derives_reached_counts_from_first_gate():
    built = report.build_surface_report(
        surface_document(
            surface_view(0, accepted=5, sameCamera=60, missingNormal=10, outsideRadius=5),
            surface_view(1, accepted=2, sameCamera=40, notCloser=2),
        )
    )
    assert built.candidates == 124 and built.accepted == 7
    funnel = {step.gate: step for step in built.funnel()}
    assert (funnel["sameCamera"].reached, funnel["sameCamera"].failed) == (124, 100)
    assert (funnel["missingNormal"].reached, funnel["missingNormal"].failed) == (24, 10)
    assert (funnel["notCloser"].reached, funnel["notCloser"].failed) == (14, 2)
    assert (funnel["outsideRadius"].reached, funnel["outsideRadius"].failed) == (12, 5)
    assert funnel["sameCamera"].failure_share == pytest.approx(100 / 124)
    assert funnel["memberSeesProposed"].passed == 7
    carried = report.surface_receipt(built)
    assert carried["acceptedCandidateVisits"] == 7
    assert carried["funnel"][0] == {
        "gate": "sameCamera",
        "reached": 124,
        "failed": 100,
        "passed": 24,
    }
    text = report.render_surface_markdown(built, "unit")
    assert "Surface fusion gate funnel - unit" in text
    assert "not retained fusions" in text


def test_surface_gate_outcomes_must_sum_to_candidates():
    drifted = surface_document(
        surface_view(0, accepted=2, sameCamera=4), surface_view(1, accepted=0)
    )
    drifted["mesh"]["lossDiagnostics"]["views"][0]["fusionCandidateFirstGate"][
        "sameCamera"
    ] = 3
    with pytest.raises(report.MalformedDiagnostics):
        report.build_surface_report(drifted)
    renamed = surface_document(surface_view(0, accepted=1), surface_view(1, accepted=1))
    gates = renamed["mesh"]["lossDiagnostics"]["views"][0]["fusionCandidateFirstGate"]
    gates["accepted"] = gates.pop(report.SURFACE_ACCEPTED)
    with pytest.raises(report.MalformedDiagnostics):
        report.build_surface_report(renamed)


def test_surface_missing_normal_attribution_must_reconcile():
    upgraded = surface_document(
        surface_view(0, missingNormal=4), surface_view(1, sameCamera=3, accepted=3)
    )
    upgraded["mesh"]["lossDiagnostics"]["views"][0]["missingNormalReasons"][
        "sourceNormal"
    ] = 3
    with pytest.raises(report.MalformedDiagnostics):
        report.build_surface_report(upgraded)


def test_surface_only_artifact_reports_a_funnel(tmp_path, capsys):
    artifact = tmp_path / "surface.json"
    artifact.write_text(
        json.dumps(
            surface_document(
                surface_view(0, accepted=2, sameCamera=8), surface_view(1, accepted=1)
            )
        )
    )
    out = tmp_path / "funnel.json"
    assert report.main(
        ["--input", str(artifact), "--json-out", str(out), "--label", "surface"]
    ) == 0
    assert "Surface fusion gate funnel - surface" in capsys.readouterr().out
    carried = json.loads(out.read_text())
    assert carried["schema"] == 2 and carried["views"] is None
    assert carried["surface"]["candidates"] == 11
    assert carried["surface"]["acceptedCandidateVisits"] == 3
