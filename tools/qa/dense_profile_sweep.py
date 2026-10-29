#!/usr/bin/env python3
"""Bounded depth-profile sweep against a labelled synthetic fixture.

Purpose: decide whether the measured ``invalidOrBoundaryBest`` loss is a
quantisation problem (fixed by more depth planes) or a visibility/parallax
problem (not fixed by settings at all).  Every profile re-runs the actual
bounded native surface stage; nothing is simulated.

Promotion rule (pre-declared, never adjusted after seeing results)::

    PROMOTE  supported normals increase AND fusions increase
             AND intersections do not increase AND nonmanifold edges do not increase
    REJECT   any of those four conditions is violated while another improves
    INCONCLUSIVE  no measured improvement

A promoted profile is still synthetic-only evidence.  This tool never edits a
production default, admission limit or cache identity.

Usage::

    python -m tools.qa.dense_profile_sweep --json-out sweep.json

Exit codes: 0 sweep completed, 1 malformed input, 2 native library unavailable.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, asdict
import json
from pathlib import Path
import sys
from typing import Any, Iterable

from tools.qa import dense_loss_report as loss

MIN_PLANES, MAX_PLANES = 12, 128
MIN_STRIDE, MAX_STRIDE = 2, 8
DEFAULT_PROFILES = ("40x3", "56x3", "72x3", "96x3", "56x2", "72x2")
BASELINE_PROFILE = "56x3"


class InvalidProfile(ValueError):
    """A profile string is not a bounded planes x stride pair."""


@dataclass(frozen=True)
class Profile:
    planes: int
    stride: int

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


def parse_profile(text: str) -> Profile:
    parts = text.lower().replace(" ", "").split("x")
    if len(parts) != 2 or not all(part.isdigit() for part in parts):
        raise InvalidProfile(f"Expected PLANESxSTRIDE, got {text!r}")
    planes, stride = (int(part) for part in parts)
    if not MIN_PLANES <= planes <= MAX_PLANES:
        raise InvalidProfile(f"planes must be {MIN_PLANES}..{MAX_PLANES}")
    if not MIN_STRIDE <= stride <= MAX_STRIDE:
        raise InvalidProfile(f"stride must be {MIN_STRIDE}..{MAX_STRIDE}")
    return Profile(planes, stride)


def metrics_from_result(profile: Profile, result: Any) -> Metrics:
    built = loss.build_report(result)
    mesh = result.get("mesh")
    if not isinstance(mesh, dict):
        raise loss.MalformedDiagnostics(
            f"Profile {profile.name} produced no mesh section to measure"
        )
    views = mesh["lossDiagnostics"]["views"]
    supported = sum(view["normalValidity"]["supported"] for view in views)
    boundary = sum(
        view["photometric"]["rejectionCounts"]["invalidOrBoundaryBestReasons"][name]
        for view in result["dense"]["lossDiagnostics"]["views"]
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
        fusion_candidates=sum(view["fusionCandidates"] for view in views),
        fused_observations=mesh["fusedObservations"],
        vertex_count=mesh["vertexCount"],
        triangle_count=mesh["triangleCount"],
        boundary_edges=mesh["boundaryEdges"],
        intersections=mesh["intersections"],
        nonmanifold_edges=mesh["nonmanifoldEdges"],
        intersection_budget_exceeded=mesh["topologyIncomplete"],
    )


def evaluate_profile(baseline: Metrics, candidate: Metrics) -> str:
    """Apply the pre-declared promotion rule; no post-hoc tolerance."""
    if candidate.profile == baseline.profile:
        return "BASELINE"
    normals_gain = candidate.supported_normals > baseline.supported_normals
    fusion_gain = candidate.fused_observations > baseline.fused_observations
    topology_safe = (
        candidate.intersections <= baseline.intersections
        and candidate.nonmanifold_edges <= baseline.nonmanifold_edges
    )
    if normals_gain and fusion_gain and topology_safe:
        return "PROMOTE"
    if not topology_safe:
        return "REJECT"
    # A gain on only one axis is not enough evidence to prefer the profile.
    return "INCONCLUSIVE"


def render_markdown(rows: list[Metrics], decisions: dict[str, str]) -> str:
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
                    decisions[row.profile],
                )
            )
            + " |"
        )
    lines += [
        "",
        "Rule: PROMOTE needs more supported normals and more fusions with no",
        "increase in intersections or nonmanifold edges. Synthetic fixture only:",
        "no profile is a production default, admission limit or quality result.",
    ]
    return "\n".join(lines)


def receipt(rows: list[Metrics], decisions: dict[str, str]) -> dict[str, Any]:
    return {
        "schema": 1,
        "tool": "dense_profile_sweep",
        "classification": "SYNTHETIC_PROFILE_SWEEP_NOT_QUALITY_NOT_DEFAULT",
        "fixture": "synthetic textured plane, 3 registered views, seeded 504",
        "rule": {
            "promote": "normals up AND fusions up AND intersections not up AND nonmanifold not up",
            "baseline": BASELINE_PROFILE,
        },
        "profiles": [
            {**asdict(row), "decision": decisions[row.profile]} for row in rows
        ],
        "limitations": [
            "Synthetic fixture; real licensed three-photo artifact NOT_RUN",
            "Host ctypes path, not Android/controller pixels",
            "No production default, limit or cache identity changed",
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
    try:
        from tools.native_api import NativeEngine
        engine = NativeEngine()
    except (FileNotFoundError, OSError) as failure:
        raise SystemExit(f"NATIVE_UNAVAILABLE: {failure}") from failure
    images, cameras = synthetic_fixture()
    rows = [
        metrics_from_result(profile, engine.surface(images, cameras, 3.1, 7.0, profile.planes, profile.stride))
        for profile in profiles
    ]
    baseline = next((row for row in rows if row.profile == baseline_name), None)
    if baseline is None:
        raise loss.MalformedDiagnostics(f"Baseline profile {baseline_name} was not run")
    decisions = {row.profile: evaluate_profile(baseline, row) for row in rows}
    return rows, decisions


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--profile",
        action="append",
        dest="profiles",
        help="PLANESxSTRIDE; repeatable. Defaults to the fixed bounded set.",
    )
    parser.add_argument("--baseline", default=BASELINE_PROFILE)
    parser.add_argument("--json-out", type=Path)
    arguments = parser.parse_args(argv)
    try:
        names = tuple(arguments.profiles or DEFAULT_PROFILES)
        if arguments.baseline not in names:
            names = (arguments.baseline,) + names
        profiles = [parse_profile(name) for name in names]
        if len({profile.name for profile in profiles}) != len(profiles):
            raise InvalidProfile("Duplicate profile")
    except InvalidProfile as failure:
        print(f"INVALID: {failure}", file=sys.stderr)
        return 1
    try:
        rows, decisions = sweep(profiles, arguments.baseline)
    except loss.MalformedDiagnostics as failure:
        print(f"INVALID: {failure}", file=sys.stderr)
        return 1
    print(render_markdown(rows, decisions))
    if arguments.json_out:
        arguments.json_out.write_text(
            json.dumps(receipt(rows, decisions), indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
