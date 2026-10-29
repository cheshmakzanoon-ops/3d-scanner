"""Profile sweep contracts; the rule is pure and the native run is optional."""
from pathlib import Path
import sys
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import dense_profile_sweep as sweep


def metrics(profile="56x3", normals=10, fusions=0, intersections=558, nonmanifold=0):
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
        boundary_edges=1000,
        intersections=intersections,
        nonmanifold_edges=nonmanifold,
        intersection_budget_exceeded=False,
    )


def test_profile_parsing_is_bounded():
    assert sweep.parse_profile("72x2") == sweep.Profile(72, 2)
    assert sweep.parse_profile(" 96 X 3 ") == sweep.Profile(96, 3)
    for bad in ("72", "x3", "9x3", "72x9", "72x2x1", "a72x3"):
        with pytest.raises(sweep.InvalidProfile):
            sweep.parse_profile(bad)


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
