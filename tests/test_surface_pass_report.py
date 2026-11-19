"""Adversarial accounting cases for the originating schema-4 topology record."""
from copy import deepcopy
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa.surface_pass_report import LINK_REASONS, validate_pass_attribution


def inspected(number, groups, invalid=0, rolled_back=0, restored=0, reasons=None):
    return {
        "pass": number, "inspected": True, "retainedWeldGroups": groups,
        "invalidWeldGroups": invalid, "rolledBackWeldGroups": rolled_back,
        "restoredSourceVertices": restored,
        "invalidLinkReasons": reasons or {
            "branchingLink": invalid, "directionConflict": invalid,
            "disconnectedLink": 0, "invalidEndpointCount": 0,
        },
    }


def uninspected(number):
    return {"pass": number, "inspected": False, "retainedWeldGroups": None,
            "invalidWeldGroups": None, "invalidLinkReasons": None,
            "rolledBackWeldGroups": None, "restoredSourceVertices": None}


def admission(kind="fallback"):
    result = {
        "passes": 3, "proposedWeldGroups": 5, "rolledBackWeldGroups": 5,
        "restoredSourceVertices": 12, "unresolvedSourceVertexLinks": 0,
        "locallyRolledBackWeldGroups": 3, "fallbackRolledBackWeldGroups": 2,
        "retainedWeldGroups": 0,
        "passDetails": [inspected(1, 5, 3, 3, 7), inspected(2, 2, 1, 2, 5), uninspected(3)],
    }
    if kind == "partial":
        result.update(passes=2, rolledBackWeldGroups=3, restoredSourceVertices=7,
                      fallbackRolledBackWeldGroups=0, retainedWeldGroups=2,
                      passDetails=[inspected(1, 5, 3, 3, 7), inspected(2, 2)])
    elif kind == "safe":
        result.update(passes=1, rolledBackWeldGroups=0, restoredSourceVertices=0,
                      locallyRolledBackWeldGroups=0, fallbackRolledBackWeldGroups=0,
                      retainedWeldGroups=5, passDetails=[inspected(1, 5)])
    elif kind == "all_local":
        result.update(passes=2, locallyRolledBackWeldGroups=5, fallbackRolledBackWeldGroups=0,
                      passDetails=[inspected(1, 5, 5, 5, 12), uninspected(2)])
    elif kind in {"no_groups", "empty"}:
        result = {name: 0 for name in result if name != "passDetails"}
        result.update(passes=0 if kind == "empty" else 1,
                      passDetails=[] if kind == "empty" else [uninspected(1)])
    return result


@pytest.mark.parametrize("kind", ["fallback", "partial", "safe", "all_local", "no_groups", "empty"])
def test_accepts_native_state_machine_shapes_without_mutating_input(kind):
    original = admission(kind)
    before = deepcopy(original)
    result = validate_pass_attribution(original, 3, 0 if kind == "empty" else 20)
    assert result == original == before
    assert result is not original
    assert result["passDetails"] is not original["passDetails"]
    if result["passDetails"]:
        assert result["passDetails"][0] is not original["passDetails"][0]


def test_fallback_can_discard_groups_without_individual_invalid_reasons():
    original = admission()
    second = original["passDetails"][1]
    assert second["invalidWeldGroups"] == 1 < second["rolledBackWeldGroups"] == 2
    result = validate_pass_attribution(original, 3, 20)
    assert result["fallbackRolledBackWeldGroups"] == 2


def test_overlapping_link_causes_are_not_added_as_unique_groups():
    original = admission()
    first = original["passDetails"][0]
    assert sum(first["invalidLinkReasons"].values()) > first["invalidWeldGroups"]
    validate_pass_attribution(original, 3, 20)


@pytest.mark.parametrize("bad", [True, False, -1, 100_001, 1.0, "1", None])
@pytest.mark.parametrize("name", ["proposedWeldGroups", "rolledBackWeldGroups", "restoredSourceVertices",
                                  "unresolvedSourceVertexLinks", "locallyRolledBackWeldGroups",
                                  "fallbackRolledBackWeldGroups", "retainedWeldGroups"])
def test_aggregate_counters_require_bounded_actual_integers(name, bad):
    original = admission()
    original[name] = bad
    with pytest.raises(ValueError):
        validate_pass_attribution(original, 3, 20)


@pytest.mark.parametrize("view_count", [True, 1, 9, "3", 3.0, None])
def test_view_count_is_strict(view_count):
    with pytest.raises(ValueError):
        validate_pass_attribution(admission(), view_count, 20)


@pytest.mark.parametrize("accepted", [True, -1, 100_001, "20", 20.0, None, 11])
def test_accepted_samples_are_bounded_and_cover_group_sources(accepted):
    with pytest.raises(ValueError):
        validate_pass_attribution(admission(), 3, accepted)


@pytest.mark.parametrize("name,value", [
    ("passes", True), ("passes", 4), ("passes", 2), ("passDetails", {}),
    ("rolledBackWeldGroups", 4), ("retainedWeldGroups", 1),
    ("locallyRolledBackWeldGroups", 2), ("fallbackRolledBackWeldGroups", 3),
    ("restoredSourceVertices", 9), ("restoredSourceVertices", 16),
])
def test_contradictory_aggregate_admission_is_rejected(name, value):
    original = admission()
    original[name] = value
    with pytest.raises(ValueError):
        validate_pass_attribution(original, 3, 20)


@pytest.mark.parametrize("target", ["root", "pass", "reasons"])
@pytest.mark.parametrize("change", ["missing", "extra"])
def test_schema_field_sets_are_exact(target, change):
    original = admission()
    record = (original if target == "root" else original["passDetails"][0]
              if target == "pass" else original["passDetails"][0]["invalidLinkReasons"])
    if change == "missing":
        del record[next(iter(record))]
    else:
        record["invented"] = 0
    with pytest.raises(ValueError):
        validate_pass_attribution(original, 3, 20)


@pytest.mark.parametrize("name,value", [
    ("pass", True), ("pass", 2), ("inspected", 1), ("inspected", "false"),
    ("retainedWeldGroups", 4), ("retainedWeldGroups", True), ("invalidWeldGroups", 6),
    ("rolledBackWeldGroups", 2), ("restoredSourceVertices", 5), ("restoredSourceVertices", 10),
    ("invalidLinkReasons", None),
])
def test_invalid_first_pass_types_and_accounting_are_rejected(name, value):
    original = admission()
    original["passDetails"][0][name] = value
    with pytest.raises(ValueError):
        validate_pass_attribution(original, 3, 20)


@pytest.mark.parametrize("bad", [True, -1, 4, "1", 1.0, None])
def test_link_reason_counts_are_bounded_by_unique_invalid_groups(bad):
    original = admission()
    original["passDetails"][0]["invalidLinkReasons"]["branchingLink"] = bad
    with pytest.raises(ValueError):
        validate_pass_attribution(original, 3, 20)


def test_missing_link_witnesses_cannot_explain_invalid_groups():
    original = admission()
    original["passDetails"][0]["invalidLinkReasons"] = dict.fromkeys(LINK_REASONS, 0)
    with pytest.raises(ValueError, match="recorded link reason"):
        validate_pass_attribution(original, 3, 20)


@pytest.mark.parametrize("name", ["retainedWeldGroups", "invalidWeldGroups", "invalidLinkReasons",
                                  "rolledBackWeldGroups", "restoredSourceVertices"])
def test_uninspected_counters_must_be_null_not_zero(name):
    original = admission()
    original["passDetails"][-1][name] = 0
    with pytest.raises(ValueError, match="must be null"):
        validate_pass_attribution(original, 3, 20)


def test_uninspected_pass_cannot_skip_remaining_welds():
    original = admission("partial")
    original["passDetails"][-1] = uninspected(2)
    with pytest.raises(ValueError, match="final no-weld"):
        validate_pass_attribution(original, 3, 20)


def test_no_extra_pass_after_a_conflict_free_inspection():
    original = admission("safe")
    original.update(passes=2, passDetails=[inspected(1, 5), inspected(2, 5)])
    with pytest.raises(ValueError, match="stop after success"):
        validate_pass_attribution(original, 3, 20)


def test_no_success_receipt_when_last_pass_still_has_conflicts():
    original = admission("partial")
    original.update(passes=1, passDetails=original["passDetails"][:1])
    with pytest.raises(ValueError, match="continue after rollback"):
        validate_pass_attribution(original, 3, 20)


def test_second_pass_fallback_must_reject_all_remaining_groups():
    original = admission()
    original["passDetails"][1]["rolledBackWeldGroups"] = 1
    with pytest.raises(ValueError, match="local/fallback policy"):
        validate_pass_attribution(original, 3, 20)


def test_reported_totals_cannot_disagree_with_valid_pass_records():
    original = admission()
    original["restoredSourceVertices"] = 13
    with pytest.raises(ValueError, match="pass totals"):
        validate_pass_attribution(original, 3, 20)


def test_zero_pass_receipt_cannot_hide_accepted_samples_or_source_link_defects():
    with pytest.raises(ValueError, match="explicitly empty"):
        validate_pass_attribution(admission("empty"), 3, 1)
    original = admission("empty")
    original["unresolvedSourceVertexLinks"] = 1
    with pytest.raises(ValueError, match="explicitly empty"):
        validate_pass_attribution(original, 3, 0)


def test_actual_native_schema4_record_reconciles_when_library_is_available():
    pytest.importorskip("numpy")
    from tools.native_api import NativeEngine
    from tools.qa.dense_profile_sweep import synthetic_fixture
    try:
        engine = NativeEngine()
    except (FileNotFoundError, OSError) as error:
        pytest.skip(f"Native library unavailable: {error}")
    images, cameras = synthetic_fixture()
    mesh = engine.surface(images, cameras, 3.1, 7.0, 56, 3)["mesh"]
    loss = mesh["lossDiagnostics"]
    assert loss["schema"] == 4
    record = loss["topologyAdmission"]
    assert validate_pass_attribution(record, loss["viewCount"], mesh["acceptedSamples"]) == record
