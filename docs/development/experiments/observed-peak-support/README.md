# Observed peak support: correctness correction, mixed quality metrics

The dense solver used cost `2` (missing evidence) as a measured neighbor in a
three-point depth parabola. Unlike generic ZNCC, which can validly return cost2
for anticorrelation, this caller accepts only `corr > -.999`, so its cost2 is
unambiguously missing support. The correction keeps the observed center when
either immediate neighbor is missing and rejects a missing center at the helper
boundary. Existing input checks and valid-neighbor arithmetic are preserved.

This is not a claim that fitted depth is always better than observed center, nor
that this small correction improves full-scene quality. With all three costs
observed, the existing default fit still lacks a final photometric re-evaluation.
That broader algorithm change is intentionally out of scope.

## Reachable witness

The native test supplies16 inverse-depth-uniform planes, an accepted best3 with
NCC.9 and uniqueness margin.6, and a missing left neighbor. Legacy fitting shifts
center depth1.1764705882 to1.2084592145 (+2.719%). The correction retains the center.
Tests cover missing center/left/right/both, malformed neighbors despite missing
support on the other side, and100,000 bitwise comparisons for valid stencils.

A two-camera synthetic planar fixture with aligned alpha-valid strips also reaches
the issue in the real dense call. For strip width7, reference1 pixel(35,3), best43,
neighbor costs are `[.0431422600,.00959739558,2]`, uniqueness margin.5915175724.
The point survives reciprocity and export: camera-z4.967924118 before versus
5.0021953583 after, confidence1 and two supporting views in both. Evaluation-only
plane truth is5; no truth enters matching. This is not only a helper-level witness.

## Measurements and limitations

See `measurement.json` for fixed-input hashes, counts, errors, and integrated
library verification. Standalone instrumentation only logs accepted peaks, with
identical instrumentation in both experimental arms. It finds208 affected peaks
on Middlebury,25 on the frozen cuboid, and35 per aligned-strip case.

- Width7 retains59 points; median relative depth error .00602007 -> .000439072.
  Widths9/13 retain118/260 points and unchanged medians.
- Middlebury output4249 ->4240, GT-matched3875 ->3867. Disparity median
  .469924 ->.471990 pixels worsens slightly; p90 3.003377 ->2.878742 improves.
  Populations change, so this is not paired-error improvement evidence.
- Cuboid visible-areaCDF at1%diagonal stays33.8779%. Projected coverage
 75.0822% ->75.0515%; nonmanifold edges3432 ->3435 and bounded intersections
 15401 ->15439 worsen slightly. Mesh4549v/13212f ->4537v/13194f.

These are mixed coverage/topology tradeoffs, not a quality pass. The cuboid uses
synthetic images and known cameras; Middlebury uses real rectified photos with
published calibration. Neither qualifies recovered-camera scanning, Android
controller behavior, mobile capture, or a shippable APK.

## Reproduction and cache boundary

Replay exact frozen cuboid inputs using the established
`tools/qa/visible_surface_baseline/run.py` and its pinned official Blender fixture
provenance; do not rerender with an unverified system Blender. Invoke
`python tools/benchmark_dense_middlebury.py` with `RF_NATIVE_LIBRARY` set to the
arm library. Aligned-strip fixtures derive from `benchmark_multiview_coverage.fixture(2)`:
zero alpha, then retain each view's full-height strip centered at
`round(51-i*.65*camera_fx/5)` with widths7/9/13; dense arguments3.1,7,64,2.
The fixed synthetic geometry defines inputs only; truth is evaluation-only.

Depth tile refinement version3, full-reference format3, Kotlin domainv3, and dense
stage descriptorv4 prevent old cached results bypassing this correction. Native
map/surface tests reject versions1 and2; features/matching/sparse identities remain
unchanged. Full46CTest passed against integrated Release library. Android/Kotlin
integration execution is reported separately; no hosted/device success is implied.
