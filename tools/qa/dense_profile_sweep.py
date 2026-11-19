#!/usr/bin/env python3
"""Bounded depth-profile sweep against a labelled synthetic fixture.

Purpose: measure how depth-plane count and sampling stride affect the observed
``invalidOrBoundaryBest`` loss on this fixed synthetic fixture. The sweep can
test a simple more-planes hypothesis; it cannot establish a general cause of
missing evidence. Every profile runs the actual bounded native surface stage.

Promotion rule (pre-declared, never adjusted after seeing results)::

    PROMOTE  supported normals increase AND fusions increase
             AND boundary/nonmanifold edges and intersections do not increase
             AND both intersection checks completed
    REJECT   a measured topology regression, even without a gain
    INCONCLUSIVE  incomplete evidence or no gain on both improvement axes

A promoted profile is still synthetic-only evidence.  This tool never edits a
production default, admission limit or cache identity.

Usage::

    python -m tools.qa.dense_profile_sweep --json-out sweep.json

Exit codes: 0 sweep completed, 1 malformed input, 2 native library or required
originating diagnostics unavailable. Completion does not mean promotion.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, asdict
import json
from pathlib import Path
import sys
from typing import Any, Iterable, Mapping

from tools.qa import dense_loss_report as loss

MIN_PLANES, MAX_PLANES = 12, 128
MIN_STRIDE, MAX_STRIDE = 2, 8
DEFAULT_PROFILES = ("40x3", "56x3", "72x3", "96x3", "56x2", "72x2")
BASELINE_PROFILE = "56x3"
# Bounds of the current surface C-ABI and its default topology analysis. These
# are reader checks, not changes to reconstruction admission or resource limits.
MAX_VERTICES, MAX_TRIANGLES = 40_000, 80_000
MAX_INTERSECTIONS = 2_000_000
MAX_TOTAL_SAMPLES = loss.MAX_SAMPLES * loss.MAX_VIEWS
NORMAL_REASONS = ("supported", "sparseSupport", "discontinuity", "illConditioned", "invalid")


class InvalidProfile(ValueError):
    """A profile string is not a bounded planes x stride pair."""


class NativeUnavailable(RuntimeError):
    """The native binding or its library cannot be loaded."""


@dataclass(frozen=True)
class Profile:
    planes: int
    stride: int

    def __post_init__(self) -> None:
        if type(self.planes) is not int or not MIN_PLANES <= self.planes <= MAX_PLANES:
            raise InvalidProfile(f"planes must be an integer in {MIN_PLANES}..{MAX_PLANES}")
        if type(self.stride) is not int or not MIN_STRIDE <= self.stride <= MAX_STRIDE:
            raise InvalidProfile(f"stride must be an integer in {MIN_STRIDE}..{MAX_STRIDE}")

    @property
    def name(self) -> str:
        return f"{self.planes}x{self.stride}"


@dataclass(frozen=True)
class Metrics:
    profile: str
    evaluated: int
    accepted: int
    rejected: int
    ambiguous_minimum: int
    boundary_edge: int
    ncc: int
    supported_normals: int
    fusion_candidates: int
    fused_observations: int
    vertex_count: int
    triangle_count: int
    boundary_edges: int
    intersections: int
    nonmanifold_edges: int
    intersection_budget_exceeded: bool

    def __post_init__(self) -> None:
        if parse_profile(self.profile).name != self.profile:
            raise loss.MalformedDiagnostics("Metric profile name must be canonical")
        for name in (
            "evaluated", "accepted", "rejected", "ambiguous_minimum", "boundary_edge",
            "ncc", "supported_normals", "fused_observations",
        ):
            _count(getattr(self, name), name, MAX_TOTAL_SAMPLES)
        _count(self.fusion_candidates, "fusion_candidates", loss.MAX_CANDIDATES * loss.MAX_VIEWS)
        _count(self.vertex_count, "vertex_count", MAX_VERTICES)
        _count(self.triangle_count, "triangle_count", MAX_TRIANGLES)
        _count(self.boundary_edges, "boundary_edges", 3 * self.triangle_count)
        _count(self.nonmanifold_edges, "nonmanifold_edges", 3 * self.triangle_count)
        _count(self.intersections, "intersections", min(
            MAX_INTERSECTIONS, self.triangle_count * (self.triangle_count - 1) // 2
        ))
        _boolean(self.intersection_budget_exceeded, "intersection_budget_exceeded")
        if self.accepted + self.rejected != self.evaluated:
            raise loss.MalformedDiagnostics("Metric accepted/rejected counts do not reconcile")
        if self.ambiguous_minimum + self.boundary_edge + self.ncc > self.rejected:
            raise loss.MalformedDiagnostics("Metric rejection subcounts exceed rejected samples")
        if self.supported_normals > self.accepted:
            raise loss.MalformedDiagnostics("Supported normals exceed photometric accepted samples")
        if self.fused_observations > min(self.supported_normals, self.fusion_candidates):
            raise loss.MalformedDiagnostics("Retained fusions exceed supporting observations or visits")


def parse_profile(text: str) -> Profile:
    if not isinstance(text, str):
        raise InvalidProfile("Profile must be a PLANESxSTRIDE string")
    parts = text.lower().replace(" ", "").split("x")
    if len(parts) != 2 or not all(part.isascii() and part.isdigit() for part in parts):
        raise InvalidProfile(f"Expected PLANESxSTRIDE, got {text!r}")
    # Avoid parsing unbounded integer strings supplied through the CLI.
    if any(len(part) > 3 for part in parts):
        raise InvalidProfile("Profile values exceed the bounded format")
    planes, stride = (int(part) for part in parts)
    if not MIN_PLANES <= planes <= MAX_PLANES:
        raise InvalidProfile(f"planes must be {MIN_PLANES}..{MAX_PLANES}")
    if not MIN_STRIDE <= stride <= MAX_STRIDE:
        raise InvalidProfile(f"stride must be {MIN_STRIDE}..{MAX_STRIDE}")
    return Profile(planes, stride)


def _count(value: Any, name: str, maximum: int = MAX_TOTAL_SAMPLES) -> int:
    if type(value) is not int or not 0 <= value <= maximum:
        raise loss.MalformedDiagnostics(f"{name} is not a bounded non-negative integer")
    return value


def _boolean(value: Any, name: str) -> bool:
    if type(value) is not bool:
        raise loss.MalformedDiagnostics(f"{name} is not a boolean")
    return value


def metrics_from_result(profile: Profile, result: Any) -> Metrics:
    built = loss.build_report(result)
    mesh = result.get("mesh")
    if not isinstance(mesh, Mapping):
        raise loss.MalformedDiagnostics(
            f"Profile {profile.name} produced no mesh section to measure"
        )
    surface = loss.build_surface_report(result)
    if surface is None:
        raise loss.UnavailableDiagnostics(f"Profile {profile.name} has no originating surface diagnostics")
    views = mesh["lossDiagnostics"]["views"]
    if len(views) != len(built.views):
        raise loss.MalformedDiagnostics("Dense and surface view counts disagree")
    supported = 0
    for index, view in enumerate(views):
        validity = view.get("normalValidity")
        if not isinstance(validity, Mapping) or set(validity) != set(NORMAL_REASONS):
            raise loss.MalformedDiagnostics(f"Surface view {index} has an unexpected normalValidity set")
        counts = {name: _count(validity[name], name, loss.MAX_SAMPLES) for name in NORMAL_REASONS}
        samples = _count(view.get("inputSamples"), "inputSamples", loss.MAX_SAMPLES)
        if sum(counts.values()) != samples or samples > built.views[index].accepted:
            raise loss.MalformedDiagnostics(f"Surface view {index} normal/sample counts do not reconcile")
        supported += counts["supported"]
    if any(view.boundary is None for view in built.views):
        raise loss.UnavailableDiagnostics("Profile sweep requires the photometric boundary subreasons")
    boundary = sum(
        view.boundary[name]
        for view in built.views
        for name in ("nearBoundaryBest", "farBoundaryBest")
    )
    return Metrics(
        profile=profile.name,
        evaluated=built.evaluated,
        accepted=built.accepted,
        rejected=built.rejected,
        ambiguous_minimum=sum(
            view.boundary["ambiguousMinimum"]
            for view in built.views
            if view.boundary
        ),
        boundary_edge=boundary,
        ncc=built.totals["ncc"],
        supported_normals=supported,
        fusion_candidates=surface.candidates,
        fused_observations=mesh.get("fusedObservations"),
        vertex_count=mesh.get("vertexCount"),
        triangle_count=mesh.get("triangleCount"),
        boundary_edges=mesh.get("boundaryEdges"),
        intersections=mesh.get("intersections"),
        nonmanifold_edges=mesh.get("nonmanifoldEdges"),
        intersection_budget_exceeded=mesh.get("topologyIncomplete"),
    )


def _decision(baseline: Metrics, candidate: Metrics) -> tuple[str, tuple[str, ...]]:
    """Apply the pre-declared promotion rule; no post-hoc tolerance."""
    if candidate.profile == baseline.profile:
        if candidate != baseline:
            raise loss.MalformedDiagnostics("Conflicting measurements for the baseline profile")
        return "BASELINE", ("baseline_profile",)
    regressions = []
    if candidate.boundary_edges > baseline.boundary_edges:
        regressions.append("boundary_edges_increased")
    if candidate.nonmanifold_edges > baseline.nonmanifold_edges:
        regressions.append("nonmanifold_edges_increased")
    # An unfinished check yields a lower bound. It can prove a regression only
    # against a complete baseline, never against another unknown lower bound.
    if not baseline.intersection_budget_exceeded and candidate.intersections > baseline.intersections:
        regressions.append("intersections_increased")
    incomplete = []
    if baseline.intersection_budget_exceeded:
        incomplete.append("baseline_topology_incomplete")
    if candidate.intersection_budget_exceeded:
        incomplete.append("candidate_topology_incomplete")
    if regressions:
        return "REJECT", tuple(regressions + incomplete)
    if incomplete:
        return "INCONCLUSIVE", tuple(incomplete)
    no_gain = []
    if candidate.supported_normals <= baseline.supported_normals:
        no_gain.append("supported_normals_not_increased")
    if candidate.fused_observations <= baseline.fused_observations:
        no_gain.append("retained_fusions_not_increased")
    if no_gain:
        return "INCONCLUSIVE", tuple(no_gain)
    return "PROMOTE", ("both_gains_with_complete_nonregressing_topology",)


def evaluate_profile(baseline: Metrics, candidate: Metrics) -> str:
    return _decision(baseline, candidate)[0]


def _verified_decisions(
    rows: list[Metrics], decisions: dict[str, str], baseline_name: str | None = None,
) -> tuple[Metrics, dict[str, tuple[str, tuple[str, ...]]]]:
    if not rows or len({row.profile for row in rows}) != len(rows):
        raise loss.MalformedDiagnostics("A receipt requires distinct measured profiles")
    baselines = [row for row in rows if decisions.get(row.profile) == "BASELINE"]
    if len(baselines) != 1:
        raise loss.MalformedDiagnostics("A receipt requires exactly one measured baseline")
    baseline = baselines[0]
    if baseline_name is not None and parse_profile(baseline_name).name != baseline.profile:
        raise loss.MalformedDiagnostics("Receipt baseline does not match the measured baseline")
    verified = {row.profile: _decision(baseline, row) for row in rows}
    if decisions != {name: decision[0] for name, decision in verified.items()}:
        raise loss.MalformedDiagnostics("Decisions do not match the measured promotion rule")
    return baseline, verified


def render_markdown(rows: list[Metrics], decisions: dict[str, str]) -> str:
    _, verified = _verified_decisions(rows, decisions)
    columns = (
        "profile",
        "evaluated",
        "rejected",
        "ambiguous",
        "boundaryEdge",
        "ncc",
        "normals",
        "fusions",
        "tris",
        "boundary",
        "intersections",
        "nonmanifold",
        "topologyComplete",
        "decision",
    )
    lines = ["## Depth profile sweep", ""]
    lines.append("| " + " | ".join(columns) + " |")
    lines.append("|" + "---|" * len(columns))
    for row in rows:
        lines.append(
            "| "
            + " | ".join(
                str(value)
                for value in (
                    row.profile,
                    row.evaluated,
                    row.rejected,
                    row.ambiguous_minimum,
                    row.boundary_edge,
                    row.ncc,
                    row.supported_normals,
                    row.fused_observations,
                    row.triangle_count,
                    row.boundary_edges,
                    row.intersections,
                    row.nonmanifold_edges,
                    not row.intersection_budget_exceeded,
                    decisions[row.profile],
                )
            )
            + " |"
        )
    lines += [
        "",
        "Rule: PROMOTE needs more supported normals and more fusions with no",
        "increase in boundary edges, intersections or nonmanifold edges, and complete",
        "intersection checks for both profiles. Known regressions reject even with",
        "incomplete checks; otherwise incomplete evidence is inconclusive. Synthetic fixture only:",
        "no profile is a production default, admission limit or quality result.",
    ]
    lines += ["", "Decision reasons:", ""]
    lines += [f"- {row.profile}: {', '.join(verified[row.profile][1])}." for row in rows]
    return "\n".join(lines)


def receipt(
    rows: list[Metrics], decisions: dict[str, str], baseline_name: str | None = None,
    *, provenance: Any | None = None,
) -> dict[str, Any]:
    from tools.qa.dense_receipt_provenance import NativeRunEvidence

    if provenance is not None and not isinstance(provenance, NativeRunEvidence):
        raise loss.MalformedDiagnostics("Native provenance must be captured execution evidence")
    baseline, verified = _verified_decisions(rows, decisions, baseline_name)
    return {
        "schema": 2,
        "tool": "dense_profile_sweep",
        "classification": "SYNTHETIC_PROFILE_SWEEP_NOT_QUALITY_NOT_DEFAULT",
        "fixture": "synthetic textured plane, 3 registered views, seeded 504",
        "provenance": provenance.document(rows, decisions) if provenance is not None else None,
        "rule": {
            "promote": "normals up AND fusions up AND boundaries not up AND intersections not up AND nonmanifold not up AND both topology checks complete",
            "reject": "known boundary, nonmanifold or intersection regression takes precedence over incomplete evidence",
            "inconclusive": "incomplete topology evidence or no gain on both improvement axes",
            "baseline": baseline.profile,
        },
        "profiles": [
            {**asdict(row), "decision": verified[row.profile][0],
             "decisionReasons": list(verified[row.profile][1])} for row in rows
        ],
        "limitations": [
            "Synthetic fixture; real licensed three-photo artifact NOT_RUN",
            "Host ctypes path, not Android/controller pixels",
            "No production default, limit or cache identity changed",
            "Library hashes identify tested bytes; helper-source identities do not establish library build provenance",
        ],
    }


def synthetic_fixture() -> tuple[Any, Any]:
    """Committed-style synthetic plane photographs used by the existing tests."""
    import numpy as np

    width, height = 104, 76
    texture = np.random.default_rng(504).integers(0, 256, (256, 256, 3))
    y, x = np.mgrid[:height, :width]
    images, cameras = [], []
    for camera in range(3):
        u = np.clip(((x - 51.5) / 116 * 5 + camera * .55) * 31 + 128, 0, 254.999)
        v = np.clip(((y - 37.5) / 116 * 5) * 31 + 128, 0, 254.999)
        ix, iy = u.astype(int), v.astype(int)
        a, b = (u - ix)[..., None], (v - iy)[..., None]
        rgb = np.rint(
            (1 - a) * (1 - b) * texture[iy, ix]
            + a * (1 - b) * texture[iy, ix + 1]
            + (1 - a) * b * texture[iy + 1, ix]
            + a * b * texture[iy + 1, ix + 1]
        ).astype(np.uint8)
        images.append(
            np.concatenate((rgb, np.full((height, width, 1), 255, np.uint8)), axis=2)
        )
        cameras.append([1, 0, 0, 0, 1, 0, 0, 0, 1, -camera * .55, 0, 0, 116, 116, 51.5, 37.5])
    return np.asarray(images), np.asarray(cameras, dtype=np.float64)


def sweep(profiles: Iterable[Profile], baseline_name: str = BASELINE_PROFILE) -> tuple[list[Metrics], dict[str, str]]:
    from tools.qa.dense_receipt_provenance import NativeRunEvidence, NativeRunInputs, ProvenanceChanged, SweepResult

    profiles = tuple(profiles)
    baseline_name = parse_profile(baseline_name).name
    if not profiles or len({profile.name for profile in profiles}) != len(profiles):
        raise InvalidProfile("Expected distinct profiles")
    if baseline_name not in {profile.name for profile in profiles}:
        raise InvalidProfile(f"Baseline profile {baseline_name} was not run")
    try:
        from tools.native_api import NativeEngine, library_path
        inputs = NativeRunInputs.capture(library_path())
        with inputs.isolated_library() as loaded_library:
            engine = NativeEngine(loaded_library.path)
            if Path(engine.library._name).resolve() != loaded_library.path:
                raise ProvenanceChanged("Loaded native library differs from the isolated copy")
            loaded_library.verify()
            inputs.verify()
            import numpy as np

            images, cameras = synthetic_fixture()
            # Match the binding's C-contiguous uint8 / native float64 conversion
            # before recording the exact buffers supplied to the native stage.
            images = np.ascontiguousarray(images, dtype=np.uint8)
            cameras = np.ascontiguousarray(cameras, dtype=np.float64)
            evidence = NativeRunEvidence.capture(inputs, images, cameras)
            rows = []
            for profile in profiles:
                loaded_library.verify()
                evidence.verify()
                rows.append(metrics_from_result(
                    profile, engine.surface(images, cameras, 3.1, 7.0, profile.planes, profile.stride),
                ))
                loaded_library.verify()
                evidence.verify()
            baseline = next((row for row in rows if row.profile == baseline_name), None)
            if baseline is None:
                raise loss.MalformedDiagnostics(f"Baseline profile {baseline_name} was not run")
            decisions = {row.profile: evaluate_profile(baseline, row) for row in rows}
            return SweepResult(rows, decisions, evidence.bind(rows, decisions))
    except (ImportError, FileNotFoundError, OSError) as failure:
        raise NativeUnavailable(str(failure)) from failure


def main(argv: list[str] | None = None) -> int:
    from tools.qa.dense_receipt_provenance import ProvenanceChanged, SweepResult
    from tools.qa.report_io import validate_receipt_path, write_json_receipt

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--profile",
        action="append",
        dest="profiles",
        help="PLANESxSTRIDE; repeatable. Defaults to the fixed bounded set.",
    )
    parser.add_argument("--baseline", default=BASELINE_PROFILE)
    parser.add_argument("--json-out", type=Path, help="New path for an optional machine-readable receipt")
    arguments = parser.parse_args(argv)
    try:
        baseline_name = parse_profile(arguments.baseline).name
        profiles = [parse_profile(name) for name in arguments.profiles or DEFAULT_PROFILES]
        if len({profile.name for profile in profiles}) != len(profiles):
            raise InvalidProfile("Duplicate profile")
        if baseline_name not in {profile.name for profile in profiles}:
            profiles.insert(0, parse_profile(baseline_name))
    except InvalidProfile as failure:
        print(f"INVALID: {failure}", file=sys.stderr)
        return 1
    try:
        if arguments.json_out:
            validate_receipt_path(arguments.json_out)
        result = sweep(profiles, baseline_name)
        rows, decisions = result
        provenance = result.provenance if isinstance(result, SweepResult) else None
        carried = receipt(rows, decisions, baseline_name, provenance=provenance)
        rendered = render_markdown(rows, decisions)
        if arguments.json_out:
            if provenance is not None:
                provenance.verify()
            write_json_receipt(arguments.json_out, carried)
    except NativeUnavailable as failure:
        print(f"NATIVE_UNAVAILABLE: {failure}", file=sys.stderr)
        return 2
    except loss.UnavailableDiagnostics as failure:
        print(f"UNAVAILABLE: {failure}", file=sys.stderr)
        return 2
    except (loss.MalformedDiagnostics, InvalidProfile, ProvenanceChanged,
            OSError, UnicodeError, ValueError) as failure:
        print(f"INVALID: {failure}", file=sys.stderr)
        return 1
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
