"""Focused synthetic evidence checks, not production/Android qualification."""
import json
import os
import pathlib
import subprocess
import pytest

@pytest.fixture(scope="module")
def evidence():
    exe = pathlib.Path(os.environ["OBSERVED_PATCH_TEST_EXE"])
    completed = subprocess.run([str(exe)], capture_output=True, text=True, timeout=60, check=True)
    rows = [json.loads(line) for line in completed.stdout.splitlines()]
    assert rows[-1] == {"syntheticControls": "PASS_IMPLEMENTATION_EXPECTATIONS", "safeSurfaceDisambiguation": "FAIL_SUB_BOUND_DISTINCT_LAYERS", "commonModeBias": "UNDETECTABLE", "physicalQuality": "NOT_ESTABLISHED"}
    return {row["case"]: row for row in rows[:-1]}


def test_positive_geometry_and_explicit_missing_area(evidence):
    flat = evidence["flat"]
    assert flat["faces"] > 0 and flat["maxTruthError"] == 0
    assert 0 < flat["area"] < flat["fullSourceArea"]
    assert 0 < flat["truthCoveredWithin0_01"] < flat["truthSamples"]
    slanted = evidence["slanted"]
    assert slanted["faces"] > 0 and slanted["maxTruthError"] < .002
    assert slanted["maxNormalDegrees"] < 2


@pytest.mark.parametrize("case", ["separated_at_bound", "separated_above_bound", "separated_far", "foreground_extra_layer", "third_view_foreground", "thin_opposing", "disjoint", "edge_touch", "exact_zero", "output_cap", "work_cap", "cancel", "nonfinite", "allocation_failure", "camera_plane"])
def test_refusal_never_exposes_partial_geometry(evidence, case):
    row = evidence[case]
    assert row["faces"] == 0 and row["status"] != "SUCCESS_DERIVED_INTERPOLATED_HOST_QA"
    assert row["maxTruthError"] is None and row["maxTruthToGenerated"] is None


def test_common_mode_bias_is_not_claimed_repaired(evidence):
    row = evidence["common_mode_bias_undetectable"]
    assert row["faces"] > 0 and row["maxTruthError"] >= .0099


def test_memory_and_work_bounds(evidence):
    assert max(row["ownedBytes"] for row in evidence.values()) <= 8*1024*1024
    assert evidence["work_cap"]["work"] == 101
    for name in ("output_cap", "work_cap", "cancel", "allocation_failure"):
        assert evidence[name]["faces"] == 0


def test_permutation_and_gauge_evidence(evidence):
    for key in ("faces", "area", "maxTruthError", "work"):
        assert evidence["flat"][key] == evidence["permutation"][key]
    assert evidence["rigid_translation"]["maxTruthError"] < 1e-12
    assert evidence["missing_middle"]["faces"] > 0


def test_sub_bound_distinct_layers_expose_failed_disambiguation(evidence):
    row=evidence["separated_below_bound"]
    assert row["faces"] > 0 and row["maxTruthError"] >= .0049
    # This test records the failure honestly; it does not qualify the kernel.
