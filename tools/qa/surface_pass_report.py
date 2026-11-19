"""Validate schema-4 surface topology admission without inferring missing evidence.

The native producer may triangulate at most three times: local rejection,
conservative fallback, and the final no-weld triangulation.  An uninspected
pass carries null counters, not measured zeros.  Link-reason counts overlap.
This module validates that state machine; it makes no geometry-quality claim.
"""
from __future__ import annotations

from collections.abc import Mapping
from typing import Any

MAX_SAMPLES = 100_000
MAX_VIEWS = 8
LINK_REASONS = (
    "branchingLink",
    "directionConflict",
    "disconnectedLink",
    "invalidEndpointCount",
)
AGGREGATE_COUNTER_KEYS = (
    "proposedWeldGroups", "rolledBackWeldGroups",
    "restoredSourceVertices", "unresolvedSourceVertexLinks",
    "locallyRolledBackWeldGroups", "fallbackRolledBackWeldGroups",
    "retainedWeldGroups",
)
AGGREGATE_KEYS = frozenset({"passes", "passDetails", *AGGREGATE_COUNTER_KEYS})
PASS_COUNTER_KEYS = (
    "retainedWeldGroups", "invalidWeldGroups", "invalidLinkReasons",
    "rolledBackWeldGroups", "restoredSourceVertices",
)
PASS_KEYS = frozenset({"pass", "inspected", *PASS_COUNTER_KEYS})


def _count(value: Any, name: str, maximum: int = MAX_SAMPLES) -> int:
    if type(value) is not int or not 0 <= value <= maximum:
        raise ValueError(f"{name} is not a bounded non-negative integer")
    return value


def validate_pass_attribution(
    admission: Mapping[str, Any], view_count: int, accepted_samples: int,
) -> dict[str, Any]:
    """Return a detached validated schema-4 record, or raise ``ValueError``.

    The caller validates the enclosing originating surface artifact and its
    sample/fusion totals.  In particular, the zero-pass early return also needs
    explicitly empty *input* samples, which are not arguments to this function.
    Older schemas must remain unknown at the caller, not be passed as schema 4.
    """
    if type(view_count) is not int or not 2 <= view_count <= MAX_VIEWS:
        raise ValueError("Surface topology attribution requires 2 to 8 views")
    accepted = _count(accepted_samples, "acceptedSamples")
    if not isinstance(admission, Mapping) or set(admission) != AGGREGATE_KEYS:
        raise ValueError("Unexpected schema-4 topologyAdmission field set")
    passes = _count(admission["passes"], "topologyAdmission.passes", 3)
    counts = {
        name: _count(admission[name], f"topologyAdmission.{name}")
        for name in AGGREGATE_COUNTER_KEYS
    }
    details = admission["passDetails"]
    if not isinstance(details, list) or len(details) != passes:
        raise ValueError("Topology passDetails length does not match passes")

    proposed = counts["proposedWeldGroups"]
    rolled_back = counts["rolledBackWeldGroups"]
    restored = counts["restoredSourceVertices"]
    local = counts["locallyRolledBackWeldGroups"]
    fallback = counts["fallbackRolledBackWeldGroups"]
    retained = counts["retainedWeldGroups"]
    if rolled_back > proposed or retained != proposed - rolled_back:
        raise ValueError("Retained and rolled-back groups do not reconcile")
    if local + fallback != rolled_back:
        raise ValueError("Local and fallback rollback do not reconcile")
    if not 2 * rolled_back <= restored <= view_count * rolled_back:
        raise ValueError("Restored source vertices do not reconcile with rollback")
    if restored + 2 * retained > accepted:
        raise ValueError("Topology weld groups exceed accepted source samples")
    if passes == 0 and (accepted or any(counts.values())):
        raise ValueError("Zero topology passes require an explicitly empty result")

    normalized: list[dict[str, Any]] = []
    remaining = proposed
    total_rollback = total_restored = local_rollback = fallback_rollback = 0
    for index, raw in enumerate(details):
        number = index + 1
        if not isinstance(raw, Mapping) or set(raw) != PASS_KEYS:
            raise ValueError(f"Topology pass {number} has an unexpected field set")
        if _count(raw["pass"], "topology pass identity", 3) != number:
            raise ValueError("Topology pass identities must be sequential")
        if type(raw["inspected"]) is not bool:
            raise ValueError(f"Topology pass {number} inspected is not a boolean")
        inspected = raw["inspected"]
        final = number == passes
        if not inspected:
            if remaining != 0 or not final:
                raise ValueError("Only the final no-weld pass may be uninspected")
            if any(raw[name] is not None for name in PASS_COUNTER_KEYS):
                raise ValueError("Uninspected topology pass counters must be null")
            normalized.append({"pass": number, "inspected": False,
                               **dict.fromkeys(PASS_COUNTER_KEYS)})
            continue

        if remaining == 0:
            raise ValueError("A topology inspector cannot run without retained welds")
        current = _count(raw["retainedWeldGroups"], "pass retainedWeldGroups")
        invalid = _count(raw["invalidWeldGroups"], "pass invalidWeldGroups")
        newly_rolled_back = _count(raw["rolledBackWeldGroups"], "pass rolledBackWeldGroups")
        newly_restored = _count(raw["restoredSourceVertices"], "pass restoredSourceVertices")
        if current != remaining or invalid > current:
            raise ValueError("Topology pass group counts do not reconcile with prior passes")
        reasons = raw["invalidLinkReasons"]
        if not isinstance(reasons, Mapping) or set(reasons) != set(LINK_REASONS):
            raise ValueError("Topology pass has an unexpected link-reason set")
        reasons = {name: _count(reasons[name], name, invalid) for name in LINK_REASONS}
        if sum(reasons.values()) < invalid:
            raise ValueError("Every invalid weld group requires a recorded link reason")

        expected_rollback = invalid if index == 0 else (current if invalid else 0)
        if index == 2 and invalid:
            raise ValueError("A completed third topology pass cannot retain an invalid weld")
        if newly_rolled_back != expected_rollback:
            raise ValueError("Topology pass rollback does not follow the local/fallback policy")
        if not 2 * newly_rolled_back <= newly_restored <= view_count * newly_rolled_back:
            raise ValueError("Topology pass restored samples do not reconcile with rollback")
        if (invalid == 0) != final:
            raise ValueError("Topology passes must stop after success and continue after rollback")

        total_rollback += newly_rolled_back
        total_restored += newly_restored
        if index == 0:
            local_rollback += newly_rolled_back
        else:
            fallback_rollback += newly_rolled_back
        remaining -= newly_rolled_back
        normalized.append({
            "pass": number, "inspected": True,
            "retainedWeldGroups": current, "invalidWeldGroups": invalid,
            "invalidLinkReasons": reasons, "rolledBackWeldGroups": newly_rolled_back,
            "restoredSourceVertices": newly_restored,
        })

    if (total_rollback, total_restored, local_rollback, fallback_rollback, remaining) != (
        rolled_back, restored, local, fallback, retained,
    ):
        raise ValueError("Topology pass totals do not reconcile with aggregate admission")
    return {"passes": passes, **counts, "passDetails": normalized}
