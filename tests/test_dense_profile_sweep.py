"""Profile sweep contracts; the rule is pure and the native run is optional."""
from dataclasses import replace
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import dense_profile_sweep as sweep


def metrics(profile="56x3", normals=10, fusions=0, intersections=558, nonmanifold=0,
            boundaries=1000, incomplete=False):
    return sweep.Metrics(
        profile=profile,
        evaluated=100,
        accepted=50,
        rejected=50,
        ambiguous_minimum=40,
        boundary_edge=10,
        ncc=0,
        supported_normals=normals,
        fusion_candidates=100,
        fused_observations=fusions,
        vertex_count=1000,
        triangle_count=1000,
        boundary_edges=boundaries,
        intersections=intersections,
        nonmanifold_edges=nonmanifold,
        intersection_budget_exceeded=incomplete,
    )


def test_profile_parsing_is_bounded():
    assert sweep.parse_profile("72x2") == sweep.Profile(72, 2)
    assert sweep.parse_profile(" 96 X 3 ") == sweep.Profile(96, 3)
    for bad in ("72", "x3", "9x3", "72x9", "72x2x1", "a72x3", "²x3", "7" * 5000 + "x3", None):
        with pytest.raises(sweep.InvalidProfile):
            sweep.parse_profile(bad)


@pytest.mark.parametrize("planes,stride", [(True, 3), (56.0, 3), (56, True), (56, 3.0)])
def test_direct_profile_values_cannot_bypass_integer_bounds(planes, stride):
    with pytest.raises(sweep.InvalidProfile):
        sweep.Profile(planes, stride)


def test_baseline_is_never_promoted():
    baseline = metrics()
    assert sweep.evaluate_profile(baseline, baseline) == "BASELINE"


def test_more_normals_and_fusions_without_topology_cost_promotes():
    baseline = metrics()
    candidate = metrics("72x3", normals=40, fusions=9)
    assert sweep.evaluate_profile(baseline, candidate) == "PROMOTE"


def test_topology_regression_blocks_promotion():
    baseline = metrics()
    candidate = metrics("72x3", normals=40, fusions=9, intersections=600)
    assert sweep.evaluate_profile(baseline, candidate) == "REJECT"
    candidate = metrics("72x3", normals=40, fusions=9, nonmanifold=2)
    assert sweep.evaluate_profile(baseline, candidate) == "REJECT"


def test_more_boundaries_rejects_a_profile_despite_both_gains():
    baseline = metrics()
    candidate = metrics("72x3", normals=40, fusions=9, boundaries=1001)
    assert sweep.evaluate_profile(baseline, candidate) == "REJECT"


@pytest.mark.parametrize("baseline_incomplete,candidate_incomplete", [(True, False), (False, True), (True, True)])
def test_incomplete_intersection_accounting_never_promotes(baseline_incomplete, candidate_incomplete):
    baseline = metrics(incomplete=baseline_incomplete)
    candidate = metrics("72x3", normals=40, fusions=9, incomplete=candidate_incomplete)
    assert sweep.evaluate_profile(baseline, candidate) == "INCONCLUSIVE"


@pytest.mark.parametrize("changed", [{"boundaries": 1001}, {"nonmanifold": 1}])
def test_known_topology_regression_precedes_incomplete_evidence(changed):
    baseline = metrics(incomplete=True)
    candidate = metrics("72x3", normals=40, fusions=9, incomplete=True, **changed)
    assert sweep.evaluate_profile(baseline, candidate) == "REJECT"


def test_incomplete_candidate_lower_bound_can_prove_regression_against_complete_baseline():
    baseline = metrics()
    candidate = metrics("72x3", normals=40, fusions=9, incomplete=True, intersections=559)
    assert sweep.evaluate_profile(baseline, candidate) == "REJECT"


def test_incomplete_baseline_lower_bound_cannot_prove_intersection_regression():
    baseline = metrics(incomplete=True, intersections=1)
    candidate = metrics("72x3", normals=40, fusions=9, intersections=2)
    assert sweep.evaluate_profile(baseline, candidate) == "INCONCLUSIVE"


def test_regression_is_rejected_without_any_improvement():
    assert sweep.evaluate_profile(metrics(), metrics("72x3", boundaries=1001)) == "REJECT"


def test_same_profile_name_does_not_hide_conflicting_measurements():
    with pytest.raises(sweep.loss.MalformedDiagnostics, match="Conflicting measurements"):
        sweep.evaluate_profile(metrics(), metrics(boundaries=1001))


def test_no_measured_gain_is_inconclusive():
    baseline = metrics()
    assert sweep.evaluate_profile(baseline, metrics("40x3")) == "INCONCLUSIVE"


def test_normals_without_fusions_does_not_promote():
    baseline = metrics()
    assert sweep.evaluate_profile(baseline, metrics("72x3", normals=40)) == "INCONCLUSIVE"


def test_receipt_carries_the_rule_and_limitations():
    baseline = metrics()
    candidate = metrics("72x3", normals=40, fusions=9)
    rows = [baseline, candidate]
    decisions = {row.profile: sweep.evaluate_profile(baseline, row) for row in rows}
    carried = sweep.receipt(rows, decisions)
    assert carried["classification"] == "SYNTHETIC_PROFILE_SWEEP_NOT_QUALITY_NOT_DEFAULT"
    assert decisions["72x3"] == "PROMOTE"
    assert "real licensed three-photo artifact NOT_RUN" in carried["limitations"][0]
    assert "nonmanifold" in carried["rule"]["promote"]
    assert "boundaries" in carried["rule"]["promote"]
    assert "complete" in carried["rule"]["promote"]
    assert carried["schema"] == 2
    assert carried["profiles"][1]["decisionReasons"] == [
        "both_gains_with_complete_nonregressing_topology"
    ]


def test_receipt_and_markdown_explain_incomplete_rejection():
    baseline = metrics()
    candidate = metrics("72x3", normals=40, fusions=9, boundaries=1001, incomplete=True)
    rows = [baseline, candidate]
    decisions = {row.profile: sweep.evaluate_profile(baseline, row) for row in rows}
    reasons = sweep.receipt(rows, decisions)["profiles"][1]["decisionReasons"]
    assert reasons == ["boundary_edges_increased", "candidate_topology_incomplete"]
    rendered = sweep.render_markdown(rows, decisions)
    assert "topologyComplete" in rendered and "False" in rendered
    assert all(reason in rendered for reason in reasons)


def test_receipt_uses_the_measured_custom_baseline():
    baseline = metrics("72x3")
    candidate = metrics("56x3", normals=40, fusions=9)
    decisions = {"72x3": "BASELINE", "56x3": "PROMOTE"}
    assert sweep.receipt([baseline, candidate], decisions)["rule"]["baseline"] == "72x3"
    assert sweep.receipt([baseline, candidate], decisions, " 072 X 03 ")["rule"]["baseline"] == "72x3"
    with pytest.raises(sweep.loss.MalformedDiagnostics, match="baseline does not match"):
        sweep.receipt([baseline, candidate], decisions, "56x3")


def test_receipt_rejects_forged_decisions_and_duplicate_measurements():
    rows = [metrics(), metrics("72x3", normals=40, fusions=9, incomplete=True)]
    with pytest.raises(sweep.loss.MalformedDiagnostics, match="Decisions do not match"):
        sweep.receipt(rows, {"56x3": "BASELINE", "72x3": "PROMOTE"})
    with pytest.raises(sweep.loss.MalformedDiagnostics, match="distinct measured"):
        sweep.receipt([rows[0], rows[0]], {"56x3": "BASELINE"})


def result_document():
    """Synthetic, internally consistent producer-shaped data; no engine is simulated."""
    dense_views, surface_views = [], []
    for camera in range(2):
        counts = {name: 0 for name in sweep.loss.REASONS}
        counts["invalidOrBoundaryBest"] = 200
        counts["invalidOrBoundaryBestReasons"] = {
            "ambiguousMinimum": 150, "nearBoundaryBest": 20, "farBoundaryBest": 30,
        }
        dense_views.append({
            "referenceView": camera,
            "photometric": {
                "coverage": "COMPLETE", "sampledSamples": 1000, "unknownSamples": 0,
                "evaluatedSamples": 1000, "acceptedSamples": 800, "rejectionCounts": counts,
                "validReferenceMeanHistogram": [1000] + [0] * 15,
                "validLowVarianceMeanHistogram": [0] * 16,
            },
        })
        gates = {name: 0 for name in sweep.loss.SURFACE_GATES}
        gates["sameCamera"] = 300
        gates[sweep.loss.SURFACE_ACCEPTED] = 20 * camera
        surface_views.append({
            "camera": camera, "inputSamples": 600, "acceptedSamples": 590,
            "confidenceRejected": 10, "vertexLimitRejected": 0,
            "normalValidity": {"supported": 500, "sparseSupport": 100,
                               "discontinuity": 0, "illConditioned": 0, "invalid": 0},
            "fusionCandidates": sum(gates.values()), "fusionCandidateFirstGate": gates,
            "missingNormalReasons": {name: 0 for name in sweep.loss.MISSING_NORMAL_REASONS},
        })
    return {
        "dense": {"lossDiagnostics": {
            "schema": 2, "scope": "ORIGINATING_DENSE_BRANCHES", "available": True,
            "viewCount": 2, "views": dense_views,
        }},
        "mesh": {
            "inputSamples": 1200, "acceptedSamples": 1180, "fusedObservations": 10,
            "vertexCount": 1170, "triangleCount": 1000, "boundaryEdges": 1000,
            "intersections": 20, "nonmanifoldEdges": 0, "topologyIncomplete": False,
            "lossDiagnostics": {
                "schema": 3, "scope": sweep.loss.SURFACE_SCOPE, "available": True,
                "viewCount": 2, "views": surface_views, "triangleLimitReached": False,
                "topologyAdmission": {"passes": 1, "proposedWeldGroups": 10,
                    "rolledBackWeldGroups": 0, "restoredSourceVertices": 0,
                    "unresolvedSourceVertexLinks": 0},
            },
        },
    }


def test_metric_extraction_reconciles_producer_shaped_counts():
    measured = sweep.metrics_from_result(sweep.Profile(56, 3), result_document())
    assert (measured.evaluated, measured.accepted, measured.rejected) == (2000, 1600, 400)
    assert (measured.ambiguous_minimum, measured.boundary_edge, measured.ncc) == (300, 100, 0)
    assert (measured.supported_normals, measured.fused_observations) == (1000, 10)


@pytest.mark.parametrize("field", ["fusedObservations", "vertexCount", "triangleCount", "boundaryEdges", "intersections", "nonmanifoldEdges"])
@pytest.mark.parametrize("bad", [None, True, -1, 1.5, "1", 10**30])
def test_invalid_mesh_counts_fail_closed(field, bad):
    document = result_document()
    document["mesh"][field] = bad
    with pytest.raises(sweep.loss.MalformedDiagnostics):
        sweep.metrics_from_result(sweep.Profile(56, 3), document)


@pytest.mark.parametrize("bad", [None, 0, 1, "false", [], {}])
def test_topology_completion_requires_a_boolean(bad):
    document = result_document()
    document["mesh"]["topologyIncomplete"] = bad
    with pytest.raises(sweep.loss.MalformedDiagnostics):
        sweep.metrics_from_result(sweep.Profile(56, 3), document)


@pytest.mark.parametrize("bad", [True, -1, 500.0, "500", 10**30, 501])
def test_invalid_or_unreconciled_normal_counts_fail_closed(bad):
    document = result_document()
    document["mesh"]["lossDiagnostics"]["views"][0]["normalValidity"]["supported"] = bad
    with pytest.raises(sweep.loss.MalformedDiagnostics):
        sweep.metrics_from_result(sweep.Profile(56, 3), document)


def test_missing_normal_and_topology_counts_do_not_become_zero():
    document = result_document()
    del document["mesh"]["lossDiagnostics"]["views"][0]["normalValidity"]
    with pytest.raises(sweep.loss.MalformedDiagnostics):
        sweep.metrics_from_result(sweep.Profile(56, 3), document)
    document = result_document()
    del document["mesh"]["boundaryEdges"]
    with pytest.raises(sweep.loss.MalformedDiagnostics):
        sweep.metrics_from_result(sweep.Profile(56, 3), document)


def test_legacy_missing_subreasons_are_unavailable_not_measured_zero():
    document = result_document()
    document["dense"]["lossDiagnostics"]["schema"] = 1
    for view in document["dense"]["lossDiagnostics"]["views"]:
        del view["photometric"]["rejectionCounts"]["invalidOrBoundaryBestReasons"]
    with pytest.raises(sweep.loss.UnavailableDiagnostics, match="subreasons"):
        sweep.metrics_from_result(sweep.Profile(56, 3), document)


@pytest.mark.parametrize("field", ["boundary_edges", "intersections", "nonmanifold_edges", "fused_observations"])
def test_direct_metrics_cannot_bypass_count_validation(field):
    with pytest.raises(sweep.loss.MalformedDiagnostics):
        replace(metrics(), **{field: True})


def test_cli_canonicalizes_baseline_before_selection_and_records_it(monkeypatch, tmp_path, capsys):
    calls = []

    def measured(profiles, baseline_name):
        calls.append(([profile.name for profile in profiles], baseline_name))
        rows = [metrics("72x3"), metrics("56x3", normals=40, fusions=9)]
        return rows, {"72x3": "BASELINE", "56x3": "PROMOTE"}

    monkeypatch.setattr(sweep, "sweep", measured)
    output = tmp_path / "custom-baseline.json"
    assert sweep.main(["--baseline", " 072 X 03 ", "--profile", "72x3", "--profile", "56x3",
                       "--json-out", str(output)]) == 0
    assert calls == [(["72x3", "56x3"], "72x3")]
    assert json.loads(output.read_text())["rule"]["baseline"] == "72x3"
    assert "PROMOTE" in capsys.readouterr().out


def test_cli_rejects_canonical_duplicates_before_running_engine(monkeypatch, capsys):
    def unexpected(*args):
        pytest.fail("Invalid profiles must be rejected before native execution")

    monkeypatch.setattr(sweep, "sweep", unexpected)
    assert sweep.main(["--profile", "72x3", "--profile", " 072 X 03 "]) == 1
    assert "Duplicate profile" in capsys.readouterr().err


def test_native_load_failure_returns_documented_exit_two(monkeypatch, tmp_path, capsys):
    def unavailable():
        raise OSError("fixture shared library is absent")

    monkeypatch.setitem(sys.modules, "tools.native_api", SimpleNamespace(NativeEngine=unavailable))
    output = tmp_path / "not-written.json"
    assert sweep.main(["--profile", "56x3", "--json-out", str(output)]) == 2
    assert "NATIVE_UNAVAILABLE" in capsys.readouterr().err
    assert not output.exists()


def test_cli_unavailable_diagnostics_do_not_publish_a_receipt(monkeypatch, tmp_path, capsys):
    def unavailable(*args):
        raise sweep.loss.UnavailableDiagnostics("originating measurements unavailable")

    monkeypatch.setattr(sweep, "sweep", unavailable)
    output = tmp_path / "not-written.json"
    assert sweep.main(["--json-out", str(output)]) == 2
    assert "UNAVAILABLE" in capsys.readouterr().err
    assert not output.exists()


def test_native_sweep_measures_two_profiles():
    pytest.importorskip("numpy")
    sys.path.insert(0, str(ROOT / "tools"))
    try:
        from native_api import NativeEngine
        NativeEngine()
    except (FileNotFoundError, OSError) as failure:
        pytest.skip(f"native library unavailable: {failure}")
    rows, decisions = sweep.sweep(
        [sweep.Profile(56, 3), sweep.Profile(24, 2)], "56x3"
    )
    assert [row.profile for row in rows] == ["56x3", "24x2"]
    assert all(row.evaluated > 0 for row in rows)
    assert decisions["56x3"] == "BASELINE"
    assert decisions["24x2"] in {"PROMOTE", "REJECT", "INCONCLUSIVE"}
    assert all(row.triangle_count >= 0 for row in rows)
