"""Reject contradictory originating evidence without inventing cached measurements."""
from copy import deepcopy
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa import dense_loss_report as report


def measured_view(index, *, sampled=10):
    """Small synthetic accounting example, not a reconstruction quality fixture."""
    counts = {name: 0 for name in report.REASONS}
    counts.update(invalidReferencePatch=1, validLowVariance=2, ncc=1)
    return {
        "referenceView": index,
        "photometric": {
            "coverage": "COMPLETE" if sampled == 10 else "PARTIAL",
            "sampledSamples": sampled,
            "unknownSamples": sampled - 10,
            "evaluatedSamples": 10,
            "acceptedSamples": 6,
            "rejectionCounts": counts,
            "validReferenceMeanHistogram": [4, 5] + [0] * 14,
            "validLowVarianceMeanHistogram": [1, 1] + [0] * 14,
        },
    }


def diagnostic(schema=1):
    views = [measured_view(0), measured_view(1)]
    if schema == 2:
        for view in views:
            view["photometric"]["rejectionCounts"]["invalidOrBoundaryBestReasons"] = {
                "ambiguousMinimum": 0, "nearBoundaryBest": 0, "farBoundaryBest": 0,
            }
    return {
        "schema": schema, "scope": "ORIGINATING_DENSE_BRANCHES", "available": True,
        "viewCount": 2, "views": views,
    }


def reused_view(index, sampled=10):
    return {
        "referenceView": index,
        "photometric": {
            "coverage": "UNAVAILABLE", "sampledSamples": sampled,
            "unknownSamples": sampled, "evaluatedSamples": None,
            "acceptedSamples": None, "rejectionCounts": None,
            "validReferenceMeanHistogram": None, "validLowVarianceMeanHistogram": None,
        },
    }


@pytest.mark.parametrize("schema", [1, 2])
def test_supported_schemas_report_only_measured_partial_samples(schema):
    document = diagnostic(schema)
    document["views"][0]["photometric"].update(
        coverage="PARTIAL", sampledSamples=100, unknownSamples=90,
    )
    before = deepcopy(document)
    built = report.build_report(document)
    assert built.evaluated == 20 and built.accepted == 12 and built.rejected == 8
    assert built.views[0].sampled == 100 and built.views[0].evaluated == 10
    assert document == before


@pytest.mark.parametrize("field,value", [
    ("schema", True), ("schema", 1.0), ("schema", "1"),
    ("available", "false"), ("available", 1), ("available", None),
    ("viewCount", True), ("viewCount", 2.0), ("viewCount", "2"),
])
def test_metadata_requires_json_types_without_boolean_integer_coercion(field, value):
    document = diagnostic()
    document[field] = value
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(document)


@pytest.mark.parametrize("value", [False, 0.0, "0", None])
def test_reference_index_requires_an_actual_integer(value):
    document = diagnostic()
    document["views"][0]["referenceView"] = value
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(document)


@pytest.mark.parametrize("histogram", ["validReferenceMeanHistogram", "validLowVarianceMeanHistogram"])
@pytest.mark.parametrize("value", [-1, True, 1.0, "1", None, report.MAX_SAMPLES + 1])
def test_histogram_bins_are_individually_bounded_integers(histogram, value):
    document = diagnostic()
    document["views"][0]["photometric"][histogram][0] = value
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(document)


def test_negative_histogram_bins_cannot_cancel_against_positive_bins():
    document = diagnostic()
    document["views"][0]["photometric"]["validReferenceMeanHistogram"] = [-1, 10] + [0] * 14
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(document)


def test_low_variance_distribution_must_be_contained_in_valid_reference_distribution():
    document = diagnostic()
    # Both histogram totals still reconcile, but low-variance patches cannot
    # occupy a luminance bin with no valid reference patch.
    document["views"][0]["photometric"]["validLowVarianceMeanHistogram"] = [0, 1, 1] + [0] * 13
    with pytest.raises(report.MalformedDiagnostics, match="exceeds reference bins"):
        report.build_report(document)


@pytest.mark.parametrize("changes", [
    {"sampledSamples": 0}, {"sampledSamples": 9}, {"sampledSamples": False},
    {"unknownSamples": -1}, {"unknownSamples": True}, {"unknownSamples": "0"},
    {"unknownSamples": 1}, {"evaluatedSamples": 0}, {"evaluatedSamples": False},
    {"coverage": "PARTIAL"}, {"coverage": "UNAVAILABLE"}, {"coverage": None},
    {"sampledSamples": 20, "unknownSamples": 10, "coverage": "COMPLETE"},
    {"sampledSamples": 20, "unknownSamples": 9, "coverage": "PARTIAL"},
])
def test_photometric_coverage_and_unknown_counts_reconcile(changes):
    document = diagnostic()
    document["views"][0]["photometric"].update(changes)
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(document)


@pytest.mark.parametrize("unknown_index", [0, 1])
def test_mixed_reused_maps_remain_unavailable_not_measured_zero(unknown_index):
    document = diagnostic()
    document["views"][unknown_index] = reused_view(unknown_index)
    before = deepcopy(document)
    with pytest.raises(report.UnavailableDiagnostics, match="No originating photometric"):
        report.build_report(document)
    assert document == before


def test_unavailable_first_map_does_not_hide_invalid_later_measurement():
    document = diagnostic()
    document["views"][0] = reused_view(0)
    document["views"][1]["photometric"]["validReferenceMeanHistogram"][0] = "invalid"
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(document)


@pytest.mark.parametrize("field,value", [
    ("unknownSamples", 0), ("evaluatedSamples", 0), ("acceptedSamples", 0),
    ("rejectionCounts", {}), ("validReferenceMeanHistogram", [0] * 16),
    ("validLowVarianceMeanHistogram", [0] * 16),
])
def test_reused_maps_require_unknown_counts_not_fabricated_measurements(field, value):
    document = diagnostic()
    document["views"][0] = reused_view(0)
    document["views"][0]["photometric"][field] = value
    with pytest.raises(report.MalformedDiagnostics):
        report.build_report(document)


def test_total_sampled_budget_includes_unavailable_maps():
    document = diagnostic()
    document["views"] = [reused_view(0, 60000), reused_view(1, 60000)]
    with pytest.raises(report.MalformedDiagnostics, match="sample budget"):
        report.build_report(document)


def test_total_sampled_budget_applies_across_views():
    document = diagnostic()
    document["views"] = [measured_view(0, sampled=60000), measured_view(1, sampled=60000)]
    with pytest.raises(report.MalformedDiagnostics, match="sample budget"):
        report.build_report(document)


def test_old_checkpoint_is_unavailable_without_fake_counts():
    document = {
        "schema": 1, "scope": "ORIGINATING_DENSE_BRANCHES", "available": False,
        "views": None, "viewCount": None,
    }
    with pytest.raises(report.UnavailableDiagnostics):
        report.build_report(document)
    document["views"] = [measured_view(0), measured_view(1)]
    document["viewCount"] = 2
    with pytest.raises(report.MalformedDiagnostics, match="Unavailable dense diagnostics"):
        report.build_report(document)
