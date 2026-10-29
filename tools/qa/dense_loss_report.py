#!/usr/bin/env python3
"""Read-only photometric loss report for the bounded dense stage.

The production dense JSON already carries ``lossDiagnostics`` with a per-view
``photometric.rejectionCounts`` breakdown.  This tool reads that existing
artifact and reports *why* sampled pixels were rejected before depth
consistency, without re-running the engine, changing a threshold or inferring
object coverage.

Accounting identity enforced by the producer and re-checked here::

    accepted + invalidReferencePatch + validLowVariance + invalidOrBoundaryBest
    + ncc + uniqueness + invalidInverseDepth == evaluated

Nothing in this module is a scan-quality score.  A high rejection share is a
measurement about one fixture and one settings profile, not a claim that the
rejected pixels are unobservable in the scene.

Usage::

    python -m tools.qa.dense_loss_report --input dense.json --json-out loss.json

A full surface artifact also yields the fusion gate funnel: first-gate counts are
reported by the engine, and the number of candidates that *reached* each gate is
derived by subtraction in the engine's evaluation order.  The tool rejects an
artifact whose gate set is not exactly the emitted set or whose gate outcomes do
not sum to ``fusionCandidates``, so a renamed, added or dropped gate fails loudly
instead of producing a silently misaligned funnel.

Exit codes: 0 report produced, 1 malformed input or broken accounting,
2 diagnostics unavailable (null/empty/absent).
"""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
import sys
from typing import Any, Mapping

REASONS = (
    "invalidReferencePatch",
    "validLowVariance",
    "invalidOrBoundaryBest",
    "ncc",
    "uniqueness",
    "invalidInverseDepth",
)
ACCOUNTING_KEYS = ("acceptedSamples",) + REASONS
# Schema 2 adds an additive subreason breakdown; schema 1 artifacts stay readable.
SUPPORTED_SCHEMAS = (1, 2)
BOUNDARY_REASONS = ("ambiguousMinimum", "nearBoundaryBest", "farBoundaryBest")
HISTOGRAM_BINS = 16
MAX_VIEWS = 8
MAX_SAMPLES = 100000

# Surface fusion gate attribution (step 4).  Each candidate visit records exactly
# one first-failure gate, so the *reached* count per gate is derivable by
# subtraction.  The order below is the engine's evaluation order in
# ``reconstruction/src/surface.cpp``; it must match that code, not an alphabetical
# listing, or every derived count after the first out-of-order gate is wrong.
SURFACE_SCOPE = "ORIGINATING_SURFACE_EVALUATION"
SUPPORTED_SURFACE_SCHEMAS = (3,)
MAX_CANDIDATES = 10_000_000
SURFACE_GATES = (
    "sameCamera",
    "missingNormal",
    "normalMismatch",
    "notCloser",
    "outsideRadius",
    "currentVisibility",
    "proposedAnchorMetric",
    "proposedVisibility",
    "pairMetric",
    "anchorSeesSample",
    "sampleSeesAnchor",
    "memberAnchorMetric",
    "memberSeesProposed",
)
SURFACE_ACCEPTED = "acceptedCandidateVisits"
MISSING_NORMAL_REASONS = ("sourceNormal", "accumulatorNormal", "bothNormals")

# Pre-declared interpretations.  These describe an indicated investigation
# direction for a measured rejection profile; they never authorize a threshold
# change or a quality claim on their own.
INDICATED = {
    "validLowVariance": (
        "Patch contrast below the variance floor. Expect a capture/preprocessing gap "
        "(local contrast, textureless subjects) rather than a matcher defect."
    ),
    "invalidOrBoundaryBest": (
        "Best plane sits at the search boundary or the cost has no distinct minimum. "
        "Expect a depth-range/prior/spacing question, not an NCC question."
    ),
    "ncc": (
        "Correlation never reached the acceptance cost. Expect exposure, rectification "
        "or fronto-parallel-warp mismatch."
    ),
    "uniqueness": (
        "Correlated but ambiguous minimum. Expect repetitive texture or too little "
        "parallax between the available views."
    ),
    "invalidReferencePatch": (
        "Reference patch invalid (mask boundary or unsupported pixels). Expect an "
        "edge/validity bookkeeping question."
    ),
    "invalidInverseDepth": (
        "Selected inverse depth was degenerate after refinement. Expect a local "
        "refinement guard question."
    ),
}
DECISIVE_SHARE = 0.40
LEADING_SHARE = 0.25


class MalformedDiagnostics(ValueError):
    """The artifact is not a dense loss diagnostic or its accounting is broken."""


class UnavailableDiagnostics(ValueError):
    """The artifact reports that originating diagnostics were not recorded."""


@dataclass(frozen=True)
class ViewRow:
    view: int
    sampled: int
    evaluated: int
    accepted: int
    rejections: Mapping[str, int]
    boundary: Mapping[str, int] | None = None

    @property
    def rejected(self) -> int:
        return self.evaluated - self.accepted


@dataclass(frozen=True)
class Report:
    views: tuple[ViewRow, ...]
    totals: Mapping[str, int]
    evaluated: int
    accepted: int

    @property
    def rejected(self) -> int:
        return self.evaluated - self.accepted

    def shares(self) -> dict[str, float]:
        return {
            reason: (count / self.rejected if self.rejected else 0.0)
            for reason, count in self.totals.items()
        }

    def dominant(self) -> tuple[str | None, float]:
        if not self.rejected:
            return None, 0.0
        reason, count = max(self.totals.items(), key=lambda item: (item[1], item[0]))
        return (reason if count else None), count / self.rejected


@dataclass(frozen=True)
class FunnelStep:
    """One surface gate in evaluation order; ``reached`` is derived, not reported."""

    gate: str
    reached: int
    failed: int

    @property
    def passed(self) -> int:
        return self.reached - self.failed

    @property
    def failure_share(self) -> float:
        return self.failed / self.reached if self.reached else 0.0


@dataclass(frozen=True)
class SurfaceViewRow:
    camera: int
    candidates: int
    accepted: int
    gates: Mapping[str, int]
    missing_normal: Mapping[str, int]


@dataclass(frozen=True)
class SurfaceReport:
    views: tuple[SurfaceViewRow, ...]
    candidates: int
    accepted: int
    gates: Mapping[str, int]
    missing_normal: Mapping[str, int]

    def funnel(self) -> tuple[FunnelStep, ...]:
        return funnel_steps(self.candidates, self.gates)


def funnel_steps(candidates: int, gates: Mapping[str, int]) -> tuple[FunnelStep, ...]:
    """Derive how many candidates reached each gate from first-gate counts alone.

    ``reached(g) = candidates - sum(failures of gates before g)`` holds only
    because the producer records one first-failure gate per visit and applies the
    gates in short-circuit order.  The surviving remainder always equals the
    reported accepted count once gate outcomes reconcile with ``candidates``;
    that identity is enforced when parsing, so it is reported here rather than
    re-checked as if it were independent evidence.
    """
    steps: list[FunnelStep] = []
    reached = candidates
    for gate in SURFACE_GATES:
        steps.append(FunnelStep(gate, reached, gates[gate]))
        reached -= gates[gate]
    return tuple(steps)


def _diagnostics(document: Any) -> Mapping[str, Any]:
    if not isinstance(document, Mapping):
        raise MalformedDiagnostics("Root JSON value must be an object")
    if "lossDiagnostics" in document:
        candidate = document["lossDiagnostics"]
    elif "dense" in document and isinstance(document["dense"], Mapping):
        candidate = document["dense"].get("lossDiagnostics")
    else:
        candidate = document
    if not isinstance(candidate, Mapping):
        raise UnavailableDiagnostics("No lossDiagnostics object in this artifact")
    # A surface-only artifact reaches here as the whole document; absence of a
    # dense section is unavailable diagnostics, not a malformed one.
    if candidate is document and "schema" not in candidate:
        raise UnavailableDiagnostics("No dense lossDiagnostics object in this artifact")
    if candidate.get("schema") not in SUPPORTED_SCHEMAS:
        raise MalformedDiagnostics(
            f"Unsupported lossDiagnostics schema: {candidate.get('schema')!r}"
        )
    if not candidate.get("available"):
        raise UnavailableDiagnostics("lossDiagnostics reports available=false")
    if candidate.get("scope") != "ORIGINATING_DENSE_BRANCHES":
        raise MalformedDiagnostics(
            f"Unexpected diagnostics scope: {candidate.get('scope')!r}"
        )
    return candidate


def _count(value: Any, name: str, maximum: int = MAX_SAMPLES) -> int:
    if type(value) is not int or value < 0 or value > maximum:
        raise MalformedDiagnostics(f"{name} is not a bounded non-negative integer")
    return value


def _view_row(index: int, view: Any) -> ViewRow:
    if not isinstance(view, Mapping):
        raise MalformedDiagnostics(f"View {index} is not an object")
    if view.get("referenceView") != index:
        raise MalformedDiagnostics(f"View {index} has inconsistent referenceView")
    photometric = view.get("photometric")
    if not isinstance(photometric, Mapping):
        raise MalformedDiagnostics(f"View {index} has no photometric section")
    sampled = _count(photometric.get("sampledSamples"), "sampledSamples")
    if not photometric.get("evaluatedSamples"):
        raise UnavailableDiagnostics(f"View {index} has no evaluated samples")
    evaluated = _count(photometric.get("evaluatedSamples"), "evaluatedSamples")
    accepted = _count(photometric.get("acceptedSamples"), "acceptedSamples")
    counts = photometric.get("rejectionCounts")
    required = set(REASONS)
    optional = required | {"invalidOrBoundaryBestReasons"}
    if not isinstance(counts, Mapping) or set(counts) not in (required, optional):
        raise MalformedDiagnostics(f"View {index} has an unexpected rejectionCounts set")
    rejections = {reason: _count(counts[reason], reason) for reason in REASONS}
    boundary = None
    if "invalidOrBoundaryBestReasons" in counts:
        raw = counts["invalidOrBoundaryBestReasons"]
        if not isinstance(raw, Mapping) or set(raw) != set(BOUNDARY_REASONS):
            raise MalformedDiagnostics(
                f"View {index} has an unexpected invalidOrBoundaryBestReasons set"
            )
        boundary = {name: _count(raw[name], name) for name in BOUNDARY_REASONS}
        if sum(boundary.values()) != rejections["invalidOrBoundaryBest"]:
            raise MalformedDiagnostics(
                f"View {index} boundary subreasons do not reconcile"
            )
    total = accepted + sum(rejections.values())
    if total != evaluated:
        raise MalformedDiagnostics(
            f"View {index} accounting mismatch: {total} != {evaluated}"
        )
    histogram = photometric.get("validReferenceMeanHistogram")
    low_histogram = photometric.get("validLowVarianceMeanHistogram")
    if not isinstance(histogram, list) or len(histogram) != HISTOGRAM_BINS:
        raise MalformedDiagnostics(f"View {index} has no bounded reference histogram")
    if not isinstance(low_histogram, list) or len(low_histogram) != HISTOGRAM_BINS:
        raise MalformedDiagnostics(f"View {index} has no bounded low-variance histogram")
    if sum(histogram) != evaluated - rejections["invalidReferencePatch"]:
        raise MalformedDiagnostics(f"View {index} reference histogram does not reconcile")
    if sum(low_histogram) != rejections["validLowVariance"]:
        raise MalformedDiagnostics(f"View {index} low-variance histogram does not reconcile")
    return ViewRow(index, sampled, evaluated, accepted, rejections, boundary)


def build_report(document: Any) -> Report:
    diagnostics = _diagnostics(document)
    views = diagnostics.get("views")
    if not isinstance(views, list) or not 2 <= len(views) <= MAX_VIEWS:
        raise MalformedDiagnostics("Expected 2 to 8 reference views")
    if diagnostics.get("viewCount") != len(views):
        raise MalformedDiagnostics("viewCount does not match the view list")
    rows = tuple(_view_row(index, view) for index, view in enumerate(views))
    totals = {reason: sum(row.rejections[reason] for row in rows) for reason in REASONS}
    return Report(
        views=rows,
        totals=totals,
        evaluated=sum(row.evaluated for row in rows),
        accepted=sum(row.accepted for row in rows),
    )


def boundary_split(report: Report) -> dict[str, int]:
    return {
        name: sum(row.boundary[name] for row in report.views if row.boundary)
        for name in BOUNDARY_REASONS
    }


def _surface_diagnostics(document: Any) -> Mapping[str, Any] | None:
    """Return surface loss diagnostics if this artifact carries them."""
    if not isinstance(document, Mapping):
        return None
    mesh = document.get("mesh")
    if isinstance(mesh, Mapping):
        candidate = mesh.get("lossDiagnostics")
    elif document.get("scope") == SURFACE_SCOPE:
        candidate = document
    else:
        candidate = None
    if candidate is None:
        return None
    if not isinstance(candidate, Mapping):
        raise MalformedDiagnostics("mesh.lossDiagnostics is not an object")
    if candidate.get("schema") not in SUPPORTED_SURFACE_SCHEMAS:
        raise MalformedDiagnostics(
            f"Unsupported surface lossDiagnostics schema: {candidate.get('schema')!r}"
        )
    if candidate.get("scope") != SURFACE_SCOPE:
        raise MalformedDiagnostics(
            f"Unexpected surface diagnostics scope: {candidate.get('scope')!r}"
        )
    if not candidate.get("available"):
        raise UnavailableDiagnostics("Surface lossDiagnostics reports available=false")
    return candidate


def _surface_view(index: int, view: Any) -> SurfaceViewRow:
    if not isinstance(view, Mapping):
        raise MalformedDiagnostics(f"Surface view {index} is not an object")
    if view.get("camera") != index:
        raise MalformedDiagnostics(f"Surface view {index} has inconsistent camera")
    candidates = _count(view.get("fusionCandidates"), "fusionCandidates", MAX_CANDIDATES)
    gates = view.get("fusionCandidateFirstGate")
    expected = set(SURFACE_GATES) | {SURFACE_ACCEPTED}
    if not isinstance(gates, Mapping) or set(gates) != expected:
        raise MalformedDiagnostics(f"Surface view {index} has an unexpected fusion gate set")
    counts = {
        gate: _count(gates[gate], gate, MAX_CANDIDATES)
        for gate in (*SURFACE_GATES, SURFACE_ACCEPTED)
    }
    if sum(counts.values()) != candidates:
        raise MalformedDiagnostics(
            f"Surface view {index} gate outcomes do not sum to fusionCandidates"
        )
    missing = view.get("missingNormalReasons")
    if not isinstance(missing, Mapping) or set(missing) != set(MISSING_NORMAL_REASONS):
        raise MalformedDiagnostics(
            f"Surface view {index} has an unexpected missingNormalReasons set"
        )
    reasons = {
        name: _count(missing[name], name, MAX_CANDIDATES)
        for name in MISSING_NORMAL_REASONS
    }
    if sum(reasons.values()) != counts["missingNormal"]:
        raise MalformedDiagnostics(
            f"Surface view {index} missing-normal reasons do not reconcile"
        )
    return SurfaceViewRow(index, candidates, counts[SURFACE_ACCEPTED], counts, reasons)


def build_surface_report(document: Any) -> SurfaceReport | None:
    """Build the gate funnel; ``None`` when the artifact carries no surface section."""
    diagnostics = _surface_diagnostics(document)
    if diagnostics is None:
        return None
    views = diagnostics.get("views")
    if not isinstance(views, list) or not 2 <= len(views) <= MAX_VIEWS:
        raise MalformedDiagnostics("Expected 2 to 8 surface views")
    if diagnostics.get("viewCount") != len(views):
        raise MalformedDiagnostics("Surface viewCount does not match the view list")
    rows = tuple(_surface_view(index, view) for index, view in enumerate(views))
    gates = {
        gate: sum(row.gates[gate] for row in rows)
        for gate in (*SURFACE_GATES, SURFACE_ACCEPTED)
    }
    report = SurfaceReport(
        views=rows,
        candidates=sum(row.candidates for row in rows),
        accepted=gates[SURFACE_ACCEPTED],
        gates=gates,
        missing_normal={
            name: sum(row.missing_normal[name] for row in rows)
            for name in MISSING_NORMAL_REASONS
        },
    )
    report.funnel()  # the aggregate must reconcile, not only each view
    return report


def render_surface_markdown(report: SurfaceReport, label: str | None = None) -> str:
    header = "Surface fusion gate funnel" + (f" - {label}" if label else "")
    lines = [f"## {header}", ""]
    columns = ["gate", "failed", "reached", "failureOfReached", "shareOfVisits"]
    lines.append("| " + " | ".join(columns) + " |")
    lines.append("|" + "---|" * len(columns))
    for step in report.funnel():
        lines.append(
            "| "
            + " | ".join(
                (
                    step.gate,
                    str(step.failed),
                    str(step.reached),
                    f"{step.failure_share:.1%}" if step.reached else "n/a",
                    f"{step.failed / report.candidates:.1%}" if report.candidates else "n/a",
                )
            )
            + " |"
        )
    acceptance = (
        f"Accepted visits: **{report.accepted}** of {report.candidates} "
        f"({report.accepted / report.candidates:.2%})."
        if report.candidates
        else "No fusion candidate visits were recorded."
    )
    lines += ["", acceptance]
    reasons = report.missing_normal
    total = sum(reasons.values())
    if total:
        lines.append(
            "Missing-normal attribution: "
            + ", ".join(
                f"{name} {reasons[name]} ({reasons[name] / total:.1%})"
                for name in MISSING_NORMAL_REASONS
            )
            + "."
        )
    lines += [
        "",
        "Accepted visits are admissible candidates, not retained fusions: topology "
        "admission can roll a weld group back afterwards. Read retained fusion from "
        "mesh.fusedObservations.",
    ]
    return "\n".join(lines)


def surface_receipt(report: SurfaceReport) -> dict[str, Any]:
    return {
        "scope": SURFACE_SCOPE,
        "candidates": report.candidates,
        "acceptedCandidateVisits": report.accepted,
        "firstGateCounts": dict(report.gates),
        "funnel": [
            {
                "gate": step.gate,
                "reached": step.reached,
                "failed": step.failed,
                "passed": step.passed,
            }
            for step in report.funnel()
        ],
        "missingNormalReasons": dict(report.missing_normal),
        "retention": "candidate visits only; retained fusions are mesh.fusedObservations",
    }


def _refine_boundary(report: Report, indicated: str) -> str:
    """Sharpen the boundary direction only when schema-2 subreasons are present."""
    split = boundary_split(report)
    total = sum(split.values())
    if not total:
        return indicated
    top = max(split, key=lambda name: (split[name], name))
    if split[top] / total < 0.60:
        return indicated
    return {
        "ambiguousMinimum": (
            "No distinct cost minimum dominates. Expect plane spacing, patch size or "
            "parallax limits rather than a depth-range edge."
        ),
        "nearBoundaryBest": (
            "Best plane at the near edge dominates. Expect the search interval starting "
            "too far from the object."
        ),
        "farBoundaryBest": (
            "Best plane at the far edge dominates. Expect the search interval ending "
            "too near the object."
        ),
    }[top]


def classify(report: Report) -> tuple[str, str]:
    reason, share = report.dominant()
    if reason is None:
        return "NO_REJECTIONS", "Every evaluated sample was accepted; nothing to rank."
    if share >= DECISIVE_SHARE:
        band = "DECISIVE"
    elif share >= LEADING_SHARE:
        band = "LEADING_BUT_MIXED"
    else:
        band = "NO_SINGLE_CAUSE"
    indicated = INDICATED[reason]
    if reason == "invalidOrBoundaryBest":
        indicated = _refine_boundary(report, indicated)
    return band, indicated


def render_markdown(report: Report, label: str | None = None) -> str:
    reason, share = report.dominant()
    band, indicated = classify(report)
    header = "Photometric depth loss" + (f" - {label}" if label else "")
    lines = [f"## {header}", ""]
    columns = ["view", "evaluated", "accepted", "rejected"] + list(REASONS)
    lines.append("| " + " | ".join(columns) + " |")
    lines.append("|" + "---|" * len(columns))
    for row in report.views:
        cells = [str(row.view), str(row.evaluated), str(row.accepted), str(row.rejected)]
        cells += [str(row.rejections[reason]) for reason in REASONS]
        lines.append("| " + " | ".join(cells) + " |")
    totals = ["total", str(report.evaluated), str(report.accepted), str(report.rejected)]
    totals += [str(report.totals[reason]) for reason in REASONS]
    lines.append("| " + " | ".join(totals) + " |")
    lines += ["", f"Rejections: **{report.rejected}** of {report.evaluated} evaluated."]
    split = boundary_split(report)
    if any(split.values()):
        lines.append(
            "Boundary/non-distinct split: "
            + ", ".join(f"{name} {split[name]}" for name in BOUNDARY_REASONS)
            + "."
        )
    if reason is not None:
        lines.append(f"Dominant reason: **{reason}** at {share:.1%} ({band}).")
        lines.append(f"Indicated direction: {indicated}")
    else:
        lines.append("No rejections were recorded.")
    lines += [
        "",
        "Descriptive measurement of one fixture and settings profile. Not a quality "
        "score, not a coverage estimate and not authorization to change a gate.",
    ]
    return "\n".join(lines)


def receipt(
    report: Report | None,
    label: str | None = None,
    surface: SurfaceReport | None = None,
) -> dict[str, Any]:
    carried: dict[str, Any] = {
        "schema": 2,
        "tool": "dense_loss_report",
        "label": label,
        "classification": "DESCRIPTIVE_PHOTOMETRIC_LOSS_NOT_QUALITY_SCORE",
        "surface": surface_receipt(surface) if surface is not None else None,
        "limitations": [
            "Fixture and settings specific",
            "Not object coverage, accuracy or watertightness",
            "No threshold or gate changed by this report",
            "Gate funnel is derived from first-gate counts; it is not a retained-fusion count",
        ],
    }
    if report is None:
        carried.update({"views": None, "totals": None, "dominant": None})
        return carried
    reason, share = report.dominant()
    band, _ = classify(report)
    carried.update(
        {
            "views": [
                {
                    "view": row.view,
                    "sampled": row.sampled,
                    "evaluated": row.evaluated,
                    "accepted": row.accepted,
                    "rejected": row.rejected,
                    "rejectionCounts": dict(row.rejections),
                    "invalidOrBoundaryBestReasons": (
                        dict(row.boundary) if row.boundary else None
                    ),
                }
                for row in report.views
            ],
            "totals": {
                "evaluated": report.evaluated,
                "accepted": report.accepted,
                "rejected": report.rejected,
                "rejectionCounts": dict(report.totals),
            },
            "dominant": {"reason": reason, "share": round(share, 6), "band": band},
        }
    )
    return carried


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", required=True, type=Path, help="Dense or surface JSON artifact")
    parser.add_argument("--json-out", type=Path, help="Optional machine-readable receipt")
    parser.add_argument("--label", help="Fixture or profile label for the report")
    arguments = parser.parse_args(argv)
    try:
        document = json.loads(arguments.input.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        print(f"INVALID: {failure}", file=sys.stderr)
        return 1
    try:
        dense = build_report(document)
    except UnavailableDiagnostics:
        dense = None
    except MalformedDiagnostics as failure:
        print(f"INVALID: {failure}", file=sys.stderr)
        return 1
    try:
        surface = build_surface_report(document)
    except UnavailableDiagnostics:
        surface = None
    except MalformedDiagnostics as failure:
        print(f"INVALID: {failure}", file=sys.stderr)
        return 1
    if dense is None and surface is None:
        print(
            "UNAVAILABLE: no originating dense or surface loss diagnostics in this artifact",
            file=sys.stderr,
        )
        return 2
    if dense is not None:
        print(render_markdown(dense, arguments.label))
    if surface is not None:
        print(render_surface_markdown(surface, arguments.label))
    if arguments.json_out:
        arguments.json_out.write_text(
            json.dumps(receipt(dense, arguments.label, surface), indent=2, sort_keys=True)
            + "\n",
            encoding="utf-8",
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
