# Photometric depth loss and surface gate attribution

Status: `SYNTHETIC_MECHANISM_ONLY`. These records explain *why* sampled pixels
are rejected before depth consistency and *why* the fusion gate rejects
cross-camera candidates, using counters the production engine already emits plus
two additive subreason breakdowns. No threshold, gate, algorithm or cache
identity is changed, and nothing here is evidence of object coverage, accuracy or
watertightness.

## Why this exists

The three-photo HOST journey reports `31,374 sampled → 4,887 photometric →
2,510 reciprocal → 2,469 exported` dense representatives, and only
`10/2,510` supported normals. The downstream consequence is measured: all
`3,181` cross-camera candidate visits reject at `missingNormal`. Two upstream
questions were open:

1. *Why* 84% of sampled pixels never become photometric depth.
2. Whether the missing-normal gate fails on an unsupported **source** normal or
   an unsupported **accumulator** normal.

`reconstruction/src/dense.cpp` already enforced this per-view identity at
serialization time:

```
acceptedSamples + invalidReferencePatch + validLowVariance
+ invalidOrBoundaryBest + ncc + uniqueness + invalidInverseDepth == evaluatedSamples
```

This work adds `invalidOrBoundaryBestReasons` (dense, schema 2) and
`missingNormalReasons` (surface, schema 2), both validated against their parent
counter. `tools/qa/dense_loss_report.py` re-checks the identity and renders the
breakdown; it is read-only and never runs the engine.

## Measured result — photometric stage

Profile: synthetic plane photographs, `104 × 76`, three registered views, near
`3.1`, far `7.0`, `56` planes, pixel stride `3`, through `rf_surface_json` and
`tools/native_api.py`.

| view | evaluated | accepted | rejected | invalidReferencePatch | validLowVariance | invalidOrBoundaryBest | ncc | uniqueness | invalidInverseDepth |
|---|---|---|---|---|---|---|---|---|---|
| 0 | 759 | 663 | 96 | 0 | 0 | 79 | 17 | 0 | 0 |
| 1 | 759 | 759 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 2 | 759 | 667 | 92 | 0 | 0 | 79 | 13 | 0 | 0 |
| total | 2,277 | 2,089 | 188 | 0 | 0 | 158 | 30 | 0 | 0 |

Dominant reason: `invalidOrBoundaryBest`, **84.0%** of rejections.

### Boundary subreasons (schema 2)

| view | ambiguousMinimum | nearBoundaryBest | farBoundaryBest |
|---|---|---|---|
| 0 | 72 | 0 | 7 |
| 1 | 0 | 0 | 0 |
| 2 | 69 | 0 | 10 |
| total | **141** | 0 | 17 |

`ambiguousMinimum` is **89.2%** of the dominant class. The depth-interval edges
account for only 17 of 158, and the near edge for none. On this fixture the
losing samples do not sit outside the search interval; the aggregated cost has
no distinct minimum.

## Measured result — missing-normal gate

| view | sourceNormal | accumulatorNormal | bothNormals | missingNormal first-gate |
|---|---|---|---|---|
| 0 | 999 | 521 | 337 | 1,857 |
| 1 | 793 | 635 | 477 | 1,905 |
| 2 | 1,255 | 416 | 466 | 2,137 |
| total | **3,047** | 1,572 | 1,280 | 5,899 |

An unsupported **source** normal accounts for 51.7% of missing-normal
rejections; both sides unsupported 21.7%; accumulator-only 26.6%. The gate is
therefore mostly reporting that the incoming observation itself has no measured
normal — consistent with sparse depth maps, not with an accumulator-side defect.

Because every candidate records exactly one first-failure gate and the per-gate
counts sum to `fusionCandidates`, the full gate funnel is derivable by
subtraction: `passed(g) = fusionCandidates − Σ failures(g′ ≤ g)`. No additional
funnel instrumentation is required.

## Decision rule (pre-declared, unchanged by results)

| Share of rejections | Band | Meaning |
|---|---|---|
| ≥ 40% | `DECISIVE` | One mechanism explains most of the loss; investigate it first |
| 25–40% | `LEADING_BUT_MIXED` | Leading cause exists but a second mechanism is material |
| < 25% | `NO_SINGLE_CAUSE` | Loss is distributed; a single-gate change is unjustified |

Indicated investigation direction per dominant reason:

| Reason | Direction |
|---|---|
| `validLowVariance` | Capture/preprocessing contrast gap, not a matcher defect |
| `invalidOrBoundaryBest` | Refined by subreason: ambiguous minimum → spacing/patch/parallax; edge → search interval |
| `ncc` | Exposure, rectification or fronto-parallel warp |
| `uniqueness` | Repetitive texture or insufficient parallax |
| `invalidReferencePatch` | Mask/boundary validity bookkeeping |
| `invalidInverseDepth` | Local refinement guard |

A band never authorizes a threshold change. Any intervention must be re-measured
on the same fixture and must not regress intersections, nonmanifold edges or
boundary edges.

## Reproduce

```sh
cmake -S . -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/host --target realityforge --parallel 4
PYTHONPATH=. python -m tools.qa.dense_loss_report \
  --input DENSE_OR_SURFACE_RESULT.json \
  --label "fixture/settings label" \
  --json-out loss-receipt.json
```

Exit codes: `0` report produced, `1` malformed artifact or broken accounting,
`2` diagnostics unavailable (`available=false`, null counters). Schema-1
artifacts remain readable; the subreason split appears only for schema 2.

## Open evidence

- Real licensed three-photo artifact: **NOT_RUN** (inputs are not in this repository).
- Android/controller pixel path: **NOT_RUN** (host ctypes path only).
- Effect of any depth-range, spacing or patch-size change: **NOT_RUN**; no such
  change is made or recommended by this record.
- Physical object coverage, accuracy, watertightness: unchanged and unqualified.
